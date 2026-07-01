/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.interest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.blitzy.carddemo.interest.io.AccountRepository;
import com.blitzy.carddemo.interest.io.CardXrefRepository;
import com.blitzy.carddemo.interest.io.DisclosureGroupRepository;
import com.blitzy.carddemo.interest.io.FixedWidthCodec;
import com.blitzy.carddemo.interest.io.TransactionWriter;
import com.blitzy.carddemo.interest.model.Account;
import com.blitzy.carddemo.interest.model.TransactionCategoryBalance;
import com.blitzy.carddemo.interest.support.Db2TimestampSupplier;
import com.blitzy.carddemo.interest.support.ZonedDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-rule unit tests for {@link InterestCalculationService} &mdash; a faithful port of the
 * {@code CBACT04C} {@code PROCEDURE DIVISION} loop ({@code app/cbl/CBACT04C.cbl:L188-222}). Covers
 * business rules <strong>BR-01..BR-05, BR-07, BR-09 (truncation), BR-10..BR-17</strong> as catalogued
 * in AAP &sect;0.6.3.
 *
 * <p>The suite is <strong>fully self-contained</strong>: every input is synthesized in-memory and
 * written to a per-test {@link TempDir @TempDir}. It reads nothing from {@code src/test/resources/**},
 * touches no network, and depends on no external resource, so {@code mvn test} proves functional
 * equivalence with zero live infrastructure (AAP &sect;0.7 &mdash; tests are the primary correctness
 * mechanism; there is no live mainframe).</p>
 *
 * <h2>Fidelity rules honored by this suite (AAP &sect;0.7)</h2>
 * <ul>
 *   <li><strong>Behavior over structure / source-over-narrative:</strong> the suite asserts ONLY what
 *       {@code CBACT04C} actually does. {@code TCATBAL} is read-only ({@code OPEN INPUT}); the ONLY
 *       mutated file is {@code ACCTFILE}. No "updated TCATBAL" output is asserted (the &sect;2.1.8
 *       narrative is resolved in favor of the source).</li>
 *   <li><strong>Decimal fidelity:</strong> monthly interest <em>truncates</em>
 *       ({@code RoundingMode.DOWN}, scale 2), never rounds half-up. BR-09 is the single most important
 *       numeric assertion in this file (1.04, never 1.05).</li>
 *   <li><strong>Determinism by injection:</strong> a FIXED {@link Db2TimestampSupplier} lambda
 *       ({@code () -> FIXED_TS}) is injected so timestamp assertions are stable.</li>
 *   <li><strong>Real collaborators only:</strong> repositories are built via their {@code static
 *       load(Path)} factories from synthesized fixed-width fixtures; the writer is a real
 *       {@link TransactionWriter}. No mocking framework is used (none is on the classpath).</li>
 * </ul>
 *
 * <p>Every migrated-rule assertion carries a comment citing the originating {@code CBACT04C}
 * paragraph/line (mandatory traceability, AAP &sect;0.7).</p>
 *
 * <p><strong>Source lineage (READ-ONLY REFERENCE; additive migration, never modified):</strong>
 * behavior reverse-engineered from {@code app/cbl/CBACT04C.cbl}; record widths/offsets from
 * {@code app/cpy/CVTRA01Y.cpy} (driver 50), {@code CVTRA02Y.cpy} (disc 50), {@code CVACT03Y.cpy}
 * (xref fixture 36), {@code CVACT01Y.cpy} (account 300), {@code CVTRA05Y.cpy} (txn output 350);
 * {@code PARM_DATE} from {@code app/jcl/INTCALC.jcl:L22}.</p>
 */
@DisplayName("InterestCalculationService — per-rule port of CBACT04C PROCEDURE DIVISION L188-222")
public class InterestCalculationServiceTest {

    // =====================================================================
    // Phase B — shared constants
    // =====================================================================

    /** INTCALC {@code PARM-DATE} ({@code app/jcl/INTCALC.jcl:L22}); 10 chars, prefixes every TRAN-ID. */
    private static final String PARM_DATE = "2022071800";

    /**
     * The injected DB2-format timestamp ({@code YYYY-MM-DD-HH.MM.SS.NNNNNN}); EXACTLY 26 chars
     * (10 + 1 + 8 + 1 + 6). Mirrors {@code DB2-FORMAT-TS} produced by {@code Z-GET-DB2-FORMAT-TIMESTAMP}
     * ({@code app/cbl/CBACT04C.cbl:L613-626}); injected so origTs/procTs assertions are deterministic.
     */
    private static final String FIXED_TS = "2022-07-18-00.00.00.000000";

    /** US-ASCII charset (every in-repo fixed-width record is US-ASCII: 1 byte == 1 char). */
    private static final java.nio.charset.Charset US_ASCII = StandardCharsets.US_ASCII;

    /** Account id A ({@code ACCT-ID 9(11)}), zero-padded to the 11-char field width. */
    private static final String ACCT_A = "00000000001";
    /** Account id B ({@code ACCT-ID 9(11)}), zero-padded to the 11-char field width. */
    private static final String ACCT_B = "00000000002";

    /** Card number A ({@code XREF-CARD-NUM X(16)}), exact 16-char width. */
    private static final String CARD_A = "1111111111111111";
    /** Card number B ({@code XREF-CARD-NUM X(16)}), exact 16-char width. */
    private static final String CARD_B = "2222222222222222";

    /** Customer id A ({@code XREF-CUST-ID 9(09)}), exact 9-char width. */
    private static final String CUST_A = "000000001";
    /** Customer id B ({@code XREF-CUST-ID 9(09)}), exact 9-char width. */
    private static final String CUST_B = "000000002";

    /** Standard non-zero-rate disclosure group ({@code ACCT-GROUP-ID X(10)}); already 10 chars. */
    private static final String GROUP_STD = "A000000000";
    /** Zero-APR group (7 chars); builders pad it to {@code "ZEROAPR   "} consistently on both sides. */
    private static final String GROUP_ZERO = "ZEROAPR";
    /** A group with no matching disc row and no DEFAULT row — drives the fatal fallback (BR-17c). */
    private static final String GROUP_MISSING = "NOSUCHGRP";

    /** {@code TRANCAT-TYPE-CD X(02)} used by driver records and matching disc rows. */
    private static final String TYPE_CD = "01";
    /** {@code TRANCAT-CD 9(04)} used by driver records and matching disc rows. */
    private static final String CAT_CD = "0001";

    // =====================================================================
    // JUnit 5 per-test temp directory + a monotonic counter for unique fixture file names.
    // Non-static @TempDir => a fresh directory per test method (full isolation between tests).
    // =====================================================================

    /** Fresh temp directory injected per test method; all synthesized fixtures live here. */
    @TempDir
    Path tempDir;

    /** Monotonic counter so repeated {@link #execute} calls within one test use distinct file names. */
    private int seq;

    // =====================================================================
    // Phase C.1 — fixed-width fixture line builders
    //
    // Every field is framed with the SAME production codecs the readers use
    // (FixedWidthCodec.alpha for X(n) / unsigned 9(n); ZonedDecimal.encode for
    // signed S9(n)V99), so a build -> load -> service -> read round-trip is
    // byte-consistent by construction. Widths/offsets verified vs the copybooks.
    // =====================================================================

    /**
     * Builds a 300-byte {@code ACCOUNT-RECORD} line ({@code app/cpy/CVACT01Y.cpy}). Only the three
     * fields CBACT04C {@code 1050-UPDATE-ACCOUNT} (L350-356) mutates are parameterised
     * ({@code currBal}, {@code cycCredit}, {@code cycDebit}, plus {@code groupId} which drives the
     * DISCGRP key); the remaining fields carry stable filler values.
     *
     * <p>Width check: 11+1+12+12+12+10+10+10+12+12+10+10+178 = 300.</p>
     */
    private static String acctLine(String acctId, BigDecimal currBal, BigDecimal cycCredit,
                                   BigDecimal cycDebit, String groupId) {
        String rec =
                FixedWidthCodec.alpha(acctId, 11)                      // [0,11)   ACCT-ID 9(11)
              + FixedWidthCodec.alpha("Y", 1)                          // [11,12)  ACCT-ACTIVE-STATUS X(01)
              + ZonedDecimal.encode(currBal, 12, 2)                    // [12,24)  ACCT-CURR-BAL S9(10)V99 (MUTATED)
              + ZonedDecimal.encode(new BigDecimal("1000.00"), 12, 2)  // [24,36)  ACCT-CREDIT-LIMIT
              + ZonedDecimal.encode(new BigDecimal("1000.00"), 12, 2)  // [36,48)  ACCT-CASH-CREDIT-LIMIT
              + FixedWidthCodec.alpha("2020-01-01", 10)                // [48,58)  ACCT-OPEN-DATE
              + FixedWidthCodec.alpha("2025-01-01", 10)                // [58,68)  ACCT-EXPIRAION-DATE (source spelling)
              + FixedWidthCodec.alpha("2025-01-01", 10)                // [68,78)  ACCT-REISSUE-DATE
              + ZonedDecimal.encode(cycCredit, 12, 2)                  // [78,90)  ACCT-CURR-CYC-CREDIT (MUTATED->0)
              + ZonedDecimal.encode(cycDebit, 12, 2)                   // [90,102) ACCT-CURR-CYC-DEBIT (MUTATED->0)
              + FixedWidthCodec.alpha("A000000000", 10)                // [102,112) ACCT-ADDR-ZIP
              + FixedWidthCodec.alpha(groupId, 10)                     // [112,122) ACCT-GROUP-ID (drives DISCGRP key)
              + " ".repeat(178);                                       // [122,300) FILLER X(178)
        // Framing guard: a mis-sized account line would mis-align every following field on read.
        assertEquals(300, rec.length(), "account fixture line must be exactly 300 bytes");
        return rec;
    }

    /**
     * Builds a 36-byte {@code CARD-XREF-RECORD} fixture line ({@code app/cpy/CVACT03Y.cpy}; the ASCII
     * fixture stores the 36 significant bytes, omitting the copybook's trailing {@code FILLER X(14)}).
     * Callers pass already-correct-width digit strings so {@code alpha} returns them unchanged.
     *
     * <p>Layout: {@code [0,16) XREF-CARD-NUM X(16) | [16,25) XREF-CUST-ID 9(09) | [25,36) XREF-ACCT-ID 9(11)}.</p>
     */
    private static String xrefLine(String cardNum, String custId, String acctId) {
        String rec = FixedWidthCodec.alpha(cardNum, 16)
                   + FixedWidthCodec.alpha(custId, 9)
                   + FixedWidthCodec.alpha(acctId, 11);
        assertEquals(36, rec.length(), "xref fixture line must be exactly 36 bytes");
        return rec;
    }

    /**
     * Builds a 50-byte {@code DIS-GROUP-RECORD} line ({@code app/cpy/CVTRA02Y.cpy}). Use the SAME
     * logical {@code groupId}/{@code typeCd}/{@code catCd} strings here and on the matching account +
     * driver so the service's {@code lookup(account.getGroupId(), type, cat)} hits this row; both
     * sides pad through the identical codec, so keys match byte-for-byte.
     *
     * <p>Layout: {@code [0,10) DIS-ACCT-GROUP-ID X(10) | [10,12) DIS-TRAN-TYPE-CD X(02) |
     * [12,16) DIS-TRAN-CAT-CD 9(04) | [16,22) DIS-INT-RATE S9(04)V99 | [22,50) FILLER X(28)}.</p>
     */
    private static String discLine(String groupId, String typeCd, String catCd, BigDecimal rate) {
        String rec = FixedWidthCodec.alpha(groupId, 10)
                   + FixedWidthCodec.alpha(typeCd, 2)
                   + FixedWidthCodec.alpha(catCd, 4)
                   + ZonedDecimal.encode(rate, 6, 2)
                   + " ".repeat(28);
        assertEquals(50, rec.length(), "disclosure-group fixture line must be exactly 50 bytes");
        return rec;
    }

    // =====================================================================
    // Phase C.2 — file writer + driver builder
    // =====================================================================

    /**
     * Writes the fixture {@code lines} to {@code p} joined by a single LF (deterministic; no platform
     * separators, no trailing newline). An empty list yields an empty file, which loads to an empty
     * repository (used by the BR-17 abend tests). CREATE/TRUNCATE by default.
     */
    private static void writeLines(Path p, List<String> lines) throws IOException {
        Files.writeString(p, String.join("\n", lines), US_ASCII);
    }

    /**
     * Convenience factory for a driver element ({@code TRAN-CAT-BAL-RECORD}, {@code CVTRA01Y}): the
     * three key fields plus the {@code TRAN-CAT-BAL} the interest is computed on (parsed at scale 2
     * via {@link BigDecimal#BigDecimal(String)}, e.g. {@code "100.00"}).
     */
    private static TransactionCategoryBalance tcb(String acctId, String typeCd, String catCd, String balance) {
        return new TransactionCategoryBalance(acctId, typeCd, catCd, new BigDecimal(balance));
    }

    // =====================================================================
    // Phase C.3 — execution harness
    // =====================================================================

    /**
     * Holds the outputs of one service run: the live {@link AccountRepository} (whose in-memory
     * {@link Account} instances reflect the service's mutations) and the list of written 350-byte
     * transaction lines (LF-separated, in emit order).
     */
    private record Result(AccountRepository accounts, List<String> txns) {
    }

    /** Distinct suffix per {@link #execute} call so repeated runs in one test use unique file names. */
    private String uniq() {
        return Integer.toString(seq++);
    }

    /**
     * Synthesizes the three input fixtures under {@link #tempDir}, loads the real repositories, runs
     * {@link InterestCalculationService#process} with an injected FIXED timestamp and {@code PARM_DATE},
     * then returns the live account repository plus the written transaction lines.
     *
     * <p>The {@link TransactionWriter} is created in a try-with-resources so {@code close()} (flush)
     * runs BEFORE the output file is read back &mdash; otherwise the last record may not be flushed.
     * For abend tests, invoke {@code execute(...)} inside {@code assertThrows(...)}: the exception
     * propagates out of the try-with-resources (the writer still closes).</p>
     */
    private Result execute(List<TransactionCategoryBalance> driver,
                           List<String> acctLines,
                           List<String> xrefLines,
                           List<String> discLines) throws Exception {
        Path acctF = tempDir.resolve("acct-" + uniq() + ".txt");
        Path xrefF = tempDir.resolve("xref-" + uniq() + ".txt");
        Path discF = tempDir.resolve("disc-" + uniq() + ".txt");
        Path outF = tempDir.resolve("txn-" + uniq() + ".txt");

        writeLines(acctF, acctLines);
        writeLines(xrefF, xrefLines);
        writeLines(discF, discLines);

        AccountRepository accounts = AccountRepository.load(acctF);
        CardXrefRepository xrefs = CardXrefRepository.load(xrefF);
        DisclosureGroupRepository rates = DisclosureGroupRepository.load(discF);

        // Injected FIXED timestamp — the single non-deterministic seam (CBACT04C L614) pinned for
        // stable origTs/procTs assertions (BR-15).
        Db2TimestampSupplier ts = () -> FIXED_TS;

        try (TransactionWriter writer = new TransactionWriter(outF)) {
            new InterestCalculationService()
                    .process(driver, accounts, xrefs, rates, writer, ts, PARM_DATE);
        }

        List<String> txns = Files.readAllLines(outF, US_ASCII); // each line = 350 chars, LF-separated
        return new Result(accounts, txns);
    }

    // =====================================================================
    // Phase C.4 — output-transaction field parsers (CVTRA05Y 350-byte layout)
    //
    // field map (0-based; US-ASCII 1 byte == 1 char):
    //   tranId[0,16) typeCd[16,18) catCd[18,22) source[22,32) desc[32,132)
    //   amount[132,143) merchantId[143,152) merchantName[152,202) merchantCity[202,252)
    //   merchantZip[252,262) cardNum[262,278) origTs[278,304) procTs[304,330) FILLER[330,350)
    // =====================================================================

    /** Slices a fixed-width field {@code [off, off+width)} from a de-newlined 350-byte output line. */
    private static String fld(String line, int off, int width) {
        return FixedWidthCodec.slice(line, off, width);
    }

    /** Decodes {@code TRAN-AMT S9(09)V99} at {@code [132,143)} to a scale-2 {@link BigDecimal}. */
    private static BigDecimal amt(String line) {
        return ZonedDecimal.decode(FixedWidthCodec.slice(line, 132, 11), 2);
    }

    /** Asserts an output line is exactly 350 chars before slicing (guards fixed-width framing regressions). */
    private static void assertFramed(String line) {
        assertEquals(350, line.length(), "each TRAN-RECORD output line must be exactly 350 bytes");
    }


    // =====================================================================
    // Phase D — per-rule tests (each cites its CBACT04C paragraph/line)
    // =====================================================================

    @Test
    @DisplayName("fixture constants are well-formed (FIXED_TS 26 chars, PARM_DATE 10 chars)")
    void constantsAreWellFormed() {
        // Guards the DB2 timestamp width (YYYY-MM-DD-HH.MM.SS.NNNNNN = 10+1+8+1+6) and PARM-DATE width
        // used to build TRAN-ORIG-TS/TRAN-PROC-TS (X(26)) and TRAN-ID (PARM-DATE + 6-digit suffix).
        assertAll(
                () -> assertEquals(26, FIXED_TS.length(), "FIXED_TS must be 26 chars"),
                () -> assertEquals(10, PARM_DATE.length(), "PARM_DATE must be 10 chars"),
                () -> assertEquals(16, CARD_A.length(), "CARD_A must be 16 chars"),
                () -> assertEquals(11, ACCT_A.length(), "ACCT_A must be 11 chars"));
    }

    @Test
    @DisplayName("BR-01: TCATBAL driven sequentially; every rate!=0 record yields a txn, in order")
    void br01_sequentialDriveInOrder() throws Exception {
        // Three records for the SAME account, each rate!=0 -> three txns emitted in driver order.
        List<TransactionCategoryBalance> driver = List.of(
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"),
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"),
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"));
        Result r = execute(driver,
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        // BR-01 PROCEDURE main loop + 1000-TCATBALF-GET-NEXT CBACT04C.cbl L188-193, L326
        assertEquals(3, r.txns().size(), "one txn per rate!=0 driver record, front-to-back");
        r.txns().forEach(InterestCalculationServiceTest::assertFramed);
        assertEquals(PARM_DATE + "000001", fld(r.txns().get(0), 0, 16));
        assertEquals(PARM_DATE + "000002", fld(r.txns().get(1), 0, 16));
        assertEquals(PARM_DATE + "000003", fld(r.txns().get(2), 0, 16));
    }

    @Test
    @DisplayName("BR-02: account break when TRANCAT-ACCT-ID changes; each account gets its own interest")
    void br02_accountBreak() throws Exception {
        List<TransactionCategoryBalance> driver = List.of(
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"),
                tcb(ACCT_B, TYPE_CD, CAT_CD, "100.00"));
        Result r = execute(driver,
                List.of(
                        acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                                new BigDecimal("0.00"), GROUP_STD),
                        acctLine(ACCT_B, new BigDecimal("100.00"), new BigDecimal("0.00"),
                                new BigDecimal("0.00"), GROUP_STD)),
                List.of(
                        xrefLine(CARD_A, CUST_A, ACCT_A),
                        xrefLine(CARD_B, CUST_B, ACCT_B)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        // BR-02 account break when TRANCAT-ACCT-ID != WS-LAST-ACCT-NUM CBACT04C.cbl L194, L201
        assertEquals(new BigDecimal("101.04"), r.accounts().read(ACCT_A).getCurrBal(),
                "account A posts its own 1.04 interest");
        assertEquals(new BigDecimal("101.04"), r.accounts().read(ACCT_B).getCurrBal(),
                "account B posts its own 1.04 interest (break switched accounts)");
    }

    @Test
    @DisplayName("BR-03(a): first-time guard — single account updated exactly once")
    void br03a_firstTimeGuardSingleAccount() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        // BR-03 WS-FIRST-TIME guard CBACT04C.cbl L195-199
        assertEquals(new BigDecimal("101.04"), r.accounts().read(ACCT_A).getCurrBal(),
                "currBal == initial 100.00 + its single 1.04 interest (no extra/prior update)");
        assertEquals(1, r.txns().size(), "exactly one interest transaction for the single record");
    }

    @Test
    @DisplayName("BR-03(b)/BR-12: empty driver — firstTime guard prevents any update, no exception")
    void br03b_emptyDriverNoUpdate() throws Exception {
        Result r = execute(
                List.of(),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("5.00"),
                        new BigDecimal("7.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        // BR-03/BR-12 empty driver: firstTime guard prevents update CBACT04C.cbl L195-199, L219-220
        assertTrue(r.txns().isEmpty(), "no driver records -> no interest transactions");
        assertEquals(new BigDecimal("100.00"), r.accounts().read(ACCT_A).getCurrBal(),
                "currBal unchanged — the post-loop final update is guarded by firstTime");
    }

    @Test
    @DisplayName("BR-04: interest accumulator reset to 0 on each new account")
    void br04_accumulatorResetPerAccount() throws Exception {
        List<TransactionCategoryBalance> driver = List.of(
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"),
                tcb(ACCT_B, TYPE_CD, CAT_CD, "200.00"));
        Result r = execute(driver,
                List.of(
                        acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                                new BigDecimal("0.00"), GROUP_STD),
                        acctLine(ACCT_B, new BigDecimal("100.00"), new BigDecimal("0.00"),
                                new BigDecimal("0.00"), GROUP_STD)),
                List.of(
                        xrefLine(CARD_A, CUST_A, ACCT_A),
                        xrefLine(CARD_B, CUST_B, ACCT_B)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        // BR-04 MOVE 0 TO WS-TOTAL-INT on new account CBACT04C.cbl L200
        // A: 100.00*12.55/1200 = 1.0458 -> 1.04 ; B: 200.00*12.55/1200 = 2.0916 -> 2.09.
        // If the accumulator were NOT reset, B would show 100.00 + (1.04 + 2.09) = 103.13.
        assertEquals(new BigDecimal("101.04"), r.accounts().read(ACCT_A).getCurrBal(),
                "A delta is exactly 1.04");
        assertEquals(new BigDecimal("102.09"), r.accounts().read(ACCT_B).getCurrBal(),
                "B delta is exactly 2.09 (accumulator was reset on the break to B)");
    }

    @Test
    @DisplayName("BR-05: per-account Account read + Xref read by acct-id alt key; card flows to txn")
    void br05_accountAndXrefReadByAcctId() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        assertEquals(1, r.txns().size());
        String line = r.txns().get(0);
        assertFramed(line);
        // BR-05 1100-GET-ACCT-DATA + 1110-GET-XREF-DATA (alt key) CBACT04C.cbl L202-205, L373, L394-395
        assertEquals(FixedWidthCodec.alpha(CARD_A, 16), fld(line, 262, 16),
                "XREF-CARD-NUM (read by account id) flows into TRAN-CARD-NUM");
        // Account read corroborated by the currBal mutation and the description containing the acctId.
        assertEquals(new BigDecimal("101.04"), r.accounts().read(ACCT_A).getCurrBal());
        assertEquals(FixedWidthCodec.alpha("Int. for a/c " + ACCT_A, 100), fld(line, 32, 100),
                "TRAN-DESC embeds the account id read from ACCTFILE");
    }


    @Test
    @DisplayName("BR-07(a): zero rate -> NO txn written; account still updated (cycles zeroed) at EOF")
    void br07a_zeroRateWritesNoTransaction() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "500.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("500.00"), new BigDecimal("5.00"),
                        new BigDecimal("7.00"), GROUP_ZERO)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_ZERO, TYPE_CD, CAT_CD, new BigDecimal("0.00"))));

        // BR-07 IF DIS-INT-RATE NOT = 0 -> skip when rate==0 CBACT04C.cbl L214
        assertTrue(r.txns().isEmpty(), "rate 0 -> the write is skipped entirely");
        Account a = r.accounts().read(ACCT_A);
        // The account update is driven by the break/EOF, NOT by writing a txn: cycle fields are
        // zeroed and currBal is unchanged because the accrual is 0.00.
        assertEquals(new BigDecimal("500.00"), a.getCurrBal(), "currBal unchanged (accrual 0.00)");
        assertEquals(new BigDecimal("0.00"), a.getCurrCycCredit(), "cycle credit still zeroed at EOF");
        assertEquals(new BigDecimal("0.00"), a.getCurrCycDebit(), "cycle debit still zeroed at EOF");
    }

    @Test
    @DisplayName("BR-07(b): zero balance + non-zero rate -> ONE 0.00 txn (guard is on RATE, not balance)")
    void br07b_zeroBalanceNonZeroRateWritesZeroTxn() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "0.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("15.00"))));

        // BR-07 guard on rate not balance: zero balance + nonzero rate still writes 0.00 CBACT04C.cbl L214
        assertEquals(1, r.txns().size(), "non-zero rate -> exactly one transaction even at balance 0");
        assertFramed(r.txns().get(0));
        assertEquals(new BigDecimal("0.00"), amt(r.txns().get(0)),
                "the emitted transaction amount is 0.00 (0.00 * 15.00 / 1200)");
    }

    @Test
    @DisplayName("BR-09: interest truncates to scale 2 (DOWN) — 1.04, never rounds to 1.05")
    void br09_interestTruncationBoundary() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        assertEquals(1, r.txns().size());
        assertFramed(r.txns().get(0));
        // BR-09 1300-COMPUTE-INTEREST (TRAN-CAT-BAL*DIS-INT-RATE)/1200 NO ROUNDED -> truncate scale2 DOWN CBACT04C.cbl L462-465
        // (100.00 * 12.55) / 1200 = 1.045833... -> setScale(2, DOWN) = 1.04 (HALF_UP would give 1.05).
        // assertEquals with a scale-2 literal verifies BOTH the value AND the scale (BigDecimal.equals
        // is scale-sensitive).
        assertEquals(new BigDecimal("1.04"), amt(r.txns().get(0)), "monthly interest truncated to 1.04");
        assertEquals(new BigDecimal("101.04"), r.accounts().read(ACCT_A).getCurrBal(),
                "posted balance = 100.00 + 1.04");
    }

    @Test
    @DisplayName("BR-10: monthly amounts accumulate into the per-account total")
    void br10_accumulateMonthlyAmounts() throws Exception {
        List<TransactionCategoryBalance> driver = List.of(
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"),
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"));
        Result r = execute(driver,
                List.of(acctLine(ACCT_A, new BigDecimal("0.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        // BR-10 ADD WS-MONTHLY-INT TO WS-TOTAL-INT CBACT04C.cbl L467
        assertEquals(2, r.txns().size(), "two rate!=0 records -> two txns");
        assertEquals(new BigDecimal("1.04"), amt(r.txns().get(0)));
        assertEquals(new BigDecimal("1.04"), amt(r.txns().get(1)));
        assertEquals(new BigDecimal("2.08"), r.accounts().read(ACCT_A).getCurrBal(),
                "accumulated total = 1.04 + 1.04 posted to currBal (0.00 + 2.08)");
    }

    @Test
    @DisplayName("BR-11: post total to ACCT-CURR-BAL and zero BOTH cycle fields (survives rewrite)")
    void br11_postTotalAndZeroCycleFields() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("5.00"),
                        new BigDecimal("7.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        Account a = r.accounts().read(ACCT_A);
        assertAll("1050-UPDATE-ACCOUNT mutations",
                // BR-11 ADD WS-TOTAL-INT TO ACCT-CURR-BAL L352
                () -> assertEquals(new BigDecimal("101.04"), a.getCurrBal(), "currBal = 100.00 + 1.04"),
                // BR-11 MOVE 0 TO ACCT-CURR-CYC-CREDIT L353
                () -> assertEquals(new BigDecimal("0.00"), a.getCurrCycCredit(), "cycle credit zeroed"),
                // BR-11 MOVE 0 TO ACCT-CURR-CYC-DEBIT L354
                () -> assertEquals(new BigDecimal("0.00"), a.getCurrCycDebit(), "cycle debit zeroed"));

        // Stronger: the mutation survives the 300-byte REWRITE (L356). Re-read the emitted account
        // master and decode the three mutated slices; other bytes are AccountRepository's own concern.
        Path acctOut = tempDir.resolve("acct-out-" + uniq() + ".txt");
        r.accounts().writeUpdatedAccounts(acctOut);
        List<String> lines = Files.readAllLines(acctOut, US_ASCII);
        String rewritten = lines.stream()
                .filter(l -> !l.isEmpty() && FixedWidthCodec.slice(l, 0, 11).equals(ACCT_A))
                .findFirst()
                .orElseThrow(() -> new AssertionError("rewritten ACCT_A record not found"));
        assertEquals(300, rewritten.length(), "rewritten account record must be 300 bytes");
        assertAll("mutations survive the 300-byte rewrite",
                () -> assertEquals(new BigDecimal("101.04"),
                        ZonedDecimal.decode(FixedWidthCodec.slice(rewritten, 12, 12), 2), "currBal @[12,24)"),
                () -> assertEquals(new BigDecimal("0.00"),
                        ZonedDecimal.decode(FixedWidthCodec.slice(rewritten, 78, 12), 2), "cycCredit @[78,90)"),
                () -> assertEquals(new BigDecimal("0.00"),
                        ZonedDecimal.decode(FixedWidthCodec.slice(rewritten, 90, 12), 2), "cycDebit @[90,102)"));
    }


    @Test
    @DisplayName("BR-12: final per-account update fires post-loop at EOF")
    void br12_finalUpdateAtEof() throws Exception {
        // Single record, no subsequent break -> the ONLY place the update can occur is the post-loop
        // EOF update, so a changed balance proves BR-12.
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        // BR-12 final 1050-UPDATE-ACCOUNT at end-of-file CBACT04C.cbl L219-220
        assertEquals(new BigDecimal("101.04"), r.accounts().read(ACCT_A).getCurrBal(),
                "EOF final update posted the 1.04 interest to the last (only) account");
    }

    @Test
    @DisplayName("BR-13: TRAN-ID = PARM-DATE + run-wide 6-digit suffix (000001,000002,000003)")
    void br13_tranIdRunWideSuffix() throws Exception {
        // [A, A, B] all rate!=0 -> the suffix is run-wide (NOT reset per account) and pre-incremented.
        List<TransactionCategoryBalance> driver = List.of(
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"),
                tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00"),
                tcb(ACCT_B, TYPE_CD, CAT_CD, "100.00"));
        Result r = execute(driver,
                List.of(
                        acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                                new BigDecimal("0.00"), GROUP_STD),
                        acctLine(ACCT_B, new BigDecimal("100.00"), new BigDecimal("0.00"),
                                new BigDecimal("0.00"), GROUP_STD)),
                List.of(
                        xrefLine(CARD_A, CUST_A, ACCT_A),
                        xrefLine(CARD_B, CUST_B, ACCT_B)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        assertEquals(3, r.txns().size());
        r.txns().forEach(InterestCalculationServiceTest::assertFramed);
        // BR-13 ADD 1 TO WS-TRANID-SUFFIX; STRING PARM-DATE + suffix INTO TRAN-ID CBACT04C.cbl L474-480
        assertAll("run-wide, pre-incremented 6-digit suffix",
                () -> assertEquals("2022071800000001", fld(r.txns().get(0), 0, 16)),
                () -> assertEquals("2022071800000002", fld(r.txns().get(1), 0, 16)),
                () -> assertEquals("2022071800000003", fld(r.txns().get(2), 0, 16)));
    }

    @Test
    @DisplayName("BR-14: fixed transaction field values (type '01', cat '0005', source, desc, merchant, card)")
    void br14_fixedTransactionFields() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        assertEquals(1, r.txns().size());
        String line = r.txns().get(0);
        assertFramed(line);
        // BR-14 fixed transaction fields CBACT04C.cbl L482-495
        assertAll("fixed TRAN-RECORD field values",
                () -> assertEquals("01", fld(line, 16, 2), "TRAN-TYPE-CD '01' // L482"),
                () -> assertEquals("0005", fld(line, 18, 4), "TRAN-CAT-CD MOVE '05' into 9(04) -> '0005' // L483"),
                () -> assertEquals(FixedWidthCodec.alpha("System", 10), fld(line, 22, 10),
                        "TRAN-SOURCE 'System    ' // L484"),
                () -> assertEquals(FixedWidthCodec.alpha("Int. for a/c " + ACCT_A, 100), fld(line, 32, 100),
                        "TRAN-DESC 'Int. for a/c ' + ACCT-ID // L485-489"),
                () -> assertEquals("000000000", fld(line, 143, 9), "TRAN-MERCHANT-ID MOVE 0 into 9(09) // L491"),
                () -> assertEquals(" ".repeat(50), fld(line, 152, 50), "TRAN-MERCHANT-NAME spaces // L492"),
                () -> assertEquals(" ".repeat(50), fld(line, 202, 50), "TRAN-MERCHANT-CITY spaces // L493"),
                () -> assertEquals(" ".repeat(10), fld(line, 252, 10), "TRAN-MERCHANT-ZIP spaces // L494"),
                () -> assertEquals(FixedWidthCodec.alpha(CARD_A, 16), fld(line, 262, 16),
                        "TRAN-CARD-NUM = XREF-CARD-NUM // L495"));
    }

    @Test
    @DisplayName("BR-15: TRAN-ORIG-TS == TRAN-PROC-TS == injected FIXED_TS")
    void br15_origTsEqualsProcTs() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        assertEquals(1, r.txns().size());
        String line = r.txns().get(0);
        assertFramed(line);
        String origTs = fld(line, 278, 26);
        String procTs = fld(line, 304, 26);
        // BR-15 origTs == procTs, single Z-GET-DB2-FORMAT-TIMESTAMP value CBACT04C.cbl L496-498, L613-626
        assertAll("single injected timestamp used for both stamps",
                () -> assertEquals(FIXED_TS, origTs, "TRAN-ORIG-TS == injected timestamp"),
                () -> assertEquals(FIXED_TS, procTs, "TRAN-PROC-TS == injected timestamp"),
                () -> assertEquals(origTs, procTs, "the two stamps are identical"));
    }

    @Test
    @DisplayName("BR-16: fees are a no-op — no extra transaction, no fee component in the balance")
    void br16_feesAreNoOp() throws Exception {
        Result r = execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55"))));

        // BR-16 1400-COMPUTE-FEES empty stub -> no fee effects CBACT04C.cbl L518-520
        assertEquals(1, r.txns().size(), "exactly one txn == number of rate!=0 records (no fee txn)");
        assertEquals(new BigDecimal("101.04"), r.accounts().read(ACCT_A).getCurrBal(),
                "balance delta equals exactly the interest, with NO fee component");
    }

    @Test
    @DisplayName("BR-17(a): missing account -> fatal RuntimeException (1100 INVALID KEY -> abend)")
    void br17a_missingAccountAbends() {
        // Empty account fixture; a driver record for ACCT_A; valid xref & disc.
        RuntimeException ex = assertThrows(RuntimeException.class, () -> execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(), // no accounts -> read(ACCT_A) fails
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55")))));
        // BR-17 1100 INVALID KEY -> 9999-ABEND CBACT04C.cbl L383-390
        assertNotNull(ex.getMessage(), "abend surfaces a (non-null) diagnostic message");
    }

    @Test
    @DisplayName("BR-17(b): missing xref -> fatal RuntimeException (1110 INVALID KEY -> abend)")
    void br17b_missingXrefAbends() {
        // Valid account; empty xref fixture; valid disc.
        RuntimeException ex = assertThrows(RuntimeException.class, () -> execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_STD)),
                List.of(), // no xrefs -> byAccountId(ACCT_A) fails
                List.of(discLine(GROUP_STD, TYPE_CD, CAT_CD, new BigDecimal("12.55")))));
        // BR-17 1110 INVALID KEY -> 9999-ABEND CBACT04C.cbl L405-412
        assertNotNull(ex.getMessage(), "abend surfaces a (non-null) diagnostic message");
    }

    @Test
    @DisplayName("BR-17(c): missing exact + DEFAULT disclosure group -> fatal RuntimeException (1200-A abend)")
    void br17c_missingDefaultGroupAbends() {
        // Valid account with GROUP_MISSING; valid xref; a disc fixture with NO matching row and NO
        // DEFAULT row (a single unrelated row) -> lookup exhausts exact + DEFAULT and throws.
        RuntimeException ex = assertThrows(RuntimeException.class, () -> execute(
                List.of(tcb(ACCT_A, TYPE_CD, CAT_CD, "100.00")),
                List.of(acctLine(ACCT_A, new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), GROUP_MISSING)),
                List.of(xrefLine(CARD_A, CUST_A, ACCT_A)),
                List.of(discLine("OTHERGRP", "99", "9999", new BigDecimal("1.00")))));
        // BR-17 1200-A DEFAULT missing -> 9999-ABEND CBACT04C.cbl L452-459
        assertNotNull(ex.getMessage(), "abend surfaces a (non-null) diagnostic message");
    }
}


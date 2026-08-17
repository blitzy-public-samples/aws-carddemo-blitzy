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
package com.blitzy.carddemo.interest.golden;

import com.blitzy.carddemo.interest.InterestCalculator;
import com.blitzy.carddemo.interest.support.Db2TimestampSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end <strong>byte-exact golden-master / characterization test</strong> for the Java&nbsp;21
 * port of the COBOL batch program {@code CBACT04C} (the interest-calculation job driven by
 * {@code app/jcl/INTCALC.jcl}). It is the single most important correctness artifact of the
 * migration: it drives the whole batch through the public CLI seam
 * {@link InterestCalculator#run(String[], Db2TimestampSupplier)} over the in-repo golden fixtures and
 * asserts that the two produced output files reproduce the captured legacy outputs
 * <em>byte&nbsp;for&nbsp;byte</em>.
 *
 * <h2>What is characterized</h2>
 * <p>The whole {@code CBACT04C} {@code PROCEDURE DIVISION} orchestration
 * (<code>app/cbl/CBACT04C.cbl:L180-232</code>): open the five files, drive the sequential TCATBAL
 * loop with an account break on every key change, look up the disclosure-group rate (with the
 * DEFAULT-group fallback), compute the truncating monthly interest, emit a 350-byte interest
 * transaction per driven record, and rewrite the 300-byte account master at each account break and at
 * end-of-file.</p>
 *
 * <p><strong>What the assertion pair actually locks</strong> is the observable <em>output contract of
 * the shipped fixtures</em>: it fails on any change to the bytes those four fixtures produce, so it is
 * a broad end-to-end net across the whole pipeline rather than a rule-by-rule check &mdash; but it
 * locks a rule only to the extent that these fixtures make that rule byte-visible. The account-side
 * <em>value</em> semantics are the gap: every account accumulates {@code 0.00} over already-zero cycle
 * fields, so the ratified end-of-file account update (<strong>BR-12</strong>,
 * <code>app/cbl/CBACT04C.cbl:L219-220</code>) is <strong>byte-neutral</strong> here &mdash;
 * characterized (documented and executed) by this test, but locked by
 * {@code InterestCalculationServiceTest.br12_finalUpdateAtEof} instead. See the
 * fixture-characterization note below for why.</p>
 *
 * <h2>Business rules characterized (AAP &sect;0.6.3)</h2>
 * <p>Every rule below is exercised end-to-end by this run, and the byte comparison locks each one only
 * to the extent that these fixtures make its effect visible in the produced bytes. The
 * transaction-side rules write their computed values straight into the 350-byte records (with every
 * driver balance at {@code 0.00}, BR-09's truncation is executed but not discriminated &mdash; its
 * boundary vectors live in {@code CobolArithmeticTest} and
 * {@code InterestCalculationServiceTest.br09_interestTruncationBoundary}). On the account side the
 * fixtures are deliberately quiet: the 300-byte output locks the {@code REWRITE} geometry and the
 * preservation of every account field, while the <em>value</em> semantics of <strong>BR-11</strong>
 * (posting and zeroing) and of the ratified <strong>BR-12</strong> (the end-of-driver update) are
 * byte-neutral here and are locked with non-zero data by
 * {@code InterestCalculationServiceTest.br11_postTotalAndZeroCycleFields} and
 * {@code InterestCalculationServiceTest.br12_finalUpdateAtEof} respectively.</p>
 * <ul>
 *   <li><strong>BR-01</strong> &mdash; TCATBAL read sequentially as the driver; one pass over all
 *       records (<code>app/cbl/CBACT04C.cbl:L188-193,L326</code>).</li>
 *   <li><strong>BR-05</strong> &mdash; per-account random ACCOUNT read + XREF read by alternate key
 *       (<code>app/cbl/CBACT04C.cbl:L373,L394-395</code>).</li>
 *   <li><strong>BR-07</strong> &mdash; the write guard tests the <em>rate</em>, not the balance, so a
 *       zero balance with a non-zero rate still emits a {@code 0.00} transaction
 *       (<code>app/cbl/CBACT04C.cbl:L214</code>).</li>
 *   <li><strong>BR-08</strong> &mdash; DEFAULT-group fallback when the exact key misses (COBOL
 *       file-status {@code '23'}) (<code>app/cbl/CBACT04C.cbl:L436-439,L443-460</code>).</li>
 *   <li><strong>BR-09</strong> &mdash; interest {@code = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200},
 *       <em>truncated</em> to scale&nbsp;2 (the {@code COMPUTE} carries no {@code ROUNDED} clause)
 *       (<code>app/cbl/CBACT04C.cbl:L464-465</code>).</li>
 *   <li><strong>BR-11</strong> &mdash; post total interest to {@code ACCT-CURR-BAL}, zero the two
 *       cycle fields, and {@code REWRITE} (<code>app/cbl/CBACT04C.cbl:L350-356</code>).</li>
 *   <li><strong>BR-12 (RATIFIED) &mdash; documented here, locked elsewhere:</strong> the final account
 *       update fires at end-of-file: the port performs it at end-of-driver (post-loop), guarded by the
 *       first-time flag, porting {@code ELSE PERFORM 1050-UPDATE-ACCOUNT}
 *       (<code>app/cbl/CBACT04C.cbl:L219-220</code>) so the LAST account in driver order is posted too.
 *       That end-of-driver reading is a <strong>ratified</strong> porting decision, not an open
 *       question; because it is byte-neutral for these fixtures (see the fixture-characterization note
 *       below), these bytes cannot detect its removal &mdash; its observable effect is locked by
 *       {@code InterestCalculationServiceTest.br12_finalUpdateAtEof}, not here.</li>
 *   <li><strong>BR-13</strong> &mdash; {@code TRAN-ID = PARM-DATE + 6-digit suffix}, incremented per
 *       transaction (<code>app/cbl/CBACT04C.cbl:L474-480</code>).</li>
 *   <li><strong>BR-14</strong> &mdash; fixed transaction fields: type {@code '01'}, cat {@code '05'},
 *       source {@code 'System'}, desc {@code 'Int. for a/c ' + ACCT-ID}, merchant id {@code 0},
 *       name/city/zip spaces, card = {@code XREF-CARD-NUM}
 *       (<code>app/cbl/CBACT04C.cbl:L482-495</code>).</li>
 *   <li><strong>BR-15</strong> &mdash; {@code TRAN-ORIG-TS} equals {@code TRAN-PROC-TS} (the same
 *       injected DB2 timestamp) (<code>app/cbl/CBACT04C.cbl:L496-498,L613-626</code>).</li>
 * </ul>
 *
 * <h2>Determinism by injection (AAP &sect;0.7)</h2>
 * <p>The <em>only</em> non-deterministic input in {@code CBACT04C} is
 * {@code MOVE FUNCTION CURRENT-DATE} (<code>app/cbl/CBACT04C.cbl:L614</code>). This test neutralizes it
 * by injecting a <strong>fixed</strong> {@link Db2TimestampSupplier} lambda through the {@code run(...)}
 * seam &mdash; it never calls {@link InterestCalculator#main(String[])} (which would bind the wall
 * clock) nor {@link Db2TimestampSupplier#systemDefault()}. The fixed value is byte-identical to the
 * {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} embedded in the expected transaction file.</p>
 *
 * <h2>Why the golden outputs look the way they do (fixture characterization)</h2>
 * <p>The four fixtures make the run fully deterministic: there are 50 distinct accounts and one
 * TCATBAL driver record each, so an account break fires on <em>every</em> record; every driver record
 * carries {@code (TRANCAT-TYPE-CD, TRANCAT-CD) = ("01","0001")} and {@code TRAN-CAT-BAL = 0.00}; every
 * {@code ACCT-GROUP-ID} is blank, so the exact DISCGRP key misses and the DEFAULT group supplies rate
 * {@code 15.00} (non-zero) for all 50 (BR-08). Because the write guard tests {@code rate != 0} rather
 * than the balance (BR-07), all 50 transactions are emitted, each with {@code TRAN-AMT = 0.00}
 * (as {@code 0.00 * 15.00 / 1200} truncates to {@code 0.00}). Each account is rewritten with
 * {@code ACCT-CURR-BAL += 0.00} and its two cycle fields (already zero) zeroed, so the updated master
 * is byte-identical to the input {@code acctdata.txt} (BR-11, BR-12).</p>
 *
 * <p>That byte-identity is a property of <em>these</em> fixtures rather than of the rules themselves:
 * it holds <strong>precisely because every account accumulates {@code 0.00}</strong> over already-zero
 * cycle fields, so these bytes are <strong>byte-neutral</strong> for the ratified BR-12 decision &mdash;
 * they look identical whether or not the end-of-driver account update
 * (<code>app/cbl/CBACT04C.cbl:L219-220</code>) fires. The ratified decision's observable effect is
 * therefore locked by {@code InterestCalculationServiceTest.br12_finalUpdateAtEof}, which drives
 * non-zero accumulated interest and non-zero cycle fields; what these bytes lock is that the rule is
 * byte-neutral <em>here</em>.</p>
 *
 * <h2>Self-contained (AAP &sect;0.7 self-contained-tests rule)</h2>
 * <p>All inputs are read from the test classpath ({@code src/test/resources/**}, copied by Maven to
 * {@code target/test-classes/**}); outputs are written into a JUnit-managed {@link TempDir}. There is
 * no network, no environment variable, no system property, and no dependency on a live mainframe or on
 * {@code app/data/ASCII/}. Only exactly TWO output files are produced &mdash; {@code TCATBAL} is
 * {@code OPEN INPUT} (read-only) and {@code ACCTFILE} is the sole mutated/rewritten file
 * (<code>app/cbl/CBACT04C.cbl:L356</code>; AAP &sect;0.7 source-vs-narrative resolution) &mdash; so no
 * "updated-tcatbal" artifact is expected or asserted.</p>
 *
 * <p>Dependencies: JUnit&nbsp;5 (Jupiter) + the pure JDK only; no third-party library is referenced.</p>
 *
 * @see InterestCalculator#run(String[], Db2TimestampSupplier)
 * @see Db2TimestampSupplier
 */
@DisplayName("CBACT04C golden master — end-to-end byte-exact outputs (interest-transactions + updated-accounts)")
public class InterestCalculationGoldenMasterTest {

    /**
     * The <strong>fixed</strong> 26-character DB2-format timestamp injected in place of
     * {@code FUNCTION CURRENT-DATE} (<code>app/cbl/CBACT04C.cbl:L614</code>). It is byte-identical to
     * the {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} fields baked into
     * {@code expected/interest-transactions.txt}, which locks <strong>BR-15</strong> (origTs ==
     * procTs, <code>app/cbl/CBACT04C.cbl:L496-498</code> / {@code Z-GET-DB2-FORMAT-TIMESTAMP}
     * <code>L613-626</code>). Do not alter a single character.
     */
    private static final String FIXED_DB2_TS = "2022-07-18-00.00.00.000000";

    /**
     * The {@code PARM-DATE} passed as {@code args[6]}; it is the 10-character prefix of every
     * {@code TRAN-ID} (<code>app/cbl/CBACT04C.cbl:L474-480</code>, BR-13). Sourced verbatim from the
     * INTCALC job card {@code PARM='2022071800'} (<code>app/jcl/INTCALC.jcl:L22</code>).
     */
    private static final String PARM_DATE = "2022071800";

    /**
     * Expected size of the interest-transactions output: 50 records &times; 350 bytes + a trailing LF
     * = 17550 bytes. The 350-byte record length is the {@code CVTRA05Y TRAN-RECORD} layout written by
     * {@code TRANSACT} (<code>app/cbl/CBACT04C.cbl:L500</code>).
     */
    private static final long EXPECTED_TXN_BYTES = 17550L;

    /**
     * Expected size of the updated-accounts output: 50 records &times; 300 bytes + a trailing LF
     * = 15050 bytes. The 300-byte record length is the {@code CVACT01Y ACCOUNT-RECORD} layout
     * rewritten by {@code ACCTFILE} (<code>app/cbl/CBACT04C.cbl:L356</code>).
     */
    private static final long EXPECTED_ACCT_BYTES = 15050L;

    /**
     * Canonical SHA-256 content anchor of the golden interest-transactions output (lowercase hex).
     * Used as a JDK-only secondary cross-check that pins the produced bytes to their known-good
     * content hash, independent of the expected resource file (TRANSACT WRITE,
     * <code>app/cbl/CBACT04C.cbl:L500</code>).
     */
    private static final String EXPECTED_TXN_SHA256 =
            "99cc67d495974da0ecd631861b139c0ce9b993d3a1a4330b5dfdad822fb59ebd";

    /**
     * Canonical SHA-256 content anchor of the golden updated-accounts output (lowercase hex). Equal to
     * the hash of the input {@code acctdata.txt} because every balance is unchanged (BR-11/BR-12,
     * ACCTFILE REWRITE, <code>app/cbl/CBACT04C.cbl:L356</code>).
     *
     * <p>That equality is byte-neutrality of <em>these</em> fixtures (every account accumulates
     * {@code 0.00} over already-zero cycle fields), so this anchor does not by itself exercise the
     * ratified end-of-driver BR-12 update (<code>L219-220</code>) &mdash;
     * {@code InterestCalculationServiceTest.br12_finalUpdateAtEof} does.</p>
     */
    private static final String EXPECTED_ACCT_SHA256 =
            "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260";

    /**
     * Resolves a test resource on the classpath to a real filesystem {@link Path}.
     *
     * <p>{@link InterestCalculator#run(String[], Db2TimestampSupplier)} receives filesystem path
     * strings and opens them with {@code java.nio.file}. The fixtures/expected files live under
     * {@code target/test-classes/} at run time (Maven copies {@code src/test/resources/**} there), so
     * they are real files on disk; this helper resolves them via the class loader &mdash; never via a
     * hardcoded {@code src/test/resources/...} or absolute path &mdash; so the test passes under
     * {@code mvn test} regardless of the process working directory.</p>
     *
     * @param name the classpath-relative resource name (e.g. {@code "fixtures/tcatbal.txt"})
     * @return the resource resolved to a real {@link Path}
     * @throws Exception if {@link URL#toURI()} fails (checked {@code URISyntaxException})
     */
    private static Path resource(String name) throws Exception {
        URL url = InterestCalculationGoldenMasterTest.class.getClassLoader().getResource(name);
        assertNotNull(url, "Required test resource missing from classpath (target/test-classes): " + name);
        return Path.of(url.toURI());
    }

    /**
     * Computes the lowercase-hex SHA-256 digest of the given bytes using only the JDK
     * ({@link MessageDigest} + {@link HexFormat}) &mdash; no third-party dependency.
     *
     * @param bytes the content to hash
     * @return the 64-character lowercase-hex SHA-256 digest
     * @throws Exception if the SHA-256 algorithm is unavailable (never on a conformant JDK)
     */
    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    /**
     * Drives the full batch once with a fixed timestamp and asserts both output files are byte-exact.
     *
     * @param tempDir a JUnit-managed, auto-cleaned temporary directory for the two outputs (never
     *                {@code src/} or the classpath)
     * @throws Exception from {@code run(...)} ({@code IOException}) and {@code toURI()}
     *                   ({@code URISyntaxException}); any thrown exception fails the test
     */
    @Test
    @DisplayName("run(...) over the golden fixtures reproduces interest-transactions (350B) and updated-accounts (300B) byte-for-byte")
    void goldenMaster_endToEnd_isByteExact(@TempDir Path tempDir) throws Exception {
        // Outputs go into the JUnit-managed temp dir; the canonical filenames mirror the two artifacts
        // the COBOL writes: TRANSACT (interest transactions) and the rewritten ACCTFILE (updated accts).
        Path txnOut = tempDir.resolve("interest-transactions.txt");   // TRANSACT WRITE (CBACT04C:L500)
        Path acctOut = tempDir.resolve("updated-accounts.txt");        // ACCTFILE REWRITE (CBACT04C:L356)

        // Build the 7 positional arguments EXACTLY per the InterestCalculator CLI contract (AAP §0.4.2),
        // which mirrors the INTCALC DD-to-fixture map (app/jcl/INTCALC.jcl:L27-41). Inputs resolve from
        // the classpath to real files; outputs are the temp-dir paths above; args[6] is the PARM-DATE.
        String[] args = new String[] {
                resource("fixtures/tcatbal.txt").toString(),   // args[0] TCATBAL driver input  (OPEN INPUT; 1000-TCATBALF-GET-NEXT L325-348)
                resource("fixtures/cardxref.txt").toString(),  // args[1] CARD-XREF input        (1110-GET-XREF-DATA L393-413)
                resource("fixtures/acctdata.txt").toString(),  // args[2] ACCOUNT input          (OPEN I-O; 1100-GET-ACCT-DATA L372-391)
                resource("fixtures/discgrp.txt").toString(),   // args[3] DISCGRP rate input     (1200-GET-INTEREST-RATE L415-460)
                txnOut.toString(),                             // args[4] interest-transactions OUTPUT (TRANSACT 350B WRITE, L500)
                acctOut.toString(),                            // args[5] updated-accounts OUTPUT      (ACCTFILE 300B REWRITE, L356)
                PARM_DATE                                      // args[6] PARM-DATE (app/jcl/INTCALC.jcl:L22)
        };

        // Determinism by injection: neutralize the only non-deterministic input, FUNCTION CURRENT-DATE
        // (app/cbl/CBACT04C.cbl:L614), with a FIXED supplier so TRAN-ORIG-TS/TRAN-PROC-TS are stable
        // (BR-15, L496-498 / Z-GET-DB2-FORMAT-TIMESTAMP L613-626). The no-arg lambda is assignable to
        // the @FunctionalInterface Db2TimestampSupplier (single abstract method String get()).
        Db2TimestampSupplier fixedTimestamp = () -> FIXED_DB2_TS;
        // Call run(...), NOT main(...): main binds Db2TimestampSupplier.systemDefault() (the wall clock)
        // and would break determinism. run(...) is public precisely so this golden test can inject the
        // fixed supplier and obtain byte-exact output (CBACT04C PROCEDURE DIVISION L180-232).
        InterestCalculator.run(args, fixedTimestamp);

        // --- Secondary diagnostics: file sizes (fail fast with a clearer message than a raw mismatch) ---
        // 50 driver records -> 50 transactions × 350B + trailing LF = 17550B (TRANSACT 1300-B-WRITE-TX
        // WRITE, app/cbl/CBACT04C.cbl:L500; record layout CVTRA05Y TRAN-RECORD 350B).
        assertEquals(EXPECTED_TXN_BYTES, Files.size(txnOut),
                "interest-transactions.txt must be 50x350B + LF = 17550B (TRANSACT 350B WRITE, CBACT04C:L500)");
        // 50 accounts × 300B + trailing LF = 15050B (ACCTFILE REWRITE 1050-UPDATE-ACCOUNT,
        // app/cbl/CBACT04C.cbl:L356; record layout CVACT01Y ACCOUNT-RECORD 300B).
        assertEquals(EXPECTED_ACCT_BYTES, Files.size(acctOut),
                "updated-accounts.txt must be 50x300B + LF = 15050B (ACCTFILE 300B REWRITE, CBACT04C:L356)");

        // Read RAW BYTES for both produced and both expected files. NEVER decode to String: the
        // overpunch sign bytes (e.g. '{') and fixed-width space padding must compare exactly; charset
        // decoding or line-ending normalization would corrupt the comparison. The files are
        // LF-terminated US-ASCII, which raw-byte comparison handles correctly.
        byte[] expectedTxn = Files.readAllBytes(resource("expected/interest-transactions.txt"));
        byte[] actualTxn = Files.readAllBytes(txnOut);
        byte[] expectedAcct = Files.readAllBytes(resource("expected/updated-accounts.txt"));
        byte[] actualAcct = Files.readAllBytes(acctOut);

        // === PRIMARY ASSERTION 1 — interest-transactions byte-for-byte ==================================
        // Locks the whole transaction-generation pipeline of 1300-B-WRITE-TX
        // (app/cbl/CBACT04C.cbl:L473-515, WRITE L500): truncating interest 1300-COMPUTE-INTEREST
        // (L464-465, BR-09), TRAN-ID = PARM-DATE + 6-digit suffix (L474-480, BR-13), fixed fields
        // type '01'/cat '05'/source 'System'/desc 'Int. for a/c '+ACCT-ID/merchant id 0/name,city,zip
        // spaces/card = XREF-CARD-NUM (L482-495, BR-14), TRAN-ORIG-TS == TRAN-PROC-TS (L496-498, BR-15),
        // and the sequential driver loop that emits one txn per record because the write guard tests
        // rate != 0 not balance (L188-193 BR-01; L214 BR-07; DEFAULT fallback L436-439/L443-460 BR-08).
        assertArrayEquals(expectedTxn, actualTxn,
                "interest-transactions.txt not byte-exact: locks 1300-B-WRITE-TX WRITE (CBACT04C:L473-515,L500), "
                        + "truncating interest 1300-COMPUTE-INTEREST (L464-465, BR-09), TRAN-ID build (L474-480, BR-13), "
                        + "fixed fields (L482-495, BR-14), origTs==procTs (L496-498, BR-15), driver loop (L188-193, BR-01)");

        // === PRIMARY ASSERTION 2 — updated-accounts byte-for-byte =======================================
        // WHAT THIS LOCKS: the 1050-UPDATE-ACCOUNT REWRITE output contract — all 50 account records
        // re-emitted at exactly 300 bytes with every field preserved (app/cbl/CBACT04C.cbl:L350-356,
        // BR-11) — and the per-account ACCOUNT + XREF load that feeds it (L373, L394-395, BR-05).
        // WHAT IT DOES NOT LOCK (byte-neutral for these fixtures): the account-side VALUE semantics —
        // posting the accumulated interest to ACCT-CURR-BAL (L352) and zeroing the two cycle fields
        // (L353-354) of BR-11, and the RATIFIED final end-of-file account update (L219-220, BR-12),
        // which this port performs at end-of-driver (post-loop), guarded by the first-time flag, so the
        // LAST account in driver order is posted too. With the golden fixtures every balance += 0.00
        // over already-zero cycle fields, so the rewritten master is byte-identical to acctdata.txt —
        // identical whether or not the end-of-driver update fires. These bytes therefore DOCUMENT
        // ratified BR-12 (and BR-11's value semantics) as byte-neutral here; both are locked
        // behaviorally, with non-zero data, by InterestCalculationServiceTest:
        // br11_postTotalAndZeroCycleFields and br12_finalUpdateAtEof.
        assertArrayEquals(expectedAcct, actualAcct,
                "updated-accounts.txt not byte-exact: locks the per-account 1050-UPDATE-ACCOUNT REWRITE output "
                        + "contract (CBACT04C:L350-356, BR-11) and the per-account ACCOUNT+XREF load "
                        + "(L373,L394-395, BR-05). It DOCUMENTS ratified BR-12 (the end-of-driver final account "
                        + "update, L219-220) as BYTE-NEUTRAL for these fixtures — every account accumulates 0.00 "
                        + "over already-zero cycle fields, so these bytes are identical whether or not it fires; "
                        + "the behavioral locks are InterestCalculationServiceTest.br12_finalUpdateAtEof and, for "
                        + "BR-11's posting (L352) and zeroing (L353-354), br11_postTotalAndZeroCycleFields");

        // --- Secondary cross-check: SHA-256 digests match the canonical anchors (JDK-only; defense in
        // depth). The anchors are hardcoded literals independent of the expected resource files, so this
        // still catches a hypothetical drift in which the produced and expected files changed together.
        // interest-transactions content hash (TRANSACT WRITE, app/cbl/CBACT04C.cbl:L500).
        assertEquals(EXPECTED_TXN_SHA256, sha256Hex(actualTxn),
                "interest-transactions.txt SHA-256 must equal the canonical golden anchor (TRANSACT WRITE, CBACT04C:L500)");
        // updated-accounts content hash (ACCTFILE REWRITE, app/cbl/CBACT04C.cbl:L356).
        assertEquals(EXPECTED_ACCT_SHA256, sha256Hex(actualAcct),
                "updated-accounts.txt SHA-256 must equal the canonical golden anchor (ACCTFILE REWRITE, CBACT04C:L356)");
    }
}

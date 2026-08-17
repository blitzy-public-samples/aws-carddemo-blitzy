/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blitzy.carddemo.interest.service;

import java.math.BigDecimal;

import com.blitzy.carddemo.interest.io.AccountRepository;
import com.blitzy.carddemo.interest.io.CardXrefRepository;
import com.blitzy.carddemo.interest.io.DisclosureGroupRepository;
import com.blitzy.carddemo.interest.io.TransactionWriter;
import com.blitzy.carddemo.interest.model.Account;
import com.blitzy.carddemo.interest.model.CardXref;
import com.blitzy.carddemo.interest.model.DisclosureGroup;
import com.blitzy.carddemo.interest.model.TransactionCategoryBalance;
import com.blitzy.carddemo.interest.model.TransactionRecord;
import com.blitzy.carddemo.interest.support.CobolArithmetic;
import com.blitzy.carddemo.interest.support.Db2TimestampSupplier;

/**
 * Pure, behavior-preserving Java port of the {@code CBACT04C} interest-calculation business loop.
 *
 * <p>This service reproduces the {@code PROCEDURE DIVISION} driver loop of the legacy COBOL batch
 * program {@code app/cbl/CBACT04C.cbl} (L188-222) and the paragraphs it {@code PERFORM}s:
 * {@code 1050-UPDATE-ACCOUNT} (L350-356), {@code 1100-GET-ACCT-DATA} (L372-391),
 * {@code 1110-GET-XREF-DATA} (L393-413), {@code 1200-GET-INTEREST-RATE} (L415-440) with its
 * {@code 1200-A-GET-DEFAULT-INT-RATE} fallback (L443-460), {@code 1300-COMPUTE-INTEREST} (L462-470),
 * {@code 1300-B-WRITE-TX} (L473-515), and {@code 1400-COMPUTE-FEES} (L518-520). The COBOL
 * open/read/write/close plumbing is not reproduced here; those responsibilities live in the
 * {@code io} collaborators and in {@code InterestCalculator} (the CLI adapter).</p>
 *
 * <p><b>Design (AAP &sect;0.3.3 &mdash; service layer).</b> The class is a <em>pure</em> orchestration
 * of already-loaded collaborators: it opens/closes/loads no files, holds no instance state, and is
 * therefore stateless and thread-safe. Every working-storage item of the COBOL loop
 * ({@code WS-LAST-ACCT-NUM}, {@code WS-FIRST-TIME}, {@code WS-TOTAL-INT}, {@code WS-TRANID-SUFFIX},
 * {@code WS-RECORD-COUNT}, and the current account/xref) is modeled as a <em>local variable</em>
 * inside {@link #process}, so a single instance may be reused across runs and threads.</p>
 *
 * <p><b>Decimal fidelity (BR-09).</b> All interest arithmetic flows through
 * {@link CobolArithmetic#monthlyInterest(BigDecimal, BigDecimal)}, which truncates
 * ({@code RoundingMode.DOWN}) to scale 2 because the source {@code COMPUTE} at L464-465 carries no
 * {@code ROUNDED} clause. This class never rounds {@code HALF_EVEN} and never computes interest
 * inline.</p>
 *
 * <p><b>Determinism (BR-15).</b> The single non-deterministic COBOL input,
 * {@code FUNCTION CURRENT-DATE} (L614), is injected via {@link Db2TimestampSupplier}. The service
 * reads the clock nowhere; it calls {@link Db2TimestampSupplier#get()} exactly once per generated
 * transaction and uses that one value for both {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}.</p>
 *
 * <p><b>Error semantics (BR-17).</b> Account-not-found (L383-390) and xref-not-found (L405-412) port
 * the COBOL {@code 9999-ABEND-PROGRAM} abend as fatal {@link RuntimeException}s thrown by the
 * {@code io} layer; this service deliberately does not catch them &mdash; they propagate. A
 * disclosure-group miss is resolved inside {@link DisclosureGroupRepository#lookup} via the DEFAULT
 * fallback (BR-08, L436-439, L443-460) and never surfaces here; only a missing DEFAULT group is
 * fatal (thrown by the repository).</p>
 *
 * <p><b>Source-vs-narrative discrepancy (AAP &sect;0.7).</b> The Tech-Spec narrative claims CBACT04C
 * "updates TCATBAL category balances"; the authoritative source opens TCATBAL read-only and mutates
 * only {@code ACCTFILE} ({@code REWRITE} at L356). This port follows the source: the driver
 * ({@code TransactionCategoryBalance}) is never mutated; the {@link Account} is the only mutated
 * model.</p>
 *
 * <p>Reverse-engineered solely from {@code app/cbl/CBACT04C.cbl}; strictly additive with no
 * build/runtime dependency on {@code app/}.</p>
 */
public final class InterestCalculationService {

    /**
     * Scale-2 zero used to (a) initialize and reset the per-account interest accumulator
     * ({@code WS-TOTAL-INT}, mirroring {@code MOVE 0 TO WS-TOTAL-INT} at L200) and (b) zero the two
     * account cycle fields during an account update ({@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} L353 and
     * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} L354).
     *
     * <p>Scale 2 is deliberate: because the loaded balances are carried at scale 2, keeping the zero
     * at scale 2 preserves scale-2 results so the downstream zoned-decimal encoder in
     * {@code io.AccountRepository} emits the {@code S9(10)V99} form correctly.</p>
     */
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    /**
     * Creates a stateless interest-calculation service. The class holds no instance fields, so this
     * no-arg constructor exists only so callers (e.g. {@code InterestCalculator}) can write
     * {@code new InterestCalculationService()}.
     */
    public InterestCalculationService() {
        // Intentionally empty — the service is stateless; all run state is local to process(...).
    }

    /**
     * Executes one interest-calculation run, faithfully porting the {@code CBACT04C}
     * {@code PROCEDURE DIVISION} business loop (L188-222).
     *
     * <p>The {@code driver} is iterated once, in order, as the COBOL program reads the TCATBAL file
     * sequentially in {@code 1000-TCATBALF-GET-NEXT} (L325-348). For each record the service detects
     * account breaks, updates the prior account, (re-)reads the current account and its card
     * cross-reference, looks up the disclosure-group interest rate, and &mdash; when the rate is
     * non-zero &mdash; computes the truncated monthly interest, accumulates it, and writes one
     * interest transaction. After the driver is exhausted, the final (last-seen) account is updated
     * (BR-12, L219-220) &mdash; a decision that is <b>Ratified</b>: COBOL's TEST-BEFORE
     * {@code PERFORM UNTIL} (L188-222) in fact leaves the source
     * {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} (L219-220) unreachable on the mainframe, yet posting
     * that last account is the accepted, signed-off contract of this port and must not be
     * "corrected" away. The full rationale &mdash; including why it is byte-neutral for the shipped
     * fixtures &mdash; is recorded in the ratified-decision note at that end-of-driver update site in
     * this method's body, and is summarized on {@link #updateAccount}.</p>
     *
     * <p>The service performs no file I/O of its own: it receives already-loaded repositories, an
     * already-iterable driver, and an already-open {@link TransactionWriter}. It does not call
     * {@link TransactionWriter#close()} nor {@code AccountRepository.writeUpdatedAccounts(...)};
     * those belong to {@code InterestCalculator}, invoked after this method returns. The account
     * {@code REWRITE} (L356) is realized by mutating the live {@link Account} instances the
     * repository handed out (see {@link #updateAccount}).</p>
     *
     * <p>No {@code throws} clause is declared because every collaborator failure is unchecked:
     * repository not-found conditions throw {@link RuntimeException} (porting the abend) and
     * {@link TransactionWriter#write} throws {@link java.io.UncheckedIOException}; the service lets
     * them propagate (BR-17).</p>
     *
     * @param driver            the TCATBAL driver records in sequential order; typed as
     *                          {@link Iterable} so a reader or a plain {@code List} may be supplied
     *                          (CBACT04C {@code 1000-TCATBALF-GET-NEXT}, L325-348). Must not be null.
     * @param accounts          random-access account repository; {@link AccountRepository#read}
     *                          ports {@code 1100-GET-ACCT-DATA} (L372-391) and returns the live
     *                          mutable {@link Account} retained by the repository. Must not be null.
     * @param xrefs             card cross-reference repository; {@link CardXrefRepository#byAccountId}
     *                          ports the alternate-key read of {@code 1110-GET-XREF-DATA} (L393-413).
     *                          Must not be null.
     * @param rates             disclosure-group rate repository; {@link DisclosureGroupRepository#lookup}
     *                          ports {@code 1200-GET-INTEREST-RATE} plus the DEFAULT fallback
     *                          {@code 1200-A} (L415-460). Must not be null.
     * @param txnWriter         open writer for the 350-byte interest transactions
     *                          ({@code 1300-B-WRITE-TX} {@code WRITE}, L500). Must not be null.
     * @param timestampSupplier injected DB2-format timestamp source ({@code Z-GET-DB2-FORMAT-TIMESTAMP},
     *                          L613-626); the only non-deterministic seam. Must not be null.
     * @param parmDate          the INTCALC {@code PARM-DATE} (COBOL {@code PIC X(10)}, e.g.
     *                          {@code "2022071800"}) used as the TRAN-ID prefix (L476-480). Must not
     *                          be null.
     */
    public void process(final Iterable<TransactionCategoryBalance> driver,
                        final AccountRepository accounts,
                        final CardXrefRepository xrefs,
                        final DisclosureGroupRepository rates,
                        final TransactionWriter txnWriter,
                        final Db2TimestampSupplier timestampSupplier,
                        final String parmDate) {

        // --- COBOL working-storage items, modeled as run-local variables (no instance state). ---

        // WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES (L167): the previous record's account id, used to
        // detect account breaks. "" is a sentinel that cannot equal any real 11-digit account id.
        String lastAcctNum = "";

        // WS-FIRST-TIME PIC X(01) VALUE 'Y' (L170): guards the very first account so no prior-account
        // update fires before an account has been loaded.
        boolean firstTime = true;

        // WS-TOTAL-INT PIC S9(09)V99 (L169): per-account accumulator of truncated monthly interest.
        BigDecimal totalInt = ZERO_AMOUNT;

        // WS-TRANID-SUFFIX PIC 9(06) VALUE 0 (L173): RUN-WIDE transaction counter, pre-incremented per
        // written transaction (first written transaction gets suffix 1). NOT reset per account.
        int suffix = 0;

        // WS-RECORD-COUNT PIC 9(09) VALUE 0 (L172): count of driver records processed (BR-01). Does not
        // affect either output file; maintained for fidelity with the COBOL counter.
        long recordCount = 0;

        // The account/xref for the current account id; (re-)read ONLY on an account break and reused
        // across all driver records of the same account (mirrors COBOL working-storage reuse).
        Account currentAccount = null;
        CardXref currentXref = null;

        // PERFORM UNTIL END-OF-FILE = 'Y' + 1000-TCATBALF-GET-NEXT (L188-193, L325-348): the enhanced-for
        // consumes the driver record-by-record; the (reader) iterable signals end-of-file by ending.
        for (final TransactionCategoryBalance tcb : driver) {
            recordCount++; // BR-01 ADD 1 TO WS-RECORD-COUNT (L192)

            // BR-02 account break: IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM (L194). The steps below run
            // in the EXACT COBOL order: update the PRIOR account (L196) BEFORE resetting the accumulator
            // (L200) and BEFORE reading the NEW account (L203).
            if (!tcb.acctId().equals(lastAcctNum)) {
                if (!firstTime) {
                    // BR-11 PERFORM 1050-UPDATE-ACCOUNT (L196): post the PRIOR account's accumulated
                    // total; currentAccount still references the PRIOR account at this point.
                    updateAccount(currentAccount, totalInt);
                } else {
                    // BR-03 MOVE 'N' TO WS-FIRST-TIME (L198): skip the update on the very first account.
                    firstTime = false;
                }
                // BR-04 MOVE 0 TO WS-TOTAL-INT (L200): reset the accumulator AFTER updating the prior
                // account and BEFORE reading the new one.
                totalInt = ZERO_AMOUNT;

                // MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM (L201).
                lastAcctNum = tcb.acctId();

                // BR-05 PERFORM 1100-GET-ACCT-DATA (L202-203): random account read; fatal if missing
                // (abend L383-390) — the RuntimeException propagates. Reassign to the NEW account.
                currentAccount = accounts.read(tcb.acctId());

                // BR-05 PERFORM 1110-GET-XREF-DATA (L204-205): alternate-key xref read; fatal if missing
                // (abend L405-412) — the RuntimeException propagates.
                currentXref = xrefs.byAccountId(tcb.acctId());
            }

            // BR-06 rate lookup for EVERY record (L210-213). The COBOL MOVEs CAT-CD (L211) before
            // TYPE-CD (L212), but the physical DISCGRP key layout (CVTRA02Y) is GROUP-ID + TYPE-CD +
            // CAT-CD; DisclosureGroupRepository.lookup takes them in that physical-key order. The DEFAULT
            // fallback (BR-08) happens inside lookup(...).
            final DisclosureGroup dg = rates.lookup(currentAccount.getGroupId(), tcb.typeCd(), tcb.catCd());
            final BigDecimal rate = dg.intRate();

            // BR-07 write guard on the RATE, not the balance: IF DIS-INT-RATE NOT = 0 (L214). A zero
            // balance with a non-zero rate STILL emits a 0.00 transaction; when the rate is zero we skip
            // entirely (no accrual, no transaction).
            if (rate.signum() != 0) {
                // PERFORM 1300-COMPUTE-INTEREST (L215) -> also PERFORM 1300-B-WRITE-TX (L468).
                // BR-09 COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 with NO ROUNDED
                // clause -> truncate to scale 2, RoundingMode.DOWN (L464-465).
                final BigDecimal monthlyInt = CobolArithmetic.monthlyInterest(tcb.balance(), rate);

                // BR-10 ADD WS-MONTHLY-INT TO WS-TOTAL-INT (L467): accumulate into the per-account total.
                totalInt = totalInt.add(monthlyInt);

                // --- 1300-B-WRITE-TX (L473-515): build and write one interest transaction. ---

                // BR-13 ADD 1 TO WS-TRANID-SUFFIX (L474): pre-increment so the first txn's suffix is 1;
                // the counter is run-wide (not reset per account).
                suffix++;

                // BR-13 STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID (L476-480):
                // 10-char PARM-DATE + 6-digit zero-padded suffix = 16-char TRAN-ID.
                final String tranId = parmDate + String.format("%06d", suffix);

                // BR-15 PERFORM Z-GET-DB2-FORMAT-TIMESTAMP (L496): obtain the 26-char DB2 timestamp ONCE
                // and use the SAME value for both TRAN-ORIG-TS (L497) and TRAN-PROC-TS (L498).
                final String ts = timestampSupplier.get();

                // BR-14 fixed transaction fields (L482-498). Constructor args are in the canonical
                // 13-field order of TransactionRecord (CVTRA05Y layout).
                final TransactionRecord record = new TransactionRecord(
                        tranId,                                       // 1  TRAN-ID (L476-480)
                        "01",                                         // 2  TRAN-TYPE-CD = '01' (L482)
                        "0005",                                       // 3  TRAN-CAT-CD 9(04): MOVE '05' -> '0005' (L483)
                        "System",                                     // 4  TRAN-SOURCE = 'System' (L484)
                        "Int. for a/c " + currentAccount.getAcctId(), // 5  TRAN-DESC = 'Int. for a/c ' + ACCT-ID (L485-489)
                        monthlyInt,                                   // 6  TRAN-AMT = WS-MONTHLY-INT, this record's interest (L490)
                        "000000000",                                  // 7  TRAN-MERCHANT-ID 9(09): MOVE 0 -> 9 zero digits (L491)
                        "",                                           // 8  TRAN-MERCHANT-NAME = SPACES; writer pads to 50 (L492)
                        "",                                           // 9  TRAN-MERCHANT-CITY = SPACES; writer pads to 50 (L493)
                        "",                                           // 10 TRAN-MERCHANT-ZIP = SPACES; writer pads to 10 (L494)
                        currentXref.cardNum(),                        // 11 TRAN-CARD-NUM = XREF-CARD-NUM (L495)
                        ts,                                           // 12 TRAN-ORIG-TS (L497)
                        ts);                                          // 13 TRAN-PROC-TS = same value (BR-15, L498)

                // WRITE FD-TRANFILE-REC FROM TRAN-RECORD (L500). A write failure is fatal in COBOL
                // (abend L510-513); TransactionWriter surfaces it as an unchecked UncheckedIOException,
                // which propagates.
                txnWriter.write(record);

                // BR-16 PERFORM 1400-COMPUTE-FEES (L216): empty stub in the source — intentional no-op.
                computeFees();
            }
        }

        // --- BR-12 final account update at end-of-driver — RATIFIED DECISION (do not "correct") ---
        //
        // Decision: this port performs the final account update AFTER the driver loop, guarded by the
        // firstTime flag — the port of the WS-FIRST-TIME guard IF WS-FIRST-TIME NOT = 'Y' at
        // app/cbl/CBACT04C.cbl:L195-199 (cleared by MOVE 'N' TO WS-FIRST-TIME, L198) — porting
        // ELSE PERFORM 1050-UPDATE-ACCOUNT at app/cbl/CBACT04C.cbl:L219-220 (AAP §0.6.3 BR-12 and the
        // §0.1.2 pipeline). It posts the LAST-seen account's accumulated total through
        // 1050-UPDATE-ACCOUNT (L350-356: ADD WS-TOTAL-INT TO ACCT-CURR-BAL L352, then MOVE 0 TO the
        // two cycle fields L353-354). The firstTime guard (WS-FIRST-TIME, L195-199) makes an EMPTY
        // driver a no-op (and prevents an NPE on the null currentAccount).
        //
        // Subtlety the decision resolves: the source driver loop (L188-222) is
        // PERFORM UNTIL END-OF-FILE = 'Y', which is TEST-BEFORE. Once 1000-TCATBALF-GET-NEXT
        // (L325-348) sets END-OF-FILE = 'Y' (L340), the inner IF END-OF-FILE = 'N' (L191) fails and the
        // re-tested loop condition ends the loop — so the outer ELSE PERFORM 1050-UPDATE-ACCOUNT
        // (L219-220) never executes on the mainframe. A strict-execution port would therefore leave the
        // LAST account's accumulated interest unposted.
        //
        // Effect on the shipped fixtures: byte-neutral. With the 50-record golden fixtures every
        // account accumulates 0.00 interest and both cycle fields are already zero, so the
        // updated-accounts output stays byte-identical to app/data/ASCII/acctdata.txt (SHA-256
        // c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260).
        //
        // Status: Ratified in PR review — this end-of-driver update is the accepted contract of the
        // port, not an open question. DO NOT remove it, guard it away, or "correct" it to the
        // unreachable-ELSE reading of L219-220. The regression lock is
        // InterestCalculationServiceTest.br12_finalUpdateAtEof, which drives non-zero accumulated
        // interest over non-zero cycle fields and so fails if this update is removed;
        // InterestCalculationGoldenMasterTest documents the decision but cannot detect its removal,
        // because the shipped fixtures make it byte-neutral (see "Effect on the shipped fixtures"
        // above and that class's fixture-characterization note).
        if (!firstTime) {
            updateAccount(currentAccount, totalInt);
        }
    }

    /**
     * Ports {@code 1050-UPDATE-ACCOUNT} (CBACT04C.cbl L350-356): applies the accumulated interest to
     * the account balance and zeroes the two current-cycle fields.
     *
     * <p>Mutates the supplied {@link Account} in place:</p>
     * <ul>
     *   <li>{@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} (L352) &rarr; {@code setCurrBal(getCurrBal() + totalInt)} (BR-11)</li>
     *   <li>{@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} (L353) &rarr; {@code setCurrCycCredit(0.00)}</li>
     *   <li>{@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} (L354) &rarr; {@code setCurrCycDebit(0.00)}</li>
     * </ul>
     *
     * <p><b>No file write here.</b> The COBOL {@code REWRITE FD-ACCTFILE-REC} (L356) is deferred to
     * {@code AccountRepository.writeUpdatedAccounts(...)}, which {@code InterestCalculator} invokes
     * after {@link #process} returns. Because the repository handed out (and retains) this very
     * {@link Account} instance, mutating it in memory is sufficient for the later rewrite to observe
     * the changes.</p>
     *
     * <p>This helper fires on every account break after the first (BR-11) and once more at
     * end-of-driver (BR-12) &mdash; even when {@code totalInt} is {@code 0.00}, in which case only the
     * cycle fields are zeroed. Because {@link #ZERO_AMOUNT} and the loaded balances are scale 2, the
     * result stays scale 2 for the downstream zoned-decimal encoder.</p>
     *
     * <p>That end-of-driver invocation is the <b>Ratified</b> BR-12 semantics: posting the last-seen
     * account's accumulated total after the loop is the accepted contract of this port (ratified in PR
     * review), even though COBOL's TEST-BEFORE {@code PERFORM UNTIL} (L188-222) leaves the source
     * {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} (L219-220) unreachable on the mainframe. It is
     * byte-neutral for the shipped fixtures and must not be removed or "corrected"; the
     * ratified-decision note at the call site in {@link #process} carries the full rationale.</p>
     *
     * @param account  the account to update in place (the prior/last account on the break); never null
     *                 when invoked because the {@code firstTime} guard ({@code WS-FIRST-TIME},
     *                 CBACT04C.cbl L195-199) precedes every call
     * @param totalInt the accumulated per-account monthly interest to post to the balance (scale 2)
     */
    private void updateAccount(final Account account, final BigDecimal totalInt) {
        // BR-11 ADD WS-TOTAL-INT TO ACCT-CURR-BAL (L352).
        account.setCurrBal(account.getCurrBal().add(totalInt));
        // MOVE 0 TO ACCT-CURR-CYC-CREDIT (L353).
        account.setCurrCycCredit(ZERO_AMOUNT);
        // MOVE 0 TO ACCT-CURR-CYC-DEBIT (L354).
        account.setCurrCycDebit(ZERO_AMOUNT);
        // REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD (L356) is deferred to
        // AccountRepository.writeUpdatedAccounts(...) (called by InterestCalculator after process()).
    }

    /**
     * Ports {@code 1400-COMPUTE-FEES} (CBACT04C.cbl L518-520). The source paragraph is an empty
     * "To be implemented" stub, so this is an intentional no-op (BR-16): no fee computation, no fee
     * transaction, and no balance side effect. It is retained (and performed inside the rate-non-zero
     * guard, mirroring {@code PERFORM 1400-COMPUTE-FEES} at L216) purely for traceability with the
     * source control flow.
     */
    private void computeFees() {
        // BR-16 1400-COMPUTE-FEES CBACT04C.cbl L518-520 — empty stub in source; intentional no-op.
    }
}

/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.interest;

import java.io.IOException;
import java.nio.file.Path;

import com.blitzy.carddemo.interest.io.AccountRepository;
import com.blitzy.carddemo.interest.io.CardXrefRepository;
import com.blitzy.carddemo.interest.io.DisclosureGroupRepository;
import com.blitzy.carddemo.interest.io.TransactionCategoryBalanceReader;
import com.blitzy.carddemo.interest.io.TransactionWriter;
import com.blitzy.carddemo.interest.service.InterestCalculationService;
import com.blitzy.carddemo.interest.support.Db2TimestampSupplier;

/**
 * Command-line entry point of the standalone interest-calculation module &mdash; the Java&nbsp;21
 * port of the COBOL batch program {@code CBACT04C} (interest calculator, run by the {@code INTCALC}
 * job).
 *
 * <p><strong>Ported from</strong> the {@code CBACT04C} {@code PROCEDURE DIVISION}
 * (<code>app/cbl/CBACT04C.cbl:L180-232</code>) plus the OPEN/CLOSE/abend paragraphs it performs, and
 * the {@code app/jcl/INTCALC.jcl} DD-to-dataset mapping (<code>app/jcl/INTCALC.jcl:L22-41</code>),
 * which defines the positional command-line argument contract below.</p>
 *
 * <h2>Role &mdash; thin CLI adapter (hexagonal-lite)</h2>
 * <p>This class is a <strong>thin adapter</strong>: it wires the file adapters ({@code io.*}) and the
 * injectable timestamp supplier ({@link Db2TimestampSupplier}) into the pure business-logic core
 * ({@link InterestCalculationService}) and drives the batch job. It contains <strong>no business
 * arithmetic</strong>; all decimal/truncation/overpunch logic lives in the {@code support} and
 * {@code io} packages, and the account-break / interest-accumulation loop
 * (<code>app/cbl/CBACT04C.cbl:L188-222</code>) belongs to {@link InterestCalculationService}
 * (AAP &sect;0.3.1). The layering is: CLI adapter &rarr; pure service &larr; file adapters.</p>
 *
 * <h2>CLI argument contract &mdash; 7 positional args</h2>
 * <p>Derived from the {@code INTCALC} DD names and the COBOL {@code SELECT ... ASSIGN} clauses
 * (AAP &sect;0.4.2). All seven arguments are required and positional:</p>
 * <pre>
 *   args[0] = tcatbal  input  (DD TCATBALF &rarr; tcatbal.txt,  50-byte driver records, OPEN INPUT)
 *   args[1] = cardxref input  (DD XREFFILE &rarr; cardxref.txt, card cross-reference, OPEN INPUT)
 *   args[2] = acctdata input  (DD ACCTFILE &rarr; acctdata.txt, 300-byte account master, OPEN I-O)
 *   args[3] = discgrp  input  (DD DISCGRP  &rarr; discgrp.txt,  50-byte disclosure-group rates)
 *   args[4] = interest-transactions OUTPUT (DD TRANSACT, 350-byte records, OPEN OUTPUT)
 *   args[5] = updated-accounts OUTPUT      (post-run rewritten ACCTFILE, 300-byte records)
 *   args[6] = PARM-DATE  (the INTCALC job uses '2022071800'; mirrors LINKAGE PARM-DATE PIC X(10))
 * </pre>
 *
 * <h2>Faithful-behavior notes (preserved exactly; do not "fix")</h2>
 * <ul>
 *   <li><strong>ACCTFILE is the only mutated file</strong> ({@code args[5]} output); TCATBAL, XREF
 *       and DISCGRP are read-only; TRANSACT ({@code args[4]}) is write-only. Per the binding
 *       resolution in AAP &sect;0.7, TCATBAL is <em>not</em> updated &mdash; there is no TCATBAL
 *       output.</li>
 *   <li><strong>PARM-DATE passthrough:</strong> {@code args[6]} is forwarded verbatim; CBACT04C
 *       performs no validation on it (the service uses it as the 10-char prefix of each
 *       {@code TRAN-ID}, <code>app/cbl/CBACT04C.cbl:L474-480</code>).</li>
 *   <li><strong>Determinism:</strong> the only non-deterministic input is the DB2 timestamp
 *       (<code>app/cbl/CBACT04C.cbl:L614</code>). It is injected through {@link Db2TimestampSupplier}
 *       so the golden-master test can supply a fixed value and obtain byte-exact output. Production
 *       uses {@link Db2TimestampSupplier#systemDefault()}.</li>
 * </ul>
 *
 * <p>Dependencies: the JDK only ({@link java.io.IOException}, {@link java.nio.file.Path}) plus the
 * sibling module packages. No third-party libraries are referenced anywhere under {@code main/}.</p>
 *
 * @see InterestCalculationService
 * @see Db2TimestampSupplier
 */
public final class InterestCalculator {

    /**
     * The exact number of positional command-line arguments required, mirroring the {@code INTCALC}
     * DD map plus the {@code PARM-DATE} (AAP &sect;0.4.2): four inputs, two outputs, and the date.
     */
    private static final int EXPECTED_ARG_COUNT = 7;

    // -------------------------------------------------------------------------------------------
    // Positional argument indices. Named constants keep the wiring in run(...) self-documenting and
    // guard against off-by-one errors when mapping args -> DD names -> COBOL FD ASSIGN targets.
    // -------------------------------------------------------------------------------------------

    /** {@code args[0]}: TCATBAL driver input (DD TCATBALF; OPEN INPUT, 1000-TCATBALF-GET-NEXT). */
    private static final int ARG_TCATBAL_IN = 0;
    /** {@code args[1]}: card cross-reference input (DD XREFFILE; OPEN INPUT, 1110-GET-XREF-DATA). */
    private static final int ARG_CARDXREF_IN = 1;
    /** {@code args[2]}: account master input (DD ACCTFILE; OPEN I-O, 1100-GET-ACCT-DATA). */
    private static final int ARG_ACCTDATA_IN = 2;
    /** {@code args[3]}: disclosure-group rate input (DD DISCGRP; OPEN INPUT, 1200-GET-INTEREST-RATE). */
    private static final int ARG_DISCGRP_IN = 3;
    /** {@code args[4]}: interest-transactions output (DD TRANSACT; OPEN OUTPUT, 1300-B WRITE, 350B). */
    private static final int ARG_TXN_OUT = 4;
    /** {@code args[5]}: updated-accounts output (post-run rewritten ACCTFILE; REWRITE 1050, 300B). */
    private static final int ARG_ACCT_OUT = 5;
    /** {@code args[6]}: PARM-DATE (LINKAGE PARM-DATE PIC X(10); the INTCALC job uses '2022071800'). */
    private static final int ARG_PARM_DATE = 6;

    /**
     * Exit status returned on invalid command-line usage (wrong argument count). Distinct from the
     * abend status so callers/scripts can tell a usage error from a processing failure.
     */
    private static final int EXIT_USAGE_ERROR = 2;

    /**
     * Exit status returned on a fatal processing failure &mdash; the Java analogue of the COBOL
     * {@code 9999-ABEND-PROGRAM} {@code CALL 'CEE3ABD'} (<code>app/cbl/CBACT04C.cbl:L628-632</code>).
     */
    private static final int EXIT_ABEND = 1;

    /**
     * Utility entry-point class; not instantiable. The batch is driven entirely through the static
     * {@link #main(String[])} / {@link #run(String[], Db2TimestampSupplier)} methods.
     */
    private InterestCalculator() {
        // No instances. All behavior is exposed via the static entry points.
    }

    /**
     * Production entry point. Validates the argument count, then delegates to the testable
     * {@link #run(String[], Db2TimestampSupplier)} seam using the production (wall-clock) timestamp
     * supplier. Fatal failures are surfaced as a non-zero process exit, mirroring the COBOL abend.
     *
     * <p><strong>Mirrors</strong> the {@code CBACT04C} program lifecycle: normal completion
     * (<code>app/cbl/CBACT04C.cbl:L232</code> {@code GOBACK}) yields the default zero exit, while a
     * fatal error routes through {@code 9999-ABEND-PROGRAM}
     * (<code>app/cbl/CBACT04C.cbl:L628-632</code>) &mdash; here a non-zero {@link System#exit(int)}.</p>
     *
     * @param args the seven positional arguments described in the class documentation
     */
    public static void main(String[] args) {
        // Usage guard: CBACT04C is invoked by INTCALC with a fixed DD set + PARM; the Java CLI
        // requires all seven positional arguments. A bad invocation is a usage error, NOT an abend,
        // so it exits with a distinct status and never throws an uncaught exception (no NPE/AIOOBE).
        if (args == null || args.length != EXPECTED_ARG_COUNT) {
            printUsage(args == null ? 0 : args.length);
            System.exit(EXIT_USAGE_ERROR);
            return; // Unreachable after System.exit; retained for compiler clarity.
        }

        try {
            // Delegate to the run(...) seam with the PRODUCTION timestamp supplier. This mirrors the
            // COBOL Z-GET-DB2-FORMAT-TIMESTAMP reading FUNCTION CURRENT-DATE (app/cbl/CBACT04C.cbl:L614);
            // Db2TimestampSupplier.systemDefault() derives the 26-char timestamp from the wall clock.
            run(args, Db2TimestampSupplier.systemDefault());
        } catch (IOException | RuntimeException fatal) {
            // 9999-ABEND-PROGRAM (app/cbl/CBACT04C.cbl:L628-632): DISPLAY 'ABENDING PROGRAM' then
            // CALL 'CEE3ABD'. The fatal-error paragraphs (account-not-found 1100 L383-390,
            // xref-not-found 1110 L405-412, default-group-missing 1200-A L452-459, and any I/O
            // failure) are thrown as (Unchecked)Exceptions by the io/service classes; main is the
            // single place they are caught, so the abend is observable as a non-zero process exit.
            System.err.println("ABENDING PROGRAM"); // mirrors DISPLAY 'ABENDING PROGRAM' (L629)
            System.err.println(
                    "CBACT04C interest calculation failed: " + fatal.getMessage());
            fatal.printStackTrace(System.err); // full diagnostic for the batch operator
            System.exit(EXIT_ABEND);
        }
    }

    /**
     * Testable orchestration seam &mdash; a faithful port of the {@code CBACT04C}
     * {@code PROCEDURE DIVISION} main flow (<code>app/cbl/CBACT04C.cbl:L180-232</code>).
     *
     * <p>This method performs no {@link System#exit(int)} and swallows no exceptions: it lets fatal
     * {@link RuntimeException}s (the Java analogue of {@code 9999-ABEND-PROGRAM}) and
     * {@link IOException}s propagate to the caller. {@link #main(String[])} invokes it with the
     * production supplier; the sibling golden-master test
     * ({@code com.blitzy.carddemo.interest.golden.InterestCalculationGoldenMasterTest}, a different
     * package) invokes it with a <em>fixed</em> {@link Db2TimestampSupplier} to neutralize the only
     * non-deterministic input and assert byte-exact output (business rule BR-15, AAP &sect;0.6.3).
     * It is therefore intentionally {@code public}.</p>
     *
     * <p><strong>Orchestration parity</strong> with the COBOL, in order:</p>
     * <ol>
     *   <li>Open/load the five files (<code>L182-186</code>): TCATBAL, XREF, DISCGRP, ACCTFILE, TRANSACT.</li>
     *   <li>Run the driver loop (<code>L188-222</code>) &mdash; delegated to the service.</li>
     *   <li>Emit the updated 300-byte account master &mdash; realizes {@code REWRITE FD-ACCTFILE-REC}
     *       (<code>1050-UPDATE-ACCOUNT L356</code>) and the {@code 9300-ACCTFILE-CLOSE} (<code>L227</code>).</li>
     *   <li>Close (flush) the 350-byte transaction writer &mdash; {@code 9400-TRANFILE-CLOSE}
     *       (<code>L228</code>, <code>L595</code>).</li>
     * </ol>
     *
     * @param args              the seven positional arguments (see class documentation); the caller
     *                          is responsible for having validated {@code args.length == 7}
     * @param timestampSupplier the DB2-timestamp source to inject (production wall-clock supplier
     *                          from {@code main}, or a fixed supplier from the golden-master test);
     *                          must be non-null
     * @throws IOException           if opening/loading an input or emitting an output file fails
     *                               (treated by {@code main} as a fatal abend)
     * @throws RuntimeException       if a fatal business/data error occurs (account-not-found,
     *                               xref-not-found, missing DEFAULT group, malformed record); these
     *                               are the Java analogue of {@code 9999-ABEND-PROGRAM}
     * @throws IllegalArgumentException if {@code timestampSupplier} is null
     */
    public static void run(String[] args, Db2TimestampSupplier timestampSupplier) throws IOException {
        if (timestampSupplier == null) {
            // The single injection seam must be present; a null supplier would abend on first write.
            throw new IllegalArgumentException("Db2TimestampSupplier must be non-null");
        }

        // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C' (app/cbl/CBACT04C.cbl:L181). Informational
        // only; it is written to stdout and does not affect either output file.
        System.out.println("START OF EXECUTION OF PROGRAM CBACT04C");

        // --- Open / load inputs, in the COBOL OPEN order L182-186 -----------------------------------

        // 0000-TCATBALF-OPEN (L182 / L234): OPEN INPUT TCATBAL-FILE. The sequential driver; records
        // are read on demand (1000-TCATBALF-GET-NEXT L325) in the exact file order the account-break
        // logic depends on.
        final TransactionCategoryBalanceReader driver =
                new TransactionCategoryBalanceReader(Path.of(args[ARG_TCATBAL_IN]));

        // 0100-XREFFILE-OPEN (L183 / L252): OPEN INPUT XREF-FILE. Loaded into an in-memory map keyed
        // by the alternate index XREF-ACCT-ID (1110-GET-XREF-DATA L393-413).
        final CardXrefRepository xrefs =
                CardXrefRepository.load(Path.of(args[ARG_CARDXREF_IN]));

        // 0200-DISCGRP-OPEN (L184 / L270): OPEN INPUT DISCGRP-FILE. Loaded into an in-memory map for
        // the rate lookup + DEFAULT-group fallback (1200/1200-A L415-460).
        final DisclosureGroupRepository rates =
                DisclosureGroupRepository.load(Path.of(args[ARG_DISCGRP_IN]));

        // 0300-ACCTFILE-OPEN (L185 / L289): OPEN I-O ACCOUNT-FILE. This is the ONLY mutated file: the
        // service posts accumulated interest to ACCT-CURR-BAL and zeroes the two cycle fields
        // (1050-UPDATE-ACCOUNT L352-354); the mutated set is emitted to args[5] below.
        final AccountRepository accounts =
                AccountRepository.load(Path.of(args[ARG_ACCTDATA_IN]));

        // 0400-TRANFILE-OPEN (L186 / L307): OPEN OUTPUT TRANSACT-FILE. The 350-byte interest
        // transactions are written during the loop (1300-B-WRITE-TX L500). try-with-resources
        // guarantees the writer is closed/flushed (9400-TRANFILE-CLOSE L228 / L595), even on abend.
        try (TransactionWriter txnWriter =
                     new TransactionWriter(Path.of(args[ARG_TXN_OUT]))) {

            // --- Run the business loop (L188-222), delegated to the pure service -------------------
            // The service owns the PERFORM UNTIL END-OF-FILE loop, the account-break logic (L194-206),
            // the DISCGRP key assembly + rate lookup + write-guard (L210-217), and the end-of-file
            // final 1050-UPDATE-ACCOUNT (L219-220). This adapter contributes NO business arithmetic.
            final InterestCalculationService service = new InterestCalculationService();
            service.process(
                    driver,                       // TCATBAL sequential driver (L188-193)
                    accounts,                     // ACCOUNT master, read + mutate (1100 / 1050)
                    xrefs,                        // card cross-reference by acct-id (1110)
                    rates,                        // disclosure-group rate lookup (1200)
                    txnWriter,                    // interest-transaction sink (1300-B)
                    timestampSupplier,            // injected DB2 timestamp (Z-GET... L613-626)
                    args[ARG_PARM_DATE]);         // PARM-DATE, forwarded verbatim (L476-480)

            // --- Emit the updated account master (300B) ---------------------------------------------
            // Realizes REWRITE FD-ACCTFILE-REC (1050-UPDATE-ACCOUNT L356) and the corresponding
            // 9300-ACCTFILE-CLOSE (L227). Writes ALL loaded accounts in original key/input order, with
            // the in-memory mutations applied to the processed accounts and untouched accounts written
            // unchanged. Placed before the writer's implicit close to mirror COBOL closing ACCTFILE
            // (L227) before TRANSACT (L228).
            accounts.writeUpdatedAccounts(Path.of(args[ARG_ACCT_OUT]));

        } // <- txnWriter.close(): 9400-TRANFILE-CLOSE (app/cbl/CBACT04C.cbl:L228 / L595) flushes output.

        // Note on the remaining COBOL CLOSE paragraphs: TCATBAL (9000-TCATBALF-CLOSE L224), XREF
        // (9100 L225) and DISCGRP (9200 L226) need no explicit OS close in Java -- the driver reads
        // eagerly/self-closing and the xref/rate repositories are fully in-memory after load(...).

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C' (app/cbl/CBACT04C.cbl:L230), then GOBACK
        // (L232) -> normal return (zero exit).
        System.out.println("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    /**
     * Prints a clear usage message to {@code System.err} enumerating all seven positional arguments.
     * Called only on an argument-count mismatch; the caller then exits with {@link #EXIT_USAGE_ERROR}.
     *
     * @param actualCount the number of arguments actually supplied (for the diagnostic line)
     */
    private static void printUsage(int actualCount) {
        System.err.println(
                "ERROR: expected " + EXPECTED_ARG_COUNT + " arguments but received " + actualCount + ".");
        System.err.println();
        System.err.println("Usage:");
        System.err.println(
                "  java -jar interest-calculation-1.0.0.jar \\");
        System.err.println(
                "       <tcatbal-in> <cardxref-in> <acctdata-in> <discgrp-in> \\");
        System.err.println(
                "       <interest-transactions-out> <updated-accounts-out> <PARM-DATE>");
        System.err.println();
        System.err.println("Positional arguments (all required, in order):");
        System.err.println("  1) tcatbal-in                 TCATBAL driver input   (50-byte records)");
        System.err.println("  2) cardxref-in                card cross-reference   (input)");
        System.err.println("  3) acctdata-in                account master         (300-byte records)");
        System.err.println("  4) discgrp-in                 disclosure-group rates (50-byte records)");
        System.err.println("  5) interest-transactions-out  generated transactions (350-byte records)");
        System.err.println("  6) updated-accounts-out       rewritten accounts     (300-byte records)");
        System.err.println("  7) PARM-DATE                  e.g. 2022071800 (10-char TRAN-ID prefix)");
    }
}

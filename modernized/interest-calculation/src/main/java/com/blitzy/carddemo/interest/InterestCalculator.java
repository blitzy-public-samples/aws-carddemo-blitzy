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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

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
 *       output. Because the {@code INTCALC} job assigns {@code TRANSACT} and {@code ACCTFILE} to
 *       <em>separate</em> datasets (and the four inputs to separate read-only datasets), a single
 *       file can never serve two DDs on the mainframe; the CLI enforces the same invariant by
 *       rejecting a run whose two output paths ({@code args[4]}, {@code args[5]}) resolve to the
 *       same file (see {@link #main(String[])}), which would otherwise let the 350-byte and 300-byte
 *       writers clobber each other and emit corrupt output.</li>
 *   <li><strong>PARM-DATE ({@code PIC X(10)}):</strong> {@code args[6]} models the COBOL
 *       {@code LINKAGE} field {@code PARM-DATE PIC X(10)} (<code>app/cbl/CBACT04C.cbl:L177-178</code>).
 *       Like any {@code PIC X(10)} field it is fixed at ten bytes, so the raw argument is coerced to
 *       exactly ten characters &mdash; left-justified and space-padded if shorter, truncated if
 *       longer &mdash; before use, exactly as a {@code MOVE} (or {@code LINKAGE} receive) into a
 *       {@code PIC X(10)} field would. CBACT04C performs no <em>explicit</em> validation; that fixed
 *       field width is the sole normalization and is preserved here so a non-ten-character argument
 *       cannot yield a malformed/misaligned {@code TRAN-ID}. The service then uses that ten-character
 *       value as the prefix of each 16-char {@code TRAN-ID} (<code>app/cbl/CBACT04C.cbl:L474-480</code>);
 *       the INTCALC job's {@code '2022071800'} is already ten characters and passes through unchanged.</li>
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
     * Fixed width of the COBOL {@code LINKAGE} field {@code PARM-DATE PIC X(10)}
     * (<code>app/cbl/CBACT04C.cbl:L177-178</code>). {@code args[6]} is coerced to exactly this many
     * characters (space-padded if shorter, truncated if longer) before it is used as the {@code TRAN-ID}
     * prefix (<code>app/cbl/CBACT04C.cbl:L474-480</code>), mirroring {@code PIC X(10)} field semantics.
     */
    private static final int PARM_DATE_WIDTH = 10;

    /**
     * Exit status returned on invalid command-line usage (wrong argument count, or two output paths
     * that resolve to the same file). Distinct from the abend status so callers/scripts can tell a
     * usage error from a processing failure.
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
            System.err.println(
                    "ERROR: expected " + EXPECTED_ARG_COUNT + " arguments but received "
                            + (args == null ? 0 : args.length) + ".");
            System.err.println();
            printUsage();
            System.exit(EXIT_USAGE_ERROR);
            return; // Unreachable after System.exit; retained for compiler clarity.
        }

        // Output-path safety guard: the INTCALC job maps TRANSACT (350-byte records) and ACCTFILE
        // (300-byte records) to DISTINCT datasets via separate DD statements, so a single OS file can
        // never back both DDs on the mainframe. The Java CLI, however, accepts free-form path strings,
        // so args[4] and args[5] could point at the same file -- and because BOTH output writers replace
        // the contents of the file they open (TransactionWriter / AccountRepository.writeUpdatedAccounts),
        // that alias lets them truncate and interleave each other's bytes, silently producing a corrupt
        // file while still exiting 0. The collision must be caught however it is spelled: identical text,
        // a symlinked parent directory, symlinked file names, or two hard links to one inode all name a
        // single physical file. Detect it BEFORE opening any file and reject it as a usage error
        // (exit 2), preserving the source's separate-DD invariant (AAP §0.7 minimal-change: the CLI
        // adapter must honor the DD-to-file contract). This is a usage error, NOT an abend.
        // This guard reads path NAMES, which a concurrent caller can still change after it returns, so
        // it is the fast, message-rich first line of defense rather than the only one: each writer
        // additionally claims an exclusive lock on the handle it actually opens, so an alias created
        // after this point is caught at open time and abends instead of corrupting a file (see
        // TransactionWriter's constructor and AccountRepository.writeUpdatedAccounts).
        final String outputPathError = findDuplicateOutputPaths(args);
        if (outputPathError != null) {
            System.err.println("ERROR: " + outputPathError);
            System.err.println();
            printUsage();
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
     *   <li>Open/load the five files (<code>L182-186</code>): TCATBAL, XREF, DISCGRP, ACCTFILE,
     *       TRANSACT. That order is observable on the <em>failure</em> path too, not only on the happy
     *       path: the TCATBAL {@code OPEN INPUT} status check runs first
     *       ({@link com.blitzy.carddemo.interest.io.TransactionCategoryBalanceReader#open()} at the
     *       <code>L182</code> position), so a run whose driver, cross-reference, rate or account input
     *       cannot be opened abends before the 350-byte TRANSACT output is created
     *       (<code>L186</code>) and therefore leaves no output artifact behind.</li>
     *   <li>Run the driver loop (<code>L188-222</code>) &mdash; delegated to the service.</li>
     *   <li>Emit the updated 300-byte account master &mdash; realizes {@code REWRITE FD-ACCTFILE-REC}
     *       (<code>1050-UPDATE-ACCOUNT L356</code>) and the {@code 9300-ACCTFILE-CLOSE} (<code>L227</code>).</li>
     *   <li>Close (flush) the 350-byte transaction writer &mdash; {@code 9400-TRANFILE-CLOSE}
     *       (<code>L228</code>, <code>L595</code>).</li>
     * </ol>
     *
     * @param args              the seven positional arguments (see class documentation); the caller
     *                          is responsible for having validated {@code args.length == 7} and that
     *                          the two output paths ({@code args[4]}, {@code args[5]}) resolve to
     *                          different files ({@link #main(String[])} enforces both before calling
     *                          this seam). {@code args[6]} is coerced here to the {@code PARM-DATE}
     *                          {@code PIC X(10)} field width before it drives {@code TRAN-ID} assembly.
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

        // 0000-TCATBALF-OPEN (L182 / L234-250): OPEN INPUT TCATBAL-FILE. The sequential driver; records
        // are read on demand (1000-TCATBALF-GET-NEXT L325) in the exact file order the account-break
        // logic depends on.
        final TransactionCategoryBalanceReader driver =
                new TransactionCategoryBalanceReader(Path.of(args[ARG_TCATBAL_IN]));

        // Perform the OPEN INPUT status check HERE, at the L182 position, before any other file is
        // touched. The COBOL open order is fixed -- TCATBAL first (L182), TRANSACT last (L186) -- so an
        // unavailable TCATBAL abends (L245 DISPLAY + L248 9999-ABEND-PROGRAM) before TRANSACT is ever
        // opened, and a failed run leaves NO interest-transactions dataset behind. The driver reads
        // lazily, so without this explicit check the first file access would happen inside the loop
        // below -- after the TransactionWriter (L186 position) had already created an empty 350-byte
        // output file that a downstream job could mistake for a legitimate "no interest to post" run.
        // This validates availability only; a failure discovered later while READING a record stays in
        // 1000-TCATBALF-GET-NEXT (L326-345), i.e. after TRANSACT is open, exactly as in the source.
        driver.open();

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
            // final 1050-UPDATE-ACCOUNT (L219-220) -- the ratified BR-12 decision, whose full rationale
            // is recorded at that update site in InterestCalculationService. This adapter contributes NO
            // business arithmetic.
            // PARM-DATE arrives as free-form text but the COBOL LINKAGE field is PARM-DATE PIC X(10)
            // (app/cbl/CBACT04C.cbl:L177-178), a fixed 10-byte field: a MOVE/receive left-justifies and
            // space-pads a shorter value and truncates a longer one to 10 bytes. Coerce here -- the Java
            // analogue of receiving the PARM into that PIC X(10) field -- so the service STRINGs a
            // guaranteed 10-char prefix in front of the 6-digit suffix to build a well-formed 16-char
            // TRAN-ID (L474-480). The INTCALC '2022071800' is already 10 chars, so this is byte-neutral
            // for well-formed input and does not perturb the golden-master output.
            final String parmDate = toPicX10(args[ARG_PARM_DATE]);

            final InterestCalculationService service = new InterestCalculationService();
            service.process(
                    driver,                       // TCATBAL sequential driver (L188-193)
                    accounts,                     // ACCOUNT master, read + mutate (1100 / 1050)
                    xrefs,                        // card cross-reference by acct-id (1110)
                    rates,                        // disclosure-group rate lookup (1200)
                    txnWriter,                    // interest-transaction sink (1300-B)
                    timestampSupplier,            // injected DB2 timestamp (Z-GET... L613-626)
                    parmDate);                    // PARM-DATE coerced to PIC X(10) (L177-178, L476-480)

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
     * Prints the usage body (invocation syntax + the seven positional arguments) to
     * {@code System.err}. This is the reusable tail shared by every usage-error path; each caller is
     * responsible for first printing its own specific {@code ERROR: ...} line describing WHY the
     * invocation was rejected (a wrong argument count, or two output paths that collide). Keeping the
     * cause line at the call site avoids emitting a self-contradictory message such as "expected 7
     * arguments but received 7" when the real fault is a duplicate output path rather than the count.
     * The caller exits with {@link #EXIT_USAGE_ERROR} after this returns.
     */
    private static void printUsage() {
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

    /**
     * Checks that the two <em>output</em> paths ({@code args[4]} = interest-transactions,
     * {@code args[5]} = updated-accounts) resolve to different files.
     *
     * <p><strong>Why this guard exists.</strong> On the mainframe the {@code INTCALC} job assigns
     * {@code TRANSACT} and {@code ACCTFILE} to two separate datasets via distinct DD statements, so a
     * single physical file can never back both DDs. The Java CLI instead accepts two free-form path
     * strings, so a caller can accidentally point both at the same file. That is unsafe: the two
     * writers use incompatible layouts (350-byte transaction records vs. 300-byte account records) and
     * each replaces the contents of the file it opens
     * ({@link com.blitzy.carddemo.interest.io.TransactionWriter} and
     * {@link com.blitzy.carddemo.interest.io.AccountRepository#writeUpdatedAccounts(Path)}), so
     * writing both to one path truncates/interleaves their bytes and yields a corrupt file. Rejecting
     * the collision up front restores the source's separate-DD invariant (AAP &sect;0.7 minimal-change:
     * the CLI adapter must honor the DD-to-file contract) and is a usage error, not a processing abend.</p>
     *
     * <p><strong>What this guard is, and is not.</strong> It inspects path <em>names</em>, and a name is
     * mutable: a symbolic link validated here can be retargeted at the other output before the writers
     * open it, so no amount of name canonicalization can make this check authoritative on its own. It is
     * therefore the fast, specific, pre-flight rejection &mdash; it reports the problem as a usage error
     * with an actionable message and creates nothing &mdash; while the authoritative check lives on the
     * opened handles: each writer takes an exclusive lock on the file it just opened before writing a
     * byte, so a collision this guard cannot see becomes a fatal abend at open time rather than a
     * corrupt file with a zero exit status.</p>
     *
     * <p><strong>How a collision is detected.</strong> Two argument strings can name one physical file
     * in several ways, and every one of them corrupts the output, so all are checked &mdash; in order,
     * cheapest first:</p>
     * <ol>
     *   <li><em>Lexical</em> &mdash; {@link Path#toAbsolutePath()} + {@link Path#normalize()} equality,
     *       which catches identical text and distinct spellings of one location ({@code out.txt} vs
     *       {@code ./out.txt}). This works even when neither file exists yet and needs no I/O.</li>
     *   <li><em>Physical identity</em> &mdash; {@link #physicalOutputKey(Path)} canonicalizes each path
     *       (following a symbolic-link chain on the file name to its end, however long, and resolving the
     *       parent directory with {@link Path#toRealPath(java.nio.file.LinkOption...)}), so a symlinked
     *       parent directory or a symlinked file name is recognized as the same target <em>before</em> the
     *       file is created &mdash; which matters because the outputs normally do not exist when the run
     *       starts.</li>
     *   <li><em>Same-file check</em> &mdash; when both resolved paths already exist,
     *       {@link Files#isSameFile(Path, Path)} compares the underlying file, catching aliases that no
     *       amount of path canonicalization can reveal: two <strong>hard links</strong> to one inode
     *       have two equally canonical names.</li>
     * </ol>
     * <p>A path-inspection {@link IOException} is not fatal here: the lexical check has already run and
     * the writers surface any genuine I/O problem as an abend, so the guard fails open rather than
     * rejecting a legitimate invocation because a directory could not be canonicalized.</p>
     *
     * <p>This is intentionally limited to the two <em>outputs</em>: it does not forbid an output from
     * equalling an input, because CBACT04C opens {@code ACCTFILE} I-O (read then rewrite the same
     * dataset) and {@code AccountRepository} likewise reads the whole account file into memory before
     * any output is written, so an in-place rewrite is a legitimate, source-faithful usage. A single
     * output written through a symbolic link also remains legitimate; only a <em>collision between the
     * two outputs</em> is rejected.</p>
     *
     * @param args the seven positional arguments (already validated to have length 7)
     * @return a human-readable diagnostic if the two output paths name the same file, or {@code null}
     *         if they are distinct
     */
    private static String findDuplicateOutputPaths(String[] args) {
        final Path txnArg = Path.of(args[ARG_TXN_OUT]);
        final Path acctArg = Path.of(args[ARG_ACCT_OUT]);

        // (1) Lexical: identical text or a different spelling of one location (out.txt vs ./out.txt).
        final Path txnOut = txnArg.toAbsolutePath().normalize();
        final Path acctOut = acctArg.toAbsolutePath().normalize();
        if (txnOut.equals(acctOut)) {
            return duplicateOutputMessage("both resolve to: " + txnOut);
        }

        try {
            // (2) Physical identity: collapses symlinked parents and symlinked file names, and works
            // before either file exists (the normal case -- the outputs are created by this run).
            final Path txnKey = physicalOutputKey(txnArg);
            final Path acctKey = physicalOutputKey(acctArg);
            if (txnKey.equals(acctKey)) {
                return duplicateOutputMessage("both resolve to: " + txnKey);
            }

            // (3) Same-file check: two hard links to one inode are two equally canonical names, so only
            // the file system can tell they are one file. Requires both files to exist already.
            if (Files.exists(txnKey) && Files.exists(acctKey) && Files.isSameFile(txnKey, acctKey)) {
                return duplicateOutputMessage(
                        "'" + txnKey + "' and '" + acctKey + "' are the same file "
                                + "(for example two hard links to one inode)");
            }
        } catch (IOException e) {
            // Canonicalization failed (an unreadable directory, a symlink loop, ...). The lexical check
            // above has already run; treat the paths as distinct and let the writers report any real
            // I/O failure as an abend, rather than rejecting a possibly valid invocation here.
        }
        return null;
    }

    /**
     * Builds the duplicate-output usage diagnostic, naming both argument positions so the operator can
     * see which two paths collided regardless of how the collision was detected.
     *
     * @param detail the collision-specific tail (which paths, and how they coincide)
     * @return the complete {@code ERROR: ...} body for {@link #main(String[])} to print
     */
    private static String duplicateOutputMessage(String detail) {
        return "the interest-transactions output (arg 5) and the updated-accounts output (arg 6) "
                + "must be different files, but " + detail;
    }

    /**
     * Reduces an output path to a canonical <em>physical</em> identity that two aliasing spellings share,
     * without requiring the file itself to exist.
     *
     * <p>{@link Path#toRealPath(java.nio.file.LinkOption...)} cannot be used directly on an output path:
     * it throws when the file does not exist, which is the normal case for both outputs. So the file
     * name and the directory are handled separately &mdash; a chain of symbolic links on the final
     * component is followed explicitly to its <em>end</em>, and the remaining parent directory, which
     * must already exist for the run to write anything, is canonicalized with {@code toRealPath()}. Two
     * paths reaching one file through different link spellings therefore produce the same key.</p>
     *
     * <p><strong>The link walk is not cut off at an arbitrary depth.</strong> Stopping early would return
     * a half-resolved key while the candidate is still a link, and two spellings of one file would then
     * be judged distinct &mdash; precisely the collision this guard exists to prevent. Termination is
     * instead guaranteed by remembering every path visited: a link chain of distinct entries is finite,
     * and a repeat means the chain is a cycle, which no process can open at all (the operating system
     * reports {@code ELOOP}), so the walk stops there and the writers surface the loop as an ordinary
     * I/O failure.</p>
     *
     * @param rawPath the output path exactly as supplied on the command line; must be non-null
     * @return a canonical key for the physical file the path designates
     * @throws IOException if a symbolic link or the parent directory cannot be resolved
     */
    private static Path physicalOutputKey(Path rawPath) throws IOException {
        Path candidate = rawPath.toAbsolutePath().normalize();

        // Follow symbolic links on the file name ourselves, all the way to the end of the chain: the
        // target may not exist yet (so toRealPath cannot do it) and two different link spellings of one
        // target must yield one key. The visited set makes the walk terminate without a hop limit -- a
        // repeated path is a cycle, i.e. a path that cannot be opened at all.
        final Set<Path> visited = new HashSet<>();
        visited.add(candidate);
        while (Files.isSymbolicLink(candidate)) {
            final Path linkTarget = Files.readSymbolicLink(candidate);
            final Path linkParent = candidate.getParent();
            final Path next = (linkTarget.isAbsolute() || linkParent == null
                    ? linkTarget
                    : linkParent.resolve(linkTarget)).toAbsolutePath().normalize();
            if (!visited.add(next)) {
                // Cyclic chain: unopenable by definition, so stop walking rather than spin. The writers
                // report the ELOOP; no output can be produced through such a path.
                break;
            }
            candidate = next;
        }

        // Canonicalize the directory part so a symlinked parent collapses to the real directory even
        // though the output file itself does not exist yet.
        final Path parent = candidate.getParent();
        if (parent != null && Files.exists(parent)) {
            return parent.toRealPath().resolve(candidate.getFileName());
        }
        return candidate;
    }

    /**
     * Coerces a raw {@code PARM-DATE} argument to the fixed width of the COBOL {@code LINKAGE} field
     * {@code PARM-DATE PIC X(10)} (<code>app/cbl/CBACT04C.cbl:L177-178</code>).
     *
     * <p>A COBOL {@code PIC X(10)} field is fixed at ten bytes: receiving (or {@code MOVE}-ing) a
     * shorter value left-justifies it and pads the remainder with spaces, and a longer value is
     * truncated to the leftmost ten bytes. This method reproduces exactly that, so the value the
     * service {@code STRING}s in front of the six-digit {@code WS-TRANID-SUFFIX} to form the 16-char
     * {@code TRAN-ID} (<code>app/cbl/CBACT04C.cbl:L474-480</code>) is always exactly ten characters.
     * A value that is already ten characters (the INTCALC job's {@code '2022071800'}) is returned
     * unchanged, so the coercion is byte-neutral for well-formed input.</p>
     *
     * @param raw the raw {@code args[6]} value; must be non-null (the caller validated the arg count)
     * @return {@code raw} normalized to exactly {@value #PARM_DATE_WIDTH} characters (space-padded on
     *         the right if shorter, truncated if longer), matching COBOL {@code PIC X(10)} semantics
     */
    private static String toPicX10(String raw) {
        final int len = raw.length();
        if (len == PARM_DATE_WIDTH) {
            // Already the exact field width -> byte-neutral (e.g. the INTCALC PARM '2022071800').
            return raw;
        }
        if (len > PARM_DATE_WIDTH) {
            // MOVE of a longer sending item into PIC X(10): keep the leftmost 10 bytes (truncate right).
            return raw.substring(0, PARM_DATE_WIDTH);
        }
        // MOVE of a shorter sending item into PIC X(10): left-justify and space-pad to 10 bytes.
        return raw + " ".repeat(PARM_DATE_WIDTH - len);
    }
}

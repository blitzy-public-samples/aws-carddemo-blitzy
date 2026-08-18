/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.blitzy.carddemo.interest.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.blitzy.carddemo.interest.model.Account;

/**
 * Account-master repository — ports the CBACT04C {@code ACCTFILE} I-O behavior.
 *
 * <p>Ports CBACT04C ACCTFILE I-O: {@code 1100-GET-ACCT-DATA} random read
 * ({@code app/cbl/CBACT04C.cbl} L372-391) + {@code 1050-UPDATE-ACCOUNT} balance
 * post &amp; {@code REWRITE} (L350-370). <b>ACCTFILE is the ONLY mutated file</b>
 * in CBACT04C (it alone is opened {@code I-O}; TCATBAL/XREF/DISCGRP are read-only
 * INPUT). Record = {@code CVACT01Y} ACCOUNT-RECORD (RECLN 300).
 *
 * <p>In the COBOL program {@code ACCOUNT-FILE} is an INDEXED, RANDOM-access VSAM
 * KSDS keyed on {@code FD-ACCT-ID}. This class reproduces the keyed-lookup
 * semantics with an in-memory {@link LinkedHashMap} loaded from the flat
 * {@code acctdata.txt} fixture in VSAM key order (= fixture order), serves random
 * reads by account id ({@link #read(String)}), and — mirroring the single
 * {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD} at L356 — emits an updated
 * 300-byte accounts file ({@link #writeUpdatedAccounts(Path)}).
 *
 * <h2>Byte-exact rewrite (why the raw line is retained)</h2>
 * <p>The {@link Account} model deliberately carries neither a raw-bytes field nor
 * the trailing COBOL {@code FILLER X(178)}. Guaranteeing that the rewritten file
 * round-trips byte-for-byte (only the mutated fields changing) is therefore
 * <em>this repository's</em> responsibility. To achieve it the repository retains
 * each record's original 300-character line and, on write, overlays ONLY the three
 * fields that {@code 1050-UPDATE-ACCOUNT} mutates
 * ({@code ACCT-CURR-BAL}, {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT})
 * back onto that raw line, leaving every other byte — dates, limits, {@code addrZip},
 * the blank {@code groupId}, and the {@code FILLER} — verbatim. Moreover each mutable
 * field is re-encoded ONLY when its live value actually differs from the value
 * originally read; an unmutated field keeps its original raw bytes verbatim. This is
 * required for strict sign fidelity: {@code support.ZonedDecimal.encode} canonicalizes
 * zero to a positive {@code '{'} overpunch, so blindly re-encoding an untouched field
 * that stored a degenerate negative-zero {@code '}'} (which decodes to {@code 0.00})
 * would change a byte; preserving the original bytes when the value is unchanged keeps a
 * no-mutation load&rarr;write byte-identical for <em>every</em> valid zoned-decimal input
 * (see {@link #writeUpdatedAccounts} and {@link #overlayMutableField}).
 *
 * <h2>Mutation discipline</h2>
 * <p>This class performs NO interest arithmetic and NO cycle-field zeroing. The
 * {@code service} layer applies {@code 1050-UPDATE-ACCOUNT} (L352-354) —
 * {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}, {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT},
 * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} — by calling the {@code Account} mutators.
 * Because {@link #read(String)} returns the live mutable instance held in the map,
 * those mutations are automatically reflected when {@link #writeUpdatedAccounts}
 * re-encodes the record. (For byte-exactness of the zeroed cycle fields the service
 * passes a scale-2 zero, e.g. {@code new BigDecimal("0.00")}, so
 * {@code encodeNumeric(..,12,2)} emits {@code 00000000000{} — the fixture's
 * positive-zero overpunch.)
 *
 * <p>Record layout (from {@code app/cpy/CVACT01Y.cpy}, RECLN 300; 0-based offsets;
 * corroborated against {@code app/data/ASCII/acctdata.txt}, 50 records of 300B):
 * <pre>
 *   #  Field            COBOL / PIC                       [offset,end)   Java
 *   1  acctId           ACCT-ID                9(11)      [  0, 11)      String  (map key)
 *   2  activeStatus     ACCT-ACTIVE-STATUS     X(01)      [ 11, 12)      String
 *   3  currBal          ACCT-CURR-BAL          S9(10)V99  [ 12, 24)      BigDecimal  MUTATED
 *   4  creditLimit      ACCT-CREDIT-LIMIT      S9(10)V99  [ 24, 36)      BigDecimal
 *   5  cashCreditLimit  ACCT-CASH-CREDIT-LIMIT S9(10)V99  [ 36, 48)      BigDecimal
 *   6  openDate         ACCT-OPEN-DATE         X(10)      [ 48, 58)      String
 *   7  expirationDate   ACCT-EXPIRAION-DATE    X(10)      [ 58, 68)      String  (source name misspelled)
 *   8  reissueDate      ACCT-REISSUE-DATE      X(10)      [ 68, 78)      String
 *   9  currCycCredit    ACCT-CURR-CYC-CREDIT   S9(10)V99  [ 78, 90)      BigDecimal  MUTATED->0
 *  10  currCycDebit     ACCT-CURR-CYC-DEBIT    S9(10)V99  [ 90,102)      BigDecimal  MUTATED->0
 *  11  addrZip          ACCT-ADDR-ZIP          X(10)      [102,112)      String  ("A000000000" in fixtures)
 *  12  groupId          ACCT-GROUP-ID          X(10)      [112,122)      String  (10 SPACES in fixtures)
 *     FILLER            —                      X(178)     [122,300)      —       (not modeled; verbatim)
 * </pre>
 * All five money fields are signed zoned decimal {@code S9(10)V99} (12 stored digits,
 * scale 2) decoded/encoded through {@code support.ZonedDecimal} via
 * {@code FixedWidthCodec}; the rest are fixed-width substrings. The {@code groupId}
 * column is blank (10 spaces) in every fixture row — this is what makes the assembled
 * DISCGRP key miss and trigger the DEFAULT-group fallback downstream, so it is
 * decoded straight from {@code [112,122)} and preserved verbatim (do NOT relocate it
 * with {@code addrZip}).
 *
 * <p>Reverse-engineered strictly from {@code app/cbl/CBACT04C.cbl}
 * ({@code 1100-GET-ACCT-DATA} L372-391, {@code 1050-UPDATE-ACCOUNT} L350-370, REWRITE
 * L356) and {@code app/cpy/CVACT01Y.cpy}; strictly additive — nothing under
 * {@code app/} is modified (AAP §0.7 traceability). Depends only on the JDK,
 * {@code model.Account}, and same-package {@code FixedWidthCodec}.
 */
public final class AccountRepository {

    // ---------------------------------------------------------------------
    // Record geometry — CVACT01Y ACCOUNT-RECORD (RECLN 300), 0-based offsets.
    // Widths/positions verified against app/cpy/CVACT01Y.cpy and the fixed-width
    // app/data/ASCII/acctdata.txt fixture (50 records, 300 bytes each).
    // ---------------------------------------------------------------------

    /** Fixed COBOL record length of ACCOUNT-RECORD (CVACT01Y RECLN 300). */
    private static final int RECORD_LENGTH = 300;

    /** Stored digit count of every signed money field {@code S9(10)V99} (10 + 2). */
    private static final int MONEY_DIGITS = 12;

    /** Implied fractional digits of every money field ({@code V99}). */
    private static final int MONEY_SCALE = 2;

    // Field offsets/widths (0-based [offset, offset+width)); money widths == MONEY_DIGITS.
    private static final int ACCT_ID_OFFSET = 0;            // ACCT-ID 9(11)
    private static final int ACCT_ID_WIDTH = 11;
    private static final int ACTIVE_STATUS_OFFSET = 11;     // ACCT-ACTIVE-STATUS X(01)
    private static final int ACTIVE_STATUS_WIDTH = 1;
    private static final int CURR_BAL_OFFSET = 12;          // ACCT-CURR-BAL S9(10)V99  (MUTATED)
    private static final int CREDIT_LIMIT_OFFSET = 24;      // ACCT-CREDIT-LIMIT S9(10)V99
    private static final int CASH_CREDIT_LIMIT_OFFSET = 36; // ACCT-CASH-CREDIT-LIMIT S9(10)V99
    private static final int OPEN_DATE_OFFSET = 48;         // ACCT-OPEN-DATE X(10)
    private static final int EXPIRATION_DATE_OFFSET = 58;   // ACCT-EXPIRAION-DATE X(10) (source misspelling)
    private static final int REISSUE_DATE_OFFSET = 68;      // ACCT-REISSUE-DATE X(10)
    private static final int CURR_CYC_CREDIT_OFFSET = 78;   // ACCT-CURR-CYC-CREDIT S9(10)V99 (MUTATED->0)
    private static final int CURR_CYC_DEBIT_OFFSET = 90;    // ACCT-CURR-CYC-DEBIT S9(10)V99 (MUTATED->0)
    private static final int ADDR_ZIP_OFFSET = 102;         // ACCT-ADDR-ZIP X(10)   ("A000000000")
    private static final int GROUP_ID_OFFSET = 112;         // ACCT-GROUP-ID X(10)   (blank/10 spaces)
    private static final int DATE_WIDTH = 10;               // shared X(10) width for dates/zip/group

    // ---------------------------------------------------------------------
    // State — two order-preserving maps keyed by ACCT-ID in VSAM key order
    // (= fixture load order). accountsById holds the live mutable models the
    // service updates; rawById holds each original 300-char line so the rewrite
    // can round-trip every unmodelled byte (FILLER, dates, blank groupId, ...).
    // ---------------------------------------------------------------------

    /** ACCT-ID -> live mutable {@link Account}; iteration order == load order. */
    private final Map<String, Account> accountsById = new LinkedHashMap<>();

    /** ACCT-ID -> original 300-char record line; iteration order == load order. */
    private final Map<String, String> rawById = new LinkedHashMap<>();

    /**
     * Private constructor — instances are created only through the {@link #load(Path)}
     * factory, which populates the two maps. Mirrors the COBOL {@code OPEN INPUT/I-O}
     * step that makes ACCTFILE available before any {@code READ}/{@code REWRITE}.
     */
    private AccountRepository() {
        // Intentionally empty; state is populated by load(...).
    }

    /**
     * Loads the account master ({@code acctdata.txt}, 300-byte records) in file
     * order, parsing each record into an {@link Account} and retaining the original
     * raw line for a byte-exact rewrite.
     *
     * <p>Reproduces the effect of the COBOL {@code OPEN} of {@code ACCOUNT-FILE}
     * (INDEXED, RANDOM, KEY {@code FD-ACCT-ID}) plus a full load of the VSAM cluster
     * into keyed storage: each line becomes a map entry keyed by {@code ACCT-ID}, and
     * insertion order (VSAM key order = fixture order) is preserved by
     * {@link LinkedHashMap}. Every record must be exactly {@value #RECORD_LENGTH}
     * characters; a short/long line is a malformed fixture and is fatal.
     *
     * @param path filesystem path to the fixed-width account master file
     *             (CLI {@code args[2]}: {@code ACCTFILE} -> {@code app/data/ASCII/acctdata.txt})
     * @return a populated, read-and-rewrite-ready {@code AccountRepository}
     * @throws UncheckedIOException  if the file cannot be read (fatal, wrapping {@link IOException})
     * @throws IllegalStateException if any record is not exactly {@value #RECORD_LENGTH} characters
     */
    public static AccountRepository load(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("account master path must be non-null");
        }
        final AccountRepository repository = new AccountRepository();

        // US-ASCII, LF-delimited fixtures: readAllLines strips the line terminators and
        // does NOT emit a trailing empty element for the file's final newline, so a
        // well-formed acctdata.txt yields exactly its 50 records.
        final List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            // Fatal: cannot open/read ACCTFILE. Mirrors the abend semantics of a failed OPEN.
            throw new UncheckedIOException("Failed to read account master file: " + path, e);
        }

        int lineNumber = 0;
        for (final String record : lines) {
            lineNumber++;
            // Guard the copybook record length up-front: a mis-sized line would silently
            // mis-frame every following field, so treat it as a fatal malformed fixture.
            if (record.length() != RECORD_LENGTH) {
                throw new IllegalStateException(
                        "Malformed ACCOUNT-RECORD at line " + lineNumber + " of " + path
                                + ": expected " + RECORD_LENGTH + " chars but was " + record.length());
            }
            final Account account = parse(record);
            // Key by ACCT-ID; retain BOTH the live model and the original raw bytes.
            repository.accountsById.put(account.getAcctId(), account);
            repository.rawById.put(account.getAcctId(), record);
        }
        return repository;
    }

    /**
     * Random read by account id — ports {@code 1100-GET-ACCT-DATA} (L372-391).
     *
     * <p>Returns the <em>live, mutable</em> {@link Account} instance held in the map, so
     * any {@code setCurrBal}/{@code setCurrCycCredit}/{@code setCurrCycDebit} the service
     * applies (per {@code 1050-UPDATE-ACCOUNT}) is reflected at write time.
     *
     * <p>missing account -&gt; 9999-ABEND-PROGRAM equivalent: fatal (L383-390). The COBOL
     * {@code READ ... INVALID KEY} sets a non-{@code '00'} status, which drives
     * {@code PERFORM 9999-ABEND-PROGRAM} ({@code CALL 'CEE3ABD'}). A missing key is a
     * fatal error here — NOT a fallback — surfaced as a {@link RuntimeException} whose
     * message mirrors the program's {@code DISPLAY 'ACCOUNT NOT FOUND: '} (L375).
     *
     * @param acctId the account id key ({@code ACCT-ID}, {@code FD-ACCT-ID})
     * @return the live mutable {@link Account} for {@code acctId}
     * @throws RuntimeException if no account exists for {@code acctId} (fatal, mirrors abend)
     */
    public Account read(String acctId) {
        final Account account = accountsById.get(acctId);
        if (account == null) {
            // INVALID KEY at 1100-GET-ACCT-DATA -> 9999-ABEND-PROGRAM (L383-390): fatal.
            throw new RuntimeException("ACCOUNT NOT FOUND: " + acctId);
        }
        return account;
    }

    /**
     * Emits the updated account master as a 300-byte-per-record file — realizes
     * {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD} (L356) as the
     * updated-accounts output artifact (CLI {@code args[5]}).
     *
     * <p>REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD (L356): overlay
     * {@code currBal}/{@code currCycCredit}/{@code currCycDebit} on the original 300
     * bytes; all other bytes verbatim. For each loaded account the original raw line is
     * taken and each of the three {@code 1050-UPDATE-ACCOUNT}-mutated field slices is
     * re-encoded from the in-memory model (via
     * {@code FixedWidthCodec.encodeNumeric(value, 12, 2)}) ONLY when its live value differs
     * from the value originally read; an unchanged field keeps its original raw bytes
     * verbatim (see {@link #overlayMutableField}). Dates, limits, {@code addrZip}, the blank
     * {@code groupId}, and the trailing {@code FILLER} are always copied unchanged.
     * Overlaying only genuinely-changed fields preserves every valid overpunch encoding
     * (including a stored negative-zero {@code '}'}) for accounts the service never touched,
     * so a no-mutation load&rarr;write round-trips byte-for-byte.
     *
     * <p><b>Order &amp; completeness:</b> CBACT04C only {@code REWRITE}s accounts that
     * appear in the TCATBAL driver, but the module contract (AAP §0.3.1) is to emit ALL
     * loaded accounts in original VSAM key order to produce the complete updated-accounts
     * file: untouched accounts are written byte-identically, touched accounts reflect the
     * service's mutations. Each record is followed by a single LF ({@code "\n"}, no CR);
     * output is US-ASCII and truncates any existing file.
     *
     * <p><b>The opened file is claimed exclusively, and only then truncated.</b> The
     * {@code INTCALC} job gives ACCTFILE and TRANSACT separate DD statements, so one physical
     * file can never back both; on the Java side both output writers open a caller-supplied
     * path, and a path can be made to alias another one <em>after</em> the CLI has validated it
     * (a symlink retargeted between validation and open). Comparing path names therefore cannot
     * be the last line of defense. This method takes an exclusive {@link FileLock} on the
     * <em>handle it just opened</em>, so the identity that is checked is the identity that is
     * written: the run's {@code TransactionWriter} still holds its own claim while this method
     * runs, so if both paths resolve to one physical file &mdash; through any spelling, symlink
     * or hard link &mdash; this lock attempt fails and the run abends instead of interleaving
     * 300-byte records into the 350-byte transaction stream. Truncation happens only after the
     * lock is held, so a rejected collision never destroys the bytes of the file it collided
     * with.
     *
     * @param out filesystem path for the updated 300-byte account master output
     * @throws UncheckedIOException  if the file cannot be written, is already held as another
     *                               output of this run, or is locked by another process (fatal,
     *                               wrapping {@link IOException})
     * @throws IllegalStateException if an overlay would change a record's length (framing bug)
     */
    public void writeUpdatedAccounts(Path out) {
        if (out == null) {
            throw new IllegalArgumentException("output path must be non-null");
        }
        // CREATE + WRITE: create-or-overwrite, matching an OUTPUT open. TRUNCATE_EXISTING is
        // deliberately NOT used -- the exclusive claim below must run BEFORE any content is
        // destroyed, so a refused collision leaves the colliding file's bytes intact.
        FileChannel opened = null;
        try {
            opened = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            // Bind this run's updated-ACCTFILE identity to this very handle (see the method javadoc).
            claimExclusively(opened, out);
            if (opened.size() > 0L) {
                // Now that the file is provably ours, realize the replace-contents semantics.
                opened.truncate(0L);
            }
            writeAllRecords(new BufferedWriter(new OutputStreamWriter(
                    Channels.newOutputStream(opened), StandardCharsets.US_ASCII)));
        } catch (IOException e) {
            // Fatal: cannot write the rewritten ACCTFILE. Mirrors the abend on a failed REWRITE.
            final UncheckedIOException failure =
                    new UncheckedIOException("Failed to write updated account master file: " + out, e);
            releaseOnFailure(opened, failure);
            throw failure;
        } catch (RuntimeException e) {
            // A refused claim (collision / foreign lock) or an overlay framing bug is already a fatal
            // diagnostic; do not mask it, but release the handle this method opened before it propagates.
            releaseOnFailure(opened, e);
            throw e;
        }
    }

    /**
     * Writes every loaded account as one 300-byte record to an already-opened, already-claimed sink,
     * then closes it. Split out of {@link #writeUpdatedAccounts(Path)} only so the open-claim-truncate
     * sequence and the record-framing loop each read as one unit; the framing behavior is unchanged.
     *
     * <p>REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD (L356): the original raw line is emitted with
     * only the three {@code 1050-UPDATE-ACCOUNT}-mutated field slices re-encoded, and only when their
     * live values differ from the values originally read (see {@link #overlayMutableField}).
     *
     * @param sink the character sink to write to and close; must be positioned at offset 0
     * @throws IOException           if a record cannot be written or the sink cannot be closed
     * @throws IllegalStateException if an overlay would change a record's length (framing bug)
     */
    private void writeAllRecords(BufferedWriter sink) throws IOException {
        try (BufferedWriter writer = sink) {

            // Iterate in load order (LinkedHashMap) so the output preserves VSAM key order.
            for (final Map.Entry<String, Account> entry : accountsById.entrySet()) {
                final Account account = entry.getValue();
                final String original = rawById.get(entry.getKey());

                // 1050-UPDATE-ACCOUNT mutates ONLY three fields (L352-354): ACCT-CURR-BAL,
                // ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT. Overlay a field's slice ONLY when the
                // live in-memory value actually differs from the value originally read; otherwise keep
                // the ORIGINAL raw bytes verbatim (see overlayMutableField). This preserves every valid
                // overpunch encoding for fields the service never touched -- including a stored
                // negative-zero '}' that decodes to 0.00 (which ZonedDecimal.encode would otherwise
                // canonicalize to positive '{') -- so a no-mutation load->write round-trips
                // byte-for-byte (R1 sign fidelity). The pre-image is always decoded from `original`
                // (never the partially-overlaid `record`), and the three fields are non-overlapping.
                String record = original;
                record = replace(record, CURR_BAL_OFFSET,
                        overlayMutableField(original, CURR_BAL_OFFSET, account.getCurrBal()));
                record = replace(record, CURR_CYC_CREDIT_OFFSET,
                        overlayMutableField(original, CURR_CYC_CREDIT_OFFSET, account.getCurrCycCredit()));
                record = replace(record, CURR_CYC_DEBIT_OFFSET,
                        overlayMutableField(original, CURR_CYC_DEBIT_OFFSET, account.getCurrCycDebit()));

                writer.write(record);
                writer.write("\n"); // LF-only record terminator, matching the fixtures.
            }
        }
    }

    /**
     * Takes an exclusive whole-file {@link FileLock} on an <em>already opened</em> output channel, so
     * the file this method will write is provably not the file the run's other output is writing.
     * Mirrors the identical claim taken by {@code TransactionWriter}'s constructor for the 350-byte
     * TRANSACT side, with the diagnostic worded for the updated-ACCTFILE side.
     *
     * <p>The lock is held on behalf of the whole JVM and the JVM's lock table is keyed by the
     * underlying <em>physical file</em>, not by the path spelling. An attempt to lock a file this
     * process already holds therefore fails with {@link OverlappingFileLockException} no matter how it
     * was addressed &mdash; a different string, a symlink, a symlink chain, a symlinked parent, or a
     * hard link &mdash; and, crucially, no matter what happened to those names after the CLI validated
     * them. That is what makes this check immune to the validate-then-open gap that pure path
     * comparison leaves open.</p>
     *
     * <p>The returned lock is deliberately not retained: {@link FileLock} stays held until the channel
     * that acquired it is closed, and that channel is closed by the writer layered over it once the
     * records have been flushed.</p>
     *
     * @param channel the freshly opened output channel to claim; must be open and writable
     * @param out     the path the channel was opened from, for diagnostics only
     * @throws UncheckedIOException if this run already holds the same physical file as an output, or
     *                              another process holds a conflicting lock on it
     * @throws IOException          if the lock cannot be attempted at all
     */
    private static void claimExclusively(FileChannel channel, Path out) throws IOException {
        final FileLock claim;
        try {
            claim = channel.tryLock();
        } catch (OverlappingFileLockException alreadyOursForSomethingElse) {
            throw new UncheckedIOException(
                    "Refusing to write the updated ACCTFILE output: " + out + " is the same physical "
                            + "file as another output of this run; the updated ACCTFILE (300-byte "
                            + "records) and TRANSACT (350-byte records) must be separate datasets",
                    new FileSystemException(
                            out.toString(), null, "already claimed as another output of this run"));
        }
        if (claim == null) {
            throw new UncheckedIOException(
                    "Refusing to write the updated ACCTFILE output: " + out
                            + " is locked by another process",
                    new FileSystemException(out.toString(), null, "locked by another process"));
        }
    }

    /**
     * Closes a channel a failed write had already opened, recording any close failure as a suppressed
     * exception on the failure being propagated so no diagnostic is lost and no handle leaks. Closing
     * an already-closed channel is a no-op, so this is safe on the paths where the layered writer has
     * itself already closed it.
     *
     * @param channel the channel to release; may be {@code null} if the open itself failed
     * @param failure the exception about to be thrown, which collects any close failure
     */
    private static void releaseOnFailure(FileChannel channel, RuntimeException failure) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Number of accounts currently loaded (VSAM records). Convenience accessor for
     * callers and tests; equals the number of records read by {@link #load(Path)}.
     *
     * @return the count of loaded accounts
     */
    public int size() {
        return accountsById.size();
    }

    /**
     * Parses one 300-character ACCOUNT-RECORD into an {@link Account}, in the exact
     * field order of {@code app/cpy/CVACT01Y.cpy}.
     *
     * <p>String fields ({@code X(n)}, and the unsigned {@code 9(11)} key) are taken as
     * fixed-width substrings via {@code FixedWidthCodec.slice}; the five signed money
     * fields ({@code S9(10)V99}) are decoded via {@code FixedWidthCodec.decodeNumeric}
     * (overpunch sign + implied {@code V99} decimal, scale 2). Offsets are the CVACT01Y
     * positions; note {@code expirationDate} maps the source's misspelled
     * {@code ACCT-EXPIRAION-DATE}, and {@code groupId} {@code [112,122)} is read straight
     * (blank in fixtures) — it is NOT swapped with {@code addrZip} {@code [102,112)}
     * ({@code "A000000000"}).
     *
     * @param record the raw 300-character record (already length-validated by the caller)
     * @return the parsed {@link Account} (money fields at scale 2)
     */
    private static Account parse(String record) {
        final String acctId = FixedWidthCodec.slice(record, ACCT_ID_OFFSET, ACCT_ID_WIDTH);
        final String activeStatus = FixedWidthCodec.slice(record, ACTIVE_STATUS_OFFSET, ACTIVE_STATUS_WIDTH);
        final BigDecimal currBal =
                FixedWidthCodec.decodeNumeric(record, CURR_BAL_OFFSET, MONEY_DIGITS, MONEY_SCALE);
        final BigDecimal creditLimit =
                FixedWidthCodec.decodeNumeric(record, CREDIT_LIMIT_OFFSET, MONEY_DIGITS, MONEY_SCALE);
        final BigDecimal cashCreditLimit =
                FixedWidthCodec.decodeNumeric(record, CASH_CREDIT_LIMIT_OFFSET, MONEY_DIGITS, MONEY_SCALE);
        final String openDate = FixedWidthCodec.slice(record, OPEN_DATE_OFFSET, DATE_WIDTH);
        // ACCT-EXPIRAION-DATE: the copybook field name is misspelled; only the byte
        // position [58,68) is contractual (the Java field uses the corrected spelling).
        final String expirationDate = FixedWidthCodec.slice(record, EXPIRATION_DATE_OFFSET, DATE_WIDTH);
        final String reissueDate = FixedWidthCodec.slice(record, REISSUE_DATE_OFFSET, DATE_WIDTH);
        final BigDecimal currCycCredit =
                FixedWidthCodec.decodeNumeric(record, CURR_CYC_CREDIT_OFFSET, MONEY_DIGITS, MONEY_SCALE);
        final BigDecimal currCycDebit =
                FixedWidthCodec.decodeNumeric(record, CURR_CYC_DEBIT_OFFSET, MONEY_DIGITS, MONEY_SCALE);
        // addrZip [102,112) holds "A000000000" and groupId [112,122) is blank (10 spaces)
        // in every fixture row — decoded straight from their own slices, NOT relocated.
        final String addrZip = FixedWidthCodec.slice(record, ADDR_ZIP_OFFSET, DATE_WIDTH);
        final String groupId = FixedWidthCodec.slice(record, GROUP_ID_OFFSET, DATE_WIDTH);

        return new Account(
                acctId,
                activeStatus,
                currBal,
                creditLimit,
                cashCreditLimit,
                openDate,
                expirationDate,
                reissueDate,
                currCycCredit,
                currCycDebit,
                addrZip,
                groupId);
    }

    /**
     * Returns the {@value #MONEY_DIGITS}-char field text to write at {@code offset} for one of the
     * three mutable money fields, realizing the byte-exact overlay rule of {@code REWRITE
     * FD-ACCTFILE-REC} (app/cbl/CBACT04C.cbl:L356) with strict sign fidelity.
     *
     * <p>The field is re-encoded from the live in-memory {@code current} value ONLY when it actually
     * differs from the value originally read (numeric comparison via {@link BigDecimal#compareTo});
     * otherwise the <em>original raw bytes</em> of that slice are returned unchanged. This matters
     * because {@code support.ZonedDecimal.encode} canonicalizes zero to a positive {@code '{'}
     * overpunch: a field the service never mutated whose stored value is a degenerate negative-zero
     * {@code '}'} (which decodes to {@code 0.00}) must be preserved byte-for-byte, not rewritten to
     * {@code '{'}. Overlaying only genuinely-changed fields therefore keeps a no-mutation
     * load&rarr;write round-trip byte-identical for <em>every</em> valid zoned-decimal input (R1 sign
     * fidelity), while a field the service did change (e.g. {@code ACCT-CURR-BAL} after posting
     * interest, or a cycle field zeroed by {@code 1050-UPDATE-ACCOUNT} L352-354) is re-encoded from the
     * mutated value via {@link FixedWidthCodec#encodeNumeric(BigDecimal, int, int)}.</p>
     *
     * @param original the untouched original 300-char record (pre-image source of value and bytes)
     * @param offset   the 0-based start of the money field within the record
     * @param current  the live, possibly-mutated in-memory value of that field
     * @return the original raw slice when {@code current} is unchanged, else the re-encoded field
     */
    private static String overlayMutableField(String original, int offset, BigDecimal current) {
        // Pre-image value as originally read (decoded from the retained raw bytes at this offset).
        final BigDecimal originalValue =
                FixedWidthCodec.decodeNumeric(original, offset, MONEY_DIGITS, MONEY_SCALE);
        if (current.compareTo(originalValue) == 0) {
            // Unchanged by the service -> keep the ORIGINAL bytes verbatim (do NOT re-encode; this
            // preserves e.g. a stored negative-zero '}' that encode() would canonicalize to '{').
            return original.substring(offset, offset + MONEY_DIGITS);
        }
        // Changed by 1050-UPDATE-ACCOUNT (L352-354) -> re-encode from the mutated in-memory value.
        return FixedWidthCodec.encodeNumeric(current, MONEY_DIGITS, MONEY_SCALE);
    }

    /**
     * Overlays {@code field} onto {@code record} starting at {@code offset}, returning a
     * new record of identical length (the surrounding bytes are preserved verbatim).
     *
     * <p>Used by {@link #writeUpdatedAccounts} to splice a freshly-encoded 12-digit money
     * field back into the original 300-character line. The post-condition asserts the
     * record length is unchanged, guarding against an off-by-one framing bug.
     *
     * @param record the original fixed-width record
     * @param offset the 0-based start position of the field to overlay
     * @param field  the replacement field text (must equal the original field width)
     * @return the record with {@code [offset, offset+field.length())} replaced by {@code field}
     * @throws IllegalStateException if the overlay changes the record length
     */
    private static String replace(String record, int offset, String field) {
        final String result =
                record.substring(0, offset) + field + record.substring(offset + field.length());
        if (result.length() != record.length()) {
            // Off-by-one framing guard: an overlay must never resize the fixed-width record.
            throw new IllegalStateException(
                    "overlay at offset " + offset + " changed record length: expected "
                            + record.length() + " but got " + result.length());
        }
        return result;
    }
}

package com.blitzy.carddemo.interest.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.blitzy.carddemo.interest.model.DisclosureGroup;

/**
 * Interest-rate lookup with DEFAULT-group fallback for the CBACT04C interest-calculation port.
 *
 * <p>Ports CBACT04C {@code 1200-GET-INTEREST-RATE} + {@code 1200-A-GET-DEFAULT-INT-RATE}
 * ({@code app/cbl/CBACT04C.cbl:L415-460}). DISCGRP composite-key read; DEFAULT-group fallback on
 * status {@code '23'}; record = {@code CVTRA02Y} DIS-GROUP-RECORD (RECLN 50).</p>
 *
 * <h2>What the COBOL does</h2>
 * <p>CBACT04C opens the DISCGRP VSAM KSDS {@code OPEN INPUT} (read-only,
 * {@code app/cbl/CBACT04C.cbl:L272}) and, for every driver record, reads it by the composite
 * {@code FD-DISCGRP-KEY} to obtain the annual interest rate ({@code DIS-INT-RATE}). If the specific
 * group key is not present the VSAM read returns file status {@code '23'} (record-not-found), and
 * the program retries the read after {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}
 * ({@code L437}); if <em>that</em> DEFAULT read also fails the program abends via
 * {@code 9999-ABEND-PROGRAM} ({@code L452-459}). This is business rule <strong>BR-08</strong>: the
 * exact-group match versus the DEFAULT-group fallback is a genuine two-tier lookup and must not be
 * collapsed.</p>
 *
 * <h2>How the Java models it (Repository pattern, AAP &sect;0.3.3)</h2>
 * <p>VSAM random keyed access is modeled as an in-memory {@link Map} keyed by the 16-character
 * composite key {@code DIS-ACCT-GROUP-ID(10) + DIS-TRAN-TYPE-CD(2) + DIS-TRAN-CAT-CD(4)}. A
 * {@link LinkedHashMap} is used so iteration/deterministic build order mirrors the fixture's
 * physical record order. {@link #load(Path)} frames each fixed-width 50-byte record via
 * {@link FixedWidthCodec} (same package) into an immutable {@link DisclosureGroup}, and
 * {@link #lookup(String, String, String)} reproduces the {@code 1200}/{@code 1200-A} control flow:
 * exact match &rarr; DEFAULT fallback &rarr; fatal abend.</p>
 *
 * <h2>Record layout &mdash; CVTRA02Y DIS-GROUP-RECORD (50 bytes, 0-based offsets)</h2>
 * <p>Verified against {@code app/cpy/CVTRA02Y.cpy} and {@code app/data/ASCII/discgrp.txt}. Only the
 * signed {@code DIS-INT-RATE} field is zoned-decimal (decoded via {@link FixedWidthCodec#decodeNumeric};
 * overpunch handled by {@code support.ZonedDecimal}); the three key fields are raw fixed-width
 * substrings (leading zeros of {@code DIS-TRAN-CAT-CD} preserved).</p>
 * <ul>
 *   <li>{@code DIS-ACCT-GROUP-ID} {@code PIC X(10)}  &mdash; {@code [0,10)}  &mdash; {@code slice(rec,0,10)}</li>
 *   <li>{@code DIS-TRAN-TYPE-CD}  {@code PIC X(02)}  &mdash; {@code [10,12)} &mdash; {@code slice(rec,10,2)}</li>
 *   <li>{@code DIS-TRAN-CAT-CD}   {@code PIC 9(04)}  &mdash; {@code [12,16)} &mdash; {@code slice(rec,12,4)}</li>
 *   <li>{@code DIS-INT-RATE}      {@code PIC S9(04)V99} &mdash; {@code [16,22)} &mdash; {@code decodeNumeric(rec,16,6,2)}</li>
 *   <li>{@code FILLER}            {@code PIC X(28)}  &mdash; {@code [22,50)} &mdash; ignored on read</li>
 * </ul>
 *
 * <h2>Why DEFAULT fires for the in-repo fixtures (behavior-preservation, do NOT "fix")</h2>
 * <p>Every {@code acctdata.txt} row carries a <strong>blank</strong> {@code ACCT-GROUP-ID} (cols
 * 113-122 are spaces), so the service looks the rate up with a blank/space group; the exact key
 * misses and the DEFAULT rows supply the rate. This repository therefore must never trim or
 * normalize the blank group away &mdash; doing so would change the observable outcome.</p>
 *
 * <p>Reverse-engineered strictly from {@code app/cbl/CBACT04C.cbl} {@code 1200}/{@code 1200-A}
 * (L415-460), the {@code CVTRA02Y.cpy} DIS-GROUP-RECORD layout, and the {@code app/data/ASCII/discgrp.txt}
 * fixture (51 records: {@code A000000000}/{@code DEFAULT}/{@code ZEROAPR} &times; 17). Depends only on
 * the JDK, same-package {@link FixedWidthCodec}, and {@code model.DisclosureGroup}; modifies nothing
 * under {@code app/} (AAP &sect;0.7 traceability). The rate-zero write skip lives in the service
 * (BR-07), not here &mdash; this repository always returns a group/rate or throws.</p>
 */
public final class DisclosureGroupRepository {

    // ---------------------------------------------------------------------------------------------
    // CVTRA02Y DIS-GROUP-RECORD field framing (0-based offsets; widths in bytes == chars, US-ASCII).
    // FD-DISCGRP-KEY physical layout: group(10) + type(2) + cat(4) (app/cbl/CBACT04C.cbl:L78-81).
    // ---------------------------------------------------------------------------------------------

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} start offset. */
    private static final int GROUP_ID_OFFSET = 0;
    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} width (also the composite-key group segment width). */
    private static final int GROUP_ID_WIDTH = 10;

    /** {@code DIS-TRAN-TYPE-CD PIC X(02)} start offset. */
    private static final int TYPE_CD_OFFSET = 10;
    /** {@code DIS-TRAN-TYPE-CD PIC X(02)} width. */
    private static final int TYPE_CD_WIDTH = 2;

    /** {@code DIS-TRAN-CAT-CD PIC 9(04)} start offset. */
    private static final int CAT_CD_OFFSET = 12;
    /** {@code DIS-TRAN-CAT-CD PIC 9(04)} width. */
    private static final int CAT_CD_WIDTH = 4;

    /** {@code DIS-INT-RATE PIC S9(04)V99} start offset. */
    private static final int INT_RATE_OFFSET = 16;
    /** {@code DIS-INT-RATE PIC S9(04)V99} stored width in digits (6). */
    private static final int INT_RATE_WIDTH = 6;
    /** {@code DIS-INT-RATE} implied {@code V99} scale (2 fractional digits). */
    private static final int INT_RATE_SCALE = 2;

    /**
     * Authoritative fixed record width of a {@code DIS-GROUP-RECORD} &mdash; CVTRA02Y RECLN 50
     * (app/cpy/CVTRA02Y.cpy; the 50-byte fixture is verified in {@code app/data/ASCII/discgrp.txt}).
     * Every real record MUST be exactly this many characters; a short/long line is malformed
     * fixed-width input in the rate table and is rejected up-front (CWE-20) so a record truncated
     * before {@code FILLER [22,50)} can never silently mis-frame (see {@link #load(Path)}).
     */
    private static final int RECORD_LENGTH = 50;

    /**
     * The literal group id used for the fallback re-read
     * ({@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}, {@code app/cbl/CBACT04C.cbl:L437}). Held as the
     * bare 7-character literal; {@link #key(String, String, String)} space-pads it to the 10-char
     * group width so it matches the stored {@code "DEFAULT   "} rows byte-for-byte.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * In-memory model of the DISCGRP VSAM KSDS: composite key ({@link #key(String, String, String)})
     * to the disclosure-group record. A {@link LinkedHashMap} preserves the fixture's physical record
     * order for deterministic iteration/debugging. Immutable after construction.
     */
    private final Map<String, DisclosureGroup> byKey;

    /**
     * Private constructor used only by {@link #load(Path)}; takes ownership of the fully populated
     * lookup map. External callers obtain instances via the {@code load} factory.
     *
     * @param byKey the populated composite-key-to-record map (non-null)
     */
    private DisclosureGroupRepository(Map<String, DisclosureGroup> byKey) {
        this.byKey = byKey;
    }

    /**
     * Loads the DISCGRP fixture file, framing every 50-byte {@code DIS-GROUP-RECORD} into a
     * {@link DisclosureGroup} and indexing it by the composite key.
     *
     * <p>Mirrors the effect of {@code OPEN INPUT DISCGRP-FILE} ({@code app/cbl/CBACT04C.cbl:L272})
     * plus the population that a VSAM KSDS provides for random keyed reads: the whole keyed data set
     * is materialized once into an in-memory {@link Map}. Records are keyed with the <em>same</em>
     * {@link #key(String, String, String)} helper used at lookup time, so build-time and lookup-time
     * keys are guaranteed identical.</p>
     *
     * <p>Input is validated as it is read: every real record must be exactly the CVTRA02Y record
     * width ({@link #RECORD_LENGTH} = 50 chars). A mid-file blank line or a short/long record is
     * malformed fixed-width input in the rate table and is rejected as a fatal framing error with the
     * offending line number (only a single terminal blank line, a trailing-newline artifact, is
     * tolerated). This prevents a truncated record missing its {@code FILLER}/rate bytes from silently
     * mis-framing (CWE-20).</p>
     *
     * @param path the path to the fixed-width DISCGRP file (e.g. {@code discgrp.txt}); each line is a
     *             de-newlined 50-byte record. Must be non-null and readable.
     * @return a fully populated, ready-to-query {@code DisclosureGroupRepository}
     * @throws UncheckedIOException if the file cannot be read (a fatal condition &mdash; the COBOL job
     *                              cannot proceed without its rate table)
     * @throws IllegalArgumentException if a real record is not exactly {@link #RECORD_LENGTH} chars, or
     *                              a non-terminal blank line is encountered &mdash; surfacing a
     *                              malformed rate table as a fatal framing error (the message reports
     *                              the line number and expected/actual width but never the record
     *                              content)
     */
    public static DisclosureGroupRepository load(Path path) {
        // LinkedHashMap => deterministic build order matching the fixture's physical record order.
        final Map<String, DisclosureGroup> byKey = new LinkedHashMap<>();
        try {
            // US-ASCII fixtures (1 byte == 1 char); readAllLines strips the LF record terminators.
            final List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
            final int lineCount = lines.size();
            for (int index = 0; index < lineCount; index++) {
                final String line = lines.get(index);
                final int lineNumber = index + 1; // 1-based for human-readable diagnostics.

                if (line.isEmpty()) {
                    // Tolerate ONLY a single zero-length FINAL line (a trailing-newline artifact): it
                    // carries no DIS-GROUP-RECORD. A mid-file blank line in a fixed-width rate table is
                    // malformed input and must NOT be silently skipped (that would hide corruption);
                    // reject it as a fatal framing error with its line number (CWE-20).
                    if (index == lineCount - 1) {
                        break;
                    }
                    throw new IllegalArgumentException(
                            "Malformed DIS-GROUP-RECORD: unexpected empty record at line " + lineNumber
                                    + " of " + path + " (expected " + RECORD_LENGTH + " chars)");
                }

                // Enforce the authoritative CVTRA02Y record width (RECLN 50) BEFORE slicing so a record
                // truncated before FILLER [22,50) -- or otherwise mis-sized -- cannot parse successfully
                // and silently accept malformed input (CWE-20). The diagnostic reports only the line
                // number and expected/actual width, never the record content.
                if (line.length() != RECORD_LENGTH) {
                    throw new IllegalArgumentException(
                            "Malformed DIS-GROUP-RECORD at line " + lineNumber + " of " + path
                                    + ": expected " + RECORD_LENGTH + " chars but was " + line.length());
                }

                // Three key fields are raw fixed-width substrings (leading zeros preserved).
                final String groupId = FixedWidthCodec.slice(line, GROUP_ID_OFFSET, GROUP_ID_WIDTH);
                final String tranTypeCd = FixedWidthCodec.slice(line, TYPE_CD_OFFSET, TYPE_CD_WIDTH);
                final String tranCatCd = FixedWidthCodec.slice(line, CAT_CD_OFFSET, CAT_CD_WIDTH);
                // Only DIS-INT-RATE is zoned-decimal: overpunch sign + implied V99 via ZonedDecimal.
                final BigDecimal intRate =
                        FixedWidthCodec.decodeNumeric(line, INT_RATE_OFFSET, INT_RATE_WIDTH, INT_RATE_SCALE);

                final DisclosureGroup group =
                        new DisclosureGroup(groupId, tranTypeCd, tranCatCd, intRate);
                // Index by the composite key using the SAME helper as lookup() (build/lookup parity).
                byKey.put(key(groupId, tranTypeCd, tranCatCd), group);
            }
        } catch (IOException e) {
            // A missing/unreadable rate table is fatal (COBOL would abend on the OPEN/READ failure).
            throw new UncheckedIOException("Unable to read DISCGRP file: " + path, e);
        }
        return new DisclosureGroupRepository(byKey);
    }

    /**
     * Looks up the disclosure-group interest rate for a composite key, applying the DEFAULT-group
     * fallback &mdash; a faithful port of {@code 1200-GET-INTEREST-RATE} / {@code 1200-A-GET-DEFAULT-INT-RATE}
     * ({@code app/cbl/CBACT04C.cbl:L415-460}; BR-06 key assembly, BR-08 fallback).
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li><strong>Exact match</strong> &mdash; if the composite key
     *       {@code group + type + cat} is present, return it (COBOL file status {@code '00'}).</li>
     *   <li><strong>DEFAULT fallback</strong> &mdash; otherwise (COBOL status {@code '23'}) retry with
     *       group id {@code "DEFAULT"} (space-padded to width 10) keeping the same {@code type} and
     *       {@code cat}, and return the DEFAULT row if present.</li>
     *   <li><strong>Fatal</strong> &mdash; if the DEFAULT key is also absent, throw
     *       ({@code 9999-ABEND-PROGRAM}).</li>
     * </ol>
     *
     * <p>The caller (service) reads {@link DisclosureGroup#intRate()} and applies the write guard
     * {@code rate != 0} (BR-07); that guard is intentionally <em>not</em> here &mdash; this method
     * always returns a matched group or throws.</p>
     *
     * @param groupId    the account's {@code ACCT-GROUP-ID} (may be blank/space-padded &mdash; NOT
     *                   trimmed or normalized; the blank group is what triggers the DEFAULT fallback
     *                   for the in-repo fixtures)
     * @param tranTypeCd the {@code TRANCAT-TYPE-CD} (2-char transaction type code)
     * @param tranCatCd  the {@code TRANCAT-CD} (4-char transaction category code)
     * @return the matched {@link DisclosureGroup} (exact-group or DEFAULT-group)
     * @throws RuntimeException if neither the exact key nor the DEFAULT key resolves &mdash; the fatal
     *                          abend semantics of {@code 1200-A} ({@code L452-459})
     */
    public DisclosureGroup lookup(String groupId, String tranTypeCd, String tranCatCd) {
        // status '00' (L422).
        final DisclosureGroup exact = byKey.get(key(groupId, tranTypeCd, tranCatCd));
        if (exact != null) {
            return exact;
        }
        // status '23' -> MOVE 'DEFAULT' + 1200-A re-read (L436-439, L443-460).
        final DisclosureGroup fallback = byKey.get(key(DEFAULT_GROUP_ID, tranTypeCd, tranCatCd));
        if (fallback != null) {
            return fallback;
        }
        // DEFAULT missing -> 9999-ABEND-PROGRAM (L452-459).
        throw new RuntimeException(
                "DEFAULT disclosure group not found for type=" + tranTypeCd + " cat=" + tranCatCd);
    }

    /**
     * Builds the 16-character composite lookup key from its three segments.
     *
     * <p>Reproduces the physical {@code FD-DISCGRP-KEY} layout &mdash;
     * {@code FD-DIS-ACCT-GROUP-ID PIC X(10) + FD-DIS-TRAN-TYPE-CD PIC X(02) + FD-DIS-TRAN-CAT-CD PIC 9(04)}
     * ({@code app/cbl/CBACT04C.cbl:L78-81}; MOVEs at {@code L210-212}) &mdash; i.e. <strong>group,
     * then type, then cat</strong>. (The {@code L210-212} MOVEs assign the fields cat-then-type by
     * name, but the byte order that forms the VSAM key is the FD physical layout: type before cat.)</p>
     *
     * <p>Each segment is framed with {@link FixedWidthCodec#alpha(String, int)} (left-justified,
     * right space-padded, truncated if longer), so the literal {@code "DEFAULT"} becomes
     * {@code "DEFAULT   "} and matches the stored 10-char group exactly. Because {@link #load(Path)}
     * and {@link #lookup(String, String, String)} both route through this single helper, build-time
     * and lookup-time keys are always consistent.</p>
     *
     * @param groupId the group id segment (10-char field)
     * @param typeCd  the transaction type code segment (2-char field)
     * @param catCd   the transaction category code segment (4-char field)
     * @return the concatenated 16-character composite key
     */
    private static String key(String groupId, String typeCd, String catCd) {
        // FD-DISCGRP-KEY = group(10) + type(2) + cat(4) (app/cbl/CBACT04C.cbl:L78-81 / L210-212).
        return FixedWidthCodec.alpha(groupId, GROUP_ID_WIDTH)
                + FixedWidthCodec.alpha(typeCd, TYPE_CD_WIDTH)
                + FixedWidthCodec.alpha(catCd, CAT_CD_WIDTH);
    }
}

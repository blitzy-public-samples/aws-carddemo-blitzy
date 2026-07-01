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
package com.blitzy.carddemo.interest.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.blitzy.carddemo.interest.model.CardXref;

/**
 * In-memory card cross-reference repository, keyed by account id (the VSAM
 * <em>alternate</em> index).
 *
 * <p>Ports CBACT04C {@code 1110-GET-XREF-DATA} (app/cbl/CBACT04C.cbl:L393-413).
 * In the COBOL program the XREF file is read with
 * {@code READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-ACCT-ID} &mdash;
 * i.e. by the <strong>alternate index</strong>, which is the account id
 * (SELECT clause {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID},
 * CBACT04C L38; the READ at L394-395). The retrieved card number is later
 * copied into every generated interest transaction
 * ({@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM}, CBACT04C L495).</p>
 *
 * <p>The record read is a {@code CVACT03Y} {@code CARD-XREF-RECORD}. The
 * copybook declares RECLN 50, but the in-repo fixture
 * {@code app/data/ASCII/cardxref.txt} stores only the 36 significant bytes
 * (16 + 9 + 11) and omits the trailing {@code FILLER X(14)} &mdash; every line
 * is 36 characters. This repository frames only the three significant fields,
 * so it works over the 36-byte lines without ever touching the absent filler.</p>
 *
 * <h2>Design (AAP &sect;0.3.3 Repository pattern; BR-05)</h2>
 * <p>VSAM random access by the alternate key is modeled as an in-memory
 * {@link Map} keyed by account id, built once from the flat fixture at
 * {@link #load(Path)} time. Lookups ({@link #byAccountId(String)}) are then
 * O(1) map reads that reproduce the keyed-lookup semantics of the COBOL
 * {@code READ ... KEY IS} exactly. The backing map is a {@link LinkedHashMap}
 * so iteration (were it ever exposed) would follow file order deterministically;
 * lookups themselves are order-independent.</p>
 *
 * <p>Minimal-change note (AAP &sect;0.7): CBACT04C reads XREF <em>only</em> by
 * the alternate (account-id) key in this program, so this repository models
 * only that access path. The prime key (card number) lookup is intentionally
 * omitted (CBACT04C never reads XREF by card number).</p>
 *
 * <p>Instances are effectively immutable: the backing map is fully populated
 * inside {@link #load(Path)} before being handed to the private constructor,
 * is held in a {@code private final} field, is never mutated afterwards, and
 * never escapes (only individual immutable {@link CardXref} records are
 * returned). It is therefore safe to share a loaded repository across threads.</p>
 */
public final class CardXrefRepository {

    // ------------------------------------------------------------------------
    // CVACT03Y CARD-XREF-RECORD field framing (0-based offsets, US-ASCII so
    // char offset == COBOL byte offset). Verified against app/cpy/CVACT03Y.cpy
    // and app/data/ASCII/cardxref.txt (50 records, 36 bytes each):
    //   XREF-CARD-NUM  PIC X(16)  -> [0, 16)
    //   XREF-CUST-ID   PIC 9(09)  -> [16, 25)   (unsigned; leading zeros preserved)
    //   XREF-ACCT-ID   PIC 9(11)  -> [25, 36)   (unsigned; leading zeros preserved) = MAP KEY
    //   FILLER         PIC X(14)  -> [36, 50)   ABSENT in the ASCII fixture; never sliced.
    // ------------------------------------------------------------------------

    /** Start offset of XREF-CARD-NUM (PIC X(16)) within the record. */
    private static final int CARD_NUM_OFFSET = 0;
    /** Width of XREF-CARD-NUM (PIC X(16)) in bytes. */
    private static final int CARD_NUM_WIDTH = 16;

    /** Start offset of XREF-CUST-ID (PIC 9(09)) within the record. */
    private static final int CUST_ID_OFFSET = 16;
    /** Width of XREF-CUST-ID (PIC 9(09)) in bytes. */
    private static final int CUST_ID_WIDTH = 9;

    /** Start offset of XREF-ACCT-ID (PIC 9(11)) within the record; this field is the map key. */
    private static final int ACCT_ID_OFFSET = 25;
    /** Width of XREF-ACCT-ID (PIC 9(11)) in bytes. */
    private static final int ACCT_ID_WIDTH = 11;

    /**
     * Card cross-references keyed by account id (XREF-ACCT-ID, the alternate
     * index). Populated once in {@link #load(Path)}; never mutated afterwards.
     */
    private final Map<String, CardXref> byAcctId;

    /**
     * Creates a repository over an already-populated account-id -&gt; xref map.
     * Private: instances are only ever created by {@link #load(Path)}.
     *
     * @param byAcctId the fully-populated, caller-owned map (not copied; the
     *                 caller must not retain or mutate it after construction)
     */
    private CardXrefRepository(Map<String, CardXref> byAcctId) {
        this.byAcctId = byAcctId;
    }

    /**
     * Loads {@code cardxref.txt} and builds the account-id -&gt; xref map (AAP
     * &sect;0.3.3 Repository; BR-05). This is the Java analogue of opening the
     * VSAM XREF file: the whole cross-reference is read into memory keyed by
     * the alternate index (account id) so that subsequent
     * {@link #byAccountId(String)} calls reproduce the COBOL
     * {@code READ ... KEY IS FD-XREF-ACCT-ID}.
     *
     * <p>Each line is a fixed-width {@code CVACT03Y CARD-XREF-RECORD}; the file
     * is read as US-ASCII (1 byte = 1 char, matching the fixed-width byte
     * offsets) with file order preserved by a {@link LinkedHashMap}. On a
     * duplicate account-id key (not expected &mdash; the VSAM alternate index
     * is effectively unique here, and each account id appears exactly once in
     * the fixture) the last record wins, which is the simplest behavior and
     * keeps a re-ingested file idempotent.</p>
     *
     * <p>A read failure is fatal: any {@link IOException} is wrapped in an
     * {@link UncheckedIOException} rather than swallowed, so a missing or
     * unreadable input aborts the job (there is no meaningful recovery from an
     * absent cross-reference file).</p>
     *
     * @param path the path to the fixed-width {@code cardxref.txt} fixture
     *             (36-byte lines; see the class Javadoc)
     * @return a repository whose map is keyed by account id (XREF-ACCT-ID)
     * @throws UncheckedIOException if the file cannot be read
     * @throws IllegalArgumentException if a non-empty line is shorter than the
     *             36 significant bytes (propagated from
     *             {@link FixedWidthCodec#slice(String, int, int)}), surfacing a
     *             truncated/malformed fixture as a fatal framing error
     */
    public static CardXrefRepository load(Path path) {
        // LinkedHashMap preserves the file's record order for deterministic
        // iteration; lookups by key are order-independent (AAP §0.3.3).
        final Map<String, CardXref> byAcctId = new LinkedHashMap<>();
        try {
            // US-ASCII: char index == COBOL byte offset, so FixedWidthCodec.slice
            // frames each copybook field by its exact byte position.
            final List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
            for (final String line : lines) {
                // Tolerate a stray trailing/blank empty line (e.g. from an extra
                // newline): an empty line carries no CARD-XREF-RECORD. All real
                // records are exactly 36 chars, so this never skips data.
                if (line.isEmpty()) {
                    continue;
                }
                final CardXref xref = parse(line);
                // Key by the alternate index (XREF-ACCT-ID). Last-wins on a
                // duplicate key (see method Javadoc; not expected in the fixture).
                byAcctId.put(xref.acctId(), xref);
            }
        } catch (final IOException e) {
            // Load failure is fatal (no usable cross-reference) -> unchecked abort.
            throw new UncheckedIOException("Failed to load XREF file: " + path, e);
        }
        return new CardXrefRepository(byAcctId);
    }

    /**
     * Parses one fixed-width {@code CVACT03Y CARD-XREF-RECORD} line into an
     * immutable {@link CardXref}.
     *
     * <p>Field framing per {@code app/cpy/CVACT03Y.cpy} (0-based offsets):
     * XREF-CARD-NUM {@code X(16)} {@code [0,16)}; XREF-CUST-ID {@code 9(09)}
     * {@code [16,25)}; XREF-ACCT-ID {@code 9(11)} {@code [25,36)}. The trailing
     * {@code FILLER X(14)} {@code [36,50)} is <strong>absent</strong> in
     * {@code app/data/ASCII/cardxref.txt} (every line is 36 chars) and is never
     * sliced. All three fields are alphanumeric/unsigned-numeric Strings, so no
     * zoned-decimal (overpunch) decoding is involved &mdash; leading zeros in
     * the numeric identifiers are preserved verbatim.</p>
     *
     * @param record a de-newlined 36-byte (or longer) xref record line
     * @return the parsed cross-reference record
     * @throws IllegalArgumentException if the line is too short for the fields
     *             (propagated from {@link FixedWidthCodec#slice(String, int, int)})
     */
    private static CardXref parse(String record) {
        // FixedWidthCodec is in this same package (io) -> no import required.
        final String cardNum = FixedWidthCodec.slice(record, CARD_NUM_OFFSET, CARD_NUM_WIDTH);
        final String custId = FixedWidthCodec.slice(record, CUST_ID_OFFSET, CUST_ID_WIDTH);
        final String acctId = FixedWidthCodec.slice(record, ACCT_ID_OFFSET, ACCT_ID_WIDTH);
        return new CardXref(cardNum, custId, acctId);
    }

    /**
     * Returns the card cross-reference for the given account id (the alternate
     * index), reproducing CBACT04C {@code 1110-GET-XREF-DATA}
     * ({@code READ XREF-FILE ... KEY IS FD-XREF-ACCT-ID}, L394-395). The COBOL
     * key is set from {@code TRANCAT-ACCT-ID} of the driver record
     * ({@code MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID}, CBACT04C L204), so
     * callers pass the raw, zero-padded 11-character account id exactly as it
     * appears in the driver record (no trimming); the map was keyed with the
     * same form.
     *
     * @param acctId the account id (XREF-ACCT-ID form: raw 11-char, zero-padded)
     * @return the matching {@link CardXref}; never {@code null}
     * @throws RuntimeException if no cross-reference exists for {@code acctId}
     */
    public CardXref byAccountId(String acctId) {
        final CardXref xref = byAcctId.get(acctId);
        if (xref == null) {
            // missing xref -> 9999-ABEND-PROGRAM equivalent: fatal (L405-412). NOT a fallback.
            // (Contrast DisclosureGroupRepository, which falls back to the DEFAULT group.)
            throw new RuntimeException("XREF not found for account id: " + acctId);
        }
        return xref;
    }
}

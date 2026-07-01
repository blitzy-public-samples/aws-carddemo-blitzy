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
package com.blitzy.carddemo.interest.io;

import java.math.BigDecimal;

import com.blitzy.carddemo.interest.support.ZonedDecimal;

/**
 * Generic fixed-width record framing primitive shared by every reader, writer, and repository in the
 * {@code io} package of the CBACT04C interest-calculation port.
 *
 * <p>COBOL sequential/VSAM records are <strong>fixed-width</strong>: each field occupies a known byte
 * span and there are no delimiters between fields. This class supplies the two halves of that
 * contract:</p>
 * <ul>
 *   <li><strong>Slicing</strong> ({@link #slice(String, int, int)}, {@link #decodeNumeric}) &mdash;
 *       extract a field from a read record by {@code (offset, width)}. Used by the readers and
 *       repositories.</li>
 *   <li><strong>Assembling</strong> ({@link #alpha(String, int)}, {@link #encodeNumeric},
 *       {@link RecordBuilder}) &mdash; append fixed-width fields to build an output record. Used by
 *       the writers ({@code TransactionWriter} 350 bytes, {@code AccountRepository} rewrite 300
 *       bytes).</li>
 * </ul>
 *
 * <h2>Width authority (copybook PIC layouts)</h2>
 * <p>The record and field widths this class frames are defined by CBACT04C's five copybooks; this
 * class does <em>not</em> hardcode the record totals (callers pass {@code offset}/{@code width}), but
 * they are cited here as the authoritative source:</p>
 * <ul>
 *   <li>{@code app/cpy/CVTRA01Y.cpy} &mdash; {@code TRAN-CAT-BAL-RECORD} = <strong>50</strong> bytes
 *       (driver input).</li>
 *   <li>{@code app/cpy/CVTRA02Y.cpy} &mdash; {@code DIS-GROUP-RECORD} = <strong>50</strong> bytes
 *       (rate lookup).</li>
 *   <li>{@code app/cpy/CVACT03Y.cpy} &mdash; {@code CARD-XREF-RECORD} = <strong>50</strong> bytes in
 *       the copybook, but the {@code app/data/ASCII/cardxref.txt} fixture stores only the
 *       <strong>36</strong> significant bytes (XREF-CARD-NUM 16 + XREF-CUST-ID 9 + XREF-ACCT-ID 11);
 *       callers therefore pass offsets that fit within 36 and this class does no total-width
 *       policing.</li>
 *   <li>{@code app/cpy/CVACT01Y.cpy} &mdash; {@code ACCOUNT-RECORD} = <strong>300</strong> bytes
 *       (read + rewrite).</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} &mdash; {@code TRAN-RECORD} = <strong>350</strong> bytes
 *       (interest-transaction output).</li>
 * </ul>
 *
 * <h2>Encoding conventions</h2>
 * <ul>
 *   <li><strong>US-ASCII: char offset == byte offset.</strong> All in-repo fixtures are US-ASCII
 *       (1 byte = 1 char), so a Java {@link String} character index is identical to the COBOL byte
 *       position. This class operates on {@link String} offsets accordingly.</li>
 *   <li><strong>De-newlined records.</strong> Fixtures are LF-terminated with one record per line;
 *       this class operates on already-stripped record strings (the readers/writers own the line
 *       terminator, never this class).</li>
 *   <li><strong>Numeric framing delegated to {@code support.ZonedDecimal}.</strong> Alphanumeric
 *       ({@code X(n)}) framing is handled here; every signed zoned-decimal ({@code S9(n)Vnn}) field
 *       is routed through {@link ZonedDecimal#decode(String, int)} on read and
 *       {@link ZonedDecimal#encode(BigDecimal, int, int)} on write &mdash; the overpunch-sign and
 *       implied-decimal logic is <em>never</em> reimplemented here, and {@code Integer.parseInt} /
 *       {@code new BigDecimal(String)} are <em>never</em> applied to raw zoned bytes.</li>
 *   <li><strong>Encode/decode symmetry.</strong> {@link #decodeNumeric} and {@link #encodeNumeric}
 *       are thin pass-throughs to the exact-inverse {@code ZonedDecimal} codec, preserving the
 *       byte-identical read&rarr;write round-trip that the 300-byte account REWRITE depends on.</li>
 * </ul>
 *
 * <p>This is a stateless utility: all methods are {@code static}, the class holds no static mutable
 * state, and the static methods are therefore thread-safe pure functions. Reverse-engineered strictly
 * from the copybook PIC layouts in {@code app/cpy/} and the fixed-width / US-ASCII / LF conventions
 * evidenced in {@code app/data/ASCII/}; it modifies nothing under {@code app/} (AAP &sect;0.7
 * traceability). Numeric framing depends on {@code support.ZonedDecimal}.</p>
 */
public final class FixedWidthCodec {

    /**
     * Non-instantiable: {@code FixedWidthCodec} is a pure, stateless static utility.
     */
    private FixedWidthCodec() {
        // Intentionally empty -- prevents instantiation of this utility class.
    }

    /**
     * Extracts the fixed-width field {@code [offset, offset + width)} from a de-newlined record.
     *
     * <p>Because every in-repo fixture is US-ASCII (1 byte = 1 char), the character indices used here
     * are identical to the COBOL byte positions of the copybook field &mdash;
     * <em>char offset == byte offset</em>. This is the primitive the readers and repositories use to
     * carve each copybook field out of a read line (e.g. {@code slice(line, 0, 11)} for
     * {@code TRANCAT-ACCT-ID}).</p>
     *
     * <p><strong>Length policing:</strong> this too-short guard is the <em>only</em> record-length
     * check {@code FixedWidthCodec} performs; it surfaces a malformed/truncated fixture as a fatal
     * error rather than silently mis-framing subsequent fields. Callers that intentionally read a
     * variable-length fixture (e.g. the 36-byte xref line) simply pass offsets that fit within the
     * available bytes, so the guard never fires for well-formed input.</p>
     *
     * @param record the de-newlined fixed-width record; must be non-null and at least
     *               {@code offset + width} characters long
     * @param offset the zero-based start position of the field (== COBOL byte offset); must be
     *               {@code >= 0}
     * @param width  the field width in bytes/characters; must be {@code >= 0}
     * @return the field substring of length {@code width}
     * @throws IllegalArgumentException if {@code record} is null, {@code offset} or {@code width} is
     *                                  negative, or {@code record.length() < offset + width}
     */
    public static String slice(String record, int offset, int width) {
        if (record == null) {
            throw new IllegalArgumentException("record must be non-null");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0, but was " + offset);
        }
        if (width < 0) {
            throw new IllegalArgumentException("width must be >= 0, but was " + width);
        }
        // The only length policing this class performs: a too-short record is a fatal, not silent,
        // framing error (a truncated fixture would otherwise mis-align every following field).
        if (record.length() < offset + width) {
            throw new IllegalArgumentException(
                    "record too short: need " + (offset + width) + " got " + record.length());
        }
        return record.substring(offset, offset + width);
    }

    /**
     * Slices the fixed-width field {@code [offset, offset + width)} and decodes it as a signed
     * COBOL zoned-decimal value at the given {@code scale}.
     *
     * <p>Convenience for reading every signed {@code S9(n)Vnn} field (transaction-category balance,
     * disclosure interest rate, and all account money fields). This method performs <strong>pure
     * delegation</strong>: it {@link #slice(String, int, int) slices} the raw field then hands the
     * overpunch-sign + implied-decimal decoding to {@link ZonedDecimal#decode(String, int)}; the
     * zoned-decimal logic is never reimplemented here.</p>
     *
     * @param record the de-newlined fixed-width record; must be non-null and long enough for the field
     * @param offset the zero-based start position of the field (== COBOL byte offset)
     * @param width  the field width in bytes/characters (== total stored digits, e.g. 11 for
     *               {@code S9(09)V99}, 6 for {@code S9(04)V99}, 12 for {@code S9(10)V99})
     * @param scale  the number of implied fractional digits (2 for every {@code V99} field)
     * @return the decoded value as a {@link BigDecimal} at exactly {@code scale} fractional digits
     * @throws IllegalArgumentException if {@code slice} rejects the record, or if the sliced field is
     *                                  not a valid zoned-decimal field (propagated from
     *                                  {@link ZonedDecimal#decode(String, int)})
     */
    public static BigDecimal decodeNumeric(String record, int offset, int width, int scale) {
        // Slice honors the null / negative / too-short guards, then ZonedDecimal owns the numeric
        // semantics (overpunch sign + implied Vnn decimal). Pure pass-through -- no local parsing.
        return ZonedDecimal.decode(slice(record, offset, width), scale);
    }

    /**
     * Frames an alphanumeric ({@code X(n)}) field: left-justified, right space-padded to {@code width},
     * truncated to {@code width} if longer.
     *
     * <p>This mirrors COBOL {@code MOVE &lt;alphanumeric&gt; TO X(n)} exactly: the value is placed
     * left-justified in the fixed field and the remainder is <em>blank-filled</em> (spaces, never
     * zeros); a value longer than the field is truncated on the right. A {@code null} value is treated
     * as empty (all spaces) for safety.</p>
     *
     * <p>Note on already-padded numeric strings: unsigned {@code 9(n)} fields that the service models
     * as zero-padded {@link String}s (e.g. {@code TRAN-MERCHANT-ID = "000000000"},
     * {@code TRAN-CAT-CD = "0005"}) are produced at their exact width by the caller; passing such a
     * string to {@code alpha(value, width)} with {@code value.length() == width} returns it unchanged.
     * Zero-padding of numeric-as-string fields is the value producer's responsibility, not this
     * method's.</p>
     *
     * @param value the field value; {@code null} is treated as an empty string (yielding all spaces)
     * @param width the fixed field width in bytes/characters; must be {@code >= 0}
     * @return a string of exactly {@code width} characters, left-justified and right space-padded (or
     *         right-truncated when the value is longer than {@code width})
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String alpha(String value, int width) {
        if (width < 0) {
            throw new IllegalArgumentException("width must be >= 0, but was " + width);
        }
        // COBOL MOVE <alphanumeric> TO X(n): left-justified, blank-filled; null == empty field.
        final String v = (value == null) ? "" : value;
        if (v.length() >= width) {
            // Longer-or-equal: right-truncate to the fixed field width (equal length is unchanged).
            return v.substring(0, width);
        }
        // Shorter: pad the right with spaces (blank-fill, NOT zero-fill, NOT right-justified).
        return v + " ".repeat(width - v.length());
    }

    /**
     * Encodes a signed COBOL zoned-decimal ({@code S9(n)Vnn}) field for writing.
     *
     * <p>Convenience for writing every signed money/rate field (e.g. {@code TRAN-AMT} with
     * {@code totalDigits = 11}, account money fields with {@code totalDigits = 12}). This method
     * performs <strong>pure delegation</strong> to {@link ZonedDecimal#encode(BigDecimal, int, int)},
     * which is the exact inverse of {@link ZonedDecimal#decode(String, int)} so a read&rarr;write
     * round-trip is byte-identical.</p>
     *
     * @param value       the value to encode; must be non-null and already at (or below) {@code scale}
     *                    fractional precision
     * @param totalDigits the fixed field width in stored digits (e.g. 6, 11, or 12 in this module)
     * @param scale       the number of implied fractional digits (2 for every {@code V99} field)
     * @return the fixed-width zoned-decimal field string of length {@code totalDigits}
     * @throws IllegalArgumentException if {@code ZonedDecimal.encode} rejects the arguments (null
     *                                  value, bad width/scale, or magnitude too large for the field)
     * @throws ArithmeticException      if {@code value} carries more fractional digits than
     *                                  {@code scale} (propagated from {@link ZonedDecimal#encode})
     */
    public static String encodeNumeric(BigDecimal value, int totalDigits, int scale) {
        // Pure pass-through: ZonedDecimal owns overpunch + implied-decimal + left-zero-pad.
        return ZonedDecimal.encode(value, totalDigits, scale);
    }

    /**
     * Creates a new {@link RecordBuilder} for assembling a fixed-width output record field-by-field.
     *
     * @return a fresh, empty {@code RecordBuilder}
     */
    public static RecordBuilder builder() {
        return new RecordBuilder();
    }

    /**
     * A tiny fluent assembler that appends fixed-width fields to build an output record and asserts the
     * final record length, guarding against off-by-one framing errors.
     *
     * <p>Each mutator delegates to the enclosing {@code FixedWidthCodec} helpers so the framing rules
     * are applied uniformly, and {@link #build(int)} enforces the exact record total (e.g.
     * {@code build(350)} for {@code TRAN-RECORD}, {@code build(300)} for {@code ACCOUNT-RECORD}). A
     * {@code RecordBuilder} instance is backed by a mutable {@link StringBuilder}, so a single builder
     * must be confined to one thread (build one record per builder); the enclosing utility's static
     * methods remain pure and thread-safe.</p>
     */
    public static final class RecordBuilder {

        /** Accumulates the fixed-width record; the builder's only (instance-local) state. */
        private final StringBuilder sb = new StringBuilder();

        /**
         * Creates an empty builder. Instantiated via {@link FixedWidthCodec#builder()}.
         */
        private RecordBuilder() {
            // Intentionally empty.
        }

        /**
         * Appends an alphanumeric ({@code X(n)}) field per {@link FixedWidthCodec#alpha(String, int)}.
         *
         * @param value the field value ({@code null} treated as empty / all spaces)
         * @param width the fixed field width
         * @return this builder, for chaining
         */
        public RecordBuilder alpha(String value, int width) {
            sb.append(FixedWidthCodec.alpha(value, width));
            return this;
        }

        /**
         * Appends a signed zoned-decimal field per
         * {@link FixedWidthCodec#encodeNumeric(BigDecimal, int, int)}.
         *
         * @param value       the value to encode
         * @param totalDigits the fixed field width in stored digits
         * @param scale       the number of implied fractional digits (2 for {@code V99})
         * @return this builder, for chaining
         */
        public RecordBuilder numeric(BigDecimal value, int totalDigits, int scale) {
            sb.append(FixedWidthCodec.encodeNumeric(value, totalDigits, scale));
            return this;
        }

        /**
         * Appends {@code width} blank ({@code ' '}) bytes &mdash; used for COBOL {@code FILLER X(n)}
         * padding and space-initialized fields (e.g. {@code TRAN-MERCHANT-NAME}).
         *
         * @param width the number of spaces to append; must be {@code >= 0}
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code width} is negative (propagated from
         *                                  {@link String#repeat(int)})
         */
        public RecordBuilder spaces(int width) {
            sb.append(" ".repeat(width));
            return this;
        }

        /**
         * Returns the assembled record, asserting it is exactly {@code expectedLength} bytes.
         *
         * <p>The length assertion is a strong guard against off-by-one framing errors: if the sum of
         * appended field widths does not equal the copybook record total, this fails fast rather than
         * emitting a mis-sized record that would corrupt the golden master.</p>
         *
         * @param expectedLength the exact copybook record length (e.g. 300 or 350)
         * @return the assembled fixed-width record string
         * @throws IllegalStateException if the assembled length differs from {@code expectedLength}
         */
        public String build(int expectedLength) {
            if (sb.length() != expectedLength) {
                throw new IllegalStateException(
                        "record length " + sb.length() + " != expected " + expectedLength);
            }
            return sb.toString();
        }
    }
}

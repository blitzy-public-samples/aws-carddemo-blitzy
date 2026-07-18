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
package com.aws.carddemo.common.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;

/**
 * Foundational, dependency-free codec for the fixed-width record layouts that form the
 * <em>external file contracts</em> of the CardDemo batch programs. It is the Java re-platform of
 * the COBOL sequential-file record I/O and exists so that those byte-for-byte contracts survive the
 * migration from VSAM/QSAM to Spring Batch flat files (AAP &sect;0.5.5, hotspot M2 in &sect;0.7.2).
 *
 * <p>Source lineage (all relocated under {@code legacy/**} during migration):</p>
 * <ul>
 *   <li>{@code legacy/cpy/CVTRA06Y.cpy} &mdash; the 350-byte {@code DALYTRAN-RECORD} daily
 *       transaction layout consumed by the posting job;</li>
 *   <li>{@code legacy/cbl/CBTRN02C.cbl} &mdash; the {@code DALYTRAN-FILE} input FD and the
 *       {@code DALYREJS-FILE} reject-output FD (a 350-byte transaction image plus an 80-byte
 *       validation trailer = 430 bytes), including the {@code 2500-WRITE-REJECT-REC} composition;</li>
 *   <li>{@code legacy/cbl/CBSTM03A.CBL} &mdash; the 80-byte statement line record
 *       ({@code FD-STMTFILE-REC PIC X(80)}).</li>
 * </ul>
 *
 * <h2>Why a general-purpose codec</h2>
 * <p>The class deliberately does <strong>not</strong> hard-code any single record layout. It exposes
 * position-driven read/write primitives plus a small {@link FieldDef} descriptor so that the batch
 * {@code reader}/{@code writer} components declare each record's fields (offset, length, type, and
 * &mdash; for decimals &mdash; scale) and drive the codec. This keeps the DALYTRAN, DALYREJS, and
 * statement layouts as data owned by their callers, not as behavior baked into this leaf utility.</p>
 *
 * <h2>Zoned-decimal overpunch (the parity-critical detail)</h2>
 * <p>COBOL {@code PIC S9(i)V9(f)} DISPLAY fields store the sign as a <em>zoned-decimal overpunch</em>
 * on the <em>last</em> digit &mdash; there is no leading minus and no physical decimal point. For
 * example, in the real seed data the amount field {@code 0000005047G} is {@code +504.77},
 * {@code 0000009190}} is {@code -919.00}, and {@code 0000000678H} is {@code +67.88}. The positive
 * overpunch characters for digits {@code 0..9} are <code>{ A B C D E F G H I</code>; the negative
 * characters are <code>} J K L M N O P Q R</code>. A plain digit in the last position denotes a
 * positive value. Failing to honor this encoding would break the golden-file parity tests
 * (AAP &sect;0.9.2 / &sect;0.9.6), because their fixtures are derived from this seed data.</p>
 *
 * <h2>Monetary discipline</h2>
 * <p>Decimal fields are decoded to, and encoded from, {@link BigDecimal} at their declared scale
 * (scale 2 for money). {@code double}/{@code float} are never used; every scale adjustment uses an
 * explicit {@link RoundingMode} (AAP &sect;0.6.4). The codec is a faithful transcoder and never
 * reformats or normalizes data beyond the exact column widths.</p>
 *
 * <h2>Canonical DALYREJS usage</h2>
 * <p>The reject record ({@code CBTRN02C 2500-WRITE-REJECT-REC}) is the untouched 350-byte input
 * record followed by an 80-byte trailer of {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} and
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. With these primitives that is simply:</p>
 * <pre>{@code
 * String rejectRecord = dalytranRecord                       // untouched 350 chars
 *         + FixedWidthCodec.writeNumeric(102, 4)             // reason code
 *         + FixedWidthCodec.writeAlphanumeric("OVERLIMIT TRANSACTION", 76);
 * // rejectRecord.length() == 430
 * }</pre>
 *
 * <p>All members are {@code static}; the class is stateless and cannot be instantiated. It depends
 * on nothing in the project &mdash; only {@code java.math} and {@code java.util} from the JDK.</p>
 */
public final class FixedWidthCodec {

    /**
     * Positive zoned-decimal overpunch characters indexed by the trailing digit {@code 0..9}
     * (<code>{ A B C D E F G H I</code>). Immutable.
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * Negative zoned-decimal overpunch characters indexed by the trailing digit {@code 0..9}
     * (<code>} J K L M N O P Q R</code>). Immutable.
     */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Space fill for COBOL {@code PIC X} (alphanumeric) fields. */
    private static final char PAD_SPACE = ' ';

    /** Zero fill for COBOL {@code PIC 9} (numeric) fields. */
    private static final char PAD_ZERO = '0';

    /**
     * Non-instantiable stateless utility.
     *
     * @throws AssertionError always &mdash; this class exposes only {@code static} members.
     */
    private FixedWidthCodec() {
        throw new AssertionError("utility class");
    }

    // ------------------------------------------------------------------------
    // Field model
    // ------------------------------------------------------------------------

    /**
     * The COBOL {@code PICTURE} category of a fixed-width field.
     */
    public enum FieldType {

        /** COBOL {@code PIC X(n)} &mdash; left-justified, space-filled text. */
        ALPHANUMERIC,

        /** COBOL {@code PIC 9(n)} &mdash; right-justified, zero-filled unsigned integer. */
        NUMERIC,

        /**
         * COBOL {@code PIC S9(i)V9(f)} DISPLAY &mdash; zero-filled, sign overpunched on the last
         * digit, with an implied decimal point yielding a {@link BigDecimal} of scale {@code f}.
         */
        SIGNED_DECIMAL
    }

    /**
     * Immutable descriptor of one field within a fixed-width record: its {@code name} (for error
     * messages and traceability), its zero-based {@code offset} into the record string, its
     * {@code length} in characters, its {@link FieldType}, and &mdash; only for
     * {@link FieldType#SIGNED_DECIMAL} &mdash; the fractional {@code scale}.
     *
     * @param name   the field name (from the COBOL data name); must not be {@code null}
     * @param offset the zero-based character offset into the record; must be {@code >= 0}
     * @param length the field width in characters; must be {@code > 0}
     * @param type   the field category; must not be {@code null}
     * @param scale  the number of implied fractional digits for {@link FieldType#SIGNED_DECIMAL}
     *               (use {@code 0} otherwise); must be {@code >= 0} and, for signed decimals,
     *               {@code < length} (at least one digit must precede the sign-bearing digit)
     */
    public record FieldDef(String name, int offset, int length, FieldType type, int scale) {

        /**
         * Validates the descriptor invariants at construction time.
         */
        public FieldDef {
            Objects.requireNonNull(name, "field name must not be null");
            Objects.requireNonNull(type, "field type must not be null");
            if (offset < 0) {
                throw new IllegalArgumentException(
                        "field '" + name + "' offset must be >= 0 but was " + offset);
            }
            if (length <= 0) {
                throw new IllegalArgumentException(
                        "field '" + name + "' length must be > 0 but was " + length);
            }
            if (scale < 0) {
                throw new IllegalArgumentException(
                        "field '" + name + "' scale must be >= 0 but was " + scale);
            }
            if (type == FieldType.SIGNED_DECIMAL && scale >= length) {
                throw new IllegalArgumentException(
                        "field '" + name + "' signed-decimal scale (" + scale
                                + ") must be < length (" + length + ")");
            }
        }

        /**
         * Convenience factory for a COBOL {@code PIC X(length)} field.
         *
         * @param name   the field name
         * @param offset the zero-based offset
         * @param length the field width
         * @return an {@link FieldType#ALPHANUMERIC} descriptor
         */
        public static FieldDef alphanumeric(String name, int offset, int length) {
            return new FieldDef(name, offset, length, FieldType.ALPHANUMERIC, 0);
        }

        /**
         * Convenience factory for a COBOL {@code PIC 9(length)} field.
         *
         * @param name   the field name
         * @param offset the zero-based offset
         * @param length the field width
         * @return a {@link FieldType#NUMERIC} descriptor
         */
        public static FieldDef numeric(String name, int offset, int length) {
            return new FieldDef(name, offset, length, FieldType.NUMERIC, 0);
        }

        /**
         * Convenience factory for a COBOL {@code PIC S9(i)V9(scale)} DISPLAY field. The total
         * {@code length} equals {@code i + scale}.
         *
         * @param name   the field name
         * @param offset the zero-based offset
         * @param length the field width ({@code i + scale})
         * @param scale  the number of implied fractional digits
         * @return a {@link FieldType#SIGNED_DECIMAL} descriptor
         */
        public static FieldDef signedDecimal(String name, int offset, int length, int scale) {
            return new FieldDef(name, offset, length, FieldType.SIGNED_DECIMAL, scale);
        }

        /**
         * Returns the exclusive end offset of this field ({@code offset + length}), i.e. the offset
         * of the first character after this field.
         *
         * @return {@code offset + length}
         */
        public int endOffset() {
            return offset + length;
        }
    }

    // ------------------------------------------------------------------------
    // READ primitives (fixed-width record String -> typed value)
    // ------------------------------------------------------------------------

    /**
     * Reads a COBOL {@code PIC X(length)} field verbatim (no trimming), preserving trailing space
     * padding exactly as stored.
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param offset the zero-based offset of the field
     * @param length the field width
     * @return the raw {@code length}-character substring
     * @throws IllegalArgumentException if the field range falls outside the record
     */
    public static String readAlphanumeric(String record, int offset, int length) {
        checkReadBounds(record, offset, length, "alphanumeric");
        return record.substring(offset, offset + length);
    }

    /**
     * Reads a COBOL {@code PIC X(length)} field and strips <em>trailing spaces</em> only (COBOL
     * right-pads {@code PIC X} with spaces). Leading spaces and embedded spaces are preserved.
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param offset the zero-based offset of the field
     * @param length the field width
     * @return the substring with trailing spaces removed
     * @throws IllegalArgumentException if the field range falls outside the record
     */
    public static String readAlphanumericTrimmed(String record, int offset, int length) {
        return stripTrailingSpaces(readAlphanumeric(record, offset, length));
    }

    /**
     * Reads a COBOL {@code PIC 9(length)} unsigned integer field (leading zeros permitted).
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param offset the zero-based offset of the field
     * @param length the field width
     * @return the parsed non-negative value
     * @throws IllegalArgumentException if the field range falls outside the record, the field
     *                                  contains non-digits, or the value exceeds {@code long} range
     */
    public static long readNumeric(String record, int offset, int length) {
        checkReadBounds(record, offset, length, "numeric");
        String sub = record.substring(offset, offset + length);
        return parseUnsignedLong(sub, "numeric field at offset " + offset + " length " + length);
    }

    /**
     * Reads a COBOL {@code PIC 9(length)} field as an {@code int}.
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param offset the zero-based offset of the field
     * @param length the field width
     * @return the parsed non-negative value
     * @throws IllegalArgumentException if the field is invalid or the value exceeds {@code int} range
     */
    public static int readNumericInt(String record, int offset, int length) {
        long value = readNumeric(record, offset, length);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "numeric field at offset " + offset + " length " + length
                            + " exceeds int range: " + value, ex);
        }
    }

    /**
     * Reads a COBOL {@code PIC S9(i)V9(scale)} DISPLAY field, decoding the zoned-decimal overpunch
     * on the last character and applying the implied decimal point. This is the parity-critical
     * reader (see the class documentation for the overpunch tables).
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param offset the zero-based offset of the field
     * @param length the field width ({@code i + scale})
     * @param scale  the number of implied fractional digits
     * @return the decoded value as a {@link BigDecimal} whose scale equals {@code scale}
     * @throws IllegalArgumentException if the field range falls outside the record, {@code scale}
     *                                  is invalid, the leading digits are not all digits, or the
     *                                  trailing character is not a valid overpunch/digit
     */
    public static BigDecimal readSignedDecimal(String record, int offset, int length, int scale) {
        checkReadBounds(record, offset, length, "signed-decimal");
        if (scale < 0 || scale >= length) {
            throw new IllegalArgumentException(
                    "signed-decimal field at offset " + offset + " has invalid scale " + scale
                            + " for length " + length);
        }
        String field = record.substring(offset, offset + length);
        String body = field.substring(0, length - 1);
        char last = field.charAt(length - 1);

        String context = "signed-decimal field at offset " + offset + " length " + length;
        requireDigits(body, context);

        boolean negative;
        int lastDigit;
        if (last >= '0' && last <= '9') {
            negative = false;
            lastDigit = last - '0';
        } else {
            int positiveIndex = POSITIVE_OVERPUNCH.indexOf(last);
            if (positiveIndex >= 0) {
                negative = false;
                lastDigit = positiveIndex;
            } else {
                int negativeIndex = NEGATIVE_OVERPUNCH.indexOf(last);
                if (negativeIndex >= 0) {
                    negative = true;
                    lastDigit = negativeIndex;
                } else {
                    throw new IllegalArgumentException(
                            "invalid overpunch character '" + last + "' in " + context);
                }
            }
        }

        String digits = body + (char) (PAD_ZERO + lastDigit);
        BigDecimal unscaled = new BigDecimal(new BigInteger(digits));
        BigDecimal value = unscaled.movePointLeft(scale);
        if (negative) {
            value = value.negate();
        }
        // The value already carries exactly `scale` fractional digits; HALF_UP is defensive only.
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Reads an {@link FieldType#ALPHANUMERIC} field described by {@code def}, verbatim.
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param def    the field descriptor; must not be {@code null} and must be alphanumeric
     * @return the raw substring for the field
     * @throws IllegalArgumentException if {@code def} is not alphanumeric or the range is invalid
     */
    public static String readAlphanumeric(String record, FieldDef def) {
        requireType(def, FieldType.ALPHANUMERIC);
        return readAlphanumeric(record, def.offset(), def.length());
    }

    /**
     * Reads an {@link FieldType#ALPHANUMERIC} field described by {@code def}, trimming trailing
     * spaces.
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param def    the field descriptor; must not be {@code null} and must be alphanumeric
     * @return the substring for the field with trailing spaces removed
     * @throws IllegalArgumentException if {@code def} is not alphanumeric or the range is invalid
     */
    public static String readAlphanumericTrimmed(String record, FieldDef def) {
        requireType(def, FieldType.ALPHANUMERIC);
        return readAlphanumericTrimmed(record, def.offset(), def.length());
    }

    /**
     * Reads a {@link FieldType#NUMERIC} field described by {@code def}.
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param def    the field descriptor; must not be {@code null} and must be numeric
     * @return the parsed non-negative value
     * @throws IllegalArgumentException if {@code def} is not numeric or the field is invalid
     */
    public static long readNumeric(String record, FieldDef def) {
        requireType(def, FieldType.NUMERIC);
        checkReadBounds(record, def.offset(), def.length(), def.name());
        String sub = record.substring(def.offset(), def.endOffset());
        return parseUnsignedLong(sub, "field '" + def.name() + "'");
    }

    /**
     * Reads a {@link FieldType#SIGNED_DECIMAL} field described by {@code def}, using the descriptor's
     * scale.
     *
     * @param record the full fixed-width record; must not be {@code null}
     * @param def    the field descriptor; must not be {@code null} and must be signed-decimal
     * @return the decoded value at the descriptor's scale
     * @throws IllegalArgumentException if {@code def} is not signed-decimal or the field is invalid
     */
    public static BigDecimal readSignedDecimal(String record, FieldDef def) {
        requireType(def, FieldType.SIGNED_DECIMAL);
        return readSignedDecimal(record, def.offset(), def.length(), def.scale());
    }

    // ------------------------------------------------------------------------
    // WRITE primitives (typed value -> fixed-width String of exactly `length`)
    // ------------------------------------------------------------------------

    /**
     * Renders a value into a COBOL {@code PIC X(length)} field: left-justified and right-padded with
     * spaces. A {@code null} value is treated as the empty string. A value longer than {@code length}
     * is truncated on the right, mirroring a COBOL {@code MOVE} into a shorter {@code PIC X} item.
     *
     * @param value  the text to render; may be {@code null}
     * @param length the field width; must be {@code > 0}
     * @return a string of exactly {@code length} characters
     * @throws IllegalArgumentException if {@code length <= 0}
     */
    public static String writeAlphanumeric(String value, int length) {
        requirePositiveLength(length, "alphanumeric");
        String text = (value == null) ? "" : sanitizeRecordDelimiters(value);
        if (text.length() > length) {
            return text.substring(0, length);
        }
        return padRight(text, length, PAD_SPACE);
    }

    /**
     * Replaces embedded record-delimiter control characters &mdash; line feed ({@code 0x0A}) and
     * carriage return ({@code 0x0D}) &mdash; inside an alphanumeric field value with spaces, so that a
     * data byte inside a field can never be confused with the physical newline used to frame the
     * fixed-width records on disk. The substitution is strictly one-for-one (one control character
     * becomes one space), so the field's character width &mdash; and therefore the record's fixed
     * length &mdash; is always preserved. Clean data (no embedded delimiters) is returned unchanged via
     * a fast path, so byte-for-byte golden output is unaffected. The rationale and the retained
     * trailing framing newline are recorded in decision log D36.
     *
     * @param value the raw field value (never {@code null} at the call site)
     * @return the value with any embedded {@code LF}/{@code CR} replaced by spaces
     */
    private static String sanitizeRecordDelimiters(String value) {
        // Fast path: the overwhelming majority of field values contain neither delimiter.
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\n' || chars[i] == '\r') {
                chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    /**
     * Renders a non-negative value into a COBOL {@code PIC 9(length)} field: left-padded with zeros
     * to the exact width. Never truncates &mdash; a value with more digits than {@code length}
     * overflows and is rejected.
     *
     * @param value  the non-negative integer to render
     * @param length the field width; must be {@code > 0}
     * @return a string of exactly {@code length} digit characters
     * @throws IllegalArgumentException if {@code length <= 0}, {@code value < 0}, or {@code value}
     *                                  has more than {@code length} digits
     */
    public static String writeNumeric(long value, int length) {
        requirePositiveLength(length, "numeric");
        if (value < 0) {
            throw new IllegalArgumentException(
                    "numeric value must be >= 0 but was " + value);
        }
        String digits = Long.toString(value);
        if (digits.length() > length) {
            throw new IllegalArgumentException(
                    "numeric value " + value + " does not fit in " + length + " digits");
        }
        return leftPad(digits, length, PAD_ZERO);
    }

    /**
     * Renders a {@link BigDecimal} into a COBOL {@code PIC S9(i)V9(scale)} DISPLAY field, applying
     * the implied decimal point and encoding the sign as a zoned-decimal overpunch on the last
     * character. This is the exact inverse of {@link #readSignedDecimal(String, int, int, int)}.
     *
     * <p>Zero is non-negative, so {@code 0.00} encodes with the positive-zero overpunch
     * ({@code "0000000000{"} for an 11-character field), matching COBOL positive-zero.</p>
     *
     * @param value  the amount to render; must not be {@code null}
     * @param length the field width ({@code i + scale}); must be {@code > 0}
     * @param scale  the number of implied fractional digits; must be {@code >= 0} and {@code < length}
     * @return a string of exactly {@code length} characters with the trailing overpunch
     * @throws IllegalArgumentException if {@code length <= 0}, {@code scale} is out of range, or the
     *                                  scaled integer value has more than {@code length} digits
     */
    public static String writeSignedDecimal(BigDecimal value, int length, int scale) {
        Objects.requireNonNull(value, "value must not be null");
        requirePositiveLength(length, "signed-decimal");
        if (scale < 0 || scale >= length) {
            throw new IllegalArgumentException(
                    "invalid signed-decimal scale " + scale + " for length " + length);
        }
        // Normalize to the field scale (HALF_UP per the AAP money rule); inputs are already scale 2.
        BigDecimal normalized = value.setScale(scale, RoundingMode.HALF_UP);
        boolean negative = normalized.signum() < 0;
        BigInteger unscaled = normalized.abs().movePointRight(scale).toBigIntegerExact();
        String digits = unscaled.toString();
        if (digits.length() > length) {
            throw new IllegalArgumentException(
                    "signed-decimal value " + value + " does not fit in " + length + " digits");
        }
        digits = leftPad(digits, length, PAD_ZERO);
        int lastDigit = digits.charAt(length - 1) - PAD_ZERO;
        char overpunch = overpunchChar(lastDigit, negative);
        return digits.substring(0, length - 1) + overpunch;
    }

    /**
     * Renders an {@link FieldType#ALPHANUMERIC} field described by {@code def}.
     *
     * @param value the text to render; may be {@code null}
     * @param def   the field descriptor; must not be {@code null} and must be alphanumeric
     * @return a string of exactly {@code def.length()} characters
     * @throws IllegalArgumentException if {@code def} is not alphanumeric
     */
    public static String writeAlphanumeric(String value, FieldDef def) {
        requireType(def, FieldType.ALPHANUMERIC);
        return writeAlphanumeric(value, def.length());
    }

    /**
     * Renders a {@link FieldType#NUMERIC} field described by {@code def}.
     *
     * @param value the non-negative integer to render
     * @param def   the field descriptor; must not be {@code null} and must be numeric
     * @return a string of exactly {@code def.length()} digit characters
     * @throws IllegalArgumentException if {@code def} is not numeric, {@code value < 0}, or overflow
     */
    public static String writeNumeric(long value, FieldDef def) {
        requireType(def, FieldType.NUMERIC);
        return writeNumeric(value, def.length());
    }

    /**
     * Renders a {@link FieldType#SIGNED_DECIMAL} field described by {@code def}, using the
     * descriptor's scale.
     *
     * @param value the amount to render; must not be {@code null}
     * @param def   the field descriptor; must not be {@code null} and must be signed-decimal
     * @return a string of exactly {@code def.length()} characters with the trailing overpunch
     * @throws IllegalArgumentException if {@code def} is not signed-decimal or overflow occurs
     */
    public static String writeSignedDecimal(BigDecimal value, FieldDef def) {
        requireType(def, FieldType.SIGNED_DECIMAL);
        return writeSignedDecimal(value, def.length(), def.scale());
    }

    // ------------------------------------------------------------------------
    // Record assembly helper
    // ------------------------------------------------------------------------

    /**
     * Starts a {@link RecordBuilder} for a record of exactly {@code recordLength} characters,
     * initialized to all spaces. The builder places each field at its declared offset, freeing the
     * caller from manual buffer arithmetic.
     *
     * @param recordLength the total record width; must be {@code > 0}
     * @return a new, space-initialized {@link RecordBuilder}
     * @throws IllegalArgumentException if {@code recordLength <= 0}
     */
    public static RecordBuilder of(int recordLength) {
        return new RecordBuilder(recordLength);
    }

    /**
     * A short-lived, single-record assembly buffer. It is created by {@link #of(int)}, filled via the
     * {@code put(...)} overloads (which delegate to the {@code write*} primitives), and finalized by
     * {@link #build()}. Instances are mutable but strictly local; {@link FixedWidthCodec} itself
     * holds no mutable state.
     */
    public static final class RecordBuilder {

        private final char[] buffer;
        private final int recordLength;

        private RecordBuilder(int recordLength) {
            if (recordLength <= 0) {
                throw new IllegalArgumentException(
                        "record length must be > 0 but was " + recordLength);
            }
            this.recordLength = recordLength;
            this.buffer = new char[recordLength];
            Arrays.fill(this.buffer, PAD_SPACE);
        }

        /**
         * Writes an {@link FieldType#ALPHANUMERIC} field at its declared offset.
         *
         * @param def   the field descriptor; must be alphanumeric
         * @param value the text to render; may be {@code null}
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code def} is not alphanumeric or lies out of range
         */
        public RecordBuilder put(FieldDef def, String value) {
            requireType(def, FieldType.ALPHANUMERIC);
            return placeField(def, writeAlphanumeric(value, def.length()));
        }

        /**
         * Writes a {@link FieldType#NUMERIC} field at its declared offset.
         *
         * @param def   the field descriptor; must be numeric
         * @param value the non-negative integer to render
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code def} is not numeric, overflow occurs, or the
         *                                  field lies out of range
         */
        public RecordBuilder put(FieldDef def, long value) {
            requireType(def, FieldType.NUMERIC);
            return placeField(def, writeNumeric(value, def.length()));
        }

        /**
         * Writes a {@link FieldType#SIGNED_DECIMAL} field at its declared offset.
         *
         * @param def   the field descriptor; must be signed-decimal
         * @param value the amount to render; must not be {@code null}
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code def} is not signed-decimal, overflow occurs, or
         *                                  the field lies out of range
         */
        public RecordBuilder put(FieldDef def, BigDecimal value) {
            requireType(def, FieldType.SIGNED_DECIMAL);
            return placeField(def, writeSignedDecimal(value, def.length(), def.scale()));
        }

        /**
         * Copies a pre-formatted segment verbatim at the given offset. Useful for placing an already
         * fixed-width block, such as the untouched 350-byte {@code DALYTRAN} image at offset 0 of a
         * {@code DALYREJS} record.
         *
         * @param offset  the zero-based offset at which to place the segment
         * @param segment the pre-formatted characters; must not be {@code null}
         * @return this builder, for chaining
         * @throws IllegalArgumentException if the segment lies out of range
         */
        public RecordBuilder putRaw(int offset, String segment) {
            Objects.requireNonNull(segment, "segment must not be null");
            placeRaw(offset, segment.length(), segment, "raw segment");
            return this;
        }

        private RecordBuilder placeField(FieldDef def, String formatted) {
            placeRaw(def.offset(), def.length(), formatted, "field '" + def.name() + "'");
            return this;
        }

        private void placeRaw(int offset, int length, String formatted, String context) {
            if (offset < 0) {
                throw new IllegalArgumentException(
                        context + " offset must be >= 0 but was " + offset);
            }
            if (offset + length > recordLength) {
                throw new IllegalArgumentException(
                        context + " range [" + offset + ", " + (offset + length)
                                + ") exceeds record length " + recordLength);
            }
            if (formatted.length() != length) {
                throw new IllegalStateException(
                        context + " rendered width " + formatted.length()
                                + " does not match field length " + length);
            }
            formatted.getChars(0, length, buffer, offset);
        }

        /**
         * Returns the assembled fixed-width record of exactly {@code recordLength} characters.
         *
         * @return the completed record string
         */
        public String build() {
            return new String(buffer);
        }
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    /**
     * Returns the zoned-decimal overpunch character for the given trailing {@code digit} and sign.
     *
     * @param digit    the trailing digit {@code 0..9}
     * @param negative whether the value is negative
     * @return the overpunch character from the positive or negative table
     */
    private static char overpunchChar(int digit, boolean negative) {
        return (negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH).charAt(digit);
    }

    /**
     * Validates that a field range lies wholly within {@code record}.
     */
    private static void checkReadBounds(String record, int offset, int length, String context) {
        Objects.requireNonNull(record, "record must not be null");
        if (offset < 0) {
            throw new IllegalArgumentException(
                    context + " offset must be >= 0 but was " + offset);
        }
        if (length <= 0) {
            throw new IllegalArgumentException(
                    context + " length must be > 0 but was " + length);
        }
        if (offset + length > record.length()) {
            throw new IllegalArgumentException(
                    context + " range [" + offset + ", " + (offset + length)
                            + ") exceeds record length " + record.length());
        }
    }

    /**
     * Parses an all-digit substring as an unsigned {@code long}, rejecting non-digits and overflow.
     */
    private static long parseUnsignedLong(String value, String context) {
        requireDigits(value, context);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    context + " value \"" + value + "\" exceeds long range", ex);
        }
    }

    /**
     * Verifies that every character in {@code value} is an ASCII digit.
     */
    private static void requireDigits(String value, String context) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                        context + " must contain only digits but found '" + c
                                + "' in \"" + value + "\"");
            }
        }
    }

    /**
     * Requires a field descriptor of a specific type.
     */
    private static void requireType(FieldDef def, FieldType expected) {
        Objects.requireNonNull(def, "field descriptor must not be null");
        if (def.type() != expected) {
            throw new IllegalArgumentException(
                    "field '" + def.name() + "' is " + def.type()
                            + " but " + expected + " was required");
        }
    }

    /**
     * Requires a strictly positive field width.
     */
    private static void requirePositiveLength(int length, String context) {
        if (length <= 0) {
            throw new IllegalArgumentException(
                    context + " length must be > 0 but was " + length);
        }
    }

    /**
     * Removes trailing {@code ' '} (space) characters only; leading and embedded spaces are kept.
     */
    private static String stripTrailingSpaces(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == PAD_SPACE) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Left-pads {@code value} with {@code pad} to {@code length}; returns {@code value} unchanged if
     * it is already at least {@code length} characters.
     */
    private static String leftPad(String value, int length, char pad) {
        if (value.length() >= length) {
            return value;
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = value.length(); i < length; i++) {
            sb.append(pad);
        }
        sb.append(value);
        return sb.toString();
    }

    /**
     * Right-pads {@code value} with {@code pad} to {@code length}; returns {@code value} unchanged if
     * it is already at least {@code length} characters.
     */
    private static String padRight(String value, int length, char pad) {
        if (value.length() >= length) {
            return value;
        }
        StringBuilder sb = new StringBuilder(length);
        sb.append(value);
        while (sb.length() < length) {
            sb.append(pad);
        }
        return sb.toString();
    }
}

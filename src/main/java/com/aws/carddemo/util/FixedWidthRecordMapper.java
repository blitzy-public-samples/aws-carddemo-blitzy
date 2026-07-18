package com.aws.carddemo.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Byte-exact serializer / deserializer for fixed-width flat-file records that mirror COBOL
 * {@code PIC}-clause record layouts.
 *
 * <p>This helper is the mechanism that guarantees external-interface parity for every flat-file
 * feed in the AWS CardDemo COBOL&#8594;Java migration (AAP &#167;0.6.4, &#167;0.6.2). It reproduces,
 * character-for-character, the layouts of the daily-transaction feed {@code DALYTRAN}
 * (350&#160;bytes, copybook {@code CVTRA06Y}), the reject file {@code DALYREJS}
 * (430&#160;bytes = 350-byte transaction + 80-byte reason), report outputs, and the ASCII test
 * fixtures. The canonical layout oracle is {@code legacy/cpy/CVTRA06Y.cpy}
 * ({@code DALYTRAN-RECORD}, {@code RECLN = 350}); its source-branch path is
 * {@code app/cpy/CVTRA06Y.cpy}.</p>
 *
 * <p><b>Encoding.</b> The mapper operates on <em>single-byte</em> text (US-ASCII / ISO-8859-1),
 * which is how the loaded/ASCII form of the feeds is represented (one Java {@code char} per byte,
 * so string indices equal byte offsets). Callers must not hand this mapper a multi-byte charset,
 * because that would break the fixed offsets. The native EBCDIC datasets under
 * {@code legacy/data/EBCDIC} remain the retained binary source-of-truth (AAP &#167;0.6.6); this
 * mapper handles the ASCII/loaded form used for load, test, and inter-process feeds.</p>
 *
 * <p><b>Field types.</b> Each field is one of {@link FieldType}:</p>
 * <ul>
 *   <li>{@link FieldType#TEXT} &#8660; COBOL {@code PIC X(n)}: left-justified, right-padded with
 *       spaces on write; the raw bytes are preserved on read (a trimmed accessor is offered
 *       separately).</li>
 *   <li>{@link FieldType#NUMERIC} &#8660; COBOL {@code PIC 9(n)}: right-justified, left-padded with
 *       {@code '0'} on write; digits only.</li>
 *   <li>{@link FieldType#SIGNED_DECIMAL} &#8660; COBOL {@code PIC S9(m)V99}: right-justified,
 *       zero-filled digits with an <em>implied</em> decimal point (the {@code '.'} is never stored)
 *       and a fixed fractional {@code scale}. Represented in Java as {@link BigDecimal}.</li>
 *   <li>{@link FieldType#FILLER} &#8660; COBOL {@code FILLER}: reserved bytes that consume record
 *       space, are emitted as spaces, and are never surfaced as a business field.</li>
 * </ul>
 *
 * <p><b>Signed numerics / overpunch.</b> COBOL signed {@code DISPLAY} numerics carry the sign as a
 * <em>trailing overpunch</em> on the final digit (the default sign position). The
 * {@code DALYTRAN} {@code AMT} field ({@code S9(09)V99}) is such a field: e.g. {@code "0000005047G"}
 * decodes to {@code +504.77} ({@code 'G'} = +7) and {@code "0000009190}"} decodes to
 * {@code -919.00} ({@code '}'} = -0). {@link #getSignedDecimal(String, String)} decodes and
 * {@link #encodeSignedDecimal(String, java.math.BigDecimal)} re-encodes this convention so that a
 * round trip against {@code legacy/data/ASCII/dailytran.txt} is byte-exact. Plain unsigned digit
 * strings also decode (as non-negative), so the mapper stays fully generic. Only the default
 * TRAILING sign position is implemented; LEADING or SEPARATE sign positions can be added if a
 * future feed requires them.</p>
 *
 * <p><b>Monetary safety.</b> Decimal fields use {@link BigDecimal} exclusively; no {@code float} or
 * {@code double} is used anywhere, preserving COBOL fixed-scale semantics (AAP &#167;0.6.1).</p>
 *
 * <p><b>Genericity &amp; reuse.</b> The mapper bakes in no feed-specific fields; the daily-transaction
 * DTO and the Spring Batch job configurations supply their own ordered {@link FieldDef} list per
 * layout. Multi-segment records such as {@code DALYREJS} are expressed by {@link #concat(FixedWidthRecordMapper)}.
 * Because {@link #parse(String)} yields a {@code name}&#8594;raw-slice map and {@link #format(Map)}
 * consumes one, the class adapts cleanly into a Spring Batch {@code LineMapper} (parse + typed
 * accessors) and {@code LineAggregator} (build a value map, then {@code format}) without importing
 * any framework type.</p>
 *
 * <p><b>Thread-safety.</b> Instances are immutable after construction (the layout and derived
 * offset tables are fixed and defensively copied); all methods are stateless with respect to the
 * instance, so a single mapper may be shared across threads.</p>
 *
 * <p>Origin: derived from the fixed-width-layout rule of the migration; layout oracle
 * {@code legacy/cpy/CVTRA06Y.cpy}.</p>
 */
public final class FixedWidthRecordMapper {

    /**
     * Category of a fixed-width field, mirroring the COBOL {@code PIC}-clause families.
     */
    public enum FieldType {

        /** COBOL {@code PIC X(n)} &#8212; left-justified alphanumeric text, right-padded with spaces. */
        TEXT,

        /** COBOL {@code PIC 9(n)} &#8212; unsigned display integer, right-justified, left-padded with zeros. */
        NUMERIC,

        /** COBOL {@code PIC S9(m)V99} &#8212; signed display decimal with an implied decimal point. */
        SIGNED_DECIMAL,

        /** COBOL {@code FILLER} &#8212; reserved bytes that consume space but hold no business value. */
        FILLER
    }

    /**
     * Immutable definition of a single field within a fixed-width record layout.
     *
     * <p>{@code scale} is meaningful only for {@link FieldType#SIGNED_DECIMAL} (the number of implied
     * fractional digits, typically {@code 2}); it must be {@code 0} for every other type. For a
     * {@code SIGNED_DECIMAL} field the total digit count equals {@code length} and the integer digit
     * count is {@code length - scale}.</p>
     *
     * @param name   the business field name; must be non-blank for every non-FILLER field and may be
     *               {@code null} for a {@link FieldType#FILLER} field
     * @param type   the field category; never {@code null}
     * @param length the fixed byte width of the field; must be positive
     * @param scale  the implied fractional-digit count for {@code SIGNED_DECIMAL}; {@code 0} otherwise
     */
    public record FieldDef(String name, FieldType type, int length, int scale) {

        /**
         * Canonical constructor with validation of the layout invariants.
         */
        public FieldDef {
            Objects.requireNonNull(type, "field type must not be null");
            if (length <= 0) {
                throw new IllegalArgumentException("field length must be positive: " + length);
            }
            if (type != FieldType.FILLER && (name == null || name.isBlank())) {
                throw new IllegalArgumentException(
                        "a non-FILLER field requires a non-blank name (type=" + type + ")");
            }
            if (type == FieldType.SIGNED_DECIMAL) {
                if (scale < 0 || scale >= length) {
                    throw new IllegalArgumentException(
                            "SIGNED_DECIMAL scale must be >= 0 and < length: scale=" + scale
                                    + ", length=" + length);
                }
            } else if (scale != 0) {
                throw new IllegalArgumentException(
                        "scale is only valid for SIGNED_DECIMAL fields, but type=" + type
                                + " had scale=" + scale);
            }
        }

        /**
         * Creates a {@link FieldType#TEXT} field ({@code PIC X(length)}).
         *
         * @param name   the field name; must be non-blank
         * @param length the byte width; must be positive
         * @return a text field definition
         */
        public static FieldDef text(String name, int length) {
            return new FieldDef(name, FieldType.TEXT, length, 0);
        }

        /**
         * Creates a {@link FieldType#NUMERIC} field ({@code PIC 9(length)}).
         *
         * @param name   the field name; must be non-blank
         * @param length the byte width; must be positive
         * @return an unsigned numeric field definition
         */
        public static FieldDef numeric(String name, int length) {
            return new FieldDef(name, FieldType.NUMERIC, length, 0);
        }

        /**
         * Creates a {@link FieldType#SIGNED_DECIMAL} field ({@code PIC S9(length-scale)V9(scale)}).
         *
         * @param name   the field name; must be non-blank
         * @param length the total digit count (integer digits + fractional digits); must be positive
         * @param scale  the implied fractional-digit count; must satisfy {@code 0 <= scale < length}
         * @return a signed-decimal field definition
         */
        public static FieldDef signedDecimal(String name, int length, int scale) {
            return new FieldDef(name, FieldType.SIGNED_DECIMAL, length, scale);
        }

        /**
         * Creates a {@link FieldType#FILLER} field of the given width. Filler fields are anonymous
         * (name {@code null}), are excluded from parse output, and are emitted as spaces on write.
         *
         * @param length the byte width; must be positive
         * @return a filler field definition
         */
        public static FieldDef filler(int length) {
            return new FieldDef(null, FieldType.FILLER, length, 0);
        }
    }

    /** Trailing overpunch characters for positive digits 0..9 (COBOL zoned-decimal, ASCII form). */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /** Trailing overpunch characters for negative digits 0..9 (COBOL zoned-decimal, ASCII form). */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    private final List<FieldDef> fields;
    private final Map<String, FieldDef> fieldsByName;
    private final Map<String, Integer> offsetsByName;
    private final int recordLength;

    /**
     * Builds a mapper from an ordered list of field definitions describing one record layout.
     *
     * <p>The list order is the physical field order; each field's offset is the sum of the lengths of
     * all preceding fields and the total record length is the sum of all field lengths. Named fields
     * must be unique.</p>
     *
     * @param layout the ordered field definitions; must be non-null, non-empty, and contain no null
     *               elements
     * @throws NullPointerException     if {@code layout} or any element is {@code null}
     * @throws IllegalArgumentException if the layout is empty or contains duplicate named fields
     */
    public FixedWidthRecordMapper(List<FieldDef> layout) {
        Objects.requireNonNull(layout, "layout must not be null");
        if (layout.isEmpty()) {
            throw new IllegalArgumentException("layout must contain at least one field");
        }
        // List.copyOf rejects null elements and yields an unmodifiable snapshot.
        List<FieldDef> copy = List.copyOf(layout);
        Map<String, FieldDef> byName = new LinkedHashMap<>();
        Map<String, Integer> offsets = new LinkedHashMap<>();
        int offset = 0;
        for (FieldDef field : copy) {
            if (field.type() != FieldType.FILLER) {
                if (byName.putIfAbsent(field.name(), field) != null) {
                    throw new IllegalArgumentException("duplicate field name: " + field.name());
                }
                offsets.put(field.name(), offset);
            }
            offset += field.length();
        }
        this.fields = copy;
        this.fieldsByName = Map.copyOf(byName);
        this.offsetsByName = Map.copyOf(offsets);
        this.recordLength = offset;
    }

    /**
     * Convenience factory building a mapper from a varargs field list.
     *
     * @param layout the ordered field definitions
     * @return a new mapper for the given layout
     */
    public static FixedWidthRecordMapper of(FieldDef... layout) {
        Objects.requireNonNull(layout, "layout must not be null");
        return new FixedWidthRecordMapper(List.of(layout));
    }

    /**
     * Convenience factory building a mapper from a field list.
     *
     * @param layout the ordered field definitions
     * @return a new mapper for the given layout
     */
    public static FixedWidthRecordMapper of(List<FieldDef> layout) {
        return new FixedWidthRecordMapper(layout);
    }

    /**
     * Returns a new mapper whose layout is this mapper's fields followed by {@code other}'s fields.
     *
     * <p>This expresses multi-segment records without duplicating layouts &#8212; for example the
     * 430-byte {@code DALYREJS} reject record is the 350-byte {@code DALYTRAN} layout concatenated
     * with an 80-byte reason segment.</p>
     *
     * @param other the layout to append; must not be {@code null} and must not share a named field
     *              with this layout
     * @return a new combined mapper
     * @throws IllegalArgumentException if the two layouts share a named field
     */
    public FixedWidthRecordMapper concat(FixedWidthRecordMapper other) {
        Objects.requireNonNull(other, "other must not be null");
        List<FieldDef> combined = new ArrayList<>(this.fields.size() + other.fields.size());
        combined.addAll(this.fields);
        combined.addAll(other.fields);
        return new FixedWidthRecordMapper(combined);
    }

    /**
     * Returns the total fixed record length in bytes (the sum of all field lengths).
     *
     * @return the record length
     */
    public int getRecordLength() {
        return recordLength;
    }

    /**
     * Returns the ordered, unmodifiable list of field definitions for this layout (including FILLER).
     *
     * @return the immutable field list
     */
    public List<FieldDef> getFields() {
        return fields;
    }

    /**
     * Indicates whether a named (non-FILLER) field exists in this layout.
     *
     * @param field the field name to test
     * @return {@code true} if the named field exists
     */
    public boolean hasField(String field) {
        return fieldsByName.containsKey(field);
    }

    // ------------------------------------------------------------------------------------------
    // Parse (deserialize): fixed-width line -> raw field values
    // ------------------------------------------------------------------------------------------

    /**
     * Splits a fixed-width record into a {@code name}&#8594;raw-substring map, excluding FILLER fields.
     *
     * <p>The returned values are the exact byte slices of each named field (no trimming, no numeric
     * interpretation), preserving byte-level fidelity. Iteration order of the returned map follows
     * the physical field order.</p>
     *
     * @param record the fixed-width record line; its length must equal {@link #getRecordLength()}
     * @return an ordered map of field name to raw field content (FILLER excluded)
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record.length()} does not equal the record length
     */
    public Map<String, String> parse(String record) {
        requireLength(record);
        Map<String, String> values = new LinkedHashMap<>();
        int offset = 0;
        for (FieldDef field : fields) {
            if (field.type() != FieldType.FILLER) {
                values.put(field.name(), record.substring(offset, offset + field.length()));
            }
            offset += field.length();
        }
        return values;
    }

    /**
     * Returns the raw (untrimmed) bytes of a named field, exactly as they appear in the record.
     *
     * @param record the fixed-width record; must match {@link #getRecordLength()}
     * @param field  the field name; must exist in this layout
     * @return the raw field slice
     * @throws IllegalArgumentException if the record length is wrong or the field is unknown
     */
    public String getText(String record, String field) {
        return slice(record, require(field));
    }

    /**
     * Returns the value of a named field with COBOL right-padding (trailing spaces) removed. This is
     * the precise inverse of the space padding applied to {@link FieldType#TEXT} fields on write.
     *
     * @param record the fixed-width record; must match {@link #getRecordLength()}
     * @param field  the field name; must exist in this layout
     * @return the field value with trailing spaces stripped
     * @throws IllegalArgumentException if the record length is wrong or the field is unknown
     */
    public String getTrimmedText(String record, String field) {
        return getText(record, field).stripTrailing();
    }

    /**
     * Interprets a {@link FieldType#NUMERIC} field as a non-negative {@code long}. Leading zeros are
     * ignored; an all-blank field is treated as {@code 0}.
     *
     * @param record the fixed-width record; must match {@link #getRecordLength()}
     * @param field  the field name; must be a NUMERIC field in this layout
     * @return the parsed non-negative integer value
     * @throws IllegalArgumentException if the field is not NUMERIC, the record length is wrong, or the
     *                                  content is not a valid unsigned integer
     */
    public long getNumeric(String record, String field) {
        FieldDef def = require(field);
        if (def.type() != FieldType.NUMERIC) {
            throw new IllegalArgumentException(
                    "field '" + field + "' is not NUMERIC (type=" + def.type()
                            + "); use getSignedDecimal for signed fields");
        }
        String raw = slice(record, def);
        String digits = raw.strip();
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "field '" + field + "' does not hold a valid unsigned integer: '" + raw + "'", ex);
        }
    }

    /**
     * Interprets a {@link FieldType#SIGNED_DECIMAL} field as a {@link BigDecimal}, applying the
     * field's implied decimal {@code scale} and decoding the COBOL trailing-overpunch sign.
     *
     * <p>Example: for a field of length {@code 11} and scale {@code 2}, {@code "0000005047G"} yields
     * {@code 504.77} and {@code "0000009190}"} yields {@code -919.00}. A field consisting entirely of
     * spaces yields zero at the configured scale.</p>
     *
     * @param record the fixed-width record; must match {@link #getRecordLength()}
     * @param field  the field name; must be a SIGNED_DECIMAL field in this layout
     * @return the signed decimal value with scale equal to the field's scale
     * @throws IllegalArgumentException if the field is not SIGNED_DECIMAL, the record length is wrong,
     *                                  or the content contains an invalid digit or sign character
     */
    public BigDecimal getSignedDecimal(String record, String field) {
        FieldDef def = require(field);
        if (def.type() != FieldType.SIGNED_DECIMAL) {
            throw new IllegalArgumentException(
                    "field '" + field + "' is not SIGNED_DECIMAL (type=" + def.type() + ")");
        }
        String raw = slice(record, def);
        if (raw.isBlank()) {
            return BigDecimal.ZERO.setScale(def.scale());
        }
        int lastIndex = raw.length() - 1;
        char signChar = raw.charAt(lastIndex);
        int finalDigit = digitOf(signChar);
        if (finalDigit < 0) {
            throw new IllegalArgumentException(
                    "field '" + field + "' has an invalid trailing sign character: '" + signChar + "'");
        }
        int signum = signumOf(signChar);
        StringBuilder magnitude = new StringBuilder(raw.length());
        for (int i = 0; i < lastIndex; i++) {
            char c = raw.charAt(i);
            if (c == ' ') {
                magnitude.append('0');
            } else if (c >= '0' && c <= '9') {
                magnitude.append(c);
            } else {
                throw new IllegalArgumentException(
                        "field '" + field + "' has a non-numeric character at offset " + i
                                + ": '" + c + "'");
            }
        }
        magnitude.append((char) ('0' + finalDigit));
        BigDecimal value = new BigDecimal(magnitude.toString()).movePointLeft(def.scale());
        return signum < 0 ? value.negate() : value;
    }

    // ------------------------------------------------------------------------------------------
    // Encode (typed value -> raw field content), for building records field-by-field
    // ------------------------------------------------------------------------------------------

    /**
     * Encodes a value into the raw content of a {@link FieldType#TEXT} field: truncated if longer than
     * the field, otherwise right-padded with spaces. A {@code null} value is treated as empty.
     *
     * @param field the field name; must be a TEXT field in this layout
     * @param value the value to encode; may be {@code null}
     * @return the raw field content, exactly {@code field.length()} characters
     * @throws IllegalArgumentException if the field is not TEXT or is unknown
     */
    public String encodeText(String field, String value) {
        FieldDef def = require(field);
        if (def.type() != FieldType.TEXT) {
            throw new IllegalArgumentException("field '" + field + "' is not TEXT (type=" + def.type() + ")");
        }
        return padText(value == null ? "" : value, def.length());
    }

    /**
     * Encodes a non-negative {@code long} into the raw content of a {@link FieldType#NUMERIC} field,
     * right-justified and left-padded with {@code '0'}.
     *
     * @param field the field name; must be a NUMERIC field in this layout
     * @param value the non-negative value to encode
     * @return the raw field content, exactly {@code field.length()} characters
     * @throws IllegalArgumentException if the field is not NUMERIC, the value is negative, or the value
     *                                  has more digits than the field can hold
     */
    public String encodeNumeric(String field, long value) {
        FieldDef def = require(field);
        if (def.type() != FieldType.NUMERIC) {
            throw new IllegalArgumentException("field '" + field + "' is not NUMERIC (type=" + def.type() + ")");
        }
        if (value < 0) {
            throw new IllegalArgumentException(
                    "NUMERIC field '" + field + "' cannot hold a negative value: " + value);
        }
        return padNumeric(Long.toString(value), def, field);
    }

    /**
     * Encodes a {@link BigDecimal} into the raw content of a {@link FieldType#SIGNED_DECIMAL} field:
     * zero-filled magnitude digits at the field's implied scale, with the sign carried as a trailing
     * overpunch on the final digit.
     *
     * <p>The value must be exactly representable at the field scale (excess fractional precision is a
     * caller error and raises {@link ArithmeticException}); rounding decisions belong to the business
     * layer, not to this byte-level mapper (AAP &#167;0.6.1).</p>
     *
     * @param field the field name; must be a SIGNED_DECIMAL field in this layout
     * @param value the value to encode; must not be {@code null}
     * @return the raw field content, exactly {@code field.length()} characters
     * @throws IllegalArgumentException if the field is not SIGNED_DECIMAL or the magnitude does not fit
     * @throws ArithmeticException      if {@code value} has more fractional digits than the field scale
     */
    public String encodeSignedDecimal(String field, BigDecimal value) {
        FieldDef def = require(field);
        if (def.type() != FieldType.SIGNED_DECIMAL) {
            throw new IllegalArgumentException(
                    "field '" + field + "' is not SIGNED_DECIMAL (type=" + def.type() + ")");
        }
        return encodeSignedDecimalRaw(value, def, field);
    }

    // ------------------------------------------------------------------------------------------
    // Format (serialize): raw field values -> fixed-width line
    // ------------------------------------------------------------------------------------------

    /**
     * Produces a fixed-width record of exactly {@link #getRecordLength()} characters from a map of
     * field values.
     *
     * <p>Each value is the raw content that should occupy its field; this method only normalizes width
     * and justification per field type:</p>
     * <ul>
     *   <li>{@link FieldType#TEXT}: truncated if longer than the field, otherwise right-padded with
     *       spaces.</li>
     *   <li>{@link FieldType#NUMERIC}: any decimal point is stripped, then right-justified and
     *       left-padded with {@code '0'}.</li>
     *   <li>{@link FieldType#SIGNED_DECIMAL}: a signed/decimal literal (containing {@code '.'} or a
     *       leading sign) is converted through {@link BigDecimal} and emitted with the trailing
     *       overpunch sign; otherwise the value is treated as already-zoned raw digits (an existing
     *       trailing overpunch character is preserved) and left-padded with {@code '0'}. Either form
     *       reproduces the canonical bytes for the {@code DALYTRAN} feed.</li>
     *   <li>{@link FieldType#FILLER}: emitted as spaces regardless of any supplied value.</li>
     * </ul>
     *
     * <p>A missing or {@code null} entry for a named field is treated as empty. The produced length is
     * asserted to equal the configured record length before returning, guaranteeing byte parity.</p>
     *
     * @param values the field-name to raw-value map; must not be {@code null} (individual entries may
     *               be absent or {@code null})
     * @return the assembled fixed-width record
     * @throws NullPointerException  if {@code values} is {@code null}
     * @throws IllegalStateException if any field or the assembled record has an unexpected length
     */
    public String format(Map<String, String> values) {
        Objects.requireNonNull(values, "values must not be null");
        StringBuilder builder = new StringBuilder(recordLength);
        for (FieldDef field : fields) {
            String piece = switch (field.type()) {
                case FILLER -> " ".repeat(field.length());
                case TEXT -> padText(orEmpty(values.get(field.name())), field.length());
                case NUMERIC -> padNumeric(orEmpty(values.get(field.name())), field, field.name());
                case SIGNED_DECIMAL -> formatSignedField(values.get(field.name()), field);
            };
            if (piece.length() != field.length()) {
                throw new IllegalStateException(
                        "internal error: field " + describe(field) + " produced " + piece.length()
                                + " chars, expected " + field.length());
            }
            builder.append(piece);
        }
        String result = builder.toString();
        if (result.length() != recordLength) {
            throw new IllegalStateException(
                    "formatted record length " + result.length() + " does not match expected "
                            + recordLength);
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Formats a SIGNED_DECIMAL field from either a decimal literal or an already-zoned raw string.
     */
    private static String formatSignedField(String value, FieldDef def) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            return encodeSignedDecimalRaw(BigDecimal.ZERO.setScale(def.scale()), def, def.name());
        }
        boolean decimalLiteral = trimmed.indexOf('.') >= 0
                || trimmed.charAt(0) == '+' || trimmed.charAt(0) == '-';
        if (decimalLiteral) {
            return encodeSignedDecimalRaw(new BigDecimal(trimmed), def, def.name());
        }
        if (trimmed.length() > def.length()) {
            throw new IllegalArgumentException(
                    "raw value '" + value + "' does not fit SIGNED_DECIMAL field '" + def.name()
                            + "' of length " + def.length());
        }
        return leftPadZero(trimmed, def.length());
    }

    /**
     * Encodes a decimal magnitude at the field scale with a trailing overpunch sign.
     */
    private static String encodeSignedDecimalRaw(BigDecimal value, FieldDef def, String field) {
        Objects.requireNonNull(value, "value must not be null for field '" + field + "'");
        BigDecimal scaled = value.setScale(def.scale(), RoundingMode.UNNECESSARY);
        String digits = scaled.abs().unscaledValue().toString();
        if (digits.length() > def.length()) {
            throw new IllegalArgumentException(
                    "value " + value + " does not fit SIGNED_DECIMAL field '" + field
                            + "' of length " + def.length());
        }
        String padded = leftPadZero(digits, def.length());
        int lastDigit = padded.charAt(padded.length() - 1) - '0';
        char signChar = overpunch(lastDigit, value.signum() < 0 ? -1 : 1);
        return padded.substring(0, padded.length() - 1) + signChar;
    }

    /**
     * Right-pads (or truncates) a text value to the field width using spaces.
     */
    private static String padText(String value, int length) {
        if (value.length() > length) {
            return value.substring(0, length);
        }
        if (value.length() == length) {
            return value;
        }
        return value + " ".repeat(length - value.length());
    }

    /**
     * Strips a stray decimal point, validates that the remainder is digits, and left-pads with zeros.
     */
    private static String padNumeric(String rawValue, FieldDef def, String field) {
        String digits = rawValue.strip();
        int dot = digits.indexOf('.');
        if (dot >= 0) {
            digits = digits.substring(0, dot) + digits.substring(dot + 1);
        }
        if (digits.isEmpty()) {
            digits = "0";
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                        "NUMERIC field '" + field + "' has a non-digit character in value: '"
                                + rawValue + "'");
            }
        }
        if (digits.length() > def.length()) {
            throw new IllegalArgumentException(
                    "value '" + rawValue + "' does not fit NUMERIC field '" + field
                            + "' of length " + def.length());
        }
        return leftPadZero(digits, def.length());
    }

    /**
     * Left-pads a digit string with {@code '0'} to the requested width (no-op if already wide enough).
     */
    private static String leftPadZero(String digits, int length) {
        if (digits.length() >= length) {
            return digits;
        }
        return "0".repeat(length - digits.length()) + digits;
    }

    /**
     * Resolves a named field definition or fails fast for an unknown name.
     */
    private FieldDef require(String field) {
        FieldDef def = fieldsByName.get(field);
        if (def == null) {
            throw new IllegalArgumentException("unknown field '" + field + "'");
        }
        return def;
    }

    /**
     * Returns the raw slice of a named field after validating the record length.
     */
    private String slice(String record, FieldDef def) {
        requireLength(record);
        int offset = offsetsByName.get(def.name());
        return record.substring(offset, offset + def.length());
    }

    /**
     * Validates that a record has exactly the configured length.
     */
    private void requireLength(String record) {
        Objects.requireNonNull(record, "record must not be null");
        if (record.length() != recordLength) {
            throw new IllegalArgumentException(
                    "record length " + record.length() + " does not match expected " + recordLength);
        }
    }

    /**
     * Decodes the digit value (0..9) of a plain digit or an overpunch character; {@code -1} if invalid.
     */
    private static int digitOf(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        for (int i = 0; i < POSITIVE_OVERPUNCH.length; i++) {
            if (POSITIVE_OVERPUNCH[i] == c) {
                return i;
            }
        }
        for (int i = 0; i < NEGATIVE_OVERPUNCH.length; i++) {
            if (NEGATIVE_OVERPUNCH[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns {@code -1} for a negative overpunch character, otherwise {@code 1}.
     */
    private static int signumOf(char c) {
        for (char negative : NEGATIVE_OVERPUNCH) {
            if (negative == c) {
                return -1;
            }
        }
        return 1;
    }

    /**
     * Returns the overpunch character for a digit (0..9) and sign ({@code -1} negative, else positive).
     */
    private static char overpunch(int digit, int signum) {
        return signum < 0 ? NEGATIVE_OVERPUNCH[digit] : POSITIVE_OVERPUNCH[digit];
    }

    /**
     * Coalesces a possibly-null field value to an empty string.
     */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Renders a short human-readable description of a field for diagnostics.
     */
    private static String describe(FieldDef def) {
        return (def.name() == null ? "<filler>" : def.name()) + " (" + def.type() + ")";
    }

    /**
     * Returns a diagnostic summary of this layout (record length and field count).
     *
     * @return a short description of the mapper
     */
    @Override
    public String toString() {
        return "FixedWidthRecordMapper[recordLength=" + recordLength
                + ", fields=" + fields.size() + "]";
    }
}


package com.aws.carddemo.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <em>byte-for-byte</em>, the layouts of the daily-transaction feed {@code DALYTRAN}
 * (350&#160;bytes, copybook {@code CVTRA06Y}), the reject file {@code DALYREJS}
 * (430&#160;bytes = 350-byte transaction + 80-byte reason), report outputs, and the ASCII test
 * fixtures. The canonical layout oracle is {@code legacy/cpy/CVTRA06Y.cpy}
 * ({@code DALYTRAN-RECORD}, {@code RECLN = 350}); its source-branch path is
 * {@code app/cpy/CVTRA06Y.cpy}.</p>
 *
 * <p><b>Byte boundary and encoding.</b> The external boundary of this mapper is {@code byte[]}, not
 * {@link String}: {@link #parse(byte[])} consumes raw record bytes and {@link RecordBuilder#build()}
 * / {@link ParsedRecord#toByteArray()} produce them, so record and field widths are always measured
 * in <em>encoded bytes</em> (never UTF-16 code units). Each mapper owns an explicit
 * <em>single-byte</em> {@link Charset}; the default is {@link StandardCharsets#ISO_8859_1}, which maps
 * every byte value {@code 0x00}-{@code 0xFF} to exactly one character and back, guaranteeing a lossless
 * round trip for arbitrary record content (including the non-space {@code FILLER} bytes and signed
 * overpunch characters found in the real fixtures). A multi-byte charset is rejected at construction
 * because it would break the fixed offsets. The native EBCDIC datasets under
 * {@code legacy/data/EBCDIC} remain the retained binary source-of-truth (AAP &#167;0.6.6); this mapper
 * handles the ASCII/loaded form used for load, test, and inter-process feeds.</p>
 *
 * <p><b>Field types.</b> Each field is one of {@link FieldType}:</p>
 * <ul>
 *   <li>{@link FieldType#TEXT} &#8660; COBOL {@code PIC X(n)}: left-justified, right-padded with
 *       spaces when written; the raw bytes are preserved on read (a trimmed accessor is offered
 *       separately).</li>
 *   <li>{@link FieldType#NUMERIC} &#8660; COBOL {@code PIC 9(n)}: right-justified, left-padded with
 *       {@code '0'} when written; digits only.</li>
 *   <li>{@link FieldType#SIGNED_DECIMAL} &#8660; COBOL {@code PIC S9(m)V99}: right-justified,
 *       zero-filled digits with an <em>implied</em> decimal point (the {@code '.'} is never stored)
 *       and a fixed fractional {@code scale}. Represented in Java as {@link BigDecimal}.</li>
 *   <li>{@link FieldType#FILLER} &#8660; COBOL {@code FILLER}: reserved bytes that consume record
 *       space and hold no business field. Their bytes are <em>modeled and preserved</em>: a record
 *       parsed and re-emitted via {@link ParsedRecord#toByteArray()} reproduces the original filler
 *       bytes exactly (they are not silently rewritten as spaces). Freshly built records default
 *       filler to spaces unless seeded from an existing record via {@link #newRecord(byte[])}.</li>
 * </ul>
 *
 * <p><b>Signed numerics / overpunch.</b> COBOL signed {@code DISPLAY} numerics carry the sign as a
 * <em>trailing overpunch</em> on the final digit (the default sign position). The {@code DALYTRAN}
 * {@code AMT} field ({@code S9(09)V99}) is such a field: e.g. {@code "0000005047G"} decodes to
 * {@code +504.77} ({@code 'G'} = +7) and {@code "0000005047}"} decodes to {@code -504.70}
 * ({@code '}'} = -0). {@link ParsedRecord#getSignedDecimal(String)} decodes and
 * {@link RecordBuilder#setSignedDecimal(String, BigDecimal)} re-encodes this convention so a round
 * trip against {@code legacy/data/ASCII/dailytran.txt} is byte-exact. Because {@link BigDecimal}
 * cannot represent negative zero, the stored sign is exposed independently via
 * {@link ParsedRecord#isNegative(String)} (which returns {@code true} for a negative overpunch even
 * when the magnitude is zero), and negative zero can be written explicitly through
 * {@link RecordBuilder#setSignedDecimal(String, BigDecimal, boolean)}. Only the default TRAILING sign
 * position is implemented; LEADING or SEPARATE sign positions can be added if a future feed requires
 * them.</p>
 *
 * <p><b>No truncation.</b> A value that does not fit its field is <em>rejected</em> with an exception;
 * it is never silently truncated. Every numeric and overpunch byte is validated on read, and the
 * decimal scale is validated on write ({@link RoundingMode#UNNECESSARY}).</p>
 *
 * <p><b>Sensitive data.</b> No exception message emitted by this mapper contains raw field content
 * (CWE-532). Diagnostics are limited to the layout metadata that is safe to log: field name, field
 * type, byte offset, and expected/actual lengths &#8212; never the decoded business value, digit
 * bytes, or sign character.</p>
 *
 * <p><b>Monetary safety.</b> Decimal fields use {@link BigDecimal} exclusively; no {@code float} or
 * {@code double} is used anywhere, preserving COBOL fixed-scale semantics (AAP &#167;0.6.1).</p>
 *
 * <p><b>Genericity &amp; reuse.</b> The mapper bakes in no feed-specific fields; the daily-transaction
 * DTO and the Spring Batch job configurations supply their own ordered {@link FieldDef} list per
 * layout. Multi-segment records such as {@code DALYREJS} are expressed by
 * {@link #concat(FixedWidthRecordMapper)}. {@link #parse(byte[])} yields a {@link ParsedRecord} with
 * typed accessors (a Spring Batch {@code LineMapper}) and {@link #newRecord()} yields a
 * {@link RecordBuilder} (a Spring Batch {@code LineAggregator}) &#8212; without importing any framework
 * type.</p>
 *
 * <p><b>Thread-safety.</b> The mapper is immutable after construction (the layout and derived offset
 * tables are fixed and defensively copied) and holds no mutable state, so a single mapper may be
 * shared across threads. {@link ParsedRecord} and {@link RecordBuilder} are per-record and not shared.</p>
 *
 * <p>Origin: derived from the fixed-width-layout rule of the migration; layout oracle
 * {@code legacy/cpy/CVTRA06Y.cpy}.</p>
 */
public final class FixedWidthRecordMapper {

    /** The ASCII space byte used to pad {@link FieldType#TEXT} and default {@link FieldType#FILLER}. */
    private static final byte SPACE = (byte) ' ';

    /** The ASCII {@code '0'} byte used to left-pad numeric fields. */
    private static final byte ZERO = (byte) '0';

    /** Trailing overpunch bytes for positive digits 0..9 (COBOL zoned-decimal, ASCII form). */
    private static final byte[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /** Trailing overpunch bytes for negative digits 0..9 (COBOL zoned-decimal, ASCII form). */
    private static final byte[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

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
         * (name {@code null}) and are excluded from named access; their bytes are preserved on a
         * parse/re-emit round trip and default to spaces in a freshly built record.
         *
         * @param length the byte width; must be positive
         * @return a filler field definition
         */
        public static FieldDef filler(int length) {
            return new FieldDef(null, FieldType.FILLER, length, 0);
        }
    }

    private final List<FieldDef> fields;
    private final Map<String, FieldDef> fieldsByName;
    private final Map<String, Integer> offsetsByName;
    private final int recordLength;
    private final Charset charset;

    /**
     * Builds a mapper from an ordered list of field definitions using the default single-byte charset
     * {@link StandardCharsets#ISO_8859_1}.
     *
     * @param layout the ordered field definitions
     * @see #FixedWidthRecordMapper(List, Charset)
     */
    public FixedWidthRecordMapper(List<FieldDef> layout) {
        this(layout, StandardCharsets.ISO_8859_1);
    }

    /**
     * Builds a mapper from an ordered list of field definitions and an explicit single-byte charset.
     *
     * <p>The list order is the physical field order; each field's offset is the sum of the lengths of
     * all preceding fields and the total record length is the sum of all field lengths. Named fields
     * must be unique. The charset must encode every character to a single byte (verified via its
     * encoder's {@code maxBytesPerChar}); a multi-byte charset would break the fixed offsets and is
     * rejected.</p>
     *
     * @param layout  the ordered field definitions; must be non-null, non-empty, and contain no null
     *                elements
     * @param charset the single-byte charset used to decode/encode {@link FieldType#TEXT} content
     * @throws NullPointerException     if {@code layout}, any element, or {@code charset} is {@code null}
     * @throws IllegalArgumentException if the layout is empty, contains duplicate named fields, or the
     *                                  charset is not single-byte
     */
    public FixedWidthRecordMapper(List<FieldDef> layout, Charset charset) {
        Objects.requireNonNull(layout, "layout must not be null");
        this.charset = requireSingleByte(charset);
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
     * Convenience factory building a mapper from a varargs field list (default charset).
     *
     * @param layout the ordered field definitions
     * @return a new mapper for the given layout
     */
    public static FixedWidthRecordMapper of(FieldDef... layout) {
        Objects.requireNonNull(layout, "layout must not be null");
        return new FixedWidthRecordMapper(List.of(layout));
    }

    /**
     * Convenience factory building a mapper from a field list (default charset).
     *
     * @param layout the ordered field definitions
     * @return a new mapper for the given layout
     */
    public static FixedWidthRecordMapper of(List<FieldDef> layout) {
        return new FixedWidthRecordMapper(layout);
    }

    /**
     * Returns a copy of this mapper that uses the given single-byte charset while keeping the same
     * layout.
     *
     * @param newCharset the replacement single-byte charset
     * @return a new mapper with the same fields and the given charset
     * @throws IllegalArgumentException if {@code newCharset} is not single-byte
     */
    public FixedWidthRecordMapper withCharset(Charset newCharset) {
        return new FixedWidthRecordMapper(this.fields, newCharset);
    }

    /**
     * Returns a new mapper whose layout is this mapper's fields followed by {@code other}'s fields,
     * using this mapper's charset.
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
        return new FixedWidthRecordMapper(combined, this.charset);
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
     * Returns the single-byte charset this mapper uses to decode/encode {@link FieldType#TEXT} content.
     *
     * @return the charset
     */
    public Charset getCharset() {
        return charset;
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
    // Parse (deserialize): fixed-width bytes -> ParsedRecord
    // ------------------------------------------------------------------------------------------

    /**
     * Parses a fixed-width record from its raw bytes, returning a {@link ParsedRecord} that retains the
     * exact bytes (so {@link ParsedRecord#toByteArray()} reproduces the record byte-for-byte, including
     * FILLER and signed-zero overpunch) and exposes typed, validated accessors.
     *
     * @param record the raw record bytes; length must equal {@link #getRecordLength()}
     * @return a parsed record bound to this layout
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} does not equal the record length
     */
    public ParsedRecord parse(byte[] record) {
        Objects.requireNonNull(record, "record must not be null");
        if (record.length != recordLength) {
            throw new IllegalArgumentException(
                    "record byte length " + record.length + " does not match expected " + recordLength);
        }
        return new ParsedRecord(this, record.clone());
    }

    // ------------------------------------------------------------------------------------------
    // Build (serialize): typed field values -> fixed-width bytes
    // ------------------------------------------------------------------------------------------

    /**
     * Starts building a new fixed-width record. Every field is initialized to its COBOL default
     * (TEXT/FILLER = spaces, NUMERIC = zeros, SIGNED_DECIMAL = zero magnitude with a positive
     * overpunch); setters overwrite individual fields.
     *
     * @return a fresh record builder for this layout
     */
    public RecordBuilder newRecord() {
        return new RecordBuilder(null);
    }

    /**
     * Starts building a new record seeded from an existing record's bytes, so any bytes not explicitly
     * overwritten &#8212; notably FILLER regions &#8212; are preserved exactly. This supports the
     * "read, modify a few fields, re-emit" pattern with byte-level fidelity.
     *
     * @param template the seed bytes; length must equal {@link #getRecordLength()}
     * @return a record builder pre-populated from {@code template}
     * @throws NullPointerException     if {@code template} is {@code null}
     * @throws IllegalArgumentException if {@code template.length} does not equal the record length
     */
    public RecordBuilder newRecord(byte[] template) {
        Objects.requireNonNull(template, "template must not be null");
        if (template.length != recordLength) {
            throw new IllegalArgumentException(
                    "template byte length " + template.length + " does not match expected " + recordLength);
        }
        return new RecordBuilder(template.clone());
    }

    // ------------------------------------------------------------------------------------------
    // Internal layout helpers (sanitized diagnostics only: name/type/offset/length; never content)
    // ------------------------------------------------------------------------------------------

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
     * Resolves a named field and asserts its type, using only layout metadata in any error message.
     */
    private FieldDef requireType(String field, FieldType expected) {
        FieldDef def = require(field);
        if (def.type() != expected) {
            throw new IllegalArgumentException(
                    "field '" + field + "' is " + def.type() + ", expected " + expected);
        }
        return def;
    }

    /**
     * Validates that {@code charset} encodes every character to a single byte.
     */
    private static Charset requireSingleByte(Charset charset) {
        Objects.requireNonNull(charset, "charset must not be null");
        float maxBytes = charset.newEncoder().maxBytesPerChar();
        if (maxBytes != 1.0f) {
            throw new IllegalArgumentException(
                    "charset '" + charset.name() + "' is not single-byte (maxBytesPerChar="
                            + maxBytes + "); fixed-width offsets require a single-byte charset");
        }
        return charset;
    }

    /** Whether {@code b} (an unsigned byte value 0..255) is an ASCII digit {@code '0'}..{@code '9'}. */
    private static boolean isAsciiDigit(int b) {
        return b >= '0' && b <= '9';
    }

    /**
     * Decodes the digit value (0..9) of a plain ASCII digit or a zoned overpunch byte; {@code -1} if
     * the byte is neither.
     */
    private static int digitOfByte(int b) {
        if (isAsciiDigit(b)) {
            return b - '0';
        }
        for (int i = 0; i < POSITIVE_OVERPUNCH.length; i++) {
            if ((POSITIVE_OVERPUNCH[i] & 0xFF) == b) {
                return i;
            }
        }
        for (int i = 0; i < NEGATIVE_OVERPUNCH.length; i++) {
            if ((NEGATIVE_OVERPUNCH[i] & 0xFF) == b) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code b} (an unsigned byte value 0..255) is a negative-sign overpunch byte. */
    private static boolean isNegativeOverpunchByte(int b) {
        for (byte negative : NEGATIVE_OVERPUNCH) {
            if ((negative & 0xFF) == b) {
                return true;
            }
        }
        return false;
    }

    /** Returns the overpunch byte for a digit (0..9) and sign. */
    private static byte overpunchByte(int digit, boolean negative) {
        return negative ? NEGATIVE_OVERPUNCH[digit] : POSITIVE_OVERPUNCH[digit];
    }

    /**
     * Returns a diagnostic summary of this layout (record length and field count only).
     *
     * @return a short, non-sensitive description of the mapper
     */
    @Override
    public String toString() {
        return "FixedWidthRecordMapper[recordLength=" + recordLength
                + ", fields=" + fields.size() + ", charset=" + charset.name() + "]";
    }

    // ==========================================================================================
    // ParsedRecord: an immutable view over one record's raw bytes with typed, validated accessors
    // ==========================================================================================

    /**
     * An immutable, byte-exact view over a single parsed fixed-width record.
     *
     * <p>The record's raw bytes are retained in full, so {@link #toByteArray()} reproduces the input
     * exactly &#8212; including {@link FieldType#FILLER} bytes and signed-zero overpunch that a
     * value-only representation would lose. Typed accessors decode individual fields on demand and
     * validate every byte; none of their exception messages contain raw field content (CWE-532).</p>
     */
    public static final class ParsedRecord {

        private final FixedWidthRecordMapper mapper;
        private final byte[] raw;

        private ParsedRecord(FixedWidthRecordMapper mapper, byte[] raw) {
            this.mapper = mapper;
            this.raw = raw;
        }

        /**
         * Returns the exact record bytes (a defensive copy), reproducing the parsed input byte-for-byte.
         *
         * @return a copy of the record bytes
         */
        public byte[] toByteArray() {
            return raw.clone();
        }

        /**
         * Returns the raw bytes of a named field (a defensive copy), exactly as they appear in the record.
         *
         * @param field the field name; must exist in this layout
         * @return the raw field bytes
         * @throws IllegalArgumentException if the field is unknown
         */
        public byte[] getRawBytes(String field) {
            FieldDef def = mapper.require(field);
            int off = mapper.offsetsByName.get(field);
            return Arrays.copyOfRange(raw, off, off + def.length());
        }

        /**
         * Decodes a {@link FieldType#TEXT} field to a string via the mapper's charset, preserving all
         * bytes (no trimming).
         *
         * @param field the field name; must be a TEXT field
         * @return the decoded, untrimmed field value
         * @throws IllegalArgumentException if the field is unknown or not TEXT
         */
        public String getText(String field) {
            FieldDef def = mapper.requireType(field, FieldType.TEXT);
            int off = mapper.offsetsByName.get(field);
            return new String(raw, off, def.length(), mapper.charset);
        }

        /**
         * Like {@link #getText(String)} but with COBOL right-padding (trailing spaces) removed.
         *
         * @param field the field name; must be a TEXT field
         * @return the decoded field value with trailing spaces stripped
         * @throws IllegalArgumentException if the field is unknown or not TEXT
         */
        public String getTrimmedText(String field) {
            return getText(field).stripTrailing();
        }

        /**
         * Interprets a {@link FieldType#NUMERIC} field as a non-negative {@code long}. An all-space
         * field is treated as {@code 0}; every other byte must be an ASCII digit.
         *
         * @param field the field name; must be a NUMERIC field
         * @return the parsed non-negative integer value
         * @throws IllegalArgumentException if the field is unknown, not NUMERIC, or contains a non-digit
         *                                  byte (the message identifies only the offset, never the byte)
         */
        public long getNumeric(String field) {
            FieldDef def = mapper.requireType(field, FieldType.NUMERIC);
            int off = mapper.offsetsByName.get(field);
            long value = 0L;
            boolean allSpace = true;
            for (int i = 0; i < def.length(); i++) {
                int b = raw[off + i] & 0xFF;
                if (b == (SPACE & 0xFF)) {
                    continue;
                }
                allSpace = false;
                if (!isAsciiDigit(b)) {
                    throw new IllegalArgumentException(
                            "NUMERIC field '" + field + "' has a non-digit byte at offset " + (off + i));
                }
                value = value * 10 + (b - '0');
            }
            return allSpace ? 0L : value;
        }

        /**
         * Interprets a {@link FieldType#SIGNED_DECIMAL} field as a {@link BigDecimal} at the field's
         * scale, decoding the COBOL trailing-overpunch sign. An all-space field yields zero at scale.
         *
         * <p>Because {@link BigDecimal} has no negative zero, a stored negative zero decodes to a
         * non-negative zero here; use {@link #isNegative(String)} to recover the stored sign.</p>
         *
         * @param field the field name; must be a SIGNED_DECIMAL field
         * @return the signed decimal value with scale equal to the field's scale
         * @throws IllegalArgumentException if the field is unknown, not SIGNED_DECIMAL, or contains an
         *                                  invalid digit/sign byte (the message identifies only the
         *                                  offset, never the byte or value)
         */
        public BigDecimal getSignedDecimal(String field) {
            FieldDef def = mapper.requireType(field, FieldType.SIGNED_DECIMAL);
            int off = mapper.offsetsByName.get(field);
            int last = def.length() - 1;
            if (isAllSpace(off, def.length())) {
                return BigDecimal.ZERO.setScale(def.scale());
            }
            int finalDigit = digitOfByte(raw[off + last] & 0xFF);
            if (finalDigit < 0) {
                throw new IllegalArgumentException(
                        "SIGNED_DECIMAL field '" + field + "' has an invalid trailing sign byte at offset "
                                + (off + last));
            }
            boolean negative = isNegativeOverpunchByte(raw[off + last] & 0xFF);
            StringBuilder magnitude = new StringBuilder(def.length());
            for (int i = 0; i < last; i++) {
                int b = raw[off + i] & 0xFF;
                if (!isAsciiDigit(b)) {
                    throw new IllegalArgumentException(
                            "SIGNED_DECIMAL field '" + field + "' has a non-digit byte at offset "
                                    + (off + i));
                }
                magnitude.append((char) b);
            }
            magnitude.append((char) ('0' + finalDigit));
            BigDecimal value = new BigDecimal(magnitude.toString())
                    .movePointLeft(def.scale())
                    .setScale(def.scale());
            return negative ? value.negate() : value;
        }

        /**
         * Reports the stored sign of a {@link FieldType#SIGNED_DECIMAL} field independently of its
         * magnitude, so signed zero is recoverable. Returns {@code true} iff the trailing byte is a
         * negative overpunch character; an all-space (uninitialized) field is non-negative.
         *
         * @param field the field name; must be a SIGNED_DECIMAL field
         * @return {@code true} if the field carries a negative sign (including negative zero)
         * @throws IllegalArgumentException if the field is unknown or not SIGNED_DECIMAL
         */
        public boolean isNegative(String field) {
            FieldDef def = mapper.requireType(field, FieldType.SIGNED_DECIMAL);
            int off = mapper.offsetsByName.get(field);
            return isNegativeOverpunchByte(raw[off + def.length() - 1] & 0xFF);
        }

        /**
         * Returns a builder seeded from this record's bytes, preserving unmodified fields (including
         * FILLER) exactly.
         *
         * @return a record builder pre-populated from this record
         */
        public RecordBuilder toBuilder() {
            return mapper.newRecord(raw);
        }

        private boolean isAllSpace(int off, int length) {
            for (int i = 0; i < length; i++) {
                if ((raw[off + i] & 0xFF) != (SPACE & 0xFF)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Returns a non-sensitive description (no field content is exposed).
         *
         * @return a short description
         */
        @Override
        public String toString() {
            return "FixedWidthRecordMapper.ParsedRecord[recordLength=" + raw.length + "]";
        }
    }

    // ==========================================================================================
    // RecordBuilder: assembles a byte-exact record; rejects (never truncates) overlong values
    // ==========================================================================================

    /**
     * A mutable, single-use builder that assembles one fixed-width record. Setters validate and reject
     * (never truncate) values that do not fit, validate numeric content, and encode the COBOL
     * justification/overpunch conventions. No setter exception message contains raw field content
     * (CWE-532). The builder is not thread-safe and is intended to be used by one thread and discarded.
     */
    public final class RecordBuilder {

        private final byte[] buffer;

        private RecordBuilder(byte[] template) {
            if (template != null) {
                this.buffer = template;
            } else {
                this.buffer = new byte[recordLength];
                initDefaults();
            }
        }

        private void initDefaults() {
            int off = 0;
            for (FieldDef field : fields) {
                switch (field.type()) {
                    case TEXT, FILLER -> fill(off, field.length(), SPACE);
                    case NUMERIC -> fill(off, field.length(), ZERO);
                    case SIGNED_DECIMAL -> {
                        fill(off, field.length() - 1, ZERO);
                        buffer[off + field.length() - 1] = POSITIVE_OVERPUNCH[0];
                    }
                    default -> throw new IllegalStateException("unhandled field type: " + field.type());
                }
                off += field.length();
            }
        }

        /**
         * Sets a {@link FieldType#TEXT} field, right-padding with spaces. A value whose encoded byte
         * length exceeds the field width is rejected, not truncated. A {@code null} value is treated as
         * empty.
         *
         * @param field the field name; must be a TEXT field
         * @param value the value to encode; may be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if the field is unknown, not TEXT, or the value is too long
         */
        public RecordBuilder setText(String field, String value) {
            FieldDef def = requireType(field, FieldType.TEXT);
            int off = offsetsByName.get(field);
            byte[] encoded = (value == null ? "" : value).getBytes(charset);
            if (encoded.length > def.length()) {
                throw new IllegalArgumentException(
                        "TEXT field '" + field + "' value of " + encoded.length
                                + " bytes exceeds field length " + def.length());
            }
            for (int k = 0; k < def.length(); k++) {
                buffer[off + k] = k < encoded.length ? encoded[k] : SPACE;
            }
            return this;
        }

        /**
         * Sets a {@link FieldType#NUMERIC} field, right-justified and left-padded with {@code '0'}. A
         * negative value, or a value with more digits than the field can hold, is rejected.
         *
         * @param field the field name; must be a NUMERIC field
         * @param value the non-negative value to encode
         * @return this builder
         * @throws IllegalArgumentException if the field is unknown, not NUMERIC, the value is negative,
         *                                  or the value has too many digits
         */
        public RecordBuilder setNumeric(String field, long value) {
            FieldDef def = requireType(field, FieldType.NUMERIC);
            if (value < 0) {
                throw new IllegalArgumentException(
                        "NUMERIC field '" + field + "' requires a non-negative value");
            }
            String digits = Long.toString(value);
            if (digits.length() > def.length()) {
                throw new IllegalArgumentException(
                        "NUMERIC field '" + field + "' value of " + digits.length()
                                + " digits exceeds field length " + def.length());
            }
            int off = offsetsByName.get(field);
            int pad = def.length() - digits.length();
            for (int k = 0; k < def.length(); k++) {
                buffer[off + k] = k < pad ? ZERO : (byte) digits.charAt(k - pad);
            }
            return this;
        }

        /**
         * Sets a {@link FieldType#SIGNED_DECIMAL} field with a positive/negative sign derived from the
         * value's own signum (zero is encoded as positive).
         *
         * @param field the field name; must be a SIGNED_DECIMAL field
         * @param value the value to encode; must not be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if the field is unknown, not SIGNED_DECIMAL, or the magnitude
         *                                  does not fit
         * @throws ArithmeticException      if {@code value} has more fractional digits than the scale
         */
        public RecordBuilder setSignedDecimal(String field, BigDecimal value) {
            return setSignedDecimal(field, value, false);
        }

        /**
         * Sets a {@link FieldType#SIGNED_DECIMAL} field, allowing an explicit negative zero. The stored
         * sign is negative when the value is negative, or when the value is zero and {@code negativeZero}
         * is {@code true}; otherwise it is positive. Excess fractional precision is rejected (the scale
         * is validated with {@link RoundingMode#UNNECESSARY}); a magnitude that does not fit is rejected.
         *
         * @param field        the field name; must be a SIGNED_DECIMAL field
         * @param value        the value to encode; must not be {@code null}
         * @param negativeZero when {@code true} and {@code value} is zero, encodes a negative-zero sign
         * @return this builder
         * @throws IllegalArgumentException if the field is unknown, not SIGNED_DECIMAL, or the magnitude
         *                                  does not fit
         * @throws ArithmeticException      if {@code value} has more fractional digits than the scale
         */
        public RecordBuilder setSignedDecimal(String field, BigDecimal value, boolean negativeZero) {
            FieldDef def = requireType(field, FieldType.SIGNED_DECIMAL);
            Objects.requireNonNull(value, "value must not be null");
            BigDecimal scaled = value.setScale(def.scale(), RoundingMode.UNNECESSARY);
            String digits = scaled.abs().unscaledValue().toString();
            if (digits.length() > def.length()) {
                throw new IllegalArgumentException(
                        "SIGNED_DECIMAL field '" + field + "' magnitude of " + digits.length()
                                + " digits exceeds field length " + def.length());
            }
            int off = offsetsByName.get(field);
            int pad = def.length() - digits.length();
            for (int k = 0; k < def.length(); k++) {
                buffer[off + k] = k < pad ? ZERO : (byte) digits.charAt(k - pad);
            }
            int lastDigit = buffer[off + def.length() - 1] - '0';
            boolean negative = scaled.signum() < 0 || (scaled.signum() == 0 && negativeZero);
            buffer[off + def.length() - 1] = overpunchByte(lastDigit, negative);
            return this;
        }

        /**
         * Writes exactly {@code field.length()} raw bytes into a named field, bypassing type-specific
         * encoding. This is an escape hatch for byte-precise control (e.g., a specific overpunch or a
         * pre-encoded value); the length must match exactly.
         *
         * @param field the field name; must exist in this layout
         * @param value the raw bytes; length must equal the field length
         * @return this builder
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if the field is unknown or the byte count is wrong
         */
        public RecordBuilder setRawBytes(String field, byte[] value) {
            FieldDef def = require(field);
            Objects.requireNonNull(value, "value must not be null");
            if (value.length != def.length()) {
                throw new IllegalArgumentException(
                        "field '" + field + "' requires exactly " + def.length() + " bytes, got "
                                + value.length);
            }
            System.arraycopy(value, 0, buffer, offsetsByName.get(field), def.length());
            return this;
        }

        /**
         * Returns the assembled record bytes (a defensive copy) of exactly {@link #getRecordLength()}
         * bytes.
         *
         * @return the assembled record bytes
         */
        public byte[] build() {
            return buffer.clone();
        }

        private void fill(int off, int length, byte value) {
            for (int k = 0; k < length; k++) {
                buffer[off + k] = value;
            }
        }
    }
}

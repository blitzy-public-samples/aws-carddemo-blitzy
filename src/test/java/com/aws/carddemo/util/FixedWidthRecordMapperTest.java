package com.aws.carddemo.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aws.carddemo.util.FixedWidthRecordMapper.FieldDef;
import com.aws.carddemo.util.FixedWidthRecordMapper.FieldType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit&#160;5 parity-oracle unit test for {@link FixedWidthRecordMapper}.
 *
 * <p>This test proves byte-exact fixed-width record layout preservation for the two flat-file
 * external interfaces of the AWS CardDemo COBOL&#8594;Java migration, guarding the external-interface
 * byte contract (AAP &#167;0.6.4):</p>
 * <ul>
 *   <li>the daily-transaction feed {@code DALYTRAN} &#8212; <b>350&#160;bytes</b>; and</li>
 *   <li>the reject file {@code DALYREJS} &#8212; <b>430&#160;bytes</b> (a 350-byte transaction image
 *       followed by an 80-byte validation trailer).</li>
 * </ul>
 *
 * <p>It exercises exact-length enforcement, the COBOL {@code DISPLAY} padding conventions
 * (TEXT = left-justified / space-padded on the right; NUMERIC = right-justified / zero-padded on the
 * left), the zoned trailing-overpunch decode of the signed {@code AMT} field, and a build&#8596;parse
 * round-trip identity on an unambiguous TEXT/NUMERIC/FILLER layout.</p>
 *
 * <p>The mapper's public surface is byte-oriented: a record is assembled through
 * {@link FixedWidthRecordMapper#newRecord()} / {@code RecordBuilder} and read back through
 * {@link FixedWidthRecordMapper#parse(byte[])} / {@code ParsedRecord}. Because the mapper's default
 * charset is single-byte ISO-8859-1, the assembled {@code byte[]} maps one-to-one to characters, so a
 * {@code new String(bytes, ISO_8859_1)} view is used only to assert human-readable padding slices.</p>
 *
 * <p><b>Oracles</b> (retained legacy sources, verified against this test's expectations):</p>
 * <ul>
 *   <li>{@code legacy/cpy/CVTRA06Y.cpy} &#8212; {@code DALYTRAN-RECORD} (RECLN&#160;=&#160;350); the
 *       14-field layout reproduced by {@link #dalytranLayout()}.</li>
 *   <li>{@code legacy/cbl/CBTRN02C.cbl} L176&#8211;L182 &#8212; {@code REJECT-RECORD} =
 *       {@code REJECT-TRAN-DATA PIC X(350)} + {@code VALIDATION-TRAILER PIC X(80)} = 430&#160;bytes;
 *       the trailer is {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} +
 *       {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}.</li>
 *   <li>{@code legacy/data/ASCII/dailytran.txt} &#8212; 300 rows, each verified uniformly
 *       350&#160;bytes wide; row&#160;1 carries the {@code AMT} slice {@code "0000005047G"}
 *       (zoned overpunch {@code 'G'} = +7) which decodes to {@code +504.77}.</li>
 * </ul>
 *
 * <p><b>Scope.</b> This is a pure unit test: it constructs no Spring context, starts no database or
 * Testcontainers, and touches the filesystem only in the single, self-skipping fixture-width sanity
 * check ({@link #legacyDalytranFixtureIsUniform350Wide()}). No {@code float}/{@code double} arithmetic
 * appears anywhere; decimal values use {@link BigDecimal} constructed from {@link String}s.</p>
 */
class FixedWidthRecordMapperTest {

    // ------------------------------------------------------------------------------------------
    // Layout helpers (built inline; the production mapper bakes in no feed-specific layout, and the
    // DALYTRAN DTO is intentionally not imported so this test depends only on FixedWidthRecordMapper).
    // ------------------------------------------------------------------------------------------

    /**
     * Reproduces the 14-field {@code DALYTRAN-RECORD} layout from {@code legacy/cpy/CVTRA06Y.cpy}.
     * Field lengths sum to exactly 350: 16+2+4+10+100+11+9+50+50+10+16+26+26+20.
     *
     * @return the ordered DALYTRAN field definitions (13 named fields + one trailing 20-byte FILLER)
     */
    private static List<FieldDef> dalytranLayout() {
        return List.of(
                FieldDef.text("tranId", 16),          // DALYTRAN-ID           PIC X(16)
                FieldDef.text("typeCode", 2),         // DALYTRAN-TYPE-CD      PIC X(02)
                FieldDef.numeric("catCode", 4),       // DALYTRAN-CAT-CD       PIC 9(04)
                FieldDef.text("source", 10),          // DALYTRAN-SOURCE       PIC X(10)
                FieldDef.text("desc", 100),           // DALYTRAN-DESC         PIC X(100)
                FieldDef.signedDecimal("amount", 11, 2), // DALYTRAN-AMT       PIC S9(09)V99
                FieldDef.numeric("merchantId", 9),    // DALYTRAN-MERCHANT-ID  PIC 9(09)
                FieldDef.text("merchantName", 50),    // DALYTRAN-MERCHANT-NAME PIC X(50)
                FieldDef.text("merchantCity", 50),    // DALYTRAN-MERCHANT-CITY PIC X(50)
                FieldDef.text("merchantZip", 10),     // DALYTRAN-MERCHANT-ZIP  PIC X(10)
                FieldDef.text("cardNum", 16),         // DALYTRAN-CARD-NUM     PIC X(16)
                FieldDef.text("origTs", 26),          // DALYTRAN-ORIG-TS      PIC X(26)
                FieldDef.text("procTs", 26),          // DALYTRAN-PROC-TS      PIC X(26)
                FieldDef.filler(20));                 // FILLER                PIC X(20)
    }

    /**
     * Builds a mapper for the 350-byte DALYTRAN record layout.
     *
     * @return a DALYTRAN mapper
     */
    private static FixedWidthRecordMapper dalytranMapper() {
        return FixedWidthRecordMapper.of(dalytranLayout());
    }

    /**
     * Builds a mapper for the 80-byte {@code VALIDATION-TRAILER} segment of {@code DALYREJS}
     * ({@code CBTRN02C} L180&#8211;L182): {@code failReason PIC 9(04)} + {@code failReasonDesc PIC X(76)}.
     *
     * @return the reject-trailer mapper (record length 80)
     */
    private static FixedWidthRecordMapper rejectTrailerMapper() {
        return FixedWidthRecordMapper.of(
                FieldDef.numeric("failReason", 4),
                FieldDef.text("failReasonDesc", 76));
    }

    /**
     * Produces a fully-populated, valid value map for the DALYTRAN named fields. Values are short
     * business values; {@link #assemble(FixedWidthRecordMapper, Map)} applies the canonical padding via
     * the mapper's record builder. The {@code amount} is supplied as the decimal literal {@code "504.77"},
     * which the mapper encodes to the zoned form {@code "0000005047G"}.
     *
     * @return a mutable, ordered map of DALYTRAN field name to raw business value
     */
    private static Map<String, String> validDalytranValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tranId", "TXN123");
        values.put("typeCode", "01");
        values.put("catCode", "1");
        values.put("source", "POS TERM");
        values.put("desc", "Purchase at Abshire-Lowe");
        values.put("amount", "504.77");
        values.put("merchantId", "800000000");
        values.put("merchantName", "Abshire-Lowe");
        values.put("merchantCity", "North Enoshaven");
        values.put("merchantZip", "72112");
        values.put("cardNum", "4859452612877065");
        values.put("origTs", "2022-06-10 19:27:53.000000");
        values.put("procTs", "");
        return values;
    }

    /**
     * Assembles a fixed-width record from a name&#8594;value map, dispatching each named field to the
     * builder setter appropriate for its {@link FieldType} (TEXT / NUMERIC / SIGNED_DECIMAL); FILLER
     * fields keep the builder's COBOL defaults and unlisted fields are left at their default.
     *
     * @param mapper the layout mapper
     * @param values the raw business values keyed by field name
     * @return the assembled record bytes, exactly {@link FixedWidthRecordMapper#getRecordLength()} long
     */
    private static byte[] assemble(FixedWidthRecordMapper mapper, Map<String, String> values) {
        var builder = mapper.newRecord();
        for (FieldDef field : mapper.getFields()) {
            if (field.type() == FieldType.FILLER) {
                continue;
            }
            String value = values.get(field.name());
            if (value == null) {
                continue;
            }
            switch (field.type()) {
                case TEXT -> builder.setText(field.name(), value);
                case NUMERIC -> builder.setNumeric(field.name(), value.isEmpty() ? 0L : Long.parseLong(value));
                case SIGNED_DECIMAL -> builder.setSignedDecimal(field.name(), new BigDecimal(value));
                default -> throw new IllegalStateException("unexpected field type: " + field.type());
            }
        }
        return builder.build();
    }

    /**
     * Renders record bytes as an ISO-8859-1 string so human-readable padding slices can be asserted.
     * This is a lossless one-byte-per-char view (the mapper's charset is single-byte ISO-8859-1).
     *
     * @param record the record bytes
     * @return the one-byte-per-char string view
     */
    private static String asText(byte[] record) {
        return new String(record, StandardCharsets.ISO_8859_1);
    }

    /**
     * Encodes a fixed-width record image expressed as a printable ASCII string back into the raw bytes
     * the mapper parses, using the mapper's single-byte charset.
     *
     * @param image the fixed-width record image
     * @return the raw bytes
     */
    private static byte[] bytes(String image) {
        return image.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------------------------------
    // Phase 2 - byte-exact build<->parse identity on TEXT/NUMERIC/FILLER (no sign ambiguity)
    // ------------------------------------------------------------------------------------------

    @Test
    void buildThenParseIsIdentity_forTextNumericFiller() {
        FixedWidthRecordMapper mapper = FixedWidthRecordMapper.of(
                FieldDef.text("a", 5),
                FieldDef.numeric("n", 5),
                FieldDef.text("b", 4),
                FieldDef.filler(6));
        assertEquals(20, mapper.getRecordLength(), "layout must total 20 bytes");

        // Short business values; the builder applies the canonical COBOL padding (TEXT spaces, NUMERIC zeros).
        byte[] record = assemble(mapper, Map.of("a", "hi", "n", "42", "b", "ok"));
        assertEquals(20, record.length, "assembled record must be exactly 20 bytes");

        // parse() retains the full bytes; the named fields read back with canonical padding preserved.
        var parsed = mapper.parse(record);
        assertEquals("hi   ", parsed.getText("a"), "TEXT 'a' is space-padded on the right");
        assertEquals(42L, parsed.getNumeric("n"), "NUMERIC 'n' round-trips as 42");
        assertEquals("ok  ", parsed.getText("b"), "TEXT 'b' is space-padded on the right");

        // The round-trip is byte-for-byte identical (FILLER bytes included).
        assertArrayEquals(record, parsed.toByteArray(),
                "build then parse must reproduce the record byte-for-byte");

        // The FILLER field is unnamed and therefore excluded from the mapper's named fields (3 remain).
        long named = mapper.getFields().stream().filter(f -> f.type() != FieldType.FILLER).count();
        assertEquals(3, named, "FILLER must be excluded from the named fields");
    }

    // ------------------------------------------------------------------------------------------
    // Phase 3 - TEXT pads spaces, NUMERIC pads zeros; typed accessors read raw record slices
    // ------------------------------------------------------------------------------------------

    @Test
    void textPadsSpaces_numericPadsZeros() {
        FixedWidthRecordMapper mapper = FixedWidthRecordMapper.of(
                FieldDef.text("a", 5),
                FieldDef.numeric("n", 5));

        byte[] record = assemble(mapper, Map.of("a", "hi", "n", "42"));
        String out = asText(record);

        assertEquals(10, record.length, "record must be exactly 10 bytes");
        assertEquals("hi   ", out.substring(0, 5), "TEXT is left-justified, right-padded with spaces");
        assertEquals("00042", out.substring(5, 10), "NUMERIC is right-justified, left-padded with zeros");
    }

    @Test
    void accessorsReadFieldsBack() {
        FixedWidthRecordMapper mapper = FixedWidthRecordMapper.of(
                FieldDef.text("a", 5),
                FieldDef.numeric("n", 5));

        // The typed accessors operate on a parsed view of the raw fixed-width record bytes.
        var parsed = mapper.parse(bytes("hi   00042"));
        assertEquals("hi   ", parsed.getText("a"), "getText returns the raw padded slice");
        assertEquals("hi", parsed.getTrimmedText("a"), "getTrimmedText strips trailing blanks");
        assertEquals(42L, parsed.getNumeric("n"), "getNumeric parses the zero-padded integer");
    }

    // ------------------------------------------------------------------------------------------
    // Phase 4 - DALYTRAN 350-byte length + exact-length enforcement (headline record contract)
    // ------------------------------------------------------------------------------------------

    @Test
    void dalytranRecordIsExactly350Bytes() {
        FixedWidthRecordMapper mapper = dalytranMapper();
        assertEquals(350, mapper.getRecordLength(), "DALYTRAN record length must be exactly 350");
        assertTrue(mapper.hasField("tranId"), "tranId must be a named field");
        assertTrue(mapper.hasField("cardNum"), "cardNum must be a named field");

        byte[] record = assemble(mapper, validDalytranValues());
        assertEquals(350, record.length, "a formatted DALYTRAN record must be exactly 350 bytes");

        // Spot-check that named fields read back at their exact byte offsets.
        var parsed = mapper.parse(record);
        assertEquals("TXN123", parsed.getTrimmedText("tranId"), "tranId reads back");
        assertEquals("4859452612877065", parsed.getText("cardNum"), "cardNum reads back");
        assertEquals(0, parsed.getSignedDecimal("amount").compareTo(new BigDecimal("504.77")),
                "amount round-trips through build then getSignedDecimal");

        // The 20-byte FILLER is unnamed; exactly 13 named DALYTRAN fields remain.
        long named = mapper.getFields().stream().filter(f -> f.type() != FieldType.FILLER).count();
        assertEquals(13, named, "the layout must expose 13 named DALYTRAN fields (FILLER excluded)");
    }

    @Test
    void parseRejectsWrongLength_349_and_351() {
        FixedWidthRecordMapper mapper = dalytranMapper();
        assertThrows(IllegalArgumentException.class, () -> mapper.parse(bytes("X".repeat(349))),
                "a 349-byte record must be rejected");
        assertThrows(IllegalArgumentException.class, () -> mapper.parse(bytes("X".repeat(351))),
                "a 351-byte record must be rejected");
        // A 350-byte record is accepted on length grounds even if numeric fields hold non-numeric bytes,
        // because parse() returns raw slices without numeric interpretation.
        assertDoesNotThrow(() -> mapper.parse(bytes("X".repeat(350))),
                "a 350-byte record must pass the length check");
    }

    // ------------------------------------------------------------------------------------------
    // Phase 5 - SIGNED_DECIMAL zoned trailing-overpunch decode (verified against real fixture data)
    // ------------------------------------------------------------------------------------------

    @Test
    void signedDecimalDecodesZonedOverpunch() {
        FixedWidthRecordMapper mapper = FixedWidthRecordMapper.of(FieldDef.signedDecimal("amt", 11, 2));

        // Positive: 'G' = +7 -> digits 000000504 . 7(7) = +504.77 (row 1 of legacy/data/ASCII/dailytran.txt).
        BigDecimal positive = mapper.parse(bytes("0000005047G")).getSignedDecimal("amt");
        assertEquals(0, positive.compareTo(new BigDecimal("504.77")), "'0000005047G' decodes to +504.77");
        assertEquals(2, positive.scale(), "decoded value carries the field's implied scale of 2");

        // Negative: '}' = -0 -> digits 000000919 . 0(0) negated = -919.00.
        BigDecimal negative = mapper.parse(bytes("0000009190}")).getSignedDecimal("amt");
        assertEquals(0, negative.compareTo(new BigDecimal("-919.00")), "'0000009190}' decodes to -919.00");
        assertEquals(2, negative.scale(), "decoded value carries the field's implied scale of 2");
    }

    // ------------------------------------------------------------------------------------------
    // Phase 6 - DALYREJS 430-byte record = 350-byte DALYTRAN image + 80-byte validation trailer
    // ------------------------------------------------------------------------------------------

    @Test
    void dalyrejsRecordIsExactly430Bytes() {
        FixedWidthRecordMapper mapper = dalytranMapper().concat(rejectTrailerMapper());
        assertEquals(430, mapper.getRecordLength(), "DALYREJS record length must be exactly 430 (350 + 80)");

        Map<String, String> values = new LinkedHashMap<>(validDalytranValues());
        values.put("failReason", "100");
        values.put("failReasonDesc", "OVERLIMIT TRANSACTION");

        byte[] record = assemble(mapper, values);
        assertEquals(430, record.length, "a formatted DALYREJS record must be exactly 430 bytes");

        // The 80-byte trailer reads back through the typed accessors.
        var parsed = mapper.parse(record);
        assertEquals(100L, parsed.getNumeric("failReason"), "failReason reads back as 100");
        assertEquals("OVERLIMIT TRANSACTION", parsed.getTrimmedText("failReasonDesc"),
                "failReasonDesc reads back trimmed");

        assertThrows(IllegalArgumentException.class, () -> mapper.parse(bytes("X".repeat(429))),
                "a 429-byte reject record must be rejected");
        assertThrows(IllegalArgumentException.class, () -> mapper.parse(bytes("X".repeat(431))),
                "a 431-byte reject record must be rejected");
    }

    // ------------------------------------------------------------------------------------------
    // Phase 7 - FieldType enum guard (exactly four PIC-clause families)
    // ------------------------------------------------------------------------------------------

    @Test
    void fieldTypeEnumHasExactlyFourConstants() {
        assertEquals(4, FieldType.values().length, "FieldType must declare exactly four constants");
        assertEquals(FieldType.TEXT, FieldType.valueOf("TEXT"));
        assertEquals(FieldType.NUMERIC, FieldType.valueOf("NUMERIC"));
        assertEquals(FieldType.SIGNED_DECIMAL, FieldType.valueOf("SIGNED_DECIMAL"));
        assertEquals(FieldType.FILLER, FieldType.valueOf("FILLER"));
    }

    // ------------------------------------------------------------------------------------------
    // Phase 8 - OPTIONAL, self-skipping fixture-width sanity against the retained legacy file
    // ------------------------------------------------------------------------------------------

    @Test
    void legacyDalytranFixtureIsUniform350Wide() throws IOException {
        // Maven Surefire runs with user.dir at the module basedir; the retained fixture lives here.
        Path fixture = Paths.get("legacy/data/ASCII/dailytran.txt");
        Assumptions.assumeTrue(Files.exists(fixture),
                "legacy DALYTRAN fixture not present; skipping fixture-width sanity check");

        List<String> lines = Files.readAllLines(fixture);
        Assumptions.assumeFalse(lines.isEmpty(), "legacy DALYTRAN fixture is empty; skipping");

        assertEquals(350, lines.get(0).length(),
                "each retained DALYTRAN fixture row must be exactly 350 bytes wide");
    }
}

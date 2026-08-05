package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PicClause;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Rederives every row of {@code expected/fixture-coverage.csv} from the CardDemo sample data and
 * compares it against the value the file records.
 *
 * <p>The census file is the equivalence evidence for fixture shape. It holds
 * {@link #CENSUS_DATA_ROW_COUNT} data rows over the nine text fixtures in {@code app/data/ASCII}
 * and the twelve binary fixtures in {@code app/data/EBCDIC}. Every row states one measurement:
 * a record count, a byte width, a line ending, a filler character, a sign overpunch position
 * count, a distinct value count, a bound, or a decline reason occurrence count.</p>
 *
 * <p>A checked-in table of expected values proves nothing on its own. This class therefore derives
 * each row again from the files the row names, and one collected assertion reports every row whose
 * derived value differs. A second collected assertion reports every row no derivation covers, so a
 * row added to the file without a matching derivation fails the build rather than passing unread.
 * That is the property the census exists for: the evidence is recomputed on every run, not
 * asserted once and then trusted.</p>
 *
 * <p>Derivations read the fixtures through {@link CardDemoFixtureLoader} and
 * {@link CopybookRecordParser} wherever the row concerns a parsed field, and through the raw record
 * bytes wherever it concerns a byte position, a padding character or a sign overpunch. Byte offsets
 * come from {@link #LAYOUTS}, which lists each copybook's fields in declaration order at the widths
 * {@link PicClause} publishes. No offset is written as a literal, so a width correction in
 * {@link PicClause} moves every offset below it.</p>
 *
 * <p>Two rows are not measurable from a fixture and are derived from the source instead. The
 * security record has no text fixture, so its record width is the sum of the six {@code PIC X(n)}
 * clauses of {@code app/cpy/CSUSR01Y.cpy} and its record count is the twin's byte count divided by
 * that width. Four rows describe the processing timestamp a service renders rather than one a
 * fixture holds; they are derived by putting a timestamp through
 * {@link CopybookRecordParser#truncateProcessingTimestampToHundredths} and measuring the result
 * against the {@link PicClause} component offsets.</p>
 *
 * <p>No failure message here carries a card number, a card verification value, a social security
 * number, a government-issued identifier or an electronic funds transfer account identifier. A
 * mismatch reports the row ordinal, the entity key, the field name, the recorded value and the
 * derived value. Both values are census evidence already checked into the repository and neither is
 * a cardholder secret: the census records counts, widths, codes, dates, bounds and one address zip,
 * and never a value from any of the five field classes above. Where a derivation expects one
 * distinct value and finds several it returns {@link #NOT_UNIQUE} and the count, so a divergence is
 * reported without quoting the values that caused it.</p>
 */
class FixtureCoverageEquivalenceTest {

    /** Classpath location of the census file, which sits at {@code src/test/resources}. */
    private static final String CENSUS_RESOURCE = "/expected/fixture-coverage.csv";

    /** Repository-relative path of the census file, reported when it cannot be read. */
    private static final String CENSUS_PATH =
            "card-platform/equivalence-tests/src/test/resources/expected/fixture-coverage.csv";

    /** Character that opens the one comment line, which sits above the header. */
    private static final String COMMENT_PREFIX = "#";

    /** Columns the census declares, in order. */
    private static final List<String> CENSUS_COLUMNS = List.of(
            "evaluation_model",
            "derived_from",
            "record_seq",
            "entity_key",
            "expected_field",
            "expected_value",
            "pic_clause",
            "source_locator");

    /** Data rows the census holds, comment and header excluded. */
    private static final int CENSUS_DATA_ROW_COUNT = 190;

    /**
     * The one evaluation model every row carries. Fixture shape does not depend on the accumulator
     * state a posting run would change, so no row needs a before-and-after model.
     */
    private static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    /** Affirmative value of every boolean-shaped census row. */
    private static final String YES = "yes";

    /** Negative value of every boolean-shaped census row. */
    private static final String NO = "no";

    /** Reported in place of a value when a derivation expecting one distinct value finds several. */
    private static final String NOT_UNIQUE = "NOT_UNIQUE";

    /** Line ending value the census records for a file terminated by line feeds only. */
    private static final String LINE_FEED = "LF";

    /** Line ending value for a file holding carriage return and line feed pairs. */
    private static final String CARRIAGE_RETURN_LINE_FEED = "CRLF";

    /** Line ending value for a file holding carriage returns only. */
    private static final String CARRIAGE_RETURN = "CR";

    /** Line ending value for a file holding neither. */
    private static final String NO_LINE_ENDING = "NONE";

    /** Filler value the census records when every filler byte is a space. */
    private static final String FILLER_SPACE = "space";

    /** Filler value the census records when every filler byte is the character zero. */
    private static final String FILLER_ASCII_ZERO = "ascii_zero";

    /** Filler value the census records when the fixture stops before the filler begins. */
    private static final String FILLER_ABSENT = "absent";

    /**
     * Reported in place of a single-valued measurement when the fixture holds more than one value.
     *
     * <p>It stands in for a filler that is not all one character, for a line ending that is not one
     * kind, and for a record width that is not uniform. No census row records it, so a derivation
     * returning it always reports a mismatch, and it quotes none of the values that caused it.</p>
     */
    private static final String MIXED_MEASUREMENT = "MIXED";

    /** Byte the record separator occupies in every text fixture. */
    private static final byte LINE_FEED_BYTE = '\n';

    /** Byte a carriage return occupies, counted by one census row per fixture. */
    private static final byte CARRIAGE_RETURN_BYTE = '\r';

    /** Character COBOL pads a {@code PIC X(n)} field with on the right. */
    private static final char COBOL_TEXT_PAD = ' ';

    /** Character the three reference fixtures pad their filler with. */
    private static final char ASCII_ZERO = '0';

    /** Amount added to a zero-based index to report it as a one-based ordinal. */
    private static final int FIRST_ORDINAL = 1;

    /** Separator between an entity key and a field name in a derivation key. */
    private static final String KEY_SEPARATOR = ".";

    /** Separator the census uses inside a joined value list and inside a locator list. */
    private static final String VALUE_SEPARATOR = ",";

    /** Directory below the repository root holding the nine text fixtures. */
    private static final String ASCII_DIRECTORY = "app/data/ASCII";

    /** Directory below the repository root holding the binary twins. */
    private static final String EBCDIC_DIRECTORY = "app/data/EBCDIC";

    /** Prefix of repository metadata files that are not fixtures. */
    private static final String HIDDEN_FILE_PREFIX = ".";

    /** Copybook that declares the security record, which has no text fixture. */
    private static final String SECURITY_COPYBOOK = "app/cpy/CSUSR01Y.cpy";

    /** Fields {@code app/cpy/CSUSR01Y.cpy} declares below {@code 01 SEC-USER-DATA}. */
    private static final int SECURITY_RECORD_FIELD_COUNT = 6;

    /** Matches one {@code PIC X(n)} clause of the security copybook and captures its width. */
    private static final Pattern PICTURE_TEXT_CLAUSE = Pattern.compile("PIC\\s+X\\((\\d+)\\)");

    /** Name the census gives the security entity, whose only source is its binary twin. */
    private static final String USRSEC_ENTITY = "usrsec";

    /** Text fixture the census records as absent for {@link #USRSEC_ENTITY}. */
    private static final String USRSEC_ABSENT_FIXTURE_NAME = "usrsec.txt";

    /**
     * A timestamp fed to {@link CopybookRecordParser#truncateProcessingTimestampToHundredths} so
     * the four rendered-timestamp rows are measured from a real truncation rather than restated.
     *
     * <p>Every component differs from every other, and the six fractional digits are all distinct,
     * so a shape derived from the result cannot agree by coincidence.</p>
     */
    private static final String RENDERED_PROCESSING_TIMESTAMP = "2026-08-02-13.45.59.123456";

    /** Letter the derived shape carries at each year position. */
    private static final char SHAPE_YEAR = 'Y';

    /** Letter the derived shape carries at each month position. */
    private static final char SHAPE_MONTH = 'M';

    /** Letter the derived shape carries at each day position. */
    private static final char SHAPE_DAY = 'D';

    /** Letter the derived shape carries at each hour position. */
    private static final char SHAPE_HOUR = 'H';

    /** Letter the derived shape carries at each minute position. */
    private static final char SHAPE_MINUTE = 'M';

    /** Letter the derived shape carries at each second position. */
    private static final char SHAPE_SECOND = 'S';

    /** Letter the derived shape carries at each significant fraction position. */
    private static final char SHAPE_HUNDREDTHS = 'h';

    /** Letter the derived shape carries at each fraction position of an origin timestamp. */
    private static final char SHAPE_FRACTION = 'f';

    // Census file model.

    /**
     * One data row of the census.
     *
     * @param evaluationModel whether the row depends on accumulator state
     * @param derivedFrom     repository-relative path of the file the row is measured from
     * @param recordSeq       one-based ordinal of the row inside the census
     * @param entityKey       fixture the row describes
     * @param expectedField   measurement the row records
     * @param expectedValue   value the census records for that measurement
     * @param picClause       COBOL picture clause of the field involved, or {@code NONE}
     * @param sourceLocator   one or more file and line references for the measurement
     */
    private record CensusRow(String evaluationModel, String derivedFrom, int recordSeq,
            String entityKey, String expectedField, String expectedValue, String picClause,
            String sourceLocator) {

        /** Key a derivation is looked up under when it applies to one entity only. */
        private String specificKey() {
            return entityKey + KEY_SEPARATOR + expectedField;
        }

        /** Names the row without quoting either value, for use in a covering-derivation report. */
        private String label() {
            return "row " + recordSeq + " " + specificKey();
        }
    }

    // Copybook layout model. Offsets are running sums over the widths PicClause publishes.

    /** What a field holds, which decides how a census row reads it. */
    private enum FieldKind {

        /** A {@code PIC X(n)} field. */
        TEXT,

        /** A {@code PIC 9(n)} field, every character a digit. */
        UNSIGNED,

        /** A {@code PIC S9(n)V99} field, whose trailing character carries the sign. */
        SIGNED,

        /** The trailing filler a copybook declares and no program reads. */
        FILLER
    }

    /**
     * One field of a copybook.
     *
     * @param name  COBOL field name, spelled as the copybook spells it
     * @param width byte width the field occupies
     * @param kind  what the field holds
     */
    private record LayoutField(String name, int width, FieldKind kind) {
    }

    /**
     * One record layout, listing its fields in copybook declaration order.
     *
     * @param fixtureFileName    text fixture holding the layout, or null when none exists
     * @param ebcdicTwinFileName binary twin holding the layout
     * @param fields             every field the copybook declares, filler included
     */
    private record Layout(String fixtureFileName, String ebcdicTwinFileName,
            List<LayoutField> fields) {

        /** Sum of every field width, which is the record length the dataset definition declares. */
        private int declaredWidth() {
            int total = 0;
            for (LayoutField field : fields) {
                total += field.width();
            }
            return total;
        }

        /** Zero-based byte offset of a field, being the sum of the widths declared above it. */
        private int offsetOf(String fieldName) {
            int offset = 0;
            for (LayoutField field : fields) {
                if (field.name().equals(fieldName)) {
                    return offset;
                }
                offset += field.width();
            }
            throw new IllegalArgumentException(fieldName + " is no field of this layout");
        }

        /** Returns a field by name. */
        private LayoutField field(String fieldName) {
            for (LayoutField field : fields) {
                if (field.name().equals(fieldName)) {
                    return field;
                }
            }
            throw new IllegalArgumentException(fieldName + " is no field of this layout");
        }

        /** Every field whose trailing character carries a sign overpunch. */
        private List<LayoutField> signedFields() {
            List<LayoutField> signed = new ArrayList<>();
            for (LayoutField field : fields) {
                if (field.kind() == FieldKind.SIGNED) {
                    signed.add(field);
                }
            }
            return List.copyOf(signed);
        }

        /** The trailing filler field, which every one of these layouts declares. */
        private LayoutField filler() {
            return field("FILLER");
        }
    }

    /**
     * The nine text fixture layouts, keyed by census entity.
     *
     * <p>Every width is a {@link PicClause} constant and every offset is derived from the order
     * below, so no byte position in this class is a literal.</p>
     */
    private static final Map<String, Layout> LAYOUTS = layouts();

    /** Builds {@link #LAYOUTS}. */
    private static Map<String, Layout> layouts() {
        Map<String, Layout> layouts = new LinkedHashMap<>();
        layouts.put("acctdata", new Layout(CardDemoFixtureLoader.ACCTDATA_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.ACCTDATA.PS", List.of(
                        new LayoutField("ACCT-ID", PicClause.ACCT_ID_WIDTH, FieldKind.UNSIGNED),
                        new LayoutField("ACCT-ACTIVE-STATUS", PicClause.ACCT_ACTIVE_STATUS_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("ACCT-CURR-BAL", PicClause.ACCT_CURR_BAL_WIDTH,
                                FieldKind.SIGNED),
                        new LayoutField("ACCT-CREDIT-LIMIT", PicClause.ACCT_CREDIT_LIMIT_WIDTH,
                                FieldKind.SIGNED),
                        new LayoutField("ACCT-CASH-CREDIT-LIMIT",
                                PicClause.ACCT_CASH_CREDIT_LIMIT_WIDTH, FieldKind.SIGNED),
                        new LayoutField("ACCT-OPEN-DATE", PicClause.ACCT_OPEN_DATE_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("ACCT-EXPIRAION-DATE", PicClause.ACCT_EXPIRATION_DATE_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("ACCT-REISSUE-DATE", PicClause.ACCT_REISSUE_DATE_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("ACCT-CURR-CYC-CREDIT",
                                PicClause.ACCT_CURR_CYC_CREDIT_WIDTH, FieldKind.SIGNED),
                        new LayoutField("ACCT-CURR-CYC-DEBIT", PicClause.ACCT_CURR_CYC_DEBIT_WIDTH,
                                FieldKind.SIGNED),
                        new LayoutField("ACCT-ADDR-ZIP", PicClause.ACCT_ADDR_ZIP_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("ACCT-GROUP-ID", PicClause.ACCT_GROUP_ID_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("FILLER", PicClause.ACCOUNT_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        layouts.put("carddata", new Layout(CardDemoFixtureLoader.CARDDATA_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.CARDDATA.PS", List.of(
                        new LayoutField("CARD-NUM", PicClause.CARD_NUM_WIDTH, FieldKind.UNSIGNED),
                        new LayoutField("CARD-ACCT-ID", PicClause.CARD_ACCT_ID_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("CARD-CVV-CD", PicClause.CARD_CVV_CD_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("CARD-EMBOSSED-NAME", PicClause.CARD_EMBOSSED_NAME_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CARD-EXPIRAION-DATE", PicClause.CARD_EXPIRATION_DATE_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CARD-ACTIVE-STATUS", PicClause.CARD_ACTIVE_STATUS_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("FILLER", PicClause.CARD_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        layouts.put("cardxref", new Layout(CardDemoFixtureLoader.CARDXREF_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.CARDXREF.PS", List.of(
                        new LayoutField("XREF-CARD-NUM", PicClause.XREF_CARD_NUM_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("XREF-CUST-ID", PicClause.XREF_CUST_ID_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("XREF-ACCT-ID", PicClause.XREF_ACCT_ID_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("FILLER", PicClause.CARD_XREF_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        layouts.put("custdata", new Layout(CardDemoFixtureLoader.CUSTDATA_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.CUSTDATA.PS", List.of(
                        new LayoutField("CUST-ID", PicClause.CUST_ID_WIDTH, FieldKind.UNSIGNED),
                        new LayoutField("CUST-FIRST-NAME", PicClause.CUST_FIRST_NAME_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-MIDDLE-NAME", PicClause.CUST_MIDDLE_NAME_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-LAST-NAME", PicClause.CUST_LAST_NAME_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-ADDR-LINE-1", PicClause.CUST_ADDR_LINE_1_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-ADDR-LINE-2", PicClause.CUST_ADDR_LINE_2_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-ADDR-LINE-3", PicClause.CUST_ADDR_LINE_3_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-ADDR-STATE-CD", PicClause.CUST_ADDR_STATE_CD_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-ADDR-COUNTRY-CD",
                                PicClause.CUST_ADDR_COUNTRY_CD_WIDTH, FieldKind.TEXT),
                        new LayoutField("CUST-ADDR-ZIP", PicClause.CUST_ADDR_ZIP_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-PHONE-NUM-1", PicClause.CUST_PHONE_NUM_1_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-PHONE-NUM-2", PicClause.CUST_PHONE_NUM_2_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-SSN", PicClause.CUST_SSN_WIDTH, FieldKind.UNSIGNED),
                        new LayoutField("CUST-GOVT-ISSUED-ID", PicClause.CUST_GOVT_ISSUED_ID_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-DOB-YYYY-MM-DD", PicClause.CUST_DOB_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-EFT-ACCOUNT-ID", PicClause.CUST_EFT_ACCOUNT_ID_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("CUST-PRI-CARD-HOLDER-IND",
                                PicClause.CUST_PRI_CARD_HOLDER_IND_WIDTH, FieldKind.TEXT),
                        new LayoutField("CUST-FICO-CREDIT-SCORE",
                                PicClause.CUST_FICO_CREDIT_SCORE_WIDTH, FieldKind.UNSIGNED),
                        new LayoutField("FILLER", PicClause.CUSTOMER_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        layouts.put("dailytran", new Layout(CardDemoFixtureLoader.DAILYTRAN_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.DALYTRAN.PS", List.of(
                        new LayoutField("DALYTRAN-ID", PicClause.DALYTRAN_ID_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("DALYTRAN-TYPE-CD", PicClause.DALYTRAN_TYPE_CD_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("DALYTRAN-CAT-CD", PicClause.DALYTRAN_CAT_CD_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("DALYTRAN-SOURCE", PicClause.DALYTRAN_SOURCE_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("DALYTRAN-DESC", PicClause.DALYTRAN_DESC_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("DALYTRAN-AMT", PicClause.DALYTRAN_AMT_WIDTH,
                                FieldKind.SIGNED),
                        new LayoutField("DALYTRAN-MERCHANT-ID", PicClause.DALYTRAN_MERCHANT_ID_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("DALYTRAN-MERCHANT-NAME",
                                PicClause.DALYTRAN_MERCHANT_NAME_WIDTH, FieldKind.TEXT),
                        new LayoutField("DALYTRAN-MERCHANT-CITY",
                                PicClause.DALYTRAN_MERCHANT_CITY_WIDTH, FieldKind.TEXT),
                        new LayoutField("DALYTRAN-MERCHANT-ZIP",
                                PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH, FieldKind.TEXT),
                        new LayoutField("DALYTRAN-CARD-NUM", PicClause.DALYTRAN_CARD_NUM_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("DALYTRAN-ORIG-TS", PicClause.DALYTRAN_ORIG_TS_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("DALYTRAN-PROC-TS", PicClause.DALYTRAN_PROC_TS_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("FILLER", PicClause.DALYTRAN_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        layouts.put("discgrp", new Layout(CardDemoFixtureLoader.DISCGRP_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.DISCGRP.PS", List.of(
                        new LayoutField("DIS-ACCT-GROUP-ID", PicClause.DIS_ACCT_GROUP_ID_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("DIS-TRAN-TYPE-CD", PicClause.DIS_TRAN_TYPE_CD_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("DIS-TRAN-CAT-CD", PicClause.DIS_TRAN_CAT_CD_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("DIS-INT-RATE", PicClause.DIS_INT_RATE_WIDTH,
                                FieldKind.SIGNED),
                        new LayoutField("FILLER", PicClause.DIS_GROUP_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        layouts.put("tcatbal", new Layout(CardDemoFixtureLoader.TCATBAL_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.TCATBALF.PS", List.of(
                        new LayoutField("TRANCAT-ACCT-ID", PicClause.TRANCAT_ACCT_ID_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("TRANCAT-TYPE-CD", PicClause.TRANCAT_TYPE_CD_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("TRANCAT-CD", PicClause.TRANCAT_CD_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("TRAN-CAT-BAL", PicClause.TRAN_CAT_BAL_WIDTH,
                                FieldKind.SIGNED),
                        new LayoutField("FILLER", PicClause.TRAN_CAT_BAL_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        layouts.put("trancatg", new Layout(CardDemoFixtureLoader.TRANCATG_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.TRANCATG.PS", List.of(
                        new LayoutField("TRAN-TYPE-CD", PicClause.TRAN_CAT_RECORD_TYPE_CD_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("TRAN-CAT-CD", PicClause.TRAN_CAT_RECORD_CAT_CD_WIDTH,
                                FieldKind.UNSIGNED),
                        new LayoutField("TRAN-CAT-TYPE-DESC", PicClause.TRAN_CAT_TYPE_DESC_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("FILLER", PicClause.TRAN_CAT_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        layouts.put("trantype", new Layout(CardDemoFixtureLoader.TRANTYPE_FIXTURE_FILE_NAME,
                "AWS.M2.CARDDEMO.TRANTYPE.PS", List.of(
                        new LayoutField("TRAN-TYPE", PicClause.TRAN_TYPE_WIDTH, FieldKind.TEXT),
                        new LayoutField("TRAN-TYPE-DESC", PicClause.TRAN_TYPE_DESC_WIDTH,
                                FieldKind.TEXT),
                        new LayoutField("FILLER", PicClause.TRAN_TYPE_RECORD_FILLER_WIDTH,
                                FieldKind.FILLER))));
        return Map.copyOf(layouts);
    }

    // Fixture access. Every fixture is read once and held, because 188 rows read the same files.

    /**
     * Reads a value once and returns the same value afterwards.
     *
     * @param source produces the value on first call
     * @param <T>    type of the value
     * @return a supplier that calls {@code source} at most once
     */
    private static <T> Supplier<T> readOnce(Supplier<T> source) {
        return new Supplier<>() {

            private T value;

            private boolean read;

            @Override
            public synchronized T get() {
                if (!read) {
                    value = source.get();
                    read = true;
                }
                return value;
            }
        };
    }

    /** Raw bytes of each text fixture and each binary twin, keyed by repository-relative path. */
    private static final Map<String, Supplier<byte[]>> FILE_BYTES = new LinkedHashMap<>();

    /** Records of each text fixture, the line feed removed, keyed by census entity. */
    private static final Map<String, Supplier<List<String>>> FIXTURE_RECORDS = new LinkedHashMap<>();

    /** Repository root, being the ancestor holding {@code app/data/ASCII}. */
    private static final Supplier<Path> REPOSITORY_ROOT = readOnce(() ->
            CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent());

    /** Returns the bytes of a repository-relative path, reading it at most once. */
    private static byte[] bytesOf(String repositoryRelativePath) {
        Supplier<byte[]> bytes;
        synchronized (FILE_BYTES) {
            bytes = FILE_BYTES.computeIfAbsent(repositoryRelativePath,
                    path -> readOnce(() -> readAllBytes(REPOSITORY_ROOT.get().resolve(path))));
        }
        return bytes.get();
    }

    /** Reads a file, turning the checked failure into an unchecked one. */
    private static byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    /** Reads a repository file as text, one element per line, the line endings removed. */
    private static List<String> linesOf(String repositoryRelativePath) {
        String text = new String(bytesOf(repositoryRelativePath), StandardCharsets.ISO_8859_1);
        return List.of(text.split("\\R", -1));
    }

    /** Repository-relative path of a text fixture. */
    private static String asciiPath(String entityKey) {
        return ASCII_DIRECTORY + "/" + LAYOUTS.get(entityKey).fixtureFileName();
    }

    /** Repository-relative path of a binary twin. */
    private static String ebcdicPath(String twinFileName) {
        return EBCDIC_DIRECTORY + "/" + twinFileName;
    }

    /** Returns every authoritative binary fixture path from the repository directory. */
    private static Set<String> authoritativeEBCDICFiles() {
        Path directory = REPOSITORY_ROOT.get().resolve(EBCDIC_DIRECTORY);
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString()
                            .startsWith(HIDDEN_FILE_PREFIX))
                    .map(path -> EBCDIC_DIRECTORY + "/" + path.getFileName())
                    .sorted()
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /** Bytes of a text fixture, the record separators included. */
    private static byte[] fixtureBytes(String entityKey) {
        return bytesOf(asciiPath(entityKey));
    }

    /**
     * Records of a text fixture, each holding the bytes the fixture delivers and no separator.
     *
     * <p>The split keeps every empty element, so a fixture holding a blank record reports it as a
     * record of width zero rather than dropping it. Only a trailing empty element is removed, and
     * only because a terminating line feed produces one.</p>
     */
    private static List<String> fixtureRecords(String entityKey) {
        Supplier<List<String>> records;
        synchronized (FIXTURE_RECORDS) {
            records = FIXTURE_RECORDS.computeIfAbsent(entityKey,
                    key -> readOnce(() -> splitRecords(fixtureBytes(key))));
        }
        return records.get();
    }

    /** Splits fixture bytes on the line feed, dropping the empty element a final line feed adds. */
    private static List<String> splitRecords(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> records =
                new ArrayList<>(List.of(text.split(String.valueOf((char) LINE_FEED_BYTE), -1)));
        if (!records.isEmpty() && records.get(records.size() - 1).isEmpty()) {
            records.remove(records.size() - 1);
        }
        return List.copyOf(records);
    }

    /** Reads one field of one record, keeping every padding character. */
    private static String field(String entityKey, String record, String fieldName) {
        Layout layout = LAYOUTS.get(entityKey);
        LayoutField target = layout.field(fieldName);
        return CopybookRecordParser.fixedText(record, layout.offsetOf(fieldName), target.width(),
                fieldName);
    }

    /** Reads one field of every record of a fixture, keeping every padding character. */
    private static List<String> fieldOfEveryRecord(String entityKey, String fieldName) {
        List<String> values = new ArrayList<>();
        for (String record : fixtureRecords(entityKey)) {
            values.add(field(entityKey, record, fieldName));
        }
        return List.copyOf(values);
    }

    // Typed fixture views. Each loader runs once, so every parse operation is exercised once.

    /** {@code app/data/ASCII/acctdata.txt} through {@link CopybookRecordParser#parseAccount}. */
    private static final Supplier<List<CopybookRecordParser.AccountRecord>> ACCOUNTS =
            readOnce(CardDemoFixtureLoader::loadAccounts);

    /** {@code app/data/ASCII/carddata.txt} through {@link CopybookRecordParser#parseCard}. */
    private static final Supplier<List<CopybookRecordParser.CardRecord>> CARDS =
            readOnce(CardDemoFixtureLoader::loadCards);

    /** {@code app/data/ASCII/cardxref.txt} through the cross-reference parse operation. */
    private static final Supplier<List<CopybookRecordParser.CardCrossReferenceRecord>>
            CROSS_REFERENCES = readOnce(CardDemoFixtureLoader::loadCardCrossReferences);

    /** {@code app/data/ASCII/custdata.txt} through {@link CopybookRecordParser#parseCustomer}. */
    private static final Supplier<List<CopybookRecordParser.CustomerRecord>> CUSTOMERS =
            readOnce(CardDemoFixtureLoader::loadCustomers);

    /** {@code app/data/ASCII/dailytran.txt} through the daily transaction parse operation. */
    private static final Supplier<List<CopybookRecordParser.DailyTransactionRecord>>
            DAILY_TRANSACTIONS = readOnce(CardDemoFixtureLoader::loadDailyTransactions);

    /** {@code app/data/ASCII/discgrp.txt} through the disclosure group parse operation. */
    private static final Supplier<List<CopybookRecordParser.DisclosureGroupRecord>>
            DISCLOSURE_GROUPS = readOnce(CardDemoFixtureLoader::loadDisclosureGroups);

    /** {@code app/data/ASCII/tcatbal.txt} through the category balance parse operation. */
    private static final Supplier<List<CopybookRecordParser.TransactionCategoryBalanceRecord>>
            CATEGORY_BALANCES = readOnce(CardDemoFixtureLoader::loadTransactionCategoryBalances);

    /** {@code app/data/ASCII/trancatg.txt} through the transaction category parse operation. */
    private static final Supplier<List<CopybookRecordParser.TransactionCategoryRecord>>
            TRANSACTION_CATEGORIES = readOnce(CardDemoFixtureLoader::loadTransactionCategories);

    /** {@code app/data/ASCII/trantype.txt} through the transaction type parse operation. */
    private static final Supplier<List<CopybookRecordParser.TransactionTypeRecord>>
            TRANSACTION_TYPES = readOnce(CardDemoFixtureLoader::loadTransactionTypes);

    /** Cross-reference rows keyed by card number, as the authorization lookup keys them. */
    private static final Supplier<Map<String, CopybookRecordParser.CardCrossReferenceRecord>>
            CROSS_REFERENCES_BY_CARD =
                    readOnce(CardDemoFixtureLoader::cardCrossReferencesByCardNumber);

    /** Account rows keyed by the account identifier text the fixture holds. */
    private static final Supplier<Map<String, CopybookRecordParser.AccountRecord>>
            ACCOUNTS_BY_ACCOUNT_ID = readOnce(CardDemoFixtureLoader::accountsByAccountId);

    // Measurement helpers. Each returns the census's own text form of a measurement.

    /** Renders a count the way the census records it. */
    private static String count(long value) {
        return Long.toString(value);
    }

    /** Counts the distinct elements of a list. */
    private static String distinctCount(List<String> values) {
        return count(new LinkedHashSet<>(values).size());
    }

    /**
     * Returns the one value a list holds, or {@link #MIXED_MEASUREMENT} and the distinct count.
     *
     * <p>The values themselves are never reported, so a fixture holding several card numbers where
     * the census expects one reports the divergence without quoting any of them.</p>
     */
    private static String soleValue(List<String> values) {
        Set<String> distinct = new LinkedHashSet<>(values);
        if (distinct.size() == 1) {
            return distinct.iterator().next();
        }
        return MIXED_MEASUREMENT + " " + distinct.size();
    }

    /**
     * Returns the one number a set holds, or {@link #MIXED_MEASUREMENT} and the distinct count.
     *
     * <p>Used where the census records a measurement it expects to be the same for every record: a
     * record width, a padding character count, or an occurrence count.</p>
     */
    private static String soleInteger(Set<Integer> numbers) {
        if (numbers.size() == 1) {
            return count(numbers.iterator().next());
        }
        return MIXED_MEASUREMENT + " " + numbers.size();
    }

    /**
     * Returns how many times each distinct value occurs when every one occurs the same number of
     * times, and {@link #MIXED_MEASUREMENT} otherwise.
     */
    private static String soleOccurrenceCount(List<String> values) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (String value : values) {
            occurrences.merge(value, 1, Integer::sum);
        }
        Set<Integer> counts = new LinkedHashSet<>(occurrences.values());
        if (counts.size() == 1) {
            return count(counts.iterator().next());
        }
        return MIXED_MEASUREMENT + " " + counts.size();
    }

    /** Joins the distinct values of a list in ascending order, as the census records a value set. */
    private static String joinedDistinct(List<String> values) {
        return String.join(VALUE_SEPARATOR, new TreeSet<>(values));
    }

    /** Renders a boolean the way the census records it. */
    private static String yesNo(boolean flag) {
        return flag ? YES : NO;
    }

    /** Renders a money value at the scale its picture clause declares. */
    private static String amount(BigDecimal value) {
        return value.toPlainString();
    }

    /** Removes the trailing space padding a {@code PIC X(n)} field carries. */
    private static String withoutPadding(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == COBOL_TEXT_PAD) {
            end--;
        }
        return value.substring(0, end);
    }

    /** Removes the trailing space padding from every element of a list. */
    private static List<String> withoutPadding(List<String> values) {
        List<String> stripped = new ArrayList<>(values.size());
        for (String value : values) {
            stripped.add(withoutPadding(value));
        }
        return List.copyOf(stripped);
    }

    /** Reports whether a character carries a sign overpunch rather than a plain digit. */
    private static boolean isSignOverpunch(char candidate) {
        return CopybookRecordParser.POSITIVE_SIGN_OVERPUNCH_DIGITS.indexOf(candidate) >= 0
                || CopybookRecordParser.NEGATIVE_SIGN_OVERPUNCH_DIGITS.indexOf(candidate) >= 0;
    }

    /** Reports whether a character is one of the ten ASCII digits. */
    private static boolean isAsciiDigit(char candidate) {
        return candidate >= '0' && candidate <= '9';
    }

    /** Widths the records of a fixture occupy. */
    private static Set<Integer> deliveredWidths(String entityKey) {
        Set<Integer> widths = new LinkedHashSet<>();
        for (String record : fixtureRecords(entityKey)) {
            widths.add(record.length());
        }
        return widths;
    }

    /**
     * Counts trailing sign positions across a fixture, either overpunched or plain.
     *
     * @param entityKey   fixture to scan
     * @param overpunched true to count positions carrying an overpunch, false to count plain digits
     * @return the position count over every signed field of every record
     */
    private static int countSignPositions(String entityKey, boolean overpunched) {
        Layout layout = LAYOUTS.get(entityKey);
        int total = 0;
        for (String record : fixtureRecords(entityKey)) {
            for (LayoutField signed : layout.signedFields()) {
                char trailing = trailingCharacter(layout, record, signed);
                if (overpunched ? isSignOverpunch(trailing) : isAsciiDigit(trailing)) {
                    total++;
                }
            }
        }
        return total;
    }

    /** Counts the records whose named signed field carries a sign overpunch. */
    private static int countOverpunchedRecords(String entityKey, String fieldName) {
        Layout layout = LAYOUTS.get(entityKey);
        LayoutField target = layout.field(fieldName);
        int total = 0;
        for (String record : fixtureRecords(entityKey)) {
            if (isSignOverpunch(trailingCharacter(layout, record, target))) {
                total++;
            }
        }
        return total;
    }

    /** Reads the trailing character of one field of one record. */
    private static char trailingCharacter(Layout layout, String record, LayoutField target) {
        String value = CopybookRecordParser.fixedText(record, layout.offsetOf(target.name()),
                target.width(), target.name());
        return value.charAt(value.length() - 1);
    }

    /** Derives the filler character of a fixture, or reports the filler as absent. */
    private static String fillerCharacter(String entityKey) {
        Layout layout = LAYOUTS.get(entityKey);
        LayoutField filler = layout.filler();
        int offset = layout.offsetOf(filler.name());
        List<String> segments = new ArrayList<>();
        for (String record : fixtureRecords(entityKey)) {
            if (record.length() < offset + filler.width()) {
                return FILLER_ABSENT;
            }
            segments.add(record.substring(offset, offset + filler.width()));
        }
        String allSpaces = String.valueOf(COBOL_TEXT_PAD).repeat(filler.width());
        String allZeros = String.valueOf(ASCII_ZERO).repeat(filler.width());
        Set<String> distinct = new LinkedHashSet<>(segments);
        if (distinct.equals(Set.of(allSpaces))) {
            return FILLER_SPACE;
        }
        if (distinct.equals(Set.of(allZeros))) {
            return FILLER_ASCII_ZERO;
        }
        return MIXED_MEASUREMENT;
    }

    /** Derives the line ending of a fixture from its bytes. */
    private static String lineEnding(String entityKey) {
        byte[] bytes = fixtureBytes(entityKey);
        boolean pair = false;
        boolean lineFeed = false;
        boolean carriage = false;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == LINE_FEED_BYTE) {
                lineFeed = true;
                if (index > 0 && bytes[index - 1] == CARRIAGE_RETURN_BYTE) {
                    pair = true;
                }
            }
            if (bytes[index] == CARRIAGE_RETURN_BYTE) {
                carriage = true;
            }
        }
        if (pair) {
            return CARRIAGE_RETURN_LINE_FEED;
        }
        if (lineFeed && !carriage) {
            return LINE_FEED;
        }
        if (carriage && !lineFeed) {
            return CARRIAGE_RETURN;
        }
        if (!lineFeed && !carriage) {
            return NO_LINE_ENDING;
        }
        return MIXED_MEASUREMENT;
    }

    /** Counts one byte value across a fixture. */
    private static int countByte(String entityKey, byte target) {
        int total = 0;
        for (byte current : fixtureBytes(entityKey)) {
            if (current == target) {
                total++;
            }
        }
        return total;
    }

    // Generic derivations. Each applies to any fixture that records the measurement.

    /**
     * Derivations keyed by census field, applying to every fixture that records the field.
     *
     * <p>Built on first use rather than at class initialisation, because the tables the entity
     * derivations read are declared below this point and a static initialiser runs in declaration
     * order.</p>
     */
    private static final Supplier<Map<String, Function<CensusRow, String>>> GENERIC_DERIVATIONS =
            readOnce(FixtureCoverageEquivalenceTest::genericDerivations);

    /** Builds {@link #GENERIC_DERIVATIONS}. */
    private static Map<String, Function<CensusRow, String>> genericDerivations() {
        Map<String, Function<CensusRow, String>> derivations = new LinkedHashMap<>();
        derivations.put("record_count",
                row -> count(fixtureRecords(row.entityKey()).size()));
        derivations.put("record_width_as_delivered",
                row -> soleInteger(deliveredWidths(row.entityKey())));
        derivations.put("byte_count",
                row -> count(fixtureBytes(row.entityKey()).length));
        derivations.put("line_ending",
                row -> lineEnding(row.entityKey()));
        derivations.put("terminating_newline", row -> {
            byte[] bytes = fixtureBytes(row.entityKey());
            return yesNo(bytes.length > 0 && bytes[bytes.length - 1] == LINE_FEED_BYTE);
        });
        derivations.put("carriage_return_count",
                row -> count(countByte(row.entityKey(), CARRIAGE_RETURN_BYTE)));
        derivations.put("width_uniform",
                row -> yesNo(deliveredWidths(row.entityKey()).size() == 1));
        derivations.put("filler_character",
                row -> fillerCharacter(row.entityKey()));
        derivations.put("overpunch_position_count",
                row -> count(countSignPositions(row.entityKey(), true)));
        derivations.put("plain_digit_sign_position_count",
                row -> count(countSignPositions(row.entityKey(), false)));
        derivations.put("signed_fields_per_record",
                row -> count(LAYOUTS.get(row.entityKey()).signedFields().size()));
        derivations.put("ebcdic_twin_width_agrees", row -> {
            Layout layout = LAYOUTS.get(row.entityKey());
            Set<Integer> widths = deliveredWidths(row.entityKey());
            if (widths.size() != 1) {
                return MIXED_MEASUREMENT;
            }
            long delivered = (long) fixtureRecords(row.entityKey()).size()
                    * widths.iterator().next();
            return yesNo(bytesOf(ebcdicPath(layout.ebcdicTwinFileName())).length == delivered);
        });
        derivations.put("inventory_member",
                row -> yesNo(authoritativeEBCDICFiles().contains(row.derivedFrom())));
        return Map.copyOf(derivations);
    }

    // Typed-value helpers, used by the derivations that read parsed fields.

    /** Reads one text component of every record. */
    private static <T> List<String> textOf(List<T> records, Function<T, String> reader) {
        List<String> values = new ArrayList<>(records.size());
        for (T record : records) {
            values.add(reader.apply(record));
        }
        return List.copyOf(values);
    }

    /** Reads one money component of every record. */
    private static <T> List<BigDecimal> amountsOf(List<T> records,
            Function<T, BigDecimal> reader) {
        List<BigDecimal> values = new ArrayList<>(records.size());
        for (T record : records) {
            values.add(reader.apply(record));
        }
        return List.copyOf(values);
    }

    /** Renders every money value at the scale its picture clause declares. */
    private static List<String> rendered(List<BigDecimal> amounts) {
        List<String> values = new ArrayList<>(amounts.size());
        for (BigDecimal amount : amounts) {
            values.add(amount(amount));
        }
        return List.copyOf(values);
    }

    /** Lowest money value of a list. */
    private static BigDecimal least(List<BigDecimal> amounts) {
        BigDecimal lowest = amounts.get(0);
        for (BigDecimal amount : amounts) {
            if (amount.compareTo(lowest) < 0) {
                lowest = amount;
            }
        }
        return lowest;
    }

    /** Highest money value of a list. */
    private static BigDecimal greatest(List<BigDecimal> amounts) {
        BigDecimal highest = amounts.get(0);
        for (BigDecimal amount : amounts) {
            if (amount.compareTo(highest) > 0) {
                highest = amount;
            }
        }
        return highest;
    }

    /** Lowest text value of a list, compared as the COBOL comparison compares text. */
    private static String lowest(List<String> values) {
        String lowest = values.get(0);
        for (String value : values) {
            if (value.compareTo(lowest) < 0) {
                lowest = value;
            }
        }
        return lowest;
    }

    /** Highest text value of a list, compared as the COBOL comparison compares text. */
    private static String highest(List<String> values) {
        String highest = values.get(0);
        for (String value : values) {
            if (value.compareTo(highest) > 0) {
                highest = value;
            }
        }
        return highest;
    }

    /** Counts one character inside a value. */
    private static int countCharacter(String value, char target) {
        int total = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == target) {
                total++;
            }
        }
        return total;
    }

    /** Counts the records whose named text field carries only one repeated character count. */
    private static String solePaddingCount(String entityKey, String fieldName) {
        Set<Integer> counts = new LinkedHashSet<>();
        for (String value : fieldOfEveryRecord(entityKey, fieldName)) {
            counts.add(countCharacter(value, COBOL_TEXT_PAD));
        }
        return soleInteger(counts);
    }

    // Timestamp shape derivation. One component table serves both timestamp forms, because the
    // origin timestamp of app/cpy/CVTRA06Y.cpy:L16 and the rendered timestamp of
    // app/cbl/CBTRN02C.cbl:L159-L174 place their components at the same offsets and differ only in
    // the separator characters between them and in how the six fraction bytes are used.

    /**
     * One component of a timestamp.
     *
     * @param offset zero-based offset of the component
     * @param width  characters the component occupies
     * @param letter letter the derived shape carries at each of those characters
     */
    private record ShapeComponent(int offset, int width, char letter) {
    }

    /** The six calendar and clock components, at the offsets {@link PicClause} publishes. */
    private static final List<ShapeComponent> TIMESTAMP_COMPONENTS = List.of(
            new ShapeComponent(PicClause.PROCESSING_TIMESTAMP_YEAR_OFFSET,
                    PicClause.PROCESSING_TIMESTAMP_YEAR_WIDTH, SHAPE_YEAR),
            new ShapeComponent(PicClause.PROCESSING_TIMESTAMP_MONTH_OFFSET,
                    PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH, SHAPE_MONTH),
            new ShapeComponent(PicClause.PROCESSING_TIMESTAMP_DAY_OFFSET,
                    PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH, SHAPE_DAY),
            new ShapeComponent(PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET,
                    PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH, SHAPE_HOUR),
            new ShapeComponent(PicClause.PROCESSING_TIMESTAMP_MINUTE_OFFSET,
                    PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH, SHAPE_MINUTE),
            new ShapeComponent(PicClause.PROCESSING_TIMESTAMP_SECOND_OFFSET,
                    PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH, SHAPE_SECOND));

    /**
     * Derives the shape of a timestamp by replacing each component character with its letter and
     * keeping every other character as the timestamp holds it.
     *
     * @param timestamp the timestamp to measure
     * @param processing true for a timestamp a service rendered, whose trailing four bytes are the
     *        hard-coded zeros of {@code app/cbl/CBTRN02C.cbl:L701} and therefore appear literally
     * @return the shape, at the width of {@code timestamp}
     */
    private static String timestampShape(String timestamp, boolean processing) {
        StringBuilder shape = new StringBuilder(timestamp.length());
        for (int index = 0; index < timestamp.length(); index++) {
            Character letter = shapeLetter(index, processing);
            shape.append(letter == null ? timestamp.charAt(index) : letter.charValue());
        }
        return shape.toString();
    }

    /** Returns the shape letter of one offset, or null where the timestamp's own byte shows. */
    private static Character shapeLetter(int index, boolean processing) {
        for (ShapeComponent component : TIMESTAMP_COMPONENTS) {
            if (index >= component.offset() && index < component.offset() + component.width()) {
                return component.letter();
            }
        }
        int fractionStart = PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET;
        int significantEnd =
                fractionStart + PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS;
        if (index >= fractionStart && index < significantEnd) {
            return processing ? SHAPE_HUNDREDTHS : SHAPE_FRACTION;
        }
        if (index >= significantEnd && index < PicClause.PROCESSING_TIMESTAMP_WIDTH) {
            return processing ? null : SHAPE_FRACTION;
        }
        return null;
    }

    /** The rendered timestamp, put through the truncation the ledger applies before it posts. */
    private static final Supplier<String> TRUNCATED_PROCESSING_TIMESTAMP = readOnce(() ->
            CopybookRecordParser.truncateProcessingTimestampToHundredths(
                    RENDERED_PROCESSING_TIMESTAMP));

    /** Width of the security record, summed from the picture clauses of its copybook. */
    private static int securityRecordWidth() {
        int total = 0;
        int clauses = 0;
        for (String line : linesOf(SECURITY_COPYBOOK)) {
            Matcher clause = PICTURE_TEXT_CLAUSE.matcher(line);
            while (clause.find()) {
                total += Integer.parseInt(clause.group(FIRST_ORDINAL));
                clauses++;
            }
        }
        return clauses == SECURITY_RECORD_FIELD_COUNT ? total : 0;
    }

    // Entity derivations. Each is keyed by entity key and field, and is looked up before the
    // generic derivation of the same field, so a fixture with no text file overrides every one.

    /** Derivations keyed by entity key and census field, built on first use for the same reason. */
    private static final Supplier<Map<String, Function<CensusRow, String>>> SPECIFIC_DERIVATIONS =
            readOnce(FixtureCoverageEquivalenceTest::specificDerivations);

    /** Builds {@link #SPECIFIC_DERIVATIONS} from the seven groups below. */
    private static Map<String, Function<CensusRow, String>> specificDerivations() {
        Map<String, Function<CensusRow, String>> derivations = new LinkedHashMap<>();
        accountDerivations(derivations);
        cardDerivations(derivations);
        crossReferenceDerivations(derivations);
        customerDerivations(derivations);
        dailyTransactionDerivations(derivations);
        referenceDataDerivations(derivations);
        securityDerivations(derivations);
        return Map.copyOf(derivations);
    }

    /**
     * The five signed account fields, keyed by the census field prefix that names each one.
     *
     * <p>The census records one overpunch position count per field as well as the total, so the
     * five per-field rows and the one total row cross-check each other.</p>
     */
    private static final Map<String, String> ACCOUNT_SIGNED_FIELDS = Map.of(
            "acct_curr_bal", "ACCT-CURR-BAL",
            "acct_credit_limit", "ACCT-CREDIT-LIMIT",
            "acct_cash_credit_limit", "ACCT-CASH-CREDIT-LIMIT",
            "acct_curr_cyc_credit", "ACCT-CURR-CYC-CREDIT",
            "acct_curr_cyc_debit", "ACCT-CURR-CYC-DEBIT");

    /** Suffix of the five per-field overpunch census rows of {@code app/data/ASCII/acctdata.txt}. */
    private static final String OVERPUNCH_SUFFIX = "_overpunch_position_count";

    /** Adds the derivations of {@code app/data/ASCII/acctdata.txt}. */
    private static void accountDerivations(Map<String, Function<CensusRow, String>> derivations) {
        derivations.put("acctdata.distinct_account_ids", row -> distinctCount(
                textOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::accountId)));
        derivations.put("acctdata.active_status_distinct_values", row -> distinctCount(
                textOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::activeStatus)));
        derivations.put("acctdata.active_status_value", row -> soleValue(
                textOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::activeStatus)));
        derivations.put("acctdata.current_balance_min", row -> amount(least(
                amountsOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::currentBalance))));
        derivations.put("acctdata.current_balance_max", row -> amount(greatest(
                amountsOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::currentBalance))));
        derivations.put("acctdata.credit_limit_min", row -> amount(least(
                amountsOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::creditLimit))));
        derivations.put("acctdata.credit_limit_max", row -> amount(greatest(
                amountsOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::creditLimit))));
        derivations.put("acctdata.distinct_expiration_dates", row -> distinctCount(
                textOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::expirationDate)));
        derivations.put("acctdata.earliest_expiration_date", row -> lowest(
                textOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::expirationDate)));
        derivations.put("acctdata.latest_expiration_date", row -> highest(
                textOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::expirationDate)));
        derivations.put("acctdata.cycle_credit_distinct_values", row -> distinctCount(rendered(
                amountsOf(ACCOUNTS.get(),
                        CopybookRecordParser.AccountRecord::currentCycleCredit))));
        derivations.put("acctdata.cycle_credit_value", row -> soleValue(rendered(
                amountsOf(ACCOUNTS.get(),
                        CopybookRecordParser.AccountRecord::currentCycleCredit))));
        derivations.put("acctdata.cycle_debit_distinct_values", row -> distinctCount(rendered(
                amountsOf(ACCOUNTS.get(),
                        CopybookRecordParser.AccountRecord::currentCycleDebit))));
        derivations.put("acctdata.cycle_debit_value", row -> soleValue(rendered(
                amountsOf(ACCOUNTS.get(),
                        CopybookRecordParser.AccountRecord::currentCycleDebit))));
        derivations.put("acctdata.addr_zip_value", row -> soleValue(
                textOf(ACCOUNTS.get(), CopybookRecordParser.AccountRecord::addressZip)));
        derivations.put("acctdata.addr_zip_matches_discgrp_group_id", row -> {
            Set<String> groups = new LinkedHashSet<>(withoutPadding(textOf(DISCLOSURE_GROUPS.get(),
                    CopybookRecordParser.DisclosureGroupRecord::accountGroupId)));
            for (CopybookRecordParser.AccountRecord account : ACCOUNTS.get()) {
                if (!groups.contains(withoutPadding(account.addressZip()))) {
                    return NO;
                }
            }
            return YES;
        });
        derivations.put("acctdata.group_id_blank", row -> {
            for (CopybookRecordParser.AccountRecord account : ACCOUNTS.get()) {
                if (!account.groupId().isBlank()) {
                    return NO;
                }
            }
            return YES;
        });
        derivations.put("acctdata.group_id_space_count",
                row -> solePaddingCount("acctdata", "ACCT-GROUP-ID"));
        for (Map.Entry<String, String> signed : ACCOUNT_SIGNED_FIELDS.entrySet()) {
            String cobolField = signed.getValue();
            derivations.put("acctdata." + signed.getKey() + OVERPUNCH_SUFFIX,
                    row -> count(countOverpunchedRecords("acctdata", cobolField)));
        }
    }

    /** Adds the derivations of {@code app/data/ASCII/carddata.txt}. */
    private static void cardDerivations(Map<String, Function<CensusRow, String>> derivations) {
        derivations.put("carddata.distinct_card_numbers", row -> distinctCount(
                textOf(CARDS.get(), CopybookRecordParser.CardRecord::cardNumber)));
        derivations.put("carddata.active_status_distinct_values", row -> distinctCount(
                textOf(CARDS.get(), CopybookRecordParser.CardRecord::activeStatus)));
        derivations.put("carddata.active_status_value", row -> soleValue(
                textOf(CARDS.get(), CopybookRecordParser.CardRecord::activeStatus)));
        derivations.put("carddata.cvv_present", row -> {
            Layout layout = LAYOUTS.get("carddata");
            LayoutField verificationValue = layout.field("CARD-CVV-CD");
            for (String record : fixtureRecords("carddata")) {
                try {
                    // The read proves the field holds digits at its declared width. It returns the
                    // value and the value is discarded, because a verification value must not reach
                    // a variable this class could report. The read itself quotes nothing.
                    CopybookRecordParser.unsignedInteger(record,
                            layout.offsetOf(verificationValue.name()), verificationValue.width(),
                            verificationValue.name());
                } catch (IllegalArgumentException notPresent) {
                    return NO;
                }
            }
            return YES;
        });
    }

    /** Adds the derivations of {@code app/data/ASCII/cardxref.txt}. */
    private static void crossReferenceDerivations(
            Map<String, Function<CensusRow, String>> derivations) {
        derivations.put("cardxref.record_width_declared",
                row -> count(LAYOUTS.get("cardxref").declaredWidth()));
        derivations.put("cardxref.record_width_ebcdic_twin", row -> {
            int records = fixtureRecords("cardxref").size();
            byte[] twin = bytesOf(ebcdicPath(LAYOUTS.get("cardxref").ebcdicTwinFileName()));
            if (records == 0 || twin.length % records != 0) {
                return MIXED_MEASUREMENT;
            }
            return count(twin.length / records);
        });
        derivations.put("cardxref.trailing_filler_present", row -> {
            Layout layout = LAYOUTS.get("cardxref");
            LayoutField filler = layout.filler();
            int required = layout.offsetOf(filler.name()) + filler.width();
            for (String record : fixtureRecords("cardxref")) {
                if (record.length() < required) {
                    return NO;
                }
            }
            return YES;
        });
        derivations.put("cardxref.all_cards_resolve", row -> {
            Map<String, CopybookRecordParser.CardCrossReferenceRecord> byCardNumber =
                    CROSS_REFERENCES_BY_CARD.get();
            for (CopybookRecordParser.CardRecord card : CARDS.get()) {
                if (!byCardNumber.containsKey(
                        CardDemoFixtureLoader.cardNumberKey(card.cardNumber()))) {
                    return NO;
                }
            }
            return YES;
        });
        derivations.put("cardxref.distinct_customer_ids", row -> distinctCount(textOf(
                CROSS_REFERENCES.get(),
                CopybookRecordParser.CardCrossReferenceRecord::customerId)));
        derivations.put("cardxref.all_resolved_accounts_exist_in_acctdata", row -> {
            Map<String, CopybookRecordParser.AccountRecord> byAccountId =
                    ACCOUNTS_BY_ACCOUNT_ID.get();
            for (CopybookRecordParser.CardCrossReferenceRecord crossReference
                    : CROSS_REFERENCES.get()) {
                if (!byAccountId.containsKey(crossReference.accountId())) {
                    return NO;
                }
            }
            return YES;
        });
    }

    /** Adds the derivations of {@code app/data/ASCII/custdata.txt}. */
    private static void customerDerivations(Map<String, Function<CensusRow, String>> derivations) {
        derivations.put("custdata.distinct_customer_ids", row -> distinctCount(
                textOf(CUSTOMERS.get(), CopybookRecordParser.CustomerRecord::customerId)));
    }

    /** Transaction type {@code app/data/ASCII/trantype.txt} names, carried by 250 fixture records. */
    private static final String DAILY_TYPE_CODE_PURCHASE = "01";

    /**
     * Transaction type carried by the fifty fixture records holding a negative amount, which
     * {@code app/cbl/CBTRN02C.cbl:L548} routes to the cycle debit accumulator.
     */
    private static final String DAILY_TYPE_CODE_CREDIT = "03";

    /** Reason {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns when the cross-reference read misses. */
    private static final int DECLINE_REASON_INVALID_CARD = 100;

    /** Reason {@code app/cbl/CBTRN02C.cbl:L397-L399} assigns when the account read misses. */
    private static final int DECLINE_REASON_ACCOUNT_MISSING = 101;

    /** Reason {@code app/cbl/CBTRN02C.cbl:L403-L413} assigns when the credit limit is exceeded. */
    private static final int DECLINE_REASON_OVERLIMIT = 102;

    /** Reason {@code app/cbl/CBTRN02C.cbl:L414-L420} assigns when the account has expired. */
    private static final int DECLINE_REASON_EXPIRED = 103;

    /** Value the reason field holds while validation is still passing. */
    private static final int DECLINE_REASON_NONE = 0;

    /**
     * Runs the validation chain of {@code app/cbl/CBTRN02C.cbl:L370-L420} over one fixture record.
     *
     * <p>The chain short-circuits exactly as the source does: a cross-reference miss reports 100 and
     * never reaches 101, and an over-limit record reports 102 and never reaches the expiration test.
     * The over-limit arithmetic runs at full precision here because every fixture cycle accumulator
     * is zero and every amount is under a thousand, which is nine orders of magnitude below the
     * narrowing boundary of {@code app/cbl/CBTRN02C.cbl:L187}. That narrowing is asserted at its own
     * boundary by {@code AuthorizationDecisionEquivalenceTest}.</p>
     *
     * @param daily one record of {@code app/data/ASCII/dailytran.txt}
     * @return the reason the chain assigns, or {@link #DECLINE_REASON_NONE} when it passes
     */
    private static int declineReasonOf(CopybookRecordParser.DailyTransactionRecord daily) {
        CopybookRecordParser.CardCrossReferenceRecord crossReference =
                CROSS_REFERENCES_BY_CARD.get().get(daily.cardNumber());
        if (crossReference == null) {
            return DECLINE_REASON_INVALID_CARD;
        }
        CopybookRecordParser.AccountRecord account =
                ACCOUNTS_BY_ACCOUNT_ID.get().get(crossReference.accountId());
        if (account == null) {
            return DECLINE_REASON_ACCOUNT_MISSING;
        }
        BigDecimal working = account.currentCycleCredit()
                .subtract(account.currentCycleDebit())
                .add(daily.amount());
        if (account.creditLimit().compareTo(working) < 0) {
            return DECLINE_REASON_OVERLIMIT;
        }
        String originDate = CopybookRecordParser.timestampDatePart(daily.originTimestamp());
        if (account.expirationDate().compareTo(originDate) < 0) {
            return DECLINE_REASON_EXPIRED;
        }
        return DECLINE_REASON_NONE;
    }

    /** Counts the fixture records the validation chain rejects with one reason. */
    private static int declineOccurrences(int reason) {
        int occurrences = 0;
        for (CopybookRecordParser.DailyTransactionRecord daily : DAILY_TRANSACTIONS.get()) {
            if (declineReasonOf(daily) == reason) {
                occurrences++;
            }
        }
        return occurrences;
    }

    /** Counts the fixture records carrying one transaction type code. */
    private static int recordsOfType(String typeCode) {
        int occurrences = 0;
        for (CopybookRecordParser.DailyTransactionRecord daily : DAILY_TRANSACTIONS.get()) {
            if (daily.typeCode().equals(typeCode)) {
                occurrences++;
            }
        }
        return occurrences;
    }

    /** Card numbers of the fixture records carrying one transaction type code. */
    private static List<String> cardNumbersOfType(String typeCode) {
        List<String> cardNumbers = new ArrayList<>();
        for (CopybookRecordParser.DailyTransactionRecord daily : DAILY_TRANSACTIONS.get()) {
            if (daily.typeCode().equals(typeCode)) {
                cardNumbers.add(daily.cardNumber());
            }
        }
        return List.copyOf(cardNumbers);
    }

    /** Adds the derivations of {@code app/data/ASCII/dailytran.txt}. */
    private static void dailyTransactionDerivations(
            Map<String, Function<CensusRow, String>> derivations) {
        derivations.put("dailytran.type_01_record_count",
                row -> count(recordsOfType(DAILY_TYPE_CODE_PURCHASE)));
        derivations.put("dailytran.type_03_record_count",
                row -> count(recordsOfType(DAILY_TYPE_CODE_CREDIT)));
        derivations.put("dailytran.distinct_category_codes", row -> distinctCount(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::categoryCode)));
        derivations.put("dailytran.category_code", row -> soleValue(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::categoryCode)));
        derivations.put("dailytran.negative_amount_count", row -> {
            int negative = 0;
            for (BigDecimal value : amountsOf(DAILY_TRANSACTIONS.get(),
                    CopybookRecordParser.DailyTransactionRecord::amount)) {
                if (value.signum() < 0) {
                    negative++;
                }
            }
            return count(negative);
        });
        derivations.put("dailytran.negative_amounts_equal_type_03_set", row -> {
            Set<Integer> negative = new LinkedHashSet<>();
            Set<Integer> credits = new LinkedHashSet<>();
            List<CopybookRecordParser.DailyTransactionRecord> records = DAILY_TRANSACTIONS.get();
            for (int index = 0; index < records.size(); index++) {
                if (records.get(index).amount().signum() < 0) {
                    negative.add(index);
                }
                if (records.get(index).typeCode().equals(DAILY_TYPE_CODE_CREDIT)) {
                    credits.add(index);
                }
            }
            return yesNo(negative.equals(credits));
        });
        derivations.put("dailytran.amount_min", row -> amount(least(amountsOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::amount))));
        derivations.put("dailytran.amount_max", row -> amount(greatest(amountsOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::amount))));
        derivations.put("dailytran.distinct_transaction_ids", row -> distinctCount(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::transactionId)));
        derivations.put("dailytran.distinct_card_count", row -> distinctCount(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::cardNumber)));
        derivations.put("dailytran.occurrences_per_card", row -> soleOccurrenceCount(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::cardNumber)));
        derivations.put("dailytran.type_01_occurrences_per_card",
                row -> soleOccurrenceCount(cardNumbersOfType(DAILY_TYPE_CODE_PURCHASE)));
        derivations.put("dailytran.type_03_occurrences_per_card",
                row -> soleOccurrenceCount(cardNumbersOfType(DAILY_TYPE_CODE_CREDIT)));
        derivations.put("dailytran.distinct_transaction_sources", row -> distinctCount(textOf(
                DAILY_TRANSACTIONS.get(), CopybookRecordParser.DailyTransactionRecord::source)));
        derivations.put("dailytran.transaction_source_values", row -> joinedDistinct(textOf(
                DAILY_TRANSACTIONS.get(), CopybookRecordParser.DailyTransactionRecord::source)));
        derivations.put("dailytran.distinct_merchant_ids", row -> distinctCount(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::merchantId)));
        derivations.put("dailytran.merchant_id_value", row -> soleValue(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::merchantId)));
        derivations.put("dailytran.distinct_orig_timestamps", row -> distinctCount(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::originTimestamp)));
        derivations.put("dailytran.orig_timestamp_value", row -> soleValue(textOf(
                DAILY_TRANSACTIONS.get(),
                CopybookRecordParser.DailyTransactionRecord::originTimestamp)));
        derivations.put("dailytran.orig_timestamp_shape", row -> {
            String timestamp = soleValue(textOf(DAILY_TRANSACTIONS.get(),
                    CopybookRecordParser.DailyTransactionRecord::originTimestamp));
            return timestamp.startsWith(MIXED_MEASUREMENT)
                    ? timestamp
                    : timestampShape(timestamp, false);
        });
        derivations.put("dailytran.orig_timestamp_fraction_digits", row -> {
            String timestamp = soleValue(textOf(DAILY_TRANSACTIONS.get(),
                    CopybookRecordParser.DailyTransactionRecord::originTimestamp));
            return timestamp.startsWith(MIXED_MEASUREMENT)
                    ? timestamp
                    : count(countCharacter(timestampShape(timestamp, false), SHAPE_FRACTION));
        });
        derivations.put("dailytran.distinct_orig_dates", row -> {
            List<String> dates = new ArrayList<>();
            for (CopybookRecordParser.DailyTransactionRecord daily : DAILY_TRANSACTIONS.get()) {
                dates.add(CopybookRecordParser.timestampDatePart(daily.originTimestamp()));
            }
            return distinctCount(dates);
        });
        derivations.put("dailytran.orig_date_value", row -> {
            List<String> dates = new ArrayList<>();
            for (CopybookRecordParser.DailyTransactionRecord daily : DAILY_TRANSACTIONS.get()) {
                dates.add(CopybookRecordParser.timestampDatePart(daily.originTimestamp()));
            }
            return soleValue(dates);
        });
        derivations.put("dailytran.proc_timestamp_populated", row -> {
            for (CopybookRecordParser.DailyTransactionRecord daily : DAILY_TRANSACTIONS.get()) {
                if (!daily.processingTimestamp().isBlank()) {
                    return YES;
                }
            }
            return NO;
        });
        derivations.put("dailytran.proc_timestamp_space_count",
                row -> solePaddingCount("dailytran", "DALYTRAN-PROC-TS"));
        derivations.put("dailytran.proc_timestamp_rendered_shape",
                row -> timestampShape(TRUNCATED_PROCESSING_TIMESTAMP.get(), true));
        derivations.put("dailytran.proc_timestamp_rendered_fraction_digits", row -> count(
                countCharacter(timestampShape(TRUNCATED_PROCESSING_TIMESTAMP.get(), true),
                        SHAPE_HUNDREDTHS)));
        derivations.put("dailytran.proc_timestamp_rendered_trailing_zeros",
                row -> TRUNCATED_PROCESSING_TIMESTAMP.get()
                        .substring(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET));
        derivations.put("dailytran.proc_timestamp_rendered_dash_position", row -> count(
                timestampShape(TRUNCATED_PROCESSING_TIMESTAMP.get(), true)
                        .lastIndexOf(PicClause.PROCESSING_TIMESTAMP_DASH) + FIRST_ORDINAL));
        derivations.put("dailytran.decline_reason_100_fixture_occurrences",
                row -> count(declineOccurrences(DECLINE_REASON_INVALID_CARD)));
        derivations.put("dailytran.decline_reason_101_fixture_occurrences",
                row -> count(declineOccurrences(DECLINE_REASON_ACCOUNT_MISSING)));
        derivations.put("dailytran.decline_reason_102_fixture_reachable",
                row -> yesNo(declineOccurrences(DECLINE_REASON_OVERLIMIT) > 0));
        derivations.put("dailytran.decline_reason_103_fixture_occurrences",
                row -> count(declineOccurrences(DECLINE_REASON_EXPIRED)));
    }

    /** Adds the derivations of the four reference fixtures. */
    private static void referenceDataDerivations(
            Map<String, Function<CensusRow, String>> derivations) {
        derivations.put("discgrp.distinct_group_count", row -> distinctCount(withoutPadding(textOf(
                DISCLOSURE_GROUPS.get(),
                CopybookRecordParser.DisclosureGroupRecord::accountGroupId))));
        derivations.put("discgrp.group_id_values", row -> joinedDistinct(withoutPadding(textOf(
                DISCLOSURE_GROUPS.get(),
                CopybookRecordParser.DisclosureGroupRecord::accountGroupId))));
        derivations.put("discgrp.rows_per_group", row -> soleOccurrenceCount(withoutPadding(textOf(
                DISCLOSURE_GROUPS.get(),
                CopybookRecordParser.DisclosureGroupRecord::accountGroupId))));
        derivations.put("tcatbal.distinct_accounts", row -> distinctCount(textOf(
                CATEGORY_BALANCES.get(),
                CopybookRecordParser.TransactionCategoryBalanceRecord::accountId)));
        derivations.put("tcatbal.accounts_match_dailytran_accounts", row -> {
            Set<String> balanced = new LinkedHashSet<>(textOf(CATEGORY_BALANCES.get(),
                    CopybookRecordParser.TransactionCategoryBalanceRecord::accountId));
            Set<String> resolved = new LinkedHashSet<>();
            Map<String, CopybookRecordParser.CardCrossReferenceRecord> byCardNumber =
                    CROSS_REFERENCES_BY_CARD.get();
            for (CopybookRecordParser.DailyTransactionRecord daily : DAILY_TRANSACTIONS.get()) {
                CopybookRecordParser.CardCrossReferenceRecord crossReference =
                        byCardNumber.get(daily.cardNumber());
                if (crossReference != null) {
                    resolved.add(crossReference.accountId());
                }
            }
            return yesNo(balanced.equals(resolved));
        });
        derivations.put("tcatbal.type_code_distinct_values", row -> distinctCount(textOf(
                CATEGORY_BALANCES.get(),
                CopybookRecordParser.TransactionCategoryBalanceRecord::typeCode)));
        derivations.put("tcatbal.type_code_value", row -> soleValue(textOf(
                CATEGORY_BALANCES.get(),
                CopybookRecordParser.TransactionCategoryBalanceRecord::typeCode)));
        derivations.put("tcatbal.category_code_distinct_values", row -> distinctCount(textOf(
                CATEGORY_BALANCES.get(),
                CopybookRecordParser.TransactionCategoryBalanceRecord::categoryCode)));
        derivations.put("tcatbal.category_code_value", row -> soleValue(textOf(
                CATEGORY_BALANCES.get(),
                CopybookRecordParser.TransactionCategoryBalanceRecord::categoryCode)));
        derivations.put("tcatbal.balance_distinct_values", row -> distinctCount(rendered(amountsOf(
                CATEGORY_BALANCES.get(),
                CopybookRecordParser.TransactionCategoryBalanceRecord::balance))));
        derivations.put("tcatbal.balance_value", row -> soleValue(rendered(amountsOf(
                CATEGORY_BALANCES.get(),
                CopybookRecordParser.TransactionCategoryBalanceRecord::balance))));
        derivations.put("trancatg.distinct_type_codes", row -> distinctCount(textOf(
                TRANSACTION_CATEGORIES.get(),
                CopybookRecordParser.TransactionCategoryRecord::transactionTypeCode)));
        derivations.put("trancatg.distinct_category_codes", row -> distinctCount(textOf(
                TRANSACTION_CATEGORIES.get(),
                CopybookRecordParser.TransactionCategoryRecord::transactionCategoryCode)));
        derivations.put("trantype.distinct_type_codes", row -> distinctCount(textOf(
                TRANSACTION_TYPES.get(),
                CopybookRecordParser.TransactionTypeRecord::transactionType)));
        derivations.put("trantype.type_code_values", row -> joinedDistinct(textOf(
                TRANSACTION_TYPES.get(),
                CopybookRecordParser.TransactionTypeRecord::transactionType)));
    }

    /** Binary twin of the security dataset, the only source the census has for that entity. */
    private static final String USRSEC_TWIN_FILE_NAME = "AWS.M2.CARDDEMO.USRSEC.PS";

    /**
     * Adds the derivations of the security dataset.
     *
     * <p>{@code app/data/ASCII} holds no security fixture even though {@code app/data/EBCDIC} holds
     * its twin, so the width comes from the copybook and the record count from the twin's byte count
     * divided by that width.</p>
     */
    private static void securityDerivations(Map<String, Function<CensusRow, String>> derivations) {
        derivations.put(USRSEC_ENTITY + ".ascii_fixture_present", row -> yesNo(Files.isRegularFile(
                REPOSITORY_ROOT.get().resolve(ASCII_DIRECTORY).resolve(USRSEC_ABSENT_FIXTURE_NAME))));
        derivations.put(USRSEC_ENTITY + ".record_width_as_delivered",
                row -> count(securityRecordWidth()));
        derivations.put(USRSEC_ENTITY + ".record_count", row -> {
            int width = securityRecordWidth();
            byte[] twin = bytesOf(ebcdicPath(USRSEC_TWIN_FILE_NAME));
            if (width == 0 || twin.length % width != 0) {
                return MIXED_MEASUREMENT;
            }
            return count(twin.length / width);
        });
        derivations.put(USRSEC_ENTITY + ".byte_count",
                row -> count(bytesOf(ebcdicPath(USRSEC_TWIN_FILE_NAME)).length));
    }

    // Census file reading.

    /** Lines of the census file, comment and header included. */
    private static final Supplier<List<String>> CENSUS_LINES =
            readOnce(FixtureCoverageEquivalenceTest::readCensusLines);

    /** Data rows of the census file, in file order. */
    private static final Supplier<List<CensusRow>> CENSUS_ROWS =
            readOnce(FixtureCoverageEquivalenceTest::readCensusRows);

    /** Reads the census file from the test classpath, dropping any trailing blank line. */
    private static List<String> readCensusLines() {
        try (InputStream source =
                FixtureCoverageEquivalenceTest.class.getResourceAsStream(CENSUS_RESOURCE)) {
            if (source == null) {
                throw new IllegalStateException(CENSUS_PATH + " is absent from the test classpath "
                        + "at " + CENSUS_RESOURCE);
            }
            String text = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return List.copyOf(lines);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + CENSUS_PATH, unreadable);
        }
    }

    /** Parses every data row, which begins after the comment line and the header. */
    private static List<CensusRow> readCensusRows() {
        List<String> lines = CENSUS_LINES.get();
        List<CensusRow> rows = new ArrayList<>();
        for (int index = HEADER_LINE_INDEX + 1; index < lines.size(); index++) {
            List<String> fields = splitCsvLine(lines.get(index));
            if (fields.size() != CENSUS_COLUMNS.size()) {
                throw new IllegalStateException(CENSUS_PATH + " line " + (index + FIRST_ORDINAL)
                        + " holds " + fields.size() + " fields and the header declares "
                        + CENSUS_COLUMNS.size());
            }
            rows.add(new CensusRow(fields.get(0), fields.get(1), Integer.parseInt(fields.get(2)),
                    fields.get(3), fields.get(4), fields.get(5), fields.get(6), fields.get(7)));
        }
        return List.copyOf(rows);
    }

    /** Zero-based index of the comment line, which sits above the header. */
    private static final int COMMENT_LINE_INDEX = 0;

    /** Zero-based index of the header line. */
    private static final int HEADER_LINE_INDEX = 1;

    /** The one comment line of the census file. */
    private static String commentLine() {
        return CENSUS_LINES.get().get(COMMENT_LINE_INDEX);
    }

    /**
     * Splits one line into fields, honouring the double quotes that wrap the three value lists.
     *
     * <p>Three census rows record a comma-separated value set, so a split on every comma would tear
     * those rows apart and shift every field to its right.</p>
     */
    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quoted) {
                if (character != '"') {
                    current.append(character);
                } else if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = false;
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        fields.add(current.toString());
        return List.copyOf(fields);
    }

    // Row resolution and comparison.

    /**
     * Derives one row from the files it names, or returns null when no derivation covers it.
     *
     * <p>An entity derivation is looked up first, so a fixture with no text file overrides the
     * generic reading of the same field. An entity with no layout and no entity derivation is
     * reported as uncovered rather than raising, because an uncovered row is the failure the census
     * exists to catch.</p>
     */
    private static String derive(CensusRow row) {
        Function<CensusRow, String> specific = SPECIFIC_DERIVATIONS.get().get(row.specificKey());
        if (specific != null) {
            return specific.apply(row);
        }
        if (!LAYOUTS.containsKey(row.entityKey())) {
            return null;
        }
        Function<CensusRow, String> generic = GENERIC_DERIVATIONS.get().get(row.expectedField());
        return generic == null ? null : generic.apply(row);
    }

    /** Rows whose derived value differs from the value the census records. */
    private static List<String> mismatchesOf(List<CensusRow> rows) {
        List<String> mismatches = new ArrayList<>();
        for (CensusRow row : rows) {
            String derived = derive(row);
            if (derived == null || derived.equals(row.expectedValue())) {
                continue;
            }
            mismatches.add(row.label() + ": census records '" + row.expectedValue()
                    + "' and " + row.derivedFrom() + " derives '" + derived + "'");
        }
        return List.copyOf(mismatches);
    }

    /** Rows no derivation covers, which the census must never hold. */
    private static List<String> uncoveredOf(List<CensusRow> rows) {
        List<String> uncovered = new ArrayList<>();
        for (CensusRow row : rows) {
            if (derive(row) == null) {
                uncovered.add(row.label());
            }
        }
        return List.copyOf(uncovered);
    }

    /** Returns the one row recording a measurement, for use by the self-verification tests. */
    private static CensusRow rowFor(String entityKey, String expectedField) {
        for (CensusRow row : CENSUS_ROWS.get()) {
            if (row.entityKey().equals(entityKey) && row.expectedField().equals(expectedField)) {
                return row;
            }
        }
        throw new IllegalStateException(CENSUS_PATH + " holds no row for " + entityKey
                + KEY_SEPARATOR + expectedField);
    }

    /** Copies a row with one value replaced, so a mutation never edits the census file. */
    private static CensusRow withExpectedValue(CensusRow row, String expectedValue) {
        return new CensusRow(row.evaluationModel(), row.derivedFrom(), row.recordSeq(),
                row.entityKey(), row.expectedField(), expectedValue, row.picClause(),
                row.sourceLocator());
    }

    /** Repository-relative paths a row names, being its source file and every locator file. */
    private static Set<String> filesNamedBy(CensusRow row) {
        Set<String> files = new LinkedHashSet<>();
        files.add(row.derivedFrom());
        for (String locator : row.sourceLocator().split(VALUE_SEPARATOR)) {
            files.add(locator.split(":")[0]);
        }
        return files;
    }

    /** Text fixtures the census measures, being every layout this class models. */
    private static final int ASCII_FIXTURE_COUNT = 9;

    /** Entities the census records, being the text fixtures plus the security dataset. */
    private static final int CENSUS_ENTITY_COUNT = ASCII_FIXTURE_COUNT + 1;

    @Nested
    @DisplayName("Shape of expected/fixture-coverage.csv")
    class CensusFileContract {

        /**
         * The file opens with one comment line and one header, and the header declares the eight
         * columns in the order every row fills them.
         */
        @Test
        @DisplayName("one comment line and one header of eight named columns open the file")
        void oneCommentAndOneHeaderOpenTheFile() {
            List<String> lines = CENSUS_LINES.get();

            assertTrue(lines.size() > HEADER_LINE_INDEX + 1,
                    CENSUS_PATH + " holds a comment line, a header and at least one data row");
            assertTrue(commentLine().startsWith(COMMENT_PREFIX),
                    CENSUS_PATH + " line 1 opens with " + COMMENT_PREFIX
                            + ", so a reader parsing the file skips it");
            assertEquals(CENSUS_COLUMNS, splitCsvLine(lines.get(HEADER_LINE_INDEX)),
                    CENSUS_PATH + " declares the eight census columns in order");
        }

        /** The census holds the row count this class and the comment line both state. */
        @Test
        @DisplayName("the file holds exactly the declared number of data rows")
        void theFileHoldsTheDeclaredNumberOfDataRows() {
            assertEquals(CENSUS_DATA_ROW_COUNT, CENSUS_ROWS.get().size(),
                    CENSUS_PATH + " holds " + CENSUS_DATA_ROW_COUNT + " data rows; a row added or "
                            + "removed changes what the census claims and has to be stated here");
        }

        /**
         * Every row carries the one evaluation model, because fixture shape does not depend on the
         * accumulator state a posting run changes.
         */
        @Test
        @DisplayName("every row carries the NOT_APPLICABLE evaluation model")
        void everyRowCarriesTheOneEvaluationModel() {
            for (CensusRow row : CENSUS_ROWS.get()) {
                assertEquals(NOT_APPLICABLE, row.evaluationModel(),
                        row.label() + " carries an evaluation model the census does not define");
            }
        }

        /** Row ordinals run from one without a gap and without a repeat. */
        @Test
        @DisplayName("row ordinals run from one without gap or repeat")
        void rowOrdinalsRunFromOneWithoutGapOrRepeat() {
            List<CensusRow> rows = CENSUS_ROWS.get();
            Set<Integer> ordinals = new LinkedHashSet<>();
            for (CensusRow row : rows) {
                assertTrue(ordinals.add(row.recordSeq()),
                        "record_seq " + row.recordSeq() + " appears more than once");
            }
            for (int ordinal = FIRST_ORDINAL; ordinal <= rows.size(); ordinal++) {
                assertTrue(ordinals.contains(ordinal),
                        CENSUS_PATH + " skips record_seq " + ordinal);
            }
        }

        /** Every file a row names as its source, or in a locator, is present in the repository. */
        @Test
        @DisplayName("every file a row names is present in the repository")
        void everyFileARowNamesIsPresent() {
            Set<String> named = new LinkedHashSet<>();
            for (CensusRow row : CENSUS_ROWS.get()) {
                named.addAll(filesNamedBy(row));
            }
            for (String file : named) {
                assertTrue(Files.isRegularFile(REPOSITORY_ROOT.get().resolve(file)),
                        CENSUS_PATH + " names " + file + " and no file sits there");
            }
        }

        /**
         * The census measures the nine text fixtures, every binary fixture and the ten entities the
         * comment line claims, and no others.
         */
        @Test
        @DisplayName("the census measures nine text fixtures, all binary fixtures and ten entities")
        void theCensusMeasuresTheFilesItClaims() {
            Set<String> ascii = new LinkedHashSet<>();
            Set<String> ebcdic = new LinkedHashSet<>();
            Set<String> entities = new LinkedHashSet<>();
            for (CensusRow row : CENSUS_ROWS.get()) {
                entities.add(row.entityKey());
                for (String file : filesNamedBy(row)) {
                    if (file.startsWith(ASCII_DIRECTORY)) {
                        ascii.add(file);
                    }
                    if (file.startsWith(EBCDIC_DIRECTORY)) {
                        ebcdic.add(file);
                    }
                }
            }

            assertEquals(ASCII_FIXTURE_COUNT, ascii.size(),
                    "the census measures the nine fixtures of " + ASCII_DIRECTORY);
            assertEquals(authoritativeEBCDICFiles(), ebcdic,
                    "the census names every binary fixture present in " + EBCDIC_DIRECTORY);
            assertEquals(CENSUS_ENTITY_COUNT, entities.size(),
                    "the census records the nine text fixtures and the security dataset");
            assertEquals(LAYOUTS.keySet(), new LinkedHashSet<>(entities.stream()
                            .filter(entity -> !entity.equals(USRSEC_ENTITY)).toList()),
                    "every entity other than " + USRSEC_ENTITY + " has a modelled layout");
        }

        /**
         * The comment line states only what holds. It names this class as the reader, because this
         * class is what reads the file, and it states the counts the three assertions above measure.
         */
        @Test
        @DisplayName("the comment line names its reader and states counts that hold")
        void theCommentLineStatesOnlyWhatHolds() {
            String comment = commentLine();

            assertTrue(comment.contains(FixtureCoverageEquivalenceTest.class.getSimpleName()),
                    CENSUS_PATH + " line 1 names the class that reads it, which is "
                            + FixtureCoverageEquivalenceTest.class.getSimpleName());
            assertTrue(comment.contains(NOT_APPLICABLE),
                    CENSUS_PATH + " line 1 explains the one evaluation model every row carries");
            assertTrue(comment.contains(count(CENSUS_DATA_ROW_COUNT) + " data rows"),
                    CENSUS_PATH + " line 1 states the data row count the file holds");
            assertTrue(comment.contains(count(ASCII_FIXTURE_COUNT) + " ASCII fixtures"),
                    CENSUS_PATH + " line 1 states how many text fixtures the census measures");
            assertTrue(comment.contains(
                            count(authoritativeEBCDICFiles().size()) + " EBCDIC fixtures"),
                    CENSUS_PATH + " line 1 states how many binary fixtures the census reads");
            assertFalse(comment.contains(CardDemoFixtureLoader.class.getSimpleName()),
                    CENSUS_PATH + " line 1 must not name "
                            + CardDemoFixtureLoader.class.getSimpleName() + " as a reader of the "
                            + "census; that class reads the fixtures and never this file");
        }
    }

    @Nested
    @DisplayName("Rederivation of every census row from app/data")
    class RowRederivation {

        /**
         * Every row has a derivation. A row added without one fails here, which is what stops the
         * census growing rows that nothing recomputes.
         */
        @Test
        @DisplayName("every row is covered by a derivation")
        void everyRowIsCoveredByADerivation() {
            List<String> uncovered = uncoveredOf(CENSUS_ROWS.get());

            assertEquals(List.of(), uncovered,
                    "these census rows have no derivation, so nothing recomputes them: "
                            + uncovered);
        }

        /**
         * Every row rederives to the value the census records. One collected assertion reports every
         * divergence, so a change to a fixture is reported in full rather than one row at a time.
         */
        @Test
        @DisplayName("every row rederives to the value the census records")
        void everyRowRederivesToItsRecordedValue() {
            List<String> mismatches = mismatchesOf(CENSUS_ROWS.get());

            assertEquals(List.of(), mismatches,
                    "these census rows no longer agree with the files they are measured from: "
                            + mismatches);
        }

        /**
         * Every derivation is reached by a row. A derivation no row reaches is dead weight and would
         * hide a renamed census field behind an apparently complete run.
         */
        @Test
        @DisplayName("every derivation is reached by at least one row")
        void everyDerivationIsReachedByAtLeastOneRow() {
            Set<String> reachedSpecific = new LinkedHashSet<>();
            Set<String> reachedGeneric = new LinkedHashSet<>();
            for (CensusRow row : CENSUS_ROWS.get()) {
                if (SPECIFIC_DERIVATIONS.get().containsKey(row.specificKey())) {
                    reachedSpecific.add(row.specificKey());
                } else if (GENERIC_DERIVATIONS.get().containsKey(row.expectedField())) {
                    reachedGeneric.add(row.expectedField());
                }
            }

            Set<String> unusedSpecific = new TreeSet<>(SPECIFIC_DERIVATIONS.get().keySet());
            unusedSpecific.removeAll(reachedSpecific);
            Set<String> unusedGeneric = new TreeSet<>(GENERIC_DERIVATIONS.get().keySet());
            unusedGeneric.removeAll(reachedGeneric);

            assertEquals(Set.of(), unusedSpecific,
                    "these entity derivations are reached by no census row: " + unusedSpecific);
            assertEquals(Set.of(), unusedGeneric,
                    "these generic derivations are reached by no census row: " + unusedGeneric);
        }

        /**
         * The two derivation maps cover every row between them and neither is empty, so the census
         * cannot be satisfied by one catch-all reading.
         */
        @Test
        @DisplayName("entity and generic derivations together account for every row")
        void bothDerivationKindsAccountForEveryRow() {
            int specific = 0;
            int generic = 0;
            for (CensusRow row : CENSUS_ROWS.get()) {
                if (SPECIFIC_DERIVATIONS.get().containsKey(row.specificKey())) {
                    specific++;
                } else if (GENERIC_DERIVATIONS.get().containsKey(row.expectedField())) {
                    generic++;
                }
            }

            assertEquals(CENSUS_DATA_ROW_COUNT, specific + generic,
                    "every census row is read by exactly one of the two derivation maps");
            assertTrue(specific > 0, "entity derivations read the rows that name one fixture field");
            assertTrue(generic > 0, "generic derivations read the rows every fixture records");
        }
    }

    /**
     * Asserts that a loader routes every record of its fixture through one named parse operation.
     *
     * @param entityKey fixture the loader reads
     * @param parse     the parse operation the loader is expected to dispatch to
     * @param loaded    what the loader returned
     */
    private static void assertLoaderDispatchesTo(String entityKey,
            Function<String, Object> parse, List<?> loaded) {
        List<String> records = fixtureRecords(entityKey);

        assertEquals(records.size(), loaded.size(),
                "the loader of " + entityKey + " returns one element per fixture record");
        for (int index = 0; index < records.size(); index++) {
            assertEquals(parse.apply(records.get(index)), loaded.get(index),
                    "the loader of " + entityKey + " routed record "
                            + (index + FIRST_ORDINAL) + " through another parse operation");
        }
    }

    @Nested
    @DisplayName("Parse operations the census reaches through the loaders")
    class ParserDispatch {

        /**
         * Every loader dispatches to the parse operation of its own layout.
         *
         * <p>The census reads its typed rows through the loaders, so its evidence is only as good as
         * that dispatch. Naming each operation here makes the routing an assertion rather than an
         * assumption, and a loader wired to a neighbouring layout of the same record width would
         * otherwise return records whose components all sat one field out.</p>
         */
        @Test
        @DisplayName("each of the nine loaders routes every record through its own parse operation")
        void eachLoaderRoutesEveryRecordThroughItsOwnParseOperation() {
            assertLoaderDispatchesTo("acctdata",
                    CopybookRecordParser::parseAccount, ACCOUNTS.get());
            assertLoaderDispatchesTo("carddata",
                    CopybookRecordParser::parseCard, CARDS.get());
            assertLoaderDispatchesTo("cardxref",
                    CopybookRecordParser::parseCardCrossReference, CROSS_REFERENCES.get());
            assertLoaderDispatchesTo("custdata",
                    CopybookRecordParser::parseCustomer, CUSTOMERS.get());
            assertLoaderDispatchesTo("dailytran",
                    CopybookRecordParser::parseDailyTransaction, DAILY_TRANSACTIONS.get());
            assertLoaderDispatchesTo("discgrp",
                    CopybookRecordParser::parseDisclosureGroup, DISCLOSURE_GROUPS.get());
            assertLoaderDispatchesTo("tcatbal",
                    CopybookRecordParser::parseTransactionCategoryBalance, CATEGORY_BALANCES.get());
            assertLoaderDispatchesTo("trancatg",
                    CopybookRecordParser::parseTransactionCategory, TRANSACTION_CATEGORIES.get());
            assertLoaderDispatchesTo("trantype",
                    CopybookRecordParser::parseTransactionType, TRANSACTION_TYPES.get());
        }

        /**
         * The cross-reference fixture reads the same three components at both widths.
         *
         * <p>{@code app/jcl/XREFFILE.jcl:L44} declares fifty bytes and the text fixture delivers
         * thirty-six, which is register item 25. The loader expands each record to the declared width
         * by adding the filler the copybook declares, and the expanded record has to parse to the
         * same three components as the delivered one.</p>
         */
        @Test
        @DisplayName("the cross-reference layout reads the same components at both of its widths")
        void theCrossReferenceLayoutReadsTheSameComponentsAtBothWidths() {
            List<String> delivered = fixtureRecords("cardxref");
            List<String> expanded = CardDemoFixtureLoader.cardCrossReferenceRecordsAtDeclaredWidth();
            Layout layout = LAYOUTS.get("cardxref");

            assertEquals(delivered.size(), expanded.size(),
                    "the expansion returns one record per delivered record");
            for (int index = 0; index < delivered.size(); index++) {
                assertEquals(PicClause.CARDXREF_FIXTURE_RECORD_WIDTH, delivered.get(index).length(),
                        "app/data/ASCII/cardxref.txt record " + (index + FIRST_ORDINAL)
                                + " holds the width the fixture delivers");
                assertEquals(layout.declaredWidth(), expanded.get(index).length(),
                        "the expanded record " + (index + FIRST_ORDINAL)
                                + " holds the width app/jcl/XREFFILE.jcl:L44 declares");
                assertEquals(CopybookRecordParser.parseCardCrossReference(delivered.get(index)),
                        CopybookRecordParser.parseCardCrossReference(expanded.get(index)),
                        "the two widths of record " + (index + FIRST_ORDINAL)
                                + " read different components");
            }
        }
    }

    @Nested
    @DisplayName("Self-verification of the census comparison")
    class CensusSelfVerification {

        /** A changed recorded value is reported, so the comparison is not vacuous. */
        @Test
        @DisplayName("a changed recorded value is reported as a mismatch")
        void aChangedRecordedValueIsReported() {
            CensusRow row = rowFor("dailytran", "record_count");
            CensusRow mutated = withExpectedValue(row, row.expectedValue() + "0");

            assertEquals(List.of(), mismatchesOf(List.of(row)),
                    "the unchanged row agrees with app/data/ASCII/dailytran.txt");
            assertEquals(1, mismatchesOf(List.of(mutated)).size(),
                    "a census row recording the wrong record count is reported");
        }

        /** A row naming a measurement no derivation covers is reported as uncovered. */
        @Test
        @DisplayName("a row naming an unknown measurement is reported as uncovered")
        void aRowNamingAnUnknownMeasurementIsReported() {
            CensusRow row = rowFor("acctdata", "record_count");
            CensusRow unknown = new CensusRow(row.evaluationModel(), row.derivedFrom(),
                    row.recordSeq(), row.entityKey(), "no_such_measurement", row.expectedValue(),
                    row.picClause(), row.sourceLocator());

            assertEquals(List.of(unknown.label()), uncoveredOf(List.of(unknown)),
                    "a measurement no derivation reads is reported rather than passing unread");
            assertEquals(List.of(), mismatchesOf(List.of(unknown)),
                    "an uncovered row is reported once, by the coverage assertion only");
        }

        /** A row naming an entity with no modelled layout is reported rather than raising. */
        @Test
        @DisplayName("a row naming an entity with no layout is reported as uncovered")
        void aRowNamingAnUnmodelledEntityIsReported() {
            CensusRow row = rowFor("carddata", "byte_count");
            CensusRow unknown = new CensusRow(row.evaluationModel(), row.derivedFrom(),
                    row.recordSeq(), "no_such_fixture", row.expectedField(), row.expectedValue(),
                    row.picClause(), row.sourceLocator());

            assertEquals(List.of(unknown.label()), uncoveredOf(List.of(unknown)),
                    "an entity with no layout is reported as uncovered, not read as another one");
        }

        /**
         * A derivation reads the fixture and never the census, so changing what the census records
         * cannot change what the derivation returns. Without this, a derivation that echoed the
         * recorded value would satisfy every assertion above.
         */
        @Test
        @DisplayName("a derivation ignores the value the census records")
        void aDerivationIgnoresTheValueTheCensusRecords() {
            CensusRow row = rowFor("acctdata", "byte_count");
            String derived = derive(row);
            CensusRow mutated = withExpectedValue(row, row.expectedValue() + "7");

            assertEquals(derived, derive(mutated),
                    "the derivation reads app/data/ASCII/acctdata.txt, so a changed recorded value "
                            + "leaves it unchanged");
            assertNotEquals(mutated.expectedValue(), derive(mutated),
                    "the derivation does not restate the value the census records");
        }

        /**
         * The measurement helpers report a divergence without quoting the values that caused it, so
         * a fixture holding several card numbers where the census expects one discloses none.
         */
        @Test
        @DisplayName("a single-valued measurement reports a divergence without quoting values")
        void aSingleValuedMeasurementQuotesNoValue() {
            String secret = "4111111111111111";
            String other = "5500000000000004";

            String reported = soleValue(List.of(secret, other));

            assertTrue(reported.startsWith(MIXED_MEASUREMENT),
                    "a measurement of several values reports " + MIXED_MEASUREMENT);
            assertFalse(reported.contains(secret),
                    "the report withholds the values that caused the divergence");
            assertFalse(reported.contains(other),
                    "the report withholds every one of them, not only the first");
            assertEquals(secret, soleValue(List.of(secret, secret)),
                    "a measurement of one value returns it, which is what a census row records");
        }
    }
}

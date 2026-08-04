package com.carddemo.notification.domain;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.domain.NotificationRenderer.CardholderContext;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationRenderer.TransactionRow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the seventeen fixed-width records of {@link PlainTextRenderer} against the text statement
 * of app/cbl/CBSTM03A.CBL.
 *
 * <p>{@code 01 STATEMENT-LINES.} at app/cbl/CBSTM03A.CBL:L85 declares seventeen record groups over
 * L86-L146: {@code ST-LINE0} through {@code ST-LINE15} number sixteen, and {@code ST-LINE14A} is
 * the seventeenth. Each group totals 80 characters, matching
 * {@code 01 FD-STMTFILE-REC PIC X(80).} at app/cbl/CBSTM03A.CBL:L45. The Job Control Language job
 * that runs the program allocates the same width, {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at
 * app/jcl/CREASTMT.JCL:L89.</p>
 *
 * <p>Two Common Business Oriented Language (COBOL) terms name the pieces below. A Picture clause,
 * written {@code PIC}, fixes the width and the form of a field. A {@code FILLER} item is an
 * unnamed field holding spaces or one character repeated.</p>
 *
 * <p>Every expected record below is typed by hand from the Picture clauses of its group. No
 * expected value comes from calling the renderer, and no value comes from a fixture file. Each
 * test asserts the width of its record on its own, so a record that gained or lost a character
 * reports both its width and its content.</p>
 *
 * <p>Four write blocks fix the record order. The banner comes from app/cbl/CBSTM03A.CBL:L460, the
 * fifteen header records from app/cbl/CBSTM03A.CBL:L488-L502, one detail record per transaction
 * from app/cbl/CBSTM03A.CBL:L679, and the three trailer records from
 * app/cbl/CBSTM03A.CBL:L435-L437.</p>
 *
 * <p>No Spring application context, no broker and no database take part. The markup records of
 * {@code 01 FD-HTMLFILE-REC PIC X(100).} at app/cbl/CBSTM03A.CBL:L47 belong to their own renderer,
 * and no assertion here reads markup.</p>
 */
@DisplayName("PlainTextRenderer, the seventeen 80-column statement records of CBSTM03A")
class PlainTextRendererTest {

    /**
     * Characters in every record, from {@code 01 FD-STMTFILE-REC PIC X(80).} at
     * app/cbl/CBSTM03A.CBL:L45 and {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at
     * app/jcl/CREASTMT.JCL:L89.
     */
    private static final int RECORD_WIDTH = 80;

    /**
     * Header records {@code 5000-CREATE-STATEMENT} writes at
     * app/cbl/CBSTM03A.CBL:L488-L502.
     */
    private static final int HEADER_RECORD_COUNT = 15;

    /**
     * Trailer records the transaction loop writes at app/cbl/CBSTM03A.CBL:L435-L437.
     */
    private static final int TRAILER_RECORD_COUNT = 3;

    /**
     * The banner record {@code 5000-CREATE-STATEMENT} writes on its own at
     * app/cbl/CBSTM03A.CBL:L460.
     */
    private static final int BANNER_RECORD_COUNT = 1;

    /**
     * Records every statement carries whatever its transaction count: the banner of
     * app/cbl/CBSTM03A.CBL:L460, the fifteen header records of app/cbl/CBSTM03A.CBL:L488-L502 and
     * the three trailer records of app/cbl/CBSTM03A.CBL:L435-L437.
     */
    private static final int SURROUNDING_RECORD_COUNT =
            BANNER_RECORD_COUNT + HEADER_RECORD_COUNT + TRAILER_RECORD_COUNT;

    /**
     * Rule records a complete statement carries. Five sit in the header, at
     * app/cbl/CBSTM03A.CBL:L492, L494, L498, L500 and L502, and the trailer adds one at
     * app/cbl/CBSTM03A.CBL:L435.
     */
    private static final int RULE_RECORD_COUNT = 6;

    /**
     * Separator between rendered records. Each {@code WRITE FD-STMTFILE-REC} at
     * app/cbl/CBSTM03A.CBL:L488-L502 emits one record of app/cbl/CBSTM03A.CBL:L45.
     */
    private static final String RECORD_SEPARATOR = "\n";

    /** Width of {@code ST-TRANDT PIC X(49)} at app/cbl/CBSTM03A.CBL:L135. */
    private static final int DESCRIPTION_RENDER_WIDTH = 49;

    /** Width of {@code TRNX-DESC PIC X(100)} at app/cpy/COSTM01.CPY:L28. */
    private static final int DESCRIPTION_STORED_WIDTH = 100;

    /**
     * Characters an edited amount holds: nine digit positions, a decimal point, two decimal digits
     * and one trailing sign position. {@code ST-TRANAMT PIC Z(9).99-} sits at
     * app/cbl/CBSTM03A.CBL:L137.
     */
    private static final int EDITED_AMOUNT_WIDTH = 13;

    /**
     * Column of the currency sign on a detail record and on the total record,
     * {@code FILLER VALUE '$' PIC X(01)} at app/cbl/CBSTM03A.CBL:L136 and
     * app/cbl/CBSTM03A.CBL:L141.
     */
    private static final int CURRENCY_SIGN_COLUMN = 67;

    /**
     * First column of an edited amount, from {@code ST-TRANAMT} at app/cbl/CBSTM03A.CBL:L137 and
     * {@code ST-TOTAL-TRAMT} at app/cbl/CBSTM03A.CBL:L142.
     */
    private static final int AMOUNT_FIRST_COLUMN = 68;

    /**
     * First column of the description column heading,
     * {@code FILLER VALUE 'Tran Details    ' PIC X(51)} at app/cbl/CBSTM03A.CBL:L130.
     */
    private static final int DESCRIPTION_HEADING_COLUMN = 17;

    /**
     * First column of the description field. The separator
     * {@code FILLER VALUE ' ' PIC X(01)} at app/cbl/CBSTM03A.CBL:L134 precedes
     * {@code ST-TRANDT} at app/cbl/CBSTM03A.CBL:L135.
     */
    private static final int DESCRIPTION_FIELD_COLUMN = 18;

    /** Index of the banner record, written at app/cbl/CBSTM03A.CBL:L460. */
    private static final int BANNER_INDEX = 0;

    /** Index of the {@code ST-LINE1} record, written at app/cbl/CBSTM03A.CBL:L488. */
    private static final int NAME_INDEX = 1;

    /** Index of the {@code ST-LINE2} record, written at app/cbl/CBSTM03A.CBL:L489. */
    private static final int ADDRESS_LINE_1_INDEX = 2;

    /** Index of the {@code ST-LINE3} record, written at app/cbl/CBSTM03A.CBL:L490. */
    private static final int ADDRESS_LINE_2_INDEX = 3;

    /** Index of the {@code ST-LINE4} record, written at app/cbl/CBSTM03A.CBL:L491. */
    private static final int ADDRESS_LINE_3_INDEX = 4;

    /**
     * Index of the first {@code ST-LINE5} record, written at app/cbl/CBSTM03A.CBL:L492.
     */
    private static final int RULE_BEFORE_BASIC_DETAILS_INDEX = 5;

    /** Index of the {@code ST-LINE6} record, written at app/cbl/CBSTM03A.CBL:L493. */
    private static final int BASIC_DETAILS_INDEX = 6;

    /**
     * Index of the second {@code ST-LINE5} record, written at app/cbl/CBSTM03A.CBL:L494.
     */
    private static final int RULE_AFTER_BASIC_DETAILS_INDEX = 7;

    /** Index of the {@code ST-LINE7} record, written at app/cbl/CBSTM03A.CBL:L495. */
    private static final int ACCOUNT_ID_INDEX = 8;

    /** Index of the {@code ST-LINE8} record, written at app/cbl/CBSTM03A.CBL:L496. */
    private static final int CURRENT_BALANCE_INDEX = 9;

    /** Index of the {@code ST-LINE9} record, written at app/cbl/CBSTM03A.CBL:L497. */
    private static final int FICO_SCORE_INDEX = 10;

    /** Index of the {@code ST-LINE10} record, written at app/cbl/CBSTM03A.CBL:L498. */
    private static final int RULE_AFTER_FICO_SCORE_INDEX = 11;

    /** Index of the {@code ST-LINE11} record, written at app/cbl/CBSTM03A.CBL:L499. */
    private static final int SUMMARY_HEADING_INDEX = 12;

    /**
     * Index of the first {@code ST-LINE12} record, written at app/cbl/CBSTM03A.CBL:L500.
     */
    private static final int RULE_BEFORE_COLUMN_HEADING_INDEX = 13;

    /** Index of the {@code ST-LINE13} record, written at app/cbl/CBSTM03A.CBL:L501. */
    private static final int COLUMN_HEADING_INDEX = 14;

    /**
     * Index of the second {@code ST-LINE12} record, written at app/cbl/CBSTM03A.CBL:L502.
     */
    private static final int RULE_AFTER_COLUMN_HEADING_INDEX = 15;

    /**
     * Index of the first detail record, written by {@code 6000-WRITE-TRANS} at
     * app/cbl/CBSTM03A.CBL:L679.
     */
    private static final int FIRST_DETAIL_INDEX = 16;

    /**
     * The rule record of {@code ST-LINE5}, {@code ST-LINE10} and {@code ST-LINE12}. All three
     * groups declare one {@code FILLER VALUE ALL '-' PIC X(80)}, at app/cbl/CBSTM03A.CBL:L102,
     * app/cbl/CBSTM03A.CBL:L121 and app/cbl/CBSTM03A.CBL:L127.
     */
    private static final String RULE_RECORD = "-".repeat(RECORD_WIDTH);

    /**
     * The banner record of {@code ST-LINE0} at app/cbl/CBSTM03A.CBL:L86-L89: asterisks in
     * {@code X(31)} at L87, the 18-character text in {@code X(18)} at L88, and asterisks in
     * {@code X(31)} at L89. The {@code VALUE ALL} phrase places an 18-character literal in an
     * 18-character field once.
     */
    private static final String OPENING_BANNER_RECORD =
            "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

    /**
     * The banner record of {@code ST-LINE15} at app/cbl/CBSTM03A.CBL:L143-L146: asterisks in
     * {@code X(32)} at L144, the 16-character text in {@code X(16)} at L145, and asterisks in
     * {@code X(32)} at L146. The two banners divide their 80 characters differently.
     */
    private static final String CLOSING_BANNER_RECORD =
            "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    /**
     * The assembled cardholder name, in the shape the {@code STRING} at
     * app/cbl/CBSTM03A.CBL:L462-L469 produces: three components, each followed by one space.
     * {@code ST-NAME} is {@code PIC X(75)} at app/cbl/CBSTM03A.CBL:L91.
     */
    private static final String ASSEMBLED_NAME = "JOHN Q PUBLIC ";

    /**
     * {@code ST-ADD1} at app/cbl/CBSTM03A.CBL:L94, filled by the
     * {@code MOVE CUST-ADDR-LINE-1 TO ST-ADD1} at app/cbl/CBSTM03A.CBL:L470.
     */
    private static final String ADDRESS_LINE_1 = "500 MARKET STREET";

    /**
     * {@code ST-ADD2} at app/cbl/CBSTM03A.CBL:L97, filled by the
     * {@code MOVE CUST-ADDR-LINE-2 TO ST-ADD2} at app/cbl/CBSTM03A.CBL:L471.
     */
    private static final String ADDRESS_LINE_2 = "SUITE 200";

    /**
     * {@code ST-ADD3} at app/cbl/CBSTM03A.CBL:L100, in the shape the {@code STRING} at
     * app/cbl/CBSTM03A.CBL:L472-L481 produces: four components, each followed by one space.
     */
    private static final String ADDRESS_LINE_3 = "SPRINGFIELD IL USA 62704 ";

    /**
     * {@code ST-ACCT-ID} at app/cbl/CBSTM03A.CBL:L107-L110, filled by the
     * {@code MOVE ACCT-ID TO ST-ACCT-ID} at app/cbl/CBSTM03A.CBL:L483. An account identifier
     * carries eleven digits, and its leading zeros survive the move into a 20-character field.
     */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * {@code ST-CURR-BAL} at app/cbl/CBSTM03A.CBL:L113, filled by the
     * {@code MOVE ACCT-CURR-BAL TO ST-CURR-BAL} at app/cbl/CBSTM03A.CBL:L484. The
     * {@code PIC 9(9).99-} form renders every digit position as a digit, and the trailing sign
     * position holds a space for a positive balance.
     */
    private static final String EDITED_BALANCE = "000002500.00 ";

    /**
     * {@code ST-FICO-SCORE} at app/cbl/CBSTM03A.CBL:L118, filled by the
     * {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE} at app/cbl/CBSTM03A.CBL:L485.
     */
    private static final String FICO_SCORE = "742";

    /**
     * {@code ST-TRANID} of the first detail record, filled by the
     * {@code MOVE TRNX-ID TO ST-TRANID} at app/cbl/CBSTM03A.CBL:L676. {@code TRNX-ID} is
     * {@code PIC X(16)} at app/cpy/COSTM01.CPY:L23, so an identifier carries letters as well as
     * digits.
     */
    private static final String FIRST_TRANSACTION_ID = "TRNXAA0000000001";

    /**
     * {@code ST-TRANID} of the second detail record, in the alphanumeric form of
     * {@code TRNX-ID PIC X(16)} at app/cpy/COSTM01.CPY:L23.
     */
    private static final String SECOND_TRANSACTION_ID = "TRNXAA0000000002";

    /**
     * {@code ST-TRANDT} of the first detail record, filled by the
     * {@code MOVE TRNX-DESC TO ST-TRANDT} at app/cbl/CBSTM03A.CBL:L677.
     */
    private static final String FIRST_DESCRIPTION = "GROCERY STORE PURCHASE";

    /**
     * {@code ST-TRANDT} of the second detail record, from the same {@code MOVE} at
     * app/cbl/CBSTM03A.CBL:L677.
     */
    private static final String SECOND_DESCRIPTION = "FUEL STATION";

    /**
     * {@code ST-TRANAMT} of the first detail record, filled by the
     * {@code MOVE TRNX-AMT TO ST-TRANAMT} at app/cbl/CBSTM03A.CBL:L678. The
     * {@code PIC Z(9).99-} form at app/cbl/CBSTM03A.CBL:L137 renders each leading zero as a
     * space.
     */
    private static final String FIRST_EDITED_AMOUNT = "      125.50 ";

    /**
     * {@code ST-TRANAMT} of the second detail record, in the {@code PIC Z(9).99-} form of
     * app/cbl/CBSTM03A.CBL:L137.
     */
    private static final String SECOND_EDITED_AMOUNT = "      250.25 ";

    /**
     * The transaction total the trailer carries. {@code WS-TOTAL-AMT PIC S9(9)V99} at
     * app/cbl/CBSTM03A.CBL:L65 accumulates it, and app/cbl/CBSTM03A.CBL:L433-L434 move it into
     * {@code ST-TOTAL-TRAMT}.
     */
    private static final BigDecimal TOTAL = new BigDecimal("375.75");

    /**
     * {@code ST-TOTAL-TRAMT} at app/cbl/CBSTM03A.CBL:L142, in the same {@code PIC Z(9).99-} form
     * as a detail amount.
     */
    private static final String EDITED_TOTAL = "      375.75 ";

    /**
     * A description filling {@code ST-TRANDT PIC X(49)} at app/cbl/CBSTM03A.CBL:L135 exactly.
     */
    private static final String DESCRIPTION_AT_RENDER_WIDTH =
            "GROCERY STORE PURCHASE, DOWNTOWN BRANCH, AISLE 12";

    /**
     * The 51 characters {@code TRNX-DESC PIC X(100)} at app/cpy/COSTM01.CPY:L28 carries past the
     * 49 of {@code ST-TRANDT} at app/cbl/CBSTM03A.CBL:L135.
     */
    private static final String DESCRIPTION_TAIL =
            ", CLERK 07, REGISTER 4, LOYALTY TIER GOLD, REBATE X";

    /**
     * A stored description filling {@code TRNX-DESC PIC X(100)} at app/cpy/COSTM01.CPY:L28
     * exactly, in two parts: the 49 characters {@code ST-TRANDT} holds and the 51 the
     * {@code MOVE} at app/cbl/CBSTM03A.CBL:L677 drops.
     */
    private static final String STORED_DESCRIPTION =
            DESCRIPTION_AT_RENDER_WIDTH + DESCRIPTION_TAIL;

    /**
     * A description shorter than {@code ST-TRANDT PIC X(49)} at app/cbl/CBSTM03A.CBL:L135.
     */
    private static final String SHORT_DESCRIPTION = "COFFEE";

    /**
     * The full sixteen-digit Primary Account Number (PAN) of the card the rendered statement
     * covers. {@code TRNX-CARD-NUM PIC X(16)} at app/cpy/COSTM01.CPY:L22 keys the stored read
     * model, and {@code CARD-NUM PIC X(16)} at app/cpy/CVACT02Y.cpy:L5 declares the same width.
     * The card detail screen shows all sixteen characters unprotected, {@code LENGTH=16} at
     * app/bms/COCRDSL.bms:L99.
     */
    private static final String CARD_NUMBER = "9999888877776666";

    /**
     * The card verification value of the same card, {@code CARD-CVV-CD PIC 9(03)} at
     * app/cpy/CVACT02Y.cpy:L7.
     */
    private static final String CARD_VERIFICATION_VALUE = "351";

    /** The transaction the fraud alert names, from {@code TRNX-ID} at app/cpy/COSTM01.CPY:L23. */
    private static final String FLAGGED_TRANSACTION_ID = "TRNXAA0000000003";

    /** Name of the one card component {@code TransactionPosted} declares. */
    private static final String MASKED_CARD_COMPONENT = "maskedCardNumber";

    /**
     * A value carrying all five characters that mean something in markup. The text format escapes
     * none of them, so this value reaches a record as supplied.
     */
    private static final String HOSTILE_VALUE = "<b>&'\"x</b>";

    /**
     * The renderer under test, built with the no-argument constructor it declares. It holds no
     * state and writes the fixed-width file of {@code 01 FD-STMTFILE-REC PIC X(80).} at
     * app/cbl/CBSTM03A.CBL:L45.
     */
    private final PlainTextRenderer renderer = new PlainTextRenderer();

    /**
     * Reports the format of the fixed-width statement file,
     * {@code 01 FD-STMTFILE-REC PIC X(80).} at app/cbl/CBSTM03A.CBL:L45.
     */
    @Test
    @DisplayName("The renderer reports the fixed-width text format")
    void theRendererReportsTheFixedWidthTextFormat() {
        assertThat(renderer.format()).isSameAs(RenderedFormat.PLAIN_TEXT);
    }

    /**
     * Checks the three record counts the renderer declares. Their source blocks are the record
     * width at app/cbl/CBSTM03A.CBL:L45, the fifteen header writes at
     * app/cbl/CBSTM03A.CBL:L488-L502 and the three trailer writes at
     * app/cbl/CBSTM03A.CBL:L435-L437.
     */
    @Test
    @DisplayName("The declared record counts match the source write blocks")
    void theDeclaredRecordCountsMatchTheSourceWriteBlocks() {
        assertThat(PlainTextRenderer.RECORD_WIDTH)
                .as("FD-STMTFILE-REC PIC X(80) at app/cbl/CBSTM03A.CBL:L45")
                .isEqualTo(RECORD_WIDTH);
        assertThat(PlainTextRenderer.HEADER_RECORD_COUNT)
                .as("the header writes at app/cbl/CBSTM03A.CBL:L488-L502")
                .isEqualTo(HEADER_RECORD_COUNT);
        assertThat(PlainTextRenderer.TRAILER_RECORD_COUNT)
                .as("the trailer writes at app/cbl/CBSTM03A.CBL:L435-L437")
                .isEqualTo(TRAILER_RECORD_COUNT);
    }

    /**
     * Checks {@code ST-LINE0} at app/cbl/CBSTM03A.CBL:L86-L89: 31 asterisks, the 18-character
     * text, and 31 asterisks.
     */
    @Test
    @DisplayName("ST-LINE0 opens with 31 asterisks, the opening text and 31 asterisks")
    void openingBannerCarriesThirtyOneAsterisksOnEachSideOfItsText() {
        String record = statementRecords().get(BANNER_INDEX);

        assertThat(record).isEqualTo(OPENING_BANNER_RECORD);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE1} at app/cbl/CBSTM03A.CBL:L90-L92: {@code ST-NAME PIC X(75)} at L91
     * and {@code FILLER VALUE SPACES PIC X(05)} at L92.
     */
    @Test
    @DisplayName("ST-LINE1 carries the assembled name in 75 columns and 5 filler columns")
    void nameRecordCarriesTheAssembledNameAndFiveFillerColumns() {
        String record = statementRecords().get(NAME_INDEX);

        assertThat(record).isEqualTo(ASSEMBLED_NAME + spaces(61) + spaces(5));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE2} at app/cbl/CBSTM03A.CBL:L93-L95: {@code ST-ADD1 PIC X(50)} at L94
     * and {@code FILLER VALUE SPACES PIC X(30)} at L95.
     */
    @Test
    @DisplayName("ST-LINE2 carries the first address line in 50 columns and 30 filler columns")
    void firstAddressRecordCarriesFiftyColumnsAndThirtyFillerColumns() {
        String record = statementRecords().get(ADDRESS_LINE_1_INDEX);

        assertThat(record).isEqualTo(ADDRESS_LINE_1 + spaces(33) + spaces(30));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE3} at app/cbl/CBSTM03A.CBL:L96-L98: {@code ST-ADD2 PIC X(50)} at L97
     * and {@code FILLER VALUE SPACES PIC X(30)} at L98.
     */
    @Test
    @DisplayName("ST-LINE3 carries the second address line in 50 columns and 30 filler columns")
    void secondAddressRecordCarriesFiftyColumnsAndThirtyFillerColumns() {
        String record = statementRecords().get(ADDRESS_LINE_2_INDEX);

        assertThat(record).isEqualTo(ADDRESS_LINE_2 + spaces(41) + spaces(30));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE4} at app/cbl/CBSTM03A.CBL:L99-L100. The group declares
     * {@code ST-ADD3 PIC X(80)} alone at L100 and adds no filler.
     */
    @Test
    @DisplayName("ST-LINE4 spans all 80 columns with one field and no filler")
    void thirdAddressRecordSpansAllEightyColumnsWithOneField() {
        String record = statementRecords().get(ADDRESS_LINE_3_INDEX);

        assertThat(record).isEqualTo(ADDRESS_LINE_3 + spaces(55));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks the first rule record, {@code ST-LINE5} at app/cbl/CBSTM03A.CBL:L101-L102. Its one
     * {@code FILLER VALUE ALL '-' PIC X(80)} sits at L102.
     */
    @Test
    @DisplayName("ST-LINE5 declares 80 hyphens and carries them in every column")
    void theFirstRuleDeclarationCarriesEightyHyphens() {
        String record = statementRecords().get(RULE_BEFORE_BASIC_DETAILS_INDEX);

        assertThat(record).isEqualTo(RULE_RECORD);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE6} at app/cbl/CBSTM03A.CBL:L103-L106: filler {@code X(33)} at L104,
     * the heading in {@code X(14)} at L105, and filler {@code X(33)} at L106. The heading literal
     * is 13 characters, so one space closes its field.
     */
    @Test
    @DisplayName("ST-LINE6 centres a 13-character heading in a 14-column field")
    void basicDetailsHeadingCarriesOneTrailingSpaceInsideItsField() {
        String record = statementRecords().get(BASIC_DETAILS_INDEX);

        assertThat(record).isEqualTo(spaces(33) + "Basic Details" + spaces(1) + spaces(33));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE7} at app/cbl/CBSTM03A.CBL:L107-L110: the 20-character label at L108,
     * the account identifier in {@code X(20)}, and filler {@code X(40)} at L110. The label ends
     * with a colon and carries no trailing space.
     */
    @Test
    @DisplayName("ST-LINE7 pairs a 20-column label with a 20-column account identifier")
    void accountIdentifierRecordPairsItsLabelWithATwentyColumnValue() {
        String record = statementRecords().get(ACCOUNT_ID_INDEX);

        assertThat(record).isEqualTo(
                "Account ID         :" + ACCOUNT_ID + spaces(9) + spaces(40));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE8} at app/cbl/CBSTM03A.CBL:L111-L115: the 20-character label at L112,
     * {@code ST-CURR-BAL PIC 9(9).99-} at L113, and two consecutive fillers, {@code X(07)} at
     * L114 and {@code X(40)} at L115. The two fillers place 47 trailing spaces, so the rendered
     * record is the same under either split.
     */
    @Test
    @DisplayName("ST-LINE8 places the edited balance ahead of 47 trailing spaces")
    void currentBalanceRecordPlacesTheEditedBalanceAheadOfFortySevenSpaces() {
        String record = statementRecords().get(CURRENT_BALANCE_INDEX);

        assertThat(record).isEqualTo(
                "Current Balance    :" + EDITED_BALANCE + spaces(7) + spaces(40));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE9} at app/cbl/CBSTM03A.CBL:L116-L119: the 20-character label at L117,
     * {@code ST-FICO-SCORE PIC X(20)} at L118, and filler {@code X(40)} at L119.
     */
    @Test
    @DisplayName("ST-LINE9 pairs a 20-column label with a 20-column credit score")
    void creditScoreRecordPairsItsLabelWithATwentyColumnValue() {
        String record = statementRecords().get(FICO_SCORE_INDEX);

        assertThat(record).isEqualTo(
                "FICO Score         :" + FICO_SCORE + spaces(17) + spaces(40));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks the second rule record, {@code ST-LINE10} at app/cbl/CBSTM03A.CBL:L120-L121. Its own
     * {@code FILLER VALUE ALL '-' PIC X(80)} sits at L121.
     */
    @Test
    @DisplayName("ST-LINE10 declares 80 hyphens of its own")
    void theSecondRuleDeclarationCarriesEightyHyphens() {
        String record = statementRecords().get(RULE_AFTER_FICO_SCORE_INDEX);

        assertThat(record).isEqualTo(RULE_RECORD);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE11} at app/cbl/CBSTM03A.CBL:L122-L125: filler {@code X(30)} at L123,
     * the heading in {@code X(20)} at L124, and filler {@code X(30)} at L125. The heading literal
     * carries its own trailing space and fills its field exactly.
     */
    @Test
    @DisplayName("ST-LINE11 centres a heading whose literal carries its own trailing space")
    void summaryHeadingFillsItsFieldWithTheLiteralTrailingSpace() {
        String record = statementRecords().get(SUMMARY_HEADING_INDEX);

        assertThat(record).isEqualTo(spaces(30) + "TRANSACTION SUMMARY " + spaces(30));
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks the third rule record, {@code ST-LINE12} at app/cbl/CBSTM03A.CBL:L126-L127. Its own
     * {@code FILLER VALUE ALL '-' PIC X(80)} sits at L127, so the program declares 80 hyphens
     * three times over.
     */
    @Test
    @DisplayName("ST-LINE12 declares 80 hyphens, the third such declaration in the program")
    void theThirdRuleDeclarationCarriesEightyHyphens() {
        String record = statementRecords().get(RULE_BEFORE_COLUMN_HEADING_INDEX);

        assertThat(record).isEqualTo(RULE_RECORD);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE13} at app/cbl/CBSTM03A.CBL:L128-L131: a 16-character literal in
     * {@code X(16)} at L129, a 16-character literal in {@code X(51)} at L130, and a
     * 13-character literal in {@code X(13)} at L131. The second literal leaves 35 spaces after its
     * own four, and the third carries two leading spaces.
     */
    @Test
    @DisplayName("ST-LINE13 carries three column headings of 16, 51 and 13 columns")
    void columnHeadingRecordCarriesItsThreeHeadingsAtTheirDeclaredWidths() {
        String record = statementRecords().get(COLUMN_HEADING_INDEX);

        assertThat(record).isEqualTo(
                "Tran ID         " + "Tran Details    " + spaces(35) + "  Tran Amount");
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE14} at app/cbl/CBSTM03A.CBL:L132-L137: {@code ST-TRANID PIC X(16)} at
     * L133, the separator {@code X(01)} at L134, {@code ST-TRANDT PIC X(49)} at L135, the currency
     * sign {@code X(01)} at L136, and {@code ST-TRANAMT PIC Z(9).99-} at L137.
     */
    @Test
    @DisplayName("ST-LINE14 carries the identifier, the description and the edited amount")
    void detailRecordCarriesItsFiveFieldsAtTheirDeclaredWidths() {
        String record = statementRecords().get(FIRST_DETAIL_INDEX);

        assertThat(record).isEqualTo(FIRST_TRANSACTION_ID + spaces(1) + FIRST_DESCRIPTION
                + spaces(27) + "$" + FIRST_EDITED_AMOUNT);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE14A} at app/cbl/CBSTM03A.CBL:L138-L142: the 10-character label at L139,
     * filler {@code X(56)} at L140, the currency sign {@code X(01)} at L141, and
     * {@code ST-TOTAL-TRAMT PIC Z(9).99-} at L142.
     */
    @Test
    @DisplayName("ST-LINE14A carries the total label, 56 filler columns and the edited total")
    void totalRecordCarriesItsLabelFillerAndEditedTotal() {
        List<String> records = statementRecords();
        String record = records.get(records.size() - 2);

        assertThat(record).isEqualTo("Total EXP:" + spaces(56) + "$" + EDITED_TOTAL);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks {@code ST-LINE15} at app/cbl/CBSTM03A.CBL:L143-L146: 32 asterisks, the 16-character
     * text, and 32 asterisks.
     */
    @Test
    @DisplayName("ST-LINE15 closes with 32 asterisks, the closing text and 32 asterisks")
    void closingBannerCarriesThirtyTwoAsterisksOnEachSideOfItsText() {
        List<String> records = statementRecords();
        String record = records.get(records.size() - 1);

        assertThat(record).isEqualTo(CLOSING_BANNER_RECORD);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks the whole emitted sequence for a two-transaction statement. The order follows the
     * four write blocks at app/cbl/CBSTM03A.CBL:L460, app/cbl/CBSTM03A.CBL:L488-L502,
     * app/cbl/CBSTM03A.CBL:L679 and app/cbl/CBSTM03A.CBL:L435-L437.
     */
    @Test
    @DisplayName("A two-transaction statement emits 21 records in source order")
    void aTwoTransactionStatementEmitsEveryRecordInSourceOrder() {
        assertThat(statementRecords()).containsExactly(
                OPENING_BANNER_RECORD,
                ASSEMBLED_NAME + spaces(61) + spaces(5),
                ADDRESS_LINE_1 + spaces(33) + spaces(30),
                ADDRESS_LINE_2 + spaces(41) + spaces(30),
                ADDRESS_LINE_3 + spaces(55),
                RULE_RECORD,
                spaces(33) + "Basic Details" + spaces(1) + spaces(33),
                RULE_RECORD,
                "Account ID         :" + ACCOUNT_ID + spaces(9) + spaces(40),
                "Current Balance    :" + EDITED_BALANCE + spaces(7) + spaces(40),
                "FICO Score         :" + FICO_SCORE + spaces(17) + spaces(40),
                RULE_RECORD,
                spaces(30) + "TRANSACTION SUMMARY " + spaces(30),
                RULE_RECORD,
                "Tran ID         " + "Tran Details    " + spaces(35) + "  Tran Amount",
                RULE_RECORD,
                FIRST_TRANSACTION_ID + spaces(1) + FIRST_DESCRIPTION + spaces(27) + "$"
                        + FIRST_EDITED_AMOUNT,
                SECOND_TRANSACTION_ID + spaces(1) + SECOND_DESCRIPTION + spaces(37) + "$"
                        + SECOND_EDITED_AMOUNT,
                RULE_RECORD,
                "Total EXP:" + spaces(56) + "$" + EDITED_TOTAL,
                CLOSING_BANNER_RECORD);
    }

    /**
     * Checks that the banner stands on its own ahead of the header block. The
     * {@code INITIALIZE STATEMENT-LINES} at app/cbl/CBSTM03A.CBL:L459 and the
     * {@code WRITE FD-STMTFILE-REC FROM ST-LINE0} at app/cbl/CBSTM03A.CBL:L460 precede the two
     * markup blocks at app/cbl/CBSTM03A.CBL:L461 and app/cbl/CBSTM03A.CBL:L486, and the header
     * block opens at app/cbl/CBSTM03A.CBL:L488.
     */
    @Test
    @DisplayName("The banner is the first record and the name record follows it")
    void theBannerStandsAloneAheadOfTheHeaderBlock() {
        List<String> records = statementRecords();

        assertThat(records.get(BANNER_INDEX)).isEqualTo(OPENING_BANNER_RECORD);
        assertThat(records.get(NAME_INDEX)).isEqualTo(ASSEMBLED_NAME + spaces(61) + spaces(5));
        assertThat(records.subList(BANNER_INDEX, NAME_INDEX)).hasSize(BANNER_RECORD_COUNT);
    }

    /**
     * Checks the fifteen header writes of app/cbl/CBSTM03A.CBL:L488-L502 as one sequence.
     * {@code ST-LINE5} is written at L492 and again at L494, and {@code ST-LINE12} is written at
     * L500 and again at L502, so fifteen writes emit thirteen distinct groups.
     */
    @Test
    @DisplayName("The header emits 15 records from 13 groups, repeating two of them")
    void theHeaderEmitsFifteenRecordsAndRepeatsTwoGroups() {
        List<String> header = statementRecords()
                .subList(NAME_INDEX, NAME_INDEX + HEADER_RECORD_COUNT);

        assertThat(header).hasSize(HEADER_RECORD_COUNT);
        assertThat(header).containsExactly(
                ASSEMBLED_NAME + spaces(61) + spaces(5),
                ADDRESS_LINE_1 + spaces(33) + spaces(30),
                ADDRESS_LINE_2 + spaces(41) + spaces(30),
                ADDRESS_LINE_3 + spaces(55),
                RULE_RECORD,
                spaces(33) + "Basic Details" + spaces(1) + spaces(33),
                RULE_RECORD,
                "Account ID         :" + ACCOUNT_ID + spaces(9) + spaces(40),
                "Current Balance    :" + EDITED_BALANCE + spaces(7) + spaces(40),
                "FICO Score         :" + FICO_SCORE + spaces(17) + spaces(40),
                RULE_RECORD,
                spaces(30) + "TRANSACTION SUMMARY " + spaces(30),
                RULE_RECORD,
                "Tran ID         " + "Tran Details    " + spaces(35) + "  Tran Amount",
                RULE_RECORD);
    }

    /**
     * Checks the three trailer writes of app/cbl/CBSTM03A.CBL:L435-L437 in order: the rule at
     * L435, the total at L436 and the closing banner at L437.
     */
    @Test
    @DisplayName("The trailer emits the rule, the total and the closing banner in that order")
    void theTrailerEmitsItsThreeRecordsInSourceOrder() {
        List<String> records = statementRecords();
        List<String> trailer = records.subList(records.size() - TRAILER_RECORD_COUNT,
                records.size());

        assertThat(trailer).containsExactly(
                RULE_RECORD,
                "Total EXP:" + spaces(56) + "$" + EDITED_TOTAL,
                CLOSING_BANNER_RECORD);
    }

    /**
     * Checks the record count against the four write blocks. The banner of
     * app/cbl/CBSTM03A.CBL:L460, the fifteen header records of app/cbl/CBSTM03A.CBL:L488-L502 and
     * the three trailer records of app/cbl/CBSTM03A.CBL:L435-L437 make 19, and the detail write at
     * app/cbl/CBSTM03A.CBL:L679 adds one per transaction.
     */
    @Test
    @DisplayName("A statement of N transactions emits 19 plus N records")
    void aStatementEmitsNineteenRecordsPlusOnePerTransaction() {
        for (int transactionCount : new int[] {0, 1, 2, 7, 12}) {
            assertThat(renderStatement(rows(transactionCount)))
                    .as("a statement of %d transactions", transactionCount)
                    .hasSize(SURROUNDING_RECORD_COUNT + transactionCount);
        }
    }

    /**
     * Checks the six rule records of a complete statement. Five come from the header writes at
     * app/cbl/CBSTM03A.CBL:L492, L494, L498, L500 and L502, and the sixth from the trailer write
     * at app/cbl/CBSTM03A.CBL:L435.
     */
    @Test
    @DisplayName("A complete statement carries six rules of 80 hyphens")
    void aCompleteStatementCarriesSixRuleRecords() {
        List<String> records = statementRecords();

        assertThat(records.stream().filter(RULE_RECORD::equals).count())
                .isEqualTo(RULE_RECORD_COUNT);
        assertThat(records).containsSequence(RULE_RECORD,
                "Total EXP:" + spaces(56) + "$" + EDITED_TOTAL,
                CLOSING_BANNER_RECORD);
    }

    /**
     * Checks that a rule brackets the {@code Basic Details} heading, matching the writes at
     * app/cbl/CBSTM03A.CBL:L492, L493 and L494.
     */
    @Test
    @DisplayName("A rule sits on each side of the Basic Details heading")
    void aRuleSitsOnEachSideOfTheBasicDetailsHeading() {
        List<String> records = statementRecords();

        assertThat(records.get(RULE_BEFORE_BASIC_DETAILS_INDEX)).isEqualTo(RULE_RECORD);
        assertThat(records.get(BASIC_DETAILS_INDEX))
                .isEqualTo(spaces(33) + "Basic Details" + spaces(1) + spaces(33));
        assertThat(records.get(RULE_AFTER_BASIC_DETAILS_INDEX)).isEqualTo(RULE_RECORD);
    }

    /**
     * Checks that a rule brackets the column heading, matching the writes at
     * app/cbl/CBSTM03A.CBL:L500, L501 and L502.
     */
    @Test
    @DisplayName("A rule sits on each side of the column heading")
    void aRuleSitsOnEachSideOfTheColumnHeading() {
        List<String> records = statementRecords();

        assertThat(records.get(RULE_BEFORE_COLUMN_HEADING_INDEX)).isEqualTo(RULE_RECORD);
        assertThat(records.get(COLUMN_HEADING_INDEX))
                .isEqualTo("Tran ID         " + "Tran Details    " + spaces(35)
                        + "  Tran Amount");
        assertThat(records.get(RULE_AFTER_COLUMN_HEADING_INDEX)).isEqualTo(RULE_RECORD);
    }

    /**
     * Checks the total label of {@code ST-LINE14A}, {@code FILLER VALUE 'Total EXP:' PIC X(10)} at
     * app/cbl/CBSTM03A.CBL:L139.
     */
    @Test
    @DisplayName("The total record opens with the 10-character total label")
    void theTotalRecordOpensWithItsTenCharacterLabel() {
        List<String> records = statementRecords();
        String record = records.get(records.size() - 2);

        assertThat(record).startsWith("Total EXP:");
        assertThat(record.substring(0, 10)).isEqualTo("Total EXP:");
    }

    /**
     * Checks a statement covering no transaction. The banner of app/cbl/CBSTM03A.CBL:L460, the
     * fifteen header records of app/cbl/CBSTM03A.CBL:L488-L502 and the three trailer records of
     * app/cbl/CBSTM03A.CBL:L435-L437 still emit, and the detail write at
     * app/cbl/CBSTM03A.CBL:L679 never runs.
     */
    @Test
    @DisplayName("A statement with no transaction still emits the banner, header and trailer")
    void aStatementWithNoTransactionStillEmitsNineteenRecords() {
        List<String> records = renderStatement(List.of());

        assertThat(records).hasSize(SURROUNDING_RECORD_COUNT);
        assertThat(records.get(BANNER_INDEX)).isEqualTo(OPENING_BANNER_RECORD);
        assertThat(records.get(RULE_AFTER_COLUMN_HEADING_INDEX)).isEqualTo(RULE_RECORD);
        assertThat(records.get(FIRST_DETAIL_INDEX)).isEqualTo(RULE_RECORD);
        assertThat(records.get(records.size() - 1)).isEqualTo(CLOSING_BANNER_RECORD);
        assertThat(records).allSatisfy(record -> assertThat(record).hasSize(RECORD_WIDTH));
    }

    /**
     * Checks a statement covering twelve transactions. The detail write at
     * app/cbl/CBSTM03A.CBL:L679 runs once per transaction and reaches no ceiling.
     */
    @Test
    @DisplayName("A statement of twelve transactions emits twelve detail records")
    void aStatementOfTwelveTransactionsEmitsOneDetailRecordPerTransaction() {
        List<TransactionRow> rows = rows(12);
        List<String> records = renderStatement(rows);
        List<String> details = records.subList(FIRST_DETAIL_INDEX,
                FIRST_DETAIL_INDEX + rows.size());

        assertThat(records).hasSize(SURROUNDING_RECORD_COUNT + rows.size());
        assertThat(details).hasSize(rows.size());
        assertThat(details).doesNotHaveDuplicates();
        for (int index = 0; index < rows.size(); index++) {
            assertThat(details.get(index))
                    .as("detail record %d", index)
                    .startsWith(rows.get(index).transactionId())
                    .hasSize(RECORD_WIDTH);
        }
    }

    /**
     * Checks the currency sign column on both amount-bearing records.
     * {@code FILLER VALUE '$' PIC X(01)} follows a 16-column identifier, a 1-column separator and
     * a 49-column description at app/cbl/CBSTM03A.CBL:L136, and follows a 10-column label and a
     * 56-column filler at app/cbl/CBSTM03A.CBL:L141.
     */
    @Test
    @DisplayName("The currency sign sits in column 67 on the detail record and on the total")
    void theCurrencySignSitsInColumnSixtySevenOnBothAmountRecords() {
        List<String> records = statementRecords();
        String detail = records.get(FIRST_DETAIL_INDEX);
        String total = records.get(records.size() - 2);

        assertThat(column(detail, CURRENCY_SIGN_COLUMN)).isEqualTo('$');
        assertThat(column(total, CURRENCY_SIGN_COLUMN)).isEqualTo('$');
    }

    /**
     * Checks that the edited total shares its columns with every edited detail amount.
     * {@code ST-TRANAMT PIC Z(9).99-} at app/cbl/CBSTM03A.CBL:L137 and
     * {@code ST-TOTAL-TRAMT PIC Z(9).99-} at app/cbl/CBSTM03A.CBL:L142 both close their record.
     */
    @Test
    @DisplayName("The edited total occupies columns 68 through 80, as each detail amount does")
    void theEditedTotalOccupiesTheSameColumnsAsEachDetailAmount() {
        List<String> records = statementRecords();
        String firstDetail = records.get(FIRST_DETAIL_INDEX);
        String secondDetail = records.get(FIRST_DETAIL_INDEX + 1);
        String total = records.get(records.size() - 2);

        assertThat(columns(firstDetail, AMOUNT_FIRST_COLUMN, RECORD_WIDTH))
                .isEqualTo(FIRST_EDITED_AMOUNT)
                .hasSize(EDITED_AMOUNT_WIDTH);
        assertThat(columns(secondDetail, AMOUNT_FIRST_COLUMN, RECORD_WIDTH))
                .isEqualTo(SECOND_EDITED_AMOUNT)
                .hasSize(EDITED_AMOUNT_WIDTH);
        assertThat(columns(total, AMOUNT_FIRST_COLUMN, RECORD_WIDTH))
                .isEqualTo(EDITED_TOTAL)
                .hasSize(EDITED_AMOUNT_WIDTH);
    }

    /**
     * Checks the amount column heading of {@code ST-LINE13}. The 13-character literal at
     * app/cbl/CBSTM03A.CBL:L131 follows the 16-column heading of app/cbl/CBSTM03A.CBL:L129 and the
     * 51-column heading of app/cbl/CBSTM03A.CBL:L130, so it closes the record.
     */
    @Test
    @DisplayName("The amount column heading occupies columns 68 through 80")
    void theAmountColumnHeadingOccupiesColumnsSixtyEightThroughEighty() {
        String heading = statementRecords().get(COLUMN_HEADING_INDEX);

        assertThat(columns(heading, AMOUNT_FIRST_COLUMN, RECORD_WIDTH))
                .isEqualTo("  Tran Amount")
                .hasSize(EDITED_AMOUNT_WIDTH);
    }

    /**
     * Checks the one-column offset between the two description columns. The heading
     * {@code FILLER VALUE 'Tran Details    ' PIC X(51)} at app/cbl/CBSTM03A.CBL:L130 opens in
     * column 17, and the field {@code ST-TRANDT} at app/cbl/CBSTM03A.CBL:L135 opens in column 18
     * after the separator {@code FILLER VALUE ' ' PIC X(01)} at app/cbl/CBSTM03A.CBL:L134.
     */
    @Test
    @DisplayName("The description heading opens in column 17 and the field in column 18")
    void theDescriptionHeadingOpensOneColumnAheadOfTheDescriptionField() {
        List<String> records = statementRecords();
        String heading = records.get(COLUMN_HEADING_INDEX);
        String detail = records.get(FIRST_DETAIL_INDEX);

        assertThat(columns(heading, DESCRIPTION_HEADING_COLUMN,
                DESCRIPTION_HEADING_COLUMN + "Tran Details    ".length() - 1))
                .isEqualTo("Tran Details    ");
        assertThat(column(detail, DESCRIPTION_HEADING_COLUMN)).isEqualTo(' ');
        assertThat(columns(detail, DESCRIPTION_FIELD_COLUMN,
                DESCRIPTION_FIELD_COLUMN + DESCRIPTION_RENDER_WIDTH - 1))
                .isEqualTo(FIRST_DESCRIPTION + spaces(27))
                .hasSize(DESCRIPTION_RENDER_WIDTH);
        assertThat(DESCRIPTION_FIELD_COLUMN - DESCRIPTION_HEADING_COLUMN).isEqualTo(1);
    }

    /**
     * Checks a stored description of 100 characters through the detail record. The
     * {@code MOVE TRNX-DESC TO ST-TRANDT} at app/cbl/CBSTM03A.CBL:L677 sends
     * {@code TRNX-DESC PIC X(100)} at app/cpy/COSTM01.CPY:L28 into {@code ST-TRANDT PIC X(49)} at
     * app/cbl/CBSTM03A.CBL:L135, and the leading 49 characters arrive.
     */
    @Test
    @DisplayName("A 100-character description renders as its leading 49 characters")
    void aStoredDescriptionRendersAsItsLeadingFortyNineCharacters() {
        assertThat(STORED_DESCRIPTION).hasSize(DESCRIPTION_STORED_WIDTH);
        assertThat(DESCRIPTION_AT_RENDER_WIDTH).hasSize(DESCRIPTION_RENDER_WIDTH);
        assertThat(DESCRIPTION_TAIL)
                .hasSize(DESCRIPTION_STORED_WIDTH - DESCRIPTION_RENDER_WIDTH);

        String record = detailRecordFor(STORED_DESCRIPTION);

        assertThat(record).isEqualTo(FIRST_TRANSACTION_ID + spaces(1)
                + DESCRIPTION_AT_RENDER_WIDTH + "$" + FIRST_EDITED_AMOUNT);
        assertThat(record).doesNotContain(DESCRIPTION_TAIL);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks a description filling {@code ST-TRANDT PIC X(49)} at app/cbl/CBSTM03A.CBL:L135
     * exactly. The {@code MOVE} at app/cbl/CBSTM03A.CBL:L677 adds no padding and drops nothing.
     */
    @Test
    @DisplayName("A description of exactly 49 characters renders unchanged")
    void aDescriptionOfExactlyFortyNineCharactersRendersUnchanged() {
        String record = detailRecordFor(DESCRIPTION_AT_RENDER_WIDTH);

        assertThat(record).isEqualTo(FIRST_TRANSACTION_ID + spaces(1)
                + DESCRIPTION_AT_RENDER_WIDTH + "$" + FIRST_EDITED_AMOUNT);
        assertThat(columns(record, DESCRIPTION_FIELD_COLUMN,
                DESCRIPTION_FIELD_COLUMN + DESCRIPTION_RENDER_WIDTH - 1))
                .isEqualTo(DESCRIPTION_AT_RENDER_WIDTH);
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks a description shorter than {@code ST-TRANDT PIC X(49)} at
     * app/cbl/CBSTM03A.CBL:L135. Trailing spaces fill the field, so the currency sign of
     * app/cbl/CBSTM03A.CBL:L136 keeps column 67.
     */
    @Test
    @DisplayName("A short description is padded to 49 columns and the currency sign holds")
    void aShortDescriptionIsPaddedToFortyNineColumns() {
        String record = detailRecordFor(SHORT_DESCRIPTION);

        assertThat(record).isEqualTo(FIRST_TRANSACTION_ID + spaces(1) + SHORT_DESCRIPTION
                + spaces(43) + "$" + FIRST_EDITED_AMOUNT);
        assertThat(columns(record, DESCRIPTION_FIELD_COLUMN,
                DESCRIPTION_FIELD_COLUMN + DESCRIPTION_RENDER_WIDTH - 1))
                .isEqualTo(SHORT_DESCRIPTION + spaces(43))
                .hasSize(DESCRIPTION_RENDER_WIDTH);
        assertThat(column(record, CURRENCY_SIGN_COLUMN)).isEqualTo('$');
        assertThat(record).hasSize(RECORD_WIDTH);
    }

    /**
     * Checks the input boundary of the renderer. Neither record it reads declares a card number or
     * a card verification value, so no caller can route either into a statement record. None of the
     * seventeen groups at app/cbl/CBSTM03A.CBL:L86-L146 declares a card-number field either, and the
     * read model holds the number in {@code TRNX-CARD-NUM PIC X(16)} at app/cpy/COSTM01.CPY:L22,
     * which no record the renderer reads carries forward.
     */
    @Test
    @DisplayName("Neither renderer input record declares a card number or a verification value")
    void neitherRendererInputRecordDeclaresACardField() {
        assertThat(componentNamesOf(CardholderContext.class))
                .as("components CardholderContext declares")
                .isNotEmpty()
                .noneMatch(PlainTextRendererTest::namesACardField);
        assertThat(componentNamesOf(TransactionRow.class))
                .as("components TransactionRow declares")
                .isNotEmpty()
                .noneMatch(PlainTextRendererTest::namesACardField);
    }

    /**
     * Checks the availability of a card number to this service. {@code TransactionPosted} carries
     * one card component and it is the masked one, and {@code FraudFlagged} carries none, so the
     * only card value a listener can read is already masked.
     */
    @Test
    @DisplayName("The consumed events carry a masked card number at most")
    void theConsumedEventsCarryAMaskedCardNumberAtMost() {
        List<String> posted = componentNamesOf(TransactionPosted.class);
        List<String> flagged = componentNamesOf(FraudFlagged.class);

        assertThat(posted).as("components TransactionPosted declares")
                .contains(MASKED_CARD_COMPONENT);
        assertThat(posted).as("card components TransactionPosted declares")
                .filteredOn(PlainTextRendererTest::namesACardField)
                .containsExactly(MASKED_CARD_COMPONENT);
        assertThat(flagged).as("card components FraudFlagged declares")
                .isNotEmpty()
                .noneMatch(PlainTextRendererTest::namesACardField);
    }

    /**
     * Drives the full Primary Account Number (PAN) through the production masking step and into
     * every value field a statement renders, then checks that no record carries the full number.
     * The masked form keeps the last four characters alone, so the twelve leading characters cannot
     * reach a record through any field.
     *
     * <p>Masking is an additive deviation, recorded in card-platform/docs/decision-log.md
     * (planned). The source masks nothing: the card detail screen shows all sixteen characters at
     * {@code LENGTH=16} in app/bms/COCRDSL.bms:L99.</p>
     */
    @Test
    @DisplayName("A full card number reaches no record once the production masker has run")
    void aFullCardNumberReachesNoRecordOnceTheProductionMaskerHasRun() {
        String masked = PanMasker.maskCardNumber(CARD_NUMBER);

        assertThat(CARD_NUMBER).hasSize(PanMasker.CARD_NUMBER_LENGTH);
        assertThat(masked).hasSize(PanMasker.CARD_NUMBER_LENGTH);
        assertThat(masked.contains(CARD_NUMBER))
                .as("the masked form carries the full card number").isFalse();
        assertThat(masked.endsWith(CARD_NUMBER.substring(PanMasker.CARD_NUMBER_LENGTH
                - PanMasker.VISIBLE_DIGIT_COUNT)))
                .as("the masked form keeps the last four characters").isTrue();

        CardholderContext context = new CardholderContext(masked, masked, masked, masked,
                ACCOUNT_ID, EDITED_BALANCE, masked);
        List<TransactionRow> rows =
                List.of(new TransactionRow(FIRST_TRANSACTION_ID, masked, FIRST_EDITED_AMOUNT));
        List<String> statement = records(renderer.renderStatementAlert(context, rows, TOTAL));
        List<String> fraudAlert = records(renderer.renderFraudAlert(context,
                FLAGGED_TRANSACTION_ID, 87, List.of("VELOCITY", masked)));

        assertThat(statement).as("records a masked statement holds")
                .hasSize(SURROUNDING_RECORD_COUNT + rows.size());
        assertNoRecordCarries(statement, "the full card number", CARD_NUMBER);
        assertNoRecordCarries(statement, "the leading twelve card characters",
                CARD_NUMBER.substring(0, PanMasker.CARD_NUMBER_LENGTH
                        - PanMasker.VISIBLE_DIGIT_COUNT));
        assertNoRecordCarries(fraudAlert, "the full card number", CARD_NUMBER);
    }

    /**
     * Drives the card verification value through the production redaction step and into every value
     * field, then checks that no record carries the value. {@code CARD-CVV-CD PIC 9(03)} at
     * app/cpy/CVACT02Y.cpy:L7 declares the field, no group at app/cbl/CBSTM03A.CBL:L86-L146
     * renders it, and no entity of this service stores it.
     */
    @Test
    @DisplayName("A card verification value reaches no record once the production redaction has run")
    void aVerificationValueReachesNoRecordOnceTheProductionRedactionHasRun() {
        String redacted = PanMasker.redactCardVerificationValue(CARD_VERIFICATION_VALUE);

        assertThat(CARD_VERIFICATION_VALUE).hasSize(PanMasker.CARD_VERIFICATION_VALUE_LENGTH);
        assertThat(redacted.contains(CARD_VERIFICATION_VALUE))
                .as("the redacted form carries the verification value").isFalse();

        CardholderContext context = new CardholderContext(redacted, redacted, redacted, redacted,
                ACCOUNT_ID, EDITED_BALANCE, redacted);
        List<TransactionRow> rows =
                List.of(new TransactionRow(FIRST_TRANSACTION_ID, redacted, FIRST_EDITED_AMOUNT));
        List<String> statement = records(renderer.renderStatementAlert(context, rows, TOTAL));
        List<String> fraudAlert = records(renderer.renderFraudAlert(context,
                FLAGGED_TRANSACTION_ID, 87, List.of("VELOCITY", redacted)));

        assertNoRecordCarries(statement, "the card verification value", CARD_VERIFICATION_VALUE);
        assertNoRecordCarries(fraudAlert, "the card verification value", CARD_VERIFICATION_VALUE);
    }

    /**
     * Drives markup characters through every value field of both render operations. A text record
     * carries them unchanged, because {@code app/cbl/CBSTM03A.CBL} escapes nothing and an escaped
     * ampersand would widen the fixed-width field the source declares.
     *
     * <p>The property under test is the record geometry. Every record still holds eighty
     * characters and the record count still follows the row count, so no supplied character can
     * split a record or add one.</p>
     */
    @Test
    @DisplayName("Markup characters reach a text record unchanged and change no record geometry")
    void markupCharactersReachATextRecordUnchangedAndChangeNoRecordGeometry() {
        CardholderContext context = new CardholderContext(HOSTILE_VALUE, HOSTILE_VALUE,
                HOSTILE_VALUE, HOSTILE_VALUE, HOSTILE_VALUE, EDITED_BALANCE, HOSTILE_VALUE);
        List<TransactionRow> rows = List.of(
                new TransactionRow(FIRST_TRANSACTION_ID, HOSTILE_VALUE, FIRST_EDITED_AMOUNT));
        List<String> statement = records(renderer.renderStatementAlert(context, rows, TOTAL));
        List<String> fraudAlert = records(renderer.renderFraudAlert(context,
                FLAGGED_TRANSACTION_ID, 87, List.of(HOSTILE_VALUE)));

        assertThat(statement).as("records a statement of hostile values holds")
                .hasSize(SURROUNDING_RECORD_COUNT + rows.size())
                .allSatisfy(record -> assertThat(record).hasSize(RECORD_WIDTH));
        assertThat(fraudAlert).as("records a fraud alert of hostile values holds")
                .isNotEmpty()
                .allSatisfy(record -> assertThat(record).hasSize(RECORD_WIDTH));

        // The text format escapes nothing, so the characters arrive as supplied and no entity
        // reference appears in any record.
        assertThat(String.join(RECORD_SEPARATOR, statement)).contains(HOSTILE_VALUE);
        assertNoRecordCarries(statement, "an escaped ampersand", "&amp;");
        assertNoRecordCarries(statement, "an escaped opening angle bracket", "&lt;");
        assertNoRecordCarries(fraudAlert, "an escaped ampersand", "&amp;");
        assertNoRecordCarries(fraudAlert, "an escaped opening angle bracket", "&lt;");
    }

    /**
     * Checks the width of every record of the fraud alert. The operation is ADDITIVE and has no
     * ancestor in app/cbl/CBSTM03A.CBL, and it reuses the 80-column record of
     * {@code 01 FD-STMTFILE-REC PIC X(80).} at app/cbl/CBSTM03A.CBL:L45.
     */
    @Test
    @DisplayName("Every fraud alert record holds 80 characters")
    void everyFraudAlertRecordHoldsEightyCharacters() {
        List<String> records = fraudAlertRecords();

        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(record -> assertThat(record).hasSize(RECORD_WIDTH));
        assertThat(records.get(BANNER_INDEX)).isEqualTo(OPENING_BANNER_RECORD);
        assertThat(records.get(records.size() - 1)).isEqualTo(CLOSING_BANNER_RECORD);
    }

    /**
     * Checks that the fraud alert carries no card field either. Its record vocabulary comes from
     * the groups at app/cbl/CBSTM03A.CBL:L86-L146, and none of them declares a card number or a
     * card verification value. The alert takes no card parameter, so the only route into a record
     * is a triggered rule, and this test supplies both card values there.
     */
    @Test
    @DisplayName("No fraud alert record carries a card number or a card verification value")
    void noFraudAlertRecordCarriesACardField() {
        List<String> records = records(renderer.renderFraudAlert(context(),
                FLAGGED_TRANSACTION_ID, 87,
                List.of(PanMasker.maskCardNumber(CARD_NUMBER),
                        PanMasker.redactCardVerificationValue(CARD_VERIFICATION_VALUE))));

        assertNoRecordCarries(records, "the full card number", CARD_NUMBER);
        assertNoRecordCarries(records, "the card verification value", CARD_VERIFICATION_VALUE);
    }

    /**
     * Asserts a forbidden value reaches no record, naming the field type and the record index.
     *
     * <p>The subject of each assertion is a boolean, so a failure reports which record carried
     * something forbidden and prints neither the record nor the value it carried.</p>
     *
     * @param records   the rendered records, in emitted order
     * @param fieldType a description of the forbidden value, carrying none of its characters
     * @param forbidden the value no record may carry
     */
    private static void assertNoRecordCarries(List<String> records, String fieldType,
            String forbidden) {
        for (int index = 0; index < records.size(); index++) {
            assertThat(records.get(index).contains(forbidden))
                    .as("%s reaches record %d of %d", fieldType, index + 1, records.size())
                    .isFalse();
        }
    }

    /**
     * Returns the component names one record type declares, in declaration order.
     *
     * @param recordType a record class
     * @return the component names
     */
    private static List<String> componentNamesOf(Class<?> recordType) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Reports whether a component name reaches a card number or a card verification value. The
     * masked component name reaches the masked form alone and is approved.
     *
     * @param componentName one record component name
     * @return {@code true} when the name reaches a card value
     */
    private static boolean namesACardField(String componentName) {
        String folded = componentName.toLowerCase(Locale.ROOT);

        return folded.contains("card") || folded.contains("pan") || folded.contains("cvv")
                || folded.contains("verification");
    }

    /**
     * Renders the two-transaction statement the content assertions read, covering the write blocks
     * at app/cbl/CBSTM03A.CBL:L460, app/cbl/CBSTM03A.CBL:L488-L502, app/cbl/CBSTM03A.CBL:L679 and
     * app/cbl/CBSTM03A.CBL:L435-L437.
     *
     * @return the 21 records of a two-transaction statement, in emitted order
     */
    private List<String> statementRecords() {
        return renderStatement(standardRows());
    }

    /**
     * Renders one statement over the supplied rows, each row reaching the detail write at
     * app/cbl/CBSTM03A.CBL:L679.
     *
     * @param rows the transactions to render, in the order they render
     * @return the emitted records, in order
     */
    private List<String> renderStatement(List<TransactionRow> rows) {
        return records(renderer.renderStatementAlert(context(), rows, TOTAL));
    }

    /**
     * Renders the detail record of a one-transaction statement, from
     * {@code 6000-WRITE-TRANS} at app/cbl/CBSTM03A.CBL:L675-L679.
     *
     * @param description the value the {@code MOVE} at app/cbl/CBSTM03A.CBL:L677 sends into
     *                    {@code ST-TRANDT}
     * @return the one detail record the statement carries
     */
    private String detailRecordFor(String description) {
        TransactionRow row =
                new TransactionRow(FIRST_TRANSACTION_ID, description, FIRST_EDITED_AMOUNT);

        return renderStatement(List.of(row)).get(FIRST_DETAIL_INDEX);
    }

    /**
     * Renders one fraud alert. The operation is ADDITIVE and reuses the 80-column record of
     * {@code 01 FD-STMTFILE-REC PIC X(80).} at app/cbl/CBSTM03A.CBL:L45.
     *
     * @return the emitted records, in order
     */
    private List<String> fraudAlertRecords() {
        return records(renderer.renderFraudAlert(context(), FLAGGED_TRANSACTION_ID, 87,
                List.of("VELOCITY", "AMOUNT_ANOMALY")));
    }

    /**
     * Builds the cardholder fields {@code 5000-CREATE-STATEMENT} fills at
     * app/cbl/CBSTM03A.CBL:L462-L485.
     *
     * @return the seven cardholder components, each at the width its group declares
     */
    private static CardholderContext context() {
        return new CardholderContext(ASSEMBLED_NAME, ADDRESS_LINE_1, ADDRESS_LINE_2,
                ADDRESS_LINE_3, ACCOUNT_ID, EDITED_BALANCE, FICO_SCORE);
    }

    /**
     * Builds the two rows the content assertions read, in the field order of
     * app/cbl/CBSTM03A.CBL:L676-L678.
     *
     * @return two transactions with distinct identifiers, descriptions and amounts
     */
    private static List<TransactionRow> standardRows() {
        return List.of(
                new TransactionRow(FIRST_TRANSACTION_ID, FIRST_DESCRIPTION, FIRST_EDITED_AMOUNT),
                new TransactionRow(SECOND_TRANSACTION_ID, SECOND_DESCRIPTION,
                        SECOND_EDITED_AMOUNT));
    }

    /**
     * Builds the requested number of rows, each carrying a distinct identifier in the
     * alphanumeric form of {@code TRNX-ID PIC X(16)} at app/cpy/COSTM01.CPY:L23.
     *
     * @param count the transactions to build, from zero upward
     * @return the rows, in the order they render
     */
    private static List<TransactionRow> rows(int count) {
        List<TransactionRow> rows = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            String suffix = index < 10 ? "0" + index : Integer.toString(index);
            rows.add(new TransactionRow("TRNXAA00000000" + suffix, "PURCHASE " + suffix,
                    FIRST_EDITED_AMOUNT));
        }

        return rows;
    }

    /**
     * Splits a rendered alert into its records. Each {@code WRITE FD-STMTFILE-REC} at
     * app/cbl/CBSTM03A.CBL:L488-L502 emits one record of app/cbl/CBSTM03A.CBL:L45.
     *
     * @param rendered the value a render operation returned
     * @return one element per emitted record, in order
     */
    private static List<String> records(String rendered) {
        return List.of(rendered.split(RECORD_SEPARATOR, -1));
    }

    /**
     * Builds a {@code FILLER VALUE SPACES} field of the given width, such as the
     * {@code PIC X(05)} at app/cbl/CBSTM03A.CBL:L92.
     *
     * @param width the field width, taken from a Picture clause
     * @return exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return " ".repeat(width);
    }


    /**
     * Checks that a carriage return and line feed inside a description cannot add a record.
     *
     * <p>The statement file is a sequence of fixed-width records, {@code FD-STMTFILE-REC PIC X(80)}
     * at app/cbl/CBSTM03A.CBL:L45. A reader splits it by width or by line, and a description
     * carrying a line ending would let a caller close the current record early and have the
     * characters after it read as a further one. The forged text below is shaped to look like a
     * genuine detail record: an identifier, a description and an amount.
     */
    @Test
    @DisplayName("A carriage return in a description adds no record and shifts no column")
    void aCarriageReturnInADescriptionAddsNoRecord() {
        String forged = "Coffee\r\nTRNXAA9999999999 Refund issued";
        List<String> records = renderStatement(List.of(
                new TransactionRow(FIRST_TRANSACTION_ID, forged, FIRST_EDITED_AMOUNT)));
        List<String> clean = renderStatement(List.of(
                new TransactionRow(FIRST_TRANSACTION_ID, "Coffee", FIRST_EDITED_AMOUNT)));

        assertThat(records)
                .as("a line ending inside a description must not change the record count")
                .hasSameSizeAs(clean);
        assertThat(records)
                .as("no record may carry a line ending, or a reader would split it")
                .allSatisfy(record -> assertThat(record)
                        .hasSize(RECORD_WIDTH)
                        .doesNotContain("\r")
                        .doesNotContain("\n"));
        assertThat(records.get(FIRST_DETAIL_INDEX))
                .as("the forged text stays inside the description column, spaces in place of the "
                        + "line ending")
                .contains("Coffee  TRNXAA9999999999 Refund issued");
    }

    /**
     * Checks that a tab inside a description does not shift a column.
     *
     * <p>Every record of this file is laid out by position. A tab is one character wide in the
     * record and eight columns wide in a terminal, so a tab that survived would misalign every
     * column after it for a reader.
     */
    @Test
    @DisplayName("A tab in a description becomes a space and the columns stay in place")
    void aTabInADescriptionKeepsEveryColumnInPlace() {
        String record = detailRecordFor("Coffee\tshop");
        String expected = detailRecordFor("Coffee shop");

        assertThat(record)
                .as("one character in, one character out, so the layout is identical")
                .isEqualTo(expected)
                .hasSize(RECORD_WIDTH)
                .doesNotContain("\t");
    }

    /**
     * Checks that the remaining control characters a caller might try are normalised too.
     *
     * <p>A null character truncates the record for a reader written in C, an escape opens a
     * terminal control sequence in a console log, a vertical tab and a form feed both end a line
     * for some readers, and delete is invisible. Each becomes one space.
     */
    @Test
    @DisplayName("Every other control character becomes a space")
    void everyOtherControlCharacterBecomesASpace() {
        for (char control : new char[] {'\u0000', '\u0007', '\u000b', '\u000c', '\u001b',
                '\u007f'}) {
            String record = detailRecordFor("Coffee" + control + "shop");

            assertThat(record)
                    .as("code point " + (int) control + " must not survive into a record")
                    .isEqualTo(detailRecordFor("Coffee shop"))
                    .hasSize(RECORD_WIDTH);
        }
    }

    /**
     * Checks that the printable punctuation the fixtures hold is left alone.
     *
     * <p>Descriptions in app/data/ASCII/dailytran.txt carry the apostrophe, the comma and the
     * hyphen. Normalising a control character must not disturb any of them, or the renderer would
     * stop reproducing the source text.
     */
    @Test
    @DisplayName("Printable punctuation reaches the record unchanged")
    void printablePunctuationReachesTheRecordUnchanged() {
        String punctuated = "O'Connell's Bar & Grill, Ltd. - 50% off";

        assertThat(detailRecordFor(punctuated))
                .as("app/data/ASCII/dailytran.txt holds the apostrophe, the comma and the hyphen")
                .contains(punctuated)
                .hasSize(RECORD_WIDTH);
    }

    /**
     * Checks that a control character in a cardholder field is normalised as well.
     *
     * <p>{@code ST-NAME} at app/cbl/CBSTM03A.CBL:L91 and the three address lines pass through the
     * same fixed-width gate, so the guard must cover them and not the description alone.
     */
    @Test
    @DisplayName("A control character in a cardholder field adds no record either")
    void aControlCharacterInACardholderFieldAddsNoRecord() {
        CardholderContext injected = new CardholderContext("Doe\r\nJohn", ADDRESS_LINE_1,
                ADDRESS_LINE_2, ADDRESS_LINE_3, ACCOUNT_ID, EDITED_BALANCE, FICO_SCORE);

        List<String> records = records(
                renderer.renderStatementAlert(injected, standardRows(), TOTAL));

        assertThat(records)
                .as("a line ending in a cardholder field must not change the record count")
                .hasSameSizeAs(statementRecords());
        assertThat(records)
                .as("the record width holds and no record carries a line ending")
                .allSatisfy(record -> assertThat(record)
                        .hasSize(RECORD_WIDTH)
                        .doesNotContain("\r")
                        .doesNotContain("\n"));
        assertThat(records.get(NAME_INDEX))
                .as("ST-NAME at app/cbl/CBSTM03A.CBL:L91 carries the name with spaces in place of "
                        + "the line ending")
                .startsWith("Doe  John");
    }

    /**
     * Reads one column of a record. Column 1 is the first character of
     * {@code 01 FD-STMTFILE-REC PIC X(80).} at app/cbl/CBSTM03A.CBL:L45.
     *
     * @param record the record to read
     * @param column the column number, counting from 1
     * @return the character in that column
     */
    private static char column(String record, int column) {
        return record.charAt(column - 1);
    }

    /**
     * Reads a closed range of columns from a record, counting from column 1 of
     * {@code 01 FD-STMTFILE-REC PIC X(80).} at app/cbl/CBSTM03A.CBL:L45.
     *
     * @param record      the record to read
     * @param firstColumn the first column of the range, counting from 1
     * @param lastColumn  the last column of the range, counting from 1
     * @return the characters in those columns
     */
    private static String columns(String record, int firstColumn, int lastColumn) {
        return record.substring(firstColumn - 1, lastColumn);
    }
}

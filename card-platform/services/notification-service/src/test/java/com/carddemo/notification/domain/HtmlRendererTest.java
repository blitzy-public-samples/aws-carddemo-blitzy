package com.carddemo.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.domain.NotificationRenderer.CardholderContext;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationRenderer.TransactionRow;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the markup records {@link HtmlRenderer} emits against the {@code HTMLFILE} records of
 * {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>Every expected value below is typed by hand from the Common Business Oriented Language
 * (COBOL) declaration it reproduces. A Picture clause, written {@code PIC}, fixes the width of a
 * COBOL field. A condition name, written at level 88, gives one literal value a name. A
 * {@code STRING} statement joins several sending fields into one receiving field, and its
 * {@code DELIMITED BY} phrase names the characters that end each sending field.</p>
 *
 * <p>Thirty-four condition names sit at {@code app/cbl/CBSTM03A.CBL:L150-L211}. Twenty-three
 * occupy a single source fragment, and eleven continue onto a second line under a hyphen in
 * column 7. A continuation joins with no separator, so each of the eleven is asserted here in its
 * concatenated form. Two group items follow at {@code app/cbl/CBSTM03A.CBL:L212} and
 * {@code app/cbl/CBSTM03A.CBL:L217}.</p>
 *
 * <p>Every record holds exactly {@value #RECORD_WIDTH} characters. Three declarations fix that
 * width: {@code FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47},
 * {@code HTML-FIXED-LN PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149}, and the dataset the
 * Job Control Language job allocates with {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at
 * {@code app/jcl/CREASTMT.JCL:L94}.</p>
 *
 * <p>Four write blocks supply the records. The header writes 22 at
 * {@code app/cbl/CBSTM03A.CBL:L509-L552}. The name, address and basic-details block writes 34 at
 * {@code app/cbl/CBSTM03A.CBL:L568-L669}. Each transaction row writes 11 at
 * {@code app/cbl/CBSTM03A.CBL:L681-L721}. The trailer writes 8 at
 * {@code app/cbl/CBSTM03A.CBL:L439-L454}.</p>
 *
 * <p>Every input is built in line, so no test reads a file. No Spring application context, no
 * broker and no database take part.</p>
 *
 * <p>Three neighbouring test classes hold the assertions this one leaves out. The fixed-width
 * text records of {@code app/cbl/CBSTM03A.CBL:L488-L502} belong to
 * {@code PlainTextRendererTest}. The shared helpers that fill
 * {@code app/cbl/CBSTM03A.CBL:L462-L481} belong to {@code NotificationRendererTest}. The total at
 * {@code app/cbl/CBSTM03A.CBL:L434} belongs to {@code NotificationServiceTest}.</p>
 */
@DisplayName("HtmlRenderer, the hundred-column markup records of CBSTM03A")
class HtmlRendererTest {

    /**
     * Characters in every markup record, from {@code FD-HTMLFILE-REC PIC X(100)} at
     * {@code app/cbl/CBSTM03A.CBL:L47}.
     */
    private static final int RECORD_WIDTH = 100;

    /** Records the header block writes at {@code app/cbl/CBSTM03A.CBL:L509-L552}. */
    private static final int HEADER_RECORD_COUNT = 22;

    /**
     * Records the name, address and basic-details block writes at
     * {@code app/cbl/CBSTM03A.CBL:L568-L669}.
     */
    private static final int BLOCK_RECORD_COUNT = 34;

    /**
     * Markup records each transaction row writes at {@code app/cbl/CBSTM03A.CBL:L681-L721}. The
     * text write for the same row sits at {@code app/cbl/CBSTM03A.CBL:L679}.
     */
    private static final int ROW_RECORD_COUNT = 11;

    /** Records the trailer writes at {@code app/cbl/CBSTM03A.CBL:L439-L454}. */
    private static final int TRAILER_RECORD_COUNT = 8;

    /**
     * Records a statement carries apart from its rows, summing the three blocks that run once at
     * {@code app/cbl/CBSTM03A.CBL:L509-L552}, {@code app/cbl/CBSTM03A.CBL:L568-L669} and
     * {@code app/cbl/CBSTM03A.CBL:L439-L454}.
     */
    private static final int FIXED_RECORD_COUNT =
            HEADER_RECORD_COUNT + BLOCK_RECORD_COUNT + TRAILER_RECORD_COUNT;

    /**
     * Index of the account-number heading, the group write
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-L11} at {@code app/cbl/CBSTM03A.CBL:L530}.
     */
    private static final int ACCOUNT_HEADING_INDEX = 10;

    /**
     * Index of the composed name record, the bare {@code WRITE FD-HTMLFILE-REC} at
     * {@code app/cbl/CBSTM03A.CBL:L568}.
     */
    private static final int NAME_LINE_INDEX = 22;

    /** Index of the first address record, the write at {@code app/cbl/CBSTM03A.CBL:L576}. */
    private static final int ADDRESS_LINE_1_INDEX = 23;

    /** Index of the second address record, the write at {@code app/cbl/CBSTM03A.CBL:L584}. */
    private static final int ADDRESS_LINE_2_INDEX = 24;

    /** Index of the third address record, the write at {@code app/cbl/CBSTM03A.CBL:L592}. */
    private static final int ADDRESS_LINE_3_INDEX = 25;

    /**
     * Index of the account-identifier cell, the write at {@code app/cbl/CBSTM03A.CBL:L619}.
     */
    private static final int ACCOUNT_CELL_INDEX = 35;

    /** Index of the balance cell, the write at {@code app/cbl/CBSTM03A.CBL:L626}. */
    private static final int BALANCE_CELL_INDEX = 36;

    /** Index of the credit-score cell, the write at {@code app/cbl/CBSTM03A.CBL:L633}. */
    private static final int FICO_CELL_INDEX = 37;

    /**
     * Index of the first record of the first transaction row. The column-header row closes at
     * {@code app/cbl/CBSTM03A.CBL:L669} and {@code 6000-WRITE-TRANS} opens at
     * {@code app/cbl/CBSTM03A.CBL:L675}.
     */
    private static final int FIRST_ROW_INDEX = HEADER_RECORD_COUNT + BLOCK_RECORD_COUNT;

    /**
     * Offset of the transaction-identifier cell inside a row. The {@code STRING} that composes it
     * runs at {@code app/cbl/CBSTM03A.CBL:L687-L689}.
     */
    private static final int ROW_TRANSACTION_ID_OFFSET = 2;

    /**
     * Offset of the description cell inside a row. Its {@code STRING} reads {@code ST-TRANDT} at
     * {@code app/cbl/CBSTM03A.CBL:L700} and the write follows at
     * {@code app/cbl/CBSTM03A.CBL:L704}.
     */
    private static final int ROW_DESCRIPTION_OFFSET = 5;

    /** Offset of the amount cell inside a row, the write at {@code app/cbl/CBSTM03A.CBL:L716}. */
    private static final int ROW_AMOUNT_OFFSET = 8;

    /** {@code HTML-L01}, the condition name at {@code app/cbl/CBSTM03A.CBL:L150}. */
    private static final String HTML_L01 = "<!DOCTYPE html>";

    /** {@code HTML-L02}, the condition name at {@code app/cbl/CBSTM03A.CBL:L151}. */
    private static final String HTML_L02 = "<html lang=\"en\">";

    /** {@code HTML-L03}, the condition name at {@code app/cbl/CBSTM03A.CBL:L152}. */
    private static final String HTML_L03 = "<head>";

    /** {@code HTML-L04}, the condition name at {@code app/cbl/CBSTM03A.CBL:L153}. */
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";

    /** {@code HTML-L05}, the condition name at {@code app/cbl/CBSTM03A.CBL:L154}. */
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";

    /** {@code HTML-L06}, the condition name at {@code app/cbl/CBSTM03A.CBL:L155}. */
    private static final String HTML_L06 = "</head>";

    /** {@code HTML-L07}, the condition name at {@code app/cbl/CBSTM03A.CBL:L156}. */
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";

    /**
     * {@code HTML-L08}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L157-L158}. The first fragment ends after {@code styl} and the
     * second opens with {@code e=}. Two spaces follow the element name, and the font stack sits
     * at {@code app/cbl/CBSTM03A.CBL:L158} alone.
     */
    private static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; "
                    + "font:12px Segoe UI,sans-serif;\">";

    /** {@code HTML-LTRS}, the condition name at {@code app/cbl/CBSTM03A.CBL:L159}. */
    private static final String HTML_LTRS = "<tr>";

    /** {@code HTML-LTRE}, the condition name at {@code app/cbl/CBSTM03A.CBL:L160}. */
    private static final String HTML_LTRE = "</tr>";

    /**
     * {@code HTML-LTDS}, the condition name at {@code app/cbl/CBSTM03A.CBL:L161}. No
     * {@code SET} statement and no {@code WRITE} statement in the 924 lines of
     * {@code app/cbl/CBSTM03A.CBL} names it, so no record carries it.
     */
    private static final String HTML_LTDS = "<td>";

    /** {@code HTML-LTDE}, the condition name at {@code app/cbl/CBSTM03A.CBL:L162}. */
    private static final String HTML_LTDE = "</td>";

    /**
     * {@code HTML-L10}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L163-L164}. The join places no space after {@code 5px;}.
     */
    private static final String HTML_L10 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";

    /**
     * {@code HTML-L15}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L165-L166}.
     */
    private static final String HTML_L15 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";

    /**
     * {@code HTML-L16}, the condition name at {@code app/cbl/CBSTM03A.CBL:L167} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L168}. Free-form continuation of one statement carries no
     * hyphen in column 7, so the value is a single fragment.
     */
    private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";

    /**
     * {@code HTML-L17}, the condition name at {@code app/cbl/CBSTM03A.CBL:L169} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L170}.
     */
    private static final String HTML_L17 = "<p>410 Terry Ave N</p>";

    /**
     * {@code HTML-L18}, the condition name at {@code app/cbl/CBSTM03A.CBL:L171} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L172}.
     */
    private static final String HTML_L18 = "<p>Seattle WA 99999</p>";

    /**
     * {@code HTML-L22-35}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L174-L175}.
     */
    private static final String HTML_L22_35 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";

    /**
     * {@code HTML-L30-42}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L177-L178}. The cell centres its content.
     */
    private static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; "
                    + "text-align:center;\">";

    /**
     * {@code HTML-L31}, the condition name at {@code app/cbl/CBSTM03A.CBL:L179} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L180}.
     */
    private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";

    /**
     * {@code HTML-L43}, the condition name at {@code app/cbl/CBSTM03A.CBL:L181} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L182}.
     */
    private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";

    /**
     * {@code HTML-L47}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L184-L185}. The first fragment ends after
     * {@code background-} and the second opens with {@code color:}.
     */
    private static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; "
                    + "text-align:left;\">";

    /**
     * {@code HTML-L48}, the condition name at {@code app/cbl/CBSTM03A.CBL:L186} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L187}.
     */
    private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";

    /**
     * {@code HTML-L50}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L189-L190}.
     */
    private static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; "
                    + "text-align:left;\">";

    /**
     * {@code HTML-L51}, the condition name at {@code app/cbl/CBSTM03A.CBL:L191} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L192}.
     */
    private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";

    /**
     * {@code HTML-L53}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L194-L195}.
     */
    private static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; "
                    + "text-align:right;\">";

    /**
     * {@code HTML-L54}, the condition name at {@code app/cbl/CBSTM03A.CBL:L196} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L197}.
     */
    private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";

    /**
     * {@code HTML-L58}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L199-L200}.
     */
    private static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; "
                    + "text-align:left;\">";

    /**
     * {@code HTML-L61}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L202-L203}.
     */
    private static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; "
                    + "text-align:left;\">";

    /**
     * {@code HTML-L64}, the condition name continued across
     * {@code app/cbl/CBSTM03A.CBL:L205-L206}.
     */
    private static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; "
                    + "text-align:right;\">";

    /**
     * {@code HTML-L75}, the condition name at {@code app/cbl/CBSTM03A.CBL:L207} with its value on
     * {@code app/cbl/CBSTM03A.CBL:L208}.
     */
    private static final String HTML_L75 = "<h3>End of Statement</h3>";

    /** {@code HTML-L78}, the condition name at {@code app/cbl/CBSTM03A.CBL:L209}. */
    private static final String HTML_L78 = "</table>";

    /** {@code HTML-L79}, the condition name at {@code app/cbl/CBSTM03A.CBL:L210}. */
    private static final String HTML_L79 = "</body>";

    /** {@code HTML-L80}, the condition name at {@code app/cbl/CBSTM03A.CBL:L211}. */
    private static final String HTML_L80 = "</html>";

    /**
     * First member of the group {@code HTML-L11}, {@code FILLER PIC X(34)} at
     * {@code app/cbl/CBSTM03A.CBL:L213} with its value at {@code app/cbl/CBSTM03A.CBL:L214}. One
     * space closes the literal inside its quotation marks.
     */
    private static final String L11_HEADING_PREFIX = "<h3>Statement for Account Number: ";

    /**
     * Third member of the group {@code HTML-L11}, {@code FILLER PIC X(05)} at
     * {@code app/cbl/CBSTM03A.CBL:L216}.
     */
    private static final String L11_HEADING_SUFFIX = "</h3>";

    /**
     * First member of the group {@code HTML-L23}, {@code FILLER PIC X(26)} at
     * {@code app/cbl/CBSTM03A.CBL:L218} with its value at {@code app/cbl/CBSTM03A.CBL:L219}. The
     * same 26 characters open the {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L562}.
     */
    private static final String L23_OPEN_PARAGRAPH = "<p style=\"font-size:16px\">";

    /**
     * Opening tag of the three address lines and the three transaction cells, the literal at
     * {@code app/cbl/CBSTM03A.CBL:L570} and {@code app/cbl/CBSTM03A.CBL:L687}.
     */
    private static final String OPEN_PARAGRAPH = "<p>";

    /**
     * Closing tag every composed line supplies as its own last component, the literal at
     * {@code app/cbl/CBSTM03A.CBL:L565} and {@code app/cbl/CBSTM03A.CBL:L616}.
     */
    private static final String CLOSE_PARAGRAPH = "</p>";

    /**
     * Two literal spaces the name and address {@code STRING} statements contribute
     * {@code DELIMITED BY SIZE}, at {@code app/cbl/CBSTM03A.CBL:L564} and
     * {@code app/cbl/CBSTM03A.CBL:L572}. Both spaces reach the record.
     */
    private static final String TWO_SPACES = "  ";

    /**
     * The account label, the literal at {@code app/cbl/CBSTM03A.CBL:L614}. The text counterpart
     * {@code 'Account ID         :' PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L108} carries no
     * trailing space.
     */
    private static final String ACCOUNT_ID_LABEL = "<p>Account ID         : ";

    /**
     * The balance label, the literal at {@code app/cbl/CBSTM03A.CBL:L621}. The text counterpart
     * {@code 'Current Balance    :' PIC X(20)} sits at {@code app/cbl/CBSTM03A.CBL:L112}.
     */
    private static final String CURRENT_BALANCE_LABEL = "<p>Current Balance    : ";

    /**
     * The credit-score label, the literal at {@code app/cbl/CBSTM03A.CBL:L628}. The text
     * counterpart {@code 'FICO Score         :' PIC X(20)} sits at
     * {@code app/cbl/CBSTM03A.CBL:L117}.
     */
    private static final String FICO_SCORE_LABEL = "<p>FICO Score         : ";

    /**
     * Characters in each basic-details label, measured on the literals at
     * {@code app/cbl/CBSTM03A.CBL:L614}, {@code app/cbl/CBSTM03A.CBL:L621} and
     * {@code app/cbl/CBSTM03A.CBL:L628}.
     */
    private static final int BASIC_DETAILS_LABEL_WIDTH = 24;

    /** Width of {@code L11-ACCT PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L215}. */
    private static final int L11_ACCOUNT_WIDTH = 20;

    /** Width of {@code L23-NAME PIC X(50)} at {@code app/cbl/CBSTM03A.CBL:L220}. */
    private static final int L23_NAME_WIDTH = 50;

    /**
     * Width of {@code ST-ACCT-ID}, the second operand of the {@code STRING} at
     * {@code app/cbl/CBSTM03A.CBL:L615}.
     */
    private static final int ST_ACCT_ID_WIDTH = 20;

    /** Width of {@code ST-FICO-SCORE PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L118}. */
    private static final int ST_FICO_SCORE_WIDTH = 20;

    /** Width of {@code ST-TRANID PIC X(16)} at {@code app/cbl/CBSTM03A.CBL:L133}. */
    private static final int ST_TRANID_WIDTH = 16;

    /** Width of {@code ST-TRANDT PIC X(49)} at {@code app/cbl/CBSTM03A.CBL:L135}. */
    private static final int ST_TRANDT_WIDTH = 49;

    /**
     * Characters an edited amount holds: nine digit positions, a decimal point, two decimal digits
     * and one trailing sign position. The form sits at {@code app/cbl/CBSTM03A.CBL:L113}.
     */
    private static final int EDITED_AMOUNT_WIDTH = 13;

    /**
     * Content characters of the account-number heading, from its three members at
     * {@code app/cbl/CBSTM03A.CBL:L213}, {@code app/cbl/CBSTM03A.CBL:L215} and
     * {@code app/cbl/CBSTM03A.CBL:L216}.
     */
    private static final int ACCOUNT_HEADING_LENGTH =
            L11_HEADING_PREFIX.length() + L11_ACCOUNT_WIDTH + L11_HEADING_SUFFIX.length();

    /**
     * Content characters of the account-identifier cell, from the three operands at
     * {@code app/cbl/CBSTM03A.CBL:L614-L616}.
     */
    private static final int ACCOUNT_CELL_LENGTH =
            BASIC_DETAILS_LABEL_WIDTH + ST_ACCT_ID_WIDTH + CLOSE_PARAGRAPH.length();

    /**
     * Content characters of the balance cell, from the three operands at
     * {@code app/cbl/CBSTM03A.CBL:L621-L623}.
     */
    private static final int BALANCE_CELL_LENGTH =
            BASIC_DETAILS_LABEL_WIDTH + EDITED_AMOUNT_WIDTH + CLOSE_PARAGRAPH.length();

    /**
     * Content characters of the credit-score cell, from the three operands at
     * {@code app/cbl/CBSTM03A.CBL:L628-L630}.
     */
    private static final int FICO_CELL_LENGTH =
            BASIC_DETAILS_LABEL_WIDTH + ST_FICO_SCORE_WIDTH + CLOSE_PARAGRAPH.length();

    /**
     * Content characters of the transaction-identifier cell, from the three operands at
     * {@code app/cbl/CBSTM03A.CBL:L687-L689}.
     */
    private static final int TRANSACTION_ID_CELL_LENGTH =
            OPEN_PARAGRAPH.length() + ST_TRANID_WIDTH + CLOSE_PARAGRAPH.length();

    /**
     * Content characters of the description cell, whose second operand is {@code ST-TRANDT} at
     * {@code app/cbl/CBSTM03A.CBL:L700}.
     */
    private static final int DESCRIPTION_CELL_LENGTH =
            OPEN_PARAGRAPH.length() + ST_TRANDT_WIDTH + CLOSE_PARAGRAPH.length();

    /**
     * Content characters of the amount cell, from the three operands at
     * {@code app/cbl/CBSTM03A.CBL:L711-L713}.
     */
    private static final int AMOUNT_CELL_LENGTH =
            OPEN_PARAGRAPH.length() + EDITED_AMOUNT_WIDTH + CLOSE_PARAGRAPH.length();

    /**
     * The five colour values of {@code app/cbl/CBSTM03A.CBL}, at
     * {@code app/cbl/CBSTM03A.CBL:L164}, {@code app/cbl/CBSTM03A.CBL:L166},
     * {@code app/cbl/CBSTM03A.CBL:L175}, {@code app/cbl/CBSTM03A.CBL:L178} and
     * {@code app/cbl/CBSTM03A.CBL:L185}. Two carry lower-case digits and three carry upper-case
     * digits, and the first carries eight hexadecimal digits.
     */
    private static final List<String> SOURCE_COLOURS =
            List.of("#1d1d96b3", "#FFAF33", "#f2f2f2", "#33FFD1", "#33FF5E");

    /**
     * The font stack of {@code HTML-L08}, on the continuation fragment at
     * {@code app/cbl/CBSTM03A.CBL:L158} and nowhere else in the 924 lines of
     * {@code app/cbl/CBSTM03A.CBL}.
     */
    private static final String FONT_STACK = "12px Segoe UI,sans-serif";

    /**
     * The two spaces between the opening table element name and its first attribute, on the first
     * fragment of {@code HTML-L08} at {@code app/cbl/CBSTM03A.CBL:L157}.
     */
    private static final String TABLE_TAG_WITH_DOUBLE_SPACE = "<table  align=";

    /**
     * The padding declaration of the four full-width cells, joined with no space at
     * {@code app/cbl/CBSTM03A.CBL:L163-L164}, {@code app/cbl/CBSTM03A.CBL:L165-L166},
     * {@code app/cbl/CBSTM03A.CBL:L174-L175} and {@code app/cbl/CBSTM03A.CBL:L177-L178}.
     */
    private static final String PADDING_WITHOUT_SPACE = "padding:0px 5px;background-color:";

    /**
     * The padding declaration of the six column cells, joined with one space at
     * {@code app/cbl/CBSTM03A.CBL:L184-L185}, {@code app/cbl/CBSTM03A.CBL:L189-L190},
     * {@code app/cbl/CBSTM03A.CBL:L194-L195}, {@code app/cbl/CBSTM03A.CBL:L199-L200},
     * {@code app/cbl/CBSTM03A.CBL:L202-L203} and {@code app/cbl/CBSTM03A.CBL:L205-L206}.
     */
    private static final String PADDING_WITH_SPACE = "padding:0px 5px; background-color:";

    /** Matches one colour value of a rendered record, in the form used at
     * {@code app/cbl/CBSTM03A.CBL:L164}. */
    private static final Pattern COLOUR_PATTERN = Pattern.compile("#[0-9A-Fa-f]+");

    /**
     * Account identifier fixture, at the width of {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}. The {@code MOVE ACCT-ID TO L11-ACCT} at
     * {@code app/cbl/CBSTM03A.CBL:L529} carries it into the heading.
     */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * Credit-score fixture, reaching {@code ST-FICO-SCORE} through the {@code MOVE} at
     * {@code app/cbl/CBSTM03A.CBL:L485}.
     */
    private static final String FICO_SCORE = "750";

    /**
     * Assembled name fixture in the form the {@code STRING} at
     * {@code app/cbl/CBSTM03A.CBL:L462-L469} produces: three components, each followed by one
     * literal space.
     */
    private static final String ASSEMBLED_NAME = "JOHN Q PUBLIC ";

    /**
     * The characters of the name fixture before its first pair of spaces, which is what the
     * {@code DELIMITED BY '  '} phrase at {@code app/cbl/CBSTM03A.CBL:L563} contributes.
     */
    private static final String NAME_BEFORE_DOUBLE_SPACE = "JOHN Q PUBLIC";

    /**
     * First address fixture, reaching {@code ST-ADD1} through the {@code MOVE} at
     * {@code app/cbl/CBSTM03A.CBL:L470}.
     */
    private static final String ADDRESS_LINE_1 = "410 TERRY AVE N";

    /**
     * Second address fixture, reaching {@code ST-ADD2} through the {@code MOVE} at
     * {@code app/cbl/CBSTM03A.CBL:L471}.
     */
    private static final String ADDRESS_LINE_2 = "SUITE 100";

    /**
     * Third address fixture in the form the {@code STRING} at
     * {@code app/cbl/CBSTM03A.CBL:L472-L481} produces.
     */
    private static final String ADDRESS_LINE_3 = "SEATTLE WA USA 99999";

    /**
     * Edited balance fixture in the form of {@code ST-CURR-BAL PIC 9(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L113}: nine digit positions, a point, two decimals and a blank
     * sign position.
     */
    private static final String EDITED_BALANCE = "000001234.56 ";

    /**
     * Edited row amount fixture in the form of {@code ST-TRANAMT PIC Z(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L137}, where a {@code Z} position renders a leading zero as a
     * space.
     */
    private static final String EDITED_ROW_AMOUNT = "       42.50 ";

    /**
     * Description fixture, narrowed on arrival to {@code ST-TRANDT PIC X(49)} at
     * {@code app/cbl/CBSTM03A.CBL:L135}.
     */
    private static final String DESCRIPTION = "GROCERY STORE PURCHASE";

    /**
     * Transaction total fixture. The markup trailer at
     * {@code app/cbl/CBSTM03A.CBL:L439-L454} names no total field, so no markup record carries
     * this value.
     */
    private static final BigDecimal TOTAL = new BigDecimal("98765.43");

    /**
     * The full sixteen-character Primary Account Number (PAN) of the card a statement covers.
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} declares the width, and the
     * card detail screen shows all sixteen characters at {@code LENGTH=16} in
     * {@code app/bms/COCRDSL.bms:L99}.
     */
    private static final String CARD_NUMBER = "9999888877776666";

    /**
     * The card verification value of the same card, {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    private static final String CARD_VERIFICATION_VALUE = "357";

    /**
     * A value carrying all five characters that mean something in markup. It holds no pair of
     * spaces and no asterisk, so neither delimiter of
     * {@code app/cbl/CBSTM03A.CBL} ends it early and every character reaches the escaping step.
     */
    private static final String HOSTILE_VALUE = "<b>&'\"x</b>";

    /** The five entity references {@link NotificationRenderer#escapeHtmlText(String)} writes. */
    private static final String ESCAPED_HOSTILE_VALUE =
            "&lt;b&gt;&amp;&#39;&quot;x&lt;/b&gt;";

    /** The four raw markup characters an escaped rendering must not gain. */
    private static final char[] RAW_MARKUP_CHARACTERS = {'<', '>', '"', '\''};

    /** The five entity references an ampersand may open in a rendering. */
    private static final List<String> ENTITY_REFERENCES =
            List.of("&amp;", "&lt;", "&gt;", "&quot;", "&#39;");

    /** Expected content of the account-number heading, the group write at
     * {@code app/cbl/CBSTM03A.CBL:L530}. */
    private static final String EXPECTED_ACCOUNT_HEADING =
            L11_HEADING_PREFIX + padTo(ACCOUNT_ID, L11_ACCOUNT_WIDTH) + L11_HEADING_SUFFIX;

    /** Expected content of the composed name record, the bare write at
     * {@code app/cbl/CBSTM03A.CBL:L568}. */
    private static final String EXPECTED_NAME_LINE =
            L23_OPEN_PARAGRAPH + NAME_BEFORE_DOUBLE_SPACE + TWO_SPACES + CLOSE_PARAGRAPH;

    /** Expected content of the first address record, the write at
     * {@code app/cbl/CBSTM03A.CBL:L576}. */
    private static final String EXPECTED_ADDRESS_LINE_1 =
            OPEN_PARAGRAPH + ADDRESS_LINE_1 + TWO_SPACES + CLOSE_PARAGRAPH;

    /** Expected content of the second address record, the write at
     * {@code app/cbl/CBSTM03A.CBL:L584}. */
    private static final String EXPECTED_ADDRESS_LINE_2 =
            OPEN_PARAGRAPH + ADDRESS_LINE_2 + TWO_SPACES + CLOSE_PARAGRAPH;

    /** Expected content of the third address record, the write at
     * {@code app/cbl/CBSTM03A.CBL:L592}. */
    private static final String EXPECTED_ADDRESS_LINE_3 =
            OPEN_PARAGRAPH + ADDRESS_LINE_3 + TWO_SPACES + CLOSE_PARAGRAPH;

    /** Expected content of the account-identifier cell, the write at
     * {@code app/cbl/CBSTM03A.CBL:L619}. */
    private static final String EXPECTED_ACCOUNT_CELL =
            ACCOUNT_ID_LABEL + padTo(ACCOUNT_ID, ST_ACCT_ID_WIDTH) + CLOSE_PARAGRAPH;

    /** Expected content of the balance cell, the write at {@code app/cbl/CBSTM03A.CBL:L626}. */
    private static final String EXPECTED_BALANCE_CELL =
            CURRENT_BALANCE_LABEL + EDITED_BALANCE + CLOSE_PARAGRAPH;

    /** Expected content of the credit-score cell, the write at
     * {@code app/cbl/CBSTM03A.CBL:L633}. */
    private static final String EXPECTED_FICO_CELL =
            FICO_SCORE_LABEL + padTo(FICO_SCORE, ST_FICO_SCORE_WIDTH) + CLOSE_PARAGRAPH;

    /** Expected content of the amount cell of every row, from the operands at
     * {@code app/cbl/CBSTM03A.CBL:L711-L713}. */
    private static final String EXPECTED_AMOUNT_CELL =
            OPEN_PARAGRAPH + EDITED_ROW_AMOUNT + CLOSE_PARAGRAPH;

    /** Expected content of the description cell of every row, whose operand is
     * {@code ST-TRANDT} at {@code app/cbl/CBSTM03A.CBL:L700}. */
    private static final String EXPECTED_DESCRIPTION_CELL =
            OPEN_PARAGRAPH + padTo(DESCRIPTION, ST_TRANDT_WIDTH) + CLOSE_PARAGRAPH;

    /**
     * The 22 condition names a rendered statement carries as whole records, each declared in a
     * single source fragment between {@code app/cbl/CBSTM03A.CBL:L150} and
     * {@code app/cbl/CBSTM03A.CBL:L211}. {@code HTML-LTDS} at
     * {@code app/cbl/CBSTM03A.CBL:L161} completes the 23 and reaches no record.
     */
    private static final List<String> EMITTED_SINGLE_FRAGMENT_LITERALS = List.of(
            HTML_L01, HTML_L02, HTML_L03, HTML_L04, HTML_L05, HTML_L06, HTML_L07,
            HTML_LTRS, HTML_LTRE, HTML_LTDE,
            HTML_L16, HTML_L17, HTML_L18,
            HTML_L31, HTML_L43, HTML_L48, HTML_L51, HTML_L54,
            HTML_L75, HTML_L78, HTML_L79, HTML_L80);

    /**
     * The 11 condition names declared across two source fragments, listed in declaration order
     * from {@code app/cbl/CBSTM03A.CBL:L157} to {@code app/cbl/CBSTM03A.CBL:L206}.
     */
    private static final List<String> CONTINUED_LITERALS = List.of(
            HTML_L08, HTML_L10, HTML_L15, HTML_L22_35, HTML_L30_42,
            HTML_L47, HTML_L50, HTML_L53, HTML_L58, HTML_L61, HTML_L64);

    /**
     * The renderer under test. The write blocks at {@code app/cbl/CBSTM03A.CBL:L439-L721} hold no
     * mutable rendering state, and neither does this class, so one instance serves every test.
     */
    private final HtmlRenderer renderer = new HtmlRenderer();

    /**
     * Renders a fixture value at a declared field width, matching a COBOL {@code MOVE} into an
     * alphanumeric field. {@code L11-ACCT PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L215} is one
     * such field.
     *
     * @param value the fixture value, no wider than {@code width}
     * @param width the declared field width
     * @return the value followed by trailing spaces, exactly {@code width} characters
     */
    private static String padTo(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Builds the transaction identifier of one row. Each identifier carries the row position and
     * one letter, so the rendered markup holds no run of sixteen digits. The field is
     * {@code ST-TRANID PIC X(16)} at {@code app/cbl/CBSTM03A.CBL:L133}.
     *
     * @param position the zero-based row position
     * @return a 15-character identifier
     */
    private static String transactionIdAt(int position) {
        return "TRNX%010dA".formatted(position + 1);
    }

    /**
     * Builds the cardholder fixture, matching the fields {@code 5000-CREATE-STATEMENT} fills at
     * {@code app/cbl/CBSTM03A.CBL:L462-L485}.
     *
     * @return the cardholder fixture every statement test renders
     */
    private static CardholderContext referenceContext() {
        return new CardholderContext(ASSEMBLED_NAME, ADDRESS_LINE_1, ADDRESS_LINE_2,
                ADDRESS_LINE_3, ACCOUNT_ID, EDITED_BALANCE, FICO_SCORE);
    }

    /**
     * Builds one detail row, matching the three fields {@code 6000-WRITE-TRANS} fills at
     * {@code app/cbl/CBSTM03A.CBL:L676-L678}.
     *
     * @param position the zero-based row position
     * @return the row at that position
     */
    private static TransactionRow rowAt(int position) {
        return new TransactionRow(transactionIdAt(position), DESCRIPTION, EDITED_ROW_AMOUNT);
    }

    /**
     * Builds a list of detail rows. {@code 05 WS-CARD-TBL OCCURS 51 TIMES} at
     * {@code app/cbl/CBSTM03A.CBL:L226} and {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} at
     * {@code app/cbl/CBSTM03A.CBL:L228} bound the source tables, and the target list carries any
     * count.
     *
     * @param count the row count, zero or greater
     * @return the rows in render order
     */
    private static List<TransactionRow> rows(int count) {
        List<TransactionRow> built = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            built.add(rowAt(position));
        }

        return built;
    }

    /**
     * Splits a rendered alert into its records. The separator is
     * {@link NotificationRenderer#LINE_SEPARATOR}, and each record reproduces one
     * {@code WRITE FD-HTMLFILE-REC} of {@code app/cbl/CBSTM03A.CBL:L439-L721}.
     *
     * @param rendered the output of one render operation
     * @return the records in write order
     */
    private static List<String> recordsOf(String rendered) {
        return List.of(rendered.split(Pattern.quote(NotificationRenderer.LINE_SEPARATOR), -1));
    }

    /**
     * Returns the content of a record, dropping the trailing pad of
     * {@code FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47}. Every record
     * content closes an element, which one test in {@link RecordWidth} asserts, so no content
     * loses a character here.
     *
     * @param record one 100-character record
     * @return the record without its trailing spaces
     */
    private static String contentOf(String record) {
        return record.stripTrailing();
    }

    /**
     * Returns the content of every record, in write order. The write order runs from
     * {@code app/cbl/CBSTM03A.CBL:L509} to {@code app/cbl/CBSTM03A.CBL:L454}.
     *
     * @param records the records of one rendered alert
     * @return the contents in the same order
     */
    private static List<String> contentsOf(List<String> records) {
        return records.stream().map(HtmlRendererTest::contentOf).toList();
    }

    /**
     * Counts the non-overlapping occurrences of a value in a rendered alert. The colour values of
     * {@code app/cbl/CBSTM03A.CBL:L164-L206} repeat across blocks, and each repetition counts.
     *
     * @param rendered the output of one render operation
     * @param value    the value to count
     * @return the occurrence count, zero or greater
     */
    private static int occurrences(String rendered, String value) {
        int found = 0;
        int from = 0;
        while (true) {
            int at = rendered.indexOf(value, from);
            if (at < 0) {
                return found;
            }
            found++;
            from = at + value.length();
        }
    }

    /**
     * Collects the distinct colour values a rendered alert carries. Ten hash characters sit in the
     * 924 lines of {@code app/cbl/CBSTM03A.CBL}, at {@code app/cbl/CBSTM03A.CBL:L164},
     * {@code app/cbl/CBSTM03A.CBL:L166}, {@code app/cbl/CBSTM03A.CBL:L175},
     * {@code app/cbl/CBSTM03A.CBL:L178}, {@code app/cbl/CBSTM03A.CBL:L185},
     * {@code app/cbl/CBSTM03A.CBL:L190}, {@code app/cbl/CBSTM03A.CBL:L195},
     * {@code app/cbl/CBSTM03A.CBL:L200}, {@code app/cbl/CBSTM03A.CBL:L203} and
     * {@code app/cbl/CBSTM03A.CBL:L206}, and each carries one of five values.
     *
     * @param rendered the output of one render operation
     * @return the distinct colour values, in first-appearance order
     */
    private static Set<String> distinctColours(String rendered) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = COLOUR_PATTERN.matcher(rendered);
        while (matcher.find()) {
            found.add(matcher.group());
        }

        return found;
    }

    /**
     * Renders the reference statement and returns its records. The blocks run in the order
     * {@code 5000-CREATE-STATEMENT} performs them at {@code app/cbl/CBSTM03A.CBL:L458-L504}.
     *
     * @param rowCount the transaction row count
     * @return the records in write order
     */
    private List<String> renderRecords(int rowCount) {
        return recordsOf(renderStatement(rowCount));
    }

    /**
     * Renders the reference statement. {@code app/cbl/CBSTM03A.CBL:L439-L454} closes it with the
     * eight-record trailer.
     *
     * @param rowCount the transaction row count
     * @return the rendered alert
     */
    private String renderStatement(int rowCount) {
        return renderer.renderStatementAlert(referenceContext(), rows(rowCount), TOTAL);
    }

    /**
     * Renders a statement over one supplied cardholder fixture and one row. The cardholder fields
     * reach the markup at {@code app/cbl/CBSTM03A.CBL:L560}, {@code app/cbl/CBSTM03A.CBL:L571},
     * {@code app/cbl/CBSTM03A.CBL:L579} and {@code app/cbl/CBSTM03A.CBL:L587}.
     *
     * @param context the cardholder fixture
     * @return the record contents in write order
     */
    private List<String> contentsFor(CardholderContext context) {
        return contentsOf(recordsOf(
                renderer.renderStatementAlert(context, List.of(rowAt(0)), TOTAL)));
    }

    /**
     * Builds a cardholder fixture carrying one supplied assembled name. The
     * {@code MOVE ST-NAME TO L23-NAME} at {@code app/cbl/CBSTM03A.CBL:L560} narrows the name to
     * {@value #L23_NAME_WIDTH} characters.
     *
     * @param assembledName the value {@code ST-NAME} at {@code app/cbl/CBSTM03A.CBL:L91} holds
     * @return the cardholder fixture
     */
    private static CardholderContext contextWithName(String assembledName) {
        return new CardholderContext(assembledName, ADDRESS_LINE_1, ADDRESS_LINE_2,
                ADDRESS_LINE_3, ACCOUNT_ID, EDITED_BALANCE, FICO_SCORE);
    }

    /**
     * The format this renderer reports. {@code app/cbl/CBSTM03A.CBL:L44-L47} declares one file for
     * each of the two formats.
     */
    @Nested
    @DisplayName("The reported output format")
    class FormatContract {

        /**
         * The renderer reports the markup format of {@code FD-HTMLFILE-REC PIC X(100)} at
         * {@code app/cbl/CBSTM03A.CBL:L47}.
         */
        @Test
        @DisplayName("The renderer reports the markup format")
        void theRendererReportsTheMarkupFormat() {
            assertThat(renderer.format()).isSameAs(RenderedFormat.HTML);
        }

        /**
         * The two implementations report different formats.
         * {@code FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45} and
         * {@code FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47} are separate
         * files with separate widths.
         */
        @Test
        @DisplayName("The markup format differs from the text format")
        void theMarkupFormatDiffersFromTheTextFormat() {
            RenderedFormat markupFormat = renderer.format();
            RenderedFormat textFormat = new PlainTextRenderer().format();

            assertThat(textFormat).isNotSameAs(markupFormat);
            assertThat(markupFormat).isSameAs(RenderedFormat.HTML);
        }
    }

    /**
     * The hundred-column record and the record count.
     * {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at {@code app/jcl/CREASTMT.JCL:L94} allocates
     * the dataset the source writes.
     */
    @Nested
    @DisplayName("The hundred-column record")
    class RecordWidth {

        /**
         * Every record holds {@value HtmlRendererTest#RECORD_WIDTH} characters. Four staging
         * fields feed the write, each {@code PIC X(100)}: {@code HTML-FIXED-LN} at
         * {@code app/cbl/CBSTM03A.CBL:L149}, {@code HTML-ADDR-LN} at
         * {@code app/cbl/CBSTM03A.CBL:L221}, {@code HTML-BSIC-LN} at
         * {@code app/cbl/CBSTM03A.CBL:L222} and {@code HTML-TRAN-LN} at
         * {@code app/cbl/CBSTM03A.CBL:L223}. The group {@code HTML-L11} at
         * {@code app/cbl/CBSTM03A.CBL:L212} spans 59 characters, and the write pads it to the
         * record width.
         *
         * @param rowCount the transaction row count under test
         */
        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 3, 12})
        @DisplayName("Every record holds exactly one hundred characters")
        void everyRecordHoldsExactlyOneHundredCharacters(int rowCount) {
            List<String> records = renderRecords(rowCount);

            assertThat(records).isNotEmpty();
            for (int index = 0; index < records.size(); index++) {
                assertThat(records.get(index))
                        .describedAs("record %d of %d", index, records.size())
                        .hasSize(RECORD_WIDTH);
            }
        }

        /**
         * A statement carries {@value HtmlRendererTest#FIXED_RECORD_COUNT} records plus
         * {@value HtmlRendererTest#ROW_RECORD_COUNT} for each row. The three fixed blocks write 22
         * at {@code app/cbl/CBSTM03A.CBL:L509-L552}, 34 at
         * {@code app/cbl/CBSTM03A.CBL:L568-L669} and 8 at
         * {@code app/cbl/CBSTM03A.CBL:L439-L454}.
         *
         * @param rowCount the transaction row count under test
         */
        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 3, 12})
        @DisplayName("A statement carries sixty-four records plus eleven for each row")
        void aStatementCarriesSixtyFourRecordsPlusElevenForEachRow(int rowCount) {
            assertThat(FIXED_RECORD_COUNT).isEqualTo(64);
            assertThat(renderRecords(rowCount))
                    .hasSize(FIXED_RECORD_COUNT + ROW_RECORD_COUNT * rowCount);
        }

        /**
         * Every record content ends with a closing angle bracket, so the trailing pad of
         * {@code FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47} carries no
         * content character.
         */
        @Test
        @DisplayName("Every record content closes an element and ends in no space")
        void everyRecordContentClosesAnElement() {
            List<String> contents = contentsOf(renderRecords(3));

            assertThat(contents).isNotEmpty()
                    .allSatisfy(content -> assertThat(content).endsWith(">"));
        }

        /**
         * A fraud alert holds the same record width. Its markup has no ancestor in
         * {@code app/cbl/CBSTM03A.CBL}, and it reuses the blocks at
         * {@code app/cbl/CBSTM03A.CBL:L509-L552} and {@code app/cbl/CBSTM03A.CBL:L439-L454}.
         */
        @Test
        @DisplayName("Every record of a fraud alert holds exactly one hundred characters")
        void everyRecordOfAFraudAlertHoldsExactlyOneHundredCharacters() {
            List<String> records = recordsOf(renderer.renderFraudAlert(referenceContext(),
                    transactionIdAt(0), 87, List.of("VELOCITY", "AMOUNT_ANOMALY")));

            assertThat(records).isNotEmpty()
                    .allSatisfy(record -> assertThat(record).hasSize(RECORD_WIDTH));
        }
    }

    /**
     * The condition names declared in a single source fragment, at
     * {@code app/cbl/CBSTM03A.CBL:L150-L211}, and the two group items at
     * {@code app/cbl/CBSTM03A.CBL:L212} and {@code app/cbl/CBSTM03A.CBL:L217}.
     */
    @Nested
    @DisplayName("The single-fragment condition names and the two group items")
    class SingleFragmentLiterals {

        /**
         * Twenty-two single-fragment condition names reach a record whole. The 23rd,
         * {@code HTML-LTDS} at {@code app/cbl/CBSTM03A.CBL:L161}, reaches none.
         */
        @Test
        @DisplayName("Each of the twenty-two emitted condition names reaches a record whole")
        void eachEmittedConditionNameReachesARecordWhole() {
            List<String> contents = contentsOf(renderRecords(1));

            assertThat(EMITTED_SINGLE_FRAGMENT_LITERALS).hasSize(22);
            assertThat(contents).containsAll(EMITTED_SINGLE_FRAGMENT_LITERALS);
        }

        /**
         * {@code HTML-LTDS} at {@code app/cbl/CBSTM03A.CBL:L161} is declared and never set and
         * never written, so no record carries an unadorned cell opening element. Every cell opening
         * element a record does carry names a style attribute.
         */
        @Test
        @DisplayName("The declared and unused cell opening element reaches no record")
        void theDeclaredAndUnusedCellOpeningElementReachesNoRecord() {
            String rendered = renderStatement(3);

            assertThat(rendered).doesNotContain(HTML_LTDS);
            assertThat(contentsOf(recordsOf(rendered)))
                    .filteredOn(content -> content.startsWith("<td"))
                    .isNotEmpty()
                    .allSatisfy(content -> assertThat(content).contains("style=\""));
        }

        /**
         * The group {@code HTML-L23} at {@code app/cbl/CBSTM03A.CBL:L217} reaches no record as a
         * group. Its two members span 76 characters and declare no closing tag. The
         * {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L562-L567} supplies the closing tag at
         * {@code app/cbl/CBSTM03A.CBL:L565}. That statement also trims {@code L23-NAME} at its
         * first pair of spaces.
         */
        @Test
        @DisplayName("The name group reaches no record as a group")
        void theNameGroupReachesNoRecordAsAGroup() {
            String nameRecord = renderRecords(1).get(NAME_LINE_INDEX);

            assertThat(L23_OPEN_PARAGRAPH).hasSize(26);
            assertThat(nameRecord).startsWith(L23_OPEN_PARAGRAPH);
            assertThat(contentOf(nameRecord)).endsWith(CLOSE_PARAGRAPH);
            assertThat(nameRecord)
                    .doesNotContain(padTo(NAME_BEFORE_DOUBLE_SPACE, L23_NAME_WIDTH));
        }

        /**
         * The group {@code HTML-L11} at {@code app/cbl/CBSTM03A.CBL:L212} reaches exactly one
         * record, through {@code WRITE FD-HTMLFILE-REC FROM HTML-L11} at
         * {@code app/cbl/CBSTM03A.CBL:L530}. Its three members span 34, 20 and 5 characters, and
         * {@code MOVE ACCT-ID TO L11-ACCT} at {@code app/cbl/CBSTM03A.CBL:L529} fills the middle
         * member.
         */
        @Test
        @DisplayName("The account-number heading reaches one record at fifty-nine characters")
        void theAccountNumberHeadingReachesOneRecordAtFiftyNineCharacters() {
            List<String> records = renderRecords(1);
            List<String> contents = contentsOf(records);

            assertThat(L11_HEADING_PREFIX).hasSize(34).endsWith(": ");
            assertThat(L11_HEADING_SUFFIX).hasSize(5);
            assertThat(ACCOUNT_HEADING_LENGTH).isEqualTo(59);
            assertThat(records.get(ACCOUNT_HEADING_INDEX)).hasSize(RECORD_WIDTH);
            assertThat(contents.get(ACCOUNT_HEADING_INDEX))
                    .isEqualTo(EXPECTED_ACCOUNT_HEADING)
                    .hasSize(ACCOUNT_HEADING_LENGTH);
            assertThat(contents).filteredOn(content -> content.startsWith(L11_HEADING_PREFIX))
                    .hasSize(1);
        }
    }

    /**
     * The eleven condition names continued across two source lines, at
     * {@code app/cbl/CBSTM03A.CBL:L158}, {@code app/cbl/CBSTM03A.CBL:L164},
     * {@code app/cbl/CBSTM03A.CBL:L166}, {@code app/cbl/CBSTM03A.CBL:L175},
     * {@code app/cbl/CBSTM03A.CBL:L178}, {@code app/cbl/CBSTM03A.CBL:L185},
     * {@code app/cbl/CBSTM03A.CBL:L190}, {@code app/cbl/CBSTM03A.CBL:L195},
     * {@code app/cbl/CBSTM03A.CBL:L200}, {@code app/cbl/CBSTM03A.CBL:L203} and
     * {@code app/cbl/CBSTM03A.CBL:L206}. Seven of the eleven break inside a word.
     */
    @Nested
    @DisplayName("The eleven two-line continuations")
    class ContinuedLiterals {

        /**
         * Each continued condition name reaches a record whole, at the length its two fragments
         * measure. The eleven at {@code app/cbl/CBSTM03A.CBL:L157-L206} are the only continuations
         * in the 924 lines of the program, and each is shorter than the record width, so the write
         * pads it.
         */
        @Test
        @DisplayName("Each continued condition name reaches a record at its measured length")
        void eachContinuedConditionNameReachesARecordAtItsMeasuredLength() {
            List<Map.Entry<String, Integer>> measured = List.of(
                    Map.entry(HTML_L08, 85),
                    Map.entry(HTML_L10, 68),
                    Map.entry(HTML_L15, 66),
                    Map.entry(HTML_L22_35, 66),
                    Map.entry(HTML_L30_42, 85),
                    Map.entry(HTML_L47, 83),
                    Map.entry(HTML_L50, 83),
                    Map.entry(HTML_L53, 84),
                    Map.entry(HTML_L58, 83),
                    Map.entry(HTML_L61, 83),
                    Map.entry(HTML_L64, 84));
            List<String> contents = contentsOf(renderRecords(1));

            assertThat(measured).hasSize(11);
            assertThat(CONTINUED_LITERALS).hasSize(11).containsAll(
                    measured.stream().map(Map.Entry::getKey).toList());
            for (Map.Entry<String, Integer> entry : measured) {
                assertThat(entry.getKey()).hasSize(entry.getValue());
                assertThat(contents).contains(entry.getKey());
            }
        }

        /**
         * The four full-width cells join their fragments with no space after the semicolon, at
         * {@code app/cbl/CBSTM03A.CBL:L163-L164}, {@code app/cbl/CBSTM03A.CBL:L165-L166},
         * {@code app/cbl/CBSTM03A.CBL:L174-L175} and {@code app/cbl/CBSTM03A.CBL:L177-L178}.
         */
        @Test
        @DisplayName("The four full-width cells place no space after the padding declaration")
        void theFourFullWidthCellsPlaceNoSpaceAfterThePaddingDeclaration() {
            List<String> fullWidthCells = List.of(HTML_L10, HTML_L15, HTML_L22_35, HTML_L30_42);

            for (String cell : fullWidthCells) {
                assertThat(cell).contains("colspan=\"3\"")
                        .contains(PADDING_WITHOUT_SPACE)
                        .doesNotContain(PADDING_WITH_SPACE);
            }
            assertThat(contentsOf(renderRecords(1))).containsAll(fullWidthCells);
        }

        /**
         * The six column cells join their fragments with one space after the semicolon, at
         * {@code app/cbl/CBSTM03A.CBL:L184-L185}, {@code app/cbl/CBSTM03A.CBL:L189-L190},
         * {@code app/cbl/CBSTM03A.CBL:L194-L195}, {@code app/cbl/CBSTM03A.CBL:L199-L200},
         * {@code app/cbl/CBSTM03A.CBL:L202-L203} and {@code app/cbl/CBSTM03A.CBL:L205-L206}. Each
         * of the six splits the property name after {@code background-}.
         */
        @Test
        @DisplayName("The six column cells place one space after the padding declaration")
        void theSixColumnCellsPlaceOneSpaceAfterThePaddingDeclaration() {
            List<String> columnCells =
                    List.of(HTML_L47, HTML_L50, HTML_L53, HTML_L58, HTML_L61, HTML_L64);

            for (String cell : columnCells) {
                assertThat(cell).contains(PADDING_WITH_SPACE)
                        .doesNotContain(PADDING_WITHOUT_SPACE)
                        .doesNotContain("colspan");
            }
            assertThat(contentsOf(renderRecords(1))).containsAll(columnCells);
        }

        /**
         * Two spaces follow the opening table element name, on the first fragment of
         * {@code HTML-L08} at {@code app/cbl/CBSTM03A.CBL:L157}. One record carries the element.
         */
        @Test
        @DisplayName("The opening table element carries two spaces before its first attribute")
        void theOpeningTableElementCarriesTwoSpacesBeforeItsFirstAttribute() {
            assertThat(HTML_L08).startsWith(TABLE_TAG_WITH_DOUBLE_SPACE);
            assertThat(contentsOf(renderRecords(1))).contains(HTML_L08);
            assertThat(occurrences(renderStatement(1), TABLE_TAG_WITH_DOUBLE_SPACE)).isOne();
        }

        /**
         * The three columns take 25, 55 and 20 percent of the table width. The heading cells sit
         * at {@code app/cbl/CBSTM03A.CBL:L184-L185}, {@code app/cbl/CBSTM03A.CBL:L189-L190} and
         * {@code app/cbl/CBSTM03A.CBL:L194-L195}, and the data cells repeat the widths at
         * {@code app/cbl/CBSTM03A.CBL:L199-L200}, {@code app/cbl/CBSTM03A.CBL:L202-L203} and
         * {@code app/cbl/CBSTM03A.CBL:L205-L206}. The third column aligns right and the other two
         * align left.
         */
        @Test
        @DisplayName("The three columns take twenty-five, fifty-five and twenty percent")
        void theThreeColumnsTakeTwentyFiveFiftyFiveAndTwentyPercent() {
            assertThat(HTML_L47).contains("width:25%").contains("text-align:left;");
            assertThat(HTML_L58).contains("width:25%").contains("text-align:left;");
            assertThat(HTML_L50).contains("width:55%").contains("text-align:left;");
            assertThat(HTML_L61).contains("width:55%").contains("text-align:left;");
            assertThat(HTML_L53).contains("width:20%").contains("text-align:right;");
            assertThat(HTML_L64).contains("width:20%").contains("text-align:right;");
            assertThat(contentsOf(renderRecords(1))).containsAll(
                    List.of(HTML_L47, HTML_L50, HTML_L53, HTML_L58, HTML_L61, HTML_L64));
        }
    }

    /**
     * The five colour values and the single font stack of {@code app/cbl/CBSTM03A.CBL}. The
     * declarations sit between {@code app/cbl/CBSTM03A.CBL:L158} and
     * {@code app/cbl/CBSTM03A.CBL:L206}.
     */
    @Nested
    @DisplayName("The five colour values and the font stack")
    class ColourLiterals {

        /**
         * Each colour value reaches the markup with the letter case its declaration carries.
         * {@code #1d1d96b3} at {@code app/cbl/CBSTM03A.CBL:L164} and {@code #f2f2f2} at
         * {@code app/cbl/CBSTM03A.CBL:L175} carry lower-case digits. {@code #FFAF33} at
         * {@code app/cbl/CBSTM03A.CBL:L166}, {@code #33FFD1} at
         * {@code app/cbl/CBSTM03A.CBL:L178} and {@code #33FF5E} at
         * {@code app/cbl/CBSTM03A.CBL:L185} carry upper-case digits.
         */
        @Test
        @DisplayName("Each colour value reaches the markup with its declared letter case")
        void eachColourValueReachesTheMarkupWithItsDeclaredLetterCase() {
            String rendered = renderStatement(1);

            assertThat(SOURCE_COLOURS).hasSize(5);
            for (String colour : SOURCE_COLOURS) {
                assertThat(rendered).contains(colour);
            }
            assertThat(rendered).doesNotContain(
                    "#1D1D96B3", "#ffaf33", "#F2F2F2", "#33ffd1", "#33ff5e");
        }

        /**
         * Each colour appears as often as the blocks that carry it. {@code #1d1d96b3} sits in the
         * cell written at {@code app/cbl/CBSTM03A.CBL:L527} and again at
         * {@code app/cbl/CBSTM03A.CBL:L442}, so it appears twice. {@code #FFAF33} sits in one cell,
         * written at {@code app/cbl/CBSTM03A.CBL:L538}.
         *
         * <p>{@code #33FFD1} sits in two, written at {@code app/cbl/CBSTM03A.CBL:L601} and
         * {@code app/cbl/CBSTM03A.CBL:L641}. {@code #33FF5E} sits in three, written at
         * {@code app/cbl/CBSTM03A.CBL:L651}, {@code app/cbl/CBSTM03A.CBL:L657} and
         * {@code app/cbl/CBSTM03A.CBL:L663}. {@code #f2f2f2} sits in two fixed cells, written at
         * {@code app/cbl/CBSTM03A.CBL:L552} and {@code app/cbl/CBSTM03A.CBL:L611}, and in three
         * more for each row at {@code app/cbl/CBSTM03A.CBL:L681-L721}.</p>
         *
         * @param rowCount the transaction row count under test
         */
        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 12})
        @DisplayName("Each colour appears as often as the blocks that carry it")
        void eachColourAppearsAsOftenAsTheBlocksThatCarryIt(int rowCount) {
            String rendered = renderStatement(rowCount);

            assertThat(occurrences(rendered, "#1d1d96b3")).isEqualTo(2);
            assertThat(occurrences(rendered, "#FFAF33")).isEqualTo(1);
            assertThat(occurrences(rendered, "#33FFD1")).isEqualTo(2);
            assertThat(occurrences(rendered, "#33FF5E")).isEqualTo(3);
            assertThat(occurrences(rendered, "#f2f2f2")).isEqualTo(2 + 3 * rowCount);
        }

        /**
         * The font stack sits on one continuation fragment, at
         * {@code app/cbl/CBSTM03A.CBL:L158}, and reaches one record through the write at
         * {@code app/cbl/CBSTM03A.CBL:L523}.
         */
        @Test
        @DisplayName("The font stack appears exactly once")
        void theFontStackAppearsExactlyOnce() {
            assertThat(occurrences(renderStatement(3), FONT_STACK)).isOne();
        }

        /**
         * No sixth colour value appears. Ten hash characters sit in the 924 lines of
         * {@code app/cbl/CBSTM03A.CBL}, between {@code app/cbl/CBSTM03A.CBL:L164} and
         * {@code app/cbl/CBSTM03A.CBL:L206}, and each carries one of the five values.
         */
        @Test
        @DisplayName("No sixth colour value appears")
        void noSixthColourValueAppears() {
            assertThat(distinctColours(renderStatement(3)))
                    .containsExactlyInAnyOrderElementsOf(SOURCE_COLOURS);
        }
    }

    /**
     * The four write blocks and their order. {@code 5000-CREATE-STATEMENT} at
     * {@code app/cbl/CBSTM03A.CBL:L458-L504} performs the first two, one row block runs for each
     * transaction at {@code app/cbl/CBSTM03A.CBL:L681-L721}, and the trailer closes at
     * {@code app/cbl/CBSTM03A.CBL:L439-L454}.
     */
    @Nested
    @DisplayName("The four markup write blocks, in source order")
    class WriteSequences {

        /**
         * The header writes 22 records at {@code app/cbl/CBSTM03A.CBL:L509-L552}. One of them, the
         * account-number heading at {@code app/cbl/CBSTM03A.CBL:L530}, comes from a group item and
         * the other 21 come from {@code HTML-FIXED-LN} at {@code app/cbl/CBSTM03A.CBL:L149}.
         */
        @Test
        @DisplayName("The header writes its twenty-two records in source order")
        void theHeaderWritesItsTwentyTwoRecordsInSourceOrder() {
            List<String> contents = contentsOf(renderRecords(1));

            assertThat(contents.subList(0, HEADER_RECORD_COUNT)).containsExactly(
                    HTML_L01, HTML_L02, HTML_L03, HTML_L04, HTML_L05, HTML_L06, HTML_L07,
                    HTML_L08, HTML_LTRS, HTML_L10, EXPECTED_ACCOUNT_HEADING, HTML_LTDE,
                    HTML_LTRE, HTML_LTRS, HTML_L15, HTML_L16, HTML_L17, HTML_L18, HTML_LTDE,
                    HTML_LTRE, HTML_LTRS, HTML_L22_35);
        }

        /**
         * The name, address and basic-details block writes 34 records at
         * {@code app/cbl/CBSTM03A.CBL:L568-L669}. {@code 5200-WRITE-HTML-NMADBS} opens at
         * {@code app/cbl/CBSTM03A.CBL:L558} and {@code 5200-EXIT} closes at
         * {@code app/cbl/CBSTM03A.CBL:L671}.
         */
        @Test
        @DisplayName("The name and address block writes its thirty-four records in source order")
        void theNameAndAddressBlockWritesItsThirtyFourRecordsInSourceOrder() {
            List<String> contents = contentsOf(renderRecords(1));

            assertThat(contents.subList(HEADER_RECORD_COUNT, FIRST_ROW_INDEX)).containsExactly(
                    EXPECTED_NAME_LINE, EXPECTED_ADDRESS_LINE_1, EXPECTED_ADDRESS_LINE_2,
                    EXPECTED_ADDRESS_LINE_3, HTML_LTDE, HTML_LTRE, HTML_LTRS,
                    HTML_L30_42, HTML_L31, HTML_LTDE, HTML_LTRE, HTML_LTRS, HTML_L22_35,
                    EXPECTED_ACCOUNT_CELL, EXPECTED_BALANCE_CELL, EXPECTED_FICO_CELL,
                    HTML_LTDE, HTML_LTRE, HTML_LTRS, HTML_L30_42, HTML_L43, HTML_LTDE,
                    HTML_LTRE, HTML_LTRS, HTML_L47, HTML_L48, HTML_LTDE, HTML_L50,
                    HTML_L51, HTML_LTDE, HTML_L53, HTML_L54, HTML_LTDE, HTML_LTRE);
            assertThat(FIRST_ROW_INDEX - HEADER_RECORD_COUNT).isEqualTo(BLOCK_RECORD_COUNT);
        }

        /**
         * Every transaction row writes 11 records at {@code app/cbl/CBSTM03A.CBL:L681-L721}. The
         * eleventh row and the twelfth write the same 11, since the target list carries any count
         * while {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} at {@code app/cbl/CBSTM03A.CBL:L228} bounds
         * the source table.
         *
         * @param rowCount the transaction row count under test
         */
        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 12})
        @DisplayName("Every transaction row writes the same eleven records in source order")
        void everyTransactionRowWritesTheSameElevenRecordsInSourceOrder(int rowCount) {
            List<String> contents = contentsOf(renderRecords(rowCount));

            for (int position = 0; position < rowCount; position++) {
                int start = FIRST_ROW_INDEX + position * ROW_RECORD_COUNT;
                String identifierCell = OPEN_PARAGRAPH
                        + padTo(transactionIdAt(position), ST_TRANID_WIDTH) + CLOSE_PARAGRAPH;

                assertThat(contents.subList(start, start + ROW_RECORD_COUNT))
                        .describedAs("row %d of %d", position, rowCount)
                        .containsExactly(
                                HTML_LTRS, HTML_L58, identifierCell, HTML_LTDE,
                                HTML_L61, EXPECTED_DESCRIPTION_CELL, HTML_LTDE,
                                HTML_L64, EXPECTED_AMOUNT_CELL, HTML_LTDE, HTML_LTRE);
            }
        }

        /**
         * The trailer writes 8 records at {@code app/cbl/CBSTM03A.CBL:L440},
         * {@code app/cbl/CBSTM03A.CBL:L442}, {@code app/cbl/CBSTM03A.CBL:L444},
         * {@code app/cbl/CBSTM03A.CBL:L446}, {@code app/cbl/CBSTM03A.CBL:L448},
         * {@code app/cbl/CBSTM03A.CBL:L450}, {@code app/cbl/CBSTM03A.CBL:L452} and
         * {@code app/cbl/CBSTM03A.CBL:L454}.
         *
         * @param rowCount the transaction row count under test
         */
        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 3, 12})
        @DisplayName("The trailer writes its eight records in source order")
        void theTrailerWritesItsEightRecordsInSourceOrder(int rowCount) {
            List<String> contents = contentsOf(renderRecords(rowCount));
            int start = contents.size() - TRAILER_RECORD_COUNT;

            assertThat(contents.subList(start, contents.size())).containsExactly(
                    HTML_LTRS, HTML_L10, HTML_L75, HTML_LTDE, HTML_LTRE,
                    HTML_L78, HTML_L79, HTML_L80);
        }

        /**
         * The trailer carries no total. {@code ST-TOTAL-TRAMT} sits at two points in the 924 lines
         * of {@code app/cbl/CBSTM03A.CBL}: its declaration at
         * {@code app/cbl/CBSTM03A.CBL:L142} and the {@code MOVE} at
         * {@code app/cbl/CBSTM03A.CBL:L434}. No {@code STRING} statement names it, and the eight
         * writes at {@code app/cbl/CBSTM03A.CBL:L439-L454} name only condition names. The currency
         * symbol at {@code app/cbl/CBSTM03A.CBL:L141} belongs to the text trailer.
         */
        @Test
        @DisplayName("The trailer carries no total, no decimal point and no currency symbol")
        void theTrailerCarriesNoTotalNoDecimalPointAndNoCurrencySymbol() {
            List<String> contents = contentsOf(renderRecords(1));
            String trailer = String.join("",
                    contents.subList(contents.size() - TRAILER_RECORD_COUNT, contents.size()));

            assertThat(trailer).doesNotContain("$").doesNotContain(".")
                    .doesNotContain(TOTAL.toPlainString());
            assertThat(renderStatement(1)).doesNotContain(TOTAL.toPlainString());
        }

        /**
         * The composed name record sits immediately after the last header record. The bare
         * {@code WRITE FD-HTMLFILE-REC} at {@code app/cbl/CBSTM03A.CBL:L568} names no
         * {@code FROM} clause.
         *
         * <p>{@code MOVE SPACES TO FD-HTMLFILE-REC} at {@code app/cbl/CBSTM03A.CBL:L561} clears
         * the record first. The {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L562} then assembles
         * into the record, through {@code INTO FD-HTMLFILE-REC} at
         * {@code app/cbl/CBSTM03A.CBL:L566} and {@code END-STRING} at
         * {@code app/cbl/CBSTM03A.CBL:L567}. The three address writes stage through
         * {@code HTML-ADDR-LN} at {@code app/cbl/CBSTM03A.CBL:L221} and name a {@code FROM}
         * clause.</p>
         */
        @Test
        @DisplayName("The composed name record follows the last header record at full width")
        void theComposedNameRecordFollowsTheLastHeaderRecordAtFullWidth() {
            List<String> records = renderRecords(1);
            List<String> contents = contentsOf(records);

            assertThat(contents.get(NAME_LINE_INDEX - 1)).isEqualTo(HTML_L22_35);
            assertThat(contents.get(NAME_LINE_INDEX)).isEqualTo(EXPECTED_NAME_LINE);
            assertThat(records.get(NAME_LINE_INDEX)).hasSize(RECORD_WIDTH);
        }

        /**
         * The header block precedes the name and address block, matching
         * {@code PERFORM 5100-WRITE-HTML-HEADER} at {@code app/cbl/CBSTM03A.CBL:L461} and
         * {@code PERFORM 5200-WRITE-HTML-NMADBS} at {@code app/cbl/CBSTM03A.CBL:L486}. The text
         * header follows both, from {@code app/cbl/CBSTM03A.CBL:L488}, and belongs to
         * {@code PlainTextRendererTest}.
         */
        @Test
        @DisplayName("The header block precedes the name and address block")
        void theHeaderBlockPrecedesTheNameAndAddressBlock() {
            List<String> contents = contentsOf(renderRecords(1));

            assertThat(contents.indexOf(HTML_L01)).isZero();
            assertThat(contents.indexOf(EXPECTED_NAME_LINE)).isEqualTo(HEADER_RECORD_COUNT);
            assertThat(contents.indexOf(EXPECTED_ACCOUNT_CELL))
                    .isGreaterThan(contents.indexOf(EXPECTED_NAME_LINE));
        }
    }

    /**
     * The two delimiters that shape a composed record. Twelve {@code STRING} statements sit in
     * {@code app/cbl/CBSTM03A.CBL:L462-L715}, and this renderer serves ten.
     *
     * <p>Four take the double space. The name opens at {@code app/cbl/CBSTM03A.CBL:L562}, and the
     * three address lines open at {@code app/cbl/CBSTM03A.CBL:L570},
     * {@code app/cbl/CBSTM03A.CBL:L578} and {@code app/cbl/CBSTM03A.CBL:L586}.</p>
     *
     * <p>Six take the asterisk. The basic-details lines open at
     * {@code app/cbl/CBSTM03A.CBL:L614}, {@code app/cbl/CBSTM03A.CBL:L621} and
     * {@code app/cbl/CBSTM03A.CBL:L628}. The transaction lines run at
     * {@code app/cbl/CBSTM03A.CBL:L687-L689}, {@code app/cbl/CBSTM03A.CBL:L700} and
     * {@code app/cbl/CBSTM03A.CBL:L711-L713}.</p>
     *
     * <p>The remaining two, at {@code app/cbl/CBSTM03A.CBL:L462-L481}, take a single space and
     * belong to {@code NotificationRendererTest}.</p>
     */
    @Nested
    @DisplayName("The double-space and asterisk delimiters")
    class DelimiterSemantics {

        /**
         * The asterisk delimiter carries each field whole, padding included. No source operand
         * holds an asterisk. The six lines at {@code app/cbl/CBSTM03A.CBL:L614},
         * {@code app/cbl/CBSTM03A.CBL:L621}, {@code app/cbl/CBSTM03A.CBL:L628},
         * {@code app/cbl/CBSTM03A.CBL:L687-L689}, {@code app/cbl/CBSTM03A.CBL:L700} and
         * {@code app/cbl/CBSTM03A.CBL:L711-L713} therefore reach 48, 41, 48, 23, 56 and 20
         * characters of content.
         */
        @Test
        @DisplayName("The asterisk delimiter carries each field whole at its composed length")
        void theAsteriskDelimiterCarriesEachFieldWholeAtItsComposedLength() {
            List<String> contents = contentsOf(renderRecords(1));

            assertThat(ACCOUNT_CELL_LENGTH).isEqualTo(48);
            assertThat(BALANCE_CELL_LENGTH).isEqualTo(41);
            assertThat(FICO_CELL_LENGTH).isEqualTo(48);
            assertThat(TRANSACTION_ID_CELL_LENGTH).isEqualTo(23);
            assertThat(DESCRIPTION_CELL_LENGTH).isEqualTo(56);
            assertThat(AMOUNT_CELL_LENGTH).isEqualTo(20);

            assertThat(contents.get(ACCOUNT_CELL_INDEX)).isEqualTo(EXPECTED_ACCOUNT_CELL)
                    .hasSize(ACCOUNT_CELL_LENGTH);
            assertThat(contents.get(BALANCE_CELL_INDEX)).isEqualTo(EXPECTED_BALANCE_CELL)
                    .hasSize(BALANCE_CELL_LENGTH);
            assertThat(contents.get(FICO_CELL_INDEX)).isEqualTo(EXPECTED_FICO_CELL)
                    .hasSize(FICO_CELL_LENGTH);
            assertThat(contents.get(FIRST_ROW_INDEX + ROW_TRANSACTION_ID_OFFSET))
                    .hasSize(TRANSACTION_ID_CELL_LENGTH);
            assertThat(contents.get(FIRST_ROW_INDEX + ROW_DESCRIPTION_OFFSET))
                    .isEqualTo(EXPECTED_DESCRIPTION_CELL).hasSize(DESCRIPTION_CELL_LENGTH);
            assertThat(contents.get(FIRST_ROW_INDEX + ROW_AMOUNT_OFFSET))
                    .isEqualTo(EXPECTED_AMOUNT_CELL).hasSize(AMOUNT_CELL_LENGTH);
        }

        /**
         * A short transaction identifier keeps its trailing spaces inside the paragraph element.
         * The {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L687-L689} reads
         * {@code ST-TRANID PIC X(16)} at {@code app/cbl/CBSTM03A.CBL:L133} under
         * {@code DELIMITED BY '*'}, so all sixteen characters transfer and the write pads the
         * record.
         */
        @Test
        @DisplayName("A short transaction identifier keeps its trailing spaces in the paragraph")
        void aShortTransactionIdentifierKeepsItsTrailingSpacesInTheParagraph() {
            TransactionRow shortRow =
                    new TransactionRow("TRNXA", DESCRIPTION, EDITED_ROW_AMOUNT);
            List<String> records = recordsOf(renderer.renderStatementAlert(
                    referenceContext(), List.of(shortRow), TOTAL));
            String cell = records.get(FIRST_ROW_INDEX + ROW_TRANSACTION_ID_OFFSET);

            assertThat(contentOf(cell))
                    .isEqualTo(OPEN_PARAGRAPH + padTo("TRNXA", ST_TRANID_WIDTH)
                            + CLOSE_PARAGRAPH)
                    .hasSize(TRANSACTION_ID_CELL_LENGTH);
            assertThat(cell).hasSize(RECORD_WIDTH);
        }

        /**
         * The double-space delimiter drops everything from the first pair of spaces. The
         * {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L570-L575} reads {@code ST-ADD1} under
         * {@code DELIMITED BY '  '} at {@code app/cbl/CBSTM03A.CBL:L571}, and the same phrase
         * governs {@code ST-ADD2} at {@code app/cbl/CBSTM03A.CBL:L579} and {@code ST-ADD3} at
         * {@code app/cbl/CBSTM03A.CBL:L587}.
         */
        @Test
        @DisplayName("The double-space delimiter drops everything from the first pair of spaces")
        void theDoubleSpaceDelimiterDropsEverythingFromTheFirstPairOfSpaces() {
            CardholderContext context = new CardholderContext(ASSEMBLED_NAME,
                    "12 MAIN ST  APT 4B", ADDRESS_LINE_2, ADDRESS_LINE_3, ACCOUNT_ID,
                    EDITED_BALANCE, FICO_SCORE);
            List<String> contents = contentsFor(context);

            assertThat(contents.get(ADDRESS_LINE_1_INDEX))
                    .isEqualTo(OPEN_PARAGRAPH + "12 MAIN ST" + TWO_SPACES + CLOSE_PARAGRAPH)
                    .doesNotContain("APT 4B");
            assertThat(contents.get(ADDRESS_LINE_2_INDEX)).isEqualTo(EXPECTED_ADDRESS_LINE_2);
        }

        /**
         * A cardholder with no middle name renders the first name alone in markup. The assembled
         * name of {@code app/cbl/CBSTM03A.CBL:L462-L469} places one literal space after every
         * component, so an all-spaces middle name leaves two adjacent spaces. The
         * {@code MOVE ST-NAME TO L23-NAME} at {@code app/cbl/CBSTM03A.CBL:L560} carries that pair
         * into the staging field, and {@code DELIMITED BY '  '} at
         * {@code app/cbl/CBSTM03A.CBL:L563} stops there, with the two literal spaces of
         * {@code app/cbl/CBSTM03A.CBL:L564} following. The text line at
         * {@code app/cbl/CBSTM03A.CBL:L490} carries the full 75-character field and belongs to
         * {@code PlainTextRendererTest}.
         */
        @Test
        @DisplayName("A cardholder with no middle name renders the first name alone in markup")
        void aCardholderWithNoMiddleNameRendersTheFirstNameAloneInMarkup() {
            List<String> contents = contentsFor(contextWithName(
                    NotificationRenderer.assembleName("JOHN", "", "PUBLIC")));

            assertThat(contents.get(NAME_LINE_INDEX))
                    .isEqualTo(L23_OPEN_PARAGRAPH + "JOHN" + TWO_SPACES + CLOSE_PARAGRAPH)
                    .doesNotContain("PUBLIC");
        }
    }

    /**
     * The fifty-character narrowing of the markup name. {@code MOVE ST-NAME TO L23-NAME} at
     * {@code app/cbl/CBSTM03A.CBL:L560} moves {@code ST-NAME PIC X(75)} at
     * {@code app/cbl/CBSTM03A.CBL:L91} into {@code L23-NAME PIC X(50)} at
     * {@code app/cbl/CBSTM03A.CBL:L220}. The text path keeps all 75 characters and belongs to
     * {@code PlainTextRendererTest}.
     */
    @Nested
    @DisplayName("The fifty-character markup name")
    class NameTruncation {

        /**
         * A name longer than the staging field keeps its leading 50 characters. The staging field
         * is {@code L23-NAME PIC X(50)} at {@code app/cbl/CBSTM03A.CBL:L220}.
         */
        @Test
        @DisplayName("A name longer than fifty characters keeps its leading fifty")
        void aNameLongerThanFiftyCharactersKeepsItsLeadingFifty() {
            String longName = "NAME-" + "A".repeat(70);
            String leadingFifty = longName.substring(0, L23_NAME_WIDTH);
            List<String> contents = contentsFor(contextWithName(longName));

            assertThat(longName).hasSize(75);
            assertThat(contents.get(NAME_LINE_INDEX))
                    .isEqualTo(L23_OPEN_PARAGRAPH + leadingFifty + TWO_SPACES + CLOSE_PARAGRAPH)
                    .doesNotContain(longName.substring(0, L23_NAME_WIDTH + 1));
        }

        /**
         * A name already at the staging width survives the {@code MOVE} at
         * {@code app/cbl/CBSTM03A.CBL:L560} whole.
         */
        @Test
        @DisplayName("A name already at fifty characters survives the move whole")
        void aNameAlreadyAtFiftyCharactersSurvivesTheMoveWhole() {
            String fiftyCharacterName = "FIFTY-" + "B".repeat(44);
            List<String> contents = contentsFor(contextWithName(fiftyCharacterName));

            assertThat(fiftyCharacterName).hasSize(L23_NAME_WIDTH);
            assertThat(contents.get(NAME_LINE_INDEX)).isEqualTo(
                    L23_OPEN_PARAGRAPH + fiftyCharacterName + TWO_SPACES + CLOSE_PARAGRAPH);
        }

        /**
         * A pair of spaces inside the leading 50 characters still ends the name, since
         * {@code DELIMITED BY '  '} at {@code app/cbl/CBSTM03A.CBL:L563} runs after the
         * {@code MOVE} at {@code app/cbl/CBSTM03A.CBL:L560}.
         */
        @Test
        @DisplayName("A pair of spaces inside the leading fifty still ends the name")
        void aPairOfSpacesInsideTheLeadingFiftyStillEndsTheName() {
            List<String> contents = contentsFor(contextWithName("ALPHA BETA  GAMMA DELTA"));

            assertThat(contents.get(NAME_LINE_INDEX))
                    .isEqualTo(L23_OPEN_PARAGRAPH + "ALPHA BETA" + TWO_SPACES + CLOSE_PARAGRAPH)
                    .doesNotContain("GAMMA");
        }
    }

    /**
     * The three basic-details labels of the markup path, at {@code app/cbl/CBSTM03A.CBL:L614},
     * {@code app/cbl/CBSTM03A.CBL:L621} and {@code app/cbl/CBSTM03A.CBL:L628}. Their text
     * counterparts at {@code app/cbl/CBSTM03A.CBL:L108}, {@code app/cbl/CBSTM03A.CBL:L112} and
     * {@code app/cbl/CBSTM03A.CBL:L117} hold 20 characters and belong to
     * {@code PlainTextRendererTest}.
     */
    @Nested
    @DisplayName("The three basic-details labels")
    class BasicDetailLabels {

        /**
         * Each markup label holds 24 characters and closes with a colon and one space. The
         * literals sit at {@code app/cbl/CBSTM03A.CBL:L614}, {@code app/cbl/CBSTM03A.CBL:L621} and
         * {@code app/cbl/CBSTM03A.CBL:L628}.
         */
        @Test
        @DisplayName("Each label holds twenty-four characters and closes with a trailing space")
        void eachLabelHoldsTwentyFourCharactersAndClosesWithATrailingSpace() {
            List<String> labels =
                    List.of(ACCOUNT_ID_LABEL, CURRENT_BALANCE_LABEL, FICO_SCORE_LABEL);
            List<String> contents = contentsOf(renderRecords(1));

            for (String label : labels) {
                assertThat(label).hasSize(BASIC_DETAILS_LABEL_WIDTH).endsWith(": ");
            }
            assertThat(BASIC_DETAILS_LABEL_WIDTH).isEqualTo(24);
            assertThat(contents.get(ACCOUNT_CELL_INDEX)).startsWith(ACCOUNT_ID_LABEL);
            assertThat(contents.get(BALANCE_CELL_INDEX)).startsWith(CURRENT_BALANCE_LABEL);
            assertThat(contents.get(FICO_CELL_INDEX)).startsWith(FICO_SCORE_LABEL);
        }
    }

    /**
     * The balance narrowing the markup cell repeats. {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L7} holds ten integer digits and
     * {@code ST-CURR-BAL PIC 9(9).99-} at {@code app/cbl/CBSTM03A.CBL:L113} holds nine, so the
     * {@code MOVE} at {@code app/cbl/CBSTM03A.CBL:L484} drops the high-order digit. The markup
     * cell reads the same edited field at {@code app/cbl/CBSTM03A.CBL:L622}.
     */
    @Nested
    @DisplayName("The nine-digit balance field")
    class BalanceNarrowing {

        /**
         * A balance at or above one billion renders in the markup cell without its high-order
         * digit. The operand of the {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L621-L623} is the
         * edited field of {@code app/cbl/CBSTM03A.CBL:L113}, so the markup cell and the text line
         * lose the same digit. {@code NotificationRendererTest} holds the assertion on the edit
         * itself.
         */
        @Test
        @DisplayName("A balance above one billion renders in markup without its high-order digit")
        void aBalanceAboveOneBillionRendersInMarkupWithoutItsHighOrderDigit() {
            String editedTenDigitBalance =
                    NotificationRenderer.editTrailingSign9(new BigDecimal("1234567890.12"));
            CardholderContext context = new CardholderContext(ASSEMBLED_NAME, ADDRESS_LINE_1,
                    ADDRESS_LINE_2, ADDRESS_LINE_3, ACCOUNT_ID, editedTenDigitBalance,
                    FICO_SCORE);
            List<String> contents = contentsFor(context);

            assertThat(contents.get(BALANCE_CELL_INDEX))
                    .isEqualTo(CURRENT_BALANCE_LABEL + editedTenDigitBalance + CLOSE_PARAGRAPH)
                    .contains("234567890.12")
                    .doesNotContain("1234567890.12")
                    .hasSize(BALANCE_CELL_LENGTH);
        }
    }

    /**
     * The Primary Account Number and the card verification value reach no markup record. The
     * markup lines at {@code app/cbl/CBSTM03A.CBL:L148-L223} declare no card-number field.
     *
     * <p>The source masks nothing. The card detail screen shows all sixteen characters at
     * {@code app/bms/COCRDSL.bms:L99}, and the card record stores {@code CARD-CVV-CD PIC 9(03)} in
     * the clear at {@code app/cpy/CVACT02Y.cpy:L7}.</p>
     */
    @Nested
    @DisplayName("The absent card number and card verification value")
    class MaskingAbsence {

        /**
         * A full card number reaches no markup record. The description field passes through
         * {@code DELIMITED BY '*'}, whose operand {@code ST-TRANDT} sits at
         * {@code app/cbl/CBSTM03A.CBL:L700}, so a masked value ends the field at its first mask
         * character. Every transaction identifier carries a letter, so no run of sixteen digits
         * appears anywhere in the output.
         */
        @Test
        @DisplayName("A full card number reaches no markup record")
        void aFullCardNumberReachesNoMarkupRecord() {
            String maskedCardNumber = PanMasker.maskCardNumber(CARD_NUMBER);

            assertThat(maskedCardNumber).hasSize(PanMasker.CARD_NUMBER_LENGTH);
            assertThat(maskedCardNumber.contains(CARD_NUMBER))
                    .as("the masked form carries the full card number").isFalse();

            TransactionRow row = new TransactionRow(transactionIdAt(0),
                    "PURCHASE CARD " + maskedCardNumber, EDITED_ROW_AMOUNT);
            List<String> records = recordsOf(renderer.renderStatementAlert(
                    referenceContext(), List.of(row), TOTAL));

            assertNoRecordCarries(records, "the full card number", CARD_NUMBER);
            assertNoRecordCarries(records, "the leading twelve card characters",
                    CARD_NUMBER.substring(0, PanMasker.CARD_NUMBER_LENGTH
                            - PanMasker.VISIBLE_DIGIT_COUNT));
            assertThat(contentsOf(records).get(FIRST_ROW_INDEX + ROW_DESCRIPTION_OFFSET))
                    .isEqualTo(OPEN_PARAGRAPH + "PURCHASE CARD " + CLOSE_PARAGRAPH);
        }

        /**
         * A card verification value reaches no markup record. No markup line of
         * {@code app/cbl/CBSTM03A.CBL:L148-L223} declares the field, and no entity of this service
         * holds it.
         */
        @Test
        @DisplayName("A card verification value reaches no markup record")
        void aCardVerificationValueReachesNoMarkupRecord() {
            String redacted = PanMasker.redactCardVerificationValue(CARD_VERIFICATION_VALUE);

            assertThat(redacted.contains(CARD_VERIFICATION_VALUE))
                    .as("the redacted form carries the verification value").isFalse();

            TransactionRow row = new TransactionRow(transactionIdAt(0),
                    "PURCHASE CARD " + redacted, EDITED_ROW_AMOUNT);
            List<String> records = recordsOf(renderer.renderStatementAlert(
                    referenceContext(), List.of(row), TOTAL));

            assertNoRecordCarries(records, "the card verification value",
                    CARD_VERIFICATION_VALUE);
        }

        /**
         * A fraud alert carries no full card number either. Its markup reuses the blocks at
         * {@code app/cbl/CBSTM03A.CBL:L509-L552} and {@code app/cbl/CBSTM03A.CBL:L439-L454}, and
         * neither names a card-number field.
         */
        @Test
        @DisplayName("A fraud alert carries no full card number")
        void aFraudAlertCarriesNoFullCardNumber() {
            List<String> records = recordsOf(renderer.renderFraudAlert(referenceContext(),
                    transactionIdAt(0), 87,
                    List.of("VELOCITY", PanMasker.maskCardNumber(CARD_NUMBER))));

            assertNoRecordCarries(records, "the full card number", CARD_NUMBER);
        }

        /**
         * Neither record the renderer reads declares a card number or a card verification value, so
         * no caller can route either into a markup record. The markup lines at
         * {@code app/cbl/CBSTM03A.CBL:L148-L223} declare no card-number field either.
         */
        @Test
        @DisplayName("Neither renderer input record declares a card field")
        void neitherRendererInputRecordDeclaresACardField() {
            assertThat(componentNamesOf(CardholderContext.class))
                    .as("components CardholderContext declares")
                    .isNotEmpty()
                    .noneMatch(HtmlRendererTest::namesACardField);
            assertThat(componentNamesOf(TransactionRow.class))
                    .as("components TransactionRow declares")
                    .isNotEmpty()
                    .noneMatch(HtmlRendererTest::namesACardField);
        }
    }

    /**
     * Markup characters supplied by a cardholder reach every record as an entity reference.
     *
     * <p>ADDITIVE. {@code app/cbl/CBSTM03A.CBL} escapes nothing, because a 3270 screen and a
     * fixed-width dataset carry no markup meaning. A rendering sent to a browser does, so
     * {@link HtmlRenderer} routes every value-bearing field through
     * {@link NotificationRenderer#escapeHtmlText(String)}. The tests below supply hostile values to
     * every interpolated field of both render operations and read the whole rendering back.</p>
     *
     * <p>The oracle counts raw markup characters. A benign rendering carries a known number of
     * them, all from the literals of the source, and a hostile rendering must carry exactly the
     * same number: every character the cardholder supplied has become an entity reference. That
     * comparison needs no second copy of the escaping rule.</p>
     */
    @Nested
    @DisplayName("The escaping of cardholder-supplied markup")
    class MarkupEscaping {

        /**
         * A hostile value in every statement field adds no raw markup character to the rendering,
         * and the element count therefore cannot grow.
         */
        @Test
        @DisplayName("Hostile values in every statement field add no raw markup character")
        void hostileValuesInEveryStatementFieldAddNoRawMarkupCharacter() {
            String benign = renderer.renderStatementAlert(referenceContext(), rows(1), TOTAL);
            String hostile = renderer.renderStatementAlert(hostileContext(), hostileRows(), TOTAL);

            assertNoRawMarkupCharacterGained(benign, hostile);
            assertEveryAmpersandOpensAnEntity(hostile);
        }

        /**
         * A hostile value in every fraud-alert field adds no raw markup character either. The alert
         * interpolates the cardholder fields, the transaction identifier and the joined rule
         * list.
         */
        @Test
        @DisplayName("Hostile values in every fraud-alert field add no raw markup character")
        void hostileValuesInEveryFraudAlertFieldAddNoRawMarkupCharacter() {
            String benign = renderer.renderFraudAlert(referenceContext(), transactionIdAt(0), 87,
                    List.of("VELOCITY"));
            String hostile = renderer.renderFraudAlert(hostileContext(), HOSTILE_VALUE, 87,
                    List.of(HOSTILE_VALUE));

            assertNoRawMarkupCharacterGained(benign, hostile);
            assertEveryAmpersandOpensAnEntity(hostile);
        }

        /**
         * Each interpolated field escapes on its own. One field at a time carries the hostile
         * value, so a field that skipped the escaping step fails under its own name rather than
         * hiding behind the others.
         */
        @Test
        @DisplayName("Each interpolated statement field escapes on its own")
        void eachInterpolatedStatementFieldEscapesOnItsOwn() {
            String benign = renderer.renderStatementAlert(referenceContext(), rows(1), TOTAL);

            for (Map.Entry<String, CardholderContext> field : hostileByField().entrySet()) {
                String hostile =
                        renderer.renderStatementAlert(field.getValue(), rows(1), TOTAL);

                assertNoRawMarkupCharacterGained(benign, hostile, field.getKey());
            }

            String hostileIdentifier = renderer.renderStatementAlert(referenceContext(),
                    List.of(new TransactionRow(HOSTILE_VALUE, DESCRIPTION, EDITED_ROW_AMOUNT)),
                    TOTAL);
            assertNoRawMarkupCharacterGained(benign, hostileIdentifier,
                    "the transaction identifier");

            String hostileDescription = renderer.renderStatementAlert(referenceContext(),
                    List.of(new TransactionRow(transactionIdAt(0), HOSTILE_VALUE,
                            EDITED_ROW_AMOUNT)), TOTAL);
            assertNoRawMarkupCharacterGained(benign, hostileDescription, "the description");

            String hostileAmount = renderer.renderStatementAlert(referenceContext(),
                    List.of(new TransactionRow(transactionIdAt(0), DESCRIPTION, HOSTILE_VALUE)),
                    TOTAL);
            assertNoRawMarkupCharacterGained(benign, hostileAmount, "the edited amount");
        }

        /**
         * The escaped form of a hostile value reaches the rendering, so the escaping replaced the
         * characters rather than dropping them. The description field carries the value whole with
         * its fixed-width pad, because it reaches the record through the asterisk delimiter.
         */
        @Test
        @DisplayName("The escaped form of a hostile description reaches its own record")
        void theEscapedFormOfAHostileDescriptionReachesItsOwnRecord() {
            List<String> contents = contentsOf(recordsOf(renderer.renderStatementAlert(
                    referenceContext(),
                    List.of(new TransactionRow(transactionIdAt(0), HOSTILE_VALUE,
                            EDITED_ROW_AMOUNT)),
                    TOTAL)));
            String pad = " ".repeat(
                    NotificationRenderer.ST_TRANDT_WIDTH - HOSTILE_VALUE.length());

            assertThat(contents.get(FIRST_ROW_INDEX + ROW_DESCRIPTION_OFFSET))
                    .as("the description record of a hostile row")
                    .isEqualTo(OPEN_PARAGRAPH + ESCAPED_HOSTILE_VALUE + pad + CLOSE_PARAGRAPH);
        }

        /** Builds a cardholder fixture whose every component carries the hostile value. */
        private CardholderContext hostileContext() {
            return new CardholderContext(HOSTILE_VALUE, HOSTILE_VALUE, HOSTILE_VALUE,
                    HOSTILE_VALUE, HOSTILE_VALUE, HOSTILE_VALUE, HOSTILE_VALUE);
        }

        /** Builds one detail row whose every component carries the hostile value. */
        private List<TransactionRow> hostileRows() {
            return List.of(new TransactionRow(HOSTILE_VALUE, HOSTILE_VALUE, HOSTILE_VALUE));
        }

        /**
         * Builds one cardholder fixture per component, each carrying the hostile value in that
         * component alone, keyed by the field it names.
         */
        private Map<String, CardholderContext> hostileByField() {
            Map<String, CardholderContext> byField = new LinkedHashMap<>();
            byField.put("the assembled name", new CardholderContext(HOSTILE_VALUE, ADDRESS_LINE_1,
                    ADDRESS_LINE_2, ADDRESS_LINE_3, ACCOUNT_ID, EDITED_BALANCE, FICO_SCORE));
            byField.put("address line one", new CardholderContext(ASSEMBLED_NAME, HOSTILE_VALUE,
                    ADDRESS_LINE_2, ADDRESS_LINE_3, ACCOUNT_ID, EDITED_BALANCE, FICO_SCORE));
            byField.put("address line two", new CardholderContext(ASSEMBLED_NAME, ADDRESS_LINE_1,
                    HOSTILE_VALUE, ADDRESS_LINE_3, ACCOUNT_ID, EDITED_BALANCE, FICO_SCORE));
            byField.put("address line three", new CardholderContext(ASSEMBLED_NAME, ADDRESS_LINE_1,
                    ADDRESS_LINE_2, HOSTILE_VALUE, ACCOUNT_ID, EDITED_BALANCE, FICO_SCORE));
            byField.put("the account identifier", new CardholderContext(ASSEMBLED_NAME,
                    ADDRESS_LINE_1, ADDRESS_LINE_2, ADDRESS_LINE_3, HOSTILE_VALUE, EDITED_BALANCE,
                    FICO_SCORE));
            byField.put("the edited balance", new CardholderContext(ASSEMBLED_NAME, ADDRESS_LINE_1,
                    ADDRESS_LINE_2, ADDRESS_LINE_3, ACCOUNT_ID, HOSTILE_VALUE, FICO_SCORE));
            byField.put("the credit score", new CardholderContext(ASSEMBLED_NAME, ADDRESS_LINE_1,
                    ADDRESS_LINE_2, ADDRESS_LINE_3, ACCOUNT_ID, EDITED_BALANCE, HOSTILE_VALUE));
            return byField;
        }
    }

    /**
     * Asserts a hostile rendering gained no raw markup character over a benign one, so no supplied
     * character reached the markup unescaped.
     *
     * @param benign  a rendering of the reference fixture
     * @param hostile a rendering carrying the hostile value
     */
    private static void assertNoRawMarkupCharacterGained(String benign, String hostile) {
        assertNoRawMarkupCharacterGained(benign, hostile, "a hostile value");
    }

    /**
     * Asserts a hostile rendering gained no raw markup character over a benign one, naming the
     * field under test.
     *
     * <p>The comparison is an upper bound rather than an equality. An escaped value is longer than
     * the value it replaces, so an assembled line can pass the hundred characters of
     * {@code FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47} and lose its tail to
     * the same truncation a COBOL {@code MOVE} performs. A rendering that lost a character of its
     * own markup carries no character a cardholder supplied, so the security property is the upper
     * bound.</p>
     *
     * <p>The ampersand is checked separately by
     * {@link #assertEveryAmpersandOpensAnEntity(String)}, because every escaped character
     * introduces one.</p>
     *
     * @param benign  a rendering of the reference fixture
     * @param hostile a rendering carrying the hostile value
     * @param field   a description of the field carrying the hostile value
     */
    private static void assertNoRawMarkupCharacterGained(String benign, String hostile,
            String field) {
        for (char markup : RAW_MARKUP_CHARACTERS) {
            assertThat(countOf(hostile, markup))
                    .as("raw %s characters after %s carried markup", markupName(markup), field)
                    .isLessThanOrEqualTo(countOf(benign, markup));
        }
        assertThat(recordsOf(hostile))
                .as("records after %s carried markup", field)
                .hasSameSizeAs(recordsOf(benign));
    }

    /**
     * Asserts every ampersand in a rendering opens one of the five entity references
     * {@link NotificationRenderer#escapeHtmlText(String)} writes. A bare ampersand would leave the
     * rendering able to carry an entity a cardholder chose.
     *
     * <p>The scan runs record by record and reads each record without its trailing pad. An entity
     * reference sitting at the hundredth character loses its tail to the record width, so a
     * trailing fragment that opens one of the five references satisfies the check.</p>
     *
     * @param rendered the output of one render operation
     */
    private static void assertEveryAmpersandOpensAnEntity(String rendered) {
        List<String> records = recordsOf(rendered);

        for (int index = 0; index < records.size(); index++) {
            String content = contentOf(records.get(index));
            for (int position = 0; position < content.length(); position++) {
                if (content.charAt(position) != '&') {
                    continue;
                }
                String tail = content.substring(position);
                boolean opensAnEntity = ENTITY_REFERENCES.stream().anyMatch(tail::startsWith);
                boolean cutByTheRecordWidth =
                        ENTITY_REFERENCES.stream().anyMatch(entity -> entity.startsWith(tail));

                assertThat(opensAnEntity || cutByTheRecordWidth)
                        .as("the ampersand at position %d of record %d of %d opens one of the "
                                + "five entity references", position + 1, index + 1, records.size())
                        .isTrue();
            }
        }
    }

    /**
     * Asserts a forbidden value reaches no record, naming the field type and the record index.
     *
     * <p>The subject of each assertion is a boolean, so a failure reports which record carried
     * something forbidden and prints neither the record nor the value it carried.</p>
     *
     * @param records   the rendered records, in write order
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

    /** Counts one character in a rendering. */
    private static int countOf(String rendered, char sought) {
        int found = 0;
        for (int position = 0; position < rendered.length(); position++) {
            if (rendered.charAt(position) == sought) {
                found++;
            }
        }
        return found;
    }

    /** Names one markup character for a failure description. */
    private static String markupName(char markup) {
        return switch (markup) {
            case '<' -> "opening angle bracket";
            case '>' -> "closing angle bracket";
            case '"' -> "double quotation mark";
            case '\'' -> "apostrophe";
            default -> "markup";
        };
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
     * Reports whether a component name reaches a card number or a card verification value.
     *
     * @param componentName one record component name
     * @return {@code true} when the name reaches a card value
     */
    private static boolean namesACardField(String componentName) {
        String folded = componentName.toLowerCase(Locale.ROOT);

        return folded.contains("card") || folded.contains("pan") || folded.contains("cvv")
                || folded.contains("verification");
    }
}

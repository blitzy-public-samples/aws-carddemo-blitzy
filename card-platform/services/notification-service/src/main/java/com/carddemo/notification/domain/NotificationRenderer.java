package com.carddemo.notification.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import java.util.List;

/**
 * Contract for rendering one cardholder alert in one output format, with the fixed-width
 * formatting helpers an implementation calls.
 *
 * <p>ADDITIVE. No COBOL program declares this abstraction. {@code app/cbl/CBSTM03A.CBL} writes
 * two formats from a single procedure division: the text records at
 * {@code app/cbl/CBSTM03A.CBL:L488-L502} and the markup records at
 * {@code app/cbl/CBSTM03A.CBL:L558-L669}. The helpers below carry the field assembly and the
 * numeric editing of {@code 5000-CREATE-STATEMENT} at
 * {@code app/cbl/CBSTM03A.CBL:L458-L504}.</p>
 *
 * <p>Four COBOL terms recur below. A Picture clause, written {@code PIC}, fixes a field's width
 * and form. A {@code Z} digit position renders a leading zero as a space. A trailing sign
 * position holds {@code '-'} for a negative value and a space otherwise. {@code DELIMITED BY}
 * names the character that ends each value a COBOL {@code STRING} statement contributes.</p>
 *
 * <p>Every implementation reports its own format through {@link #format()}. A third output
 * format needs one new implementation and one new {@link RenderedFormat} constant.</p>
 *
 * <p>A static interface method is not inherited, so an implementation reaches the helpers below
 * as {@code NotificationRenderer.pic(value, width)} or through a static import. The constants
 * are fields and are inherited, so an implementation names them directly.</p>
 *
 * <p>Two obligations bind every implementation. An implementation reporting
 * {@link RenderedFormat#HTML} routes every value-bearing field through
 * {@link #escapeHtmlText(String)} after {@link #pic(String, int)} has set the field width, so a
 * cardholder value cannot open an element, close one, or break out of an attribute. An
 * implementation of either format reads the fields of {@link CardholderContext} and
 * {@link TransactionRow} through their accessors and never through {@code toString()}: both
 * records redact their rendering, because both carry personal data.</p>
 */
public interface NotificationRenderer {

    /**
     * Separator placed between rendered records.
     *
     * <p>The source writes one fixed-length record per line, through
     * {@code WRITE FD-STMTFILE-REC} at {@code app/cbl/CBSTM03A.CBL:L488-L502} and
     * {@code WRITE FD-HTMLFILE-REC} at {@code app/cbl/CBSTM03A.CBL:L558-L669}. Records are
     * joined with this constant, not with the separator of the host operating system.</p>
     */
    String LINE_SEPARATOR = "\n";

    /**
     * Text a diagnostic rendering carries in place of a component value. ADDITIVE, with no COBOL
     * ancestor.
     *
     * <p>{@link CardholderContext#toString()} and {@link TransactionRow#toString()} carry this
     * text in place of every component that names a cardholder, an account, or an amount. Each
     * rendering reports the width the component holds, so a fixed-width fault stays diagnosable.
     * The rendered statement itself carries every value, matching the source records.</p>
     */
    String REDACTED = "<redacted>";

    /**
     * Width of {@code ST-NAME}, {@code PIC X(75)} at {@code app/cbl/CBSTM03A.CBL:L91}.
     * {@link #assembleName(String, String, String)} produces a value of this width.
     */
    int ST_NAME_WIDTH = 75;

    /**
     * Width of {@code ST-ADD1}, {@code PIC X(50)} at {@code app/cbl/CBSTM03A.CBL:L94}. The
     * {@code MOVE CUST-ADDR-LINE-1 TO ST-ADD1} at {@code app/cbl/CBSTM03A.CBL:L470} fills it.
     */
    int ST_ADD1_WIDTH = 50;

    /**
     * Width of {@code ST-ADD2}, {@code PIC X(50)} at {@code app/cbl/CBSTM03A.CBL:L97}. The
     * {@code MOVE CUST-ADDR-LINE-2 TO ST-ADD2} at {@code app/cbl/CBSTM03A.CBL:L471} fills it.
     */
    int ST_ADD2_WIDTH = 50;

    /**
     * Width of {@code ST-ADD3}, {@code PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L100}.
     * {@link #assembleAddress3(String, String, String, String)} produces a value of this width.
     */
    int ST_ADD3_WIDTH = 80;

    /**
     * Width of {@code ST-ACCT-ID}, {@code PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L109}. The
     * {@code MOVE ACCT-ID TO ST-ACCT-ID} at {@code app/cbl/CBSTM03A.CBL:L483} fills it, and the
     * markup path repeats the account identifier at {@code app/cbl/CBSTM03A.CBL:L529}.
     */
    int ST_ACCT_ID_WIDTH = 20;

    /**
     * Width of {@code ST-FICO-SCORE}, {@code PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L118}.
     * The {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE} at
     * {@code app/cbl/CBSTM03A.CBL:L485} fills it.
     */
    int ST_FICO_SCORE_WIDTH = 20;

    /**
     * Width of {@code ST-TRANID}, {@code PIC X(16)} at {@code app/cbl/CBSTM03A.CBL:L133}. The
     * {@code MOVE TRNX-ID TO ST-TRANID} at {@code app/cbl/CBSTM03A.CBL:L676} fills it.
     */
    int ST_TRANID_WIDTH = 16;

    /**
     * Width of {@code ST-TRANDT}, {@code PIC X(49)} at {@code app/cbl/CBSTM03A.CBL:L135}.
     *
     * <p>{@code TRNX-DESC} is {@code PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28}, so the
     * {@code MOVE TRNX-DESC TO ST-TRANDT} at {@code app/cbl/CBSTM03A.CBL:L677} drops the last 51
     * characters. The loss belongs to this rendering width alone.</p>
     */
    int ST_TRANDT_WIDTH = 49;

    /**
     * Width of an edited amount: nine digit positions, a decimal point, two decimal digits and
     * one trailing sign position, totalling 13 characters.
     *
     * <p>Three source fields carry that form. {@code ST-CURR-BAL PIC 9(9).99-} sits at
     * {@code app/cbl/CBSTM03A.CBL:L113}, {@code ST-TRANAMT PIC Z(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L137} and {@code ST-TOTAL-TRAMT PIC Z(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L142}.</p>
     *
     * <p>The digit count comes from {@link PicClause#TRAN_AMT_PRECISION}, which matches
     * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}. The two extra
     * characters are the decimal point and the sign position.</p>
     */
    int EDITED_AMOUNT_WIDTH = PicClause.TRAN_AMT_PRECISION + 2;

    /**
     * Reports which output format this renderer produces.
     *
     * @return the format this renderer produces, never {@code null}
     */
    RenderedFormat format();

    /**
     * Renders a statement alert covering one cardholder and the supplied transactions.
     *
     * <p>Reproduces the record sequence of {@code 5000-CREATE-STATEMENT} at
     * {@code app/cbl/CBSTM03A.CBL:L458-L504}, one detail record per row from
     * {@code 6000-WRITE-TRANS} at {@code app/cbl/CBSTM03A.CBL:L675-L679}, and the total from
     * {@code ST-TOTAL-TRAMT} at {@code app/cbl/CBSTM03A.CBL:L142}.</p>
     *
     * <p>The result carries every record in one string, separated by {@link #LINE_SEPARATOR}.
     * An empty row list renders the surrounding records and no detail record.</p>
     *
     * @param context the cardholder fields, already assembled and already edited; must not be
     *                {@code null}
     * @param rows    the detail rows in the order they render; must not be {@code null}, and no
     *                element may be {@code null}
     * @param total   the transaction total, matching {@code WS-TOTAL-AMT PIC S9(9)V99} at
     *                {@code app/cbl/CBSTM03A.CBL:L65}; must not be {@code null}
     * @return the rendered alert
     */
    String renderStatementAlert(CardholderContext context, List<TransactionRow> rows,
                                BigDecimal total);

    /**
     * Renders a fraud alert covering one flagged transaction.
     *
     * <p>ADDITIVE. {@code app/cbl/CBSTM03A.CBL} carries no fraud concept, so this operation has
     * no COBOL ancestor. The cardholder fields it renders are the fields of
     * {@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504}.</p>
     *
     * <p>The parameters match the payload of the {@code FraudFlagged} event, which carries no
     * amount and no card number. The result carries every record in one string, separated by
     * {@link #LINE_SEPARATOR}.</p>
     *
     * @param context        the cardholder fields, already assembled and already edited; must
     *                       not be {@code null}
     * @param transactionId  the identifier of the flagged transaction; must not be {@code null}
     * @param riskScore      the score the fraud service assigned
     * @param triggeredRules the identifiers of the rules that fired; must not be {@code null},
     *                       and no element may be {@code null}
     * @return the rendered alert
     */
    String renderFraudAlert(CardholderContext context, String transactionId, int riskScore,
                            List<String> triggeredRules);

    /**
     * Escapes the five characters that carry meaning in markup, so a value renders as text.
     *
     * <p>ADDITIVE. {@code app/cbl/CBSTM03A.CBL} escapes nothing. Its markup path moves cardholder
     * values straight into {@code FD-HTMLFILE-REC} at
     * {@code app/cbl/CBSTM03A.CBL:L558-L669}, because a 3270 screen and a fixed-width dataset
     * carry no markup meaning. A rendering this service sends to a browser or an electronic mail
     * client does, so an implementation reporting {@link RenderedFormat#HTML} must route every
     * value-bearing field through this method. {@link RenderedFormat#PLAIN_TEXT} does not: an
     * escaped ampersand would change the fixed-width text the source writes.</p>
     *
     * <p>The five replacements are the ones that end an element, open an element, close an
     * attribute value and end an entity: {@code &} first so a later replacement is not escaped
     * twice, then {@code <}, {@code >}, {@code "} and {@code '}. A field of spaces, a field of
     * digits and a field of letters are returned unchanged, so escaping does not alter the width
     * of a normal field.</p>
     *
     * <p>Escaping runs after {@link #pic(String, int)}, never before: escaping first would push
     * characters past the field width and the fixed-width copy would then split an entity.</p>
     *
     * @param value the value to render as text, or {@code null} for an empty result
     * @return the value with every markup character replaced by its entity, and the empty string
     *         for {@code null}
     */
    static String escapeHtmlText(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(value.length());

        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }

        return escaped.toString();
    }

    /**
     * Renders a value at a fixed width, reproducing a {@code PIC X(n)} field.
     *
     * <p>A shorter value gains trailing spaces and a longer value loses its tail, which is what
     * a COBOL {@code MOVE} into an alphanumeric field does. The result holds exactly
     * {@code width} characters for every input, including {@code null}.</p>
     *
     * <p>A {@code null} value renders as spaces rather than raising an exception.</p>
     *
     * @param value the value to render, or {@code null} for an all-spaces field
     * @param width the field width, taken from a Picture clause; must not be negative
     * @return a string of exactly {@code width} characters
     * @throws IllegalArgumentException if {@code width} is negative
     */
    static String pic(String value, int width) {
        if (width < 0) {
            throw new IllegalArgumentException("width must not be negative: width=" + width);
        }
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Edits an amount into a {@code PIC 9(9).99-} field, reproducing {@code ST-CURR-BAL} at
     * {@code app/cbl/CBSTM03A.CBL:L113}.
     *
     * <p>The result holds exactly {@value #EDITED_AMOUNT_WIDTH} characters: nine digit
     * positions, a decimal point, two decimal digits and one trailing sign position. Every
     * digit position renders a digit, so 1234.56 renders as {@code 000001234.56} followed by
     * the sign position. {@link #editTrailingSignZ(BigDecimal)} renders the same amount with
     * leading spaces instead of leading zeros.</p>
     *
     * <p>The sign position holds {@code '-'} when the field holds a negative value and a space
     * otherwise. The amount passes through
     * {@link CobolDecimal#truncateToPictureField(BigDecimal, int, int)}, which truncates the
     * digits past the second decimal toward zero and drops any digit above the ninth.</p>
     *
     * @param amount the amount to edit; must not be {@code null}
     * @return the edited amount, exactly {@value #EDITED_AMOUNT_WIDTH} characters
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    static String editTrailingSign9(BigDecimal amount) {
        BigDecimal held = heldValue(amount);
        String digits = magnitudeDigits(held);
        int integerPositions = PicClause.TRAN_AMT_PRECISION - PicClause.TRAN_AMT_SCALE;

        return digits.substring(0, integerPositions) + '.'
                + digits.substring(integerPositions) + signPosition(held);
    }

    /**
     * Edits an amount into a {@code PIC Z(9).99-} field, reproducing {@code ST-TRANAMT} at
     * {@code app/cbl/CBSTM03A.CBL:L137} and {@code ST-TOTAL-TRAMT} at
     * {@code app/cbl/CBSTM03A.CBL:L142}.
     *
     * <p>The result holds exactly {@value #EDITED_AMOUNT_WIDTH} characters, in the same form as
     * {@link #editTrailingSign9(BigDecimal)}. A {@code Z} digit position renders a leading zero
     * as a space, so 1234.56 renders as five spaces, then {@code 1234.56}, then the sign
     * position.</p>
     *
     * <p>Suppression stops at the first digit that is not zero, or at the decimal point,
     * whichever comes first. An amount of 0.00 therefore renders as nine spaces, then
     * {@code .00}, then the sign position.</p>
     *
     * <p>The sign position and the truncation match {@link #editTrailingSign9(BigDecimal)}.</p>
     *
     * @param amount the amount to edit; must not be {@code null}
     * @return the edited amount, exactly {@value #EDITED_AMOUNT_WIDTH} characters
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    static String editTrailingSignZ(BigDecimal amount) {
        BigDecimal held = heldValue(amount);
        String digits = magnitudeDigits(held);
        int integerPositions = PicClause.TRAN_AMT_PRECISION - PicClause.TRAN_AMT_SCALE;

        StringBuilder integerPart = new StringBuilder(digits.substring(0, integerPositions));
        for (int position = 0; position < integerPositions; position++) {
            if (integerPart.charAt(position) != '0') {
                break;
            }
            integerPart.setCharAt(position, ' ');
        }

        return integerPart.toString() + '.'
                + digits.substring(integerPositions) + signPosition(held);
    }

    /**
     * Assembles the three customer name components into {@code ST-NAME}, reproducing the
     * {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L462-L469}.
     *
     * <p>Each component is {@code DELIMITED BY ' '}, so each contributes only the characters
     * before its first space. A two-word middle name contributes its first word only:
     * {@code "Ann Marie"} contributes {@code "Ann"}.</p>
     *
     * <p>The source repeats {@code ' ' DELIMITED BY SIZE} three times, so one literal space
     * follows each component, including the last. The result holds exactly
     * {@value #ST_NAME_WIDTH} characters. A {@code null} or blank component contributes no
     * characters and raises no exception.</p>
     *
     * @param first  {@code CUST-FIRST-NAME}, from the {@code CUSTREC} copybook that
     *               {@code app/cbl/CBSTM03A.CBL:L55} copies
     * @param middle {@code CUST-MIDDLE-NAME}, from the same copybook
     * @param last   {@code CUST-LAST-NAME}, from the same copybook
     * @return the assembled name, exactly {@value #ST_NAME_WIDTH} characters
     */
    static String assembleName(String first, String middle, String last) {
        String assembled = beforeFirstSpace(first) + ' '
                + beforeFirstSpace(middle) + ' '
                + beforeFirstSpace(last) + ' ';

        return pic(assembled, ST_NAME_WIDTH);
    }

    /**
     * Assembles the four address components into {@code ST-ADD3}, reproducing the
     * {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L472-L481}.
     *
     * <p>Each component is {@code DELIMITED BY ' '} and contributes only the characters before
     * its first space. One literal space follows each of the four. A city line holding
     * {@code "New York"} contributes {@code "New"}.</p>
     *
     * <p>The result holds exactly {@value #ST_ADD3_WIDTH} characters. A {@code null} or blank
     * component contributes no characters and raises no exception.</p>
     *
     * @param line3       {@code CUST-ADDR-LINE-3}, from the {@code CUSTREC} copybook that
     *                    {@code app/cbl/CBSTM03A.CBL:L55} copies
     * @param stateCode   {@code CUST-ADDR-STATE-CD}, from the same copybook
     * @param countryCode {@code CUST-ADDR-COUNTRY-CD}, from the same copybook
     * @param zip         {@code CUST-ADDR-ZIP}, from the same copybook
     * @return the assembled line, exactly {@value #ST_ADD3_WIDTH} characters
     */
    static String assembleAddress3(String line3, String stateCode, String countryCode,
                                   String zip) {
        String assembled = beforeFirstSpace(line3) + ' '
                + beforeFirstSpace(stateCode) + ' '
                + beforeFirstSpace(countryCode) + ' '
                + beforeFirstSpace(zip) + ' ';

        return pic(assembled, ST_ADD3_WIDTH);
    }

    /**
     * Truncates an amount into the digit positions an edited field holds.
     *
     * <p>Delegates to {@link CobolDecimal#truncateToPictureField(BigDecimal, int, int)}, which
     * truncates toward zero and offers no rounding mode. The result carries at most
     * {@value PicClause#TRAN_AMT_PRECISION} digits and a scale of
     * {@value PicClause#TRAN_AMT_SCALE}.</p>
     *
     * @param amount the amount being stored; must not be {@code null}
     * @return the amount as the edited field holds it
     */
    private static BigDecimal heldValue(BigDecimal amount) {
        return CobolDecimal.truncateToPictureField(
                amount, PicClause.TRAN_AMT_PRECISION, PicClause.TRAN_AMT_SCALE);
    }

    /**
     * Returns the magnitude of a held value as {@value PicClause#TRAN_AMT_PRECISION} digits,
     * padded on the left with zeros and carrying no sign.
     *
     * @param heldValue the output of {@link #heldValue(BigDecimal)}
     * @return exactly {@value PicClause#TRAN_AMT_PRECISION} digit characters
     */
    private static String magnitudeDigits(BigDecimal heldValue) {
        String digits = heldValue.abs().unscaledValue().toString();
        if (digits.length() >= PicClause.TRAN_AMT_PRECISION) {
            return digits.substring(digits.length() - PicClause.TRAN_AMT_PRECISION);
        }

        return "0".repeat(PicClause.TRAN_AMT_PRECISION - digits.length()) + digits;
    }

    /**
     * Returns the trailing sign position for a held value.
     *
     * <p>The position reflects the value the field holds, so an amount whose magnitude
     * truncates to zero renders a space.</p>
     *
     * @param heldValue the output of {@link #heldValue(BigDecimal)}
     * @return {@code '-'} for a negative value, a space for zero and for a positive value
     */
    private static char signPosition(BigDecimal heldValue) {
        return heldValue.signum() < 0 ? '-' : ' ';
    }

    /**
     * Returns the characters of a component before its first space, reproducing
     * {@code DELIMITED BY ' '}.
     *
     * <p>A component with no space contributes every character. A {@code null} component and a
     * component starting with a space both contribute none.</p>
     *
     * @param component one sending field of a COBOL {@code STRING} statement, or {@code null}
     * @return the characters before the first space, never {@code null}
     */
    private static String beforeFirstSpace(String component) {
        if (component == null) {
            return "";
        }
        int firstSpace = component.indexOf(' ');

        return firstSpace < 0 ? component : component.substring(0, firstSpace);
    }

    /**
     * The output formats this service renders.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL} declares one file for each, at
     * {@code app/cbl/CBSTM03A.CBL:L44-L47}. A third format adds one constant here and one
     * implementation of {@link NotificationRenderer}.</p>
     */
    enum RenderedFormat {

        /**
         * Fixed-width text, matching {@code FD-STMTFILE-REC PIC X(80)} at
         * {@code app/cbl/CBSTM03A.CBL:L45}.
         */
        PLAIN_TEXT,

        /**
         * Markup, matching {@code FD-HTMLFILE-REC PIC X(100)} at
         * {@code app/cbl/CBSTM03A.CBL:L47}.
         */
        HTML
    }

    /**
     * The cardholder fields both formats read, assembled once before either renderer runs.
     *
     * <p>{@code 5000-CREATE-STATEMENT} fills these fields at
     * {@code app/cbl/CBSTM03A.CBL:L462-L485}, then the markup path reads them back at
     * {@code app/cbl/CBSTM03A.CBL:L560}, {@code app/cbl/CBSTM03A.CBL:L571},
     * {@code app/cbl/CBSTM03A.CBL:L579}, {@code app/cbl/CBSTM03A.CBL:L587},
     * {@code app/cbl/CBSTM03A.CBL:L615}, {@code app/cbl/CBSTM03A.CBL:L622} and
     * {@code app/cbl/CBSTM03A.CBL:L629}.</p>
     *
     * <p>The canonical constructor normalises every component to its exact width through
     * {@link #pic(String, int)}. A {@code null} component becomes spaces, so no renderer
     * receives a short value, an over-long value, or {@code null}.</p>
     *
     * <p>The customer components trace to {@code CUSTREC}, which
     * {@code app/cbl/CBSTM03A.CBL:L55} copies.</p>
     *
     * @param assembledName        {@code ST-NAME} at {@code app/cbl/CBSTM03A.CBL:L91}, normally
     *                             the output of
     *                             {@link #assembleName(String, String, String)}, held at
     *                             {@value #ST_NAME_WIDTH} characters
     * @param addressLine1         {@code ST-ADD1} at {@code app/cbl/CBSTM03A.CBL:L94}, held at
     *                             {@value #ST_ADD1_WIDTH} characters
     * @param addressLine2         {@code ST-ADD2} at {@code app/cbl/CBSTM03A.CBL:L97}, held at
     *                             {@value #ST_ADD2_WIDTH} characters
     * @param addressLine3         {@code ST-ADD3} at {@code app/cbl/CBSTM03A.CBL:L100}, normally
     *                             the output of
     *                             {@link #assembleAddress3(String, String, String, String)},
     *                             held at {@value #ST_ADD3_WIDTH} characters
     * @param accountId            {@code ST-ACCT-ID} at {@code app/cbl/CBSTM03A.CBL:L109}, held
     *                             at {@value #ST_ACCT_ID_WIDTH} characters
     * @param editedCurrentBalance {@code ST-CURR-BAL} at {@code app/cbl/CBSTM03A.CBL:L113},
     *                             already edited by
     *                             {@link #editTrailingSign9(BigDecimal)} to match the
     *                             {@code MOVE ACCT-CURR-BAL TO ST-CURR-BAL} at
     *                             {@code app/cbl/CBSTM03A.CBL:L484}, held at
     *                             {@value #EDITED_AMOUNT_WIDTH} characters
     * @param ficoScore            {@code ST-FICO-SCORE} at {@code app/cbl/CBSTM03A.CBL:L118},
     *                             held at {@value #ST_FICO_SCORE_WIDTH} characters
     */
    record CardholderContext(String assembledName,
                             String addressLine1,
                             String addressLine2,
                             String addressLine3,
                             String accountId,
                             String editedCurrentBalance,
                             String ficoScore) {

        /** Normalises every component to the width its source field declares. */
        public CardholderContext {
            assembledName = pic(assembledName, ST_NAME_WIDTH);
            addressLine1 = pic(addressLine1, ST_ADD1_WIDTH);
            addressLine2 = pic(addressLine2, ST_ADD2_WIDTH);
            addressLine3 = pic(addressLine3, ST_ADD3_WIDTH);
            accountId = pic(accountId, ST_ACCT_ID_WIDTH);
            editedCurrentBalance = pic(editedCurrentBalance, EDITED_AMOUNT_WIDTH);
            ficoScore = pic(ficoScore, ST_FICO_SCORE_WIDTH);
        }

        /**
         * Renders the type and the component count, and no component value.
         *
         * <p>Every component of this record is personal data: the cardholder name, three address
         * lines, the account identifier, the current balance and the credit score. A generated
         * record rendering carries all seven, and a rendering reaches a log line, an exception
         * message or a debugger view without a caller intending it. This override closes that
         * path. A renderer reads the fields through their accessors.</p>
         *
         * @return a fixed description carrying no cardholder value
         */
        @Override
        public String toString() {
            return "CardholderContext[7 cardholder fields redacted]";
        }
    }

    /**
     * One transaction detail row, as both formats read it.
     *
     * <p>{@code 6000-WRITE-TRANS} fills these three fields at
     * {@code app/cbl/CBSTM03A.CBL:L676-L678}, then the markup path reads them back at
     * {@code app/cbl/CBSTM03A.CBL:L688}, {@code app/cbl/CBSTM03A.CBL:L700} and
     * {@code app/cbl/CBSTM03A.CBL:L712}.</p>
     *
     * <p>The canonical constructor normalises every component to its exact width through
     * {@link #pic(String, int)}. A {@code null} component becomes spaces, and a description
     * longer than {@value #ST_TRANDT_WIDTH} characters loses its tail.</p>
     *
     * @param transactionId {@code ST-TRANID} at {@code app/cbl/CBSTM03A.CBL:L133}, held at
     *                      {@value #ST_TRANID_WIDTH} characters
     * @param description   {@code ST-TRANDT} at {@code app/cbl/CBSTM03A.CBL:L135}, held at
     *                      {@value #ST_TRANDT_WIDTH} characters. {@code TRNX-DESC} is
     *                      {@code PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28}, so the
     *                      {@code MOVE} at {@code app/cbl/CBSTM03A.CBL:L677} drops 51
     *                      characters.
     * @param editedAmount  {@code ST-TRANAMT} at {@code app/cbl/CBSTM03A.CBL:L137}, already
     *                      edited by {@link #editTrailingSignZ(BigDecimal)}, held at
     *                      {@value #EDITED_AMOUNT_WIDTH} characters
     */
    record TransactionRow(String transactionId, String description, String editedAmount) {

        /** Normalises every component to the width its source field declares. */
        public TransactionRow {
            transactionId = pic(transactionId, ST_TRANID_WIDTH);
            description = pic(description, ST_TRANDT_WIDTH);
            editedAmount = pic(editedAmount, EDITED_AMOUNT_WIDTH);
        }

        /**
         * Renders the type, the transaction identifier and the component count, and no other
         * component value.
         *
         * <p>The description and the amount describe a cardholder's spending, so a generated
         * record rendering places both in any log line or exception message that names the row.
         * The transaction identifier stays: it names the row under discussion and identifies no
         * person on its own.</p>
         *
         * @return a description carrying the transaction identifier and no other value
         */
        @Override
        public String toString() {
            return "TransactionRow[transactionId=" + transactionId
                    + ", description and amount redacted]";
        }
    }
}

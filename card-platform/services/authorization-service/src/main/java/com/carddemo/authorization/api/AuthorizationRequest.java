package com.carddemo.authorization.api;

import com.carddemo.cobol.CobolDateValidator;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.NumvalParser;
import com.carddemo.cobol.PicClause;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body of the synchronous authorization call.
 *
 * <p>Thirteen components carry the daily transaction record declared at
 * {@code app/cpy/CVTRA06Y.cpy:L4-L17}. The fourteenth carries {@code XREF-ACCT-ID PIC 9(11)} from
 * {@code app/cpy/CVACT03Y.cpy:L7}. The set matches the fields the online transaction-add program
 * stores at {@code app/cbl/COTRN02C.cbl:L451-L465}. The trailing {@code FILLER PIC X(20)} at
 * {@code app/cpy/CVTRA06Y.cpy:L18} is omitted.
 *
 * <p>Every component holds text. Three derived accessors turn that text into the forms the decision
 * rules read: {@link #canonicalAccountId()}, {@link #canonicalCardNumber()} and
 * {@link #amountValue()}. Each returns {@code null} when its component is absent, so an absent
 * value stays absent.
 *
 * <p>A caller supplies an account identifier or a card number.
 * {@code app/cbl/COTRN02C.cbl:L195-L230} branches on the same two fields and reports
 * {@value #IDENTIFIER_REQUIRED_MESSAGE} when neither arrives. A component filled with spaces counts
 * as absent, matching the {@code NOT = SPACES AND LOW-VALUES} test at
 * {@code app/cbl/COTRN02C.cbl:L196}.
 *
 * <p>Both timestamps are tested over their first ten characters against
 * {@link CobolDateValidator#TOLERANT_POLICY_DATE_MASK}. That policy accepts a date reporting
 * severity {@code 0000} and also one reporting message number {@code 2513}, as
 * {@code app/cbl/COTRN02C.cbl:L397-L400} and {@code app/cbl/COTRN02C.cbl:L417-L420} do. The
 * {@code 2513} branch is catalogued in {@code card-platform/docs/business-rule-flags.md}.
 *
 * @param transactionId           identifier of the transaction, from
 *                                {@code DALYTRAN-ID PIC X(16)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L5}. Optional, and at most sixteen
 *                                characters.
 * @param transactionTypeCode     type code of the transaction, from
 *                                {@code DALYTRAN-TYPE-CD PIC X(02)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L6}. At most two characters.
 * @param transactionCategoryCode category code of the transaction, from
 *                                {@code DALYTRAN-CAT-CD PIC 9(04)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L7}. At most four characters.
 * @param source                  channel that captured the transaction, from
 *                                {@code DALYTRAN-SOURCE PIC X(10)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L8}. At most ten characters.
 * @param description             description of the transaction, from
 *                                {@code DALYTRAN-DESC PIC X(100)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L9}. At most one hundred characters.
 * @param amount                  amount as the caller supplied it, from
 *                                {@code DALYTRAN-AMT PIC S9(09)V99} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L10}. Required, and twelve characters
 *                                shaped by {@value #AMOUNT_PATTERN}. {@link #amountValue()}
 *                                returns the value those characters carry.
 * @param merchantId              identifier of the merchant, from
 *                                {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L11}. Required, and exactly nine
 *                                digits.
 * @param merchantName            name of the merchant, from
 *                                {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L12}. At most fifty characters.
 * @param merchantCity            city of the merchant, from
 *                                {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L13}. At most fifty characters.
 * @param merchantZip             postal code of the merchant, from
 *                                {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L14}. At most ten characters.
 * @param cardNumber              card number, from {@code DALYTRAN-CARD-NUM PIC X(16)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L15}. One to sixteen digits, supplied
 *                                when {@code accountId} is absent.
 *                                {@link #canonicalCardNumber()} returns it at its stored width.
 * @param originTimestamp         moment the transaction was captured, from
 *                                {@code DALYTRAN-ORIG-TS PIC X(26)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L16}. Required, and shaped by
 *                                {@value #ORIGIN_TIMESTAMP_PATTERN}.
 * @param processingTimestamp     moment the transaction was posted, from
 *                                {@code DALYTRAN-PROC-TS PIC X(26)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L17}. Optional, and shaped by
 *                                {@value #PROCESSING_TIMESTAMP_PATTERN} when supplied.
 * @param accountId               account identifier, from {@code XREF-ACCT-ID PIC 9(11)} at
 *                                {@code app/cpy/CVACT03Y.cpy:L7}. One to eleven digits, supplied
 *                                when {@code cardNumber} is absent.
 *                                {@link #canonicalAccountId()} returns it at its stored width.
 */
public record AuthorizationRequest(

        @Size(max = PicClause.DALYTRAN_ID_WIDTH)
        String transactionId,

        @Size(max = PicClause.DALYTRAN_TYPE_CD_WIDTH)
        String transactionTypeCode,

        @Size(max = PicClause.DALYTRAN_CAT_CD_WIDTH)
        String transactionCategoryCode,

        @Size(max = PicClause.DALYTRAN_SOURCE_WIDTH)
        String source,

        @Size(max = PicClause.DALYTRAN_DESC_WIDTH)
        String description,

        @NotBlank(message = AMOUNT_FORMAT_MESSAGE)
        @Pattern(regexp = AMOUNT_PATTERN, message = AMOUNT_FORMAT_MESSAGE)
        String amount,

        @NotBlank(message = MERCHANT_ID_NOT_NUMERIC_MESSAGE)
        @Pattern(regexp = MERCHANT_ID_PATTERN, message = MERCHANT_ID_NOT_NUMERIC_MESSAGE)
        String merchantId,

        @Size(max = PicClause.DALYTRAN_MERCHANT_NAME_WIDTH)
        String merchantName,

        @Size(max = PicClause.DALYTRAN_MERCHANT_CITY_WIDTH)
        String merchantCity,

        @Size(max = PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH)
        String merchantZip,

        @Pattern(regexp = CARD_NUMBER_PATTERN, message = CARD_NUMBER_NOT_NUMERIC_MESSAGE)
        String cardNumber,

        @NotBlank(message = ORIGIN_DATE_FORMAT_MESSAGE)
        @Pattern(regexp = ORIGIN_TIMESTAMP_PATTERN, message = ORIGIN_DATE_FORMAT_MESSAGE)
        String originTimestamp,

        @Pattern(regexp = PROCESSING_TIMESTAMP_PATTERN, message = PROCESSING_DATE_FORMAT_MESSAGE)
        String processingTimestamp,

        @Pattern(regexp = ACCOUNT_ID_PATTERN, message = ACCOUNT_ID_NOT_NUMERIC_MESSAGE)
        String accountId) {

    // The nine rejection texts of app/cbl/COTRN02C.cbl, each reproduced character for character
    // from the paragraph that moves it into WS-MESSAGE.

    /**
     * Rejection text for an account identifier that is not all digits, from
     * {@code app/cbl/COTRN02C.cbl:L199}.
     */
    public static final String ACCOUNT_ID_NOT_NUMERIC_MESSAGE = "Account ID must be Numeric...";

    /**
     * Rejection text for a card number that is not all digits, from
     * {@code app/cbl/COTRN02C.cbl:L213}.
     */
    public static final String CARD_NUMBER_NOT_NUMERIC_MESSAGE = "Card Number must be Numeric...";

    /**
     * Rejection text for a request carrying neither identifier, from the {@code WHEN OTHER} branch
     * at {@code app/cbl/COTRN02C.cbl:L226}.
     */
    public static final String IDENTIFIER_REQUIRED_MESSAGE =
            "Account or Card Number must be entered...";

    /**
     * Rejection text for an amount outside {@value #AMOUNT_PATTERN}, from
     * {@code app/cbl/COTRN02C.cbl:L345}.
     */
    public static final String AMOUNT_FORMAT_MESSAGE = "Amount should be in format -99999999.99";

    /**
     * Rejection text for an origin timestamp whose first ten characters are not a dated shape, from
     * {@code app/cbl/COTRN02C.cbl:L360}.
     */
    public static final String ORIGIN_DATE_FORMAT_MESSAGE =
            "Orig Date should be in format YYYY-MM-DD";

    /**
     * Rejection text for a processing timestamp whose first ten characters are not a dated shape,
     * from {@code app/cbl/COTRN02C.cbl:L375}. This text carries no trailing ellipsis, and
     * {@link #ORIGIN_DATE_FORMAT_MESSAGE} carries none either.
     */
    public static final String PROCESSING_DATE_FORMAT_MESSAGE =
            "Proc Date should be in format YYYY-MM-DD";

    /**
     * Rejection text for an origin date the tolerant policy declines, from
     * {@code app/cbl/COTRN02C.cbl:L401}.
     */
    public static final String ORIGIN_DATE_INVALID_MESSAGE = "Orig Date - Not a valid date...";

    /**
     * Rejection text for a processing date the tolerant policy declines, from
     * {@code app/cbl/COTRN02C.cbl:L421}.
     */
    public static final String PROCESSING_DATE_INVALID_MESSAGE = "Proc Date - Not a valid date...";

    /**
     * Rejection text for a merchant identifier that is not all digits, from
     * {@code app/cbl/COTRN02C.cbl:L432}.
     */
    public static final String MERCHANT_ID_NOT_NUMERIC_MESSAGE = "Merchant ID must be Numeric...";

    // The six accepted shapes. Each one is the COBOL class test or positional test of the field it
    // constrains, written as a regular expression.

    /**
     * Shape of {@link #accountId()}: one to eleven digits.
     * {@code app/cbl/COTRN02C.cbl:L197} applies the COBOL numeric class test to the same field, and
     * {@link #canonicalAccountId()} widens a shorter value to
     * {@link PicClause#XREF_ACCT_ID_WIDTH} digits.
     */
    public static final String ACCOUNT_ID_PATTERN = "^[0-9]{1,11}$";

    /**
     * Shape of {@link #cardNumber()}: one to sixteen digits.
     * {@code app/cbl/COTRN02C.cbl:L211} applies the COBOL numeric class test to the same field, and
     * {@link #canonicalCardNumber()} widens a shorter value to
     * {@link PicClause#DALYTRAN_CARD_NUM_WIDTH} digits.
     */
    public static final String CARD_NUMBER_PATTERN = "^[0-9]{1,16}$";

    /**
     * Shape of {@link #merchantId()}: exactly nine digits, the width of
     * {@code DALYTRAN-MERCHANT-ID PIC 9(09)}. {@code app/cbl/COTRN02C.cbl:L430} applies the COBOL
     * numeric class test to the same field.
     */
    public static final String MERCHANT_ID_PATTERN = "^[0-9]{9}$";

    /**
     * Shape of {@link #amount()}: a sign, eight digits, a decimal point, then two digits.
     * {@code app/cbl/COTRN02C.cbl:L339-L351} tests position 1 for a sign, positions 2 through 9 for
     * digits, position 10 for the point, and positions 11 and 12 for digits.
     */
    public static final String AMOUNT_PATTERN = "^[-+]\\d{8}\\.\\d{2}$";

    /**
     * Shape of {@link #originTimestamp()}: a dated first ten characters, a space at position 11,
     * colons at positions 14 and 17, a point at position 20, then six digits. Position 11 holds a
     * space in all three hundred records of {@code app/data/ASCII/dailytran.txt}.
     */
    public static final String ORIGIN_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$";

    /**
     * Shape of {@link #processingTimestamp()}: a dated first ten characters, a third dash at
     * position 11, points at positions 14, 17 and 20, two digits, then four zeros. The redefinition
     * at {@code app/cbl/CBTRN02C.cbl:L160-L174} declares {@code DB2-STREEP-3} at position 11,
     * {@code DB2-MIL PIC 9(002)} for the two digits and {@code DB2-REST PIC X(04)} for the zeros,
     * which {@code app/cbl/CBTRN02C.cbl:L701} fills.
     */
    public static final String PROCESSING_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000$";

    /** The character {@link #canonicalAccountId()} and {@link #canonicalCardNumber()} pad with. */
    private static final String PAD_DIGIT = "0";

    /**
     * Reports whether the request carries an account identifier or a card number.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L195-L230} reads whichever field arrived, resolves the other
     * from the cross-reference, and reaches its {@code WHEN OTHER} branch when neither did. A
     * component that is {@code null}, empty or all whitespace counts as absent here, matching the
     * {@code NOT = SPACES AND LOW-VALUES} test the two branches share.
     *
     * @return {@code true} when at least one of the two identifiers arrived
     */
    @AssertTrue(message = IDENTIFIER_REQUIRED_MESSAGE)
    public boolean isEitherIdentifierSupplied() {
        return supplied(accountId) || supplied(cardNumber);
    }

    /**
     * Reports whether the tolerant policy accepts the date {@link #originTimestamp()} opens with.
     *
     * <p>{@value #ORIGIN_DATE_FORMAT_MESSAGE} reports an absent or short component.
     *
     * @return {@code true} when the policy accepts that date, and {@code true} when the component
     *         is absent or holds fewer than ten characters
     */
    @AssertTrue(message = ORIGIN_DATE_INVALID_MESSAGE)
    public boolean isOriginTimestampDateValid() {
        return dateAccepted(originTimestamp);
    }

    /**
     * Reports whether the tolerant policy accepts the date {@link #processingTimestamp()} opens
     * with.
     *
     * <p>The component is optional, and {@value #PROCESSING_DATE_FORMAT_MESSAGE} reports a short
     * one.
     *
     * @return {@code true} when the policy accepts that date, and {@code true} when the component
     *         is absent or holds fewer than ten characters
     */
    @AssertTrue(message = PROCESSING_DATE_INVALID_MESSAGE)
    public boolean isProcessingTimestampDateValid() {
        return dateAccepted(processingTimestamp);
    }

    /**
     * Returns {@link #accountId()} widened to the eleven digits the cross-reference record holds.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L204-L207} converts the field, then moves the value back into
     * a {@code PIC 9(11)} field, which fills the leading positions with zeros. A caller sending
     * {@code 7} therefore reaches the decision rules as {@code 00000000007}.
     *
     * @return the widened identifier, or {@code null} when {@link #accountId()} is absent or holds
     *         a value the plain numeric grammar rejects
     */
    public String canonicalAccountId() {
        return canonical(accountId, PicClause.XREF_ACCT_ID_WIDTH);
    }

    /**
     * Returns {@link #cardNumber()} widened to the sixteen digits the transaction record holds.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L218-L221} converts the field, then moves the value back into
     * a {@code PIC 9(16)} field, which fills the leading positions with zeros.
     * {@code app/cbl/COTRN02C.cbl:L459} stores that widened field into
     * {@code TRAN-CARD-NUM PIC X(16)}.
     *
     * @return the widened card number, or {@code null} when {@link #cardNumber()} is absent or
     *         holds a value the plain numeric grammar rejects
     */
    public String canonicalCardNumber() {
        return canonical(cardNumber, PicClause.DALYTRAN_CARD_NUM_WIDTH);
    }

    /**
     * Returns the value {@link #amount()} carries, at two digits after the decimal point.
     *
     * <p>Three steps run in the order {@code app/cbl/COTRN02C.cbl} runs them. The positional test
     * of {@code app/cbl/COTRN02C.cbl:L339-L351} gates the field, {@code FUNCTION NUMVAL-C} at
     * {@code app/cbl/COTRN02C.cbl:L456-L457} converts it, and the store into
     * {@code TRAN-AMT PIC S9(09)V99} fixes the scale at {@link PicClause#DALYTRAN_AMT_SCALE}.
     *
     * <p>{@code +00000504.77} carries {@code 504.77}, the amount record 1 of
     * {@code app/data/ASCII/dailytran.txt} holds at positions 133 through 143.
     *
     * @return the amount at scale {@link PicClause#DALYTRAN_AMT_SCALE}, or {@code null} when
     *         {@link #amount()} is absent or holds a value the currency-tolerant grammar rejects
     */
    public BigDecimal amountValue() {
        if (!supplied(amount)) {
            return null;
        }
        String text = amount.strip();
        if (!NumvalParser.isValidNumvalCurrency(text)) {
            return null;
        }
        return CobolDecimal.truncateToScale(NumvalParser.numvalCurrency(text),
                PicClause.DALYTRAN_AMT_SCALE);
    }

    /**
     * Applies the tolerant policy to the first ten characters of a timestamp.
     *
     * <p>{@code CSUTLDTC-DATE PIC X(10)} at {@code app/cbl/COTRN02C.cbl:L64} holds ten characters,
     * and {@code app/cbl/CBTRN02C.cbl:L414} compares the same ten. The sixteen characters that
     * follow are never read as part of a date.
     *
     * @param timestamp the twenty-six character component to read; may be {@code null}
     * @return {@code true} when the policy accepts the date, or when {@code timestamp} is absent or
     *         holds fewer than ten characters
     */
    private static boolean dateAccepted(String timestamp) {
        if (!supplied(timestamp) || timestamp.length() < CobolDateValidator.TESTED_DATE_WIDTH) {
            return true;
        }
        return CobolDateValidator.isAcceptedByTolerantPolicy(
                timestamp.substring(0, CobolDateValidator.TESTED_DATE_WIDTH),
                CobolDateValidator.TOLERANT_POLICY_DATE_MASK);
    }

    /**
     * Widens a digit string to the count of digits its record field holds.
     *
     * <p>The plain grammar gates and converts, matching the class test at
     * {@code app/cbl/COTRN02C.cbl:L197} and {@code app/cbl/COTRN02C.cbl:L211} and the two
     * {@code FUNCTION NUMVAL} calls that follow each of them. Both target fields are unsigned, so
     * a sign does not survive the conversion.
     *
     * @param value the component to widen; may be {@code null}
     * @param width digits the target field holds, from {@link PicClause}
     * @return the value at {@code width} digits, unchanged when it already holds that many or more,
     *         or {@code null} when {@code value} is absent or the plain grammar rejects it
     */
    private static String canonical(String value, int width) {
        if (!supplied(value)) {
            return null;
        }
        String text = value.strip();
        if (!NumvalParser.isValidNumval(text)) {
            return null;
        }
        String digits = NumvalParser.numval(text).toBigInteger().abs().toString();
        if (digits.length() >= width) {
            return digits;
        }
        return PAD_DIGIT.repeat(width - digits.length()) + digits;
    }

    /**
     * Reports whether a component arrived with content.
     *
     * @param value the component to test; may be {@code null}
     * @return {@code true} when {@code value} holds at least one character that is not whitespace
     */
    private static boolean supplied(String value) {
        return value != null && !value.isBlank();
    }
}

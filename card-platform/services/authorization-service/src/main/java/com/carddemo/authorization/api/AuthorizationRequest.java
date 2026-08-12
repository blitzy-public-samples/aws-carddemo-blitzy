package com.carddemo.authorization.api;

import com.carddemo.cobol.CobolDateValidator;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.NumvalParser;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

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
 * <p>The canonical constructor turns a blank component into {@code null} before any constraint runs.
 * {@code app/cbl/COTRN02C.cbl:L251-L320} tests eleven fields for {@code SPACES OR LOW-VALUES} and
 * sends the screen on the first empty one, so a field of spaces never reaches the class test that
 * follows at {@code app/cbl/COTRN02C.cbl:L322-L334}. Normalising first reproduces that ordering: a
 * blank field reports its own empty text, and only a filled field reports a shape failure. One
 * definition of "supplied" therefore governs the constraints and the derived accessors alike.
 *
 * <p>Eleven components are required, matching the eleven fields
 * {@code app/cbl/COTRN02C.cbl:L251-L320} rejects when empty — the transaction type code, the category
 * code, the source, the description, the amount, both timestamps, and the four merchant fields.
 * {@code transactionId} may not be supplied at all: this service allocates every identifier, as
 * {@link #TRANSACTION_ID_NOT_ACCEPTED_MESSAGE} explains.
 *
 * <p>A caller names its subject by card number or by account identifier, and at least one of the two
 * has to arrive. {@code VALIDATE-INPUT-KEY-FIELDS} at {@code app/cbl/COTRN02C.cbl:L195-L230} is the
 * paragraph this reproduces, and it admits either: the account branch at {@code :L196-L209} reads the
 * cross-reference by account identifier through its alternate index and takes the card number the row
 * carries, the card branch at {@code :L210-L223} reads it by card number and takes the account
 * identifier, and the {@code WHEN OTHER} branch at {@code :L224-L229} refuses a request carrying
 * neither with {@value #IDENTIFIER_REQUIRED_MESSAGE}. {@link #isIdentifierSupplied()} is that third
 * branch. A component filled with spaces counts as absent, matching the
 * {@code NOT = SPACES AND LOW-VALUES} test at {@code app/cbl/COTRN02C.cbl:L196}.
 *
 * <p>Where both arrive, the card decides and the row that card resolves names the subject.
 * {@code app/cbl/CBTRN02C.cbl:L383} keys the cross-reference read on the card the transaction
 * carries, so the card a caller presents is the card the four rules run against. The account is read
 * from the cross-reference row that card resolves, and an account declared on the request selects a
 * card on the account branch and takes no further part. Where the card resolves no row the call is
 * decided under reject reason {@code 0100} of {@code app/cbl/CBTRN02C.cbl:L385-L387} against no
 * account at all, because nothing this platform stores names one.
 *
 * <p>Where only the account arrives, the account branch of
 * {@code app/cbl/COTRN02C.cbl:L196-L209} runs: the cross-reference is read by account identifier
 * through its alternate index and {@code app/cbl/COTRN02C.cbl:L209} moves the card number of the row
 * it read over the card field, so that card is the card the rules run against. An account holding no
 * cross-reference row is refused with {@value #ACCOUNT_ID_NOT_FOUND_MESSAGE}, from the
 * {@code NOTFND} limb at {@code app/cbl/COTRN02C.cbl:L591-L592}.
 *
 * <p>Every free-text component is bounded twice: by the width of its source field and by
 * {@value #PRINTABLE_TEXT_PATTERN}, which admits no control character. The description
 * reaches the fixed-width alert record the notification service renders, and a carriage
 * return there would let a caller forge a line of it.
 *
 * <p>Both timestamps are tested over their first ten characters against
 * {@link CobolDateValidator#TOLERANT_POLICY_DATE_MASK}. That policy accepts a date reporting
 * severity {@code 0000} and also one reporting message number {@code 2513}, as
 * {@code app/cbl/COTRN02C.cbl:L397-L400} and {@code app/cbl/COTRN02C.cbl:L417-L420} do.
 *
 * @param transactionId           identifier of the transaction, from
 *                                {@code DALYTRAN-ID PIC X(16)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L5}. Must be absent: this service
 *                                allocates every identifier from a database sequence, and a
 *                                supplied value is refused with
 *                                {@value #TRANSACTION_ID_NOT_ACCEPTED_MESSAGE}.
 * @param transactionTypeCode     type code of the transaction, from
 *                                {@code DALYTRAN-TYPE-CD PIC X(02)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L6}. Required, and one or two digits
 *                                shaped by {@value #TYPE_CODE_PATTERN}.
 * @param transactionCategoryCode category code of the transaction, from
 *                                {@code DALYTRAN-CAT-CD PIC 9(04)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L7}. Required, and one to four digits
 *                                shaped by {@value #CATEGORY_CODE_PATTERN}.
 * @param source                  channel that captured the transaction, from
 *                                {@code DALYTRAN-SOURCE PIC X(10)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L8}. Required, and at most ten
 *                                characters.
 * @param description             description of the transaction, from
 *                                {@code DALYTRAN-DESC PIC X(100)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L9}. Required, and at most one hundred
 *                                characters.
 * @param amount                  amount as the caller supplied it, from
 *                                {@code DALYTRAN-AMT PIC S9(09)V99} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L10}. Required, and accepted in the
 *                                currency-tolerant grammar {@link #amountValue()} reads.
 *                                {@value #AMOUNT_PATTERN} is the form the source screen emits, and
 *                                not the only form accepted here.
 * @param merchantId              identifier of the merchant, from
 *                                {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L11}. Required, and exactly nine
 *                                digits.
 * @param merchantName            name of the merchant, from
 *                                {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L12}. Required, and at most fifty
 *                                characters.
 * @param merchantCity            city of the merchant, from
 *                                {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L13}. Required, and at most fifty
 *                                characters.
 * @param merchantZip             postal code of the merchant, from
 *                                {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L14}. Required, and at most ten
 *                                characters.
 * @param cardNumber              card number, from {@code DALYTRAN-CARD-NUM PIC X(16)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L15}. One to sixteen digits. Required
 *                                unless {@code accountId} arrives instead, which is the card branch
 *                                of {@code app/cbl/COTRN02C.cbl:L210-L223} beside its account branch
 *                                at {@code :L196-L209}.
 *                                {@link #canonicalCardNumber()} returns it at its stored width.
 * @param originTimestamp         moment the transaction was captured, from
 *                                {@code DALYTRAN-ORIG-TS PIC X(26)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L16}. Required, and shaped by
 *                                {@value #ORIGIN_TIMESTAMP_PATTERN}.
 * @param processingTimestamp     moment the transaction was posted, from
 *                                {@code DALYTRAN-PROC-TS PIC X(26)} at
 *                                {@code app/cpy/CVTRA06Y.cpy:L17}. Required, and shaped by
 *                                {@value #PROCESSING_TIMESTAMP_PATTERN}.
 *                                {@link #recordProcessingTimestamp()} returns it at the record
 *                                width, which is the form the decision row records, as
 *                                {@code app/cbl/COTRN02C.cbl:L470} records it on the transaction
 *                                record.
 * @param accountId               account identifier, from {@code XREF-ACCT-ID PIC 9(11)} at
 *                                {@code app/cpy/CVACT03Y.cpy:L7}. One to eleven digits. Required
 *                                unless {@code cardNumber} arrives instead. A value here names the
 *                                subject the decision applies to wherever the card resolves no
 *                                cross-reference row, and the card is read from the cross-reference
 *                                by it where no card number arrives.
 *                                {@link #canonicalAccountId()} returns it at its stored width.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public record AuthorizationRequest(

        @Null(message = TRANSACTION_ID_NOT_ACCEPTED_MESSAGE)
        @Size(max = PicClause.DALYTRAN_ID_WIDTH, message = TRANSACTION_ID_WIDTH_MESSAGE)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String transactionId,

        @NotBlank(message = TYPE_CODE_EMPTY_MESSAGE)
        @Pattern(regexp = TYPE_CODE_PATTERN, message = TYPE_CODE_NOT_NUMERIC_MESSAGE)
        String transactionTypeCode,

        @NotBlank(message = CATEGORY_CODE_EMPTY_MESSAGE)
        @Pattern(regexp = CATEGORY_CODE_PATTERN, message = CATEGORY_CODE_NOT_NUMERIC_MESSAGE)
        String transactionCategoryCode,

        @NotBlank(message = SOURCE_EMPTY_MESSAGE)
        @Size(max = PicClause.DALYTRAN_SOURCE_WIDTH, message = SOURCE_WIDTH_MESSAGE)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String source,

        @NotBlank(message = DESCRIPTION_EMPTY_MESSAGE)
        @Size(max = PicClause.DALYTRAN_DESC_WIDTH, message = DESCRIPTION_WIDTH_MESSAGE)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String description,

        @NotBlank(message = AMOUNT_EMPTY_MESSAGE)
        String amount,

        @NotBlank(message = MERCHANT_ID_EMPTY_MESSAGE)
        @Pattern(regexp = MERCHANT_ID_PATTERN, message = MERCHANT_ID_NOT_NUMERIC_MESSAGE)
        String merchantId,

        @NotBlank(message = MERCHANT_NAME_EMPTY_MESSAGE)
        @Size(max = PicClause.DALYTRAN_MERCHANT_NAME_WIDTH, message = MERCHANT_NAME_WIDTH_MESSAGE)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String merchantName,

        @NotBlank(message = MERCHANT_CITY_EMPTY_MESSAGE)
        @Size(max = PicClause.DALYTRAN_MERCHANT_CITY_WIDTH, message = MERCHANT_CITY_WIDTH_MESSAGE)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String merchantCity,

        @NotBlank(message = MERCHANT_ZIP_EMPTY_MESSAGE)
        @Size(max = PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH, message = MERCHANT_ZIP_WIDTH_MESSAGE)
        @Pattern(regexp = PRINTABLE_TEXT_PATTERN, message = CONTROL_CHARACTER_MESSAGE)
        String merchantZip,

        // No @NotBlank: a request may name its subject by account identifier instead, which is
        // the account branch of VALIDATE-INPUT-KEY-FIELDS at app/cbl/COTRN02C.cbl:L196-L209.
        // isIdentifierSupplied() refuses a request that carries neither.
        @Pattern(regexp = CARD_NUMBER_PATTERN, message = CARD_NUMBER_NOT_NUMERIC_MESSAGE)
        String cardNumber,

        @NotBlank(message = ORIGIN_DATE_EMPTY_MESSAGE)
        @Pattern(regexp = ORIGIN_TIMESTAMP_PATTERN, message = ORIGIN_DATE_FORMAT_MESSAGE)
        String originTimestamp,

        @NotBlank(message = PROCESSING_DATE_EMPTY_MESSAGE)
        @Pattern(regexp = PROCESSING_TIMESTAMP_PATTERN, message = PROCESSING_DATE_FORMAT_MESSAGE)
        String processingTimestamp,

        @Pattern(regexp = ACCOUNT_ID_PATTERN, message = ACCOUNT_ID_NOT_NUMERIC_MESSAGE)
        String accountId) {

    // The twenty-two rejection texts of app/cbl/COTRN02C.cbl, each reproduced character for
    // character from the paragraph that moves it into WS-MESSAGE, and beside them the texts each
    // marked ADDITIVE, which answer a condition a fixed-width map field cannot raise. Every text of
    // both kinds fits WS-MESSAGE PIC X(80) at app/cbl/COTRN02C.cbl:L38.

    /**
     * Rejection text for an empty transaction type code, from
     * {@code app/cbl/COTRN02C.cbl:L254}. First of the eleven emptiness tests the
     * {@code EVALUATE TRUE} at {@code app/cbl/COTRN02C.cbl:L251} runs.
     */
    public static final String TYPE_CODE_EMPTY_MESSAGE = "Type CD can NOT be empty...";

    /** Rejection text for an empty category code, from {@code app/cbl/COTRN02C.cbl:L260}. */
    public static final String CATEGORY_CODE_EMPTY_MESSAGE = "Category CD can NOT be empty...";

    /** Rejection text for an empty source, from {@code app/cbl/COTRN02C.cbl:L266}. */
    public static final String SOURCE_EMPTY_MESSAGE = "Source can NOT be empty...";

    /** Rejection text for an empty description, from {@code app/cbl/COTRN02C.cbl:L272}. */
    public static final String DESCRIPTION_EMPTY_MESSAGE = "Description can NOT be empty...";

    /** Rejection text for an empty amount, from {@code app/cbl/COTRN02C.cbl:L278}. */
    public static final String AMOUNT_EMPTY_MESSAGE = "Amount can NOT be empty...";

    /** Rejection text for an empty origin timestamp, from {@code app/cbl/COTRN02C.cbl:L284}. */
    public static final String ORIGIN_DATE_EMPTY_MESSAGE = "Orig Date can NOT be empty...";

    /**
     * Rejection text for an empty processing timestamp, from
     * {@code app/cbl/COTRN02C.cbl:L290}. The component is required, because the source tests it for
     * emptiness alongside the other ten.
     */
    public static final String PROCESSING_DATE_EMPTY_MESSAGE = "Proc Date can NOT be empty...";

    /** Rejection text for an empty merchant identifier, from {@code app/cbl/COTRN02C.cbl:L296}. */
    public static final String MERCHANT_ID_EMPTY_MESSAGE = "Merchant ID can NOT be empty...";

    /** Rejection text for an empty merchant name, from {@code app/cbl/COTRN02C.cbl:L302}. */
    public static final String MERCHANT_NAME_EMPTY_MESSAGE = "Merchant Name can NOT be empty...";

    /** Rejection text for an empty merchant city, from {@code app/cbl/COTRN02C.cbl:L308}. */
    public static final String MERCHANT_CITY_EMPTY_MESSAGE = "Merchant City can NOT be empty...";

    /**
     * Rejection text for an empty merchant postal code, from
     * {@code app/cbl/COTRN02C.cbl:L314}. Last of the eleven emptiness tests.
     */
    public static final String MERCHANT_ZIP_EMPTY_MESSAGE = "Merchant Zip can NOT be empty...";

    /**
     * Rejection text for a transaction type code that is not all digits, from
     * {@code app/cbl/COTRN02C.cbl:L325}. The {@code EVALUATE TRUE} at
     * {@code app/cbl/COTRN02C.cbl:L322} applies the COBOL numeric class test to the same field, and
     * it runs after every emptiness test above.
     */
    public static final String TYPE_CODE_NOT_NUMERIC_MESSAGE = "Type CD must be Numeric...";

    /**
     * Rejection text for a category code that is not all digits, from
     * {@code app/cbl/COTRN02C.cbl:L331}.
     */
    public static final String CATEGORY_CODE_NOT_NUMERIC_MESSAGE =
            "Category CD must be Numeric...";

    /**
     * ADDITIVE rejection text for a supplied transaction identifier of another width.
     *
     * <p>No source message corresponds. {@code app/cbl/COTRN02C.cbl:L444-L451} derives the
     * identifier and reads none from the screen, so the source has no field to reject. Every event
     * contract of this platform keys its transaction identifier at
     * {@code com.carddemo.events.TransactionAuthorized#TRANSACTION_ID_LENGTH} characters, and a
     * narrower value reaches that check after the decision has already been taken.
     */
    public static final String TRANSACTION_ID_WIDTH_MESSAGE =
            "Transaction ID must hold sixteen printable characters...";

    /**
     * ADDITIVE rejection text for a source wider than {@code DALYTRAN-SOURCE PIC X(10)}.
     *
     * <p>No source message corresponds, and none could. The field arrives as the fixed-width map
     * area {@code TRNSRC} at {@code app/bms/COTRN02.bms:L148}, which {@code :L151} fixes at the same
     * ten characters, on map {@code COTRN2A} at {@code app/bms/COTRN02.bms:L26}, so a longer value
     * cannot reach
     * {@code app/cbl/COTRN02C.cbl} at all: the terminal stops the operator at the field boundary and
     * the Basic Mapping Support copy truncates whatever a program moves in. A request body carries no
     * such boundary, so the width becomes a rule here.
     *
     * <p>Five texts of this family replace the reader's own default wording, which named a bound and
     * no field: {@code "size must be between 0 and 100"} tells a caller nothing about which of the
     * five it refused. Each text names its field and its width, and each fits
     * {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COTRN02C.cbl:L38} like every source text does.
     */
    public static final String SOURCE_WIDTH_MESSAGE =
            "Source must hold at most ten characters...";

    /**
     * ADDITIVE rejection text for a description wider than {@code DALYTRAN-DESC PIC X(100)}.
     *
     * <p>ADDITIVE for the reason {@link #SOURCE_WIDTH_MESSAGE} records. This width carries more
     * weight than the other four: the value reaches the fixed-width alert record the notification
     * service renders from {@code app/cpy/COSTM01.CPY}, which lays out its columns by position.
     */
    public static final String DESCRIPTION_WIDTH_MESSAGE =
            "Description must hold at most one hundred characters...";

    /**
     * ADDITIVE rejection text for a merchant name wider than
     * {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. ADDITIVE for the reason
     * {@link #SOURCE_WIDTH_MESSAGE} records.
     */
    public static final String MERCHANT_NAME_WIDTH_MESSAGE =
            "Merchant Name must hold at most fifty characters...";

    /**
     * ADDITIVE rejection text for a merchant city wider than
     * {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. ADDITIVE for the reason
     * {@link #SOURCE_WIDTH_MESSAGE} records.
     */
    public static final String MERCHANT_CITY_WIDTH_MESSAGE =
            "Merchant City must hold at most fifty characters...";

    /**
     * ADDITIVE rejection text for a merchant postal code wider than
     * {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. ADDITIVE for the reason
     * {@link #SOURCE_WIDTH_MESSAGE} records. Records of
     * {@code app/data/ASCII/dailytran.txt} hold both the five-digit and the hyphenated nine-digit
     * form, and both fit the width.
     */
    public static final String MERCHANT_ZIP_WIDTH_MESSAGE =
            "Merchant Zip must hold at most ten characters...";

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
     * Rejection text for an account identifier that resolves no card, from the {@code NOTFND} limb
     * of {@code READ-CXACAIX-FILE} at {@code app/cbl/COTRN02C.cbl:L591-L592}.
     *
     * <p>The account branch of {@code VALIDATE-INPUT-KEY-FIELDS} reads the cross-reference by the
     * account identifier the caller supplied, and an account with no row there is an input refusal
     * at the capture stage rather than an authorization outcome. The source answers it with this
     * text and re-sends the screen, so no transaction is captured and no identifier is consumed.
     */
    public static final String ACCOUNT_ID_NOT_FOUND_MESSAGE = "Account ID NOT found...";

    /**
     * Rejection text for a request that carries a transaction identifier. ADDITIVE: no source
     * paragraph writes it, because no screen field offers the value.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L444-L451} allocates the identifier by browsing the file
     * backwards from high values and adding one, so the screen never supplies it. This service
     * allocates it from a database sequence, and a caller-supplied value is refused rather than
     * ignored: an identifier a caller chooses is an identifier a caller can repeat, and a repeated
     * identifier merges one payment with another in the ledger, the alert history and the fraud
     * assessment while every consumer sees a fresh event identifier and no duplicate to skip.
     */
    public static final String TRANSACTION_ID_NOT_ACCEPTED_MESSAGE =
            "Transaction ID is allocated by this service and must not be supplied...";

    /**
     * Rejection text for a component carrying a character outside the printable range.
     *
     * <p>No COBOL ancestor: the source reads its fields from fixed-width map areas, which no
     * control character can reach.
     *
     * <p>Every character of all three hundred records of
     * {@code app/data/ASCII/dailytran.txt} falls inside the printable range this text
     * guards. That was measured across the transaction identifier, the type code, the
     * category code, the source, the description and all three merchant fields.
     *
     * <p>A carriage return or a line feed in a description would let a caller add or forge
     * a line of the fixed-width alert record the notification service renders. The check
     * therefore sits here at ingress, and not only where the text is rendered.
     */
    public static final String CONTROL_CHARACTER_MESSAGE =
            "Text fields must hold printable characters only...";

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

    /**
     * Every rejection text this record declares, in the order {@code app/cbl/COTRN02C.cbl} reaches
     * the condition that emits it.
     *
     * <p>The source emits exactly one text per rejected screen. Each paragraph moves its text into
     * {@code WS-MESSAGE} and performs {@code SEND-TRNADD-SCREEN}, which returns to the terminal, so
     * the edits that would have followed never run. {@link GlobalExceptionHandler} reads this list to
     * choose the one text a refused request reports, which reproduces that behaviour over a
     * validator that reports every failing component at once.
     *
     * <p>The first twenty-three entries follow the source in paragraph order: the two class tests and
     * the account lookup of {@code VALIDATE-INPUT-KEY-FIELDS} at
     * {@code app/cbl/COTRN02C.cbl:L195-L229}, the eleven emptiness tests at
     * {@code app/cbl/COTRN02C.cbl:L251-L320}, the two class tests at
     * {@code app/cbl/COTRN02C.cbl:L322-L334}, the three positional tests at
     * {@code app/cbl/COTRN02C.cbl:L336-L380}, the two tolerant date validations at
     * {@code app/cbl/COTRN02C.cbl:L389-L423} and the merchant class test at
     * {@code app/cbl/COTRN02C.cbl:L430-L436}.
     *
     * <p>The eight entries after them are the texts marked ADDITIVE. Each answers a condition a
     * fixed-width map field cannot raise, so the source reaches none of them and holds no position
     * for them. They follow the source-ordered entries, which keeps one order for every text.
     */
    public static final List<String> REJECTION_TEXTS_IN_SOURCE_ORDER = List.of(
            ACCOUNT_ID_NOT_NUMERIC_MESSAGE,
            ACCOUNT_ID_NOT_FOUND_MESSAGE,
            CARD_NUMBER_NOT_NUMERIC_MESSAGE,
            IDENTIFIER_REQUIRED_MESSAGE,
            TYPE_CODE_EMPTY_MESSAGE,
            CATEGORY_CODE_EMPTY_MESSAGE,
            SOURCE_EMPTY_MESSAGE,
            DESCRIPTION_EMPTY_MESSAGE,
            AMOUNT_EMPTY_MESSAGE,
            ORIGIN_DATE_EMPTY_MESSAGE,
            PROCESSING_DATE_EMPTY_MESSAGE,
            MERCHANT_ID_EMPTY_MESSAGE,
            MERCHANT_NAME_EMPTY_MESSAGE,
            MERCHANT_CITY_EMPTY_MESSAGE,
            MERCHANT_ZIP_EMPTY_MESSAGE,
            TYPE_CODE_NOT_NUMERIC_MESSAGE,
            CATEGORY_CODE_NOT_NUMERIC_MESSAGE,
            AMOUNT_FORMAT_MESSAGE,
            ORIGIN_DATE_FORMAT_MESSAGE,
            PROCESSING_DATE_FORMAT_MESSAGE,
            ORIGIN_DATE_INVALID_MESSAGE,
            PROCESSING_DATE_INVALID_MESSAGE,
            MERCHANT_ID_NOT_NUMERIC_MESSAGE,
            TRANSACTION_ID_NOT_ACCEPTED_MESSAGE,
            TRANSACTION_ID_WIDTH_MESSAGE,
            SOURCE_WIDTH_MESSAGE,
            DESCRIPTION_WIDTH_MESSAGE,
            MERCHANT_NAME_WIDTH_MESSAGE,
            MERCHANT_CITY_WIDTH_MESSAGE,
            MERCHANT_ZIP_WIDTH_MESSAGE,
            CONTROL_CHARACTER_MESSAGE);

    // The eight accepted shapes. Each one is the COBOL class test or positional test of the field
    // it constrains, written as a regular expression.

    /**
     * Shape of {@link #transactionTypeCode()}: one or two digits, within the width of
     * {@code DALYTRAN-TYPE-CD PIC X(02)}. {@code app/cbl/COTRN02C.cbl:L324} applies the COBOL
     * numeric class test to the same field, and {@code app/data/ASCII/trantype.txt} carries seven
     * two-digit codes.
     */
    public static final String TYPE_CODE_PATTERN = "^[0-9]{1,2}$";

    /**
     * Shape of {@link #transactionCategoryCode()}: one to four digits, within the width of
     * {@code DALYTRAN-CAT-CD PIC 9(04)}. {@code app/cbl/COTRN02C.cbl:L330} applies the COBOL
     * numeric class test to the same field, and {@code app/data/ASCII/trancatg.txt} carries eighteen
     * four-digit codes.
     */
    public static final String CATEGORY_CODE_PATTERN = "^[0-9]{1,4}$";

    /**
     * Shape of {@link #accountId()}: exactly {@link PicClause#XREF_ACCT_ID_WIDTH} digits.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L197} applies the COBOL numeric class test to
     * {@code ACTIDINI}, which {@code app/bms/COTRN02.bms:L85-L90} declares {@code LENGTH=11} with no
     * {@code JUSTIFY} and no {@code PICIN}. A shorter entry therefore arrives left-aligned with
     * trailing spaces, fails that class test at {@code app/cbl/COTRN02C.cbl:L197} and reports
     * {@value #ACCOUNT_ID_NOT_NUMERIC_MESSAGE}. Only two maps in {@code app/bms/} zero-fill a short
     * entry, {@code app/bms/COMEN01.bms:L147} and {@code app/bms/COADM01.bms:L147}, and this is
     * neither.
     */
    public static final String ACCOUNT_ID_PATTERN = "^[0-9]{11}$";

    /**
     * Shape of {@link #cardNumber()}: exactly {@link PicClause#DALYTRAN_CARD_NUM_WIDTH} digits.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L211} applies the COBOL numeric class test to
     * {@code CARDNINI}, which {@code app/bms/COTRN02.bms:L104-L108} declares {@code LENGTH=16} with
     * no {@code JUSTIFY} and no {@code PICIN}. A shorter entry fails that class test and reports
     * {@value #CARD_NUMBER_NOT_NUMERIC_MESSAGE}.
     *
     * <p>Widening a shorter value here would change which card the cross-reference read finds: a
     * fifteen-digit value widened by one zero names a different sixteen-character key, and the
     * decision would then apply to another cardholder's account.
     */
    public static final String CARD_NUMBER_PATTERN = "^[0-9]{16}$";

    /**
     * Shape of {@link #merchantId()}: exactly nine digits, the width of
     * {@code DALYTRAN-MERCHANT-ID PIC 9(09)}. {@code app/cbl/COTRN02C.cbl:L430} applies the COBOL
     * numeric class test to the same field.
     */
    public static final String MERCHANT_ID_PATTERN = "^[0-9]{9}$";

    /**
     * The form the source screen emits for {@link #amount()}: a sign, eight digits, a decimal point,
     * then two digits. {@code app/cbl/COTRN02C.cbl:L339-L351} tests position 1 for a sign, positions
     * 2 through 9 for digits, position 10 for the point, and positions 11 and 12 for digits.
     *
     * <p>That test constrains a twelve-character screen field, the map area {@code TRNAMT} at
     * {@code app/bms/COTRN02.bms:L174}, which {@code :L177} fixes at twelve characters. The map is
     * {@code COTRN2A} at {@code app/bms/COTRN02.bms:L26}, declared inside mapset {@code COTRN02} at
     * {@code app/bms/COTRN02.bms:L19}; no member named {@code COTRN2A.bms} exists, because
     * {@code COTRN2A} names the map rather than the file. This request has no screen, so the
     * form is documented here and is not the only form accepted.
     * {@link #isAmountAcceptedAndInRange()} accepts every form the currency-tolerant grammar reads,
     * including {@code 504.77} and {@code $1,234.56}, because
     * {@code app/cbl/COTRN02C.cbl:L456-L457} converts the field with {@code FUNCTION NUMVAL-C}.
     */
    public static final String AMOUNT_PATTERN = "^[-+]\\d{8}\\.\\d{2}$";

    /**
     * Digits {@link #amountValue()} holds before the decimal point, from
     * {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:L10}. The screen field the
     * source validates is one digit narrower, so a value this record accepts always fits the record
     * field the ledger stores.
     */
    public static final int AMOUNT_INTEGER_DIGITS =
            PicClause.DALYTRAN_AMT_PRECISION - PicClause.DALYTRAN_AMT_SCALE;

    /**
     * Shape of {@link #originTimestamp()}: the ten-character date the synchronous screen field
     * carries, on its own or followed by the time part the stored record carries.
     *
     * <p>{@code TORIGDTI OF COTRN2AI} is ten characters wide, the width
     * {@code CSUTLDTC-DATE PIC X(10)} at {@code app/cbl/COTRN02C.cbl:L64} declares and the width the
     * date validation reads. {@code app/cbl/COTRN02C.cbl:L469} moves that field into
     * {@code TRAN-ORIG-TS PIC X(26)}, so the ten characters are what a caller of the synchronous
     * path supplies and the twenty-six are what the record holds.
     *
     * <p>The optional tail is the record form: a space at position 11, colons at positions 14 and
     * 17, a point at position 20, then six digits. Position 11 holds a space in all three hundred
     * records of {@code app/data/ASCII/dailytran.txt}, so a caller replaying a fixture record sends
     * that form and a caller of the capture path sends the date alone.
     * {@link #recordOriginTimestamp()} renders either form at the record width.
     */
    public static final String ORIGIN_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2}\\.\\d{6})?$";

    /**
     * Shape of {@link #processingTimestamp()}: the ten-character date the synchronous screen field
     * carries, on its own or followed by the time part the stored record carries.
     *
     * <p>{@code TPROCDTI OF COTRN2AI} is the second ten-character date the capture screen validates,
     * at {@code app/cbl/COTRN02C.cbl:L409-L423}, and {@code app/cbl/COTRN02C.cbl:L470} moves it into
     * {@code TRAN-PROC-TS PIC X(26)}.
     *
     * <p>The optional tail is the record form: a third dash at position 11, points at positions 14,
     * 17 and 20, two digits, then four zeros. The redefinition at
     * {@code app/cbl/CBTRN02C.cbl:L160-L174} declares {@code DB2-STREEP-3} at position 11,
     * {@code DB2-MIL PIC 9(002)} for the two digits and {@code DB2-REST PIC X(04)} for the zeros,
     * which {@code app/cbl/CBTRN02C.cbl:L701} fills.
     * {@link #recordProcessingTimestamp()} renders either form at the record width.
     */
    public static final String PROCESSING_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}(-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000)?$";

    /**
     * Time part appended to a ten-character origin date to reach the record width.
     *
     * <p>Reject reason {@code 0103} compares the first ten characters and nothing further, at
     * {@code app/cbl/CBTRN02C.cbl:L414-L420}, so no decision reads these sixteen characters. The
     * published event declares the record form for {@code authorizedAt}, which is why a rendered
     * value carries them at all.
     */
    private static final String START_OF_DAY_ORIGIN_TIME = " 00:00:00.000000";

    /**
     * Time part appended to a ten-character processing date to reach the record width.
     *
     * <p>The two significant fractional digits and the four fixed zeros are the shape
     * {@code app/cbl/CBTRN02C.cbl:L701} writes. The ledger stamps its own processing timestamp when
     * it posts, at {@code app/cbl/CBTRN02C.cbl:L438}, so this value is the capture record of what the
     * caller declared.
     */
    private static final String START_OF_DAY_PROCESSING_TIME = "-00.00.00.000000";

    /**
     * Shape of every free-text component: printable characters and nothing else, of any length the
     * component's own {@code Size} bound allows.
     *
     * <p>The range runs from the space at {@code 0x20} to the tilde at {@code 0x7E}, so
     * every C0 control character is outside it, carriage return and line feed included.
     * All three hundred records of {@code app/data/ASCII/dailytran.txt} hold characters
     * from this range alone, measured across every text field of
     * {@code app/cpy/CVTRA06Y.cpy}, so the range refuses nothing the source accepts.
     *
     * <p>An empty component matches, because the bound on presence belongs to
     * {@code NotBlank} and to {@link #isEitherIdentifierSupplied()} rather than to a
     * character class.
     */
    public static final String PRINTABLE_TEXT_PATTERN = "^[ -~]*$";

    /** Stands in for a component that arrived, in the form {@link #toString()} returns. */
    public static final String WITHHELD = "<withheld>";

    /** Stands in for a component that did not arrive, in the form {@link #toString()} returns. */
    public static final String ABSENT = "<absent>";

    /** Widest {@link #amountValue()} this record accepts, and the widest the record field holds. */
    private static final BigDecimal AMOUNT_LIMIT = BigDecimal.TEN.pow(AMOUNT_INTEGER_DIGITS);

    /**
     * Turns every blank component into {@code null}, and changes no component that holds content.
     *
     * <p>One definition of "supplied" then governs the whole record. A component of spaces reports
     * the emptiness text its field carries at {@code app/cbl/COTRN02C.cbl:L251-L320}. It never
     * reports the shape text of a class test, which {@code app/cbl/COTRN02C.cbl:L322-L334} reaches
     * on a filled field alone. {@link #supplied(String)} and every constraint above therefore
     * agree.
     *
     * <p>Content passes through unchanged, spaces included. The COBOL class test reads a field
     * position by position, so {@code "7 "} is not numeric there and is not numeric here.
     */
    public AuthorizationRequest {
        transactionId = contentOrAbsent(transactionId);
        transactionTypeCode = contentOrAbsent(transactionTypeCode);
        transactionCategoryCode = contentOrAbsent(transactionCategoryCode);
        source = contentOrAbsent(source);
        description = contentOrAbsent(description);
        amount = contentOrAbsent(amount);
        merchantId = contentOrAbsent(merchantId);
        merchantName = contentOrAbsent(merchantName);
        merchantCity = contentOrAbsent(merchantCity);
        merchantZip = contentOrAbsent(merchantZip);
        cardNumber = contentOrAbsent(cardNumber);
        originTimestamp = contentOrAbsent(originTimestamp);
        processingTimestamp = contentOrAbsent(processingTimestamp);
        accountId = contentOrAbsent(accountId);
    }

    /**
     * Reports whether the currency-tolerant grammar reads {@link #amount()} and whether the value
     * fits the record field.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L456-L457} converts the field with {@code FUNCTION NUMVAL-C},
     * which reads a currency symbol and a thousands separator. A request may therefore send
     * {@code 504.77}, {@code +00000504.77} or {@code $504.77}, and all three carry one value.
     * {@link NumvalParser#isValidNumvalCurrency(String)} is the same gate
     * {@code app/cbl/COACTUPC.cbl:L2201} applies before its own conversion.
     *
     * <p>The value must also fit {@code DALYTRAN-AMT PIC S9(09)V99}, so its magnitude stays under
     * ten raised to {@value #AMOUNT_INTEGER_DIGITS}. A wider value would lose its high-order digit
     * on the store, and that silent loss is what this test prevents.
     *
     * <p>{@value #AMOUNT_EMPTY_MESSAGE} reports an absent component, so this test passes one
     * through.
     *
     * @return {@code true} when the grammar reads the component and the value fits the record field,
     *         and {@code true} when the component is absent
     */
    @AssertTrue(message = AMOUNT_FORMAT_MESSAGE)
    public boolean isAmountAcceptedAndInRange() {
        if (!supplied(amount)) {
            return true;
        }
        String text = amount.strip();
        if (!NumvalParser.isValidNumvalCurrency(text)) {
            return false;
        }
        return NumvalParser.numvalCurrency(text).abs().compareTo(AMOUNT_LIMIT) < 0;
    }

    /**
     * Reports whether the request names its subject, by card number or by account identifier.
     *
     * <p>This is the {@code WHEN OTHER} branch of {@code VALIDATE-INPUT-KEY-FIELDS} at
     * {@code app/cbl/COTRN02C.cbl:L224-L229}, which refuses a request carrying neither identifier
     * and reports {@value #IDENTIFIER_REQUIRED_MESSAGE}. The two branches above it each accept one
     * identifier and resolve the other from the cross-reference: the account branch at
     * {@code :L196-L209} reads the alternate index and takes the card number the row carries, and
     * the card branch at {@code :L210-L223} reads the primary key and takes the account identifier.
     *
     * <p>A component that is {@code null}, empty or all whitespace counts as absent, matching the
     * {@code NOT = SPACES AND LOW-VALUES} test at {@code app/cbl/COTRN02C.cbl:L196}.
     *
     * <p>Which identifier arrived decides how the card is resolved and nothing else. The decision
     * itself always runs on a full card number, because the record it reproduces carries one and no
     * account identifier at all ({@code app/cpy/CVTRA06Y.cpy:L4-L18}) and the cross-reference read
     * at {@code app/cbl/CBTRN02C.cbl:L382-L383} keys on it.
     *
     * @return {@code true} when a card number or an account identifier arrived
     */
    @AssertTrue(message = IDENTIFIER_REQUIRED_MESSAGE)
    public boolean isIdentifierSupplied() {
        return supplied(cardNumber) || supplied(accountId);
    }

    /**
     * Reports whether the request carries a card number.
     *
     * <p>A request that carries none names its subject by account identifier instead, and
     * {@code domain/AuthorizationService} resolves the card through the account branch of
     * {@code app/cbl/COTRN02C.cbl:L196-L209}. This method is what tells the two paths apart.
     *
     * @return {@code true} when a card number arrived
     */
    public boolean isCardNumberSupplied() {
        return supplied(cardNumber);
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

    @AssertTrue(message = PROCESSING_DATE_INVALID_MESSAGE)
    public boolean isProcessingTimestampDateValid() {
        return dateAccepted(processingTimestamp);
    }

    /**
     * Returns {@link #accountId()} at the eleven digits the cross-reference record holds.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L204-L207} converts the field, then moves the value back into
     * a {@code PIC 9(11)} field. The field it converts is already eleven characters wide, so a value
     * reaching that move holds eleven digits and the move changes nothing.
     *
     * <p>A value of another width returns {@code null}, and {@link #ACCOUNT_ID_PATTERN} reports it
     * as {@value #ACCOUNT_ID_NOT_NUMERIC_MESSAGE} before the decision rules run.
     *
     * @return the identifier at its stored width, or {@code null} when {@link #accountId()} is
     *         absent, is another width, or holds a value the plain numeric grammar rejects
     */
    public String canonicalAccountId() {
        return canonical(accountId, PicClause.XREF_ACCT_ID_WIDTH);
    }

    /**
     * Returns {@link #cardNumber()} at the sixteen digits the transaction record holds.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L218-L221} converts the field, then moves the value back into
     * a {@code PIC 9(16)} field, and {@code :L459} stores that field into
     * {@code TRAN-CARD-NUM PIC X(16)}. The field it converts is already sixteen characters wide.
     *
     * <p>A value of another width returns {@code null}, and {@link #CARD_NUMBER_PATTERN} reports it
     * as {@value #CARD_NUMBER_NOT_NUMERIC_MESSAGE} before the cross-reference read runs.
     *
     * @return the card number at its stored width, or {@code null} when {@link #cardNumber()} is
     *         absent, is another width, or holds a value the plain numeric grammar rejects
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
     * Returns {@link #originTimestamp()} at the width the transaction record holds.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L469} moves the ten-character screen field into
     * {@code TRAN-ORIG-TS PIC X(26)}. A caller that sent the record form already carries that width
     * and is returned unchanged. A caller that sent the date alone has the start of that day
     * appended, so the value reaches {@value PicClause#TRAN_ORIG_TS_WIDTH} characters and satisfies
     * the {@code authorizedAt} shape the published event declares.
     *
     * <p>The first ten characters are identical either way, which is the whole of what reject reason
     * {@code 0103} compares at {@code app/cbl/CBTRN02C.cbl:L414-L420}, so no decision changes.
     *
     * @return the capture moment at {@value PicClause#TRAN_ORIG_TS_WIDTH} characters, or
     *         {@code null} when {@link #originTimestamp()} is absent or holds neither accepted form
     */
    public String recordOriginTimestamp() {
        return atRecordWidth(originTimestamp, START_OF_DAY_ORIGIN_TIME,
                PicClause.TRAN_ORIG_TS_WIDTH);
    }

    /**
     * Returns {@link #processingTimestamp()} at the width the transaction record holds.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L470} moves the ten-character screen field into
     * {@code TRAN-PROC-TS PIC X(26)}. The rendering rule is the one
     * {@link #recordOriginTimestamp()} applies, with the separators
     * {@code app/cbl/CBTRN02C.cbl:L160-L174} declares for this field.
     *
     * @return the declared processing moment at {@value PicClause#TRAN_PROC_TS_WIDTH} characters, or
     *         {@code null} when {@link #processingTimestamp()} is absent or holds neither accepted
     *         form
     */
    public String recordProcessingTimestamp() {
        return atRecordWidth(processingTimestamp, START_OF_DAY_PROCESSING_TIME,
                PicClause.TRAN_PROC_TS_WIDTH);
    }

    /**
     * Renders one date component at the width its record field holds.
     *
     * @param value    the component to render; may be {@code null}
     * @param startOfDay the time part a ten-character value takes
     * @param width    the width the record field declares
     * @return the value at {@code width} characters, or {@code null} when the component is absent or
     *         holds neither the date nor the record form
     */
    private static String atRecordWidth(String value, String startOfDay, int width) {
        if (!supplied(value)) {
            return null;
        }
        String text = value.strip();
        if (text.length() == width) {
            return text;
        }
        if (text.length() != CobolDateValidator.TESTED_DATE_WIDTH) {
            return null;
        }
        return text + startOfDay;
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
        if (text.length() != width || !NumvalParser.isValidNumval(text)) {
            return null;
        }
        return text;
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

    /**
     * Renders this request with the card number masked and every other value withheld.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints every component, and one component holds a full Primary Account Number
     * (PAN). A framework, an exception, a debugger or a structured log line that renders a request
     * would publish the whole number.
     *
     * <p>The masked form comes from {@link PanMasker#maskCardNumber(String)}: twelve mask
     * characters then the last four digits. The decision path is untouched, because
     * {@link #canonicalCardNumber()} still returns all sixteen characters and the cross-reference
     * lookup keys on that value.
     *
     * <p>Six components are named without their values, so a reader can tell which arrived without
     * reading any of them. The amount, the merchant identifier, the merchant name, the merchant
     * city, the merchant zip code, the description and the account identifier appear as
     * {@value #WITHHELD} or {@value #ABSENT}.
     *
     * @return the masked and withheld form of this request, never {@code null}
     */
    @Override
    public String toString() {
        return "AuthorizationRequest[transactionId=" + present(transactionId)
                + ", transactionTypeCode=" + present(transactionTypeCode)
                + ", transactionCategoryCode=" + present(transactionCategoryCode)
                + ", source=" + present(source)
                + ", description=" + present(description)
                + ", amount=" + present(amount)
                + ", merchantId=" + present(merchantId)
                + ", merchantName=" + present(merchantName)
                + ", merchantCity=" + present(merchantCity)
                + ", merchantZip=" + present(merchantZip)
                + ", cardNumber=" + maskedCardNumber()
                + ", originTimestamp=" + present(originTimestamp)
                + ", processingTimestamp=" + present(processingTimestamp)
                + ", accountId=" + present(accountId) + "]";
    }

    /**
     * Returns the card number with every digit but the last four replaced.
     *
     * <p>A value that is absent yields {@value #ABSENT}. A value the sixteen-digit grammar rejects
     * yields {@value #WITHHELD} rather than a partial number, so a malformed component cannot leak
     * through the masker.
     *
     * @return the masked card number, {@value #ABSENT}, or {@value #WITHHELD}
     */
    private String maskedCardNumber() {
        if (!supplied(cardNumber)) {
            return ABSENT;
        }
        String canonical = canonicalCardNumber();
        if (canonical == null || canonical.length() != PanMasker.CARD_NUMBER_LENGTH) {
            return WITHHELD;
        }
        return PanMasker.maskCardNumber(canonical);
    }

    /**
     * Reports whether a component arrived, without disclosing what it holds.
     *
     * @param value the component to describe; may be {@code null}
     * @return {@value #WITHHELD} when the component arrived, {@value #ABSENT} when it did not
     */
    private static String present(String value) {
        return supplied(value) ? WITHHELD : ABSENT;
    }

    /**
     * Returns a component that holds content, or {@code null} for one that does not.
     *
     * @param value the component to normalise; may be {@code null}
     * @return {@code value} unchanged when it holds content, and {@code null} otherwise
     */
    private static String contentOrAbsent(String value) {
        return supplied(value) ? value : null;
    }
}

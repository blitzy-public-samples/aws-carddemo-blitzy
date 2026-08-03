package com.carddemo.events;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * The event one approved authorization call publishes, and the event three services consume.
 *
 * <p>Twelve payload components each carry one field of the 350-byte transaction record at
 * {@code app/cpy/CVTRA05Y.cpy}. Its daily-feed twin at {@code app/cpy/CVTRA06Y.cpy} declares the
 * same fields in the same order under a {@code DALYTRAN-} prefix.
 * {@code app/cbl/CBTRN02C.cbl:L425-L436} moves those twelve fields from the feed record onto the
 * posted record.
 *
 * <p>The wire form is flat. A serialized event holds eighteen properties in one JavaScript Object
 * Notation (JSON) object: the five {@link EventEnvelope} fields and the thirteen declared below. No
 * {@code envelope} key reaches a topic. The contract is
 * {@code schemas/transaction-authorized-v1.json}, and both ends validate against it.
 *
 * <p>Two components are ADDITIVE. {@link #maskedCardNumber()} takes its width and position from
 * {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15}. No source program masks a
 * card number, and {@code app/bms/COCRDSL.bms:L96-L99} defines the card detail field at the full
 * sixteen characters. A producer supplies the masked value, and this record masks nothing.
 * {@link #currency()} is ADDITIVE in full and always holds {@link #CURRENCY}.
 *
 * <p>Two source fields have no component here. The trailing {@code FILLER PIC X(20)} at
 * {@code app/cpy/CVTRA05Y.cpy:L18} is a deliberate omission, recorded in the traceability matrix.
 * {@code TRAN-PROC-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17} carries the posting time,
 * which {@code app/cbl/CBTRN02C.cbl:L437-L438} stamps later, so {@code TransactionPosted} carries
 * that field instead. All 300 records of {@code app/data/ASCII/dailytran.txt} leave it blank.
 *
 * <p>{@link #accountId()} reads {@code aggregateId} from the envelope, which holds the eleven-digit
 * identifier from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. That value is
 * the Kafka message key. One account identifier travels on the event, so the key and the payload
 * cannot disagree.
 *
 * <p>{@link #authorizedAt()} holds twenty-six characters shaped
 * {@code YYYY-MM-DD HH:MM:SS.ffffff}, from {@code TRAN-ORIG-TS PIC X(26)} at
 * {@code app/cpy/CVTRA05Y.cpy:L16}. A space separates the date from the time. The layout is not
 * ISO-8601. All 300 feed records carry that one layout.
 *
 * <p>{@link #amount()} is a {@link BigDecimal} at scale two and serializes as a decimal string,
 * never as a JSON number. Nine digits sit before the point, from
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}. A negative amount is
 * ordinary traffic: 50 of the 300 feed records hold one. The canonical constructor truncates
 * toward zero and never rounds half up.
 *
 * <p>The authorization service publishes this event to topic {@code transaction.authorized}. The
 * ledger-posting and fraud-detection services consume it in the consumer groups
 * {@code ledger-posting} and {@code fraud-detection}, and neither calls the other. Adding a
 * consumer needs no change here. For the path this event travels from publish to consume, read
 * {@code card-platform/docs/event-flow.md}; for the reasoning behind the choices above, read
 * {@code card-platform/docs/decision-log.md}.
 *
 * @param envelope             the five fields every event carries, serialized flat beside the
 *                             thirteen below
 * @param transactionId        the transaction identifier, sixteen characters. From
 *                             {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}
 * @param transactionTypeCode  the transaction type code, at most two characters. From
 *                             {@code TRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA05Y.cpy:L6}
 * @param merchantCategoryCode the merchant category code, four digits. From
 *                             {@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7}.
 *                             Leading zeros belong to the value
 * @param source               the channel that captured the transaction, at most ten characters.
 *                             From {@code TRAN-SOURCE PIC X(10)} at
 *                             {@code app/cpy/CVTRA05Y.cpy:L8}
 * @param description          the transaction description, at most one hundred characters. From
 *                             {@code TRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy:L9}
 * @param amount               the transaction amount at scale two, nine digits before the point.
 *                             From {@code TRAN-AMT PIC S9(09)V99} at
 *                             {@code app/cpy/CVTRA05Y.cpy:L10}
 * @param merchantId           the merchant identifier, nine digits. From
 *                             {@code TRAN-MERCHANT-ID PIC 9(09)} at
 *                             {@code app/cpy/CVTRA05Y.cpy:L11}. Leading zeros belong to the value
 * @param merchantName         the merchant name, at most fifty characters. From
 *                             {@code TRAN-MERCHANT-NAME PIC X(50)} at
 *                             {@code app/cpy/CVTRA05Y.cpy:L12}
 * @param merchantCity         the merchant city, at most fifty characters. From
 *                             {@code TRAN-MERCHANT-CITY PIC X(50)} at
 *                             {@code app/cpy/CVTRA05Y.cpy:L13}
 * @param merchantZip          the merchant postal code, at most ten characters. From
 *                             {@code TRAN-MERCHANT-ZIP PIC X(10)} at
 *                             {@code app/cpy/CVTRA05Y.cpy:L14}
 * @param maskedCardNumber     twelve asterisks then the last four digits, sixteen characters in
 *                             all. ADDITIVE. Width and position from
 *                             {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15}
 * @param authorizedAt         the authorization timestamp, twenty-six characters shaped
 *                             {@code YYYY-MM-DD HH:MM:SS.ffffff}. From
 *                             {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16}
 * @param currency             the currency of the amount, always {@link #CURRENCY}. ADDITIVE. No
 *                             source field exists
 */
public record TransactionAuthorized(@JsonUnwrapped EventEnvelope envelope, String transactionId,
        String transactionTypeCode, String merchantCategoryCode, String source, String description,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount, String merchantId,
        String merchantName, String merchantCity, String merchantZip, String maskedCardNumber,
        String authorizedAt, String currency) {

    /**
     * The routing discriminator every instance carries, and the simple name of this record.
     *
     * <p>{@code schemas/transaction-authorized-v1.json} pins {@code eventType} to this text with
     * {@code "const"}. The canonical constructor accepts no envelope carrying another value, and
     * {@link #of} stamps this one.
     */
    public static final String EVENT_TYPE = "TransactionAuthorized";

    /**
     * The currency every instance carries.
     *
     * <p>ADDITIVE. Neither {@code app/cpy/CVTRA05Y.cpy} nor {@code app/cpy/CVTRA06Y.cpy} declares
     * a currency field, and {@code schemas/transaction-authorized-v1.json} pins {@code currency}
     * to this text with {@code "const"}. The canonical constructor accepts no other value.
     */
    public static final String CURRENCY = "USD";

    /**
     * The fractional digits {@link #amount()} always carries.
     *
     * <p>Two, from the {@code V99} of {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:L10}.
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * The rounding every scale change in this record applies: truncation toward zero.
     *
     * <p>The {@code ROUNDED} phrase appears in none of the twenty-eight programs under
     * {@code app/cbl}, so every monetary store in the CardDemo source truncates.
     */
    public static final RoundingMode AMOUNT_ROUNDING = RoundingMode.DOWN;

    /**
     * The form {@link #amount()} takes on the wire: an optional minus, one to nine digits, a point
     * and two digits.
     *
     * <p>Nine integer digits from {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:L10}. The same pattern constrains {@code amount} in
     * {@code schemas/transaction-authorized-v1.json}. {@code TransactionPosted} carries a balance
     * of ten integer digits, and the two widths are not interchangeable.
     */
    public static final String AMOUNT_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /**
     * The form {@link #merchantCategoryCode()} takes: exactly four decimal digits.
     *
     * <p>Width from {@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7}.
     */
    public static final String MERCHANT_CATEGORY_CODE_PATTERN = "^[0-9]{4}$";

    /**
     * The form {@link #merchantId()} takes: exactly nine decimal digits.
     *
     * <p>Width from {@code TRAN-MERCHANT-ID PIC 9(09)} at {@code app/cpy/CVTRA05Y.cpy:L11}.
     */
    public static final String MERCHANT_ID_PATTERN = "^[0-9]{9}$";

    /**
     * The form {@link #maskedCardNumber()} takes: twelve asterisks then four decimal digits.
     *
     * <p>ADDITIVE. The sixteen-character total comes from {@code TRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L15}. A card number opens with a digit, so an unmasked value
     * fails this pattern.
     */
    public static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * The form {@link #authorizedAt()} takes: {@code YYYY-MM-DD HH:MM:SS.ffffff}.
     *
     * <p>A space separates the date from the time, colons separate the time parts, and a point
     * precedes six fractional digits. From {@code TRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L16}. The layout is not ISO-8601.
     */
    public static final String AUTHORIZED_AT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$";

    /** Characters in {@link #transactionId()}, from {@code TRAN-ID PIC X(16)}. */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /** Characters in {@link #maskedCardNumber()}, from {@code TRAN-CARD-NUM PIC X(16)}. */
    public static final int MASKED_CARD_NUMBER_LENGTH = 16;

    /** Characters in {@link #authorizedAt()}, from {@code TRAN-ORIG-TS PIC X(26)}. */
    public static final int AUTHORIZED_AT_LENGTH = 26;

    /** Ceiling on {@link #transactionTypeCode()}, from {@code TRAN-TYPE-CD PIC X(02)}. */
    public static final int TRANSACTION_TYPE_CODE_MAX_LENGTH = 2;

    /** Ceiling on {@link #source()}, from {@code TRAN-SOURCE PIC X(10)}. */
    public static final int SOURCE_MAX_LENGTH = 10;

    /** Ceiling on {@link #description()}, from {@code TRAN-DESC PIC X(100)}. */
    public static final int DESCRIPTION_MAX_LENGTH = 100;

    /** Ceiling on {@link #merchantName()}, from {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    public static final int MERCHANT_NAME_MAX_LENGTH = 50;

    /** Ceiling on {@link #merchantCity()}, from {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    public static final int MERCHANT_CITY_MAX_LENGTH = 50;

    /** Ceiling on {@link #merchantZip()}, from {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final int MERCHANT_ZIP_MAX_LENGTH = 10;

    /** {@link #AMOUNT_PATTERN} compiled. */
    private static final Pattern AMOUNT_MATCHER = Pattern.compile(AMOUNT_PATTERN);

    /** {@link #MERCHANT_CATEGORY_CODE_PATTERN} compiled. */
    private static final Pattern MERCHANT_CATEGORY_CODE_MATCHER =
            Pattern.compile(MERCHANT_CATEGORY_CODE_PATTERN);

    /** {@link #MERCHANT_ID_PATTERN} compiled. */
    private static final Pattern MERCHANT_ID_MATCHER = Pattern.compile(MERCHANT_ID_PATTERN);

    /** {@link #MASKED_CARD_NUMBER_PATTERN} compiled. */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /** {@link #AUTHORIZED_AT_PATTERN} compiled. */
    private static final Pattern AUTHORIZED_AT_MATCHER = Pattern.compile(AUTHORIZED_AT_PATTERN);

    /**
     * Checks all fourteen components and rejects a value
     * {@code schemas/transaction-authorized-v1.json} would reject.
     *
     * <p>Every exception message names the component that failed. The checks match the schema
     * document. A component it caps by length accepts any shorter value, including an empty one. A
     * component it constrains by pattern must match. Nothing here is stricter than the document, so
     * every event the document accepts deserializes.
     *
     * <p>{@code amount} is the one component this constructor changes. The amount arrives at any
     * scale and is stored at {@link #AMOUNT_SCALE} using {@link #AMOUNT_ROUNDING}, so
     * {@code 50.479} becomes {@code 50.47}. The stored value is then checked against
     * {@link #AMOUNT_PATTERN}, which rejects a tenth integer digit. Every other component is stored
     * as supplied, so a serialize and deserialize round trip returns an equal record.
     *
     * <p>A failure on {@code maskedCardNumber} reports the length of the rejected value and never
     * the value, so no card number reaches a log through a failure.
     *
     * @throws NullPointerException     when any component is {@code null}
     * @throws IllegalArgumentException when the envelope does not carry {@link #EVENT_TYPE}, when a
     *                                 text component breaks its width, when a component breaks its
     *                                 pattern, or when {@code currency} is not {@link #CURRENCY}
     */
    public TransactionAuthorized {
        Objects.requireNonNull(envelope, "envelope must be present");
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            throw new IllegalArgumentException("envelope.eventType must be \"" + EVENT_TYPE
                    + "\" and the supplied value is \"" + envelope.eventType() + "\"");
        }

        requireExactLength(transactionId, TRANSACTION_ID_LENGTH, "transactionId");
        requireMaxLength(transactionTypeCode, TRANSACTION_TYPE_CODE_MAX_LENGTH,
                "transactionTypeCode");
        requirePattern(merchantCategoryCode, MERCHANT_CATEGORY_CODE_MATCHER,
                MERCHANT_CATEGORY_CODE_PATTERN, "merchantCategoryCode");
        requireMaxLength(source, SOURCE_MAX_LENGTH, "source");
        requireMaxLength(description, DESCRIPTION_MAX_LENGTH, "description");

        amount = requireAmount(amount);

        requirePattern(merchantId, MERCHANT_ID_MATCHER, MERCHANT_ID_PATTERN, "merchantId");
        requireMaxLength(merchantName, MERCHANT_NAME_MAX_LENGTH, "merchantName");
        requireMaxLength(merchantCity, MERCHANT_CITY_MAX_LENGTH, "merchantCity");
        requireMaxLength(merchantZip, MERCHANT_ZIP_MAX_LENGTH, "merchantZip");
        requireMaskedForm(maskedCardNumber);
        requireExactLength(authorizedAt, AUTHORIZED_AT_LENGTH, "authorizedAt");
        requirePattern(authorizedAt, AUTHORIZED_AT_MATCHER, AUTHORIZED_AT_PATTERN, "authorizedAt");

        Objects.requireNonNull(currency, "currency must be present");
        if (!CURRENCY.equals(currency)) {
            throw new IllegalArgumentException("currency must be \"" + CURRENCY
                    + "\" and the supplied value is \"" + currency + "\"");
        }
    }

    /**
     * The account identifier the event belongs to, read from the envelope.
     *
     * <p>Returns {@code aggregateId}, which holds the eleven-digit identifier from
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} and is the Kafka message
     * key. The value is derived, not stored, so the key and the account a consumer posts to cannot
     * disagree. A serialized event carries the identifier once, under {@code aggregateId}, and
     * carries no {@code accountId} property.
     *
     * @return eleven decimal digits, with leading zeros
     */
    @JsonIgnore
    public String accountId() {
        return envelope.aggregateId();
    }

    /**
     * Builds an event, stamping the envelope and the currency a producer never chooses by hand.
     *
     * <p>The envelope comes from {@link EventEnvelope#of(String, String)} carrying
     * {@link #EVENT_TYPE}, so {@code eventId}, {@code schemaVersion} and {@code occurredAt} are
     * stamped here. {@code currency} takes {@link #CURRENCY}. The account identifier is supplied
     * once and becomes {@code aggregateId}, which {@link #accountId()} reads back.
     *
     * @param accountId            the eleven-digit account identifier, and the Kafka message key
     * @param transactionId        the transaction identifier, sixteen characters
     * @param transactionTypeCode  the transaction type code, at most two characters
     * @param merchantCategoryCode the merchant category code, four digits
     * @param source               the channel that captured the transaction
     * @param description          the transaction description
     * @param amount               the transaction amount, stored at {@link #AMOUNT_SCALE}
     * @param merchantId           the merchant identifier, nine digits
     * @param merchantName         the merchant name
     * @param merchantCity         the merchant city
     * @param merchantZip          the merchant postal code
     * @param maskedCardNumber     twelve asterisks then the last four digits
     * @param authorizedAt         the authorization timestamp, shaped
     *                             {@code YYYY-MM-DD HH:MM:SS.ffffff}
     * @return an event carrying the thirteen supplied values, a stamped envelope and
     *         {@link #CURRENCY}
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when an argument breaks its width or its pattern
     */
    public static TransactionAuthorized of(String accountId, String transactionId,
            String transactionTypeCode, String merchantCategoryCode, String source,
            String description, BigDecimal amount, String merchantId, String merchantName,
            String merchantCity, String merchantZip, String maskedCardNumber,
            String authorizedAt) {
        return new TransactionAuthorized(EventEnvelope.of(EVENT_TYPE, accountId), transactionId,
                transactionTypeCode, merchantCategoryCode, source, description, amount, merchantId,
                merchantName, merchantCity, merchantZip, maskedCardNumber, authorizedAt, CURRENCY);
    }

    /**
     * Stores an amount at {@link #AMOUNT_SCALE} and rejects one too wide for the source field.
     *
     * @param amount the amount as supplied, at any scale
     * @return the amount at {@link #AMOUNT_SCALE}
     * @throws NullPointerException     when {@code amount} is {@code null}
     * @throws IllegalArgumentException when the stored amount holds a tenth integer digit
     */
    private static BigDecimal requireAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must be present");

        BigDecimal stored = amount.setScale(AMOUNT_SCALE, AMOUNT_ROUNDING);
        String text = stored.toPlainString();
        if (!AMOUNT_MATCHER.matcher(text).matches()) {
            throw new IllegalArgumentException("amount must match " + AMOUNT_PATTERN
                    + " once stored at scale " + AMOUNT_SCALE + " and the supplied value stores as "
                    + text);
        }
        return stored;
    }

    /**
     * Requires a component to hold one exact number of characters.
     *
     * @param value     the component value
     * @param length    the required length
     * @param component the component name, used in the failure text
     * @throws NullPointerException     when {@code value} is {@code null}
     * @throws IllegalArgumentException when {@code value} holds another number of characters
     */
    private static void requireExactLength(String value, int length, String component) {
        Objects.requireNonNull(value, component + " must be present");

        if (value.length() != length) {
            throw new IllegalArgumentException(component + " must hold " + length
                    + " characters and the supplied value holds " + value.length());
        }
    }

    /**
     * Requires a component to stay within the width of its source field.
     *
     * <p>The schema document caps these components by length alone. A shorter value is accepted,
     * including an empty one.
     *
     * @param value     the component value
     * @param maximum   the greatest accepted length
     * @param component the component name, used in the failure text
     * @throws NullPointerException     when {@code value} is {@code null}
     * @throws IllegalArgumentException when {@code value} is longer than {@code maximum}
     */
    private static void requireMaxLength(String value, int maximum, String component) {
        Objects.requireNonNull(value, component + " must be present");

        if (value.length() > maximum) {
            throw new IllegalArgumentException(component + " must hold at most " + maximum
                    + " characters and the supplied value holds " + value.length());
        }
    }

    /**
     * Requires a component to match the pattern its schema property declares.
     *
     * @param value     the component value
     * @param matcher   the compiled pattern
     * @param pattern   the pattern text, used in the failure text
     * @param component the component name, used in the failure text
     * @throws NullPointerException     when {@code value} is {@code null}
     * @throws IllegalArgumentException when {@code value} does not match
     */
    private static void requirePattern(String value, Pattern matcher, String pattern,
            String component) {
        Objects.requireNonNull(value, component + " must be present");

        if (!matcher.matcher(value).matches()) {
            throw new IllegalArgumentException(component + " must match " + pattern
                    + " and the supplied value is \"" + value + "\"");
        }
    }

    /**
     * Requires the masked card component to carry twelve asterisks then four digits.
     *
     * <p>The failure text reports the length of the rejected value and never the value. An unmasked
     * card number opens with a digit, so it fails the pattern. A producer that forgets to mask
     * cannot publish, and the rejected value stays out of the message.
     *
     * @param value the component value
     * @throws NullPointerException     when {@code value} is {@code null}
     * @throws IllegalArgumentException when {@code value} does not match
     *                                  {@link #MASKED_CARD_NUMBER_PATTERN}
     */
    private static void requireMaskedForm(String value) {
        Objects.requireNonNull(value, "maskedCardNumber must be present");

        if (!MASKED_CARD_NUMBER_MATCHER.matcher(value).matches()) {
            throw new IllegalArgumentException("maskedCardNumber must hold "
                    + MASKED_CARD_NUMBER_LENGTH + " characters matching "
                    + MASKED_CARD_NUMBER_PATTERN + " and the supplied value holds "
                    + value.length() + " characters");
        }
    }
}

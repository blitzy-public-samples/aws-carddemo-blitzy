package com.carddemo.events;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * The event one approved authorization call publishes, and the event the ledger posting and fraud
 * detection services are to consume. The notification service reacts to what those two publish, so
 * three services react to one call.
 *
 * <p>Twelve payload components each carry one field of the 350-byte transaction record at
 * {@code app/cpy/CVTRA05Y.cpy}. Its daily-feed twin at {@code app/cpy/CVTRA06Y.cpy} declares the
 * same fields in the same order under a {@code DALYTRAN-} prefix.
 * {@code app/cbl/CBTRN02C.cbl:L425-L436} moves those twelve fields from the feed record onto the
 * posted record.
 *
 * <p>The wire form is flat. A serialized event holds its properties in one JavaScript Object
 * Notation (JSON) object: the five envelope fields declared first below and the payload fields that
 * follow them. No {@code envelope} key reaches a topic. Two contracts govern the event, one per
 * version. {@code schemas/transaction-authorized-v1.json} lists nineteen names and
 * {@code schemas/transaction-authorized-v2.json} lists twenty, adding {@link #cardToken()}. Each
 * document names every field of its version in one {@code required} array and sets
 * {@code additionalProperties} to {@code false}. Both ends validate against the document the
 * {@code schemaVersion} of the event selects.
 *
 * <p>Version 2 is the version a producer stamps, and {@link #CARD_TOKEN_SCHEMA_VERSION} names it.
 * Version 1 stays governed and readable, so a consumer written against it is not broken by the
 * addition. A version 1 event carries no card token and {@link #cardToken()} answers {@code null}
 * for it.
 *
 * <p>Three components are ADDITIVE. {@link #maskedCardNumber()} takes its width and position from
 * {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15}. No source program masks a
 * card number, and {@code app/bms/COCRDSL.bms:L96-L99} defines the card detail field at the full
 * sixteen characters. A producer supplies the masked value, and this record masks nothing.
 * {@link #cardToken()} is the card identity a masked card number cannot supply: twelve of its
 * sixteen characters are the mask, so two cards ending in the same four digits mask alike. A
 * consumer that keys rows on a card keys them on the token.
 * {@link #currency()} is ADDITIVE in full and always holds {@link #CURRENCY}.
 *
 * <p>Two source fields have no component here. The trailing {@code FILLER PIC X(20)} at
 * {@code app/cpy/CVTRA05Y.cpy:L18} is a deliberate omission, recorded in the traceability matrix.
 * {@code TRAN-PROC-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17} carries the posting time,
 * which {@code app/cbl/CBTRN02C.cbl:L437-L438} stamps later, so {@code TransactionPosted} carries
 * that field instead. All 300 records of {@code app/data/ASCII/dailytran.txt} leave it blank.
 *
 * <p>{@link #accountId()} and {@link #aggregateId()} both hold the eleven-digit identifier from
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, and {@code aggregateId} is the
 * Kafka message key. The canonical constructor rejects two differing values, so the key and the
 * payload cannot disagree.
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
 * ledger-posting and fraud-detection services read it in the consumer groups
 * {@code ledger-posting} and {@code fraud-detection}, and neither calls the other. Adding a
 * consumer needs no change here.
 *
 * @param eventId              the idempotency key each consumer records before it applies side
 *                             effects, a Universally Unique Identifier (UUID)
 * @param eventType            the routing discriminator, always {@link #EVENT_TYPE}
 * @param schemaVersion        the contract version, either {@link EventEnvelope#SCHEMA_VERSION} or
 *                             {@link #CARD_TOKEN_SCHEMA_VERSION}. A producer stamps the second
 * @param occurredAt           the moment the producer wrote the event, in Coordinated Universal
 *                             Time
 * @param aggregateId          the eleven-digit account identifier, and the Kafka message key. From
 *                             {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
 * @param transactionId        the transaction identifier, sixteen characters. From
 *                             {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}
 * @param accountId            the account identifier the card resolved to, eleven digits. From
 *                             {@code XREF-ACCT-ID PIC 9(11)} at
 *                             {@code app/cpy/CVACT03Y.cpy:L7}. Always equal to the
 *                             {@code aggregateId} of {@code envelope}. Leading zeros belong to the
 *                             value
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
 *                             {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15}.
 *                             Display data, and never an identity: two cards ending alike share one
 *                             masked value
 * @param cardToken            the card identity, {@value #CARD_TOKEN_LENGTH} lower-case
 *                             hexadecimal characters derived from the full card number. ADDITIVE.
 *                             No source field exists. Required under
 *                             {@link #CARD_TOKEN_SCHEMA_VERSION} and absent under
 *                             {@link EventEnvelope#SCHEMA_VERSION}. A consumer keys a card-scoped
 *                             row, route or authority on this value
 * @param authorizedAt         the authorization timestamp, twenty-six characters shaped
 *                             {@code YYYY-MM-DD HH:MM:SS.ffffff}. From
 *                             {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16}
 * @param accountId            the same eleven-digit account identifier {@code aggregateId} carries.
 *                             From {@code XREF-ACCT-ID PIC 9(11)} at
 *                             {@code app/cpy/CVACT03Y.cpy:L7}. Leading zeros belong to the value
 * @param currency             the currency of the amount, always {@link #CURRENCY}. ADDITIVE. No
 *                             source field exists
 */
public record TransactionAuthorized(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String transactionId,
        String transactionTypeCode,
        String merchantCategoryCode,
        String source,
        String description,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String maskedCardNumber,
        @JsonInclude(JsonInclude.Include.NON_NULL) String cardToken,
        String authorizedAt,
        String accountId,
        String currency) {

    /**
     * The routing discriminator every instance carries, and the simple name of this record.
     *
     * <p>{@code schemas/transaction-authorized-v1.json} pins {@code eventType} to this text with
     * {@code "const"}. The canonical constructor accepts no other value, and {@link #of} stamps
     * this one.
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
     * The contract version that carries {@link #cardToken()}, and the version a producer stamps.
     *
     * <p>{@code schemas/transaction-authorized-v2.json} pins {@code schemaVersion} to this number.
     * Version {@link EventEnvelope#SCHEMA_VERSION} stays governed by
     * {@code schemas/transaction-authorized-v1.json} and carries no card token, so a consumer
     * written against version 1 keeps reading version 1 events unchanged.
     */
    public static final int CARD_TOKEN_SCHEMA_VERSION = 2;

    /**
     * The characters {@link #cardToken()} holds: the hexadecimal rendering of a SHA-256 digest.
     *
     * <p>{@code PanMasker.cardToken} in {@code card-platform/libs/cobol-compat} derives the value,
     * and this module declares the width rather than depending on that module.
     */
    public static final int CARD_TOKEN_LENGTH = 64;

    /**
     * The form {@link #cardToken()} takes: exactly {@value #CARD_TOKEN_LENGTH} lower-case
     * hexadecimal characters.
     *
     * <p>The same pattern constrains {@code cardToken} in
     * {@code schemas/transaction-authorized-v2.json}.
     */
    public static final String CARD_TOKEN_PATTERN = "^[0-9a-f]{64}$";

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

    /**
     * The characters a free-text component may hold: printable ones and nothing else.
     *
     * <p>The range runs from the space at {@code 0x20} to the tilde at {@code 0x7E}, so
     * every C0 control character is outside it, carriage return and line feed included.
     * All three hundred records of {@code app/data/ASCII/dailytran.txt} hold characters
     * from this range alone, measured across every text field of
     * {@code app/cpy/CVTRA06Y.cpy}, so the range refuses nothing the source carries.
     *
     * <p>The check is here as well as at ingress because this record is the contract, and a
     * contract that admits a carriage return admits a forged line in the fixed-width alert
     * record {@code app/cbl/CBSTM03A.CBL:L86-L159} lays out. A producer that reached this
     * constructor by another route than the request DTO is bound by the same rule.
     */
    public static final String PRINTABLE_TEXT_PATTERN = "^[ -~]*$";

    /** {@link #PRINTABLE_TEXT_PATTERN} compiled, and the check each text component runs. */
    private static final Pattern PRINTABLE_TEXT_MATCHER =
            Pattern.compile(PRINTABLE_TEXT_PATTERN);

    /** Ceiling on {@link #merchantName()}, from {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    public static final int MERCHANT_NAME_MAX_LENGTH = 50;

    /** Ceiling on {@link #merchantCity()}, from {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    public static final int MERCHANT_CITY_MAX_LENGTH = 50;

    /** Ceiling on {@link #merchantZip()}, from {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final int MERCHANT_ZIP_MAX_LENGTH = 10;

    /**
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN} compiled, and the check {@link #accountId()} runs.
     *
     * <p>Reusing the envelope constant keeps one pattern behind both account identifiers.
     */
    private static final Pattern ACCOUNT_ID_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

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

    /** {@link #CARD_TOKEN_PATTERN} compiled. */
    private static final Pattern CARD_TOKEN_MATCHER = Pattern.compile(CARD_TOKEN_PATTERN);

    /**
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN} compiled, and the check both account identifiers
     * run.
     */
    private static final Pattern ACCOUNT_IDENTIFIER_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

    /**
     * Checks every component and rejects a value the schema document of its version would reject.
     *
     * <p>Every exception message names the component that failed. The checks match the schema
     * document. A component it caps by length accepts any shorter value, including an empty one. A
     * component it constrains by pattern must match. Nothing here is stricter than the document, so
     * every event the document accepts deserializes.
     *
     * <p>{@code cardToken} is the one component whose rule depends on the version. Under
     * {@link #CARD_TOKEN_SCHEMA_VERSION} it must match {@link #CARD_TOKEN_PATTERN}, and under
     * {@link EventEnvelope#SCHEMA_VERSION} it must be absent, because version 1 declares no such
     * property and closes its property set.
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
     * @throws NullPointerException     when any component other than {@code cardToken} is
     *                                 {@code null}
     * @throws IllegalArgumentException when {@code eventType} is not {@link #EVENT_TYPE}, when
     *                                 {@code schemaVersion} is neither
     *                                 {@link EventEnvelope#SCHEMA_VERSION} nor
     *                                 {@link #CARD_TOKEN_SCHEMA_VERSION}, when either account
     *                                 identifier is not eleven decimal digits, when the two account
     *                                 identifiers differ, when a text component breaks its width,
     *                                 when a component breaks its pattern, when {@code cardToken}
     *                                 disagrees with the version, or when {@code currency} is not
     *                                 {@link #CURRENCY}
     */
    public TransactionAuthorized {
        Objects.requireNonNull(eventId, "eventId must be present");
        Objects.requireNonNull(eventType, "eventType must be present");
        Objects.requireNonNull(occurredAt, "occurredAt must be present");

        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be \"" + EVENT_TYPE
                    + "\" and the supplied value is \"" + eventType + "\"");
        }
        if (schemaVersion != EventEnvelope.SCHEMA_VERSION
                && schemaVersion != CARD_TOKEN_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be "
                    + EventEnvelope.SCHEMA_VERSION + " or " + CARD_TOKEN_SCHEMA_VERSION
                    + " and the supplied value is " + schemaVersion);
        }

        requireAccountIdentifier(aggregateId, "aggregateId");
        if (accountId == null) {
            accountId = aggregateId;
        }
        requireAccountIdentifier(accountId, "accountId");
        if (!aggregateId.equals(accountId)) {
            throw new IllegalArgumentException("aggregateId and accountId must hold one account "
                    + "identifier and the two supplied values differ");
        }

        requireExactLength(transactionId, TRANSACTION_ID_LENGTH, "transactionId");
        requireMaxLength(transactionTypeCode, TRANSACTION_TYPE_CODE_MAX_LENGTH,
                "transactionTypeCode");
        requirePattern(merchantCategoryCode, MERCHANT_CATEGORY_CODE_MATCHER,
                MERCHANT_CATEGORY_CODE_PATTERN, "merchantCategoryCode");
        requireMaxLength(source, SOURCE_MAX_LENGTH, "source");
        requireMaxLength(description, DESCRIPTION_MAX_LENGTH, "description");

        // No control character reaches a consumer that lays this text out by column.
        requirePrintable(transactionId, "transactionId");
        requirePrintable(transactionTypeCode, "transactionTypeCode");
        requirePrintable(source, "source");
        requirePrintable(description, "description");

        amount = requireAmount(amount);

        requirePattern(merchantId, MERCHANT_ID_MATCHER, MERCHANT_ID_PATTERN, "merchantId");
        requireMaxLength(merchantName, MERCHANT_NAME_MAX_LENGTH, "merchantName");
        requireMaxLength(merchantCity, MERCHANT_CITY_MAX_LENGTH, "merchantCity");
        requireMaxLength(merchantZip, MERCHANT_ZIP_MAX_LENGTH, "merchantZip");
        requirePrintable(merchantName, "merchantName");
        requirePrintable(merchantCity, "merchantCity");
        requirePrintable(merchantZip, "merchantZip");
        requireMaskedForm(maskedCardNumber);
        requireCardTokenOfVersion(cardToken, schemaVersion);
        requireExactLength(authorizedAt, AUTHORIZED_AT_LENGTH, "authorizedAt");
        requirePattern(authorizedAt, AUTHORIZED_AT_MATCHER, AUTHORIZED_AT_PATTERN, "authorizedAt");

        Objects.requireNonNull(currency, "currency must be present");
        if (!CURRENCY.equals(currency)) {
            throw new IllegalArgumentException("currency must be \"" + CURRENCY
                    + "\" and the supplied value is \"" + currency + "\"");
        }
    }

    /**
     * The five envelope components, as the carrier a producer builds and a consumer routes on.
     *
     * <p>The returned envelope holds the values this record already carries, so the two cannot
     * disagree. Serialization ignores the method, and a serialized event carries no
     * {@code envelope} key.
     *
     * @return an envelope holding {@code eventId}, {@code eventType}, {@code schemaVersion},
     *         {@code occurredAt} and {@code aggregateId}
     */
    public EventEnvelope envelope() {
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);
    }

    /**
     * Checks one account identifier against {@link EventEnvelope#AGGREGATE_ID_PATTERN}.
     *
     * @param value     the identifier to check
     * @param component the component name the failure message reports
     * @throws IllegalArgumentException when {@code value} is {@code null} or is not eleven decimal
     *                                  digits. The message reports the length and never the value
     */
    private static void requireAccountIdentifier(String value, String component) {
        if (value == null || !ACCOUNT_IDENTIFIER_MATCHER.matcher(value).matches()) {
            throw new IllegalArgumentException(component + " must match "
                    + EventEnvelope.AGGREGATE_ID_PATTERN + " and the supplied value "
                    + (value == null ? "is null" : "holds " + value.length() + " characters"));
        }
    }

    /**
     * Builds an event, stamping the envelope, the version and the currency a producer never chooses
     * by hand.
     *
     * <p>The envelope comes from {@link EventEnvelope#of(String, String, int)} carrying
     * {@link #EVENT_TYPE} and {@link #CARD_TOKEN_SCHEMA_VERSION}, so {@code eventId},
     * {@code schemaVersion} and {@code occurredAt} are stamped here. {@code currency} takes
     * {@link #CURRENCY}. The account identifier is supplied once and becomes both
     * {@code aggregateId} and {@code accountId}.
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
     * @param maskedCardNumber     twelve asterisks then the last four digits, display data only
     * @param cardToken            the card identity, {@value #CARD_TOKEN_LENGTH} lower-case
     *                             hexadecimal characters
     * @param authorizedAt         the authorization timestamp, shaped
     *                             {@code YYYY-MM-DD HH:MM:SS.ffffff}
     * @return an event carrying the fourteen supplied values, a stamped envelope at
     *         {@link #CARD_TOKEN_SCHEMA_VERSION}, the account identifier under both names, and
     *         {@link #CURRENCY}
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when an argument breaks its width or its pattern
     */
    public static TransactionAuthorized of(String accountId, String transactionId,
            String transactionTypeCode, String merchantCategoryCode, String source,
            String description, BigDecimal amount, String merchantId, String merchantName,
            String merchantCity, String merchantZip, String maskedCardNumber, String cardToken,
            String authorizedAt) {
        EventEnvelope envelope =
                EventEnvelope.of(EVENT_TYPE, accountId, CARD_TOKEN_SCHEMA_VERSION);

        return new TransactionAuthorized(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                transactionId, transactionTypeCode, merchantCategoryCode, source, description,
                amount, merchantId, merchantName, merchantCity, merchantZip, maskedCardNumber,
                cardToken, authorizedAt, envelope.aggregateId(), CURRENCY);
    }

    /**
     * Checks {@code cardToken} against the version that carries it.
     *
     * <p>Under {@link #CARD_TOKEN_SCHEMA_VERSION} the token is required and must match
     * {@link #CARD_TOKEN_PATTERN}. Under {@link EventEnvelope#SCHEMA_VERSION} it must be absent,
     * because {@code schemas/transaction-authorized-v1.json} declares no such property and sets
     * {@code additionalProperties} to {@code false}.
     *
     * <p>The failure message reports the length of a rejected value and never the value, matching
     * the discipline every card-bearing component of this record follows.
     *
     * @param cardToken     the token to check, or {@code null}
     * @param schemaVersion the version the event carries
     * @throws IllegalArgumentException when the token disagrees with the version
     */
    private static void requireCardTokenOfVersion(String cardToken, int schemaVersion) {
        if (schemaVersion == CARD_TOKEN_SCHEMA_VERSION) {
            if (cardToken == null || !CARD_TOKEN_MATCHER.matcher(cardToken).matches()) {
                throw new IllegalArgumentException("cardToken must match " + CARD_TOKEN_PATTERN
                        + " under schemaVersion " + CARD_TOKEN_SCHEMA_VERSION
                        + " and the supplied value " + (cardToken == null ? "is null"
                                : "holds " + cardToken.length() + " characters"));
            }
            return;
        }

        if (cardToken != null) {
            throw new IllegalArgumentException("cardToken must be absent under schemaVersion "
                    + EventEnvelope.SCHEMA_VERSION + ", which declares no such property, and a"
                    + " value of " + cardToken.length() + " characters was supplied");
        }
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
                    + " once stored at scale " + AMOUNT_SCALE
                    + " and the supplied value stores as " + text.length()
                    + " characters with precision " + stored.precision() + " and scale "
                    + stored.scale());
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
    /**
     * Rejects a component holding a character outside {@link #PRINTABLE_TEXT_PATTERN}.
     *
     * <p>The message reports the position of the first offending character and its code
     * point, and never the surrounding text, so a failure carries no cardholder value into
     * a log. A position is what a caller needs in order to find the character it sent.
     *
     * @param value     the component to check, already known to be present
     * @param component the component name for the message
     * @throws IllegalArgumentException when the value holds a character outside the range
     */
    private static void requirePrintable(String value, String component) {
        if (PRINTABLE_TEXT_MATCHER.matcher(value).matches()) {
            return;
        }
        int position = 0;
        while (position < value.length()) {
            char character = value.charAt(position);
            if (character < ' ' || character > '~') {
                break;
            }
            position++;
        }
        throw new IllegalArgumentException(component + " must match " + PRINTABLE_TEXT_PATTERN
                + " and the supplied value holds code point "
                + (int) value.charAt(position) + " at position " + position);
    }

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
                    + " and the supplied value holds " + value.length() + " characters");
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
    /**
     * Renders the technical identifiers and withholds every value the payload carries.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the amount, the merchant identifier, the merchant name, the merchant
     * city, the merchant zip code, the description and the masked card number, and it prints the
     * account identifier the envelope holds.
     *
     * <p>Ten components appear as {@link EventEnvelope#WITHHELD}. The five envelope fields and the
     * transaction identifier render themselves, because none of the six carries cardholder data.
     *
     * @return the identifiers of this event with every payload value withheld, never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionAuthorized[eventId=" + eventId + ", eventType=" + eventType
                + ", schemaVersion=" + schemaVersion + ", occurredAt=" + occurredAt
                + ", aggregateId=" + EventEnvelope.WITHHELD
                + ", transactionId=" + transactionId
                + ", transactionTypeCode=" + EventEnvelope.WITHHELD + ", merchantCategoryCode="
                + EventEnvelope.WITHHELD + ", source=" + EventEnvelope.WITHHELD + ", description="
                + EventEnvelope.WITHHELD + ", amount=" + EventEnvelope.WITHHELD + ", merchantId="
                + EventEnvelope.WITHHELD + ", merchantName=" + EventEnvelope.WITHHELD
                + ", merchantCity=" + EventEnvelope.WITHHELD + ", merchantZip="
                + EventEnvelope.WITHHELD + ", maskedCardNumber=" + EventEnvelope.WITHHELD
                + ", cardToken=" + EventEnvelope.WITHHELD
                + ", authorizedAt=" + EventEnvelope.WITHHELD + ", accountId="
                + EventEnvelope.WITHHELD + ", currency=" + currency + "]";
    }
}

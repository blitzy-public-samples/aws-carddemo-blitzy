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
 * The event the ledger-posting service publishes after it applies one posting to an account
 * balance.
 *
 * <p>The notification service reads it from the {@code transaction.posted} topic, builds its
 * card-keyed read model from it and renders a cardholder alert. Two contracts govern the event, one
 * per version. {@code schemas/transaction-posted-v1.json} declares the balance and the four fields
 * that identify the posting. {@code schemas/transaction-posted-v2.json} adds the card token and the
 * nine remaining fields of the posted transaction record, so one event carries the whole record a
 * card-keyed consumer stores. Version 2 is the version a producer stamps, and version 1 stays
 * governed and readable, so a consumer written against it is not broken by the addition.
 *
 * <p>The ten components version 2 adds answer a plain question: what does a consumer keyed on a
 * card store, and where does it come from? {@code app/jcl/CREASTMT.JCL} answers it in the source by
 * sorting and copying the whole posted transaction record into a card-keyed copy, so the consumer
 * that replaces that job needs the whole record. A field the event does not carry would be stored
 * blank, and a blank field read back as a transaction fact is a false one.
 *
 * <p>Provenance is one paragraph of one program. {@code 2800-UPDATE-ACCOUNT-REC} opens at
 * {@code app/cbl/CBTRN02C.cbl:L545} and adds the transaction amount to {@code ACCT-CURR-BAL} at
 * {@code app/cbl/CBTRN02C.cbl:L547}. {@link #newBalance()} carries that field after the add. Its
 * width comes from {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}, so ten
 * digits sit ahead of the decimal point.
 *
 * <p>The same paragraph routes the amount into one of two billing-cycle accumulators at
 * {@code app/cbl/CBTRN02C.cbl:L548-L551}. Those two fields sit at
 * {@code app/cpy/CVACT01Y.cpy:L13-L14} and no event of this module carries either one.
 *
 * <p>Four payload fields come from the posted transaction record. {@code TRAN-ID PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:L5} gives {@link #transactionId()} and
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10} gives {@link #amount()}.
 * {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15} gives
 * {@link #maskedCardNumber()} and {@code TRAN-PROC-TS PIC X(26)} at
 * {@code app/cpy/CVTRA05Y.cpy:L17} gives {@link #postedAt()}.
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} gives {@link #accountId()} and
 * {@link #aggregateId()}, which hold one value. The trailing filler of both records,
 * {@code FILLER PIC X(178)} at {@code app/cpy/CVACT01Y.cpy:L17} and {@code FILLER PIC X(20)} at
 * {@code app/cpy/CVTRA05Y.cpy:L18}, is a deliberate omission.
 *
 * <p>Nine further payload fields of version 2 come from the same posted transaction record:
 * {@code TRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA05Y.cpy:L6},
 * {@code TRAN-CAT-CD PIC 9(04)} at L7, {@code TRAN-SOURCE PIC X(10)} at L8,
 * {@code TRAN-DESC PIC X(100)} at L9, {@code TRAN-MERCHANT-ID PIC 9(09)} at L11,
 * {@code TRAN-MERCHANT-NAME PIC X(50)} at L12, {@code TRAN-MERCHANT-CITY PIC X(50)} at L13,
 * {@code TRAN-MERCHANT-ZIP PIC X(10)} at L14 and {@code TRAN-ORIG-TS PIC X(26)} at L16. Together
 * with the four fields above they are the twelve values
 * {@code app/cbl/CBTRN02C.cbl:L425-L436} moves onto the posted record.
 *
 * <p>{@link #maskedCardNumber()} and {@link #cardToken()} are ADDITIVE. No CardDemo program masks a
 * Primary Account Number (PAN): {@code app/bms/COCRDSL.bms:L96-L99} defines the card detail field at
 * the full sixteen characters. Masking happens where the event is serialized, so the field reaches
 * this record already masked. A masked card number is display data and identifies nothing, because
 * twelve of its sixteen characters are the mask; {@link #cardToken()} is the identity a card-keyed
 * consumer stores, routes on and authorizes against. The five envelope fields are ADDITIVE in full
 * and {@link EventEnvelope} defines them.
 *
 * <p>{@link #postedAt()} is text, not a temporal type, and its layout is not ISO-8601. Twenty-six
 * characters run {@code YYYY-MM-DD-HH.MM.SS.NN0000}, with the three dashes moved at
 * {@code app/cbl/CBTRN02C.cbl:L702} and the three dots at {@code app/cbl/CBTRN02C.cbl:L703}.
 * Precision is hundredths of a second: {@code DB2-MIL PIC 9(002)} at
 * {@code app/cbl/CBTRN02C.cbl:L173} holds two digits and {@code app/cbl/CBTRN02C.cbl:L701} moves
 * four zeros behind them. A consumer truncates to hundredths before it compares two values byte
 * for byte.
 *
 * <p>Both money fields are {@link BigDecimal} and both travel as decimal strings with two
 * fractional digits, never as JSON numbers. Every scale change applies {@link #MONEY_ROUNDING}.
 * The two integer widths differ, and {@link #TEN_INTEGER_DIGIT_BALANCE_PATTERN} and
 * {@link #NINE_INTEGER_DIGIT_AMOUNT_PATTERN} keep them apart. Negative values are ordinary traffic
 * on both fields.
 *
 * <p>The wire form is flat. A serialized event holds the five envelope fields and the payload fields
 * of its version in one JavaScript Object Notation (JSON) object, so no {@code envelope} key reaches
 * a topic. {@link #envelope()} returns the carrier a producer builds and passes.
 *
 * @param eventId          the idempotency key, a Universally Unique Identifier (UUID) that
 *                         serializes as thirty-six lower-case characters. ADDITIVE
 * @param eventType        the routing discriminator, always {@link #EVENT_TYPE}. ADDITIVE
 * @param schemaVersion    the contract version, either {@link EventEnvelope#SCHEMA_VERSION} or
 *                         {@link #TRANSACTION_DETAIL_SCHEMA_VERSION}. A producer stamps the second.
 *                         ADDITIVE
 * @param occurredAt       the moment the ledger wrote the event, serialized in Coordinated
 *                         Universal Time. ADDITIVE
 * @param aggregateId      the eleven-digit account identifier and the Kafka message key, from
 *                         {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
 * @param transactionId    the sixteen-character transaction identifier, from
 *                         {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}
 * @param accountId        the same eleven-digit account identifier {@code aggregateId} carries,
 *                         from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
 *                         {@code schemas/transaction-posted-v1.json} declares and requires this
 *                         property beside {@code aggregateId}, and the canonical constructor
 *                         refuses two differing values, so the message key and the payload cannot
 *                         disagree
 * @param newBalance       the account balance after the posting, at scale two and ten integer
 *                         digits, from {@code ACCT-CURR-BAL PIC S9(10)V99} at
 *                         {@code app/cpy/CVACT01Y.cpy:L7} after the add at
 *                         {@code app/cbl/CBTRN02C.cbl:L547}
 * @param postedAt         the twenty-six-character posting timestamp, from
 *                         {@code TRAN-PROC-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17}
 * @param amount           the amount that moved the balance, at scale two and nine integer digits,
 *                         from {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}
 * @param maskedCardNumber the card number with twelve mask characters ahead of its last four
 *                         digits, at the width of {@code TRAN-CARD-NUM PIC X(16)} at
 *                         {@code app/cpy/CVTRA05Y.cpy:L15}. Display data, never an identity.
 *                         ADDITIVE
 * @param cardToken        the card identity, {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal
 *                         characters derived from the full card number. ADDITIVE. Required under
 *                         {@link #TRANSACTION_DETAIL_SCHEMA_VERSION} and absent under
 *                         {@link EventEnvelope#SCHEMA_VERSION}
 * @param transactionTypeCode  the transaction type code, at most
 *                         {@value #TRANSACTION_TYPE_CODE_MAX_LENGTH} characters, from
 *                         {@code TRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA05Y.cpy:L6}.
 *                         Required under {@link #TRANSACTION_DETAIL_SCHEMA_VERSION} and absent
 *                         under {@link EventEnvelope#SCHEMA_VERSION}
 * @param merchantCategoryCode the merchant category code, four digits, from
 *                         {@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7}. Leading
 *                         zeros belong to the value. Version-conditional as above
 * @param source           the channel that captured the transaction, at most
 *                         {@value #SOURCE_MAX_LENGTH} characters, from
 *                         {@code TRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8}.
 *                         Version-conditional as above
 * @param description      the transaction description, at most {@value #DESCRIPTION_MAX_LENGTH}
 *                         characters, from {@code TRAN-DESC PIC X(100)} at
 *                         {@code app/cpy/CVTRA05Y.cpy:L9}. Version-conditional as above
 * @param merchantId       the merchant identifier, nine digits, from
 *                         {@code TRAN-MERCHANT-ID PIC 9(09)} at {@code app/cpy/CVTRA05Y.cpy:L11}.
 *                         Version-conditional as above
 * @param merchantName     the merchant name, at most {@value #MERCHANT_NAME_MAX_LENGTH}
 *                         characters, from {@code TRAN-MERCHANT-NAME PIC X(50)} at
 *                         {@code app/cpy/CVTRA05Y.cpy:L12}. Version-conditional as above
 * @param merchantCity     the merchant city, at most {@value #MERCHANT_CITY_MAX_LENGTH}
 *                         characters, from {@code TRAN-MERCHANT-CITY PIC X(50)} at
 *                         {@code app/cpy/CVTRA05Y.cpy:L13}. Version-conditional as above
 * @param merchantZip      the merchant postal code, at most {@value #MERCHANT_ZIP_MAX_LENGTH}
 *                         characters, from {@code TRAN-MERCHANT-ZIP PIC X(10)} at
 *                         {@code app/cpy/CVTRA05Y.cpy:L14}. Version-conditional as above
 * @param originTimestamp  the moment the transaction originated, twenty-six characters shaped
 *                         {@code YYYY-MM-DD HH:MM:SS.ffffff}, from
 *                         {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16}. A
 *                         space separates the date from the time, which is a different layout from
 *                         {@link #postedAt()}. Version-conditional as above
 */
public record TransactionPosted(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String transactionId,
        String accountId,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal newBalance,
        String postedAt,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
        String maskedCardNumber,
        @JsonInclude(JsonInclude.Include.NON_NULL) String cardToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String transactionTypeCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String merchantCategoryCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String source,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String merchantId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String merchantName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String merchantCity,
        @JsonInclude(JsonInclude.Include.NON_NULL) String merchantZip,
        @JsonInclude(JsonInclude.Include.NON_NULL) String originTimestamp) {

    /**
     * The one value {@link #eventType()} accepts.
     *
     * <p>{@code schemas/transaction-posted-v1.json} pins the same text as the {@code const} of its
     * {@code eventType} property and as its {@code title}.
     */
    public static final String EVENT_TYPE = "TransactionPosted";

    /**
     * The scale both money fields carry: two fractional digits.
     *
     * <p>From the {@code V99} of {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L7} and of {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:L10}.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The rounding mode every scale change in this record applies.
     *
     * <p>The {@code ROUNDED} phrase appears in no CardDemo program, so every monetary store in the
     * source truncates toward zero. A scale change here drops a third fractional digit and never
     * carries it upward.
     */
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.DOWN;

    /** The width of {@link #transactionId()}, from {@code TRAN-ID PIC X(16)}. */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /** The width of {@link #postedAt()}, from {@code TRAN-PROC-TS PIC X(26)}. */
    public static final int POSTED_AT_LENGTH = 26;

    /** The width of {@link #maskedCardNumber()}, from {@code TRAN-CARD-NUM PIC X(16)}. */
    public static final int MASKED_CARD_NUMBER_LENGTH = 16;

    /**
     * The contract version that carries the card token and the nine remaining transaction fields,
     * and the version a producer stamps.
     *
     * <p>{@code schemas/transaction-posted-v2.json} pins {@code schemaVersion} to this number.
     * Version {@link EventEnvelope#SCHEMA_VERSION} stays governed by
     * {@code schemas/transaction-posted-v1.json} and carries none of the ten, so a consumer written
     * against version 1 keeps reading version 1 events unchanged.
     */
    public static final int TRANSACTION_DETAIL_SCHEMA_VERSION = 2;

    /**
     * The same contract version as {@link #TRANSACTION_DETAIL_SCHEMA_VERSION}, under its shorter
     * name.
     *
     * <p>Both names are read across the platform, so both resolve to the one value the file name
     * suffix of {@code schemas/transaction-posted-v2.json} declares.</p>
     */
    public static final int DETAIL_SCHEMA_VERSION = TRANSACTION_DETAIL_SCHEMA_VERSION;

    /**
     * The characters {@link #cardToken()} holds: the hexadecimal rendering of a SHA-256 digest.
     *
     * <p>{@code PanMasker.cardToken} in {@code card-platform/libs/cobol-compat} derives the value,
     * and this module declares the width rather than depending on that module.
     */
    public static final int CARD_TOKEN_LENGTH = 64;

    /** The width of {@link #originTimestamp()}, from {@code TRAN-ORIG-TS PIC X(26)}. */
    public static final int ORIGIN_TIMESTAMP_LENGTH = 26;

    /** The width of {@link #transactionTypeCode()}, from {@code TRAN-TYPE-CD PIC X(02)}. */
    public static final int TRANSACTION_TYPE_CODE_MAX_LENGTH = 2;

    /** The width of {@link #source()}, from {@code TRAN-SOURCE PIC X(10)}. */
    public static final int SOURCE_MAX_LENGTH = 10;

    /** The width of {@link #description()}, from {@code TRAN-DESC PIC X(100)}. */
    public static final int DESCRIPTION_MAX_LENGTH = 100;

    /** The width of {@link #merchantName()}, from {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    public static final int MERCHANT_NAME_MAX_LENGTH = 50;

    /** The width of {@link #merchantCity()}, from {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    public static final int MERCHANT_CITY_MAX_LENGTH = 50;

    /** The width of {@link #merchantZip()}, from {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final int MERCHANT_ZIP_MAX_LENGTH = 10;

    /**
     * The form {@link #cardToken()} takes: exactly {@value #CARD_TOKEN_LENGTH} lower-case
     * hexadecimal characters.
     *
     * <p>The same pattern constrains {@code cardToken} in
     * {@code schemas/transaction-posted-v2.json}.
     */
    public static final String CARD_TOKEN_PATTERN = "^[0-9a-f]{64}$";

    /**
     * The form {@link #merchantCategoryCode()} takes: exactly four digits.
     *
     * <p>From {@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7}. The eighteen codes
     * of {@code app/data/ASCII/trancatg.txt} each hold four digits, so a leading zero belongs to
     * the value.
     */
    public static final String MERCHANT_CATEGORY_CODE_PATTERN = "^[0-9]{4}$";

    /**
     * The form {@link #merchantId()} takes: exactly nine digits.
     *
     * <p>From {@code TRAN-MERCHANT-ID PIC 9(09)} at {@code app/cpy/CVTRA05Y.cpy:L11}.
     */
    public static final String MERCHANT_ID_PATTERN = "^[0-9]{9}$";

    /**
     * The form {@link #originTimestamp()} takes: {@code YYYY-MM-DD HH:MM:SS.ffffff}.
     *
     * <p>A space separates the date from the time, colons separate the time parts, and a point
     * precedes six fractional digits. From {@code TRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L16}. All 300 records of {@code app/data/ASCII/dailytran.txt}
     * carry that one layout, and it is not the layout {@link #POSTED_AT_PATTERN} describes.
     */
    public static final String ORIGIN_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$";

    /**
     * The characters a text component of this record may hold: printable United States American
     * Standard Code for Information Interchange characters and the space.
     *
     * <p>A consumer lays this text out by column, so a control character would break a rendered
     * line rather than appear in it.
     */
    public static final String PRINTABLE_TEXT_PATTERN = "^[ -~]*$";

    /**
     * The form {@link #newBalance()} takes: up to <strong>ten</strong> integer digits, then two
     * fractional digits, with an optional leading minus.
     *
     * <p>Ten from {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}. The same
     * pattern constrains {@code newBalance} in {@code schemas/transaction-posted-v1.json}. It is
     * not {@link #NINE_INTEGER_DIGIT_AMOUNT_PATTERN} and the two are not interchangeable.
     */
    public static final String TEN_INTEGER_DIGIT_BALANCE_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /**
     * The form {@link #amount()} takes: up to <strong>nine</strong> integer digits, then two
     * fractional digits, with an optional leading minus.
     *
     * <p>Nine from {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}. The same
     * pattern constrains {@code amount} in {@code schemas/transaction-posted-v1.json}. It is not
     * {@link #TEN_INTEGER_DIGIT_BALANCE_PATTERN} and the two are not interchangeable.
     */
    public static final String NINE_INTEGER_DIGIT_AMOUNT_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /**
     * The form {@link #postedAt()} takes: {@code YYYY-MM-DD-HH.MM.SS.NN0000}.
     *
     * <p>Three dashes, then three dots, then two fractional digits, then four literal zeros. The
     * separators come from {@code app/cbl/CBTRN02C.cbl:L702} and
     * {@code app/cbl/CBTRN02C.cbl:L703}, and the four zeros from
     * {@code app/cbl/CBTRN02C.cbl:L701}. The same pattern constrains {@code postedAt} in
     * {@code schemas/transaction-posted-v1.json}, which declares no {@code date-time} format
     * beside it.
     */
    public static final String POSTED_AT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000$";

    /**
     * The form {@link #maskedCardNumber()} takes: twelve mask characters, then four digits.
     *
     * <p>Sixteen characters in total, at the width of {@code TRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L15}. The same pattern constrains {@code maskedCardNumber} in
     * {@code schemas/transaction-posted-v1.json}, so a full Primary Account Number fails the
     * check.
     */
    public static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * {@link #TEN_INTEGER_DIGIT_BALANCE_PATTERN} compiled, and the check {@code newBalance} runs.
     */
    private static final Pattern BALANCE_MATCHER =
            Pattern.compile(TEN_INTEGER_DIGIT_BALANCE_PATTERN);

    /** {@link #NINE_INTEGER_DIGIT_AMOUNT_PATTERN} compiled, and the check {@code amount} runs. */
    private static final Pattern AMOUNT_MATCHER =
            Pattern.compile(NINE_INTEGER_DIGIT_AMOUNT_PATTERN);

    /** {@link #POSTED_AT_PATTERN} compiled, and the check {@code postedAt} runs. */
    private static final Pattern POSTED_AT_MATCHER = Pattern.compile(POSTED_AT_PATTERN);

    /**
     * {@link #MASKED_CARD_NUMBER_PATTERN} compiled, and the check {@code maskedCardNumber} runs.
     */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /**
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN} compiled, and the check both account identifiers
     * run.
     */
    private static final Pattern ACCOUNT_IDENTIFIER_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

    /** {@link #CARD_TOKEN_PATTERN} compiled, and the check {@code cardToken} runs. */
    private static final Pattern CARD_TOKEN_MATCHER = Pattern.compile(CARD_TOKEN_PATTERN);

    /**
     * {@link #MERCHANT_CATEGORY_CODE_PATTERN} compiled, and the check
     * {@code merchantCategoryCode} runs.
     */
    private static final Pattern MERCHANT_CATEGORY_CODE_MATCHER =
            Pattern.compile(MERCHANT_CATEGORY_CODE_PATTERN);

    /** {@link #MERCHANT_ID_PATTERN} compiled, and the check {@code merchantId} runs. */
    private static final Pattern MERCHANT_ID_MATCHER = Pattern.compile(MERCHANT_ID_PATTERN);

    /** {@link #ORIGIN_TIMESTAMP_PATTERN} compiled, and the check {@code originTimestamp} runs. */
    private static final Pattern ORIGIN_TIMESTAMP_MATCHER =
            Pattern.compile(ORIGIN_TIMESTAMP_PATTERN);

    /** {@link #PRINTABLE_TEXT_PATTERN} compiled, and the check each text component runs. */
    private static final Pattern PRINTABLE_TEXT_MATCHER = Pattern.compile(PRINTABLE_TEXT_PATTERN);

    /**
     * Checks every component and normalises the two money components to scale
     * {@link #MONEY_SCALE}.
     *
     * <p>Every failure message names the component that failed. The check rejects any value the
     * schema document of the event's version rejects, so a record that exists validates against its
     * contract. The two account identifiers must hold one value, which is the rule the schema
     * dialect cannot state. An absent {@code accountId} takes the value of {@code aggregateId},
     * which the contract document names as the one account identifier it declares.
     *
     * <p>Ten components depend on the version. Under
     * {@link #TRANSACTION_DETAIL_SCHEMA_VERSION} each is required and each meets the width or the
     * pattern its source field declares. Under {@link EventEnvelope#SCHEMA_VERSION} each must be
     * absent, because {@code schemas/transaction-posted-v1.json} declares none of them and closes
     * its property set.
     *
     * <p>A message reports the length of a rejected identifier or masked card number, never the
     * value. A failure therefore leaks no account identifier and no Primary Account Number into a
     * log. A rejected timestamp or money value does appear in the message, so the reader sees the
     * offending text.
     *
     * <p>Normalisation is the one change the constructor makes: each money component takes
     * {@link BigDecimal#setScale(int, RoundingMode)} with {@link #MONEY_ROUNDING} before its
     * pattern check. Every other component survives a serialize and deserialize round trip
     * unchanged.
     *
     * @throws NullPointerException     when {@code eventId}, {@code eventType} or
     *                                  {@code occurredAt} is {@code null}
     * @throws IllegalArgumentException when a component fails its check. The envelope checks
     *                                  cover {@code eventType} against {@link #EVENT_TYPE},
     *                                  {@code schemaVersion} against
     *                                  {@link EventEnvelope#SCHEMA_VERSION}, and a present
     *                                  {@code aggregateId}. Both account identifiers must hold
     *                                  eleven decimal digits and one value. {@code transactionId}
     *                                  must hold {@link #TRANSACTION_ID_LENGTH} characters, each
     *                                  money component must be present and within its integer
     *                                  width, and {@code postedAt} and {@code maskedCardNumber}
     *                                  must match their patterns
     */
    public TransactionPosted {
        Objects.requireNonNull(eventId, "eventId must be present");
        Objects.requireNonNull(eventType, "eventType must be present");
        Objects.requireNonNull(occurredAt, "occurredAt must be present");

        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be \"" + EVENT_TYPE
                    + "\" and the supplied value is \"" + eventType + "\"");
        }
        if (schemaVersion != EventEnvelope.SCHEMA_VERSION
                && schemaVersion != TRANSACTION_DETAIL_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be "
                    + EventEnvelope.SCHEMA_VERSION + " or " + TRANSACTION_DETAIL_SCHEMA_VERSION
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

        if (transactionId == null || transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must hold " + TRANSACTION_ID_LENGTH
                    + " characters and the supplied value " + describeLength(transactionId));
        }

        newBalance = normalisedMoney(newBalance, "newBalance", BALANCE_MATCHER,
                TEN_INTEGER_DIGIT_BALANCE_PATTERN);
        amount = normalisedMoney(amount, "amount", AMOUNT_MATCHER,
                NINE_INTEGER_DIGIT_AMOUNT_PATTERN);

        if (postedAt == null || postedAt.length() != POSTED_AT_LENGTH
                || !POSTED_AT_MATCHER.matcher(postedAt).matches()) {
            throw new IllegalArgumentException("postedAt must hold " + POSTED_AT_LENGTH
                    + " characters matching " + POSTED_AT_PATTERN + " and the supplied value "
                    + describeLength(postedAt));
        }

        if (maskedCardNumber == null || maskedCardNumber.length() != MASKED_CARD_NUMBER_LENGTH
                || !MASKED_CARD_NUMBER_MATCHER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber must hold "
                    + MASKED_CARD_NUMBER_LENGTH + " characters matching "
                    + MASKED_CARD_NUMBER_PATTERN + " and the supplied value "
                    + describeLength(maskedCardNumber));
        }

        if (schemaVersion == TRANSACTION_DETAIL_SCHEMA_VERSION) {
            requirePattern(cardToken, CARD_TOKEN_MATCHER, CARD_TOKEN_PATTERN, "cardToken");
            requirePattern(merchantCategoryCode, MERCHANT_CATEGORY_CODE_MATCHER,
                    MERCHANT_CATEGORY_CODE_PATTERN, "merchantCategoryCode");
            requirePattern(merchantId, MERCHANT_ID_MATCHER, MERCHANT_ID_PATTERN, "merchantId");
            requirePattern(originTimestamp, ORIGIN_TIMESTAMP_MATCHER, ORIGIN_TIMESTAMP_PATTERN,
                    "originTimestamp");

            requireText(transactionTypeCode, TRANSACTION_TYPE_CODE_MAX_LENGTH,
                    "transactionTypeCode");
            requireText(source, SOURCE_MAX_LENGTH, "source");
            requireText(description, DESCRIPTION_MAX_LENGTH, "description");
            requireText(merchantName, MERCHANT_NAME_MAX_LENGTH, "merchantName");
            requireText(merchantCity, MERCHANT_CITY_MAX_LENGTH, "merchantCity");
            requireText(merchantZip, MERCHANT_ZIP_MAX_LENGTH, "merchantZip");
        } else {
            requireAbsentAtVersionOne(cardToken, "cardToken");
            requireAbsentAtVersionOne(transactionTypeCode, "transactionTypeCode");
            requireAbsentAtVersionOne(merchantCategoryCode, "merchantCategoryCode");
            requireAbsentAtVersionOne(source, "source");
            requireAbsentAtVersionOne(description, "description");
            requireAbsentAtVersionOne(merchantId, "merchantId");
            requireAbsentAtVersionOne(merchantName, "merchantName");
            requireAbsentAtVersionOne(merchantCity, "merchantCity");
            requireAbsentAtVersionOne(merchantZip, "merchantZip");
            requireAbsentAtVersionOne(originTimestamp, "originTimestamp");
        }
    }

    /**
     * Checks one component against a compiled pattern.
     *
     * <p>The message reports the length of a rejected value and never the value, because
     * {@code cardToken} passes through here.
     *
     * @param value     the component value
     * @param matcher   the compiled pattern
     * @param pattern   the pattern text the failure message reports
     * @param component the component name the failure message reports
     * @throws IllegalArgumentException when {@code value} is {@code null} or does not match
     */
    private static void requirePattern(String value, Pattern matcher, String pattern,
            String component) {
        if (value == null || !matcher.matcher(value).matches()) {
            throw new IllegalArgumentException(component + " must match " + pattern
                    + " under schemaVersion " + TRANSACTION_DETAIL_SCHEMA_VERSION
                    + " and the supplied value " + describeLength(value));
        }
    }

    /**
     * Checks one text component against its declared width and the printable character set.
     *
     * <p>A shorter value passes, including an empty one, which is what a fixed-width source field
     * holding spaces becomes once trimmed.
     *
     * @param value     the component value
     * @param maximum   the width the source field declares
     * @param component the component name the failure message reports
     * @throws IllegalArgumentException when {@code value} is {@code null}, is wider than
     *                                  {@code maximum}, or holds a character outside
     *                                  {@link #PRINTABLE_TEXT_PATTERN}
     */
    private static void requireText(String value, int maximum, String component) {
        if (value == null || value.length() > maximum) {
            throw new IllegalArgumentException(component + " must hold at most " + maximum
                    + " characters under schemaVersion " + TRANSACTION_DETAIL_SCHEMA_VERSION
                    + " and the supplied value " + describeLength(value));
        }
        if (!PRINTABLE_TEXT_MATCHER.matcher(value).matches()) {
            throw new IllegalArgumentException(component + " must match " + PRINTABLE_TEXT_PATTERN
                    + " and the supplied value holds " + value.length()
                    + " characters, one of them outside that set");
        }
    }

    /**
     * Requires a component version 1 does not declare to be absent.
     *
     * @param value     the component value
     * @param component the component name the failure message reports
     * @throws IllegalArgumentException when {@code value} is present
     */
    private static void requireAbsentAtVersionOne(String value, String component) {
        if (value != null) {
            throw new IllegalArgumentException(component + " must be absent under schemaVersion "
                    + EventEnvelope.SCHEMA_VERSION + ", which declares no such property, and a"
                    + " value of " + value.length() + " characters was supplied");
        }
    }

    /**
     * Builds an event from an envelope a producer already holds.
     *
     * <p>{@link #accountId()} takes {@link EventEnvelope#aggregateId()}, so the two account
     * identifiers hold one value by construction. The envelope must carry {@link #EVENT_TYPE};
     * {@link EventEnvelope#of(String, String)} stamps it when the caller passes that constant.
     *
     * <p>The ten version-2 components must agree with the version the envelope carries: all present
     * at {@link #TRANSACTION_DETAIL_SCHEMA_VERSION}, all absent at
     * {@link EventEnvelope#SCHEMA_VERSION}.
     *
     * @param envelope             the envelope carrying the event identifier, the event type, the
     *                             contract version, the publish moment and the account identifier
     * @param transactionId        the sixteen-character transaction identifier
     * @param newBalance           the account balance after the posting, at up to ten integer
     *                             digits
     * @param postedAt             the twenty-six-character posting timestamp
     * @param amount               the amount that moved the balance, at up to nine integer digits
     * @param maskedCardNumber     the card number with twelve mask characters ahead of its last
     *                             four digits
     * @param cardToken            the card identity, or {@code null} at version 1
     * @param transactionTypeCode  the transaction type code, or {@code null} at version 1
     * @param merchantCategoryCode the merchant category code, or {@code null} at version 1
     * @param source               the capture channel, or {@code null} at version 1
     * @param description          the transaction description, or {@code null} at version 1
     * @param merchantId           the merchant identifier, or {@code null} at version 1
     * @param merchantName         the merchant name, or {@code null} at version 1
     * @param merchantCity         the merchant city, or {@code null} at version 1
     * @param merchantZip          the merchant postal code, or {@code null} at version 1
     * @param originTimestamp      the origin timestamp, or {@code null} at version 1
     * @return the event, with both money components at scale {@link #MONEY_SCALE}
     * @throws NullPointerException     when {@code envelope} is {@code null}
     * @throws IllegalArgumentException when any component fails the canonical constructor
     */
    public static TransactionPosted of(EventEnvelope envelope, String transactionId,
            BigDecimal newBalance, String postedAt, BigDecimal amount, String maskedCardNumber,
            String cardToken, String transactionTypeCode, String merchantCategoryCode,
            String source, String description, String merchantId, String merchantName,
            String merchantCity, String merchantZip, String originTimestamp) {
        Objects.requireNonNull(envelope, "envelope must be present");

        return new TransactionPosted(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                transactionId, envelope.aggregateId(), newBalance, postedAt, amount,
                maskedCardNumber, cardToken, transactionTypeCode, merchantCategoryCode, source,
                description, merchantId, merchantName, merchantCity, merchantZip, originTimestamp);
    }

    /**
     * Builds a version-1 event for one account, carrying none of the ten components version 2 adds.
     *
     * <p>{@link EventEnvelope#of(String, String)} supplies a fresh event identifier,
     * {@link #EVENT_TYPE}, {@link EventEnvelope#SCHEMA_VERSION} and the current moment truncated to
     * milliseconds. Version 1 stays governed by {@code schemas/transaction-posted-v1.json}, so this
     * factory is what builds an event a consumer written against that document reads.
     *
     * @param accountId        the eleven-digit account identifier, which becomes both
     *                         {@link #aggregateId()} and {@link #accountId()}
     * @param transactionId    the sixteen-character transaction identifier
     * @param newBalance       the account balance after the posting, at up to ten integer digits
     * @param postedAt         the twenty-six-character posting timestamp
     * @param amount           the amount that moved the balance, at up to nine integer digits
     * @param maskedCardNumber the card number with twelve mask characters ahead of its last four
     *                         digits
     * @return the event at {@link EventEnvelope#SCHEMA_VERSION}, with both money components at
     *         scale {@link #MONEY_SCALE}
     * @throws IllegalArgumentException when any component fails the canonical constructor
     */
    public static TransactionPosted forAccount(String accountId, String transactionId,
            BigDecimal newBalance, String postedAt, BigDecimal amount, String maskedCardNumber) {
        return of(EventEnvelope.of(EVENT_TYPE, accountId), transactionId, newBalance, postedAt,
                amount, maskedCardNumber, null, null, null, null, null, null, null, null, null,
                null);
    }

    /**
     * Builds the event that follows one authorized transaction, at the version the authorized event
     * supports.
     *
     * <p>At version two, fourteen of the fifteen payload values are copied from the authorized
     * event and only the balance is new, so a producer cannot transpose two of them or leave one
     * blank. The account identifier, the transaction identifier, the amount, the masked card number
     * and the card token all come from the same source, which is why the two events describe one
     * transaction and not two.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L425-L436} moves the same twelve values from the feed record
     * onto the posted record, and {@code app/cbl/CBTRN02C.cbl:L438} stamps the posting timestamp,
     * which is the argument below.
     *
     * <h2>Why the version is derived and not assumed</h2>
     *
     * <p>The card token is the one component of a posted event that no other component can supply.
     * {@code schemas/transaction-authorized-v2.json} requires it and
     * {@code schemas/transaction-authorized-v1.json} declares no such property at all, so an event
     * at {@link EventEnvelope#SCHEMA_VERSION} carries none and
     * {@link TransactionAuthorized#cardToken()} answers {@code null} for it. Both versions stay
     * governed by {@code EventSchemas.SCHEMA_DOCUMENTS}, because evolution here is additive only: a
     * version is never withdrawn, so a producer that has not yet moved to version 2 keeps working.
     *
     * <p>This factory therefore answers the version the authorized event supports rather than
     * refusing the older one. A token present answers
     * {@link #TRANSACTION_DETAIL_SCHEMA_VERSION} carrying the whole posted record. A token absent
     * answers {@link EventEnvelope#SCHEMA_VERSION} carrying the five envelope components and the
     * balance, the transaction identifier, the account identifier, the posting timestamp, the
     * amount and the masked card number, which is exactly the payload
     * {@code schemas/transaction-posted-v1.json} declares and the shape
     * {@link #forAccount(String, String, BigDecimal, String, BigDecimal, String)} builds. The nine
     * remaining detail components travel only beside the token, because
     * {@code schemas/transaction-posted-v1.json} declares none of them and the canonical
     * constructor refuses a version-1 event that carries one.
     *
     * <p>Refusing a version-1 authorization instead would have left a governed, schema-valid event
     * that no consumer could apply: the ledger's posting arithmetic needs no card token, so the
     * balance movement it owns would have been abandoned over a component it never reads. A
     * consumer that does need the token, such as the card-keyed read model of the notification
     * service, declines a version-1 posted event on its own terms and says so.
     *
     * @param authorized the authorized transaction this posting applied, at either
     *                   {@link EventEnvelope#SCHEMA_VERSION} or
     *                   {@link TransactionAuthorized#CARD_TOKEN_SCHEMA_VERSION}
     * @param newBalance the account balance after the posting, at up to ten integer digits
     * @param postedAt   the twenty-six-character posting timestamp, shaped
     *                   {@code YYYY-MM-DD-HH.MM.SS.NN0000}
     * @return the event at {@link #TRANSACTION_DETAIL_SCHEMA_VERSION} when the authorized event
     *         carries a card token and at {@link EventEnvelope#SCHEMA_VERSION} when it does not,
     *         with both money components at scale {@link #MONEY_SCALE}
     * @throws NullPointerException     when {@code authorized} is {@code null}
     * @throws IllegalArgumentException when any component fails the canonical constructor
     */
    public static TransactionPosted forAuthorized(TransactionAuthorized authorized,
            BigDecimal newBalance, String postedAt) {
        Objects.requireNonNull(authorized, "authorized must be present");

        if (authorized.cardToken() == null) {
            return forAccount(authorized.accountId(), authorized.transactionId(), newBalance,
                    postedAt, authorized.amount(), authorized.maskedCardNumber());
        }

        EventEnvelope envelope = EventEnvelope.of(EVENT_TYPE, authorized.accountId(),
                TRANSACTION_DETAIL_SCHEMA_VERSION);

        return of(envelope, authorized.transactionId(), newBalance, postedAt, authorized.amount(),
                authorized.maskedCardNumber(), authorized.cardToken(),
                authorized.transactionTypeCode(), authorized.merchantCategoryCode(),
                authorized.source(), authorized.description(), authorized.merchantId(),
                authorized.merchantName(), authorized.merchantCity(), authorized.merchantZip(),
                authorized.authorizedAt());
    }

    /**
     * The five envelope components, as the carrier a producer builds and a consumer routes on.
     *
     * <p>The returned envelope holds the values this record already carries, so the two cannot
     * disagree. Serialization ignores the method, and a serialized event carries no
     * {@code envelope} key.
     *
     * @return an envelope equal to the one
     *         {@link #of(EventEnvelope, TransactionAuthorized, BigDecimal, String)} accepted
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
                    + describeLength(value));
        }
    }

    /**
     * Sets one money component to scale {@link #MONEY_SCALE} and checks the result against its own
     * pattern.
     *
     * <p>The scale change applies {@link #MONEY_ROUNDING}. The check runs on
     * {@link BigDecimal#toPlainString()}, which is the text the event carries at scale
     * {@link #MONEY_SCALE}.
     *
     * @param value     the amount to normalise
     * @param component the component name the failure message reports
     * @param matcher   the compiled pattern for that component
     * @param pattern   the pattern text the failure message reports
     * @return {@code value} at scale {@link #MONEY_SCALE}
     * @throws IllegalArgumentException when {@code value} is {@code null} or carries more integer
     *                                  digits than its component allows
     */
    private static BigDecimal normalisedMoney(BigDecimal value, String component, Pattern matcher,
            String pattern) {
        if (value == null) {
            throw new IllegalArgumentException(component + " must be present");
        }
        BigDecimal scaled = value.setScale(MONEY_SCALE, MONEY_ROUNDING);

        if (!matcher.matcher(scaled.toPlainString()).matches()) {
            throw new IllegalArgumentException(component + " must match " + pattern
                    + " and the supplied value holds precision " + scaled.precision()
                    + " and scale " + scaled.scale());
        }
        return scaled;
    }

    /**
     * Describes a rejected value by its length, keeping the value out of the message.
     *
     * @param value the rejected value, possibly {@code null}
     * @return {@code "is null"}, or the character count
     */
    private static String describeLength(String value) {
        return value == null ? "is null" : "holds " + value.length() + " characters";
    }
    /**
     * Renders the technical identifiers and withholds every value the payload carries.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the new balance, the amount, the masked card number, the account
     * identifier and the aggregate identifier.
     *
     * <p>The new balance is the value this event exists to carry, and it is the value a log line
     * must not publish.
     *
     * @return the identifiers of this event with every payload value withheld, never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionPosted[eventId=" + eventId + ", eventType=" + eventType
                + ", schemaVersion=" + schemaVersion + ", occurredAt=" + occurredAt
                + ", aggregateId=" + EventEnvelope.WITHHELD + ", transactionId=" + transactionId
                + ", accountId=" + EventEnvelope.WITHHELD + ", newBalance="
                + EventEnvelope.WITHHELD + ", postedAt=" + EventEnvelope.WITHHELD + ", amount="
                + EventEnvelope.WITHHELD + ", maskedCardNumber=" + EventEnvelope.WITHHELD
                + ", cardToken=" + EventEnvelope.WITHHELD + ", transactionTypeCode="
                + EventEnvelope.WITHHELD + ", merchantCategoryCode=" + EventEnvelope.WITHHELD
                + ", source=" + EventEnvelope.WITHHELD + ", description=" + EventEnvelope.WITHHELD
                + ", merchantId=" + EventEnvelope.WITHHELD + ", merchantName="
                + EventEnvelope.WITHHELD + ", merchantCity=" + EventEnvelope.WITHHELD
                + ", merchantZip=" + EventEnvelope.WITHHELD + ", originTimestamp="
                + EventEnvelope.WITHHELD + "]";
    }

    /**
     * Answers whether this event carries the transaction detail components.
     *
     * @return {@code true} when {@link #schemaVersion()} is
     *         {@link #TRANSACTION_DETAIL_SCHEMA_VERSION}, and {@code false} at
     *         {@link EventEnvelope#SCHEMA_VERSION}
     */
    public boolean carriesTransactionDetail() {
        return schemaVersion == TRANSACTION_DETAIL_SCHEMA_VERSION;
    }

    /**
     * Builds the posted event from a caller-supplied envelope and the authorization it posts.
     *
     * <p>{@link #forAuthorized(TransactionAuthorized, BigDecimal, String)} builds its own envelope,
     * which is what a producer wants. A test that pins an event identifier or an occurrence moment
     * supplies the envelope instead, and this overload is the one it calls.</p>
     *
     * @param envelope   the five envelope components, whose aggregate identifier is the account
     * @param authorized the authorization this posting settles
     * @param newBalance the account balance after the posting arithmetic
     * @param postedAt   the processing timestamp, twenty-six characters
     * @return the event carrying {@code envelope}, and the detail of {@code authorized} when
     *         {@code envelope} names {@link #TRANSACTION_DETAIL_SCHEMA_VERSION}
     * @throws NullPointerException     when {@code envelope} or {@code authorized} is {@code null}
     * @throws IllegalArgumentException when {@code envelope} names
     *                                  {@link #TRANSACTION_DETAIL_SCHEMA_VERSION} and the
     *                                  authorized event carries no card token, or when any
     *                                  component fails the canonical constructor
     */
    public static TransactionPosted of(EventEnvelope envelope, TransactionAuthorized authorized,
            BigDecimal newBalance, String postedAt) {
        Objects.requireNonNull(envelope, "envelope must be present");
        Objects.requireNonNull(authorized, "authorized must be present");

        // The envelope names the version here, because the caller supplied it. A version-1
        // envelope therefore carries the six payload values schemas/transaction-posted-v1.json
        // declares and none of the ten the canonical constructor refuses at that version, whether
        // or not the authorization it follows carried a card token.
        if (envelope.schemaVersion() == EventEnvelope.SCHEMA_VERSION) {
            return of(envelope, authorized.transactionId(), newBalance, postedAt,
                    authorized.amount(), authorized.maskedCardNumber(), null, null, null, null,
                    null, null, null, null, null, null);
        }

        if (authorized.cardToken() == null) {
            throw new IllegalArgumentException("the authorized event must carry a card token to"
                    + " build a posted event at schemaVersion "
                    + TRANSACTION_DETAIL_SCHEMA_VERSION + ", so it must be at schemaVersion "
                    + TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION
                    + ", and the supplied event is at schemaVersion "
                    + authorized.schemaVersion());
        }

        return of(envelope, authorized.transactionId(), newBalance, postedAt, authorized.amount(),
                authorized.maskedCardNumber(), authorized.cardToken(),
                authorized.transactionTypeCode(), authorized.merchantCategoryCode(),
                authorized.source(), authorized.description(), authorized.merchantId(),
                authorized.merchantName(), authorized.merchantCity(), authorized.merchantZip(),
                authorized.authorizedAt());
    }
}

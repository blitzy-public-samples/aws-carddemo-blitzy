package com.carddemo.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * The event the authorization service publishes when it rejects a transaction.
 *
 * <p>Provenance is one program. {@code app/cbl/CBTRN02C.cbl} holds the only
 * validate-and-authorize logic in the CardDemo source, and its eighty-byte validation trailer at
 * {@code app/cbl/CBTRN02C.cbl:L176-L182} gives this record its shape. Two source fields fix two
 * widths. {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} gives
 * the code four digits, and {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
 * {@code app/cbl/CBTRN02C.cbl:L182} caps the text at seventy-six characters. The batch job writes
 * both fields into the trailer of a four-hundred-and-thirty-byte reject record, allocated at
 * {@code app/jcl/POSTTRAN.jcl:L36}.
 *
 * <p>A decline is expected traffic, not a failure. {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the
 * batch job with return code 4 when any record was rejected. The authorization service answers the
 * caller with the reason and publishes one event.
 *
 * <p>Four reason locators. {@link DeclineReason#INVALID_CARD_NUMBER} comes from
 * {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@link DeclineReason#ACCOUNT_NOT_FOUND} from
 * {@code app/cbl/CBTRN02C.cbl:L397-L399}, {@link DeclineReason#OVER_CREDIT_LIMIT} from
 * {@code app/cbl/CBTRN02C.cbl:L410-L412}, and {@link DeclineReason#ACCOUNT_EXPIRED} from
 * {@code app/cbl/CBTRN02C.cbl:L417-L419}. One declined transaction carries one reason, so
 * {@code declineReasonCode} holds a single value and never a list. The source reads the account
 * only while the code is still zero, gated at {@code app/cbl/CBTRN02C.cbl:L372}, and no gate
 * separates the credit-limit test from the expiration test.
 *
 * <p>The wire form is flat. The five envelope fields are declared first below, so one JavaScript
 * Object Notation (JSON) object holds them beside the six payload fields and no {@code envelope}
 * key reaches a topic. {@link #envelope()} returns the carrier a producer builds and passes, and
 * {@link #of(EventEnvelope, String, DeclineReason, BigDecimal, String)} takes it back. The document
 * at {@code schemas/transaction-declined-v1.json} validates the result on serialize and on
 * deserialize.
 *
 * <p>TWO CONTRACTS, ONE RECORD. Reject reason {@code 0100} fires precisely when the keyed read of
 * the cross-reference file misses at {@code app/cbl/CBTRN02C.cbl:L382-L387}, so at that moment the
 * platform holds no account identifier it established itself. Version 1 requires one. That one
 * decline therefore travels under its own contract:
 *
 * <p>Reject reason {@code 0100} fires when the keyed read of the cross-reference file misses at
 * {@code app/cbl/CBTRN02C.cbl:L382-L387}, and at that moment no account identifier exists.
 * {@link DeclineReason#resolvesAccount()} answers {@code false} for it, and the canonical
 * constructor refuses it. The authorization service records that attempt in its own
 * {@code unresolved_card_attempt} table and publishes no event, so no decline on a topic can name
 * the wrong account and none can key on anything other than an account.
 *
 * <p>Only the first is on a publish path today. The authorization service records an unresolved
 * card in {@code unresolved_card_attempt} and writes no outbox row for it, so {@link
 * #ofUnresolvedAccount(String, BigDecimal, String)} and version 2 make that decline representable
 * rather than published.
 *
 * <p>{@code maskedCardNumber} has no source ancestor. No CardDemo program masks a Primary Account
 * Number (PAN), and {@code app/bms/COCRDSL.bms:L96-L99} defines the card detail field at the full
 * sixteen characters. The authorization decision runs on the full PAN, because the cross-reference
 * read at {@code app/cbl/CBTRN02C.cbl:L382-L383} keys on all sixteen characters. Masking happens
 * where the event is serialized, so a caller supplies a value that is already masked.
 *
 * <p>Add a fifth reason as a new {@link DeclineReason} constant, not by changing this record. The
 * source marks the same extension point with the comment {@code * ADD MORE VALIDATIONS HERE} at
 * {@code app/cbl/CBTRN02C.cbl:L377}.
 *
 * <p>The authorization service publishes this event to the {@code transaction.declined} topic, and
 * no service consumes it in the demo topology.
 *
 * @param eventId                  the idempotency key each consumer commits alongside the side
 *                                 effects it guards, a Universally Unique Identifier (UUID)
 * @param eventType                the routing discriminator, always {@link #EVENT_TYPE}
 * @param schemaVersion            the contract version, always
 *                                 {@link EventEnvelope#SCHEMA_VERSION}
 * @param occurredAt               the moment the producer wrote the event, in Coordinated
 *                                 Universal Time
 * @param aggregateId              the eleven-digit account identifier, and the Kafka message key.
 *                                 From {@code XREF-ACCT-ID PIC 9(11)} at
 *                                 {@code app/cpy/CVACT03Y.cpy:L7}
 * @param transactionId            the transaction identifier, exactly
 *                                 {@link #TRANSACTION_ID_LENGTH} characters. From
 *                                 {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} and
 *                                 {@code DALYTRAN-ID} at {@code app/cpy/CVTRA06Y.cpy:L5}
 * @param accountId                the eleven-digit account identifier, from
 *                                 {@code XREF-ACCT-ID PIC 9(11)} at
 *                                 {@code app/cpy/CVACT03Y.cpy:L7}. Always the identifier the card
 *                                 cross-reference row carried, never one a caller supplied, and
 *                                 always equal to the {@code aggregateId} of {@code envelope}, so
 *                                 the message key and the payload cannot disagree. Leading zeros
 *                                 belong to the value
 * @param declineReasonCode        the reason the authorization service rejected the transaction,
 *                                 serialized as four zero-padded digits
 * @param declineReasonDescription the reason text, character for character from the source, and
 *                                 always the {@link DeclineReason#description()} of
 *                                 {@code declineReasonCode}
 * @param amount                   the attempted amount, from
 *                                 {@code DALYTRAN-AMT PIC S9(09)V99} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L10}. Held at
 *                                 {@link #AMOUNT_SCALE} fractional digits and serialized as a
 *                                 decimal string. A negative amount is ordinary traffic: 50 of the
 *                                 300 records in {@code app/data/ASCII/dailytran.txt} carry one
 * @param maskedCardNumber         twelve mask characters then the last four digits of the card
 *                                 number, sixteen characters in all. No COBOL ancestor. Width from
 *                                 {@code DALYTRAN-CARD-NUM PIC X(16)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L15}
 * @param transactionTypeCode      the transaction type code, at most
 *                                 {@value #TRANSACTION_TYPE_CODE_MAX_LENGTH} characters, from
 *                                 {@code DALYTRAN-TYPE-CD PIC X(02)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L6}. Required under
 *                                 {@link #TRANSACTION_DETAIL_SCHEMA_VERSION} and absent under both
 *                                 other versions
 * @param merchantCategoryCode     the merchant category code, four digits, from
 *                                 {@code DALYTRAN-CAT-CD PIC 9(04)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L7}
 * @param source                   the capture channel, at most {@value #SOURCE_MAX_LENGTH}
 *                                 characters, from {@code DALYTRAN-SOURCE PIC X(10)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L8}
 * @param description              the transaction description, at most
 *                                 {@value #TRANSACTION_DESCRIPTION_MAX_LENGTH} characters, from
 *                                 {@code DALYTRAN-DESC PIC X(100)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L9}
 * @param merchantId               the merchant identifier, nine digits, from
 *                                 {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L11}
 * @param merchantName             the merchant name, at most {@value #MERCHANT_NAME_MAX_LENGTH}
 *                                 characters, from {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L12}
 * @param merchantCity             the merchant city, at most {@value #MERCHANT_CITY_MAX_LENGTH}
 *                                 characters, from {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L13}
 * @param merchantZip              the merchant postal code, at most
 *                                 {@value #MERCHANT_ZIP_MAX_LENGTH} characters, from
 *                                 {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L14}
 * @param originTimestamp          the moment the transaction originated, twenty-six characters,
 *                                 from {@code DALYTRAN-ORIG-TS PIC X(26)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L16}
 */
public record TransactionDeclined(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String transactionId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String accountId,
        DeclineReason declineReasonCode,
        String declineReasonDescription,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
        String maskedCardNumber,
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
     * The routing discriminator this record carries, and the {@code eventType} the schema document
     * pins with {@code "const": "TransactionDeclined"}.
     *
     * <p>The canonical constructor accepts no other value. {@link #of(String, String,
     * DeclineReason, BigDecimal, String)} stamps this one.
     */
    public static final String EVENT_TYPE = "TransactionDeclined";

    /**
     * The width of {@code transactionId}: sixteen characters.
     *
     * <p>From {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}. The identifier is text,
     * not a number, so leading zeros survive a serialize and deserialize round trip.
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * The number of fractional digits {@code amount} carries on the wire: two.
     *
     * <p>From the two decimal places of {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}.
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * The form {@code amount} takes on the wire: a decimal string with an optional leading minus,
     * at most nine digits before the point, and exactly {@link #AMOUNT_SCALE} digits after it.
     *
     * <p>The nine integer digits come from {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}. The same pattern constrains {@code amount} in
     * {@code schemas/transaction-declined-v1.json}. A balance carries ten integer digits and a
     * different pattern, so the two are not interchangeable.
     */
    public static final String AMOUNT_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /**
     * The form {@code maskedCardNumber} takes: twelve mask characters then four digits.
     *
     * <p>The total width of sixteen matches {@code DALYTRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA06Y.cpy:L15}. The same pattern constrains {@code maskedCardNumber} in
     * {@code schemas/transaction-declined-v1.json}.
     */
    public static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * The cap on {@code declineReasonDescription}: seventy-six characters.
     *
     * <p>From {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:L182}. The longest of the four texts holds forty-two characters.
     */
    public static final int DESCRIPTION_MAX_LENGTH = 76;

    /**
     * The contract version a decline carries when the card cross-reference resolved no account: 2.
     *
     * <p>{@code schemas/transaction-declined-v2.json} governs it. That document declares no
     * {@code accountId} at all and keys the event on {@code transactionId}. A producer with no
     * authoritative account identifier therefore neither trusts a supplied one nor invents one.
     *
     * <p>Version 1 is unchanged and remains the contract for every other decline, so a consumer
     * reading resolved declines sees the same bytes it always saw. A consumer that wants the
     * unresolved decline opts in by reading version 2, which is the additive evolution the platform
     * promises rather than a break of the existing contract.
     */
    public static final int UNRESOLVED_ACCOUNT_SCHEMA_VERSION = 2;

    /**
     * The one reject reason that fires before an account identifier exists:
     * {@link DeclineReason#INVALID_CARD_NUMBER}.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L382-L387} performs the keyed read of the cross-reference file
     * and assigns this reason on an invalid key. {@code app/cbl/CBTRN02C.cbl:L370-L378} reads the
     * account only while the reason code is still zero, so every later reason has already resolved
     * an account identifier from the cross-reference row. This reason therefore is the whole set of
     * declines that {@link #UNRESOLVED_ACCOUNT_SCHEMA_VERSION} covers.
     */
    public static final DeclineReason UNRESOLVED_ACCOUNT_REASON = DeclineReason.INVALID_CARD_NUMBER;

    /**
     * The contract version a decline carries when it also carries the refused transaction record: 3.
     *
     * <p>{@code schemas/transaction-declined-v3.json} governs it. It adds the nine descriptive fields
     * of {@code app/cpy/CVTRA06Y.cpy} to the version 1 set and changes nothing in it, so a consumer
     * reading version 1 is unaffected and a consumer that needs the record opts in by reading this
     * version.
     *
     * <p>The nine exist for one reader. {@code app/cbl/CBTRN02C.cbl:L447} copies the arriving
     * 350-byte record into {@code REJECT-TRAN-DATA PIC X(350)} before appending the validation
     * trailer, and the ledger posting service owns that reject row. Version 1 carries the reason and
     * the amount alone, so a consumer holding only version 1 cannot render the record the source
     * wrote, and the reject row could not exist outside a test.
     *
     * <p>The widths and patterns below are the same ones
     * {@code schemas/transaction-posted-v2.json} declares for the same nine fields, so one parser
     * reads a posted event and a declined one. Version 2 stays the contract for a decline whose card
     * resolved no account, and carries none of the nine: no account was resolved, so there is no
     * reject row to attribute the record to.
     */
    public static final int TRANSACTION_DETAIL_SCHEMA_VERSION = 3;

    /**
     * The cap on {@code transactionTypeCode}: two characters.
     *
     * <p>From {@code DALYTRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA06Y.cpy:L6}.
     */
    public static final int TRANSACTION_TYPE_CODE_MAX_LENGTH = 2;

    /**
     * The cap on {@code source}: ten characters.
     *
     * <p>From {@code DALYTRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA06Y.cpy:L8}.
     */
    public static final int SOURCE_MAX_LENGTH = 10;

    /**
     * The cap on {@code description}: one hundred characters.
     *
     * <p>From {@code DALYTRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA06Y.cpy:L9}. This is not
     * {@link #DESCRIPTION_MAX_LENGTH}, which bounds the reject reason text at seventy-six characters.
     * The two are different fields of different records and the names are kept apart for that reason.
     */
    public static final int TRANSACTION_DESCRIPTION_MAX_LENGTH = 100;

    /**
     * The cap on {@code merchantName}: fifty characters.
     *
     * <p>From {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at {@code app/cpy/CVTRA06Y.cpy:L12}.
     */
    public static final int MERCHANT_NAME_MAX_LENGTH = 50;

    /**
     * The cap on {@code merchantCity}: fifty characters.
     *
     * <p>From {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at {@code app/cpy/CVTRA06Y.cpy:L13}.
     */
    public static final int MERCHANT_CITY_MAX_LENGTH = 50;

    /**
     * The cap on {@code merchantZip}: ten characters.
     *
     * <p>From {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/CVTRA06Y.cpy:L14}. Records in
     * {@code app/data/ASCII/dailytran.txt} hold both the five-digit and the hyphenated nine-digit
     * form, so the cap is a width and not a format.
     */
    public static final int MERCHANT_ZIP_MAX_LENGTH = 10;

    /**
     * The form {@code merchantCategoryCode} takes: exactly four digits.
     *
     * <p>From {@code DALYTRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA06Y.cpy:L7}. The value keeps
     * its leading zeros, because the eighteen codes of {@code app/data/ASCII/trancatg.txt} read
     * {@code 0001} through {@code 0016}.
     */
    public static final String MERCHANT_CATEGORY_CODE_PATTERN = "^[0-9]{4}$";

    /**
     * The form {@code merchantId} takes: exactly nine digits.
     *
     * <p>From {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at {@code app/cpy/CVTRA06Y.cpy:L11}.
     */
    public static final String MERCHANT_ID_PATTERN = "^[0-9]{9}$";

    /**
     * The form {@code originTimestamp} takes: twenty-six characters shaped
     * {@code YYYY-MM-DD HH:MM:SS.ffffff}.
     *
     * <p>From {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}. The separator
     * between the date and the time is a space, which is what all 300 records of
     * {@code app/data/ASCII/dailytran.txt} carry, so this is not an ISO-8601 date and time.
     */
    public static final String ORIGIN_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$";

    /**
     * The characters a text component of the record may hold: printable ASCII, the space included.
     *
     * <p>A fixed-width source field is space padded, so an empty value and a padded one both pass. A
     * control character does not, because it would reach a rendered reject record and a log line.
     */
    public static final String PRINTABLE_TEXT_PATTERN = "^[ -~]*$";

    /**
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN} compiled, and the check {@code accountId} runs.
     *
     * <p>Reusing the envelope constant keeps one pattern behind both account identifiers.
     */
    private static final Pattern ACCOUNT_ID_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

    /**
     * {@link EventEnvelope#AGGREGATE_KEY_PATTERN} compiled, and the check {@code aggregateId} runs
     * under {@link #UNRESOLVED_ACCOUNT_SCHEMA_VERSION}.
     *
     * <p>That contract keys the event on the transaction identifier, sixteen printable characters
     * rather than eleven digits. The key check therefore reads the wider of the two envelope
     * patterns, while {@code accountId} keeps reading the account one.
     */
    private static final Pattern AGGREGATE_KEY_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_KEY_PATTERN);

    /** {@link #AMOUNT_PATTERN} compiled, and the check {@code amount} runs once normalized. */
    private static final Pattern AMOUNT_MATCHER = Pattern.compile(AMOUNT_PATTERN);

    /** {@link #MASKED_CARD_NUMBER_PATTERN} compiled, and the check {@code maskedCardNumber} runs. */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

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
     * Checks every component and rejects a value the schema document would reject.
     *
     * <p>Each exception message names the component that failed. Three messages report a length
     * instead of a value, so no account identifier, no transaction identifier and no card number
     * reaches a log through a failure. A caller that mistakes a full Primary Account Number for a
     * masked one therefore leaks nothing.
     *
     * <p>{@code amount} arrives held at {@link #AMOUNT_SCALE} fractional digits by
     * {@link RoundingMode#DOWN}, which truncates toward zero. The pattern check then runs on the
     * held value, so the stored amount and the serialized string agree. Every other component is
     * stored as supplied.
     *
     * @throws NullPointerException     when {@code eventId}, {@code eventType},
     *                                  {@code occurredAt}, {@code declineReasonCode} or
     *                                  {@code amount} is {@code null}
     * @throws IllegalArgumentException when a component fails its check. {@code eventType} must be
     *                                  {@link #EVENT_TYPE}, {@code schemaVersion} must be
     *                                  {@link EventEnvelope#SCHEMA_VERSION}, and
     *                                  {@code transactionId} must hold
     *                                  {@link #TRANSACTION_ID_LENGTH} characters. Both account
     *                                  identifiers must be eleven digits and must hold the same
     *                                  value, and {@link DeclineReason#resolvesAccount()} must
     *                                  answer {@code true} for {@code declineReasonCode}.
     *                                  {@code declineReasonDescription} must be the
     *                                  {@link DeclineReason#description()} of
     *                                  {@code declineReasonCode}, within
     *                                  {@link #DESCRIPTION_MAX_LENGTH} characters.
     *                                  {@code amount} must fit nine digits before the point, and
     *                                  {@code maskedCardNumber} must match
     *                                  {@link #MASKED_CARD_NUMBER_PATTERN}.
     */
    public TransactionDeclined {
        Objects.requireNonNull(eventId, "eventId must be present");
        Objects.requireNonNull(eventType, "eventType must be present");
        Objects.requireNonNull(occurredAt, "occurredAt must be present");
        Objects.requireNonNull(declineReasonCode, "declineReasonCode must be present");
        Objects.requireNonNull(amount, "amount must be present");

        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be " + EVENT_TYPE
                    + " and the supplied value is " + eventType);
        }
        if (schemaVersion != EventEnvelope.SCHEMA_VERSION
                && schemaVersion != UNRESOLVED_ACCOUNT_SCHEMA_VERSION
                && schemaVersion != TRANSACTION_DETAIL_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be "
                    + EventEnvelope.SCHEMA_VERSION + ", " + UNRESOLVED_ACCOUNT_SCHEMA_VERSION
                    + " or " + TRANSACTION_DETAIL_SCHEMA_VERSION
                    + ", the three contracts this event type ships, and the supplied value is "
                    + schemaVersion);
        }
        if (aggregateId == null
                || !(schemaVersion == UNRESOLVED_ACCOUNT_SCHEMA_VERSION
                        ? AGGREGATE_KEY_MATCHER.matcher(aggregateId).matches()
                        : ACCOUNT_ID_MATCHER.matcher(aggregateId).matches())) {
            throw new IllegalArgumentException("aggregateId must match "
                    + (schemaVersion == UNRESOLVED_ACCOUNT_SCHEMA_VERSION
                            ? EventEnvelope.AGGREGATE_KEY_PATTERN
                            : EventEnvelope.AGGREGATE_ID_PATTERN)
                    + " and the supplied value "
                    + (aggregateId == null ? "is null"
                            : "holds " + aggregateId.length() + " characters"));
        }
        if (transactionId == null || transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must hold " + TRANSACTION_ID_LENGTH
                    + " characters and the supplied value " + (transactionId == null ? "is null"
                            : "holds " + transactionId.length() + " characters"));
        }
        if (schemaVersion == UNRESOLVED_ACCOUNT_SCHEMA_VERSION) {
            if (accountId != null) {
                throw new IllegalArgumentException("accountId must be absent under schemaVersion "
                        + UNRESOLVED_ACCOUNT_SCHEMA_VERSION + ", the contract for a decline whose"
                        + " account identity the card cross-reference could not resolve, and a"
                        + " value of " + accountId.length() + " characters was supplied");
            }
            if (!transactionId.equals(aggregateId)) {
                throw new IllegalArgumentException("aggregateId must equal"
                        + " transactionId under schemaVersion " + UNRESOLVED_ACCOUNT_SCHEMA_VERSION
                        + ", because the transaction identifier is the message key when no account"
                        + " identifier exists, and the two supplied values differ");
            }
            if (declineReasonCode != UNRESOLVED_ACCOUNT_REASON) {
                throw new IllegalArgumentException("schemaVersion "
                        + UNRESOLVED_ACCOUNT_SCHEMA_VERSION + " carries reason code "
                        + UNRESOLVED_ACCOUNT_REASON.code() + " alone, because"
                        + " app/cbl/CBTRN02C.cbl:L370-L378 reads the account only while the reason"
                        + " code is still zero, so every other reason has already resolved one."
                        + " The supplied reason code is " + declineReasonCode.code());
            }
        } else {
            if (accountId == null || !ACCOUNT_ID_MATCHER.matcher(accountId).matches()) {
                throw new IllegalArgumentException("accountId must match "
                        + EventEnvelope.AGGREGATE_ID_PATTERN + " and the supplied value "
                        + (accountId == null ? "is null"
                                : "holds " + accountId.length() + " characters"));
            }
            if (!accountId.equals(aggregateId)) {
                throw new IllegalArgumentException("accountId must equal aggregateId, which is"
                        + " the Kafka message key, and the two supplied values differ");
            }
            if (declineReasonCode == UNRESOLVED_ACCOUNT_REASON) {
                throw new IllegalArgumentException("reason code "
                        + UNRESOLVED_ACCOUNT_REASON.code() + " follows a cross-reference read that"
                        + " resolved no account, so it cannot carry one. Build it with"
                        + " ofUnresolvedAccount, which publishes it under schemaVersion "
                        + UNRESOLVED_ACCOUNT_SCHEMA_VERSION + ".");
            }
        }
        if (!declineReasonCode.description().equals(declineReasonDescription)) {
            throw new IllegalArgumentException("declineReasonDescription must be \""
                    + declineReasonCode.description() + "\" beside reason code "
                    + declineReasonCode.code() + " and the supplied value is "
                    + (declineReasonDescription == null ? "null"
                            : "\"" + declineReasonDescription + "\""));
        }
        if (declineReasonDescription.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("declineReasonDescription must hold at most "
                    + DESCRIPTION_MAX_LENGTH + " characters and the text of reason code "
                    + declineReasonCode.code() + " holds " + declineReasonDescription.length());
        }

        amount = amount.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        if (!AMOUNT_MATCHER.matcher(amount.toPlainString()).matches()) {
            throw new IllegalArgumentException("amount must match " + AMOUNT_PATTERN
                    + " once held at " + AMOUNT_SCALE
                    + " fractional digits, and the supplied value holds precision "
                    + amount.precision() + " and scale " + amount.scale());
        }
        if (maskedCardNumber == null
                || !MASKED_CARD_NUMBER_MATCHER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber must match "
                    + MASKED_CARD_NUMBER_PATTERN + " and the supplied value "
                    + (maskedCardNumber == null ? "is null"
                            : "holds " + maskedCardNumber.length() + " characters"));
        }

        if (schemaVersion == TRANSACTION_DETAIL_SCHEMA_VERSION) {
            requirePattern(merchantCategoryCode, MERCHANT_CATEGORY_CODE_MATCHER,
                    MERCHANT_CATEGORY_CODE_PATTERN, "merchantCategoryCode");
            requirePattern(merchantId, MERCHANT_ID_MATCHER, MERCHANT_ID_PATTERN, "merchantId");
            requirePattern(originTimestamp, ORIGIN_TIMESTAMP_MATCHER, ORIGIN_TIMESTAMP_PATTERN,
                    "originTimestamp");

            requireText(transactionTypeCode, TRANSACTION_TYPE_CODE_MAX_LENGTH,
                    "transactionTypeCode");
            requireText(source, SOURCE_MAX_LENGTH, "source");
            requireText(description, TRANSACTION_DESCRIPTION_MAX_LENGTH, "description");
            requireText(merchantName, MERCHANT_NAME_MAX_LENGTH, "merchantName");
            requireText(merchantCity, MERCHANT_CITY_MAX_LENGTH, "merchantCity");
            requireText(merchantZip, MERCHANT_ZIP_MAX_LENGTH, "merchantZip");
        } else {
            requireAbsentWithoutDetail(transactionTypeCode, "transactionTypeCode", schemaVersion);
            requireAbsentWithoutDetail(merchantCategoryCode, "merchantCategoryCode", schemaVersion);
            requireAbsentWithoutDetail(source, "source", schemaVersion);
            requireAbsentWithoutDetail(description, "description", schemaVersion);
            requireAbsentWithoutDetail(merchantId, "merchantId", schemaVersion);
            requireAbsentWithoutDetail(merchantName, "merchantName", schemaVersion);
            requireAbsentWithoutDetail(merchantCity, "merchantCity", schemaVersion);
            requireAbsentWithoutDetail(merchantZip, "merchantZip", schemaVersion);
            requireAbsentWithoutDetail(originTimestamp, "originTimestamp", schemaVersion);
        }
    }

    /**
     * Checks one component of the refused record against a compiled pattern.
     *
     * <p>The message reports the length of a rejected value and never the value itself, matching
     * every other failure this record raises.
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
                    + " and the supplied value " + (value == null ? "is null"
                            : "holds " + value.length() + " characters"));
        }
    }

    /**
     * Checks one text component of the refused record against its width and character set.
     *
     * <p>A shorter value passes, an empty one included, because a fixed-width source field is space
     * padded and a trimmed value is the same value.
     *
     * @param value     the component value
     * @param maxLength the declared width of the source field
     * @param component the component name the failure message reports
     * @throws IllegalArgumentException when {@code value} is {@code null}, longer than
     *                                  {@code maxLength}, or holds a character outside
     *                                  {@link #PRINTABLE_TEXT_PATTERN}
     */
    private static void requireText(String value, int maxLength, String component) {
        if (value == null || value.length() > maxLength
                || !PRINTABLE_TEXT_MATCHER.matcher(value).matches()) {
            throw new IllegalArgumentException(component + " must hold at most " + maxLength
                    + " characters matching " + PRINTABLE_TEXT_PATTERN + " under schemaVersion "
                    + TRANSACTION_DETAIL_SCHEMA_VERSION + " and the supplied value "
                    + (value == null ? "is null" : "holds " + value.length() + " characters"));
        }
    }

    /**
     * Refuses a component of the refused record on a version whose document does not declare it.
     *
     * <p>Both other documents close their property set, so a value present here would be serialized
     * and then refused at the publish gate. Refusing it in the constructor names the component
     * itself, rather than leaving a schema message to describe a property the caller did not mean to
     * set.
     *
     * @param value         the component value, which must be {@code null}
     * @param component     the component name the failure message reports
     * @param schemaVersion the version being built
     * @throws IllegalArgumentException when {@code value} is present
     */
    private static void requireAbsentWithoutDetail(String value, String component,
            int schemaVersion) {
        if (value != null) {
            throw new IllegalArgumentException(component + " belongs to schemaVersion "
                    + TRANSACTION_DETAIL_SCHEMA_VERSION
                    + " alone, whose document declares it, and a value of " + value.length()
                    + " characters was supplied under schemaVersion " + schemaVersion);
        }
    }

    /**
     * Answers whether this event carries the refused transaction record.
     *
     * <p>A consumer that renders {@code REJECT-TRAN-DATA PIC X(350)} reads this before it tries, so
     * a version 1 event reaches no renderer that would find nine nulls.
     *
     * @return {@code true} at {@link #TRANSACTION_DETAIL_SCHEMA_VERSION}, {@code false} at both other
     *         versions
     */
    public boolean carriesTransactionDetail() {
        return schemaVersion == TRANSACTION_DETAIL_SCHEMA_VERSION;
    }

    /**
     * Builds a declined event on an envelope the producer already holds.
     *
     * <p>{@code accountId} takes the {@code aggregateId} of {@code envelope}, and
     * {@code declineReasonDescription} takes the {@link DeclineReason#description()} of
     * {@code declineReasonCode}. A caller cannot pair one reason code with another reason's text,
     * and cannot make the payload disagree with the Kafka message key.
     *
     * @param envelope          the envelope to carry, naming {@link #EVENT_TYPE}
     * @param transactionId     the transaction identifier, {@link #TRANSACTION_ID_LENGTH}
     *                          characters
     * @param declineReasonCode the reason the authorization service rejected the transaction
     * @param amount            the attempted amount, held at {@link #AMOUNT_SCALE} fractional
     *                          digits
     * @param maskedCardNumber  the card number already masked to
     *                          {@link #MASKED_CARD_NUMBER_PATTERN}
     * @return a declined event carrying the four supplied values and the two derived ones
     * @throws NullPointerException     when {@code envelope}, {@code declineReasonCode} or
     *                                  {@code amount} is {@code null}
     * @throws IllegalArgumentException when a component fails a check of the canonical constructor
     */
    public static TransactionDeclined of(EventEnvelope envelope, String transactionId,
            DeclineReason declineReasonCode, BigDecimal amount, String maskedCardNumber) {
        Objects.requireNonNull(envelope, "envelope must be present");
        Objects.requireNonNull(declineReasonCode, "declineReasonCode must be present");

        if (envelope.schemaVersion() == UNRESOLVED_ACCOUNT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("this factory copies the aggregateId of envelope into"
                    + " accountId, and schemaVersion " + UNRESOLVED_ACCOUNT_SCHEMA_VERSION
                    + " carries no account identifier. Build that contract with"
                    + " ofUnresolvedAccount, which keys the event on its transaction identifier.");
        }
        return new TransactionDeclined(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                transactionId, envelope.aggregateId(), declineReasonCode,
                declineReasonCode.description(), amount, maskedCardNumber,
                null, null, null, null, null, null, null, null, null);
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
     * Builds a declined event and stamps a fresh envelope, which is the path the authorization
     * service takes.
     *
     * <p>{@link EventEnvelope#of(String, String)} supplies a new event identifier, the schema
     * version, and the current moment truncated to milliseconds. The two derived components behave
     * as they do in {@link #of(EventEnvelope, String, DeclineReason, BigDecimal, String)}.
     *
     * @param accountId         the eleven-digit account identifier, which becomes both the
     *                          {@code aggregateId} of the new envelope and the Kafka message key
     * @param transactionId     the transaction identifier, {@link #TRANSACTION_ID_LENGTH}
     *                          characters
     * @param declineReasonCode the reason the authorization service rejected the transaction
     * @param amount            the attempted amount, held at {@link #AMOUNT_SCALE} fractional
     *                          digits
     * @param maskedCardNumber  the card number already masked to
     *                          {@link #MASKED_CARD_NUMBER_PATTERN}
     * @return a declined event carrying a freshly stamped envelope
     * @throws NullPointerException     when {@code declineReasonCode} or {@code amount} is
     *                                  {@code null}
     * @throws IllegalArgumentException when {@code accountId} is not eleven digits, or when a
     *                                  component fails a check of the canonical constructor
     */
    public static TransactionDeclined of(String accountId, String transactionId,
            DeclineReason declineReasonCode, BigDecimal amount, String maskedCardNumber) {
        return of(EventEnvelope.of(EVENT_TYPE, accountId), transactionId, declineReasonCode, amount,
                maskedCardNumber);
    }

    /**
     * Builds the declined event for a card the cross-reference resolved no account for.
     *
     * <p>This is the only path that builds {@link #UNRESOLVED_ACCOUNT_SCHEMA_VERSION}, and no
     * current producer calls it. It takes no account identifier, because at this point none exists
     * that the platform itself established:
     * {@code app/cbl/CBTRN02C.cbl:L382-L387} has just missed on the keyed read of the
     * cross-reference file. An identifier a caller supplied alongside the card number is not
     * evidence of ownership, and publishing it here would attribute one caller's declined attempt to
     * another caller's account. A synthetic identifier would be worse, because it would occupy the
     * same key space as a real account.
     *
     * <p>The event is keyed on {@code transactionId} instead. That key is deterministic, so a
     * retried publish of the same decline lands on the same partition, and it names no cardholder,
     * no account and no card.
     *
     * <p>The reason code is fixed to {@link #UNRESOLVED_ACCOUNT_REASON} and the description to its
     * text, so a caller can pair neither with anything else.
     *
     * @param transactionId    the transaction identifier, {@link #TRANSACTION_ID_LENGTH}
     *                         characters, which becomes the message key
     * @param amount           the attempted amount, held at {@link #AMOUNT_SCALE} fractional digits
     * @param maskedCardNumber the card number already masked to
     *                         {@link #MASKED_CARD_NUMBER_PATTERN}
     * @return a declined event under {@link #UNRESOLVED_ACCOUNT_SCHEMA_VERSION}, carrying no account
     *         identifier
     * @throws NullPointerException     when {@code amount} is {@code null}
     * @throws IllegalArgumentException when {@code transactionId} does not hold
     *                                  {@link #TRANSACTION_ID_LENGTH} characters, or when a
     *                                  component fails a check of the canonical constructor
     */
    public static TransactionDeclined ofUnresolvedAccount(String transactionId, BigDecimal amount,
            String maskedCardNumber) {
        if (transactionId == null || transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must hold " + TRANSACTION_ID_LENGTH
                    + " characters, because it is the message key of this contract, and the supplied"
                    + " value " + (transactionId == null ? "is null"
                            : "holds " + transactionId.length() + " characters"));
        }
        EventEnvelope envelope = EventEnvelope.of(EVENT_TYPE, transactionId,
                UNRESOLVED_ACCOUNT_SCHEMA_VERSION);
        return new TransactionDeclined(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                transactionId, null, UNRESOLVED_ACCOUNT_REASON,
                UNRESOLVED_ACCOUNT_REASON.description(), amount, maskedCardNumber,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a declined event carrying the refused transaction record, at
     * {@link #TRANSACTION_DETAIL_SCHEMA_VERSION}.
     *
     * <p>This is the contract the ledger posting service reads. The reject row it owns reproduces
     * {@code REJECT-TRAN-DATA PIC X(350)} at {@code app/cbl/CBTRN02C.cbl:L177}, which
     * {@code app/cbl/CBTRN02C.cbl:L447} fills by copying the arriving record, so every descriptive
     * field of that record has to travel with the decline. Version 1 carries the reason and the amount
     * alone, which is enough to notify a cardholder and not enough to write the reject row.
     *
     * <p>The argument order matches
     * {@link TransactionAuthorized#of(String, String, String, String, String, String, BigDecimal,
     * String, String, String, String, String, String, String)}, so the approve and the decline call
     * sites of one producer read alike.
     *
     * @param accountId             the eleven-digit account identifier the cross-reference resolved,
     *                              which becomes both the {@code aggregateId} of the new envelope and
     *                              the Kafka message key
     * @param transactionId         the transaction identifier, {@link #TRANSACTION_ID_LENGTH}
     *                              characters
     * @param declineReasonCode     the reason that stands, which must resolve an account
     * @param transactionTypeCode   the transaction type code
     * @param merchantCategoryCode  the merchant category code, four digits
     * @param source                the capture channel
     * @param description           the transaction description
     * @param amount                the attempted amount, held at {@link #AMOUNT_SCALE} fractional
     *                              digits
     * @param merchantId            the merchant identifier, nine digits
     * @param merchantName          the merchant name
     * @param merchantCity          the merchant city
     * @param merchantZip           the merchant postal code
     * @param maskedCardNumber      the card number already masked to
     *                              {@link #MASKED_CARD_NUMBER_PATTERN}
     * @param originTimestamp       the moment the transaction originated, twenty-six characters
     * @return a declined event at {@link #TRANSACTION_DETAIL_SCHEMA_VERSION}, carrying a freshly
     *         stamped envelope
     * @throws NullPointerException     when {@code declineReasonCode} or {@code amount} is
     *                                  {@code null}
     * @throws IllegalArgumentException when {@code accountId} is not eleven digits, when
     *                                  {@code declineReasonCode} resolves no account, or when a
     *                                  component fails a check of the canonical constructor
     */
    public static TransactionDeclined withTransactionDetail(String accountId, String transactionId,
            DeclineReason declineReasonCode, String transactionTypeCode,
            String merchantCategoryCode, String source, String description, BigDecimal amount,
            String merchantId, String merchantName, String merchantCity, String merchantZip,
            String maskedCardNumber, String originTimestamp) {
        Objects.requireNonNull(declineReasonCode, "declineReasonCode must be present");
        EventEnvelope envelope =
                EventEnvelope.of(EVENT_TYPE, accountId, TRANSACTION_DETAIL_SCHEMA_VERSION);
        return new TransactionDeclined(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                transactionId, envelope.aggregateId(), declineReasonCode,
                declineReasonCode.description(), amount, maskedCardNumber, transactionTypeCode,
                merchantCategoryCode, source, description, merchantId, merchantName, merchantCity,
                merchantZip, originTimestamp);
    }

    /**
     * Renders the technical identifiers and the decline reason, and withholds the rest.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the amount, the masked card number and the account identifier.
     *
     * <p>The reason code and its description stay, because both are fixed constants of
     * {@link DeclineReason} rather than values a caller supplies, and a reader diagnosing a decline
     * needs them.
     *
     * @return the identifiers and the decline reason of this event, never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionDeclined[eventId=" + eventId + ", eventType=" + eventType
                + ", schemaVersion=" + schemaVersion + ", occurredAt=" + occurredAt
                + ", aggregateId=" + EventEnvelope.WITHHELD
                + ", transactionId=" + transactionId
                + ", accountId=" + EventEnvelope.WITHHELD + ", declineReasonCode="
                + declineReasonCode + ", declineReasonDescription=" + declineReasonDescription
                + ", amount=" + EventEnvelope.WITHHELD + ", maskedCardNumber="
                + EventEnvelope.WITHHELD + "]";
    }
}

package com.carddemo.events;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.EventSchemas;
import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import com.carddemo.events.serde.SensitiveEventProperties;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Freezes the version-one baseline of the seven event schema documents.
 *
 * <p>ADDITIVE. This class has no COBOL ancestor. The CardDemo source holds no Java and no test of
 * any kind, so nothing here translates a source construct. The locators below fix the widths, the
 * texts and the codes that the documents reproduce.
 *
 * <p>Each test reads one structural invariant of the documents at {@code schemas/} on the
 * classpath and asserts it. The set of invariants is bounded, and these are its members:
 *
 * <ul>
 *   <li>the exact required set and the required count of each document;</li>
 *   <li>the five envelope properties, their order, and the constraints they share;</li>
 *   <li>the closed property set, its bounded additive room, and the relative order of the eight
 *       top-level keywords;</li>
 *   <li>the title agreeing with the {@code eventType} constant;</li>
 *   <li>the two money patterns, and the two fixed-width timestamp forms;</li>
 *   <li>the four decline code and text pairs, and the three rule identifiers;</li>
 *   <li>the masking pattern, and the absence of a card secret or a status flag;</li>
 *   <li>the property set each Java record puts on the wire.</li>
 * </ul>
 *
 * <p>An edit that breaks one of those turns a test red, which fails the build. This class is not a
 * general schema-diff checker. It compares no document against an earlier revision, so a narrowing
 * outside the list above passes. Every assertion runs offline: no {@code $id} is dereferenced, no
 * schema registry service is contacted, and no Kafka broker starts.
 *
 * <p>Three groups of test sit beside the structural ones. The first serializes each event record
 * this module declares and compares its property set against the document, then reads it back
 * through the two serde classes and compares the two records. The second mutates a document in
 * memory and asserts that a compatible addition passes and each breaking change fails. The third
 * pins which account identifier each reject reason carries, since the cross-reference read resolves
 * none for the first of the four.
 *
 * <p>Five documents cover an event whose record lives here. The account service declares
 * {@code AccountStateChanged} and the card service declares {@code CardUpdated}, so their two
 * documents are covered here as a wire form and in those modules as a record.
 *
 * <p>Two checks carry the weight. {@code everyPropertyKeepsItsVersionOneAssertionKeywords}
 * compares every property of every document against the literal keyword baseline in this class, so
 * a constraint that is removed, added, retyped, widened or narrowed fails even when a sample value
 * would still validate. {@code everyEventRecordRoundTripsThroughItsOwnDocument} publishes each Java
 * record through its own document and reads it back, so a record and its contract cannot drift.
 *
 * <p>Decline locators. The four codes and their texts sit at
 * {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@code :L397-L399}, {@code :L410-L412} and
 * {@code :L417-L419}. The condition blocks that reach the last two are {@code :L403-L413} and
 * {@code :L414-L420}. The reason width is {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
 * {@code :L181} and the description width is {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
 * {@code :L182}. {@code app/jcl/POSTTRAN.jcl:L36} allocates the reject dataset at 430 bytes and
 * corroborates both widths.
 *
 * <p>Money and timestamp locators. The nine-integer-digit amount is
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10} and the ten-integer-digit
 * balance is {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}. The two
 * fixed-width timestamps are {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16}
 * and {@code TRAN-PROC-TS PIC X(26)} at {@code :L17}. The second carries hundredths of a second,
 * from {@code DB2-MIL PIC 9(002)} at {@code app/cbl/CBTRN02C.cbl:L173} and the four-zero move at
 * {@code :L701}.
 *
 * <p>Identifier and omitted-field locators. The eleven-digit account identifier is
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The card verification value
 * no event carries is {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}, and the
 * card status no event carries is {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code :L10}.
 *
 * <p>A decline is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} sets return code 4 when
 * the reject count is positive, so no test here treats a decline as an error.
 *
 * <p>Two pitfalls. Every module declares {@code java.version} 25; the framework parent defaults
 * the language level to 17, and a module that omits the override compiles at 17 with no warning
 * and no failure. The wire form is flat: a payload that nests the five envelope fields under an
 * {@code envelope} key fails every document.
 *
 * <p>Extending the platform. Adding a consumer needs no producer change, since a new consumer
 * group reads an event that already exists. The fixed required sets asserted here are what keep
 * that true, and a field joins a payload either through the bounded {@code extensions} object of
 * this version or through a new {@code schemaVersion} and a new {@code -v<n>} document.
 *
 * <p>Versions in use: Java 25, Apache Maven 3.9.16, junit-jupiter 6.0.3,
 * spring-boot-starter-test 4.1.0, json-schema-validator 3.0.6, jackson-databind 3.1.4 and
 * kafka-clients 4.2.1.
 */
class SchemaBackwardCompatibilityTest {

    /** The approved authorization contract, and the reference copy of the envelope block. */
    private static final String AUTHORIZED = "schemas/transaction-authorized-v1.json";

    /** The declined authorization contract, holding the four reject reasons. */
    private static final String DECLINED = "schemas/transaction-declined-v1.json";

    /**
     * The declined document at version two: the contract for a decline whose account identity the
     * card cross-reference could not resolve.
     *
     * <p>Reject reason {@code 0100} at {@code app/cbl/CBTRN02C.cbl:L385-L387} fires exactly when the
     * keyed read of the cross-reference file misses, so no authoritative account identifier exists at
     * that moment. Version 1 requires one. This document declares none and keys the event on its
     * transaction identifier, so the producer neither trusts an identifier a caller supplied nor
     * invents one inside the real account key space.
     *
     * <p>This document is a NEW version beside version one and not an edit of it. The tests below
     * assert both halves of that claim: version one still requires its account identifier, and
     * version two refuses one.
     */
    private static final String DECLINED_UNRESOLVED = "schemas/transaction-declined-v2.json";

    /** The ledger posting contract, holding the new account balance. */
    private static final String POSTED = "schemas/transaction-posted-v1.json";

    /** The flagged risk assessment contract. */
    private static final String FLAGGED = "schemas/fraud-flagged-v1.json";

    /** The cleared risk assessment contract. */
    private static final String CLEARED = "schemas/fraud-cleared-v1.json";

    /** The account mutation contract the account service publishes. */
    private static final String ACCOUNT_STATE = "schemas/account-state-changed-v1.json";

    /** The card mutation contract the card service publishes. */
    private static final String CARD_STATE = "schemas/card-updated-v1.json";

    /** Every document this baseline covers, written as literals and never derived from a name. */
    private static final List<String> DOCUMENTS =
            List.of(AUTHORIZED, DECLINED, POSTED, FLAGGED, CLEARED, ACCOUNT_STATE, CARD_STATE);

    /**
     * The five documents whose events pass through the posting path.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl} allocates no card dataset and the six OPEN statements at
     * {@code app/cbl/CBTRN02C.cbl:L195-L200} name no card file, so that path reads neither
     * {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10} nor
     * {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6}. The two state-change
     * documents do declare a status, because the account and card services own that field.
     */
    private static final List<String> TRANSACTION_AND_FRAUD_DOCUMENTS =
            List.of(AUTHORIZED, DECLINED, POSTED, FLAGGED, CLEARED);

    /** The two documents an account or card mutation publishes. */
    private static final List<String> STATE_CHANGE_DOCUMENTS =
            List.of(ACCOUNT_STATE, CARD_STATE);

    /**
     * The five documents whose Java record this module ships, so a record-to-document parity check
     * can build one instance and serialize it here.
     *
     * <p>The two state-change records belong to the account service and the card service. Their
     * parity is asserted by the publish-path test of the service that owns each one.
     */
    private static final List<String> RECORD_BEARING_DOCUMENTS =
            List.of(AUTHORIZED, DECLINED, POSTED, FLAGGED, CLEARED);

    /** The four documents that declare a masked card number. */
    private static final List<String> MASKED_CARD_DOCUMENTS =
            List.of(AUTHORIZED, DECLINED, POSTED, CARD_STATE);

    /** The three documents that declare no card number in any form. */
    private static final List<String> CARD_FREE_DOCUMENTS =
            List.of(FLAGGED, CLEARED, ACCOUNT_STATE);

    /** The one property every document declares and no record writes. */
    private static final String EXTENSIONS_PROPERTY = "extensions";

    /** How many members the bounded {@code extensions} object of every document admits. */
    private static final int EXTENSIONS_MAX_PROPERTIES = 16;

    /** The widest value the bounded {@code extensions} object of every document admits. */
    private static final int EXTENSIONS_VALUE_MAX_LENGTH = 256;

    /** The names the bounded {@code extensions} object of every document admits. */
    private static final String EXTENSIONS_NAME_PATTERN = "^[a-zA-Z][a-zA-Z0-9_]{0,39}$";

    /** The dialect every document declares. */
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    /** The simple class name each document titles itself with, and its {@code eventType} const. */
    private static final Map<String, String> TITLES = Map.of(
            AUTHORIZED, "TransactionAuthorized",
            DECLINED, "TransactionDeclined",
            POSTED, "TransactionPosted",
            FLAGGED, "FraudFlagged",
            CLEARED, "FraudCleared",
            ACCOUNT_STATE, "AccountStateChanged",
            CARD_STATE, "CardUpdated");

    /** The nine keywords every document declares, in the relative order they appear. */
    private static final List<String> ORDERED_TOP_LEVEL_KEYWORDS = List.of(
            "$schema", "$id", "title", "description", "type", "properties", "required",
            "maxProperties", "additionalProperties");

    /** The five envelope properties, in the order every document declares them. */
    private static final List<String> ENVELOPE_PROPERTIES = List.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId");

    /**
     * Keywords that annotate a property and assert nothing about an instance. A difference in one
     * of these carries no wire effect, so the envelope comparison skips them.
     */
    private static final Set<String> ANNOTATION_KEYWORDS = Set.of(
            "description", "examples", "$comment", "title", "default", "deprecated", "readOnly",
            "writeOnly");

    /** The exact required set of each document. A name entering or leaving this set is breaking. */
    private static final Map<String, Set<String>> REQUIRED_SETS = requiredSets();

    /** Nine integer digits and two fractional digits, from {@code app/cpy/CVTRA05Y.cpy:L10}. */
    private static final String NINE_INTEGER_DIGIT_AMOUNT_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /** Ten integer digits and two fractional digits, from {@code app/cpy/CVACT01Y.cpy:L7}. */
    private static final String TEN_INTEGER_DIGIT_BALANCE_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /** Twelve asterisks and the last four digits of the Primary Account Number (PAN). */
    private static final String MASKED_CARD_PATTERN = "^\\*{12}[0-9]{4}$";

    /** The eleven-digit account identifier, from {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final String ACCOUNT_IDENTIFIER_PATTERN = "^[0-9]{11}$";

    /** Space separator, colons, and six fractional digits. From {@code TRAN-ORIG-TS}. */
    private static final String AUTHORIZED_AT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$";

    /** Three dashes, three dots, hundredths, then four zeros. From {@code TRAN-PROC-TS}. */
    private static final String POSTED_AT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000$";

    /** The property carrying the transaction amount on three documents. */
    private static final String AMOUNT_PROPERTY = "amount";

    /** The property carrying the account balance after a posting. */
    private static final String BALANCE_PROPERTY = "newBalance";

    /** The property carrying the masked Primary Account Number (PAN) on three documents. */
    private static final String MASKED_CARD_PROPERTY = "maskedCardNumber";

    /** The property carrying the reject reason code. */
    private static final String DECLINE_CODE_PROPERTY = "declineReasonCode";

    /** The property carrying the reject reason text. */
    private static final String DECLINE_TEXT_PROPERTY = "declineReasonDescription";

    /** The reason text width, from {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. */
    private static final int DECLINE_TEXT_WIDTH = 76;

    /** Each reject reason code paired with the text the source writes beside it. */
    private static final Map<String, String> DECLINE_PAIRS = declinePairs();

    /**
     * The code the source writes at {@code app/cbl/CBTRN02C.cbl:L556} after the account rewrite
     * hits an invalid key, excluded from the decline enum and routed to the dead-letter topic.
     */
    private static final String EXCLUDED_REASON_CODE = "0109";

    /** The three rule identifiers the flagged document enumerates. */
    private static final List<String> TRIGGERED_RULE_VALUES = List.of(
            "VELOCITY", "AMOUNT_ANOMALY", "MERCHANT_CATEGORY");

    /**
     * The two change kinds the account document enumerates. The update covers
     * {@code app/cbl/COACTUPC.cbl}, and the cycle close covers the two accumulator statements at
     * {@code app/cbl/CBACT04C.cbl:L353-L354}.
     */
    private static final List<String> ACCOUNT_CHANGE_KINDS = List.of(
            "ACCOUNT_UPDATED", "BILLING_CYCLE_CLOSED");

    /**
     * The one change kind the card document enumerates, covering the field update validated at
     * {@code app/cbl/COCRDUPC.cbl:L190-L202}.
     */
    private static final List<String> CARD_CHANGE_KINDS = List.of("CARD_UPDATED");

    /** The property naming what changed on the two state change documents. */
    private static final String CHANGE_TYPE_PROPERTY = "changeType";

    /**
     * Card secrets and status flags that no transaction or fraud document may name. Lower case for
     * a text scan.
     */
    private static final List<String> FORBIDDEN_SOURCE_FIELDS = List.of(
            "cvv", "activestatus", "active_status", "cardstatus", "accountstatus");

    /**
     * Property-name fragments that no state change document may declare. A state change carries the
     * record after the change, so it carries the active status, and it still carries no card
     * secret.
     */
    private static final List<String> FORBIDDEN_STATE_CHANGE_PROPERTIES = List.of(
            "cvv", "verification", "cardstatus", "accountstatus");

    /** The one-character active status the two state change documents carry as data. */
    private static final String ACTIVE_STATUS_PROPERTY = "activeStatus";

    /** Card-number checks the source does not perform, so no document may declare one. */
    private static final List<String> FORBIDDEN_CARD_CHECKS = List.of(
            "luhn", "checksum", "mod10", "mod 10");

    /** Property-name fragments each document must not carry. Lower case for a name scan. */
    private static final Map<String, List<String>> FORBIDDEN_PROPERTY_FRAGMENTS = Map.of(
            AUTHORIZED, List.of("processedat", "procts", "proc-ts", "filler"),
            DECLINED, List.of("filler", "rejecttrandata", "reject-tran-data"),
            POSTED, List.of("authorizedat", "origts", "orig-ts", "cyccredit", "cycdebit",
                    "creditlimit", "filler"),
            FLAGGED, List.of("cardnumber", "maskedcard", "pan", "amount", "balance", "authorizedat",
                    "postedat"),
            CLEARED, List.of("riskscore", "triggered", "rules", "amount", "balance", "cardnumber",
                    "maskedcard", "pan", "cvv", "reason", "note"),
            ACCOUNT_STATE, List.of("cardnumber", "maskedcard", "pan", "cvv", "transactionid",
                    "riskscore", "filler", "customerid"),
            CARD_STATE, List.of("cvv", "verificationvalue", "currentbalance", "creditlimit",
                    "riskscore", "transactionid", "filler"));

    /** A well-formed event identifier, matching the envelope pattern. */
    private static final String EVENT_ID = "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418";

    /** A well-formed publish timestamp, in Coordinated Universal Time. */
    private static final String OCCURRED_AT = "2022-06-10T19:27:53.412Z";

    /** A well-formed risk assessment timestamp, in Coordinated Universal Time. */
    private static final String ASSESSED_AT = "2022-06-10T19:27:53.512Z";

    /** A well-formed account identifier, eleven digits. */
    private static final String ACCOUNT_IDENTIFIER = "00000000007";

    /** A second account identifier, for the case where the two identifiers disagree. */
    private static final String OTHER_ACCOUNT_IDENTIFIER = "00000000011";

    /** The envelope account identifier, and always the Kafka message key. */
    private static final String ENVELOPE_ACCOUNT_PROPERTY = "aggregateId";

    /** The payload account identifier, declared by the three documents whose records carry it. */
    private static final String PAYLOAD_ACCOUNT_PROPERTY = "accountId";

    /** A risk score inside the bounds the flagged document declares. */
    private static final int RISK_SCORE = 82;

    /** Any topic name. Schema selection reads {@code eventType}, so the topic changes nothing. */
    private static final String ANY_TOPIC = "schema-parity";

    /** The event type whose topic the unknown-type test publishes against. */
    private static final String POSTED_EVENT_TYPE = TransactionPosted.class.getSimpleName();

    /** A well-formed transaction identifier, sixteen characters. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** A masked card number that the pattern accepts. */
    private static final String MASKED_CARD_NUMBER = "************7065";


    /**
     * The ten-character expiry text the two mutation documents carry, from
     * {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9} and
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11}.
     */
    private static final String EXPIRATION_DATE = "2027-05-31";

    /** A full sixteen-digit Primary Account Number (PAN) that the pattern rejects. */
    private static final String FULL_CARD_NUMBER = "4859452612877065";

    /** A positive amount the nine-digit pattern accepts. */
    private static final String POSITIVE_AMOUNT = "1250.75";

    /** A negative amount the nine-digit pattern accepts. */
    private static final String NEGATIVE_AMOUNT = "-125.00";

    /** A positive balance the ten-digit pattern accepts. */
    private static final String POSITIVE_BALANCE = "1250.75";

    /** A negative balance the ten-digit pattern accepts. */
    private static final String NEGATIVE_BALANCE = "-43.10";

    /**
     * A zero balance, the value both cycle accumulators hold in all 50 records of
     * {@code app/data/ASCII/acctdata.txt}.
     */
    private static final String ZERO_BALANCE = "0.00";

    /** The account change type an account update publishes. */
    private static final String ACCOUNT_UPDATED = "ACCOUNT_UPDATED";

    /** The account change type the cycle-close endpoint publishes. */
    private static final String BILLING_CYCLE_CLOSED = "BILLING_CYCLE_CLOSED";

    /**
     * The status byte both state-change documents carry, from
     * {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6} and
     * {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}. All 50 records of
     * {@code app/data/ASCII/acctdata.txt} and {@code app/data/ASCII/carddata.txt} hold this value.
     */
    private static final String ACTIVE_STATUS = "Y";


    /**
     * A cardholder name inside {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}.
     */
    private static final String EMBOSSED_NAME = "PAULA A CHRISTOFFERSEN";

    /** The authorization timestamp form the authorized document accepts. */
    private static final String AUTHORIZED_AT_VALUE = "2022-06-10 19:27:53.000000";

    /** The posting timestamp form the posted document accepts. */
    private static final String POSTED_AT_VALUE = "2022-07-19-23.16.01.470000";

    /** The topic name each serde call receives. Schema selection ignores it. */
    private static final String TOPIC = "transaction.posted";

    /**
     * Property names no event may carry, each one the guard refuses.
     *
     * <p>{@code CARD-CVV-CD PIC 9(03)} sits at {@code app/cpy/CVACT02Y.cpy:L7} and
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
     * {@code SEC-USR-PWD PIC X(08)} sits at {@code app/cpy/CSUSR01Y.cpy:L21} and
     * {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L17}.
     */
    private static final List<String> FORBIDDEN_PROPERTY_NAMES = List.of(
            "cvv", "cardCvv", "cvvCode", "cardVerificationValue", "verificationValue",
            "securityCode", "cardNumber", "fullCardNumber", "primaryAccountNumber", "pan",
            "pinBlock", "pin", "password", "userPassword", "ssn", "socialSecurityNumber");

    /**
     * Property names the guard must not refuse: the properties the seven documents declare, plus
     * two names a later version could add.
     */
    private static final List<String> ALLOWED_PROPERTY_NAMES = List.of(
            "maskedCardNumber", "activeStatus", "accountId", "aggregateId", "transactionId",
            "merchantName", "embossedName", "expirationDate", "settlementReference",
            "shippingAddress");

    /**
     * The property name every probe uses when it needs a name no document declares.
     *
     * <p>Three probes need one. An instance carrying this property at the top level must fail its
     * document. A document that adds this property as optional must stay compatible with the one
     * that does not. A document that adds it as required must not. No document may ever declare it,
     * which keeps all three probes honest: a contributor who adds a field of their own therefore
     * turns none of them red.</p>
     */
    private static final String NEVER_DECLARED_PROBE_PROPERTY = "probeOnlyNeverDeclaredProperty";

    /**
     * Sensitive names spelled with underscores, each one a member name the bounded
     * {@code extensions} object of every document admits.
     *
     * <p>The member-name pattern is {@code ^[a-zA-Z][a-zA-Z0-9_]{0,39}$}, which permits an
     * underscore and refuses a hyphen. Every entry below therefore satisfies the document and
     * reaches the publish-side guard, which is the gate that refuses it.</p>
     */
    private static final List<String> SEPARATOR_SPELLED_SENSITIVE_MEMBERS = List.of(
            "card_number", "CARD_NUMBER", "card_verification_value", "social_security_number",
            "primary_account_number", "p_a_n", "pin_block", "user_password", "c_v_v", "s_s_n");

    /** Reads and writes JSON trees. Jackson 3 only; no Jackson 2 type appears in this class. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Writes and reads the event records themselves, with timestamps as text so
     * {@code occurredAt} and {@code assessedAt} carry an ISO-8601 string and never a numeric epoch.
     */
    private static final ObjectMapper RECORD_MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /** Compiles a document into a validating schema, offline. */
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** The publish path every round trip and every guard assertion runs through. */
    private static final JsonSchemaValidatingSerializer<Object> SERIALIZER =
            new JsonSchemaValidatingSerializer<>();

    /**
     * The consume path for a payload this class built as a tree.
     *
     * <p>Binding to a tree covers the two state-change documents, whose records live in the account
     * service and the card service and cannot be built here.
     */
    private static final JsonSchemaValidatingDeserializer<JsonNode> TREE_DESERIALIZER =
            new JsonSchemaValidatingDeserializer<>(JsonNode.class);

    /** One sample record per document whose record this module declares. */
    private static final Map<String, Object> RECORDS_BY_DOCUMENT = recordsByDocument();

    /**
     * Asserts that every document loads from the classpath, compiles as a Draft 2020-12 schema,
     * and declares a title, an {@code $id} and an object type.
     *
     * <p>No document holds a {@code $ref} or a {@code $defs} block, so nothing here resolves a
     * reference and no network call can occur.</p>
     */
    @Test
    void everyDocumentLoadsFromTheClasspathAndCompilesOffline() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);

            assertEquals(DRAFT_2020_12, tree.get("$schema").asString(),
                    document + " stopped declaring the Draft 2020-12 dialect, so a consumer "
                            + "validating against version one changes dialect underneath it");
            assertNotNull(tree.get("$id"),
                    document + " stopped declaring an $id, so the document lost its stable "
                            + "identity");
            assertEquals("object", tree.get("type").asString(),
                    document + " stopped declaring an object type");
            assertNotNull(schemaFor(document),
                    document + " failed to compile as a Draft 2020-12 schema");

            String text = rawTextOf(document);
            assertFalse(text.contains("\"$ref\""),
                    document + " introduced a $ref, and this baseline resolves no reference");
            assertFalse(text.contains("\"$defs\""),
                    document + " introduced a $defs block, and this baseline resolves no "
                            + "reference");
        }
    }

    @Test
    void anAbsentSchemaResourceFailsWithTheResourcePathNamed() {
        String absent = "schemas/transaction-settled-v1.json";

        AssertionError failure = assertThrows(AssertionError.class, () -> readDocument(absent),
                "loading an absent schema resource stopped failing, so a renamed document would "
                        + "pass unnoticed");

        assertTrue(failure.getMessage().contains(absent),
                "the failure for an absent schema resource stopped naming the path, and the "
                        + "message read: " + failure.getMessage());
    }

    @Test
    void everyDocumentKeepsTheRelativeOrderOfItsEightTopLevelKeywords() {
        for (String document : DOCUMENTS) {
            List<String> declared = new ArrayList<>();
            for (String keyword : readDocument(document).propertyNames()) {
                if (ORDERED_TOP_LEVEL_KEYWORDS.contains(keyword)) {
                    declared.add(keyword);
                }
            }

            assertEquals(ORDERED_TOP_LEVEL_KEYWORDS, declared,
                    document + " changed the presence or the relative order of its top-level "
                            + "keywords, and declared " + declared);
        }
    }

    @Test
    void theTitleOfEveryDocumentEqualsItsEventTypeConst() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);
            String expected = TITLES.get(document);

            assertEquals(expected, tree.get("title").asString(),
                    document + " changed its title, which routing and the event record name both "
                            + "follow");
            assertEquals(expected, propertiesOf(document).get("eventType").get("const").asString(),
                    document + " lost the agreement between its title and its eventType const, so "
                            + "a consumer routing on eventType would read a value the title "
                            + "denies");
        }
    }

    @Test
    void everyDocumentDeclaresTheFiveEnvelopePropertiesFirstAndInOneOrder() {
        for (String document : DOCUMENTS) {
            List<String> declared = new ArrayList<>(propertiesOf(document).propertyNames());

            assertTrue(declared.size() > ENVELOPE_PROPERTIES.size(),
                    document + " declares " + declared.size() + " properties, too few to carry "
                            + "the five envelope fields and a payload");
            assertEquals(ENVELOPE_PROPERTIES, declared.subList(0, ENVELOPE_PROPERTIES.size()),
                    document + " reordered or dropped an envelope property, and its first five "
                            + "properties read " + declared.subList(0, ENVELOPE_PROPERTIES.size()));
        }
    }

    @Test
    void theEnvelopeBlockIsIdenticalAcrossEveryDocumentExceptTheEventTypeConst() {
        JsonNode reference = propertiesOf(AUTHORIZED);

        String accountPattern = reference.get("aggregateId").get("pattern").asString();
        assertEquals(ACCOUNT_IDENTIFIER_PATTERN, accountPattern,
                AUTHORIZED + " changed the aggregateId pattern away from the eleven digits of "
                        + "XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7, and that value is "
                        + "the Kafka message key");

        for (String document : DOCUMENTS) {
            JsonNode properties = propertiesOf(document);

            for (String property : ENVELOPE_PROPERTIES) {
                Map<String, String> expected = assertionKeywords(reference.get(property));
                Map<String, String> actual = assertionKeywords(properties.get(property));

                if ("eventType".equals(property)) {
                    expected.remove("const");
                    actual.remove("const");
                }

                assertEquals(expected, actual,
                        document + " changed the envelope property " + property + ", which every "
                                + "consumer reads before it looks at a payload");
            }
        }
    }

    @Test
    void everyDocumentKeepsItsExactRequiredPropertySet() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);
            JsonNode properties = tree.get("properties");
            Set<String> declared = new LinkedHashSet<>();

            for (JsonNode entry : tree.get("required")) {
                String name = entry.asString();
                declared.add(name);
                assertTrue(properties.has(name),
                        document + " requires " + name + " and declares no schema for it");
            }

            Set<String> expected = REQUIRED_SETS.get(document);
            assertEquals(expected.size(), tree.get("required").size(),
                    document + " changed its required-property count from " + expected.size()
                            + ", so a field joined or left the version-one contract");
            assertEquals(expected, declared,
                    document + " changed its required-property set, and now requires " + declared);
        }
    }

    /**
     * Asserts every document closes its top-level property set and bounds the one place an added
     * value may travel.
     *
     * <p>Two obligations meet here. A consumer validating against version one has to accept an event
     * a later version enriched, or the next additive change breaks every consumer already on the
     * topic. A validator also has to refuse a property no document declares, or a producer can hide
     * an unmasked Primary Account Number, a card verification value or any other undeclared field
     * inside a known event type, and no schema stands in the way.</p>
     *
     * <p>Both hold at once when the top level is closed and additive room is bounded: an unknown
     * top-level property is refused, and a later version puts its new value in the {@code extensions}
     * object, which every version-one consumer already accepts. This test asserts the closed half and
     * the bounds; {@link #everyDocumentAcceptsAnAddedPropertySoANewFieldDoesNotBreakAConsumer()}
     * asserts the additive half.</p>
     */
    @Test
    void everyDocumentClosesItsPropertySetAndBoundsItsAdditiveExtensionObject() {
        for (String document : DOCUMENTS) {
            JsonNode root = readDocument(document);
            JsonNode declared = root.get("additionalProperties");

            assertNotNull(declared,
                    document + " stopped declaring additionalProperties, so its stance on an "
                            + "added field is no longer written down");
            assertFalse(declared.asBoolean(),
                    document + " reopened its top-level property set, so a producer could hide an "
                            + "undeclared field, a full card number among them, inside a known "
                            + "event type");

            JsonNode ceiling = root.get("maxProperties");
            assertNotNull(ceiling,
                    document + " stopped declaring maxProperties, so the number of properties one "
                            + "event may carry is no longer bounded");
            assertEquals(propertiesOf(document).size(), ceiling.asInt(),
                    document + " declares a maxProperties ceiling that does not match the number "
                            + "of properties it declares");

            JsonNode extensions = propertiesOf(document).get("extensions");
            assertNotNull(extensions,
                    document + " stopped declaring the extensions object, so a later version has "
                            + "nowhere to put an added value that a version-one consumer accepts");
            assertEquals("object", extensions.path("type").asString(),
                    document + " declares extensions as something other than an object");
            assertEquals(EXTENSIONS_MAX_PROPERTIES, extensions.path("maxProperties").asInt(),
                    document + " changed how many members the extensions object may carry");
            assertEquals("string", extensions.path("additionalProperties").path("type").asString(),
                    document + " admits a non-string value into extensions, so a nested object or "
                            + "array could travel there");
            assertEquals(EXTENSIONS_VALUE_MAX_LENGTH,
                    extensions.path("additionalProperties").path("maxLength").asInt(),
                    document + " changed the widest value the extensions object may carry");
            assertEquals(EXTENSIONS_NAME_PATTERN,
                    extensions.path("propertyNames").path("pattern").asString(),
                    document + " changed the names the extensions object admits");

            assertFalse(readDocument(document).path("required").toString().contains("extensions"),
                    document + " made extensions required, so every producer would have to send it");
        }
    }

    /**
     * Asserts a payload that nests the five envelope properties under an {@code envelope} key
     * fails every document, and that the failure names each missing envelope property.
     *
     * <p>The wire form is flat: envelope and payload properties sit at one level.</p>
     */
    @Test
    void aNestedEnvelopeShapeFailsEveryDocumentAndNamesTheMissingEnvelopeProperties() {
        for (String document : DOCUMENTS) {
            ObjectNode flat = validPayloadFor(document);
            ObjectNode nested = MAPPER.createObjectNode();
            ObjectNode envelope = MAPPER.createObjectNode();

            for (Map.Entry<String, JsonNode> property : flat.properties()) {
                if (ENVELOPE_PROPERTIES.contains(property.getKey())) {
                    envelope.set(property.getKey(), property.getValue());
                } else {
                    nested.set(property.getKey(), property.getValue());
                }
            }
            nested.set("envelope", envelope);

            List<Error> errors = validate(document, nested);
            assertFalse(errors.isEmpty(),
                    document + " accepted a payload nesting the envelope under an envelope key, so "
                            + "the flat wire form is no longer enforced");

            String reported = messagesOf(errors);
            for (String property : ENVELOPE_PROPERTIES) {
                assertTrue(reported.contains(property),
                        document + " rejected the nested shape without naming the missing envelope "
                                + "property " + property + ", and reported: " + reported);
            }
        }
    }

    @Test
    void noDocumentDeclaresAPropertyAsAJsonNumber() {
        for (String document : DOCUMENTS) {
            for (Map.Entry<String, JsonNode> property : propertiesOf(document).properties()) {
                assertFalse("number".equals(typeOf(property.getValue())),
                        document + " declared property " + property.getKey() + " as a JSON number, "
                                + "and a number parses into a binary floating-point value");
            }

            String text = rawTextOf(document);
            boolean spelled = text.contains("\"type\": \"number\"")
                    || text.contains("\"type\":\"number\"");
            assertFalse(spelled,
                    document + " introduced a JSON number type, and a number parses into a binary "
                            + "floating-point value");
        }
    }

    @Test
    void theNineDigitAmountAndTheTenDigitBalancePatternsAreNotSwapped() {
        for (String document : DOCUMENTS) {
            JsonNode properties = propertiesOf(document);

            if (properties.has(AMOUNT_PROPERTY)) {
                JsonNode amount = properties.get(AMOUNT_PROPERTY);
                assertEquals("string", typeOf(amount),
                        document + " stopped carrying " + AMOUNT_PROPERTY + " as a string");
                assertEquals(NINE_INTEGER_DIGIT_AMOUNT_PATTERN, amount.get("pattern").asString(),
                        document + " changed the nine-integer-digit amount pattern, which follows "
                                + "TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10");
            }

            if (properties.has(BALANCE_PROPERTY)) {
                JsonNode balance = properties.get(BALANCE_PROPERTY);
                assertEquals("string", typeOf(balance),
                        document + " stopped carrying " + BALANCE_PROPERTY + " as a string");
                assertEquals(TEN_INTEGER_DIGIT_BALANCE_PATTERN, balance.get("pattern").asString(),
                        document + " changed the ten-integer-digit balance pattern, which follows "
                                + "ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L7");
            }
        }

        assertNotEquals(NINE_INTEGER_DIGIT_AMOUNT_PATTERN, TEN_INTEGER_DIGIT_BALANCE_PATTERN,
                "the amount pattern and the balance pattern became the same expression, so one of "
                        + "the two source widths was lost");
    }

    /**
     * Asserts both money patterns accept a negative value.
     *
     * <p>A negative amount reaches the cycle debit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L551}, so a pattern matching only non-negative values would
     * reject part of the daily feed.</p>
     */
    @Test
    void bothMoneyPatternsAcceptANegativeValue() {
        ObjectNode declinedRefund = validPayloadFor(DECLINED);
        declinedRefund.put(AMOUNT_PROPERTY, NEGATIVE_AMOUNT);
        assertValid(DECLINED, declinedRefund,
                "the amount pattern stopped accepting a negative value");

        ObjectNode postedRefund = validPayloadFor(POSTED);
        postedRefund.put(AMOUNT_PROPERTY, NEGATIVE_AMOUNT);
        postedRefund.put(BALANCE_PROPERTY, NEGATIVE_BALANCE);
        assertValid(POSTED, postedRefund,
                "the amount or the balance pattern stopped accepting a negative value");
    }

    @Test
    void bothMoneyPatternsRejectMalformedText() {
        for (String malformed : List.of("50.4", "50.475", "1,000.00", "$50.47")) {
            ObjectNode payload = validPayloadFor(POSTED);
            payload.put(AMOUNT_PROPERTY, malformed);
            assertInvalid(POSTED, payload,
                    "the amount pattern accepted the malformed value " + malformed);
        }

        ObjectNode overWidth = validPayloadFor(POSTED);
        overWidth.put(BALANCE_PROPERTY, "12345678901.00");
        assertInvalid(POSTED, overWidth,
                "the balance pattern accepted eleven integer digits, one more than "
                        + "ACCT-CURR-BAL PIC S9(10)V99 carries");

        ObjectNode shortFraction = validPayloadFor(POSTED);
        shortFraction.put(BALANCE_PROPERTY, "1250.7");
        assertInvalid(POSTED, shortFraction,
                "the balance pattern accepted a one-digit fraction");
    }

    @Test
    void integerAppearsOnlyOnSchemaVersionAndOnRiskScoreInTheFlaggedDocument() {
        for (String document : DOCUMENTS) {
            JsonNode properties = propertiesOf(document);
            Set<String> integers = new LinkedHashSet<>();

            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                if ("integer".equals(typeOf(property.getValue()))) {
                    integers.add(property.getKey());
                }
            }

            Set<String> expected = FLAGGED.equals(document)
                    ? new LinkedHashSet<>(List.of("schemaVersion", "riskScore"))
                    : new LinkedHashSet<>(List.of("schemaVersion"));
            assertEquals(expected, integers,
                    document + " changed which properties travel as a JSON integer, and now "
                            + "declares " + integers);

            assertEquals(1, properties.get("schemaVersion").get("const").intValue(),
                    document + " changed its schemaVersion const away from 1, which the -v1 "
                            + "filename suffix follows");
        }

        JsonNode riskScore = propertiesOf(FLAGGED).get("riskScore");
        assertEquals(0, riskScore.get("minimum").intValue(),
                FLAGGED + " changed the riskScore lower bound away from 0");
        assertEquals(100, riskScore.get("maximum").intValue(),
                FLAGGED + " changed the riskScore upper bound away from 100");
    }

    /**
     * Asserts the declined document enumerates exactly four reject reason codes, each a
     * four-character zero-padded string, and that the code is a single value.
     *
     * <p>One record carries one code. {@code app/cbl/CBTRN02C.cbl:L370-L378} runs the
     * cross-reference check first, and the account check only while the code is still zero. The
     * two tests at {@code :L403-L413} and {@code :L414-L420} share one branch, so the later test
     * overwrites the earlier code.</p>
     */
    @Test
    void theDeclinedDocumentEnumeratesExactlyFourReasonCodes() {
        JsonNode code = propertiesOf(DECLINED).get(DECLINE_CODE_PROPERTY);

        assertEquals("string", typeOf(code),
                DECLINED + " stopped carrying " + DECLINE_CODE_PROPERTY + " as a string, and the "
                        + "wire form is the four-character zero-padded code");
        assertFalse("array".equals(typeOf(code)),
                DECLINED + " turned " + DECLINE_CODE_PROPERTY + " into an array, and one event "
                        + "carries one code");

        List<String> declared = stringsOf(code.get("enum"));
        assertEquals(new ArrayList<>(DECLINE_PAIRS.keySet()), declared,
                DECLINED + " changed the reject reason enum, and now enumerates " + declared);

        for (String value : declared) {
            assertEquals(4, value.length(),
                    DECLINED + " enumerated the reason code " + value + ", which is not the "
                            + "four-character width of WS-VALIDATION-FAIL-REASON PIC 9(04) at "
                            + "app/cbl/CBTRN02C.cbl:L181");
        }
    }

    @Test
    void theFourDeclineDescriptionsMatchTheSourceTextsCharacterForCharacter() {
        JsonNode text = propertiesOf(DECLINED).get(DECLINE_TEXT_PROPERTY);

        assertEquals(DECLINE_TEXT_WIDTH, text.get("maxLength").intValue(),
                DECLINED + " changed the reason text limit away from the 76 characters of "
                        + "WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at app/cbl/CBTRN02C.cbl:L182, "
                        + "corroborated by the 430-byte reject record at app/jcl/POSTTRAN.jcl:L36");

        List<String> declared = stringsOf(text.get("enum"));
        assertEquals(new ArrayList<>(DECLINE_PAIRS.values()), declared,
                DECLINED + " changed the reject reason texts, and now enumerates " + declared);

        for (String value : declared) {
            assertTrue(value.length() <= DECLINE_TEXT_WIDTH,
                    DECLINED + " enumerated a reason text of " + value.length() + " characters, "
                            + "which does not fit the 76-character description field");
        }
    }

    /**
     * Asserts a payload for each of the four reject reasons validates.
     *
     * <p>A decline is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} sets return code 4
     * when the reject count is positive, and treats the run as normal.</p>
     */
    @Test
    void everyDeclineReasonValidatesAsExpectedTraffic() {
        for (Map.Entry<String, String> pair : DECLINE_PAIRS.entrySet()) {
            ObjectNode payload = validPayloadFor(DECLINED);
            payload.put(DECLINE_CODE_PROPERTY, pair.getKey());
            payload.put(DECLINE_TEXT_PROPERTY, pair.getValue());

            assertValid(DECLINED, payload,
                    "the declined document rejected reason " + pair.getKey() + ", which the source "
                            + "writes as a normal outcome");
        }
    }

    @Test
    void aReasonCodeOutsideTheFourFailsValidation() {
        ObjectNode unknownCode = validPayloadFor(DECLINED);
        unknownCode.put(DECLINE_CODE_PROPERTY, EXCLUDED_REASON_CODE);
        assertInvalid(DECLINED, unknownCode,
                "the declined document accepted the reason code " + EXCLUDED_REASON_CODE
                        + ", which the decline enum excludes");

        ObjectNode bareInteger = validPayloadFor(DECLINED);
        bareInteger.put(DECLINE_CODE_PROPERTY, 9999);
        assertInvalid(DECLINED, bareInteger,
                "the declined document accepted a bare integer reason code, and the wire form is "
                        + "the four-character zero-padded string");
    }

    /**
     * Asserts {@code authorizedAt} keeps the space-separated form with six fractional digits, and
     * rejects a value using the letter T as its separator.
     *
     * <p>The field is 26 characters of fixed-width text, from {@code TRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L16}.</p>
     */
    @Test
    void authorizedAtKeepsItsSpaceSeparatedSixDigitFractionForm() {
        JsonNode authorizedAt = propertiesOf(AUTHORIZED).get("authorizedAt");

        assertEquals(AUTHORIZED_AT_PATTERN, authorizedAt.get("pattern").asString(),
                AUTHORIZED + " changed the authorizedAt pattern away from the form TRAN-ORIG-TS "
                        + "carries at app/cpy/CVTRA05Y.cpy:L16");
        assertEquals(26, authorizedAt.get("minLength").intValue(),
                AUTHORIZED + " changed the authorizedAt minimum length away from 26");
        assertEquals(26, authorizedAt.get("maxLength").intValue(),
                AUTHORIZED + " changed the authorizedAt maximum length away from 26");

        ObjectNode accepted = validPayloadFor(AUTHORIZED);
        accepted.put("authorizedAt", AUTHORIZED_AT_VALUE);
        assertValid(AUTHORIZED, accepted,
                "authorizedAt rejected " + AUTHORIZED_AT_VALUE + ", the form the daily feed holds");

        ObjectNode isoSeparator = validPayloadFor(AUTHORIZED);
        isoSeparator.put("authorizedAt", "2022-06-10T19:27:53.000000");
        assertInvalid(AUTHORIZED, isoSeparator,
                "authorizedAt accepted a T separator, and the source writes a space at "
                        + "position 11");
    }

    /**
     * Asserts {@code postedAt} keeps hundredths of a second followed by four zeros, and rejects the
     * space-separated form, the T-separated form, and six significant fractional digits.
     *
     * <p>Precision comes from {@code DB2-MIL PIC 9(002)} at {@code app/cbl/CBTRN02C.cbl:L173} and
     * the four-zero move at {@code :L701}. A consumer truncates to hundredths before any
     * byte-for-byte comparison.</p>
     */
    @Test
    void postedAtKeepsItsHundredthsPrecisionAndFourTrailingZeros() {
        JsonNode postedAt = propertiesOf(POSTED).get("postedAt");

        assertEquals(POSTED_AT_PATTERN, postedAt.get("pattern").asString(),
                POSTED + " changed the postedAt pattern away from the form TRAN-PROC-TS carries at "
                        + "app/cpy/CVTRA05Y.cpy:L17");
        assertEquals(26, postedAt.get("minLength").intValue(),
                POSTED + " changed the postedAt minimum length away from 26");
        assertEquals(26, postedAt.get("maxLength").intValue(),
                POSTED + " changed the postedAt maximum length away from 26");

        ObjectNode accepted = validPayloadFor(POSTED);
        accepted.put("postedAt", POSTED_AT_VALUE);
        assertValid(POSTED, accepted,
                "postedAt rejected " + POSTED_AT_VALUE + ", the form the posting routine writes");

        for (String rejected : List.of("2022-07-19 23:16:01.470000", "2022-07-19T23:16:01.470000",
                "2022-07-19-23.16.01.470123")) {
            ObjectNode payload = validPayloadFor(POSTED);
            payload.put("postedAt", rejected);
            assertInvalid(POSTED, payload,
                    "postedAt accepted " + rejected + ", which is not the dash-and-dot form with "
                            + "hundredths precision and four trailing zeros");
        }
    }

    @Test
    void neitherCobolTimestampClaimsAnIsoDateTimeFormat() {
        assertFalse(propertiesOf(AUTHORIZED).get("authorizedAt").has("format"),
                AUTHORIZED + " gave authorizedAt a format keyword, and the field is fixed-width "
                        + "text that no ISO-8601 parser reads");
        assertFalse(propertiesOf(POSTED).get("postedAt").has("format"),
                POSTED + " gave postedAt a format keyword, and the field is fixed-width text that "
                        + "no ISO-8601 parser reads");

        for (String document : DOCUMENTS) {
            assertEquals("date-time", formatOf(propertiesOf(document).get("occurredAt")),
                    document + " changed the occurredAt format away from date-time");
        }

        for (String document : List.of(FLAGGED, CLEARED)) {
            assertEquals("date-time", formatOf(propertiesOf(document).get("assessedAt")),
                    document + " changed the assessedAt format away from date-time");
        }

        for (String document : DOCUMENTS) {
            Set<String> declared = new LinkedHashSet<>();
            for (Map.Entry<String, JsonNode> property : propertiesOf(document).properties()) {
                if ("date-time".equals(formatOf(property.getValue()))) {
                    declared.add(property.getKey());
                }
            }

            Set<String> expected = new LinkedHashSet<>(List.of("occurredAt"));
            if (FLAGGED.equals(document) || CLEARED.equals(document)) {
                expected.add("assessedAt");
            }
            assertEquals(expected, declared,
                    document + " changed which timestamps claim the ISO-8601 date-time format, and "
                            + "now declares " + declared);
        }
    }

    /**
     * Asserts {@code triggeredRules} declares no {@code maxItems} bound, and pins the three rule
     * identifiers its items enumerate.
     *
     * <p>The two assertions cover different things. The absent {@code maxItems} keyword leaves the
     * array length unbounded, so a flagged event naming a fourth rule fits the document a consumer
     * on version one already reads. The final assertion pins the value set, so a new identifier
     * reaches a reader of this class before it reaches a topic.</p>
     *
     * <p>Adding a fourth identifier takes two edits: the value joins
     * {@code properties.triggeredRules.items.enum} in {@code schemas/fraud-flagged-v1.json}, and it
     * joins {@link #TRIGGERED_RULE_VALUES} here. The compatibility evaluator reads a widened
     * enumeration as compatible, which the closing block of
     * {@code retypingOrNarrowingAnExistingPropertyIsBreaking} asserts on the decline codes.</p>
     *
     * <p>The three identifiers have no COBOL ancestor. The source performs no risk scoring, so the
     * rule names come from the fraud service this platform adds.</p>
     */
    @Test
    void triggeredRulesHasNoMaxItemsAndPinsItsThreeRuleIdentifiers() {
        JsonNode triggeredRules = propertiesOf(FLAGGED).get("triggeredRules");

        assertEquals("array", typeOf(triggeredRules),
                FLAGGED + " stopped carrying triggeredRules as an array");
        assertEquals(1, triggeredRules.get("minItems").intValue(),
                FLAGGED + " changed the triggeredRules lower bound away from one item, and a "
                        + "flagged event names at least one rule");
        assertTrue(triggeredRules.get("uniqueItems").asBoolean(),
                FLAGGED + " stopped requiring unique triggeredRules entries");
        assertFalse(triggeredRules.has("maxItems"),
                FLAGGED + " gave triggeredRules a maxItems bound, which turns a fourth rule "
                        + "identifier into a breaking change for every consumer on version one");

        List<String> declared = stringsOf(triggeredRules.get("items").get("enum"));
        assertEquals(TRIGGERED_RULE_VALUES, declared,
                FLAGGED + " changed the triggeredRules identifiers, and now enumerates " + declared
                        + ". Add a fourth identifier to TRIGGERED_RULE_VALUES in this class to "
                        + "record the change here as well");
    }

    /**
     * Asserts no transaction or fraud document names a card verification value, a card status or an
     * account status, and that no document at all declares a card-number checksum.
     *
     * <p>The source stores the card verification value in the clear at
     * {@code app/cpy/CVACT02Y.cpy:L7} and the target emits it nowhere. The posting path never opens
     * the card file, so it reads neither {@code CARD-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10} nor {@code ACCT-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT01Y.cpy:L6}, and {@code app/jcl/POSTTRAN.jcl} allocates no card dataset.
     * The source validates a card number as sixteen numeric digits and nothing more.</p>
     */
    @Test
    void noDocumentCarriesACardSecretAStatusFlagOrAChecksumRule() {
        for (String document : TRANSACTION_AND_FRAUD_DOCUMENTS) {
            String text = rawTextOf(document).toLowerCase(Locale.ROOT);

            for (String field : FORBIDDEN_SOURCE_FIELDS) {
                assertFalse(text.contains(field),
                        document + " names " + field + ", and no event on the authorization, "
                                + "posting or risk path carries a card secret or a status flag");
            }
        }

        for (String document : DOCUMENTS) {
            String text = rawTextOf(document).toLowerCase(Locale.ROOT);

            for (String check : FORBIDDEN_CARD_CHECKS) {
                assertFalse(text.contains(check),
                        document + " declares the card-number check " + check + ", which the "
                                + "source does not perform");
            }
        }
    }

    /**
     * Asserts each state-change document declares a status byte and no card secret, and that no
     * property of any document is a name the publish-side guard refuses.
     *
     * <p>The card service owns {@code CARD-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10}, so the card document declares a status the five transaction
     * and fraud documents must not. The account document names which mutation produced the event
     * instead, through its {@code changeKind} enumeration. {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7} reaches no document, and the card document names it only in
     * the annotation that records its absence.</p>
     */
    @Test
    void eachStateChangeDocumentDeclaresItsOwnDiscriminatorAndNoCardSecret() {
        JsonNode activeStatus = propertiesOf(CARD_STATE).get("activeStatus");

        assertNotNull(activeStatus,
                CARD_STATE + " dropped activeStatus, which the card service owns");
        assertEquals(1, activeStatus.get("minLength").intValue(),
                CARD_STATE + " changed the status width away from the one byte of PIC X(01)");
        assertEquals(1, activeStatus.get("maxLength").intValue(),
                CARD_STATE + " changed the status width away from the one byte of PIC X(01)");

        assertNotNull(propertiesOf(ACCOUNT_STATE).get("changeKind"),
                ACCOUNT_STATE + " dropped changeKind, which names the mutation that produced the "
                        + "event");

        for (String document : DOCUMENTS) {
            for (String declared : propertiesOf(document).propertyNames()) {
                assertFalse(SensitiveEventProperties.isForbidden(declared),
                        document + " declares the property " + declared + ", which the publish-side "
                                + "guard refuses, so no producer could publish this event");
            }
        }
    }

    /**
     * Asserts each document omits the properties a sibling document owns.
     *
     * <p>The daily feed carries 26 blanks in its posting timestamp field, and
     * {@code app/cbl/CBTRN02C.cbl:L437-L438} fills that field at posting time. The posted document
     * therefore carries a posting timestamp and the authorized document does not. Trailing filler
     * is dropped: {@code FILLER PIC X(20)} at {@code app/cpy/CVTRA05Y.cpy:L18} and
     * {@code FILLER PIC X(178)} at {@code app/cpy/CVACT01Y.cpy:L17} reach no target field.</p>
     */
    @Test
    void everyDocumentOmitsThePropertiesItsSiblingsOwn() {
        for (String document : DOCUMENTS) {
            for (String declared : propertiesOf(document).propertyNames()) {
                String name = declared.toLowerCase(Locale.ROOT);

                for (String fragment : FORBIDDEN_PROPERTY_FRAGMENTS.get(document)) {
                    assertFalse(name.contains(fragment),
                            document + " declared the property " + declared + ", which carries the "
                                    + "fragment " + fragment + " that belongs to another document "
                                    + "or to dropped filler");
                }
            }
        }
    }

    /**
     * Asserts the masked card number keeps twelve asterisks and four digits on the three documents
     * that carry it. The pattern rejects a full Primary Account Number (PAN), and neither fraud
     * document declares the property.
     *
     * <p>The authorization decision reads the full sixteen characters at
     * {@code app/cbl/CBTRN02C.cbl:L382-L383}. Masking happens at the serialization boundary
     * alone.</p>
     */
    @Test
    void maskedCardNumberRejectsAFullPrimaryAccountNumberAndIsAbsentFromBothFraudDocuments() {
        for (String document : MASKED_CARD_DOCUMENTS) {
            JsonNode masked = propertiesOf(document).get(MASKED_CARD_PROPERTY);

            assertNotNull(masked,
                    document + " dropped " + MASKED_CARD_PROPERTY + ", which every consumer of "
                            + "this event displays");
            assertEquals(MASKED_CARD_PATTERN, masked.get("pattern").asString(),
                    document + " changed the masking pattern away from twelve asterisks and four "
                            + "digits");
            assertEquals(16, masked.get("minLength").intValue(),
                    document + " changed the masked card minimum length away from 16");
            assertEquals(16, masked.get("maxLength").intValue(),
                    document + " changed the masked card maximum length away from 16");

            ObjectNode accepted = validPayloadFor(document);
            accepted.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
            assertValid(document, accepted,
                    MASKED_CARD_PROPERTY + " rejected the masked form " + MASKED_CARD_NUMBER);

            ObjectNode unmasked = validPayloadFor(document);
            unmasked.put(MASKED_CARD_PROPERTY, FULL_CARD_NUMBER);
            assertInvalid(document, unmasked,
                    MASKED_CARD_PROPERTY + " accepted a full sixteen-digit Primary Account Number "
                            + "(PAN), so an unmasked card number could reach the topic");
        }

        for (String document : CARD_FREE_DOCUMENTS) {
            assertFalse(propertiesOf(document).has(MASKED_CARD_PROPERTY),
                    document + " added " + MASKED_CARD_PROPERTY + ", and a risk assessment and an "
                            + "account mutation each carry no card number in any form");
        }
    }

    @Test
    void everyDocumentAcceptsItsVersionOneShapeAndRejectsTheLossOfAnyRequiredProperty() {
        for (String document : DOCUMENTS) {
            ObjectNode payload = validPayloadFor(document);
            assertValid(document, payload,
                    "the version-one shape of " + document + " stopped validating");

            for (String property : REQUIRED_SETS.get(document)) {
                ObjectNode reduced = payload.deepCopy();
                reduced.remove(property);
                assertInvalid(document, reduced,
                        document + " accepted a payload missing the required property " + property);
            }
        }
    }

    /**
     * Asserts a document accepts a value a later version adds, and refuses one added anywhere else.
     *
     * <p>This is the additive half of the guarantee. A consumer validating against version one keeps
     * working when a later version adds a value inside {@code extensions}, and the same consumer
     * refuses a top-level property no document declares, which is what keeps an undeclared field out
     * of a known event type.</p>
     */
    @Test
    void everyDocumentRejectsAPropertyItDoesNotDeclare() {
        for (String document : DOCUMENTS) {
            ObjectNode enriched = validPayloadFor(document);
            ObjectNode extensions = MAPPER.createObjectNode();
            extensions.put("settlementReference", "0000000000000001");
            enriched.set("extensions", extensions);

            assertValid(document, enriched,
                    document + " rejected a payload whose extensions object carries one added "
                            + "value, so the next field added to this event would break every "
                            + "consumer on version one");

            ObjectNode smuggled = validPayloadFor(document);
            smuggled.put(NEVER_DECLARED_PROBE_PROPERTY, "0000000000000001");
            assertInvalid(document, smuggled,
                    document + " accepted the undeclared top-level property "
                            + NEVER_DECLARED_PROBE_PROPERTY + ", so a producer could carry a field "
                            + "no schema describes inside a known event type");

            ObjectNode oversized = validPayloadFor(document);
            ObjectNode wide = MAPPER.createObjectNode();
            for (int member = 0; member <= EXTENSIONS_MAX_PROPERTIES; member++) {
                wide.put("member" + member, "value");
            }
            oversized.set("extensions", wide);
            assertInvalid(document, oversized,
                    document + " accepted an extensions object carrying more than "
                            + EXTENSIONS_MAX_PROPERTIES + " members, so additive room is unbounded");

            ObjectNode nested = validPayloadFor(document);
            ObjectNode holdingObject = MAPPER.createObjectNode();
            holdingObject.set("payload", MAPPER.createObjectNode().put("pan", "0500024453765740"));
            nested.set("extensions", holdingObject);
            assertInvalid(document, nested,
                    document + " accepted a nested object inside extensions, so a structure of any "
                            + "depth could travel there");
        }
    }

    /**
     * Asserts each event record serializes exactly the properties its document declares.
     *
     * <p>Every property set stays open, so a schema accepts a property it does not declare. A
     * property a record puts on the wire and a document never names would therefore validate, and a
     * consumer reading the document would never learn of it. This test compares the two sets.</p>
     */
    @Test
    void everyEventRecordSerializesExactlyThePropertiesItsDocumentDeclares() {
        for (Map.Entry<String, Object> sample : RECORDS_BY_DOCUMENT.entrySet()) {
            String document = sample.getKey();
            // extensions is the one declared property no record writes: it is the bounded room a
            // later version puts an added value in, and it is declared without being required.
            Set<String> declared = new LinkedHashSet<>(propertiesOf(document).propertyNames());
            declared.remove(EXTENSIONS_PROPERTY);
            Set<String> written = new LinkedHashSet<>(serializedTree(document).propertyNames());

            assertEquals(declared, written,
                    document + " and " + sample.getValue().getClass().getSimpleName()
                            + " disagree on the property set of the wire form, so a field is "
                            + "travelling undeclared or a declared field is missing");
        }
    }

    /**
     * Asserts each event record survives a serialize and deserialize round trip unchanged, and that
     * the serialized form validates against its document.
     *
     * <p>The round trip runs through the two serde classes, so the assertion covers the publish
     * path and the consume path rather than a mapper this test configured. A money value keeps its
     * two fractional digits, so an amount written as text reads back at the same scale.</p>
     */
    @Test
    void everyEventRecordSurvivesASerializeAndDeserializeRoundTrip() {
        for (Map.Entry<String, Object> sample : RECORDS_BY_DOCUMENT.entrySet()) {
            String document = sample.getKey();
            Object event = sample.getValue();
            String topic = topicFor(event);
            byte[] bytes = SERIALIZER.serialize(topic, event);

            assertNotNull(bytes,
                    document + " serialized to no bytes, so nothing would reach the topic");
            assertValid(document, MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8)),
                    "the wire form " + event.getClass().getSimpleName() + " writes stopped "
                            + "validating against " + document);

            Object read = deserializerFor(event.getClass()).deserialize(topic, bytes);
            assertEquals(event, read,
                    document + " lost a value in the round trip through the two serde classes, so "
                            + event.getClass().getSimpleName() + " and its wire form disagree");
        }
    }

    /**
     * Asserts the publish path refuses an event whose type no document covers, and that the consume
     * path refuses the same payload.
     *
     * <p>A type with no document would otherwise reach a topic unchecked.</p>
     */
    @Test
    void bothSerdeDirectionsRefuseAnEventTypeNoDocumentCovers() {
        ObjectNode unknown = validPayloadFor(POSTED);
        unknown.put("eventType", "TransactionSettled");
        byte[] bytes = MAPPER.writeValueAsString(unknown).getBytes(StandardCharsets.UTF_8);

        SerializationException publishFailure = assertThrows(SerializationException.class,
                () -> SERIALIZER.serialize(EventContracts.defaultTopicFor(POSTED_EVENT_TYPE),
                        unknown),
                "the publish path accepted an event type no document covers");
        assertTrue(publishFailure.getMessage().contains(TransactionPosted.class.getSimpleName()),
                "the publish failure stopped naming the records this module writes, so a caller "
                        + "cannot tell why the value was refused, and the message read: "
                        + publishFailure.getMessage());

        SerializationException consumeFailure = assertThrows(SerializationException.class,
                () -> TREE_DESERIALIZER.deserialize(
                        EventContracts.defaultTopicFor(POSTED_EVENT_TYPE), bytes),
                "the consume path accepted an event type no document covers");
        assertTrue(consumeFailure.getMessage().contains("TransactionSettled"),
                "the consume failure stopped naming the unknown event type, and the message read: "
                        + consumeFailure.getMessage());
    }

    /**
     * Asserts the consume path refuses a payload that breaks its document, one required property at
     * a time, and refuses a payload that nests the envelope.
     *
     * <p>The failure names the document, so a reader learns which contract the payload broke.</p>
     */
    @Test
    void theConsumeSideRefusesEveryPayloadThatBreaksItsDocument() {
        for (String document : DOCUMENTS) {
            for (String property : REQUIRED_SETS.get(document)) {
                ObjectNode reduced = validPayloadFor(document);
                reduced.remove(property);
                byte[] bytes =
                        MAPPER.writeValueAsString(reduced).getBytes(StandardCharsets.UTF_8);

                SerializationException failure = assertThrows(SerializationException.class,
                        () -> TREE_DESERIALIZER.deserialize(TOPIC, bytes),
                        document + " accepted a payload on the consume path with the required "
                                + "property " + property + " removed");
                String named = "eventType".equals(property) ? "eventType" : document;
                assertTrue(failure.getMessage().contains(named),
                        document + " stopped naming " + named + " in the consume failure for the "
                                + "removal of " + property + ", and the message read: "
                                + failure.getMessage());
            }

            ObjectNode nested = nestedEnvelopeForm(validPayloadFor(document));
            byte[] nestedBytes =
                    MAPPER.writeValueAsString(nested).getBytes(StandardCharsets.UTF_8);
            assertThrows(SerializationException.class,
                    () -> TREE_DESERIALIZER.deserialize(TOPIC, nestedBytes),
                    document + " accepted a payload that nests the five envelope properties under "
                            + "an envelope key");
        }
    }

    /**
     * Asserts a payload of each document passes the consume path, including the two state-change
     * documents whose records live in the account service and the card service.
     *
     * <p>Those two records cannot be built here, and their wire form can be. This test covers the
     * schema selection, the validation and the property guard for all seven types.</p>
     */
    @Test
    void everyDocumentPassesTheConsumeSideGuardIncludingBothStateChangeDocuments() {
        for (String document : DOCUMENTS) {
            ObjectNode payload = validPayloadFor(document);
            byte[] bytes = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            JsonNode read = TREE_DESERIALIZER.deserialize(TOPIC, bytes);

            assertNotNull(read, document + " read back as nothing on the consume path");
            assertEquals(payload, read,
                    document + " changed a value on the consume path");
        }

        for (String changeKind : changeKindsOf(ACCOUNT_STATE)) {
            ObjectNode payload = validPayloadFor(ACCOUNT_STATE);
            payload.put("changeKind", changeKind);
            byte[] bytes = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);

            assertNotNull(TREE_DESERIALIZER.deserialize(TOPIC, bytes),
                    ACCOUNT_STATE + " refused the change kind " + changeKind + ", which its enum "
                            + "declares");
        }

        ObjectNode unknownChange = validPayloadFor(ACCOUNT_STATE);
        unknownChange.put("changeKind", "ACCOUNT_CLOSED");
        assertInvalid(ACCOUNT_STATE, unknownChange,
                ACCOUNT_STATE + " accepted a change kind its enum does not declare");
    }

    /**
     * Asserts the publish-side guard refuses every forbidden property name and spares the one
     * approved card property.
     *
     * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} and
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} reach no event. Two independent
     * gates stop either from travelling: the closed property set of the document refuses a property
     * it does not declare, and the guard refuses the name wherever it appears. Both are asserted, so
     * neither can be the only thing standing between a secret and a topic.</p>
     */
    @Test
    void thePublishSideGuardRefusesEveryForbiddenPropertyNameAndSparesTheApprovedOne() {
        for (String forbidden : FORBIDDEN_PROPERTY_NAMES) {
            assertTrue(SensitiveEventProperties.isForbidden(forbidden),
                    "the guard stopped refusing the property name " + forbidden);

            ObjectNode carrying = validPayloadFor(POSTED);
            carrying.put(forbidden, "0123456789012345");
            byte[] bytes = MAPPER.writeValueAsString(carrying).getBytes(StandardCharsets.UTF_8);

            assertInvalid(POSTED, carrying,
                    POSTED + " opened its property set and accepted the undeclared property "
                            + forbidden);
            SerializationException failure = assertThrows(SerializationException.class,
                    () -> TREE_DESERIALIZER.deserialize(TOPIC, bytes),
                    "the consume path accepted a payload carrying the property " + forbidden);
            assertTrue(failure.getMessage().contains(forbidden),
                    "the failure for " + forbidden + " stopped naming the property, and the "
                            + "message read: " + failure.getMessage());
        }

        for (String allowed : ALLOWED_PROPERTY_NAMES) {
            assertFalse(SensitiveEventProperties.isForbidden(allowed),
                    "the guard began refusing the property name " + allowed + ", which a document "
                            + "declares or a later version may add");
        }
    }

    /**
     * Asserts the guard reads a sensitive name the same way whatever separators its spelling uses,
     * and reads a legitimate name the same way too.
     *
     * <p>The {@code extensions} object of every document admits a member name matching
     * {@code ^[a-zA-Z][a-zA-Z0-9_]{0,39}$}, so {@code card_number} and {@code CARD_NUMBER} satisfy
     * the document as readily as {@code cardNumber}. The last loop puts an underscore spelling
     * inside {@code extensions}, asserts the document accepts the member, and asserts the guard
     * refuses it. That pairing pins which of the two gates closes this door.</p>
     *
     * <p>{@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} and
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} are the two values a member
     * of this shape would carry.</p>
     */
    @Test
    void thePublishSideGuardReadsANameTheSameWayInEverySpelling() {
        for (String forbidden : FORBIDDEN_PROPERTY_NAMES) {
            for (String spelling : separatorSpellingsOf(forbidden)) {
                assertTrue(SensitiveEventProperties.isForbidden(spelling),
                        "the guard refuses the property name " + forbidden + " and admits the "
                                + "spelling " + spelling + ", so the same value travels under a "
                                + "different name");
            }
        }

        for (String allowed : ALLOWED_PROPERTY_NAMES) {
            for (String spelling : separatorSpellingsOf(allowed)) {
                assertFalse(SensitiveEventProperties.isForbidden(spelling),
                        "the guard began refusing the spelling " + spelling + " of the property "
                                + "name " + allowed + ", which a document declares or a later "
                                + "version may add");
            }
        }

        for (String document : DOCUMENTS) {
            for (String member : SEPARATOR_SPELLED_SENSITIVE_MEMBERS) {
                ObjectNode carrying = validPayloadFor(document);
                carrying.set(EXTENSIONS_PROPERTY,
                        MAPPER.createObjectNode().put(member, "4859452612877065"));

                assertValid(document, carrying,
                        document + " stopped admitting the extensions member name " + member
                                + ", so this assertion no longer reaches the guard");
                assertEquals(member,
                        SensitiveEventProperties.firstForbiddenProperty(carrying),
                        document + " carried the extensions member " + member + " past the "
                                + "publish-side guard, and the document admits that member name, "
                                + "so nothing stopped the value");
            }

            ObjectNode legitimate = validPayloadFor(document);
            legitimate.set(EXTENSIONS_PROPERTY,
                    MAPPER.createObjectNode().put("settlement_reference", "0000000000000001"));

            assertValid(document, legitimate,
                    document + " rejected an extensions member spelled with an underscore, which "
                            + "its member-name pattern admits");
            assertNull(SensitiveEventProperties.firstForbiddenProperty(legitimate),
                    document + " refused the extensions member settlement_reference, so the guard "
                            + "now blocks a name a later version may add");
        }
    }

    /**
     * Asserts the guard reaches a forbidden property nested inside an object and inside an array.
     *
     * <p>A guard reading the top level only would miss a secret one level down.</p>
     */
    @Test
    void thePublishSideGuardReachesAForbiddenPropertyInsideANestedStructure() {
        ObjectNode nestedObject = validPayloadFor(POSTED);
        nestedObject.putObject("settlementDetail").put("cvv", "123");
        byte[] objectBytes =
                MAPPER.writeValueAsString(nestedObject).getBytes(StandardCharsets.UTF_8);

        assertThrows(SerializationException.class,
                () -> TREE_DESERIALIZER.deserialize(TOPIC, objectBytes),
                "the guard stopped reaching a forbidden property one level down");

        ObjectNode nestedArray = validPayloadFor(POSTED);
        ArrayNode entries = nestedArray.putArray("settlementEntries");
        entries.addObject().put("amount", "1.00");
        entries.addObject().put("cardNumber", "4859452612877065");
        byte[] arrayBytes = MAPPER.writeValueAsString(nestedArray).getBytes(StandardCharsets.UTF_8);

        assertThrows(SerializationException.class,
                () -> TREE_DESERIALIZER.deserialize(TOPIC, arrayBytes),
                "the guard stopped reaching a forbidden property inside an array");
    }

    /**
     * Asserts each reject reason carries the account identity its source paragraph resolves, and
     * that the record refuses the other pairing.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L383-L387} assigns reason 0100 in the {@code INVALID KEY} limb
     * of the cross-reference read, before {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7} has been read, and the gate at
     * {@code app/cbl/CBTRN02C.cbl:L372} then stops the account lookup. The reject record written at
     * {@code app/cbl/CBTRN02C.cbl:L448-L449} carries the daily transaction record and the validation
     * trailer, and neither holds an account identifier. The other three reasons follow
     * {@code MOVE XREF-ACCT-ID TO FD-ACCT-ID} at {@code app/cbl/CBTRN02C.cbl:L394}, so an account
     * identifier is in hand.</p>
     */
    @Test
    void eachDeclineReasonCarriesTheAccountIdentityItsSourceParagraphResolves() {
        for (DeclineReason reason : DeclineReason.values()) {
            boolean resolved = reason.resolvesAccount();
            String document = resolved ? DECLINED : DECLINED_UNRESOLVED;
            TransactionDeclined event = resolved
                    ? TransactionDeclined.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, reason,
                            new java.math.BigDecimal(POSITIVE_AMOUNT), MASKED_CARD_NUMBER)
                    : TransactionDeclined.ofUnresolvedAccount(TRANSACTION_ID,
                            new java.math.BigDecimal(POSITIVE_AMOUNT), MASKED_CARD_NUMBER);

            if (resolved) {
                assertEquals(ACCOUNT_IDENTIFIER, event.accountId(),
                        "reason code " + reason.code() + " stopped carrying the account identifier "
                                + "its source paragraph resolves");
                assertEquals(ACCOUNT_IDENTIFIER, event.envelope().aggregateId(),
                        "reason code " + reason.code() + " lost the agreement between its account "
                                + "identifier and the Kafka message key");
                assertEquals(EventEnvelope.SCHEMA_VERSION, event.schemaVersion(),
                        "a resolved decline stopped travelling under version one");
            } else {
                assertNull(event.accountId(),
                        "reason code " + reason.code() + " carries an account identifier, and the "
                                + "cross-reference read at app/cbl/CBTRN02C.cbl:L383-L387 resolved "
                                + "none");
                assertEquals(TRANSACTION_ID, event.envelope().aggregateId(),
                        "the unresolved decline is keyed on its transaction identifier, which is "
                                + "the one deterministic value that names no cardholder");
                assertEquals(TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION,
                        event.schemaVersion(),
                        "the unresolved decline stopped travelling under its own contract version");
            }

            JsonNode wire = MAPPER.readTree(new String(
                    SERIALIZER.serialize(topicFor(event), event), StandardCharsets.UTF_8));
            assertValid(document, wire,
                    "the wire form of reason code " + reason.code() + " stopped validating");
            assertEquals(reason.code(), wire.get(DECLINE_CODE_PROPERTY).asString(),
                    "the wire form stopped carrying reason code " + reason.code());
            if (resolved) {
                assertEquals(ACCOUNT_IDENTIFIER, wire.get("aggregateId").asString(),
                        "the wire form of reason code " + reason.code() + " carries an aggregateId "
                                + "the record denies");
                assertEquals(ACCOUNT_IDENTIFIER, wire.get("accountId").asString(),
                        DECLINED + " declares accountId beside aggregateId and the two stopped "
                                + "holding one value on the wire");
            } else {
                assertEquals(TRANSACTION_ID, wire.get("aggregateId").asString(),
                        DECLINED_UNRESOLVED + " keys the event on its transaction identifier");
                assertNull(wire.get("accountId"),
                        DECLINED_UNRESOLVED + " declares no accountId, so none may reach the wire");
            }

            java.math.BigDecimal amount = new java.math.BigDecimal(POSITIVE_AMOUNT);
            if (resolved) {
                EventEnvelope unresolved = EventEnvelope.of(TransactionDeclined.EVENT_TYPE,
                        TRANSACTION_ID, TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION);
                assertThrows(IllegalArgumentException.class,
                        () -> new TransactionDeclined(unresolved.eventId(), unresolved.eventType(),
                                unresolved.schemaVersion(), unresolved.occurredAt(),
                                unresolved.aggregateId(), TRANSACTION_ID, null, reason,
                                reason.description(), amount, MASKED_CARD_NUMBER),
                        "reason code " + reason.code() + " reached the contract that carries no "
                                + "account identifier, and only the cross-reference miss may");
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> TransactionDeclined.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, reason,
                                amount, MASKED_CARD_NUMBER),
                        "reason code " + reason.code() + " accepted an account identifier its "
                                + "source paragraph denies, so a misattributed decline became "
                                + "constructable");
            }
        }

        assertEquals(1, java.util.Arrays.stream(DeclineReason.values())
                        .filter(reason -> !reason.resolvesAccount()).count(),
                "the count of reject reasons that resolve no account moved away from one, and only "
                        + "the cross-reference miss at app/cbl/CBTRN02C.cbl:L383-L387 resolves "
                        + "none");
        assertEquals("0100", DeclineReason.INVALID_CARD_NUMBER.code(),
                "the reason that resolves no account stopped being the cross-reference miss");
    }

    /**
     * Asserts the compatibility evaluator reports no change as compatible, and an added property as
     * compatible, for every document.
     *
     * <p>Adding a consumer needs no producer change, and adding a field needs no consumer change.
     * The second half is what these two cases pin.</p>
     */
    @Test
    void addingAPropertyIsCompatibleForEveryDocument() {
        for (String document : DOCUMENTS) {
            ObjectNode baseline = mutableCopy(document);

            assertEquals(List.of(), incompatibilities(baseline, mutableCopy(document)),
                    document + " reported an incompatibility against an identical copy of itself");

            ObjectNode extended = mutableCopy(document);
            ((ObjectNode) extended.get("properties")).putObject(NEVER_DECLARED_PROBE_PROPERTY)
                    .put("type", "string");
            assertEquals(List.of(), incompatibilities(baseline, extended),
                    document + " reported an added property as breaking, so the next field added "
                            + "to this event could not ship");

            ObjectNode annotated = mutableCopy(document);
            ((ObjectNode) annotated.get("properties").get("eventId"))
                    .put("description", "A different sentence.");
            assertEquals(List.of(), incompatibilities(baseline, annotated),
                    document + " reported a changed annotation as breaking, and an annotation "
                            + "asserts nothing about an instance");
        }
    }

    /**
     * Asserts the evaluator reports a removed property and a changed required set as breaking, for
     * every document.
     */
    @Test
    void removingAPropertyOrChangingTheRequiredSetIsBreakingForEveryDocument() {
        for (String document : DOCUMENTS) {
            ObjectNode baseline = mutableCopy(document);

            for (String property : REQUIRED_SETS.get(document)) {
                ObjectNode without = mutableCopy(document);
                ((ObjectNode) without.get("properties")).remove(property);
                ((ArrayNode) without.get("required")).remove(
                        requiredIndexOf(without, property));

                assertFalse(incompatibilities(baseline, without).isEmpty(),
                        document + " reported the loss of the property " + property + " as "
                                + "compatible, and a consumer already reading it would break");
            }

            ObjectNode widened = mutableCopy(document);
            ((ObjectNode) widened.get("properties")).putObject(NEVER_DECLARED_PROBE_PROPERTY)
                    .put("type", "string");
            ((ArrayNode) widened.get("required")).add(NEVER_DECLARED_PROBE_PROPERTY);
            assertFalse(incompatibilities(baseline, widened).isEmpty(),
                    document + " reported a new required property as compatible, and a producer on "
                            + "version one writes no such field");

            ObjectNode relaxed = mutableCopy(document);
            ((ArrayNode) relaxed.get("required")).remove(
                    requiredIndexOf(relaxed, "eventId"));
            assertFalse(incompatibilities(baseline, relaxed).isEmpty(),
                    document + " reported the loss of a required name as compatible, and a "
                            + "consumer that depends on the field arriving would break");
        }
    }

    /**
     * Asserts the evaluator reports a changed type, a changed constant, a narrowed pattern, a
     * narrowed length and a narrowed enumeration as breaking.
     */
    @Test
    void retypingOrNarrowingAnExistingPropertyIsBreaking() {
        ObjectNode baseline = mutableCopy(POSTED);

        ObjectNode retyped = mutableCopy(POSTED);
        ((ObjectNode) retyped.get("properties").get(BALANCE_PROPERTY)).put("type", "number");
        assertFalse(incompatibilities(baseline, retyped).isEmpty(),
                POSTED + " reported the balance turning into a JSON number as compatible");

        ObjectNode reconstanted = mutableCopy(POSTED);
        ((ObjectNode) reconstanted.get("properties").get("schemaVersion")).put("const", 2);
        assertFalse(incompatibilities(baseline, reconstanted).isEmpty(),
                POSTED + " reported a changed version constant as compatible");

        ObjectNode narrowedPattern = mutableCopy(POSTED);
        ((ObjectNode) narrowedPattern.get("properties").get(AMOUNT_PROPERTY))
                .put("pattern", "^\\d{1,9}\\.\\d{2}$");
        assertFalse(incompatibilities(baseline, narrowedPattern).isEmpty(),
                POSTED + " reported a pattern that drops the leading minus as compatible, and 50 "
                        + "of the 300 records in app/data/ASCII/dailytran.txt carry a negative "
                        + "amount");

        ObjectNode narrowedLength = mutableCopy(POSTED);
        ((ObjectNode) narrowedLength.get("properties").get(MASKED_CARD_PROPERTY))
                .put("maxLength", 15);
        assertFalse(incompatibilities(baseline, narrowedLength).isEmpty(),
                POSTED + " reported a shorter masked card number as compatible");

        ObjectNode raisedFloor = mutableCopy(POSTED);
        ((ObjectNode) raisedFloor.get("properties").get("transactionId")).put("minLength", 17);
        assertFalse(incompatibilities(baseline, raisedFloor).isEmpty(),
                POSTED + " reported a longer transaction identifier as compatible");

        ObjectNode declinedBaseline = mutableCopy(DECLINED);
        ObjectNode narrowedEnum = mutableCopy(DECLINED);
        ((ArrayNode) narrowedEnum.get("properties").get(DECLINE_CODE_PROPERTY).get("enum"))
                .remove(0);
        assertFalse(incompatibilities(declinedBaseline, narrowedEnum).isEmpty(),
                DECLINED + " reported the loss of a reject reason code as compatible");

        ObjectNode widenedEnum = mutableCopy(DECLINED);
        ((ArrayNode) widenedEnum.get("properties").get(DECLINE_CODE_PROPERTY).get("enum"))
                .add("0110");
        assertEquals(List.of(), incompatibilities(declinedBaseline, widenedEnum),
                DECLINED + " reported an added enumeration value as breaking, and a consumer on "
                        + "version one still reads every value it knew");
    }

    /**
     * Asserts the evaluator reports a newly required property and a nested envelope as breaking, for
     * every document.
     *
     * <p>Every document ships with {@code additionalProperties} false, so a field joins a payload
     * through a new {@code schemaVersion} and a new {@code -v<n>} document rather than through an
     * addition to this one. Inside one version the breaking changes are therefore a property that
     * becomes required, which fails every producer already writing the version, and a nested
     * envelope, which moves five properties a consumer reads before it looks at a payload.</p>
     */
    @Test
    void aNewlyRequiredPropertyOrANestedEnvelopeIsBreakingForEveryDocument() {
        for (String document : DOCUMENTS) {
            ObjectNode baseline = mutableCopy(document);

            assertFalse(baseline.get("additionalProperties").booleanValue(),
                    document + " opened its property set, and a closed set is what stops a field "
                            + "from travelling undeclared");

            ObjectNode required = mutableCopy(document);
            ((ObjectNode) required.get("properties")).putObject(NEVER_DECLARED_PROBE_PROPERTY)
                    .put("type", "string");
            ((ArrayNode) required.get("required")).add(NEVER_DECLARED_PROBE_PROPERTY);
            assertFalse(incompatibilities(baseline, required).isEmpty(),
                    document + " reported a newly required property as compatible");

            ObjectNode nested = mutableCopy(document);
            ObjectNode properties = (ObjectNode) nested.get("properties");
            ObjectNode envelope = properties.putObject("envelope");
            ObjectNode envelopeProperties = envelope.putObject("properties");
            for (String property : ENVELOPE_PROPERTIES) {
                envelopeProperties.set(property, properties.get(property));
                properties.remove(property);
                ((ArrayNode) nested.get("required")).remove(
                        requiredIndexOf(nested, property));
            }
            assertFalse(incompatibilities(baseline, nested).isEmpty(),
                    document + " reported the envelope moving under an envelope key as "
                            + "compatible");
        }
    }

    /**
     * Asserts every property the five Java records put on the wire is declared and required by the
     * document that governs the event.
     *
     * <p>{@code accountId} once travelled on TransactionDeclined and TransactionPosted while
     * neither document described it. This test closes that gap from the record side: the bytes
     * {@link JsonSchemaValidatingSerializer} writes must carry exactly the declared set.</p>
     *
     * <p>The two state-change documents are covered by the publish-path test of the service that
     * owns each record, {@code AccountStateChangedPublishPathTest} and
     * {@code CardUpdatedPublishPathTest}, because neither record is on this module's classpath.</p>
     */
    @Test
    void everyPropertyTheRecordsPutOnTheWireIsDeclaredAndRequiredByItsDocument() {
        for (String document : RECORD_BEARING_DOCUMENTS) {
            JsonNode event = wireFormOf(document);
            Set<String> serialized = namesOf(event);

            Set<String> declared = new LinkedHashSet<>(namesOf(propertiesOf(document)));
            declared.remove(EXTENSIONS_PROPERTY);
            assertEquals(declared, serialized,
                    document + " and its Java record disagree on the property set, so a field "
                            + "travels undeclared or a declared field never travels");
            assertEquals(REQUIRED_SETS.get(document), serialized,
                    document + " requires a set of names the wire form does not match");
            assertValid(document, event,
                    "the serialized wire form of " + document + " stopped validating");
        }
    }

    /**
     * Asserts the two account identifiers hold one value on every event carrying both, and that a
     * record refuses a differing pair.
     *
     * <p>{@code aggregateId} is the Kafka message key. Draft 2020-12 declares no keyword comparing
     * one property against another, so the record constructor carries the equality check and this
     * test pins it. The identifier is {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}.</p>
     */
    @Test
    void theTwoAccountIdentifiersHoldOneValueAndARecordRefusesADifferingPair() {
        for (String document : List.of(DECLINED, POSTED, CLEARED)) {
            JsonNode event = wireFormOf(document);

            assertTrue(event.has(PAYLOAD_ACCOUNT_PROPERTY),
                    document + " stopped carrying " + PAYLOAD_ACCOUNT_PROPERTY + ", which its "
                            + "record declares and its consumers read");
            assertEquals(event.get(ENVELOPE_ACCOUNT_PROPERTY).asString(),
                    event.get(PAYLOAD_ACCOUNT_PROPERTY).asString(),
                    document + " put two different account identifiers on the wire, so the Kafka "
                            + "message key and the payload disagree");
        }

        EventEnvelope envelope = new EventEnvelope(UUID.fromString(EVENT_ID),
                TransactionDeclined.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION,
                Instant.parse(OCCURRED_AT), ACCOUNT_IDENTIFIER);

        assertThrows(IllegalArgumentException.class,
                () -> new TransactionDeclined(envelope.eventId(), envelope.eventType(),
                        envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                        TRANSACTION_ID, OTHER_ACCOUNT_IDENTIFIER,
                        DeclineReason.OVER_CREDIT_LIMIT,
                        DeclineReason.OVER_CREDIT_LIMIT.description(),
                        new BigDecimal(POSITIVE_AMOUNT), MASKED_CARD_NUMBER),
                "TransactionDeclined accepted an accountId differing from its aggregateId");

        assertThrows(IllegalArgumentException.class,
                () -> new TransactionPosted(UUID.fromString(EVENT_ID), TransactionPosted.EVENT_TYPE,
                        EventEnvelope.SCHEMA_VERSION, Instant.parse(OCCURRED_AT),
                        ACCOUNT_IDENTIFIER, TRANSACTION_ID, OTHER_ACCOUNT_IDENTIFIER,
                        new BigDecimal(POSITIVE_BALANCE), POSTED_AT_VALUE,
                        new BigDecimal(POSITIVE_AMOUNT), MASKED_CARD_NUMBER),
                "TransactionPosted accepted an accountId differing from its aggregateId");
    }

    /**
     * The exact required set of each document, written as five envelope properties followed by the
     * payload properties that document declares.
     *
     * @return each classpath resource mapped to the names its {@code required} array holds
     */
    private static Map<String, Set<String>> requiredSets() {
        Map<String, Set<String>> sets = new LinkedHashMap<>();
        sets.put(AUTHORIZED, requiredSet("transactionId", "accountId", "transactionTypeCode",
                "merchantCategoryCode", "source", "description", "amount", "merchantId",
                "merchantName", "merchantCity", "merchantZip", "maskedCardNumber", "authorizedAt",
                "accountId", "currency"));
        sets.put(DECLINED, requiredSet("transactionId", "accountId", "declineReasonCode",
                "declineReasonDescription", "amount", "maskedCardNumber"));
        sets.put(POSTED, requiredSet("transactionId", "accountId", "newBalance", "postedAt",
                "amount", "maskedCardNumber"));
        sets.put(FLAGGED, requiredSet("transactionId", "riskScore", "triggeredRules", "assessedAt",
                "accountId"));
        sets.put(CLEARED, requiredSet("transactionId", "accountId", "assessedAt"));
        sets.put(ACCOUNT_STATE, requiredSet("accountId", "creditLimit", "currentCycleCredit",
                "currentCycleDebit", "expirationDate", "changeKind"));
        sets.put(CARD_STATE, requiredSet("accountId", "maskedCardNumber", "embossedName",
                "expirationDate", "activeStatus"));
        return Map.copyOf(sets);
    }

    /**
     * Prefixes the five envelope properties to the payload properties of one document.
     *
     * @param payloadProperties the payload property names, in document order
     * @return the envelope names followed by the payload names
     */
    private static Set<String> requiredSet(String... payloadProperties) {
        Set<String> names = new LinkedHashSet<>(ENVELOPE_PROPERTIES);
        names.addAll(List.of(payloadProperties));
        return names;
    }

    /**
     * Each reject reason code paired with the text the source writes beside it, at
     * {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@code :L397-L399}, {@code :L410-L412} and
     * {@code :L417-L419}. Reproduced character for character.
     *
     * @return each code mapped to its text, in the order the source assigns them
     */
    private static Map<String, String> declinePairs() {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("0100", "INVALID CARD NUMBER FOUND");
        pairs.put("0101", "ACCOUNT RECORD NOT FOUND");
        pairs.put("0102", "OVERLIMIT TRANSACTION");
        pairs.put("0103", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        return pairs;
    }

    /**
     * Opens a document from the classpath, or fails naming the resource path.
     *
     * @param document the classpath path of the document
     * @return an open stream over the document
     */
    private static InputStream openResource(String document) {
        InputStream stream = SchemaBackwardCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(document);

        if (stream == null) {
            return fail("classpath resource " + document + " is missing from "
                    + "libs/event-contracts/src/main/resources, so the version-one baseline of "
                    + "that event cannot be read");
        }
        return stream;
    }

    /**
     * Reads one document as a JSON tree.
     *
     * @param document the classpath path of the document
     * @return the parsed document
     */
    private static JsonNode readDocument(String document) {
        try (InputStream stream = openResource(document)) {
            return MAPPER.readTree(stream);
        } catch (IOException failure) {
            return fail("classpath resource " + document + " could not be read: "
                    + failure.getMessage());
        }
    }

    /**
     * Reads the {@code properties} block of one document.
     *
     * @param document the classpath path of the document
     * @return the property definitions, in document order
     */
    private static JsonNode propertiesOf(String document) {
        JsonNode properties = readDocument(document).get("properties");

        if (properties == null) {
            return fail(document + " stopped declaring a properties block");
        }
        return properties;
    }

    /**
     * Reads one document as text, for the keyword and field-name scans.
     *
     * @param document the classpath path of the document
     * @return the document text
     */
    private static String rawTextOf(String document) {
        try (InputStream stream = openResource(document)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return fail("classpath resource " + document + " could not be read as text: "
                    + failure.getMessage());
        }
    }

    /**
     * Compiles one document into a validating schema.
     *
     * @param document the classpath path of the document
     * @return the compiled schema
     */
    private static Schema schemaFor(String document) {
        try (InputStream stream = openResource(document)) {
            return REGISTRY.getSchema(stream);
        } catch (IOException failure) {
            return fail("classpath resource " + document + " could not be compiled: "
                    + failure.getMessage());
        }
    }

    /**
     * Validates a payload as text against one document.
     *
     * @param document the classpath path of the document
     * @param payload  the payload to validate
     * @return one error per failure, empty when the payload validates
     */
    private static List<Error> validate(String document, JsonNode payload) {
        return schemaFor(document).validate(MAPPER.writeValueAsString(payload), InputFormat.JSON);
    }

    /**
     * Asserts a payload validates, and names every reported error when it does not.
     *
     * @param document the classpath path of the document
     * @param payload  the payload to validate
     * @param message  what a reader should do when this assertion fails
     */
    private static void assertValid(String document, JsonNode payload, String message) {
        List<Error> errors = validate(document, payload);

        assertTrue(errors.isEmpty(), message + " (" + document + " reported: "
                + messagesOf(errors) + ")");
    }

    /**
     * Asserts a payload fails validation.
     *
     * @param document the classpath path of the document
     * @param payload  the payload to validate
     * @param message  what a reader should do when this assertion fails
     */
    private static void assertInvalid(String document, JsonNode payload, String message) {
        assertFalse(validate(document, payload).isEmpty(), message + " (" + document + ")");
    }

    /**
     * Joins the reported validation errors into one readable line.
     *
     * @param errors the reported errors
     * @return the messages joined by a semicolon
     */
    private static String messagesOf(List<Error> errors) {
        List<String> messages = new ArrayList<>();

        for (Error error : errors) {
            messages.add(error.getMessage());
        }
        return String.join("; ", messages);
    }

    /**
     * Projects a property schema down to the keywords that assert something about an instance.
     * Annotation keywords are skipped.
     *
     * @param propertySchema the property schema
     * @return each assertion keyword mapped to its serialized value
     */
    private static Map<String, String> assertionKeywords(JsonNode propertySchema) {
        Map<String, String> keywords = new LinkedHashMap<>();

        for (Map.Entry<String, JsonNode> keyword : propertySchema.properties()) {
            if (!ANNOTATION_KEYWORDS.contains(keyword.getKey())) {
                keywords.put(keyword.getKey(), keyword.getValue().toString());
            }
        }
        return keywords;
    }

    /**
     * Reads the {@code type} keyword of a property schema.
     *
     * @param propertySchema the property schema
     * @return the type name, or {@code null} when the property declares none
     */
    private static String typeOf(JsonNode propertySchema) {
        JsonNode type = propertySchema.get("type");

        return type == null ? null : type.asString();
    }

    /**
     * Reads the {@code format} keyword of a property schema.
     *
     * @param propertySchema the property schema
     * @return the format name, or {@code null} when the property declares none
     */
    private static String formatOf(JsonNode propertySchema) {
        JsonNode format = propertySchema.get("format");

        return format == null ? null : format.asString();
    }

    /**
     * Reads a JSON array of strings into a list, preserving order.
     *
     * @param array the array node to read
     * @return the string values, in array order
     */
    private static List<String> stringsOf(JsonNode array) {
        if (array == null || !array.isArray()) {
            return fail("a keyword expected to hold an array of strings held " + array);
        }

        List<String> values = new ArrayList<>();
        for (JsonNode entry : array) {
            values.add(entry.asString());
        }
        return values;
    }

    /**
     * The separator spellings of one camel-case property name.
     *
     * <p>Each spelling names the same value a producer could write. The list holds the camel-case
     * name itself, the underscore spelling, the upper-case underscore spelling, and the hyphen
     * spelling. A name of three letters or fewer gains a character-separated spelling, which is how
     * {@code pan} reaches {@code p_a_n}.</p>
     *
     * @param camelCaseName the property name as a document or a record spells it
     * @return the spellings the guard must read alike, the given name first
     */
    private static List<String> separatorSpellingsOf(String camelCaseName) {
        StringBuilder underscored = new StringBuilder();
        for (int index = 0; index < camelCaseName.length(); index++) {
            char letter = camelCaseName.charAt(index);
            if (Character.isUpperCase(letter) && index > 0) {
                underscored.append('_');
            }
            underscored.append(Character.toLowerCase(letter));
        }

        String snake = underscored.toString();
        List<String> spellings = new ArrayList<>(List.of(camelCaseName, snake,
                snake.toUpperCase(Locale.ROOT), snake.replace('_', '-')));

        if (camelCaseName.length() <= 3) {
            spellings.add(String.join("_", camelCaseName.split("")));
        }
        return spellings;
    }

    /**
     * Serializes the event record of one document through the production serializer and reads the
     * bytes back as a tree.
     *
     * <p>The serializer validates the bytes against the document before it returns them, so a
     * record and its document that disagree on a required property fail here first.</p>
     *
     * @param document the classpath path of the document
     * @return the wire form of that event
     */
    private static JsonNode wireFormOf(String document) {
        Object event = eventFor(document);

        try (JsonSchemaValidatingSerializer<Object> serializer =
                new JsonSchemaValidatingSerializer<>()) {
            byte[] bytes = serializer.serialize(topicFor(event), event);

            return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /**
     * Builds one valid event record per document, through the factory a producer calls.
     *
     * @param document the classpath path of the document
     * @return the record the document governs
     */
    private static Object eventFor(String document) {
        return switch (document) {
            case AUTHORIZED -> TransactionAuthorized.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, "01",
                    "0001", "POS TERM", "Purchase at Abshire-Lowe", new BigDecimal(POSITIVE_AMOUNT),
                    "800000000", "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                    AUTHORIZED_AT_VALUE);
            case DECLINED -> TransactionDeclined.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                    DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal(POSITIVE_AMOUNT),
                    MASKED_CARD_NUMBER);
            case POSTED -> TransactionPosted.forAccount(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                    new BigDecimal(POSITIVE_BALANCE), POSTED_AT_VALUE,
                    new BigDecimal(POSITIVE_AMOUNT), MASKED_CARD_NUMBER);
            case FLAGGED -> FraudFlagged.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, RISK_SCORE,
                    List.of(TRIGGERED_RULE_VALUES.get(0), TRIGGERED_RULE_VALUES.get(1)),
                    Instant.parse(ASSESSED_AT));
            case CLEARED -> FraudCleared.of(TRANSACTION_ID, ACCOUNT_IDENTIFIER,
                    Instant.parse(ASSESSED_AT));
            default -> fail("no event record is defined for the document " + document);
        };
    }

    /**
     * Reads the property names of one JSON object, preserving document order.
     *
     * @param object the object node to read
     * @return the property names
     */
    private static Set<String> namesOf(JsonNode object) {
        Set<String> names = new LinkedHashSet<>();

        for (String name : object.propertyNames()) {
            names.add(name);
        }
        return names;
    }

    /**
     * Builds the five envelope properties of a payload for one event type.
     *
     * @param eventType the routing discriminator the payload carries
     * @return a payload holding the envelope and nothing more
     */
    private static ObjectNode envelopeFor(String eventType) {
        ObjectNode payload = MAPPER.createObjectNode();

        payload.put("eventId", EVENT_ID);
        payload.put("eventType", eventType);
        payload.put("schemaVersion", 1);
        payload.put("occurredAt", OCCURRED_AT);
        payload.put("aggregateId", ACCOUNT_IDENTIFIER);
        return payload;
    }

    /**
     * Builds a payload carrying every required property of one document and nothing more.
     *
     * @param document the classpath path of the document
     * @return a payload that validates against that document
     */
    private static ObjectNode validPayloadFor(String document) {
        ObjectNode payload = envelopeFor(TITLES.get(document));
        if (!STATE_CHANGE_DOCUMENTS.contains(document)) {
            payload.put("transactionId", TRANSACTION_ID);
        }
        payload.put("accountId", ACCOUNT_IDENTIFIER);

        switch (document) {
            case AUTHORIZED -> {
                payload.put("transactionId", TRANSACTION_ID);
                payload.put("transactionTypeCode", "01");
                payload.put("merchantCategoryCode", "0001");
                payload.put("source", "POS TERM");
                payload.put("description", "Purchase at Abshire-Lowe");
                payload.put(AMOUNT_PROPERTY, POSITIVE_AMOUNT);
                payload.put("merchantId", "800000000");
                payload.put("merchantName", "Abshire-Lowe");
                payload.put("merchantCity", "North Enoshaven");
                payload.put("merchantZip", "72112");
                payload.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
                payload.put("authorizedAt", AUTHORIZED_AT_VALUE);
                payload.put("currency", "USD");
            }
            case DECLINED -> {
                payload.put(PAYLOAD_ACCOUNT_PROPERTY, ACCOUNT_IDENTIFIER);
                payload.put(DECLINE_CODE_PROPERTY, "0102");
                payload.put(DECLINE_TEXT_PROPERTY, "OVERLIMIT TRANSACTION");
                payload.put(AMOUNT_PROPERTY, POSITIVE_AMOUNT);
                payload.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
            }
            case POSTED -> {
                payload.put(PAYLOAD_ACCOUNT_PROPERTY, ACCOUNT_IDENTIFIER);
                payload.put(BALANCE_PROPERTY, POSITIVE_BALANCE);
                payload.put("postedAt", POSTED_AT_VALUE);
                payload.put(AMOUNT_PROPERTY, POSITIVE_AMOUNT);
                payload.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
            }
            case FLAGGED -> {
                payload.put("transactionId", TRANSACTION_ID);
                payload.put("riskScore", 82);
                ArrayNode rules = payload.putArray("triggeredRules");
                rules.add(TRIGGERED_RULE_VALUES.get(0));
                rules.add(TRIGGERED_RULE_VALUES.get(1));
                payload.put("assessedAt", ASSESSED_AT);
            }
            case CLEARED -> payload.put("assessedAt", ASSESSED_AT);
            case ACCOUNT_STATE -> {
                payload.put("creditLimit", POSITIVE_BALANCE);
                payload.put("currentCycleCredit", ZERO_BALANCE);
                payload.put("currentCycleDebit", ZERO_BALANCE);
                payload.put("expirationDate", EXPIRATION_DATE);
                payload.put("changeKind", ACCOUNT_UPDATED);
            }
            case CARD_STATE -> {
                payload.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
                payload.put("embossedName", EMBOSSED_NAME);
                payload.put("expirationDate", EXPIRATION_DATE);
                payload.put("activeStatus", ACTIVE_STATUS);
            }
            default -> fail("no payload is defined for the document " + document);
        }
        return payload;
    }

    /**
     * One sample record per document whose record this module declares.
     *
     * <p>{@code AccountStateChanged} and {@code CardUpdated} are absent, because the account
     * service and the card service declare them.
     *
     * @return each classpath resource mapped to a record instance that satisfies it
     */
    private static Map<String, Object> recordsByDocument() {
        Map<String, Object> records = new LinkedHashMap<>();
        records.put(AUTHORIZED, TransactionAuthorized.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, "01",
                "0001", "POS TERM", "Purchase at Abshire-Lowe", new BigDecimal(POSITIVE_AMOUNT),
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                AUTHORIZED_AT_VALUE));
        records.put(DECLINED, TransactionDeclined.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal(POSITIVE_AMOUNT),
                MASKED_CARD_NUMBER));
        records.put(POSTED, TransactionPosted.forAccount(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                new BigDecimal(POSITIVE_BALANCE), POSTED_AT_VALUE, new BigDecimal(POSITIVE_AMOUNT),
                MASKED_CARD_NUMBER));
        records.put(FLAGGED, FraudFlagged.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, 82,
                List.of(TRIGGERED_RULE_VALUES.get(0), TRIGGERED_RULE_VALUES.get(1)),
                Instant.parse(ASSESSED_AT)));
        records.put(CLEARED, FraudCleared.of(TRANSACTION_ID, ACCOUNT_IDENTIFIER,
                Instant.parse(ASSESSED_AT)));
        return Map.copyOf(records);
    }

    /**
     * The topic the shared registry binds one event record to.
     *
     * <p>{@code EventContracts} refuses a publish to any other topic, so every serialize below asks
     * the registry rather than naming a topic of its own.
     *
     * @param event the event record about to be written
     * @return the topic that event travels on
     */
    private static String topicFor(Object event) {
        return EventContracts.defaultTopicFor(event.getClass().getSimpleName());
    }

    /**
     * The wire form one document's record writes, read back as a tree.
     *
     * @param document the classpath path of the document
     * @return the serialized event as a JSON tree
     */
    private static JsonNode serializedTree(String document) {
        Object record = RECORDS_BY_DOCUMENT.get(document);
        byte[] bytes = SERIALIZER.serialize(topicFor(record), record);

        if (bytes == null) {
            return fail("the record of " + document + " serialized to no bytes");
        }
        return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * A consume-path deserializer bound to one event type.
     *
     * @param eventClass the record the payload binds to
     * @return a deserializer that validates before it binds
     */
    private static JsonSchemaValidatingDeserializer<?> deserializerFor(Class<?> eventClass) {
        return new JsonSchemaValidatingDeserializer<>(eventClass);
    }

    /**
     * The change-kind values the account document enumerates. The card document enumerates none,
     * because a card event follows one mutation only.
     *
     * @param document the classpath path of the document
     * @return each value of the {@code changeKind} enumeration, in document order
     */
    private static List<String> changeKindsOf(String document) {
        return ACCOUNT_STATE.equals(document)
                ? List.of(ACCOUNT_UPDATED, BILLING_CYCLE_CLOSED)
                : List.of();
    }

    /**
     * The same payload with its five envelope properties moved under an {@code envelope} key.
     *
     * @param flat a payload in the wire form, with envelope and payload properties at one level
     * @return the nested form, which every document rejects
     */
    private static ObjectNode nestedEnvelopeForm(ObjectNode flat) {
        ObjectNode nested = MAPPER.createObjectNode();
        ObjectNode envelope = MAPPER.createObjectNode();

        for (Map.Entry<String, JsonNode> property : flat.properties()) {
            if (ENVELOPE_PROPERTIES.contains(property.getKey())) {
                envelope.set(property.getKey(), property.getValue());
            } else {
                nested.set(property.getKey(), property.getValue());
            }
        }
        nested.set("envelope", envelope);
        return nested;
    }

    /**
     * A mutable deep copy of one document, for the compatibility mutations.
     *
     * @param document the classpath path of the document
     * @return a copy no other assertion shares
     */
    private static ObjectNode mutableCopy(String document) {
        JsonNode tree = readDocument(document);

        if (!tree.isObject()) {
            return fail(document + " stopped reading as a JSON object");
        }
        return ((ObjectNode) tree).deepCopy();
    }

    /**
     * The index of one name inside a document's {@code required} array.
     *
     * @param document the document copy to search
     * @param property the required name to find
     * @return the index of that name
     */
    private static int requiredIndexOf(ObjectNode document, String property) {
        JsonNode required = document.get("required");

        for (int index = 0; index < required.size(); index++) {
            if (property.equals(required.get(index).asString())) {
                return index;
            }
        }
        return fail("the required array does not name " + property);
    }

    /**
     * Every way a candidate document would break a consumer that validates against a baseline
     * document.
     *
     * <p>Evolution is additive only. A property added to {@code properties} is compatible, and so is
     * a changed annotation and an added enumeration value. Nine changes are breaking: a declared
     * property removed, a name added to {@code required}, a name removed from {@code required}, a
     * changed {@code type}, a changed {@code const}, a changed {@code pattern}, a raised
     * {@code minLength} or {@code minimum}, a lowered {@code maxLength} or {@code maximum}, an
     * enumeration value removed, and {@code additionalProperties} closing.</p>
     *
     * <p>Every check runs in memory against two trees. No registry service is contacted.</p>
     *
     * @param baseline  the document a consumer already validates against
     * @param candidate the document a later change would ship
     * @return one entry per breaking change, empty when the candidate is compatible
     */
    private static List<String> incompatibilities(JsonNode baseline, JsonNode candidate) {
        List<String> breaks = new ArrayList<>();

        if (baseline.get("additionalProperties").asBoolean()
                && !candidate.get("additionalProperties").asBoolean()) {
            breaks.add("additionalProperties closed");
        }

        Set<String> baselineRequired = new LinkedHashSet<>(stringsOf(baseline.get("required")));
        Set<String> candidateRequired = new LinkedHashSet<>(stringsOf(candidate.get("required")));
        for (String name : baselineRequired) {
            if (!candidateRequired.contains(name)) {
                breaks.add("required name removed: " + name);
            }
        }
        for (String name : candidateRequired) {
            if (!baselineRequired.contains(name)) {
                breaks.add("required name added: " + name);
            }
        }

        JsonNode baselineProperties = baseline.get("properties");
        JsonNode candidateProperties = candidate.get("properties");
        for (Map.Entry<String, JsonNode> property : baselineProperties.properties()) {
            String name = property.getKey();
            JsonNode candidateSchema = candidateProperties.get(name);

            if (candidateSchema == null) {
                breaks.add("declared property removed: " + name);
                continue;
            }
            breaks.addAll(propertyIncompatibilities(name, property.getValue(), candidateSchema));
        }
        return breaks;
    }

    /**
     * Every way one property schema would break a consumer reading the baseline property schema.
     *
     * @param name      the property name, for the failure text
     * @param baseline  the property schema a consumer already validates against
     * @param candidate the property schema a later change would ship
     * @return one entry per breaking change to that property
     */
    private static List<String> propertyIncompatibilities(String name, JsonNode baseline,
            JsonNode candidate) {
        List<String> breaks = new ArrayList<>();

        for (String keyword : List.of("type", "const", "pattern", "format")) {
            JsonNode before = baseline.get(keyword);
            JsonNode after = candidate.get(keyword);

            if (before != null && (after == null || !before.equals(after))) {
                breaks.add(name + " changed " + keyword);
            }
        }

        for (String floor : List.of("minLength", "minItems", "minimum")) {
            JsonNode before = baseline.get(floor);
            JsonNode after = candidate.get(floor);

            if (before != null && after != null && after.intValue() > before.intValue()) {
                breaks.add(name + " raised " + floor);
            }
            if (before == null && after != null) {
                breaks.add(name + " gained " + floor);
            }
        }

        for (String ceiling : List.of("maxLength", "maxItems", "maximum")) {
            JsonNode before = baseline.get(ceiling);
            JsonNode after = candidate.get(ceiling);

            if (before != null && after != null && after.intValue() < before.intValue()) {
                breaks.add(name + " lowered " + ceiling);
            }
            if (before == null && after != null) {
                breaks.add(name + " gained " + ceiling);
            }
        }

        JsonNode baselineEnum = baseline.get("enum");
        if (baselineEnum != null) {
            JsonNode candidateEnum = candidate.get("enum");

            if (candidateEnum == null) {
                breaks.add(name + " dropped its enumeration");
            } else {
                Set<String> after = new LinkedHashSet<>(stringsOf(candidateEnum));
                for (String value : stringsOf(baselineEnum)) {
                    if (!after.contains(value)) {
                        breaks.add(name + " removed the enumeration value " + value);
                    }
                }
            }
        }

        JsonNode baselineItems = baseline.get("items");
        if (baselineItems != null && candidate.get("items") != null) {
            breaks.addAll(propertyIncompatibilities(name + "/items", baselineItems,
                    candidate.get("items")));
        }
        return breaks;
    }

    /**
     * Asserts the version-two declined document loads from the classpath and compiles offline, as
     * the five version-one documents do.
     */
    @Test
    void theDeclinedDocumentAtVersionTwoLoadsFromTheClasspathAndCompilesOffline() {
        assertNotNull(schemaFor(DECLINED_UNRESOLVED),
                DECLINED_UNRESOLVED + " must load from the classpath and compile offline");
        assertEquals(2, readDocument(DECLINED_UNRESOLVED).path("properties")
                        .path("schemaVersion").path("const").asInt(0),
                DECLINED_UNRESOLVED + " pins schemaVersion to 2, matching the -v2 suffix of its"
                        + " name");
        assertEquals("TransactionDeclined", readDocument(DECLINED_UNRESOLVED).path("title")
                        .stringValue(""),
                "both declined documents title themselves with the record they govern, because a"
                        + " new version of a contract is not a new event type");
    }

    /**
     * Asserts version one of the declined document still requires its account identifier.
     *
     * <p>This is the backward-compatibility half of the reason-0100 change. Relaxing version one so
     * that the unresolved decline fitted inside it would have broken every consumer already reading
     * the topic, which is exactly what this class exists to prevent. The unresolved decline took a
     * new version instead, and this test fails if anyone later relaxes version one after all.
     */
    @Test
    void versionOneOfTheDeclinedDocumentStillRequiresItsAccountIdentifier() {
        Set<String> required = new LinkedHashSet<>();
        readDocument(DECLINED).path("required").forEach(name -> required.add(name.stringValue()));

        assertTrue(required.contains("accountId"),
                () -> DECLINED + " must keep accountId in its required set. It holds " + required
                        + ". A consumer reading this version relies on the field being present.");
        assertEquals(ACCOUNT_IDENTIFIER_PATTERN, propertiesOf(DECLINED).path("accountId")
                        .path("pattern").stringValue(""),
                DECLINED + " must keep accountId pinned to the eleven digits of XREF-ACCT-ID"
                        + " PIC 9(11) at app/cpy/CVACT03Y.cpy:L7");

        ObjectNode resolved = validPayloadFor(DECLINED);
        assertTrue(validate(DECLINED, resolved).isEmpty(),
                () -> "a resolved decline must still validate against version one: "
                        + messagesOf(validate(DECLINED, resolved)));
    }

    /**
     * Asserts version two declares no account identifier and refuses one that arrives anyway.
     *
     * <p>The property is absent from the declared set, and the set is closed, so a producer holding
     * an identifier a caller supplied cannot publish it here even by accident.
     */
    @Test
    void versionTwoOfTheDeclinedDocumentDeclaresNoAccountIdentifierAndRefusesOne() {
        assertTrue(propertiesOf(DECLINED_UNRESOLVED).path("accountId").isMissingNode(),
                DECLINED_UNRESOLVED + " must declare no accountId property, because reject reason"
                        + " 0100 fires before app/cbl/CBTRN02C.cbl:L383 has resolved one");
        assertFalse(readDocument(DECLINED_UNRESOLVED).path("additionalProperties").booleanValue(true),
                DECLINED_UNRESOLVED + " must close its property set, so an undeclared accountId is"
                        + " refused rather than carried");

        ObjectNode claimed = unresolvedDecline();
        claimed.put("accountId", ACCOUNT_IDENTIFIER);
        assertFalse(validate(DECLINED_UNRESOLVED, claimed).isEmpty(),
                "an event carrying an account identifier the platform did not establish must fail"
                        + " version two");
    }

    /**
     * Asserts version two keys the event on its transaction identifier and refuses an account key.
     *
     * <p>Sixteen characters against eleven digits: the two key forms cannot be confused, so a
     * consumer reading the message key knows which it holds without parsing the payload.
     */
    @Test
    void versionTwoOfTheDeclinedDocumentKeysOnTheTransactionIdentifier() {
        assertEquals("^[!-~]{16}$", propertiesOf(DECLINED_UNRESOLVED).path("aggregateId")
                        .path("pattern").stringValue(""),
                DECLINED_UNRESOLVED + " keys on the sixteen characters of TRAN-ID PIC X(16) at"
                        + " app/cpy/CVTRA05Y.cpy:L5");

        ObjectNode keyed = unresolvedDecline();
        assertTrue(validate(DECLINED_UNRESOLVED, keyed).isEmpty(),
                () -> "an unresolved decline keyed on its transaction identifier must validate: "
                        + messagesOf(validate(DECLINED_UNRESOLVED, keyed)));

        ObjectNode accountKeyed = unresolvedDecline();
        accountKeyed.put("aggregateId", ACCOUNT_IDENTIFIER);
        assertFalse(validate(DECLINED_UNRESOLVED, accountKeyed).isEmpty(),
                "an eleven-digit account key must fail version two, whose whole reason for existing"
                        + " is that no account identifier is available");
    }

    /**
     * Asserts version two carries reject reason {@code 0100} alone.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L370-L378} reads the account only while the reason code is
     * still zero, so every reason other than {@code 0100} has already resolved an account identifier
     * and belongs to version one. Pinning the code here keeps the two contracts from overlapping.
     */
    @Test
    void versionTwoOfTheDeclinedDocumentCarriesReasonZeroOneHundredAlone() {
        assertEquals("0100", propertiesOf(DECLINED_UNRESOLVED).path(DECLINE_CODE_PROPERTY)
                        .path("const").stringValue(""),
                DECLINED_UNRESOLVED + " pins the reason code set at app/cbl/CBTRN02C.cbl:L385");
        assertEquals("INVALID CARD NUMBER FOUND", propertiesOf(DECLINED_UNRESOLVED)
                        .path(DECLINE_TEXT_PROPERTY).path("const").stringValue(""),
                DECLINED_UNRESOLVED + " pins the text at app/cbl/CBTRN02C.cbl:L386");

        for (Map.Entry<String, String> pair : DECLINE_PAIRS.entrySet()) {
            if (pair.getKey().equals("0100")) {
                continue;
            }
            ObjectNode other = unresolvedDecline();
            other.put(DECLINE_CODE_PROPERTY, pair.getKey());
            other.put(DECLINE_TEXT_PROPERTY, pair.getValue());
            assertFalse(validate(DECLINED_UNRESOLVED, other).isEmpty(),
                    "reason " + pair.getKey() + " has already resolved an account identifier, so it"
                            + " belongs to version one and must fail version two");
        }
    }

    /**
     * Asserts a reason-{@code 0100} event published under version one still validates.
     *
     * <p>Nothing already on the topic becomes unreadable. Version two is where a producer publishes
     * an unresolved decline from now on; version one still describes every event published before
     * that, which is what makes this an additive change rather than a break.
     */
    @Test
    void aReasonZeroOneHundredEventUnderVersionOneStillValidates() {
        ObjectNode old = validPayloadFor(DECLINED);
        old.put(DECLINE_CODE_PROPERTY, "0100");
        old.put(DECLINE_TEXT_PROPERTY, "INVALID CARD NUMBER FOUND");

        assertTrue(validate(DECLINED, old).isEmpty(),
                () -> "an event published under version one must stay readable under version one: "
                        + messagesOf(validate(DECLINED, old)));
    }

    /**
     * Asserts the schema table selects a document by event type AND contract version, and that only
     * the declined contract has two versions.
     *
     * <p>Selecting on the event type alone would validate one declined contract against the other's
     * document and report a violation naming the wrong contract.
     */
    @Test
    void theSchemaTableSelectsADocumentByEventTypeAndVersionTogether() {
        assertEquals(List.of(1, 2), EventSchemas.governedVersions("TransactionDeclined"),
                "the declined contract is governed at both versions, so both stay readable");
        assertEquals(DECLINED, EventSchemas.resourceFor("TransactionDeclined", 1),
                "version 1 of the declined contract selects the version-one document");
        assertEquals(DECLINED_UNRESOLVED, EventSchemas.resourceFor("TransactionDeclined", 2),
                "version 2 of the declined contract selects the version-two document");
        assertNull(EventSchemas.resourceFor("TransactionDeclined", 3),
                "a version this module ships no document for selects nothing, so a gate refuses the"
                        + " event rather than checking it against another version");

        for (String eventType : EventSchemas.governedEventTypes()) {
            if (eventType.equals("TransactionDeclined")) {
                continue;
            }
            assertEquals(List.of(1), EventSchemas.governedVersions(eventType),
                    () -> eventType + " is governed at version 1 alone. Adding a version to it takes"
                            + " a new document and an entry in the schema table, not an edit of the"
                            + " version it already publishes.");
        }
    }

    /**
     * Asserts every other document still pins its message key to an eleven-digit account identifier.
     *
     * <p>{@code EventEnvelope} accepts either key form, so that one record type can carry both. That
     * widening must not reach the wire for any contract but the unresolved decline, and each
     * document is what holds the line. This test is the reason the widening is safe.
     */
    @Test
    void everyDocumentButTheUnresolvedDeclineKeysOnAnAccountIdentifier() {
        for (String document : DOCUMENTS) {
            assertEquals(ACCOUNT_IDENTIFIER_PATTERN, propertiesOf(document).path("aggregateId")
                            .path("pattern").stringValue(""),
                    () -> document + " must keep aggregateId pinned to the eleven digits of"
                            + " XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7");

            ObjectNode keyed = validPayloadFor(document);
            keyed.put("aggregateId", TRANSACTION_ID);
            assertFalse(validate(document, keyed).isEmpty(),
                    () -> document + " must refuse a sixteen-character message key. Only the"
                            + " unresolved decline is keyed on a transaction identifier.");
        }
    }

    /**
     * The version-two declined payload every test above starts from: keyed on its transaction
     * identifier, carrying reason {@code 0100}, and carrying no account identifier.
     *
     * @return one valid unresolved decline
     */
    private static ObjectNode unresolvedDecline() {
        ObjectNode payload = envelopeFor("TransactionDeclined");

        payload.put("schemaVersion", 2);
        payload.put("aggregateId", TRANSACTION_ID);
        payload.put("transactionId", TRANSACTION_ID);
        payload.put(DECLINE_CODE_PROPERTY, "0100");
        payload.put(DECLINE_TEXT_PROPERTY, "INVALID CARD NUMBER FOUND");
        payload.put(AMOUNT_PROPERTY, POSITIVE_AMOUNT);
        payload.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
        return payload;
    }
}

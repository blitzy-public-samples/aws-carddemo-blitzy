package com.carddemo.authorization.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;
import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.EventSchemas;
import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.repository.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the serialized form of each event this service publishes to the shape its schema document
 * declares.
 *
 * <p>This service publishes two events and reads neither back. An approval travels as
 * {@code TransactionAuthorized} on topic {@code transaction.authorized} and a rejection travels as
 * {@code TransactionDeclined} on {@code transaction.declined}. Two further contracts appear here
 * only where an assertion covers the shared envelope or the two monetary widths, and each such
 * method says so in its name.
 *
 * <p>Four properties of the wire form carry the weight, and each one is asserted here. The document
 * is one flat JavaScript Object Notation (JSON) object, so no {@code envelope} key reaches a topic.
 * Each monetary value travels as a decimal string of two fractional digits. A decline code travels
 * as four zero-padded characters. The two twenty-six-character timestamps carry different layouts
 * and refuse each other.
 *
 * <p>Nothing here needs a broker, a database or a network. The publish seam takes a topic name, a
 * message key and an already-written payload, all three as text. A small stub of that seam records
 * what a broker receives.
 *
 * <p>Source provenance for each assertion sits in its own method documentation. The transaction
 * record is {@code app/cpy/CVTRA05Y.cpy:L5-L18} and its daily-feed twin supplies
 * {@code app/cpy/CVTRA06Y.cpy:L10}, {@code :L15} and {@code :L16}. The account record supplies
 * {@code app/cpy/CVACT01Y.cpy:L6}, {@code :L7}, {@code :L13} and {@code :L14}, and the card record
 * {@code app/cpy/CVACT02Y.cpy:L5}, {@code :L7} and {@code :L10}. The cross-reference key is
 * {@code app/cpy/CVACT03Y.cpy:L7} and the abend group is {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
 *
 * <p>Decision logic and both timestamp layouts come from {@code app/cbl/CBTRN02C.cbl}, at
 * {@code :L149}, {@code :L159}, {@code :L160-L174}, {@code :L173-L174}, {@code :L181-L182},
 * {@code :L370-L378}, {@code :L382-L383}, {@code :L385-L387}, {@code :L397-L399},
 * {@code :L410-L412}, {@code :L417-L419}, {@code :L437}, {@code :L438}, {@code :L701} and
 * {@code :L702}. The unmasked card field is {@code app/bms/COCRDSL.bms:L96} with {@code :L99}, and
 * the sixteen-digit card check is {@code app/cbl/COCRDUPC.cbl:L193-L194}. The posting job
 * allocations are {@code app/jcl/POSTTRAN.jcl:L23} and {@code :L36}.
 *
 * <p>Dropped source fields and the two additive properties are recorded in
 * {@code card-platform/docs/traceability-matrix.md}.
 */
@DisplayName("The serialized form of each event the authorization service publishes")
class EventSerializationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Logger LOG = LoggerFactory.getLogger(EventSerializationTest.class);

    /**
     * A card number sliced at positions 263 to 278 of record one of
     * {@code app/data/ASCII/dailytran.txt}, filling {@code DALYTRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA06Y.cpy:L15}.
     *
     * <p>The cross-reference read at {@code app/cbl/CBTRN02C.cbl:L382-L383} keys on all sixteen
     * characters.
     */
    private static final String CARD_NUMBER = "4859452612877065";

    /**
     * A card verification value shaped like {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}, which the source stores in the clear.
     *
     * <p>No event, no log line and no response of this platform carries the value.
     * {@link MaskingAndTheCardVerificationValue} holds that invariant.
     */
    private static final String CARD_VERIFICATION_VALUE = "321";

    /** The masked form the published payload carries, sixteen characters ending in four digits. */
    private static final String MASKED_CARD_NUMBER = PanMasker.maskCardNumber(CARD_NUMBER);

    /**
     * The account identifier the cross-reference resolves, filling
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>The row slices by the copybook offsets sixteen, nine and eleven, and the leading zeros of
     * each slice belong to the value.
     */
    private static final String ACCOUNT_ID = "00000000007";

    /**
     * The transaction identifier of record one, at positions 1 to 16, from
     * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}.
     *
     * <p>Sixteen characters of text, never a number, so its leading zeros survive a round trip.
     */
    private static final String TRANSACTION_ID = "0000000000683580";

    /**
     * The transaction type code of record one, from {@code TRAN-TYPE-CD PIC X(02)} at
     * {@code app/cpy/CVTRA05Y.cpy:L6}.
     */
    private static final String TRANSACTION_TYPE_CODE = "01";

    /**
     * The merchant category code of record one, from {@code TRAN-CAT-CD PIC 9(04)} at
     * {@code app/cpy/CVTRA05Y.cpy:L7}.
     */
    private static final String MERCHANT_CATEGORY_CODE = "0001";

    /**
     * The capture channel of record one, ten raw characters, from {@code TRAN-SOURCE PIC X(10)} at
     * {@code app/cpy/CVTRA05Y.cpy:L8}.
     */
    private static final String SOURCE = "POS TERM  ";

    /**
     * The description of record one, from {@code TRAN-DESC PIC X(100)} at
     * {@code app/cpy/CVTRA05Y.cpy:L9}.
     */
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";

    /**
     * The amount of record one, from {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:L10}.
     *
     * <p>Positions 133 to 143 hold {@code 0000005047G}, eleven characters whose trailing byte
     * overpunches the sign onto the final digit. {@code G} is the plus-seven overpunch, giving the
     * digit string {@code 00000050477} and the value below.
     */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /**
     * The merchant identifier of record one, from {@code TRAN-MERCHANT-ID PIC 9(09)} at
     * {@code app/cpy/CVTRA05Y.cpy:L11}.
     */
    private static final String MERCHANT_ID = "800000000";

    /**
     * The merchant name of record one, from {@code TRAN-MERCHANT-NAME PIC X(50)} at
     * {@code app/cpy/CVTRA05Y.cpy:L12}.
     */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /**
     * The merchant city of record one, from {@code TRAN-MERCHANT-CITY PIC X(50)} at
     * {@code app/cpy/CVTRA05Y.cpy:L13}.
     */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /**
     * The merchant postal code of record one, from {@code TRAN-MERCHANT-ZIP PIC X(10)} at
     * {@code app/cpy/CVTRA05Y.cpy:L14}.
     */
    private static final String MERCHANT_ZIP = "72112";

    /**
     * The authorization timestamp of record one, from {@code TRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L16}.
     *
     * <p>A space separates the date from the time, colons separate the time components, and six
     * fractional digits close the value. All 300 feed records carry this one layout.
     */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /**
     * A posting timestamp in the layout {@code app/cbl/CBTRN02C.cbl:L692-L705} builds, reaching
     * {@code TRAN-PROC-TS} at {@code :L438}.
     *
     * <p>Dashes separate all three date components from the hour, dots separate the time
     * components, and the final four characters are the literal zeros {@code :L701} moves into
     * {@code DB2-REST PIC X(04)} at {@code :L174}.
     */
    private static final String POSTED_AT = "2022-07-19-23.16.01.470000";

    /**
     * A fixed event identifier, so each assertion below reads one document and never a random one.
     *
     * <p>Four of its five groups hold a hexadecimal letter, so the value carries no long run of
     * decimal digits and the digit-run assertion stays deterministic.
     */
    private static final UUID EVENT_ID = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5");

    /** A fixed publish moment, in Coordinated Universal Time, for the same determinism. */
    private static final Instant OCCURRED_AT = Instant.parse("2024-01-01T00:00:00Z");

    /** The topic an approval travels on, read from the contract registry and never restated. */
    private static final String AUTHORIZED_TOPIC =
            EventContracts.defaultTopicFor(TransactionAuthorized.EVENT_TYPE);

    /** The topic a rejection travels on, read from the same registry. */
    private static final String DECLINED_TOPIC =
            EventContracts.defaultTopicFor(TransactionDeclined.EVENT_TYPE);

    /** The account form of the message key, from {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final Pattern ACCOUNT_KEY = Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

    /** The masked-card form, twelve mask characters then four digits. */
    private static final Pattern MASKED_CARD =
            Pattern.compile(TransactionAuthorized.MASKED_CARD_NUMBER_PATTERN);

    /** The authorization layout, twenty-six characters with a space at position eleven. */
    private static final Pattern AUTHORIZATION_TIMESTAMP =
            Pattern.compile(TransactionAuthorized.AUTHORIZED_AT_PATTERN);

    /** The posting-timestamp layout, twenty-six characters with a dash at position eleven. */
    private static final Pattern POSTING_TIMESTAMP =
            Pattern.compile(TransactionPosted.POSTED_AT_PATTERN);

    /** The nine-integer-digit monetary form an amount takes. */
    private static final Pattern NINE_DIGIT_AMOUNT =
            Pattern.compile(TransactionAuthorized.AMOUNT_PATTERN);

    /** The ten-integer-digit monetary form a balance takes. */
    private static final Pattern TEN_DIGIT_BALANCE =
            Pattern.compile(TransactionPosted.TEN_INTEGER_DIGIT_BALANCE_PATTERN);

    /**
     * The shortest run of decimal digits that could hold a payment card number: twelve.
     *
     * <p>{@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is what this platform
     * stores, and the shortest card number in circulation holds twelve digits.
     */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    private static final Pattern INTEGER_DIGIT_BOUND = Pattern.compile("\\{1,([0-9]+)\\}");

    /**
     * Folded component names no event record and no diagnostic record may carry.
     *
     * <p>Each names a card verification value under one of its spellings. The source holds that
     * value in {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} and this platform
     * publishes none of it.
     */
    private static final List<String> CARD_VERIFICATION_NAMES =
            List.of("cvv", "cvc", "cv2", "cardverification", "verificationvalue", "securitycode");

    /**
     * Folded component names carrying a source field the posting path never publishes.
     *
     * <p>{@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6} and
     * {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10} are never tested
     * before a posting: {@code app/jcl/POSTTRAN.jcl:L23} allocates no card file. The two cycle
     * accumulators at {@code app/cpy/CVACT01Y.cpy:L13} and {@code :L14} stay inside the account
     * service. The three-hundred-and-fifty-byte reject payload of
     * {@code app/cbl/CBTRN02C.cbl:L176-L178} belongs to the ledger posting service.
     */
    private static final List<String> WITHHELD_SOURCE_FIELDS = List.of("activestatus", "cyccredit",
            "cycdebit", "cyclecredit", "cycledebit", "rejecttrandata", "validationtrailer");

    /**
     * Records what one publish call handed the event bus, standing in for a broker.
     *
     * <p>The seam takes three text arguments, so a stub of it needs no Kafka type and no running
     * broker. A call that never happens leaves {@link #topic}, {@link #key} and {@link #payload}
     * null, which is how the assertions below prove a refused event reached no topic.
     *
     * <p>The completed stage this returns is the contract of the seam rather than a convenience: the
     * port hands the caller a stage it owns the wait on, so a stub that captured the call has already
     * finished the send it is standing in for.
     */
    private static final class CapturingPublisher implements EventPublisherPort {

        /** The topic of the one captured call, or null when no call arrived. */
        private String topic;

        /** The message key of the one captured call, or null when no call arrived. */
        private String key;

        /** The already-written payload of the one captured call, or null when no call arrived. */
        private String payload;

        /** How many calls arrived, so a second publish cannot hide behind the first. */
        private int calls;

        @Override
        public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
            this.topic = topic;
            this.key = aggregateId;
            this.payload = payload;
            this.calls++;
            return CompletableFuture.completedStage(null);
        }
    }

    /**
     * Builds the worked example at contract version 1, which carries no card token.
     *
     * <p>Each value comes from record one of {@code app/data/ASCII/dailytran.txt}, measured at the
     * copybook offsets of {@code app/cpy/CVTRA06Y.cpy}. The twelve feed fields are the twelve
     * {@code app/cbl/CBTRN02C.cbl:L425-L436} moves onto the posted record, and the masked card
     * number and the currency are additive.
     *
     * @return the approval event, at {@link EventEnvelope#SCHEMA_VERSION}
     */
    private static TransactionAuthorized authorizedRecordOne() {
        return new TransactionAuthorized(EVENT_ID, TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID,
                TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, MASKED_CARD_NUMBER, null,
                AUTHORIZED_AT, ACCOUNT_ID, TransactionAuthorized.CURRENCY);
    }

    /**
     * @param amount the amount the event carries, at any scale
     * @return the approval event carrying that amount
     */
    private static TransactionAuthorized authorizedWithAmount(BigDecimal amount) {
        return new TransactionAuthorized(EVENT_ID, TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID,
                TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE, SOURCE, DESCRIPTION, amount,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, MASKED_CARD_NUMBER, null,
                AUTHORIZED_AT, ACCOUNT_ID, TransactionAuthorized.CURRENCY);
    }

    /**
     * @param cardNumber the value the masked component carries
     * @return the approval event carrying that value
     */
    private static TransactionAuthorized authorizedWithCardNumber(String cardNumber) {
        return new TransactionAuthorized(EVENT_ID, TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID,
                TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, cardNumber, null,
                AUTHORIZED_AT, ACCOUNT_ID, TransactionAuthorized.CURRENCY);
    }

    /**
     * Builds the rejection carrying one decline code, on a fixed envelope.
     *
     * <p>Three of the four codes follow a cross-reference read that resolved an account, so they
     * carry it. Code {@code 0100} fires inside the invalid-key limb at
     * {@code app/cbl/CBTRN02C.cbl:L385-L387}, before any account identifier exists, so its contract
     * keys on the transaction identifier and declares no account.
     *
     * @param reason the decline code the event carries
     * @return the rejection event
     */
    private static TransactionDeclined declined(DeclineReason reason) {
        if (!reason.resolvesAccount()) {
            return new TransactionDeclined(EVENT_ID, TransactionDeclined.EVENT_TYPE,
                    TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION, OCCURRED_AT,
                    TRANSACTION_ID, TRANSACTION_ID, null, reason, reason.description(), AMOUNT,
                    MASKED_CARD_NUMBER, null, null, null, null, null, null, null, null, null);
        }
        return TransactionDeclined.of(new EventEnvelope(EVENT_ID, TransactionDeclined.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID), TRANSACTION_ID, reason,
                AMOUNT, MASKED_CARD_NUMBER);
    }

    /**
     * Writes one event and checks it, exactly as the publish path does.
     *
     * <p>The serializer validates the written document against the schema of the contract version
     * the event declares, and refuses an event bound for another event type's topic.
     *
     * @param topic the destination topic the event is bound for
     * @param event the event to write
     * @param <T>   the event type
     * @return the checked document, as text
     */
    private static <T> String write(String topic, T event) {
        JsonSchemaValidatingSerializer<T> serializer = new JsonSchemaValidatingSerializer<>();
        return new String(serializer.serialize(topic, event), StandardCharsets.UTF_8);
    }

    /**
     * Writes one event, checks it, and reads the document back as a tree.
     *
     * @param topic the destination topic the event is bound for
     * @param event the event to write
     * @param <T>   the event type
     * @return the top-level object of the written document
     */
    private static <T> JsonNode tree(String topic, T event) {
        JsonNode document = MAPPER.readTree(write(topic, event));
        assertTrue(document.isObject(), "a written event must be one JSON object");
        return document;
    }

    /**
     * Reads the {@code required} array of one schema document from the classpath.
     *
     * <p>The document is a classpath resource and its {@code $id} is never dereferenced, so these
     * assertions run with networking unavailable.
     *
     * @param eventType     the routing discriminator naming the contract
     * @param schemaVersion the contract version
     * @return the property names the document requires, in declaration order
     */
    private static Set<String> requiredPropertiesOf(String eventType, int schemaVersion) {
        String resource = EventSchemas.resourceFor(eventType, schemaVersion);
        assertNotNull(resource, () -> "this module governs no document for " + eventType
                + " at contract version " + schemaVersion);

        JsonNode document = readClasspathJson(resource);
        JsonNode required = document.path("required");
        assertTrue(required.isArray() && required.size() > 0,
                () -> resource + " must name its properties in a required array");

        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < required.size(); index++) {
            names.add(required.get(index).stringValue());
        }
        return names;
    }

    /**
     * Reads one schema document, or one constraint of it, from the classpath.
     *
     * @param resource the classpath resource holding the document
     * @return the parsed document
     */
    private static JsonNode readClasspathJson(String resource) {
        try (InputStream document =
                EventSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (document == null) {
                return fail("classpath resource " + resource + " is missing");
            }
            return MAPPER.readTree(document);
        } catch (IOException failure) {
            return fail("cannot read classpath resource " + resource, failure);
        }
    }

    /**
     * Renders one numeric reject code at the width the source field declares.
     *
     * <p>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} holds
     * four digits, so a code shorter than four is zero-padded.
     *
     * <p>Formatted against {@link Locale#ROOT} rather than the default locale. The wire form carries
     * ASCII digits, and a default locale with a non-Latin decimal script renders the same code in
     * that script instead, so the expectation this builds would be compared against the wire form in
     * digits the wire form never uses. The build pins no language and no country.
     *
     * @param code the numeric reject code
     * @return the code at the four-digit width, as the wire form carries it
     */
    private static String padded(int code) {
        return String.format(Locale.ROOT,
                "%0" + PicClause.VALIDATION_FAIL_REASON_WIDTH + "d", code);
    }

    /**
     * Reads the integer-digit bound of one monetary pattern.
     *
     * @param moneyPattern the pattern a monetary property is constrained by
     * @return the digits the pattern allows before the decimal point
     */
    private static int integerDigitsOf(String moneyPattern) {
        Matcher bound = INTEGER_DIGIT_BOUND.matcher(moneyPattern);
        if (!bound.find()) {
            return fail("monetary pattern " + moneyPattern + " names no integer-digit bound");
        }
        return Integer.parseInt(bound.group(1));
    }

    /**
     * The component names of one record, folded to lower case with separators discarded.
     *
     * @param type the record to read
     * @return one folded name per component, in declaration order
     */
    private static List<String> foldedComponentNames(Class<?> type) {
        assertTrue(type.isRecord(), () -> type.getName() + " must be a record");

        List<String> folded = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            folded.add(component.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
        }
        return folded;
    }

    /**
     * Each record whose components can reach a topic from this service or from its peers.
     *
     * <p>{@code EventSchemas.RECORD_TYPES} is the closed set of governed event records, so a new
     * event type joins this sweep as soon as the platform can publish it. The envelope carrier and
     * the diagnostic record of this package join it here.
     *
     * @return each record type mapped to its own name, in a stable order
     */
    private static Map<String, Class<?>> publishableRecords() {
        Map<String, Class<?>> records = new LinkedHashMap<>();
        EventSchemas.RECORD_TYPES.values().stream()
                .sorted(Comparator.comparing(Class::getName))
                .forEach(type -> records.put(type.getSimpleName(), type));
        records.put(EventEnvelope.class.getSimpleName(), EventEnvelope.class);
        records.put(DeadLetterMetadata.class.getSimpleName(), DeadLetterMetadata.class);
        return records;
    }

    /**
     * Each interface this service declares, read from the compiled classes of this module.
     *
     * <p>Reading the code source, and not a written list, keeps the seam count honest: an interface
     * added to this service joins the sweep with no edit here.
     *
     * @return the declared interfaces, ordered by name
     */
    private static List<Class<?>> declaredInterfaces() {
        Path root = moduleClasses();
        List<Class<?>> interfaces = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> classFiles = files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".class"))
                    .sorted()
                    .toList();
            for (Path file : classFiles) {
                String binaryName = root.relativize(file).toString()
                        .replace(File.separatorChar, '.');
                binaryName = binaryName.substring(0, binaryName.length() - ".class".length());
                if (!binaryName.startsWith("com.carddemo.authorization.")) {
                    continue;
                }
                Class<?> type = Class.forName(binaryName, false,
                        EventPublisherPort.class.getClassLoader());
                if (type.isInterface()) {
                    interfaces.add(type);
                }
            }
        } catch (IOException | ClassNotFoundException failure) {
            return fail("cannot read the compiled classes of this module under " + root, failure);
        }
        interfaces.sort(Comparator.comparing(Class::getName));
        return interfaces;
    }

    /**
     * The directory holding the compiled classes of this module.
     *
     * @return the code source of {@link EventPublisherPort}
     */
    private static Path moduleClasses() {
        try {
            Path root = Path.of(EventPublisherPort.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            if (!Files.isDirectory(root)) {
                return fail("expected the compiled classes of this module in a directory and found "
                        + root);
            }
            return root;
        } catch (URISyntaxException failure) {
            return fail("cannot locate the compiled classes of this module", failure);
        }
    }

    /**
     * The five envelope properties and the payload properties sit in one object.
     *
     * <p>Each schema document of the platform names all five envelope properties in the same
     * {@code required} array as its payload properties, and closes its property set. A document
     * that nested the envelope under a key breaks that array on each payload name and on the closed
     * set. It reaches no topic and no consumer.
     *
     * <p>Each count follows a source field set. An approval carries the twelve fields of
     * {@code app/cpy/CVTRA05Y.cpy:L5-L16}, the cross-reference key of
     * {@code app/cpy/CVACT03Y.cpy:L7} and one additive currency property. A rejection carries the
     * eighty-byte trailer of {@code app/cbl/CBTRN02C.cbl:L181-L182} beside the daily-feed amount of
     * {@code app/cpy/CVTRA06Y.cpy:L10}.
     */
    @Nested
    @DisplayName("The wire form is one flat object")
    class TheWireFormIsFlat {

        /**
         * Fourteen payload properties sit beside the five envelope properties, from the twelve
         * fields of {@code app/cpy/CVTRA05Y.cpy:L5-L16} and {@code app/cpy/CVACT03Y.cpy:L7}.
         */
        @Test
        @DisplayName("an approval holds nineteen top-level properties and no envelope key")
        void anApprovalHoldsNineteenTopLevelProperties() {
            String document = write(AUTHORIZED_TOPIC, authorizedRecordOne());
            Set<String> written = new LinkedHashSet<>(MAPPER.readTree(document).propertyNames());

            assertAll(
                    () -> assertEquals(19, written.size(),
                            () -> "an approval at contract version " + EventEnvelope.SCHEMA_VERSION
                                    + " carries fourteen payload properties beside the five"
                                    + " envelope properties, and this document carries " + written),
                    () -> assertEquals(requiredPropertiesOf(TransactionAuthorized.EVENT_TYPE,
                            EventEnvelope.SCHEMA_VERSION), written,
                            "the written properties must be the properties the schema document"
                                    + " requires, at one level"),
                    () -> assertFalse(document.contains("envelope"),
                            "no envelope key, and no occurrence of that word, may reach a topic"));
        }

        /**
         * Six payload properties sit beside the five envelope properties, from the reject trailer
         * of {@code app/cbl/CBTRN02C.cbl:L181-L182} and {@code app/cpy/CVTRA06Y.cpy:L10}.
         */
        @Test
        @DisplayName("a rejection holds eleven top-level properties and no envelope key")
        void aRejectionHoldsElevenTopLevelProperties() {
            String document = write(DECLINED_TOPIC, declined(DeclineReason.OVER_CREDIT_LIMIT));
            Set<String> written = new LinkedHashSet<>(MAPPER.readTree(document).propertyNames());

            assertAll(
                    () -> assertEquals(11, written.size(),
                            () -> "a rejection at contract version " + EventEnvelope.SCHEMA_VERSION
                                    + " carries six payload properties beside the five envelope"
                                    + " properties, and this document carries " + written),
                    () -> assertEquals(requiredPropertiesOf(TransactionDeclined.EVENT_TYPE,
                            EventEnvelope.SCHEMA_VERSION), written,
                            "the written properties must be the properties the schema document"
                                    + " requires, at one level"),
                    () -> assertFalse(document.contains("envelope"),
                            "no envelope key may reach a topic"));
        }

        /**
         * The feed that {@code app/jcl/POSTTRAN.jcl:L30-L31} allocates as a sequential file becomes
         * a topic, and a document reaches that topic in the flat form alone.
         */
        @Test
        @DisplayName("a payload nesting the envelope under a key fails its document")
        void aNestedEnvelopeFailsItsDocument() {
            String nested = "{\"" + EventSchemas.EVENT_TYPE_PROPERTY + "\":\""
                    + TransactionAuthorized.EVENT_TYPE + "\",\""
                    + EventSchemas.SCHEMA_VERSION_PROPERTY + "\":" + EventEnvelope.SCHEMA_VERSION
                    + ",\"envelope\":{\"" + EventSchemas.EVENT_ID_PROPERTY + "\":\"" + EVENT_ID
                    + "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"aggregateId\":\"" + ACCOUNT_ID
                    + "\"},\"transactionId\":\"" + TRANSACTION_ID + "\"}";

            List<String> violations =
                    EventContracts.violationsOf(TransactionAuthorized.EVENT_TYPE, nested);
            JsonSchemaValidatingDeserializer<TransactionAuthorized> reader =
                    new JsonSchemaValidatingDeserializer<>(TransactionAuthorized.class);
            RuntimeException refused = assertThrows(RuntimeException.class, () -> reader
                    .deserialize(AUTHORIZED_TOPIC, nested.getBytes(StandardCharsets.UTF_8)),
                    "a nested payload must be refused before it becomes an event");

            assertAll(
                    () -> assertTrue(violations.contains("/envelope (additionalProperties)"),
                            () -> "the closed property set must refuse the envelope key, and the"
                                    + " document reported " + violations),
                    () -> assertTrue(violations.contains("/aggregateId (required)"),
                            () -> "an envelope property nested one level down is a missing"
                                    + " property, and the document reported " + violations),
                    () -> assertTrue(refused.getMessage().contains("/envelope"),
                            () -> "the refusal must name the offending property and it reads "
                                    + refused.getMessage()));
        }
    }

    /**
     * Five properties label each event, in the same five forms on each contract.
     *
     * <p>A consumer routes on {@code eventType}, deduplicates on {@code eventId} and reads
     * {@code aggregateId} as its partition key, and it parses no payload to do any of the three.
     *
     * <p>{@code aggregateId} holds the account identifier, eleven decimal digits wide from
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Its leading zeros belong
     * to the value, so it travels as text.
     */
    @Nested
    @DisplayName("The shared envelope")
    class TheSharedEnvelope {

        @Test
        @DisplayName("each envelope property takes the form its document declares")
        void eachEnvelopePropertyTakesItsDeclaredForm() {
            JsonNode approval = tree(AUTHORIZED_TOPIC, authorizedRecordOne());
            JsonNode document = readClasspathJson(EventSchemas.resourceFor(
                    TransactionAuthorized.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION));
            JsonNode declared = document.path("properties");

            assertAll(
                    () -> assertEquals(EVENT_ID.toString(),
                            approval.path(EventSchemas.EVENT_ID_PROPERTY).stringValue(),
                            "the idempotency key travels as its own text"),
                    () -> assertTrue(Pattern
                            .compile(declared.path(EventSchemas.EVENT_ID_PROPERTY).path("pattern")
                                    .stringValue())
                            .matcher(approval.path(EventSchemas.EVENT_ID_PROPERTY).stringValue())
                            .matches(),
                            "the idempotency key must match the form its document declares"),
                    () -> assertEquals(TransactionAuthorized.class.getSimpleName(),
                            approval.path(EventSchemas.EVENT_TYPE_PROPERTY).stringValue(),
                            "the routing discriminator is the simple name of the record"),
                    () -> assertEquals(
                            declared.path(EventSchemas.EVENT_TYPE_PROPERTY).path("const")
                                    .stringValue(),
                            approval.path(EventSchemas.EVENT_TYPE_PROPERTY).stringValue(),
                            "the document pins that discriminator to one value"),
                    () -> assertTrue(
                            approval.path(EventSchemas.SCHEMA_VERSION_PROPERTY).isIntegralNumber(),
                            "the contract version travels as a bare integer"),
                    () -> assertFalse(
                            approval.path(EventSchemas.SCHEMA_VERSION_PROPERTY).isString(),
                            "the contract version never travels as text"),
                    () -> assertEquals(EventEnvelope.SCHEMA_VERSION,
                            approval.path(EventSchemas.SCHEMA_VERSION_PROPERTY).intValue(),
                            "the version is the constant the contract library declares"),
                    () -> assertEquals(OCCURRED_AT, Instant
                            .parse(approval.path("occurredAt").stringValue()),
                            "the publish moment is the one ISO-8601 timestamp of this platform"),
                    () -> assertTrue(ACCOUNT_KEY.matcher(approval.path("aggregateId").stringValue())
                            .matches(),
                            "the message key is eleven decimal digits, from XREF-ACCT-ID PIC 9(11)"
                                    + " at app/cpy/CVACT03Y.cpy:L7"),
                    () -> assertEquals(PicClause.XREF_ACCT_ID_WIDTH,
                            approval.path("aggregateId").stringValue().length(),
                            "the message key holds the width the cross-reference key declares"));
        }

        @Test
        @DisplayName("the leading zeros of an account identifier survive a round trip")
        void leadingZerosSurviveARoundTrip() {
            TransactionAuthorized approval = authorizedRecordOne();
            byte[] written = write(AUTHORIZED_TOPIC, approval).getBytes(StandardCharsets.UTF_8);
            JsonSchemaValidatingDeserializer<TransactionAuthorized> reader =
                    new JsonSchemaValidatingDeserializer<>(TransactionAuthorized.class);
            TransactionAuthorized read = reader.deserialize(AUTHORIZED_TOPIC, written);

            assertAll(
                    () -> assertEquals(ACCOUNT_ID, read.aggregateId(),
                            "three leading zeros belong to the account identifier"),
                    () -> assertEquals(ACCOUNT_ID, read.accountId(),
                            "the payload identifier and the message key hold one value"),
                    () -> assertEquals(TRANSACTION_ID, read.transactionId(),
                            "the transaction identifier is text, from TRAN-ID PIC X(16) at"
                                    + " app/cpy/CVTRA05Y.cpy:L5, so its leading zeros survive"),
                    () -> assertEquals(approval, read,
                            "a round trip returns an equal record, component for component"));
        }

        /**
         * One topic replaces the sequential feed of {@code app/jcl/POSTTRAN.jcl:L30-L31} and a
         * second carries what {@code :L34-L38} allocated as a reject dataset.
         */
        @Test
        @DisplayName("each event type this service publishes names its own record")
        void eachPublishedEventTypeNamesItsOwnRecord() {
            JsonNode approval = tree(AUTHORIZED_TOPIC, authorizedRecordOne());
            JsonNode rejection =
                    tree(DECLINED_TOPIC, declined(DeclineReason.ACCOUNT_NOT_FOUND));

            assertAll(
                    () -> assertEquals(TransactionAuthorized.class.getSimpleName(),
                            approval.path(EventSchemas.EVENT_TYPE_PROPERTY).stringValue(),
                            "an approval names the approval record"),
                    () -> assertEquals(TransactionDeclined.class.getSimpleName(),
                            rejection.path(EventSchemas.EVENT_TYPE_PROPERTY).stringValue(),
                            "a rejection names the rejection record"),
                    () -> assertEquals("transaction.authorized", AUTHORIZED_TOPIC,
                            "an approval travels on the topic the contract registry binds it to"),
                    () -> assertEquals("transaction.declined", DECLINED_TOPIC,
                            "a rejection travels on the topic the registry binds it to"));
        }
    }

    /**
     * Each monetary value travels as a decimal string of two fractional digits.
     *
     * <p>A JSON number arrives in most parsers as a binary approximation, which cannot hold each
     * two-place decimal exactly. The whole correctness argument of this platform rests on
     * fixed-point arithmetic, so each schema document constrains its monetary properties as strings
     * with a pattern fixing the two places.
     *
     * <p>Two widths appear. An amount is {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:L10} and a balance is {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L7}. The {@code ROUNDED} phrase appears in none of the
     * twenty-eight members of {@code app/cbl}, so each store there truncates toward zero.
     */
    @Nested
    @DisplayName("Money travels as a decimal string")
    class MoneyTravelsAsADecimalString {

        @Test
        @DisplayName("the amount travels as a two-place decimal string, never as a number")
        void theAmountTravelsAsATwoPlaceDecimalString() {
            JsonNode approval = tree(AUTHORIZED_TOPIC, authorizedRecordOne());
            JsonNode amount = approval.path("amount");

            assertAll(
                    () -> assertTrue(amount.isString(),
                            "TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10 travels as text"),
                    () -> assertFalse(amount.isNumber(), "no monetary value travels as a number"),
                    () -> assertEquals("504.77", amount.stringValue(),
                            "record one of app/data/ASCII/dailytran.txt holds 0000005047G at"
                                    + " positions 133 to 143, whose plus-seven overpunch gives"
                                    + " 00000050477"),
                    () -> assertTrue(NINE_DIGIT_AMOUNT.matcher(amount.stringValue()).matches(),
                            "an amount allows nine digits before the point and exactly two after"),
                    () -> assertEquals(PicClause.TRAN_AMT_SCALE,
                            new BigDecimal(amount.stringValue()).scale(),
                            "the V99 of the source field fixes the fractional digits"));
        }

        @Test
        @DisplayName("a negative amount keeps its sign on the wire")
        void aNegativeAmountKeepsItsSign() {
            BigDecimal refund = new BigDecimal("-998.33");
            JsonNode approval = tree(AUTHORIZED_TOPIC, authorizedWithAmount(refund));
            String written = approval.path("amount").stringValue();

            assertAll(
                    () -> assertEquals("-998.33", written,
                            "50 of the " + PicClause.DAILYTRAN_FIXTURE_RECORD_COUNT + " records of"
                                    + " app/data/ASCII/dailytran.txt carry a negative overpunch at"
                                    + " position 143, and this is the measured minimum"),
                    () -> assertTrue(NINE_DIGIT_AMOUNT.matcher(written).matches(),
                            "the monetary pattern admits an optional leading minus"),
                    () -> assertTrue(written.startsWith("-"), "the sign travels with the value"));
        }

        @Test
        @DisplayName("a posted balance allows one integer digit more than an amount")
        void aPostedBalanceAllowsOneIntegerDigitMoreThanAnAmount() {
            BigDecimal balance = new BigDecimal("9999999999.99");
            TransactionPosted posted = TransactionPosted.forAccount(ACCOUNT_ID, TRANSACTION_ID,
                    balance, POSTED_AT, AMOUNT, MASKED_CARD_NUMBER);
            JsonNode document = tree(
                    EventContracts.defaultTopicFor(TransactionPosted.EVENT_TYPE), posted);
            int amountDigits = integerDigitsOf(TransactionAuthorized.AMOUNT_PATTERN);
            int balanceDigits =
                    integerDigitsOf(TransactionPosted.TEN_INTEGER_DIGIT_BALANCE_PATTERN);

            assertAll(
                    () -> assertEquals(
                            PicClause.TRAN_AMT_PRECISION - PicClause.TRAN_AMT_SCALE, amountDigits,
                            "an amount takes its integer digits from TRAN-AMT PIC S9(09)V99 at"
                                    + " app/cpy/CVTRA05Y.cpy:L10"),
                    () -> assertEquals(
                            PicClause.ACCT_CURR_BAL_PRECISION - PicClause.ACCT_CURR_BAL_SCALE,
                            balanceDigits,
                            "a balance takes its integer digits from ACCT-CURR-BAL PIC S9(10)V99 at"
                                    + " app/cpy/CVACT01Y.cpy:L7"),
                    () -> assertEquals(1, balanceDigits - amountDigits,
                            "the two widths differ by one and are not interchangeable"),
                    () -> assertTrue(TEN_DIGIT_BALANCE
                            .matcher(document.path("newBalance").stringValue()).matches(),
                            "the balance matches the wider pattern"),
                    () -> assertFalse(NINE_DIGIT_AMOUNT
                            .matcher(document.path("newBalance").stringValue()).matches(),
                            "the narrower amount pattern refuses a ten-digit balance"),
                    () -> assertTrue(NINE_DIGIT_AMOUNT
                            .matcher(document.path("amount").stringValue()).matches(),
                            "the amount of the same event matches its own pattern"));
        }

        /**
         * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10} holds nine integer
         * digits and two places, and no exponent form carries that shape.
         */
        @Test
        @DisplayName("an amount held in exponent notation reaches the wire in plain notation")
        void anExponentNotationAmountReachesTheWireInPlainNotation() {
            BigDecimal exponent = new BigDecimal("1E+2");
            JsonNode approval = tree(AUTHORIZED_TOPIC, authorizedWithAmount(exponent));

            assertAll(
                    () -> assertTrue(exponent.toString().contains("E"),
                            "the supplied value renders with an exponent on its own"),
                    () -> assertEquals("100.00", approval.path("amount").stringValue(),
                            "the wire form carries the plain rendering at two places"),
                    () -> assertTrue(NINE_DIGIT_AMOUNT
                            .matcher(approval.path("amount").stringValue()).matches(),
                            "no exponent form would match the monetary pattern"));
        }

        @Test
        @DisplayName("a scale change truncates toward zero and never rounds up")
        void aScaleChangeTruncatesTowardZero() {
            BigDecimal fraction = new BigDecimal("504.779");
            BigDecimal negative = new BigDecimal("-504.779");
            JsonNode approval = tree(AUTHORIZED_TOPIC, authorizedWithAmount(fraction));

            assertAll(
                    () -> assertEquals(RoundingMode.DOWN, TransactionAuthorized.AMOUNT_ROUNDING,
                            "the ROUNDED phrase appears in none of the twenty-eight programs under"
                                    + " app/cbl, so each monetary store truncates"),
                    () -> assertEquals(new BigDecimal("504.77"),
                            CobolDecimal.truncateToScale(fraction, PicClause.TRAN_AMT_SCALE),
                            "the third fractional digit is discarded, not carried"),
                    () -> assertEquals(new BigDecimal("-504.77"),
                            CobolDecimal.truncateToScale(negative, PicClause.TRAN_AMT_SCALE),
                            "truncation toward zero moves a negative value up, not down"),
                    () -> assertEquals("504.77", approval.path("amount").stringValue(),
                            "the wire form carries the truncated value"));
        }

        @Test
        @DisplayName("the fraud risk score is the one bare number on any wire form")
        void theFraudRiskScoreIsTheOneBareNumber() {
            FraudFlagged flagged = FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID,
                    FraudFlagged.MAXIMUM_RISK_SCORE, List.of(FraudFlagged.VELOCITY_RULE),
                    OCCURRED_AT);
            String fraudTopic = EventContracts.defaultTopicFor(FraudFlagged.EVENT_TYPE);
            JsonNode assessment = tree(fraudTopic, flagged);
            JsonNode cleared = tree(EventContracts.defaultTopicFor(FraudCleared.EVENT_TYPE),
                    FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, OCCURRED_AT));

            assertAll(
                    () -> assertTrue(assessment.path("riskScore").isIntegralNumber(),
                            "a risk score is a count and no source field defines it"),
                    () -> assertFalse(assessment.path("riskScore").isString(),
                            "a risk score carries no monetary scale, so it needs no string form"),
                    () -> assertTrue(assessment.path("triggeredRules").isArray(),
                            "the rules that flagged the transaction travel as an array"),
                    () -> assertEquals(fraudTopic,
                            EventContracts.defaultTopicFor(FraudCleared.EVENT_TYPE),
                            "both fraud contracts share one topic and are told apart by eventType"),
                    () -> assertEquals(8, cleared.size(),
                            "the smallest contract still carries all five envelope properties"));
        }
    }

    /**
     * A decline code travels as four zero-padded characters, beside the text the source writes.
     *
     * <p>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} and
     * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code :L182} are the eighty-byte trailer
     * of the reject record at {@code :L176-L178}. A rejection is ordinary traffic:
     * {@code :L229-L230} ends the batch job with return code 4 once any record was rejected.
     */
    @Nested
    @DisplayName("The decline code and its text")
    class TheDeclineCodeAndItsText {

        /**
         * A locale whose numbering system renders digits outside ASCII.
         *
         * <p>Verified on this runtime rather than assumed: {@code String.format} under this locale
         * renders 102 as Arabic-Indic digits, so it is a real difference and not a hypothetical one.
         * The build pins neither {@code user.language} nor {@code user.country} in either surefire or
         * failsafe configuration, so the default locale is whatever the host supplies.
         */
        private static final Locale NON_ASCII_DIGIT_LOCALE = Locale.forLanguageTag("ar-EG");

        /**
         * Asserts the expected wire form is built in ASCII digits whatever the default locale is.
         *
         * <p>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} holds
         * four ASCII digits, and the wire form carries them unchanged. The expectation
         * {@code padded} builds was formatted against the default locale, so on a host whose default
         * locale carries a non-Latin numbering system the expectation itself came out in that script
         * and the comparison tested a wire form the service never produces.
         *
         * <p>The default locale is changed for the duration of this test only and restored in a
         * finally block. No parallelism is configured in either surefire or failsafe, so no other
         * test observes the change.
         *
         * <p>First the locale is shown to make a difference, then the padding is shown not to depend
         * on it. Without the first assertion the second would pass on a runtime where every locale
         * renders ASCII, and would prove nothing.
         */
        @Test
        @DisplayName("the padded expectation stays ASCII under a non-Latin default locale")
        void thePaddedExpectationStaysAsciiUnderANonLatinDefaultLocale() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(NON_ASCII_DIGIT_LOCALE);
                String defaultLocaleRendering =
                        String.format("%0" + PicClause.VALIDATION_FAIL_REASON_WIDTH + "d",
                                DeclineReason.OVER_CREDIT_LIMIT.numericCode());

                assertAll(
                        () -> assertFalse(isAsciiDigits(defaultLocaleRendering),
                                "this locale renders ASCII, so it cannot demonstrate the"
                                        + " difference: " + defaultLocaleRendering),
                        () -> assertTrue(isAsciiDigits(padded(DeclineReason.OVER_CREDIT_LIMIT
                                .numericCode())), "the padded expectation left ASCII"),
                        () -> assertEquals(DeclineReason.OVER_CREDIT_LIMIT.code(),
                                padded(DeclineReason.OVER_CREDIT_LIMIT.numericCode()),
                                "the padded expectation equals the code the enum publishes"),
                        () -> assertTrue(isAsciiDigits(tree(DECLINED_TOPIC,
                                        declined(DeclineReason.OVER_CREDIT_LIMIT))
                                        .path("declineReasonCode").stringValue()),
                                "the wire form itself left ASCII"));
            } finally {
                Locale.setDefault(original);
            }
        }

        /**
         * Answers whether every character is an ASCII digit.
         *
         * @param value the rendered value
         * @return true when the value is non-empty and holds ASCII digits only
         */
        private static boolean isAsciiDigits(String value) {
            return !value.isEmpty() && value.chars().allMatch(digit -> digit >= '0' && digit <= '9');
        }

        @Test
        @DisplayName("each code travels as four zero-padded characters and never as a number")
        void eachCodeTravelsAsFourZeroPaddedCharacters() {
            for (DeclineReason reason : DeclineReason.values()) {
                JsonNode code = tree(DECLINED_TOPIC, declined(reason)).path("declineReasonCode");

                assertAll(
                        () -> assertTrue(code.isString(),
                                () -> reason + " travels as text, at the PIC 9(04) width of"
                                        + " app/cbl/CBTRN02C.cbl:L181"),
                        () -> assertFalse(code.isNumber(),
                                () -> reason + " never travels as a bare number"),
                        () -> assertEquals(reason.code(), code.stringValue(),
                                () -> reason + " travels as the code its own accessor returns"),
                        () -> assertEquals(PicClause.VALIDATION_FAIL_REASON_WIDTH,
                                code.stringValue().length(),
                                () -> reason + " fills the four digits of the source field"),
                        () -> assertNotEquals(String.valueOf(reason.numericCode()),
                                code.stringValue(),
                                () -> reason + " travels padded and never in its numeric form"));
            }
        }

        @Test
        @DisplayName("each text comes from the enum and fits the trailer width")
        void eachTextComesFromTheEnumAndFitsTheTrailerWidth() {
            for (DeclineReason reason : DeclineReason.values()) {
                String text = tree(DECLINED_TOPIC, declined(reason))
                        .path("declineReasonDescription").stringValue();

                assertAll(
                        () -> assertEquals(reason.description(), text,
                                () -> reason + " carries the text its own accessor returns,"
                                        + " character for character"),
                        () -> assertTrue(
                                text.length() <= PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                                () -> reason + " carries " + text.length() + " characters and"
                                        + " WS-VALIDATION-FAIL-REASON-DESC at"
                                        + " app/cbl/CBTRN02C.cbl:L182 holds "
                                        + PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH),
                        () -> assertEquals(text.trim(), text,
                                () -> reason + " carries no padding of its own"));
            }
        }

        @Test
        @DisplayName("the closed set holds four codes and one rejection carries one of them")
        void theClosedSetHoldsFourCodesAndOneRejectionCarriesOne() {
            RecordComponent code = Stream.of(TransactionDeclined.class.getRecordComponents())
                    .filter(component -> "declineReasonCode".equals(component.getName()))
                    .findFirst()
                    .orElseGet(() -> fail("a rejection must carry a decline code"));

            assertAll(
                    () -> assertEquals(4, DeclineReason.values().length,
                            () -> "app/cbl/CBTRN02C.cbl assigns four reject codes, at L385-L387,"
                                    + " L397-L399, L410-L412 and L417-L419, and the enum holds "
                                    + List.of(DeclineReason.values())),
                    () -> assertEquals((long) DeclineReason.values().length,
                            Stream.of(DeclineReason.values()).map(DeclineReason::code).distinct()
                                    .count(),
                            "each code in the closed set is distinct"),
                    () -> assertEquals(DeclineReason.class, code.getType(),
                            "one rejection carries one code"),
                    () -> assertFalse(Collection.class.isAssignableFrom(code.getType()),
                            "no rejection carries a list, a set or a mask of codes: the tests at"
                                    + " app/cbl/CBTRN02C.cbl:L407 and :L414 write one field, so a"
                                    + " later assignment overwrites an earlier one"));
        }

        /**
         * The four codes are assigned at {@code app/cbl/CBTRN02C.cbl:L385}, {@code :L397},
         * {@code :L410} and {@code :L417}, and the source assigns no fifth published code.
         */
        @Test
        @DisplayName("a code outside the closed set is refused by the enum and by the document")
        void aCodeOutsideTheClosedSetIsRefused() {
            String unknownCode = "9999";
            String aCodeTheSetOmits = padded(DeclineReason.ACCOUNT_EXPIRED.numericCode() + 6);
            String document = write(DECLINED_TOPIC, declined(DeclineReason.ACCOUNT_EXPIRED))
                    .replace("\"" + DeclineReason.ACCOUNT_EXPIRED.code() + "\"",
                            "\"" + unknownCode + "\"");
            List<String> violations =
                    EventContracts.violationsOf(TransactionDeclined.EVENT_TYPE, document);

            IllegalArgumentException refusedUnknown = assertThrows(IllegalArgumentException.class,
                    () -> DeclineReason.fromCode(unknownCode),
                    "a code outside the four must be refused");
            IllegalArgumentException refusedOmitted = assertThrows(IllegalArgumentException.class,
                    () -> DeclineReason.fromCode(aCodeTheSetOmits),
                    "a four-digit code the closed set omits must be refused too");

            assertAll(
                    () -> assertTrue(refusedUnknown.getMessage().contains(unknownCode),
                            "the refusal names the value it received"),
                    () -> assertTrue(refusedOmitted.getMessage().contains(aCodeTheSetOmits),
                            "the second refusal names its value too"),
                    () -> assertTrue(violations.contains("/declineReasonCode (enum)"),
                            () -> "the document enumerates the four codes, so a fifth breaks it,"
                                    + " and it reported " + violations));
        }

        @Test
        @DisplayName("a rejection is ordinary traffic and serializes without failure")
        void aRejectionIsOrdinaryTrafficAndSerializesWithoutFailure() {
            for (DeclineReason reason : DeclineReason.values()) {
                TransactionDeclined event = declined(reason);

                String written = assertDoesNotThrow(() -> write(DECLINED_TOPIC, event),
                        () -> "app/cbl/CBTRN02C.cbl:L229-L230 ends the batch job with return code 4"
                                + " once a record was rejected, so " + reason + " builds a valid"
                                + " event and never a dead-letter routing");

                assertAll(
                        () -> assertNotNull(written, reason + " serialized to nothing"),
                        () -> assertTrue(written.contains("\"declineReasonCode\":\"" + reason.code()
                                        + "\""),
                                () -> "the written event has to carry the source reject code, or a"
                                        + " consumer cannot tell why the transaction was refused: "
                                        + written),
                        () -> assertTrue(written.contains("\"declineReasonDescription\":\""
                                        + reason.description() + "\""),
                                () -> "and the verbatim source description that goes with it: "
                                        + written));
            }
        }
    }

    /**
     * Two source fields hold twenty-six characters each, in two layouts that refuse each other.
     *
     * <p>{@code TRAN-ORIG-TS} at {@code app/cpy/CVTRA05Y.cpy:L16} separates its date from its time
     * with a space and carries six fractional digits. {@code TRAN-PROC-TS} at {@code :L17} takes
     * the layout {@code DB2-FORMAT-TS PIC X(26)} builds at {@code app/cbl/CBTRN02C.cbl:L159} and
     * redefines at {@code :L160-L174}. That layout puts a third dash where the space sits and dots
     * through the time. It closes with two significant digits at {@code DB2-MIL PIC 9(002)} on
     * {@code :L173} and the four literal zeros {@code :L701} moves into
     * {@code DB2-REST PIC X(04)} on {@code :L174}.
     *
     * <p>Neither layout is ISO-8601, so neither carries a date-time format keyword. The publish
     * moment of the envelope is the one ISO-8601 timestamp this service emits.
     */
    @Nested
    @DisplayName("The two twenty-six-character timestamps")
    class TheTwoTimestampLayouts {

        @Test
        @DisplayName("each layout refuses the other")
        void eachLayoutRefusesTheOther() {
            int separator = PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET
                    - PicClause.PROCESSING_TIMESTAMP_SEPARATOR_WIDTH;

            assertAll(
                    () -> assertEquals(PicClause.TRAN_ORIG_TS_WIDTH, AUTHORIZED_AT.length(),
                            "the authorization timestamp fills its source field"),
                    () -> assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, POSTED_AT.length(),
                            "the posting timestamp fills its source field"),
                    () -> assertTrue(AUTHORIZATION_TIMESTAMP.matcher(AUTHORIZED_AT).matches(),
                            "the authorization layout accepts its own value"),
                    () -> assertFalse(AUTHORIZATION_TIMESTAMP.matcher(POSTED_AT).matches(),
                            "the authorization layout refuses a posting timestamp"),
                    () -> assertTrue(POSTING_TIMESTAMP.matcher(POSTED_AT).matches(),
                            "the posting layout accepts its own value"),
                    () -> assertFalse(POSTING_TIMESTAMP.matcher(AUTHORIZED_AT).matches(),
                            "the posting layout refuses an authorization timestamp"),
                    () -> assertEquals(' ', AUTHORIZED_AT.charAt(separator),
                            "a space separates the date from the time at app/cpy/CVTRA05Y.cpy:L16"),
                    () -> assertEquals(PicClause.PROCESSING_TIMESTAMP_DASH,
                            POSTED_AT.charAt(separator),
                            "a third dash separates them at app/cbl/CBTRN02C.cbl:L702"),
                    () -> assertEquals(AUTHORIZED_AT,
                            tree(AUTHORIZED_TOPIC, authorizedRecordOne()).path("authorizedAt")
                                    .stringValue(),
                            "the approval carries the authorization layout unchanged"));
        }

        /**
         * {@code app/cbl/CBTRN02C.cbl:L702} moves a dash into all three separators, and
         * {@code :L701} moves four literal zeros into {@code DB2-REST PIC X(04)} at {@code :L174}.
         */
        @Test
        @DisplayName("the posting layout refuses a space, an ISO separator and six fraction digits")
        void thePostingLayoutRefusesThreeNeighbouringForms() {
            List<String> refusedForms = List.of(
                    "2022-07-19 23:16:01.470000",
                    "2022-07-19T23:16:01.470000",
                    "2022-07-19-23.16.01.470123");

            for (String form : refusedForms) {
                assertAll(
                        () -> assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, form.length(),
                                () -> form + " holds the right width and the wrong layout"),
                        () -> assertFalse(POSTING_TIMESTAMP.matcher(form).matches(),
                                () -> form + " must not match the posting layout"),
                        () -> assertThrows(IllegalArgumentException.class,
                                () -> TransactionPosted.forAccount(ACCOUNT_ID, TRANSACTION_ID,
                                        AMOUNT, form, AMOUNT, MASKED_CARD_NUMBER),
                                () -> form + " must be refused before it reaches a topic"));
            }
        }

        @Test
        @DisplayName("the processing-timestamp helper truncates to hundredths and pads with zeros")
        void theProcessingTimestampHelperTruncatesToHundredths() {
            String rendered = CobolDecimal
                    .formatProcessingTimestamp(LocalDateTime.parse("2022-07-19T23:16:01.479"));
            String fraction = rendered.substring(PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET,
                    PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET);

            assertAll(
                    () -> assertEquals(POSTED_AT, rendered,
                            "the helper renders the layout app/cbl/CBTRN02C.cbl:L692-L705 builds"),
                    () -> assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, rendered.length(),
                            "the rendering fills DB2-FORMAT-TS PIC X(26) at"
                                    + " app/cbl/CBTRN02C.cbl:L159"),
                    () -> assertEquals(PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS,
                            fraction.length(),
                            "DB2-MIL PIC 9(002) at app/cbl/CBTRN02C.cbl:L173 holds two digits"),
                    () -> assertEquals("47", fraction,
                            "the third fractional digit is discarded, never carried"),
                    () -> assertTrue(
                            rendered.endsWith(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS),
                            "app/cbl/CBTRN02C.cbl:L701 moves four literal zeros into DB2-REST"),
                    () -> assertTrue(POSTING_TIMESTAMP.matcher(rendered).matches(),
                            "the rendering satisfies the layout its own contract declares"),
                    () -> assertEquals(rendered,
                            tree(EventContracts.defaultTopicFor(TransactionPosted.EVENT_TYPE),
                                    TransactionPosted.forAccount(ACCOUNT_ID, TRANSACTION_ID, AMOUNT,
                                            rendered, AMOUNT, MASKED_CARD_NUMBER))
                                    .path("postedAt").stringValue(),
                            "the rendering reaches the wire unchanged"));
        }
    }

    /**
     * The decision reads the full card number and the payload carries only the masked form.
     *
     * <p>The order is what matters. {@code app/cbl/CBTRN02C.cbl:L382-L383} keys the cross-reference
     * read on all sixteen characters, so masking before that read breaks the lookup itself. Masking
     * happens where the payload is built, and the published document carries nothing else.
     *
     * <p>No masking exists in the source. {@code app/bms/COCRDSL.bms:L96} defines the card detail
     * field unprotected with {@code LENGTH=16} at {@code :L99}, and
     * {@code app/cbl/COCRDUPC.cbl:L193-L194} validates a card number only as sixteen digits. The
     * masked property is therefore additive, and the traceability matrix records it.
     */
    @Nested
    @DisplayName("Masking and the card verification value")
    @ExtendWith(OutputCaptureExtension.class)
    class MaskingAndTheCardVerificationValue {

        @Test
        @DisplayName("the lookup key is the full card number and the payload carries the mask")
        void theLookupKeyIsFullAndThePayloadIsMasked() {
            String document = write(AUTHORIZED_TOPIC, authorizedRecordOne());
            String written = MAPPER.readTree(document).path("maskedCardNumber").stringValue();

            assertAll(
                    () -> assertEquals(PicClause.XREF_CARD_NUM_WIDTH, CARD_NUMBER.length(),
                            "the cross-reference key is the whole card number, from"
                                    + " XREF-CARD-NUM PIC X(16) at app/cpy/CVACT03Y.cpy:L5"),
                    () -> assertEquals(MASKED_CARD_NUMBER, written,
                            "the payload carries the value the masking helper produced"),
                    () -> assertEquals("************7065", written,
                            "twelve mask characters then the last four digits of the card of"
                                    + " record one"),
                    () -> assertEquals(PicClause.TRAN_CARD_NUM_WIDTH, written.length(),
                            "the masked value keeps the width of TRAN-CARD-NUM PIC X(16) at"
                                    + " app/cpy/CVTRA05Y.cpy:L15"),
                    () -> assertTrue(MASKED_CARD.matcher(written).matches(),
                            "the masked value matches the pattern its document declares"),
                    () -> assertFalse(document.contains(CARD_NUMBER),
                            "the full card number reaches no topic"));
        }

        /**
         * The masked form keeps the width of {@code CARD-NUM PIC X(16)} at
         * {@code app/cpy/CVACT02Y.cpy:L5} and carries the last four digits alone.
         */
        @Test
        @DisplayName("the masked property refuses the unmasked value without echoing it")
        void theMaskedPropertyRefusesTheUnmaskedValue() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> authorizedWithCardNumber(CARD_NUMBER),
                    "a card number opens with a digit, so the masked pattern refuses it");

            assertAll(
                    () -> assertFalse(MASKED_CARD.matcher(CARD_NUMBER).matches(),
                            "the pattern exists so an unmasked value cannot pass"),
                    () -> assertFalse(refused.getMessage().contains(CARD_NUMBER),
                            "the refusal reports a length and never the value"),
                    () -> assertFalse(LONG_DIGIT_RUN.matcher(refused.getMessage()).find(),
                            "no refusal carries a run long enough to hold a card number"));
        }

        /**
         * A full sixteen-digit card number, the form {@code app/cpy/CVACT02Y.cpy:L5} declares, is
         * the value most likely to fail the masked pattern.
         */
        @Test
        @DisplayName("a document carrying the unmasked value is refused by pointer alone")
        void aDocumentCarryingTheUnmaskedValueIsRefusedByPointerAlone() {
            String leaking = write(AUTHORIZED_TOPIC, authorizedRecordOne())
                    .replace("\"" + MASKED_CARD_NUMBER + "\"", "\"" + CARD_NUMBER + "\"");
            List<String> violations =
                    EventContracts.violationsOf(TransactionAuthorized.EVENT_TYPE, leaking);
            String message =
                    EventContracts.describeViolations(TransactionAuthorized.EVENT_TYPE, violations);
            JsonSchemaValidatingDeserializer<TransactionAuthorized> reader =
                    new JsonSchemaValidatingDeserializer<>(TransactionAuthorized.class);
            RuntimeException refused = assertThrows(RuntimeException.class, () -> reader
                    .deserialize(AUTHORIZED_TOPIC, leaking.getBytes(StandardCharsets.UTF_8)),
                    "an unmasked card number must be refused on arrival as well");

            assertAll(
                    () -> assertTrue(violations.contains("/maskedCardNumber (pattern)"),
                            () -> "the document must name the property and the keyword, and it"
                                    + " reported " + violations),
                    () -> assertTrue(message.contains("/maskedCardNumber"),
                            "the failure text names the property as a JSON pointer"),
                    () -> assertFalse(message.contains(CARD_NUMBER),
                            "the failure text carries no value from the document"),
                    () -> assertFalse(LONG_DIGIT_RUN.matcher(message).find(),
                            "the failure text carries no run long enough to hold a card number"),
                    () -> assertTrue(refused.getMessage().contains("/maskedCardNumber"),
                            "the arrival refusal names the same property"),
                    () -> assertFalse(refused.getMessage().contains(CARD_NUMBER),
                            "the arrival refusal carries no value either"));
        }

        /**
         * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} holds sixteen characters, so
         * a long digit run on a wire form is that identifier and nothing else.
         */
        @Test
        @DisplayName("the transaction identifier is the only long digit run on the wire")
        void theTransactionIdentifierIsTheOnlyLongDigitRun() {
            String document = write(AUTHORIZED_TOPIC, authorizedRecordOne());
            List<String> runs = LONG_DIGIT_RUN.matcher(document).results()
                    .map(match -> match.group())
                    .toList();

            assertAll(
                    () -> assertFalse(runs.isEmpty(),
                            "the identifier of TRAN-ID PIC X(16) is itself sixteen digits, so one"
                                    + " run is expected"),
                    () -> assertEquals(List.of(TRANSACTION_ID), runs,
                            () -> "each long digit run must be the transaction identifier, and the"
                                    + " document carries " + runs),
                    () -> assertFalse(document.contains(CARD_NUMBER),
                            "no card number rides along in any property"));
        }

        @Test
        @DisplayName("no publishable component is named for a card verification value")
        void noPublishableComponentIsNamedForACardVerificationValue() {
            Map<String, Class<?>> records = publishableRecords();

            assertFalse(records.isEmpty(), "the sweep must cover at least one record");
            records.forEach((name, type) -> {
                for (String folded : foldedComponentNames(type)) {
                    for (String forbidden : CARD_VERIFICATION_NAMES) {
                        assertFalse(folded.contains(forbidden),
                                () -> name + " declares the component " + folded + ", which names a"
                                        + " card verification value. The source holds one in"
                                        + " CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7 and"
                                        + " this platform publishes none of it");
                    }
                }
            });
        }

        /**
         * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} stores three digits in
         * the clear, and no log line of this service carries them.
         */
        @Test
        @DisplayName("no log line carries the card verification value or the full card number")
        void noLogLineCarriesTheVerificationValueOrTheCardNumber(CapturedOutput output) {
            String marker = "authorization-event-under-test";
            TransactionAuthorized approval = authorizedRecordOne();
            String payload = write(AUTHORIZED_TOPIC, approval);

            LOG.info("{} rendered={} payload={}", marker, approval, payload);

            String captured = output.getAll();
            int start = captured.indexOf(marker);
            assertTrue(start >= 0, "the capture must hold the line this method wrote");
            String line = captured.substring(start);

            assertAll(
                    () -> assertEquals(PicClause.CARD_CVV_CD_WIDTH,
                            CARD_VERIFICATION_VALUE.length(),
                            "the source holds three digits in CARD-CVV-CD PIC 9(03)"),
                    () -> assertFalse(line.contains(CARD_VERIFICATION_VALUE),
                            "no verification value reaches a log line"),
                    () -> assertFalse(line.contains(CARD_NUMBER),
                            "no full card number reaches a log line"),
                    () -> assertTrue(line.contains(EventEnvelope.WITHHELD),
                            "the rendering withholds each value a reader does not need"),
                    () -> assertEquals(PanMasker.REDACTED_CARD_VERIFICATION_VALUE,
                            PanMasker.redactCardVerificationValue(CARD_VERIFICATION_VALUE),
                            "a verification value handed to a diagnostic comes back redacted"));
        }

        @Test
        @DisplayName("the masking helper offers no way back to the card number")
        void theMaskingHelperOffersNoWayBack() {
            List<String> reversingNames =
                    List.of("unmask", "reverse", "decode", "decrypt", "restore", "original", "raw");

            for (Method method : PanMasker.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                String folded = method.getName().toLowerCase(Locale.ROOT);
                for (String reversing : reversingNames) {
                    assertFalse(folded.contains(reversing),
                            () -> "the masking helper declares " + method.getName()
                                    + ", and no operation may lead back to a card number");
                }
            }
        }
    }

    /**
     * One interface carries each event out of this service, and it takes three text arguments.
     *
     * <p>The seam is the one abstraction of an external system this service declares. Its ancestor
     * in the source is the queue write at {@code app/cbl/CORPT00C.cbl:L517-L518}, the single
     * asynchronous handoff of the CardDemo application. A different event bus needs one more
     * implementation of the seam and no other change.
     */
    @Nested
    @DisplayName("The publish seam")
    class ThePublishSeam {

        /**
         * The one asynchronous handoff of the source is the queue write at
         * {@code app/cbl/CORPT00C.cbl:L517-L518}, and this seam replaces it.
         *
         * <p>The stage this seam answers with is the contract that lets the caller own the wait. A
         * seam returning nothing had to block inside itself, and the duration it blocked for was one
         * it chose: {@code outbox/OutboxRelay} could then spend the whole of one pass on a single
         * unreachable send, because it had no way to say how much of its pass remained. The stage
         * moves that decision to the component that knows it.
         */
        @Test
        @DisplayName("the seam declares one publish of three text arguments answering a stage")
        void theSeamDeclaresOnePublishOfThreeTextArgumentsAnsweringAStage() {
            Method[] declared = EventPublisherPort.class.getDeclaredMethods();

            assertEquals(1, declared.length,
                    () -> "the seam declares one method and it declares "
                            + Stream.of(declared).map(Method::getName).toList());

            Method publish = declared[0];

            assertAll(
                    () -> assertEquals("publish", publish.getName(),
                            "the one method publishes one payload"),
                    () -> assertEquals(CompletionStage.class, publish.getReturnType(),
                            "the seam answers a stage, so the caller owns how long it waits"),
                    () -> assertEquals("java.util.concurrent.CompletionStage<java.lang.Void>",
                            publish.getGenericReturnType().getTypeName(),
                            "the stage carries no value, only the outcome of the send"),
                    () -> assertEquals(3, publish.getParameterCount(),
                            "a topic, a message key and a payload"),
                    () -> assertEquals(List.of(String.class, String.class, String.class),
                            List.of(publish.getParameterTypes()),
                            "all three arguments are text, so a stub of the seam needs no broker"
                                    + " type"),
                    () -> assertEquals(0, publish.getExceptionTypes().length,
                            "a failed publish arrives as an unchecked failure"),
                    () -> assertFalse(publish.isDefault(),
                            "the seam carries no implementation of its own"),
                    () -> assertFalse(Modifier.isStatic(publish.getModifiers()),
                            "the seam is implemented, never called statically"),
                    () -> assertEquals(0, publish.getAnnotations().length,
                            "the seam carries no framework annotation"),
                    () -> assertEquals(0, EventPublisherPort.class.getAnnotations().length,
                            "and neither does the interface"),
                    () -> assertFalse(EventPublisherPort.class.isSealed(),
                            "a new implementation needs no edit to the seam"));
        }

        @Test
        @DisplayName("the event bus is the only external seam this service declares")
        void theEventBusIsTheOnlyExternalSeam() {
            List<Class<?>> declared = declaredInterfaces();
            List<Class<?>> beyondPersistence = declared.stream()
                    .filter(type -> !Repository.class.isAssignableFrom(type))
                    .toList();
            List<Class<?>> beyondDomain = beyondPersistence.stream()
                    .filter(type -> !type.getPackageName()
                            .equals("com.carddemo.authorization.domain"))
                    .toList();

            assertAll(
                    () -> assertTrue(declared.contains(EventPublisherPort.class),
                            () -> "the sweep must reach the seam itself, and it read " + declared),
                    () -> assertTrue(declared.size() > beyondPersistence.size(),
                            "the persistence contracts of this service are store interfaces"),
                    () -> assertEquals(List.of(EventPublisherPort.class), beyondDomain,
                            () -> "one interface abstracts an external system and the sweep found "
                                    + beyondDomain + ". The rest are store contracts"
                                    + " or the domain rule seam app/cbl/CBTRN02C.cbl:L377 marks"));
        }

        @Test
        @DisplayName("the message key is the eleven-digit account identifier the payload carries")
        void theMessageKeyIsTheAccountIdentifierThePayloadCarries() {
            CapturingPublisher publisher = new CapturingPublisher();
            TransactionAuthorized approval = authorizedRecordOne();
            String payload = write(AUTHORIZED_TOPIC, approval);

            publisher.publish(AUTHORIZED_TOPIC, approval.aggregateId(), payload);

            assertAll(
                    () -> assertEquals(1, publisher.calls, "one approval publishes one message"),
                    () -> assertEquals(AUTHORIZED_TOPIC, publisher.topic,
                            "an approval reaches the topic its contract binds it to"),
                    () -> assertEquals(ACCOUNT_ID, publisher.key,
                            "the key is the account identifier, so all events of one account"
                                    + " land on one partition and stay in publish order"),
                    () -> assertTrue(ACCOUNT_KEY.matcher(publisher.key).matches(),
                            "the key holds eleven decimal digits"),
                    () -> assertEquals(PicClause.XREF_ACCT_ID_WIDTH, publisher.key.length(),
                            "the key holds the width of XREF-ACCT-ID PIC 9(11) at"
                                    + " app/cpy/CVACT03Y.cpy:L7"),
                    () -> assertEquals(publisher.key,
                            MAPPER.readTree(publisher.payload).path("aggregateId").stringValue(),
                            "the key and the payload name one aggregate"),
                    () -> assertEquals(payload, publisher.payload,
                            "the payload travels as the text the publish path checked"));
        }

        @Test
        @DisplayName("a refused event reaches no topic")
        void aRefusedEventReachesNoTopic() {
            CapturingPublisher publisher = new CapturingPublisher();
            JsonSchemaValidatingSerializer<Object> serializer =
                    new JsonSchemaValidatingSerializer<>();
            TransactionAuthorized approval = authorizedRecordOne();

            RuntimeException refusedType = assertThrows(RuntimeException.class,
                    () -> publisher.publish(AUTHORIZED_TOPIC, ACCOUNT_ID, new String(
                            serializer.serialize(AUTHORIZED_TOPIC, "not an event"),
                            StandardCharsets.UTF_8)),
                    "a value that is no registered event must be refused before any publish");
            RuntimeException refusedRoute = assertThrows(RuntimeException.class,
                    () -> publisher.publish(DECLINED_TOPIC, ACCOUNT_ID, new String(
                            serializer.serialize(DECLINED_TOPIC, approval),
                            StandardCharsets.UTF_8)),
                    "an approval addressed to the rejection topic must be refused too");

            assertAll(
                    () -> assertEquals(0, publisher.calls,
                            "a refused event reaches no topic, so the seam is never called"),
                    () -> assertNull(publisher.payload, "and nothing is captured"),
                    () -> assertTrue(refusedType.getMessage()
                            .contains(TransactionAuthorized.EVENT_TYPE),
                            "the refusal names the event types it does write"),
                    () -> assertTrue(refusedRoute.getMessage().contains(AUTHORIZED_TOPIC),
                            "the misrouting refusal names the topic the event belongs on"));
        }
    }

    /**
     * Four diagnostics travel with a dead-letter routing, and nothing the source never published.
     *
     * <p>{@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29} holds four alphanumeric
     * fields, each with a {@code VALUE SPACES} clause: {@code ABEND-CODE PIC X(4)} at {@code :L22},
     * {@code ABEND-CULPRIT PIC X(8)} at {@code :L24}, {@code ABEND-REASON PIC X(50)} at
     * {@code :L26} and {@code ABEND-MSG PIC X(72)} at {@code :L28}. The four widths bound what a
     * dead-letter topic carries.
     */
    @Nested
    @DisplayName("Dead-letter diagnostics and deliberate absences")
    class DiagnosticsAndDeliberateAbsences {

        @Test
        @DisplayName("four text diagnostics travel in the copybook order, each inside its width")
        void fourTextDiagnosticsTravelInTheCopybookOrder() {
            RecordComponent[] components = DeadLetterMetadata.class.getRecordComponents();
            List<Integer> widths = List.of(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
                    DeadLetterMetadata.CULPRIT_MAX_LENGTH, DeadLetterMetadata.REASON_MAX_LENGTH,
                    DeadLetterMetadata.MESSAGE_MAX_LENGTH);
            int widest = widths.stream().max(Integer::compareTo).orElseThrow();
            DeadLetterMetadata overLong = new DeadLetterMetadata("A".repeat(widest + 1),
                    "B".repeat(widest + 1), "C".repeat(widest + 1), "D".repeat(widest + 1));
            List<String> held = List.of(overLong.abendCode(), overLong.culprit(),
                    overLong.reason(), overLong.message());

            assertAll(
                    () -> assertEquals(widths.size(), components.length,
                            "the record holds one component per source field"),
                    () -> assertEquals(List.of("abendCode", "culprit", "reason", "message"),
                            Stream.of(components).map(RecordComponent::getName).toList(),
                            "the components follow app/cpy/CSMSG02Y.cpy:L21-L29 in order"),
                    () -> assertTrue(Stream.of(components)
                            .allMatch(component -> String.class.equals(component.getType())),
                            "each of the four source fields is alphanumeric"),
                    () -> assertEquals(widths,
                            held.stream().map(String::length).toList(),
                            () -> "no diagnostic may exceed its source width, and the four held "
                                    + held.stream().map(String::length).toList()));
        }

        @Test
        @DisplayName("neither published event carries a timestamp its contract does not declare")
        void neitherPublishedEventCarriesAnUndeclaredTimestamp() {
            JsonNode approval = tree(AUTHORIZED_TOPIC, authorizedRecordOne());
            JsonNode rejection = tree(DECLINED_TOPIC, declined(DeclineReason.OVER_CREDIT_LIMIT));

            assertAll(
                    () -> assertTrue(approval.has("authorizedAt"),
                            "an approval carries the origin timestamp of"
                                    + " app/cpy/CVTRA05Y.cpy:L16"),
                    () -> assertFalse(approval.has("processedAt"),
                            "TRAN-PROC-TS at app/cpy/CVTRA05Y.cpy:L17 is blank in all "
                                    + PicClause.DAILYTRAN_FIXTURE_RECORD_COUNT + " records of"
                                    + " app/data/ASCII/dailytran.txt"),
                    () -> assertFalse(approval.has("postedAt"),
                            "app/cbl/CBTRN02C.cbl:L437-L438 stamps that field at posting time, so"
                                    + " the posted contract carries it"),
                    () -> assertFalse(rejection.has("declinedAt"),
                            "a rejection carries no timestamp of its own"),
                    () -> assertFalse(rejection.has("processedAt"),
                            "and no processing timestamp either"),
                    () -> assertTrue(rejection.has("occurredAt"),
                            "the publish moment of the envelope is the one timestamp it needs"));
        }

        /**
         * {@code ACCT-ACTIVE-STATUS} at {@code app/cpy/CVACT01Y.cpy:L6}, the cycle accumulators at
         * {@code :L13-L14} and {@code CARD-ACTIVE-STATUS} at {@code app/cpy/CVACT02Y.cpy:L10} reach
         * no published document.
         */
        @Test
        @DisplayName("no event carries a field the posting path never published")
        void noEventCarriesAFieldThePostingPathNeverPublished() {
            Map<String, Class<?>> records = publishableRecords();
            Set<String> writtenProperties =
                    new LinkedHashSet<>(tree(AUTHORIZED_TOPIC, authorizedRecordOne())
                            .propertyNames());

            records.forEach((name, type) -> {
                for (String folded : foldedComponentNames(type)) {
                    for (String withheld : WITHHELD_SOURCE_FIELDS) {
                        assertFalse(folded.contains(withheld),
                                () -> name + " declares the component " + folded
                                        + ", which no published event carries");
                    }
                }
            });
            for (String property : writtenProperties) {
                String folded = property.toLowerCase(Locale.ROOT);
                for (String withheld : WITHHELD_SOURCE_FIELDS) {
                    assertFalse(folded.contains(withheld),
                            () -> "an approval carries the property " + property
                                    + ", which the posting path never published");
                }
            }
        }
    }
}

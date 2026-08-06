package com.carddemo.ledger.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.config.ObservabilityConfig;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.domain.AccountBalanceUpdater.AccountBalanceRowMissingException;
import com.carddemo.ledger.domain.PostingService;
import com.carddemo.ledger.domain.RejectRecorder;
import com.carddemo.ledger.entity.ProcessedEventEntity;
import com.carddemo.ledger.outbox.OutboxWriter;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that a second delivery of one event identifier changes nothing, and that a failed
 * delivery commits no offset.
 *
 * <p>{@link TransactionAuthorizedConsumer} replaces the job step at
 * {@code app/jcl/POSTTRAN.jcl:L23}. Two of its behaviours are ADDITIVE, with no Common Business
 * Oriented Language (COBOL) ancestor. The term {@code duplicate} appears nowhere in
 * {@code app/cbl/CBTRN02C.cbl}, and a replayed feed reaches {@code :L562-L579}, hits a duplicate
 * key and abends at {@code :L707-L711}. The reject dataset at
 * {@code app/jcl/POSTTRAN.jcl:L34-L38} allocated an empty file per run and redelivered nothing.
 *
 * <p>Each collaborator records its call in one ordered journal, and a test states the sequence it
 * expects. Nothing here opens a database connection or reaches a broker.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("The transaction-authorized listener, its guard and its acknowledgement")
final class TransactionAuthorizedConsumerTest {

    /**
     * Account row 21 of {@code app/data/ASCII/cardxref.txt} resolves the fixture card to, eleven
     * digits with the leading zeros held.
     *
     * <p>Width from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     */
    private static final String ACCOUNT_ID = "00000000007";

    /** Transaction identifier of record 1 of {@code app/data/ASCII/dailytran.txt}, sixteen
     * bytes. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /**
     * The card number of that record as an event carries it: twelve mask characters, then the last
     * four digits. ADDITIVE, since no source program masks anything. The full card number appears
     * nowhere in this file.
     */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /**
     * The opaque card identity a version 2 event carries, sixty-four lower-case hexadecimal
     * characters. The value derives from no card number present here.
     */
    private static final String CARD_TOKEN =
            "a3f1c9d40b6e28a7f5c1d9e3b8a46f20c7d5e1b9a4f68c3d2e07b1a95f4c8d6e";

    /**
     * The origin timestamp of that record, twenty-six characters. A space at position eleven and
     * colons in the time, from {@code DALYTRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA06Y.cpy:L16}. The layout is not ISO-8601.
     */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /**
     * The amount of that record. Bytes 133 to 143 hold {@code 0000005047G}, and the zoned overpunch
     * {@code G} carries the sign with the digit 7, from
     * {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:L10}.
     */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** Transaction type code of that record, from {@code DALYTRAN-TYPE-CD PIC X(02)}. */
    private static final String TRANSACTION_TYPE_CODE = "01";

    /** Merchant category code of that record, from {@code DALYTRAN-CAT-CD PIC 9(04)}. */
    private static final String MERCHANT_CATEGORY_CODE = "0001";

    /** Capture channel of that record, from {@code DALYTRAN-SOURCE PIC X(10)}. */
    private static final String SOURCE = "POS TERM";

    /** Description of that record, from {@code DALYTRAN-DESC PIC X(100)}. */
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";

    /** Merchant identifier of that record, from {@code DALYTRAN-MERCHANT-ID PIC 9(09)}. */
    private static final String MERCHANT_ID = "800000000";

    /** Merchant name of that record, from {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** Merchant city of that record, from {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** Merchant postal code of that record, from {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. */
    private static final String MERCHANT_ZIP = "72112";

    /** The identifier both deliveries of the duplicate tests carry, pinned so a run repeats. */
    private static final UUID EVENT_ID = UUID.fromString("7f9a0b21-3c4d-4e5f-8a6b-1c2d3e4f5a6b");

    /** The moment the producer stamped the event, the one ISO-8601 field an event carries. */
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53.470Z");

    /** The topic this listener reads, and the value every marker it writes records. */
    private static final String TOPIC = "transaction.authorized";

    /** The topic a record the container gives up on is addressed to. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** Opening text of the record rendering a failed delivery writes. */
    private static final String DIAGNOSTIC_PREFIX = "DeadLetterMetadata[";

    /**
     * The component name a diagnostic of this listener carries, at the width of
     * {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    private static final String CULPRIT = "LEDGPOST";

    /** A run of twelve or more digits, the shape a card number or an account leak would take. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    /**
     * Sixteen digits, the width {@code TransactionAuthorized.MASKED_CARD_NUMBER_LENGTH} declares.
     * The value is assembled from one repeated digit, so no such run is written in this file.
     */
    private static final String CARD_WIDTH_DIGITS =
            "9".repeat(TransactionAuthorized.MASKED_CARD_NUMBER_LENGTH);

    /** A failure message wide enough to leak a card number, used to prove that none travels. */
    private static final String FAILURE_MESSAGE_CARRYING_DIGITS =
            "no balance row for card " + CARD_WIDTH_DIGITS;

    /** Counter family of the driver-loop outcomes, one series per outcome. */
    private static final String OUTCOME_METER = "carddemo.ledger.transactions.processed";

    /** Counter family of the processing faults, one series per stage. */
    private static final String FAILURE_METER = "carddemo.ledger.failures";

    /** Counter of the events read, after {@code app/cbl/CBTRN02C.cbl:L206}. */
    private static final String CONSUMED_METER = "carddemo.ledger.events.consumed";

    /** Timer of one delivery. ADDITIVE, since the source times nothing. */
    private static final String LATENCY_METER = "carddemo.ledger.processing.latency";

    /** Journal entry the transaction manager writes when the boundary opens. */
    private static final String BEGIN = "begin";

    /** Journal entry the guard writes when it reads the marker store. */
    private static final String EXISTS = "exists";

    /** Journal entry the posting path writes when it is entered. */
    private static final String POST = "post";

    /** Journal entry the marker store writes when a marker is saved. */
    private static final String SAVE = "save";

    /** Journal entry the transaction manager writes when the boundary commits. */
    private static final String COMMIT = "commit";

    /** Journal entry the transaction manager writes when the boundary rolls back. */
    private static final String ROLLBACK = "rollback";

    /** Journal entry the acknowledgement writes when the offset is committed. */
    private static final String ACK = "ack";

    /** Every call each collaborator received, in the order the subject made them. */
    private final List<String> journal = new ArrayList<>();

    private PostingService postingService;
    private ProcessedEventRepository processedEvents;
    private MeterRegistry registry;
    private LedgerMeters meters;
    private RecordingAcknowledgment acknowledgment;
    private TransactionAuthorizedConsumer consumer;

    /** The marker the subject last handed to the store, or null when it saved none. */
    private ProcessedEventEntity savedMarker;

    /** The event the subject last handed to the posting path, or null when it posted nothing. */
    private TransactionAuthorized postedEvent;

    /** The message key the subject last handed to the posting path. */
    private String postedKey;

    /**
     * Builds the subject over recording collaborators, a real transaction template and a real meter
     * registry.
     */
    @BeforeEach
    void buildSubject() {
        journal.clear();
        savedMarker = null;
        postedEvent = null;
        postedKey = null;
        postingService = mock(PostingService.class);
        processedEvents = mock(ProcessedEventRepository.class);
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().ledgerMeters(registry);
        acknowledgment = new RecordingAcknowledgment(journal);
        consumer = new TransactionAuthorizedConsumer(postingService, processedEvents,
                new TransactionTemplate(new RecordingTransactionManager(journal)), meters, TOPIC,
                DEAD_LETTER_TOPIC);
        recordSavedMarkers();
        recordPostings();
    }

    /**
     * Builds one event carrying the fixture values of record 1 of the daily feed.
     *
     * @param eventId the identifier this delivery carries
     * @return the event a delivery hands to the listener
     */
    private static TransactionAuthorized anEvent(UUID eventId) {
        return new TransactionAuthorized(eventId, TransactionAuthorized.EVENT_TYPE,
                TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID,
                TRANSACTION_ID, TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE, SOURCE, DESCRIPTION,
                AMOUNT, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT, ACCOUNT_ID,
                TransactionAuthorized.CURRENCY);
    }

    /**
     * Builds the event every test in this class delivers, under the pinned identifier.
     *
     * @return the event a delivery hands to the listener
     */
    private static TransactionAuthorized anEvent() {
        return anEvent(EVENT_ID);
    }

    /** Answers the guard with no marker, so this delivery is the first to hold the identifier. */
    private void markerAbsent() {
        when(processedEvents.existsById(any(UUID.class))).thenAnswer(invocation -> {
            journal.add(EXISTS);
            return false;
        });
    }

    /** Answers the guard with a marker, so a delivery of this event has already been applied. */
    private void markerPresent() {
        when(processedEvents.existsById(any(UUID.class))).thenAnswer(invocation -> {
            journal.add(EXISTS);
            return true;
        });
    }

    /** Journals each saved marker and keeps the last one for inspection. */
    private void recordSavedMarkers() {
        when(processedEvents.save(any(ProcessedEventEntity.class))).thenAnswer(invocation -> {
            journal.add(SAVE);
            savedMarker = invocation.getArgument(0);
            return savedMarker;
        });
    }

    /** Journals each posting and applies nothing, so this class observes the calls alone. */
    private void recordPostings() {
        doAnswer(invocation -> {
            journal.add(POST);
            postedEvent = invocation.getArgument(0);
            postedKey = invocation.getArgument(1);
            return null;
        }).when(postingService).postTransaction(any(TransactionAuthorized.class), anyString());
    }

    /**
     * Makes the posting raise a failure, journalling the attempt before it does.
     *
     * @param failure the failure the posting path raises
     */
    private void postingFails(RuntimeException failure) {
        doAnswer(invocation -> {
            journal.add(POST);
            throw failure;
        }).when(postingService).postTransaction(any(TransactionAuthorized.class), anyString());
    }

    /**
     * Builds the store fault the retry policy treats as retryable.
     *
     * @return a fault of the family {@code config/KafkaConsumerConfig} leaves retryable
     */
    private static DataAccessException aStoreFault() {
        return new TransientDataAccessResourceException("the marker store is unreachable");
    }

    /**
     * Finds one counter series by its name and the tag that separates it.
     *
     * @param name     the meter name
     * @param tagKey   the tag that separates the series
     * @param tagValue the value of that tag
     * @return the counter of that series
     */
    private Counter counter(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter();
    }

    /**
     * Finds the counter of one driver-loop outcome.
     *
     * @param outcome one of the outcome tag values the meters declare
     * @return the counter of that outcome
     */
    private Counter outcomeCounter(String outcome) {
        return counter(OUTCOME_METER, "outcome", outcome);
    }

    /**
     * Finds the counter of one failure stage.
     *
     * @param stage one of the stage tag values the meters declare
     * @return the counter of that stage
     */
    private Counter failureCounter(String stage) {
        return counter(FAILURE_METER, "stage", stage);
    }

    /** Finds the one listener method of the subject. */
    private static Method listenerMethod() {
        for (Method method : TransactionAuthorizedConsumer.class.getDeclaredMethods()) {
            if (method.getName().equals("onTransactionAuthorized")) {
                return method;
            }
        }
        throw new AssertionError("onTransactionAuthorized must stay a method of the subject");
    }

    /** Reads the {@link KafkaListener} the one listener method carries. */
    private static KafkaListener listenerAnnotation() {
        return listenerMethod().getAnnotation(KafkaListener.class);
    }

    /** Finds the one constructor the subject offers for injection. */
    private static Constructor<?> injectionConstructor() {
        Constructor<?>[] constructors = TransactionAuthorizedConsumer.class.getConstructors();
        assertEquals(1, constructors.length, "one constructor keeps the collaborator set stated");
        return constructors[0];
    }

    /**
     * Reads the one record rendering the captured console output holds.
     *
     * @param consoleOutput everything the delivery wrote to the console
     * @return the rendering, from its opening text to its closing bracket
     */
    private static String theOneDiagnostic(CapturedOutput consoleOutput) {
        String all = consoleOutput.getAll();
        assertEquals(1, occurrences(all, DIAGNOSTIC_PREFIX),
                "one failed delivery builds one record and renders it once");
        int start = all.indexOf(DIAGNOSTIC_PREFIX);
        int end = all.indexOf(']', start);
        assertTrue(end > start, "the rendering must be complete");
        return all.substring(start, end + 1);
    }

    /**
     * Counts how often one text occurs in another.
     *
     * @param haystack the text to search
     * @param needle   the text to count
     * @return the number of occurrences
     */
    private static int occurrences(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    /**
     * Asserts one text carries no run of twelve or more digits.
     *
     * @param subject the text to check
     * @param what    what the text is, for the failure message
     */
    private static void assertCarriesNoLongDigitRun(String subject, String what) {
        Matcher run = LONG_DIGIT_RUN.matcher(subject);
        assertFalse(run.find(),
                () -> what + " must carry no run of twelve or more digits, and holds " + subject);
    }

    @Nested
    @DisplayName("A first delivery posts, marks and acknowledges in that order")
    class PostingPath {

        /**
         * The whole sequence of one new delivery. The guard reads before the posting, the marker
         * follows it, and the acknowledgement follows the commit.
         */
        @Test
        @DisplayName("the six calls arrive in one fixed order")
        void theSixCallsArriveInOrder() {
            markerAbsent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            assertEquals(List.of(BEGIN, EXISTS, POST, SAVE, COMMIT, ACK), journal,
                    "the guard reads first, the marker follows the posting, and the offset is"
                            + " committed after the transaction");
        }

        /** The posting receives the event it was handed, keyed by the account of the event. */
        @Test
        @DisplayName("the event travels unchanged, keyed by its aggregate identifier")
        void theEventTravelsUnchanged() {
            markerAbsent();
            TransactionAuthorized event = anEvent();

            consumer.onTransactionAuthorized(event, acknowledgment);

            assertEquals(ACCOUNT_ID, event.aggregateId(),
                    "the key is the account identifier of app/cpy/CVACT03Y.cpy:L7");
            assertSame(event, postedEvent, "the listener alters nothing it passes on");
            assertEquals(ACCOUNT_ID, postedKey, "the posting is keyed by that same identifier");
            assertEquals("USD", event.currency(),
                    "the currency is a constant and it is ADDITIVE: no source record carries one");
            assertEquals(AMOUNT, event.amount(),
                    "the amount is the value app/cpy/CVTRA06Y.cpy:L10 declares, at scale two");
        }

        /** The marker carries the event identifier and the topic the delivery arrived on. */
        @Test
        @DisplayName("the marker holds the event identifier and the topic")
        void theMarkerHoldsTheIdentifierAndTopic() {
            markerAbsent();
            TransactionAuthorized event = anEvent();

            consumer.onTransactionAuthorized(event, acknowledgment);

            assertNotNull(savedMarker, "one new delivery saves one marker");
            assertEquals(event.eventId(), savedMarker.getEventId(),
                    "the marker is keyed by the envelope identifier");
            assertEquals(TOPIC, savedMarker.getConsumedTopic(),
                    "the marker names the topic this listener reads");
        }

        /**
         * The listener supplies the instant of the marker. The instant falls inside the window of
         * the call, so it comes from no field of the event.
         */
        @Test
        @DisplayName("the listener stamps the marker instant itself")
        void theListenerStampsTheMarkerInstant() {
            markerAbsent();

            Instant before = Instant.now();
            consumer.onTransactionAuthorized(anEvent(), acknowledgment);
            Instant after = Instant.now();

            Instant stamped = savedMarker.getProcessedAt();
            assertFalse(stamped.isBefore(before), "the instant is taken during the call");
            assertFalse(stamped.isAfter(after), "the instant is taken during the call");
            assertTrue(stamped.isAfter(OCCURRED_AT),
                    "the instant is the moment of processing and not a field of the event");
        }

        /**
         * The counters of the driver loop. {@code app/cbl/CBTRN02C.cbl:L206} counts a record read
         * and {@code :L227-L230} reports both totals.
         */
        @Test
        @DisplayName("one posting counts one consumed event and one posted outcome")
        void onePostingCountsItsOutcome() {
            markerAbsent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            assertEquals(1.0d, registry.get(CONSUMED_METER).counter().count(),
                    "one delivery is one consumed event, after app/cbl/CBTRN02C.cbl:L206");
            assertEquals(1.0d,
                    outcomeCounter("posted").count(),
                    "the posted outcome carries the count");
            assertEquals(1L, registry.get(LATENCY_METER).timer().count(),
                    "the timer records one observation per delivery");
        }
    }

    @Nested
    @DisplayName("A duplicate delivery writes nothing and still acknowledges")
    class DuplicateDelivery {

        /**
         * The whole sequence of a second delivery. Nothing sits between the guard and the commit,
         * so the ADDITIVE guard the gap at {@code app/cbl/CBTRN02C.cbl:L562-L579} calls for holds.
         */
        @Test
        @DisplayName("the guard ends the delivery, and the offset is still committed")
        void theGuardEndsTheDelivery() {
            markerPresent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            assertEquals(List.of(BEGIN, EXISTS, COMMIT, ACK), journal,
                    "no posting and no marker sit between the guard and the commit");
        }

        /** The second delivery reads the marker store under the identifier of the event. */
        @Test
        @DisplayName("the guard reads the identifier the event carries")
        void theGuardReadsTheEventIdentifier() {
            markerPresent();
            TransactionAuthorized event = anEvent();

            consumer.onTransactionAuthorized(event, acknowledgment);

            verify(processedEvents).existsById(event.eventId());
            assertFalse(journal.contains(SAVE), "the guard writes no second marker");
        }

        /**
         * Two deliveries of one identifier post once. The second reaches the guard and stops, which
         * is the whole of the ADDITIVE protection.
         */
        @Test
        @DisplayName("two deliveries of one identifier post once")
        void twoDeliveriesPostOnce() {
            markerAbsent();
            consumer.onTransactionAuthorized(anEvent(), acknowledgment);
            markerPresent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            assertEquals(List.of(BEGIN, EXISTS, POST, SAVE, COMMIT, ACK,
                    BEGIN, EXISTS, COMMIT, ACK), journal,
                    "one posting and one marker across two deliveries, and both acknowledge");
        }

        /**
         * A duplicate is expected traffic. {@code app/cbl/CBTRN02C.cbl:L214} counts a reject and
         * {@code :L229-L230} answers it with return code 4 and no abend, so no failure is counted
         * here either.
         */
        @Test
        @DisplayName("a duplicate counts its own outcome and no failure")
        void aDuplicateCountsNoFailure() {
            markerPresent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            assertEquals(1.0d,
                    outcomeCounter("duplicate").count(),
                    "a duplicate carries its own outcome");
            assertEquals(0.0d, failureCounter("process").count(),
                    "a duplicate is not a fault");
            assertEquals(0.0d,
                    outcomeCounter("rejected").count(),
                    "a duplicate is not a reject");
        }

        /**
         * A duplicate reaches neither severity that would page somebody.
         *
         * @param consoleOutput everything the delivery wrote to the console
         */
        @Test
        @DisplayName("a duplicate is logged at neither error nor warning severity")
        void aDuplicateIsLoggedQuietly(CapturedOutput consoleOutput) {
            markerPresent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            String all = consoleOutput.getAll();
            assertFalse(all.contains("ERROR"), "a duplicate is not an error");
            assertFalse(all.contains("WARN"), "a duplicate is not a warning");
            assertEquals(0, occurrences(all, DIAGNOSTIC_PREFIX),
                    "a duplicate builds no diagnostic, so it reaches no dead-letter topic");
            assertFalse(all.contains(TRANSACTION_ID), "no line names the transaction");
            assertFalse(all.contains(MASKED_CARD_NUMBER), "no line names a card field");
        }
    }

    @Nested
    @DisplayName("A failed delivery rolls back and commits no offset")
    class FailureHandling {

        /**
         * The unguarded rewrite at {@code app/cbl/CBTRN02C.cbl:L545-L560} assigned a fifth reject
         * reason at {@code :L556} that nothing read. Here the same condition is a fault.
         */
        @Test
        @DisplayName("an absent balance row rolls back and acknowledges nothing")
        void anAbsentBalanceRowRollsBack() {
            markerAbsent();
            postingFails(new AccountBalanceRowMissingException(ACCOUNT_ID));

            assertThrows(AccountBalanceRowMissingException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment),
                    "the fault reaches the container, which decides the next attempt");

            assertEquals(List.of(BEGIN, EXISTS, POST, ROLLBACK), journal,
                    "no marker is saved and no offset is committed");
        }

        /** A store fault behaves as the missing row does. Both keep the retryable default. */
        @Test
        @DisplayName("a store fault rolls back and acknowledges nothing")
        void aStoreFaultRollsBack() {
            markerAbsent();
            postingFails(aStoreFault());

            assertThrows(DataAccessException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment),
                    "the fault reaches the container unchanged");

            assertEquals(List.of(BEGIN, EXISTS, POST, ROLLBACK), journal,
                    "a store fault leaves the offset uncommitted");
        }

        /** A failure counts under the process stage, which is a series of its own. */
        @Test
        @DisplayName("a failure counts one process failure and no reject")
        void aFailureCountsOneProcessFailure() {
            markerAbsent();
            postingFails(aStoreFault());

            assertThrows(DataAccessException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment));

            assertEquals(1.0d, failureCounter("process").count(),
                    "the fault is counted as the fault it is");
            assertEquals(0.0d,
                    outcomeCounter("rejected").count(),
                    "the reject series of app/cbl/CBTRN02C.cbl:L214 stays separate from failures");
        }

        /** The timer stops in a finally block, so a failed delivery is measured too. */
        @Test
        @DisplayName("the latency timer records a failed delivery")
        void theTimerRecordsAFailedDelivery() {
            markerAbsent();
            postingFails(aStoreFault());

            assertThrows(DataAccessException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment));

            assertEquals(1L, registry.get(LATENCY_METER).timer().count(),
                    "one delivery is timed whatever became of it");
        }

        /** Neither argument may be absent, since neither has a default the listener could use. */
        @Test
        @DisplayName("neither argument may be absent")
        void neitherArgumentMayBeAbsent() {
            assertThrows(NullPointerException.class,
                    () -> consumer.onTransactionAuthorized(null, acknowledgment),
                    "a delivery with no event has nothing to apply");
            assertThrows(NullPointerException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), null),
                    "a delivery with no acknowledgement could never commit its offset");
        }
    }

    @Nested
    @DisplayName("The diagnostic a failed delivery renders")
    class FailureDiagnostic {

        /**
         * The abend routine at {@code app/cbl/CBTRN02C.cbl:L707-L711} became this diagnostic, whose
         * four values take the shape of {@code ABEND-DATA} at
         * {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
         * The dead-letter route it names is ADDITIVE.
         *
         * @param consoleOutput everything the delivery wrote to the console
         */
        @Test
        @DisplayName("one failed delivery renders one diagnostic naming this component")
        void oneFailedDeliveryRendersOneDiagnostic(CapturedOutput consoleOutput) {
            markerAbsent();
            postingFails(aStoreFault());

            assertThrows(DataAccessException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment));

            String diagnostic = theOneDiagnostic(consoleOutput);
            assertTrue(diagnostic.contains(CULPRIT),
                    () -> "the culprit of app/cpy/CSMSG02Y.cpy:L24 names this component, and the"
                            + " diagnostic holds " + diagnostic);
            assertTrue(consoleOutput.getAll().contains(DEAD_LETTER_TOPIC),
                    "the failure line names the destination a spent record is addressed to");
        }

        /**
         * The four values of the diagnostic carry no digit run, so no card number and no account
         * identifier can travel in one.
         *
         * @param consoleOutput everything the delivery wrote to the console
         */
        @Test
        @DisplayName("the diagnostic carries no long digit run")
        void theDiagnosticCarriesNoLongDigitRun(CapturedOutput consoleOutput) {
            markerAbsent();
            postingFails(aStoreFault());

            assertThrows(DataAccessException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment));

            String diagnostic = theOneDiagnostic(consoleOutput);
            assertCarriesNoLongDigitRun(diagnostic, "the diagnostic");
            assertFalse(diagnostic.contains(TRANSACTION_ID),
                    "the transaction identifier stays out of the diagnostic");
            assertFalse(diagnostic.contains(MASKED_CARD_NUMBER),
                    "no card field reaches the diagnostic at all");
        }

        /**
         * The diagnostic names the type of the failure and copies no text from it, so a message
         * carrying sixteen digits leaks nothing.
         *
         * @param consoleOutput everything the delivery wrote to the console
         */
        @Test
        @DisplayName("the diagnostic names the failure class and copies no failure text")
        void theDiagnosticNamesTheFailureClass(CapturedOutput consoleOutput) {
            markerAbsent();
            RuntimeException talkative =
                    new TransientDataAccessResourceException(FAILURE_MESSAGE_CARRYING_DIGITS);
            postingFails(talkative);

            assertThrows(DataAccessException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment));

            String diagnostic = theOneDiagnostic(consoleOutput);
            assertTrue(diagnostic.contains(talkative.getClass().getSimpleName()),
                    "the fourth value of the diagnostic is a class name");
            assertFalse(consoleOutput.getAll().contains(CARD_WIDTH_DIGITS),
                    "the text of the failure reaches no line of output");
            assertCarriesNoLongDigitRun(diagnostic, "the diagnostic");
        }
    }

    @Nested
    @DisplayName("The collaborators the listener is given")
    class CollaboratorSet {

        /**
         * Six collaborators and no publisher. The posted event leaves through the outbox row that
         * {@code domain/PostingService} writes, so this listener publishes nothing itself.
         */
        @Test
        @DisplayName("the constructor takes no publisher and no outbox writer")
        void theConstructorTakesNoPublisher() {
            List<Class<?>> parameters = List.of(injectionConstructor().getParameterTypes());

            assertEquals(List.of(PostingService.class, ProcessedEventRepository.class,
                    TransactionTemplate.class, LedgerMeters.class, String.class, String.class),
                    parameters, "the collaborator set holds the posting path, the marker store, the"
                            + " boundary, the meters and two topic names");
        }

        /** No field holds an outbox writer or a reject recorder, whether injected or built. */
        @Test
        @DisplayName("no field holds an outbox writer or a reject recorder")
        void noFieldHoldsAWriterOrARecorder() {
            for (Field field : TransactionAuthorizedConsumer.class.getDeclaredFields()) {
                assertFalse(OutboxWriter.class.equals(field.getType()),
                        () -> "field " + field.getName() + " must not write outbox rows");
                assertFalse(RejectRecorder.class.equals(field.getType()),
                        () -> "field " + field.getName() + " must not record rejects: a reject is a"
                                + " feed-validation outcome and belongs to the domain path");
            }
        }

        /**
         * A missing balance row raises the one typed failure this listener lets escape, and the
         * failure names the account in its own message alone.
         */
        @Test
        @DisplayName("the typed failure of a missing balance row is a runtime failure")
        void theTypedFailureIsARuntimeFailure() {
            RuntimeException missing = new AccountBalanceRowMissingException(ACCOUNT_ID);

            assertNotNull(missing.getMessage(), "the failure states what was missing");
            assertEquals("AccountBalanceRowMissingException",
                    missing.getClass().getSimpleName(),
                    "the diagnostic carries this name and no message text");
        }
    }

    @Nested
    @DisplayName("The listener contract the container binds")
    class ListenerContract {

        /** The topic is a placeholder, so a renamed key breaks start-up. */
        @Test
        @DisplayName("the topic is a property placeholder")
        void theTopicIsAPlaceholder() {
            String[] topics = listenerAnnotation().topics();

            assertEquals(1, topics.length, "one listener reads one topic");
            assertEquals("${carddemo.kafka.topics.transaction-authorized}", topics[0],
                    "a stale topic name must never bind silently");
        }

        /** The group is a placeholder, so the shipped configuration names the group. */
        @Test
        @DisplayName("the group is a property placeholder")
        void theGroupIsAPlaceholder() {
            assertEquals("${spring.kafka.consumer.group-id}", listenerAnnotation().groupId(),
                    "a group of its own makes this reader independent of the other consumers");
        }

        /** One record per invocation. No header parameter and no key parameter. */
        @Test
        @DisplayName("the method takes one event and one acknowledgement")
        void theMethodTakesTwoParameters() {
            Method listener = listenerMethod();

            assertEquals(2, listener.getParameterCount(), "one record per invocation");
            assertEquals(TransactionAuthorized.class, listener.getParameterTypes()[0],
                    "the first parameter is the deserialized payload");
            assertEquals(Acknowledgment.class, listener.getParameterTypes()[1],
                    "the second parameter is the offset commit");
            assertEquals(void.class, listener.getReturnType(), "a listener returns nothing");
        }
    }

    @Nested
    @DisplayName("The meter families a demonstration reads")
    class MeterFamilies {

        /** The three required families register before the first message arrives. */
        @Test
        @DisplayName("all three families register before the first message")
        void allThreeFamiliesRegisterAtStartUp() {
            assertNotNull(registry.find(CONSUMED_METER).counter(),
                    "events consumed is the first family");
            assertNotNull(registry.find(LATENCY_METER).timer(),
                    "processing latency is the second, and it is ADDITIVE");
            assertNotNull(registry.find(FAILURE_METER).tag("stage", "process")
                    .counter(), "failure count is the third");
            assertEquals(0.0d, registry.get(CONSUMED_METER).counter().count(),
                    "a scrape before the first message reports zero");
        }

        /**
         * The reject series after {@code app/cbl/CBTRN02C.cbl:L214} and the failure series are
         * separate meters. A reject is a normal outcome under {@code :L229-L230}.
         */
        @Test
        @DisplayName("the reject series and the failure series stay separate")
        void theRejectAndFailureSeriesStaySeparate() {
            meters.recordTransactionRejected();

            assertEquals(1.0d,
                    outcomeCounter("rejected").count(),
                    "the reject count stands on its own");
            assertEquals(0.0d, failureCounter("process").count(),
                    "a reject never reaches the failure series");
        }

        /** No meter name and no tag value carries an identifier, so the series count stays
         * fixed. */
        @Test
        @DisplayName("no meter name and no tag value carries an identifier")
        void noMeterCarriesAnIdentifier() {
            markerAbsent();
            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            registry.getMeters().forEach(meter -> {
                String rendered = meter.getId().getName() + " " + meter.getId().getTags();
                assertFalse(rendered.contains(ACCOUNT_ID), () -> "meter " + rendered
                        + " must name no account");
                assertFalse(rendered.contains(TRANSACTION_ID), () -> "meter " + rendered
                        + " must name no transaction");
                assertCarriesNoLongDigitRun(rendered, "meter " + rendered);
            });
        }
    }

    /** Records every acknowledgement in the shared journal. */
    private static final class RecordingAcknowledgment implements Acknowledgment {

        /** The shared journal this fake appends to. */
        private final List<String> journal;

        /**
         * Takes the journal every collaborator of one test shares.
         *
         * @param journal the ordered call record
         */
        RecordingAcknowledgment(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void acknowledge() {
            journal.add(ACK);
        }
    }

    /**
     * Records the boundary a real {@link TransactionTemplate} opens, commits and rolls back.
     *
     * <p>The template runs its callback and reaches this manager afterwards, so the guarded work of
     * every test below actually runs.
     */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        /** The shared journal this fake appends to. */
        private final List<String> journal;

        /**
         * Takes the journal every collaborator of one test shares.
         *
         * @param journal the ordered call record
         */
        RecordingTransactionManager(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            journal.add(BEGIN);
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            journal.add(COMMIT);
        }

        @Override
        public void rollback(TransactionStatus status) {
            journal.add(ROLLBACK);
        }
    }
}

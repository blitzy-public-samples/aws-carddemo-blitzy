package com.carddemo.account.messaging;

import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.domain.PostedTransactionService;
import com.carddemo.account.domain.PostedTransactionService.AccountRowMissingException;
import com.carddemo.account.repository.ProcessedEventRepository;
import com.carddemo.events.TransactionPosted;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour of {@link TransactionPostedConsumer}, the listener that carries a posted amount into the
 * account record this service owns.
 *
 * <p>ADDITIVE IN FULL. {@code app/cbl/CBTRN02C.cbl:L545-L560} adds the amount to the account record
 * itself, because {@code ACCTDAT} was one dataset with one writer. Three services hold parts of that
 * record here and may not call one another, so the amount travels as an event and a listener applies
 * it. Nothing in the source corresponds to this class.
 *
 * <p>Five properties are held here, and arithmetic is not among them:
 * {@code domain/PostedTransactionServiceTest} owns the arithmetic against a real schema.
 *
 * <ul>
 *   <li>The marker is claimed before any effect runs, so a delivery another one already took writes
 *       nothing. The claim is one statement, so two deliveries of one event cannot both pass it.</li>
 *   <li>A repeat delivery still acknowledges. Leaving the offset uncommitted on a duplicate would
 *       have the container redeliver a record that can never make progress.</li>
 *   <li>The acknowledgement follows the transaction the effects ran in, and never happens on a
 *       failure. An uncommitted offset is what has the container retry and then dead-letter the
 *       record rather than lose the amount.</li>
 *   <li>A key that names another account is refused before the claim, because Kafka orders records
 *       within a partition and the key selects the partition. Two postings applied out of order
 *       reach a different balance.</li>
 *   <li>Every outcome is metered, and a duplicate is counted apart from an applied posting. The two
 *       are indistinguishable on a consumed count and mean opposite things.</li>
 * </ul>
 *
 * <p>Every test runs against mocked collaborators and an immediate transaction template, so
 * {@code mvn test} needs no database and no broker.
 *
 * <p>Event paths: {@code card-platform/docs/event-flow.md}.
 */
@DisplayName("TransactionPostedConsumer, the posted-amount listener of the account service")
class TransactionPostedConsumerTest {

    /** The account every event in this class names, eleven digits with leading zeros. */
    private static final String ACCOUNT_ID = "00000000027";

    /** An account no event in this class names, used as a mismatched key. */
    private static final String OTHER_ACCOUNT_ID = "00000000030";

    /** The transaction every event in this class names, sixteen characters. */
    private static final String TRANSACTION_ID = "0000001000000042";

    /** The topic every delivery in this class arrives on. */
    private static final String TOPIC = "transaction.posted";

    /** The amount every event in this class carries. */
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /** The balance the ledger reported after its own posting. */
    private static final BigDecimal NEW_BALANCE = new BigDecimal("384.00");

    /**
     * The posting timestamp, twenty-six characters.
     *
     * <p>Two significant fractional digits then four literal zeros, which is what
     * {@code app/cbl/CBTRN02C.cbl:L701} writes and what
     * {@link TransactionPosted#POSTED_AT_PATTERN} accepts.
     */
    private static final String POSTED_AT = "2026-08-07-05.29.01.530000";

    /** The masked card number: twelve mask characters then four digits. */
    private static final String MASKED_CARD_NUMBER = "************1516";

    /** What {@code claimEvent} answers when this delivery took the claim. */
    private static final int CLAIMED = 1;

    /** What {@code claimEvent} answers when the marker was already present. */
    private static final int ALREADY_CLAIMED = 0;

    /** Applies the arithmetic. Mocked, because this class asserts ordering and not arithmetic. */
    private PostedTransactionService postedTransactionService;

    /** The marker store, whose answer decides whether this delivery applies anything. */
    private ProcessedEventRepository processedEvents;

    /** The offset commit the listener invokes once its transaction has committed. */
    private Acknowledgment acknowledgment;

    /** The registry the meters register against, read back for the counts. */
    private MeterRegistry registry;

    /** The meters the listener records through. */
    private AccountMeters meters;

    /** The listener under test. */
    private TransactionPostedConsumer consumer;

    @BeforeEach
    void buildListener() {
        postedTransactionService = Mockito.mock(PostedTransactionService.class);
        processedEvents = Mockito.mock(ProcessedEventRepository.class);
        acknowledgment = Mockito.mock(Acknowledgment.class);
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().accountMeters(registry);
        consumer = new TransactionPostedConsumer(postedTransactionService, processedEvents,
                immediateTransactions(), meters);
    }

    @Test
    @DisplayName("a first delivery claims the marker, applies the amount, then acknowledges")
    void aFirstDeliveryClaimsAppliesThenAcknowledges() {
        when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIMED);
        TransactionPosted event = postedEvent();

        consumer.onTransactionPosted(event, acknowledgment, TOPIC, ACCOUNT_ID);

        InOrder order = inOrder(processedEvents, postedTransactionService, acknowledgment);
        order.verify(processedEvents).claimEvent(eq(event.eventId()), any(Instant.class), eq(TOPIC));
        order.verify(postedTransactionService)
                .applyPostedAmount(ACCOUNT_ID, event.amount(), TRANSACTION_ID);
        order.verify(acknowledgment).acknowledge();
        assertThat(counter("carddemo.account.events.consumed"))
                .as("deliveries read from the topic")
                .isEqualTo(1.0D);
        assertThat(counter("carddemo.account.posting.applied"))
                .as("postings applied to the account record")
                .isEqualTo(1.0D);
        assertThat(counter("carddemo.account.posting.duplicates.skipped"))
                .as("a first delivery counted as a duplicate")
                .isZero();
        assertThat(timerCount("carddemo.account.posting.latency"))
                .as("deliveries timed")
                .isEqualTo(1L);
    }

    /**
     * A rollback leaves the applied counter unmoved and counts one failure instead.
     *
     * <p>Both outcome counters follow the template call, which is what commits, so reaching them
     * means the claim, the account row and the outbox row all committed. Raising them inside the
     * transaction, beside the work they describe, would let a rollback undo the account row and keep
     * the count, reporting a posting the store never kept.
     */
    @Test
    @DisplayName("a rolled-back posting counts no applied posting and one failure")
    void aRolledBackPostingCountsNoAppliedPostingAndOneFailure() {
        when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIMED);
        Mockito.doThrow(new DataIntegrityViolationException("the account row refused the update"))
                .when(postedTransactionService)
                .applyPostedAmount(anyString(), any(), anyString());

        assertThatThrownBy(() -> consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC,
                ACCOUNT_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(counter("carddemo.account.posting.applied"))
                .as("a posting the store never kept is not counted")
                .isZero();
        assertThat(counter("carddemo.account.posting.duplicates.skipped"))
                .as("a rollback is no duplicate")
                .isZero();
        assertThat(registry.get("carddemo.account.transaction.failures")
                        .tag("operation", "posting").counter().count())
                .as("the attempt is counted as a failure instead")
                .isEqualTo(1.0D);
        assertThat(counter("carddemo.account.events.consumed"))
                .as("the delivery still arrived")
                .isEqualTo(1.0D);
        assertThat(timerCount("carddemo.account.posting.latency"))
                .as("the latency of the failed delivery, recorded in a finally")
                .isEqualTo(1L);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("the amount reaches the account service and the event's own balance never does")
    void theAmountReachesTheAccountServiceAndTheEventsBalanceNeverDoes() {
        when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIMED);
        TransactionPosted event = postedEvent();

        consumer.onTransactionPosted(event, acknowledgment, TOPIC, ACCOUNT_ID);

        verify(postedTransactionService).applyPostedAmount(ACCOUNT_ID, AMOUNT, TRANSACTION_ID);
        verify(postedTransactionService, never())
                .applyPostedAmount(anyString(), eq(NEW_BALANCE), anyString());
    }

    @Test
    @DisplayName("a repeat delivery applies nothing, counts a duplicate, and still acknowledges")
    void aRepeatDeliveryAppliesNothingAndStillAcknowledges() {
        when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(ALREADY_CLAIMED);

        consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC, ACCOUNT_ID);

        verifyNoInteractions(postedTransactionService);
        verify(acknowledgment).acknowledge();
        assertThat(counter("carddemo.account.posting.duplicates.skipped"))
                .as("duplicate deliveries the marker suppressed")
                .isEqualTo(1.0D);
        assertThat(counter("carddemo.account.posting.applied"))
                .as("postings applied by a repeat delivery")
                .isZero();
        assertThat(counter("carddemo.account.events.consumed"))
                .as("a duplicate is still a delivery this service read")
                .isEqualTo(1.0D);
    }

    @Test
    @DisplayName("a failure inside the transaction is rethrown, counted, and never acknowledged")
    void aFailureIsRethrownAndNeverAcknowledged() {
        when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIMED);
        AccountRowMissingException absent = new AccountRowMissingException();
        when(postedTransactionService.applyPostedAmount(anyString(), any(BigDecimal.class),
                anyString())).thenThrow(absent);

        assertThatThrownBy(() ->
                consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC, ACCOUNT_ID))
                .as("answer to a posting whose account row is absent")
                .isSameAs(absent);

        verify(acknowledgment, never()).acknowledge();
        assertThat(counter("carddemo.account.transaction.failures",
                ObservabilityConfig.POSTING_OPERATION))
                .as("failures counted against the posting operation")
                .isEqualTo(1.0D);
        assertThat(counter("carddemo.account.posting.applied"))
                .as("a failed posting counted as an applied one")
                .isZero();
        assertThat(timerCount("carddemo.account.posting.latency"))
                .as("a failed delivery is timed as well as a committed one")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a key naming another account is refused before the marker is claimed")
    void aKeyNamingAnotherAccountIsRefusedBeforeTheClaim() {
        assertThatThrownBy(() ->
                consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC,
                        OTHER_ACCOUNT_ID))
                .as("answer to a delivery whose key names another aggregate")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition");

        verifyNoInteractions(processedEvents, postedTransactionService);
        verify(acknowledgment, never()).acknowledge();
        assertThat(counter("carddemo.account.events.consumed"))
                .as("the delivery reached this service, so it is counted as read")
                .isEqualTo(1.0d);
        assertThat(counter("carddemo.account.transaction.failures",
                ObservabilityConfig.POSTING_OPERATION))
                .as("a refusal is a failure of this listener, and it travels to the dead-letter "
                        + "topic; counting it nowhere left that record invisible")
                .isEqualTo(1.0d);
        assertThat(timerCount("carddemo.account.posting.latency"))
                .as("the refusal is timed like every other delivery")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("an unkeyed delivery is refused, because nothing orders it against its account")
    void anUnkeyedDeliveryIsRefused() {
        assertThatThrownBy(() ->
                consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC, null))
                .as("answer to a delivery carrying no key")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no message key");

        verifyNoInteractions(processedEvents, postedTransactionService);
        verify(acknowledgment, never()).acknowledge();
        assertThat(counter("carddemo.account.events.consumed"))
                .as("an unkeyed delivery is still a delivery this service read")
                .isEqualTo(1.0d);
        assertThat(counter("carddemo.account.transaction.failures",
                ObservabilityConfig.POSTING_OPERATION))
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("neither refusal message repeats the key or the aggregate it named")
    void neitherRefusalMessageRepeatsTheKey() {
        assertThatThrownBy(() ->
                consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC,
                        OTHER_ACCOUNT_ID))
                .as("a refusal message must not put a producer-supplied key in a log line")
                .hasMessageNotContaining(OTHER_ACCOUNT_ID)
                .hasMessageNotContaining(ACCOUNT_ID);
    }

    @Test
    @DisplayName("one listener method reads one topic, and both names come from configuration")
    void oneListenerMethodReadsOneTopicFromConfiguration() {
        Method[] listeners = Arrays.stream(TransactionPostedConsumer.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(KafkaListener.class))
                .toArray(Method[]::new);

        assertThat(listeners)
                .as("a second listener on one class shares its offsets with the first")
                .hasSize(1);
        KafkaListener listener = listeners[0].getAnnotation(KafkaListener.class);
        assertThat(listener.topics())
                .as("topic the listener binds")
                .containsExactly("${carddemo.kafka.topics.transaction-posted}");
        assertThat(listener.groupId())
                .as("group the listener joins, which must not be a literal")
                .isEqualTo("${carddemo.kafka.groups.transaction-posted}");
    }

    /**
     * Builds one version-1 event for {@link #ACCOUNT_ID} carrying {@link #AMOUNT}.
     *
     * <p>{@code TransactionPosted.forAccount} supplies a fresh event identifier and the current
     * moment, so two calls describe two deliveries rather than one.
     *
     * @return the event this class delivers
     */
    private static TransactionPosted postedEvent() {
        return TransactionPosted.forAccount(ACCOUNT_ID, TRANSACTION_ID, NEW_BALANCE, POSTED_AT,
                AMOUNT, MASKED_CARD_NUMBER);
    }

    /** Runs the callback on the calling thread, so no transaction manager is needed. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus(true));
            }
        };
    }

    /**
     * Reads one untagged counter back from the registry.
     *
     * @param name the meter name
     * @return the count the meter carries
     */
    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    /**
     * Reads one counter carrying an operation tag back from the registry.
     *
     * @param name      the meter name
     * @param operation the operation tag value
     * @return the count the meter carries
     */
    private double counter(String name, String operation) {
        return registry.get(name).tag(ObservabilityConfig.OPERATION_TAG, operation)
                .counter().count();
    }

    /**
     * Reads how many recordings one timer holds.
     *
     * @param name the meter name
     * @return the recording count
     */
    private long timerCount(String name) {
        return registry.get(name).timer().count();
    }
}

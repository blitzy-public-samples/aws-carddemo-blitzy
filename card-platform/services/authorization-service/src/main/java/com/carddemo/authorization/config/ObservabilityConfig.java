package com.carddemo.authorization.config;

import com.carddemo.authorization.messaging.AccountStateChanged;
import com.carddemo.authorization.messaging.CardUpdated;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;

import io.micrometer.core.instrument.Counter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.time.Duration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the meters the authorization service reports, and the one tag each of them carries.
 *
 * <p>No CardDemo program declares a meter and none times its own work. The batch posting
 * program counts with two working-storage fields, {@code WS-TRANSACTION-COUNT} and
 * {@code WS-REJECT-COUNT} at {@code app/cbl/CBTRN02C.cbl:L184-L186}.
 *
 * <p>{@link #EVENTS_WRITTEN_COUNTER} is the produce-side events family. Its ancestor is the
 * transaction count of {@code app/cbl/CBTRN02C.cbl:L206}, printed at
 * {@code app/cbl/CBTRN02C.cbl:L227}. A decided authorization call writes exactly one event, and the
 * two series separate an approval from a decline. All four reject reasons raise the decline series,
 * reason 0100 included, because every decided outcome publishes one event.
 *
 * <p>{@link #EVENTS_CONSUMED_COUNTER}, {@link #DUPLICATES_SKIPPED_COUNTER} and
 * {@link #REPLICA_PROCESSING_TIMER} are the consume side, one series each per replica stream.
 * {@code messaging/AccountStateChangedConsumer} and {@code messaging/CardUpdatedConsumer} refresh the
 * rows every decline rule reads, and they used to report nothing at all: a stream that had stopped
 * arriving, a stream arriving entirely as duplicates and a stream taking seconds per delivery were one
 * reading — silence — and the first sign of any of them was a decision taken against a stale replica.
 * A delivery this service gives up on lands on {@link #FAILURES_COUNTER} under
 * {@link #REPLICA_STAGE}, so a spent record and a slow one are separate readings.
 *
 * <p>{@link #DECISION_TIMER} is the latency family, one timer over one decision. The source times
 * nothing, so this family has no ancestor.
 *
 * <p>The failure family splits in two. A decline lands on {@link #DECISIONS_COUNTER} under its
 * four-digit reject code, whose ancestor is the reject count of {@code app/cbl/CBTRN02C.cbl:L214},
 * printed at {@code app/cbl/CBTRN02C.cbl:L228}. A decline is expected traffic, and
 * {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the batch job with return code 4 once that count
 * passes zero. No decline reaches {@link #FAILURES_COUNTER}.
 *
 * <p>{@link #FAILURES_COUNTER} pre-registers a {@link #PERSIST_STAGE} series, a
 * {@link #PUBLISH_STAGE} series and a {@link #REPLICA_STAGE} series at zero, and each of the three
 * has a recorder. {@code domain/AuthorizationService} counts the persist stage where the decision
 * and its outbox row cannot commit, and the replica stage where the rows it resolved were observed
 * too long ago to authorize against. {@code outbox/OutboxRelay} counts the publish stage where a row
 * cannot reach the broker. {@code config/KafkaConsumerConfig} counts the replica stage again where a
 * replica delivery is spent and routes to the dead-letter topic. The source answer to any of them is
 * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}, four statements that display
 * one message, move 999 into an abend code and call {@code CEE3ABD}. Neither that routine nor the
 * file-status formatter at {@code app/cbl/CBTRN02C.cbl:L714-L727} is reproduced, because a counted
 * failure and a retried or dead-lettered delivery replace a terminated address space.
 *
 * <p>{@code domain/AuthorizationService} records against {@link #EVENTS_WRITTEN_COUNTER},
 * {@link #DECISION_TIMER} and {@link #DECISIONS_COUNTER} under these names, so one meter serves the
 * registration here and the recording there.
 *
 * <p>Every meter registers while the context builds, so a scrape taken before the first request
 * lists each series at zero. Each tag value set is closed and small: five outcomes, two event types,
 * three stages, one service name.
 *
 * <p>No meter name and no tag holds an account identifier, a transaction identifier or an event
 * identifier. None holds a card number, and none holds the three-digit verification value that
 * {@code app/cpy/CVACT02Y.cpy:L7} declares.
 *
 * <p>The actuator surface, the Prometheus export and the structured log format live in
 * {@code src/main/resources/application.yml}. This class configures none of the three, and this
 * module ships no Logback configuration file.
 *
 */
@Configuration
public class ObservabilityConfig {

    /** Counter carrying one authorization outcome per call. */
    public static final String DECISIONS_COUNTER = "carddemo.authorization.decisions";

    /** Tag key that separates an approval from a reject code on {@link #DECISIONS_COUNTER}. */
    public static final String OUTCOME_TAG = "outcome";

    /** The {@link #OUTCOME_TAG} value of an approved authorization. */
    public static final String APPROVED_OUTCOME = "approved";

    /** Counter carrying the events written to the outbox, one per authorization call. */
    public static final String EVENTS_WRITTEN_COUNTER = "carddemo.authorization.events.written";

    /** Tag key that names the event type on {@link #EVENTS_WRITTEN_COUNTER}. */
    public static final String EVENT_TYPE_TAG = "eventType";

    /**
     * Counter carrying one outbox row the broker acknowledged.
     *
     * <p>{@link #EVENTS_WRITTEN_COUNTER} counts a row WRITTEN inside a decision's transaction, and
     * this one counts a row the relay PUBLISHED and the broker acknowledged. A relay that cannot reach
     * the broker leaves the first rising and this one flat, which is the reading that separates a
     * stalled relay from a service nobody is calling.
     */
    public static final String EVENTS_PUBLISHED_COUNTER =
            "carddemo.authorization.events.published";

    /** Counter carrying one outbox row given up on after its delivery attempts were spent. */
    public static final String OUTBOX_ABANDONED_COUNTER = "carddemo.authorization.outbox.abandoned";

    /** Counter carrying the terminal diagnostic of an abandoned row, by what became of it. */
    public static final String DEAD_LETTERS_COUNTER = "carddemo.authorization.dead.letters";

    /** Tag key that names what became of a terminal diagnostic on {@link #DEAD_LETTERS_COUNTER}. */
    public static final String OUTCOME_OF_DIAGNOSTIC_TAG = "outcome";

    /** The {@link #OUTCOME_OF_DIAGNOSTIC_TAG} value of a diagnostic the broker acknowledged. */
    public static final String DIAGNOSTIC_PUBLISHED = "published";

    /** The {@link #OUTCOME_OF_DIAGNOSTIC_TAG} value of a diagnostic the broker refused. */
    public static final String DIAGNOSTIC_FAILED = "failed";

    /** Counter carrying one replica delivery read from a topic, before it is applied. */
    public static final String EVENTS_CONSUMED_COUNTER = "carddemo.authorization.events.consumed";

    /** Counter carrying one replica delivery a processed-event marker made a repeat of. */
    public static final String DUPLICATES_SKIPPED_COUNTER =
            "carddemo.authorization.duplicates.skipped";

    /** Timer over one replica delivery, whether it applied a change, skipped one, or failed. */
    public static final String REPLICA_PROCESSING_TIMER =
            "carddemo.authorization.processing.latency";

    /** Timer over one decision, from request entry to commit. */
    public static final String DECISION_TIMER = "carddemo.authorization.decision.duration";

    /** Counter reserved for an infrastructure fault. No current code records it. */
    public static final String FAILURES_COUNTER = "carddemo.authorization.failures";

    /** Tag key that names the stage which raised a fault on {@link #FAILURES_COUNTER}. */
    public static final String STAGE_TAG = "stage";

    /** The {@link #STAGE_TAG} value reserved for a decision and event row that could not commit. */
    public static final String PERSIST_STAGE = "persist";

    /** The {@link #STAGE_TAG} value reserved for an outbox row the relay could not publish. */
    public static final String PUBLISH_STAGE = "publish";

    /**
     * The {@link #STAGE_TAG} value of a call refused because the replica rows it resolved had not
     * been observed recently enough to authorize against.
     *
     * <p>This stage is a fault and not a decline, and the distinction is the reason it is here. The
     * card was valid and the account was valid; this service was the component unable to answer,
     * because {@code card_xref} and {@code account_credit_snapshot} are replicas whose state-change
     * events had stopped arriving. Counting the refusal under a reject reason would attribute a
     * service fault to a cardholder.
     */
    public static final String REPLICA_STAGE = "replica";

    /**
     * The {@link #STAGE_TAG} value of a call refused because the caller reaches neither the account nor
     * the card the request resolved to.
     *
     * <p>A refusal here is neither a decline nor a fault of this service: nothing was decided, no
     * transaction identifier was drawn and no event was written. It is counted as a failure stage
     * because the alternative is counting it nowhere, and a credential probing accounts it does not own
     * would then be invisible.
     */
    public static final String ENTITLEMENT_STAGE = "entitlement";

    /** Tag key that names this service on every meter it reports. */
    public static final String SERVICE_TAG = "service";

    /**
     * The two event types the replica listeners of this service read, and the whole of the
     * {@link #EVENT_TYPE_TAG} value set on the three consume-side meters.
     *
     * <p>The set is closed here rather than taken from a delivery. A tag value read from a payload
     * would let one malformed producer register an unbounded number of series, which is how a metrics
     * endpoint becomes the thing that fills a collector.
     */
    public static final List<String> REPLICA_EVENT_TYPES =
            List.of(AccountStateChanged.EVENT_TYPE, CardUpdated.EVENT_TYPE);

    /**
     * Registers all sixteen series of the seven meters against each registry Spring Boot builds.
     *
     * <p>Spring Boot applies a {@link MeterBinder} bean after the common tag of
     * {@link #authorizationCommonTags(String)}, so each series below carries that tag. A
     * registration names one meter, and a later recording under the same name, tag key and tag value
     * reaches that same meter.
     *
     * @return the binder that registers the meter set of this service
     */
    @Bean
    public MeterBinder authorizationMeters() {
        return registry -> {
            Objects.requireNonNull(registry, "registry");
            registerOutcomes(registry);
            registerWrittenEvents(registry);
            registerPublishedEvents(registry);
            registerTerminalOutboxSeries(registry);
            registerDecisionTimer(registry);
            registerFailureStages(registry);
        };
    }

    /**
     * Builds the recording surface both replica listeners hold.
     *
     * <p>Every series is resolved once here, at start-up, so a scrape taken before the first delivery
     * lists each of them at zero and no delivery decides which series exist. That is also what keeps
     * the tag value set closed: {@link ReplicaMeters} answers only for the two types
     * {@link #REPLICA_EVENT_TYPES} names and refuses anything else.
     *
     * @param registry the registry each series registers against
     * @return the surface {@code messaging/AccountStateChangedConsumer} and
     *         {@code messaging/CardUpdatedConsumer} record through
     */
    @Bean
    public ReplicaMeters replicaMeters(MeterRegistry registry) {
        return new ReplicaMeters(Objects.requireNonNull(registry, "registry"));
    }

    /**
     * Adds the one common tag every meter of this service carries.
     *
     * <p>Six services scrape into one collector, and this tag is what separates their series. The
     * value is the bound {@code spring.application.name} of
     * {@code src/main/resources/application.yml}, which holds one name per service and never a value
     * per request.
     *
     * @param applicationName the bound {@code spring.application.name}
     * @return the customizer that installs the common tag
     * @throws IllegalStateException when the bound name is blank, which would leave every series of
     *                               this service unattributed
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> authorizationCommonTags(
            @Value("${spring.application.name:authorization-service}") String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException("spring.application.name must hold a value: it is the "
                    + "tag that separates this service's meters from the other five");
        }
        return registry -> registry.config().commonTags(SERVICE_TAG, applicationName);
    }

    /**
     * Registers the five outcome series: one approval and one per reject code.
     *
     * <p>{@link DeclineReason} holds the four reasons of {@code app/cbl/CBTRN02C.cbl:L385-L420} and
     * no others, so this tag value set closes at five.
     *
     * @param registry the registry each series registers against
     */
    private static void registerOutcomes(MeterRegistry registry) {
        registerOutcome(registry, APPROVED_OUTCOME);
        for (DeclineReason reason : DeclineReason.values()) {
            registerOutcome(registry, reason.code());
        }
    }

    /**
     * Registers one outcome series.
     *
     * @param registry the registry the series registers against
     * @param outcome  the {@link #OUTCOME_TAG} value this series carries
     */
    private static void registerOutcome(MeterRegistry registry, String outcome) {
        decisionCounter(registry, outcome);
    }

    /**
     * Answers one outcome series of the decision counter, registering it if it is absent.
     *
     * <p>The outcome is not checked against a list. A reject reason this service has not seen before
     * still needs counting, and refusing it here would lose the outcome rather than report it.
     *
     * @param registry the registry holding or receiving the series
     * @param outcome  the approval marker, or the reject code
     * @return the counter of calls that reached that outcome
     */
    public static Counter decisionCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(DECISIONS_COUNTER)
                .tag(OUTCOME_TAG, outcome)
                .description("Authorization outcomes, one per call, tagged approved or by reject "
                        + "code")
                .register(registry);
    }

    /**
     * Registers one written-event series per event type this service produces.
     *
     * <p>An approval writes {@link TransactionAuthorized} and a decline writes
     * {@link TransactionDeclined}.
     *
     * @param registry the registry each series registers against
     */
    private static void registerWrittenEvents(MeterRegistry registry) {
        List<String> eventTypes =
                List.of(TransactionAuthorized.EVENT_TYPE, TransactionDeclined.EVENT_TYPE);
        for (String eventType : eventTypes) {
            eventsWrittenCounter(registry, eventType);
        }
    }

    /**
     * Answers one written-event series, registering it with its description if it is absent.
     *
     * @param registry  the registry holding or receiving the series
     * @param eventType the event type this series counts
     * @return the counter of events of that type written to the outbox
     */
    public static Counter eventsWrittenCounter(MeterRegistry registry, String eventType) {
        return Counter.builder(EVENTS_WRITTEN_COUNTER)
                .tag(EVENT_TYPE_TAG, eventType)
                .description("Events written to the outbox, one per authorization call")
                .register(registry);
    }

    /**
     * Registers the untagged publish-success series, so a reader can compare rows written against rows
     * the broker acknowledged from start-up.
     *
     * @param registry the registry the series registers against
     */
    private static void registerPublishedEvents(MeterRegistry registry) {
        eventsPublishedCounter(registry);
    }

    /**
     * Answers the publish-success series, registering it with its description if it is absent.
     *
     * <p>Every reader of this series comes through here, so the name and the description are written
     * once. A caller that built the series itself would register it without a description whenever it
     * ran before the binder, and which of the two ran first is not something either one controls.
     *
     * @param registry the registry holding or receiving the series
     * @return the counter of rows the broker acknowledged
     */
    public static Counter eventsPublishedCounter(MeterRegistry registry) {
        return Counter.builder(EVENTS_PUBLISHED_COUNTER)
                .description("Outbox rows the broker acknowledged, counted after the sweep that sent "
                        + "them committed")
                .register(registry);
    }

    /**
     * Registers the two terminal outbox series: rows given up on, and what became of the diagnostic
     * naming each one.
     *
     * <p>An abandoned row is the one outcome nothing else reports. It is not a retry, which the
     * publish stage of {@link #FAILURES_COUNTER} already counts, and it is not recoverable by another
     * sweep, so a reader who watches only the failure series sees a number that stops rising and
     * cannot tell whether the rows were sent or dropped.
     *
     * @param registry the registry each series registers against
     */
    private static void registerTerminalOutboxSeries(MeterRegistry registry) {
        outboxAbandonedCounter(registry);
        for (String outcome : List.of(DIAGNOSTIC_PUBLISHED, DIAGNOSTIC_FAILED)) {
            deadLetterCounter(registry, outcome);
        }
    }

    /**
     * Answers the abandoned-row series, registering it with its description if it is absent.
     *
     * @param registry the registry holding or receiving the series
     * @return the counter of rows this service gave up on
     */
    public static Counter outboxAbandonedCounter(MeterRegistry registry) {
        return Counter.builder(OUTBOX_ABANDONED_COUNTER)
                .description("Outbox rows given up on after their delivery attempts were spent")
                .register(registry);
    }

    /**
     * Answers one outcome series of the terminal diagnostic, registering it if it is absent.
     *
     * @param registry the registry holding or receiving the series
     * @param outcome  {@link #DIAGNOSTIC_PUBLISHED} or {@link #DIAGNOSTIC_FAILED}
     * @return the counter of diagnostics that reached that outcome
     * @throws IllegalArgumentException when the outcome is not one of the two
     */
    public static Counter deadLetterCounter(MeterRegistry registry, String outcome) {
        if (!DIAGNOSTIC_PUBLISHED.equals(outcome) && !DIAGNOSTIC_FAILED.equals(outcome)) {
            throw new IllegalArgumentException("unknown diagnostic outcome: " + outcome);
        }
        return Counter.builder(DEAD_LETTERS_COUNTER)
                .tag(OUTCOME_OF_DIAGNOSTIC_TAG, outcome)
                .description("Terminal diagnostics naming an abandoned outbox row, by what "
                        + "became of the diagnostic")
                .register(registry);
    }

    /**
     * Registers the untagged decision timer, so its count and its total appear from start-up.
     *
     * @param registry the registry the timer registers against
     */
    private static void registerDecisionTimer(MeterRegistry registry) {
        Timer.builder(DECISION_TIMER)
                .description("Wall time of one authorization decision, from request entry to commit")
                .register(registry);
    }

    /**
     * Registers one failure series per stage that can raise an infrastructure fault. Each series
     * registers at zero and no current code records against it.
     *
     * <p>Each series is registered eagerly so it reads zero before the first fault, which is what
     * lets a dashboard tell "no faults" apart from "no such series". Every one of the four has a
     * recording site: the persist stage in {@code AuthorizationService.authorize}, the publish stage
     * in {@code OutboxRelay.publishPendingEvents}, the replica stage in
     * {@code AuthorizationService.requireFreshReplicaData}, and the entitlement stage in
     * {@code AuthorizationService.authorize} where a caller reaching neither resolved subject is
     * refused.
     *
     * @param registry the registry each series registers against
     */
    private static void registerFailureStages(MeterRegistry registry) {
        for (String stage :
                List.of(PERSIST_STAGE, PUBLISH_STAGE, REPLICA_STAGE, ENTITLEMENT_STAGE)) {
            failureCounter(registry, stage);
        }
    }

    /**
     * Answers one stage series of the failure counter, registering it if it is absent.
     *
     * @param registry the registry holding or receiving the series
     * @param stage    the stage that would raise the fault
     * @return the counter of faults raised by that stage
     */
    public static Counter failureCounter(MeterRegistry registry, String stage) {
        return Counter.builder(FAILURES_COUNTER)
                .tag(STAGE_TAG, stage)
                .description("Infrastructure faults, tagged by the stage that raised one. A "
                        + "decline is not a fault")
                .register(registry);
    }

    /**
     * The three consume-side meters of this service, one series of each per replica stream.
     *
     * <p>Both replica listeners hold one of these rather than a registry, so no listener can name a
     * meter or invent a tag value. The series are resolved in the constructor, which is what makes
     * every one of them readable at zero before the first delivery: a dashboard can then tell "no
     * deliveries" apart from "no such series", and a stream that has stopped arriving is visible as a
     * count that stopped rising rather than as a series that never appeared.
     *
     * <p>No meter and no tag here holds an account identifier, a card number, an event identifier or
     * the three-digit verification value {@code app/cpy/CVACT02Y.cpy:L7} declares. The one tag is the
     * event type, and its two values are compile-time constants.
     */
    public static final class ReplicaMeters {

        /** One consumed-delivery counter per replica stream, keyed by event type. */
        private final Map<String, Counter> consumed;

        /** One duplicate-delivery counter per replica stream, keyed by event type. */
        private final Map<String, Counter> duplicates;

        /** One delivery timer per replica stream, keyed by event type. */
        private final Map<String, Timer> latency;

        /**
         * Resolves all three series of both replica streams.
         *
         * @param registry the registry each series registers against
         */
        ReplicaMeters(MeterRegistry registry) {
            Map<String, Counter> consumedByType = new LinkedHashMap<>();
            Map<String, Counter> duplicatesByType = new LinkedHashMap<>();
            Map<String, Timer> latencyByType = new LinkedHashMap<>();

            for (String eventType : REPLICA_EVENT_TYPES) {
                consumedByType.put(eventType, Counter.builder(EVENTS_CONSUMED_COUNTER)
                        .tag(EVENT_TYPE_TAG, eventType)
                        .description("Replica deliveries read from a topic, counted before they are "
                                + "applied")
                        .register(registry));
                duplicatesByType.put(eventType, Counter.builder(DUPLICATES_SKIPPED_COUNTER)
                        .tag(EVENT_TYPE_TAG, eventType)
                        .description("Replica deliveries a processed-event marker made a repeat of, "
                                + "so they changed nothing")
                        .register(registry));
                latencyByType.put(eventType, Timer.builder(REPLICA_PROCESSING_TIMER)
                        .tag(EVENT_TYPE_TAG, eventType)
                        .description("Wall time of one replica delivery, whether it applied a "
                                + "change, skipped one, or failed")
                        .register(registry));
            }

            this.consumed = Map.copyOf(consumedByType);
            this.duplicates = Map.copyOf(duplicatesByType);
            this.latency = Map.copyOf(latencyByType);
        }

        /**
         * Records one delivery read from a replica stream.
         *
         * <p>Counted as the delivery arrives, ahead of every check the listener makes, so the count
         * reports what the broker handed over rather than what the listener accepted. A delivery
         * refused for its key, its payload shape or a missing row is therefore still counted here and
         * counted again as a fault where it is spent.
         *
         * @param eventType one of {@link #REPLICA_EVENT_TYPES}
         * @throws IllegalArgumentException when the type is not one of those two
         */
        public void recordEventConsumed(String eventType) {
            series(consumed, eventType).increment();
        }

        /**
         * Records one delivery whose event already carried a marker, so it applied nothing.
         *
         * @param eventType one of {@link #REPLICA_EVENT_TYPES}
         * @throws IllegalArgumentException when the type is not one of those two
         */
        public void recordDuplicateSkipped(String eventType) {
            series(duplicates, eventType).increment();
        }

        /**
         * Records how long one delivery took, whichever way it ended.
         *
         * @param eventType one of {@link #REPLICA_EVENT_TYPES}
         * @param elapsed   the wall time of the delivery
         * @throws IllegalArgumentException when the type is not one of those two
         */
        public void recordProcessingLatency(String eventType, Duration elapsed) {
            series(latency, eventType).record(Objects.requireNonNull(elapsed, "elapsed"));
        }

        /**
         * Answers with the series of one stream, refusing a type this surface does not carry.
         *
         * @param <M>       the meter type
         * @param series    the series of one family, keyed by event type
         * @param eventType the type a listener named
         * @return the meter that type registers against
         * @throws IllegalArgumentException when the type is not one of {@link #REPLICA_EVENT_TYPES}
         */
        private static <M> M series(Map<String, M> series, String eventType) {
            M meter = series.get(eventType);
            if (meter == null) {
                throw new IllegalArgumentException("No replica meter carries the event type '"
                        + eventType + "'. The streams this service reads are " + REPLICA_EVENT_TYPES
                        + ".");
            }
            return meter;
        }
    }

}

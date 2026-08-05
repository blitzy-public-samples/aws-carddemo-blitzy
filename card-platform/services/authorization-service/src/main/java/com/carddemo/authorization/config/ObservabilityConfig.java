package com.carddemo.authorization.config;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.List;
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
 * <p>{@link #EVENTS_WRITTEN_COUNTER} is the events family. Its ancestor is the transaction count of
 * {@code app/cbl/CBTRN02C.cbl:L206}, printed at {@code app/cbl/CBTRN02C.cbl:L227}. One authorization
 * call writes one event, and the two series separate an approval from a decline. The family stays a
 * produce-side family even though the service does read two topics: {@code messaging/AccountStateChangedConsumer}
 * and {@code messaging/CardUpdatedConsumer} refresh replica rows rather than reach an authorization
 * decision, so neither one writes an event and neither registers a consumed-event series. A replica
 * delivery this service cannot apply lands on {@link #FAILURES_COUNTER} under {@link #REPLICA_STAGE}
 * instead.
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

    /** Tag key that names this service on every meter it reports. */
    public static final String SERVICE_TAG = "service";

    /**
     * Registers every series of the four meters against each registry Spring Boot builds.
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
            registerDecisionTimer(registry);
            registerFailureStages(registry);
        };
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
        Counter.builder(DECISIONS_COUNTER)
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
            Counter.builder(EVENTS_WRITTEN_COUNTER)
                    .tag(EVENT_TYPE_TAG, eventType)
                    .description("Events written to the outbox, one per authorization call")
                    .register(registry);
        }
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
     * lets a dashboard tell "no faults" apart from "no such series". Every one of the three has a
     * recording site: the persist stage in {@code AuthorizationService.authorize}, the publish stage
     * in {@code OutboxRelay.publishPendingEvents}, and the replica stage in
     * {@code AuthorizationService.requireFreshReplicaData}.
     *
     * @param registry the registry each series registers against
     */
    private static void registerFailureStages(MeterRegistry registry) {
        for (String stage : List.of(PERSIST_STAGE, PUBLISH_STAGE, REPLICA_STAGE)) {
            Counter.builder(FAILURES_COUNTER)
                    .tag(STAGE_TAG, stage)
                    .description("Infrastructure faults, tagged by the stage that raised one. A "
                            + "decline is not a fault")
                    .register(registry);
        }
    }
}

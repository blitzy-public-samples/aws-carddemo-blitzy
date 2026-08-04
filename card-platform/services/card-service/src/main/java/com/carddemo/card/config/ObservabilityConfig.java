package com.carddemo.card.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the six meters the card service measures its work through. This class records nothing;
 * each bean below is injected by name into the class that performs the work.
 *
 * <p>ADDITIVE. No COBOL program and no copybook declares a meter. The nearest source construct is
 * the job log. {@code app/cbl/CBTRN02C.cbl} holds 53 lines carrying {@code DISPLAY}, and it formats
 * a two-byte file status into four digits at {@code app/cbl/CBTRN02C.cbl:L714-L727}.
 *
 * <p>Four counters and two timers cover the three metric families. Applied updates and published
 * events carry throughput, the two timers carry processing latency, and one counter carries the
 * failure count. Each counter is a bean of its own, and the two timers arrive behind
 * {@link CardLatencyTimers}.
 *
 * <p>This service reads no topic and registers no listener, so no meter counts a consumed event.
 * {@link #METRIC_CARD_EVENTS_PUBLISHED} is the family it reports on.
 *
 * <p>A refused update and a failed update are separate counters. The batch posting program counts a
 * rejection as a normal outcome at {@code app/cbl/CBTRN02C.cbl:L230}, and
 * {@code app/cbl/COCRDUPC.cbl} sets the two conditions at two sites, {@code :L1511} and
 * {@code :L1491}.
 *
 * <p>No meter name and no tag holds a card number, an account identifier, a customer identifier, a
 * transaction identifier, an event identifier or a timestamp. A card number reaches a log in its
 * masked form alone, and the card verification value at {@code app/cpy/CVACT02Y.cpy:L7} reaches no
 * meter and no log. Masking the Primary Account Number (PAN) is additive: the card detail map
 * carried all sixteen digits at {@code app/bms/COCRDSL.bms:L99}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Card updates that committed. The one card mutation site of
     * {@code app/cbl/COCRDUPC.cbl} is the rewrite at {@code :L1478}, and {@code :L1488} is its
     * success branch.
     */
    public static final String METRIC_CARD_UPDATE_APPLIED = "carddemo.card.update.applied";

    /**
     * Card updates refused after a concurrent change. {@code app/cbl/COCRDUPC.cbl:L1511} sets that
     * condition inside paragraph {@code 9300-CHECK-CHANGE-IN-REC} at {@code :L1498-L1523}.
     */
    public static final String METRIC_CARD_UPDATE_CONFLICTS = "carddemo.card.update.conflicts";

    /** Card events published to the broker. ADDITIVE, with no card program ancestor. */
    public static final String METRIC_CARD_EVENTS_PUBLISHED = "carddemo.card.events.published";

    /**
     * Card update attempts that failed on infrastructure.
     * {@code app/cbl/COCRDUPC.cbl:L1491} sets that condition.
     */
    public static final String METRIC_CARD_FAILURES = "carddemo.card.failures";

    /** Wall time of one card update, from request entry to commit. */
    public static final String METRIC_CARD_UPDATE_LATENCY = "carddemo.card.update.latency";

    /** Wall time of one publish attempt made by the outbox relay. */
    public static final String METRIC_CARD_PUBLISH_LATENCY = "carddemo.card.publish.latency";

    /** Tag key every meter carries. Its value space holds one entry. */
    public static final String TAG_SERVICE = "service";

    /** Tag value, matching {@code spring.application.name} in {@code application.yml}. */
    public static final String SERVICE_TAG_VALUE = "card-service";

    /**
     * Counts card updates that committed. {@code domain/CardUpdateService.java} increments it once
     * per applied update.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the registered counter, named {@link #METRIC_CARD_UPDATE_APPLIED}
     */
    @Bean
    public Counter cardUpdatesAppliedCounter(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return Counter.builder(METRIC_CARD_UPDATE_APPLIED)
                .description("Card updates that committed")
                .register(registry);
    }

    /**
     * Counts card updates refused after a concurrent change, which the source reports with the text
     * at {@code app/cbl/COCRDUPC.cbl:L208}. {@code domain/CardUpdateService.java} increments it.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the registered counter, named {@link #METRIC_CARD_UPDATE_CONFLICTS}
     */
    @Bean
    public Counter cardUpdateConflictCounter(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return Counter.builder(METRIC_CARD_UPDATE_CONFLICTS)
                .description("Card updates refused after a concurrent change")
                .register(registry);
    }

    /**
     * Counts card events published to the broker. ADDITIVE. No card program counts a published
     * event, and the nearest relative is the transaction counter of the batch posting program,
     * incremented at {@code app/cbl/CBTRN02C.cbl:L206} and reported at {@code :L227}.
     * {@code outbox/OutboxRelay.java} increments this counter after a publish succeeds.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the registered counter, named {@link #METRIC_CARD_EVENTS_PUBLISHED}
     */
    @Bean
    public Counter cardEventsPublishedCounter(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return Counter.builder(METRIC_CARD_EVENTS_PUBLISHED)
                .description("Card events published to the broker")
                .register(registry);
    }

    /**
     * Counts card work that failed on infrastructure. This counter stays separate from
     * {@link #cardUpdateConflictCounter}, which holds a business outcome the source treats as
     * normal traffic at {@code app/cbl/CBTRN02C.cbl:L230}. Its source condition sits at
     * {@code app/cbl/COCRDUPC.cbl:L1491}, and the source answer was the abend routine at
     * {@code :L1531-L1537}.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the registered counter, named {@link #METRIC_CARD_FAILURES}
     */
    @Bean
    public Counter cardInfrastructureFailureCounter(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return Counter.builder(METRIC_CARD_FAILURES)
                .description("Card work that failed on infrastructure")
                .register(registry);
    }

    /**
     * Registers the two latency timers and hands them over as one bean.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the two timers of this service
     */
    @Bean
    public CardLatencyTimers cardLatencyTimers(MeterRegistry registry) {
        return new CardLatencyTimers(registry);
    }

    /**
     * The two latency timers of this service, {@link #METRIC_CARD_UPDATE_LATENCY} and
     * {@link #METRIC_CARD_PUBLISH_LATENCY}.
     *
     * <p>A timer arrives behind this holder and never as a bean of its own. Spring reads the
     * declared fields of every bean it creates. The Micrometer timer implementations extend a base
     * class carrying a field of an optional type this platform does not ship, so that read stops
     * start-up. Both fields below are declared as the {@link Timer} interface, which Spring reads
     * without loading an implementation. Neither timer carries a tag of its own.
     */
    public static final class CardLatencyTimers {

        private final Timer cardUpdate;

        private final Timer eventPublish;

        CardLatencyTimers(MeterRegistry registry) {
            Objects.requireNonNull(registry, "registry");
            this.cardUpdate = Timer.builder(METRIC_CARD_UPDATE_LATENCY)
                    .description("Wall time of one card update, from request entry to commit")
                    .register(registry);
            this.eventPublish = Timer.builder(METRIC_CARD_PUBLISH_LATENCY)
                    .description("Wall time of one publish attempt made by the outbox relay")
                    .register(registry);
        }

        /**
         * Times one card update, from request entry to commit.
         * {@code domain/CardUpdateService.java} records against it.
         *
         * @return the card update timer
         */
        public Timer cardUpdate() {
            return cardUpdate;
        }

        /**
         * Times one publish attempt made by the outbox relay. {@code outbox/OutboxRelay.java}
         * records against it, on a successful attempt and on a failed one alike.
         *
         * @return the publish attempt timer
         */
        public Timer eventPublish() {
            return eventPublish;
        }
    }

    /**
     * Adds the one common tag every meter of this service carries, including the meters Spring Boot
     * registers for the Java Virtual Machine and for the web surface. Spring Boot applies this
     * customizer while it post-processes the registry, so every meter above inherits the tag. The
     * fallback value matches {@code spring.application.name} in {@code application.yml}, so a
     * context that omits the property still tags its meters.
     *
     * @param applicationName the bound {@code spring.application.name}
     * @return the customizer that installs the {@link #TAG_SERVICE} tag
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> cardServiceCommonTags(
            @Value("${spring.application.name:card-service}") String applicationName) {

        String serviceName = (applicationName == null || applicationName.isBlank())
                ? SERVICE_TAG_VALUE
                : applicationName;

        return registry -> registry.config().commonTags(TAG_SERVICE, serviceName);
    }
}

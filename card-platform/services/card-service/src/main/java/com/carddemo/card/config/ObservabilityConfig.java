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
 * Registers the three meters the card service measures its work through. This class records nothing;
 * each bean below is injected by name into the class that performs the work.
 *
 * <p>No COBOL program and no copybook declares a meter. The nearest source construct is
 * the job log. {@code app/cbl/CBTRN02C.cbl} holds 53 lines carrying {@code DISPLAY}, and it formats
 * a two-byte file status into four digits at {@code app/cbl/CBTRN02C.cbl:L714-L727}.
 *
 * <p>Two counters and one timer cover the published-event, processing-latency, and failure
 * families. Each counter is a bean of its own, and the timer arrives behind
 * {@link CardLatencyTimers}.
 *
 * <p>This service reads no topic and registers no listener, so no meter counts a consumed event.
 * {@link #METRIC_CARD_EVENTS_PUBLISHED} is the family it reports on.
 *
 * <p>No meter name and no tag holds a card number, an account identifier, a customer identifier, a
 * transaction identifier, an event identifier or a timestamp. A card number reaches a log in its
 * masked form alone, and the card verification value at {@code app/cpy/CVACT02Y.cpy:L7} reaches no
 * meter and no log. Masking the Primary Account Number (PAN) is additive: the card detail map
 * carried all sixteen digits at {@code app/bms/COCRDSL.bms:L99}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
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

    /**
     * Card updates whose row disagreed with the {@code card_xref} replica about the account.
     *
     * <p>ADDITIVE, with no card program ancestor, because the source has no replica to disagree
     * with: {@code app/cbl/COCRDSLC.cbl} reads the cross-reference dataset itself, and
     * {@code app/cbl/COCRDUPC.cbl:L356} reads {@code *COPY CVACT03Y.} commented out.
     *
     * <p>This service holds both values, and it is the only service that does.
     * {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6} carries the account of the
     * card row and is what a published event keys on, while
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} carries the account the
     * authorization decision resolves the same card to at
     * {@code app/cbl/CBTRN02C.cbl:L383-L387}. A disagreement puts an event on one account's
     * partition while the decision for that card reads another, which loses the per-account
     * ordering this platform rests on. The counter is how that becomes visible instead of silent.
     */
    public static final String METRIC_CARD_XREF_DIVERGENCE =
            "carddemo.card.xref.divergence";

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
     * business refusal. Its source answer was the abend routine at
     * {@code app/cbl/COCRDUPC.cbl:L1531-L1537}.
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
     * Counts card updates whose row and cross-reference replica named different accounts.
     *
     * <p>{@code domain/CardUpdateService} increments it inside the writing transaction, after the
     * row is locked and before the event row is written, and it changes no outcome: the update
     * commits exactly as it would have. The source performs no such comparison, so refusing an
     * update here would answer a text no card program writes. Recording it is what transformation
     * rule T7 asks for, which is to reproduce behaviour and surface what looks wrong rather than
     * silently correct it.
     *
     * <p>The counter is expected to stay at zero. Both copies are loaded from
     * {@code app/data/ASCII/cardxref.txt}, and no operation this platform serves changes a
     * cross-reference row: the card update edits the embossed name, the expiry and the active status
     * alone, from {@code CCUP-NEW-CARDDATA} at {@code app/cbl/COCRDUPC.cbl:L307}, and none of those
     * three is a cross-reference column. A non-zero reading therefore means an out-of-band change,
     * which is exactly the case a demonstration cannot otherwise see.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the registered counter, named {@link #METRIC_CARD_XREF_DIVERGENCE}
     */
    @Bean
    public Counter cardCrossReferenceDivergenceCounter(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return Counter.builder(METRIC_CARD_XREF_DIVERGENCE)
                .description("Card updates whose row and cross-reference replica named different"
                        + " accounts")
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
     * The outbox publish latency timer of this service.
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
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException("spring.application.name must hold a value");
        }
        return registry -> registry.config().commonTags(TAG_SERVICE, applicationName);
    }
}

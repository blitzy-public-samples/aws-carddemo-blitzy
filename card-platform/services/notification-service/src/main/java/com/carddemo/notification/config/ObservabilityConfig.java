package com.carddemo.notification.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the meters the notification service reports: one counter and one timer per consumed
 * event type, a failure counter, a rendered-alert counter and a skipped-duplicate counter.
 *
 * <p>No COBOL program declares a per-service configuration class. The meter names below
 * carry the shape of the source counters and none of their logic.</p>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L185-L186} declares {@code WS-TRANSACTION-COUNT} and
 * {@code WS-REJECT-COUNT}. {@code app/cbl/CBTRN02C.cbl:L227-L230} displays both counts and sets
 * return code 4 once the reject count passes zero. A rejection is expected traffic, so no normal
 * outcome reaches the failure counter. Shape only, no logic.</p>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:L65} declares {@code WS-TOTAL-AMT}, the one accumulator the
 * statement program holds. {@code app/cbl/CBSTM03A.CBL:L359-L360} names one failing operation and
 * reports its return code, which is the shape of the {@code failure.kind} tag. Shape only, no
 * logic. These meters replace the display-to-job-log observability at
 * {@code app/cbl/CBTRN02C.cbl:L714-L727}, the paragraph that formats a two-byte file status into
 * four digits.</p>
 *
 * <p>Actuator exposure, Prometheus export, the structured log format and every log level live in
 * {@code src/main/resources/application.yml}. This class configures none of them and changes no
 * log level. The persistence-layer statement and parameter loggers keep their default levels, so
 * no card number reaches a log line.</p>
 *
 * <p>Two facts a contributor needs. The five helpers in {@code com.carddemo.cobol} are final,
 * expose static members only and hide their constructors, so none can become a bean and every
 * caller invokes them statically. This module compiles at release 25 through the
 * {@code java.version} property in its own {@code pom.xml}, which the Spring Boot 4.1.0 parent
 * otherwise defaults to 17. Class-file major version 69 is the proof of that release.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class ObservabilityConfig {

    /** Tag key that names this service on every application and framework meter. */
    public static final String SERVICE_TAG = "service";

    /**
     * Registers every meter against the injected registry, which Spring Boot auto-configuration
     * supplies from Micrometer 1.17.0 and exports through micrometer-registry-prometheus 1.17.0.
     *
     * @param registry the injected meter registry
     * @return the holder every listener and renderer reads
     */
    @Bean
    public NotificationMetrics notificationMetrics(MeterRegistry registry) {
        return new NotificationMetrics(registry);
    }

    /** Adds the service name to application, Java Virtual Machine, and web meters alike. */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> notificationCommonTags(
            @Value("${spring.application.name:notification-service}") String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException("spring.application.name must hold a value");
        }
        return registry -> registry.config().commonTags(SERVICE_TAG, applicationName);
    }

    /**
     * The seven meters this service reports, registered once and read by name.
     *
     * <p>The constructor registers every meter and every bounded tag value eagerly, so a scrape
     * taken before the first message lists each series at zero. Every field is final and holds
     * either an unmodifiable map or one counter. Kafka calls the recording sites from consumer
     * threads, and a Micrometer {@link Counter} and {@link Timer} both accept concurrent
     * recording.</p>
     *
     * <p>A tag value outside its own set, and a null value, both resolve to {@link #UNKNOWN},
     * which every tagged meter registers. No lookup registers a meter and no lookup returns null.
     * An eighth meter needs one field here, one registration in the constructor and one lookup
     * method.</p>
     *
     * <p>The path that records against each meter:
     * {@code messaging/TransactionAuthorizedConsumer.java},
     * {@code messaging/TransactionPostedConsumer.java} and
     * {@code messaging/FraudFlaggedConsumer.java}, plus
     * {@code messaging/CustomerContextChangedConsumer.java}, increment
     * {@link NotificationMetrics#eventsConsumed(String)}, time
     * {@link NotificationMetrics#processingLatency(String)}, increment
     * {@link NotificationMetrics#duplicatesSkipped()} when the idempotency guard rejects a replay,
     * increment {@link NotificationMetrics#eventsUnapplied(String)} for a governed event whose
     * contract version this service applies nothing for, and increment
     * {@link NotificationMetrics#failures(String)} on a fault;
     * {@code config/KafkaConsumerConfig.java} increments
     * {@link NotificationMetrics#deadLettered(String)} once for each record whose attempts ran out;
     * {@code domain/NotificationService.java} increments
     * {@link NotificationMetrics#notificationsRendered(String)} once per alert it renders through
     * {@link com.carddemo.notification.domain.PlainTextRenderer} or
     * {@link com.carddemo.notification.domain.HtmlRenderer}. The renderers stay pure and record
     * nothing themselves, so a formatter test needs no meter registry. Every meter registers at
     * start-up, so each one is scrapable before its caller records against it.</p>
     */
    public static final class NotificationMetrics {

        /**
         * Tag values the {@code event.type} dimension carries, one per consumed event. This service
         * reads four topics, which is exactly what its broker entries grant it.
         * {@code transaction.authorized} carries the decision the authorization service reached,
         * {@code transaction.posted} carries the new balance the ledger derived, two verdicts travel
         * together on {@code fraud.assessed}, and {@code customer.context-changed} carries renderer
         * context.
         *
         * <p>The entry on {@code transaction.authorized} makes this service the third service that
         * consumes the authorization event directly, beside the ledger and the fraud detector, which
         * is the fan-out AAP 0.1.1 and 0.8.3 require. None of the three reads any of the others.
         *
         * <p>Every value below is registered as a series. A value a listener records against but that
         * no series carries is not a compile error and not a test failure: the lookup falls back to
         * {@link #UNKNOWN}, and the events of that listener are silently attributed to a tag that
         * names nothing. {@code CustomerContextChanged} was in exactly that state.
         */
        public static final String EVENT_TRANSACTION_AUTHORIZED = "TransactionAuthorized";
        public static final String EVENT_TRANSACTION_POSTED = "TransactionPosted";
        public static final String EVENT_FRAUD_FLAGGED = "FraudFlagged";
        public static final String EVENT_FRAUD_CLEARED = "FraudCleared";
        public static final String EVENT_CUSTOMER_CONTEXT_CHANGED = "CustomerContextChanged";

        /**
         * Tag values the {@code failure.kind} dimension carries, one per processing fault. The
         * sibling listener configuration records one of the first two when it routes a record to
         * the dead-letter topic.
         */
        public static final String FAILURE_SCHEMA_VALIDATION = "schema_validation";
        public static final String FAILURE_DESERIALIZATION = "deserialization";
        public static final String FAILURE_PERSISTENCE = "persistence";
        public static final String FAILURE_RENDERING = "rendering";

        /** Tag values the {@code format} dimension carries, one per renderer. */
        public static final String FORMAT_TEXT = "text";
        public static final String FORMAT_HTML = "html";

        /** Tag value a lookup resolves to when it receives a value outside its own set. */
        public static final String UNKNOWN = "unknown";

        /** Depth cap on a cause-chain walk, which ends the walk on a self-referencing cause. */
        private static final int MAX_CAUSE_DEPTH = 16;

        private static final String EVENT_TYPE_TAG = "event.type";
        private static final String FAILURE_KIND_TAG = "failure.kind";
        private static final String FORMAT_TAG = "format";

        private final Map<String, Counter> eventsConsumed;
        private final Map<String, Timer> processingLatency;
        private final Map<String, Counter> failures;
        private final Map<String, Counter> deadLettered;
        private final Map<String, Counter> notificationsRendered;
        private final Map<String, Counter> eventsUnapplied;
        private final Counter duplicatesSkipped;

        NotificationMetrics(MeterRegistry registry) {
            List<String> eventTypes = List.of(EVENT_TRANSACTION_AUTHORIZED,
                    EVENT_TRANSACTION_POSTED, EVENT_FRAUD_FLAGGED, EVENT_FRAUD_CLEARED,
                    EVENT_CUSTOMER_CONTEXT_CHANGED, UNKNOWN);
            List<String> failureKinds = List.of(FAILURE_SCHEMA_VALIDATION, FAILURE_DESERIALIZATION,
                    FAILURE_PERSISTENCE, FAILURE_RENDERING, UNKNOWN);

            Map<String, Timer> timers = new LinkedHashMap<>();
            for (String eventType : eventTypes) {
                timers.put(eventType, Timer.builder("carddemo.notification.processing.latency")
                        .description("Time one listener spent handling one event")
                        .tag(EVENT_TYPE_TAG, eventType)
                        .register(registry));
            }

            this.eventsConsumed = counters(registry, "carddemo.notification.events.consumed",
                    "Events this service consumed", EVENT_TYPE_TAG, eventTypes);
            this.processingLatency = Map.copyOf(timers);
            this.failures = counters(registry, "carddemo.notification.failures",
                    "Failed delivery attempts, one per attempt", FAILURE_KIND_TAG, failureKinds);
            this.deadLettered = counters(registry, "carddemo.notification.records.dead.lettered",
                    "Records whose delivery attempts ran out, one per record", FAILURE_KIND_TAG,
                    failureKinds);
            this.notificationsRendered = counters(registry,
                    "carddemo.notification.notifications.rendered", "Cardholder alerts rendered",
                    FORMAT_TAG, List.of(FORMAT_TEXT, FORMAT_HTML, UNKNOWN));
            this.eventsUnapplied = counters(registry, "carddemo.notification.events.unapplied",
                    "Events consumed, recognised and deliberately not applied, one per delivery",
                    EVENT_TYPE_TAG, eventTypes);
            this.duplicatesSkipped = Counter.builder("carddemo.notification.duplicates.skipped")
                    .description("Events skipped as already processed")
                    .register(registry);
        }

        /** Returns the consumed-event counter for {@code eventType}. */
        public Counter eventsConsumed(String eventType) {
            return resolve(this.eventsConsumed, eventType);
        }

        /** Returns the processing-latency timer for {@code eventType}. */
        public Timer processingLatency(String eventType) {
            return resolve(this.processingLatency, eventType);
        }

        /**
         * Returns the counter of failed delivery attempts for {@code failureKind}.
         *
         * <p>The unit is one delivery attempt, not one record. A record taken three times before its
         * attempts run out increments this counter three times, which is what makes a retry storm
         * visible. {@link #deadLettered(String)} is the per-record counter, and the ratio between
         * the two is the average attempt count.
         */
        public Counter failures(String failureKind) {
            return resolve(this.failures, failureKind);
        }

        /**
         * Returns the counter of records whose delivery attempts ran out, for {@code failureKind}.
         *
         * <p>The unit is one record. It is incremented once, by the dead-letter recoverer, at the
         * moment the record leaves the retry cycle for good. Counting the terminal outcome on a
         * series of its own is what separates "three attempts failed" from "one record was lost to
         * the dead-letter topic".
         */
        public Counter deadLettered(String failureKind) {
            return resolve(this.deadLettered, failureKind);
        }

        /** Returns the rendered-alert counter for {@code format}. */
        public Counter notificationsRendered(String format) {
            return resolve(this.notificationsRendered, format);
        }

        /**
         * Returns the counter of events consumed and deliberately not applied, for
         * {@code eventType}.
         *
         * <p>The unit is one delivery. It moves for a governed, valid event this service consumed and
         * chose to apply nothing for, which today is exactly one case: a contract version that
         * predates the card token both of this service's tables are keyed on. Such a record was
         * previously refused, retried three times and dead-lettered, which reported a valid event as a
         * poison record and lost it to a topic nobody reads.
         *
         * <p>It is separate from every other series here for a reason each. It is not a
         * {@link #failures(String)}, because nothing failed. It is not a
         * {@link #deadLettered(String)}, because the record is acknowledged. It is not a
         * {@link #duplicatesSkipped()}, because the event was never processed before. And
         * {@link #eventsConsumed(String)} alone cannot show it, because a consumed count that rises
         * with no alert rendered and no failure recorded is the ambiguity this series removes.
         *
         * @param eventType the event type the unapplied delivery carried
         * @return the counter for that type, or the {@link #UNKNOWN} series for an undeclared value
         */
        public Counter eventsUnapplied(String eventType) {
            return resolve(this.eventsUnapplied, eventType);
        }

        /** Returns the counter of events an idempotency guard skipped. */
        public Counter duplicatesSkipped() {
            return this.duplicatesSkipped;
        }

        /**
         * Whether {@code failure} names a database fault anywhere in its cause chain.
         *
         * <p>This is the one place the platform decides what {@link #FAILURE_PERSISTENCE} covers, and
         * every caller that tags a fault reads it. Three types answer yes.
         * {@link DataAccessException} covers a statement the database refused.
         * {@link TransactionException} covers a fault raised before a statement ran, and a connection
         * pool that cannot hand out a connection raises exactly that: a paused or unreachable
         * database surfaces as {@code CannotCreateTransactionException}, which is a
         * {@code TransactionException} and is not a {@code DataAccessException}. {@link SQLException}
         * covers a driver fault that reached a caller unwrapped.
         *
         * <p>Testing only for a data-access fault left {@link #FAILURE_PERSISTENCE} at zero for the
         * most common database failure there is, while {@link #FAILURE_RENDERING} counted faults that
         * never reached a renderer. A failure count an operator cannot read the degraded integration
         * from does not meet the observability requirement.
         *
         * <p>The walk is depth-capped and ends on a self-referencing cause, so a malformed chain
         * cannot spin.
         *
         * @param failure the fault a delivery raised; may be {@code null}
         * @return {@code true} when the chain names a database fault
         */
        public static boolean isPersistenceFault(Throwable failure) {
            Throwable cause = failure;

            for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
                if (cause instanceof DataAccessException
                        || cause instanceof TransactionException
                        || cause instanceof SQLException) {
                    return true;
                }
                cause = cause.getCause() == cause ? null : cause.getCause();
            }
            return false;
        }

        private static Map<String, Counter> counters(MeterRegistry registry, String name,
                String what, String tagKey, List<String> tagValues) {
            Map<String, Counter> byTagValue = new LinkedHashMap<>();
            for (String tagValue : tagValues) {
                byTagValue.put(tagValue, Counter.builder(name)
                        .description(what)
                        .tag(tagKey, tagValue)
                        .register(registry));
            }
            return Map.copyOf(byTagValue);
        }

        private static <M> M resolve(Map<String, M> byTagValue, String tagValue) {
            M meter = tagValue == null ? null : byTagValue.get(tagValue);
            return meter != null ? meter : byTagValue.get(UNKNOWN);
        }
    }
}

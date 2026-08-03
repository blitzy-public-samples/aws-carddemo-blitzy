package com.carddemo.notification.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the meters the notification service reports: one counter and one timer per consumed
 * event type, a failure counter, a rendered-alert counter and a skipped-duplicate counter.
 *
 * <p>ADDITIVE. No COBOL program declares a per-service configuration class. The meter names below
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
 * <p>See {@code card-platform/docs/decision-log.md} for the decisions behind this module.</p>
 */
@Configuration
public class ObservabilityConfig {

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

    /**
     * The five meters this service reports, registered once and read by name.
     *
     * <p>The constructor registers every meter and every bounded tag value eagerly, so a scrape
     * taken before the first message lists each series at zero. Every field is final and holds
     * either an unmodifiable map or one counter. Kafka calls the recording sites from consumer
     * threads, and a Micrometer {@link Counter} and {@link Timer} both accept concurrent
     * recording.</p>
     *
     * <p>A tag value outside its own set, and a null value, both resolve to {@link #UNKNOWN},
     * which every tagged meter registers. No lookup registers a meter and no lookup returns null.
     * A sixth meter needs one field here, one registration in the constructor and one lookup
     * method.</p>
     */
    public static final class NotificationMetrics {

        /**
         * Tag values the {@code event.type} dimension carries. Two of them travel on
         * {@code fraud.assessed}, and the envelope {@code eventType} field separates those two.
         */
        public static final String EVENT_TRANSACTION_POSTED = "TransactionPosted";
        public static final String EVENT_FRAUD_FLAGGED = "FraudFlagged";
        public static final String EVENT_FRAUD_CLEARED = "FraudCleared";

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

        private static final String EVENT_TYPE_TAG = "event.type";
        private static final String FAILURE_KIND_TAG = "failure.kind";
        private static final String FORMAT_TAG = "format";

        private final Map<String, Counter> eventsConsumed;
        private final Map<String, Timer> processingLatency;
        private final Map<String, Counter> failures;
        private final Map<String, Counter> notificationsRendered;
        private final Counter duplicatesSkipped;

        NotificationMetrics(MeterRegistry registry) {
            List<String> eventTypes = List.of(EVENT_TRANSACTION_POSTED, EVENT_FRAUD_FLAGGED,
                    EVENT_FRAUD_CLEARED, UNKNOWN);

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
                    "Processing faults", FAILURE_KIND_TAG,
                    List.of(FAILURE_SCHEMA_VALIDATION, FAILURE_DESERIALIZATION,
                            FAILURE_PERSISTENCE, FAILURE_RENDERING, UNKNOWN));
            this.notificationsRendered = counters(registry,
                    "carddemo.notification.notifications.rendered", "Cardholder alerts rendered",
                    FORMAT_TAG, List.of(FORMAT_TEXT, FORMAT_HTML, UNKNOWN));
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

        /** Returns the failure counter for {@code failureKind}. */
        public Counter failures(String failureKind) {
            return resolve(this.failures, failureKind);
        }

        /** Returns the rendered-alert counter for {@code format}. */
        public Counter notificationsRendered(String format) {
            return resolve(this.notificationsRendered, format);
        }

        /** Returns the counter of events an idempotency guard skipped. */
        public Counter duplicatesSkipped() {
            return this.duplicatesSkipped;
        }

        /** Registers one counter per tag value and returns them keyed by that value. */
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

        /** Returns the meter one tag value names, falling back to the {@link #UNKNOWN} series. */
        private static <M> M resolve(Map<String, M> byTagValue, String tagValue) {
            M meter = tagValue == null ? null : byTagValue.get(tagValue);
            return meter != null ? meter : byTagValue.get(UNKNOWN);
        }
    }
}

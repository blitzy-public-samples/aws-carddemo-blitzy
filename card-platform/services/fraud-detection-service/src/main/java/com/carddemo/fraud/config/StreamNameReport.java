package com.carddemo.fraud.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Writes the stream names this service resolved, and says where each one came from.
 *
 * <p>No Common Business Oriented Language (COBOL) ancestor: the source carries no fraud program,
 * no risk score and no rules engine.
 *
 * <p>Each name is therefore logged once as the context finishes starting: at INFO when the platform
 * supplied the value, naming the variable it came from, and at WARN when it did not, naming the
 * variable that is missing.
 *
 * <p>Stream names only. The retry settings, the relay sweep and the risk thresholds stay in the
 * bound {@link FraudProperties} record, which fails start-up when one of them is invalid.
 *
 * <p>No credential is reported here, and none can be: a topic name is not a credential, and the
 * class reads nothing else.
 *
 * <p>Rationale, alternatives considered and accepted risks:
 * {@code card-platform/docs/decision-log.md}.
 */
@Component
public class StreamNameReport implements ApplicationListener<ApplicationReadyEvent> {

    /** Where the report is written. */
    private static final Logger LOG = LoggerFactory.getLogger(StreamNameReport.class);

    /** The resolved environment, used to ask whether a variable was set rather than for its value. */
    private final Environment environment;

    /** The bound configuration, which holds the values actually in force. */
    private final FraudProperties properties;

    /**
     * Builds the report.
     *
     * @param environment the resolved environment
     * @param properties  the bound {@code carddemo} block
     */
    public StreamNameReport(Environment environment, FraudProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /**
     * The names this service resolved, in the order they are reported.
     *
     * <p>Package-private and returned rather than logged, so a test can assert the list without
     * capturing log output. One entry per topic this module binds.
     *
     * @return one entry per stream name, never empty
     */
    List<ReportedName> reportedNames() {
        FraudProperties.Kafka.Topics topics = properties.kafka().topics();
        return List.of(
                new ReportedName(
                        "topic this service consumes an approved authorization from",
                        "TOPIC_TRANSACTION_AUTHORIZED",
                        topics.transactionAuthorized()),
                new ReportedName(
                        "topic a risk assessment travels on",
                        "TOPIC_FRAUD_ASSESSED",
                        topics.fraudAssessed()),
                new ReportedName(
                        "topic an unprocessable record is routed to",
                        "TOPIC_DEAD_LETTER",
                        topics.deadLetter()),
                new ReportedName(
                        "suffix composed with a consumed topic to name its dead-letter stream",
                        "TOPIC_DEAD_LETTER_SUFFIX",
                        topics.deadLetterSuffix()));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        for (ReportedName name : reportedNames()) {
            if (environment.containsProperty(name.environmentKey())) {
                LOG.info("Resolved {}: {} (from {})",
                        name.role(), name.value(), name.environmentKey());
            } else {
                LOG.warn("Resolved {}: {} — {} is not set, so this is the name built into the"
                                + " image rather than one this deployment supplied. Check the"
                                + " ConfigMap or compose environment if that is not intended.",
                        name.role(), name.value(), name.environmentKey());
            }
        }
    }

    /**
     * One reported stream name.
     *
     * @param role           what the stream is for, in words an operator reads
     * @param environmentKey the environment variable the platform sets to override the default
     * @param value          the name actually in force
     */
    record ReportedName(String role, String environmentKey, String value) {
    }
}

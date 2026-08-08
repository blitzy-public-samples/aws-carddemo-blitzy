package com.carddemo.notification.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Writes the stream and group names this service resolved, and says where each one came from.
 *
 * <p>No COBOL ancestor. {@code app/jcl/CREASTMT.JCL} names the sorted file this service's streams
 * replace, and it names it in the job rather than in {@code app/cbl/CBSTM03A.CBL}, so the program
 * could not resolve a name at run time at all.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>This module registers four listeners, and each is decided by two configured names: the topic
 * it reads and the consumer group it reads under. All eight names are reported here, beside
 * the shared dead-letter topic and the per-source suffix. Both arrive with an in-image default,
 * for example
 * {@code ${TOPIC_TRANSACTION_POSTED:transaction.posted}} and
 * {@code ${GROUP_NOTIFICATION_POSTED:notification-posted}}. That is deliberate: a service has to
 * start on a developer machine with nothing set. It also means a dropped, renamed or mistyped key in
 * {@code card-platform/deploy/k8s/30-configmap.yaml} or {@code card-platform/docker-compose.yml}
 * produces no error at all. A wrong topic leaves this service waiting for events that arrive
 * elsewhere; a wrong group is worse, because two listeners sharing one group split the partitions
 * between them and each sees half the events, which looks like intermittent loss rather than like
 * misconfiguration.
 *
 * <p>This class makes both choices visible instead of removing them. Each name is logged once as the
 * context finishes starting: at INFO when the platform supplied the value, naming the variable it
 * came from, and at WARN when it did not, naming the variable that is missing.
 *
 * <h2>What is reported, and what is not</h2>
 *
 * <p>Stream and group names only. The retry settings and the history page sizes arrive the same way,
 * and a wrong value there changes a retry or a page; a wrong stream or group name costs events. Those
 * numbers stay in the bound {@link NotificationProperties} record, which fails start-up when one of
 * them is invalid.
 *
 * <p>No credential is reported here, and no card number: a topic name and a group name are neither,
 * and the class reads nothing else.
 */
@Component
public class StreamNameReport implements ApplicationListener<ApplicationReadyEvent> {

    /** Where the report is written. */
    private static final Logger LOG = LoggerFactory.getLogger(StreamNameReport.class);

    /** The resolved environment, used to ask whether a variable was set rather than for its value. */
    private final Environment environment;

    /** The bound configuration, which holds the values actually in force. */
    private final NotificationProperties properties;

    /**
     * Builds the report.
     *
     * @param environment the resolved environment
     * @param properties  the bound {@code carddemo} block
     */
    public StreamNameReport(Environment environment, NotificationProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /**
     * The names this service resolved, in the order they are reported.
     *
     * <p>Package-private and returned rather than logged so a test can assert the list without
     * capturing log output. One entry per topic and per consumer group this module binds: an entry
     * missing here would be a name whose resolution is unreported, which is the defect this class
     * exists to prevent.
     *
     * @return one entry per stream or group name, never empty
     */
    List<ReportedName> reportedNames() {
        NotificationProperties.Kafka.Topics topics = properties.kafka().topics();
        NotificationProperties.Kafka.Groups groups = properties.kafka().groups();
        return List.of(
                new ReportedName(
                        "topic this service consumes an authorized transaction from",
                        "TOPIC_TRANSACTION_AUTHORIZED",
                        topics.transactionAuthorized()),
                new ReportedName(
                        "consumer group the authorized-transaction listener reads under",
                        "GROUP_NOTIFICATION_AUTHORIZED",
                        groups.transactionAuthorized()),
                new ReportedName(
                        "topic this service consumes a posted transaction from",
                        "TOPIC_TRANSACTION_POSTED",
                        topics.transactionPosted()),
                new ReportedName(
                        "consumer group the posted-transaction listener reads under",
                        "GROUP_NOTIFICATION_POSTED",
                        groups.transactionPosted()),
                new ReportedName(
                        "topic this service consumes a risk assessment from",
                        "TOPIC_FRAUD_ASSESSED",
                        topics.fraudAssessed()),
                new ReportedName(
                        "consumer group the risk-assessment listener reads under",
                        "GROUP_NOTIFICATION_FRAUD",
                        groups.fraudAssessed()),
                new ReportedName(
                        "topic this service consumes cardholder context from",
                        "TOPIC_CUSTOMER_CONTEXT_CHANGED",
                        topics.customerContextChanged()),
                new ReportedName(
                        "consumer group the cardholder-context listener reads under",
                        "GROUP_NOTIFICATION_CUSTOMER",
                        groups.customerContextChanged()),
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
     * One reported stream or group name.
     *
     * @param role           what the name is for, in words an operator reads
     * @param environmentKey the environment variable the platform sets to override the default
     * @param value          the name actually in force
     */
    record ReportedName(String role, String environmentKey, String value) {
    }
}

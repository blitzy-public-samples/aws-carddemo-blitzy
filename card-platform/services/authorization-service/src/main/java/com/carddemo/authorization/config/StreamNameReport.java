package com.carddemo.authorization.config;

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
 * <p>No COBOL ancestor. {@code app/cbl/CBTRN02C.cbl} names its datasets in a data division and a
 * job stream names the files behind them, so a program could not resolve a name at run time at all.
 * Its nearest relative is the source habit of displaying what a program is about to work on.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>One authorization call publishes exactly one event, and which stream that event lands on is
 * decided by {@code ${TOPIC_TRANSACTION_AUTHORIZED:transaction.authorized}}. The image carries a
 * default and the platform supplies the real value, which is deliberate: a service has to start on a
 * developer machine with nothing set. It also means a dropped, renamed or mistyped key in
 * {@code card-platform/deploy/k8s/30-configmap.yaml} or {@code card-platform/docker-compose.yml}
 * produces no error at all — this service publishes onto the built-in name, reports itself healthy,
 * and the ledger and fraud services read a stream nothing arrives on. That failure is the whole
 * fan-out going quiet, and nothing in the logs says why.
 *
 * <p>This class makes the choice visible instead of removing it. Each name is logged once as the
 * context finishes starting: at INFO when the platform supplied the value, naming the variable it
 * came from, and at WARN when it did not, naming the variable that is missing.
 *
 * <h2>What is reported, and what is not</h2>
 *
 * <p>Stream names only. The relay sweep, the claim timeout and the replica lag ceiling arrive the
 * same way, and a wrong value there changes throughput or how much backlog a decision tolerates; a
 * wrong stream name costs every event. Those values stay in the bound
 * {@link AuthorizationProperties} record, which fails start-up when one of them is invalid.
 *
 * <p>No credential is reported here, and none can be: a topic name is not a credential, and the
 * class reads nothing else. No card number reaches this class either.
 *
 */
@Component
public class StreamNameReport implements ApplicationListener<ApplicationReadyEvent> {

    /** Where the report is written. */
    private static final Logger LOG = LoggerFactory.getLogger(StreamNameReport.class);

    /** The resolved environment, used to ask whether a variable was set rather than for its value. */
    private final Environment environment;

    /** The bound configuration, which holds the values actually in force. */
    private final AuthorizationProperties properties;

    /**
     * Builds the report.
     *
     * @param environment the resolved environment
     * @param properties  the bound {@code carddemo} block
     */
    public StreamNameReport(Environment environment, AuthorizationProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /**
     * The names this service resolved, in the order they are reported.
     *
     * <p>Package-private and returned rather than logged so a test can assert the list without
     * capturing log output. One entry per topic this module binds, published and consumed alike: an
     * entry missing here would be a stream whose resolution is unreported, which is the defect this
     * class exists to prevent. A mistyped consumed name costs as much as a mistyped published one,
     * because a listener then reads a topic nothing arrives on and reports itself healthy.
     *
     * @return one entry per stream name, never empty
     */
    List<ReportedName> reportedNames() {
        AuthorizationProperties.Kafka.Topics topics = properties.kafka().topics();
        return List.of(
                new ReportedName(
                        "topic an approved authorization travels on",
                        "TOPIC_TRANSACTION_AUTHORIZED",
                        topics.transactionAuthorized()),
                new ReportedName(
                        "topic a declined authorization travels on",
                        "TOPIC_TRANSACTION_DECLINED",
                        topics.transactionDeclined()),
                new ReportedName(
                        "topic the account projection is kept current from",
                        "TOPIC_ACCOUNT_STATE_CHANGED",
                        topics.accountStateChanged()),
                new ReportedName(
                        "topic the cross-reference observation is kept current from",
                        "TOPIC_CARD_UPDATED",
                        topics.cardUpdated()),
                new ReportedName(
                        "topic a record no listener could consume reaches",
                        "TOPIC_DEAD_LETTER",
                        topics.deadLetter()));
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

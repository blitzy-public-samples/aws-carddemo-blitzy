package com.carddemo.ledger.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Writes the stream names and dead-letter suffix this service resolved, and says where each came
 *
 * from.
 *
 * <p>No COBOL ancestor. {@code app/jcl/POSTTRAN.jcl} names the daily transaction file this
 * service's stream replaces, and it names it in the job rather than in the program, so {@code
 * app/cbl/CBTRN02C.cbl} could not resolve a name at run time at all. Its nearest relative is the
 * source habit of displaying what a program is about to work on.
 *
 * <p>This class makes the choice visible instead of removing it. Each name is logged once as the
 * context finishes starting: at INFO when the platform supplied the value, naming the variable it
 * came from, and at WARN when it did not, naming the variable that is missing.
 *
 * <p>Stream names only. The retry attempts, the backoff and the relay sweep arrive the same way, and
 * a wrong value there changes how quickly work is retried; a wrong stream name costs every event.
 * Those numbers stay in the bound {@link LedgerProperties} record, which fails start-up when one of
 * them is invalid.
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
    private final LedgerProperties properties;

    /**
     * Builds the report.
     *
     * @param environment the resolved environment
     * @param properties  the bound {@code carddemo} block
     */
    public StreamNameReport(Environment environment, LedgerProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /**
     * The names and suffix this service resolved, in the order they are reported.
     *
     * <p>Package-private and returned rather than logged so a test can assert the list without
     * capturing log output. One entry per topic this module binds: an entry missing here would be a
     * stream whose resolution is unreported, which is the defect this class exists to prevent.
     *
     * @return one entry per stream name, never empty
     */
    List<ReportedName> reportedNames() {
        LedgerProperties.Kafka.Topics topics = properties.kafka().topics();
        return List.of(
                new ReportedName(
                        "topic this service consumes an approved authorization from",
                        "TOPIC_TRANSACTION_AUTHORIZED",
                        topics.transactionAuthorized()),
                new ReportedName(
                        "topic this service consumes a refused authorization from",
                        "TOPIC_TRANSACTION_DECLINED",
                        topics.transactionDeclined()),
                new ReportedName(
                        "topic this service consumes an account state change from",
                        "TOPIC_ACCOUNT_STATE_CHANGED",
                        topics.accountStateChanged()),
                new ReportedName(
                        "topic a posted transaction travels on",
                        "TOPIC_TRANSACTION_POSTED",
                        topics.transactionPosted()),
                new ReportedName(
                        "fallback topic for a record with no source topic",
                        "TOPIC_DEAD_LETTER",
                        topics.deadLetter()),
                new ReportedName(
                        "suffix appended to each source topic for dead-letter routing",
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

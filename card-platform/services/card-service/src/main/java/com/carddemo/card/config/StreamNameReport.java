package com.carddemo.card.config;

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
 * <p>No COBOL ancestor. The card programs {@code app/cbl/COCRDLIC.cbl}, {@code
 * app/cbl/COCRDSLC.cbl} and {@code app/cbl/COCRDUPC.cbl} read no configuration file, so this class
 * has no COBOL ancestor. Its nearest relative is the source habit of displaying what a program is
 * about to work on, as {@code app/cbl/CBTRN02C.cbl} does before it posts.
 *
 * <p>This class makes the choice visible instead of removing it. Each name is logged once as the
 * context finishes starting: at INFO when the platform supplied the value, naming the variable it
 * came from, and at WARN when it did not, naming the variable that is missing. An operator reading
 * the first hundred lines of {@code kubectl logs} therefore sees which stream this Pod is really
 * attached to, and a demo that publishes into the wrong topic is one grep away from being
 * explained.
 *
 * <p>Stream names only. The relay sweep interval and batch size arrive the same way, and a wrong
 * value there costs throughput and nothing else; a wrong stream name costs the event. Reporting
 * every numeric knob would bury the two lines that matter, so the numbers stay in the bound
 * {@link CardProperties} record, which fails start-up when one of them is invalid.
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

    /** The bound configuration, which holds the value actually in force. */
    private final CardProperties properties;

    /**
     * Builds the report.
     *
     * @param environment the resolved environment
     * @param properties  the bound {@code carddemo} block
     */
    public StreamNameReport(Environment environment, CardProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /**
     * The names this service resolved, in the order they are reported.
     *
     * <p>Package-private and returned rather than logged so a test can assert the list without
     * capturing log output. One entry per topic this module names in its {@code application.yml}: an
     * entry missing here would be a stream whose resolution is unreported, which is the defect this
     * class exists to prevent.
     *
     * @return one entry per stream name, never empty
     */
    List<ReportedName> reportedNames() {
        return List.of(
                new ReportedName(
                        "topic this service publishes a card update onto",
                        "TOPIC_CARD_UPDATED",
                        properties.kafka().topics().cardUpdated()),
                new ReportedName(
                        "topic this service publishes a terminal outbox diagnostic onto",
                        "TOPIC_DEAD_LETTER",
                        properties.kafka().topics().deadLetter()));
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

package com.carddemo.card.config;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads back what {@link StreamNameReport} would write.
 *
 * <p>This class has no COBOL ancestor.
 *
 * <p>The first test is the coverage check: every topic the shipped {@code application.yml} names
 * must appear in the report, because a stream whose resolution goes unreported is the silent
 * fallback this class exists to expose. The second and third assert the two branches an operator
 * depends on: the value in force is the bound value, and the variable named is the one the platform
 * sets.
 */
@DisplayName("StreamNameReport, the stream names this service resolved")
class StreamNameReportTest {

    /** The shipped defaults, bound by hand so no context has to start. */
    private static final CardProperties SHIPPED = new CardProperties(
            new CardProperties.Api(65536L),
            new CardProperties.Kafka(new CardProperties.Kafka.Topics("card.updated")),
            new CardProperties.Outbox(new CardProperties.Outbox.Relay(
                    500L,
                    100,
                    "card-relay",
                    Duration.ofMinutes(2L)), 168L),
            new CardProperties.ProcessedEvent(168L),
            new CardProperties.Retention(3_600_000L));

    @Test
    @DisplayName("every topic this module names is reported exactly once")
    void everyTopicThisModuleNamesIsReportedExactlyOnce() {
        StreamNameReport report = new StreamNameReport(new MockEnvironment(), SHIPPED);

        assertThat(report.reportedNames())
                .extracting(StreamNameReport.ReportedName::environmentKey)
                .containsExactly("TOPIC_CARD_UPDATED");
    }

    @Test
    @DisplayName("the reported value is the bound value, not the environment variable")
    void theReportedValueIsTheBoundValue() {
        CardProperties overridden = new CardProperties(
                SHIPPED.api(),
                new CardProperties.Kafka(new CardProperties.Kafka.Topics("card.updated.v2")),
                SHIPPED.outbox(),
                SHIPPED.processedEvent(),
                SHIPPED.retention());

        StreamNameReport report = new StreamNameReport(new MockEnvironment(), overridden);

        assertThat(report.reportedNames())
                .extracting(StreamNameReport.ReportedName::value)
                .containsExactly("card.updated.v2");
    }

    @Test
    @DisplayName("a report entry names a variable the platform actually sets")
    void aReportEntryNamesAVariableThePlatformActuallySets() {
        MockEnvironment platformSupplied = new MockEnvironment()
                .withProperty("TOPIC_CARD_UPDATED", "card.updated");

        StreamNameReport report = new StreamNameReport(platformSupplied, SHIPPED);

        List<StreamNameReport.ReportedName> names = report.reportedNames();
        assertThat(names).isNotEmpty();
        assertThat(names).allSatisfy(name ->
                assertThat(platformSupplied.containsProperty(name.environmentKey())).isTrue());
    }
}

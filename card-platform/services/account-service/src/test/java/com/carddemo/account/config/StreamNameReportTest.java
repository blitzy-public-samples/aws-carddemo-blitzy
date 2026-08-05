package com.carddemo.account.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads back what {@link StreamNameReport} would write for the account service.
 *
 * <p>This class has no COBOL ancestor.
 *
 * <p>The first test is the coverage check: every name the shipped {@code application.yml} carries
 * must appear in the report, and with the value the shipped file documents. A name missing here is a
 * stream whose resolution goes unreported, which is the silent fallback
 * {@link StreamNameReport} exists to expose. The second test asserts the other half an operator
 * depends on: every entry names a real variable and describes what the name is for, so a line in
 * {@code kubectl logs} can be acted on without reading the source.
 */
@DisplayName("StreamNameReport, the stream names the account service resolved")
class StreamNameReportTest {

    /** Registers the properties bean and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AccountProperties.class)
    static class PropertiesEnabled {
    }

    /** A context whose only property source is the shipped {@code application.yml}. */
    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesEnabled.class);

    @Test
    @DisplayName("every name the shipped configuration carries is reported once, with its value")
    void everyShippedNameIsReportedOnceWithItsValue() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            StreamNameReport report =
                    new StreamNameReport(new MockEnvironment(), context.getBean(AccountProperties.class));

            assertThat(report.reportedNames())
                    .extracting(StreamNameReport.ReportedName::environmentKey)
                    .containsExactly("TOPIC_ACCOUNT_STATE_CHANGED",
                            "TOPIC_CUSTOMER_CONTEXT_CHANGED",
                            "TOPIC_DEAD_LETTER");
            assertThat(report.reportedNames())
                    .extracting(StreamNameReport.ReportedName::value)
                    .containsExactly("account.state-changed",
                            "customer.context-changed",
                            "carddemo.dead-letter");
        });
    }

    @Test
    @DisplayName("every entry names a variable and says what the name is for")
    void everyEntryNamesAVariableAndSaysWhatTheNameIsFor() {
        shipped.run(context -> {
            StreamNameReport report =
                    new StreamNameReport(new MockEnvironment(), context.getBean(AccountProperties.class));

            assertThat(report.reportedNames()).isNotEmpty();
            assertThat(report.reportedNames()).allSatisfy(name -> {
                assertThat(name.environmentKey()).matches("[A-Z][A-Z0-9_]+");
                assertThat(name.role()).isNotBlank();
                assertThat(name.value()).isNotBlank();
            });
        });
    }
}

package com.carddemo.authorization.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requires each shared series of this service to be declared in exactly one place.
 *
 * <p>This class has no COBOL ancestor. The CardDemo source reports through {@code DISPLAY} to
 * {@code SYSOUT} and keeps no meter, so nothing there declares a series once or twice.
 *
 * <p>Two paths used to register the abandoned-row series and both diagnostic-outcome series, with a
 * different description on each path. Micrometer keeps the meter that registered first and ignores
 * the later description, so one whole block was dead: an operator could read a description in the
 * source that no scrape would ever carry. The relay then resolved the same three identifiers with a
 * builder carrying no description at all, which made the published metadata depend on which bean
 * was constructed first.
 *
 * <p>Four properties are read here. Applying the binder registers each series once. Applying it
 * again changes nothing. A caller that reaches a series before the binder does still leaves the
 * description in place, so construction order decides nothing. And no component
 * outside {@link ObservabilityConfig} names any shared series, so a future caller cannot reintroduce
 * a description-less registration.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Every shared series of the authorization service is declared once")
class MeterDeclarationTest {

    /** Each shared series, mapped to the number of tag-distinguished series it carries. */
    private static final Map<String, Integer> SHARED_SERIES = Map.of(
            ObservabilityConfig.DECISIONS_COUNTER, 5,
            ObservabilityConfig.EVENTS_WRITTEN_COUNTER, 2,
            ObservabilityConfig.EVENTS_PUBLISHED_COUNTER, 1,
            ObservabilityConfig.OUTBOX_ABANDONED_COUNTER, 1,
            ObservabilityConfig.DEAD_LETTERS_COUNTER, 2,
            ObservabilityConfig.FAILURES_COUNTER, 4);

    /** The registry each test reads. */
    private MeterRegistry registry;

    /** Opens a registry holding nothing. */
    @BeforeEach
    void openAnEmptyRegistry() {
        registry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("the binder registers each shared series once and describes every one")
    void theBinderRegistersEachSeriesOnceAndDescribesEveryOne() {
        bind();

        SHARED_SERIES.forEach((name, series) -> {
            List<Meter> found = registry.getMeters().stream()
                    .filter(meter -> meter.getId().getName().equals(name)).toList();

            assertThat(found).as("the series registered under %s", name).hasSize(series);
            assertThat(found).allSatisfy(meter -> assertThat(meter.getId().getDescription())
                    .as("the description of %s %s", name, meter.getId().getTags())
                    .isNotBlank());
        });
    }

    @Test
    @DisplayName("applying the binder a second time registers nothing further")
    void applyingTheBinderTwiceRegistersNothingFurther() {
        bind();
        int afterFirst = registry.getMeters().size();
        bind();

        assertThat(registry.getMeters()).as("the registry after a second application")
                .hasSize(afterFirst);
    }

    @Test
    @DisplayName("a caller reaching a series before the binder still leaves it described")
    void aCallerReachingASeriesFirstStillLeavesItDescribed() {
        ObservabilityConfig.outboxAbandonedCounter(registry);
        ObservabilityConfig.deadLetterCounter(registry, ObservabilityConfig.DIAGNOSTIC_PUBLISHED);
        ObservabilityConfig.deadLetterCounter(registry, ObservabilityConfig.DIAGNOSTIC_FAILED);
        ObservabilityConfig.eventsPublishedCounter(registry);
        ObservabilityConfig.failureCounter(registry, ObservabilityConfig.PUBLISH_STAGE);
        int beforeBinding = registry.getMeters().size();

        bind();

        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(
                meter.getId().getDescription())
                .as("the description of %s, which the ordering used to decide",
                        meter.getId().getName())
                .isNotBlank());
        assertThat(registry.getMeters().size())
                .as("the binder adds the series this caller did not reach, and doubles none")
                .isGreaterThan(beforeBinding);
        assertThat(registry.getMeters().stream()
                .filter(meter -> meter.getId().getName()
                        .equals(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER)).toList())
                .as("the abandoned series after both paths ran").hasSize(1);
    }

    @Test
    @DisplayName("both diagnostic outcomes are described, and no third outcome is accepted")
    void bothDiagnosticOutcomesAreDescribedAndNoThirdIsAccepted() {
        ObservabilityConfig.deadLetterCounter(registry, ObservabilityConfig.DIAGNOSTIC_PUBLISHED);
        ObservabilityConfig.deadLetterCounter(registry, ObservabilityConfig.DIAGNOSTIC_FAILED);

        assertThat(registry.getMeters()).extracting(meter -> meter.getId()
                        .getTag(ObservabilityConfig.OUTCOME_OF_DIAGNOSTIC_TAG))
                .as("the two outcomes of a terminal diagnostic")
                .containsExactlyInAnyOrder(ObservabilityConfig.DIAGNOSTIC_PUBLISHED,
                        ObservabilityConfig.DIAGNOSTIC_FAILED);
        assertThatThrownBy(() -> ObservabilityConfig.deadLetterCounter(registry, "retried"))
                .as("an outcome outside the closed set of two")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no component outside the observability configuration names a shared series")
    void noComponentOutsideTheConfigurationNamesASharedSeries() {
        Pattern builder = Pattern.compile("Counter\\.builder\\(([^)]*)\\)");
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java").toAbsolutePath();

        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("ObservabilityConfig.java"))
                    .sorted()
                    .forEach(path -> {
                        Matcher matcher = builder.matcher(read(path));
                        while (matcher.find()) {
                            String named = matcher.group(1);
                            SHARED_SERIES.keySet().stream()
                                    .map(MeterDeclarationTest::constantOf)
                                    .filter(named::contains)
                                    .forEach(hit -> offenders.add(
                                            root.relativize(path) + " builds " + hit));
                        }
                    });
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
        }

        assertThat(offenders)
                .as("each of these must call the accessor instead, so the description cannot"
                        + " depend on which component was constructed first")
                .isEmpty();
    }

    /** Applies the meter binder of this service to the open registry. */
    private void bind() {
        new ObservabilityConfig().authorizationMeters().bindTo(registry);
    }

    /**
     * Names the constant a shared series is referred to by in source.
     *
     * @param series the dotted meter name
     * @return the constant identifier holding that name
     */
    private static String constantOf(String series) {
        return switch (series) {
            case "carddemo.authorization.decisions" -> "DECISIONS_COUNTER";
            case "carddemo.authorization.events.written" -> "EVENTS_WRITTEN_COUNTER";
            case "carddemo.authorization.events.published" -> "EVENTS_PUBLISHED_COUNTER";
            case "carddemo.authorization.outbox.abandoned" -> "OUTBOX_ABANDONED_COUNTER";
            case "carddemo.authorization.dead.letters" -> "DEAD_LETTERS_COUNTER";
            case "carddemo.authorization.failures" -> "FAILURES_COUNTER";
            default -> throw new IllegalArgumentException("unmapped series: " + series);
        };
    }

    /**
     * Reads one file as text.
     *
     * @param path the file to read
     * @return its content
     */
    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}

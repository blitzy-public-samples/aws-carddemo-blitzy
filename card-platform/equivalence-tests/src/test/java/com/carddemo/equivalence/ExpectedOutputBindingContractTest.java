package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds every checked-in expected-output file to a test that reads it.
 *
 * <h2>What this class exists to prevent</h2>
 *
 * <p>An expected-output file that no test reads is the most misleading artefact this module can
 * carry. It has the shape of evidence — an evaluation model, a source locator per row, a Picture
 * clause per value — and it proves nothing, because the source can change, the services can change,
 * and the file can change, in any combination, without a build failing. Twelve of the fourteen files
 * were in exactly that state.</p>
 *
 * <p>This class closes that gap structurally rather than by review. It reads the
 * {@code expected/} directory from disk, so a file added later is covered the moment it lands, and
 * for each file it requires three things: the file parses under the canonical eight-column schema,
 * its comment line names the test class that consumes it, and that class's source really mentions
 * the file. The per-row half of the guarantee lives in the consuming classes themselves, each of
 * which asserts in {@code @AfterAll} that {@link ExpectedOutcomes#unconsumedRows()} is empty.</p>
 *
 * <p>Between the two halves, a row cannot be added without an assertion reading it, an assertion
 * cannot be deleted without the row becoming unconsumed, and a file cannot be added without a named
 * consumer.</p>
 */
class ExpectedOutputBindingContractTest {

    /** Directory on disk holding the expected-output files. */
    private static final Path EXPECTED_DIRECTORY =
            Path.of("src", "test", "resources", "expected");

    /** Directory on disk holding this module's test sources. */
    private static final Path TEST_SOURCE_DIRECTORY =
            Path.of("src", "test", "java", "com", "carddemo", "equivalence");

    /**
     * Phrase a comment line uses to name the tests that read the file.
     *
     * <p>Three phrasings are in use across the fourteen files — with and without a colon, and
     * singular or plural with the names joined by commas, by {@code and}, or by both. All three are
     * accepted, because the files are the evidence and this test exists to hold them to a reader,
     * not to impose a house style on their prose.</p>
     */
    private static final Pattern CONSUMING_TEST = Pattern.compile(
            "Consuming tests?:? +([A-Za-z0-9_]+(?: *(?:,|and) *[A-Za-z0-9_]+)*)");

    /** Separator between two class names in a comment line that names more than one. */
    private static final Pattern CONSUMER_SEPARATOR = Pattern.compile(" *(?:,|and) *");

    /** Extension every expected-output file carries. */
    private static final String CSV_SUFFIX = ".csv";

    /** Extension every test source carries. */
    private static final String JAVA_SUFFIX = ".java";

    /** File names present in the directory, sorted so failures read the same way every run. */
    private static final List<String> FILE_NAMES = readFileNames();

    /** Reads the expected-output file names from disk. */
    private static List<String> readFileNames() {
        Path directory = moduleRoot().resolve(EXPECTED_DIRECTORY);
        assertTrue(Files.isDirectory(directory), () -> directory + " must be a directory");
        try (var entries = Files.list(directory)) {
            return List.copyOf(new TreeSet<>(entries
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(CSV_SUFFIX))
                    .toList()));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /** Returns the equivalence module root, found by walking up to the directory holding it. */
    private static Path moduleRoot() {
        Path base = Path.of("").toAbsolutePath().normalize();
        while (base != null && !Files.isDirectory(base.resolve(EXPECTED_DIRECTORY))) {
            base = base.getParent();
        }
        if (base == null) {
            throw new AssertionError("the equivalence-tests module root was not found");
        }
        return base;
    }

    /** Reads one test source, or fails when it is absent. */
    private static String readTestSource(String simpleName) {
        Path source = moduleRoot().resolve(TEST_SOURCE_DIRECTORY).resolve(simpleName + JAVA_SUFFIX);
        assertTrue(Files.isRegularFile(source),
                () -> "a comment line names " + simpleName + " as a consuming test, so "
                        + source + " must exist");
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + source, unreadable);
        }
    }

    /**
     * Reports whether one test source really reads a file, rather than merely naming it.
     *
     * <p>Naming it in a comment is not reading it, and a check that accepted a mention would leave
     * the exact gap this class exists to close. A source counts as reading the file when it carries
     * the file name as a string literal <em>and</em> opens an expected-output resource, either
     * through {@link ExpectedOutcomes#load} or through the classpath path the two classes that
     * predate that reader use. Requiring the literal to sit inside the call would be stricter still
     * and would forbid naming the file in a constant, which every binding does so that the file name
     * can appear in its own test display names.</p>
     *
     * @param source   the test source
     * @param fileName the expected-output file name
     * @return {@code true} when the source opens the file
     */
    private static boolean readsTheFile(String source, String fileName) {
        boolean namesTheFile = source.contains("\"" + fileName + "\"")
                || source.contains("\"/expected/" + fileName + "\"");
        boolean opensAResource = source.contains("ExpectedOutcomes.load(")
                || source.contains("\"/expected/");
        return namesTheFile && opensAResource;
    }

    /** Returns the consuming test class names one comment line declares. */
    private static List<String> consumersDeclaredBy(ExpectedOutcomes expected) {
        Matcher matcher = CONSUMING_TEST.matcher(expected.preamble());
        assertTrue(matcher.find(),
                () -> "the comment line of " + expected.fileName() + " must name its consuming "
                        + "test, as \"Consuming test <ClassName>.\", so an unread file is a build "
                        + "failure rather than a discovery. Its comment line reads: "
                        + expected.preamble());
        return List.of(CONSUMER_SEPARATOR.split(matcher.group(1).trim()))
                .stream().map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    @Nested
    @DisplayName("Every expected-output file")
    class EveryFile {

        @Test
        @DisplayName("parses under the canonical eight-column schema")
        void parsesUnderTheCanonicalSchema() {
            assertThat(FILE_NAMES).as("the expected directory must hold files").isNotEmpty();

            for (String fileName : FILE_NAMES) {
                ExpectedOutcomes expected = ExpectedOutcomes.load(fileName);

                assertThat(expected.rows()).as(fileName + " must hold rows").isNotEmpty();
                assertThat(expected.preamble())
                        .as(fileName + " must carry a comment line describing its derivation")
                        .isNotBlank();
                assertEquals(expected.rows().size(), expected.unconsumedRows().size(),
                        fileName + ": a freshly loaded file has consumed nothing");
            }
        }

        @Test
        @DisplayName("names the test that reads it, and that test really reads it")
        void namesTheTestThatReadsIt() {
            Map<String, List<String>> unreferenced = new LinkedHashMap<>();

            for (String fileName : FILE_NAMES) {
                ExpectedOutcomes expected = ExpectedOutcomes.load(fileName);
                List<String> consumers = consumersDeclaredBy(expected);

                assertThat(consumers).as(fileName + " must name at least one consuming test")
                        .isNotEmpty();
                List<String> silent = new ArrayList<>();
                for (String consumer : consumers) {
                    if (!readsTheFile(readTestSource(consumer), fileName)) {
                        silent.add(consumer);
                    }
                }
                if (!silent.isEmpty()) {
                    unreferenced.put(fileName, silent);
                }
            }

            assertThat(unreferenced)
                    .as("each file's comment line names a consuming test that must reference the "
                            + "file by name. A name here is a class that claims a file it never "
                            + "reads")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries a source locator on every row")
        void carriesASourceLocatorOnEveryRow() {
            for (String fileName : FILE_NAMES) {
                ExpectedOutcomes expected = ExpectedOutcomes.load(fileName);

                for (ExpectedOutcomes.Row row : expected.rows()) {
                    assertThat(row.sourceLocator())
                            .as(fileName + " row " + row.key()
                                    + " must name where the expectation came from")
                            .isNotBlank();
                    assertThat(row.expectedField())
                            .as(fileName + " row " + row.key() + " must name a field")
                            .isNotBlank();
                    assertThat(row.picClause())
                            .as(fileName + " row " + row.key() + " must carry a Picture clause or "
                                    + ExpectedOutcomes.NO_PIC_CLAUSE)
                            .isNotBlank();
                }
            }
        }
    }

    @Nested
    @DisplayName("The reader itself")
    class TheReader {

        @Test
        @DisplayName("splits a quoted value holding a comma into one field")
        void splitsAQuotedValueHoldingACommaIntoOneField() {
            ExpectedOutcomes expected =
                    ExpectedOutcomes.load("dailytran-decimal-truncation-model-b.csv");

            assertEquals("add amount to category balance, create branch",
                    expected.value("SITE-02", "2700-A-CREATE-TCATBAL-REC", "arithmetic_operation"),
                    "a value quoted in the manner of RFC 4180 holds its comma, so splitting on "
                            + "every comma would shear this row into nine columns");
        }

        @Test
        @DisplayName("keeps a record sequence that is a label rather than a number")
        void keepsARecordSequenceThatIsALabelRatherThanANumber() {
            ExpectedOutcomes expected = ExpectedOutcomes.load("validation-messages.csv");

            assertThat(expected.rows())
                    .as("record_seq carries labels as well as ordinals, so it is held as text")
                    .anyMatch(row -> "CONTRAST".equals(row.recordSequence()));
        }

        @Test
        @DisplayName("marks a row consumed only when an assertion reads its value")
        void marksARowConsumedOnlyWhenAnAssertionReadsItsValue() {
            ExpectedOutcomes expected = ExpectedOutcomes.load("posting-summary.csv");
            int total = expected.rows().size();

            assertEquals(total, expected.unconsumedRows().size(),
                    "listing rows must not count as reading them");
            expected.count("posting", "record_count");
            assertEquals(total - 1, expected.unconsumedRows().size(),
                    "reading one value consumes exactly its own row");
        }
    }
}

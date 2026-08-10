package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds Rule 1's rationale location, and the attachment of every javadoc block, to the build.
 *
 * <p>Rule 1 of this project makes {@code card-platform/docs/decision-log.md} the single source of
 * "why". A code comment carries mechanics, provenance and source locators; the decision, the
 * alternatives weighed against it and the risks accepted belong in the log. A review found decision
 * essays in twenty-four migration headers and in javadoc across the services, and found fifteen
 * javadoc blocks separated from the declaration they described by a second javadoc block. Both were
 * corrected once. This class is what keeps them corrected.
 *
 * <p>Three properties are read out of the shipped text of the whole platform. No migration or demo
 * header opens a rationale banner. No javadoc heading frames its block as a rationale essay. No
 * javadoc block is immediately followed by another javadoc block, which matters because the compiler
 * and every generated document attach the second block and leave the first describing a declaration
 * it does not belong to. A fourth property keeps the pointers usable: a shipped file that sends a
 * reader to the log names it at one path, and that path resolves.
 *
 * <p>A rule that matches nothing passes by finding nothing, so
 * {@link #theDetectorsFireOnTextCarryingEachDefect()} runs all three detectors over text that
 * carries each defect and over text that carries none.
 */
@DisplayName("Rationale location and javadoc attachment, whole platform")
class RationaleLocationContractTest {

    /** Directory below the repository root holding this platform. */
    private static final String PLATFORM_DIRECTORY = "card-platform";

    /** Path below the platform root to the one decision log. */
    private static final String DECISION_LOG = "docs/decision-log.md";

    /** The spelling every shipped file uses when it sends a reader to the log. */
    private static final String DECISION_LOG_POINTER = "card-platform/docs/decision-log.md";

    /** The six service module directory names. */
    private static final List<String> MODULES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    /** Path below a service module to its Flyway locations, both the shipped one and the demo one. */
    private static final String DATABASE_RESOURCES = "src/main/resources/db";

    /** Path below a service module to the sources a reader of the shipped code sees. */
    private static final String MAIN_SOURCES = "src/main/java";

    /**
     * Banner headings, in capitals, that open a rationale essay in a migration header.
     *
     * <p>Capitals are read exactly. {@code -- What the rule reads.} and {@code -- Where it applies.}
     * are mechanics headings and stay; {@code -- WHY THIS FILE EXISTS} and {@code -- WHAT WENT WRONG}
     * are the banner form the review named.
     */
    private static final Pattern SQL_BANNER_HEADING =
            Pattern.compile("^\\s*--\\s*(WHY|WHAT|HOW|ALTERNATIVES?|RISKS?)\\b");

    /** Sentence-case headings that open the same essay. */
    private static final Pattern SQL_RATIONALE_HEADING =
            Pattern.compile("^\\s*--\\s*(Why\\b|Alternatives considered|Risks accepted)");

    /**
     * Javadoc headings that frame a block as a rationale essay rather than as a description.
     *
     * <p>Both heading forms this codebase uses are read: a section heading such as
     * {@code <h2>Why ...</h2>} and a run-in heading such as {@code <p><b>Why ...</b>}.
     */
    private static final Pattern JAVADOC_RATIONALE_HEADING = Pattern.compile(
            "<(?:h[1-6]|b)>\\s*(?:Why\\b|Alternatives considered|Risks accepted)");

    /**
     * One javadoc block whose next line opens another javadoc block.
     *
     * <p>A line break is required between the two, because that is the shape the defect takes: the
     * closing delimiter of the first block sits on its own line and the opening delimiter of the
     * second sits on the next.
     */
    private static final Pattern CONSECUTIVE_JAVADOC =
            Pattern.compile("\\*/[ \\t]*\\R\\s*/\\*\\*");

    /**
     * This file, which the java scans skip.
     *
     * <p>It holds the detectors and the samples {@link #theDetectorsFireOnTextCarryingEachDefect()}
     * runs them over, so its own text carries every shape the detectors look for. Reading it would
     * report the negative controls as violations.
     */
    private static final String THIS_FILE = "RationaleLocationContractTest.java";

    /** Floor on the java files a scan must reach before its verdict means anything. */
    private static final int JAVA_SOURCE_FLOOR = 400;

    /** Floor on the migration files a scan must reach before its verdict means anything. */
    private static final int MIGRATION_FLOOR = 40;

    @Test
    @DisplayName("no migration or demo header opens a rationale essay")
    void noMigrationHeaderOpensARationaleEssay() {
        List<Path> migrations = migrationSources();
        assertTrue(migrations.size() >= MIGRATION_FLOOR,
                "the scan reached " + migrations.size() + " migration files, which is fewer than the "
                        + MIGRATION_FLOOR + " this platform ships, so its verdict would mean nothing");

        List<String> offending = new ArrayList<>();
        for (Path migration : migrations) {
            int number = 0;
            for (String line : readText(migration).split("\\R", -1)) {
                number++;
                if (SQL_BANNER_HEADING.matcher(line).find()
                        || SQL_RATIONALE_HEADING.matcher(line).find()) {
                    offending.add(relative(migration) + ":" + number + " " + line.trim());
                }
            }
        }

        assertEquals(List.of(), offending,
                "Rule 1 makes " + DECISION_LOG_POINTER + " the single source of \"why\", and a "
                        + "decision recorded in two places drifts: state what the file declares, its "
                        + "source locators and a pointer to the log instead: " + offending);
    }

    @Test
    @DisplayName("no javadoc heading frames its block as a rationale essay")
    void noJavadocHeadingFramesARationaleEssay() {
        List<Path> sources = javaSources();
        assertTrue(sources.size() >= JAVA_SOURCE_FLOOR,
                "the scan reached " + sources.size() + " java files, which is fewer than the "
                        + JAVA_SOURCE_FLOOR + " this platform ships, so its verdict would mean nothing");

        List<String> offending = new ArrayList<>();
        for (Path source : sources) {
            int number = 0;
            for (String line : readText(source).split("\\R", -1)) {
                number++;
                if (JAVADOC_RATIONALE_HEADING.matcher(line).find()) {
                    offending.add(relative(source) + ":" + number + " " + line.trim());
                }
            }
        }

        assertEquals(List.of(), offending,
                "a heading that opens with \"Why\" frames the block below it as a decision essay, "
                        + "which belongs in " + DECISION_LOG_POINTER + "; name what the block "
                        + "describes instead: " + offending);
    }

    @Test
    @DisplayName("no javadoc block is immediately followed by another javadoc block")
    void noJavadocBlockIsImmediatelyFollowedByAnother() {
        List<Path> sources = javaSources();
        List<String> offending = new ArrayList<>();

        for (Path source : sources) {
            String text = readText(source);
            Matcher consecutive = CONSECUTIVE_JAVADOC.matcher(text);
            while (consecutive.find()) {
                offending.add(relative(source) + ":" + lineOf(text, consecutive.start()));
            }
        }

        assertEquals(List.of(), offending,
                "the compiler and every generated document attach the second block, so the first is "
                        + "read as documenting a declaration it does not describe: merge the pair or "
                        + "move the first above the declaration it belongs to: " + offending);
    }

    @Test
    @DisplayName("every shipped pointer to the decision log names one path, and it resolves")
    void everyShippedPointerToTheDecisionLogResolves() {
        Path log = platformRoot().resolve(DECISION_LOG);
        assertTrue(Files.isRegularFile(log),
                "the file every shipped pointer names is missing: " + log);

        List<Path> shipped = new ArrayList<>(migrationSources());
        shipped.addAll(shippedJavaSources());

        List<String> offending = new ArrayList<>();
        int pointers = 0;
        for (Path source : shipped) {
            int number = 0;
            for (String line : readText(source).split("\\R", -1)) {
                number++;
                int at = line.indexOf("decision-log.md");
                if (at < 0) {
                    continue;
                }
                pointers++;
                if (!line.contains(DECISION_LOG_POINTER)) {
                    offending.add(relative(source) + ":" + number + " " + line.trim());
                }
            }
        }

        assertTrue(pointers >= MIGRATION_FLOOR,
                "the scan found " + pointers + " pointers to the decision log, which is fewer than "
                        + "the files that carry one, so its verdict would mean nothing");
        assertEquals(List.of(), offending,
                "a pointer a reader cannot follow from the repository root is not a pointer: name "
                        + DECISION_LOG_POINTER + ": " + offending);
    }

    @Test
    @DisplayName("the detectors fire on text carrying each defect and stay silent on text that does not")
    void theDetectorsFireOnTextCarryingEachDefect() {
        assertTrue(SQL_BANNER_HEADING.matcher("-- WHY THIS FILE EXISTS").find(),
                "the banner detector missed a capitalised rationale banner");
        assertTrue(SQL_BANNER_HEADING.matcher("-- WHAT WENT WRONG. With ON CONFLICT (event_id)").find(),
                "the banner detector missed a capitalised banner carrying prose");
        assertTrue(SQL_RATIONALE_HEADING.matcher("-- Why VARCHAR and not CHAR.").find(),
                "the heading detector missed a sentence-case rationale heading");
        assertTrue(SQL_RATIONALE_HEADING.matcher("-- Alternatives considered: a version column").find(),
                "the heading detector missed an alternatives heading");
        assertFalse(SQL_BANNER_HEADING.matcher("-- What the rule reads. Reason code 103 approves").find(),
                "a mechanics heading was read as a rationale banner");
        assertFalse(SQL_RATIONALE_HEADING.matcher("-- What the rule reads.").find(),
                "a mechanics heading was read as a rationale heading");
        assertFalse(SQL_BANNER_HEADING.matcher("-- RECORDSIZE(300 300) declared by the dataset definition").find(),
                "a dataset token was read as a rationale banner");

        assertTrue(JAVADOC_RATIONALE_HEADING.matcher(" * <h2>Why one pass is several transactions</h2>").find(),
                "the javadoc detector missed a section heading");
        assertTrue(JAVADOC_RATIONALE_HEADING.matcher(" * <p><b>Why this test exists.</b> The same").find(),
                "the javadoc detector missed a run-in heading");
        assertFalse(JAVADOC_RATIONALE_HEADING.matcher(" * <h2>One pass is several transactions</h2>").find(),
                "a descriptive heading was read as a rationale heading");

        assertTrue(CONSECUTIVE_JAVADOC.matcher("    /** One. */\n    /**\n     * Two.\n     */\n").find(),
                "the attachment detector missed a javadoc block followed by another");
        assertFalse(CONSECUTIVE_JAVADOC.matcher("    /** One. */\n    private int one;\n").find(),
                "a javadoc block followed by its declaration was read as unattached");
    }

    /** Returns every java file of the platform, build output and this file excluded. */
    private static List<Path> javaSources() {
        return filesUnder(platformRoot(), ".java").stream()
                .filter(path -> !THIS_FILE.equals(path.getFileName().toString()))
                .toList();
    }

    /** Returns the java files a reader of the shipped code sees, tests excluded. */
    private static List<Path> shippedJavaSources() {
        List<Path> sources = new ArrayList<>();
        for (String module : MODULES) {
            sources.addAll(filesUnder(
                    platformRoot().resolve("services").resolve(module).resolve(MAIN_SOURCES), ".java"));
        }
        return sources;
    }

    /** Returns every migration and demo overlay of the six services. */
    private static List<Path> migrationSources() {
        List<Path> migrations = new ArrayList<>();
        for (String module : MODULES) {
            migrations.addAll(filesUnder(
                    platformRoot().resolve("services").resolve(module).resolve(DATABASE_RESOURCES),
                    ".sql"));
        }
        return migrations;
    }

    /** Walks one directory, returning the files with the given extension in a stable order. */
    private static List<Path> filesUnder(Path directory, String extension) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .filter(path -> !carriesBuildOutput(path))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("unreadable directory " + directory, unreadable);
        }
    }

    /** Reports whether a path passes through a build output directory. */
    private static boolean carriesBuildOutput(Path path) {
        for (Path element : path) {
            if ("target".equals(element.toString())) {
                return true;
            }
        }
        return false;
    }

    /** Returns the one-based line number an offset falls on. */
    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int at = 0; at < offset; at++) {
            if (text.charAt(at) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Returns a path as a reader of the repository would name it. */
    private static String relative(Path path) {
        return repositoryRoot().relativize(path).toString();
    }

    /** Returns the platform root, which holds every module and every document. */
    private static Path platformRoot() {
        return repositoryRoot().resolve(PLATFORM_DIRECTORY);
    }

    /** Returns the repository root, reached from the fixture directory the loader publishes. */
    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent();
    }

    /** Reads one file as text. */
    private static String readText(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("unreadable file " + path, unreadable);
        }
    }
}

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <h2>What a co-presence check missed</h2>
 *
 * <p>The first version of this guard asked two questions separately: does the consuming source carry
 * the file name as a string literal anywhere, and does it call the reader anywhere. Both answers were
 * yes for every class, and they stayed yes no matter which file each call actually opened. A class
 * naming ten files and loading one passed. A constant renamed to a different file passed, because the
 * old name still sat in a Javadoc string. The check could not fail for the reason it existed.</p>
 *
 * <p>This version resolves the binding instead of sampling for it. For each consuming class it reads
 * the {@code String} constants the class declares, walks every {@link ExpectedOutcomes#load} call and
 * every {@code getResourceAsStream} call, and resolves each argument — a literal to its own text, an
 * identifier through the constant table — to the file that call opens. A file is bound to a class only
 * when a call site in that class resolves to it. An argument the resolver cannot follow fails the
 * build rather than passing quietly, because a binding nobody can resolve is the state this class
 * exists to refuse.</p>
 *
 * <h2>The four properties this class holds</h2>
 *
 * <ol>
 *   <li>Every file on disk parses under the canonical eight-column schema and carries a source
 *       locator, a field name and a Picture clause on every row.</li>
 *   <li>Every consuming class a file's comment line names really opens that file, proven by a
 *       resolved call site rather than by a mention.</li>
 *   <li>Every class that opens a file is named on that file's comment line, so the declaration
 *       cannot under-claim its readers either.</li>
 *   <li>Every file's rows demonstrably reach assertions: a file read through the shared reader is
 *       closed by an {@link ExpectedOutcomes#unconsumedRows()} assertion on the field bound to it,
 *       and the one file read directly from the classpath is closed by an assertion comparing its
 *       declared row count against the rows parsed out of it.</li>
 * </ol>
 *
 * <p>Between those four, a row cannot be added without an assertion reading it, an assertion cannot
 * be deleted without the row becoming unconsumed, a file cannot be added without a named consumer,
 * and a consumer cannot be named without opening what it claims.</p>
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

    /**
     * Suffix every class that consumes expected output carries.
     *
     * <p>The scan is scoped to these classes deliberately. An equivalence test reads a file to hold
     * the platform to it, which is a consuming relationship a file's comment line has to declare. A
     * contract test — this class included — reads the same files to validate them, and declaring it
     * as a consumer of every expectation would make the declarations meaningless.</p>
     */
    private static final String CONSUMER_SUFFIX = "EquivalenceTest";

    /** Declaration of a {@code String} constant, which a call site may name instead of a literal. */
    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Za-z0-9_]+)\\s*=\\s*\"([^\"]*)\"\\s*;");

    /** Declaration of an {@code int} constant, which a row-count assertion may name. */
    private static final Pattern INT_CONSTANT = Pattern.compile(
            "static\\s+final\\s+int\\s+([A-Za-z0-9_]+)\\s*=\\s*(\\d+)\\s*;");

    /** A call that reads an expected-output file through the shared reader. */
    private static final Pattern LOAD_CALL = Pattern.compile(
            "ExpectedOutcomes\\.load\\(\\s*([^()]*?)\\s*\\)");

    /** A call that reads a classpath resource as bytes, which the one direct reader uses. */
    private static final Pattern STREAM_CALL = Pattern.compile(
            "getResourceAsStream\\(\\s*([^()]*?)\\s*\\)");

    /** Declaration of a field holding a loaded file, whose name a closure assertion names. */
    private static final Pattern OUTCOMES_FIELD = Pattern.compile(
            "static\\s+final\\s+ExpectedOutcomes\\s+([A-Za-z0-9_]+)\\s*=\\s*"
                    + "ExpectedOutcomes\\.load\\(\\s*([^()]*?)\\s*\\)");

    /** Characters that end the statement preceding an occurrence. */
    private static final String STATEMENT_BREAKS = ";{}";

    /** Call that reports the rows of a loaded file no assertion has read. */
    private static final String CLOSURE_CALL = ".unconsumedRows()";

    /** File names present in the directory, sorted so failures read the same way every run. */
    private static final List<String> FILE_NAMES = readFileNames();

    /** Source of every consuming class, keyed by simple name and ordered by it. */
    private static final Map<String, String> CONSUMER_SOURCES = readConsumerSources();

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

    /** Reads the source of every class whose name marks it a consumer of expected output. */
    private static Map<String, String> readConsumerSources() {
        Path directory = moduleRoot().resolve(TEST_SOURCE_DIRECTORY);
        assertTrue(Files.isDirectory(directory), () -> directory + " must be a directory");
        Map<String, String> sources = new LinkedHashMap<>();
        try (var entries = Files.list(directory)) {
            for (Path path : entries.sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(CONSUMER_SUFFIX + JAVA_SUFFIX)) {
                    sources.put(name.substring(0, name.length() - JAVA_SUFFIX.length()),
                            Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the sources under " + directory, unreadable);
        }
        return Collections.unmodifiableMap(sources);
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

    /** Returns the source of one consuming class, or fails when there is no such source. */
    private static String consumerSource(String simpleName, String fileName) {
        String source = CONSUMER_SOURCES.get(simpleName);
        assertTrue(source != null,
                () -> "the comment line of " + fileName + " names " + simpleName + " as a consuming "
                        + "test, so " + TEST_SOURCE_DIRECTORY.resolve(simpleName + JAVA_SUFFIX)
                        + " must exist and its name must end in " + CONSUMER_SUFFIX
                        + ". The sources this guard scans are " + CONSUMER_SOURCES.keySet());
        return source;
    }

    /** Returns every {@code String} constant one source declares, keyed by constant name. */
    private static Map<String, String> stringConstantsIn(String source) {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher declaration = STRING_CONSTANT.matcher(source);
        while (declaration.find()) {
            constants.put(declaration.group(1), declaration.group(2));
        }
        return constants;
    }

    /** Returns every {@code int} constant one source declares, keyed by constant name. */
    private static Map<String, Integer> intConstantsIn(String source) {
        Map<String, Integer> constants = new LinkedHashMap<>();
        Matcher declaration = INT_CONSTANT.matcher(source);
        while (declaration.find()) {
            constants.put(declaration.group(1), Integer.valueOf(declaration.group(2)));
        }
        return constants;
    }

    /**
     * Resolves one call argument to the expected-output file that call opens.
     *
     * <p>Two argument forms resolve: a string literal, which carries its own answer, and an
     * identifier declared as a {@code String} constant in the same source, which is the form every
     * binding in this module uses so that the file name can appear in test display names. A resource
     * path is accepted under {@link ExpectedOutcomes#RESOURCE_DIRECTORY} and reduced to the file
     * name, so the direct reader and the shared reader resolve to the same answer.</p>
     *
     * @param argument  the argument text, exactly as it appears at the call site
     * @param constants the {@code String} constants the calling source declares
     * @return the file the call opens, or empty when the argument names no expected-output file
     */
    private static Optional<String> resolveExpectedFile(
            String argument, Map<String, String> constants) {
        String value;
        if (argument.length() > 1 && argument.startsWith("\"") && argument.endsWith("\"")) {
            value = argument.substring(1, argument.length() - 1);
        } else if (constants.containsKey(argument)) {
            value = constants.get(argument);
        } else {
            return Optional.empty();
        }
        if (value.startsWith(ExpectedOutcomes.RESOURCE_DIRECTORY)) {
            value = value.substring(ExpectedOutcomes.RESOURCE_DIRECTORY.length());
        }
        return value.endsWith(CSV_SUFFIX) && !value.contains("/") ? Optional.of(value)
                : Optional.empty();
    }

    /**
     * The expected-output files one source opens, and the reader calls that resolve to nothing.
     *
     * @param files      the files call sites in the source resolve to
     * @param unresolved reader calls whose argument the resolver could not follow
     */
    private record ReadingCallSites(Set<String> files, List<String> unresolved) {
    }

    /**
     * Resolves every reading call site in one source.
     *
     * <p>A {@link ExpectedOutcomes#load} argument that resolves to nothing is reported rather than
     * ignored: the shared reader opens expected output and nothing else, so an argument this guard
     * cannot follow is a binding it cannot hold. A {@code getResourceAsStream} argument that resolves
     * to nothing is ignored, because that call reads fixtures and configuration as well.</p>
     *
     * @param source the source text to scan
     * @return the resolved files and the unresolved reader calls
     */
    private static ReadingCallSites readingCallSitesIn(String source) {
        Map<String, String> constants = stringConstantsIn(source);
        Set<String> files = new TreeSet<>();
        List<String> unresolved = new ArrayList<>();
        Matcher load = LOAD_CALL.matcher(source);
        while (load.find()) {
            String argument = load.group(1);
            Optional<String> resolved = resolveExpectedFile(argument, constants);
            if (resolved.isPresent()) {
                files.add(resolved.get());
            } else {
                unresolved.add("ExpectedOutcomes.load(" + argument + ")");
            }
        }
        Matcher stream = STREAM_CALL.matcher(source);
        while (stream.find()) {
            resolveExpectedFile(stream.group(1), constants).ifPresent(files::add);
        }
        return new ReadingCallSites(Set.copyOf(files), List.copyOf(unresolved));
    }

    /** Reports whether one source opens one file through the shared reader. */
    private static boolean readsThroughTheSharedReader(String source, String fileName) {
        Map<String, String> constants = stringConstantsIn(source);
        Matcher load = LOAD_CALL.matcher(source);
        while (load.find()) {
            if (resolveExpectedFile(load.group(1), constants).filter(fileName::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Returns the loaded fields one source declares, keyed by field name and valued by file. */
    private static Map<String, String> loadedFieldsIn(String source) {
        Map<String, String> constants = stringConstantsIn(source);
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher declaration = OUTCOMES_FIELD.matcher(source);
        while (declaration.find()) {
            String field = declaration.group(1);
            resolveExpectedFile(declaration.group(2), constants)
                    .ifPresent(file -> fields.put(field, file));
        }
        return fields;
    }

    /** Returns the text of the statement ending at one offset, back to the previous break. */
    private static String statementEndingAt(String source, int offset) {
        int start = offset;
        while (start > 0 && STATEMENT_BREAKS.indexOf(source.charAt(start - 1)) < 0) {
            start--;
        }
        return source.substring(start, offset);
    }

    /** Reports whether one source asserts on an expression, rather than merely writing it. */
    private static boolean assertsOn(String source, String expression) {
        int at = source.indexOf(expression);
        while (at >= 0) {
            if (statementEndingAt(source, at).contains("assert")) {
                return true;
            }
            at = source.indexOf(expression, at + 1);
        }
        return false;
    }

    /**
     * Reports whether one source proves that every row of one file reaches an assertion.
     *
     * <p>Which proof is required follows from how the source reads the file. A source that goes
     * through {@link ExpectedOutcomes} has to assert {@link ExpectedOutcomes#unconsumedRows()} on the
     * field bound to that file, which is the reader's own accounting and holds however the runner
     * orders the class. A source that reads the resource itself has to assert a declared row count
     * against the rows it parsed, and the count it declares has to be the count the file really
     * holds — so a row added to the file fails the build until the declaration follows it.</p>
     *
     * @param source   the source of a declared consumer
     * @param fileName the expected-output file
     * @param rowCount the data rows the file really holds
     * @return {@code true} when this source closes the loop over that file
     */
    private static boolean provesRowClosure(String source, String fileName, int rowCount) {
        if (readsThroughTheSharedReader(source, fileName)) {
            return loadedFieldsIn(source).entrySet().stream()
                    .filter(field -> fileName.equals(field.getValue()))
                    .anyMatch(field -> assertsOn(source, field.getKey() + CLOSURE_CALL));
        }
        return intConstantsIn(source).entrySet().stream()
                .filter(constant -> constant.getValue() == rowCount)
                .anyMatch(constant -> Pattern.compile("assert\\w*\\(\\s*"
                                + Pattern.quote(constant.getKey()) + "\\s*,[^;]*?\\.size\\(\\)")
                        .matcher(source).find());
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
        @DisplayName("is opened by a resolved call site in every test it names")
        void isOpenedByAResolvedCallSiteInEveryTestItNames() {
            Map<String, List<String>> unbound = new LinkedHashMap<>();

            for (String fileName : FILE_NAMES) {
                ExpectedOutcomes expected = ExpectedOutcomes.load(fileName);
                List<String> consumers = consumersDeclaredBy(expected);

                assertThat(consumers).as(fileName + " must name at least one consuming test")
                        .isNotEmpty();
                List<String> silent = new ArrayList<>();
                for (String consumer : consumers) {
                    String source = consumerSource(consumer, fileName);
                    if (!readingCallSitesIn(source).files().contains(fileName)) {
                        silent.add(consumer);
                    }
                }
                if (!silent.isEmpty()) {
                    unbound.put(fileName, silent);
                }
            }

            assertThat(unbound)
                    .as("a declared consumer has to open the file it claims, proven by a reader "
                            + "call whose argument resolves to that file name. A class listed here "
                            + "names the file somewhere and opens something else, which is the "
                            + "state a co-presence check could not tell apart from a real binding")
                    .isEmpty();
        }

        @Test
        @DisplayName("names every test that opens it")
        void namesEveryTestThatOpensIt() {
            Map<String, Set<String>> declared = new LinkedHashMap<>();
            for (String fileName : FILE_NAMES) {
                declared.put(fileName,
                        new LinkedHashSet<>(consumersDeclaredBy(ExpectedOutcomes.load(fileName))));
            }

            Map<String, Set<String>> undeclared = new LinkedHashMap<>();
            for (Map.Entry<String, String> consumer : CONSUMER_SOURCES.entrySet()) {
                for (String fileName : readingCallSitesIn(consumer.getValue()).files()) {
                    if (!declared.getOrDefault(fileName, Set.of()).contains(consumer.getKey())) {
                        undeclared.computeIfAbsent(fileName, unused -> new LinkedHashSet<>())
                                .add(consumer.getKey());
                    }
                }
            }

            assertThat(undeclared)
                    .as("a file's comment line is the declaration this module publishes and "
                            + "equivalence-results.md repeats, so a class that opens a file has to "
                            + "be named on it. Each entry here is a reader the file does not admit "
                            + "to, which understates what a change to that file breaks")
                    .isEmpty();
        }

        @Test
        @DisplayName("is the file every reader call in a consuming class resolves to")
        void isTheFileEveryReaderCallInAConsumingClassResolvesTo() {
            assertThat(CONSUMER_SOURCES)
                    .as("the classes ending in " + CONSUMER_SUFFIX + " are the consumers this guard "
                            + "scans, and finding none would make every other check vacuous")
                    .isNotEmpty();

            Map<String, List<String>> unresolved = new LinkedHashMap<>();
            Map<String, Set<String>> absent = new LinkedHashMap<>();
            for (Map.Entry<String, String> consumer : CONSUMER_SOURCES.entrySet()) {
                ReadingCallSites sites = readingCallSitesIn(consumer.getValue());
                if (!sites.unresolved().isEmpty()) {
                    unresolved.put(consumer.getKey(), sites.unresolved());
                }
                Set<String> missing = new LinkedHashSet<>(sites.files());
                missing.removeAll(FILE_NAMES);
                if (!missing.isEmpty()) {
                    absent.put(consumer.getKey(), missing);
                }
            }

            assertThat(unresolved)
                    .as("every reader call in a consuming class has to name a file this guard can "
                            + "resolve — a literal, or a String constant the same class declares. "
                            + "An argument computed at run time reads a file nothing here can bind, "
                            + "so it is refused rather than skipped")
                    .isEmpty();
            assertThat(absent)
                    .as("a resolved call site has to name a file that exists under "
                            + EXPECTED_DIRECTORY + ". A name here is a stale constant, which fails "
                            + "at run time today and would pass a check that only looked for a "
                            + "reader call somewhere in the class")
                    .isEmpty();
        }

        @Test
        @DisplayName("has a consumer that proves every row reaches an assertion")
        void hasAConsumerThatProvesEveryRowReachesAnAssertion() {
            Map<String, List<String>> unclosed = new LinkedHashMap<>();

            for (String fileName : FILE_NAMES) {
                ExpectedOutcomes expected = ExpectedOutcomes.load(fileName);
                List<String> consumers = consumersDeclaredBy(expected);
                int rowCount = expected.rows().size();

                boolean closed = consumers.stream().anyMatch(consumer ->
                        provesRowClosure(consumerSource(consumer, fileName), fileName, rowCount));
                if (!closed) {
                    unclosed.put(fileName, consumers);
                }
            }

            assertThat(unclosed)
                    .as("every file needs one declared consumer that closes the loop: an "
                            + CLOSURE_CALL + " assertion on the field bound to it, or — for a file "
                            + "read straight off the classpath — an assertion comparing its "
                            + "declared row count against the rows parsed out of it. Without that, "
                            + "a row can be added and no assertion has to read it")
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

    /**
     * The resolver this class binds with, held to the cases that made the first version useless.
     *
     * <p>A guard is worth what its false answers cost, so each case here is a source that the
     * co-presence check accepted and the resolver has to reject. They run against source text written
     * inline rather than against the module's own classes, so the guard can be shown failing without
     * breaking a real binding to demonstrate it.</p>
     */
    @Nested
    @DisplayName("The binding resolver")
    class TheResolver {

        /** File name used as the subject of a synthetic source. */
        private static final String SUBJECT = "posting-summary.csv";

        /** A second file name, used where a source has to open something other than the subject. */
        private static final String OTHER = "validation-messages.csv";

        @Test
        @DisplayName("follows a constant to the file its call site opens")
        void followsAConstantToTheFileItsCallSiteOpens() {
            String source = """
                    private static final String SUBJECT_FILE = "posting-summary.csv";
                    private static final ExpectedOutcomes SUBJECT = ExpectedOutcomes.load(SUBJECT_FILE);
                    """;

            assertThat(readingCallSitesIn(source).files())
                    .as("the argument is an identifier the same source declares, which is the form "
                            + "every binding in this module uses")
                    .containsExactly(SUBJECT);
        }

        @Test
        @DisplayName("does not bind a file a source only mentions")
        void doesNotBindAFileASourceOnlyMentions() {
            String source = """
                    /** Compared against posting-summary.csv, which this class does not open. */
                    private static final String NOTE = "posting-summary.csv";
                    private static final String READ_FILE = "validation-messages.csv";
                    private static final ExpectedOutcomes READ = ExpectedOutcomes.load(READ_FILE);
                    """;

            Set<String> opened = readingCallSitesIn(source).files();

            assertThat(opened)
                    .as("naming a file in a comment and in an unused constant is not opening it. "
                            + "This is the source shape the co-presence check accepted")
                    .doesNotContain(SUBJECT);
            assertThat(opened).as("the file the call site resolves to is the one it opens")
                    .containsExactly(OTHER);
        }

        @Test
        @DisplayName("refuses a reader call whose argument it cannot follow")
        void refusesAReaderCallWhoseArgumentItCannotFollow() {
            String source = """
                    private static final String SUBJECT_FILE = "posting-summary.csv";
                    void read(String chosen) {
                        ExpectedOutcomes.load(chosen);
                    }
                    """;

            ReadingCallSites sites = readingCallSitesIn(source);

            assertThat(sites.unresolved())
                    .as("an argument computed at run time is reported, so a consuming class cannot "
                            + "hide a binding behind a variable")
                    .containsExactly("ExpectedOutcomes.load(chosen)");
            assertThat(sites.files())
                    .as("the constant is declared and never passed to the reader, so nothing binds")
                    .isEmpty();
        }

        @Test
        @DisplayName("reduces a classpath path to the file it names")
        void reducesAClasspathPathToTheFileItNames() {
            String source = """
                    private static final String CENSUS = "/expected/posting-summary.csv";
                    var lines = Subject.class.getResourceAsStream(CENSUS);
                    """;

            assertThat(readingCallSitesIn(source).files())
                    .as("the direct reader and the shared reader have to resolve to one answer, or "
                            + "the file that predates the shared reader could never be bound")
                    .containsExactly(SUBJECT);
        }

        @Test
        @DisplayName("reads a closure assertion only through the field bound to the file")
        void readsAClosureAssertionOnlyThroughTheFieldBoundToTheFile() {
            String closed = """
                    private static final String SUBJECT_FILE = "posting-summary.csv";
                    private static final ExpectedOutcomes SUBJECT = ExpectedOutcomes.load(SUBJECT_FILE);
                    void check() {
                        assertThat(SUBJECT.unconsumedRows()).isEmpty();
                    }
                    """;
            String openLoop = """
                    private static final String SUBJECT_FILE = "posting-summary.csv";
                    private static final String OTHER_FILE = "validation-messages.csv";
                    private static final ExpectedOutcomes SUBJECT = ExpectedOutcomes.load(SUBJECT_FILE);
                    private static final ExpectedOutcomes OTHER = ExpectedOutcomes.load(OTHER_FILE);
                    void check() {
                        assertThat(OTHER.unconsumedRows()).isEmpty();
                    }
                    """;
            String written = """
                    private static final String SUBJECT_FILE = "posting-summary.csv";
                    private static final ExpectedOutcomes SUBJECT = ExpectedOutcomes.load(SUBJECT_FILE);
                    void check() {
                        var outstanding = SUBJECT.unconsumedRows();
                    }
                    """;

            assertTrue(provesRowClosure(closed, SUBJECT, 20),
                    "an assertion on the field bound to the file closes its rows");
            assertThat(provesRowClosure(openLoop, SUBJECT, 20))
                    .as("closing a sibling file leaves this one open, so the field the assertion "
                            + "names has to be the field bound to this file")
                    .isFalse();
            assertThat(provesRowClosure(written, SUBJECT, 20))
                    .as("reading the outstanding rows into a variable asserts nothing")
                    .isFalse();
        }

        @Test
        @DisplayName("accepts a declared row count only when it is the count the file holds")
        void acceptsADeclaredRowCountOnlyWhenItIsTheCountTheFileHolds() {
            String source = """
                    private static final String CENSUS = "/expected/posting-summary.csv";
                    private static final int CENSUS_DATA_ROW_COUNT = 20;
                    void check() {
                        assertEquals(CENSUS_DATA_ROW_COUNT, rows().size(), "declared row count");
                    }
                    """;

            assertTrue(provesRowClosure(source, SUBJECT, 20),
                    "a file read straight off the classpath closes its rows by declaring how many "
                            + "it holds and asserting that against the rows it parsed");
            assertThat(provesRowClosure(source, SUBJECT, 21))
                    .as("the declared count has to be the count the file really holds, so adding a "
                            + "row fails the build until the declaration follows it")
                    .isFalse();
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

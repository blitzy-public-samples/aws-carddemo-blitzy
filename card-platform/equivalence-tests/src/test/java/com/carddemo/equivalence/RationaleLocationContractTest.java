package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * A rationale banner in capitals, in any shipped text format.
     *
     * <p>Every comment marker this platform ships is read: {@code *}, {@code //}, {@code --} and
     * {@code #}. A banner word alone is not enough, because {@code # What it does} is a mechanics
     * heading and stays; the capitals after it are the essay form the review named.
     *
     * <p>Any opening tag may stand between the marker and the word. An earlier revision of this
     * pattern named {@code <h1>} through {@code <h6>} and {@code <b>}, which are the shapes a page
     * heading takes, and missed {@code <p>} — the shape a javadoc paragraph takes, and the one the
     * finding's own example used at {@code notification/messaging/TransactionAuthorizedConsumer}.
     */
    private static final Pattern SHIPPED_BANNER_HEADING = Pattern.compile(
            "^\\s*(?:\\*|//|--|#)\\s*(?:<[a-zA-Z][^>]*>\\s*)?"
                    + "(?:WHY|WHAT|HOW|ALTERNATIVES?|RISKS?)\\b[ \\t]+[A-Z]{2,}");

    /**
     * A sentence-case rationale heading, in any shipped text format.
     *
     * <p>{@code Rationale} and {@code Why} are included because both open an argument. A heading that
     * names the log on its own line or on the next is a pointer rather than an essay, which is the
     * shape this platform uses to ask the question in the file and answer it in one place. The tag
     * alternation is the wide one {@link #SHIPPED_BANNER_HEADING} explains, so {@code <p>Why this is
     * a refresh and not an upsert.} is read as the paragraph opener it is.
     */
    private static final Pattern SHIPPED_RATIONALE_HEADING = Pattern.compile(
            "^\\s*(?:\\*|//|--|#)\\s*(?:<[a-zA-Z][^>]*>\\s*)?"
                    + "(?:Why\\b|Alternatives considered|Risks? accepted|Rationale\\b|"
                    + "Trade-?offs?\\b|The problem this solves)");

    /** One HTML comment, which is the only part of a page the essay scan reads. */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--(.*?)-->");

    /**
     * A comment that argues a choice inside the line rather than announcing it in a heading.
     *
     * <p>The heading detectors above read the shape of an opener, so a file that never writes one
     * carries rationale past them. A review measured that: the composition argued its transport
     * posture over thirty lines, the namespace manifest argued a pinned admission version, the
     * workflow justified a stage, the deck's script justified how it loads a library, and the
     * aggregator justified a raised dependency. None of them opened with "Why".
     *
     * <p>Nine shapes are read, and each one is a decision being defended rather than behaviour being
     * described: a claim that a choice was deliberate or was not an oversight, a statement of what
     * makes something acceptable, the reasoning behind something, alternatives considered or weighed,
     * a risk accepted, a first-person choice, an announced count of reasons, and a version or value
     * named because of something. A comment stating what the file declares, what a value does or
     * where a source member sits matches none of them, which is why "A parameter is used rather than
     * string interpolation" and "Named rather than discovered" stay.
     *
     * <p>The pointer form is exempt on the same terms the heading rule uses: a line naming the
     * decision log, or followed by one, asks the question in the file and answers it in one place.
     * That is what keeps {@code -- Alternatives weighed and the risk accepted:
     * card-platform/docs/decision-log.md} compliant.
     */
    private static final Pattern INLINE_RATIONALE = Pattern.compile(
            "(?i)\\b(?:(?:deliberate|deliberately|conscious|consciously) (?:choice|decision|trade-?off)"
                    + "|rather than an oversight"
                    + "|the reasoning behind"
                    + "|alternatives? (?:considered|weighed|rejected)"
                    + "|risks? (?:it accepts|accepted|we accept)"
                    + "|we (?:chose|decided|picked|rejected)"
                    + "|what makes (?:that|this|it) acceptable"
                    + "|for (?:two|three|four) reasons"
                    + "|(?:is|was) the (?:version|value|name|one|form|shape) "
                    + "(?:named|chosen|used|pinned) because)\\b");

    /** One delimited block comment, which is how the deck's script and stylesheet comment. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*(.*?)\\*/");

    /** One line comment of the deck's script. */
    private static final Pattern SLASH_COMMENT = Pattern.compile("(?m)^[ \\t]*//(.*)$");

    /** One comment line of a shipped file: where it sits and what it says. */
    private record CommentLine(int number, String text) {}

    /** The spelling that turns a heading into a pointer. */
    private static final String POINTER_TOKEN = "decision-log.md";

    /**
     * Shipped text formats the essay scan reads, each with the fewest files it must reach.
     *
     * <p>The floors are per format on purpose. A single total would stay satisfied by the seven
     * hundred java files while the scan quietly stopped seeing shell scripts, which is how the
     * previous guard came to cover two formats and report on all of them.
     *
     * <p>A key is a suffix, or the whole file name where a format has none, which is how the six
     * container definitions are reached. Every format this platform ships a comment in is a key:
     * java, migrations, shell, the two yaml spellings, the deck, the module descriptors, the
     * environment template and the container definitions.
     */
    private static final Map<String, Integer> SHIPPED_TEXT_FLOORS = Map.ofEntries(
            Map.entry(".java", 400),
            Map.entry(".sql", 40),
            Map.entry(".sh", 3),
            Map.entry(".yml", 5),
            Map.entry(".yaml", 10),
            Map.entry(".html", 1),
            Map.entry(".xml", 8),
            Map.entry(".example", 1),
            Map.entry("Dockerfile", 6),
            Map.entry(".toml", 1),
            Map.entry(".gitignore", 1),
            Map.entry(".dockerignore", 5));

    /**
     * Formats no comment scan reads, each with the reason it carries no rationale comment.
     *
     * <p>This map is the other half of {@link #SHIPPED_TEXT_FLOORS}, and
     * {@link #everyShippedFormatIsEitherScannedOrClassified()} holds every format of the tree to one
     * of the two. A format added to the platform is therefore either scanned or classified here, and
     * cannot arrive unread: the guard this replaced read two formats and reported on all of them,
     * which is the defect the review named.
     */
    private static final Map<String, String> UNSCANNED_FORMATS = Map.of(
            ".md", "documentation, which is where Rule 1 puts rationale and Rule 5 scores its prose",
            ".json", "JSON admits no comment, and a schema document carries none",
            ".csv", "expected-result data read by the equivalence suites",
            ".env", "a local generated file, git-ignored, whose template .env.example is scanned",
            ".demo-credentials", "a local generated file, git-ignored, carrying no comment");

    /**
     * Formats whose comments are delimited blocks rather than marked lines.
     *
     * <p>Both spellings carry {@code <!--} and {@code -->}, so one extractor reads both.
     */
    private static final List<String> DELIMITED_COMMENT_FORMATS = List.of(".html", ".xml");

    /**
     * Directories the essay scan does not descend into.
     *
     * <p>{@code docs} is where rationale belongs, so scanning it would fail the log itself.
     * {@code target} is build output and {@code blitzy} is run evidence.
     */
    private static final List<String> UNSCANNED_DIRECTORIES =
            List.of("target", "node_modules", ".git", "blitzy", "docs");

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

    /**
     * No shipped file of any format opens a rationale essay in a comment.
     *
     * <p>The two scans above read java heading shapes and migration headers. Everything else this
     * platform ships was unread, and that is where the essays were: {@code WHY THIS SCRIPT EXISTS,
     * AND WHY IT IS NOT A TEST} in {@code scripts/check-published-test-counts.sh}, {@code WHY THIS
     * EXISTS} in {@code scripts/redact-report-artifacts.sh}, {@code WHY PROVENANCE IS A STAGE OF ITS
     * OWN} and {@code WHY IT READS A BILL OF MATERIALS RATHER THAN THE DESCRIPTORS} in the workflow,
     * four banners in {@code deploy/k8s/kustomization.yaml}, and {@code <h2>The problem this
     * solves</h2>} in six copies of {@code config/StreamNameReport}. Each argued a choice the
     * decision log already carried, which is the drift Rule 1 exists to prevent.
     *
     * <p>Two shapes fail. A banner in capitals fails outright. A sentence-case heading that opens an
     * argument fails unless the log is named on the same line or the next, which is the pointer form:
     * the file asks the question and one place answers it. A mechanics heading such as {@code # What
     * it measures} or {@code -- What the rule reads.} is neither and stays.
     *
     * <p>The deck and the module descriptors are read as their comments only. A slide heading is
     * content Rule 4 governs and Rule 5 scores, and a descriptor element is configuration, so
     * neither is a comment carrying rationale and both are left to the rules that own them.
     */
    @Test
    @DisplayName("no shipped file of any format opens a rationale essay in a comment")
    void noShippedTextOpensARationaleEssay() {
        Map<String, List<Path>> byFormat = shippedTextSources();
        SHIPPED_TEXT_FLOORS.forEach((format, floor) -> {
            int reached = byFormat.getOrDefault(format, List.of()).size();
            assertTrue(reached >= floor,
                    "the scan reached " + reached + " " + format + " files, fewer than the " + floor
                            + " this platform ships, so its verdict for that format would mean"
                            + " nothing");
        });

        List<String> offending = new ArrayList<>();
        byFormat.forEach((format, sources) -> {
            for (Path source : sources) {
                String text = readText(source);
                if (DELIMITED_COMMENT_FORMATS.contains(format)) {
                    Matcher comment = HTML_COMMENT.matcher(text);
                    while (comment.find()) {
                        collectEssayOpeners(relative(source), asCommentLines(comment.group(1)),
                                lineOf(text, comment.start()) - 1, offending);
                    }
                } else {
                    collectEssayOpeners(relative(source), text, 0, offending);
                }
            }
        });

        assertEquals(List.of(), offending,
                "Rule 1 makes " + DECISION_LOG_POINTER + " the single source of \"why\" in every "
                        + "format, not only in java and sql: state what the file declares, its source "
                        + "locators and a pointer to the log instead: " + offending);
    }

    /**
     * No shipped comment of any format argues a choice inside the line.
     *
     * <p>This is the other half of {@link #noShippedTextOpensARationaleEssay()}. That test reads the
     * shape of an opener, and a review found five artifacts carrying rationale past it because none
     * of them wrote one: {@code docker-compose.yml} argued its transport posture, alternatives and
     * accepted risk over thirty lines; {@code deploy/k8s/00-namespace.yaml} argued a pinned admission
     * version; {@code .github/workflows/ci.yml} justified a stage; the deck's script justified how it
     * loads its renderer; and {@code pom.xml} justified a raised dependency. Each argument was
     * already in the log, so each was a second copy of a decision Rule 1 keeps in one place.
     *
     * <p>Every format is read as its comments. A marked format is read line by line, and the deck is
     * read as its HTML comments, its block comments and its line comments, which is where a page
     * keeps the reasoning a script carries.
     */
    @Test
    @DisplayName("no shipped comment of any format argues a choice inside the line")
    void noShippedCommentArguesAChoiceInline() {
        Map<String, List<Path>> byFormat = shippedTextSources();
        SHIPPED_TEXT_FLOORS.forEach((format, floor) -> {
            int reached = byFormat.getOrDefault(format, List.of()).size();
            assertTrue(reached >= floor,
                    "the scan reached " + reached + " " + format + " files, fewer than the " + floor
                            + " this platform ships, so its verdict for that format would mean"
                            + " nothing");
        });

        List<String> offending = new ArrayList<>();
        byFormat.forEach((format, sources) -> {
            for (Path source : sources) {
                collectInlineRationale(relative(source), commentLines(format, readText(source)),
                        offending);
            }
        });

        assertEquals(List.of(), offending,
                "Rule 1 keeps the decision, the alternatives weighed against it and the risk "
                        + "accepted in " + DECISION_LOG_POINTER + ", and a comment carries behaviour, "
                        + "source locators and operational facts. State the fact and name the log "
                        + "instead: " + offending);
    }

    /**
     * Collects every comment line that argues a choice, exempting the pointer form.
     *
     * @param where     the file, relative to the repository root
     * @param comments  the comment lines of that file, in order
     * @param offending the list each finding is added to
     */
    private static void collectInlineRationale(String where, List<CommentLine> comments,
            List<String> offending) {
        for (int at = 0; at < comments.size(); at++) {
            CommentLine comment = comments.get(at);
            if (!INLINE_RATIONALE.matcher(comment.text()).find()) {
                continue;
            }
            boolean pointsAtTheLog = comment.text().contains(POINTER_TOKEN);
            if (!pointsAtTheLog && at + 1 < comments.size()) {
                CommentLine next = comments.get(at + 1);
                pointsAtTheLog = next.number() == comment.number() + 1
                        && next.text().contains(POINTER_TOKEN);
            }
            if (pointsAtTheLog) {
                continue;
            }
            offending.add(where + ":" + comment.number() + " " + comment.text().trim());
        }
    }

    /**
     * Reads one shipped file as its comment lines.
     *
     * @param format the format key, which decides how a comment is delimited
     * @param text   the whole file
     * @return each comment line with the line number it sits on, in file order
     */
    private static List<CommentLine> commentLines(String format, String text) {
        List<CommentLine> lines = new ArrayList<>();
        if (DELIMITED_COMMENT_FORMATS.contains(format)) {
            List<Pattern> delimited = ".html".equals(format)
                    ? List.of(HTML_COMMENT, BLOCK_COMMENT)
                    : List.of(HTML_COMMENT);
            for (Pattern pattern : delimited) {
                Matcher comment = pattern.matcher(text);
                while (comment.find()) {
                    int first = lineOf(text, comment.start());
                    String[] body = comment.group(1).split("\\R", -1);
                    for (int at = 0; at < body.length; at++) {
                        lines.add(new CommentLine(first + at, body[at]));
                    }
                }
            }
            if (".html".equals(format)) {
                Matcher slashed = SLASH_COMMENT.matcher(text);
                while (slashed.find()) {
                    lines.add(new CommentLine(lineOf(text, slashed.start()), slashed.group(1)));
                }
            }
            lines.sort(Comparator.comparingInt(CommentLine::number));
            return lines;
        }
        String[] all = text.split("\\R", -1);
        for (int at = 0; at < all.length; at++) {
            Matcher marked = MARKED_COMMENT.matcher(all[at]);
            if (marked.find()) {
                lines.add(new CommentLine(at + 1, marked.group(1)));
            }
        }
        return lines;
    }

    /** One comment line of a marked format: java, sql, shell, yaml, dotenv or a container file. */
    private static final Pattern MARKED_COMMENT =
            Pattern.compile("^\\s*(?:\\*|//|--|#)\\s?(.*)$");

    /**
     * Holds every format the platform ships to one of the two maps above.
     *
     * <p>A scan is only as wide as the list of formats it reads, and a format nobody added to that
     * list is a format whose comments are never read. This test removes the silence: a file of a
     * format that is neither scanned nor classified fails the build, naming the format and the file.
     *
     * <p>Every key of {@link #SHIPPED_TEXT_FLOORS} is also required to exist in the tree, so a format
     * that stops being shipped is noticed rather than leaving a floor that can never be reached.
     */
    @Test
    @DisplayName("every format the platform ships is either scanned for essays or classified")
    void everyShippedFormatIsEitherScannedOrClassified() {
        Map<String, String> firstFileOfFormat = new LinkedHashMap<>();
        for (Path root : List.of(platformRoot(), repositoryRoot().resolve(".github"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(Files::isRegularFile)
                        .filter(RationaleLocationContractTest::isDeliveredFile)
                        .sorted()
                        .forEach(path -> firstFileOfFormat.putIfAbsent(
                                formatKeyOf(path.getFileName().toString()), relative(path)));
            } catch (IOException unreadable) {
                throw new UncheckedIOException("unreadable directory " + root, unreadable);
            }
        }

        List<String> unclassified = firstFileOfFormat.entrySet().stream()
                .filter(entry -> !SHIPPED_TEXT_FLOORS.containsKey(entry.getKey()))
                .filter(entry -> !UNSCANNED_FORMATS.containsKey(entry.getKey()))
                .map(entry -> entry.getKey() + " first seen at " + entry.getValue())
                .toList();
        assertEquals(List.of(), unclassified,
                "a format arrived that no comment scan reads: give it a floor in SHIPPED_TEXT_FLOORS "
                        + "or a reason in UNSCANNED_FORMATS, because a format nobody listed is a "
                        + "format whose comments are never read: " + unclassified);

        List<String> bothWays = SHIPPED_TEXT_FLOORS.keySet().stream()
                .filter(UNSCANNED_FORMATS::containsKey)
                .sorted()
                .toList();
        assertEquals(List.of(), bothWays,
                "a format cannot be both scanned and classified as unread: " + bothWays);

        List<String> vanished = SHIPPED_TEXT_FLOORS.keySet().stream()
                .filter(format -> !firstFileOfFormat.containsKey(format))
                .sorted()
                .toList();
        assertEquals(List.of(), vanished,
                "a floor names a format the platform no longer ships, so it can never be reached "
                        + "and no longer proves anything: " + vanished);
    }

    /**
     * Reports whether one file is delivered content rather than build output or run evidence.
     *
     * <p>{@code docs} is descended into here, unlike in the essay scan, because this test classifies
     * formats rather than reading comments: a format shipped only under {@code docs} still has to be
     * accounted for.
     *
     * @param file the file to judge
     * @return {@code true} when it is delivered content
     */
    private static boolean isDeliveredFile(Path file) {
        for (Path element : repositoryRoot().relativize(file)) {
            if (List.of("target", "node_modules", ".git", "blitzy").contains(element.toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Marks every line of an HTML comment body as a comment line.
     *
     * <p>The detectors anchor on a comment marker, and the body of an HTML comment carries none: the
     * marker opened the block. Prefixing each line reads the whole block as the comment it is,
     * without a second pair of patterns that could drift from the first.
     *
     * @param body the text between the comment delimiters
     * @return the same text with each line marked
     */
    private static String asCommentLines(String body) {
        StringBuilder marked = new StringBuilder();
        for (String line : body.split("\\R", -1)) {
            marked.append("# ").append(line).append('\n');
        }
        return marked.toString();
    }

    /**
     * Adds every essay opener in one body of text to {@code offending}.
     *
     * @param where      the file, as a reader would name it
     * @param text       the text to read, which is a whole file or one delimited comment
     * @param lineOffset the file line the text begins on, less one, so a defect inside a delimited
     *                   comment is reported at the line a reader opens the file at
     * @param offending  the list defects are added to
     */
    private static void collectEssayOpeners(String where, String text, int lineOffset,
            List<String> offending) {
        String[] lines = text.split("\\R", -1);
        for (int at = 0; at < lines.length; at++) {
            boolean banner = SHIPPED_BANNER_HEADING.matcher(lines[at]).find();
            boolean heading = SHIPPED_RATIONALE_HEADING.matcher(lines[at]).find();
            if (!banner && !heading) {
                continue;
            }
            String next = at + 1 < lines.length ? lines[at + 1] : "";
            boolean pointsAtTheLog = lines[at].contains(POINTER_TOKEN) || next.contains(POINTER_TOKEN);
            if (heading && !banner && pointsAtTheLog) {
                continue;
            }
            offending.add(where + ":" + (lineOffset + at + 1) + " " + lines[at].trim());
        }
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

        assertTrue(SHIPPED_BANNER_HEADING.matcher("# WHY THIS SCRIPT EXISTS").find(),
                "the cross-format banner detector missed a shell banner");
        assertTrue(SHIPPED_BANNER_HEADING.matcher("# WHY PROVENANCE IS A STAGE OF ITS OWN.").find(),
                "and missed a workflow banner");
        assertFalse(SHIPPED_BANNER_HEADING.matcher("# What it measures").find(),
                "a sentence-case mechanics heading was read as a banner");
        assertFalse(SHIPPED_BANNER_HEADING.matcher("# TWO KINDS OF IMAGE LIVE IN THIS FOLDER").find(),
                "a capitalised mechanics heading opening on another word is not a rationale banner");

        assertTrue(SHIPPED_RATIONALE_HEADING.matcher("  # Why the overlay is needed.").find(),
                "the cross-format heading detector missed a yaml rationale heading");
        assertTrue(SHIPPED_RATIONALE_HEADING.matcher(" * <h2>The problem this solves</h2>").find(),
                "and missed the section heading six copies of one class carried");
        assertFalse(SHIPPED_RATIONALE_HEADING.matcher("  # What the overlay changes.").find(),
                "a mechanics heading was read as a rationale heading");

        assertTrue(SHIPPED_BANNER_HEADING.matcher(" * <p>WHY THIS LISTENER EXISTS. AAP 0.1.1 and 0.8.3").find(),
                "the cross-format banner detector missed a javadoc paragraph banner, which is the"
                        + " shape the finding's own example carried");
        assertTrue(SHIPPED_RATIONALE_HEADING.matcher(" * <p>Why this is a refresh and not an upsert.").find(),
                "the cross-format heading detector missed a javadoc paragraph heading");
        assertTrue(SHIPPED_RATIONALE_HEADING.matcher(" * <p>Rationale for the two-hop resolution").find(),
                "and missed a paragraph opening on the word the log is named for");
        assertFalse(SHIPPED_RATIONALE_HEADING.matcher(" * <p>What this predicate does not say.").find(),
                "a javadoc mechanics paragraph was read as a rationale heading");
        assertFalse(SHIPPED_RATIONALE_HEADING.matcher(" * <p>Three documents carry what this class does not.").find(),
                "a paragraph naming its pointers was read as a rationale heading");

        List<String> flagged = new ArrayList<>();
        collectEssayOpeners("sample", "# Why a reservation rather than an aggregate:\n"
                + "# card-platform/docs/decision-log.md.\n", 0, flagged);
        assertEquals(List.of(), flagged,
                "a heading answered by the log on the next line is a pointer, which is the shape this"
                        + " platform uses to ask in the file and answer in one place");

        collectEssayOpeners("sample", "# Why one pass is several transactions. Because a claim that\n"
                + "# spans a send holds a row open.\n", 0, flagged);
        assertEquals(1, flagged.size(),
                "a heading answered in the file rather than by the log is an essay: " + flagged);

        flagged.clear();
        collectEssayOpeners("sample", "# WHY IT EXISTS: card-platform/docs/decision-log.md\n", 0, flagged);
        assertEquals(1, flagged.size(),
                "a banner in capitals fails even when it names the log, because the banner form is"
                        + " what opens an essay: " + flagged);

        flagged.clear();
        collectEssayOpeners("sample", " * <p>WHY THIS LISTENER EXISTS. The ledger and the fraud\n"
                + " * detector read the topic directly, and this service read neither.\n", 0, flagged);
        assertEquals(1, flagged.size(),
                "a javadoc paragraph opening on a banner in capitals is an essay: " + flagged);

        flagged.clear();
        collectEssayOpeners("sample", " * <p>Rationale, alternatives considered and accepted risks:\n"
                + " * {@code card-platform/docs/decision-log.md}.\n", 0, flagged);
        assertEquals(List.of(), flagged,
                "the pointer paragraph this platform ships in place of an essay is not an essay");

        assertTrue(INLINE_RATIONALE.matcher(
                        "each is a deliberate choice for this stack rather than an oversight:").find(),
                "the inline detector missed the claim the composition carried");
        assertTrue(INLINE_RATIONALE.matcher("What makes that acceptable is a posture").find(),
                "and missed the acceptability argument beside it");
        assertTrue(INLINE_RATIONALE.matcher(
                        "v1.25 is the version named because it is where the restricted").find(),
                "and missed the pinned-version argument of the namespace manifest");
        assertTrue(INLINE_RATIONALE.matcher(
                        "written into the document head, for two reasons, and both are measured")
                        .find(),
                "and missed the announced count of reasons the deck's script carried");
        assertTrue(INLINE_RATIONALE.matcher("The reasoning behind the posture").find(),
                "and missed a reasoning announcement");
        assertTrue(INLINE_RATIONALE.matcher("we chose the keyed derivation").find(),
                "and missed a first-person choice");
        assertFalse(INLINE_RATIONALE.matcher(
                        "A parameter is used rather than string interpolation, so the value is bound")
                        .find(),
                "a comment describing behaviour is not an argument for a choice");
        assertFalse(INLINE_RATIONALE.matcher(
                        "Named rather than discovered. The actuator contributor is asked for by name")
                        .find(),
                "nor is a comment naming what the code does");
        assertFalse(INLINE_RATIONALE.matcher(
                        "RECORDSIZE(300 300) declared by the dataset definition").find(),
                "nor is a source locator");

        List<CommentLine> arguing = List.of(new CommentLine(9, " each is a deliberate choice here"));
        flagged.clear();
        collectInlineRationale("sample", arguing, flagged);
        assertEquals(1, flagged.size(), "an argument with no pointer is a finding: " + flagged);

        flagged.clear();
        collectInlineRationale("sample", List.of(
                new CommentLine(9, " Alternatives weighed and the risk accepted:"),
                new CommentLine(10, " card-platform/docs/decision-log.md")), flagged);
        assertEquals(List.of(), flagged,
                "an argument answered by the log on the next line is the pointer form this platform"
                        + " ships");

        flagged.clear();
        collectInlineRationale("sample", List.of(
                new CommentLine(9, " Alternatives weighed and the risk accepted:"),
                new CommentLine(40, " card-platform/docs/decision-log.md")), flagged);
        assertEquals(1, flagged.size(),
                "a log named thirty lines later answers nothing a reader met: " + flagged);

        assertEquals(List.of(" a block comment", "", " a line one"),
                commentLines(".html", "<p>x</p>\n/* a block comment\n*/\n// a line one\n").stream()
                        .map(CommentLine::text)
                        .toList(),
                "the deck is read as its block comments and its line comments, which is where a page"
                        + " keeps the reasoning a script carries");
        assertEquals(List.of(2, 3, 4),
                commentLines(".html", "<p>x</p>\n/* a block comment\n*/\n// a line one\n").stream()
                        .map(CommentLine::number)
                        .toList(),
                "and each carries the line it sits on, so a finding names a place");
        assertEquals(List.of(new CommentLine(2, "a shell comment")),
                commentLines(".sh", "set -eu\n# a shell comment\n"),
                "a marked format is read line by line");
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

    /**
     * Returns every shipped text file the essay scan reads, keyed by its format.
     *
     * <p>Two roots are walked: the platform tree and the workflow directory beside it. A path holding
     * any {@link #UNSCANNED_DIRECTORIES} element drops out wherever that element sits, and this file
     * drops out because it carries the samples the detectors are run over.
     *
     * @return the files of each scanned format, in a stable order
     */
    private static Map<String, List<Path>> shippedTextSources() {
        Map<String, List<Path>> byFormat = new LinkedHashMap<>();
        for (Path root : List.of(platformRoot(), repositoryRoot().resolve(".github"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(Files::isRegularFile)
                        .filter(RationaleLocationContractTest::isScannedText)
                        .sorted()
                        .forEach(path -> {
                            String key = formatKeyOf(path.getFileName().toString());
                            byFormat.computeIfAbsent(key, any -> new ArrayList<>()).add(path);
                        });
            } catch (IOException unreadable) {
                throw new UncheckedIOException("unreadable directory " + root, unreadable);
            }
        }
        return byFormat;
    }

    /**
     * Reports whether one file is shipped text of a scanned format.
     *
     * @param file a regular file under one of the scanned roots
     * @return true when no path element is unscanned and the suffix is one the scan reads
     */
    private static boolean isScannedText(Path file) {
        for (Path element : repositoryRoot().relativize(file)) {
            if (UNSCANNED_DIRECTORIES.contains(element.toString())) {
                return false;
            }
        }
        String name = file.getFileName().toString();
        if (THIS_FILE.equals(name)) {
            return false;
        }
        return SHIPPED_TEXT_FLOORS.containsKey(formatKeyOf(name));
    }

    /**
     * Returns the format key of one file name.
     *
     * @param name the file name
     * @return its suffix, or the whole name where it carries none
     */
    private static String formatKeyOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : name;
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

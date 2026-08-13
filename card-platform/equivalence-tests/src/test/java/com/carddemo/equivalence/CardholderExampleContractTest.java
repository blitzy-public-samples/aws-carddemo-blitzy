package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds every document and contract to carrying no card number a reader could replay.
 *
 * <p>A card number normalized into the authorization or card guide, or into either OpenAPI
 * document, is a card the seed migrations load. An example carrying a seeded number is a working
 * credential for the demo stack, it survives every copy of the guide, and it teaches by
 * demonstration that printing a Primary Account Number is normal.
 *
 * <p>The assertions read the fixtures rather than a list of numbers, which is what makes them hold
 * as the fixtures change. {@code app/data/ASCII/carddata.txt} holds 50 records of 150 characters
 * with {@code CARD-NUM PIC X(16)} first, and {@code app/data/ASCII/cardxref.txt} holds 50 of 36 with
 * {@code XREF-CARD-NUM PIC X(16)} first. A fixed-width record has no delimiter, so a card number
 * inside one is a substring of a longer digit run and a search for a delimited sixteen-digit token
 * misses it. Every check below therefore searches for the value as a substring.
 *
 * <p>Two rules run over the delivered surface, and that surface takes in {@code .github} as well as
 * {@code card-platform}. The first refuses a value: no file outside {@link #PERMITTED_FILES}
 * carries a number either fixture holds. The second refuses a shape: no file carries a
 * sixteen-digit literal that the file itself labels a card number, whatever that value resolves to.
 * Neither rule reaches what the other does: a synthetic literal in an OpenAPI example satisfies the
 * value rule, and a seeded number in the business smoke of {@code .github/workflows/ci.yml} lies
 * outside any sweep starting at {@code card-platform}.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
class CardholderExampleContractTest {

    /**
     * Where a card number sits in each fixture, and how wide the record is.
     *
     * <p>Both layouts put the card number first, at offset zero for sixteen characters:
     * {@code app/cpy/CVACT02Y.cpy:L5} for the card record and {@code app/cpy/CVACT03Y.cpy:L5} for
     * the cross-reference.
     */
    private static final List<String> CARD_NUMBER_FIXTURES =
            List.of("app/data/ASCII/carddata.txt", "app/data/ASCII/cardxref.txt");

    /** Width of the card-number field in both layouts. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Characters a masked card number keeps, matching {@code PanMasker.VISIBLE_DIGIT_COUNT}. */
    private static final int VISIBLE_DIGITS = 4;

    /** Records each fixture holds, which is the count both loaders assert. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /**
     * The files permitted to carry a seeded card number, and why.
     *
     * <p>A seed migration loads the fixture. Its literals are the data rather than an example of
     * how to call an API, and removing them would remove the demo data the equivalence suite
     * measures against. {@code V4__card_cross_reference_replica.sql} is here for a second reason:
     * it has run, so it is never edited, and {@code V7__account_customer_link.sql} drops the table
     * it filled.
     */
    private static final Set<String> PERMITTED_FILES = Set.of(
            "services/authorization-service/src/main/resources/db/migration/V2__seed.sql",
            "services/card-service/src/main/resources/db/migration/V2__seed.sql",
            "services/account-service/src/main/resources/db/migration/V4__card_cross_reference_replica.sql");

    /**
     * How a file names the field a value beside it fills.
     *
     * <p>The alternation covers the property name in every casing the platform uses, the COBOL field
     * name of both layouts, and the two English forms a document writes. It is matched against the
     * line the value sits on, and in YAML against {@link #YAML_LABEL_LINES} above it as well.
     */
    private static final Pattern CARD_NUMBER_LABEL = Pattern.compile(
            "(?i)card[ _-]?(number|num\\b|no\\b)|\\bPAN\\b|primary account number");

    /**
     * Lines above a YAML value that its key may sit on.
     *
     * <p>A YAML value sits under its key rather than beside it: {@code examples: ['...']} under a
     * {@code cardNumber} property is three lines below the name of the field it fills, and a property
     * carrying a type and a pattern puts it further still. Prose is read on one line only. Measured
     * over this whole tree, a window over prose read a sentence about the width of a transaction
     * identifier as a labelled card number, and {@code TRAN-ID PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L5} makes that identifier sixteen digits too.
     */
    private static final int YAML_LABEL_LINES = 6;

    /**
     * A run of exactly sixteen digits, which is the shape every card-number field declares at
     * {@code app/cpy/CVACT02Y.cpy:L5}.
     *
     * <p>The boundaries refuse a longer run, so a seventeen-digit identifier is not read as a card
     * number. A run alone is not a violation: a transaction identifier is {@code TRAN-ID PIC X(16)}
     * at {@code app/cpy/CVTRA05Y.cpy:L5} and renders as sixteen digits too, and the response examples
     * of three services carry those. A run becomes a violation where {@link #CARD_NUMBER_LABEL} names
     * the field it fills.
     */
    private static final Pattern SIXTEEN_DIGIT_RUN =
            Pattern.compile("(?<![0-9])[0-9]{16}(?![0-9])");

    /** Extensions worth reading. A binary carries no example a reader copies. */
    private static final Set<String> READABLE_SUFFIXES =
            Set.of(".md", ".yaml", ".yml", ".json", ".html", ".sql", ".toml", ".xml", ".java");

    /**
     * Returns a card number in the form this platform publishes it, keeping its last four digits.
     *
     * <p>Every assertion of this class runs over card numbers, and AssertJ writes the values it
     * compared into the failure message. That message lands in a Surefire report that
     * {@code .github/workflows/ci.yml} uploads and keeps, so a failure here would publish the
     * fixture rows this class exists to keep out of the delivered files. Each assertion therefore
     * reduces its own comparison to a list of offending values and maps that list through here
     * first: nothing named in a failure carries more than what {@code PanMasker} publishes.</p>
     *
     * @param cardNumber the value to describe, of any width
     * @return the value with everything before its last four characters masked
     */
    private static String masked(String cardNumber) {
        if (cardNumber.length() <= VISIBLE_DIGITS) {
            return "*".repeat(cardNumber.length()) + " (" + cardNumber.length() + " characters)";
        }
        return "*".repeat(cardNumber.length() - VISIBLE_DIGITS)
                + cardNumber.substring(cardNumber.length() - VISIBLE_DIGITS)
                + " (" + cardNumber.length() + " characters)";
    }

    /**
     * Returns every sixteen-digit literal one file publishes under a card-number label.
     *
     * <p>Each finding names the file, the line and the value in the masked form {@link #masked}
     * produces, so a failure published as a build artifact carries no number a reader could replay.
     *
     * @param name the file as a reader would name it, which also selects how far the label may sit
     * @param text the whole file
     * @return one entry per offending run, empty where the file publishes none
     */
    private static List<String> cardShapedLiterals(String name, String text) {
        int window = name.endsWith(".yaml") || name.endsWith(".yml") ? YAML_LABEL_LINES : 0;
        List<String> lines = Arrays.asList(text.split("\\R", -1));
        List<String> found = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String labelled = String.join("\n",
                    lines.subList(Math.max(0, index - window), index + 1));
            if (!CARD_NUMBER_LABEL.matcher(labelled).find()) {
                continue;
            }
            Matcher run = SIXTEEN_DIGIT_RUN.matcher(lines.get(index));
            while (run.find()) {
                found.add(name + ":" + (index + 1) + " publishes " + masked(run.group())
                        + " under a card-number label");
            }
        }
        return found;
    }

    /** Returns the card numbers both fixtures hold, read by offset. */
    private static Set<String> seededCardNumbers() {
        Set<String> numbers = new LinkedHashSet<>();
        for (String fixture : CARD_NUMBER_FIXTURES) {
            List<String> records = readLines(repositoryRoot().resolve(fixture));
            assertThat(records)
                    .as("%s holds %d records", fixture, FIXTURE_RECORD_COUNT)
                    .hasSize(FIXTURE_RECORD_COUNT);
            records.forEach(record -> numbers.add(record.substring(0, CARD_NUMBER_WIDTH)));
        }
        return numbers;
    }

    @Nested
    @DisplayName("The fixtures, and the shape rule read over a delivered file")
    class TheFixturesAndTheShapeRule {

        @Test
        @DisplayName("both fixtures yield fifty card numbers of sixteen digits")
        void bothFixturesYieldFiftyCardNumbers() {
            Set<String> seeded = seededCardNumbers();
            assertThat(seeded)
                    .as("fifty cards, and the cross-reference names the same fifty")
                    .hasSize(FIXTURE_RECORD_COUNT);

            List<String> misshapen = seeded.stream()
                    .filter(number -> !number.matches("[0-9]{16}"))
                    .map(CardholderExampleContractTest::masked)
                    .toList();
            assertThat(misshapen)
                    .as("every seeded number is sixteen digits. The values below are named in "
                            + "masked form because this report is published as a build artifact")
                    .isEmpty();
        }

        /**
         * Drives the shape rule over the four offending forms and four it must leave alone.
         *
         * <p>A rule nobody has seen fire is a rule nobody can trust. The offending samples are the
         * shapes a delivered file can carry: a shell assignment naming its variable for the field,
         * an OpenAPI example member, a YAML example list sitting three lines under its property
         * name, and a sentence quoting a number beside the words that name it. The accepted samples
         * are the shapes a wider rule would wrongly reject: a transaction identifier under its own
         * key, a masked value, a run in prose that names no card, and a YAML label too far above
         * the value to be its key.
         */
        @Test
        @DisplayName("the shape rule fires on a labelled card number and on nothing else")
        void theShapeRuleFiresOnALabelledCardNumber() {
            String sixteen = "1".repeat(CARD_NUMBER_WIDTH);

            assertThat(cardShapedLiterals("ci.yml", "smoke_card_number=\"" + sixteen + "\""))
                    .as("a shell variable named for the field labels the value beside it")
                    .hasSize(1);
            assertThat(cardShapedLiterals("openapi.yaml", "  cardNumber: '" + sixteen + "'"))
                    .as("an example member names the field it fills")
                    .hasSize(1);
            assertThat(cardShapedLiterals("openapi.yaml", """
                            cardNumber:
                              type: [string, 'null']
                              pattern: '^[0-9]{16}$'
                              examples: ['%s']""".formatted(sixteen)))
                    .as("a YAML value sits under its key, so the key is read from above it")
                    .hasSize(1);
            assertThat(cardShapedLiterals("README.md",
                            "Send card number " + sixteen + " to reach the approval."))
                    .as("a sentence naming the field labels the number beside it")
                    .hasSize(1);

            assertThat(cardShapedLiterals("openapi.yaml", "  transactionId: '0000000000683580'"))
                    .as("TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy:L5 is sixteen digits and is not a "
                            + "card number")
                    .isEmpty();
            assertThat(cardShapedLiterals("openapi.yaml",
                            "  maskedCardNumber: '************5740'"))
                    .as("the published form of a card number is not sixteen digits")
                    .isEmpty();
            assertThat(cardShapedLiterals("docs.md",
                            "Transaction 0000000000683580 is record 1 of the daily feed."))
                    .as("prose is read on its own line, so a run no label claims stays")
                    .isEmpty();
            assertThat(cardShapedLiterals("openapi.yaml",
                            "cardNumber:\n1\n2\n3\n4\n5\n6\n  transactionId: '" + sixteen + "'"))
                    .as("a label further above than a key can sit is not the label of this value")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("What a document is allowed to carry")
    class WhatADocumentMayCarry {

        @Test
        @DisplayName("no document, contract or configuration carries a seeded card number")
        void noDocumentCarriesASeededCardNumber() {
            Set<String> seeded = seededCardNumbers();
            List<String> failures = new ArrayList<>();

            for (Path file : reviewedFiles()) {
                String relative = relativeName(file);
                if (PERMITTED_FILES.contains(relative)) {
                    continue;
                }
                String text = read(file);
                for (String number : seeded) {
                    if (text.contains(number)) {
                        failures.add(relative + " carries a seeded card number");
                        break;
                    }
                }
            }

            assertThat(failures)
                    .as("an example carrying a seeded number is a working value against the demo "
                            + "stack, and it survives every copy of the document it sits in. The "
                            + "sweep reads the workflow tree as well as the platform, because the "
                            + "business smoke of .github/workflows/ci.yml held one")
                    .isEmpty();
        }

        /**
         * Holds every delivered file to publishing no sixteen-digit literal it labels a card number.
         *
         * <p>The value rule above refuses the fifty numbers the fixtures hold today. This one refuses
         * the shape, which is what a reader copies: a published literal of the declared width teaches
         * by demonstration that printing a Primary Account Number is normal, and it stays runnable
         * against any stack seeded from other data. Two literals absent from every fixture sat in an
         * OpenAPI example and in a property example under exactly that reasoning, and the value rule
         * admitted both.
         */
        @Test
        @DisplayName("no delivered file publishes a sixteen-digit literal it labels a card number")
        void noDeliveredFilePublishesACardNumberShape() {
            List<String> failures = new ArrayList<>();

            for (Path file : reviewedFiles()) {
                String relative = relativeName(file);
                if (PERMITTED_FILES.contains(relative)) {
                    continue;
                }
                failures.addAll(cardShapedLiterals(relative, read(file)));
            }

            assertThat(failures)
                    .as("a sixteen-digit literal beside the words naming the field is a card number "
                            + "whatever it resolves to. Read one from a fixture at run time instead, "
                            + "as the guides and the workflow do")
                    .isEmpty();
        }

        @Test
        @DisplayName("every file permitted to carry one really does, so the list stays honest")
        void everyPermittedFileReallyCarriesOne() {
            Set<String> seeded = seededCardNumbers();
            List<String> stale = new ArrayList<>();

            for (String permitted : PERMITTED_FILES) {
                Path file = platformRoot().resolve(permitted);
                if (!Files.isRegularFile(file)) {
                    stale.add(permitted + " no longer exists");
                    continue;
                }
                String text = read(file);
                if (seeded.stream().noneMatch(text::contains)) {
                    stale.add(permitted + " carries none, so it needs no exemption");
                }
            }

            assertThat(stale)
                    .as("an exemption nobody needs is an exemption nobody notices growing")
                    .isEmpty();
        }

        /**
         * Each file that carries no number names where a real one comes from instead.
         *
         * <p>Removing a value without naming its source turns a runnable guide into a broken one,
         * so the five files below are required to name a fixture. Four of them are the
         * authorization and card guides and their two OpenAPI documents. The fifth is
         * {@code .github/workflows/ci.yml}, whose business smoke authorizes a real card: it reads
         * the value with {@code cut} at run time, which keeps the command working while the file
         * itself carries no number. How each one satisfies the shape rule differs. Both guides read
         * the fixture into a shell variable. The authorization contract publishes no card-number
         * example and documents the command on the property that owns the field.
         *
         * <p>Four of the five also name the command. {@code services/card-service/openapi.yaml} does
         * not, and is not required to: both of its routes are keyed by card token, no request member
         * carries a number, and the one it discloses is the masked form of a row it names. A reader
         * needs no card number to use it, so a command for reading one would answer nothing that
         * document asks.
         */
        @Test
        @DisplayName("each file that dropped a number names where a real one is read from")
        void everyFileThatDroppedANumberNamesItsSource() {
            List<String> named = List.of(
                    "services/authorization-service/README.md",
                    "services/authorization-service/src/main/resources/openapi.yaml",
                    "services/card-service/README.md",
                    "services/card-service/src/main/resources/openapi.yaml",
                    ".github/workflows/ci.yml");
            List<String> needNoCommand = List.of(
                    "services/card-service/src/main/resources/openapi.yaml");
            List<String> failures = new ArrayList<>();

            for (String relative : named) {
                Path file = relative.startsWith(".github")
                        ? repositoryRoot().resolve(relative)
                        : platformRoot().resolve(relative);
                assertThat(file).as("%s is one of the files this rule names", relative).exists();

                String text = read(file);
                if (!text.contains("app/data/ASCII/carddata.txt")
                        && !text.contains("app/data/ASCII/cardxref.txt")) {
                    failures.add(relative + " does not name where a real number is read from");
                }
                if (!needNoCommand.contains(relative) && !text.contains("cut -c1-16")) {
                    failures.add(relative + " does not name the command that reads one");
                }
                failures.addAll(cardShapedLiterals(relative, text));
            }

            assertThat(failures)
                    .as("a reader who needs a real number has to be told where it lives and how to "
                            + "read it, or the removal reads as an omission")
                    .isEmpty();
        }
    }

    /**
     * Returns the customer-facing surface: every readable file outside build output and test source.
     *
     * <p>A test source is deliberately outside this set, and the reason is the finding's own wording:
     * the review objected to full numbers "in documentation and API examples" and to committing
     * "reusable full-PAN examples to customer-facing contracts". A test that compares a service
     * result against {@code app/data/ASCII/carddata.txt} has to name the row it compares, and no
     * reader copies an assertion into a request. Narrowing the sweep to what a reader copies is a
     * scope decision rather than a convenience: the guides, the OpenAPI documents, the schemas, the
     * migrations, the manifests, the workflow and the presentation are all inside it, and
     * {@link #PERMITTED_FILES} is the only exemption within it.
     *
     * <p>The walk prunes rather than filters: it never enters a {@code target} directory or a
     * {@code src/test} tree, so neither contributes a file and neither is read.
     *
     * <p>The walk starts at both delivered roots. {@code card-platform} holds the platform, and
     * {@code .github} holds the workflow and the build action, which a reader reads and copies from as
     * readily as a guide. A sweep of the platform alone left the business smoke of
     * {@code .github/workflows/ci.yml} unread, and it held a seeded number.
     *
     * @return every readable delivered file of both roots, in a stable order
     */
    private static List<Path> reviewedFiles() {
        List<Path> reviewed = new ArrayList<>();
        for (Path root : List.of(platformRoot(), repositoryRoot().resolve(".github"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory,
                            BasicFileAttributes attributes) {
                        return isPruned(directory)
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (attributes.isRegularFile() && isReadable(file)) {
                            reviewed.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException problem) {
                throw new UncheckedIOException(problem);
            }
        }
        reviewed.sort(Comparator.naturalOrder());
        return reviewed;
    }

    /**
     * Reports whether the sweep descends into one directory.
     *
     * <p>Build output and test source are outside the reviewed set, and both are left unentered
     * rather than read and discarded. {@code target} of any module is written while this test runs:
     * the report of a class that has just finished appears there, so a walk that enters it reads a
     * directory listing that is already out of date and fails on the entry that moved.
     *
     * @param directory a directory the walk reached
     * @return true where the sweep stops rather than descending
     */
    private static boolean isPruned(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return false;
        }
        if ("target".equals(name.toString())) {
            return true;
        }
        Path parent = directory.getParent();
        return "test".equals(name.toString())
                && parent != null
                && parent.getFileName() != null
                && "src".equals(parent.getFileName().toString());
    }

    /**
     * Reports whether one file carries text a reader reads and copies from.
     *
     * @param file a file the walk reached
     * @return true where its name ends with one of {@link #READABLE_SUFFIXES}
     */
    private static boolean isReadable(Path file) {
        String name = file.getFileName().toString();
        return READABLE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /**
     * Names one reviewed file as a reader would name it.
     *
     * <p>A file of the platform is named relative to {@code card-platform}, which is what
     * {@link #PERMITTED_FILES} lists and what every failure message of this class carries. A file
     * outside it is named relative to the repository, so the workflow reads as
     * {@code .github/workflows/ci.yml} rather than as a path climbing out of the platform.
     *
     * @param file a file the sweep reached
     * @return its name relative to the platform, or to the repository where it sits outside
     */
    private static String relativeName(Path file) {
        Path platform = platformRoot();
        return file.startsWith(platform)
                ? platform.relativize(file).toString()
                : repositoryRoot().relativize(file).toString();
    }

    /** Reads one file, failing the test rather than the run when it cannot be read. */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
    }

    /** Reads one fixture into its records. */
    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
    }

    /** Locates {@code card-platform} from the module the test runs in. */
    private static Path platformRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("equivalence-tests"))
                    && Files.isDirectory(cursor.resolve("services"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot locate card-platform root");
    }

    /** Locates the repository root, which holds {@code app/data}. */
    private static Path repositoryRoot() {
        Path root = platformRoot().getParent();
        if (root == null || !Files.isDirectory(root.resolve("app/data/ASCII"))) {
            throw new IllegalStateException("Cannot locate app/data/ASCII");
        }
        return root;
    }
}

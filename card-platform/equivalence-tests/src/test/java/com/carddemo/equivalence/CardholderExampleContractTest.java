package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds every document and contract to carrying no card number a reader could replay.
 *
 * <p>A security review found full card numbers normalized into the authorization and card guides
 * and into both OpenAPI documents: eighteen occurrences of four numbers, every one of them a card
 * the seed migrations load. An example carrying a seeded number is a working credential for the demo
 * stack, it survives every copy of the guide, and it teaches by demonstration that printing a
 * Primary Account Number is normal.
 *
 * <p>The assertions read the fixtures rather than a list of numbers, which is what makes them hold
 * as the fixtures change. {@code app/data/ASCII/carddata.txt} holds 50 records of 150 characters
 * with {@code CARD-NUM PIC X(16)} first, and {@code app/data/ASCII/cardxref.txt} holds 50 of 36 with
 * {@code XREF-CARD-NUM PIC X(16)} first. A fixed-width record has no delimiter, so a card number
 * inside one is a substring of a longer digit run and a search for a delimited sixteen-digit token
 * misses it. Every check below therefore searches for the value as a substring.
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
     * The synthetic numbers the examples now carry.
     *
     * <p>Each satisfies the sixteen-digit pattern every card field declares, so an example stays
     * valid against its own schema, and none resolves to a row in any fixture, so none is a working
     * value against the demo stack.
     */
    private static final List<String> SYNTHETIC_EXAMPLES =
            List.of("4000000000000000", "4000000000000010");

    /**
     * A run of exactly sixteen digits, which is the shape every card-number field declares at
     * {@code app/cpy/CVACT02Y.cpy:L5}.
     *
     * <p>The boundaries refuse a longer run, so a seventeen-digit identifier is not read as a card
     * number. The pattern is deliberately not applied to every document: a transaction identifier is
     * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} and renders as sixteen digits
     * too, so a run alone proves nothing. It is used only to recognize a file that publishes no run
     * at all.
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
    @DisplayName("The fixtures and the values that replaced them")
    class TheFixturesAndTheirReplacements {

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

        @Test
        @DisplayName("no synthetic example value resolves to a seeded card")
        void noSyntheticExampleResolvesToASeededCard() {
            Set<String> seeded = seededCardNumbers();

            List<String> collisions = SYNTHETIC_EXAMPLES.stream()
                    .filter(seeded::contains)
                    .map(CardholderExampleContractTest::masked)
                    .toList();
            assertThat(collisions)
                    .as("an example that happened to match a fixture row would be the finding "
                            + "again, with a value that looks safe. The comparison is by whole "
                            + "value and only the masked form of a collision is named: asserting "
                            + "the two sets apart directly would publish all fifty seeded numbers "
                            + "on failure")
                    .isEmpty();

            List<String> misshapen = SYNTHETIC_EXAMPLES.stream()
                    .filter(value -> !value.matches("[0-9]{16}"))
                    .map(CardholderExampleContractTest::masked)
                    .toList();
            assertThat(misshapen)
                    .as("an example still has to satisfy the sixteen-digit pattern every card "
                            + "field declares")
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
                String relative = platformRoot().relativize(file).toString();
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
                            + "stack, and it survives every copy of the document it sits in")
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
         * Each of the four files the review named accounts for the number it no longer prints.
         *
         * <p>Three answers satisfy the requirement, and the third is the strongest of them. A file
         * may publish a synthetic value, which a reader copies and which resolves to no row. It may
         * read a real number out of the fixture into a shell variable, which keeps the value out of
         * the text while leaving the command runnable. Or it may publish no sixteen-digit run at
         * all, which is what a document describing a surface that accepts no card number does:
         * {@code services/card-service/src/main/resources/openapi.yaml} names both of its routes by
         * card token and declares no request member carrying a number, so it has no card-number
         * example to make synthetic. Naming where a real number is read from is required of all
         * four either way, because a reader who needs one has to be told where it lives.
         */
        @Test
        @DisplayName("each of the four named files accounts for the number it no longer prints")
        void theFourNamedFilesCarryASyntheticValue() {
            List<String> named = List.of(
                    "services/authorization-service/README.md",
                    "services/authorization-service/src/main/resources/openapi.yaml",
                    "services/card-service/README.md",
                    "services/card-service/src/main/resources/openapi.yaml");
            List<String> failures = new ArrayList<>();

            for (String relative : named) {
                String text = read(platformRoot().resolve(relative));
                boolean readsTheFixture = text.contains("app/data/ASCII/carddata.txt")
                        || text.contains("app/data/ASCII/cardxref.txt");
                boolean carriesASyntheticValue =
                        SYNTHETIC_EXAMPLES.stream().anyMatch(text::contains);
                boolean readsTheFixtureIntoAVariable = text.contains("CARD_NUMBER");
                boolean publishesNoSixteenDigitRun =
                        !SIXTEEN_DIGIT_RUN.matcher(text).find();
                if (!readsTheFixture) {
                    failures.add(relative + " does not name where a real number is read from");
                }
                if (!carriesASyntheticValue && !readsTheFixtureIntoAVariable
                        && !publishesNoSixteenDigitRun) {
                    failures.add(relative + " publishes a sixteen-digit run that is neither a"
                            + " synthetic value nor a shell variable read from the fixture");
                }
            }

            assertThat(failures)
                    .as("a reader who needs a real number has to be told where it lives, or the "
                            + "removal reads as an omission")
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
     */
    private static List<Path> reviewedFiles() {
        try (Stream<Path> walk = Files.walk(platformRoot())) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> READABLE_SUFFIXES.stream()
                            .anyMatch(suffix -> path.getFileName().toString().endsWith(suffix)))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !path.toString().contains("/src/test/"))
                    .toList();
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
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

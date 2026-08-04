package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the card service's seed migration to the two CardDemo fixtures it derives from.
 *
 * <p>{@code V1__schema.sql} states that {@code V2__seed.sql} loads the rows, and the card service
 * shipped without that file. A card service with an empty {@code card} table answers every card
 * read with nothing, and the authorization demonstration has no card to authorize.
 *
 * <p>The two fixtures are loaded on z/OS by an IDCAMS copy step, which is where the seed migrations
 * of this platform come from: {@code REPRO INFILE(CARDDATA)} at {@code app/jcl/CARDFILE.jcl:L76}
 * and {@code REPRO INFILE(XREFDATA)} at {@code app/jcl/XREFFILE.jcl:L64}.
 *
 * <p>Each test compares the migration text against the fixture, field by field. A seed value that
 * drifts from its fixture fails here.
 */
class CardSeedEquivalenceTest {

    /** Directory name of the card service under {@code card-platform/services}. */
    private static final String MODULE = "card-service";

    /** One row of the {@code card} insert: six values, the fourth spanning to the next line. */
    private static final Pattern CARD_ROW = Pattern.compile(
            "^    \\('([0-9]{16})', '([0-9]{11})', '([0-9]{3})', '(.*)',$");

    /** The continuation line of a {@code card} row: the expiry date and the status. */
    private static final Pattern CARD_ROW_TAIL =
            Pattern.compile("^        '([0-9]{4}-[0-9]{2}-[0-9]{2})', '([YN])'\\),?;?$");

    /** Width of the embossed name each seeded row writes, filled by {@link #seededCardRows()}. */
    private static final Map<String, Integer> SEEDED_NAME_WIDTHS = new LinkedHashMap<>();

    /** One row of the {@code card_xref} insert. */
    private static final Pattern XREF_ROW =
            Pattern.compile("^    \\('([0-9]{16})', '([0-9]{9})', '([0-9]{11})'\\),?;?$");

    @Test
    @DisplayName("the card service ships a seed migration, as its schema migration states")
    void theSeedMigrationExists() {
        Path seed = migrationDirectory(MODULE).resolve("V2__seed.sql");

        assertTrue(Files.isRegularFile(seed),
                "V1__schema.sql states that V2__seed.sql loads the rows, and the file must exist");
    }

    @Test
    @DisplayName("all 50 card rows match carddata.txt field for field")
    void everyCardRowMatchesTheFixture() {
        Map<String, List<String>> fixture = new LinkedHashMap<>();
        for (CopybookRecordParser.CardRecord card : CardDemoFixtureLoader.loadCards()) {
            // CardDemoFixtureLoader trims the embossed name. The seed keeps the fifty characters
            // of the source field for the CHAR(50) column, so this comparison trims both sides and
            // theEmbossedNameKeepsItsSourceWidth() asserts the stored width separately.
            fixture.put(card.cardNumber(), List.of(card.accountId(), card.cardVerificationValue(),
                    card.embossedName().strip(), card.expirationDate(), card.activeStatus()));
        }
        assertEquals(50, fixture.size(), "app/data/ASCII/carddata.txt holds 50 card records");

        Map<String, List<String>> seeded = seededCardRows();

        assertEquals(fixture.keySet(), seeded.keySet(),
                "the seed writes one row per fixture card number and no other");
        fixture.forEach((cardNumber, expected) -> assertEquals(expected, seeded.get(cardNumber),
                "card " + cardNumber + " must carry its fixture values"));
    }

    @Test
    @DisplayName("all 50 cross-reference rows match cardxref.txt field for field")
    void everyCrossReferenceRowMatchesTheFixture() {
        Map<String, List<String>> fixture = new LinkedHashMap<>();
        CardDemoFixtureLoader.loadCardCrossReferences().forEach(row ->
                fixture.put(row.cardNumber(), List.of(row.customerId(), row.accountId())));
        assertEquals(50, fixture.size(), "app/data/ASCII/cardxref.txt holds 50 rows");

        Map<String, List<String>> seeded = seededCrossReferenceRows();

        assertEquals(fixture.keySet(), seeded.keySet(),
                "the seed writes one row per fixture card number and no other");
        fixture.forEach((cardNumber, expected) -> assertEquals(expected, seeded.get(cardNumber),
                "cross-reference row " + cardNumber + " must carry its fixture values"));
    }

    @Test
    @DisplayName("the seed keeps every leading zero the fixtures wrote")
    void theSeedKeepsEveryLeadingZero() {
        Map<String, List<String>> cards = seededCardRows();

        assertEquals(5, cards.keySet().stream().filter(number -> number.startsWith("0")).count(),
                "five fixture card numbers open with a zero");
        assertEquals(50, cards.values().stream().filter(row -> row.get(0).startsWith("0")).count(),
                "every fixture account identifier opens with a zero");

        List<String> maskedVerificationWidths = cards.values().stream()
                .map(row -> row.get(1))
                .filter(value -> value.startsWith("0"))
                .sorted()
                .toList();
        assertEquals(List.of("003", "021", "028", "031", "033", "045", "067", "075"),
                maskedVerificationWidths,
                "the eight verification values that open with a zero keep all three digits");

        seededCrossReferenceRows().values().forEach(row -> {
            assertTrue(row.get(0).length() == 9, "a customer identifier holds nine digits");
            assertTrue(row.get(1).length() == 11, "an account identifier holds eleven digits");
        });
    }

    @Test
    @DisplayName("an embossed name carrying an apostrophe is escaped for the database")
    void anEmbossedNameCarryingAnApostropheIsEscaped() {
        List<String> withApostrophe = CardDemoFixtureLoader.loadCards().stream()
                .map(CopybookRecordParser.CardRecord::embossedName)
                .filter(name -> name.contains("'"))
                .toList();

        assertEquals(1, withApostrophe.size(),
                "one fixture name carries an apostrophe, and it is the reason the seed doubles it");

        String migration = seedText();
        assertTrue(migration.contains("O''Connell"),
                "an apostrophe reaches the migration doubled, so the statement parses");

        // The parsed value must still hold one apostrophe, which the row comparison above proves
        // for every row. This assertion names the case so a future edit cannot quietly drop it.
        assertTrue(seededCardRows().values().stream()
                        .anyMatch(row -> row.get(2).contains("O'Connell")),
                "the seeded name holds a single apostrophe after parsing");
    }

    @Test
    @DisplayName("the embossed name keeps the fifty characters of its source field")
    void theEmbossedNameKeepsItsSourceWidth() {
        seededCardRows();

        assertEquals(50, SEEDED_NAME_WIDTHS.size(), "one width per seeded row");
        SEEDED_NAME_WIDTHS.forEach((cardNumber, width) -> assertEquals(50, width.intValue(),
                "CARD-EMBOSSED-NAME PIC X(50) is space padded, and the column is CHAR(50), so card "
                        + cardNumber + " keeps all fifty characters"));
    }

    /**
     * Reads every {@code card} row the seed writes.
     *
     * @return card number to the account identifier, the verification value, the embossed name, the
     *         expiry date and the status
     */
    private static Map<String, List<String>> seededCardRows() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        List<String> lines = insertBlock("card");
        Map<String, Integer> widthsByCardNumber = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            Matcher head = CARD_ROW.matcher(lines.get(index));
            if (!head.matches()) {
                continue;
            }
            assertTrue(index + 1 < lines.size(),
                    "a card row must carry its expiry date and status on the next line");
            Matcher tail = CARD_ROW_TAIL.matcher(lines.get(index + 1));
            assertTrue(tail.matches(),
                    "unreadable continuation line: " + lines.get(index + 1));
            rows.put(head.group(1), List.of(head.group(2), head.group(3),
                    head.group(4).replace("''", "'").strip(), tail.group(1), tail.group(2)));
            widthsByCardNumber.put(head.group(1), head.group(4).replace("''", "'").length());
        }
        assertEquals(50, rows.size(), "the card seed writes 50 rows");
        SEEDED_NAME_WIDTHS.putAll(widthsByCardNumber);
        return rows;
    }

    /**
     * Reads every {@code card_xref} row the seed writes.
     *
     * @return card number to the customer and account identifiers
     */
    private static Map<String, List<String>> seededCrossReferenceRows() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String line : insertBlock("card_xref")) {
            Matcher matcher = XREF_ROW.matcher(line);
            if (matcher.matches()) {
                rows.put(matcher.group(1), List.of(matcher.group(2), matcher.group(3)));
            }
        }
        assertEquals(50, rows.size(), "the cross-reference seed writes 50 rows");
        return rows;
    }

    /**
     * Returns the lines of one insert statement, from the statement to its terminating semicolon.
     *
     * @param table the table whose insert is read
     * @return every line of that insert
     */
    private static List<String> insertBlock(String table) {
        List<String> block = new ArrayList<>();
        boolean inside = false;
        for (String line : seedText().lines().toList()) {
            if (line.startsWith("INSERT INTO " + table + " ")) {
                inside = true;
            } else if (inside && line.startsWith("INSERT INTO")) {
                break;
            }
            if (inside) {
                block.add(line);
                if (line.stripTrailing().endsWith(";")) {
                    break;
                }
            }
        }
        assertTrue(!block.isEmpty(), "found no INSERT INTO " + table + " in the card seed");
        return block;
    }

    /**
     * Reads the card service's seed migration.
     *
     * @return the migration text
     */
    private static String seedText() {
        Path seed = migrationDirectory(MODULE).resolve("V2__seed.sql");
        try {
            return Files.readString(seed);
        } catch (IOException failure) {
            throw new IllegalStateException("could not read " + seed, failure);
        }
    }

    /**
     * Locates one service's migration directory by walking up from the working directory.
     *
     * <p>{@link CardDemoFixtureLoader#fixtureDirectory()} locates the CardDemo fixtures the same
     * way, so a test runs whether it starts in the module directory or at the repository root.
     *
     * @param module the service directory under {@code card-platform/services}
     * @return the migration directory
     * @throws IllegalStateException when no ancestor of the working directory holds the directory
     */
    private static Path migrationDirectory(String module) {
        Path relative = Path.of("card-platform", "services", module, "src", "main", "resources",
                "db", "migration");
        for (Path candidate = Path.of("").toAbsolutePath().normalize(); candidate != null;
                candidate = candidate.getParent()) {
            Path migrations = candidate.resolve(relative);
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
        }
        throw new IllegalStateException(
                "no ancestor of '" + Path.of("").toAbsolutePath() + "' holds '" + relative + "'");
    }
}

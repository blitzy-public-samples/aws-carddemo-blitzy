package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Proves that an identifier keeps every digit the CardDemo fixtures wrote, leading zeros included.
 *
 * <p>A {@code PIC 9(n)} field is a fixed count of decimal digits, and the count is part of the
 * value. {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} reaches the record as
 * {@code 00000000050}, and that same eleven-character string is the Kafka message key every event
 * of this platform is partitioned on. A numeric column returns {@code 50} for that row, and
 * {@code 50} is a different message key, a different primary key and a different wire form.
 *
 * <p>Every fixture value this class reads carries a leading zero somewhere. All 50 account
 * identifiers do, all 50 customer identifiers do, 8 of the 50 card verification values do, and 6 of
 * the 50 social security numbers do. So does every one of the 51 disclosure-group category codes.
 *
 * <p>Two kinds of assertion run here. The first compares each seed migration against the fixture it
 * was derived from, value by value, which is what proves the stored form is the source form. The
 * second builds the entities and asserts that a value of the wrong width is refused rather than
 * silently padded or truncated, which is what stops the defect coming back through application
 * code.
 *
 * <p>The identifier-bearing columns are declared {@code CHAR(n)} rather than {@code NUMERIC(n,0)},
 * and {@link SchemaValidationTest} holds the migrations and the entity mappings to that contract.
 */
class IdentifierFidelityEquivalenceTest {

    /**
     * When a replica row was last written. No assertion below reads it: the freshness columns exist
     * so a stale replica can be reported rather than authorized against, and every entity below
     * requires one.
     */
    private static final java.time.Instant OBSERVED_AT =
            java.time.Instant.parse("2026-01-01T00:00:00Z");

    /** Matches one quoted or bare value inside a seed row. */
    private static final Pattern SEED_ROW_START = Pattern.compile("^    \\('([^']*)',");

    @Test
    @DisplayName("all 50 account identifiers keep their leading zero from acctdata.txt")
    void accountIdentifiersKeepEveryDigit() {
        List<String> fixture = CardDemoFixtureLoader.loadAccounts().stream()
                .map(CopybookRecordParser.AccountRecord::accountId)
                .toList();

        assertEquals(50, fixture.size(), "app/data/ASCII/acctdata.txt holds 50 account records");
        assertTrue(fixture.stream().allMatch(id -> id.length() == 11),
                "ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5 is eleven digits wide");
        assertEquals(50, fixture.stream().filter(id -> id.startsWith("0")).count(),
                "every account identifier in the fixture opens with a zero, which is exactly why "
                        + "the column holds characters rather than a number");

        List<String> seeded = seededFirstValues("account-service", "account");
        assertEquals(fixture.stream().sorted().toList(), seeded.stream().sorted().toList(),
                "every account_id the account seed writes matches the fixture character for "
                        + "character");
    }

    @Test
    @DisplayName("all 50 customer identifiers and all 6 leading-zero social security numbers survive")
    void customerIdentifiersAndSocialSecurityNumbersKeepEveryDigit() {
        List<CopybookRecordParser.CustomerRecord> fixture = CardDemoFixtureLoader.loadCustomers();
        assertEquals(50, fixture.size(), "app/data/ASCII/custdata.txt holds 50 customer records");

        List<String> fixtureIds =
                fixture.stream().map(CopybookRecordParser.CustomerRecord::customerId).toList();
        assertTrue(fixtureIds.stream().allMatch(id -> id.length() == 9),
                "CUST-ID PIC 9(09) at app/cpy/CVCUS01Y.cpy:L5 is nine digits wide");
        assertEquals(50, fixtureIds.stream().filter(id -> id.startsWith("0")).count(),
                "every customer identifier in the fixture opens with a zero");
        assertEquals(fixtureIds.stream().sorted().toList(),
                seededFirstValues("account-service", "customer").stream().sorted().toList(),
                "every customer_id the account seed writes matches the fixture");

        List<String> fixtureSsns = fixture.stream()
                .map(CopybookRecordParser.CustomerRecord::socialSecurityNumber)
                .toList();
        assertTrue(fixtureSsns.stream().allMatch(ssn -> ssn.length() == 9),
                "CUST-SSN PIC 9(09) at app/cpy/CVCUS01Y.cpy:L17 is nine digits wide");
        // The six the review named. A numeric column returns 20973888 for the first of them.
        List<String> leadingZeroSsns =
                fixtureSsns.stream().filter(ssn -> ssn.startsWith("0")).sorted().toList();
        assertEquals(List.of("015027332", "017590544", "020973888", "029222192", "033922034",
                "054960660"), leadingZeroSsns,
                "the six social security numbers that open with a zero keep all nine digits");

        assertEquals(fixtureSsns, seededSocialSecurityNumbers(),
                "every social_security_number the account seed writes matches the fixture, in "
                        + "fixture order");
    }

    @Test
    @DisplayName("all 50 cross-reference rows keep both identifiers from cardxref.txt")
    void crossReferenceIdentifiersKeepEveryDigit() {
        List<CopybookRecordParser.CardCrossReferenceRecord> fixture =
                CardDemoFixtureLoader.loadCardCrossReferences();
        assertEquals(50, fixture.size(), "app/data/ASCII/cardxref.txt holds 50 rows");

        Map<String, String> fixtureByCard = new LinkedHashMap<>();
        fixture.forEach(row -> fixtureByCard.put(row.cardNumber(),
                row.customerId() + "|" + row.accountId()));
        assertTrue(fixture.stream().allMatch(
                row -> row.customerId().length() == 9 && row.accountId().length() == 11),
                "XREF-CUST-ID is nine digits and XREF-ACCT-ID eleven, at app/cpy/CVACT03Y.cpy:L6-L7");

        Map<String, String> seeded = seededCrossReferenceRows();
        assertEquals(fixtureByCard, seeded,
                "every card_xref row the authorization seed writes matches the fixture on both "
                        + "identifiers");
    }

    @Test
    @DisplayName("all 51 disclosure-group category codes keep their four-digit form")
    void disclosureGroupCategoryCodesKeepEveryDigit() {
        List<String> fixture = CardDemoFixtureLoader.loadDisclosureGroups().stream()
                .map(CopybookRecordParser.DisclosureGroupRecord::transactionCategoryCode)
                .toList();

        assertEquals(51, fixture.size(), "app/data/ASCII/discgrp.txt holds 51 rows");
        assertTrue(fixture.stream().allMatch(code -> code.length() == 4),
                "DIS-TRAN-CAT-CD PIC 9(04) is four digits wide");
        assertTrue(fixture.contains("0001"),
                "category 1 reaches the record as 0001, and a numeric column returns 1");
    }

    @Test
    @DisplayName("all 8 leading-zero card verification values survive carddata.txt")
    void cardVerificationValuesKeepEveryDigit() {
        List<CopybookRecordParser.CardRecord> fixture = CardDemoFixtureLoader.loadCards();
        assertEquals(50, fixture.size(), "app/data/ASCII/carddata.txt holds 50 card records");

        assertTrue(fixture.stream().allMatch(card -> card.cardVerificationValue().length() == 3),
                "CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7 is three digits wide");
        assertTrue(fixture.stream().allMatch(card -> card.accountId().length() == 11),
                "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6 is eleven digits wide");

        List<String> leadingZeroValues = fixture.stream()
                .map(CopybookRecordParser.CardRecord::cardVerificationValue)
                .filter(value -> value.startsWith("0"))
                .sorted()
                .toList();
        assertEquals(List.of("003", "021", "028", "031", "033", "045", "067", "075"),
                leadingZeroValues,
                "the eight card verification values that open with a zero keep all three digits; a "
                        + "numeric column would return 3, 21 and 28 for the first three");
    }

    @Test
    @DisplayName("every fixture identifier round-trips through the entities that store it")
    void entitiesCarryTheFixtureFormUnchanged() {
        CopybookRecordParser.CardCrossReferenceRecord xref =
                CardDemoFixtureLoader.loadCardCrossReferences().getFirst();

        var authorizationXref = new com.carddemo.authorization.entity.CardCrossReferenceEntity(
                xref.cardNumber(), xref.customerId(), xref.accountId(), OBSERVED_AT);
        assertEquals(xref.customerId(), authorizationXref.getCustomerId());
        assertEquals(xref.accountId(), authorizationXref.getAccountId());

        var cardXref = new com.carddemo.card.entity.CardCrossReferenceEntity(
                xref.cardNumber(), xref.customerId(), xref.accountId(), OBSERVED_AT);
        assertEquals(xref.accountId(), cardXref.getAccountId(),
                "the card service replica stores the same eleven characters");

        // Every one of the fifty card rows, so the eight leading-zero verification values are all
        // exercised rather than only the first of them.
        for (CopybookRecordParser.CardRecord card : CardDemoFixtureLoader.loadCards()) {
            var entity = new com.carddemo.card.entity.CardEntity(card.cardNumber(),
                    card.accountId(), card.cardVerificationValue(), card.embossedName(),
                    java.time.LocalDate.parse(card.expirationDate()), card.activeStatus());
            assertEquals(card.accountId(), entity.getAccountId());
            // The entity declares no accessor for the card verification value, so the fixture form
            // is proven to survive by the constructor accepting it and refusing the unpadded
            // numeric form below, rather than by reading the stored digits back out.
            assertThrows(IllegalArgumentException.class,
                    () -> new com.carddemo.card.entity.CardEntity(card.cardNumber(),
                            card.accountId(),
                            String.valueOf(Integer.parseInt(card.cardVerificationValue())
                                    + 1000).substring(1, 3),
                            card.embossedName(),
                            java.time.LocalDate.parse(card.expirationDate()), card.activeStatus()),
                    "a two-digit verification value is what a numeric column would have returned "
                            + "for a value opening with a zero, and it must be refused");
        }

        for (CopybookRecordParser.AccountRecord account : CardDemoFixtureLoader.loadAccounts()) {
            var snapshot = new com.carddemo.authorization.entity.AccountCreditSnapshotEntity(
                    account.accountId(), account.creditLimit(), account.expirationDate(),
                    account.currentCycleCredit(), account.currentCycleDebit(), OBSERVED_AT);
            assertEquals(account.accountId(), snapshot.getAccountId());
        }
    }

    @Test
    @DisplayName("an identifier of the wrong width is refused rather than padded")
    void wrongWidthIdentifiersAreRefused() {
        String card = "0500024453765740";

        // The unpadded numeric form is exactly the value a NUMERIC column would have returned.
        assertThrows(IllegalArgumentException.class,
                () -> new com.carddemo.authorization.entity.CardCrossReferenceEntity(
                        card, "50", "50", OBSERVED_AT),
                "the unpadded form of customer 000000050 must not be accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new com.carddemo.authorization.entity.CardCrossReferenceEntity(
                        card, "000000050", "50", OBSERVED_AT),
                "the unpadded form of account 00000000050 must not be accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new com.carddemo.authorization.entity.CardCrossReferenceEntity(
                        card, "000000050", "000000000050", OBSERVED_AT),
                "a twelve-digit account identifier is as wrong as a two-digit one");
        assertThrows(IllegalArgumentException.class,
                () -> new com.carddemo.authorization.entity.CardCrossReferenceEntity(
                        card, "00000005A", "00000000050", OBSERVED_AT),
                "a non-digit character in a PIC 9 field must not be accepted");

        assertThrows(IllegalArgumentException.class,
                () -> new com.carddemo.card.entity.CardEntity(card, "00000000050", "28",
                        "Aniya Von", java.time.LocalDate.parse("2023-03-09"), "Y"),
                "the unpadded form of card verification value 028 must not be accepted");
    }

    @Test
    @DisplayName("a card verification value never reaches a failure message")
    void cardVerificationValueStaysOutOfFailureMessages() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new com.carddemo.card.entity.CardEntity("0500024453765740", "00000000050",
                        "7477", "Aniya Von", java.time.LocalDate.parse("2023-03-09"), "Y"));

        assertTrue(failure.getMessage().contains("cardVerificationValue"),
                "the message names the field so a caller can find it");
        assertTrue(!failure.getMessage().contains("7477"),
                "the message carries no character of the rejected verification value");
    }

    @Test
    @DisplayName("a transaction identifier of any width other than sixteen is refused")
    void transactionIdentifierWidthIsEnforced() {
        assertThrows(IllegalArgumentException.class, () -> transaction("123456789012345"),
                "fifteen characters is not a TRAN-ID PIC X(16)");
        assertThrows(IllegalArgumentException.class, () -> transaction("12345678901234567"),
                "seventeen characters is not a TRAN-ID PIC X(16)");

        var accepted = transaction("1234567890123456");
        assertEquals("1234567890123456", accepted.getTransactionId(),
                "exactly sixteen characters is accepted and stored unchanged");
    }

    /**
     * Builds one transaction row with the given identifier and otherwise valid field values.
     *
     * @param transactionId the identifier under test
     * @return the entity, when the identifier is accepted
     */
    private static com.carddemo.ledger.entity.TransactionEntity transaction(String transactionId) {
        return new com.carddemo.ledger.entity.TransactionEntity(transactionId, "01", "0001",
                "POS", "purchase", new java.math.BigDecimal("12.34"), "000000001", "merchant",
                "city", "12345     ", "************5740",
                "2024-01-01 00:00:00.000000", "2024-01-01-00.00.00.000000");
    }

    /**
     * Reads the first value of every row of one seed migration's insert into the named table.
     *
     * @param module the service directory holding the migration
     * @param table  the table whose insert is read
     * @return the first value of each row, in migration order
     */
    private static List<String> seededFirstValues(String module, String table) {
        List<String> values = new ArrayList<>();
        for (String line : insertBlock(module, table)) {
            Matcher matcher = SEED_ROW_START.matcher(line);
            if (matcher.find()) {
                values.add(matcher.group(1));
            }
        }
        assertTrue(!values.isEmpty(), "found no seeded row for " + table + " in " + module);
        return values;
    }

    /**
     * Reads every social security number the account seed writes.
     *
     * <p>The value opens its own line, followed by the twenty-character government identifier,
     * which is what makes the line unambiguous.
     *
     * @return the social security numbers, in migration order
     */
    private static List<String> seededSocialSecurityNumbers() {
        Pattern row = Pattern.compile("^        '([0-9]{9})', '[0-9]{20}',");
        List<String> values = new ArrayList<>();
        for (String line : insertBlock("account-service", "customer")) {
            Matcher matcher = row.matcher(line);
            if (matcher.find()) {
                values.add(matcher.group(1));
            }
        }
        assertEquals(50, values.size(), "the account seed writes 50 social security numbers");
        return values;
    }

    /**
     * Reads every cross-reference row the authorization seed writes.
     *
     * @return card number to the customer and account identifiers, joined by a vertical bar
     */
    private static Map<String, String> seededCrossReferenceRows() {
        Pattern row = Pattern.compile("^    \\('([0-9]{16})', '([0-9]{9})', '([0-9]{11})'\\)");
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : insertBlock("authorization-service", "card_xref")) {
            Matcher matcher = row.matcher(line);
            if (matcher.find()) {
                rows.put(matcher.group(1), matcher.group(2) + "|" + matcher.group(3));
            }
        }
        assertEquals(50, rows.size(), "the authorization seed writes 50 cross-reference rows");
        return rows;
    }

    /**
     * Returns the lines of one insert statement, from the statement to its terminating semicolon.
     *
     * @param module the service directory holding the migration
     * @param table  the table whose insert is read
     * @return every line of that insert
     */
    private static List<String> insertBlock(String module, String table) {
        List<String> block = new ArrayList<>();
        boolean inside = false;
        for (String line : migrationLines(module)) {
            if (line.startsWith("INSERT INTO " + table + " ")
                    || line.startsWith("INSERT INTO " + table + "(")) {
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
        assertTrue(!block.isEmpty(), "found no INSERT INTO " + table + " in " + module);
        return block;
    }

    /**
     * Reads every line of every migration of one service, in version order.
     *
     * @param module the service directory under {@code card-platform/services}
     * @return the concatenated lines
     */
    private static List<String> migrationLines(String module) {
        Path directory = migrationDirectory(module);
        List<String> lines = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                lines.addAll(Files.readAllLines(file));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not read the migrations of " + module, failure);
        }
        return lines;
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

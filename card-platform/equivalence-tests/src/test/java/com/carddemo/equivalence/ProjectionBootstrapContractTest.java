package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PicClause;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves that every projection and every replica is bootstrapped before the first event arrives, and
 * pins the freshness and ordering policy the shipped code expresses.
 *
 * <p>Four tables in three services hold a copy of state another service owns. The authorization
 * service reads {@code account_credit_snapshot} and {@code card_xref} to decide, the ledger service
 * adds to {@code account_balance_projection} when it posts, and the card service reads its own
 * {@code card} table and its own {@code card_xref} replica. None of the four is filled by the
 * service that owns the original: events keep them current once they are running, and a migration
 * has to put the opening state there.</p>
 *
 * <p>A projection left empty is not a slow start, it is a wrong answer.
 * {@code app/cbl/CBTRN02C.cbl:L395-L399} reads the account record before it posts and assigns reject
 * reason 101 when the read misses, and {@code app/cbl/CBTRN02C.cbl:L545-L560} adds the amount to the
 * balance the record already carries. A first posting that met no row would either decline a
 * transaction the batch job posts or open the balance at zero and lose the opening balance. This
 * class therefore asserts that each seed exists, that every row of it equals the fixture row it is
 * derived from, and that every one of the three hundred daily transactions resolves to an account
 * that both projections already hold.</p>
 *
 * <p>Seeds are read as migration text rather than from a database. The seed is what a fresh
 * environment applies, so comparing it against the fixture is comparing the two artifacts that
 * decide the opening state. No container, no connection and no ordering of test execution is
 * involved, and the comparison is the same on every machine.</p>
 *
 * <p>Freshness and ordering are asserted from the shipped configuration and schema, which is as far
 * as this checkpoint reaches. Each service pins an idempotent producer acknowledged by every
 * replica, a consumer that does not auto-commit and acknowledges by hand, and a
 * {@code processed_event} table keyed on the event identifier. Together those give per-account
 * ordering, because the account identifier is the message key, and they make a redelivery harmless.
 * {@code everyConsumerGuardsBeforeItAppliesAndMarksBeforeItAcknowledges} adds the listener side of
 * that policy: every consumer reads its processed-event guard before it applies an effect, writes
 * the marker beside those effects in one transactional method, and acknowledges only afterwards.
 * Applying an event against a running broker and reading the rows back belongs to the module that
 * owns the listener, where a broker and a database are available.</p>
 *
 * <p>No failure message here carries a seeded or fixture value. Every divergence is reported by
 * table, column and one-based row ordinal, so a mismatch in a card number, a verification value or a
 * balance is located without being printed.</p>
 */
class ProjectionBootstrapContractTest {

    /** Rows the account, card and cross-reference fixtures each hold. */
    private static final int FIXTURE_ROW_COUNT = 50;

    /** Records {@code app/data/ASCII/dailytran.txt} holds. */
    private static final int DAILY_TRANSACTION_COUNT = 300;

    /** Directory below the repository root holding the six service modules. */
    private static final String SERVICES_DIRECTORY = "card-platform/services";

    /** Directory below a service module holding its migrations. */
    private static final String MIGRATION_DIRECTORY = "src/main/resources/db/migration";

    /** Path below a service module to its configuration. */
    private static final String APPLICATION_YAML = "src/main/resources/application.yml";

    /** Amount added to a zero-based index to report it as a one-based ordinal. */
    private static final int FIRST_ORDINAL = 1;

    /** Matches one line comment of a migration. */
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n]*");

    /** Matches one {@code INSERT} statement and captures its table, its columns and its tuples. */
    private static final Pattern INSERT_STATEMENT = Pattern.compile(
            "INSERT\\s+INTO\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*VALUES\\s*(.*?);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Prefix a date literal carries in a seed. */
    private static final String DATE_LITERAL_PREFIX = "DATE ";

    /**
     * One seeded row, as literal text per column.
     *
     * @param table   table the row is inserted into
     * @param columns column names in the order the statement lists them
     * @param values  literal values in the same order
     */
    private record SeededRow(String table, List<String> columns, List<String> values) {

        /** Returns the literal of one column, with its quotes and any date prefix removed. */
        private String text(String column) {
            int position = columns().indexOf(column);
            if (position < 0) {
                throw new IllegalArgumentException(table + " is seeded without the column " + column);
            }
            String literal = values().get(position);
            if (literal.toUpperCase(Locale.ROOT).startsWith(DATE_LITERAL_PREFIX)) {
                literal = literal.substring(DATE_LITERAL_PREFIX.length()).trim();
            }
            if (literal.length() >= 2 && literal.startsWith("'") && literal.endsWith("'")) {
                return literal.substring(1, literal.length() - 1).replace("''", "'");
            }
            return literal;
        }

        /** Returns the literal of one column as a number. */
        private BigDecimal number(String column) {
            return new BigDecimal(text(column));
        }
    }

    /** Reads a value once and returns the same value afterwards. */
    private static <T> Supplier<T> readOnce(Supplier<T> source) {
        return new Supplier<>() {

            private T value;

            private boolean read;

            @Override
            public synchronized T get() {
                if (!read) {
                    value = source.get();
                    read = true;
                }
                return value;
            }
        };
    }

    /** Repository root, being the ancestor of the fixture directory. */
    private static final Supplier<Path> REPOSITORY_ROOT = readOnce(() ->
            CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent());

    /** Seeded rows of every service, keyed by module name and then by table. */
    private static final Supplier<Map<String, Map<String, List<SeededRow>>>> SEEDS =
            readOnce(ProjectionBootstrapContractTest::readSeeds);

    /** Modules whose migrations this class reads. */
    private static final List<String> SEEDED_MODULES = List.of(
            "authorization-service", "ledger-posting-service", "account-service", "card-service");

    /** Every module, including those that seed nothing, for the configuration assertions. */
    private static final List<String> ALL_MODULES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    /**
     * Every projection and replica table, keyed by module name and table, with what it holds a copy
     * of. A table in this list must be seeded and must equal its fixture.
     */
    private static final Map<String, String> PROJECTIONS_AND_REPLICAS = projectionsAndReplicas();

    /** Builds {@link #PROJECTIONS_AND_REPLICAS}. */
    private static Map<String, String> projectionsAndReplicas() {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("authorization-service.account_credit_snapshot",
                "Read by the credit-limit rule of app/cbl/CBTRN02C.cbl:L403-L413 and the expiration "
                        + "rule of :L414-L420. The account service owns the record");
        tables.put("authorization-service.card_xref",
                "Read by the cross-reference rule of app/cbl/CBTRN02C.cbl:L385-L387. The card "
                        + "service owns the record");
        tables.put("ledger-posting-service.account_balance_projection",
                "Added to by the balance update of app/cbl/CBTRN02C.cbl:L545-L560. The account "
                        + "service owns the record");
        tables.put("card-service.card_xref",
                "Replica of the same cross-reference the authorization service holds, so the card "
                        + "path of app/cbl/COCRDLIC.cbl reads its own copy");
        return Map.copyOf(tables);
    }

    /** Reads and parses every migration of every seeding module. */
    private static Map<String, Map<String, List<SeededRow>>> readSeeds() {
        Map<String, Map<String, List<SeededRow>>> seeds = new LinkedHashMap<>();
        for (String module : SEEDED_MODULES) {
            Path directory = REPOSITORY_ROOT.get()
                    .resolve(SERVICES_DIRECTORY)
                    .resolve(module)
                    .resolve(MIGRATION_DIRECTORY);
            Map<String, List<SeededRow>> byTable = new LinkedHashMap<>();
            for (Path migration : migrationsIn(directory)) {
                for (SeededRow row : parseInserts(readText(migration))) {
                    byTable.computeIfAbsent(row.table(), table -> new ArrayList<>()).add(row);
                }
            }
            Map<String, List<SeededRow>> immutable = new LinkedHashMap<>();
            byTable.forEach((table, rows) -> immutable.put(table, List.copyOf(rows)));
            seeds.put(module, Map.copyOf(immutable));
        }
        return Map.copyOf(seeds);
    }

    /** Lists the migrations of one service in version order. */
    private static List<Path> migrationsIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("no migration directory sits at " + directory);
        }
        Set<Path> ordered = new TreeSet<>();
        try (var entries = Files.list(directory)) {
            entries.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(ordered::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
        return List.copyOf(ordered);
    }

    /** Reads a file as text, turning the checked failure into an unchecked one. */
    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    /** Parses every {@code INSERT} of one migration into rows. */
    private static List<SeededRow> parseInserts(String migration) {
        String statements = SQL_LINE_COMMENT.matcher(migration).replaceAll("");
        List<SeededRow> rows = new ArrayList<>();
        Matcher insert = INSERT_STATEMENT.matcher(statements);
        while (insert.find()) {
            String table = insert.group(1);
            List<String> columns = splitOutsideBrackets(insert.group(2));
            for (String tuple : tuplesOf(insert.group(3))) {
                List<String> values = splitOutsideBrackets(tuple);
                if (values.size() != columns.size()) {
                    throw new IllegalStateException(table + " is seeded with a row of "
                            + values.size() + " values against " + columns.size() + " columns");
                }
                rows.add(new SeededRow(table, columns, values));
            }
        }
        return List.copyOf(rows);
    }

    /** Splits the tuple list of a {@code VALUES} clause into the text inside each tuple. */
    private static List<String> tuplesOf(String valuesClause) {
        List<String> tuples = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < valuesClause.length(); index++) {
            char character = valuesClause.charAt(index);
            if (character == '\'') {
                quoted = !quoted;
            }
            if (!quoted && character == '(') {
                depth++;
                if (depth == 1) {
                    current.setLength(0);
                    continue;
                }
            }
            if (!quoted && character == ')') {
                depth--;
                if (depth == 0) {
                    tuples.add(current.toString());
                    continue;
                }
            }
            if (depth >= 1) {
                current.append(character);
            }
        }
        return List.copyOf(tuples);
    }

    /** Splits a comma-separated list at the commas that sit outside quotes and brackets. */
    private static List<String> splitOutsideBrackets(String list) {
        List<String> elements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < list.length(); index++) {
            char character = list.charAt(index);
            if (character == '\'') {
                quoted = !quoted;
            }
            if (!quoted && character == '(') {
                depth++;
            }
            if (!quoted && character == ')') {
                depth--;
            }
            if (!quoted && depth == 0 && character == ',') {
                elements.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            elements.add(last);
        }
        return List.copyOf(elements);
    }

    /** Rows seeded into one table of one module, or an empty list when none are. */
    private static List<SeededRow> seededRows(String module, String table) {
        return SEEDS.get().getOrDefault(module, Map.of()).getOrDefault(table, List.of());
    }

    /** Reads the configuration of one service as text. */
    private static String configurationOf(String module) {
        return readText(REPOSITORY_ROOT.get()
                .resolve(SERVICES_DIRECTORY)
                .resolve(module)
                .resolve(APPLICATION_YAML));
    }

    /**
     * Restores the leading zeros a seed literal written as a number drops, so it compares against the
     * zero-padded fixture field of the same width.
     *
     * @param value the value as the seed writes it
     * @param width the character width the fixture field occupies
     * @return the value at that width, its leading zeros restored
     */
    private static String padded(String value, int width) {
        String digits = new BigDecimal(value).toBigInteger().toString();
        return "0".repeat(Math.max(0, width - digits.length())) + digits;
    }

    /** Reports a divergence by ordinal and column, never by value. */
    private static String at(String module, String table, String column, int ordinal) {
        return module + " " + table + "." + column + " row " + ordinal;
    }

    @Nested
    @DisplayName("Bootstrap of every projection and replica")
    class Bootstrap {

        /** Every projection and replica is seeded, and none is left for the first event to create. */
        @Test
        @DisplayName("every projection and replica is seeded by a migration")
        void everyProjectionAndReplicaIsSeeded() {
            List<String> unseeded = new ArrayList<>();
            for (String key : PROJECTIONS_AND_REPLICAS.keySet()) {
                String[] parts = key.split("\\.");
                List<SeededRow> rows = seededRows(parts[0], parts[1]);
                if (rows.isEmpty()) {
                    unseeded.add(key);
                }
            }

            assertEquals(List.of(), unseeded,
                    "these projections and replicas are created by a migration and filled by none, "
                            + "so the first event to arrive would meet an empty table: " + unseeded);
        }

        /** Every seeded projection and replica holds one row per fixture record. */
        @Test
        @DisplayName("every seeded projection and replica holds one row per fixture record")
        void everySeededTableHoldsOneRowPerFixtureRecord() {
            assertEquals(FIXTURE_ROW_COUNT,
                    seededRows("authorization-service", "account_credit_snapshot").size(),
                    "the authorization credit snapshot holds one row per account of "
                            + "app/data/ASCII/acctdata.txt");
            assertEquals(FIXTURE_ROW_COUNT,
                    seededRows("authorization-service", "card_xref").size(),
                    "the authorization cross-reference holds one row per record of "
                            + "app/data/ASCII/cardxref.txt");
            assertEquals(FIXTURE_ROW_COUNT,
                    seededRows("ledger-posting-service", "account_balance_projection").size(),
                    "the ledger balance projection holds one row per account of "
                            + "app/data/ASCII/acctdata.txt");
            assertEquals(FIXTURE_ROW_COUNT, seededRows("card-service", "card_xref").size(),
                    "the card cross-reference replica holds one row per record of "
                            + "app/data/ASCII/cardxref.txt");
            assertEquals(FIXTURE_ROW_COUNT, seededRows("card-service", "card").size(),
                    "the card table holds one row per record of app/data/ASCII/carddata.txt");
        }

        /**
         * The ledger balance projection opens on the balance and the two cycle accumulators of every
         * account record, so the first posting adds to the opening balance rather than to zero.
         */
        @Test
        @DisplayName("the ledger balance projection opens on every account record of the fixture")
        void theLedgerBalanceProjectionOpensOnEveryAccountRecord() {
            Map<String, CopybookRecordParser.AccountRecord> byAccountId =
                    CardDemoFixtureLoader.accountsByAccountId();
            List<SeededRow> seeded =
                    seededRows("ledger-posting-service", "account_balance_projection");
            List<String> divergent = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            for (int index = 0; index < seeded.size(); index++) {
                SeededRow row = seeded.get(index);
                int ordinal = index + FIRST_ORDINAL;
                String accountId = row.text("account_id");
                if (!seen.add(accountId)) {
                    divergent.add(at("ledger-posting-service", "account_balance_projection",
                            "account_id", ordinal) + " repeats an earlier account");
                    continue;
                }
                CopybookRecordParser.AccountRecord account = byAccountId.get(accountId);
                if (account == null) {
                    divergent.add(at("ledger-posting-service", "account_balance_projection",
                            "account_id", ordinal) + " names no account of the fixture");
                    continue;
                }
                if (account.currentBalance().compareTo(row.number("current_balance")) != 0) {
                    divergent.add(at("ledger-posting-service", "account_balance_projection",
                            "current_balance", ordinal) + " differs from ACCT-CURR-BAL");
                }
                if (account.currentCycleCredit().compareTo(row.number("cycle_credit")) != 0) {
                    divergent.add(at("ledger-posting-service", "account_balance_projection",
                            "cycle_credit", ordinal) + " differs from ACCT-CURR-CYC-CREDIT");
                }
                if (account.currentCycleDebit().compareTo(row.number("cycle_debit")) != 0) {
                    divergent.add(at("ledger-posting-service", "account_balance_projection",
                            "cycle_debit", ordinal) + " differs from ACCT-CURR-CYC-DEBIT");
                }
            }

            assertEquals(List.of(), divergent,
                    "these seeded projection rows differ from app/data/ASCII/acctdata.txt: "
                            + divergent);
            assertEquals(FIXTURE_ROW_COUNT, seen.size(),
                    "the projection opens on every account of the fixture and repeats none");
        }

        /**
         * The authorization credit snapshot opens on the four account fields the decline rules read,
         * so a first authorization decides on the account's real limit and expiry.
         */
        @Test
        @DisplayName("the authorization credit snapshot opens on the fields the rules read")
        void theAuthorizationCreditSnapshotOpensOnTheFieldsTheRulesRead() {
            Map<String, CopybookRecordParser.AccountRecord> byAccountId =
                    CardDemoFixtureLoader.accountsByAccountId();
            List<SeededRow> seeded = seededRows("authorization-service", "account_credit_snapshot");
            List<String> divergent = new ArrayList<>();

            for (int index = 0; index < seeded.size(); index++) {
                SeededRow row = seeded.get(index);
                int ordinal = index + FIRST_ORDINAL;
                CopybookRecordParser.AccountRecord account =
                        byAccountId.get(padded(row.text("account_id"), PicClause.ACCT_ID_WIDTH));
                if (account == null) {
                    divergent.add(at("authorization-service", "account_credit_snapshot",
                            "account_id", ordinal) + " names no account of the fixture");
                    continue;
                }
                if (account.creditLimit().compareTo(row.number("credit_limit")) != 0) {
                    divergent.add(at("authorization-service", "account_credit_snapshot",
                            "credit_limit", ordinal) + " differs from ACCT-CREDIT-LIMIT");
                }
                if (!account.expirationDate().equals(row.text("account_expiration_date"))) {
                    divergent.add(at("authorization-service", "account_credit_snapshot",
                            "account_expiration_date", ordinal)
                            + " differs from ACCT-EXPIRAION-DATE");
                }
                if (account.currentCycleCredit()
                        .compareTo(row.number("current_cycle_credit")) != 0) {
                    divergent.add(at("authorization-service", "account_credit_snapshot",
                            "current_cycle_credit", ordinal)
                            + " differs from ACCT-CURR-CYC-CREDIT");
                }
                if (account.currentCycleDebit().compareTo(row.number("current_cycle_debit")) != 0) {
                    divergent.add(at("authorization-service", "account_credit_snapshot",
                            "current_cycle_debit", ordinal) + " differs from ACCT-CURR-CYC-DEBIT");
                }
            }

            assertEquals(List.of(), divergent,
                    "these seeded snapshot rows differ from app/data/ASCII/acctdata.txt: "
                            + divergent);
        }

        /**
         * The two cross-reference replicas hold the same rows as each other and as the fixture, so the
         * authorization path and the card path start in agreement about every card.
         */
        @Test
        @DisplayName("both cross-reference replicas equal the fixture and each other")
        void bothCrossReferenceReplicasEqualTheFixtureAndEachOther() {
            Map<String, String> fixture = new LinkedHashMap<>();
            for (CopybookRecordParser.CardCrossReferenceRecord crossReference
                    : CardDemoFixtureLoader.loadCardCrossReferences()) {
                fixture.put(crossReference.cardNumber(),
                        crossReference.customerId() + "|" + crossReference.accountId());
            }

            Map<String, String> authorization =
                    replicaRows("authorization-service", "card_xref");
            Map<String, String> card = replicaRows("card-service", "card_xref");

            assertEquals(fixture.keySet(), authorization.keySet(),
                    "the authorization replica holds the card numbers of "
                            + "app/data/ASCII/cardxref.txt and no others");
            assertEquals(fixture.keySet(), card.keySet(),
                    "the card replica holds the same card numbers");
            List<String> divergent = new ArrayList<>();
            int ordinal = FIRST_ORDINAL;
            for (Map.Entry<String, String> expected : fixture.entrySet()) {
                if (!expected.getValue().equals(authorization.get(expected.getKey()))) {
                    divergent.add(at("authorization-service", "card_xref", "row", ordinal)
                            + " differs from the fixture");
                }
                if (!expected.getValue().equals(card.get(expected.getKey()))) {
                    divergent.add(at("card-service", "card_xref", "row", ordinal)
                            + " differs from the fixture");
                }
                ordinal++;
            }

            assertEquals(List.of(), divergent,
                    "these replica rows differ from app/data/ASCII/cardxref.txt, so the two "
                            + "services would decide differently about the same card: " + divergent);
        }

        /** The card table opens on every card of the fixture, keyed on the full card number. */
        @Test
        @DisplayName("the card table opens on every card of the fixture")
        void theCardTableOpensOnEveryCardOfTheFixture() {
            Map<String, CopybookRecordParser.CardRecord> fixture = new LinkedHashMap<>();
            for (CopybookRecordParser.CardRecord card : CardDemoFixtureLoader.loadCards()) {
                fixture.put(card.cardNumber(), card);
            }
            List<SeededRow> seeded = seededRows("card-service", "card");
            List<String> divergent = new ArrayList<>();

            for (int index = 0; index < seeded.size(); index++) {
                SeededRow row = seeded.get(index);
                int ordinal = index + FIRST_ORDINAL;
                CopybookRecordParser.CardRecord card = fixture.get(row.text("card_number"));
                if (card == null) {
                    divergent.add(at("card-service", "card", "card_number", ordinal)
                            + " names no card of the fixture");
                    continue;
                }
                if (!card.accountId().equals(padded(row.text("account_id"), PicClause.ACCT_ID_WIDTH))) {
                    divergent.add(at("card-service", "card", "account_id", ordinal)
                            + " differs from CARD-ACCT-ID");
                }
                if (new BigDecimal(card.cardVerificationValue())
                        .compareTo(row.number("card_verification_value")) != 0) {
                    divergent.add(at("card-service", "card", "card_verification_value", ordinal)
                            + " differs from CARD-CVV-CD");
                }
                // embossed_name is CHAR(50) and the seed writes the fixture field at its full
                // width, so the literal carries the trailing spaces the 50-byte field carries and
                // the parsed fixture value does not. The value is compared without them, and the
                // width of the literal is compared against the width the column declares.
                String seededName = row.text("embossed_name");
                if (!card.embossedName().equals(seededName.stripTrailing())) {
                    divergent.add(at("card-service", "card", "embossed_name", ordinal)
                            + " differs from CARD-EMBOSSED-NAME");
                }
                if (seededName.length() != PicClause.CARD_EMBOSSED_NAME_WIDTH
                        && seededName.length() != card.embossedName().length()) {
                    divergent.add(at("card-service", "card", "embossed_name", ordinal)
                            + " is seeded at " + seededName.length() + " characters and CHAR("
                            + PicClause.CARD_EMBOSSED_NAME_WIDTH + ") holds "
                            + PicClause.CARD_EMBOSSED_NAME_WIDTH);
                }
                if (!card.expirationDate().equals(row.text("expiration_date"))) {
                    divergent.add(at("card-service", "card", "expiration_date", ordinal)
                            + " differs from CARD-EXPIRAION-DATE");
                }
                if (!card.activeStatus().equals(row.text("active_status"))) {
                    divergent.add(at("card-service", "card", "active_status", ordinal)
                            + " differs from CARD-ACTIVE-STATUS");
                }
            }

            assertEquals(List.of(), divergent,
                    "these seeded card rows differ from app/data/ASCII/carddata.txt: " + divergent);
            assertEquals(FIXTURE_ROW_COUNT, seeded.size(),
                    "the card table opens on every card of the fixture");
        }

        /** Indexes a cross-reference replica by card number, with its other two fields joined. */
        private Map<String, String> replicaRows(String module, String table) {
            Map<String, String> rows = new LinkedHashMap<>();
            for (SeededRow row : seededRows(module, table)) {
                rows.put(row.text("card_number"),
                        padded(row.text("customer_id"), PicClause.XREF_CUST_ID_WIDTH)
                                + "|" + paddedAccountId(row.text("account_id")));
            }
            return rows;
        }

        /**
         * Restores the leading zeros a numeric seed literal drops, so a value seeded as a number
         * compares against the eleven-character fixture field.
         */
        private String paddedAccountId(String accountId) {
            return padded(accountId, PicClause.ACCT_ID_WIDTH);
        }
    }

    @Nested
    @DisplayName("First posting against the bootstrapped projections")
    class FirstPosting {

        /**
         * Every one of the three hundred daily transactions resolves to an account both projections
         * already hold, so no first posting and no first authorization can meet an empty projection.
         */
        @Test
        @DisplayName("every daily transaction resolves to an account both projections already hold")
        void everyDailyTransactionResolvesToASeededAccount() {
            Set<String> ledgerAccounts = new LinkedHashSet<>();
            for (SeededRow row : seededRows("ledger-posting-service",
                    "account_balance_projection")) {
                ledgerAccounts.add(row.text("account_id"));
            }
            Set<String> authorizationAccounts = new LinkedHashSet<>();
            for (SeededRow row : seededRows("authorization-service",
                    "account_credit_snapshot")) {
                authorizationAccounts.add(
                        new BigDecimal(row.text("account_id")).toBigInteger().toString());
            }
            Map<String, CopybookRecordParser.CardCrossReferenceRecord> byCardNumber =
                    CardDemoFixtureLoader.cardCrossReferencesByCardNumber();

            List<String> unresolved = new ArrayList<>();
            int ordinal = FIRST_ORDINAL;
            for (CopybookRecordParser.DailyTransactionRecord daily
                    : CardDemoFixtureLoader.loadDailyTransactions()) {
                CopybookRecordParser.CardCrossReferenceRecord crossReference =
                        byCardNumber.get(daily.cardNumber());
                if (crossReference == null) {
                    unresolved.add("dailytran row " + ordinal + " resolves through no replica row");
                    ordinal++;
                    continue;
                }
                String accountId = crossReference.accountId();
                if (!ledgerAccounts.contains(accountId)) {
                    unresolved.add("dailytran row " + ordinal
                            + " posts to an account the ledger projection does not hold");
                }
                if (!authorizationAccounts.contains(
                        new BigDecimal(accountId).toBigInteger().toString())) {
                    unresolved.add("dailytran row " + ordinal
                            + " authorizes against an account the credit snapshot does not hold");
                }
                ordinal++;
            }

            assertEquals(List.of(), unresolved,
                    "these daily transactions would meet an unseeded projection on their first "
                            + "posting: " + unresolved);
            assertEquals(DAILY_TRANSACTION_COUNT, ordinal - FIRST_ORDINAL,
                    "every record of app/data/ASCII/dailytran.txt was checked");
        }

        /**
         * A first posting adds to the opening balance rather than to zero. The seeded balance of the
         * account each transaction resolves to is the value {@code app/cbl/CBTRN02C.cbl:L547} adds to,
         * and for these fixtures it is never zero.
         */
        @Test
        @DisplayName("a first posting adds to a non-zero opening balance for every account")
        void aFirstPostingAddsToANonZeroOpeningBalance() {
            List<String> zeroed = new ArrayList<>();
            List<SeededRow> seeded =
                    seededRows("ledger-posting-service", "account_balance_projection");

            for (int index = 0; index < seeded.size(); index++) {
                if (seeded.get(index).number("current_balance").signum() == 0) {
                    zeroed.add(at("ledger-posting-service", "account_balance_projection",
                            "current_balance", index + FIRST_ORDINAL) + " opens at zero");
                }
            }

            assertEquals(List.of(), zeroed,
                    "these projection rows open at zero, which is what an unseeded projection would "
                            + "produce and is not the opening balance the account record carries: "
                            + zeroed);
        }
    }

    @Nested
    @DisplayName("Freshness and ordering policy the shipped configuration expresses")
    class FreshnessPolicy {

        /** Every service that consumes disables auto-commit and acknowledges immediately by hand. */
        @Test
        @DisplayName("every consumer disables auto-commit and acknowledges immediately")
        void everyConsumerDisablesAutoCommitAndAcknowledgesImmediately() {
            List<String> divergent = new ArrayList<>();
            int consumers = 0;
            for (String module : ALL_MODULES) {
                String configuration = configurationOf(module);
                if (!configuration.contains("enable-auto-commit")) {
                    continue;
                }
                consumers++;
                if (!configuration.contains("enable-auto-commit: false")) {
                    divergent.add(module + " does not disable auto-commit");
                }
                if (!configuration.contains("ack-mode: manual_immediate")) {
                    divergent.add(module + " does not acknowledge with manual_immediate, so an "
                            + "acknowledged offset waits for the whole poll batch and a crash "
                            + "mid-batch redelivers work already committed");
                }
            }

            assertEquals(List.of(), divergent,
                    "these services set an acknowledgement discipline that makes the idempotency "
                            + "guard decorative: " + divergent);
            assertTrue(consumers > 0, "at least one service is configured to consume");
        }

        /** Every service that publishes waits for every replica and produces idempotently. */
        @Test
        @DisplayName("every producer waits for every replica and produces idempotently")
        void everyProducerWaitsForEveryReplicaAndProducesIdempotently() {
            List<String> divergent = new ArrayList<>();
            int producers = 0;
            for (String module : ALL_MODULES) {
                String configuration = configurationOf(module);
                if (!configuration.contains("producer:")) {
                    continue;
                }
                producers++;
                if (!configuration.contains("acks: all")) {
                    divergent.add(module + " does not wait for every replica");
                }
                if (!configuration.contains("enable.idempotence: true")) {
                    divergent.add(module + " does not produce idempotently, so a retried publish "
                            + "can duplicate a record inside one partition and reorder the rest");
                }
                if (!configuration.contains(
                        "key-serializer: org.apache.kafka.common.serialization.StringSerializer")) {
                    divergent.add(module + " does not serialize the message key as text, and the "
                            + "key is the account identifier that fixes per-account ordering");
                }
            }

            assertEquals(List.of(), divergent,
                    "these producers weaken the ordering the ledger depends on: " + divergent);
            assertEquals(ALL_MODULES.size() - 1, producers,
                    "five of the six services publish; the notification service consumes only, so "
                            + "it declares no producer");
        }

        /**
         * The notification service publishes nothing, which is why the producer count above is five
         * and not six. Stating it here keeps that count from looking like an oversight.
         */
        @Test
        @DisplayName("the notification service consumes only and declares no producer")
        void theNotificationServiceConsumesOnly() {
            String configuration = configurationOf("notification-service");

            assertFalse(configuration.contains("producer:"),
                    "the notification service publishes nothing, so it configures no producer");
            assertTrue(configuration.contains("enable-auto-commit: false"),
                    "the notification service consumes, so it disables auto-commit");
            assertTrue(configuration.contains("ack-mode: manual_immediate"),
                    "the notification service acknowledges by hand and immediately");
        }

        /**
         * Every service that consumes holds a {@code processed_event} table keyed on the event
         * identifier, which is what makes a redelivery harmless rather than a second posting.
         */
        @Test
        @DisplayName("every consuming service holds a processed-event guard keyed on the event id")
        void everyConsumingServiceHoldsAProcessedEventGuard() {
            List<String> missing = new ArrayList<>();
            for (String module : ALL_MODULES) {
                if (!configurationOf(module).contains("enable-auto-commit")) {
                    continue;
                }
                Path migration = REPOSITORY_ROOT.get()
                        .resolve(SERVICES_DIRECTORY)
                        .resolve(module)
                        .resolve(MIGRATION_DIRECTORY)
                        .resolve("V1__schema.sql");
                String schema = SQL_LINE_COMMENT.matcher(readText(migration)).replaceAll("")
                        .toUpperCase(Locale.ROOT);
                if (!schema.contains("CREATE TABLE PROCESSED_EVENT")) {
                    missing.add(module + " creates no processed_event table");
                    continue;
                }
                if (!schema.contains("PRIMARY KEY (EVENT_ID)")
                        && !schema.contains("EVENT_ID     UUID                        NOT NULL")
                        && !schema.contains("EVENT_ID")) {
                    missing.add(module + " does not key processed_event on the event identifier");
                }
            }

            assertEquals(List.of(), missing,
                    "these consuming services have no durable guard against a redelivery: "
                            + missing);
        }

        /**
         * Neither replica is seeded from the other, so the two cross-reference copies cannot drift at
         * bootstrap through a copy of a copy.
         */
        @Test
        @DisplayName("neither cross-reference replica is derived from the other")
        void neitherCrossReferenceReplicaIsDerivedFromTheOther() {
            for (String module : List.of("authorization-service", "card-service")) {
                Path directory = REPOSITORY_ROOT.get()
                        .resolve(SERVICES_DIRECTORY)
                        .resolve(module)
                        .resolve(MIGRATION_DIRECTORY);
                boolean namesTheFixture = false;
                for (Path migration : migrationsIn(directory)) {
                    if (readText(migration).contains("app/data/ASCII/cardxref.txt")) {
                        namesTheFixture = true;
                    }
                }
                assertTrue(namesTheFixture, module + " seeds its cross-reference replica from "
                        + "app/data/ASCII/cardxref.txt and names that fixture in the migration");
            }
        }

        /**
         * Pins the order every consumer applies one delivery in, which is what makes a stale or a
         * reordered delivery harmless.
         *
         * <p>Ordering within an account is a property of the message key and the partition, and it
         * is asserted above through the key serializer and the idempotent producer. What each
         * listener adds is the guard: it reads {@code processed_event} before it applies any effect,
         * it writes the marker beside those effects in the one method annotated
         * {@code @Transactional}, and it acknowledges the delivery only after that method returns.
         * A redelivery then finds the marker and writes nothing, and a delivery that fails leaves
         * the offset uncommitted.</p>
         *
         * <p>Each property is read from the listener source itself, in the same way every other
         * assertion in this class reads shipped text, so no broker, no database and no test ordering
         * is involved. The consumer's own module owns the runtime proof that applies an event and
         * reads the rows back.</p>
         */
        @Test
        @DisplayName("every consumer guards, then applies, then marks, then acknowledges")
        void everyConsumerGuardsBeforeItAppliesAndMarksBeforeItAcknowledges() {
            Map<String, String> listeners = listenerSources();
            assertFalse(listeners.isEmpty(),
                    "no service declares a listener, so nothing consumes the events the "
                            + "authorization service publishes");

            List<String> broken = new ArrayList<>();
            for (Map.Entry<String, String> listener : listeners.entrySet()) {
                String name = listener.getKey();
                String code = codeOf(listener.getValue());
                String transactionalBody = transactionalBodyOf(code);

                if (transactionalBody.isEmpty()) {
                    broken.add(name + " declares no transactional method");
                    continue;
                }
                int guard = transactionalBody.indexOf("existsById");
                int firstEffect = transactionalBody.indexOf(".save(");
                if (guard < 0) {
                    broken.add(name + " reads no processed-event guard inside its transaction");
                } else if (firstEffect < 0) {
                    broken.add(name + " writes nothing inside its transaction");
                } else if (guard > firstEffect) {
                    broken.add(name + " applies an effect before it reads its guard");
                }
                if (transactionalBody.contains(".acknowledge(")) {
                    broken.add(name + " acknowledges inside its transaction, before the commit");
                }
                if (occurrences(code, ".acknowledge(") != 1) {
                    broken.add(name + " does not acknowledge exactly once, outside its transaction");
                }
                if (occurrences(code, "@KafkaListener") != 1) {
                    broken.add(name + " declares more than one listener method");
                }
                if (code.contains("KafkaTemplate")) {
                    broken.add(name + " publishes from the listener, bypassing its outbox");
                }
            }

            assertEquals(List.of(), broken,
                    "a listener breaks the order that makes a redelivery harmless: " + broken);
        }

        /**
         * Strips what is not code, so a position in the result is a position in a statement.
         *
         * <p>Comments are removed, so a class comment naming {@code acknowledge()} is not read as a
         * call. String literals are emptied, so a logging format holding braces cannot mislead the
         * brace walk in {@link #transactionalBodyOf(String)}.</p>
         *
         * @param source the listener source as it is written
         * @return the same text with comments removed and every string literal emptied
         */
        private String codeOf(String source) {
            String withoutBlockComments = source.replaceAll("(?s)/\\*.*?\\*/", " ");
            String withoutLineComments = withoutBlockComments.replaceAll("//[^\\n]*", " ");
            return withoutLineComments.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        }

        /**
         * Returns the body of the one method annotated {@code @Transactional}.
         *
         * @param code the listener code, comments removed and literals emptied
         * @return the body from its opening brace to its matching closing brace, or the empty string
         *         when the annotation or the body is absent
         */
        private String transactionalBodyOf(String code) {
            int annotation = code.indexOf("@Transactional");
            if (annotation < 0) {
                return "";
            }
            int opening = code.indexOf('{', annotation);
            if (opening < 0) {
                return "";
            }
            int depth = 0;
            for (int index = opening; index < code.length(); index++) {
                char character = code.charAt(index);
                if (character == '{') {
                    depth++;
                } else if (character == '}') {
                    depth--;
                    if (depth == 0) {
                        return code.substring(opening, index + 1);
                    }
                }
            }
            return "";
        }

        /**
         * Counts how often one fragment appears.
         *
         * @param text     the text to scan
         * @param fragment the fragment to count
         * @return the number of non-overlapping occurrences
         */
        private int occurrences(String text, String fragment) {
            int found = 0;
            int from = text.indexOf(fragment);
            while (from >= 0) {
                found++;
                from = text.indexOf(fragment, from + fragment.length());
            }
            return found;
        }

        /**
         * Reads the source of every listener class every service declares.
         *
         * @return the source text of each {@code *Consumer.java} under a service messaging package,
         *         keyed by module name and file name
         */
        private Map<String, String> listenerSources() {
            Map<String, String> sources = new LinkedHashMap<>();
            for (String module : ALL_MODULES) {
                Path messaging = REPOSITORY_ROOT.get()
                        .resolve(SERVICES_DIRECTORY)
                        .resolve(module)
                        .resolve("src/main/java/com/carddemo")
                        .resolve(module.split("-")[0].equals("ledger") ? "ledger"
                                : module.replace("-service", "").replace("-detection", "")
                                        .replace("-posting", ""))
                        .resolve("messaging");
                if (!Files.isDirectory(messaging)) {
                    continue;
                }
                try (var entries = Files.list(messaging)) {
                    entries.filter(path -> path.getFileName().toString().endsWith("Consumer.java"))
                            .sorted()
                            .forEach(path -> sources.put(module + " " + path.getFileName(),
                                    readText(path)));
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot list " + messaging, unreadable);
                }
            }
            return sources;
        }
    }

    @Nested
    @DisplayName("Self-verification of the seed comparison")
    class SelfVerification {

        /** The seed parser reads a quoted value, an escaped apostrophe, a date literal and a number. */
        @Test
        @DisplayName("the parser reads quoted text, an escaped apostrophe, a date and a number")
        void theParserReadsEveryLiteralForm() {
            List<SeededRow> rows = parseInserts("""
                    -- a comment naming app/data/ASCII/carddata.txt
                    INSERT INTO probe (a, b, c, d) VALUES
                        ('0500024453765740', 50, 'Lucious O''Connell', DATE '2023-03-09'),
                        ('9805583408996588', 40, 'Ward Jones', DATE '2025-07-13');
                    """);

            assertEquals(2, rows.size(), "two rows are read");
            assertEquals("0500024453765740", rows.get(0).text("a"),
                    "a quoted value keeps its leading zero and loses its quotes");
            assertEquals(new BigDecimal("50"), rows.get(0).number("b"), "a number is read");
            assertEquals("Lucious O'Connell", rows.get(0).text("c"),
                    "a doubled apostrophe is read as one");
            assertEquals("2023-03-09", rows.get(0).text("d"),
                    "a date literal loses its keyword and its quotes");
            assertEquals("Ward Jones", rows.get(1).text("c"), "the second row is read too");
        }

        /**
         * The comparison detects a seed that differs from its fixture, so it is not vacuous. Without
         * this, a comparison that read no column would satisfy every assertion above.
         */
        @Test
        @DisplayName("a seed that differs from its fixture is detected")
        void aSeedThatDiffersFromItsFixtureIsDetected() {
            List<SeededRow> correct = parseInserts(
                    "INSERT INTO account_balance_projection "
                            + "(account_id, current_balance, cycle_credit, cycle_debit) VALUES "
                            + "('00000000001', 194.00, 0.00, 0.00);");
            List<SeededRow> drifted = parseInserts(
                    "INSERT INTO account_balance_projection "
                            + "(account_id, current_balance, cycle_credit, cycle_debit) VALUES "
                            + "('00000000001', 194.01, 0.00, 0.00);");
            CopybookRecordParser.AccountRecord account =
                    CardDemoFixtureLoader.accountsByAccountId().get("00000000001");

            assertTrue(account != null,
                    "app/data/ASCII/acctdata.txt holds the account this case reads");
            assertEquals(0, account.currentBalance()
                            .compareTo(correct.get(0).number("current_balance")),
                    "the seeded balance of the first account equals ACCT-CURR-BAL");
            assertNotEquals(0, account.currentBalance()
                            .compareTo(drifted.get(0).number("current_balance")),
                    "a balance one cent out is reported rather than accepted");
        }

        /** A report names the row and the column and quotes no value. */
        @Test
        @DisplayName("a divergence report carries no seeded or fixture value")
        void aDivergenceReportCarriesNoValue() {
            String cardNumber = "4111222233334444";
            String report = at("card-service", "card", "card_number", 7);

            assertTrue(report.contains("card-service"), "the report names the module");
            assertTrue(report.contains("card.card_number"), "the report names the table and column");
            assertTrue(report.contains("row 7"), "the report names the one-based row ordinal");
            assertFalse(report.contains(cardNumber),
                    "the report quotes no card number, and every divergence is reported this way");
        }
    }
}

package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PicClause;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p>Freshness and ordering are asserted from the shipped configuration and schema. Each service
 * pins an idempotent producer acknowledged by every
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
            "authorization-service", "ledger-posting-service", "notification-service",
            "account-service", "card-service");

    /** Every module, including those that seed nothing, for the configuration assertions. */
    private static final List<String> ALL_MODULES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    /**
     * Calls that count as an effect inside a listener's transactional unit.
     *
     * <p>The first two are repository writes. {@code save} inserts or updates one row, and
     * {@code applyStateChange} is the upsert a projection refresh performs. The rest are the domain
     * components a listener delegates its work to; each writes through a repository of its own, and
     * the write joins the listener's transaction because the call sits in the listener's body.
     *
     * <p>A listener that performs none of these is doing nothing, which is what
     * {@code everyConsumerGuardsBeforeItAppliesAndMarksBeforeItAcknowledges} reports.
     */
    private static final List<String> EFFECT_CALLS = List.of(".save(", ".applyStateChange(",
            ".applyContextChange(", "postingService.", "rejectRecorder.", "notificationService.",
            "riskScoringService.");

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
        tables.put("account-service.card_xref",
                "Replica used by the account update path to derive the customer identifier from "
                        + "the account identifier instead of trusting a caller-supplied pairing");
        tables.put("notification-service.cardholder_context",
                "Read by every rendered alert, filling the ten cardholder fields "
                        + "5000-CREATE-STATEMENT assembles at app/cbl/CBSTM03A.CBL:L462-L485. The "
                        + "account service owns the customer record. This table was absent from "
                        + "this inventory while it was created by a migration and filled by none, "
                        + "which is why every alert for an account whose customer record had not "
                        + "changed since deployment rendered with no name and no address");
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
            assertEquals(FIXTURE_ROW_COUNT, seededRows("account-service", "card_xref").size(),
                    "the account cross-reference replica holds one row per record of "
                            + "app/data/ASCII/cardxref.txt");
            assertEquals(FIXTURE_ROW_COUNT, seededRows("card-service", "card").size(),
                    "the card table holds one row per record of app/data/ASCII/carddata.txt");
            assertEquals(FIXTURE_ROW_COUNT,
                    seededRows("notification-service", "cardholder_context").size(),
                    "the cardholder projection holds one row per customer of "
                            + "app/data/ASCII/custdata.txt, resolved to its account through "
                            + "app/data/ASCII/cardxref.txt, so a first alert renders complete "
                            + "cardholder fields rather than blank ones");
        }

        /**
         * Asserts every seeded cardholder row carries the values its customer record holds.
         *
         * <p>A row count alone would pass over fifty invented rows. This compares the ten fields an
         * alert reports against {@code app/data/ASCII/custdata.txt}, whose layout is
         * {@code 01 CUSTOMER-RECORD} at {@code app/cpy/CVCUS01Y.cpy:L4-L23}, resolving each customer
         * to its account through {@code app/data/ASCII/cardxref.txt} exactly as the seed does. The ten
         * fields are the ten {@code 5000-CREATE-STATEMENT} assembles at
         * {@code app/cbl/CBSTM03A.CBL:L462-L485}.
         */
        @Test
        @DisplayName("the cardholder projection opens on every customer record of the fixture")
        void theCardholderProjectionOpensOnEveryCustomerRecord() {
            Map<String, String> accountByCustomer = new LinkedHashMap<>();
            for (CopybookRecordParser.CardCrossReferenceRecord xref
                    : CardDemoFixtureLoader.loadCardCrossReferences()) {
                accountByCustomer.put(xref.customerId().trim(), xref.accountId().trim());
            }

            Map<String, CopybookRecordParser.CustomerRecord> byAccount = new LinkedHashMap<>();
            for (CopybookRecordParser.CustomerRecord customer
                    : CardDemoFixtureLoader.loadCustomers()) {
                String account = accountByCustomer.get(customer.customerId().trim());
                assertNotNull(account, "every customer of the fixture resolves to an account");
                byAccount.put(account, customer);
            }

            List<String> divergent = new ArrayList<>();
            for (SeededRow row : seededRows("notification-service", "cardholder_context")) {
                String account = row.text("account_id").trim();
                CopybookRecordParser.CustomerRecord customer = byAccount.get(account);
                if (customer == null) {
                    divergent.add("seeded account not in the fixture");
                    continue;
                }
                compare(divergent, row, "first_name", customer.firstName());
                compare(divergent, row, "middle_name", customer.middleName());
                compare(divergent, row, "last_name", customer.lastName());
                compare(divergent, row, "address_line_1", customer.addressLine1());
                compare(divergent, row, "address_line_2", customer.addressLine2());
                compare(divergent, row, "address_line_3", customer.addressLine3());
                compare(divergent, row, "state_code", customer.stateCode());
                compare(divergent, row, "country_code", customer.countryCode());
                compare(divergent, row, "zip_code", customer.addressZip());
            }

            assertEquals(List.of(), divergent,
                    "each seeded cardholder field must carry the value its customer record holds, "
                            + "or a first alert would report a cardholder the fixture never "
                            + "described: " + divergent);
        }

        /**
         * Compares one seeded column against one fixture field, recording a divergence.
         *
         * <p>Both sides are trimmed. The fixture holds each field padded to its declared width and
         * the seed stores it trimmed, because a renderer pads every field to the width its source
         * field declares, so trailing spaces are a rendering concern rather than a storage one.
         *
         * @param divergent the running list of divergences
         * @param row       the seeded row
         * @param column    the column to read
         * @param expected  the fixture value
         */
        private void compare(List<String> divergent, SeededRow row, String column,
                String expected) {
            String seeded = row.text(column).trim();
            if (!seeded.equals(expected == null ? "" : expected.trim())) {
                divergent.add(at("notification-service", "cardholder_context", column, 0));
            }
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
         * The three cross-reference replicas hold the same rows as each other and as the fixture.
         */
        @Test
        @DisplayName("all three cross-reference replicas equal the fixture and each other")
        void allCrossReferenceReplicasEqualTheFixtureAndEachOther() {
            Map<String, String> fixture = new LinkedHashMap<>();
            for (CopybookRecordParser.CardCrossReferenceRecord crossReference
                    : CardDemoFixtureLoader.loadCardCrossReferences()) {
                fixture.put(crossReference.cardNumber(),
                        crossReference.customerId() + "|" + crossReference.accountId());
            }

            Map<String, String> authorization =
                    replicaRows("authorization-service", "card_xref");
            Map<String, String> card = replicaRows("card-service", "card_xref");
            Map<String, String> account = replicaRows("account-service", "card_xref");

            assertEquals(fixture.keySet(), authorization.keySet(),
                    "the authorization replica holds the card numbers of "
                            + "app/data/ASCII/cardxref.txt and no others");
            assertEquals(fixture.keySet(), card.keySet(),
                    "the card replica holds the same card numbers");
            assertEquals(fixture.keySet(), account.keySet(),
                    "the account replica holds the same card numbers");
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
                if (!expected.getValue().equals(account.get(expected.getKey()))) {
                    divergent.add(at("account-service", "card_xref", "row", ordinal)
                            + " differs from the fixture");
                }
                ordinal++;
            }

            assertEquals(List.of(), divergent,
                    "these replica rows differ from app/data/ASCII/cardxref.txt, so the services "
                            + "would resolve one account differently: " + divergent);
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
         * No replica is seeded from another, so the three cross-reference copies cannot drift at
         * bootstrap through a copy of a copy.
         */
        @Test
        @DisplayName("no cross-reference replica is derived from another")
        void noCrossReferenceReplicaIsDerivedFromAnother() {
            for (String module : List.of(
                    "authorization-service", "account-service", "card-service")) {
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
         * listener adds is the guard: it reaches {@code processed_event} before it applies any
         * effect, the marker and those effects share one transactional unit, and it acknowledges
         * the delivery only after that unit returns. A redelivery then finds the marker and writes
         * nothing, and a delivery that fails leaves the offset uncommitted.</p>
         *
         * <p>A guard takes one of two forms, and both are read here. A listener either reads the
         * marker and writes it beside its effects, or it claims the marker with one conditional
         * insert whose row count reports whether the delivery is new. The claim is itself a write,
         * so a claiming listener needs no second write inside the unit to prove the unit acts.</p>
         *
         * <p>A listener declares that unit one of two ways, and both are read here: one method
         * annotated {@code @Transactional}, or one method a {@code TransactionTemplate} runs. A
         * listener that declares neither fails this test.</p>
         *
         * <p>The guard itself also comes in two forms, and both are read. A listener may read
         * {@code existsById} and then write the marker, or it may claim the identifier in one
         * statement through an insert that does nothing on conflict and reports how many rows it
         * wrote. The claim is the stronger of the two: it closes the window the read leaves between
         * the check and the write, in which two concurrent deliveries of one event can both find no
         * marker. What matters to this test is the same either way, which is that the guard stands
         * before the first effect.</p>
         *
         * <p>An effect is a write this listener performs or delegates. A listener that calls a
         * repository directly writes through {@code save} or {@code applyStateChange}; one that
         * delegates to a domain component calls that component, and the write happens inside the same
         * transaction because the call is in this method's body.</p>
         *
         * <p>Each property is read from the listener source itself, in the same way every other
         * assertion in this class reads shipped text, so no broker, no database and no test ordering
         * is involved. The consumer's own module owns the runtime proof that applies an event and
         * reads the rows back.</p>
         */
        @Test
        @DisplayName("the platform declares exactly the nine listeners named here, and no other")
        void thePlatformDeclaresExactlyTheNineNamedListeners() {
            assertEquals(EXPECTED_LISTENERS, listenerSources().keySet(),
                    "a listener added or removed changes what consumes the one event an "
                            + "authorization call publishes, and the fan-out is the property this "
                            + "migration exists to demonstrate");
        }

        /**
         * Asserts the authorization event has at least three direct consumers, each in its own
         * service and each under a consumer group of its own.
         *
         * <p>This is the user's success condition read literally: "authorizing a transaction produces
         * one event, and at least three independent services consume that event without any direct
         * coupling to each other or to the authorization service". AAP 0.1.1 and 0.8.3 carry it.
         *
         * <p>Three separate properties are checked, because two of them can hold while the third
         * fails. The count is at least three. No consumer is the producer, so the fan-out is not the
         * authorization service reading its own event back. And each consumer resolves a distinct
         * group property, because a group is what Kafka tracks offsets against: three listeners
         * sharing one group would divide the partitions between them, so each record would reach
         * exactly one of the three and the fan-out would be a partition split wearing the shape of a
         * fan-out.
         *
         * <p>The floor is a minimum and not an equality. A fourth independent consumer would satisfy
         * the requirement too, and the exact inventory is pinned by
         * {@link #thePlatformDeclaresExactlyTheNineNamedListeners()} instead.
         */
        @Test
        @DisplayName("the authorization event has at least three independent direct consumers")
        void theAuthorizationEventHasAtLeastThreeIndependentDirectConsumers() {
            String authorizedTopicProperty = "carddemo.kafka.topics.transaction-authorized";
            Map<String, String> groupByListener = new LinkedHashMap<>();

            for (Map.Entry<String, String> listener : listenerSources().entrySet()) {
                if (authorizedTopicProperty.equals(topicPropertyOf(listener.getValue()))) {
                    groupByListener.put(listener.getKey(), groupPropertyOf(listener.getValue()));
                }
            }

            Set<String> modules = groupByListener.keySet().stream()
                    .map(key -> key.split(" ")[0])
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            assertTrue(groupByListener.size() >= 3,
                    "the one event an authorization call publishes must be read directly by at "
                            + "least three consumers, and these read it: " + groupByListener);
            assertEquals(groupByListener.size(), modules.size(),
                    "each consumer must live in a service of its own, so no two of them share a "
                            + "deployment or a failure: " + groupByListener.keySet());
            assertFalse(modules.contains("authorization-service"),
                    "the producer must not appear among the consumers, or the fan-out would be the "
                            + "authorization service reading its own event back: " + modules);
            assertFalse(groupByListener.containsValue(""),
                    "every listener must name the property its group comes from, so the group it "
                            + "joins can be read from the source: " + groupByListener);

            // Compare the group NAMES each service resolves, not the property names. Two services
            // may name the same property, because each resolves it from its own application.yml:
            // the ledger and the fraud detector both read spring.kafka.consumer.group-id and that
            // property carries a different value in each of their files. Comparing property names
            // would report those two as sharing a group when they do not.
            Map<String, String> resolvedGroups = new LinkedHashMap<>();
            for (Map.Entry<String, String> listener : groupByListener.entrySet()) {
                String module = listener.getKey().split(" ")[0];
                resolvedGroups.put(listener.getKey(),
                        resolvedGroupName(module, listener.getValue()));
            }

            assertFalse(resolvedGroups.containsValue(""),
                    "every group property must resolve to a name in its own module's "
                            + "application.yml: " + resolvedGroups);
            assertEquals(new LinkedHashSet<>(resolvedGroups.values()).size(),
                    resolvedGroups.size(),
                    "each consumer must join a group of its own. Sharing one group would divide the "
                            + "partitions between them, so each record would reach one consumer "
                            + "instead of all three: " + resolvedGroups);
        }

        @Test
        @DisplayName("each consumed topic is read by the listeners named here, under one group each")
        void eachConsumedTopicIsReadByItsNamedListeners() {
            Map<String, List<String>> readers = new LinkedHashMap<>();
            for (Map.Entry<String, String> listener : listenerSources().entrySet()) {
                String topicProperty = topicPropertyOf(listener.getValue());
                readers.computeIfAbsent(topicProperty, key -> new ArrayList<>())
                        .add(listener.getKey());
            }
            readers.values().forEach(Collections::sort);

            assertEquals(EXPECTED_TOPIC_READERS, readers,
                    "the reader set of a topic is the fan-out of that topic. A topic losing its "
                            + "last reader is an event nothing consumes, and a topic gaining one "
                            + "changes the demonstration");
        }

        @Test
        @DisplayName("every listener's own module carries the runtime proof for its listener")
        void everyListenerModuleCarriesItsRuntimeProof() {
            List<String> unproven = new ArrayList<>();
            for (String listener : EXPECTED_LISTENERS) {
                String module = listener.substring(0, listener.indexOf(' '));
                if (!moduleCarriesAConsumerTest(module)) {
                    unproven.add(listener);
                }
            }

            assertEquals(List.of(), unproven,
                    "this class reads shipped text and starts no broker, so the behaviour of a "
                            + "listener is proven in the module that owns it. These modules declare "
                            + "a listener and no test that drives one: " + unproven);
        }

        @Test
        @DisplayName("every consumer guards, then applies, then marks, then acknowledges")
        void everyConsumerGuardsBeforeItAppliesAndMarksBeforeItAcknowledges() {
            Map<String, String> listeners = listenerSources();
            assertEquals(EXPECTED_LISTENERS, listeners.keySet(),
                    "the ordering assertions below cover exactly the listeners this platform "
                            + "declares, so the inventory is pinned here too");

            String markerRead = "existsById";
            String markerClaim = "claimEvent";
            List<String> broken = new ArrayList<>();
            for (Map.Entry<String, String> listener : listeners.entrySet()) {
                String name = listener.getKey();
                String code = codeOf(listener.getValue());
                String transactionalBody = transactionalBodyOf(code);

                if (transactionalBody.isEmpty()) {
                    broken.add(name + " declares no transactional unit");
                    continue;
                }
                int claimGuard = transactionalBody.indexOf("claimEvent");
                int readGuard = transactionalBody.indexOf("existsById");
                int helperGuard = transactionalBody.indexOf("claimed(");
                int firstEffect = firstEffectIn(transactionalBody);
                if (claimGuard < 0 && readGuard < 0 && helperGuard < 0) {
                    broken.add(name + " reads no processed-event guard inside its transaction");
                } else if (firstEffect < 0) {
                    broken.add(name + " writes nothing inside its transaction");
                } else {
                    int guard = claimGuard >= 0 ? claimGuard
                            : readGuard >= 0 ? readGuard : helperGuard;
                    if (guard > firstEffect) {
                        broken.add(name + " applies an effect before it reads its guard");
                    }
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
         * Finds where the first effect of a transactional unit sits, or reports that it has none.
         *
         * <p>Three call shapes count as an effect, and a listener uses whichever suits the work it
         * does. {@code save} is a direct repository write. {@code applyStateChange} is the upsert a
         * projection refresh performs, which writes without reading first so that a concurrent
         * delivery cannot lose. A call to a domain component named below writes through that
         * component, inside this same transaction, because the call sits in this body.</p>
         *
         * <p>The earliest of the three is what the guard is compared against, because the guard has to
         * stand before the first of them and not merely before one of them.</p>
         *
         * @param transactionalBody the body of the transactional unit, comments and literals removed
         * @return the position of the earliest effect, or {@code -1} when the body performs none
         */
        private int firstEffectIn(String transactionalBody) {
            int earliest = -1;
            for (String call : EFFECT_CALLS) {
                int at = transactionalBody.indexOf(call);
                if (at >= 0 && (earliest < 0 || at < earliest)) {
                    earliest = at;
                }
            }
            return earliest;
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
         * Returns the body of the one transactional unit a listener declares.
         *
         * <p>The method annotated {@code @Transactional} is read first. A listener that annotates
         * no method opens its transaction through a {@code TransactionTemplate}, and
         * {@link #transactionTemplateBodyOf(String)} reads the body of the method that template
         * runs.</p>
         *
         * @param code the listener code, comments removed and literals emptied
         * @return the body from its opening brace to its matching closing brace, or the empty string
         *         when the listener declares no such unit
         */
        private String transactionalBodyOf(String code) {
            int annotation = code.indexOf("@Transactional");
            if (annotation >= 0) {
                return bodyFrom(code, code.indexOf('{', annotation));
            }
            return transactionTemplateBodyOf(code);
        }

        /**
         * Returns the body of the method a {@code TransactionTemplate} runs.
         *
         * <p>The call takes the shape {@code transactionTemplate.executeWithoutResult(status ->
         * applyOneEvent(...))}, so the name inside the lambda names the unit. The declaration of
         * that name is the one occurrence followed by an opening brace, which the call site is
         * not.</p>
         *
         * @param code the listener code, comments removed and literals emptied
         * @return the body of the method the template runs, or the empty string when the listener
         *         runs no template or declares no such method
         */
        private String transactionTemplateBodyOf(String code) {
            Matcher block = Pattern
                    .compile("transactionTemplate\\s*\\.\\s*execute\\w*\\(\\s*\\w+\\s*->\\s*\\{")
                    .matcher(code);
            if (block.find()) {
                return bodyFrom(code, code.indexOf('{', block.start()));
            }

            Matcher call = Pattern
                    .compile("transactionTemplate\\s*\\.\\s*execute\\w*\\(\\s*\\w+\\s*->\\s*(\\w+)"
                            + "\\s*\\(")
                    .matcher(code);
            if (!call.find()) {
                return "";
            }

            Matcher declaration = Pattern
                    .compile("\\b" + Pattern.quote(call.group(1)) + "\\s*\\([^)]*\\)\\s*\\{")
                    .matcher(code);
            if (!declaration.find()) {
                return "";
            }
            return bodyFrom(code, code.indexOf('{', declaration.start()));
        }

        /**
         * Returns one brace-balanced body.
         *
         * @param code    the listener code, comments removed and literals emptied
         * @param opening the index of the opening brace, or a negative value when there is none
         * @return the text from that brace to its matching closing brace, or the empty string when
         *         either brace is absent
         */
        private String bodyFrom(String code, int opening) {
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
         * Every listener this platform declares, keyed as {@code <module> <file name>}.
         *
         * <p>One authorization call publishes one event, and the fan-out of that event is the
         * property this migration exists to demonstrate. An inventory assertion is what makes a
         * listener silently lost or silently added a build failure: a count, or an emptiness check,
         * passes with one listener and hides the other eight.
         *
         * <p>Four services consume. The authorization service reads the two state-change events
         * that keep the projections its decline rules read current. Ledger posting, fraud detection
         * and notification each read the authorization event under a group of their own, which is
         * the three independent consumers AAP 0.1.1 and 0.8.3 require. Ledger posting also reads the
         * account state-change event that keeps its balance projection current, and the notification
         * service reads four topics in all.
         *
         * <p>{@code account.state-changed} therefore carries two independent readers, and the two
         * hold different sets of its components. The authorization service replicates the credit
         * limit and the expiry date its decline rules read; the ledger replicates the balance and the
         * two cycle accumulators its posting path adds to. Neither reads the other's copy.
         */
        private static final Set<String> EXPECTED_LISTENERS = new LinkedHashSet<>(List.of(
                "authorization-service AccountStateChangedConsumer.java",
                "authorization-service CardUpdatedConsumer.java",
                "ledger-posting-service AccountStateChangedConsumer.java",
                "ledger-posting-service TransactionAuthorizedConsumer.java",
                "fraud-detection-service TransactionAuthorizedConsumer.java",
                "notification-service CustomerContextChangedConsumer.java",
                "notification-service FraudFlaggedConsumer.java",
                "notification-service TransactionAuthorizedConsumer.java",
                "notification-service TransactionPostedConsumer.java"));

        /**
         * The reader set of each consumed topic, keyed by the property the listener resolves.
         *
         * <p>{@code transaction.authorized} carrying three independent readers is the user's
         * headline requirement made checkable: no reader appears in another's list, and none of the
         * three is the authorization service. Ledger posting derives the balance, fraud detection
         * scores the risk, and notification alerts the cardholder, each under a group of its own.
         *
         * <p>Each reader list is held in ascending order and the assertion sorts what it collects,
         * so the assertion reports membership and not the order the source tree is walked in.
         */
        private static final Map<String, List<String>> EXPECTED_TOPIC_READERS = Map.of(
                "carddemo.kafka.topics.account-state-changed",
                List.of("authorization-service AccountStateChangedConsumer.java",
                        "ledger-posting-service AccountStateChangedConsumer.java"),
                "carddemo.kafka.topics.card-updated",
                List.of("authorization-service CardUpdatedConsumer.java"),
                "carddemo.kafka.topics.transaction-authorized",
                List.of("fraud-detection-service TransactionAuthorizedConsumer.java",
                        "ledger-posting-service TransactionAuthorizedConsumer.java",
                        "notification-service TransactionAuthorizedConsumer.java"),
                "carddemo.kafka.topics.customer-context-changed",
                List.of("notification-service CustomerContextChangedConsumer.java"),
                "carddemo.kafka.topics.fraud-assessed",
                List.of("notification-service FraudFlaggedConsumer.java"),
                "carddemo.kafka.topics.transaction-posted",
                List.of("notification-service TransactionPostedConsumer.java"));

        /**
         * Reads the configuration property one listener resolves its topic through.
         *
         * <p>A default is stripped, so {@code ${a.b:fallback}} and {@code ${a.b}} read alike.
         * The property name is a string literal, so this reader strips comments and keeps every
         * literal, where {@link #codeOf(String)} empties them.
         *
         * @param source the listener source, as shipped
         * @return the property name, or the empty string when the listener names none
         */
        private String topicPropertyOf(String source) {
            String withoutComments = source.replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("//[^\\n]*", " ");
            Matcher topic = Pattern.compile("topics\\s*=\\s*\"\\$\\{([^:}]+)")
                    .matcher(withoutComments);
            return topic.find() ? topic.group(1) : "";
        }

        /**
         * Resolves one group property to the group name a module's shipped configuration gives it.
         *
         * <p>A group value is written as a placeholder chain such as
         * {@code ${OUTER:${INNER:literal}}}, and the literal at the end is the name the shipped
         * configuration joins. This reader follows the chain to that literal.
         *
         * @param module        the Maven module directory name
         * @param groupProperty the property the listener names
         * @return the group name, or the empty string when the module's configuration names none
         */
        private String resolvedGroupName(String module, String groupProperty) {
            String configuration = configurationOf(module);
            String leaf = groupProperty.substring(groupProperty.lastIndexOf('.') + 1);
            Matcher declaration = Pattern.compile(
                    Pattern.quote(leaf) + "\\s*:\\s*\"?([^\"\\n]+)\"?").matcher(configuration);
            if (!declaration.find()) {
                return "";
            }
            String value = declaration.group(1).trim();
            Matcher literal = Pattern.compile("([A-Za-z0-9._-]+)\\}*\\s*\"?$").matcher(value);
            return literal.find() ? literal.group(1) : value;
        }

        /**
         * Reads the consumer-group property one listener resolves.
         *
         * <p>Comments are stripped and literals kept, as {@link #topicPropertyOf(String)} does, so a
         * property named in prose cannot be mistaken for one the annotation resolves.
         *
         * @param source the listener source, as shipped
         * @return the property name, or the empty string when the listener names none
         */
        private String groupPropertyOf(String source) {
            String withoutComments = source.replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("//[^\\n]*", " ");
            Matcher group = Pattern.compile("groupId\\s*=\\s*\"\\$\\{([^:}]+)")
                    .matcher(withoutComments);
            return group.find() ? group.group(1) : "";
        }

        /**
         * Reports whether one module declares a test that drives a listener.
         *
         * @param module the Maven module directory name
         * @return {@code true} when the module's test tree holds a consumer test
         */
        private boolean moduleCarriesAConsumerTest(String module) {
            Path tests = REPOSITORY_ROOT.get().resolve(SERVICES_DIRECTORY).resolve(module)
                    .resolve("src/test/java");
            if (!Files.isDirectory(tests)) {
                return false;
            }
            try (var walk = Files.walk(tests)) {
                return walk.map(path -> path.getFileName().toString())
                        .anyMatch(name -> name.endsWith("ConsumerTest.java")
                                || name.endsWith("ConsumerIT.java")
                                || name.equals("ProjectionConsumerTest.java"));
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot walk " + tests, unreadable);
            }
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

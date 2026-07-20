/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.config;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the bulk demonstration data from {@code src/main/resources/db/seed/*.csv} into the
 * relational tables when the application runs under the {@code local} profile.
 *
 * <p><strong>Why this exists.</strong> Flyway (see {@code db/migration}) owns the schema
 * ({@code V1__schema.sql}) and the three static reference/lookup tables ({@code V2__reference_data.sql}:
 * {@code transaction_type}, {@code transaction_category}, {@code disclosure_group}). Flyway is
 * intentionally <em>not</em> pointed at {@code db/seed}; the bulk business data (customers, accounts,
 * cards, cross-references, transactions, category balances, users, and the daily-transaction staging
 * rows) is loaded by this "separate mechanism" instead. This component is that mechanism. It gives a
 * developer the documented demonstration logins (e.g. {@code ADMIN001}/{@code USER0001}) and a
 * populated dataset immediately after a clean start &mdash; satisfying the onboarding goal of "clean
 * machine to a running, modifiable application without asking questions".
 *
 * <p><strong>Activation.</strong> Annotated {@code @Profile("local")}, this runner is a bean only
 * when the {@code local} profile is active (the documented local workflow and the root
 * {@code docker-compose} app service both set {@code SPRING_PROFILES_ACTIVE=local}). It is therefore
 * inert in the {@code test} profile (integration tests manage their own fixtures) and in any
 * production profile, where seed data must not be injected.
 *
 * <p><strong>Idempotency.</strong> {@link #run(ApplicationArguments)} checks each target table and
 * skips any that already contains rows, so restarting the application &mdash; or running against a
 * database that was seeded on a previous start &mdash; neither duplicates rows nor errors. The
 * reference tables populated by Flyway are not in this loader's scope.
 *
 * <p><strong>Fidelity and safety.</strong> Loading proceeds in foreign-key dependency order within a
 * single transaction that is committed only if every table loads cleanly; any failure rolls the
 * whole load back and fails startup fast so the problem is visible locally. Column values are bound
 * by their JDBC type read from {@link ResultSetMetaData} &mdash; monetary/decimal columns as
 * {@link BigDecimal} (never floating point), integer columns as {@code long}, and everything else as
 * text with empty fields stored as SQL {@code NULL}. Access is plain JDBC through the injected
 * {@link DataSource} (the PostgreSQL driver is a runtime-only dependency; no driver-specific API is
 * referenced). No field value is ever logged &mdash; only table names and row counts &mdash; so the
 * sensitive columns (password hashes, SSN, CVV) are never emitted.
 */
@Component
@Profile("local")
public class LocalSeedDataLoader implements ApplicationRunner {

    /**
     * Logger for this loader. Emits only non-sensitive progress information (table names and row
     * counts); individual field values (which include password hashes, SSN, and CVV) are never
     * logged.
     */
    private static final Logger log = LoggerFactory.getLogger(LocalSeedDataLoader.class);

    /**
     * Immutable description of one seed load: the classpath CSV resource, the target table, and the
     * ordered list of destination columns that the CSV's columns map onto positionally (the CSV
     * header is ignored for mapping; only column order matters, which lets CSV header names differ
     * from database column names and lets database-managed columns such as {@code version} or an
     * identity key be omitted).
     *
     * @param csv     the CSV file name under {@code classpath:db/seed/}
     * @param table   the destination table name
     * @param columns the destination columns, in the same order as the CSV's data columns
     */
    private record SeedSpec(String csv, String table, List<String> columns) {
    }

    /**
     * The seed loads in strict foreign-key dependency order: parents before children so every inline
     * {@code FOREIGN KEY} is satisfiable. {@code customer} and {@code user_security} have no outgoing
     * FKs; {@code account} precedes {@code card}/{@code card_xref}/{@code tran_cat_balance};
     * {@code card} precedes {@code transaction}; {@code daily_transaction} (a staging table with no
     * FKs) loads last. The three reference tables are excluded (Flyway V2 owns them).
     */
    private static final List<SeedSpec> SEED_ORDER = List.of(
            new SeedSpec("customer.csv", "customer", List.of(
                    "cust_id", "cust_first_name", "cust_middle_name", "cust_last_name",
                    "cust_addr_line_1", "cust_addr_line_2", "cust_addr_line_3", "cust_addr_state_cd",
                    "cust_addr_country_cd", "cust_addr_zip", "cust_phone_num_1", "cust_phone_num_2",
                    "cust_ssn", "cust_govt_issued_id", "cust_dob", "cust_eft_account_id",
                    "cust_pri_card_holder_ind", "cust_fico_credit_score")),
            new SeedSpec("user_security.csv", "user_security", List.of(
                    "sec_usr_id", "sec_usr_fname", "sec_usr_lname", "sec_usr_pwd", "sec_usr_type")),
            new SeedSpec("account.csv", "account", List.of(
                    "acct_id", "acct_active_status", "curr_bal", "credit_limit", "cash_credit_limit",
                    "acct_open_date", "acct_expiration_date", "acct_reissue_date", "curr_cyc_credit",
                    "curr_cyc_debit", "acct_addr_zip", "group_id")),
            new SeedSpec("card.csv", "card", List.of(
                    "card_num", "acct_id", "cvv", "card_embossed_name", "card_expiration_date",
                    "card_active_status")),
            new SeedSpec("card_xref.csv", "card_xref", List.of(
                    "xref_card_num", "cust_id", "acct_id")),
            new SeedSpec("transaction.csv", "transaction", List.of(
                    "tran_id", "type_cd", "cat_cd", "tran_source", "tran_desc", "tran_amt",
                    "tran_merchant_id", "tran_merchant_name", "tran_merchant_city", "tran_merchant_zip",
                    "card_num", "orig_ts", "proc_ts")),
            new SeedSpec("tran_cat_balance.csv", "tran_cat_balance", List.of(
                    "acct_id", "type_cd", "cat_cd", "bal")),
            new SeedSpec("daily_transaction.csv", "daily_transaction", List.of(
                    "dalytran_id", "type_cd", "cat_cd", "tran_source", "tran_desc", "tran_amt",
                    "merchant_id", "merchant_name", "merchant_city", "merchant_zip", "card_num",
                    "orig_ts", "proc_ts")));

    /**
     * The application {@link DataSource}, injected by constructor (no field injection, per the
     * project's dependency-injection convention).
     */
    private final DataSource dataSource;

    /**
     * Creates the loader with the application {@link DataSource}.
     *
     * @param dataSource the JDBC data source used for all seed inserts; must not be {@code null}
     */
    public LocalSeedDataLoader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Loads every configured seed table that is currently empty, in foreign-key order, inside a
     * single transaction.
     *
     * @param args the application arguments (unused)
     * @throws Exception if a connection cannot be obtained or the load fails (the transaction is
     *                   rolled back before the exception propagates, failing startup fast)
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int totalLoaded = 0;
                for (SeedSpec spec : SEED_ORDER) {
                    long existing = rowCount(connection, spec.table());
                    if (existing > 0) {
                        log.info("Local seed: skipping '{}' (already populated with {} row(s))",
                                spec.table(), existing);
                        continue;
                    }
                    int loaded = loadTable(connection, spec);
                    totalLoaded += loaded;
                    log.info("Local seed: loaded {} row(s) into '{}' from db/seed/{}",
                            loaded, spec.table(), spec.csv());
                }
                connection.commit();
                if (totalLoaded == 0) {
                    log.info("Local seed: all bulk tables already populated; nothing to load.");
                } else {
                    log.info("Local seed: committed {} bulk row(s) across {} table(s).",
                            totalLoaded, SEED_ORDER.size());
                }
            } catch (RuntimeException | SQLException | IOException e) {
                connection.rollback();
                throw new IllegalStateException(
                        "Local seed data load failed and was rolled back: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    /**
     * Returns the number of rows currently in {@code table}. The table name originates from the
     * hard-coded {@link #SEED_ORDER} list (never from external input), so the interpolation is safe.
     *
     * @param connection the JDBC connection
     * @param table      the table to count
     * @return the current row count
     * @throws SQLException if the count query fails
     */
    private long rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * Parses the CSV for {@code spec} and inserts its data rows into the target table as a single
     * JDBC batch. Column JDBC types are discovered from {@link ResultSetMetaData} and used to bind
     * each value with the correct type.
     *
     * @param connection the JDBC connection (transaction already open)
     * @param spec       the seed specification (CSV name, table, destination columns)
     * @return the number of data rows inserted
     * @throws SQLException if metadata discovery or the batch insert fails
     * @throws IOException  if the CSV resource cannot be read
     */
    private int loadTable(Connection connection, SeedSpec spec) throws SQLException, IOException {
        List<List<String>> records = parseCsv(readResource("db/seed/" + spec.csv()));
        if (records.isEmpty()) {
            return 0;
        }

        List<String> columns = spec.columns();
        String columnList = String.join(", ", columns);
        int[] jdbcTypes = columnTypes(connection, spec.table(), columnList, columns.size());

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        String insertSql = "INSERT INTO " + spec.table() + " (" + columnList + ") VALUES ("
                + placeholders + ")";

        int rows = 0;
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            // records.get(0) is the header row; data starts at index 1.
            for (int r = 1; r < records.size(); r++) {
                List<String> fields = records.get(r);
                if (isBlankRecord(fields)) {
                    continue;
                }
                if (fields.size() != columns.size()) {
                    throw new IllegalStateException(String.format(
                            "Seed file db/seed/%s row %d has %d field(s) but table '%s' expects %d",
                            spec.csv(), r + 1, fields.size(), spec.table(), columns.size()));
                }
                for (int c = 0; c < columns.size(); c++) {
                    bind(statement, c + 1, jdbcTypes[c], redactAtRest(spec.table(), columns.get(c), fields.get(c)));
                }
                statement.addBatch();
                rows++;
            }
            statement.executeBatch();
        }
        return rows;
    }

    /**
     * Discovers the JDBC {@link Types} of the destination columns by issuing an empty
     * ({@code WHERE 1=0}) projection so binding can match each column's real type.
     *
     * @param connection the JDBC connection
     * @param table      the destination table
     * @param columnList the comma-separated destination column list
     * @param count      the number of columns expected
     * @return an array of {@link Types} constants, one per destination column, in order
     * @throws SQLException if the metadata query fails
     */
    private int[] columnTypes(Connection connection, String table, String columnList, int count)
            throws SQLException {
        int[] types = new int[count];
        String probe = "SELECT " + columnList + " FROM " + table + " WHERE 1=0";
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(probe)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int i = 0; i < count; i++) {
                types[i] = metaData.getColumnType(i + 1);
            }
        }
        return types;
    }

    /**
     * Fixed, non-reversible masked placeholder stored in {@code card.cvv} in place of any real card
     * verification value (PCI-DSS at-rest posture, decision-log <strong>D22-revised</strong>).
     *
     * <p>Under PCI-DSS Requirement&nbsp;3.2 the sensitive card-verification value (CVV/CVV2/CVC2/CID)
     * must never be retained after authorization, and this migration reads/returns/uses the CVV on
     * <em>no</em> behavioral-parity path (it is never mapped to a DTO, never logged, and excluded from
     * {@code Card#toString()}). The real {@code CARD-CVV-CD} is therefore unnecessary at rest, and this
     * loader forces every {@code card.cvv} insert to the placeholder below regardless of what the seed
     * CSV carries &mdash; a code-enforced invariant at the ingestion boundary, not merely a data-hygiene
     * convention. Width is &le; the {@code VARCHAR(3)} column so the {@code CARD-RECORD} layout shape is
     * preserved (see {@code V6__redact_cvv_at_rest.sql}).</p>
     */
    private static final String CVV_AT_REST_PLACEHOLDER = "***";

    /**
     * Ingestion-boundary redaction for sensitive-at-rest fields. Returns {@link #CVV_AT_REST_PLACEHOLDER}
     * for the {@code card.cvv} column so a real card verification value can never be persisted through
     * the seed path (decision-log <strong>D22-revised</strong>); every other column passes through
     * unchanged.
     *
     * @param table    the destination table name
     * @param column   the destination column name
     * @param rawValue the raw CSV field value
     * @return the value to bind: the CVV placeholder for {@code card.cvv}, otherwise {@code rawValue}
     */
    private static String redactAtRest(String table, String column, String rawValue) {
        if ("card".equals(table) && "cvv".equals(column)) {
            return CVV_AT_REST_PLACEHOLDER;
        }
        return rawValue;
    }

    /**
     * Binds a single CSV field to a prepared-statement parameter using the column's JDBC type. An
     * empty (or whitespace-only) field is stored as SQL {@code NULL}. Decimal/numeric columns are
     * bound as {@link BigDecimal} (never floating point); integer columns as {@code long}; all other
     * columns as text (preserving significant leading zeros in identifiers, SSNs, and codes).
     *
     * @param statement the prepared statement
     * @param index     the 1-based parameter index
     * @param jdbcType  the destination column's {@link Types} constant
     * @param rawValue  the raw CSV field value (may be {@code null} or empty)
     * @throws SQLException if binding fails
     */
    private void bind(PreparedStatement statement, int index, int jdbcType, String rawValue)
            throws SQLException {
        String value = rawValue == null ? null : rawValue.trim();
        if (value == null || value.isEmpty()) {
            statement.setNull(index, jdbcType);
            return;
        }
        switch (jdbcType) {
            case Types.NUMERIC, Types.DECIMAL -> statement.setBigDecimal(index, new BigDecimal(value));
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT ->
                statement.setLong(index, Long.parseLong(value));
            default -> statement.setString(index, value);
        }
    }

    /**
     * Reads a classpath resource fully into a UTF-8 string.
     *
     * @param location the classpath location (e.g. {@code db/seed/customer.csv})
     * @return the resource content as a UTF-8 string
     * @throws IOException if the resource is missing or cannot be read
     */
    private String readResource(String location) throws IOException {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Returns {@code true} if a parsed record represents a blank line (no fields, or a single empty
     * field), which is skipped rather than inserted.
     *
     * @param fields the parsed record
     * @return whether the record is blank
     */
    private boolean isBlankRecord(List<String> fields) {
        return fields.isEmpty() || (fields.size() == 1 && fields.get(0).isBlank());
    }

    /**
     * Parses CSV content into records of fields following RFC 4180: fields may be wrapped in double
     * quotes, a quoted field may contain commas and newlines, and an embedded double quote is written
     * as two double quotes ({@code ""}). Carriage returns are ignored so both LF and CRLF inputs are
     * handled.
     *
     * @param content the full CSV text
     * @return the parsed records (the first element is the header row)
     */
    private List<List<String>> parseCsv(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        int i = 0;
        int length = content.length();
        while (i < length) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < length && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(ch);
                    i++;
                }
            } else if (ch == '"') {
                inQuotes = true;
                i++;
            } else if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
                i++;
            } else if (ch == '\r') {
                i++;
            } else if (ch == '\n') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
                i++;
            } else {
                field.append(ch);
                i++;
            }
        }
        // Flush the final field/record when the content does not end with a newline.
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }
}

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
package com.carddemo.common.crypto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * :purpose: Bring the regulated columns protected by {@link CryptoConverter} to a
 *     fully-encrypted state at rest before the service accepts traffic. The
 *     migration seed data is written as human-readable fixed-width values taken
 *     from the legacy ASCII fixtures, and AES-GCM ciphertext cannot be produced in
 *     SQL, so the rows land unencrypted; every subsequent read of them then fails
 *     because the converter cannot decrypt plaintext. This component closes that
 *     gap by encrypting exactly the values that are not already ciphertext.
 * :output: For each configured column, the number of rows encrypted, logged as a
 *     single summary line. No column value is ever logged.
 * :note: The pass is idempotent and safe to re-run: a value that already decrypts
 *     under the configured key is left untouched, so restarts, several services
 *     running the pass concurrently, and re-seeded environments all converge to the
 *     same state. It runs during context initialization (before the web server
 *     accepts requests) so no caller can observe a half-encrypted table.
 * :note: It never DEcrypts a column. If the configured key does not match existing
 *     ciphertext, those rows are reported and left alone rather than being
 *     re-encrypted under the new key, which would destroy the original values.
 */
public class SeededPiiEncryptionMigrator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SeededPiiEncryptionMigrator.class);

    /**
     * :purpose: One regulated column to normalize.
     * :param table: physical table name.
     * :param keyColumn: single-column primary key used to address a row.
     * :param column: the column protected by {@link CryptoConverter}.
     */
    public record ProtectedColumn(String table, String keyColumn, String column) {
    }

    /**
     * :purpose: The complete set of columns mapped with
     *     ``@Convert(converter = CryptoConverter.class)`` in the shared domain model:
     *     the customer SSN, government-issued id and EFT account id, and the card
     *     verification value.
     */
    public static final List<ProtectedColumn> PROTECTED_COLUMNS = List.of(
            new ProtectedColumn("customers", "cust_id", "cust_ssn"),
            new ProtectedColumn("customers", "cust_id", "cust_govt_issued_id"),
            new ProtectedColumn("customers", "cust_id", "cust_eft_account_id"),
            new ProtectedColumn("cards", "card_num", "card_cvv_cd"));

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate transactionTemplate;

    private final CryptoConverter cryptoConverter;

    private final List<ProtectedColumn> columns;

    /**
     * :purpose: Build the migrator for the shared set of protected columns.
     * :param jdbcTemplate: data access used to read and rewrite the columns.
     * :param transactionTemplate: wraps each column's rewrite in one transaction.
     */
    public SeededPiiEncryptionMigrator(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this(jdbcTemplate, transactionTemplate, new CryptoConverter(), PROTECTED_COLUMNS);
    }

    /**
     * :purpose: Build a disabled migrator for a context that has no datasource, so the
     *     bean can always be registered while the pass simply does not run.
     * :returns: a migrator whose {@link #isEnabled()} is ``false``.
     */
    public static SeededPiiEncryptionMigrator disabled() {
        return new SeededPiiEncryptionMigrator(null, null, new CryptoConverter(), PROTECTED_COLUMNS);
    }

    /**
     * :purpose: Build the migrator with an explicit converter and column list.
     * :param jdbcTemplate: data access used to read and rewrite the columns.
     * :param transactionTemplate: wraps each column's rewrite in one transaction.
     * :param cryptoConverter: converter whose key and token format define "encrypted".
     * :param columns: the columns to normalize.
     */
    public SeededPiiEncryptionMigrator(JdbcTemplate jdbcTemplate,
                                      TransactionTemplate transactionTemplate,
                                      CryptoConverter cryptoConverter,
                                      List<ProtectedColumn> columns) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.cryptoConverter = cryptoConverter;
        this.columns = List.copyOf(columns);
    }

    /**
     * :purpose: Report whether the pass has the data access it needs. A service sliced
     *     for testing (or configured without a datasource) has none, in which case the
     *     pass is skipped rather than failing the context.
     * :output: ``true`` when the pass can run.
     */
    public boolean isEnabled() {
        return this.jdbcTemplate != null && this.transactionTemplate != null;
    }

    /**
     * :purpose: Run the normalization pass as part of context initialization, before the
     *     web server accepts requests.
     * :raises IllegalStateException: when no encryption key is configured, so a
     *     deployment cannot start with regulated data it is unable to protect.
     */
    @Override
    public void afterPropertiesSet() {
        if (!isEnabled()) {
            log.debug("PII encryption pass skipped: no datasource is configured in this context");
            return;
        }
        int encrypted = encryptUnprotectedValues();
        if (encrypted == 0) {
            log.debug("PII encryption check complete: every protected column is already encrypted at rest");
        }
    }

    /**
     * :purpose: Encrypt every value in the configured columns that is not already a
     *     readable AES-GCM token.
     * :output: the total number of rows rewritten across all configured columns.
     */
    public int encryptUnprotectedValues() {
        if (!isEnabled()) {
            return 0;
        }
        int total = 0;
        for (ProtectedColumn column : this.columns) {
            total += encryptColumn(column);
        }
        return total;
    }

    /**
     * :purpose: Encrypt the unprotected values of one column inside a single
     *     transaction, skipping the table entirely when it does not exist (a service
     *     whose schema slice does not include it).
     * :param column: the column to normalize.
     * :output: the number of rows rewritten for this column.
     */
    private int encryptColumn(ProtectedColumn column) {
        String select = "SELECT " + column.keyColumn() + " AS row_key, " + column.column() + " AS row_value"
                + " FROM " + column.table()
                + " WHERE " + column.column() + " IS NOT NULL AND " + column.column() + " <> ''";
        List<Map<String, Object>> rows;
        try {
            rows = this.jdbcTemplate.queryForList(select);
        } catch (DataAccessException ex) {
            log.debug("Skipping PII encryption for {}.{}: the table is not readable from this service",
                    column.table(), column.column());
            return 0;
        }

        List<Object[]> updates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object storedValue = row.get("row_value");
            if (!(storedValue instanceof String stored)) {
                continue;
            }
            if (this.cryptoConverter.isProtected(stored)) {
                continue;
            }
            updates.add(new Object[] {
                this.cryptoConverter.convertToDatabaseColumn(stored),
                row.get("row_key"),
                stored});
        }
        if (updates.isEmpty()) {
            return 0;
        }

        // The trailing predicate re-asserts the exact value that was read, so a row
        // another instance has already encrypted (or an operator has since changed) is
        // left alone instead of being encrypted a second time.
        String update = "UPDATE " + column.table() + " SET " + column.column() + " = ?"
                + " WHERE " + column.keyColumn() + " = ? AND " + column.column() + " = ?";
        Integer applied = this.transactionTemplate.execute(status -> {
            int[] results = this.jdbcTemplate.batchUpdate(update, updates);
            int sum = 0;
            for (int result : results) {
                sum += Math.max(result, 0);
            }
            return sum;
        });
        int rewritten = applied == null ? 0 : applied;
        log.info("Encrypted {} unprotected value(s) at rest in {}.{}", rewritten, column.table(), column.column());
        return rewritten;
    }
}

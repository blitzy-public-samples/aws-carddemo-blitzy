/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.migration;

import com.carddemo.common.crypto.CryptoConverter;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * :purpose: Flyway migration (version 4, immediately after the ``V3__seed_test_data.sql``
 *     fixtures) that converts the seeded personally-identifiable columns from their
 *     decoded fixture form into the AES-256-GCM ciphertext the runtime JPA converter
 *     expects, so seeded records are simultaneously encrypted at rest and readable
 *     through the application.
 * :output: ``customers.cust_ssn``, ``customers.cust_govt_issued_id``,
 *     ``customers.cust_eft_account_id`` and ``cards.card_cvv_cd`` rewritten as
 *     ``Base64(IV || ciphertext || GCM tag)`` tokens produced by
 *     {@link CryptoConverter}, using the deployment key resolved from the
 *     ``CARDDEMO_PII_KEY`` environment variable (or the ``carddemo.pii.key`` system
 *     property).
 * :note: A static SQL literal cannot carry this ciphertext: the key is supplied by the
 *     environment and every token embeds a freshly generated random initialization
 *     vector, so the value is only computable at migration time. Encrypting here keeps
 *     the authored seed SQL reviewable against the ``app/data/ASCII`` fixtures while
 *     still satisfying the at-rest encryption requirement. The consequence — the seeded
 *     rows become key-dependent, and a database restored under a different key can no
 *     longer decrypt them — is recorded in ``docs/decision-log.md``.
 * :note: Idempotent by construction: each value is first offered to the decrypter, and a
 *     value that already decrypts is left untouched. The migration therefore also
 *     repairs a database whose seed was applied before this step existed, and is safe if
 *     it is ever replayed against partially converted data. It runs inside Flyway's
 *     migration transaction, so a failure leaves no half-converted rows.
 * :note: Implements {@link JavaMigration} directly rather than extending
 *     ``BaseJavaMigration``, whose constructor derives the version from the class name and
 *     therefore requires a ``V<version>__<description>`` class name. The version and
 *     description are declared explicitly below, which keeps the class name descriptive.
 */
public class SeededPiiEncryptionMigration implements JavaMigration {

    /** :purpose: Logger for the per-table conversion tallies. */
    private static final Logger log = LoggerFactory.getLogger(SeededPiiEncryptionMigration.class);

    /** :purpose: Version this migration occupies in the consolidated set (after V3, before V5). */
    private static final String VERSION = "4";

    /** :purpose: Description recorded in the Flyway schema-history table. */
    private static final String DESCRIPTION = "encrypt seeded pii";

    /** :purpose: Customer identifier column, the update predicate for the customer rewrite. */
    private static final String CUSTOMER_KEY = "cust_id";

    /** :purpose: Encrypted customer columns, in the order they are read and rewritten. */
    private static final List<String> CUSTOMER_PII_COLUMNS =
            List.of("cust_ssn", "cust_govt_issued_id", "cust_eft_account_id");

    /** :purpose: Card primary-account-number column, the update predicate for the card rewrite. */
    private static final String CARD_KEY = "card_num";

    /** :purpose: Encrypted card column (the card verification value). */
    private static final List<String> CARD_PII_COLUMNS = List.of("card_cvv_cd");

    /** :purpose: Converter supplying the exact runtime encryption scheme and key resolution. */
    private final CryptoConverter cryptoConverter = new CryptoConverter();

    /**
     * :purpose: Report the version this migration is applied under.
     * :returns: migration version ``4``.
     */
    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion(VERSION);
    }

    /**
     * :purpose: Report the checksum recorded in the schema-history table.
     * :returns: ``null`` - the migration carries no script whose content could change, so
     *     Flyway records no checksum and never reports a validation mismatch for it.
     */
    @Override
    public Integer getChecksum() {
        return null;
    }

    /**
     * :purpose: Declare that this migration participates in Flyway's migration transaction, so a
     *     failure part-way through leaves no half-converted rows.
     * :returns: ``true``.
     */
    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    /**
     * :purpose: Report the description recorded in the schema-history table.
     * :returns: the human-readable migration description.
     */
    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * :purpose: Rewrite every seeded PII column that still holds a non-encrypted value.
     * :param context: the Flyway migration context supplying the JDBC connection that is
     *     enlisted in the migration transaction.
     * :raises SQLException: if reading or rewriting a row fails, aborting the migration so
     *     Flyway rolls the transaction back rather than leaving mixed-state data.
     */
    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        int customers = encryptTable(connection, "customers", CUSTOMER_KEY, CUSTOMER_PII_COLUMNS);
        int cards = encryptTable(connection, "cards", CARD_KEY, CARD_PII_COLUMNS);
        log.info("Encrypted seeded PII columns: customers rewritten={}, cards rewritten={}", customers, cards);
    }

    /**
     * :purpose: Encrypt the given columns of every row of one table whose values are not
     *     already ciphertext.
     * :param connection: the migration connection; never closed or committed here.
     * :param table: the table whose PII columns are converted.
     * :param keyColumn: the single-column identifier used as the update predicate.
     * :param piiColumns: the columns to convert, in a stable order.
     * :returns: the number of rows actually rewritten.
     * :raises SQLException: if the read or the batched update fails.
     */
    private int encryptTable(Connection connection, String table, String keyColumn, List<String> piiColumns)
            throws SQLException {
        List<Object[]> updates = new ArrayList<>();
        String select = "SELECT " + keyColumn + ", " + String.join(", ", piiColumns) + " FROM " + table;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(select)) {
            while (rows.next()) {
                Object key = rows.getObject(1);
                Object[] parameters = new Object[piiColumns.size() + 1];
                boolean changed = false;
                for (int i = 0; i < piiColumns.size(); i++) {
                    String stored = rows.getString(i + 2);
                    String encrypted = toCiphertext(stored);
                    parameters[i] = encrypted;
                    changed = changed || !equalValues(stored, encrypted);
                }
                if (changed) {
                    parameters[piiColumns.size()] = key;
                    updates.add(parameters);
                }
            }
        }
        if (updates.isEmpty()) {
            return 0;
        }
        StringBuilder update = new StringBuilder("UPDATE ").append(table).append(" SET ");
        for (int i = 0; i < piiColumns.size(); i++) {
            update.append(i == 0 ? "" : ", ").append(piiColumns.get(i)).append(" = ?");
        }
        update.append(" WHERE ").append(keyColumn).append(" = ?");
        try (PreparedStatement statement = connection.prepareStatement(update.toString())) {
            for (Object[] parameters : updates) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return updates.size();
    }

    /**
     * :purpose: Return the ciphertext form of a stored value, leaving values that are already
     *     protected at rest (and ``null`` / empty values, which the converter passes through)
     *     untouched.
     * :param stored: the value currently held in the column.
     * :returns: the value to store: the original when it is already an encrypted token or has
     *     nothing to encrypt, otherwise a freshly encrypted token.
     * :note: Protection is decided by {@link CryptoConverter#isProtected(String)}, which
     *     recognises the ``gcm1:`` envelope and an unmarked token this key still authenticates.
     *     Asking the decrypter to convert the value instead would NOT work here:
     *     ``convertToEntityAttribute`` deliberately passes an unrecognised value through unchanged
     *     for pre-encryption compatibility, so a plaintext fixture value would be misread as
     *     already encrypted and the rewrite would be skipped. That was the defect: the earlier
     *     test — "``convertToEntityAttribute`` did not throw" — could never answer the question,
     *     so every value looked like ciphertext and this sweep rewrote nothing while still
     *     recording SUCCESS, reporting ``rewritten=0`` on a database full of plaintext. (No data
     *     was ever exposed, because the separate runtime initializer encrypts the columns at
     *     service startup; the defect was that this migration's report was untrue and its
     *     idempotence accidental rather than designed.)
     */
    private String toCiphertext(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (cryptoConverter.isProtected(stored)) {
            return stored;
        }
        return cryptoConverter.convertToDatabaseColumn(stored);
    }

    /**
     * :purpose: Null-safe value comparison used to detect that a column needs rewriting.
     * :param left: the value read from the database.
     * :param right: the value that would be written.
     * :returns: ``true`` when both values are the same reference or equal strings.
     */
    private boolean equalValues(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}

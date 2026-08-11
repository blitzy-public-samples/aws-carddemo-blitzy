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
package com.carddemo.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * :purpose: Bring the sensitive columns of already-loaded seed data up to the at-rest
 *     encryption contract that {@link CryptoConverter} enforces (AAP 0.6.7 — "Sensitive fields
 *     (SSN, government id, CVV) are masked and encrypted"). The Flyway seed scripts derive
 *     their values from the legacy ASCII fixtures under ``app/data/ASCII`` and insert them
 *     with raw SQL, which bypasses the JPA attribute converter; a ciphertext literal cannot be
 *     committed to a migration because the AES key is environment-specific. This runner closes
 *     that gap by encrypting any value that is still plaintext, using the key configured for
 *     the environment it starts in.
 * :output: For every configured column, rows whose stored value is not already AES-GCM
 *     ciphertext are rewritten in place as ``Base64(IV || ciphertext)``. Rows that already
 *     decrypt are left untouched, so the runner is idempotent and safe to run on every start
 *     and in every replica: it never double-encrypts, because a value is only rewritten when
 *     it fails to decrypt, and two replicas racing on the same plaintext each write valid
 *     ciphertext of that same plaintext.
 * :note: Deliberately implemented with plain SQL through {@link JdbcTemplate} rather than
 *     JPA, so the read observes the RAW column value instead of the converted attribute —
 *     reading through the entity is exactly what fails when the column holds plaintext.
 */
public class PiiAtRestInitializer implements ApplicationRunner {

    /** :purpose: Logger for the at-rest encryption sweep; never logs a column value. */
    private static final Logger log = LoggerFactory.getLogger(PiiAtRestInitializer.class);

    /** :purpose: JDBC access used to read the raw column values and rewrite them. */
    private final JdbcTemplate jdbcTemplate;

    /** :purpose: The converter whose encryption format the columns must satisfy. */
    private final CryptoConverter cryptoConverter;

    /** :purpose: The sensitive columns this service owns, in sweep order. */
    private final List<PiiColumn> columns;

    /**
     * :purpose: Construct the sweep for a specific set of owned columns.
     * :param jdbcTemplate: JDBC access to the CardDemo schema.
     * :param cryptoConverter: the shared AES-GCM attribute converter.
     * :param columns: the table/key/column triples this service owns; each owning
     *     service passes only its own tables so no two services rewrite the same rows
     *     as a matter of routine.
     */
    public PiiAtRestInitializer(JdbcTemplate jdbcTemplate,
                                CryptoConverter cryptoConverter,
                                List<PiiColumn> columns) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoConverter = cryptoConverter;
        this.columns = List.copyOf(columns);
    }

    /**
     * :purpose: Run the encryption sweep once, after the application context is ready.
     * :param args: the application arguments; not used.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!encryptionKeyConfigured()) {
            // Without a key nothing can be encrypted OR decrypted, so a sweep would only
            // fail. Startup continues: the converter itself already fails closed on the
            // first read of a sensitive column, which is the correct place to surface a
            // misconfigured deployment.
            log.warn("Skipping at-rest encryption sweep: no PII encryption key is configured");
            return;
        }
        for (PiiColumn column : columns) {
            encryptColumn(column);
        }
    }

    /**
     * :purpose: Report whether an AES key is available to this JVM, by asking the
     *     converter to round-trip a probe value.
     * :returns: ``true`` when the configured key encrypts and decrypts successfully.
     */
    private boolean encryptionKeyConfigured() {
        try {
            String probe = "PII-KEY-PROBE";
            return probe.equals(cryptoConverter.convertToEntityAttribute(
                    cryptoConverter.convertToDatabaseColumn(probe)));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * :purpose: Rewrite every plaintext value of one column as ciphertext.
     * :param column: the table/key/column triple to sweep.
     */
    private void encryptColumn(PiiColumn column) {
        String select = String.format("SELECT %s AS k, %s AS v FROM %s WHERE %s IS NOT NULL AND %s <> ''",
                column.keyColumn(), column.valueColumn(), column.table(),
                column.valueColumn(), column.valueColumn());
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(select);
        } catch (RuntimeException ex) {
            // A missing table is not an error here: the owning service may start before
            // the shared schema is fully provisioned, and the next start will sweep it.
            log.warn("Skipping at-rest encryption sweep of {}.{}: {}",
                    column.table(), column.valueColumn(), ex.getMessage());
            return;
        }

        String update = String.format("UPDATE %s SET %s = ? WHERE %s = ?",
                column.table(), column.valueColumn(), column.keyColumn());
        int rewritten = 0;
        for (Map<String, Object> row : rows) {
            Object key = row.get("k");
            Object value = row.get("v");
            if (key == null || !(value instanceof String stored)) {
                continue;
            }
            if (isAlreadyEncrypted(stored)) {
                continue;
            }
            jdbcTemplate.update(update, cryptoConverter.convertToDatabaseColumn(stored), key);
            rewritten++;
        }
        if (rewritten > 0) {
            log.info("Encrypted {} plaintext value(s) at rest in {}.{}",
                    rewritten, column.table(), column.valueColumn());
        }
    }

    /**
     * :purpose: Decide whether a stored value already satisfies the converter's
     *     ``Base64(IV || AES-GCM ciphertext)`` format, by attempting to decrypt it.
     *     A successful decryption is the only reliable evidence, and it is what makes
     *     the sweep idempotent and non-destructive.
     * :param stored: the raw column value read straight from the database.
     * :returns: ``true`` when the value decrypts, ``false`` when it is plaintext.
     */
    private boolean isAlreadyEncrypted(String stored) {
        try {
            cryptoConverter.convertToEntityAttribute(stored);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * :purpose: Identify one encrypted column and the primary-key column used to
     *     address its rows.
     * :param table: the table holding the sensitive column.
     * :param keyColumn: the single-column primary key used in the UPDATE predicate.
     * :param valueColumn: the sensitive column to encrypt at rest.
     */
    public record PiiColumn(String table, String keyColumn, String valueColumn) {
    }
}

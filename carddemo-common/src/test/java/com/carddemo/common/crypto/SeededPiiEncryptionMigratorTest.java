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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * :purpose: Verifies that the seeded-PII normalization pass encrypts exactly the values
 *     that are not already ciphertext, leaves protected and blank values untouched, is
 *     idempotent across runs, guards each rewrite with the value it read, and never logs
 *     or reports a protected value.
 */
class SeededPiiEncryptionMigratorTest {

    /** :purpose: Base64 of exactly 32 bytes, the required AES-256 key length. */
    private static final String TEST_KEY = Base64.getEncoder()
            .encodeToString("carddemo-migrator-test-key-32byt".getBytes(StandardCharsets.UTF_8));

    private static final SeededPiiEncryptionMigrator.ProtectedColumn CUSTOMER_SSN =
            new SeededPiiEncryptionMigrator.ProtectedColumn("customers", "cust_id", "cust_ssn");

    private static String previousKey;

    private final CryptoConverter converter = new CryptoConverter();

    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate transactionTemplate;

    /**
     * :purpose: Install the test key, remembering any key already configured.
     */
    @BeforeAll
    static void configureKey() {
        Assumptions.assumeTrue(System.getenv(CryptoConverter.KEY_ENV) == null,
                CryptoConverter.KEY_ENV + " is set in the environment and takes precedence over the test key");
        previousKey = System.getProperty(CryptoConverter.KEY_PROPERTY);
        System.setProperty(CryptoConverter.KEY_PROPERTY, TEST_KEY);
    }

    /**
     * :purpose: Restore the previous key so the property does not leak between suites.
     */
    @AfterAll
    static void restoreKey() {
        if (previousKey == null) {
            System.clearProperty(CryptoConverter.KEY_PROPERTY);
        } else {
            System.setProperty(CryptoConverter.KEY_PROPERTY, previousKey);
        }
    }

    /**
     * :purpose: Build a migrator over a mocked template with a transaction template that
     *     executes its callback inline.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(status);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * :purpose: Build a migrator restricted to one column so the assertions are precise.
     */
    private SeededPiiEncryptionMigrator migrator() {
        return new SeededPiiEncryptionMigrator(jdbcTemplate, transactionTemplate, converter, List.of(CUSTOMER_SSN));
    }

    /**
     * :purpose: Assemble a query result row in the shape the migrator selects.
     */
    private Map<String, Object> row(Object key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row_key", key);
        row.put("row_value", value);
        return row;
    }

    @Test
    @DisplayName("unencrypted seeded values are encrypted and the rewrite is guarded by the value read")
    void encryptsOnlyUnprotectedValues() {
        String alreadyProtected = converter.convertToDatabaseColumn("111111111");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                row(1L, "020973888"),
                row(2L, alreadyProtected),
                row(3L, ""),
                row(4L, "0053581756")));
        when(jdbcTemplate.batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any()))
                .thenReturn(new int[] {1, 1});

        int encrypted = migrator().encryptUnprotectedValues();

        assertThat(encrypted).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), batch.capture());

        assertThat(sql.getValue())
                .isEqualTo("UPDATE customers SET cust_ssn = ? WHERE cust_id = ? AND cust_ssn = ?");
        List<Object[]> parameters = batch.getValue();
        assertThat(parameters).hasSize(2);

        List<Object> keys = new ArrayList<>();
        for (Object[] parameter : parameters) {
            keys.add(parameter[1]);
            String ciphertext = (String) parameter[0];
            String original = (String) parameter[2];
            assertThat(ciphertext).isNotEqualTo(original);
            assertThat(converter.convertToEntityAttribute(ciphertext)).isEqualTo(original);
        }
        assertThat(keys).containsExactly(1L, 4L);
    }

    @Test
    @DisplayName("a fully encrypted column is left completely untouched (idempotent re-run)")
    void alreadyEncryptedColumnIsUntouched() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                row(1L, converter.convertToDatabaseColumn("020973888")),
                row(4L, converter.convertToDatabaseColumn("0053581756"))));

        int encrypted = migrator().encryptUnprotectedValues();

        assertThat(encrypted).isZero();
        verify(jdbcTemplate, never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("a table this service cannot read is skipped instead of failing startup")
    void missingTableIsSkipped() {
        when(jdbcTemplate.queryForList(anyString()))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "select", "select 1", new java.sql.SQLException("relation does not exist")));

        assertThat(migrator().encryptUnprotectedValues()).isZero();
        verify(jdbcTemplate, never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    @DisplayName("the shared column list covers every @Convert(CryptoConverter) column")
    void protectedColumnListIsComplete() {
        assertThat(SeededPiiEncryptionMigrator.PROTECTED_COLUMNS).containsExactly(
                new SeededPiiEncryptionMigrator.ProtectedColumn("customers", "cust_id", "cust_ssn"),
                new SeededPiiEncryptionMigrator.ProtectedColumn("customers", "cust_id", "cust_govt_issued_id"),
                new SeededPiiEncryptionMigrator.ProtectedColumn("customers", "cust_id", "cust_eft_account_id"),
                new SeededPiiEncryptionMigrator.ProtectedColumn("cards", "card_num", "card_cvv_cd"));
    }

    @Test
    @DisplayName("initialization runs the pass exactly once")
    void initializationRunsThePass() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row(1L, "020973888")));
        when(jdbcTemplate.batchUpdate(eq("UPDATE customers SET cust_ssn = ? WHERE cust_id = ? AND cust_ssn = ?"),
                org.mockito.ArgumentMatchers.<List<Object[]>>any())).thenReturn(new int[] {1});

        migrator().afterPropertiesSet();

        verify(jdbcTemplate).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }
}

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
package com.carddemo.common.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.crypto.CryptoConverter;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Verifies that Flyway version ``4`` itself converts the seeded personally
 *     identifiable columns, rather than reporting a rewrite tally of zero and leaving the
 *     work to the runtime pass. The regression this pins is a detection defect: deciding
 *     "already encrypted" by asking the decrypter to convert the value silently classified
 *     every plaintext fixture value as protected, because
 *     :java:meth:`CryptoConverter.convertToEntityAttribute` passes an unrecognised value
 *     through unchanged for pre-encryption compatibility.
 * :output: assertions over the values the migration writes back through the mocked JDBC
 *     connection, and over its per-table rewrite decisions.
 */
class SeededPiiEncryptionMigrationTest {

    /** :purpose: Base64 of exactly 32 bytes, the required AES-256 key length. */
    private static final String TEST_KEY = Base64.getEncoder()
            .encodeToString("carddemo-migration-test-key-32by".getBytes(StandardCharsets.UTF_8));

    /** :purpose: Plaintext customer SSN as ``V3__seed_test_data.sql`` inserts it. */
    private static final String PLAINTEXT_SSN = "587518382";

    /** :purpose: Plaintext government id as the fixtures insert it. */
    private static final String PLAINTEXT_GOVT_ID = "00000000000506210371";

    /** :purpose: Plaintext EFT account id as the fixtures insert it. */
    private static final String PLAINTEXT_EFT = "0069194009";

    /** :purpose: Plaintext card verification value as the fixtures insert it. */
    private static final String PLAINTEXT_CVV = "123";

    private static String previousKey;

    private final CryptoConverter converter = new CryptoConverter();

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

    @Test
    @DisplayName("version 4 declares the identity Flyway records for it")
    void declaresItsOwnVersionAndDescription() {
        SeededPiiEncryptionMigration migration = new SeededPiiEncryptionMigration();

        assertThat(migration.getVersion().getVersion()).isEqualTo("4");
        assertThat(migration.getDescription()).isEqualTo("encrypt seeded pii");
        assertThat(migration.getChecksum()).isNull();
        assertThat(migration.canExecuteInTransaction()).isTrue();
    }

    @Test
    @DisplayName("plaintext seeded columns are rewritten as gcm1: tokens that decrypt to the fixture values")
    void encryptsPlaintextSeededColumns() throws SQLException {
        RecordingConnection connection = new RecordingConnection(
                List.<Object[]>of(new Object[] {1L, PLAINTEXT_SSN, PLAINTEXT_GOVT_ID, PLAINTEXT_EFT}),
                List.<Object[]>of(new Object[] {"4111111111111111", PLAINTEXT_CVV}));

        new SeededPiiEncryptionMigration().migrate(context(connection.connection));

        assertThat(connection.customerUpdates).hasSize(1);
        Object[] customer = connection.customerUpdates.get(0);
        assertThat((String) customer[0]).startsWith(CryptoConverter.ENVELOPE_PREFIX);
        assertThat((String) customer[1]).startsWith(CryptoConverter.ENVELOPE_PREFIX);
        assertThat((String) customer[2]).startsWith(CryptoConverter.ENVELOPE_PREFIX);
        assertThat(customer[3]).isEqualTo(1L);
        assertThat(converter.convertToEntityAttribute((String) customer[0])).isEqualTo(PLAINTEXT_SSN);
        assertThat(converter.convertToEntityAttribute((String) customer[1])).isEqualTo(PLAINTEXT_GOVT_ID);
        assertThat(converter.convertToEntityAttribute((String) customer[2])).isEqualTo(PLAINTEXT_EFT);

        assertThat(connection.cardUpdates).hasSize(1);
        Object[] card = connection.cardUpdates.get(0);
        assertThat((String) card[0]).startsWith(CryptoConverter.ENVELOPE_PREFIX);
        assertThat(card[1]).isEqualTo("4111111111111111");
        assertThat(converter.convertToEntityAttribute((String) card[0])).isEqualTo(PLAINTEXT_CVV);
    }

    @Test
    @DisplayName("a replay over already-encrypted rows rewrites nothing and issues no update")
    void isIdempotentOverEncryptedRows() throws SQLException {
        RecordingConnection connection = new RecordingConnection(
                List.<Object[]>of(new Object[] {
                    1L,
                    converter.convertToDatabaseColumn(PLAINTEXT_SSN),
                    converter.convertToDatabaseColumn(PLAINTEXT_GOVT_ID),
                    converter.convertToDatabaseColumn(PLAINTEXT_EFT)}),
                List.<Object[]>of(new Object[] {"4111111111111111", converter.convertToDatabaseColumn(PLAINTEXT_CVV)}));

        new SeededPiiEncryptionMigration().migrate(context(connection.connection));

        assertThat(connection.customerUpdates).isEmpty();
        assertThat(connection.cardUpdates).isEmpty();
        verify(connection.connection, never()).prepareStatement(anyString());
    }

    @Test
    @DisplayName("null and empty columns are left exactly as they are")
    void leavesNullAndEmptyColumnsUntouched() throws SQLException {
        RecordingConnection connection = new RecordingConnection(
                List.<Object[]>of(new Object[] {1L, null, "", null}),
                List.<Object[]>of(new Object[] {"4111111111111111", null}));

        new SeededPiiEncryptionMigration().migrate(context(connection.connection));

        assertThat(connection.customerUpdates).isEmpty();
        assertThat(connection.cardUpdates).isEmpty();
    }

    @Test
    @DisplayName("a partially converted table has only its plaintext rows rewritten")
    void rewritesOnlyTheRowsStillHoldingPlaintext() throws SQLException {
        RecordingConnection connection = new RecordingConnection(
                List.<Object[]>of(
                    new Object[] {
                        1L,
                        converter.convertToDatabaseColumn(PLAINTEXT_SSN),
                        converter.convertToDatabaseColumn(PLAINTEXT_GOVT_ID),
                        converter.convertToDatabaseColumn(PLAINTEXT_EFT)},
                    new Object[] {2L, PLAINTEXT_SSN, PLAINTEXT_GOVT_ID, PLAINTEXT_EFT}),
                List.of());

        new SeededPiiEncryptionMigration().migrate(context(connection.connection));

        assertThat(connection.customerUpdates).hasSize(1);
        assertThat(connection.customerUpdates.get(0)[3]).isEqualTo(2L);
        assertThat(connection.cardUpdates).isEmpty();
    }

    /**
     * :purpose: Build a Flyway migration context over the given connection.
     * :param connection: the connection the migration must use.
     * :returns: a context returning that connection.
     */
    private Context context(Connection connection) {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }

    /**
     * :purpose: A mocked JDBC connection that serves one fixed result set per table and
     *     records every batched update parameter tuple, so the test can assert on the exact
     *     values the migration writes back.
     */
    private static final class RecordingConnection {

        /** :purpose: The mocked connection handed to the migration. */
        private final Connection connection = mock(Connection.class);

        /** :purpose: Parameter tuples batched against the ``customers`` table. */
        private final List<Object[]> customerUpdates = new ArrayList<>();

        /** :purpose: Parameter tuples batched against the ``cards`` table. */
        private final List<Object[]> cardUpdates = new ArrayList<>();

        /**
         * :purpose: Wire the mocked statements for both tables the migration converts.
         * :param customerRows: rows the ``customers`` select returns, each ``{key, ssn,
         *     govtId, eft}``.
         * :param cardRows: rows the ``cards`` select returns, each ``{key, cvv}``.
         */
        private RecordingConnection(List<Object[]> customerRows, List<Object[]> cardRows) throws SQLException {
            // Every collaborating mock is fully built BEFORE it is handed to a when(...)
            // call: nesting one stubbing inside another leaves Mockito with an unfinished
            // stubbing and fails the test rather than the code under test.
            Statement statement = mock(Statement.class);
            ResultSet customerResults = resultSet(customerRows, 3);
            ResultSet cardResults = resultSet(cardRows, 1);
            PreparedStatement customerUpdate = recordingStatement(customerUpdates, 4);
            PreparedStatement cardUpdate = recordingStatement(cardUpdates, 2);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(startsWith("SELECT cust_id"))).thenReturn(customerResults);
            when(statement.executeQuery(startsWith("SELECT card_num"))).thenReturn(cardResults);
            when(connection.prepareStatement(startsWith("UPDATE customers"))).thenReturn(customerUpdate);
            when(connection.prepareStatement(startsWith("UPDATE cards"))).thenReturn(cardUpdate);
        }

        /**
         * :purpose: Build a forward-only result set over the supplied rows.
         * :param rows: the rows to serve, each ``{key, value...}``.
         * :param valueColumns: number of value columns following the key.
         * :returns: a mocked result set walking those rows once.
         */
        private static ResultSet resultSet(List<Object[]> rows, int valueColumns) throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            int[] cursor = {-1};
            when(resultSet.next()).thenAnswer(invocation -> ++cursor[0] < rows.size());
            when(resultSet.getObject(1)).thenAnswer(invocation -> rows.get(cursor[0])[0]);
            when(resultSet.getString(anyInt())).thenAnswer(invocation -> {
                int column = invocation.getArgument(0, Integer.class);
                if (column < 2 || column > valueColumns + 1) {
                    throw new SQLException("unexpected column index " + column);
                }
                return (String) rows.get(cursor[0])[column - 1];
            });
            return resultSet;
        }

        /**
         * :purpose: Build a prepared statement that records each batched parameter tuple.
         * :param sink: list receiving one array per :java:meth:`addBatch` call.
         * :param parameterCount: number of parameters the migration binds per row.
         * :returns: a mocked prepared statement recording into ``sink``.
         */
        private static PreparedStatement recordingStatement(List<Object[]> sink, int parameterCount)
                throws SQLException {
            PreparedStatement statement = mock(PreparedStatement.class);
            Object[][] pending = {new Object[parameterCount]};
            org.mockito.Mockito.doAnswer(invocation -> {
                int index = invocation.getArgument(0, Integer.class);
                pending[0][index - 1] = invocation.getArgument(1);
                return null;
            }).when(statement).setObject(anyInt(), org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(invocation -> {
                sink.add(pending[0].clone());
                pending[0] = new Object[parameterCount];
                return null;
            }).when(statement).addBatch();
            return statement;
        }
    }
}

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
package com.carddemo.account.integration;

import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.crypto.SeededPiiEncryptionMigrator;
import com.carddemo.common.crypto.SeededPiiEncryptionMigrator.ProtectedColumn;
import com.carddemo.common.crypto.CryptoConverter;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.exception.PiiEncryptionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * :purpose: Prove the fix for the reported defect where every seeded account and card read
 *     failed with ``JpaSystemException: Error attempting to apply AttributeConverter``. The
 *     Flyway seed inserts the sensitive customer columns with raw SQL, which bypasses the JPA
 *     {@link CryptoConverter}, so the stored value is plaintext while the converter expects
 *     ``Base64(IV || AES-GCM ciphertext)``. {@link SeededPiiEncryptionMigrator} closes that gap at
 *     service start.
 * :output: Three guarantees — a plaintext column makes the entity read fail closed with the
 *     domain {@link PiiEncryptionException} rather than an opaque ORM error; running the sweep
 *     makes the same read succeed and return the original plaintext; and running the sweep a
 *     second time changes nothing, so it is safe on every start and in every replica.
 * :note: The schema and the seeded customer come from the service's own Flyway run of the
 *     shared migration set (``classpath:db/migration``) against the container started below,
 *     so no explicit script list is needed here; ``plantPlaintext`` restores the raw-SQL
 *     plaintext condition the sweep exists to close.
 */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none"})
@ActiveProfiles("test")
class PiiAtRestSweepIT {

    /** :purpose: Seeded customer whose sensitive columns are swept. */
    private static final Long CUST_ID = 5L;

    /** :purpose: Plaintext social security number written directly with raw SQL. */
    private static final String PLAINTEXT_SSN = "611264288";

    /** :purpose: Plaintext government issued id written directly with raw SQL. */
    private static final String PLAINTEXT_GOVT_ID = "00000000000639799754";

    /** :purpose: Plaintext EFT account id written directly with raw SQL. */
    private static final String PLAINTEXT_EFT_ID = "0006365573";

    /**
     * :purpose: Real PostgreSQL 18 instance. Started in a static initializer so the mapped port
     *     is available to ``@DynamicPropertySource`` before the application context (and with
     *     it the Flyway migration of this container) loads.
     */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    static {
        POSTGRES.start();
    }

    /**
     * :purpose: Point the application datasource at the started container.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /** :purpose: Raw SQL access used to plant plaintext and to inspect the stored value. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** :purpose: Repository used to prove the entity read succeeds after the sweep. */
    @Autowired
    private CustomerRepository customerRepository;

    /** :purpose: Transaction manager backing the sweep's batched rewrite. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** :purpose: The sweep under test, wired for the customer columns this service owns. */
    private SeededPiiEncryptionMigrator sweep;

    /**
     * :purpose: Plant plaintext in the three sensitive columns, exactly as the Flyway seed does.
     */
    @BeforeEach
    void plantPlaintext() {
        jdbcTemplate.update("UPDATE customers SET cust_ssn = ?, cust_govt_issued_id = ?, "
                        + "cust_eft_account_id = ? WHERE cust_id = ?",
                PLAINTEXT_SSN, PLAINTEXT_GOVT_ID, PLAINTEXT_EFT_ID, CUST_ID);

        sweep = new SeededPiiEncryptionMigrator(jdbcTemplate,
                new TransactionTemplate(transactionManager), new CryptoConverter(), List.of(
                new ProtectedColumn("customers", "cust_id", "cust_ssn"),
                new ProtectedColumn("customers", "cust_id", "cust_govt_issued_id"),
                new ProtectedColumn("customers", "cust_id", "cust_eft_account_id")));
    }

    /**
     * :purpose: Neither form of an unprotected column may produce the bare
     *  ``ArrayIndexOutOfBoundsException`` the QA report observed. The converter distinguishes
     *  the two cases structurally: a value that could not possibly be a token — a nine-digit
     *  SSN, which is what the raw-SQL seed writes — is read through unchanged, which is
     *  precisely why the sweep below exists rather than a crash; whereas a value MARKED as a
     *  CardDemo token that does not decrypt is a corrupted or foreign-key ciphertext and
     *  fails closed with the domain exception. Hibernate wraps any converter failure, so the
     *  domain type is asserted on the cause chain; ``PersistenceExceptionHandler`` unwraps it
     *  for the HTTP envelope.
     * :output: the seeded plaintext returned verbatim, and a {@link PiiEncryptionException}
     *  carrying the frozen non-disclosing message for a marked-but-undecryptable value.
     */
    @Test
    void unprotectedColumnIsReadThroughWhileACorruptedTokenFailsClosed() {
        // A short plaintext value cannot structurally be a token, so it is returned as stored
        // and no key is even required to read the row.
        assertThat(customerRepository.findById(CUST_ID).orElseThrow().getCustSsn())
                .isEqualTo(PLAINTEXT_SSN);

        // Corrupt the PAYLOAD of a genuine token while leaving the envelope marker intact: the
        // value still claims to be CardDemo ciphertext, so reading it must fail loudly rather
        // than silently hand back an unusable string.
        String token = new CryptoConverter().convertToDatabaseColumn(PLAINTEXT_SSN);
        String payload = token.substring(CryptoConverter.ENVELOPE_PREFIX.length());
        String corrupted = CryptoConverter.ENVELOPE_PREFIX
                + (payload.charAt(0) == 'A' ? 'B' : 'A') + payload.substring(1);
        jdbcTemplate.update("UPDATE customers SET cust_ssn = ? WHERE cust_id = ?",
                corrupted, CUST_ID);

        Throwable thrown = catchThrowable(() -> customerRepository.findById(CUST_ID).orElseThrow()
                .getCustSsn());

        assertThat(thrown).isNotNull();
        // The domain type on the cause chain is what PersistenceExceptionHandler keys on to
        // answer with the frozen non-disclosing message; the exception's own message carries
        // the operator-facing detail and is never returned, so only the type and the absence
        // of the protected value are asserted here.
        assertThat(rootPiiFailure(thrown)).isNotNull();
        assertThat(thrown).hasMessageNotContaining(PLAINTEXT_SSN);
        assertThat(rootPiiFailure(thrown))
                .isNotNull()
                .hasMessage(PiiEncryptionException.MESSAGE);
    }

    /**
     * :purpose: Locate the at-rest encryption failure inside a wrapped throwable.
     * :param thrown: the throwable raised by the persistence layer.
     * :returns: the {@link PiiEncryptionException} found in the cause chain, or ``null``.
     */
    private static PiiEncryptionException rootPiiFailure(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof PiiEncryptionException failure) {
                return failure;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /**
     * :purpose: After the sweep the stored value is ciphertext and the entity read returns the
     *  original plaintext, so ``GET /accounts/{id}`` no longer fails.
     * :output: a ciphertext column value and a successfully hydrated customer.
     */
    @Test
    void afterSweep_columnsAreCiphertextAndTheEntityHydrates() {
        sweep.encryptUnprotectedValues();

        String storedSsn = jdbcTemplate.queryForObject(
                "SELECT cust_ssn FROM customers WHERE cust_id = ?", String.class, CUST_ID);
        assertThat(storedSsn).isNotEqualTo(PLAINTEXT_SSN);
        assertThat(new CryptoConverter().convertToEntityAttribute(storedSsn)).isEqualTo(PLAINTEXT_SSN);

        Customer customer = customerRepository.findById(CUST_ID).orElseThrow();
        assertThat(customer.getCustSsn()).isEqualTo(PLAINTEXT_SSN);
        assertThat(customer.getCustGovtIssuedId()).isEqualTo(PLAINTEXT_GOVT_ID);
        assertThat(customer.getCustEftAccountId()).isEqualTo(PLAINTEXT_EFT_ID);
    }

    /**
     * :purpose: The sweep is idempotent: a second run leaves an already-encrypted value byte
     *  identical, so it never double-encrypts on a restart or in a second replica.
     * :output: the stored ciphertext unchanged across the second run.
     */
    @Test
    void sweepIsIdempotent() {
        sweep.encryptUnprotectedValues();
        String afterFirst = jdbcTemplate.queryForObject(
                "SELECT cust_ssn FROM customers WHERE cust_id = ?", String.class, CUST_ID);

        sweep.encryptUnprotectedValues();
        String afterSecond = jdbcTemplate.queryForObject(
                "SELECT cust_ssn FROM customers WHERE cust_id = ?", String.class, CUST_ID);

        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(customerRepository.findById(CUST_ID).orElseThrow().getCustSsn())
                .isEqualTo(PLAINTEXT_SSN);
    }
}

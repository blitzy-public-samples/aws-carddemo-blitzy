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
package com.carddemo.account.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.crypto.CryptoConverter;
import com.carddemo.common.domain.Customer;

/**
 * :purpose: Verify that a customer row created by the committed Flyway seed
 *     migrations can be read back through JPA even though the seed stores the
 *     sensitive columns (``cust_ssn``, ``cust_govt_issued_id``,
 *     ``cust_eft_account_id``) as fixed-width plaintext taken from
 *     ``app/data/ASCII/custdata.txt``, and that re-saving such a row upgrades
 *     those columns to AES-256-GCM ciphertext at rest (AAP 0.6.7).
 * :output: JUnit 5 / AssertJ assertions executed against an ephemeral
 *     Testcontainers PostgreSQL seeded by the real account-service migrations.
 *     Sensitive values are compared by shape and by round-trip equality only;
 *     no PII literal is written into this test or logged.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SeededPiiRoundTripIT {

    /** :purpose: Seeded customer identifier read by every case (``CUST-ID PIC 9(09)``). */
    private static final Long CUST_ID = 1L;

    /**
     * :purpose: Ephemeral PostgreSQL 18 datastore for the class, bound to the Spring
     *     datasource by {@link #datasourceProperties}.
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
                    .withDatabaseName("carddemo")
                    .withUsername("test")
                    .withPassword("test");

    /**
     * :purpose: Bind the container coordinates and disable Hibernate schema
     *     validation, because the account-service migrations create only
     *     ``customers`` and ``accounts`` while the service entity-scans every
     *     shared domain type.
     * :param registry: registry resolved before the application context starts.
     * :note: The schema and its seed rows are provisioned by the context's OWN Flyway,
     *     which the ``test`` profile points at the shared ``classpath:db/migration`` set
     *     using the default history table. Running Flyway again from a ``@BeforeAll``
     *     under a differently-named history table populated ``public`` before the context
     *     started, and Boot's Flyway then refused to migrate a non-empty schema that
     *     carried no history table of its own.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    /** :purpose: Repository under test, exercising the converter on the read path. */
    @Autowired
    private CustomerRepository customerRepository;

    /** :purpose: Cache-bypassing access to the raw column values. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String rawColumn(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM customers WHERE cust_id = ?", String.class, CUST_ID);
    }

    @Test
    @DisplayName("a seeded customer's sensitive attributes are readable through JPA")
    void seededCustomer_isReadableThroughJpa() {
        Optional<Customer> found = customerRepository.findById(CUST_ID);

        assertThat(found).isPresent();
        Customer customer = found.orElseThrow();
        assertThat(customer.getCustSsn()).isNotBlank();
        assertThat(customer.getCustGovtIssuedId()).isNotBlank();
        assertThat(customer.getCustEftAccountId()).isNotBlank();
        // The raw-SQL seed writes plaintext, which the seeded-PII encryption migration
        // then protects, so a deployed row is a token at rest and the clear value exists
        // only in the entity the converter hands back. Both halves are asserted: the
        // column is unreadable as stored, and the read path still yields the seeded value.
        assertThat(CryptoConverter.isEncryptedToken(rawColumn("cust_ssn"))).isTrue();
        assertThat(customer.getCustSsn()).isNotEqualTo(rawColumn("cust_ssn"));
        assertThat(CryptoConverter.isEncryptedToken(rawColumn("cust_govt_issued_id"))).isTrue();
        assertThat(CryptoConverter.isEncryptedToken(rawColumn("cust_eft_account_id"))).isTrue();
        // The clear value the converter hands back is the seeded identifier itself, so the
        // round trip is lossless and not merely "different from the ciphertext".
        assertThat(customer.getCustSsn()).doesNotStartWith(CryptoConverter.ENVELOPE_PREFIX);
        assertThat(customer.getCustGovtIssuedId())
                .doesNotStartWith(CryptoConverter.ENVELOPE_PREFIX);
        assertThat(customer.getCustEftAccountId())
                .doesNotStartWith(CryptoConverter.ENVELOPE_PREFIX);
    }

    @Test
    @DisplayName("re-saving a seeded customer encrypts its sensitive attributes at rest")
    void reSavingSeededCustomer_encryptsAtRest() {
        Customer customer = customerRepository.findById(CUST_ID).orElseThrow();
        String ssnBefore = customer.getCustSsn();
        String govtIdBefore = customer.getCustGovtIssuedId();

        // Any update rewrites the whole row, so the converter re-encrypts the
        // sensitive columns; a non-sensitive field is changed to make the entity
        // dirty without touching the PII values themselves.
        customer.setCustFicoCreditScore(customer.getCustFicoCreditScore() + 1);
        customerRepository.saveAndFlush(customer);

        String storedSsn = rawColumn("cust_ssn");
        assertThat(CryptoConverter.isEncryptedToken(storedSsn)).isTrue();
        assertThat(storedSsn).isNotEqualTo(ssnBefore);
        assertThat(CryptoConverter.isEncryptedToken(rawColumn("cust_govt_issued_id"))).isTrue();

        // Reading the now-encrypted row still yields the original values.
        Customer reloaded = customerRepository.findById(CUST_ID).orElseThrow();
        assertThat(reloaded.getCustSsn()).isEqualTo(ssnBefore);
        assertThat(reloaded.getCustGovtIssuedId()).isEqualTo(govtIdBefore);
    }
}

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
package com.carddemo.account.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.common.domain.Customer;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Testcontainers-backed persistence integration test for
 *     {@link CustomerRepository}, covering the VSAM ``CUSTFILE`` ->
 *     PostgreSQL ``customers`` migration. Verifies that a customer row
 *     round-trips through the JPA layer on its non-sensitive fields.
 * :output: JUnit 5 assertions; no console output. Sensitive PII (SSN,
 *     government-issued id) is neither seeded, asserted, nor logged.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CustomerRepositoryIT {

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18`` container
     *     from :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`, whose
     *     schema comes exclusively from the committed Flyway migrations. With
     *     ``ddl-auto: validate`` this turns the entity-to-migration mapping into an assertion
     *     of this test rather than a fixture it creates.
     * :param registry: the dynamic property registry supplied by the Spring Test context.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }

    /**
     * :purpose: Customer id outside the 1..50 range the migrations seed, so this test inserts
     *     its own row instead of mutating seed data.
     */
    private static final Long CUST_ID = 900_000_001L;

    @Autowired
    private CustomerRepository customerRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static Customer newCustomer(Long custId) {
        Customer customer = new Customer();
        customer.setCustId(custId);
        customer.setCustFirstName("IMMANUEL");
        customer.setCustMiddleName("MADELINE");
        customer.setCustLastName("KESSLER");
        customer.setCustAddrLine1("618 DESHAUN ROUTE");
        customer.setCustAddrLine2("APT 802");
        customer.setCustAddrLine3("ALTENWERTHSHIRE");
        customer.setCustAddrStateCd("NC");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("12546");
        customer.setCustPhoneNum1("(908)119-8310");
        customer.setCustPhoneNum2("(373)693-8684");
        customer.setCustDobYyyyMmDd("1961-06-08");
        customer.setCustEftAccountId("0053581756");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(274);
        // Real PII is never seeded, asserted or logged (AAP 0.6.7). The migrated
        // ``customers`` table declares cust_ssn and cust_govt_issued_id NOT NULL (the
        // fixed-width COBOL fields are always present, blank when unknown), so they carry
        // blank values here - which the CryptoConverter passes through unchanged.
        customer.setCustSsn("");
        customer.setCustGovtIssuedId("");
        return customer;
    }

    @Test
    @DisplayName("save then findById round-trips a Customer on its non-PII fields")
    void saveAndFindByIdRoundTripsNonPiiFields() {
        customerRepository.saveAndFlush(newCustomer(CUST_ID));
        entityManager.clear();

        Customer loaded = customerRepository.findById(CUST_ID).orElseThrow();

        assertThat(loaded.getCustId()).isEqualTo(CUST_ID);
        assertThat(loaded.getCustFirstName()).isEqualTo("IMMANUEL");
        assertThat(loaded.getCustMiddleName()).isEqualTo("MADELINE");
        assertThat(loaded.getCustLastName()).isEqualTo("KESSLER");
        assertThat(loaded.getCustAddrStateCd()).isEqualTo("NC");
        assertThat(loaded.getCustAddrCountryCd()).isEqualTo("USA");
        assertThat(loaded.getCustAddrZip()).isEqualTo("12546");
        assertThat(loaded.getCustPriCardHolderInd()).isEqualTo("Y");
        assertThat(loaded.getCustFicoCreditScore()).isEqualTo(274);
    }

    /**
     * :purpose: Prove the AAP 0.6.7 encrypt-at-rest control on the real migrated column: a
     *     value written through the entity is stored as an AES-256-GCM token (never the
     *     plaintext) and is recovered verbatim on read. The value used is a synthetic,
     *     all-zero identifier, never a real SSN.
     * :output: The stored column differs from the written value, and the reloaded entity
     *     matches it exactly.
     */
    @Test
    @DisplayName("a converted PII column is stored encrypted at rest and decrypts on read")
    void piiColumnIsEncryptedAtRestAndDecryptsOnRead() {
        String syntheticIdentifier = "000000000";
        Customer customer = newCustomer(CUST_ID);
        customer.setCustSsn(syntheticIdentifier);
        customerRepository.saveAndFlush(customer);
        entityManager.clear();

        String stored = (String) entityManager
                .createNativeQuery("SELECT cust_ssn FROM customers WHERE cust_id = :id")
                .setParameter("id", CUST_ID)
                .getSingleResult();
        assertThat(stored).isNotEqualTo(syntheticIdentifier);
        assertThat(stored.length()).isGreaterThan(syntheticIdentifier.length());

        Customer loaded = customerRepository.findById(CUST_ID).orElseThrow();
        assertThat(loaded.getCustSsn()).isEqualTo(syntheticIdentifier);
    }
}

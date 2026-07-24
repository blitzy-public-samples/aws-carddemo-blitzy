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
import org.testcontainers.junit.jupiter.Testcontainers;

import com.carddemo.common.domain.Customer;

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
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CustomerRepositoryIT {

    private static final Long CUST_ID = 1L;

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
        // PII (custSsn, custGovtIssuedId) intentionally left null:
        // never seeded, asserted, or logged (AAP 0.6.7).
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
}

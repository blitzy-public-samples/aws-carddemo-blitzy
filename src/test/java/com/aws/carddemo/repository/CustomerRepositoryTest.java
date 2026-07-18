/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed Spring Data JPA integration tests for
 * {@link CustomerRepository} exercised against a real PostgreSQL&nbsp;16 database.
 *
 * <p><strong>Behavioral parity.</strong> These tests validate the keyed and
 * key-ordered access that the legacy COBOL customer programs performed against
 * the VSAM KSDS {@code CUSTDATA.VSAM.KSDS} (copybook {@code CVCUS01Y.cpy}), now
 * re-expressed as set-based access over the PostgreSQL {@code customer} table
 * (AAP&nbsp;0.4.3, 0.9.2):
 * <ul>
 *   <li>the customer master-print batch {@code legacy/cbl/CBCUS01C.cbl} opens
 *       {@code CUSTFILE} with {@code ACCESS MODE IS SEQUENTIAL} and reads every
 *       record in ascending {@code CUST-ID} key order — reproduced by
 *       {@link CustomerRepository#findAllByOrderByCustIdAsc()};</li>
 *   <li>the online account view/update programs
 *       ({@code legacy/cbl/COACTVWC.cbl}, {@code legacy/cbl/COACTUPC.cbl})
 *       perform a single keyed read — reproduced by the inherited
 *       {@code findById}.</li>
 * </ul>
 *
 * <p><strong>Harness.</strong> The {@link DataJpaTest} slice runs against a
 * disposable {@code postgres:16-alpine} container wired into the Spring
 * datasource by {@link ServiceConnection} (no {@code @DynamicPropertySource},
 * no hardcoded credentials — AAP&nbsp;0.8.1/0.9.3). The embedded-database
 * replacement is switched off with {@link AutoConfigureTestDatabase.Replace#NONE}
 * so the real container is used. The {@code test} profile keeps Hibernate at
 * {@code ddl-auto=validate}, letting Flyway ({@code V1__schema.sql} +
 * {@code V2__reference_data.sql}) own the schema; the {@link Customer} mapping
 * is therefore validated against the actual shipped DDL. Each test executes in
 * its own transaction that is rolled back afterwards, so the (un-seeded)
 * {@code customer} table starts empty for every test.
 *
 * <p><strong>Sensitive PII.</strong> The {@code toString} test asserts the
 * <em>absence</em> of the sensitive SSN, government-issued id, and date of
 * birth rather than printing them, honoring the rule that these fields are
 * never exposed (AAP&nbsp;0.9.3).
 *
 * @see CustomerRepository
 * @see Customer
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class CustomerRepositoryTest {

    /**
     * A single PostgreSQL&nbsp;16 container shared by every test in this class.
     *
     * <p>Declared {@code static} so it is started once per class (not once per
     * test) for speed. {@link ServiceConnection} publishes its JDBC URL,
     * username, and password to the Spring {@code Environment} automatically,
     * so no connection details are ever hardcoded.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Repository under test. */
    private final CustomerRepository repository;

    /**
     * Wrapper around the shared persistence context, used to evict managed
     * entities so that {@code findById} issues a real {@code SELECT} against
     * PostgreSQL rather than returning the first-level cache instance.
     */
    private final TestEntityManager entityManager;

    /**
     * Constructor injection of the collaborators managed by the
     * {@link DataJpaTest} slice.
     *
     * <p>The constructor is annotated with {@link Autowired} so that the Spring
     * TestContext resolves its parameters (the default test-constructor
     * autowire mode is {@code ANNOTATED}). This is constructor injection, the
     * preferred style; no field injection is used.
     *
     * @param repository    the {@link CustomerRepository} bean under test
     * @param entityManager the slice's {@link TestEntityManager}
     */
    @Autowired
    CustomerRepositoryTest(CustomerRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * Persisting a fully populated customer and reading it back by primary key
     * round-trips every scalar field through PostgreSQL.
     *
     * <p>The persistence context is cleared between the write and the read so
     * that {@link CustomerRepository#findById(Object)} genuinely reloads the
     * row from the database. Only the non-sensitive fields are asserted; the
     * sensitive PII fields are populated but never printed.
     */
    @Test
    void saveAndFindById_roundTripsScalarFields() {
        Customer customer = new Customer(
                1L,                 // custId
                "Grace",            // custFirstName
                "Brewster",         // custMiddleName
                "Hopper",           // custLastName
                "1 Navy Yard",      // custAddrLine1
                "Building 3",       // custAddrLine2
                "Room 12",          // custAddrLine3
                "DC",               // custAddrStateCd
                "USA",              // custAddrCountryCd
                "20374",            // custAddrZip
                "202-555-0100",     // custPhoneNum1
                "202-555-0101",     // custPhoneNum2
                "000112222",        // custSsn (sensitive sample)
                "GOVT-SAMPLE-0001", // custGovtIssuedId (sensitive sample)
                "1906-12-09",       // custDob (sensitive sample)
                "EFT0000001",       // custEftAccountId
                "Y",                // custPriCardHolderInd
                750);               // custFicoCreditScore

        repository.saveAndFlush(customer);
        entityManager.clear();

        Customer reloaded = repository.findById(1L).orElseThrow();
        assertThat(reloaded.getCustId()).isEqualTo(1L);
        assertThat(reloaded.getCustFirstName()).isEqualTo("Grace");
        assertThat(reloaded.getCustMiddleName()).isEqualTo("Brewster");
        assertThat(reloaded.getCustLastName()).isEqualTo("Hopper");
        assertThat(reloaded.getCustAddrLine1()).isEqualTo("1 Navy Yard");
        assertThat(reloaded.getCustAddrLine2()).isEqualTo("Building 3");
        assertThat(reloaded.getCustAddrLine3()).isEqualTo("Room 12");
        assertThat(reloaded.getCustAddrStateCd()).isEqualTo("DC");
        assertThat(reloaded.getCustAddrCountryCd()).isEqualTo("USA");
        assertThat(reloaded.getCustAddrZip()).isEqualTo("20374");
        assertThat(reloaded.getCustPhoneNum1()).isEqualTo("202-555-0100");
        assertThat(reloaded.getCustPhoneNum2()).isEqualTo("202-555-0101");
        assertThat(reloaded.getCustEftAccountId()).isEqualTo("EFT0000001");
        assertThat(reloaded.getCustPriCardHolderInd()).isEqualTo("Y");
        assertThat(reloaded.getCustFicoCreditScore()).isEqualTo(750);
    }

    /**
     * {@link CustomerRepository#findAllByOrderByCustIdAsc()} returns customers
     * strictly ordered by ascending {@code custId}, regardless of insertion
     * order.
     *
     * <p>Rows are inserted with scrambled ids ({@code 3, 1, 2}) to prove the
     * ordering is produced by the query, not by insertion sequence. This
     * reproduces the ascending key-ordered browse of the {@code CBCUS01C}
     * customer master-print batch.
     */
    @Test
    void findAllByOrderByCustIdAsc_returnsAscending() {
        repository.saveAll(List.of(
                minimalCustomer(3L, "Carol"),
                minimalCustomer(1L, "Alice"),
                minimalCustomer(2L, "Bob")));
        repository.flush();
        entityManager.clear();

        List<Customer> ordered = repository.findAllByOrderByCustIdAsc();

        assertThat(ordered)
                .hasSize(3)
                .extracting(Customer::getCustId)
                .containsExactly(1L, 2L, 3L);
    }

    /**
     * Looking up a primary key that does not exist yields an empty
     * {@link java.util.Optional} (the {@code customer} table is empty under the
     * slice), mirroring the legacy {@code FILE STATUS '23'} "record not found"
     * outcome without throwing.
     */
    @Test
    void findById_absentId_returnsEmpty() {
        assertThat(repository.findById(9_999_999L)).isEmpty();
    }

    /**
     * The FICO credit score persists and reloads as an {@link Integer},
     * guarding the {@code CUST-FICO-CREDIT-SCORE PIC 9(03) -> INTEGER} mapping.
     */
    @Test
    void ficoCreditScorePersistsAsInteger() {
        Customer customer = minimalCustomer(42L, "Fico");
        customer.setCustFicoCreditScore(680);

        repository.saveAndFlush(customer);
        entityManager.clear();

        Customer reloaded = repository.findById(42L).orElseThrow();
        assertThat(reloaded.getCustFicoCreditScore()).isEqualTo(680);
    }

    /**
     * {@link Customer#toString()} must never expose the sensitive SSN,
     * government-issued id, or date of birth (AAP&nbsp;0.9.3).
     *
     * <p>Distinctive sentinel values are assigned to the three sensitive fields
     * and the rendered string is asserted to contain none of them. The
     * customer id and all non-sensitive values are chosen so they cannot
     * accidentally contain a sentinel substring. No persistence is required.
     */
    @Test
    void toStringExcludesSensitivePii() {
        String sentinelSsn = "123456789";
        String sentinelGovtId = "GID-SENTINEL-001";
        String sentinelDob = "1911-11-11";

        Customer customer = minimalCustomer(5L, "Pat");
        customer.setCustSsn(sentinelSsn);
        customer.setCustGovtIssuedId(sentinelGovtId);
        customer.setCustDob(sentinelDob);

        String rendered = customer.toString();

        assertThat(rendered).doesNotContain(sentinelSsn, sentinelGovtId, sentinelDob);
    }

    /**
     * The {@code @Version} column added to {@code customer} by Flyway migration
     * {@code V3__add_customer_version.sql} is assigned on insert and increments on
     * update, giving the customer record the same optimistic-lock integrity as
     * {@link com.aws.carddemo.domain.Account}. This reproduces the COBOL
     * READ-UPDATE-REWRITE guarantee that {@code COACTUPC}'s
     * {@code 9700-CHECK-CHANGE-IN-REC} enforced over the customer record before
     * rewriting &mdash; a documented, intentional improvement (AAP&nbsp;0.7.1&nbsp;H6;
     * see {@code docs/decision-log.md}).
     *
     * <p>A flush plus {@code clear} after each write materializes the version
     * increment and forces the subsequent reload to be served from the database
     * rather than the persistence-context first-level cache.</p>
     */
    @Test
    void versionAssignedOnInsertAndIncrementsOnUpdate() {
        repository.saveAndFlush(minimalCustomer(2L, "Ada"));
        entityManager.clear();

        Customer inserted = repository.findById(2L).orElseThrow();
        assertThat(inserted.getVersion()).isNotNull();
        long initialVersion = inserted.getVersion();

        inserted.setCustLastName("Lovelace");
        repository.saveAndFlush(inserted);
        entityManager.clear();

        Customer updated = repository.findById(2L).orElseThrow();
        assertThat(updated.getVersion()).isNotNull();
        assertThat(updated.getVersion()).isGreaterThan(initialVersion);
    }

    /**
     * Builds a customer that satisfies the schema (only {@code cust_id} is
     * {@code NOT NULL}) with a couple of readable non-sensitive fields and no
     * sensitive PII populated. Callers set any additional fields they assert.
     *
     * <p>The public most-args constructor is used (the no-argument constructor
     * on {@link Customer} is {@code protected} and reserved for the JPA
     * provider); every field the caller does not need is left {@code null}.
     *
     * @param custId    the primary key
     * @param firstName a readable first name for diagnostics
     * @return a new, unpersisted {@link Customer}
     */
    private static Customer minimalCustomer(long custId, String firstName) {
        return new Customer(
                custId,                  // custId
                firstName,               // custFirstName
                null,                    // custMiddleName
                "Customer" + custId,     // custLastName
                null,                    // custAddrLine1
                null,                    // custAddrLine2
                null,                    // custAddrLine3
                null,                    // custAddrStateCd
                null,                    // custAddrCountryCd
                null,                    // custAddrZip
                null,                    // custPhoneNum1
                null,                    // custPhoneNum2
                null,                    // custSsn (sensitive)
                null,                    // custGovtIssuedId (sensitive)
                null,                    // custDob (sensitive)
                null,                    // custEftAccountId
                null,                    // custPriCardHolderInd
                null);                   // custFicoCreditScore
    }
}

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
package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers-backed Spring Data JPA integration tests for
 * {@link CardXrefRepository} (entity {@link CardXref}, table {@code card_xref}).
 *
 * <p>This class is <strong>alternate-index browse-critical</strong>: it proves that the
 * legacy VSAM alternate index {@code CARDXREF.VSAM.AIX} (cross-reference by account,
 * {@code AXRKP=25}) exercised by {@code legacy/cbl/COCRDSLC.cbl} (card detail via the
 * account index) and the sequential primary-key browse of
 * {@code legacy/cbl/CBACT03C.cbl} (cross-reference master print) are faithfully
 * re-expressed as a PostgreSQL B-tree index ({@code idx_card_xref_acct_id}) plus sorted
 * Spring Data derived queries. The tests lock in the two behaviours that define parity:
 * cross-reference <em>card-number key ordering</em> (both within an account and globally)
 * and the account-scoped &quot;first card&quot; resolution used to look up an account's
 * primary card (AAP&nbsp;0.4.3, 0.7.1&nbsp;H5, 0.9.2).</p>
 *
 * <h2>Harness</h2>
 * <p>The slice runs against a real PostgreSQL&nbsp;16 provisioned by Testcontainers and
 * wired to Spring Boot through {@link ServiceConnection}, so the datasource carries no
 * hardcoded URL or credentials (AAP&nbsp;0.8.1&nbsp;/&nbsp;0.9.3) and no
 * {@code @DynamicPropertySource} is required. {@link AutoConfigureTestDatabase} with
 * {@link AutoConfigureTestDatabase.Replace#NONE} keeps that container datasource in place
 * (the embedded replacement is disabled). Flyway applies the production migrations
 * ({@code V1__schema.sql}, {@code V2__reference_data.sql}, &hellip;) and Hibernate then only
 * <em>validates</em> the {@link CardXref}, {@link Customer}, and {@link Account} mappings
 * against that schema ({@code ddl-auto=validate}), per {@code application-test.yml}. Each
 * test method runs in its own transaction that is rolled back on completion, so the
 * methods are independent.</p>
 *
 * <h2>Foreign-key parents</h2>
 * <p>{@code card_xref} has two real {@code NOT NULL} foreign keys ({@code cust_id} &rarr;
 * {@code customer} and {@code acct_id} &rarr; {@code account}), the formalization of the
 * relationships the COBOL programs enforced in application code. Every persisted
 * {@link CardXref} therefore requires a pre-existing {@link Customer} <em>and</em>
 * {@link Account} parent with matching identifiers; the helpers below insert the parents
 * first. Under {@link DataJpaTest} the {@code card_xref}, {@code customer}, and
 * {@code account} tables start empty (the {@code db/seed} CSVs are not loaded), so each
 * test seeds exactly the rows it needs.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class CardXrefRepositoryTest {

    /**
     * Shared PostgreSQL&nbsp;16 container for the whole test class. {@link Container} on a
     * {@code static} field starts it once for all methods; {@link ServiceConnection}
     * publishes its JDBC coordinates to Spring Boot's auto-configured datasource.
     *
     * <p>The image is pinned by its <strong>immutable content digest</strong> instead of
     * the floating {@code postgres:16-alpine} tag, so every run uses one byte-identical
     * image and cannot silently drift across patch or base-OS updates (reproducible build,
     * AAP&nbsp;0.9.1). The pinned digest resolves to <strong>PostgreSQL&nbsp;16.14-alpine</strong>
     * (the {@code postgres:16.14-alpine} tag points to this same manifest digest),
     * preserving the PostgreSQL&nbsp;16 integration contract (AAP&nbsp;0.9.2). Testcontainers
     * parses the {@code postgres@sha256:...} form with the repository resolving to
     * {@code postgres}, so the {@link PostgreSQLContainer} compatibility check is satisfied.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(
                    "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"));

    /** Repository under test. */
    private final CardXrefRepository repository;

    /** JPA test helper used to seed parent and cross-reference rows. */
    private final TestEntityManager entityManager;

    /**
     * Constructor injection (the project-wide convention; no field injection). Spring's
     * test extension resolves both collaborators from the sliced application context.
     *
     * @param repository    the {@link CardXrefRepository} under test
     * @param entityManager the {@link TestEntityManager} used to arrange fixtures
     */
    CardXrefRepositoryTest(CardXrefRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------------

    /**
     * Persists a minimal, valid {@link Customer} parent so a cross-reference's
     * {@code cust_id} foreign key resolves. Only {@code cust_id} is {@code NOT NULL}; the
     * remaining fields carry harmless, non-sensitive placeholder values. Sensitive PII
     * (SSN, government id, date of birth) is intentionally left {@code null}.
     *
     * @param id the customer identifier (primary key)
     * @return the persisted {@link Customer}
     */
    private Customer newCustomer(long id) {
        Customer customer = new Customer(
                id,            // custId (primary key)
                "First" + id,  // custFirstName
                "M",           // custMiddleName
                "Last" + id,   // custLastName
                "1 Main St",   // custAddrLine1
                null,          // custAddrLine2
                null,          // custAddrLine3
                "NY",          // custAddrStateCd
                "USA",         // custAddrCountryCd
                "10001",       // custAddrZip
                null,          // custPhoneNum1
                null,          // custPhoneNum2
                null,          // custSsn (sensitive; omitted)
                null,          // custGovtIssuedId (sensitive; omitted)
                null,          // custDob (sensitive; omitted)
                null,          // custEftAccountId
                "Y",           // custPriCardHolderInd
                700);          // custFicoCreditScore
        entityManager.persist(customer);
        return customer;
    }

    /**
     * Persists a minimal, valid {@link Account} parent so a cross-reference's
     * {@code acct_id} foreign key resolves. Only {@code acct_id} is {@code NOT NULL}; the
     * five monetary fields use {@link BigDecimal} at scale&nbsp;2 (never {@code double}/
     * {@code float}). {@code group_id} has no database foreign key, so no
     * {@code disclosure_group} parent is needed.
     *
     * @param id the account identifier (primary key)
     * @return the persisted {@link Account}
     */
    private Account newAccount(long id) {
        Account account = new Account(
                id,                      // acctId (primary key)
                "Y",                     // acctActiveStatus
                new BigDecimal("0.00"),  // currBal
                new BigDecimal("0.00"),  // creditLimit
                new BigDecimal("0.00"),  // cashCreditLimit
                "2020-01-01",            // acctOpenDate
                "2030-01-01",            // acctExpirationDate
                "2020-01-01",            // acctReissueDate
                new BigDecimal("0.00"),  // currCycCredit
                new BigDecimal("0.00"),  // currCycDebit
                "10001",                 // acctAddrZip
                "A000000000");           // groupId (no FK; any 10-char value)
        entityManager.persist(account);
        return account;
    }

    /**
     * Builds an unpersisted {@link CardXref}. Persistence is the caller's responsibility
     * and must happen only after the matching {@link Customer} and {@link Account} parents
     * exist; leaving the persist to the caller lets the foreign-key-violation test assert
     * the failure of an orphan insert.
     *
     * @param cardNum the 16-character card number (primary key)
     * @param custId  the owning customer identifier
     * @param acctId  the linked account identifier
     * @return a new, unpersisted {@link CardXref}
     */
    private CardXref newXref(String cardNum, long custId, long acctId) {
        return new CardXref(cardNum, custId, acctId);
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    /**
     * A persisted cross-reference is retrievable by its natural key and round-trips its
     * {@code cust_id} / {@code acct_id} foreign-key values unchanged.
     */
    @Test
    void saveAndFindById_roundTrips() {
        newCustomer(1L);
        newAccount(1L);
        entityManager.persist(newXref("4000000000000001", 1L, 1L));
        entityManager.flush();
        entityManager.clear();

        Optional<CardXref> found = repository.findById("4000000000000001");

        assertThat(found).isPresent();
        assertThat(found.get().getXrefCardNum()).isEqualTo("4000000000000001");
        assertThat(found.get().getCustId()).isEqualTo(1L);
        assertThat(found.get().getAcctId()).isEqualTo(1L);
    }

    /**
     * Core {@code CARDXREF.VSAM.AIX} browse parity: the account-scoped finder returns
     * exactly the requested account's cross-references, ascending by card number, and
     * excludes rows belonging to other accounts. The rows are inserted in scrambled order
     * to prove the ordering comes from the query, not from insertion order.
     */
    @Test
    void findByAcctIdOrderByXrefCardNumAsc_returnsXrefsForAccountInKeyOrder() {
        newCustomer(1L);
        newAccount(1L);
        newAccount(2L);
        // Account 1 rows inserted in non-ascending card-number order.
        entityManager.persist(newXref("4000000000000030", 1L, 1L));
        entityManager.persist(newXref("4000000000000010", 1L, 1L));
        entityManager.persist(newXref("4000000000000020", 1L, 1L));
        // Account 2 row that must NOT appear in the account-1 result.
        entityManager.persist(newXref("4000000000000099", 1L, 2L));
        entityManager.flush();
        entityManager.clear();

        List<CardXref> result = repository.findByAcctIdOrderByXrefCardNumAsc(1L);

        assertThat(result)
                .extracting(CardXref::getXrefCardNum)
                .containsExactly("4000000000000010", "4000000000000020", "4000000000000030");
        assertThat(result).allSatisfy(xref -> assertThat(xref.getAcctId()).isEqualTo(1L));
    }

    /**
     * Account &rarr; primary-card resolution used by {@code legacy/cbl/COCRDSLC.cbl}: the
     * &quot;first&quot; finder returns the lowest card number for an account, and yields
     * {@link Optional#empty()} when the account has no cross-references.
     */
    @Test
    void findFirstByAcctIdOrderByXrefCardNumAsc_returnsLowestCardNumberOptional() {
        newCustomer(1L);
        newAccount(1L);
        entityManager.persist(newXref("4000000000000030", 1L, 1L));
        entityManager.persist(newXref("4000000000000010", 1L, 1L));
        entityManager.persist(newXref("4000000000000020", 1L, 1L));
        entityManager.flush();
        entityManager.clear();

        Optional<CardXref> first = repository.findFirstByAcctIdOrderByXrefCardNumAsc(1L);

        assertThat(first).isPresent();
        assertThat(first.get().getXrefCardNum()).isEqualTo("4000000000000010");

        // No cross-reference rows for account 999 -> empty Optional.
        assertThat(repository.findFirstByAcctIdOrderByXrefCardNumAsc(999L)).isEmpty();
    }

    /**
     * The customer-scoped finder returns only the requested customer's cross-references,
     * ascending by card number, and excludes rows belonging to other customers.
     */
    @Test
    void findByCustIdOrderByXrefCardNumAsc_returnsXrefsForCustomerInKeyOrder() {
        newCustomer(1L);
        newCustomer(2L);
        newAccount(1L);
        newAccount(2L);
        // Customer 1 rows (linked to account 1) in non-ascending card-number order.
        entityManager.persist(newXref("4000000000000030", 1L, 1L));
        entityManager.persist(newXref("4000000000000010", 1L, 1L));
        // Customer 2 row that must NOT appear in the customer-1 result.
        entityManager.persist(newXref("4000000000000020", 2L, 2L));
        entityManager.flush();
        entityManager.clear();

        List<CardXref> result = repository.findByCustIdOrderByXrefCardNumAsc(1L);

        assertThat(result)
                .extracting(CardXref::getXrefCardNum)
                .containsExactly("4000000000000010", "4000000000000030");
        assertThat(result).allSatisfy(xref -> assertThat(xref.getCustId()).isEqualTo(1L));
    }

    /**
     * Global sequential-browse parity with {@code legacy/cbl/CBACT03C.cbl}: the unscoped
     * finder returns every cross-reference ordered ascending by card number, regardless of
     * insertion order.
     */
    @Test
    void findAllByOrderByXrefCardNumAsc_returnsAllInKeyOrder() {
        newCustomer(1L);
        newAccount(1L);
        entityManager.persist(newXref("4000000000000030", 1L, 1L));
        entityManager.persist(newXref("4000000000000010", 1L, 1L));
        entityManager.persist(newXref("4000000000000020", 1L, 1L));
        entityManager.flush();
        entityManager.clear();

        List<CardXref> result = repository.findAllByOrderByXrefCardNumAsc();

        assertThat(result)
                .extracting(CardXref::getXrefCardNum)
                .containsExactly("4000000000000010", "4000000000000020", "4000000000000030");
    }

    /**
     * Persisting a cross-reference whose {@code cust_id} and {@code acct_id} reference
     * non-existent parents fails the flush, confirming the formalized foreign keys behave
     * as the documented integrity improvement (previously enforced only in COBOL program
     * logic).
     */
    @Test
    void insertingXrefWithoutParents_violatesForeignKey() {
        // Neither Customer(999) nor Account(999) exists -> both NOT-NULL FKs are violated.
        CardXref orphan = newXref("4000000000000001", 999L, 999L);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(Exception.class);
    }
}

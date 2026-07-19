package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Customer;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link CustomerRepository} against the Flyway-seeded Testcontainers
 * PostgreSQL, asserting VSAM {@code CUSTDAT} KSDS parity (random read by {@code CUST-ID}).
 *
 * <p>These tests exercise the migrated Spring Data access path end-to-end: the concrete PostgreSQL
 * engine is started once by {@link AbstractPostgresIntegrationTest}, Flyway applies the versioned
 * migrations ({@code V1__schema.sql} creates the {@code customer} table and
 * {@code V2__reference_data.sql} seeds it), and Hibernate validates the {@link Customer} entity
 * mapping against that materialized schema. The repository CRUD surface inherited from
 * {@code JpaRepository} therefore reproduces the legacy random-read-by-key access to the VSAM
 * {@code CUSTDAT} base cluster.</p>
 *
 * <p>Parity oracles (per AAP &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code legacy/cpy/CVCUS01Y.cpy} (CUSTOMER-RECORD, RECLN 500; consolidated with
 *       {@code legacy/cpy/CUSTREC.cpy} into the single {@link Customer} entity per the decision
 *       log)</li>
 *   <li>{@code legacy/data/ASCII/custdata.txt} (50 fixed-width rows, seeded by
 *       {@code V2__reference_data.sql})</li>
 * </ul>
 *
 * <p>The spot-checked expectations for customer id {@code 1} are decoded from row 1 of
 * {@code custdata.txt} against the {@code CVCUS01Y} field layout: the fixed-width {@code CHAR}
 * columns are space-padded, so every {@code String} getter is compared after {@code trim()}; the
 * {@code CUST-SSN PIC 9(09)} value {@code 020973888} is stored as {@code NUMERIC(9)} and therefore
 * surfaces as the {@code long} {@code 20973888} with its leading zero dropped.</p>
 *
 * <p><strong>Read-only contract.</strong> The base class deliberately declares no
 * {@code @Transactional} boundary, so the seeded table is shared and must never be mutated. These
 * tests invoke only the non-mutating {@code count()} and {@code findById(...)} operations and
 * issue no persistence writes (no inserts, updates, or removals).</p>
 *
 * <p><strong>PII safety.</strong> The record contains personally identifiable information. The SSN
 * and date of birth are asserted for parity but are never written to standard output or the log
 * (CWE-532).</p>
 */
class CustomerRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Verifies the {@code customer} table is seeded with exactly the 50 rows present in the
     * {@code custdata.txt} fixture, confirming the Flyway reference-data migration loaded the full
     * VSAM {@code CUSTDAT} extract.
     */
    @Test
    @DisplayName("customer table seeds exactly 50 rows (custdata.txt parity)")
    void seededRowCountMatchesFixture() {
        assertThat(customerRepository.count()).isEqualTo(50L);
    }

    /**
     * Verifies a random read by primary key ({@code findById(1L)}, the VSAM read-by-{@code CUST-ID})
     * returns customer 1 with every field decoded exactly as it appears in row 1 of
     * {@code custdata.txt} / {@code CVCUS01Y.cpy}.
     */
    @Test
    @DisplayName("findById(1) returns customer 1 with exact CVCUS01Y/custdata.txt values")
    void findByIdReturnsSeededCustomerOne() {
        Optional<Customer> found = customerRepository.findById(1L);
        assertThat(found).isPresent();
        Customer c = found.get();
        assertThat(c.getCustId()).isEqualTo(1L);
        assertThat(c.getFirstName().trim()).isEqualTo("Immanuel");
        assertThat(c.getMiddleName().trim()).isEqualTo("Madeline");
        assertThat(c.getLastName().trim()).isEqualTo("Kessler");
        assertThat(c.getAddrStateCd().trim()).isEqualTo("NC");
        assertThat(c.getAddrCountryCd().trim()).isEqualTo("USA");
        assertThat(c.getSsn()).isEqualTo(20973888L);
        assertThat(c.getDateOfBirth()).isEqualTo(LocalDate.of(1961, 6, 8));
        assertThat(c.getPriCardHolderInd().trim()).isEqualTo("Y");
        assertThat(c.getFicoCreditScore()).isEqualTo(274);
    }

    /**
     * Verifies a lookup for a customer id absent from the seed returns an empty {@link Optional},
     * reproducing the VSAM "record not found" outcome for a random read of a non-existent key.
     */
    @Test
    @DisplayName("findById for a non-existent customer id returns empty")
    void findByIdMissingReturnsEmpty() {
        assertThat(customerRepository.findById(999_999_999L)).isEmpty();
    }
}

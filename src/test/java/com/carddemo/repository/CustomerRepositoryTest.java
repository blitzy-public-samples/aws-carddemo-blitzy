package com.carddemo.repository;

import com.carddemo.entity.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link CustomerRepository}.
 *
 * <p><strong>Parity.</strong> Replaces the VSAM {@code CUSTDATA} keyed reads of the legacy
 * CardDemo system (copybook {@code app/cpy/CVCUS01Y.cpy}, record {@code CUSTOMER-RECORD},
 * primary-key length 9 at RKP 0 per {@code app/catlg/LISTCAT.txt}) with Spring Data JPA CRUD
 * over the relational {@code customers} table. The test pins the most error-prone aspects of the
 * COBOL&#8594;Java migration for this aggregate, per AAP &sect;0.4.1.2 (repository layer) and the
 * preservation rules of AAP &sect;0.7.3.</p>
 *
 * <p><strong>SSN-as-String (the parity anchor).</strong> The COBOL field {@code CUST-SSN} is
 * declared {@code PIC 9(09)} (numeric), but the Java mapping deliberately stores it as a
 * {@link String} in a {@code VARCHAR(9)} column. A numeric type would silently drop the leading
 * zero of a value such as {@code "020973888"}; this test asserts the leading zero survives a full
 * persist/read round-trip, which is the whole point of the String mapping (AAP &sect;0.6.8 PII and
 * the binding contract). Beyond the single controlled length/equality assertion, the full SSN is
 * never printed or logged.</p>
 *
 * <p><strong>Date mapping.</strong> The COBOL {@code CUST-DOB-YYYY-MM-DD PIC X(10)} maps to a
 * {@link LocalDate} ({@code DATE} column); the test verifies the seeded date of birth is read back
 * as the exact {@link LocalDate}.</p>
 *
 * <p><strong>Fixtures.</strong> Data is provided exclusively by Flyway: {@code V1__schema.sql}
 * builds the schema and {@code V3__seed_master.sql} seeds exactly 50 customers, whose ground-truth
 * is {@code app/data/ASCII/custdata.txt}. The {@code test} profile runs H2 in PostgreSQL-compatible
 * mode (see {@code src/test/resources/application-test.yml}) and Hibernate only validates the
 * Flyway-owned schema.</p>
 *
 * <p><strong>Test configuration.</strong> {@link AutoConfigureTestDatabase} with
 * {@link AutoConfigureTestDatabase.Replace#NONE} keeps the H2 datasource declared by the
 * {@code test} profile (rather than substituting a throwaway embedded database), so the
 * Flyway-seeded rows are present for the assertions below. No shared base class is used and no
 * seed row is mutated, so the seeded state stays deterministic across the suite.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CustomerRepositoryTest {

    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Verifies that the primary anchor (cust_id = 1) is present and that its name fields are
     * read back exactly as seeded by {@code V3__seed_master.sql} (ground-truth
     * {@code app/data/ASCII/custdata.txt}).
     */
    @Test
    void findById_returnsSeededCustomerNames() {
        Optional<Customer> result = customerRepository.findById(1L);
        assertThat(result).isPresent();
        Customer c = result.get();
        assertThat(c.getFirstName()).isEqualTo("Immanuel");
        assertThat(c.getLastName()).isEqualTo("Kessler");
    }

    /**
     * The parity anchor: the SSN must be a 9-character {@link String} whose leading zero is
     * preserved. A numeric mapping of the COBOL {@code PIC 9(09)} field would have dropped the
     * leading zero, so this assertion guards the deliberate String/{@code VARCHAR(9)} mapping.
     */
    @Test
    void findById_ssnPreservesLeadingZeroAsString() {
        Customer c = customerRepository.findById(1L).orElseThrow();
        assertThat(c.getSsn())
                .as("SSN must be a 9-char String preserving the leading zero")
                .isEqualTo("020973888")
                .hasSize(9);
    }

    /**
     * Verifies the {@code CUST-DOB-YYYY-MM-DD X(10)} &#8594; {@link LocalDate} mapping by checking
     * the seeded date of birth round-trips to the exact {@link LocalDate}.
     */
    @Test
    void findById_dobIsLocalDate() {
        Customer c = customerRepository.findById(1L).orElseThrow();
        assertThat(c.getDob()).isEqualTo(LocalDate.of(1961, 6, 8));
    }

    /**
     * Verifies the secondary anchor at the top of the key range (cust_id = 50) so the test
     * exercises both ends of the seeded 50-row dataset.
     */
    @Test
    void findById_lastCustomer() {
        Customer c = customerRepository.findById(50L).orElseThrow();
        assertThat(c.getFirstName()).isEqualTo("Aniya");
        assertThat(c.getLastName()).isEqualTo("Von");
    }

    /**
     * Verifies that a lookup for a non-existent key returns an empty {@link Optional}, mirroring a
     * VSAM "record not found" outcome without raising an exception.
     */
    @Test
    void findById_unknownCustomer_isEmpty() {
        assertThat(customerRepository.findById(99999L)).isEmpty();
    }

    /**
     * Verifies the seeded row count. {@code V3__seed_master.sql} seeds exactly 50 customers
     * (ground-truth {@code app/data/ASCII/custdata.txt}; corroborated by REC-TOTAL=50 for
     * {@code CUSTDATA} in {@code app/catlg/LISTCAT.txt}).
     */
    @Test
    void count_matchesSeedRowCount() {
        // V3__seed_master.sql seeds 50 customers (custdata.txt has 50 rows)
        assertThat(customerRepository.count()).isEqualTo(50L);
    }
}

package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Account;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration (parity) tests for {@link AccountRepository} against the Flyway-seeded
 * Testcontainers PostgreSQL database, asserting VSAM {@code ACCTDAT} KSDS parity
 * (random read by {@code ACCT-ID}).
 *
 * <p>This is a net-new parity-oracle test with no COBOL ancestor: it does not translate a
 * COBOL program but instead verifies that the migrated Spring Data JPA access path reproduces
 * the legacy random-read-by-key behavior and that the seed data decodes to exactly the values
 * carried by the original fixed-width fixture. The assertions are derived directly from the
 * COBOL record layout and the ASCII fixture below.</p>
 *
 * <p>Parity oracles:
 * <ul>
 *   <li>{@code legacy/cpy/CVACT01Y.cpy} (ACCOUNT-RECORD, RECLN 300) &mdash; the record layout.</li>
 *   <li>{@code legacy/data/ASCII/acctdata.txt} (50 fixed-width rows, seeded into the
 *       {@code account} table by {@code V2__reference_data.sql}).</li>
 * </ul>
 * See AAP &sect;0.6.10 (traceability).</p>
 *
 * <p><strong>Read-only.</strong> The {@code account} table is shared, Flyway-seeded state used by
 * the whole {@code *IT} suite, and the {@link AbstractPostgresIntegrationTest base class} declares
 * no {@code @Transactional} boundary (there is no per-test rollback). This test therefore invokes
 * only the inherited read operations {@code count()} and {@code findById(...)} and never mutates the
 * shared table.</p>
 *
 * <p>All Spring Boot test-context wiring &mdash; the application-context bootstrap, the active
 * {@code test} profile, the shared Testcontainers PostgreSQL&nbsp;18 container and the dynamic
 * datasource binding &mdash; is inherited from {@link AbstractPostgresIntegrationTest} and is
 * deliberately not re-declared on this class.</p>
 *
 * <p>Because the string columns are {@code CHAR(n) COLLATE "C"} (see {@code V1__schema.sql}), the
 * JDBC driver returns space-padded values; every {@code String} getter is therefore
 * {@link String#trim() trimmed} before comparison. Every {@link java.math.BigDecimal} assertion uses
 * {@code isEqualByComparingTo} so that it is scale-independent (for example {@code 194.00} compares
 * equal regardless of the stored scale).</p>
 */
class AccountRepositoryIT extends AbstractPostgresIntegrationTest {

    /** Total number of {@code account} rows seeded by {@code V2__reference_data.sql}. */
    private static final long SEEDED_ACCOUNT_COUNT = 50L;

    /** Injected repository under test; only its inherited read methods are exercised. */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * The seed migration loads every row of {@code acctdata.txt}, so the table must contain
     * exactly 50 accounts. A mismatch means the fixture drifted from the seed migration.
     */
    @Test
    @DisplayName("account table seeds exactly 50 rows (acctdata.txt parity)")
    void seededRowCountMatchesFixture() {
        assertThat(accountRepository.count()).isEqualTo(SEEDED_ACCOUNT_COUNT);
    }

    /**
     * Random read by primary key for {@code ACCT-ID = 1} must return the account whose every field
     * decodes to the exact value in row&nbsp;1 of the fixed-width fixture (decoded against
     * {@code CVACT01Y.cpy}). The {@code S9(10)V99} monetary fields carry a trailing zoned-decimal
     * overpunch on the low-order digit encoding the sign; a plus-zero overpunch on the raw digits
     * {@code 00000001940} therefore decodes to {@code 194.00}. This is the core VSAM
     * {@code ACCTDAT} random-read parity check.
     */
    @Test
    @DisplayName("findById(1) returns account 1 with exact CVACT01Y/acctdata.txt values")
    void findByIdReturnsSeededAccountOne() {
        Optional<Account> found = accountRepository.findById(1L);
        assertThat(found).isPresent();

        Account account = found.get();
        assertThat(account.getAcctId()).isEqualTo(1L);
        assertThat(account.getActiveStatus().trim()).isEqualTo("Y");
        assertThat(account.getCurrBal()).isEqualByComparingTo("194.00");
        assertThat(account.getCreditLimit()).isEqualByComparingTo("2020.00");
        assertThat(account.getCashCreditLimit()).isEqualByComparingTo("1020.00");
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo("0.00");
        assertThat(account.getOpenDate()).isEqualTo(LocalDate.of(2014, 11, 20));
        // Getter name preserves the COBOL misspelling ACCT-EXPIRAION-DATE verbatim.
        assertThat(account.getExpiraionDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(account.getReissueDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        // ACCT-ADDR-ZIP data quirk: "A000000000" is exactly 10 characters and fills CHAR(10).
        assertThat(account.getAddrZip().trim()).isEqualTo("A000000000");
        // ACCT-GROUP-ID is blank in the fixture; CHAR(10) pads it to 10 spaces.
        assertThat(account.getGroupId().trim()).isEmpty();
    }

    /**
     * A read for a key that is well outside the seeded range (nine-digit id, valid for the
     * {@code NUMERIC(11)} column but absent from the 50-row fixture) must return an empty
     * {@link Optional}, mirroring the legacy VSAM "record not found" outcome for a random read.
     */
    @Test
    @DisplayName("findById for a non-existent account id returns empty")
    void findByIdMissingReturnsEmpty() {
        assertThat(accountRepository.findById(999_999_999L)).isEmpty();
    }

    /**
     * Every seeded key in the contiguous range {@code 1..50} must be retrievable by primary key.
     * This exercises the {@code NUMERIC(11) <-> Long} identifier mapping across the whole fixture
     * and reinforces the VSAM {@code ACCTDAT} random-read-by-{@code ACCT-ID} parity guarantee beyond
     * the single spot-checked row. It remains strictly read-only (only {@code findById}).
     */
    @Test
    @DisplayName("every seeded account id 1..50 is retrievable by primary key (KSDS random-read parity)")
    void findByIdReturnsEverySeededAccount() {
        for (long acctId = 1L; acctId <= SEEDED_ACCOUNT_COUNT; acctId++) {
            assertThat(accountRepository.findById(acctId))
                    .as("account with acct_id=%d must be present", acctId)
                    .isPresent();
        }
    }
}

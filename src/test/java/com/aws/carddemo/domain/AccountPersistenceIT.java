package com.aws.carddemo.domain;

import com.aws.carddemo.AbstractPostgresIntegrationTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence integration test for the JPA entity {@link Account} against a real,
 * Flyway-migrated PostgreSQL database provisioned by Testcontainers.
 *
 * <p>Whereas the sibling unit test {@code AccountTest} locks the entity's structure by reflection
 * with no database, this test closes the loop at the persistence boundary: it proves that the
 * {@link Account} mapping agrees with the {@code account} table defined by
 * {@code src/main/resources/db/migration/V1__schema.sql} and materialised in the container, that
 * the seed rows loaded by {@code V2__reference_data.sql} are read back faithfully, and that a
 * freshly persisted row round-trips through PostgreSQL without any loss of decimal scale or date
 * value. The class name ends in {@code IT}, so it is executed by the Maven Failsafe plugin (never
 * Surefire) during the {@code integration-test}/{@code verify} phases.</p>
 *
 * <p><strong>COBOL oracle (traceability, AAP &sect;0.6.10):</strong> {@link Account} is migrated
 * one-for-one from the legacy COBOL copybook {@code ACCOUNT-RECORD} (record length 300) defined at
 * {@code legacy/cpy/CVACT01Y.cpy}, which backed the VSAM {@code ACCTDAT} key-sequenced data set.
 * This test anchors the entity to that mainframe contract so the migration cannot silently drift
 * from the source-of-truth record layout.</p>
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1):</strong> All five monetary fields
 * ({@code currBal}, {@code creditLimit}, {@code cashCreditLimit}, {@code currCycCredit},
 * {@code currCycDebit}) are COBOL {@code S9(10)V99} packed decimals mapped to
 * {@link java.math.BigDecimal} over {@code NUMERIC(12,2)} columns. Floating-point types are never
 * used for money. Value equality is asserted with AssertJ {@code isEqualByComparingTo} (which is
 * scale-insensitive), and a separate {@link java.math.BigDecimal#scale()} assertion proves that a
 * persisted value is stored and re-read at exactly scale 2 &mdash; i.e. that the column is genuinely
 * {@code NUMERIC(12,2)} and no precision is lost across the JDBC/Hibernate round trip.</p>
 *
 * <p><strong>Date persistence:</strong> The three former {@code PIC X(10)} character dates
 * ({@code openDate}, {@code expiraionDate}, {@code reissueDate}) are modelled as
 * {@link java.time.LocalDate} over SQL {@code DATE} and are asserted to round-trip to the exact
 * calendar day, both for the seeded row and for a newly persisted row.</p>
 *
 * <p><strong>Preserved misspelling (AAP &sect;0.4.1):</strong> The field {@code expiraionDate} and
 * its column {@code acct_expiraion_date} intentionally retain the misspelling ("EXPIRAION") of the
 * original COBOL field {@code ACCT-EXPIRAION-DATE}. This test deliberately exercises
 * {@link Account#getExpiraionDate()} / {@link Account#setExpiraionDate(java.time.LocalDate)} against
 * the misspelled column so that a future maintainer cannot "correct" the spelling without breaking
 * this test &mdash; and therefore parity with the legacy record.</p>
 *
 * <p><strong>Infrastructure:</strong> extends {@link AbstractPostgresIntegrationTest}, which starts
 * the shared {@code postgres:18-alpine} container and activates the {@code test} profile
 * ({@code ddl-auto=validate} with Flyway applying {@code V0 -> V1 -> V2 -> V3}). The class-level
 * {@link Transactional} annotation wraps each test method in a transaction that is rolled back on
 * completion, so the newly persisted row in
 * {@link #persistAndFindNewAccountPreservesMoneyScaleAndDates()} never leaks into other tests or the
 * seed data. A container-managed {@link EntityManager} (injected via {@link PersistenceContext}) is
 * used directly instead of a Spring Data repository so that the entity&#8596;schema contract is
 * verified without any repository abstraction in between.</p>
 *
 * @see Account
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
class AccountPersistenceIT extends AbstractPostgresIntegrationTest {

    /**
     * Container-managed persistence context bound to the active (rolled-back) test transaction.
     * Injected with {@link PersistenceContext} rather than obtained from a repository so the tests
     * exercise the raw entity&#8596;table mapping directly.
     */
    @PersistenceContext
    private EntityManager em;

    /**
     * Spot-checks the {@code account} row seeded by {@code V2__reference_data.sql} for
     * {@code acctId = 1}, verified against the ASCII fixture {@code app/data/ASCII/acctdata.txt}.
     *
     * <p>Asserts all five {@code BigDecimal(12,2)} money fields by value, all three
     * {@link LocalDate} fields (including the misspelled {@code expiraionDate}), the single-character
     * active status, the fixed-width ZIP, and that the blank disclosure-group id decodes to
     * {@code null} or an all-blank {@code CHAR(10)} string.</p>
     */
    @Test
    void seededAccountOneMatchesSpotCheck() {
        Account account = em.find(Account.class, 1L);

        assertThat(account)
                .as("seeded account acctId=1 must exist (V2__reference_data.sql)")
                .isNotNull();

        // Five BigDecimal(12,2) money fields. isEqualByComparingTo is scale-insensitive, so the
        // value 194.00 matches regardless of any trailing-zero scale differences.
        assertThat(account.getCurrBal()).isEqualByComparingTo(new BigDecimal("194.00"));
        assertThat(account.getCreditLimit()).isEqualByComparingTo(new BigDecimal("2020.00"));
        assertThat(account.getCashCreditLimit()).isEqualByComparingTo(new BigDecimal("1020.00"));
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));

        // Three LocalDate fields, including the preserved misspelling expiraionDate.
        assertThat(account.getOpenDate()).isEqualTo(LocalDate.of(2014, 11, 20));
        assertThat(account.getExpiraionDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(account.getReissueDate()).isEqualTo(LocalDate.of(2025, 5, 20));

        // CHAR(1) active status. strip() is defensive; a length-1 column cannot be space padded.
        assertThat(account.getActiveStatus().strip()).isEqualTo("Y");

        // CHAR(10) ZIP. The value is exactly 10 characters so strip() is a no-op, kept defensive.
        assertThat(account.getAddrZip().strip()).isEqualTo("A000000000");

        // CHAR(10) disclosure-group id. The seed value is blank (''), which PostgreSQL stores as ten
        // spaces, so the decoded value is either null or an all-whitespace string.
        String groupId = account.getGroupId();
        assertThat(groupId == null || groupId.strip().isEmpty())
                .as("seeded acctId=1 group id is blank/space-padded")
                .isTrue();
    }

    /**
     * Persists a brand-new {@link Account} with a non-colliding primary key and verifies that its
     * money fields and dates survive a full PostgreSQL round trip with exact fidelity.
     *
     * <p>The row is written with {@link EntityManager#persist(Object)} and
     * {@link EntityManager#flush()}, the persistence context is then cleared
     * ({@link EntityManager#clear()}) so the subsequent {@link EntityManager#find(Class, Object)}
     * re-reads the values from the database rather than the first-level cache. The re-read
     * {@code currBal} is asserted both by value and by {@link BigDecimal#scale()} to prove the
     * {@code NUMERIC(12,2)} column preserves a scale of 2, and all three dates &mdash; including the
     * misspelled {@code expiraionDate} &mdash; are asserted to round-trip unchanged. The enclosing
     * transaction is rolled back, so this row does not persist beyond the test.</p>
     */
    @Test
    void persistAndFindNewAccountPreservesMoneyScaleAndDates() {
        // 11-digit key (fits NUMERIC(11)) chosen far above the 1..50 seed range so it never collides.
        final long newAcctId = 90000000001L;

        Account account = new Account();
        account.setAcctId(newAcctId);
        account.setActiveStatus("Y");
        account.setCurrBal(new BigDecimal("100.05"));
        account.setCreditLimit(new BigDecimal("5000.00"));
        account.setCashCreditLimit(new BigDecimal("0.00"));
        account.setCurrCycCredit(new BigDecimal("0.00"));
        account.setCurrCycDebit(new BigDecimal("0.00"));
        account.setOpenDate(LocalDate.of(2020, 1, 15));
        account.setExpiraionDate(LocalDate.of(2030, 1, 15));
        account.setReissueDate(LocalDate.of(2025, 1, 15));
        account.setAddrZip("1234567890");
        account.setGroupId("TESTGROUP0");

        em.persist(account);
        em.flush();
        em.clear();

        Account reloaded = em.find(Account.class, newAcctId);

        assertThat(reloaded)
                .as("account persisted with acctId=%d must be re-findable", newAcctId)
                .isNotNull();

        // Value fidelity plus an explicit scale assertion proving the column is NUMERIC(12,2):
        // the re-read scale is dictated by the database column, not by the value that was written.
        assertThat(reloaded.getCurrBal()).isEqualByComparingTo("100.05");
        assertThat(reloaded.getCurrBal().scale())
                .as("NUMERIC(12,2) must round-trip currBal with scale 2")
                .isEqualTo(2);
        assertThat(reloaded.getCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(reloaded.getCreditLimit().scale())
                .as("NUMERIC(12,2) must round-trip creditLimit with scale 2")
                .isEqualTo(2);

        // Dates round-trip to the exact calendar day, including the preserved-misspelling column.
        assertThat(reloaded.getOpenDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(reloaded.getExpiraionDate()).isEqualTo(LocalDate.of(2030, 1, 15));
        assertThat(reloaded.getReissueDate()).isEqualTo(LocalDate.of(2025, 1, 15));
    }
}

package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test for the JPA entity {@link Customer} against a real, Flyway-migrated
 * PostgreSQL database provisioned by Testcontainers.
 *
 * <p>This is a Failsafe integration test ({@code *IT}). It extends
 * {@link AbstractPostgresIntegrationTest}, which starts a single shared {@code postgres:18-alpine}
 * container, activates the {@code test} profile, and lets Flyway apply the versioned migrations in
 * order ({@code V0__spring_batch_metadata.sql} &rarr; {@code V1__schema.sql} &rarr;
 * {@code V2__reference_data.sql} &rarr; {@code V3__indexes.sql}) with Hibernate
 * {@code ddl-auto=validate}. It therefore exercises the entity against exactly the same DDL and
 * reference data as production &mdash; no in-memory substitute and no auto-generated schema. The
 * class is {@link Transactional @Transactional} so every test rolls back and leaves the shared
 * container untouched for sibling tests.</p>
 *
 * <p><strong>COBOL provenance (dual-source consolidation):</strong> the {@link Customer} entity is
 * the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5.16 migration target for the legacy IBM&nbsp;z/OS
 * VSAM {@code CUSTDAT} KSDS. It is consolidated from <em>two</em> copybook sources that declare the
 * identical 500-byte {@code CUSTOMER-RECORD} layout, retained read-only under the {@code legacy/}
 * tree:</p>
 * <ul>
 *   <li>{@code legacy/cpy/CVCUS01Y.cpy} &mdash; {@code CUSTOMER-RECORD}, RECLN 500
 *       (date-of-birth field {@code CUST-DOB-YYYY-MM-DD PIC X(10)})</li>
 *   <li>{@code legacy/cpy/CUSTREC.cpy} &mdash; {@code CUSTOMER-RECORD}, RECLN 500
 *       (same field named {@code CUST-DOB-YYYYMMDD PIC X(10)})</li>
 * </ul>
 * <p>The decision to consolidate the two copybooks into the single {@code Customer} entity is
 * recorded in {@code docs/decision-log.md} (Technical Specification &sect;0.4.1) and is not
 * re-argued here.</p>
 *
 * <p><strong>PII round-trip vs masking (Technical Specification &sect;0.6.10):</strong> the customer
 * record carries personally identifiable information &mdash; in particular the Social Security
 * Number ({@code cust_ssn}, mapped as {@code Long}) and the date of birth ({@code cust_dob}, mapped
 * as {@link LocalDate}). This test locks two complementary contracts: (1) that PII is <em>stored and
 * round-trips intact</em> through PostgreSQL &mdash; the masking is purely a {@link Customer#toString()}
 * concern and must never corrupt the persisted data; and (2) that {@link Customer#toString()} still
 * refuses to emit the raw SSN or ISO date of birth even for a database-loaded managed entity, so PII
 * cannot leak into application logs.</p>
 *
 * <p>All character columns in the {@code customer} table are fixed-width {@code CHAR(n) COLLATE "C"}
 * (COBOL {@code PIC X(n)}); PostgreSQL right-pads them with spaces, so every text comparison strips
 * the trailing pad before asserting.</p>
 *
 * @see Customer
 * @see AbstractPostgresIntegrationTest
 * @see CustomerTest
 */
@Transactional
class CustomerPersistenceIT extends AbstractPostgresIntegrationTest {

    /**
     * JPA entity manager bound to the Testcontainers PostgreSQL datasource. A raw
     * {@link EntityManager} (rather than a Spring Data repository) is used deliberately so the test
     * asserts the entity&nbsp;&harr;&nbsp;schema mapping contract directly.
     */
    @PersistenceContext
    private EntityManager em;

    /**
     * Verifies that the {@code custId=1} row seeded by {@code V2__reference_data.sql} loads and
     * matches the known reference profile (validated against {@code app/data/ASCII/custdata.txt}),
     * including that the PII fields carry their true typed values.
     */
    @Test
    void seededCustomerOneMatchesSpotCheck() {
        Customer c = em.find(Customer.class, 1L);
        assertThat(c)
                .as("seeded customer custId=1 must exist (loaded from V2 reference data)")
                .isNotNull();

        // Text columns are fixed-width CHAR(n) COLLATE "C"; strip the trailing pad before comparing.
        assertThat(c.getFirstName().strip()).as("first name").isEqualTo("Immanuel");
        assertThat(c.getMiddleName().strip()).as("middle name").isEqualTo("Madeline");
        assertThat(c.getLastName().strip()).as("last name").isEqualTo("Kessler");
        assertThat(c.getAddrStateCd().strip()).as("state code").isEqualTo("NC");
        assertThat(c.getAddrCountryCd().strip()).as("country code").isEqualTo("USA");

        // PII and numeric fields persist as their true typed values.
        assertThat(c.getSsn()).as("SSN (PII) numeric value").isEqualTo(20973888L);
        assertThat(c.getDateOfBirth()).as("date of birth (PII)").isEqualTo(LocalDate.of(1961, 6, 8));
        assertThat(c.getFicoCreditScore()).as("FICO credit score").isEqualTo(274);
    }

    /**
     * Persists a brand-new customer and reloads it from the database (after a flush + clear that
     * forces a genuine round-trip through PostgreSQL), asserting that the PII fields &mdash; SSN and
     * date of birth &mdash; are stored and returned intact. Rolled back by the class-level
     * {@link Transactional @Transactional}.
     */
    @Test
    void persistAndFindNewCustomerRoundTripsPii() {
        // Identifier note (deliberate deviation, documented for Explainability): the file
        // specification names the literal id 90000000001, but cust_id is NUMERIC(9) (COBOL
        // CUST-ID PIC 9(09)), whose maximum magnitude is 999,999,999. Persisting an 11-digit value
        // raises PostgreSQL "numeric field overflow" and yields a false-negative failure -- which
        // the specification itself warns against. We therefore use the maximum in-range,
        // non-colliding id 999,999,999 (the V2 seed occupies custId 1..50), preserving the intent:
        // a brand-new customer whose PII round-trips intact.
        final long newCustId = 999_999_999L;

        Customer fresh = new Customer();
        fresh.setCustId(newCustId);
        fresh.setFirstName("Parity");
        fresh.setMiddleName("Roundtrip");
        fresh.setLastName("Probe");
        fresh.setAddrStateCd("VA");
        fresh.setAddrCountryCd("USA");
        fresh.setSsn(123456789L);
        fresh.setDateOfBirth(LocalDate.of(1990, 2, 3));
        fresh.setFicoCreditScore(650);

        em.persist(fresh);
        em.flush();
        em.clear();

        Customer reloaded = em.find(Customer.class, newCustId);
        assertThat(reloaded)
                .as("newly persisted customer must be retrievable after flush + clear")
                .isNotNull();
        assertThat(reloaded.getSsn())
                .as("SSN (PII) must persist and round-trip intact (masking is only in toString)")
                .isEqualTo(123456789L);
        assertThat(reloaded.getDateOfBirth())
                .as("date of birth (PII) must persist and round-trip intact")
                .isEqualTo(LocalDate.of(1990, 2, 3));
    }

    /**
     * Confirms that {@link Customer#toString()} still masks PII end-to-end, even for a managed
     * entity loaded from the database: neither the raw SSN digits nor the ISO date of birth of the
     * seeded {@code custId=1} row appear in the diagnostic string.
     */
    @Test
    void toStringStillMasksPiiOnManagedEntity() {
        Customer c = em.find(Customer.class, 1L);
        assertThat(c)
                .as("seeded customer custId=1 must exist (loaded from V2 reference data)")
                .isNotNull();

        String rendered = c.toString();
        assertThat(rendered)
                .as("toString() must not leak the raw SSN, even for a DB-loaded managed entity")
                .doesNotContain("20973888");
        assertThat(rendered)
                .as("toString() must not leak the ISO date of birth, even for a DB-loaded entity")
                .doesNotContain("1961-06-08");
    }
}

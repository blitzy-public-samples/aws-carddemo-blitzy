package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test for the composite-key interest-rate entity
 * {@link DisclosureGroup}, run against a real, Flyway-migrated PostgreSQL
 * database provisioned by Testcontainers.
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.10):</strong> the entity under
 * test is migrated one-for-one from the COBOL copybook
 * {@code DIS-GROUP-RECORD} (RECLN 50) defined at {@code legacy/cpy/CVTRA02Y.cpy}
 * (retained read-only), which described the VSAM KSDS {@code DISCGRP} file in
 * the mainframe AWS CardDemo application. This test is the persistence-parity
 * oracle proving that the relational round-trip preserves both the key
 * semantics and the numeric fidelity of that legacy record.</p>
 *
 * <p><strong>Composite-key persistence (AAP &sect;0.6.2).</strong> The legacy
 * {@code DIS-GROUP-KEY} is a three-part VSAM key &mdash; account-group id
 * ({@code DIS-ACCT-GROUP-ID PIC X(10)}) + transaction-type code
 * ({@code DIS-TRAN-TYPE-CD PIC X(02)}) + transaction-category code
 * ({@code DIS-TRAN-CAT-CD PIC 9(04)}). It is reproduced as a JPA composite
 * primary key via {@link DisclosureGroup.DisclosureGroupId}. Both test methods
 * exercise a full round-trip keyed on that three-part id, confirming the
 * {@code @IdClass} mapping and the {@code PRIMARY KEY} in
 * {@code V1__schema.sql} agree.</p>
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> The disclosure
 * interest rate ({@code DIS-INT-RATE PIC S9(04)V99}) maps to
 * {@code NUMERIC(6,2)} and is held as a {@link java.math.BigDecimal} (never a
 * floating-point type). Exactness matters because this rate is the multiplier
 * consumed by the interest-calculation batch job (migrated from
 * {@code CBACT04C}) in the fixed-scale computation
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. These tests therefore assert
 * both the numeric value and that the persisted scale is exactly {@code 2},
 * so a rate stored as {@code 24.99} never degrades to {@code 24.9} or
 * {@code 24.990}.</p>
 *
 * <p><strong>Infrastructure.</strong> Extends
 * {@link AbstractPostgresIntegrationTest}, which starts a single shared
 * {@code postgres:18-alpine} container, activates the {@code test} profile
 * (Hibernate {@code ddl-auto=validate}), and lets Flyway apply the versioned
 * migrations in order &mdash; {@code V0} (batch metadata) &rarr; {@code V1}
 * (schema) &rarr; {@code V2} (reference/seed data) &rarr; {@code V3}
 * (indexes). No Spring or container configuration is redeclared here.
 * Interaction is via the JPA {@link EntityManager} directly (no repository), so
 * the mapping itself &mdash; not a repository abstraction &mdash; is the thing
 * verified.</p>
 *
 * <p><strong>Isolation.</strong> The class is annotated {@link Transactional}
 * so each test runs inside a transaction that the Spring TestContext framework
 * rolls back on completion. The row inserted by
 * {@link #persistAndFindNewGroupWithExactScale()} therefore never persists
 * beyond the test, leaving the seeded reference data untouched for other tests
 * in the suite. (The abstract base deliberately declares no transaction
 * boundary; this concrete persistence test adds its own.)</p>
 *
 * <p><strong>Plugin routing.</strong> The {@code IT} suffix routes this class
 * to the Maven Failsafe plugin (integration-test / verify phases), disjoint
 * from the Surefire {@code *Test} unit set.</p>
 *
 * @see DisclosureGroup
 * @see DisclosureGroup.DisclosureGroupId
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
class DisclosureGroupPersistenceIT extends AbstractPostgresIntegrationTest {

    /**
     * JPA entity manager bound to the current (rolled-back) test transaction.
     * Injected with {@link PersistenceContext} so the mapping is exercised
     * directly, without an intervening Spring Data repository.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Verifies that a row seeded by {@code V2__reference_data.sql} loads by its
     * three-part composite key with its interest rate intact.
     *
     * <p>The disclosure group {@code (acctGroupId="A000000000",
     * tranTypeCd="01", tranCatCd=1)} is seeded with an interest rate of
     * {@code 15.00}. Looking it up through the composite {@code @IdClass}
     * confirms the key mapping resolves the seeded row, and comparing the rate
     * with {@link BigDecimal#compareTo(BigDecimal)} semantics
     * ({@code isEqualByComparingTo}) confirms the {@code NUMERIC(6,2)} value
     * survives the JDBC/Hibernate round-trip exactly.</p>
     */
    @Test
    void seededRateLoadsByCompositeKey() {
        DisclosureGroup.DisclosureGroupId key =
                new DisclosureGroup.DisclosureGroupId("A000000000", "01", 1);

        DisclosureGroup found = entityManager.find(DisclosureGroup.class, key);

        assertThat(found)
                .as("seeded disclosure group (A000000000, 01, 1) must load by composite key")
                .isNotNull();
        assertThat(found.getIntRate())
                .as("seeded interest rate must equal 15.00")
                .isEqualByComparingTo(new BigDecimal("15.00"));
    }

    /**
     * Verifies that a newly persisted disclosure group round-trips through the
     * database preserving its exact fixed-scale interest rate.
     *
     * <p>A new entity with a non-colliding composite key
     * {@code (acctGroupId="ZZTESTGRP0", tranTypeCd="99", tranCatCd=9999)} and an
     * interest rate of {@code 24.99} is persisted and flushed; the persistence
     * context is then cleared to force a fresh database read on
     * {@link EntityManager#find(Class, Object)}. The reloaded rate must both
     * compare equal to {@code 24.99} and carry a scale of exactly {@code 2},
     * proving the {@code NUMERIC(6,2)} column preserves COBOL
     * {@code PIC S9(04)V99} decimal fidelity end to end. The insert is rolled
     * back with the surrounding test transaction.</p>
     */
    @Test
    void persistAndFindNewGroupWithExactScale() {
        DisclosureGroup group = new DisclosureGroup();
        group.setAcctGroupId("ZZTESTGRP0");
        group.setTranTypeCd("99");
        group.setTranCatCd(9999);
        group.setIntRate(new BigDecimal("24.99"));

        entityManager.persist(group);
        entityManager.flush();
        entityManager.clear();

        DisclosureGroup.DisclosureGroupId key =
                new DisclosureGroup.DisclosureGroupId("ZZTESTGRP0", "99", 9999);
        DisclosureGroup reloaded = entityManager.find(DisclosureGroup.class, key);

        assertThat(reloaded)
                .as("newly persisted disclosure group must reload by composite key")
                .isNotNull();
        assertThat(reloaded.getIntRate())
                .as("persisted interest rate must equal 24.99")
                .isEqualByComparingTo(new BigDecimal("24.99"));
        assertThat(reloaded.getIntRate().scale())
                .as("persisted interest rate must retain NUMERIC(6,2) scale of 2")
                .isEqualTo(2);
    }
}

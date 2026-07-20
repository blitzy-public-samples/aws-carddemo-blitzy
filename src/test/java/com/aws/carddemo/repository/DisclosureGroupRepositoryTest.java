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

import java.math.BigDecimal;

import com.aws.carddemo.domain.DisclosureGroup;

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
 * Testcontainers-backed Spring Data JPA integration test for
 * {@link DisclosureGroupRepository}, the set-based replacement for the COBOL
 * VSAM keyed access to the {@code DISCGRP} KSDS (copybook {@code CVTRA02Y.cpy},
 * record {@code DIS-GROUP-RECORD}).
 *
 * <p><strong>What is verified (data-tier parity).</strong> The legacy
 * interest-calculation batch program {@code CBACT04C}
 * ({@code legacy/cbl/CBACT04C.cbl}, paragraph {@code 1200-GET-INTEREST-RATE})
 * obtains the annual interest rate for an account group by issuing a keyed
 * {@code READ DISCGRP-FILE} on the 16-byte three-part key
 * {@code DIS-GROUP-KEY} ({@code DIS-ACCT-GROUP-ID} + {@code DIS-TRAN-TYPE-CD}
 * + {@code DIS-TRAN-CAT-CD}). This test asserts the Java equivalent of that
 * single keyed read &mdash; {@link DisclosureGroupRepository#findById(Object)}
 * on the full composite key {@link DisclosureGroup.DisclosureGroupId} &mdash;
 * plus the exact {@code DECIMAL(6,2)} / {@code BigDecimal} scale-2 fidelity of
 * the rate that feeds the parity-critical monthly-interest computation
 * (AAP &sect;0.4.3, &sect;0.7.1 H3, &sect;0.9.2).
 *
 * <p><strong>What is deliberately NOT verified here.</strong> The interest
 * formula {@code WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * ({@code CBACT04C} L464-465) and the {@code DEFAULT}/{@code ZEROAPR}
 * group-fallback SELECTION performed on a {@code DISCGRP-STATUS = '23'}
 * not-found ({@code CBACT04C} paragraph {@code 1200-A-GET-DEFAULT-INT-RATE})
 * are business/orchestration concerns owned by the interest-calculation
 * service/batch layer and are exercised by the batch/service test packages.
 * This repository slice is a thin data-access contract: it proves only the
 * <em>rate lookup by key</em> and the <em>scale-2 persistence</em> of the rate.
 * The empty result of {@link #findById_absentCompositeKey_returnsEmpty()}
 * models the {@code '23'} NOTFND outcome that DRIVES that service-side
 * fallback.
 *
 * <p><strong>Harness.</strong> {@link DataJpaTest} loads only the JPA slice and
 * runs each test in a transaction that is rolled back on completion, so the
 * synthetic rows inserted here never leak between tests or persist beyond the
 * run. A managed PostgreSQL 16 container is wired as the datasource via
 * {@link ServiceConnection} (no {@code @DynamicPropertySource}, no hardcoded
 * credentials &mdash; AAP &sect;0.8.1 / &sect;0.9.3);
 * {@link AutoConfigureTestDatabase.Replace#NONE} keeps that real container
 * datasource in place. Flyway applies the production migrations
 * ({@code V1__schema.sql} then {@code V2__reference_data.sql}) against the
 * container first, after which Hibernate validates the {@link DisclosureGroup}
 * mapping (including the {@code @EmbeddedId} composite key and the
 * {@code DECIMAL(6,2)} rate column) under {@code ddl-auto: validate}.
 *
 * <p><strong>Seed awareness.</strong> {@code V2__reference_data.sql} commits the
 * disclosure-group reference rows (the {@code A000000000}, {@code DEFAULT} and
 * {@code ZEROAPR} families) before any test runs, so the table is never empty.
 * To keep assertions deterministic this test uses synthetic, non-colliding keys
 * (group id {@code ZZTESTGRP0} / {@code NOSUCHGRP0}) that cannot clash with the
 * seeded reference data rather than mutating the shared seed rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class DisclosureGroupRepositoryTest {

    /**
     * Shared PostgreSQL 16 container providing the integration-test datasource.
     * {@link ServiceConnection} publishes its JDBC coordinates to the Spring
     * context so no connection string or credential is ever hardcoded.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Repository under test (keyed access to {@code disclosure_group}). */
    private final DisclosureGroupRepository repository;

    /**
     * JPA test helper used to clear the persistence context so lookups re-read
     * from the database and reflect the {@code DECIMAL(6,2)} column exactly.
     */
    private final TestEntityManager entityManager;

    /**
     * Constructor injection of the JPA-slice collaborators (no field injection,
     * per AAP &sect;0.4.2 / &sect;0.6.4).
     *
     * @param repository    the repository under test
     * @param entityManager the JPA test entity manager
     */
    DisclosureGroupRepositoryTest(DisclosureGroupRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * A disclosure-group row persisted by its full three-part composite key is
     * retrievable by that same key &mdash; the direct analogue of the
     * {@code CBACT04C} keyed {@code READ DISCGRP-FILE} on {@code DIS-GROUP-KEY}.
     */
    @Test
    void saveAndFindByCompositeId_roundTrips() {
        DisclosureGroup.DisclosureGroupId key =
                new DisclosureGroup.DisclosureGroupId("ZZTESTGRP0", "ZZ", 9999);

        repository.saveAndFlush(new DisclosureGroup(key, new BigDecimal("12.34")));
        // Detach everything so findById issues a fresh SELECT against the DB.
        entityManager.clear();

        assertThat(repository.findById(key)).isPresent();
    }

    /**
     * The interest rate round-trips through the {@code DECIMAL(6,2)} column at
     * exactly scale 2. Both a fractional rate ({@code 12.34}) and a whole-number
     * rate ({@code 5.00}) are re-read from the database and asserted by VALUE
     * (via {@code isEqualByComparingTo}, which is scale-insensitive) and by
     * SCALE (an explicit {@code scale() == 2} check), proving the column
     * normalizes the stored rate to two fractional digits. Scale-sensitive
     * {@code isEqualTo} is intentionally avoided, and the {@code BigDecimal}
     * {@code String} constructor is used throughout so no binary
     * floating-point rounding is ever introduced (AAP &sect;0.7.1 H3).
     */
    @Test
    void intRatePersistsAtScale2() {
        DisclosureGroup.DisclosureGroupId fractionalKey =
                new DisclosureGroup.DisclosureGroupId("ZZTESTGRP0", "ZZ", 9999);
        repository.saveAndFlush(new DisclosureGroup(fractionalKey, new BigDecimal("12.34")));

        DisclosureGroup.DisclosureGroupId wholeKey =
                new DisclosureGroup.DisclosureGroupId("ZZTESTGRP0", "ZZ", 5000);
        repository.saveAndFlush(new DisclosureGroup(wholeKey, new BigDecimal("5.00")));

        // Force both rows to be re-read from the database, not the 1st-level cache.
        entityManager.clear();

        BigDecimal fractionalRate = repository.findById(fractionalKey).orElseThrow().getIntRate();
        assertThat(fractionalRate).isEqualByComparingTo("12.34");
        assertThat(fractionalRate.scale()).isEqualTo(2);

        BigDecimal wholeRate = repository.findById(wholeKey).orElseThrow().getIntRate();
        assertThat(wholeRate).isEqualByComparingTo("5.00");
        assertThat(wholeRate.scale()).isEqualTo(2);
    }

    /**
     * A lookup on a composite key that is not present returns an empty result.
     * This is the Java equivalent of the {@code DISCGRP-STATUS = '23'} not-found
     * condition in {@code CBACT04C}'s {@code 1200-GET-INTEREST-RATE}, which is
     * what triggers the service-side {@code DEFAULT}-group fallback (owned by
     * the batch/service layer, not this repository).
     */
    @Test
    void findById_absentCompositeKey_returnsEmpty() {
        DisclosureGroup.DisclosureGroupId absentKey =
                new DisclosureGroup.DisclosureGroupId("NOSUCHGRP0", "Q9", 4242);

        assertThat(repository.findById(absentKey)).isEmpty();
    }

    /**
     * The disclosure-group reference data seeded by {@code V2__reference_data.sql}
     * (including the {@code DEFAULT} group used by the interest-calc fallback) is
     * present before any test inserts its own rows. The assertion is intentionally
     * tolerant &mdash; it documents that the seed loaded without pinning the exact
     * row count, which is owned by the migration.
     */
    @Test
    void referenceDataSeededByFlywayV2IsPresent() {
        assertThat(repository.count()).isGreaterThan(0);
    }
}

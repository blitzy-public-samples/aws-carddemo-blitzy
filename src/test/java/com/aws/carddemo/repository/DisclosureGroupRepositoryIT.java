package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.DisclosureGroup;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link DisclosureGroupRepository} against the Flyway-seeded
 * Testcontainers PostgreSQL, asserting VSAM {@code DISCGRP} parity via the composite key
 * (account-group id + transaction-type code + transaction-category code) and exact
 * {@code BigDecimal} interest-rate values.
 *
 * <p>These are {@code *IT} tests that run in the Failsafe / {@code verify} phase against the
 * shared PostgreSQL 18 container started by {@link AbstractPostgresIntegrationTest}, which applies
 * the versioned Flyway migrations ({@code V1__schema.sql} then {@code V2__reference_data.sql}) so
 * the {@code disclosure_group} table is materialized and seeded exactly as in production. The
 * repository under test replaces the legacy VSAM {@code DISCGRP} KSDS random {@code READ} by
 * {@code DIS-GROUP-KEY} that fed the interest-calculation batch program {@code CBACT04C}
 * (see AAP &sect;0.6.1, &sect;0.6.2).</p>
 *
 * <p><strong>Parity oracles</strong> (cited per AAP &sect;0.6.10):
 * <ul>
 *   <li>{@code legacy/cpy/CVTRA02Y.cpy} &mdash; {@code DIS-GROUP-RECORD}, RECLN 50
 *       ({@code DIS-ACCT-GROUP-ID X(10)} + {@code DIS-TRAN-TYPE-CD X(02)} +
 *       {@code DIS-TRAN-CAT-CD 9(04)} + {@code DIS-INT-RATE S9(04)V99} + {@code FILLER X(28)}).</li>
 *   <li>{@code legacy/data/ASCII/discgrp.txt} &mdash; 51 fixed-width rows, seeded into
 *       {@code disclosure_group} by {@code V2__reference_data.sql}.</li>
 * </ul>
 *
 * <p><strong>Read-only contract.</strong> {@link AbstractPostgresIntegrationTest} declares no
 * {@code @Transactional} rollback boundary and the seeded table is shared across the whole
 * {@code *IT} suite, so these tests exercise only the non-mutating inherited operations
 * ({@code count()}, {@code findById(..)}, {@code findAll()}) and never mutate the shared seed.</p>
 *
 * <p><strong>Composite-key width note.</strong> {@code dis_acct_group_id} is a fixed-width
 * {@code CHAR(10)} and {@code dis_tran_type_cd} a {@code CHAR(2)}. {@code findById} is therefore
 * used only with key components that fill those widths exactly ({@code "A000000000"},
 * {@code "01"}, {@code "ZZZZZZZZZZ"}, {@code "99"}); the shorter {@code "ZEROAPR"} group id
 * (7 characters) is asserted through a {@link List#stream() stream} filter over {@code findAll()}
 * on the trimmed id, avoiding a {@code bpchar}-vs-bound-parameter trailing-space mismatch.</p>
 */
class DisclosureGroupRepositoryIT extends AbstractPostgresIntegrationTest {

    /** Repository under test; injected from the Flyway-migrated, container-backed context. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /**
     * The seed loads exactly the 51 fixed-width rows of {@code legacy/data/ASCII/discgrp.txt}
     * into {@code disclosure_group}, preserving the legacy {@code DISCGRP} row count.
     */
    @Test
    @DisplayName("disclosure_group seeds exactly 51 rows (discgrp.txt parity)")
    void seededRowCountMatchesFixture() {
        assertThat(disclosureGroupRepository.count()).isEqualTo(51L);
    }

    /**
     * A random read by the full composite key returns the first seeded row and its exact
     * {@code DIS-INT-RATE}. Row 1 of {@code discgrp.txt} decodes against {@code CVTRA02Y.cpy}
     * &mdash; group {@code A000000000}, type {@code 01}, category {@code 1}, and a rate field of
     * digits {@code 00150} followed by a {@code +0} overpunch sign byte (the character &#123;)
     * &mdash; to an interest rate of {@code 15.00}. The rate is compared with
     * {@code isEqualByComparingTo} so scale differences never mask a value mismatch, guarding the
     * {@code BigDecimal} fidelity the interest-calc job depends on.
     */
    @Test
    @DisplayName("findById(A000000000, 01, 1) returns interest rate 15.00")
    void findByCompositeKeyReturnsSeededRowOne() {
        Optional<DisclosureGroup> found = disclosureGroupRepository.findById(
                new DisclosureGroup.DisclosureGroupId("A000000000", "01", 1));
        assertThat(found).isPresent();
        assertThat(found.get().getIntRate()).isEqualByComparingTo("15.00");
    }

    /**
     * The fixture defines exactly three account-groups &mdash; {@code A000000000},
     * {@code DEFAULT}, and {@code ZEROAPR} (17 rows each, 51 total) &mdash; and every
     * {@code ZEROAPR} row carries a {@code 0.00} interest rate. The {@code ZEROAPR} rows are
     * located through a stream filter on the trimmed group id (not {@code findById}) because the
     * 7-character id does not fill the {@code CHAR(10)} key column.
     */
    @Test
    @DisplayName("exactly 3 disclosure groups exist and every ZEROAPR row has interest rate 0.00")
    void groupsAndZeroAprParity() {
        List<DisclosureGroup> all = disclosureGroupRepository.findAll();
        assertThat(all).hasSize(51);
        assertThat(all).extracting(d -> d.getAcctGroupId().trim())
                .containsOnly("A000000000", "DEFAULT", "ZEROAPR");
        List<DisclosureGroup> zeroApr = all.stream()
                .filter(d -> "ZEROAPR".equals(d.getAcctGroupId().trim()))
                .toList();
        assertThat(zeroApr).isNotEmpty();
        assertThat(zeroApr).allSatisfy(
                d -> assertThat(d.getIntRate()).isEqualByComparingTo("0.00"));
    }

    /**
     * A read by a composite key that matches no seeded row yields an empty {@link Optional},
     * mirroring the legacy {@code NOTFND}/{@code FILE STATUS 23} outcome for an absent
     * {@code DIS-GROUP-KEY}. The chosen components ({@code "ZZZZZZZZZZ"} 10 chars, {@code "99"}
     * 2 chars) fill the {@code CHAR} key widths exactly so the miss is genuine.
     */
    @Test
    @DisplayName("findById for an unknown group key returns empty")
    void findByMissingCompositeKeyReturnsEmpty() {
        assertThat(disclosureGroupRepository.findById(
                new DisclosureGroup.DisclosureGroupId("ZZZZZZZZZZ", "99", 9999))).isEmpty();
    }
}

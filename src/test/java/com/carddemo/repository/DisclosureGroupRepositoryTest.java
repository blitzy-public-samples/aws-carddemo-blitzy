package com.carddemo.repository;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroup.DisclosureGroupId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link DisclosureGroupRepository}.
 *
 * <p><strong>Parity.</strong> Replaces the VSAM {@code DISCGRP} composite-key reads (copybook
 * {@code app/cpy/CVTRA02Y.cpy}, {@code DIS-GROUP-RECORD}; key length 16 per
 * {@code app/catlg/LISTCAT.txt}) that supply interest rates to {@code app/cbl/CBACT04C.cbl}
 * (ported as {@code InterestCalculationService}). The disclosure-group table is the
 * per-group/per-transaction-type/per-transaction-category interest-rate lookup keyed by the
 * three-part {@link jakarta.persistence.EmbeddedId @EmbeddedId}
 * {@code (group_id, tran_type_cd, tran_cat_cd)}.</p>
 *
 * <p>The legacy {@code CBACT04C} paragraph {@code 1200-GET-INTEREST-RATE} reads {@code DISCGRP}
 * by the account's group id and, on a not-found (VSAM status {@code 23}), falls back to the
 * {@code 'DEFAULT'} group ({@code 1200-A-GET-DEFAULT-INT-RATE}). That fallback orchestration lives
 * in the service layer, <em>not</em> in the repository (AAP &sect;0.3.2, &sect;0.6.3); this test
 * therefore exercises only the raw composite-key CRUD that the fallback is built on. Specifically
 * it confirms that:</p>
 * <ul>
 *   <li>a keyed read returns the seeded rate for the {@code DEFAULT} group;</li>
 *   <li>a <strong>group-specific</strong> rate differs from the {@code DEFAULT} group's rate for
 *       the same {@code (tran_type_cd, tran_cat_cd)} pair (proving group-specific resolution, not
 *       merely DEFAULT lookup); and</li>
 *   <li>a missing key returns an empty {@link Optional} &mdash; the precondition that drives the
 *       service's DEFAULT fallback.</li>
 * </ul>
 *
 * <p>Data is seeded deterministically by Flyway {@code V2__seed_reference.sql} (51 disclosure-group
 * rows = 3 groups &times; 17 keys; ground-truth {@code app/data/ASCII/discgrp.txt}). Because the
 * rate is modelled as a {@link BigDecimal} mapped to {@code NUMERIC(6,2)}, every rate assertion uses
 * AssertJ's {@code isEqualByComparingTo} (value comparison) rather than {@code isEqualTo} (which is
 * scale-sensitive and would fail on {@code 15.0} vs {@code 15.00}) &mdash; preserving the exact
 * financial-arithmetic mandate of AAP &sect;0.7.1.</p>
 *
 * <p><strong>Test conventions.</strong> {@link AutoConfigureTestDatabase}{@code (replace = NONE)}
 * keeps the H2 datasource configured by {@code application-test.yml} (PostgreSQL-compat mode) so the
 * PostgreSQL-dialect Flyway migrations and seeds run unchanged; {@link ActiveProfiles}{@code ("test")}
 * selects that profile. No shared base class is used &mdash; these annotations are repeated on every
 * repository slice test.</p>
 *
 * @see DisclosureGroupRepository
 * @see DisclosureGroup
 * @see DisclosureGroup.DisclosureGroupId
 * @see <a href="file:app/cpy/CVTRA02Y.cpy">CVTRA02Y.cpy &mdash; DIS-GROUP-RECORD layout</a>
 * @see <a href="file:app/cbl/CBACT04C.cbl">CBACT04C.cbl &mdash; rate resolution + DEFAULT fallback (context)</a>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DisclosureGroupRepositoryTest {

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /**
     * The {@code DEFAULT} group's purchase rate ({@code tran_type_cd = '01'},
     * {@code tran_cat_cd = 1}) is the seeded {@code 15.00}. Verifies a keyed composite-id read
     * returns the expected {@link BigDecimal} rate by numeric value.
     */
    @Test
    void findById_defaultGroup_purchaseRate() {
        Optional<DisclosureGroup> result =
                disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", 1));
        assertThat(result).isPresent();
        assertThat(result.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("15.00"));
    }

    /**
     * The {@code DEFAULT} group's adjustment rate ({@code tran_type_cd = '07'},
     * {@code tran_cat_cd = 1}) is the seeded {@code 0.00}. Establishes the DEFAULT-group baseline
     * that the next test contrasts against the group-specific rate.
     */
    @Test
    void findById_defaultGroup_adjustmentRateIsZero() {
        Optional<DisclosureGroup> result =
                disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "07", 1));
        assertThat(result).isPresent();
        assertThat(result.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /**
     * Proves <strong>group-specific</strong> rate resolution: for the same
     * {@code (tran_type_cd = '07', tran_cat_cd = 1)} pair, group {@code A000000000} carries
     * {@code 15.00} while group {@code DEFAULT} carries {@code 0.00}. The two rates are therefore
     * unequal &mdash; demonstrating that the lookup distinguishes a specific group from the DEFAULT
     * group (the very condition that makes the service's DEFAULT fallback meaningful).
     */
    @Test
    void findById_specificGroupRateDiffersFromDefault() {
        // A000000000 / 07 / 1 == 15.00, whereas DEFAULT / 07 / 1 == 0.00 -> group-specific resolution
        DisclosureGroup specific =
                disclosureGroupRepository.findById(new DisclosureGroupId("A000000000", "07", 1)).orElseThrow();
        DisclosureGroup dflt =
                disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", "07", 1)).orElseThrow();
        assertThat(specific.getDisIntRate()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(dflt.getDisIntRate()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(specific.getDisIntRate()).isNotEqualByComparingTo(dflt.getDisIntRate());
    }

    /**
     * The {@code ZEROAPR} group's purchase rate ({@code tran_type_cd = '01'},
     * {@code tran_cat_cd = 1}) is the seeded {@code 0.00} &mdash; a distinct group whose rate
     * happens to match DEFAULT's adjustment rate, confirming independent per-group seeding.
     */
    @Test
    void findById_zeroAprGroup_isZero() {
        Optional<DisclosureGroup> result =
                disclosureGroupRepository.findById(new DisclosureGroupId("ZEROAPR", "01", 1));
        assertThat(result).isPresent();
        assertThat(result.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /**
     * A key that does not exist in the seed returns an empty {@link Optional}. This empty-on-miss
     * behavior is the precondition the {@code InterestCalculationService} relies on to trigger its
     * {@code 'DEFAULT'}-group fallback (legacy {@code CBACT04C} VSAM status {@code 23} path).
     */
    @Test
    void findById_unknownGroupKey_isEmpty() {
        assertThat(disclosureGroupRepository.findById(new DisclosureGroupId("NOPE", "99", 99)))
                .isEmpty();
    }

    /**
     * The reference seed {@code V2__seed_reference.sql} loads exactly 51 disclosure-group rows
     * (3 groups &times; 17 keys), mirroring {@code app/data/ASCII/discgrp.txt}. Guards against
     * seed drift / partial migration.
     */
    @Test
    void count_matchesSeedRowCount() {
        // V2__seed_reference.sql seeds 51 disclosure-group rows (3 groups x 17 keys)
        assertThat(disclosureGroupRepository.count()).isEqualTo(51L);
    }
}

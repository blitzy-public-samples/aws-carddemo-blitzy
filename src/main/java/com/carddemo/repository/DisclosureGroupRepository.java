package com.carddemo.repository;

import com.carddemo.entity.DisclosureGroup;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link DisclosureGroup} entity &mdash; the relational port of
 * the legacy VSAM {@code DISCGRP} KSDS dataset (copybook {@code app/cpy/CVTRA02Y.cpy},
 * {@code DIS-GROUP-RECORD}, RECLN = 50). The backing {@code disclosure_group} table is the
 * <strong>interest-disclosure rate lookup</strong>: it holds the per-group, per-transaction-type,
 * per-transaction-category interest rate ({@code dis_int_rate}) that the interest-calculation batch
 * program {@code app/cbl/CBACT04C.cbl} consumes to compute monthly interest (AAP &sect;0.6.3).
 *
 * <h2>Composite-key access (3-part embedded id)</h2>
 * <p>The primary key is the three-column composite {@code (group_id, tran_type_cd, tran_cat_cd)},
 * modelled by the {@code public static} {@link jakarta.persistence.Embeddable @Embeddable} class
 * {@link DisclosureGroup.DisclosureGroupId}. This repository is therefore parameterised as
 * {@code JpaRepository<DisclosureGroup, DisclosureGroup.DisclosureGroupId>}, and the keyed
 * disclosure-rate read is performed with the inherited
 * {@link JpaRepository#findById(Object) findById}:</p>
 * <pre>{@code
 *   Optional<DisclosureGroup> rate = disclosureGroupRepository.findById(
 *           new DisclosureGroup.DisclosureGroupId(groupId, tranTypeCd, tranCatCd));
 * }</pre>
 *
 * <h2>Standard CRUD only &mdash; no derived queries</h2>
 * <p>This interface deliberately declares <strong>no</strong> additional or derived query methods.
 * The single access pattern the migration requires &mdash; a primary-key lookup of the disclosure
 * rate &mdash; is fully satisfied by {@code findById}. The remaining CRUD surface ({@code save},
 * {@code saveAll}, {@code findAll}, {@code deleteById}, &hellip;) is likewise inherited from
 * {@link JpaRepository} and supports the reference-data seeding/refresh paths.</p>
 *
 * <h2>DEFAULT-group fallback lives in the service layer (not here)</h2>
 * <p>The legacy {@code CBACT04C} rate-resolution logic (paragraph
 * {@code 1200-GET-INTEREST-RATE}) reads {@code DISCGRP} keyed by the account's group id and, when
 * that read misses (VSAM file status {@code 23} / record not found), <strong>falls back to the
 * {@code 'DEFAULT'} group</strong> (paragraph {@code 1200-A-GET-DEFAULT-INT-RATE}). That fallback
 * orchestration is <strong>intentionally NOT implemented in this repository</strong>: per the
 * strict layered-architecture rule (AAP &sect;0.3.2), repositories carry no business logic.
 * {@code InterestCalculationService} owns the fallback &mdash; it calls {@code findById(...)} with
 * the account's group id and, if the {@link java.util.Optional} result is empty, calls
 * {@code findById(...)} again with {@code groupId = "DEFAULT"}. All interest arithmetic
 * ({@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, {@code RoundingMode.HALF_UP}) is likewise owned by
 * that service. Maintainers should therefore not expect to find the fallback or any rate math
 * here.</p>
 *
 * @see DisclosureGroup
 * @see DisclosureGroup.DisclosureGroupId
 * @see JpaRepository
 * @see <a href="file:app/cpy/CVTRA02Y.cpy">CVTRA02Y.cpy &mdash; DIS-GROUP-RECORD layout</a>
 * @see <a href="file:app/cbl/CBACT04C.cbl">CBACT04C.cbl &mdash; rate resolution + DEFAULT fallback (reference only)</a>
 */
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroup.DisclosureGroupId> {

    // Intentionally empty: standard Spring Data JPA CRUD is sufficient for this lookup table.
    // The keyed disclosure-rate read is the inherited findById(DisclosureGroup.DisclosureGroupId);
    // the 'DEFAULT'-group fallback and all interest arithmetic are owned by
    // InterestCalculationService (AAP §0.3.2, §0.6.3). No @Repository annotation is needed —
    // Spring Data auto-detects interfaces extending JpaRepository.
}

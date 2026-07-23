package com.aws.carddemo.repository;

import com.aws.carddemo.domain.DisclosureGroup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link DisclosureGroup} disclosure-group /
 * interest-rate reference data. It replaces the legacy VSAM {@code DISCGRP}
 * KSDS I/O with Spring Data access over PostgreSQL in the AWS CardDemo
 * COBOL-to-Java migration (AAP sections 0.3.3, 0.4.1, 0.6.2).
 *
 * <p><strong>Origin (traceability):</strong>
 * {@code Origin: legacy/cpy/CVTRA02Y.cpy (DIS-GROUP-RECORD, RECLN 50); VSAM DISCGRP;
 * composite key DIS-GROUP-KEY (group+type+category).}</p>
 *
 * <p><strong>Composite-key semantics (preserved exactly):</strong> the legacy
 * three-part VSAM key {@code DIS-GROUP-KEY} (account-group id + transaction-type
 * code + transaction-category code) is reproduced as the entity's
 * {@code @IdClass}. Accordingly, this repository is typed on the nested id class
 * {@link DisclosureGroup.DisclosureGroupId}, which is referenced through the
 * {@link DisclosureGroup} entity rather than imported separately (AAP section
 * 0.6.2).</p>
 *
 * <p><strong>Interest-rate lookup:</strong> the disclosure interest rate is
 * retrieved by exact composite key through the inherited
 * {@code findById(new DisclosureGroup.DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd))}.
 * This mirrors the COBOL random {@code READ} of {@code DISCGRP-FILE} by
 * {@code DIS-GROUP-KEY} (paragraph {@code 1050-GET-INTEREST-RATE} in the batch
 * program {@code CBACT04C}). The returned {@code DIS-INT-RATE} feeds the
 * fixed-scale interest computation {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * carried out by {@code InterestCalcJobConfig} (the Spring Batch job migrated
 * from {@code CBACT04C}). The DEFAULT-account-group fallback and all numeric
 * handling are intentionally located in that batch/service layer, not in this
 * repository, which only returns the persisted entity.</p>
 *
 * <p><strong>No feature expansion:</strong> no derived-query finders,
 * {@code @Query}/JPQL methods, or {@code @EnableJpaRepositories} declarations
 * are added. The inherited CRUD surface of {@link JpaRepository} is the complete
 * and exact access surface required for composite-key interest-rate lookup and
 * for reference/seed data loading (AAP section 0.7.1).</p>
 */
@Repository
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroup.DisclosureGroupId> {
}

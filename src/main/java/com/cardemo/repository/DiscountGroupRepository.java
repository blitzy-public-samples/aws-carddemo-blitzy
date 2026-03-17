/*
 * DiscountGroupRepository.java — Spring Data JPA Repository for discount group reference data
 *
 * Provides data access for the DiscountGroup entity, mapping the DISCGRP VSAM
 * reference dataset used during batch interest calculation (CBACT04C.cbl).
 *
 * VSAM Access Patterns Translated:
 *   - VSAM READ KEY IS DIS-ACCT-GROUP-ID  → findByGroupId(String)
 *   - VSAM READ KEY IS DIS-GROUP-KEY      → findByGroupIdAndTranTypeCodeAndTranCatCode(...)
 *   - VSAM full scan                      → findAll() (inherited from JpaRepository)
 *   - VSAM WRITE / REWRITE               → save() / saveAll() (inherited)
 *
 * The discount group data is organized by group ID ("A", "DEFAULT", "ZEROAPR")
 * with 17 category combinations per group totaling 51 reference records.
 *
 * @see com.cardemo.entity.DiscountGroup — JPA entity (← CVTRA02Y.cpy DIS-GROUP-RECORD)
 * @see app/data/ASCII/discgrp.txt — Source reference data (51 records)
 * @see app/cbl/CBACT04C.cbl — COBOL batch interest calculation program
 */
package com.cardemo.repository;

import com.cardemo.entity.DiscountGroup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DiscountGroup} entities.
 *
 * <p>Provides access to the DISCGRP VSAM reference dataset, which stores interest
 * rate definitions organized by account group, transaction type, and transaction
 * category. This data is primarily consumed during the batch interest calculation
 * process (translated from CBACT04C.cbl → InterestCalculationService).</p>
 *
 * <p><strong>Data organization:</strong> 51 records across 3 groups:</p>
 * <ul>
 *   <li>{@code "A"} — 17 category combinations with varying interest rates</li>
 *   <li>{@code "DEFAULT"} — 17 category combinations with standard rates</li>
 *   <li>{@code "ZEROAPR"} — 17 category combinations with zero interest rates</li>
 * </ul>
 *
 * <p><strong>VSAM-to-JPA access pattern mapping:</strong></p>
 * <ul>
 *   <li>VSAM READ KEY IS DIS-ACCT-GROUP-ID (partial key) →
 *       {@link #findByGroupId(String)}</li>
 *   <li>VSAM READ KEY IS DIS-GROUP-KEY (full composite key) →
 *       {@link #findByGroupIdAndTranTypeCodeAndTranCatCode(String, String, Integer)}</li>
 *   <li>VSAM STARTBR / READNEXT (full scan) → {@code findAll()} (inherited)</li>
 *   <li>VSAM WRITE / REWRITE → {@code save()} / {@code saveAll()} (inherited)</li>
 * </ul>
 *
 * <p>Uses composite primary key {@link DiscountGroup.DiscountGroupId} matching the
 * VSAM KSDS DIS-GROUP-KEY structure: (dis_acct_group_id, dis_tran_type_cd,
 * dis_tran_cat_cd). All inherited JpaRepository methods accept
 * {@code DiscountGroup.DiscountGroupId} as the ID type.</p>
 */
@Repository
public interface DiscountGroupRepository
        extends JpaRepository<DiscountGroup, DiscountGroup.DiscountGroupId> {

    /**
     * Finds all discount group entries for a specific account group identifier.
     *
     * <p>Maps to a VSAM partial key READ on DIS-ACCT-GROUP-ID. For example,
     * calling {@code findByGroupId("A")} returns all 17 category interest rates
     * defined for group "A".</p>
     *
     * <p>Used by {@code InterestCalculationService} to retrieve the complete set
     * of interest rate definitions for an account's assigned discount group
     * (ACCT-GROUP-ID from the Account entity).</p>
     *
     * <p>Spring Data JPA derives the query from the method name:
     * {@code SELECT d FROM DiscountGroup d WHERE d.groupId = ?1}</p>
     *
     * @param groupId the discount account group identifier
     *                (e.g., "A", "DEFAULT", "ZEROAPR")
     * @return list of all discount entries for the specified group;
     *         empty list if the group identifier is not found
     */
    List<DiscountGroup> findByGroupId(String groupId);

    /**
     * Finds a specific discount rate for a given group, transaction type, and
     * transaction category combination.
     *
     * <p>Maps to a VSAM keyed READ on the full composite DIS-GROUP-KEY, which is
     * the most precise lookup used during batch interest calculation in
     * CBACT04C.cbl. Returns {@link Optional#empty()} when no matching discount
     * rate exists for the given group/type/category combination.</p>
     *
     * <p>This is equivalent to the COBOL statement:
     * {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD KEY IS DIS-GROUP-KEY}</p>
     *
     * <p>Spring Data JPA derives the query from the method name:
     * {@code SELECT d FROM DiscountGroup d WHERE d.groupId = ?1
     * AND d.tranTypeCode = ?2 AND d.tranCatCode = ?3}</p>
     *
     * @param groupId      the discount account group identifier (DIS-ACCT-GROUP-ID)
     * @param tranTypeCode the 2-character transaction type code (DIS-TRAN-TYPE-CD)
     * @param tranCatCode  the transaction category code (DIS-TRAN-CAT-CD) as Integer
     * @return an {@link Optional} containing the matching discount group entry
     *         if found, or {@link Optional#empty()} if no entry matches
     */
    Optional<DiscountGroup> findByGroupIdAndTranTypeCodeAndTranCatCode(
            String groupId, String tranTypeCode, Integer tranCatCode);
}

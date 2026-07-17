/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.TransactionCategoryBalance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link TransactionCategoryBalance}, the running
 * per-(account, transaction-type, transaction-category) balance.
 *
 * <p>This repository re-expresses the legacy VSAM access to the {@code TCATBALF} KSDS
 * (copybook {@code CVTRA01Y.cpy}, {@code TRAN-CAT-BAL-RECORD}) as set-based Spring Data
 * access over the PostgreSQL table {@code tran_cat_balance}. Rows are keyed by the
 * three-part composite key {@code (acct_id, type_cd, cat_cd)} &mdash; the 17-byte COBOL
 * {@code TRAN-CAT-KEY} group ({@code 9(11)} + {@code X(02)} + {@code 9(04)}) &mdash; and
 * carry the running balance {@code bal} ({@code TRAN-CAT-BAL PIC S9(09)V99} &rarr;
 * {@code DECIMAL(11,2)}).</p>
 *
 * <h2>Legacy source access</h2>
 * <ul>
 *   <li><strong>Interest calculation</strong> ({@code legacy/cbl/CBACT04C.cbl}): opens
 *       {@code TCATBAL-FILE} for input and browses each account's category-balance rows
 *       sequentially ({@code 1000-TCATBALF-GET-NEXT}) to compute monthly interest. That
 *       per-account grouped browse is reproduced by {@link #findByAccountId(Long)}.</li>
 *   <li><strong>Transaction posting</strong> ({@code legacy/cbl/CBTRN02C.cbl}): opens
 *       {@code TCATBAL-FILE} for I-O and, in {@code 2700-UPDATE-TCATBAL}, reads a single
 *       category-balance row by its full key, adds the transaction amount, and rewrites
 *       it &mdash; the classic READ&nbsp;-&nbsp;UPDATE&nbsp;-&nbsp;REWRITE cycle. That is
 *       reproduced by the inherited {@link JpaRepository#findById(Object)} followed by
 *       {@link JpaRepository#save(Object)} within the calling service's
 *       {@code @Transactional} boundary.</li>
 * </ul>
 *
 * <p>The composite key is modeled by the embeddable value type
 * {@link TransactionCategoryBalance.TransactionCategoryBalanceId}, which is therefore the
 * identifier generic of this repository. Full-key reads and updates use the inherited
 * {@code findById} / {@code save} operations; last-writer integrity for the
 * READ&nbsp;-&nbsp;UPDATE&nbsp;-&nbsp;REWRITE cycle is provided by the entity's
 * {@code @Version} optimistic-lock column, so no explicit {@code @Lock} is declared here
 * (AAP&nbsp;&sect;0.7.1 H6).</p>
 *
 * <p>This repository returns managed entities only; all interest and posting arithmetic
 * (using {@link java.math.BigDecimal}) lives in the batch and service layers, never here.</p>
 */
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalance.TransactionCategoryBalanceId> {

    /**
     * Returns all category balances for a single account, ordered by
     * {@code (type_cd, cat_cd)}.
     *
     * <p>Reproduces the per-account grouped sequential browse of the {@code TCATBALF}
     * KSDS used by the interest-calculation batch ({@code legacy/cbl/CBACT04C.cbl}),
     * which iterates each account's category balances to compute monthly interest. The
     * ordering matches the ascending key sequence of the original VSAM browse. The query
     * is expressed explicitly (rather than as a derived query method) so the ordered
     * embedded-identifier traversal ({@code b.id.acctId}, {@code b.id.typeCd},
     * {@code b.id.catCd}) is unambiguous and warning-free.</p>
     *
     * @param acctId the account identifier ({@code TRANCAT-ACCT-ID}); must not be
     *               {@code null}
     * @return the account's category balances ordered by transaction-type code then
     *         transaction-category code; an empty list if the account has none
     */
    @Query("SELECT b FROM TransactionCategoryBalance b "
         + "WHERE b.id.acctId = :acctId ORDER BY b.id.typeCd ASC, b.id.catCd ASC")
    List<TransactionCategoryBalance> findByAccountId(@Param("acctId") Long acctId);
}

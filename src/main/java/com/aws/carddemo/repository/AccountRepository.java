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

import com.aws.carddemo.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link Account} master-account entity.
 *
 * <p>This repository is the Java re-platforming of the legacy COBOL keyed access
 * to the VSAM KSDS {@code ACCTDATA.VSAM.KSDS} ({@code ACCTFILE}, copybook
 * {@code legacy/cpy/CVACT01Y.cpy}, record length 300). The dataset becomes the
 * PostgreSQL table {@code account}, keyed by {@code acct_id} (COBOL
 * {@code ACCT-ID PIC 9(11)}, VSAM {@code KEYLEN=11}, {@code RKP=0}). The
 * {@code group_id} column relates each account to its {@code disclosure_group}
 * (enforced in application logic exactly as the COBOL original did).</p>
 *
 * <h2>Legacy source access re-platformed here</h2>
 * <ul>
 *   <li><strong>Sequential browse</strong> &mdash; the account master-print batch
 *       {@code legacy/cbl/CBACT01C.cbl} ({@code ACCESS MODE IS SEQUENTIAL},
 *       {@code RECORD KEY IS FD-ACCT-ID}) and the account iteration in the
 *       interest-calculation batch {@code legacy/cbl/CBACT04C.cbl} read the
 *       accounts in key order. Reproduced by {@link #findAllByOrderByAcctIdAsc()}.</li>
 *   <li><strong>Keyed single-record read</strong> &mdash; the online account
 *       inquiry {@code legacy/cbl/COACTVWC.cbl} ({@code EXEC CICS READ} on
 *       {@code ACCTDAT} by account id) is served by the inherited
 *       {@link JpaRepository#findById(Object) findById(Long)}.</li>
 *   <li><strong>Read-update-rewrite (posting)</strong> &mdash; the posting
 *       balance update {@code legacy/cbl/CBTRN02C.cbl} is served by
 *       {@link JpaRepository#findById(Object) findById(Long)} followed by
 *       {@link JpaRepository#save(Object) save(Account)}. The integrity of the
 *       COBOL READ-UPDATE-REWRITE cycle is provided by the entity's
 *       {@code @Version} optimistic lock inside the calling service's
 *       {@code @Transactional} boundary; a modified balance advances the version
 *       naturally, so this path needs no explicit {@code @Lock}.</li>
 *   <li><strong>Read-update-rewrite (online account update aggregate)</strong>
 *       &mdash; the online account update {@code legacy/cbl/COACTUPC.cbl}
 *       ({@code 9700-CHECK-CHANGE-IN-REC}) treats the account and its owning
 *       customer as one edit aggregate, re-reading and comparing <em>both</em>
 *       records before rewriting. Its confirmed-write path uses
 *       {@link #findByIdForVersionedUpdate(Long)}, which loads the account under
 *       {@link LockModeType#OPTIMISTIC_FORCE_INCREMENT} so that even a
 *       customer-only edit (no account column changed) advances
 *       {@code account.version}. A concurrent editor holding the now-stale
 *       account version is then rejected, reproducing the COBOL abort for the
 *       whole aggregate (AAP 0.7.1 H6; see {@code docs/decision-log.md}).</li>
 * </ul>
 *
 * <p>Monetary arithmetic and credit-limit checks are deliberately absent here;
 * those remain {@code service/} and {@code batch/} concerns operating on the
 * entity's {@link java.math.BigDecimal} fields.</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Returns every account ordered by ascending account id.
     *
     * <p>Reproduces the {@code ACCTFILE} KSDS sequential ascending browse used by
     * the account master-print batch ({@code legacy/cbl/CBACT01C.cbl}) and the
     * account iteration in the interest-calculation batch
     * ({@code legacy/cbl/CBACT04C.cbl}), both of which process accounts in
     * primary-key order.</p>
     *
     * @return all accounts sorted by {@code acctId} ascending; never {@code null}
     */
    List<Account> findAllByOrderByAcctIdAsc();

    /**
     * Reads the account by its primary key and force-increments its
     * optimistic-lock {@code version} when the surrounding transaction commits
     * &mdash; even when no business field on the account itself changes.
     *
     * <p><strong>Why force-increment.</strong> The online account-update program
     * {@code legacy/cbl/COACTUPC.cbl} ({@code 9700-CHECK-CHANGE-IN-REC}) treats
     * the account and its owning customer as a single edit aggregate: it
     * re-reads and field-compares <em>both</em> records and aborts the rewrite
     * with "Record changed by some one else. Please review" (COACTUPC line 522)
     * if either changed since the user fetched the details. In the relational
     * target the account row is the anchor of that aggregate, so loading it with
     * {@link LockModeType#OPTIMISTIC_FORCE_INCREMENT} guarantees that every
     * confirmed update advances {@code account.version} &mdash; including a
     * customer-only edit that leaves all account columns untouched. A second,
     * concurrent editor who fetched the now-stale account version is then
     * rejected by the service's version guard, reproducing the COBOL abort for
     * the whole account-plus-customer aggregate. This is a documented,
     * intentional improvement (AAP 0.7.1 H6; see {@code docs/decision-log.md}).</p>
     *
     * <p>Used exclusively by the confirmed-write path of
     * {@code AccountService.performWrite}; the read-only inquiry path continues
     * to use the inherited {@link JpaRepository#findById(Object) findById(Long)}
     * (no version bump on a pure read).</p>
     *
     * @param acctId the account primary key (COBOL {@code ACCT-ID PIC 9(11)})
     * @return the managed account if present, otherwise an empty {@link Optional}
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select a from Account a where a.acctId = :acctId")
    Optional<Account> findByIdForVersionedUpdate(@Param("acctId") Long acctId);
}

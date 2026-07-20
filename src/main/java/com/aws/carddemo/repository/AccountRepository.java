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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    /**
     * Applies one interest cycle's accumulated interest to an account
     * <strong>at most once</strong>, reproducing the {@code 1050-UPDATE-ACCOUNT}
     * paragraph of the interest-calculation batch {@code legacy/cbl/CBACT04C.cbl}
     * (L350-370) &mdash; add the accumulated per-account interest to
     * {@code ACCT-CURR-BAL} and zero both cycle amounts &mdash; while guaranteeing
     * the balance mutation is idempotent per {@code (account, cycle)}.
     *
     * <p><strong>Idempotency guard (QA findings F-P6-A / F-P5-D).</strong> The
     * {@code WHERE} clause posts interest only when the account's
     * {@code last_interest_cycle} marker is still {@code NULL} (never posted) or
     * differs from the {@code cycle} being posted. It then stamps
     * {@code last_interest_cycle = :cycle}. Consequently a re-run or a mid-run
     * restart of {@code InterestCalculationJob} for the same {@code parmDate}
     * updates <em>zero</em> rows for any account already posted in a prior run, so
     * the balance is never double-applied (the correct outcome is
     * {@code curr_bal 37.51 / version 1}, not {@code 75.02 / version 2}). Under
     * PostgreSQL {@code READ COMMITTED} two concurrent postings of the same
     * {@code (account, cycle)} serialize on the row lock and the second matches
     * zero rows, so exactly-once holds under concurrency as well as sequential
     * re-runs.</p>
     *
     * <p><strong>Why a bulk update.</strong> A conditional {@code UPDATE ... WHERE}
     * performs the compare-and-set atomically in a single statement, which a
     * read-then-{@code save} cannot do without a race window. The optimistic-lock
     * {@code version} is advanced explicitly ({@code a.version = a.version + 1})
     * because a JPQL bulk update does not auto-increment a {@code @Version} column;
     * this keeps the version consistent with the read-then-save path so a
     * successful posting still advances the version exactly once. {@code @Modifying}
     * is configured to flush pending changes before, and clear the persistence
     * context after, the statement so that any subsequently loaded {@link Account}
     * reflects the committed row rather than a stale first-level-cache copy.</p>
     *
     * <p>All arithmetic is performed by the database on the {@code DECIMAL(12,2)}
     * column against a {@link BigDecimal} parameter; {@code double}/{@code float}
     * are never involved. This is a documented, additive operational-integrity
     * mechanism (see {@code docs/decision-log.md}); the marker column is created by
     * Flyway migration {@code V5__add_account_last_interest_cycle.sql}.</p>
     *
     * @param acctId   the account to post interest to (COBOL {@code ACCT-ID PIC 9(11)}); never {@code null}
     * @param interest the accumulated per-account monthly interest to add (scale&nbsp;2); never {@code null}
     * @param cycle    the 10-character interest cycle ({@code parmDate}) being posted; never {@code null}
     * @return the number of rows updated: {@code 1} when the interest was applied,
     *         {@code 0} when this cycle was already posted to the account (idempotent no-op)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Account a set "
            + "a.currBal = a.currBal + :interest, "
            + "a.currCycCredit = 0, "
            + "a.currCycDebit = 0, "
            + "a.lastInterestCycle = :cycle, "
            + "a.version = a.version + 1 "
            + "where a.acctId = :acctId "
            + "and (a.lastInterestCycle is null or a.lastInterestCycle <> :cycle)")
    int applyInterestForCycle(@Param("acctId") Long acctId,
            @Param("interest") BigDecimal interest,
            @Param("cycle") String cycle);
}

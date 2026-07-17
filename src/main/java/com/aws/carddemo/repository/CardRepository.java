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

import com.aws.carddemo.domain.Card;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Card} entity.
 *
 * <p>This repository is the set-based re-platforming of the COBOL VSAM file
 * access to the {@code CARDDATA.VSAM.KSDS} indexed dataset and its alternate
 * index {@code CARDDATA.VSAM.AIX} (copybook originally {@code app/cpy/CVACT02Y.cpy},
 * retained for reference at {@code legacy/cpy/CVACT02Y.cpy}). The dataset is
 * mapped to the PostgreSQL {@code card} table, keyed by {@code card_num}
 * (VSAM {@code KEYLEN=16}, {@code RKP=0}); the {@code acct_id} column carries a
 * database index ({@code idx_card_acct_id}, formalizing {@code CARDDATA.VSAM.AIX}
 * whose {@code AXRKP=16} pointed at the owning account id) and a foreign key to
 * {@code account(acct_id)}. The index and foreign key are owned by the Flyway
 * migration {@code V1__schema.sql}.</p>
 *
 * <p><strong>Legacy access reproduced.</strong> The following COBOL programs
 * read this dataset; each of their access patterns is preserved by the methods
 * below (or by the inherited {@link JpaRepository} operations):</p>
 * <ul>
 *   <li>{@code legacy/cbl/CBACT02C.cbl} &mdash; the card master-print batch,
 *       which browses {@code CARDDATA} sequentially in ascending key order
 *       ({@code PERFORM UNTIL} end-of-file). Reproduced by
 *       {@link #findAllByOrderByCardNumAsc()}.</li>
 *   <li>{@code legacy/cbl/COCRDLIC.cbl} &mdash; the online card-list screen,
 *       which enumerates the cards belonging to an account, paging forward and
 *       backward ({@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR})
 *       in card-number order. Reproduced by
 *       {@link #findByAcctId(Long, Pageable)} (paged) and
 *       {@link #findByAcctIdOrderByCardNumAsc(Long)} (full list).</li>
 *   <li>{@code legacy/cbl/COCRDSLC.cbl} &mdash; the online card-view screen,
 *       which performs a single keyed {@code READ} by card number. Reproduced by
 *       the inherited {@link JpaRepository#findById(Object)}.</li>
 *   <li>{@code legacy/cbl/COCRDUPC.cbl} &mdash; the online card-update screen,
 *       which performs a keyed {@code READ} followed by {@code REWRITE}.
 *       Reproduced by the inherited {@link JpaRepository#findById(Object)} plus
 *       {@link JpaRepository#save(Object)}; the READ-UPDATE-REWRITE integrity of
 *       the legacy cycle is provided by the entity's optimistic-lock
 *       {@code @Version} column within a service {@code @Transactional}
 *       boundary, so no explicit pessimistic lock is declared here.</li>
 * </ul>
 *
 * <p><strong>Sensitive data.</strong> The {@code Card} entity carries a
 * card-verification value (CVV) that must never be logged or returned in full.
 * This repository returns whole {@code Card} instances; masking the CVV is the
 * responsibility of the DTO / mapper / service layer, not of the data-access
 * layer, so no CVV handling is performed here.</p>
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Returns all cards belonging to a single account, ordered by card number
     * ascending.
     *
     * <p>Reproduces the {@code CARDDATA.VSAM.AIX} alternate-index browse
     * (alternate key on account id, {@code AXRKP=16}) used to enumerate every
     * card an account owns ({@code legacy/cbl/COCRDLIC.cbl},
     * {@code legacy/cbl/COCRDUPC.cbl}). Formalized as a derived query on the
     * {@code acctId} property with an explicit ascending sort on
     * {@code cardNum}.</p>
     *
     * @param acctId the owning account identifier (never {@code null})
     * @return the account's cards in ascending card-number order; an empty list
     *         if the account owns none
     */
    List<Card> findByAcctIdOrderByCardNumAsc(Long acctId);

    /**
     * Returns a page of the cards belonging to a single account.
     *
     * <p>Paged variant of the account-index browse for the online card-list
     * screen ({@code legacy/cbl/COCRDLIC.cbl}), which pages forward and backward
     * through an account's cards ({@code STARTBR}/{@code READNEXT}/
     * {@code READPREV} &rarr; {@link Pageable}). Ordering follows the
     * {@link Pageable}'s {@code Sort}; callers supply card-number ascending to
     * preserve the legacy browse order.</p>
     *
     * @param acctId   the owning account identifier (never {@code null})
     * @param pageable the pagination and sort request (never {@code null})
     * @return the requested page of the account's cards
     */
    Page<Card> findByAcctId(Long acctId, Pageable pageable);

    /**
     * Returns every card ordered by card number ascending.
     *
     * <p>Reproduces the sequential ascending read performed by the card
     * master-print batch ({@code legacy/cbl/CBACT02C.cbl}), which browses
     * {@code CARDDATA} in primary-key order from first to last record.</p>
     *
     * @return all cards in ascending card-number order; an empty list if none
     *         exist
     */
    List<Card> findAllByOrderByCardNumAsc();
}

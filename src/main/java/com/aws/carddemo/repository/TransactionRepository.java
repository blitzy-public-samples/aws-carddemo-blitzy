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

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.aws.carddemo.domain.Transaction;

/**
 * Spring Data JPA repository for the {@link Transaction} entity &mdash; the
 * posted-transaction master. It is the Java re-platform of the record-at-a-time
 * VSAM access to the legacy key-sequenced dataset {@code TRANSACT.VSAM.KSDS}
 * (copybook {@code legacy/cpy/CVTRA05Y.cpy}, {@code TRAN-RECORD}, RECLN 350) and
 * its chronological alternate index {@code TRANSACT.VSAM.AIX}, re-expressed as
 * set-based queries over the relational table {@code transaction}.
 *
 * <p>The relational schema is owned by Flyway ({@code V1__schema.sql}): the
 * table is keyed on the 16-character {@code tran_id} (primary key) and carries
 * the foreign keys {@code card_num} &rarr; {@code card},
 * {@code type_cd} &rarr; {@code transaction_type} and the composite
 * {@code (type_cd, cat_cd)} &rarr; {@code transaction_category}. The legacy
 * chronological alternate index is formalized in the schema as
 * {@code idx_transaction_proc_ts}; the transaction-list browse contract exposed
 * here preserves chronological retrieval by ordering on the processing
 * timestamp {@code proc_ts} &mdash; the column on which the legacy alternate
 * index {@code TRANSACT.VSAM.AIX} is keyed ({@code LISTCAT AXRKP=304};
 * {@code legacy/jcl/TRANIDX.jcl KEYS(26 304)}) &mdash; with the unique
 * {@code tran_id} appended as a deterministic secondary sort (see
 * {@code docs/decision-log.md} D10 and AAP &sect;0.4.3).</p>
 *
 * <p>Access-pattern mapping (COBOL &rarr; Spring Data):</p>
 * <ul>
 *   <li>{@code legacy/cbl/COTRN00C.cbl} &mdash; list transactions; the
 *       forward/backward
 *       {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} browse
 *       becomes the chronological {@code findByCardNumOrderByProcTsAscTranIdAsc}
 *       queries plus {@link Pageable} paging.</li>
 *   <li>{@code legacy/cbl/COTRN01C.cbl} &mdash; view a transaction; the keyed
 *       {@code EXEC CICS READ ... RIDFLD(TRAN-ID)} becomes the inherited
 *       {@code findById(String)}.</li>
 *   <li>{@code legacy/cbl/COTRN02C.cbl} &mdash; add a transaction; the
 *       reverse-browse id-generation pattern
 *       ({@code MOVE HIGH-VALUES} &rarr; {@code STARTBR} &rarr; {@code READPREV}
 *       &rarr; {@code ENDBR} &rarr; increment) at lines 444-451 becomes
 *       {@link #findTopByOrderByTranIdDesc()} / {@link #findMaxTranId()}, and the
 *       insert becomes the inherited {@code save}.</li>
 *   <li>{@code legacy/cbl/CBTRN02C.cbl} &mdash; the posting batch that writes
 *       posted transactions ({@code TRANSACT-FILE}/{@code TRANFILE}) through the
 *       inherited {@code save}.</li>
 * </ul>
 *
 * <p>This interface holds no business logic and performs no monetary
 * arithmetic; amount handling (using {@code java.math.BigDecimal} at scale 2),
 * transaction-id increment-from-max and the associated concurrency guard live in
 * the {@code service} / {@code batch} layers. Inherited CRUD operations
 * ({@code findById}, {@code save}, {@code findAll(Pageable)}, and so on) are not
 * redeclared.</p>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Returns every transaction for a single card in chronological
     * (processing-timestamp) order.
     *
     * <p>Reproduces the {@code TRANSACT.VSAM.AIX} chronological browse, ordering
     * by the processing timestamp {@code proc_ts} ascending &mdash; the column on
     * which the legacy alternate index is keyed ({@code LISTCAT AXRKP=304}) &mdash;
     * with the unique {@code tran_id} appended as a deterministic secondary sort so
     * rows sharing a {@code proc_ts} keep a stable, repeatable order (see
     * {@code docs/decision-log.md} D10 and AAP &sect;0.4.3). The legacy online
     * lister ({@code legacy/cbl/COTRN00C.cbl}) itself browses on the primary key
     * ({@code STARTBR ... RIDFLD(TRAN-ID)}) and merely displays the origination
     * timestamp.</p>
     *
     * @param cardNum the 16-character card number ({@code TRAN-CARD-NUM})
     * @return the card's transactions ordered by processing timestamp ascending,
     *         then by {@code tran_id} ascending; an empty list when the card has none
     */
    List<Transaction> findByCardNumOrderByProcTsAscTranIdAsc(String cardNum);

    /**
     * Paged variant of {@link #findByCardNumOrderByProcTsAscTranIdAsc(String)}
     * for the transaction-list screen's forward/backward paging.
     *
     * <p>The COBOL {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR}
     * browse becomes a {@link Pageable} window while the base result set keeps its
     * chronological ({@code proc_ts} ascending, then {@code tran_id} ascending)
     * ordering. The {@code tran_id} tie-breaker guarantees stable, non-overlapping
     * pages even when several rows share the same {@code proc_ts}.</p>
     *
     * @param cardNum  the 16-character card number ({@code TRAN-CARD-NUM})
     * @param pageable the paging (and optional additional sort) request
     * @return a page of the card's transactions in chronological, tie-broken order
     */
    Page<Transaction> findByCardNumOrderByProcTsAscTranIdAsc(String cardNum, Pageable pageable);

    /**
     * Returns the transaction carrying the highest {@code tran_id} (the
     * reverse-browse tip), or {@link Optional#empty()} when the table is empty.
     *
     * <p>Reproduces the COBOL {@code MOVE HIGH-VALUES} &rarr; {@code STARTBR}
     * &rarr; {@code READPREV} &rarr; {@code ENDBR} pattern used to locate the
     * last key before generating the next transaction id
     * ({@code legacy/cbl/COTRN02C.cbl}, lines 444-451). The increment-from-max
     * and the concurrency guard are performed by
     * {@code service/TransactionService} together with
     * {@code common/util/IdGenerator} inside a {@code @Transactional} boundary;
     * they are intentionally not implemented in this repository.</p>
     *
     * @return the highest-keyed transaction, if any
     */
    Optional<Transaction> findTopByOrderByTranIdDesc();

    /**
     * Returns {@code MAX(tran_id)} &mdash; an explicit-query alternative to
     * {@link #findTopByOrderByTranIdDesc()} for reverse-browse id generation.
     *
     * <p>Yields {@link Optional#empty()} when the table holds no rows (the SQL
     * aggregate returns {@code null}), allowing the id-generation service to seed
     * the first identifier.</p>
     *
     * @return the maximum transaction id, or empty when the table is empty
     */
    @Query("SELECT MAX(t.tranId) FROM Transaction t")
    Optional<String> findMaxTranId();
}

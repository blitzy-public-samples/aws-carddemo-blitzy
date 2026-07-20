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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.aws.carddemo.domain.CardXref;

/**
 * Spring Data JPA repository for the {@link CardXref} card cross-reference entity.
 *
 * <p>Re-platformed from the COBOL VSAM access to {@code CARDXREF.VSAM.KSDS} (copybook
 * {@code legacy/cpy/CVACT03Y.cpy}, record layout {@code CARD-XREF-RECORD}, 50 bytes)
 * and its alternate index {@code CARDXREF.VSAM.AIX} ({@code AXRKP=25}, keyed on the
 * account id). A cross-reference row links a card number to the customer and account
 * it belongs to. This repository maps that indexed-file access onto the PostgreSQL
 * table {@code card_xref}, whose primary key is {@code xref_card_num}, whose
 * {@code acct_id} column is both a foreign key to {@code account} and the B-tree index
 * {@code idx_card_xref_acct_id} (the formalized alternate index), and whose
 * {@code cust_id} column is a foreign key to {@code customer}.</p>
 *
 * <p>The COBOL programs that drove this access are reproduced here as ordered,
 * set-based queries that preserve the original VSAM browse order:</p>
 * <ul>
 *   <li>{@code legacy/cbl/CBACT03C.cbl} — the cross-reference master-print batch,
 *       which browses {@code CARDXREF} sequentially in primary-key (card-number)
 *       order.</li>
 *   <li>{@code legacy/cbl/COCRDSLC.cbl} — the card-detail online program, which
 *       enumerates the card(s) belonging to an account through the account alternate
 *       index.</li>
 *   <li>{@code legacy/cbl/COTRN02C.cbl} — the transaction-add online program, which
 *       resolves the account/customer for a given card (primary-key read, served by
 *       the inherited {@link JpaRepository#findById(Object)}) and the card for a given
 *       account (alternate-index read).</li>
 * </ul>
 *
 * <p>A single keyed lookup by card number uses the inherited
 * {@link JpaRepository#findById(Object)} and is intentionally not redeclared. This
 * interface holds no business logic; it declares only the derived queries required to
 * reproduce the legacy browse patterns, keeping card-number ordering so results match
 * the original VSAM key order.</p>
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Returns every cross-reference row for one account, ordered by card number.
     *
     * <p>Reproduces the {@code CARDXREF.VSAM.AIX} alternate-index browse (account id,
     * {@code AXRKP=25}) used to enumerate the cards belonging to an account in
     * {@code legacy/cbl/COCRDSLC.cbl} and {@code legacy/cbl/CBACT03C.cbl}. The ascending
     * {@code xref_card_num} ordering preserves the legacy browse order.</p>
     *
     * @param acctId the account identifier ({@code XREF-ACCT-ID}); must not be {@code null}
     * @return the matching cross-references ordered by card number, never {@code null}
     */
    List<CardXref> findByAcctIdOrderByXrefCardNumAsc(Long acctId);

    /**
     * Returns the first cross-reference for an account, ordered by card number.
     *
     * <p>Provides the single-row account&rarr;card/customer resolution performed via
     * the account alternate index by the transaction-add flow
     * ({@code legacy/cbl/COTRN02C.cbl}, paragraph {@code READ-CXACAIX-FILE}) and by the
     * card-detail lookup ({@code legacy/cbl/COCRDSLC.cbl}).</p>
     *
     * @param acctId the account identifier ({@code XREF-ACCT-ID}); must not be {@code null}
     * @return the first matching cross-reference, or {@link Optional#empty()} if none exists
     */
    Optional<CardXref> findFirstByAcctIdOrderByXrefCardNumAsc(Long acctId);

    /**
     * Returns every cross-reference row for one customer, ordered by card number.
     *
     * <p>Supports the customer&rarr;cards resolution over the {@code cust_id}
     * foreign-key column, ordered ascending by {@code xref_card_num}.</p>
     *
     * @param custId the customer identifier ({@code XREF-CUST-ID}); must not be {@code null}
     * @return the matching cross-references ordered by card number, never {@code null}
     */
    List<CardXref> findByCustIdOrderByXrefCardNumAsc(Long custId);

    /**
     * Returns every cross-reference row ordered ascending by card number.
     *
     * <p>Reproduces the sequential primary-key browse of the cross-reference
     * master-print batch ({@code legacy/cbl/CBACT03C.cbl}), which reads
     * {@code CARDXREF} in {@code RECORD KEY} ({@code xref_card_num}) order.</p>
     *
     * @return all cross-references ordered by card number, never {@code null}
     */
    List<CardXref> findAllByOrderByXrefCardNumAsc();
}

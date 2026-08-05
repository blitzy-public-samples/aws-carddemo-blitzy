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

package com.carddemo.batch.repository;

import com.carddemo.common.domain.CardXref;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * :purpose: Spring Data JPA repository for the {@link CardXref} card-to-customer-to-account
 *  cross-reference; replaces legacy VSAM ``XREFFILE`` (CXACAIX) primary- and alternate-index
 *  access for the batch jobs.
 * :output: Persistence operations over the ``card_xref`` table, exposing the inherited
 *  {@link JpaRepository} CRUD operations keyed by the 16-character card number plus the
 *  account-scoped and card-number-ordered lookups declared below.
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * :purpose: Fetch the cross-reference for a given account id via the ``xref_acct_id``
     *  secondary index (the CXACAIX alternate-index path used when assembling the interest
     *  transaction card number), taking the LOWEST card number when the account holds
     *  several cards.
     * :param xrefAcctId: owning account identifier to match.
     * :returns: the matching cross-reference with the lowest ``XREF-CARD-NUM``, or an empty
     *  {@link Optional} when the account has no cross-reference.
     * :note: The ordering is part of the contract, not a convenience. A VSAM
     *  alternate-index read returns records sharing an alternate key in PRIMARY-key order,
     *  so ``CBACT04C`` ``1110-GET-XREF-DATA`` deterministically saw the lowest card number
     *  [app/cbl/CBACT04C.cbl:L34-39, L393-413]; an unordered ``findFirst`` tracked
     *  PostgreSQL heap order and could stamp a different card number on a financial record
     *  for identical data and parameters.
     */
    Optional<CardXref> findFirstByXrefAcctIdOrderByXrefCardNumAsc(Long xrefAcctId);

    /**
     * :purpose: List cross-references ordered by card number ascending, reproducing the
     *  sequential ``XREFFILE`` read order in card-number key order.
     * :param pageable: paging and slice bounds to apply to the ordered read.
     * :returns: the requested page of cross-references, ordered by card number ascending.
     */
    List<CardXref> findAllByOrderByXrefCardNumAsc(Pageable pageable);
}

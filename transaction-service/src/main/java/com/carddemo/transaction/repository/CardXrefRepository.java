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
package com.carddemo.transaction.repository;

import com.carddemo.common.domain.CardXref;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * :purpose: Data-access repository for the card cross-reference
 *           (``XREFFILE`` / ``CXACAIX`` alternate index); replaces the legacy
 *           VSAM keyed ``READ`` used by the ``CBTRN02C`` transaction-posting
 *           batch program and the ``COTRN02C`` online add program. This is the
 *           card-to-customer-to-account linkage that anchors referential
 *           integrity across the transaction service.
 * :output: Card cross-reference linkage rows joining card, customer, and
 *          account, exposed as Spring Data JPA managed {@link CardXref}
 *          instances keyed by the 16-character card number.
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * :purpose: Look up a cross-reference by its card number, reproducing the
     *           ``CBTRN02C`` ``1500-A-LOOKUP-XREF`` keyed ``READ`` of
     *           ``XREFFILE`` and the ``COTRN02C`` ``READ-CCXREF-FILE`` read.
     * :param xrefCardNum: the 16-character card number that keys the
     *           cross-reference (the entity primary key).
     * :output: an {@link Optional} holding the matching cross-reference, or an
     *          empty {@link Optional} when no row exists for the card number,
     *          which drives the batch posting reject reason code 100
     *          ("INVALID CARD NUMBER FOUND").
     */
    Optional<CardXref> findByXrefCardNum(String xrefCardNum);

    /**
     * :purpose: Retrieve the FIRST card cross-reference of an account through the ``CXACAIX``
     *     alternate index, in ascending card-number order.
     * :param xrefAcctId: the owning account identifier used as the alternate-index key.
     * :output: the first matching cross-reference, or an empty ``Optional`` when no
     *     cross-reference exists for the account (the legacy ``NOTFND`` short-circuit that
     *     fails the referential-integrity check).
     * :note: ``CXACAIX`` is a NON-UNIQUE alternate index: an account legitimately owns
     *     several cards (``COCRDLIC`` pages seven per screen), and the programs that resolve
     *     an account through it -- ``COACTVWC``/``COACTUPC`` ``9200-GETCARDXREF-BYACCT``,
     *     ``COTRN02C`` and ``COBIL00C`` -- issue ``STARTBR``/``READNEXT`` and use the FIRST
     *     record returned. A finder that demanded a unique result therefore failed with
     *     "Query did not return a unique result" for a perfectly normal account. The
     *     ordering makes the choice deterministic, which the VSAM key order also was.
     */
    Optional<CardXref> findFirstByXrefAcctIdOrderByXrefCardNumAsc(Long xrefAcctId);
}

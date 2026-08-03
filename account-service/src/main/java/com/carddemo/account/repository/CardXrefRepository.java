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
package com.carddemo.account.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carddemo.common.domain.CardXref;

/**
 * :purpose: Spring Data JPA repository for the ``card_xref`` store; replaces the legacy
 *     VSAM read of file ``XREFFILE`` via the ``CXACAIX`` alternate index (``COACTVWC``
 *     ``9200-GETCARDXREF-BYACCT``). Inherits the standard CRUD surface keyed on the
 *     16-character card number (``xrefCardNum``) and adds the account-id alternate-index
 *     finder used by the account-view read order.
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * :purpose: Retrieve the FIRST card cross-reference of an account through the ``CXACAIX``
     *     alternate index, in ascending card-number order.
     * :param acctId: the owning account identifier used as the alternate-index key.
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
    Optional<CardXref> findFirstByXrefAcctIdOrderByXrefCardNumAsc(Long acctId);
}

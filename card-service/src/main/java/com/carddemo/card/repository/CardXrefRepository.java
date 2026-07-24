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
package com.carddemo.card.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carddemo.common.domain.CardXref;

/**
 * :purpose: Spring Data JPA repository for the ``card_xref`` store; replaces the legacy
 *     VSAM read of file ``XREFFILE`` via the ``CXACAIX`` alternate index; anchors
 *     card-to-customer-to-account referential integrity. The primary key is the
 *     16-character card number (``xrefCardNum``), matching the base ``XREFFILE`` key.
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * :purpose: Resolve the card cross-reference for an account through the ``CXACAIX``
     *     alternate-index access path (a single read-by-account, not a browse loop).
     * :param acctId: the account id used as the alternate-index key.
     * :output: an ``Optional`` holding the matching cross-reference, or an empty
     *     ``Optional`` when no cross-reference exists for the account (the legacy
     *     ``NOTFND`` / referential-integrity failure, reject code 100 context).
     */
    Optional<CardXref> findByXrefAcctId(Long acctId);
}

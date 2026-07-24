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
package com.carddemo.billpay.repository;

import com.carddemo.common.domain.CardXref;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for the card cross-reference aggregate.
 *
 * :purpose: Provide persistence access to {@link CardXref} for the online
 *     bill-payment flow, replacing the CICS VSAM ``CXACAIX`` alternate-index
 *     access performed by the legacy COBOL paragraph ``READ-CXACAIX-FILE`` in
 *     ``COBIL00C``. The ``CXACAIX`` account browse key becomes the derived
 *     query {@link #findByXrefAcctId(Long)}, resolving the account-to-card
 *     linkage needed to build the bill-payment transaction. Standard
 *     create/read/update/delete behaviour is inherited from
 *     {@link JpaRepository}; the identifier type is ``String`` because the
 *     primary key is the 16-character card number (``XREF-CARD-NUM``).
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Look up the single card cross-reference owned by an account.
     *
     * :param acctId: the owning account identifier, matched against the entity
     *     field ``xrefAcctId`` (legacy ``XREF-ACCT-ID``).
     * :output: an {@link Optional} holding the matching {@link CardXref}, or an
     *     empty {@link Optional} when no cross-reference exists for the supplied
     *     account identifier.
     */
    Optional<CardXref> findByXrefAcctId(Long acctId);
}

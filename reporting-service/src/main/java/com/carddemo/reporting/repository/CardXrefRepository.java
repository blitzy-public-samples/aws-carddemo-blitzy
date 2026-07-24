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
package com.carddemo.reporting.repository;

import com.carddemo.common.domain.CardXref;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * :purpose: Spring Data JPA repository for the {@link CardXref} card-to-customer-to-account
 *  cross-reference, replacing the legacy VSAM ``XREFFILE`` primary-key read that drives the
 *  statement engine (``CBSTM03A`` ``1000-XREFFILE-GET-NEXT``). Statement assembly resolves a
 *  single cross-reference by card number and, for the all-statements sweep, reads every
 *  cross-reference in card-number key order via the inherited
 *  {@code findAll(org.springframework.data.domain.Sort)}.
 * :output: Managed {@link CardXref} instances keyed by the 16-character ``xrefCardNum``
 *  primary key, exposing the inherited {@link JpaRepository} CRUD, keyed, and sorted reads.
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {
}

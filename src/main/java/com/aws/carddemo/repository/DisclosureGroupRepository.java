/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.DisclosureGroup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link DisclosureGroup} reference entity.
 *
 * <p><strong>Source mapping.</strong> Replaces the COBOL VSAM keyed access to
 * the {@code DISCGRP} KSDS (copybook {@code CVTRA02Y.cpy}) with set-based access
 * over the PostgreSQL {@code disclosure_group} table. Each row is keyed by the
 * three-part composite key {@code (group_id, type_cd, cat_cd)} — the migration
 * of the 16-byte VSAM {@code DIS-GROUP-KEY} — and carries the annual interest
 * rate {@code int_rate DECIMAL(6,2)} ({@code DIS-INT-RATE PIC S9(04)V99}).</p>
 *
 * <p><strong>Legacy access.</strong> The disclosure-group record is read by the
 * interest-calculation batch program {@code CBACT04C}
 * ({@code legacy/cbl/CBACT04C.cbl}, paragraph {@code 1200-GET-INTEREST-RATE}),
 * which issues a keyed {@code READ DISCGRP-FILE} to obtain the rate for a given
 * account group, transaction type and transaction category. That rate feeds the
 * parity-critical monthly-interest computation
 * ({@code WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}); the entity
 * therefore models the rate as a {@code BigDecimal} to preserve exact decimal
 * arithmetic.</p>
 *
 * <p><strong>Access pattern.</strong> The single keyed {@code READ} maps to the
 * inherited {@link JpaRepository#findById(Object)} using the full composite key
 * {@link DisclosureGroup.DisclosureGroupId}; bulk seeding and reference loads
 * use the inherited {@code findAll} / {@code save} operations. No custom query
 * methods are required.</p>
 *
 * <p>The {@code CBACT04C} default-group fallback (retry against the
 * {@code DEFAULT} group when a specific key is not found) is deliberately
 * <em>not</em> implemented here: it is business/orchestration logic that belongs
 * to the interest-calculation service/batch layer, keeping this repository a
 * thin, purely data-access abstraction.</p>
 */
@Repository
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroup.DisclosureGroupId> {
    // No custom methods: keyed lookup by the full composite key is served by the
    // inherited findById(DisclosureGroup.DisclosureGroupId), and reference-data
    // loads use the inherited findAll/save. Additional query methods would be
    // added here only when a concrete consumer requires them.
}

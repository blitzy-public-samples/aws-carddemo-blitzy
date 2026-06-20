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
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.id.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link DisclosureGroup} (table {@code disclosure_group}).
 *
 * <p>Replaces legacy VSAM {@code DISCGRP} KSDS access (copybook {@code CVTRA02Y}). Composite
 * primary key {@link DisclosureGroupId} (KEYLEN 16). Supplies the {@code dis_int_rate} ({@code
 * BigDecimal}) read by the interest-calculation batch ({@code CBACT04C}); the {@code
 * (bal*rate)/1200} truncated arithmetic (RoundingMode.DOWN, scale 2) is performed in the SERVICE
 * layer, not here. Lookups use {@code findById(new DisclosureGroupId(grp, type, cat))}; a missing
 * record returns {@link java.util.Optional#empty()} (FILE STATUS {@code '23'}).
 */
@Repository
public interface DisclosureGroupRepository
    extends JpaRepository<DisclosureGroup, DisclosureGroupId> {}

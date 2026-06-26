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

import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.id.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TransactionCategory} (table {@code tran_category}).
 *
 * <p>Replaces legacy VSAM {@code TRANCATG} KSDS access (copybook {@code CVTRA04Y}; random read by
 * the composite key {@code TRAN-TYPE-CD}+{@code TRAN-CAT-CD}, e.g. {@code CBTRN03C}). Composite
 * primary key {@link TransactionCategoryId} (KEYLEN 6). Reference/lookup table seeded by {@code
 * V2__seed_reference_data.sql}. Lookups use {@code findById(new TransactionCategoryId(type, cat))};
 * a missing record returns {@link java.util.Optional#empty()} (FILE STATUS {@code '23'}).
 */
@Repository
public interface TransactionCategoryRepository
    extends JpaRepository<TransactionCategory, TransactionCategoryId> {}

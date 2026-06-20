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

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TransactionCategoryBalance} (table {@code
 * tran_cat_balance}).
 *
 * <p>Replaces legacy VSAM {@code TCATBALF} KSDS access (copybook {@code CVTRA01Y}), opened {@code
 * I-O} in {@code CBTRN02C}. Composite primary key {@link TransactionCategoryBalanceId} (KEYLEN 17).
 * The legacy {@code 2700-UPDATE-TCATBAL} find-or-create (upsert) — read accepts FILE STATUS {@code
 * '00' OR '23'}, then WRITE if absent / REWRITE if present — is implemented in the SERVICE layer as
 * {@code findById(...)} (returns {@link java.util.Optional}) followed by {@code save(...)}. This
 * repository exposes only the standard {@code JpaRepository} operations.
 */
@Repository
public interface TransactionCategoryBalanceRepository
    extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {}

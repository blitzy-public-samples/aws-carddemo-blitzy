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

import com.aws.carddemo.domain.DailyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link DailyTransaction} (table {@code daily_transaction}).
 *
 * <p>Replaces the legacy sequential {@code DALYTRAN} file consumed by the posting batch ({@code
 * CBTRN02C} 1000-DALYTRAN-GET-NEXT). Primary key {@code dalytran_id} ({@code String}); this table
 * has no alternate index. Sequential consumption is performed by the Spring Batch reader; {@code
 * findAll}/{@code findById} (returning {@link java.util.Optional}) cover lookups, with a missing
 * record mapping to {@code Optional.empty()} (FILE STATUS {@code '23'}).
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {}

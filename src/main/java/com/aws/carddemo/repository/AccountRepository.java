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

import com.aws.carddemo.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Account} (table {@code account}).
 *
 * <p>Replaces legacy VSAM {@code ACCTDATA} KSDS access (copybook {@code CVACT01Y}; opened {@code
 * I-O} and {@code REWRITE}n in {@code CBTRN02C} 2800-UPDATE-ACCOUNT-REC). Primary key {@code
 * acct_id} ({@code Long}). A missing record returns {@link java.util.Optional#empty()} from {@code
 * findById} (COBOL FILE STATUS {@code '23'}). The READ-for-update/REWRITE optimistic-locking parity
 * is handled by the service layer (the entity intentionally has no {@code @Version} column).
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {}

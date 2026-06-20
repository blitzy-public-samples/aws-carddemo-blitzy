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

import com.aws.carddemo.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Customer} (table {@code customer}).
 *
 * <p>Replaces legacy VSAM {@code CUSTDATA} KSDS access (copybook {@code CVCUS01Y}; FD/SELECT
 * pattern per {@code CBTRN02C}). Primary key {@code cust_id} ({@code Long}). A missing record
 * returns {@link java.util.Optional#empty()} from {@code findById} (COBOL FILE STATUS {@code
 * '23'}); the service layer performs any find-or-create/upsert via {@code findById} + {@code save}.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {}

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
package com.carddemo.account.repository;

import com.carddemo.common.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the ``customers`` store.
 *
 * :purpose: Provides primary-key CRUD for the customer master, replacing the
 *           legacy keyed VSAM read/rewrite of file ``CUSTFILE`` performed by
 *           ``COACTVWC`` (``9400-GETCUSTDATA-BYCUST``) and ``COACTUPC``
 *           (customer ``REWRITE``). The account view/update flow reads and
 *           rewrites the customer record by its ``CUST-ID`` key.
 * :output: The inherited ``findById(Long)`` returns an ``Optional<Customer>``
 *          that is present for a keyed hit and empty when the customer master
 *          holds no matching id.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}

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

import com.carddemo.common.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * :purpose: Spring Data JPA repository providing persistence access to the
 *   ``Account`` aggregate for the bill-payment flow, replacing the legacy
 *   COBIL00C ``ACCTDAT`` VSAM access: the ``READ-ACCTDAT-FILE`` read-for-update
 *   maps to the inherited ``findById(Long)`` and the ``UPDATE-ACCTDAT-FILE``
 *   rewrite to the inherited ``save(Account)``.
 * :output: JPA-managed ``Account`` instances keyed by the 11-digit ``acct_id``;
 *   optimistic concurrency is enforced by the entity ``@Version`` column, not by
 *   any method declared here.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}

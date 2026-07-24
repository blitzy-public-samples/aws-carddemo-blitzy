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

import com.carddemo.common.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * :purpose: Spring Data JPA repository for the {@link Account} master, replacing the legacy
 *  keyed VSAM ``ACCTFILE`` read performed while assembling statements (``CBSTM03A``
 *  ``3000-ACCTFILE-GET``). The account is fetched by its ``ACCT-ID`` key derived from the
 *  owning cross-reference.
 * :output: The inherited ``findById(Long)`` returns an ``Optional<Account>`` that is present
 *  for a keyed hit and empty when the account master holds no matching id.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}

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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carddemo.common.domain.Account;

/**
 * Spring Data JPA repository for the CardDemo account master store.
 *
 * :purpose: Replaces the legacy keyed VSAM read/rewrite of file ``ACCTFILE``
 *     performed by ``COACTVWC`` (``9300-GETACCTDATA-BYACCT``) and ``COACTUPC``
 *     (account ``REWRITE``). Persists the ``Account`` aggregate to the
 *     ``accounts`` table keyed by the 11-digit ``ACCT-ID``.
 * :output: The inherited CRUD surface over ``Account``: ``findById`` returns an
 *     ``Optional<Account>`` (present mirrors ``DFHRESP(NORMAL)``, empty mirrors
 *     ``DFHRESP(NOTFND)``), and ``save`` performs a ``@Version`` optimistic-lock
 *     rewrite that raises an optimistic-lock failure on concurrent modification.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.batch.repository;

import com.carddemo.common.domain.Account;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Account} master entity.
 *
 * :purpose: Replaces VSAM ACCTFILE (KSDS) access used by the batch tier
 *     (``CBACT01C`` read/print and ``CBACT04C`` interest per-account
 *     read/update). The legacy sequential key-order browse becomes a derived
 *     query, while the random read and rewrite become the inherited
 *     ``findById`` and ``save`` operations.
 * :output: Managed {@link Account} instances; ``save`` honors the
 *     ``@Version`` optimistic-lock column on the entity.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Reads accounts in ascending account-id key order for chunked batch
     * processing.
     *
     * :purpose: Backs the ``CBACT01C`` / READACCT sequential account dump,
     *     which reads VSAM ACCTFILE in ``FD-ACCT-ID`` key order.
     * :param pageable: the paging directive (page number and size) supplied by
     *     the batch reader for chunked iteration.
     * :return: the requested page of accounts ordered by ``acctId`` ascending;
     *     an empty list once no further accounts remain.
     */
    List<Account> findAllByOrderByAcctIdAsc(Pageable pageable);
}

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

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * :purpose: Advance the account's optimistic-lock version by one, for a ``COACTUPC``
     *     rewrite whose only changed fields live on the CUSTOMER record.
     * :param acctId: the eleven-digit account identifier being updated.
     * :output: the number of rows advanced -- ``1`` for a resolved account, ``0`` otherwise.
     * :note: ``COACTUPC`` rewrites the account AND the customer as ONE logical unit, so the
     *     account is the ROOT of that aggregate and its version has to change whenever any part of
     *     the aggregate changes. When only customer fields change the account row stays clean, so
     *     the provider never advances the counter, the ``version`` the caller echoed back still
     *     matched, and a second writer working from the same display silently overwrote the first
     *     writer's customer change. One atomic statement advances the root token so that stale
     *     submission becomes the verbatim conflict outcome instead (AAP 0.6.2)
     *     [app/cbl/COACTUPC.cbl:L517-523, L4066-4090].
     * :note: Expressed as a bulk statement rather than by mutating the managed entity's
     *     ``@Version`` property: the entity is deliberately NOT dirty on this path, and writing
     *     its version field would make the provider issue its own update with the stale loaded
     *     version in the ``WHERE`` clause and fail spuriously. The statement is flushed after the
     *     aggregate's own writes (``flushAutomatically``) so ordering is preserved, and the
     *     persistence context is left untouched.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Account a set a.version = a.version + 1 where a.acctId = :acctId")
    int advanceAggregateVersion(@Param("acctId") Long acctId);

    /**
     * :purpose: Read back an account's stored optimistic-lock version without disturbing the
     *     persistence context, so the response echoes the value the database now holds.
     * :param acctId: the eleven-digit account identifier.
     * :output: the stored version, or an empty ``Optional`` when no such account exists.
     */
    @Query("select a.version from Account a where a.acctId = :acctId")
    Optional<Long> findVersionByAcctId(@Param("acctId") Long acctId);
}

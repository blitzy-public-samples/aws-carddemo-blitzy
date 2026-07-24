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
package com.carddemo.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carddemo.common.domain.SecurityUser;

/**
 * Spring Data JPA repository for the ``security_users`` store.
 *
 * :purpose: Replaces the legacy keyed/browse VSAM access of file ``USRSEC``
 *     used by the administrator user-CRUD programs ``COUSR00C``/``COUSR01C``/
 *     ``COUSR02C``/``COUSR03C``. The inherited CRUD surface covers the keyed
 *     insert, update, and delete operations, while the declared derived
 *     queries cover the ordered browse and cursor paging of the user list.
 * :output: managed ``SecurityUser`` instances keyed by the eight-character
 *     ``secUsrId`` identifier.
 */
@Repository
public interface UserRepository extends JpaRepository<SecurityUser, String> {

    /**
     * :purpose: page-based user list ordered by ascending user id (``CU00``).
     * :param pageable: page request (typically size 10).
     * :output: one page of users in ascending ``secUsrId`` order.
     */
    Page<SecurityUser> findAllByOrderBySecUsrIdAsc(Pageable pageable);

    /**
     * :purpose: keyset forward paging (PF8) from a given user id.
     * :param afterId: exclusive lower-bound user id.
     * :param pageable: page-size limit for the slice.
     * :output: next users with ``secUsrId`` greater than ``afterId``, ascending.
     */
    List<SecurityUser> findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(String afterId, Pageable pageable);

    /**
     * :purpose: keyset backward paging (PF7) from a given user id.
     * :param beforeId: exclusive upper-bound user id.
     * :param pageable: page-size limit for the slice.
     * :output: previous users with ``secUsrId`` less than ``beforeId``, descending.
     */
    List<SecurityUser> findBySecUsrIdLessThanOrderBySecUsrIdDesc(String beforeId, Pageable pageable);

    /**
     * :purpose: keyed single-user lookup (update/delete read).
     * :param secUsrId: the eight-character user id (``SEC-USR-ID``).
     * :output: the matching user, or empty when none exists.
     */
    Optional<SecurityUser> findBySecUsrId(String secUsrId);

    /**
     * :purpose: existence check for add-flow duplicate detection.
     * :param secUsrId: the eight-character user id (``SEC-USR-ID``).
     * :output: ``true`` when the id already exists, else ``false``.
     */
    boolean existsBySecUsrId(String secUsrId);
}

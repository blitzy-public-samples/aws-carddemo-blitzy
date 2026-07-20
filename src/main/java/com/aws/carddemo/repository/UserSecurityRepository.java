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

import com.aws.carddemo.domain.UserSecurity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link UserSecurity} application user records.
 *
 * <p>Migrated from the legacy COBOL VSAM key-sequenced data set
 * {@code USRSEC.VSAM.KSDS} (record layout copybook {@code CSUSR01Y.cpy},
 * {@code KEYLEN=8}, {@code RKP=0}) to the relational table
 * {@code user_security}, keyed by {@code sec_usr_id}. This repository replaces
 * the record-at-a-time CICS file control used by the online programs with
 * set-based, declarative data access while preserving the observable behavior
 * of every legacy access path.</p>
 *
 * <p><strong>Legacy access paths reproduced:</strong></p>
 * <ul>
 *   <li>{@code legacy/cbl/COSGN00C.cbl} &mdash; sign-on performs a keyed
 *       {@code EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)}; reproduced by
 *       {@link #findBySecUsrId(String)}.</li>
 *   <li>{@code legacy/cbl/COUSR00C.cbl} &mdash; the admin user-list screen
 *       browses {@code USRSEC} in ascending key order
 *       ({@code STARTBR}/{@code READNEXT}); reproduced by
 *       {@link #findAllByOrderBySecUsrIdAsc()}.</li>
 *   <li>{@code legacy/cbl/COUSR01C.cbl} (add, {@code WRITE}),
 *       {@code legacy/cbl/COUSR02C.cbl} (update, {@code REWRITE}), and
 *       {@code legacy/cbl/COUSR03C.cbl} (delete, {@code DELETE}) are served by
 *       the inherited {@link JpaRepository#save(Object)},
 *       {@link JpaRepository#deleteById(Object)}, and
 *       {@link JpaRepository#existsById(Object)} operations, so no additional
 *       declarations are required here.</li>
 * </ul>
 *
 * <p><strong>Role semantics.</strong> The {@code sec_usr_type} value carried by
 * each returned entity preserves the COBOL role condition names ({@code 'A'} =
 * administrator, {@code 'U'} = standard user) and drives the security layer's
 * {@code UserDetailsService} and {@code SignonService}.</p>
 *
 * <p><strong>Security.</strong> Credential handling is a concern of the security
 * layer, not this repository: no password comparison or hashing is performed
 * here, and the returned {@link UserSecurity#getSecUsrPwd() password} must never
 * be written to logs.</p>
 *
 * @see UserSecurity
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

    /**
     * Sign-on / authentication lookup by user id.
     *
     * <p>Reproduces the keyed {@code READ} of the {@code USRSEC} KSDS performed
     * at sign-on ({@code legacy/cbl/COSGN00C.cbl}) and feeds the Spring Security
     * {@code UserDetailsService} and {@code SignonService}. An empty
     * {@link Optional} models the CICS {@code NOTFND} (record-not-found) outcome
     * cleanly, replacing the legacy {@code FILE STATUS} / {@code RESP}
     * inspection.</p>
     *
     * <p>Because {@code secUsrId} is the entity identifier, this resolves to a
     * primary-key lookup. It is declared explicitly (in addition to the
     * inherited {@code findById}) to give the sign-on and security callers an
     * intention-revealing, domain-named entry point.</p>
     *
     * @param secUsrId the user id to look up (primary key, up to 8 characters)
     * @return an {@link Optional} containing the matching {@link UserSecurity},
     *         or {@link Optional#empty()} if no user has that id
     */
    Optional<UserSecurity> findBySecUsrId(String secUsrId);

    /**
     * Sequential ascending read of all users by user id for the admin
     * user-list screen ({@code legacy/cbl/COUSR00C.cbl}), which browses the
     * {@code USRSEC} data set in key order.
     *
     * <p>Preserves the ascending key ordering of the legacy
     * {@code STARTBR}/{@code READNEXT} browse so that the listing is presented
     * in the same sequence as the original 3270 screen.</p>
     *
     * @return all {@link UserSecurity} records ordered by {@code secUsrId}
     *         ascending; an empty list if no users exist
     */
    List<UserSecurity> findAllByOrderBySecUsrIdAsc();

    /**
     * Database-paginated ascending browse starting at or after a user-id key for
     * the admin user-list screen ({@code legacy/cbl/COUSR00C.cbl}), reproducing the
     * {@code STARTBR RIDFLD(SEC-USR-ID)} reposition followed by a
     * {@code READNEXT} page.
     *
     * <p>This is the {@code >=}-anchored, {@link Pageable}-windowed analogue of
     * {@link #findAllByOrderBySecUsrIdAsc()}: the {@code WHERE sec_usr_id >= :key}
     * predicate reproduces the legacy browse reposition (because the fixed-width,
     * upper-cased {@code PIC X(08)} key makes lexicographic ordering equal to the
     * VSAM key ordering) and the database performs the {@code LIMIT}/{@code OFFSET}
     * windowing plus a single {@code COUNT}. The row ordering follows the
     * {@link Pageable}'s {@code Sort}; callers supply ascending {@code secUsrId} to
     * preserve the legacy screen sequence. It exists so the online list can page
     * from a key without loading the whole table into memory, mirroring the
     * transaction-list browse ({@code TransactionRepository.findByTranIdGreaterThanEqual}).</p>
     *
     * @param secUsrId the inclusive start-key user id (already normalized by the
     *                 service to the stored upper-cased form)
     * @param pageable the page window (page number, size, and ascending
     *                 {@code secUsrId} sort) to return
     * @return the requested {@link Page} of users with {@code secUsrId >=} the key,
     *         reporting the total number of matching rows
     */
    Page<UserSecurity> findBySecUsrIdGreaterThanEqual(String secUsrId, Pageable pageable);

    /**
     * Counts the users whose id is at or after a given key, without loading any
     * rows &mdash; the efficient {@code COUNT} that lets the online list position
     * the browse cursor for backward/forward paging ({@code COUSR00C}
     * {@code PROCESS-PF7-KEY}/{@code PROCESS-PF8-KEY}) instead of reading the whole
     * table. It is the {@code >=}-anchored companion to the inherited
     * {@link JpaRepository#count()} grand total.
     *
     * @param secUsrId the inclusive start-key user id (already normalized by the
     *                 service to the stored upper-cased form)
     * @return the number of users with {@code secUsrId >=} the key
     */
    long countBySecUsrIdGreaterThanEqual(String secUsrId);
}

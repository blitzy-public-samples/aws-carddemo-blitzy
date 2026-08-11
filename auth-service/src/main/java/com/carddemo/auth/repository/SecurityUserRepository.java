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
package com.carddemo.auth.repository;

import com.carddemo.common.domain.SecurityUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * :purpose: Spring Data JPA repository for the ``security_users`` store; replaces
 *     the legacy keyed VSAM read of file ``USRSEC`` performed by the
 *     ``READ-USER-SEC-FILE`` paragraph of ``app/cbl/COSGN00C.cbl``. Extending
 *     {@link JpaRepository} inherits the standard CRUD surface (including a
 *     primary-key ``findById`` lookup) over the {@link SecurityUser} entity,
 *     whose ``String`` identifier is the eight-character ``SEC-USR-ID`` key.
 * :output: {@link SecurityUser} aggregates keyed by their upper-cased user id.
 */
@Repository
public interface SecurityUserRepository extends JpaRepository<SecurityUser, String> {

    /**
     * :purpose: Look up a single security user by primary key, mirroring the
     *     legacy keyed exact-match read of the ``USRSEC`` file.
     * :param secUsrId: the eight-character user id (already upper-cased by the
     *     calling service layer); used verbatim as the primary-key match value.
     * :output: an ``Optional`` containing the matching {@link SecurityUser}, or an
     *     empty ``Optional`` when no row matches (the user-not-found path).
     */
    Optional<SecurityUser> findBySecUsrId(String secUsrId);
}

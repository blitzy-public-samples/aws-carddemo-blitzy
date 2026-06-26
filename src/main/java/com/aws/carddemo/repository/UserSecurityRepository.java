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
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link UserSecurity} (table {@code user_security}).
 *
 * <p>Replaces legacy VSAM {@code USRSEC} KSDS access (copybook {@code CSUSR01Y}). Primary key
 * {@code sec_usr_id} ({@code String}, char(8)). {@code findById} backs the sign-on flow ({@code
 * COSGN00C}) via Spring Security's {@code UserDetailsService} and is used by the bootstrap
 * credential seeder; {@code save}/{@code deleteById} back the admin user-management screens ({@code
 * COUSR01C}-{@code COUSR03C}). A missing record returns {@link java.util.Optional#empty()} from
 * {@code findById} (FILE STATUS {@code '23'}). Passwords are BCrypt hashes managed by the security
 * layer; this repository performs no credential logic.
 *
 * <p><strong>Keyset (range) browse methods.</strong> The {@code COUSR00C} user-list screen ({@code
 * CU00}, admin) is a pseudo-conversational STARTBR / READNEXT / READPREV browse of {@code USRSEC}
 * ascending by {@code sec_usr_id}. To preserve that PF7/PF8 paging semantics <em>without</em>
 * reading the entire table on every page turn (the unbounded {@code findAll(Sort)} read that the
 * performance audit flagged), the service issues bounded keyset queries that fetch only {@code
 * page_size + 1} rows (the page plus a single read-ahead peek). {@code GreaterThanEqual} reproduces
 * {@code STARTBR} GTEQ (refresh / browse from the top when the key is the empty string); {@code
 * GreaterThan} reproduces the {@code READNEXT} that steps past the previous page's last id (PF8);
 * {@code LessThan ... Desc} reproduces the {@code READPREV} walk (PF7), the caller reversing the
 * descending slice to ascending.
 *
 * <p>These browse methods return the {@link UserListProjection} closed projection (user id, first
 * name, last name, type) rather than the full entity, so the {@code sec_usr_pwd} BCrypt hash is
 * never selected for a list view (the screen does not display it). The entity-returning {@code
 * findById}/{@code save} accessors remain for the authentication and add/update/delete flows that
 * legitimately require the full record.
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

  /**
   * Forward keyset page anchored inclusively at {@code startKey} (STARTBR GTEQ; also the
   * from-the-top browse when {@code startKey} is the empty string). Returns up to the {@code
   * Pageable} limit of users with {@code sec_usr_id >= startKey} ascending, projected to the four
   * displayed columns only.
   *
   * @param startKey the inclusive lower-bound user id (empty string browses from the top)
   * @param pageable the row limit (typically {@code PageRequest.of(0, pageSize + 1)})
   * @return the bounded ascending page of projections (possibly empty)
   */
  List<UserListProjection> findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
      String startKey, Pageable pageable);

  /**
   * Forward keyset page anchored exclusively after {@code startKey} (READNEXT past the previous
   * page's last id; PF8). Returns up to the {@code Pageable} limit of users with {@code sec_usr_id
   * > startKey} ascending, projected to the four displayed columns only.
   *
   * @param startKey the exclusive lower-bound user id (the previous page's last id)
   * @param pageable the row limit (typically {@code PageRequest.of(0, pageSize + 1)})
   * @return the bounded ascending page of projections (possibly empty)
   */
  List<UserListProjection> findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(
      String startKey, Pageable pageable);

  /**
   * Backward keyset page strictly below {@code startKey}, descending (READPREV; PF7). The caller
   * reverses the returned descending slice to present it ascending. Returns up to the {@code
   * Pageable} limit of users with {@code sec_usr_id < startKey} descending, projected to the four
   * displayed columns only.
   *
   * @param startKey the exclusive upper-bound user id (the current page's first id)
   * @param pageable the row limit (typically {@code PageRequest.of(0, pageSize + 1)})
   * @return the bounded descending slice of projections (possibly empty); reverse for ascending
   *     display
   */
  List<UserListProjection> findBySecUsrIdLessThanOrderBySecUsrIdDesc(
      String startKey, Pageable pageable);

  /**
   * Whether any user sorts strictly after {@code key} (read-ahead peek reproducing the legacy
   * {@code NEXT-PAGE} recomputation for the PF8 next-page decision).
   *
   * @param key the current page's last user id
   * @return {@code true} when a further page exists
   */
  boolean existsBySecUsrIdGreaterThan(String key);
}

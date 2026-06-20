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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link UserSecurity} (table {@code user_security}).
 *
 * <p>Replaces legacy VSAM {@code USRSEC} KSDS access (copybook {@code CSUSR01Y}). Primary key
 * {@code sec_usr_id} ({@code String}, char(8)). {@code findById} backs the sign-on flow ({@code
 * COSGN00C}) via Spring Security's {@code UserDetailsService} and is used by the bootstrap
 * credential seeder; {@code findAll}/{@code save}/{@code deleteById} back the admin user-management
 * screens ({@code COUSR00C}-{@code COUSR03C}). A missing record returns {@link
 * java.util.Optional#empty()} from {@code findById} (FILE STATUS {@code '23'}). Passwords are
 * BCrypt hashes managed by the security layer; this repository performs no credential logic.
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {}

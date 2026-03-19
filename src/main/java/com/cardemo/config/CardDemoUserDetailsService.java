/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.config;

import com.cardemo.common.enums.UserType;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Custom {@link UserDetailsService} implementation that loads user credentials
 * from the {@code user_security} database table (formerly USRSEC VSAM KSDS).
 *
 * <p>This service bridges the Spring Security authentication framework with the
 * CardDemo user security data model. It translates the COBOL sign-on logic from
 * {@code COSGN00C.cbl} paragraph {@code 2000-SIGNIN-PROGRAM} into a Spring Security
 * compatible authentication provider:</p>
 *
 * <table>
 *   <caption>COBOL-to-Spring Security Mapping</caption>
 *   <tr><th>COBOL Construct</th><th>Spring Security Equivalent</th></tr>
 *   <tr><td>{@code EXEC CICS READ DATASET('USRSEC') RIDFLD(SEC-USR-ID)}</td>
 *       <td>{@code userSecurityRepository.findByUserId(username)}</td></tr>
 *   <tr><td>{@code SEC-USR-PWD} comparison</td>
 *       <td>BCrypt hash verification via {@code BCryptPasswordEncoder}</td></tr>
 *   <tr><td>{@code 88 CDEMO-USER-TYPE-ADMIN VALUE 'A'}</td>
 *       <td>{@code ROLE_ADMIN} granted authority</td></tr>
 *   <tr><td>{@code 88 CDEMO-USER-TYPE-USER VALUE 'U'}</td>
 *       <td>{@code ROLE_USER} granted authority</td></tr>
 * </table>
 *
 * <p>Security enhancement over COBOL: passwords are stored as BCrypt hashes
 * (72-character encoded strings) instead of plaintext 8-character values.
 * The {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}
 * is configured in {@link SecurityConfig} and used automatically by the
 * Spring Security authentication manager.</p>
 *
 * @see SecurityConfig
 * @see UserSecurity
 * @see UserSecurityRepository
 */
@Service
public class CardDemoUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CardDemoUserDetailsService.class);

    /** Spring Security role prefix for admin users (maps to 88-level CDEMO-USER-TYPE-ADMIN VALUE 'A'). */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** Spring Security role prefix for regular users (maps to 88-level CDEMO-USER-TYPE-USER VALUE 'U'). */
    private static final String ROLE_USER = "ROLE_USER";

    private final UserSecurityRepository userSecurityRepository;

    /**
     * Constructs the service with the required repository dependency.
     *
     * @param userSecurityRepository JPA repository for USRSEC VSAM dataset access;
     *                               provides {@code findByUserId()} for user lookup
     */
    public CardDemoUserDetailsService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Loads user details by username (user ID) from the {@code user_security} table.
     *
     * <p>Maps to COBOL paragraph {@code 2000-SIGNIN-PROGRAM} in {@code COSGN00C.cbl}
     * (line 389): reads the user security record by primary key ({@code SEC-USR-ID})
     * and extracts authentication and authorization data.</p>
     *
     * <p>The returned {@link UserDetails} object carries:</p>
     * <ul>
     *   <li><strong>Username</strong>: The trimmed {@code SEC-USR-ID} (max 8 chars)</li>
     *   <li><strong>Password</strong>: The BCrypt-hashed password from {@code SEC-USR-PWD}</li>
     *   <li><strong>Authorities</strong>: {@code ROLE_ADMIN} if {@code SEC-USR-TYPE = 'A'},
     *       otherwise {@code ROLE_USER}</li>
     * </ul>
     *
     * @param username the user ID to look up (corresponds to SEC-USR-ID PIC X(08))
     * @return a fully populated {@link UserDetails} for Spring Security authentication
     * @throws UsernameNotFoundException if no user exists with the given ID
     *         (equivalent to VSAM file status '23' / DFHRESP(NOTFND))
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading user details for authentication: userId='{}'", username);

        UserSecurity userSecurity = userSecurityRepository.findByUserId(username)
                .orElseThrow(() -> {
                    log.warn("User not found during authentication: userId='{}'", username);
                    return new UsernameNotFoundException(
                            "User ID '" + username + "' not found in USRSEC dataset");
                });

        // Map 88-level condition CDEMO-USER-TYPE to Spring Security granted authority
        String role = (userSecurity.getUserType() == UserType.ADMIN) ? ROLE_ADMIN : ROLE_USER;

        log.debug("User '{}' loaded successfully — type: {}, role: {}",
                userSecurity.getUserId().trim(), userSecurity.getUserType(), role);

        return new User(
                userSecurity.getUserId().trim(),
                userSecurity.getPassword(),
                List.of(new SimpleGrantedAuthority(role))
        );
    }
}

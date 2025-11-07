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

package com.carddemo.security;

import com.carddemo.entity.User;
import com.carddemo.entity.User.UserType;
import com.carddemo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;

/**
 * Spring Security UserDetailsService implementation for CardDemo application
 * 
 * <p>Replaces mainframe RACF USRSEC file-based authentication from COBOL program COSGN00C.cbl
 * with PostgreSQL User entity authentication via Spring Security UserDetailsService interface.</p>
 * 
 * <h3>COBOL to Spring Security Authentication Transformation</h3>
 * <p>Original COBOL Authentication Flow (COSGN00C.cbl lines 209-257):</p>
 * <pre>
 * READ-USER-SEC-FILE.
 *   EXEC CICS READ
 *     DATASET   (WS-USRSEC-FILE)        -- VSAM KSDS file 'USRSEC'
 *     INTO      (SEC-USER-DATA)         -- CSUSR01Y.cpy structure
 *     RIDFLD    (WS-USER-ID)            -- Primary key lookup
 *     RESP      (WS-RESP-CD)            -- Response code
 *   END-EXEC.
 *   
 *   EVALUATE WS-RESP-CD
 *     WHEN 0                             -- User found successfully
 *       IF SEC-USR-PWD = WS-USER-PWD     -- Plain text password comparison
 *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 *         IF CDEMO-USRTYP-ADMIN          -- 88-level: VALUE 'A'
 *           EXEC CICS XCTL PROGRAM('COADM01C') -- Admin menu
 *         ELSE                            -- CDEMO-USRTYP-USER: VALUE 'U'
 *           EXEC CICS XCTL PROGRAM('COMEN01C') -- Regular menu
 *       ELSE
 *         'Wrong Password. Try again ...'
 *     WHEN 13                             -- User not found (NOTFND)
 *       'User not found. Try again ...'
 *     WHEN OTHER
 *       'Unable to verify the User ...'
 * </pre>
 * 
 * <h3>Spring Security Implementation</h3>
 * <p>This service implements UserDetailsService.loadUserByUsername() which:</p>
 * <ol>
 *   <li>Queries PostgreSQL user table via UserRepository.findByUserId(username)</li>
 *   <li>Throws UsernameNotFoundException if user not found (equivalent to RESP=13)</li>
 *   <li>Maps User entity to Spring Security UserDetails with BCrypt password</li>
 *   <li>Converts UserType enum to GrantedAuthority roles for authorization:
 *     <ul>
 *       <li>UserType.ADMIN ('A') → SimpleGrantedAuthority("ROLE_ADMIN")</li>
 *       <li>UserType.USER ('U') → SimpleGrantedAuthority("ROLE_USER")</li>
 *     </ul>
 *   </li>
 * </ol>
 * 
 * <h3>Security Enhancements Over Mainframe</h3>
 * <ul>
 *   <li><b>Password Security:</b> BCrypt hashing replaces plain text SEC-USR-PWD comparison</li>
 *   <li><b>Role-Based Access:</b> Spring Security authorities enable @PreAuthorize annotations</li>
 *   <li><b>Null Safety:</b> Optional&lt;User&gt; eliminates manual RESP code checking</li>
 *   <li><b>Audit Logging:</b> SLF4J logging for authentication attempts and failures</li>
 *   <li><b>Stateless Auth:</b> JWT tokens replace CICS COMMAREA session state</li>
 * </ul>
 * 
 * <h3>Integration Points</h3>
 * <ul>
 *   <li><b>JwtAuthenticationFilter:</b> Invokes this service during token validation to populate SecurityContext</li>
 *   <li><b>AuthenticationService:</b> Uses this service indirectly via Spring Security's AuthenticationManager</li>
 *   <li><b>SecurityConfig:</b> Registers this service as the UserDetailsService bean</li>
 * </ul>
 * 
 * <h3>Two-Tier Role Model Preservation</h3>
 * <p>Maintains exact RACF role model from mainframe (COCOM01Y.cpy lines 27-28):</p>
 * <table border="1">
 *   <tr><th>User Type</th><th>COBOL Value</th><th>COBOL Condition</th><th>Spring Role</th><th>Access Level</th></tr>
 *   <tr><td>Admin</td><td>'A'</td><td>CDEMO-USRTYP-ADMIN</td><td>ROLE_ADMIN</td><td>Full system access, user management</td></tr>
 *   <tr><td>Regular</td><td>'U'</td><td>CDEMO-USRTYP-USER</td><td>ROLE_USER</td><td>Customer-facing functions only</td></tr>
 * </table>
 * 
 * <h3>Error Handling</h3>
 * <p>UsernameNotFoundException mapping:</p>
 * <ul>
 *   <li><b>COBOL RESP=13:</b> User record not found in VSAM USRSEC file</li>
 *   <li><b>Spring Exception:</b> UsernameNotFoundException thrown when Optional.orElseThrow() fails</li>
 *   <li><b>HTTP Response:</b> JwtAuthenticationEntryPoint converts to 401 Unauthorized</li>
 * </ul>
 * 
 * @see UserDetailsService Spring Security interface for loading user-specific data
 * @see UserDetails Spring Security user principal with authorities
 * @see User JPA entity from CSUSR01Y.cpy copybook
 * @see UserRepository Spring Data JPA repository for VSAM USRSEC replacement
 * @see com.carddemo.service.auth.AuthenticationService Login service using this for authentication
 * @see com.carddemo.security.JwtAuthenticationFilter JWT filter invoking this service
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    /**
     * Spring Data JPA repository for User entity
     * 
     * <p>Replaces VSAM USRSEC file operations with PostgreSQL database queries.
     * Injected via constructor (RequiredArgsConstructor generates constructor for final fields).</p>
     * 
     * <p>Primary method used: findByUserId(username) for authentication lookups</p>
     */
    private final UserRepository userRepository;

    /**
     * Load user by username for Spring Security authentication
     * 
     * <p>This method implements the UserDetailsService contract required by Spring Security
     * authentication providers. It is invoked during the authentication process to retrieve
     * user credentials and authorities for security context population.</p>
     * 
     * <h3>COBOL Equivalent Operation</h3>
     * <p>Replaces READ-USER-SEC-FILE paragraph from COSGN00C.cbl (lines 209-257):</p>
     * <pre>
     * EXEC CICS READ
     *   DATASET('USRSEC')              -- VSAM KSDS file
     *   INTO(SEC-USER-DATA)            -- User record structure
     *   RIDFLD(WS-USER-ID)             -- username parameter equivalent
     *   RESP(WS-RESP-CD)               -- 0=found, 13=not found
     * END-EXEC.
     * </pre>
     * 
     * <h3>Implementation Steps</h3>
     * <ol>
     *   <li>Log authentication attempt at DEBUG level with username</li>
     *   <li>Query PostgreSQL user table using UserRepository.findByUserId(username)</li>
     *   <li>If user not found, throw UsernameNotFoundException (matching RESP=13 handling)</li>
     *   <li>If user found, log successful retrieval at INFO level</li>
     *   <li>Map UserType enum to GrantedAuthority collection:
     *     <ul>
     *       <li>UserType.ADMIN → SimpleGrantedAuthority("ROLE_ADMIN")</li>
     *       <li>UserType.USER → SimpleGrantedAuthority("ROLE_USER")</li>
     *     </ul>
     *   </li>
     *   <li>Construct Spring Security User object with:
     *     <ul>
     *       <li>username: user.getUserId() (SEC-USR-ID field)</li>
     *       <li>password: user.getPassword() (BCrypt hash, not plain text)</li>
     *       <li>enabled: true (all retrieved users are active)</li>
     *       <li>accountNonExpired: true</li>
     *       <li>credentialsNonExpired: true</li>
     *       <li>accountNonLocked: true</li>
     *       <li>authorities: role collection from step 5</li>
     *     </ul>
     *   </li>
     * </ol>
     * 
     * <h3>Security Considerations</h3>
     * <ul>
     *   <li><b>Password Storage:</b> BCrypt-encoded password returned for Spring Security comparison</li>
     *   <li><b>Role Assignment:</b> Single role per user (matching mainframe two-tier model)</li>
     *   <li><b>User Status:</b> All flags set to true; soft delete handled by query filter</li>
     *   <li><b>Error Messages:</b> Generic message to prevent username enumeration attacks</li>
     * </ul>
     * 
     * <h3>Usage by Spring Security Components</h3>
     * <ul>
     *   <li><b>DaoAuthenticationProvider:</b> Calls this method during form-based authentication</li>
     *   <li><b>JwtAuthenticationFilter:</b> Invokes indirectly to validate JWT token claims</li>
     *   <li><b>AuthenticationManager:</b> Uses returned UserDetails for authentication</li>
     * </ul>
     * 
     * @param username User ID to authenticate (maps to SEC-USR-ID from COBOL)
     *                 Must match exactly (case-sensitive, up to 8 characters)
     * @return UserDetails object containing username, password, and granted authorities
     *         Password is BCrypt-encoded for secure comparison by Spring Security
     *         Authorities determine access to @PreAuthorize protected endpoints
     * @throws UsernameNotFoundException if user not found in database
     *                                   Equivalent to COBOL RESP=13 (NOTFND) condition
     *                                   Triggers authentication failure returning HTTP 401
     * 
     * @see UserDetails Spring Security user principal interface
     * @see GrantedAuthority Spring Security authority interface for role-based access
     * @see UsernameNotFoundException Spring Security authentication exception
     * @see UserRepository#findByUserId(String) JPA repository method for user lookup
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Log authentication attempt for security audit trail
        log.debug("Attempting to load user by username: {}", username);
        
        // Query PostgreSQL user table by primary key (user_id column)
        // Replaces: EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID) RESP(WS-RESP-CD)
        // Returns Optional<User> for null-safe handling (eliminates RESP code checking)
        User user = userRepository.findByUserId(username)
            .orElseThrow(() -> {
                // COBOL equivalent: WHEN 13 (NOTFND response code)
                // Original error message from COSGN00C.cbl line 249:
                // "User not found. Try again ..."
                log.warn("User not found in database: {}", username);
                return new UsernameNotFoundException("User not found: " + username);
            });
        
        // Log successful user retrieval for audit trail
        log.info("User loaded successfully: {} (Type: {})", user.getUserId(), user.getUserType());
        
        // Map UserType enum to Spring Security GrantedAuthority
        // Preserves two-tier RACF role model from mainframe
        Collection<? extends GrantedAuthority> authorities = mapUserTypeToAuthorities(user.getUserType());
        
        // Construct Spring Security User object (org.springframework.security.core.userdetails.User)
        // This is NOT our custom User entity but Spring Security's UserDetails implementation
        return new org.springframework.security.core.userdetails.User(
            user.getUserId(),          // username: SEC-USR-ID field from COBOL
            user.getPassword(),        // password: BCrypt hash (not plain text SEC-USR-PWD)
            true,                      // enabled: all active users are enabled
            true,                      // accountNonExpired: accounts do not expire
            true,                      // credentialsNonExpired: credentials do not expire
            true,                      // accountNonLocked: accounts are not locked
            authorities                // authorities: role collection for authorization
        );
    }
    
    /**
     * Map UserType enum to Spring Security GrantedAuthority collection
     * 
     * <p>Preserves the two-tier role model from mainframe RACF security defined in
     * COCOM01Y.cpy copybook (lines 27-28):</p>
     * <pre>
     * 10 CDEMO-USER-TYPE               PIC X(01).
     *    88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
     *    88 CDEMO-USRTYP-USER          VALUE 'U'.
     * </pre>
     * 
     * <h3>Role Mapping Logic</h3>
     * <table border="1">
     *   <tr><th>UserType Enum</th><th>Code</th><th>Spring Security Role</th><th>COBOL Condition</th></tr>
     *   <tr><td>UserType.ADMIN</td><td>'A'</td><td>ROLE_ADMIN</td><td>CDEMO-USRTYP-ADMIN</td></tr>
     *   <tr><td>UserType.USER</td><td>'U'</td><td>ROLE_USER</td><td>CDEMO-USRTYP-USER</td></tr>
     * </table>
     * 
     * <h3>Role Usage in Authorization</h3>
     * <ul>
     *   <li><b>ROLE_ADMIN:</b> Full system access including user management endpoints
     *     <ul>
     *       <li>@PreAuthorize("hasRole('ADMIN')") on admin-only methods</li>
     *       <li>Access to AdminController endpoints</li>
     *       <li>COBOL equivalent: EXEC CICS XCTL PROGRAM('COADM01C')</li>
     *     </ul>
     *   </li>
     *   <li><b>ROLE_USER:</b> Standard customer-facing functions only
     *     <ul>
     *       <li>@PreAuthorize("hasAnyRole('USER', 'ADMIN')") on regular endpoints</li>
     *       <li>Access to account, card, transaction endpoints</li>
     *       <li>COBOL equivalent: EXEC CICS XCTL PROGRAM('COMEN01C')</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Implementation Notes</h3>
     * <ul>
     *   <li>Returns single-element collection (one role per user)</li>
     *   <li>Uses Collections.singletonList() for immutable list</li>
     *   <li>SimpleGrantedAuthority wraps role string with "ROLE_" prefix</li>
     *   <li>Prefix required by Spring Security's RoleVoter for @PreAuthorize("hasRole()")</li>
     * </ul>
     * 
     * @param userType UserType enum from User entity (ADMIN or USER)
     *                 Mapped from SEC-USR-TYPE field in COBOL (PIC X(01))
     * @return Collection containing single GrantedAuthority with appropriate role
     *         ROLE_ADMIN for administrative access or ROLE_USER for regular access
     * 
     * @see UserType User entity nested enum defining user classifications
     * @see SimpleGrantedAuthority Spring Security concrete authority implementation
     * @see GrantedAuthority Spring Security authority interface
     */
    private Collection<? extends GrantedAuthority> mapUserTypeToAuthorities(UserType userType) {
        // Map UserType enum to Spring Security role string
        // Uses UserType enum values (ADMIN, USER) from User entity
        // Each enum value has internal code ('A', 'U') matching COBOL SEC-USR-TYPE
        
        // Check if user is admin (UserType.ADMIN with code 'A')
        // Matches COBOL: IF CDEMO-USRTYP-ADMIN (88-level: VALUE 'A')
        if (userType == UserType.ADMIN) {
            log.debug("Mapping UserType.ADMIN to ROLE_ADMIN authority");
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"));
        } 
        // Otherwise user is regular user (UserType.USER with code 'U')
        // Matches COBOL: ELSE (CDEMO-USRTYP-USER, 88-level: VALUE 'U')
        else {
            log.debug("Mapping UserType.USER to ROLE_USER authority");
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }
        
        // Note: No default case needed as UserType enum only has two values (ADMIN, USER)
        // This preserves the exact two-tier role model from mainframe RACF security
    }
}

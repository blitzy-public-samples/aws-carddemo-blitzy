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

import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Custom UserDetailsService implementation for Spring Security authentication.
 * 
 * <p><b>Converted from COBOL program:</b> COSGN00C.cbl (User signon and authentication)</p>
 * <p><b>Original copybook:</b> CSUSR01Y.cpy (SEC-USER-DATA structure)</p>
 * 
 * <p><b>Original function:</b> Load user credentials from VSAM USRSEC file for RACF authentication</p>
 * 
 * <h2>Conversion Notes</h2>
 * 
 * <h3>VSAM File Access to JPA Repository (COSGN00C.cbl lines 211-219)</h3>
 * <p>Replaces COBOL VSAM file read operation:</p>
 * <pre>
 * EXEC CICS READ
 *      DATASET   (WS-USRSEC-FILE)        ← VSAM USRSEC file
 *      INTO      (SEC-USER-DATA)         ← CSUSR01Y.cpy copybook structure
 *      RIDFLD    (WS-USER-ID)            ← Primary key lookup
 *      KEYLENGTH (LENGTH OF WS-USER-ID)  ← 8-character key
 *      RESP      (WS-RESP-CD)            ← Response code (0=success, 13=not found)
 * END-EXEC.
 * </pre>
 * 
 * <p>Java equivalent using JPA repository:</p>
 * <pre>
 * Optional&lt;UserSecurity&gt; userOpt = userSecurityRepository.findByUserId(userId);
 * if (userOpt.isEmpty()) {
 *     throw new UsernameNotFoundException("User not found: " + userId);
 * }
 * UserSecurity userSecurity = userOpt.get();
 * </pre>
 * 
 * <h3>COBOL Copybook to JPA Entity Mapping (CSUSR01Y.cpy lines 17-23)</h3>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Field</th>
 *     <th>COBOL Type</th>
 *     <th>Java Field</th>
 *     <th>Java Type</th>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-ID</td>
 *     <td>PIC X(08)</td>
 *     <td>userId</td>
 *     <td>String</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-FNAME</td>
 *     <td>PIC X(20)</td>
 *     <td>userFirstName</td>
 *     <td>String</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-LNAME</td>
 *     <td>PIC X(20)</td>
 *     <td>userLastName</td>
 *     <td>String</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-PWD</td>
 *     <td>PIC X(08)</td>
 *     <td>userPwdHash</td>
 *     <td>String (BCrypt)</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-TYPE</td>
 *     <td>PIC X(01)</td>
 *     <td>userType</td>
 *     <td>String</td>
 *   </tr>
 * </table>
 * 
 * <h3>Password Authentication Migration (COSGN00C.cbl line 223)</h3>
 * <p><b>COBOL plain-text comparison:</b></p>
 * <pre>
 * IF SEC-USR-PWD = WS-USER-PWD
 *     MOVE WS-USER-ID TO CDEMO-USER-ID
 *     ... (authentication success)
 * ELSE
 *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
 * END-IF.
 * </pre>
 * 
 * <p><b>Java BCrypt hash validation:</b></p>
 * <p>This service returns the BCrypt password hash to Spring Security framework.
 * The framework automatically validates the user-provided password against the hash
 * using BCryptPasswordEncoder. This eliminates plain-text password storage and
 * comparison as required by Section 0.7.9 Security Migration.</p>
 * 
 * <pre>
 * User.builder()
 *     .username(userId)
 *     .password(userSecurity.getUserPwdHash())  ← BCrypt hash, not plain text
 *     .authorities(authorities)
 *     .build();
 * </pre>
 * 
 * <h3>User Type to Granted Authority Mapping (COSGN00C.cbl line 227)</h3>
 * <p><b>COBOL user type routing logic:</b></p>
 * <pre>
 * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 * IF CDEMO-USRTYP-ADMIN          ← 88-level condition: SEC-USR-TYPE = 'A'
 *     EXEC CICS XCTL PROGRAM ('COADM01C') END-EXEC   ← Admin menu
 * ELSE
 *     EXEC CICS XCTL PROGRAM ('COMEN01C') END-EXEC   ← User menu
 * END-IF.
 * </pre>
 * 
 * <p><b>Java Spring Security role mapping:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Type</th>
 *     <th>COBOL Condition</th>
 *     <th>Spring Security Role</th>
 *     <th>Menu/Access</th>
 *   </tr>
 *   <tr>
 *     <td>'A'</td>
 *     <td>CDEMO-USRTYP-ADMIN</td>
 *     <td>ROLE_ADMIN</td>
 *     <td>Admin menu (COADM01C)</td>
 *   </tr>
 *   <tr>
 *     <td>'U'</td>
 *     <td>CDEMO-USRTYP-USER</td>
 *     <td>ROLE_USER</td>
 *     <td>Main menu (COMEN01C)</td>
 *   </tr>
 *   <tr>
 *     <td>'O'</td>
 *     <td>N/A (new)</td>
 *     <td>ROLE_OPERATOR</td>
 *     <td>Operator functions</td>
 *   </tr>
 * </table>
 * 
 * <h3>Error Handling (COSGN00C.cbl lines 247-251)</h3>
 * <p><b>COBOL error handling for record not found (RESP=13):</b></p>
 * <pre>
 * WHEN 13
 *     MOVE 'Y' TO WS-ERR-FLG
 *     MOVE 'User not found. Try again ...' TO WS-MESSAGE
 *     PERFORM SEND-SIGNON-SCREEN
 * </pre>
 * 
 * <p><b>Java exception handling:</b></p>
 * <pre>
 * if (userOpt.isEmpty()) {
 *     throw new UsernameNotFoundException("User not found: " + userId);
 * }
 * </pre>
 * 
 * <h2>RACF to Spring Security Migration (Section 0.7.9)</h2>
 * <ul>
 *   <li><b>RACF user profiles</b> → UserSecurity entity in PostgreSQL user_security table</li>
 *   <li><b>RACF password verification</b> → BCrypt hash validation via Spring Security</li>
 *   <li><b>RACF resource profiles</b> → Spring Security role-based access control (RBAC)</li>
 *   <li><b>RACF audit trail</b> → Spring Security audit logging (lastLoginTs, createdAt, updatedAt)</li>
 * </ul>
 * 
 * <h2>Data Store Transformation (Section 0.1.1)</h2>
 * <ul>
 *   <li><b>VSAM KSDS file</b> USRSEC → PostgreSQL table user_security</li>
 *   <li><b>Primary key</b>: SEC-USR-ID (8 chars) → user_id VARCHAR(8) with B-tree index</li>
 *   <li><b>Record structure</b>: SEC-USER-DATA copybook → UserSecurity JPA entity</li>
 *   <li><b>File I/O</b>: EXEC CICS READ → JPA repository findByUserId()</li>
 * </ul>
 * 
 * <h2>Performance Requirements (Section 0.7.7)</h2>
 * <ul>
 *   <li>User authentication queries MUST complete in sub-200ms to support transaction response time SLA</li>
 *   <li>Leverages PostgreSQL B-tree index on user_id column for sub-10ms primary key lookups</li>
 *   <li>Single database query per authentication attempt (no N+1 queries)</li>
 * </ul>
 * 
 * <h2>Usage in Spring Security Authentication Flow</h2>
 * <ol>
 *   <li>User submits login credentials (userId, password) via REST API</li>
 *   <li>Spring Security calls loadUserByUsername(userId)</li>
 *   <li>This service queries user_security table via UserSecurityRepository</li>
 *   <li>UserSecurity entity is mapped to Spring Security UserDetails with BCrypt hash</li>
 *   <li>Spring Security validates provided password against BCrypt hash automatically</li>
 *   <li>If valid, authentication token is created with granted authorities from user type</li>
 *   <li>JWT token is generated and returned to client for subsequent API requests</li>
 * </ol>
 * 
 * @see UserDetailsService Spring Security interface for loading user-specific data
 * @see UserSecurityRepository JPA repository for user_security table
 * @see UserSecurity JPA entity representing CSUSR01Y.cpy copybook structure
 * @see SecurityRoles Utility class for COBOL user type to Spring Security role mapping
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(CustomUserDetailsService.class);

    private final UserSecurityRepository userSecurityRepository;

    /**
     * Constructor injection for UserSecurityRepository.
     * 
     * <p>Spring automatically injects the UserSecurityRepository bean during
     * application startup. Constructor injection is preferred over field injection
     * for better testability and immutability.</p>
     * 
     * @param userSecurityRepository JPA repository for querying user_security table
     */
    public CustomUserDetailsService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
        logger.info("CustomUserDetailsService initialized with UserSecurityRepository");
    }

    /**
     * Load user-specific data for Spring Security authentication.
     * 
     * <p>This method is called by Spring Security during the authentication process
     * when a user attempts to log in. It replaces the COBOL VSAM file read operation
     * from COSGN00C.cbl lines 211-219.</p>
     * 
     * <h3>COBOL Operation Replaced</h3>
     * <pre>
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ
     *          DATASET   (WS-USRSEC-FILE)        ← 'USRSEC' VSAM file
     *          INTO      (SEC-USER-DATA)         ← CSUSR01Y.cpy structure
     *          RIDFLD    (WS-USER-ID)            ← userId parameter
     *          KEYLENGTH (LENGTH OF WS-USER-ID)  ← 8 characters
     *          RESP      (WS-RESP-CD)            ← 0=found, 13=not found
     *          RESP2     (WS-REAS-CD)
     *     END-EXEC.
     * 
     *     EVALUATE WS-RESP-CD
     *         WHEN 0                              ← Record found
     *             IF SEC-USR-PWD = WS-USER-PWD   ← Plain-text comparison
     *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *                 ... (authentication success)
     *             ELSE
     *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *             END-IF
     *         WHEN 13                             ← Record not found
     *             MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *         WHEN OTHER
     *             MOVE 'Unable to verify the User ...' TO WS-MESSAGE
     *     END-EVALUATE.
     * </pre>
     * 
     * <h3>Java Implementation</h3>
     * <ol>
     *   <li>Query user_security table using JPA repository: findByUserId(userId)</li>
     *   <li>If user not found, throw UsernameNotFoundException (equivalent to RESP=13)</li>
     *   <li>Extract BCrypt password hash from UserSecurity entity</li>
     *   <li>Convert COBOL user type ('A', 'U', 'O') to Spring Security GrantedAuthority</li>
     *   <li>Build Spring Security User object with username, password hash, and authorities</li>
     *   <li>Return UserDetails to Spring Security for password validation</li>
     * </ol>
     * 
     * <h3>Password Validation Migration</h3>
     * <p>Unlike COBOL which performs plain-text password comparison (line 223):
     * <code>IF SEC-USR-PWD = WS-USER-PWD</code>, this service returns the BCrypt
     * password hash to Spring Security. The framework automatically validates the
     * user-provided password against the hash using BCryptPasswordEncoder configured
     * in SecurityConfig. This ensures passwords are never stored or compared in plain text.</p>
     * 
     * <h3>Authority Mapping</h3>
     * <p>The COBOL user type (SEC-USR-TYPE) is converted to Spring Security GrantedAuthority
     * using SecurityRoles.fromUserType() method:</p>
     * <ul>
     *   <li>'A' (CDEMO-USRTYP-ADMIN) → ROLE_ADMIN</li>
     *   <li>'U' (CDEMO-USRTYP-USER) → ROLE_USER</li>
     *   <li>'O' (new) → ROLE_OPERATOR</li>
     * </ul>
     * 
     * <p>These roles control access to REST endpoints via @PreAuthorize annotations,
     * replacing COBOL's EXEC CICS XCTL program routing logic (lines 231-239).</p>
     * 
     * @param username the user ID entered during login (corresponds to SEC-USR-ID from CSUSR01Y.cpy).
     *                 This is an 8-character user ID, case-sensitive as stored in the database.
     *                 Spring Security calls this parameter "username" per UserDetailsService contract,
     *                 but it represents the userId field in CardDemo.
     * 
     * @return UserDetails object containing:
     *         <ul>
     *           <li><b>username</b>: userId from user_security table</li>
     *           <li><b>password</b>: BCrypt hash from userPwdHash column (not plain text)</li>
     *           <li><b>authorities</b>: Single GrantedAuthority based on userType ('A', 'U', 'O')</li>
     *           <li><b>accountNonExpired</b>: true (no expiration logic in COBOL)</li>
     *           <li><b>accountNonLocked</b>: true (no account locking in COBOL)</li>
     *           <li><b>credentialsNonExpired</b>: true (no password expiration in COBOL)</li>
     *           <li><b>enabled</b>: true (no enable/disable flag in COBOL)</li>
     *         </ul>
     * 
     * @throws UsernameNotFoundException if user cannot be found in user_security table.
     *         This is equivalent to COBOL RESP=13 (record not found) error handling
     *         from COSGN00C.cbl lines 247-251. The exception message will be displayed
     *         to the user as "User not found. Try again ..." matching COBOL behavior.
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        logger.debug("Attempting to load user details for userId: {}", username);

        // Query user_security table using JPA repository
        // Replaces: EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)
        // Using findByUserId which is functionally equivalent to findById for clarity
        UserSecurity userSecurity = userSecurityRepository.findByUserId(username)
            .orElseThrow(() -> {
                // Equivalent to COBOL RESP=13 (record not found) from line 247
                logger.warn("User not found in database: {}", username);
                return new UsernameNotFoundException("User not found: " + username);
            });

        logger.debug("User found in database: userId={}, userType={}", 
                    userSecurity.getUserId(), userSecurity.getUserType());

        // Convert COBOL user type to Spring Security GrantedAuthority
        // Replaces: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE (line 227)
        // Maps: 'A' → ROLE_ADMIN, 'U' → ROLE_USER, 'O' → ROLE_OPERATOR
        String roleName = SecurityRoles.fromUserType(userSecurity.getUserType());
        GrantedAuthority authority = new SimpleGrantedAuthority(roleName);
        
        logger.debug("Mapped user type '{}' to Spring Security role: {}", 
                    userSecurity.getUserType(), roleName);

        // Build Spring Security UserDetails object
        // Password is BCrypt hash from userPwdHash field (not plain text)
        // Spring Security will validate user-provided password against this hash
        // This replaces COBOL plain-text comparison: IF SEC-USR-PWD = WS-USER-PWD (line 223)
        UserDetails userDetails = User.builder()
            .username(userSecurity.getUserId())
            .password(userSecurity.getUserPwdHash())  // BCrypt hash, not plain text
            .authorities(Collections.singletonList(authority))
            .accountExpired(false)       // No account expiration in COBOL
            .accountLocked(false)        // No account locking in COBOL
            .credentialsExpired(false)   // No password expiration in COBOL
            .disabled(false)             // No enable/disable flag in COBOL
            .build();

        logger.info("Successfully loaded UserDetails for userId: {} with role: {}", 
                   username, roleName);

        return userDetails;
    }
}

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

import com.carddemo.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security UserDetailsService implementation for CardDemo application.
 * 
 * <p>This service replaces the COBOL USRSEC VSAM file authentication mechanism
 * from COSGN00C.cbl with Spring Security's standard authentication pattern.
 * It loads user authentication and authorization details from the PostgreSQL
 * database via UserSecurityRepository and transforms them into Spring Security
 * UserDetails objects.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>Replaces authentication logic from:</p>
 * <ul>
 *   <li>COBOL Program: COSGN00C.cbl (Sign-on Screen Processing)</li>
 *   <li>VSAM File: USRSEC (WS-USRSEC-FILE = 'USRSEC  ')</li>
 *   <li>Copybook: CSUSR01Y.cpy (SEC-USER-DATA structure)</li>
 * </ul>
 * 
 * <h2>COBOL Authentication Flow (COSGN00C.cbl lines 209-257)</h2>
 * <pre>
 * READ-USER-SEC-FILE.
 *     EXEC CICS READ
 *          DATASET   (WS-USRSEC-FILE)
 *          INTO      (SEC-USER-DATA)
 *          LENGTH    (LENGTH OF SEC-USER-DATA)
 *          RIDFLD    (WS-USER-ID)
 *          KEYLENGTH (LENGTH OF WS-USER-ID)
 *          RESP      (WS-RESP-CD)
 *          RESP2     (WS-REAS-CD)
 *     END-EXEC.
 * 
 *     EVALUATE WS-RESP-CD
 *         WHEN 0
 *             IF SEC-USR-PWD = WS-USER-PWD
 *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 *                 IF CDEMO-USRTYP-ADMIN
 *                      EXEC CICS XCTL PROGRAM ('COADM01C') ...
 *                 ELSE
 *                      EXEC CICS XCTL PROGRAM ('COMEN01C') ...
 *                 END-IF
 *             ELSE
 *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
 *             END-IF
 *         WHEN 13
 *             MOVE 'User not found. Try again ...' TO WS-MESSAGE
 *         WHEN OTHER
 *             MOVE 'Unable to verify the User ...' TO WS-MESSAGE
 *     END-EVALUATE.
 * </pre>
 * 
 * <h2>Data Structure Transformation</h2>
 * <p>From CSUSR01Y.cpy SEC-USER-DATA copybook:</p>
 * <pre>
 * COBOL Field             Type        Java Equivalent       Usage
 * ------------------------------------------------------------------------
 * SEC-USR-ID              PIC X(08)   String userId         Username for auth
 * SEC-USR-FNAME           PIC X(20)   String firstName      User display name
 * SEC-USR-LNAME           PIC X(20)   String lastName       User display name
 * SEC-USR-PWD             PIC X(08)   String password       BCrypt hash (60 chars)
 * SEC-USR-TYPE            PIC X(01)   String userType       Role mapping ('A'/'U')
 * SEC-USR-FILLER          PIC X(23)   (not mapped)          Unused padding
 * </pre>
 * 
 * <h2>Role Mapping Logic</h2>
 * <p>From COCOM01Y.cpy user type definitions:</p>
 * <pre>
 * COBOL User Type                    Spring Security Role
 * ------------------------------------------------------------------------
 * 'A' (CDEMO-USRTYP-ADMIN)      →    ROLE_ADMIN (+ ROLE_USER hierarchical)
 * 'U' (CDEMO-USRTYP-USER)       →    ROLE_USER
 * 'R' (Regular User per Sec 0.9) →   ROLE_USER
 * </pre>
 * 
 * <h2>CICS Response Code Mapping</h2>
 * <pre>
 * CICS RESP Code    Condition            Java Exception
 * ------------------------------------------------------------------------
 * 0                 Record found         Optional.of(UserSecurity)
 * 13                Record not found     UsernameNotFoundException
 * Other             I/O Error            DataAccessException (from Spring Data)
 * </pre>
 * 
 * <h2>Security Enhancements</h2>
 * <p>The Java implementation improves security over the COBOL version:</p>
 * <ul>
 *   <li><strong>Password Storage:</strong> BCrypt hash (60 chars) vs plain text (8 chars)</li>
 *   <li><strong>Hashing Algorithm:</strong> BCrypt with strength 12 vs none</li>
 *   <li><strong>Authentication:</strong> Spring Security framework vs manual comparison</li>
 *   <li><strong>Session Management:</strong> JWT tokens vs CICS COMMAREA</li>
 *   <li><strong>Authorization:</strong> Spring Security roles vs COBOL 88-level conditions</li>
 * </ul>
 * 
 * <h2>Integration with Spring Security</h2>
 * <p>This service is invoked by Spring Security's authentication flow:</p>
 * <ol>
 *   <li>User submits credentials via POST /api/auth/login</li>
 *   <li>AuthenticationController delegates to AuthenticationManager</li>
 *   <li>AuthenticationManager calls loadUserByUsername() to retrieve user details</li>
 *   <li>PasswordEncoder.matches() verifies password against BCrypt hash</li>
 *   <li>If successful, JwtTokenProvider generates JWT token</li>
 *   <li>Token returned to client for subsequent authenticated requests</li>
 * </ol>
 * 
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li>Database query uses primary key index (user_id) for O(log n) lookup</li>
 *   <li>@Transactional(readOnly=true) optimizes query as read-only</li>
 *   <li>READ_COMMITTED isolation level matches CICS default semantics</li>
 *   <li>Target response time: &lt; 100ms average per Section 0.9 requirements</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * // Spring Security automatically invokes this service during authentication:
 * 
 * // 1. User submits login credentials
 * LoginRequest request = new LoginRequest("USER0001", "password123");
 * 
 * // 2. Spring Security AuthenticationManager internally calls:
 * UserDetails userDetails = customUserDetailsService.loadUserByUsername("USER0001");
 * 
 * // 3. Password verification (Spring Security automatic):
 * boolean matches = passwordEncoder.matches("password123", userDetails.getPassword());
 * 
 * // 4. If successful, generate JWT token (in AuthenticationService):
 * String token = jwtTokenProvider.generateToken(userDetails);
 * 
 * // 5. Return token to client for subsequent requests
 * return new LoginResponse(token, "Bearer", "USER0001", ...);
 * </pre>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see org.springframework.security.core.userdetails.UserDetailsService
 * @see com.carddemo.entity.UserSecurity
 * @see com.carddemo.repository.UserSecurityRepository
 * @see com.carddemo.security.SecurityConstants
 * @see com.carddemo.service.AuthenticationService
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {
    
    /**
     * Repository for accessing user security data from PostgreSQL database.
     * 
     * <p>Replaces COBOL EXEC CICS READ DATASET(USRSEC) operations with
     * JPA repository pattern. Provides findByUserId() method that queries
     * user_security table using primary key index.</p>
     * 
     * <p>Injected by Spring's dependency injection framework using @Autowired.</p>
     */
    @Autowired
    private UserSecurityRepository userSecurityRepository;
    
    /**
     * Loads user details by username for Spring Security authentication.
     * 
     * <p>This method is the core authentication lookup mechanism, replacing
     * the COBOL EXEC CICS READ operation from COSGN00C.cbl lines 211-219.
     * It queries the user_security table by username (user_id) and transforms
     * the result into a Spring Security UserDetails object containing username,
     * password hash, and granted authorities (roles).</p>
     * 
     * <h3>COBOL Operation Being Replaced</h3>
     * <pre>
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ
     *          DATASET   (WS-USRSEC-FILE)          -- 'USRSEC  '
     *          INTO      (SEC-USER-DATA)           -- CSUSR01Y.cpy structure
     *          LENGTH    (LENGTH OF SEC-USER-DATA) -- 80 bytes
     *          RIDFLD    (WS-USER-ID)              -- Primary key (8 chars)
     *          KEYLENGTH (LENGTH OF WS-USER-ID)    -- 8
     *          RESP      (WS-RESP-CD)              -- Response code
     *          RESP2     (WS-REAS-CD)              -- Reason code
     *     END-EXEC.
     * </pre>
     * 
     * <h3>Java Equivalent Implementation</h3>
     * <pre>
     * // Database query via JPA repository:
     * Optional&lt;UserSecurity&gt; userOptional = userSecurityRepository.findByUserId(username);
     * 
     * // RESP code 0 (found) → Optional.isPresent() = true
     * // RESP code 13 (not found) → Optional.isPresent() = false
     * // Other errors → DataAccessException thrown by Spring Data JPA
     * </pre>
     * 
     * <h3>Response Code Mapping</h3>
     * <table border="1">
     *   <tr>
     *     <th>CICS RESP Code</th>
     *     <th>Meaning</th>
     *     <th>COBOL Action</th>
     *     <th>Java Exception/Result</th>
     *   </tr>
     *   <tr>
     *     <td>0</td>
     *     <td>Success</td>
     *     <td>Proceed with password check</td>
     *     <td>Return UserDetails object</td>
     *   </tr>
     *   <tr>
     *     <td>13</td>
     *     <td>Record not found</td>
     *     <td>'User not found. Try again ...'</td>
     *     <td>throw UsernameNotFoundException</td>
     *   </tr>
     *   <tr>
     *     <td>Other</td>
     *     <td>I/O Error</td>
     *     <td>'Unable to verify the User ...'</td>
     *     <td>DataAccessException (Spring Data)</td>
     *   </tr>
     * </table>
     * 
     * <h3>Password Verification</h3>
     * <p>In COBOL (COSGN00C.cbl line 223):</p>
     * <pre>
     * IF SEC-USR-PWD = WS-USER-PWD
     *     -- Plain text comparison (INSECURE)
     * </pre>
     * 
     * <p>In Java (handled by Spring Security AuthenticationManager):</p>
     * <pre>
     * passwordEncoder.matches(plainPassword, userDetails.getPassword())
     *     // BCrypt hash comparison (SECURE)
     *     // Uses BCryptPasswordEncoder with strength 12
     * </pre>
     * 
     * <h3>Role/Authority Assignment</h3>
     * <p>The method delegates to {@link #getAuthorities(String)} helper method
     * to transform COBOL user type codes into Spring Security GrantedAuthority
     * objects. This preserves the exact authorization semantics from the mainframe:</p>
     * <ul>
     *   <li>'A' (Admin) → ROLE_ADMIN authority (line 230: IF CDEMO-USRTYP-ADMIN)</li>
     *   <li>'U' or 'R' (User) → ROLE_USER authority</li>
     * </ul>
     * 
     * <h3>Transaction Management</h3>
     * <p>Annotated with @Transactional(readOnly=true) for optimal database
     * query performance:</p>
     * <ul>
     *   <li>Isolation Level: READ_COMMITTED (CICS default equivalent)</li>
     *   <li>Read-Only: true (no write operations, optimize for SELECT)</li>
     *   <li>Propagation: REQUIRED (participate in existing transaction or create new)</li>
     * </ul>
     * 
     * <h3>Error Handling</h3>
     * <p>Exception scenarios and their handling:</p>
     * <table border="1">
     *   <tr>
     *     <th>Scenario</th>
     *     <th>Exception Thrown</th>
     *     <th>HTTP Status</th>
     *     <th>Client Message</th>
     *   </tr>
     *   <tr>
     *     <td>User not found</td>
     *     <td>UsernameNotFoundException</td>
     *     <td>401 Unauthorized</td>
     *     <td>"User not found with username: {username}"</td>
     *   </tr>
     *   <tr>
     *     <td>Database connection failure</td>
     *     <td>DataAccessException</td>
     *     <td>500 Internal Server Error</td>
     *     <td>"Unable to verify user credentials"</td>
     *   </tr>
     *   <tr>
     *     <td>Invalid user data</td>
     *     <td>IllegalStateException</td>
     *     <td>500 Internal Server Error</td>
     *     <td>"User data integrity error"</td>
     *   </tr>
     * </table>
     * 
     * <h3>Performance Metrics</h3>
     * <ul>
     *   <li>Target Response Time: &lt; 100ms average (per Section 0.9)</li>
     *   <li>Database Query: Primary key index lookup O(log n)</li>
     *   <li>Expected Result: Single row or empty (not a full table scan)</li>
     *   <li>Network Latency: Minimized by HikariCP connection pooling</li>
     * </ul>
     * 
     * <h3>Security Audit Trail</h3>
     * <p>All authentication attempts (successful and failed) should be logged
     * for security audit compliance. Log entries include:</p>
     * <ul>
     *   <li>Timestamp of authentication attempt</li>
     *   <li>Username (user_id) being authenticated</li>
     *   <li>Success/failure status</li>
     *   <li>Client IP address (if available from request context)</li>
     *   <li>User agent information</li>
     * </ul>
     * 
     * @param username the username (user_id) to authenticate, maps to COBOL
     *                 WS-USER-ID PIC X(08) and SEC-USR-ID from VSAM USRSEC file.
     *                 Maximum length is 8 characters per COBOL definition.
     * 
     * @return UserDetails object containing username, BCrypt password hash,
     *         enabled status (always true), and collection of GrantedAuthority
     *         objects representing user roles. This object is used by Spring
     *         Security's AuthenticationManager for password verification and
     *         authorization decisions.
     * 
     * @throws UsernameNotFoundException if no user found with the given username.
     *         Maps to COBOL RESP code 13 (record not found) from COSGN00C.cbl
     *         line 247-251. This exception triggers HTTP 401 Unauthorized response
     *         and audit log entry for failed authentication attempt.
     * 
     * @throws org.springframework.dao.DataAccessException if database query fails
     *         due to connection issues, timeout, or other infrastructure problems.
     *         Maps to COBOL RESP code "OTHER" from COSGN00C.cbl line 252-256.
     * 
     * @see UserDetails
     * @see GrantedAuthority
     * @see UserSecurity
     * @see UserSecurityRepository#findByUserId(String)
     * @see #getAuthorities(String)
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Query user_security table by user_id (primary key)
        // Replaces: EXEC CICS READ DATASET(WS-USRSEC-FILE) RIDFLD(WS-USER-ID)
        UserSecurity user = userSecurityRepository.findByUserId(username)
            .orElseThrow(() -> new UsernameNotFoundException(
                // Maps to COBOL: WHEN 13 MOVE 'User not found. Try again ...' TO WS-MESSAGE
                "User not found with username: " + username));
        
        // Transform COBOL user type to Spring Security authorities
        // Maps to COBOL: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
        //                IF CDEMO-USRTYP-ADMIN ... (line 230)
        List<GrantedAuthority> authorities = getAuthorities(user.getUserType());
        
        // Construct Spring Security User object (implements UserDetails interface)
        // Contains username, BCrypt password hash, and granted authorities
        return new User(
            user.getUserId(),           // Username (8 chars, maps to SEC-USR-ID)
            user.getPassword(),         // BCrypt hash (60 chars, replaces SEC-USR-PWD plain text)
            true,                       // enabled (no disable logic in COBOL, all users active)
            true,                       // accountNonExpired (no expiration logic in COBOL)
            true,                       // credentialsNonExpired (no password expiration in COBOL)
            true,                       // accountNonLocked (no account locking in COBOL)
            authorities                 // Roles based on user type ('A' → ADMIN, 'U'/'R' → USER)
        );
    }
    
    /**
     * Transforms COBOL user type code to Spring Security GrantedAuthority collection.
     * 
     * <p>This method implements the role mapping logic from COBOL COCOM01Y.cpy
     * user type definitions, preserving exact access control semantics from the
     * mainframe application per Section 0.9 security model preservation requirements.</p>
     * 
     * <h3>COBOL User Type Definitions</h3>
     * <p>From COCOM01Y.cpy copybook:</p>
     * <pre>
     * 05  CDEMO-USER-TYPE              PIC X(01).
     *     88  CDEMO-USRTYP-ADMIN       VALUE 'A'.
     *     88  CDEMO-USRTYP-USER        VALUE 'U'.
     * </pre>
     * 
     * <p>From CSUSR01Y.cpy copybook:</p>
     * <pre>
     * 05  SEC-USR-TYPE                 PIC X(01).
     * </pre>
     * 
     * <h3>Role Mapping Logic</h3>
     * <table border="1">
     *   <tr>
     *     <th>COBOL Code</th>
     *     <th>COBOL Condition</th>
     *     <th>Spring Security Role(s)</th>
     *     <th>Access Level</th>
     *   </tr>
     *   <tr>
     *     <td>'A'</td>
     *     <td>CDEMO-USRTYP-ADMIN</td>
     *     <td>ROLE_ADMIN</td>
     *     <td>Full administrative access</td>
     *   </tr>
     *   <tr>
     *     <td>'U'</td>
     *     <td>CDEMO-USRTYP-USER</td>
     *     <td>ROLE_USER</td>
     *     <td>Regular user operations</td>
     *   </tr>
     *   <tr>
     *     <td>'R'</td>
     *     <td>(Per Section 0.9)</td>
     *     <td>ROLE_USER</td>
     *     <td>Regular user operations</td>
     *   </tr>
     * </table>
     * 
     * <h3>COBOL Authorization Check Pattern</h3>
     * <p>From COSGN00C.cbl lines 230-240:</p>
     * <pre>
     * IF CDEMO-USRTYP-ADMIN
     *      EXEC CICS XCTL
     *        PROGRAM ('COADM01C')     -- Administrative menu
     *        COMMAREA(CARDDEMO-COMMAREA)
     *      END-EXEC
     * ELSE
     *      EXEC CICS XCTL
     *        PROGRAM ('COMEN01C')     -- Regular user menu
     *        COMMAREA(CARDDEMO-COMMAREA)
     *      END-EXEC
     * END-IF
     * </pre>
     * 
     * <h3>Java Authorization Check Pattern</h3>
     * <p>Using Spring Security @PreAuthorize annotations:</p>
     * <pre>
     * // Administrative functions (maps to COADM01C program)
     * {@literal @}PreAuthorize("hasRole('ADMIN')")
     * public void performAdminOperation() {
     *     // Only users with 'A' user type can execute
     * }
     * 
     * // Regular user functions (maps to COMEN01C program)
     * {@literal @}PreAuthorize("hasRole('USER')")
     * public void performUserOperation() {
     *     // Both 'U' and 'A' user types can execute
     * }
     * </pre>
     * 
     * <h3>Hierarchical Role Model</h3>
     * <p>The implementation uses a flat role model (not hierarchical in this method),
     * meaning administrators must have ROLE_ADMIN explicitly set. If hierarchical
     * roles are needed (ADMIN inherits USER privileges), configure role hierarchy
     * in Spring Security configuration:</p>
     * <pre>
     * {@literal @}Bean
     * public RoleHierarchy roleHierarchy() {
     *     RoleHierarchyImpl hierarchy = new RoleHierarchyImpl();
     *     hierarchy.setHierarchy("ROLE_ADMIN > ROLE_USER");
     *     return hierarchy;
     * }
     * </pre>
     * 
     * <h3>Security Constants Usage</h3>
     * <p>Uses SecurityConstants for role definitions to eliminate magic strings:</p>
     * <ul>
     *   <li>SecurityConstants.USER_TYPE_ADMIN = "A"</li>
     *   <li>SecurityConstants.ROLE_ADMIN = "ROLE_ADMIN"</li>
     *   <li>SecurityConstants.ROLE_USER = "ROLE_USER"</li>
     * </ul>
     * 
     * <h3>Access Control Examples</h3>
     * <table border="1">
     *   <tr>
     *     <th>Operation</th>
     *     <th>Required Role</th>
     *     <th>User Type 'A'</th>
     *     <th>User Type 'U'</th>
     *   </tr>
     *   <tr>
     *     <td>View own account</td>
     *     <td>ROLE_USER</td>
     *     <td>✓ Allowed</td>
     *     <td>✓ Allowed</td>
     *   </tr>
     *   <tr>
     *     <td>Update own account</td>
     *     <td>ROLE_USER</td>
     *     <td>✓ Allowed</td>
     *     <td>✓ Allowed</td>
     *   </tr>
     *   <tr>
     *     <td>User management</td>
     *     <td>ROLE_ADMIN</td>
     *     <td>✓ Allowed</td>
     *     <td>✗ Denied</td>
     *   </tr>
     *   <tr>
     *     <td>Generate reports</td>
     *     <td>ROLE_ADMIN</td>
     *     <td>✓ Allowed</td>
     *     <td>✗ Denied</td>
     *   </tr>
     * </table>
     * 
     * <h3>Default Behavior</h3>
     * <p>Any user type value other than 'A' defaults to ROLE_USER. This provides
     * fail-safe behavior: if user type is corrupted or contains an unexpected value,
     * the user gets minimal privileges (regular user) rather than elevated access.</p>
     * 
     * @param userType single character user type code from SEC-USR-TYPE field.
     *                 Expected values: 'A' (Admin), 'U' (User), or 'R' (Regular).
     *                 Any other value defaults to ROLE_USER for security.
     * 
     * @return List of GrantedAuthority objects representing user's roles.
     *         Contains single SimpleGrantedAuthority with either ROLE_ADMIN
     *         or ROLE_USER based on user type. Never returns null or empty list.
     * 
     * @see SecurityConstants#USER_TYPE_ADMIN
     * @see SecurityConstants#ROLE_ADMIN
     * @see SecurityConstants#ROLE_USER
     * @see GrantedAuthority
     * @see SimpleGrantedAuthority
     */
    private List<GrantedAuthority> getAuthorities(String userType) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        
        // Transform COBOL user type to Spring Security roles
        // Per Section 0.9: Preserve exact access control patterns from current implementation
        
        // Check for administrative user type
        // Maps to: IF CDEMO-USRTYP-ADMIN (COBOL 88-level condition VALUE 'A')
        if (SecurityConstants.USER_TYPE_ADMIN.equals(userType)) {
            // Administrative user gets ROLE_ADMIN authority
            // Allows access to administrative functions (COADM01C program equivalent)
            authorities.add(new SimpleGrantedAuthority(SecurityConstants.ROLE_ADMIN));
        } else {
            // Regular user gets ROLE_USER authority
            // Maps to: CDEMO-USRTYP-USER VALUE 'U' or 'R' (Regular per Section 0.9)
            // Allows access to regular user functions (COMEN01C program equivalent)
            authorities.add(new SimpleGrantedAuthority(SecurityConstants.ROLE_USER));
        }
        
        return authorities;
    }
}

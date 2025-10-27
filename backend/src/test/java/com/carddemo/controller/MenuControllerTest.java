/*
 * MenuControllerTest.java
 *
 * JUnit 5 test class for MenuController REST endpoints
 * Tests menu navigation endpoints converted from COBOL programs COMEN01C.cbl and COADM01C.cbl
 *
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
 *
 * Test Coverage:
 *   - GET /api/menu/main: Main menu for regular users (COMEN01C.cbl)
 *   - GET /api/menu/admin: Admin menu with role-based access control (COADM01C.cbl)
 *
 * Testing Approach:
 *   - Uses @WebMvcTest for focused controller layer testing
 *   - Mocks JwtTokenProvider dependency for JWT validation
 *   - Uses @WithMockUser to simulate authenticated users with different roles
 *   - Validates HTTP status codes (200 OK, 403 Forbidden)
 *   - Validates JSON response structure and content
 */
package com.carddemo.controller;

import com.carddemo.security.JwtTokenProvider;
import com.carddemo.security.SecurityRoles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/**
 * Test class for MenuController REST endpoints.
 * 
 * <p>This test class validates the menu navigation endpoints that replace COBOL menu programs:
 * <ul>
 *   <li><b>COMEN01C.cbl:</b> Main menu for regular users → GET /api/menu/main</li>
 *   <li><b>COADM01C.cbl:</b> Admin menu for administrators → GET /api/menu/admin</li>
 * </ul>
 * </p>
 * 
 * <p><b>Test Strategy:</b></p>
 * <ul>
 *   <li>Uses @WebMvcTest to load only MenuController and web layer components</li>
 *   <li>Mocks JwtTokenProvider to isolate controller logic from security implementation</li>
 *   <li>Uses @WithMockUser to simulate authenticated users with ROLE_USER and ROLE_ADMIN</li>
 *   <li>Validates JSON response structure matches MenuDto format</li>
 *   <li>Validates Spring Security @PreAuthorize enforcement for admin endpoints</li>
 * </ul>
 * 
 * <p><b>COBOL Test Case Mapping:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>Test Method</th>
 *     <th>COBOL Program</th>
 *     <th>COBOL Lines</th>
 *     <th>Test Scenario</th>
 *   </tr>
 *   <tr>
 *     <td>testGetMainMenu_ReturnsMenuOptions</td>
 *     <td>COMEN01C.cbl</td>
 *     <td>182-277</td>
 *     <td>Main menu display for authenticated user</td>
 *   </tr>
 *   <tr>
 *     <td>testGetAdminMenu_WithAdminRole_ReturnsAdminOptions</td>
 *     <td>COADM01C.cbl</td>
 *     <td>172-184</td>
 *     <td>Admin menu display for admin user</td>
 *   </tr>
 *   <tr>
 *     <td>testGetAdminMenu_WithUserRole_ReturnsForbidden</td>
 *     <td>COMEN01C.cbl</td>
 *     <td>136-143</td>
 *     <td>Admin access denied for regular user</td>
 *   </tr>
 * </table>
 */
@WebMvcTest(MenuController.class)
@DisplayName("MenuController REST Endpoint Tests")
public class MenuControllerTest {

    /**
     * MockMvc instance for performing HTTP requests against MenuController.
     * Autowired by Spring Test framework to simulate HTTP requests without starting full server.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Mocked JwtTokenProvider dependency.
     * Used by MenuController.getAdminMenu() to validate JWT tokens and extract user roles.
     * Mocked to isolate controller testing from actual JWT implementation.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Test GET /api/menu/main returns main menu options for authenticated regular users.
     * 
     * <p>This test validates the endpoint that replaces COMEN01C.cbl SEND-MENU-SCREEN paragraph
     * (lines 182-194) and BUILD-MENU-OPTIONS paragraph (lines 236-277). Verifies that the endpoint
     * returns a JSON array of 10 menu options matching the COMEN02Y.cpy copybook structure.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (COMEN01C.cbl lines 236-277):
     *   PERFORM BUILD-MENU-OPTIONS
     *   EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01') FROM(COMEN1AO) ERASE END-EXEC
     * 
     * Expected Menu Options:
     *   01 - Account View (COACTVWC)
     *   02 - Account Update (COACTUPC)
     *   03 - Credit Card List (COCRDLIC)
     *   04 - Credit Card View (COCRDSLC)
     *   05 - Credit Card Update (COCRDUPC)
     *   06 - Transaction List (COTRN00C)
     *   07 - Transaction View (COTRN01C)
     *   08 - Transaction Add (COTRN02C)
     *   09 - Transaction Reports (CORPT00C)
     *   10 - Bill Payment (COBIL00C)
     * </pre>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>HTTP status is 200 OK</li>
     *   <li>Response content type is application/json</li>
     *   <li>JSON contains "menuTitle" field with value "CardDemo Main Menu"</li>
     *   <li>JSON contains "options" array with exactly 10 elements</li>
     *   <li>Each option contains: optionNumber, optionText, programName, requiredRole</li>
     *   <li>First option is "Account View" with program "COACTVWC"</li>
     *   <li>All options have requiredRole "ROLE_USER"</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    @DisplayName("GET /api/menu/main returns main menu options for authenticated user")
    public void testGetMainMenu_ReturnsMenuOptions() throws Exception {
        // Perform GET request to /api/menu/main endpoint
        mockMvc.perform(get("/api/menu/main"))
            // Assert HTTP 200 OK status
            .andExpect(status().isOk())
            
            // Assert response content type is JSON
            .andExpect(content().contentType("application/json"))
            
            // Assert menuTitle field matches expected value
            .andExpect(jsonPath("$.menuTitle").value("CardDemo Main Menu"))
            
            // Assert options array exists and has exactly 10 elements (COMEN02Y.cpy has 10 menu options)
            .andExpect(jsonPath("$.options").isArray())
            .andExpect(jsonPath("$.options", hasSize(10)))
            
            // Validate first menu option structure (Account View - COMEN02Y.cpy lines 25-29)
            .andExpect(jsonPath("$.options[0].optionNumber").value(1))
            .andExpect(jsonPath("$.options[0].optionText").value("Account View"))
            .andExpect(jsonPath("$.options[0].programName").value("COACTVWC"))
            .andExpect(jsonPath("$.options[0].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate second menu option (Account Update - COMEN02Y.cpy lines 31-35)
            .andExpect(jsonPath("$.options[1].optionNumber").value(2))
            .andExpect(jsonPath("$.options[1].optionText").value("Account Update"))
            .andExpect(jsonPath("$.options[1].programName").value("COACTUPC"))
            .andExpect(jsonPath("$.options[1].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate third menu option (Credit Card List - COMEN02Y.cpy lines 37-41)
            .andExpect(jsonPath("$.options[2].optionNumber").value(3))
            .andExpect(jsonPath("$.options[2].optionText").value("Credit Card List"))
            .andExpect(jsonPath("$.options[2].programName").value("COCRDLIC"))
            .andExpect(jsonPath("$.options[2].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate fourth menu option (Credit Card View - COMEN02Y.cpy lines 43-47)
            .andExpect(jsonPath("$.options[3].optionNumber").value(4))
            .andExpect(jsonPath("$.options[3].optionText").value("Credit Card View"))
            .andExpect(jsonPath("$.options[3].programName").value("COCRDSLC"))
            .andExpect(jsonPath("$.options[3].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate fifth menu option (Credit Card Update - COMEN02Y.cpy lines 49-53)
            .andExpect(jsonPath("$.options[4].optionNumber").value(5))
            .andExpect(jsonPath("$.options[4].optionText").value("Credit Card Update"))
            .andExpect(jsonPath("$.options[4].programName").value("COCRDUPC"))
            .andExpect(jsonPath("$.options[4].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate sixth menu option (Transaction List - COMEN02Y.cpy lines 55-59)
            .andExpect(jsonPath("$.options[5].optionNumber").value(6))
            .andExpect(jsonPath("$.options[5].optionText").value("Transaction List"))
            .andExpect(jsonPath("$.options[5].programName").value("COTRN00C"))
            .andExpect(jsonPath("$.options[5].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate seventh menu option (Transaction View - COMEN02Y.cpy lines 61-65)
            .andExpect(jsonPath("$.options[6].optionNumber").value(7))
            .andExpect(jsonPath("$.options[6].optionText").value("Transaction View"))
            .andExpect(jsonPath("$.options[6].programName").value("COTRN01C"))
            .andExpect(jsonPath("$.options[6].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate eighth menu option (Transaction Add - COMEN02Y.cpy lines 67-72)
            .andExpect(jsonPath("$.options[7].optionNumber").value(8))
            .andExpect(jsonPath("$.options[7].optionText").value("Transaction Add"))
            .andExpect(jsonPath("$.options[7].programName").value("COTRN02C"))
            .andExpect(jsonPath("$.options[7].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate ninth menu option (Transaction Reports - COMEN02Y.cpy lines 74-78)
            .andExpect(jsonPath("$.options[8].optionNumber").value(9))
            .andExpect(jsonPath("$.options[8].optionText").value("Transaction Reports"))
            .andExpect(jsonPath("$.options[8].programName").value("CORPT00C"))
            .andExpect(jsonPath("$.options[8].requiredRole").value(SecurityRoles.ROLE_USER))
            
            // Validate tenth menu option (Bill Payment - COMEN02Y.cpy lines 80-84)
            .andExpect(jsonPath("$.options[9].optionNumber").value(10))
            .andExpect(jsonPath("$.options[9].optionText").value("Bill Payment"))
            .andExpect(jsonPath("$.options[9].programName").value("COBIL00C"))
            .andExpect(jsonPath("$.options[9].requiredRole").value(SecurityRoles.ROLE_USER));
    }

    /**
     * Test GET /api/menu/admin returns admin menu options for users with ADMIN role.
     * 
     * <p>This test validates the endpoint that replaces COADM01C.cbl SEND-MENU-SCREEN paragraph
     * (lines 172-184) and BUILD-MENU-OPTIONS paragraph. Verifies that users with ROLE_ADMIN can
     * successfully access the admin menu and receive a JSON array of 4 admin menu options
     * matching the COADM02Y.cpy copybook structure.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (COADM01C.cbl lines 172-184):
     *   PERFORM BUILD-MENU-OPTIONS
     *   EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01') FROM(COADM1AO) ERASE END-EXEC
     * 
     * Expected Admin Menu Options:
     *   01 - User List (Security) - COUSR00C
     *   02 - User Add (Security) - COUSR01C
     *   03 - User Update (Security) - COUSR02C
     *   04 - User Delete (Security) - COUSR03C
     * </pre>
     * 
     * <p><b>JWT Token Validation:</b></p>
     * <p>This test mocks JwtTokenProvider to simulate successful JWT validation:
     * <ul>
     *   <li>jwtTokenProvider.validateToken() returns true (token is valid and not expired)</li>
     *   <li>jwtTokenProvider.extractUserType() returns "ROLE_ADMIN" (user has admin privileges)</li>
     * </ul>
     * </p>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>HTTP status is 200 OK</li>
     *   <li>Response content type is application/json</li>
     *   <li>JSON contains "menuTitle" field with value "CardDemo Admin Menu"</li>
     *   <li>JSON contains "options" array with exactly 4 elements</li>
     *   <li>Each option contains: optionNumber, optionText, programName, requiredRole</li>
     *   <li>First option is "User List (Security)" with program "COUSR00C"</li>
     *   <li>All options have requiredRole "ROLE_ADMIN"</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    @WithMockUser(username = "adminuser", roles = {"ADMIN"})
    @DisplayName("GET /api/menu/admin returns admin menu options for admin user")
    public void testGetAdminMenu_WithAdminRole_ReturnsAdminOptions() throws Exception {
        // Mock JWT token validation to return true (valid token)
        when(jwtTokenProvider.validateToken(anyString())).thenReturn(true);
        
        // Mock JWT user type extraction to return ROLE_ADMIN
        when(jwtTokenProvider.extractUserType(anyString())).thenReturn(SecurityRoles.ROLE_ADMIN);
        
        // Perform GET request to /api/menu/admin endpoint with valid Authorization header
        mockMvc.perform(get("/api/menu/admin")
                .header("Authorization", "Bearer valid-admin-jwt-token"))
            // Assert HTTP 200 OK status (admin access granted)
            .andExpect(status().isOk())
            
            // Assert response content type is JSON
            .andExpect(content().contentType("application/json"))
            
            // Assert menuTitle field matches expected value
            .andExpect(jsonPath("$.menuTitle").value("CardDemo Admin Menu"))
            
            // Assert options array exists and has exactly 4 elements (COADM02Y.cpy has 4 admin options)
            .andExpect(jsonPath("$.options").isArray())
            .andExpect(jsonPath("$.options", hasSize(4)))
            
            // Validate first admin menu option (User List - COADM02Y.cpy lines 24-27)
            .andExpect(jsonPath("$.options[0].optionNumber").value(1))
            .andExpect(jsonPath("$.options[0].optionText").value("User List (Security)"))
            .andExpect(jsonPath("$.options[0].programName").value("COUSR00C"))
            .andExpect(jsonPath("$.options[0].requiredRole").value(SecurityRoles.ROLE_ADMIN))
            
            // Validate second admin menu option (User Add - COADM02Y.cpy lines 29-32)
            .andExpect(jsonPath("$.options[1].optionNumber").value(2))
            .andExpect(jsonPath("$.options[1].optionText").value("User Add (Security)"))
            .andExpect(jsonPath("$.options[1].programName").value("COUSR01C"))
            .andExpect(jsonPath("$.options[1].requiredRole").value(SecurityRoles.ROLE_ADMIN))
            
            // Validate third admin menu option (User Update - COADM02Y.cpy lines 34-37)
            .andExpect(jsonPath("$.options[2].optionNumber").value(3))
            .andExpect(jsonPath("$.options[2].optionText").value("User Update (Security)"))
            .andExpect(jsonPath("$.options[2].programName").value("COUSR02C"))
            .andExpect(jsonPath("$.options[2].requiredRole").value(SecurityRoles.ROLE_ADMIN))
            
            // Validate fourth admin menu option (User Delete - COADM02Y.cpy lines 39-42)
            .andExpect(jsonPath("$.options[3].optionNumber").value(4))
            .andExpect(jsonPath("$.options[3].optionText").value("User Delete (Security)"))
            .andExpect(jsonPath("$.options[3].programName").value("COUSR03C"))
            .andExpect(jsonPath("$.options[3].requiredRole").value(SecurityRoles.ROLE_ADMIN));
    }

    /**
     * Test GET /api/menu/admin returns HTTP 403 Forbidden for users with USER role.
     * 
     * <p>This test validates the role-based access control that replaces COMEN01C.cbl lines 136-143
     * admin-only access check. In the COBOL program, when a regular user (CDEMO-USRTYP-USER)
     * attempts to access an admin-only option (CDEMO-MENU-OPT-USRTYPE = 'A'), the system displays
     * error message "No access - Admin Only option..." and prevents access.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (COMEN01C.cbl lines 136-143):
     *   IF CDEMO-USRTYP-USER AND
     *      CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     *       SET ERR-FLG-ON          TO TRUE
     *       MOVE SPACES             TO WS-MESSAGE
     *       MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
     *       PERFORM SEND-MENU-SCREEN
     *   END-IF
     * 
     * Java (MenuController.getAdminMenu() lines 352-356):
     *   if (!SecurityRoles.ROLE_ADMIN.equals(userRole)) {
     *       log.warn("Admin menu access denied: User role '{}' is not admin", userRole);
     *       return ResponseEntity.status(HttpStatus.FORBIDDEN)
     *           .body(new ErrorResponse("No access - Admin Only option..."));
     *   }
     * </pre>
     * 
     * <p><b>JWT Token Validation:</b></p>
     * <p>This test mocks JwtTokenProvider to simulate regular user attempting admin access:
     * <ul>
     *   <li>jwtTokenProvider.validateToken() returns true (token is valid)</li>
     *   <li>jwtTokenProvider.extractUserType() returns "ROLE_USER" (regular user, not admin)</li>
     * </ul>
     * </p>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>HTTP status is 403 Forbidden (access denied)</li>
     *   <li>Response content type is application/json</li>
     *   <li>JSON contains "error" field with message "No access - Admin Only option..."</li>
     *   <li>No menu options are returned in the response</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    @WithMockUser(username = "regularuser", roles = {"USER"})
    @DisplayName("GET /api/menu/admin returns 403 Forbidden for non-admin user")
    public void testGetAdminMenu_WithUserRole_ReturnsForbidden() throws Exception {
        // Mock JWT token validation to return true (valid token, but not admin)
        when(jwtTokenProvider.validateToken(anyString())).thenReturn(true);
        
        // Mock JWT user type extraction to return ROLE_USER (regular user, not admin)
        when(jwtTokenProvider.extractUserType(anyString())).thenReturn(SecurityRoles.ROLE_USER);
        
        // Perform GET request to /api/menu/admin endpoint with valid but non-admin JWT token
        mockMvc.perform(get("/api/menu/admin")
                .header("Authorization", "Bearer valid-user-jwt-token"))
            // Assert HTTP 403 Forbidden status (admin access denied for regular user)
            .andExpect(status().isForbidden())
            
            // Assert response content type is JSON
            .andExpect(content().contentType("application/json"))
            
            // Assert error message matches COBOL error message from COMEN01C.cbl line 141
            .andExpect(jsonPath("$.error").value("No access - Admin Only option..."));
    }

    /**
     * Test GET /api/menu/admin returns HTTP 403 Forbidden for invalid JWT token.
     * 
     * <p>This test validates token validation logic in MenuController.getAdminMenu().
     * When JWT token is invalid (expired, malformed, or has invalid signature), the endpoint
     * should return HTTP 403 Forbidden with error message, preventing unauthorized access.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <p>Simulates attempt to access admin menu with invalid/expired JWT token:
     * <ul>
     *   <li>jwtTokenProvider.validateToken() returns false (token is invalid or expired)</li>
     *   <li>Controller immediately rejects request without extracting user type</li>
     * </ul>
     * </p>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>HTTP status is 403 Forbidden</li>
     *   <li>Response content type is application/json</li>
     *   <li>JSON contains "error" field with admin-only access message</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    @DisplayName("GET /api/menu/admin returns 403 Forbidden for invalid JWT token")
    public void testGetAdminMenu_WithInvalidToken_ReturnsForbidden() throws Exception {
        // Mock JWT token validation to return false (invalid or expired token)
        when(jwtTokenProvider.validateToken(anyString())).thenReturn(false);
        
        // Perform GET request to /api/menu/admin endpoint with invalid JWT token
        mockMvc.perform(get("/api/menu/admin")
                .header("Authorization", "Bearer invalid-jwt-token"))
            // Assert HTTP 403 Forbidden status (access denied due to invalid token)
            .andExpect(status().isForbidden())
            
            // Assert response content type is JSON
            .andExpect(content().contentType("application/json"))
            
            // Assert error message indicates admin-only access restriction
            .andExpect(jsonPath("$.error").value("No access - Admin Only option..."));
    }

    /**
     * Test GET /api/menu/admin returns HTTP 403 Forbidden for missing Authorization header.
     * 
     * <p>This test validates header validation logic in MenuController.getAdminMenu().
     * When Authorization header is missing or empty, the endpoint should return HTTP 403 Forbidden
     * with error message, preventing unauthenticated access.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <p>Simulates attempt to access admin menu without providing JWT token:
     * <ul>
     *   <li>No Authorization header is sent with the request</li>
     *   <li>Controller detects missing header and rejects request immediately</li>
     * </ul>
     * </p>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>HTTP status is 403 Forbidden</li>
     *   <li>Response content type is application/json</li>
     *   <li>JSON contains "error" field with admin-only access message</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    @DisplayName("GET /api/menu/admin returns 403 Forbidden for missing Authorization header")
    public void testGetAdminMenu_WithMissingAuthHeader_ReturnsForbidden() throws Exception {
        // Perform GET request to /api/menu/admin endpoint without Authorization header
        mockMvc.perform(get("/api/menu/admin"))
            // Assert HTTP 403 Forbidden status (access denied due to missing authentication)
            .andExpect(status().isForbidden())
            
            // Assert response content type is application/json
            .andExpect(content().contentType("application/json"))
            
            // Assert error message indicates admin-only access restriction
            .andExpect(jsonPath("$.error").value("No access - Admin Only option..."));
    }

    /**
     * Test GET /api/menu/admin returns HTTP 403 Forbidden for malformed Authorization header.
     * 
     * <p>This test validates header format validation in MenuController.getAdminMenu().
     * When Authorization header does not start with "Bearer " prefix, the endpoint should
     * return HTTP 403 Forbidden with error message.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <p>Simulates attempt to access admin menu with incorrectly formatted Authorization header:
     * <ul>
     *   <li>Authorization header provided but missing "Bearer " prefix</li>
     *   <li>Controller detects malformed header and rejects request</li>
     * </ul>
     * </p>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>HTTP status is 403 Forbidden</li>
     *   <li>Response content type is application/json</li>
     *   <li>JSON contains "error" field with admin-only access message</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    @DisplayName("GET /api/menu/admin returns 403 Forbidden for malformed Authorization header")
    public void testGetAdminMenu_WithMalformedAuthHeader_ReturnsForbidden() throws Exception {
        // Perform GET request to /api/menu/admin endpoint with malformed Authorization header (missing "Bearer " prefix)
        mockMvc.perform(get("/api/menu/admin")
                .header("Authorization", "malformed-token-without-bearer-prefix"))
            // Assert HTTP 403 Forbidden status (access denied due to malformed header)
            .andExpect(status().isForbidden())
            
            // Assert response content type is application/json
            .andExpect(content().contentType("application/json"))
            
            // Assert error message indicates admin-only access restriction
            .andExpect(jsonPath("$.error").value("No access - Admin Only option..."));
    }
}

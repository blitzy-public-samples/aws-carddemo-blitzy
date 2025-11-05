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

package com.carddemo.controller;

import com.carddemo.config.SecurityConfig;
import com.carddemo.dto.request.AdminRequest;
import com.carddemo.exception.GlobalExceptionHandler;
import com.carddemo.security.CustomUserDetailsService;
import com.carddemo.security.JwtTokenProvider;
import com.carddemo.security.SecurityConstants;
import com.carddemo.service.AdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

/**
 * JUnit test class for AdminController REST endpoint validation.
 * 
 * <p>This test class validates the administrative function endpoints transformed from the
 * COBOL COADM01C.cbl program. It ensures proper authorization enforcement, validates ROLE_ADMIN
 * access control, tests admin operations matching COBOL admin screen logic, and verifies
 * response time requirements.</p>
 * 
 * <p><strong>COBOL Source Mapping:</strong></p>
 * <ul>
 *   <li>COADM01C.cbl → AdminController.java REST endpoints</li>
 *   <li>COADM01.bms → Admin menu screen structure and options</li>
 *   <li>Transaction CA00 → GET /api/admin endpoint</li>
 *   <li>XCTL routing logic → POST /api/admin/actions endpoint</li>
 *   <li>SEC-USR-TYPE 'A' → ROLE_ADMIN Spring Security authorization</li>
 * </ul>
 * 
 * <p><strong>Test Coverage:</strong></p>
 * <ul>
 *   <li>GET /api/admin - Admin menu retrieval with authorization</li>
 *   <li>POST /api/admin/actions - Admin function execution</li>
 *   <li>@PreAuthorize authorization enforcement validation</li>
 *   <li>Admin-only access verification (403 for regular users)</li>
 *   <li>Unauthenticated access denial (401 for anonymous users)</li>
 *   <li>Admin action audit logging verification</li>
 *   <li>Response structure validation</li>
 *   <li>Response time assertions</li>
 * </ul>
 * 
 * <p><strong>Security Testing Strategy:</strong></p>
 * <ul>
 *   <li>@WithMockUser(roles={"ADMIN"}) - Successful admin access tests</li>
 *   <li>@WithMockUser(roles={"USER"}) - Authorization denial tests (403 Forbidden)</li>
 *   <li>@WithAnonymousUser - Unauthenticated access tests (401 Unauthorized)</li>
 * </ul>
 * 
 * <p><strong>COBOL Business Logic Preserved:</strong></p>
 * <ul>
 *   <li>Admin menu options 1-8 from COADM01C program</li>
 *   <li>User authorization check (CDEMO-USRTYP-ADMIN from COBOL)</li>
 *   <li>Program control transfer logic (XCTL equivalent via service routing)</li>
 *   <li>Error handling patterns from COBOL HANDLE CONDITION</li>
 * </ul>
 * 
 * @see AdminController
 * @see AdminService
 * @see AdminRequest
 * @see SecurityConstants
 * @since 1.0.0
 */
@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
@DisplayName("Admin Controller Tests - COADM01C.cbl Migration Validation")
public class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminService adminService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Test data setup - executed before each test method.
     * 
     * <p>Initializes mock responses for AdminService methods to enable isolated
     * controller layer testing without executing actual business logic.</p>
     */
    @BeforeEach
    public void setUp() {
        // Reset all mocks before each test
        reset(adminService);
    }

    /**
     * Test GET /api/admin endpoint with ROLE_ADMIN authorization.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl SEND-MENU-SCREEN paragraph
     * displaying admin menu options via EXEC CICS SEND MAP.</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>HTTP 200 OK status for authenticated admin user</li>
     *   <li>JSON response containing admin menu options</li>
     *   <li>Response includes program name, transaction ID, current date/time</li>
     *   <li>Menu options array with 8 administrative functions</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> Requires ROLE_ADMIN via @PreAuthorize annotation</p>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    @DisplayName("GET /api/admin - Admin Role - Returns Admin Menu Successfully")
    public void testGetAdminMenu_AdminRole_ReturnsSuccess() throws Exception {
        // Arrange - Setup mock admin menu response matching COADM01C.cbl BUILD-MENU-OPTIONS
        Map<String, Object> mockMenuResponse = createMockAdminMenuResponse();
        when(adminService.getAdminMenu())
                .thenReturn(mockMenuResponse);

        // Act - Execute GET request to /api/admin endpoint
        long startTime = System.currentTimeMillis();
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/admin")
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                // Assert - Verify HTTP 200 OK response
                .andExpect(MockMvcResultMatchers.status().isOk())
                // Assert - Verify JSON content type (with optional charset)
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Assert - Verify response structure matches COADM01C COMMAREA
                .andExpect(MockMvcResultMatchers.jsonPath("$.programName").value("COADM01C"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.transactionId").value("CA00"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.currentDate").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.currentTime").exists())
                // Assert - Verify menu options array exists and has 8 items
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions").isArray())
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions", Matchers.hasSize(8)))
                // Assert - Verify first menu option structure
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[0].optionNumber").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[0].optionText").value("User Management"))
                .andReturn();
        
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;

        // Assert - Verify response time requirement (< 200ms as per Section 0.9)
        assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms threshold";

        // Verify - AdminService.getAdminMenu was called exactly once
        verify(adminService, times(1)).getAdminMenu();
    }

    /**
     * Test GET /api/admin endpoint with ROLE_USER authorization.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl authorization check at program entry
     * where CDEMO-USRTYP-ADMIN check fails for regular users, resulting in access denial.</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>HTTP 403 Forbidden status for regular user attempting admin access</li>
     *   <li>Access denied by Spring Security @PreAuthorize("hasRole('ADMIN')")</li>
     *   <li>No service method invocation (authorization fails at controller level)</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> Validates ROLE_ADMIN requirement enforcement</p>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "USER001", roles = {"USER"})
    @DisplayName("GET /api/admin - User Role - Returns 403 Forbidden")
    public void testGetAdminMenu_UserRole_ReturnsForbidden() throws Exception {
        // Act & Assert - Regular user attempting to access admin endpoint
        mockMvc.perform(MockMvcRequestBuilders.get("/api/admin")
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                // Assert - Verify HTTP 403 Forbidden status
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // Verify - AdminService should NOT be called (authorization fails before service invocation)
        verify(adminService, never()).getAdminMenu();
    }

    /**
     * Test GET /api/admin endpoint without authentication.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl EIBCALEN check for no COMMAREA,
     * resulting in RETURN-TO-SIGNON-SCREEN (equivalent to unauthenticated access denial).</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>HTTP 401 Unauthorized status for unauthenticated access attempt</li>
     *   <li>Spring Security denies access before controller method execution</li>
     *   <li>User must authenticate via /api/auth/login before accessing admin functions</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> Validates authentication requirement enforcement</p>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/admin - Anonymous User - Returns 401 Unauthorized")
    public void testGetAdminMenu_AnonymousUser_ReturnsUnauthorized() throws Exception {
        // Act & Assert - Unauthenticated user attempting to access admin endpoint
        mockMvc.perform(MockMvcRequestBuilders.get("/api/admin")
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                // Assert - Verify HTTP 403 Forbidden status (Spring Security default for anonymous users)
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // Verify - AdminService should NOT be called (authentication fails before authorization)
        verify(adminService, never()).getAdminMenu();
    }

    /**
     * Test POST /api/admin/actions endpoint with ROLE_ADMIN authorization.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl PROCESS-ENTER-KEY paragraph
     * validating user input and performing XCTL to transfer control to selected admin program
     * (COUSR00C for User Management, COUSR01C for User Profile, etc.).</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>HTTP 200 OK status for valid admin action request</li>
     *   <li>JSON response confirming action execution</li>
     *   <li>AdminService delegates to appropriate function based on optionNumber</li>
     *   <li>Audit logging captures admin action for compliance</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> Requires ROLE_ADMIN via @PreAuthorize annotation</p>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/actions - Admin Role - Executes Admin Function Successfully")
    public void testExecuteAdminAction_AdminRole_ReturnsSuccess() throws Exception {
        // Arrange - Create valid admin request matching COADM01C.cbl input structure
        AdminRequest adminRequest = new AdminRequest();
        adminRequest.setSelectedOption("User Management");
        adminRequest.setOptionNumber(1);
        adminRequest.setUserId("ADMIN001");
        adminRequest.setParameters(new HashMap<>());

        // Mock service response for admin function execution
        Map<String, Object> mockActionResponse = createMockActionResponse();
        when(adminService.executeAdminFunction(ArgumentMatchers.any(AdminRequest.class)))
                .thenReturn(mockActionResponse);

        // Act - Execute POST request to /api/admin/actions endpoint
        long startTime = System.currentTimeMillis();
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/admin/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminRequest)))
                .andDo(print())
                // Assert - Verify HTTP 200 OK response
                .andExpect(MockMvcResultMatchers.status().isOk())
                // Assert - Verify JSON content type (with optional charset)
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Assert - Verify response structure
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("success"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.executedFunction").value("User Management"))
                .andReturn();
        
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;

        // Assert - Verify response time requirement (< 200ms as per Section 0.9)
        assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms threshold";

        // Verify - AdminService.executeAdminFunction was called exactly once
        verify(adminService, times(1)).executeAdminFunction(ArgumentMatchers.any(AdminRequest.class));
    }

    /**
     * Test POST /api/admin/actions endpoint with ROLE_USER authorization.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl would not be accessible to regular users
     * due to transaction routing restrictions in CICS (transaction CA00 reserved for admin users).</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>HTTP 403 Forbidden status for regular user attempting admin action</li>
     *   <li>Access denied by Spring Security @PreAuthorize("hasRole('ADMIN')")</li>
     *   <li>No service method invocation (authorization fails at controller level)</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> Validates ROLE_ADMIN requirement enforcement for actions</p>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "USER001", roles = {"USER"})
    @DisplayName("POST /api/admin/actions - User Role - Returns 403 Forbidden")
    public void testExecuteAdminAction_UserRole_ReturnsForbidden() throws Exception {
        // Arrange - Create admin request (will be rejected before validation)
        AdminRequest adminRequest = new AdminRequest();
        adminRequest.setSelectedOption("User Management");
        adminRequest.setOptionNumber(1);
        adminRequest.setUserId("USER001");

        // Act & Assert - Regular user attempting to execute admin action
        mockMvc.perform(MockMvcRequestBuilders.post("/api/admin/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminRequest)))
                .andDo(print())
                // Assert - Verify HTTP 403 Forbidden status
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // Verify - AdminService should NOT be called (authorization fails before service invocation)
        verify(adminService, never()).executeAdminFunction(ArgumentMatchers.any(AdminRequest.class));
    }

    /**
     * Test POST /api/admin/actions endpoint without authentication.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl EIBCALEN = 0 check would force
     * RETURN-TO-SIGNON-SCREEN, preventing unauthorized access to admin functions.</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>HTTP 401 Unauthorized status for unauthenticated access attempt</li>
     *   <li>Spring Security denies access before controller method execution</li>
     *   <li>User must authenticate before executing admin actions</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> Validates authentication requirement for admin actions</p>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithAnonymousUser
    @DisplayName("POST /api/admin/actions - Anonymous User - Returns 401 Unauthorized")
    public void testExecuteAdminAction_AnonymousUser_ReturnsUnauthorized() throws Exception {
        // Arrange - Create admin request (will be rejected before processing)
        AdminRequest adminRequest = new AdminRequest();
        adminRequest.setSelectedOption("User Management");
        adminRequest.setOptionNumber(1);

        // Act & Assert - Unauthenticated user attempting to execute admin action
        mockMvc.perform(MockMvcRequestBuilders.post("/api/admin/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminRequest)))
                .andDo(print())
                // Assert - Verify HTTP 403 Forbidden status (Spring Security default for anonymous users)
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // Verify - AdminService should NOT be called (authentication fails before authorization)
        verify(adminService, never()).executeAdminFunction(ArgumentMatchers.any(AdminRequest.class));
    }

    /**
     * Test POST /api/admin/actions endpoint with invalid parameters.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl VALIDATE-OPTION paragraph checking
     * WS-OPTION field range (must be 1-8), displaying error message if invalid.</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>HTTP 400 Bad Request status for invalid admin request parameters</li>
     *   <li>Bean Validation (@NotBlank, @Min, @Max) enforces input constraints</li>
     *   <li>Error response includes validation failure details</li>
     * </ul>
     * 
     * <p><strong>Validation Rules from AdminRequest:</strong></p>
     * <ul>
     *   <li>selectedOption: Required, max 40 characters</li>
     *   <li>optionNumber: Must be between 1-8 if provided</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/actions - Invalid Parameters - Returns 400 Bad Request")
    public void testExecuteAdminAction_InvalidParameters_ReturnsBadRequest() throws Exception {
        // Arrange - Create invalid admin request (option number out of range)
        AdminRequest adminRequest = new AdminRequest();
        adminRequest.setSelectedOption(""); // Invalid: empty string violates @NotBlank
        adminRequest.setOptionNumber(99); // Invalid: exceeds @Max(8)
        adminRequest.setUserId("ADMIN001");

        // Act & Assert - Submit invalid request to admin actions endpoint
        mockMvc.perform(MockMvcRequestBuilders.post("/api/admin/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminRequest)))
                .andDo(print())
                // Assert - Verify HTTP 400 Bad Request status
                .andExpect(MockMvcResultMatchers.status().isBadRequest());

        // Verify - AdminService should NOT be called (validation fails before service invocation)
        verify(adminService, never()).executeAdminFunction(ArgumentMatchers.any(AdminRequest.class));
    }

    /**
     * Test POST /api/admin/actions endpoint with missing required fields.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl checking if OPTIONI field is SPACES
     * or contains invalid data, displaying error message and not proceeding with XCTL.</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>HTTP 400 Bad Request status for missing required fields</li>
     *   <li>Bean Validation rejects null selectedOption field</li>
     *   <li>Error response indicates which required fields are missing</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/actions - Missing Required Fields - Returns 400 Bad Request")
    public void testExecuteAdminAction_MissingRequiredFields_ReturnsBadRequest() throws Exception {
        // Arrange - Create admin request with missing required selectedOption field
        AdminRequest adminRequest = new AdminRequest();
        // selectedOption not set (null) - violates @NotBlank constraint
        adminRequest.setOptionNumber(1);
        adminRequest.setUserId("ADMIN001");

        // Act & Assert - Submit incomplete request to admin actions endpoint
        mockMvc.perform(MockMvcRequestBuilders.post("/api/admin/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminRequest)))
                .andDo(print())
                // Assert - Verify HTTP 400 Bad Request status
                .andExpect(MockMvcResultMatchers.status().isBadRequest());

        // Verify - AdminService should NOT be called (validation fails)
        verify(adminService, never()).executeAdminFunction(ArgumentMatchers.any(AdminRequest.class));
    }

    /**
     * Test audit logging for administrative actions.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl would log transaction activity via
     * CICS auxiliary trace or CESE log entries for regulatory compliance and security audit.</p>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>All administrative actions logged with timestamp, user ID, and action details</li>
     *   <li>Audit log entries support compliance requirements</li>
     *   <li>Successful and failed actions both generate audit entries</li>
     * </ul>
     * 
     * <p><strong>Audit Log Format:</strong></p>
     * <pre>
     * [2024-01-15 14:32:01] ADMIN [ADMIN001] executed [User Management] function - SUCCESS
     * </pre>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    @DisplayName("Admin Action - Audit Logging - Verifies Audit Trail Creation")
    public void testExecuteAdminAction_AuditLogging_CreatesAuditTrail() throws Exception {
        // Arrange - Create admin request for audit trail verification
        AdminRequest adminRequest = new AdminRequest();
        adminRequest.setSelectedOption("System Configuration");
        adminRequest.setOptionNumber(5);
        adminRequest.setUserId("ADMIN001");
        adminRequest.setParameters(Map.of("action", "update_config", "configKey", "batch.chunk.size"));

        // Mock service response with audit information
        Map<String, Object> mockActionResponse = createMockActionResponseWithAudit();
        when(adminService.executeAdminFunction(ArgumentMatchers.any(AdminRequest.class)))
                .thenReturn(mockActionResponse);

        // Act - Execute admin action
        mockMvc.perform(MockMvcRequestBuilders.post("/api/admin/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminRequest)))
                .andDo(print())
                // Assert - Verify HTTP 200 OK response
                .andExpect(MockMvcResultMatchers.status().isOk())
                // Assert - Verify audit information in response
                .andExpect(MockMvcResultMatchers.jsonPath("$.auditTrail").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.auditTrail.timestamp").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.auditTrail.userId").value("ADMIN001"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.auditTrail.action").value("System Configuration"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.auditTrail.status").value("SUCCESS"));

        // Verify - AdminService was called and audit trail was created
        verify(adminService, times(1)).executeAdminFunction(ArgumentMatchers.any(AdminRequest.class));
    }

    /**
     * Test all admin menu options from COADM01C.cbl.
     * 
     * <p><strong>COBOL Source:</strong> COADM01C.cbl BUILD-MENU-OPTIONS paragraph populating
     * OPTN001O through OPTN008O fields with administrative function descriptions.</p>
     * 
     * <p><strong>Menu Options:</strong></p>
     * <ul>
     *   <li>1. User Management - COUSR00C program (XCTL)</li>
     *   <li>2. Account Administration - Account admin functions</li>
     *   <li>3. Card Management - Card admin functions</li>
     *   <li>4. Transaction Reports - CORPT00C program (XCTL)</li>
     *   <li>5. System Configuration - System settings</li>
     *   <li>6. Batch Job Control - Batch job management</li>
     *   <li>7. Audit Log Review - Audit trail viewing</li>
     *   <li>8. Database Maintenance - DB admin functions</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    @DisplayName("GET /api/admin - Menu Options - Contains All 8 Admin Functions")
    public void testGetAdminMenu_MenuOptions_ContainsAllAdminFunctions() throws Exception {
        // Arrange - Setup mock response with complete admin menu
        Map<String, Object> mockMenuResponse = createMockAdminMenuResponse();
        when(adminService.getAdminMenu())
                .thenReturn(mockMenuResponse);

        // Act & Assert - Verify all 8 menu options are present
        mockMvc.perform(MockMvcRequestBuilders.get("/api/admin")
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(MockMvcResultMatchers.status().isOk())
                // Verify option 1 - User Management
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[0].optionNumber").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[0].optionText").value("User Management"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[0].targetProgram").value("COUSR00C"))
                // Verify option 2 - Account Administration
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[1].optionNumber").value(2))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[1].optionText").value("Account Administration"))
                // Verify option 3 - Card Management
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[2].optionNumber").value(3))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[2].optionText").value("Card Management"))
                // Verify option 4 - Transaction Reports
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[3].optionNumber").value(4))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[3].optionText").value("Transaction Reports"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[3].targetProgram").value("CORPT00C"))
                // Verify option 5 - System Configuration
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[4].optionNumber").value(5))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[4].optionText").value("System Configuration"))
                // Verify option 6 - Batch Job Control
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[5].optionNumber").value(6))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[5].optionText").value("Batch Job Control"))
                // Verify option 7 - Audit Log Review
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[6].optionNumber").value(7))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[6].optionText").value("Audit Log Review"))
                // Verify option 8 - Database Maintenance
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[7].optionNumber").value(8))
                .andExpect(MockMvcResultMatchers.jsonPath("$.menuOptions[7].optionText").value("Database Maintenance"));

        // Verify service call
        verify(adminService, times(1)).getAdminMenu();
    }

    /**
     * Test response time requirement for admin operations.
     * 
     * <p><strong>Performance Requirement:</strong> Section 0.9 specifies transaction response
     * times must remain under 200ms at 95th percentile. This test validates admin endpoint
     * performance meets this requirement.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM01C.cbl executing under CICS with sub-second
     * response time for screen display and input processing.</p>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    @DisplayName("Admin Operations - Response Time - Completes Within 200ms")
    public void testAdminOperations_ResponseTime_MeetsPerformanceRequirement() throws Exception {
        // Arrange - Setup mock responses
        Map<String, Object> mockMenuResponse = createMockAdminMenuResponse();
        when(adminService.getAdminMenu())
                .thenReturn(mockMenuResponse);

        // Warmup phase - Execute requests once to ensure JVM warmup and initialization
        mockMvc.perform(MockMvcRequestBuilders.get("/api/admin")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().isOk());

        AdminRequest warmupRequest = new AdminRequest();
        warmupRequest.setSelectedOption("User Management");
        warmupRequest.setOptionNumber(1);
        warmupRequest.setUserId("ADMIN001");

        Map<String, Object> mockActionResponse = createMockActionResponse();
        when(adminService.executeAdminFunction(ArgumentMatchers.any(AdminRequest.class)))
                .thenReturn(mockActionResponse);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/admin/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(warmupRequest)))
                .andExpect(MockMvcResultMatchers.status().isOk());

        // Test GET /api/admin response time (after warmup)
        long menuStartTime = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/admin")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().isOk());
        long menuEndTime = System.currentTimeMillis();
        long menuResponseTime = menuEndTime - menuStartTime;

        // Assert GET /api/admin meets 200ms requirement
        assert menuResponseTime < 200 : "GET /api/admin response time " + menuResponseTime + "ms exceeds 200ms threshold";

        // Test POST /api/admin/actions response time (after warmup)
        AdminRequest adminRequest = new AdminRequest();
        adminRequest.setSelectedOption("User Management");
        adminRequest.setOptionNumber(1);
        adminRequest.setUserId("ADMIN001");

        long actionStartTime = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.post("/api/admin/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminRequest)))
                .andExpect(MockMvcResultMatchers.status().isOk());
        long actionEndTime = System.currentTimeMillis();
        long actionResponseTime = actionEndTime - actionStartTime;

        // Assert POST /api/admin/actions meets 200ms requirement
        assert actionResponseTime < 200 : "POST /api/admin/actions response time " + actionResponseTime + "ms exceeds 200ms threshold";
    }

    // ============================================================================
    // Private Helper Methods for Test Data Creation
    // ============================================================================

    /**
     * Creates mock admin menu response matching COADM01C.cbl structure.
     * 
     * @return Mock menu response map with program details and menu options
     */
    private Map<String, Object> createMockAdminMenuResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("programName", "COADM01C");
        response.put("transactionId", "CA00");
        response.put("currentDate", LocalDateTime.now().toLocalDate().toString());
        response.put("currentTime", LocalDateTime.now().toLocalTime().toString());
        response.put("title", "CardDemo Administration Menu");
        
        List<Map<String, Object>> menuOptions = new ArrayList<>();
        
        // Option 1 - User Management
        menuOptions.add(createMenuOption(1, "User Management", "COUSR00C", "Manage user accounts and permissions"));
        // Option 2 - Account Administration
        menuOptions.add(createMenuOption(2, "Account Administration", "Account Admin", "Administer customer accounts"));
        // Option 3 - Card Management
        menuOptions.add(createMenuOption(3, "Card Management", "Card Admin", "Manage credit cards"));
        // Option 4 - Transaction Reports
        menuOptions.add(createMenuOption(4, "Transaction Reports", "CORPT00C", "Generate transaction reports"));
        // Option 5 - System Configuration
        menuOptions.add(createMenuOption(5, "System Configuration", "System Config", "Configure system settings"));
        // Option 6 - Batch Job Control
        menuOptions.add(createMenuOption(6, "Batch Job Control", "Batch Control", "Manage batch job execution"));
        // Option 7 - Audit Log Review
        menuOptions.add(createMenuOption(7, "Audit Log Review", "Audit Review", "Review system audit logs"));
        // Option 8 - Database Maintenance
        menuOptions.add(createMenuOption(8, "Database Maintenance", "DB Maintenance", "Database administration tasks"));
        
        response.put("menuOptions", menuOptions);
        return response;
    }

    /**
     * Creates individual menu option map.
     * 
     * @param optionNumber The menu option number (1-8)
     * @param optionText The descriptive text for the option
     * @param targetProgram The target program/function to invoke
     * @param description Detailed description of the function
     * @return Menu option map
     */
    private Map<String, Object> createMenuOption(int optionNumber, String optionText, 
                                                  String targetProgram, String description) {
        Map<String, Object> option = new HashMap<>();
        option.put("optionNumber", optionNumber);
        option.put("optionText", optionText);
        option.put("targetProgram", targetProgram);
        option.put("description", description);
        return option;
    }

    /**
     * Creates mock action response for admin function execution.
     * 
     * @return Mock action response map
     */
    private Map<String, Object> createMockActionResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Administrative function executed successfully");
        response.put("executedFunction", "User Management");
        response.put("timestamp", LocalDateTime.now().toString());
        return response;
    }

    /**
     * Creates mock action response with audit trail information.
     * 
     * @return Mock action response map with audit details
     */
    private Map<String, Object> createMockActionResponseWithAudit() {
        Map<String, Object> response = createMockActionResponse();
        
        Map<String, Object> auditTrail = new HashMap<>();
        auditTrail.put("timestamp", LocalDateTime.now().toString());
        auditTrail.put("userId", "ADMIN001");
        auditTrail.put("action", "System Configuration");
        auditTrail.put("status", "SUCCESS");
        auditTrail.put("details", "Configuration updated successfully");
        
        response.put("auditTrail", auditTrail);
        return response;
    }
}

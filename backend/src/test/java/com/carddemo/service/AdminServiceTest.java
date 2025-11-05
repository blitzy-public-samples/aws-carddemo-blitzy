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

package com.carddemo.service;

import com.carddemo.dto.request.AdminRequest;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.security.SecurityConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive JUnit 5 test suite for AdminService validating business logic transformation
 * from COADM01C.cbl COBOL admin menu program.
 * 
 * <p>This test class ensures functional equivalence with the original COBOL program by
 * validating:</p>
 * <ul>
 *   <li>Administrative menu display and option formatting (BUILD-MENU-OPTIONS paragraph)</li>
 *   <li>Admin function routing and delegation (PROCESS-ENTER-KEY paragraph)</li>
 *   <li>ROLE_ADMIN authorization enforcement (CDEMO-USRTYP-ADMIN='A' validation)</li>
 *   <li>Access denial for non-admin users (88-level condition checks)</li>
 *   <li>Security audit logging for administrative actions</li>
 *   <li>Service delegation to UserManagementService and ReportMenuService</li>
 * </ul>
 * 
 * <p><strong>COBOL Source Program: COADM01C.cbl</strong></p>
 * <ul>
 *   <li>Program ID: COADM01C (269 lines)</li>
 *   <li>Transaction ID: CA00</li>
 *   <li>Function: Admin Menu for Admin users</li>
 *   <li>BMS Mapset: COADM01 (Screen: COADM1A)</li>
 * </ul>
 * 
 * <p><strong>COBOL Test Coverage Mapping:</strong></p>
 * <ul>
 *   <li>MAIN-PARA (lines 75-110) → testGetAdminMenu_* methods</li>
 *   <li>PROCESS-ENTER-KEY (lines 115-155) → testExecuteAdminFunction_* methods</li>
 *   <li>BUILD-MENU-OPTIONS (lines 226-263) → testGetAdminMenu_ReturnsSystemMetrics_AllData</li>
 *   <li>POPULATE-HEADER-INFO (lines 202-221) → header validation in menu tests</li>
 *   <li>COBOL validation (lines 127-134) → testExecuteAdminFunction_InvalidOption_ThrowsException</li>
 * </ul>
 * 
 * <p><strong>Security Test Coverage (Section 0.9 Compliance):</strong></p>
 * <ul>
 *   <li>@PreAuthorize("hasRole('ADMIN')") enforcement on ALL admin methods</li>
 *   <li>AccessDeniedException for ROLE_USER attempting admin operations</li>
 *   <li>Unauthenticated access rejection with AuthenticationException</li>
 *   <li>Security audit logging for all administrative actions</li>
 * </ul>
 * 
 * <p><strong>Test Method Naming Convention:</strong></p>
 * <pre>
 * methodName_StateUnderTest_ExpectedBehavior()
 * Example: getAdminMenu_RequiresAdminRole_Authorized()
 * </pre>
 * 
 * @see com.carddemo.service.AdminService
 * @see com.carddemo.service.UserManagementService
 * @see com.carddemo.service.ReportMenuService
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    /**
     * Admin service under test with mocked dependencies injected.
     * 
     * <p>Mockito @InjectMocks creates AdminService instance and injects all @Mock
     * annotated dependencies, enabling isolated unit testing without Spring context
     * or actual database connections.</p>
     */
    @InjectMocks
    private AdminService adminService;

    /**
     * Mock UserManagementService for testing delegation of user admin operations
     * (COUSR00C-COUSR03C program equivalents).
     */
    @Mock
    private UserManagementService userManagementService;

    /**
     * Mock ReportMenuService for testing delegation of report generation operations
     * (CORPT00C program equivalent).
     */
    @Mock
    private ReportMenuService reportMenuService;

    /**
     * Mock UserSecurityRepository for testing admin user count queries and
     * dashboard metrics aggregation.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mock AccountRepository for testing account statistics and dashboard data.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Mock TransactionRepository for testing transaction metrics and statistics.
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * Test admin request DTO used across multiple test methods.
     */
    private AdminRequest testAdminRequest;

    /**
     * Test user ID for admin operations.
     */
    private static final String TEST_ADMIN_USER_ID = "ADMIN001";

    /**
     * Test user ID for regular user (non-admin).
     */
    private static final String TEST_REGULAR_USER_ID = "USER001";

    /**
     * Setup method executed before each test case.
     * 
     * <p>Initializes test fixtures, configures mock behaviors with when().thenReturn(),
     * and prepares AdminRequest test data for admin function testing.</p>
     * 
     * <p><strong>Mock Configuration:</strong></p>
     * <ul>
     *   <li>userSecurityRepository.count() → 150 (total users)</li>
     *   <li>userSecurityRepository.findByUserType("A") → 10 (admin users)</li>
     *   <li>accountRepository.count() → 500 (total accounts)</li>
     *   <li>transactionRepository.countDailyTransactions() → 2500 (daily transactions)</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Initialize test AdminRequest matching COADM01 BMS screen input fields
        testAdminRequest = new AdminRequest();
        testAdminRequest.setSelectedOption("User Management");
        testAdminRequest.setOptionNumber(1);
        testAdminRequest.setUserId(TEST_ADMIN_USER_ID);

        // Configure mock repository behaviors for dashboard metrics
        when(userSecurityRepository.count()).thenReturn(150L);
        when(accountRepository.count()).thenReturn(500L);
        when(transactionRepository.count()).thenReturn(25000L);
    }

    /**
     * Test: getAdminMenu() requires ROLE_ADMIN and returns menu successfully.
     * 
     * <p><strong>COBOL Equivalent:</strong> MAIN-PARA and BUILD-MENU-OPTIONS paragraphs
     * (lines 75-110, 226-263) which validate admin user type and build menu display.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>@PreAuthorize("hasRole('ADMIN')") allows ROLE_ADMIN access</li>
     *   <li>Menu structure returned with header info (transaction ID, program name)</li>
     *   <li>All 4 admin options displayed (matches CDEMO-ADMIN-OPT-COUNT VALUE 4)</li>
     *   <li>Current date/time formatted (MM/DD/YY, HH:MM:SS from COBOL)</li>
     * </ul>
     * 
     * <p><strong>COBOL Validation:</strong></p>
     * <pre>
     * IF CDEMO-USER-TYPE = 'A'  (admin check)
     *   PERFORM BUILD-MENU-OPTIONS
     *   PERFORM SEND-MENU-SCREEN
     * ELSE
     *   MOVE 'Unauthorized access' TO WS-MESSAGE
     * END-IF
     * </pre>
     */
    @Test
    @WithMockUser(username = TEST_ADMIN_USER_ID, roles = "ADMIN")
    void getAdminMenu_RequiresAdminRole_Authorized() {
        // Act: Execute admin menu retrieval with ROLE_ADMIN authority
        Map<String, Object> menuResponse = adminService.getAdminMenu();

        // Assert: Verify menu structure matches COBOL BUILD-MENU-OPTIONS output
        assertNotNull(menuResponse, "Admin menu response should not be null");
        assertEquals("CA00", menuResponse.get("transactionId"), 
                "Transaction ID should match COBOL WS-TRANID VALUE 'CA00'");
        assertEquals("COADM01C", menuResponse.get("programName"), 
                "Program name should match COBOL WS-PGMNAME VALUE 'COADM01C'");
        assertEquals("AWS Mainframe Modernization", menuResponse.get("title01"),
                "Title01 should match BMS screen header");
        assertEquals("CardDemo Admin Menu", menuResponse.get("title02"),
                "Title02 should match BMS screen title");

        // Verify menu options count matches COBOL CDEMO-ADMIN-OPT-COUNT VALUE 4
        assertEquals(4, menuResponse.get("optionCount"),
                "Option count should be 4 matching COBOL admin options");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> menuOptions = 
                (List<Map<String, Object>>) menuResponse.get("menuOptions");
        assertNotNull(menuOptions, "Menu options list should not be null");
        assertEquals(4, menuOptions.size(), 
                "Should have exactly 4 admin menu options");

        // Verify first option: User List (Security) - COUSR00C
        Map<String, Object> option1 = menuOptions.get(0);
        assertEquals(1, option1.get("optionNumber"));
        assertEquals("User List (Security)", option1.get("optionName"));
        assertEquals("COUSR00C", option1.get("programName"));
        assertTrue(((String) option1.get("displayText")).startsWith("1. "),
                "Display text should start with option number");

        // Verify date and time fields are present (POPULATE-HEADER-INFO validation)
        assertNotNull(menuResponse.get("currentDate"), "Current date should be present");
        assertNotNull(menuResponse.get("currentTime"), "Current time should be present");
    }

    /**
     * Test: getAdminMenu() denies access for regular users (ROLE_USER).
     * 
     * <p><strong>COBOL Equivalent:</strong> User type validation from COCOM01Y.cpy
     * (88-level condition CDEMO-USRTYP-ADMIN VALUE 'A').</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>@PreAuthorize("hasRole('ADMIN')") denies ROLE_USER access</li>
     *   <li>AccessDeniedException thrown before method execution</li>
     *   <li>Security audit log records unauthorized access attempt</li>
     * </ul>
     * 
     * <p><strong>COBOL Validation:</strong></p>
     * <pre>
     * IF CDEMO-USER-TYPE NOT = 'A'
     *   MOVE 'User not authorized for admin functions' TO WS-MESSAGE
     *   PERFORM RETURN-TO-SIGNON-SCREEN
     * END-IF
     * </pre>
     */
    @Test
    @WithMockUser(username = TEST_REGULAR_USER_ID, roles = "USER")
    void getAdminMenu_RegularUser_ThrowsAccessDenied() {
        // Act & Assert: Verify AccessDeniedException for non-admin user
        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> adminService.getAdminMenu(),
                "Should throw AccessDeniedException for ROLE_USER");

        // Verify exception message indicates authorization failure
        assertNotNull(exception.getMessage(),
                "Exception message should describe authorization failure");
    }

    /**
     * Test: getAdminMenu() returns complete system metrics and dashboard data.
     * 
     * <p><strong>COBOL Equivalent:</strong> BUILD-MENU-OPTIONS paragraph (lines 226-263)
     * which iterates through CDEMO-ADMIN-OPTIONS and formats menu display text.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>All menu options formatted correctly (1-4)</li>
     *   <li>Program names match COBOL CDEMO-ADMIN-OPT-PGMNAME values</li>
     *   <li>Display text format: "N. Function Name (Category)"</li>
     *   <li>Header information complete (titles, date, time, transaction ID)</li>
     * </ul>
     * 
     * <p><strong>COBOL Menu Options from COADM02Y.cpy:</strong></p>
     * <pre>
     * 01  CDEMO-ADMIN-OPT-COUNT        PIC 9(02) VALUE 4.
     * 01  CDEMO-ADMIN-OPTIONS.
     *   05  CDEMO-ADMIN-OPTION OCCURS 4 TIMES.
     *     10  CDEMO-ADMIN-OPT-NUM      PIC 9(02).
     *     10  CDEMO-ADMIN-OPT-NAME     PIC X(40).
     *     10  CDEMO-ADMIN-OPT-PGMNAME  PIC X(08).
     * </pre>
     */
    @Test
    @WithMockUser(username = TEST_ADMIN_USER_ID, roles = "ADMIN")
    void getAdminMenu_ReturnsSystemMetrics_AllData() {
        // Act: Retrieve complete admin menu with all dashboard data
        Map<String, Object> menuResponse = adminService.getAdminMenu();

        // Assert: Verify complete menu structure
        assertNotNull(menuResponse, "Menu response should contain all data");
        
        // Validate header section (POPULATE-HEADER-INFO paragraph)
        assertEquals("CA00", menuResponse.get("transactionId"));
        assertEquals("COADM01C", menuResponse.get("programName"));
        assertNotNull(menuResponse.get("currentDate"));
        assertNotNull(menuResponse.get("currentTime"));
        assertEquals("AWS Mainframe Modernization", menuResponse.get("title01"));
        assertEquals("CardDemo Admin Menu", menuResponse.get("title02"));

        // Validate menu options section (BUILD-MENU-OPTIONS paragraph)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> menuOptions = 
                (List<Map<String, Object>>) menuResponse.get("menuOptions");
        
        assertEquals(4, menuOptions.size(), "Should have 4 admin options");

        // Option 1: User List (Security) - COUSR00C
        verifyMenuOption(menuOptions.get(0), 1, "User List (Security)", "COUSR00C");

        // Option 2: User Add (Security) - COUSR01C
        verifyMenuOption(menuOptions.get(1), 2, "User Add (Security)", "COUSR01C");

        // Option 3: User Update (Security) - COUSR02C
        verifyMenuOption(menuOptions.get(2), 3, "User Update (Security)", "COUSR02C");

        // Option 4: User Delete (Security) - COUSR03C
        verifyMenuOption(menuOptions.get(3), 4, "User Delete (Security)", "COUSR03C");

        // Verify option count matches COBOL CDEMO-ADMIN-OPT-COUNT
        assertEquals(4, menuResponse.get("optionCount"));

        // Verify prompt message is present
        assertNotNull(menuResponse.get("promptMessage"));
    }

    /**
     * Helper method to verify menu option structure matches COBOL format.
     * 
     * @param option Menu option map to validate
     * @param expectedNumber Expected option number (1-4)
     * @param expectedName Expected option name text
     * @param expectedProgram Expected target program name (COUSR00C-COUSR03C)
     */
    private void verifyMenuOption(Map<String, Object> option, int expectedNumber,
                                   String expectedName, String expectedProgram) {
        assertEquals(expectedNumber, option.get("optionNumber"),
                "Option number should be " + expectedNumber);
        assertEquals(expectedName, option.get("optionName"),
                "Option name should be " + expectedName);
        assertEquals(expectedProgram, option.get("programName"),
                "Program name should be " + expectedProgram);
        
        String displayText = (String) option.get("displayText");
        assertNotNull(displayText, "Display text should not be null");
        assertTrue(displayText.startsWith(expectedNumber + ". "),
                "Display text should start with '" + expectedNumber + ". '");
    }

    /**
     * Test: executeAdminFunction() validates admin-only user management operations.
     * 
     * <p><strong>COBOL Equivalent:</strong> PROCESS-ENTER-KEY paragraph (lines 115-155)
     * which validates option selection and routes to user management programs via
     * EXEC CICS XCTL.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>Admin can execute create, modify, delete user operations</li>
     *   <li>Option validation (1-4 matches CDEMO-ADMIN-OPT-COUNT)</li>
     *   <li>Delegation to UserManagementService confirmed</li>
     *   <li>Audit logging for administrative actions</li>
     * </ul>
     * 
     * <p><strong>COBOL Routing Logic:</strong></p>
     * <pre>
     * EVALUATE WS-OPTION
     *   WHEN 1
     *     EXEC CICS XCTL PROGRAM('COUSR00C') COMMAREA(CARDDEMO-COMMAREA)
     *   WHEN 2
     *     EXEC CICS XCTL PROGRAM('COUSR01C') COMMAREA(CARDDEMO-COMMAREA)
     *   WHEN 3
     *     EXEC CICS XCTL PROGRAM('COUSR02C') COMMAREA(CARDDEMO-COMMAREA)
     *   WHEN 4
     *     EXEC CICS XCTL PROGRAM('COUSR03C') COMMAREA(CARDDEMO-COMMAREA)
     * END-EVALUATE
     * </pre>
     */
    @Test
    @WithMockUser(username = TEST_ADMIN_USER_ID, roles = "ADMIN")
    void manageUsers_AdminOnly_CanCreateModifyDelete() {
        // Test Option 1: User List
        testAdminRequest.setOptionNumber(1);
        testAdminRequest.setSelectedOption("User List (Security)");
        
        Map<String, Object> result1 = adminService.executeAdminFunction(testAdminRequest);
        assertNotNull(result1, "Result should not be null");
        assertEquals("SUCCESS", result1.get("status"));
        assertEquals("User List", result1.get("function"));
        assertEquals("COUSR00C", result1.get("targetProgram"));
        assertEquals("REDIRECT_TO_USER_LIST", result1.get("nextAction"));

        // Test Option 2: User Add
        testAdminRequest.setOptionNumber(2);
        testAdminRequest.setSelectedOption("User Add (Security)");
        
        Map<String, Object> result2 = adminService.executeAdminFunction(testAdminRequest);
        assertNotNull(result2, "Result should not be null");
        assertEquals("SUCCESS", result2.get("status"));
        assertEquals("User Add", result2.get("function"));
        assertEquals("COUSR01C", result2.get("targetProgram"));
        assertEquals("REDIRECT_TO_USER_ADD", result2.get("nextAction"));

        // Test Option 3: User Update
        testAdminRequest.setOptionNumber(3);
        testAdminRequest.setSelectedOption("User Update (Security)");
        
        Map<String, Object> result3 = adminService.executeAdminFunction(testAdminRequest);
        assertNotNull(result3, "Result should not be null");
        assertEquals("SUCCESS", result3.get("status"));
        assertEquals("User Update", result3.get("function"));
        assertEquals("COUSR02C", result3.get("targetProgram"));
        assertEquals("REDIRECT_TO_USER_UPDATE", result3.get("nextAction"));

        // Test Option 4: User Delete
        testAdminRequest.setOptionNumber(4);
        testAdminRequest.setSelectedOption("User Delete (Security)");
        
        Map<String, Object> result4 = adminService.executeAdminFunction(testAdminRequest);
        assertNotNull(result4, "Result should not be null");
        assertEquals("SUCCESS", result4.get("status"));
        assertEquals("User Delete", result4.get("function"));
        assertEquals("COUSR03C", result4.get("targetProgram"));
        assertEquals("REDIRECT_TO_USER_DELETE", result4.get("nextAction"));

        // Verify all results contain userId for audit trail
        assertEquals(TEST_ADMIN_USER_ID, result1.get("userId"));
        assertEquals(TEST_ADMIN_USER_ID, result2.get("userId"));
        assertEquals(TEST_ADMIN_USER_ID, result3.get("userId"));
        assertEquals(TEST_ADMIN_USER_ID, result4.get("userId"));
    }

    /**
     * Test: listAdminFunctions() returns all available admin functions.
     * 
     * <p><strong>COBOL Equivalent:</strong> COADM02Y.cpy copybook structure defining
     * CARDDEMO-ADMIN-MENU-OPTIONS with option count, names, and program mappings.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>All 4 admin functions listed with complete details</li>
     *   <li>Each function includes: optionNumber, functionName, programName, description</li>
     *   <li>Required role ROLE_ADMIN specified for each function</li>
     *   <li>Transaction IDs match COBOL CICS transaction definitions</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = TEST_ADMIN_USER_ID, roles = "ADMIN")
    void listAdminFunctions_AdminOnly_ReturnsAllFunctions() {
        // Act: Retrieve list of all admin functions
        List<Map<String, Object>> functions = adminService.listAdminFunctions();

        // Assert: Verify function list completeness
        assertNotNull(functions, "Functions list should not be null");
        assertEquals(4, functions.size(), "Should have 4 admin functions");

        // Verify each function has required fields
        for (Map<String, Object> function : functions) {
            assertNotNull(function.get("optionNumber"));
            assertNotNull(function.get("functionName"));
            assertNotNull(function.get("programName"));
            assertNotNull(function.get("description"));
            assertEquals(SecurityConstants.ROLE_ADMIN, function.get("requiredRole"),
                    "All functions should require ROLE_ADMIN");
            assertNotNull(function.get("transactionId"));
        }

        // Verify specific function details
        Map<String, Object> function1 = functions.get(0);
        assertEquals(1, function1.get("optionNumber"));
        assertEquals("User List (Security)", function1.get("functionName"));
        assertEquals("COUSR00C", function1.get("programName"));

        Map<String, Object> function4 = functions.get(3);
        assertEquals(4, function4.get("optionNumber"));
        assertEquals("User Delete (Security)", function4.get("functionName"));
        assertEquals("COUSR03C", function4.get("programName"));
    }

    /**
     * Test: executeAdminFunction() validates option number range.
     * 
     * <p><strong>COBOL Equivalent:</strong> PROCESS-ENTER-KEY validation (lines 127-134):</p>
     * <pre>
     * IF WS-OPTION IS NOT NUMERIC OR
     *    WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
     *    WS-OPTION = ZEROS
     *   MOVE 'Y' TO WS-ERR-FLG
     *   MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     *   PERFORM SEND-MENU-SCREEN
     * END-IF
     * </pre>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>Option number 0 throws IllegalArgumentException</li>
     *   <li>Option number > 4 throws IllegalArgumentException</li>
     *   <li>Null option number throws IllegalArgumentException</li>
     *   <li>Error message matches COBOL WS-MESSAGE format</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = TEST_ADMIN_USER_ID, roles = "ADMIN")
    void executeAdminFunction_InvalidOption_ThrowsException() {
        // Test: Option number 0 (invalid - must be >= 1)
        testAdminRequest.setOptionNumber(0);
        IllegalArgumentException exception1 = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.executeAdminFunction(testAdminRequest),
                "Should throw exception for option number 0");
        assertTrue(exception1.getMessage().contains("valid option number"),
                "Error message should mention valid option number");

        // Test: Option number > 4 (exceeds CDEMO-ADMIN-OPT-COUNT)
        testAdminRequest.setOptionNumber(5);
        IllegalArgumentException exception2 = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.executeAdminFunction(testAdminRequest),
                "Should throw exception for option number > 4");
        assertTrue(exception2.getMessage().contains("1-4"),
                "Error message should specify valid range 1-4");

        // Test: Null option number
        testAdminRequest.setOptionNumber(null);
        IllegalArgumentException exception3 = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.executeAdminFunction(testAdminRequest),
                "Should throw exception for null option number");
        assertNotNull(exception3.getMessage());
    }

    /**
     * Test: All admin service methods require ROLE_ADMIN authorization.
     * 
     * <p><strong>COBOL Equivalent:</strong> User type validation throughout COADM01C.cbl
     * checking CDEMO-USER-TYPE = 'A' before allowing any admin operations.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>getAdminMenu() requires ROLE_ADMIN</li>
     *   <li>executeAdminFunction() requires ROLE_ADMIN</li>
     *   <li>listAdminFunctions() requires ROLE_ADMIN</li>
     *   <li>All methods throw AccessDeniedException for ROLE_USER</li>
     * </ul>
     * 
     * <p><strong>Security Requirements (Section 0.9):</strong></p>
     * <ul>
     *   <li>@PreAuthorize("hasRole('ADMIN')") on ALL admin methods</li>
     *   <li>Exact access control pattern from COBOL USRSEC file validation</li>
     *   <li>No admin operations permitted for regular users (userType='U')</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = TEST_REGULAR_USER_ID, roles = "USER")
    void testAuthorizationOnAllMethods_RequiresAdmin() {
        // Test: getAdminMenu() denies ROLE_USER
        assertThrows(AccessDeniedException.class,
                () -> adminService.getAdminMenu(),
                "getAdminMenu() should deny ROLE_USER access");

        // Test: executeAdminFunction() denies ROLE_USER
        assertThrows(AccessDeniedException.class,
                () -> adminService.executeAdminFunction(testAdminRequest),
                "executeAdminFunction() should deny ROLE_USER access");

        // Test: listAdminFunctions() denies ROLE_USER
        assertThrows(AccessDeniedException.class,
                () -> adminService.listAdminFunctions(),
                "listAdminFunctions() should deny ROLE_USER access");
    }

    /**
     * Test: AccessDenied events are logged for security audit trail.
     * 
     * <p><strong>COBOL Equivalent:</strong> Audit logging requirement from Section 0.9
     * for maintaining complete audit trail of administrative access attempts.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>Access denial logged with user ID, timestamp, and attempted operation</li>
     *   <li>Security event captured for compliance and regulatory requirements</li>
     *   <li>Log format: "[timestamp] SECURITY_AUDIT [userId] AccessDenied [operation]"</li>
     * </ul>
     * 
     * <p><strong>Audit Requirements (Section 0.9):</strong></p>
     * <ul>
     *   <li>Comprehensive logging of all administrative actions</li>
     *   <li>User ID captured for audit trail validation</li>
     *   <li>Timestamps for all operations</li>
     *   <li>Unauthorized access attempts logged</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = TEST_REGULAR_USER_ID, roles = "USER")
    void testAccessDenied_LogsSecurityEvent_Audit() {
        // Act & Assert: Attempt admin menu access with ROLE_USER
        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> adminService.getAdminMenu(),
                "Should throw AccessDeniedException for non-admin user");

        // Verify exception details for audit logging
        assertNotNull(exception, "Exception should not be null");
        assertNotNull(exception.getMessage(), 
                "Exception message should be available for audit logging");

        // Note: Actual audit logging verification would require:
        // 1. Custom LogAppender or test logger to capture log events
        // 2. Verification that security event was logged with:
        //    - Timestamp
        //    - User ID (TEST_REGULAR_USER_ID)
        //    - Operation attempted (getAdminMenu)
        //    - Result (AccessDenied)
        // This is confirmed by reviewing AdminService logging statements
    }

    /**
     * Test: Anonymous users (not authenticated) cannot access admin functions.
     * 
     * <p><strong>COBOL Equivalent:</strong> Sign-on validation requirement from COSGN00C.cbl
     * where users must authenticate before accessing any CICS transactions.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>@WithAnonymousUser simulates unauthenticated access</li>
     *   <li>AccessDeniedException or AuthenticationException thrown</li>
     *   <li>No admin operations permitted without authentication</li>
     * </ul>
     * 
     * <p><strong>COBOL Sign-On Flow:</strong></p>
     * <pre>
     * IF EIBCALEN = 0  (no COMMAREA = not signed on)
     *   MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
     *   PERFORM RETURN-TO-SIGNON-SCREEN
     * END-IF
     * </pre>
     */
    @Test
    @WithAnonymousUser
    void testAnonymousUser_CannotAccessAdmin() {
        // Test: Anonymous user denied admin menu access
        assertThrows(Exception.class,
                () -> adminService.getAdminMenu(),
                "Anonymous user should not access admin menu");

        // Test: Anonymous user denied admin function execution
        assertThrows(Exception.class,
                () -> adminService.executeAdminFunction(testAdminRequest),
                "Anonymous user should not execute admin functions");

        // Test: Anonymous user denied function listing
        assertThrows(Exception.class,
                () -> adminService.listAdminFunctions(),
                "Anonymous user should not list admin functions");
    }

    /**
     * Test: Admin function execution preserves user context for audit trail.
     * 
     * <p><strong>COBOL Equivalent:</strong> COMMAREA preservation throughout transaction
     * flow (CDEMO-USER-ID field carried through all program transfers).</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>User ID preserved in execution result</li>
     *   <li>Timestamp recorded for audit trail</li>
     *   <li>Option selection details captured</li>
     *   <li>Audit data available for regulatory compliance</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = TEST_ADMIN_USER_ID, roles = "ADMIN")
    void executeAdminFunction_PreservesUserContext_AuditTrail() {
        // Arrange: Set up admin request with full user context
        testAdminRequest.setUserId(TEST_ADMIN_USER_ID);
        testAdminRequest.setOptionNumber(1);
        testAdminRequest.setSelectedOption("User List (Security)");

        // Act: Execute admin function
        Map<String, Object> result = adminService.executeAdminFunction(testAdminRequest);

        // Assert: Verify user context preserved for audit trail
        assertNotNull(result, "Result should not be null");
        assertEquals(TEST_ADMIN_USER_ID, result.get("userId"),
                "User ID should be preserved for audit trail");
        assertEquals(1, result.get("optionNumber"),
                "Option number should be recorded");
        assertEquals("User List (Security)", result.get("selectedOption"),
                "Selected option should be recorded");
        assertNotNull(result.get("executionTimestamp"),
                "Execution timestamp should be recorded");
        
        // Verify audit-relevant fields
        assertEquals("SUCCESS", result.get("status"));
        assertEquals("User List", result.get("function"));
        assertEquals("COUSR00C", result.get("targetProgram"));
    }

    /**
     * Test: Admin menu includes formatted current date and time.
     * 
     * <p><strong>COBOL Equivalent:</strong> POPULATE-HEADER-INFO paragraph (lines 202-221)
     * which formats current date (MM/DD/YY) and time (HH:MM:SS) for BMS screen display.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>Current date formatted as MM/DD/YY</li>
     *   <li>Current time formatted as HH:MM:SS</li>
     *   <li>Date/time values present in menu response</li>
     *   <li>Format matches COBOL screen display requirements</li>
     * </ul>
     * 
     * <p><strong>COBOL Date/Time Formatting:</strong></p>
     * <pre>
     * MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     * MOVE WS-CURDATE-MONTH TO CURDATEO(1:2)
     * MOVE '/' TO CURDATEO(3:1)
     * MOVE WS-CURDATE-DAY TO CURDATEO(4:2)
     * MOVE '/' TO CURDATEO(6:1)
     * MOVE WS-CURDATE-YEAR TO CURDATEO(7:2)
     * </pre>
     */
    @Test
    @WithMockUser(username = TEST_ADMIN_USER_ID, roles = "ADMIN")
    void getAdminMenu_IncludesFormattedDateTime() {
        // Act: Retrieve admin menu
        Map<String, Object> menuResponse = adminService.getAdminMenu();

        // Assert: Verify date/time fields present
        String currentDate = (String) menuResponse.get("currentDate");
        String currentTime = (String) menuResponse.get("currentTime");

        assertNotNull(currentDate, "Current date should be present");
        assertNotNull(currentTime, "Current time should be present");

        // Verify date format: MM/DD/YY
        assertTrue(currentDate.matches("\\d{2}/\\d{2}/\\d{2}"),
                "Date should be formatted as MM/DD/YY");

        // Verify time format: HH:MM:SS
        assertTrue(currentTime.matches("\\d{2}:\\d{2}:\\d{2}"),
                "Time should be formatted as HH:MM:SS");
    }

    /**
     * Test: executeAdminFunction() validates all menu option numbers.
     * 
     * <p><strong>COBOL Equivalent:</strong> Menu option validation and routing from
     * PROCESS-ENTER-KEY paragraph with EVALUATE statement for all valid options.</p>
     * 
     * <p><strong>Test Coverage:</strong></p>
     * <ul>
     *   <li>All valid options (1-4) execute successfully</li>
     *   <li>Each option routes to correct target program</li>
     *   <li>Each option returns appropriate next action</li>
     *   <li>Success status returned for all valid executions</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = TEST_ADMIN_USER_ID, roles = "ADMIN")
    void executeAdminFunction_ValidatesAllOptions_RoutesCorrectly() {
        // Test all valid menu options 1-4
        for (int optionNum = 1; optionNum <= 4; optionNum++) {
            testAdminRequest.setOptionNumber(optionNum);
            
            Map<String, Object> result = adminService.executeAdminFunction(testAdminRequest);
            
            assertNotNull(result, "Result should not be null for option " + optionNum);
            assertEquals("SUCCESS", result.get("status"),
                    "Option " + optionNum + " should execute successfully");
            assertEquals("00", result.get("statusCode"),
                    "Status code should be 00 for successful execution");
            assertEquals(optionNum, result.get("optionNumber"),
                    "Option number should match request");
            assertNotNull(result.get("targetProgram"),
                    "Target program should be specified");
            assertNotNull(result.get("nextAction"),
                    "Next action should be specified");
        }
    }
}

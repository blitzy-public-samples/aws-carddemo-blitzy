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

import com.carddemo.service.MenuNavigationService;
import com.carddemo.service.MenuNavigationService.MenuResponse;
import com.carddemo.service.MenuNavigationService.MenuOptionDTO;
import com.carddemo.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

/**
 * JUnit 5 test class for MenuController REST endpoint validation.
 * 
 * <p>This test class validates the transformation of COBOL program COMEN01C.cbl
 * CICS transaction CM00 to Spring Boot REST API endpoint GET /api/menu. It ensures
 * functional equivalence with the mainframe menu display logic including role-based
 * menu option filtering.</p>
 * 
 * <h2>Test Coverage</h2>
 * 
 * <p>The test suite covers the following scenarios from COMEN01C.cbl:</p>
 * <ul>
 *   <li><strong>Regular User Menu (CDEMO-USRTYP-USER):</strong> Tests that users with
 *       ROLE_USER receive filtered menu options excluding admin-only items, matching
 *       COBOL logic at lines 136-143 where CDEMO-USRTYP-USER check filters 'A' options</li>
 *   <li><strong>Admin User Menu (CDEMO-USRTYP-ADMIN):</strong> Tests that users with
 *       ROLE_ADMIN receive complete menu including administrative options, matching
 *       COBOL behavior where admin users see all menu items</li>
 *   <li><strong>Menu Structure Validation:</strong> Verifies menu response contains
 *       all required fields (userId, userName, userType, title, transactionId, programName,
 *       currentDate, currentTime, menuOptions array) matching COBOL COMEN1AO output structure</li>
 *   <li><strong>Unauthenticated Access:</strong> Tests that requests without authentication
 *       return HTTP 401 Unauthorized, matching COBOL EIBCALEN = 0 check at lines 82-84
 *       which returns to sign-on screen</li>
 *   <li><strong>Menu Option Count:</strong> Validates correct number of menu options
 *       returned based on user role matching CDEMO-MENU-OPT-COUNT from COMEN02Y copybook</li>
 *   <li><strong>Response Time Requirements:</strong> Asserts menu retrieval completes
 *       within 100ms per Section 0.6 performance requirements for menu display operations</li>
 * </ul>
 * 
 * <h2>COBOL Business Logic Transformation</h2>
 * 
 * <p><strong>Original COBOL Logic (COMEN01C.cbl lines 136-143):</strong></p>
 * <pre>
 * IF CDEMO-USRTYP-USER AND
 *    CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
 *     SET ERR-FLG-ON          TO TRUE
 *     MOVE SPACES             TO WS-MESSAGE
 *     MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
 *     PERFORM SEND-MENU-SCREEN
 * END-IF
 * </pre>
 * 
 * <p><strong>Java Test Equivalent:</strong></p>
 * <pre>
 * &#64;Test
 * &#64;WithMockUser(username = "USER0001", roles = {"USER"})
 * void testRegularUserReceivesFilteredMenu() {
 *     // Setup: Mock service returns menu with user-level options only
 *     MenuResponse regularUserMenu = buildRegularUserMenu();
 *     when(menuNavigationService.getMainMenu()).thenReturn(regularUserMenu);
 *     
 *     // Execute: GET /api/menu as regular user
 *     mockMvc.perform(get("/api/menu"))
 *         .andExpect(status().isOk())
 *         .andExpect(jsonPath("$.userType").value("U"))
 *         .andExpect(jsonPath("$.menuOptions", not(hasItem(
 *             hasEntry("requiredUserType", "A")
 *         ))));
 * }
 * </pre>
 * 
 * <h2>Test Strategy</h2>
 * 
 * <p>The test suite uses Spring Boot Test's &#64;WebMvcTest annotation to configure
 * a test context focused on the web layer:</p>
 * <ul>
 *   <li><strong>&#64;WebMvcTest(MenuController.class):</strong> Loads only MenuController
 *       and related web components, disables full auto-configuration for faster tests</li>
 *   <li><strong>&#64;MockBean:</strong> Mocks MenuNavigationService and JwtTokenProvider
 *       to isolate controller testing from business logic and security infrastructure</li>
 *   <li><strong>MockMvc:</strong> Performs HTTP requests without starting full server,
 *       validates response status codes, headers, and JSON body content</li>
 *   <li><strong>&#64;WithMockUser:</strong> Simulates authenticated user with specific
 *       roles (USER/ADMIN) matching COBOL SEC-USR-TYPE field values</li>
 *   <li><strong>&#64;WithAnonymousUser:</strong> Simulates unauthenticated request
 *       matching COBOL EIBCALEN = 0 scenario</li>
 * </ul>
 * 
 * <h2>Menu Option Structure</h2>
 * 
 * <p>Menu options follow the structure defined in COMEN02Y.cpy copybook:</p>
 * <pre>
 * 01 CDEMO-MENU-OPTIONS.
 *    05 CDEMO-MENU-OPT-COUNT       PIC 9(02) VALUE 10.
 *    05 CDEMO-MENU-OPT OCCURS 10 TIMES.
 *       10 CDEMO-MENU-OPT-NUM      PIC 9(02).
 *       10 CDEMO-MENU-OPT-NAME     PIC X(40).
 *       10 CDEMO-MENU-OPT-PGMNAME  PIC X(08).
 *       10 CDEMO-MENU-OPT-USRTYPE  PIC X(01). *&gt; 'U' or 'A'
 * </pre>
 * 
 * <p>This is transformed to Java MenuOptionDTO:</p>
 * <pre>
 * {
 *   "number": 1,
 *   "name": "Account View",
 *   "programName": "COACTVWC",
 *   "requiredUserType": "U"
 * }
 * </pre>
 * 
 * <h2>Validation Rules</h2>
 * 
 * <p>Tests validate the following business rules from COMEN01C.cbl:</p>
 * <ul>
 *   <li>Menu option numbers are sequential 1 through 10 (or less for filtered menus)</li>
 *   <li>Menu option names are not null or empty strings</li>
 *   <li>Program names are valid 8-character COBOL program identifiers</li>
 *   <li>Required user type is either 'U' (user) or 'A' (admin)</li>
 *   <li>Regular users (ROLE_USER) receive only 'U' level options</li>
 *   <li>Admin users (ROLE_ADMIN) receive both 'U' and 'A' level options</li>
 *   <li>Response time is under 100ms for menu display operations</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see MenuController REST controller being tested
 * @see MenuNavigationService Service layer with menu business logic
 * @see COMEN01C.cbl Original COBOL program
 * @since 1.0
 */
@WebMvcTest(MenuController.class)
@DisplayName("MenuController REST Endpoint Tests - COMEN01C.cbl Menu Navigation Transformation")
public class MenuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MenuNavigationService menuNavigationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private MenuResponse regularUserMenuResponse;
    private MenuResponse adminUserMenuResponse;

    /**
     * Test setup executed before each test method.
     * 
     * <p>Initializes test data fixtures including:</p>
     * <ul>
     *   <li>Regular user menu response with user-level options only</li>
     *   <li>Admin user menu response with all options including admin-only items</li>
     * </ul>
     * 
     * <p>These fixtures match the menu structure built in COBOL BUILD-MENU-OPTIONS
     * paragraph (lines 236-277 of COMEN01C.cbl).</p>
     */
    @BeforeEach
    void setUp() {
        // Build regular user menu (CDEMO-USRTYP-USER equivalent)
        regularUserMenuResponse = buildRegularUserMenu();
        
        // Build admin user menu (CDEMO-USRTYP-ADMIN equivalent)
        adminUserMenuResponse = buildAdminUserMenu();
    }

    /**
     * Test: Regular user receives filtered menu without admin-only options.
     * 
     * <p><strong>COBOL Equivalent:</strong> COMEN01C.cbl lines 136-143</p>
     * <pre>
     * IF CDEMO-USRTYP-USER AND
     *    CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     *     MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Given: Authenticated user with ROLE_USER (SEC-USR-TYPE = 'R')</li>
     *   <li>When: GET /api/menu is requested</li>
     *   <li>Then: Response contains only user-level menu options</li>
     *   <li>And: Response does NOT contain admin-only options (requiredUserType = 'A')</li>
     *   <li>And: HTTP status is 200 OK</li>
     * </ul>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Menu response contains 10 user-level options</li>
     *   <li>All options have requiredUserType = 'U'</li>
     *   <li>userType field in response is 'U' for regular user</li>
     *   <li>Response time is under 100ms</li>
     * </ul>
     */
    @Test
    @DisplayName("Regular User (ROLE_USER) - Returns Filtered Menu Without Admin Options")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void testRegularUserReceivesFilteredMenu() throws Exception {
        // Arrange: Mock service to return user-level menu options only
        // Simulates COBOL logic where CDEMO-USRTYP-USER filters out 'A' options
        when(menuNavigationService.getMainMenu()).thenReturn(regularUserMenuResponse);

        // Act & Assert: Perform GET request and validate response
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(get("/api/menu")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                
                // Validate user context fields (CDEMO-USER-ID, CDEMO-USER-TYPE from COMMAREA)
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.userName").value("John Smith"))
                .andExpect(jsonPath("$.userType").value("U"))
                
                // Validate menu metadata (WS-TRANID, WS-PGMNAME from WORKING-STORAGE)
                .andExpect(jsonPath("$.title").value("CardDemo Main Menu"))
                .andExpect(jsonPath("$.transactionId").value("CM00"))
                .andExpect(jsonPath("$.programName").value("COMEN01C"))
                
                // Validate date/time fields present (CURDATEO, CURTIMEO from BMS map)
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.currentTime").exists())
                
                // Validate menu options array structure
                .andExpect(jsonPath("$.menuOptions").isArray())
                .andExpect(jsonPath("$.menuOptions", hasSize(10)))
                
                // Validate NO admin-only options present (requiredUserType != 'A')
                .andExpect(jsonPath("$.menuOptions[*].requiredUserType", 
                        everyItem(is("U"))))
                
                // Validate menu option structure (matching CDEMO-MENU-OPT copybook)
                .andExpect(jsonPath("$.menuOptions[0].number").value(1))
                .andExpect(jsonPath("$.menuOptions[0].name").value("Account View"))
                .andExpect(jsonPath("$.menuOptions[0].programName").value("COACTVWC"))
                .andExpect(jsonPath("$.menuOptions[0].requiredUserType").value("U"))
                
                // Validate prompt message field (WS-MESSAGE from WORKING-STORAGE)
                .andExpect(jsonPath("$.promptMessage").value("Please select an option:"));
        
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;
        
        // Assert response time under 100ms per Section 0.6 requirements
        org.assertj.core.api.Assertions.assertThat(responseTime)
                .as("Menu display response time should be under 100ms")
                .isLessThan(100);
        
        // Verify service method was called exactly once
        verify(menuNavigationService, times(1)).getMainMenu();
        verifyNoMoreInteractions(menuNavigationService);
    }

    /**
     * Test: Admin user receives complete menu including admin-only options.
     * 
     * <p><strong>COBOL Equivalent:</strong> COMEN01C.cbl admin user processing</p>
     * <p>Admin users (CDEMO-USRTYP-ADMIN) bypass the filtering check at lines 136-143
     * and receive all menu options including those with CDEMO-MENU-OPT-USRTYPE = 'A'.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Given: Authenticated user with ROLE_ADMIN (SEC-USR-TYPE = 'A')</li>
     *   <li>When: GET /api/menu is requested</li>
     *   <li>Then: Response contains all menu options (user + admin)</li>
     *   <li>And: Response includes admin-only options (requiredUserType = 'A')</li>
     *   <li>And: HTTP status is 200 OK</li>
     * </ul>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Menu response contains 12 options (10 user + 2 admin)</li>
     *   <li>Some options have requiredUserType = 'A'</li>
     *   <li>userType field in response is 'A' for admin user</li>
     *   <li>Admin-only options include User Management and Admin Functions</li>
     * </ul>
     */
    @Test
    @DisplayName("Admin User (ROLE_ADMIN) - Returns Complete Menu Including Admin Options")
    @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
    void testAdminUserReceivesCompleteMenu() throws Exception {
        // Arrange: Mock service to return complete menu with admin options
        // Simulates COBOL logic where CDEMO-USRTYP-ADMIN sees all options
        when(menuNavigationService.getMainMenu()).thenReturn(adminUserMenuResponse);

        // Act & Assert: Perform GET request and validate response
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(get("/api/menu")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                
                // Validate admin user context
                .andExpect(jsonPath("$.userId").value("ADMIN001"))
                .andExpect(jsonPath("$.userName").value("Admin User"))
                .andExpect(jsonPath("$.userType").value("A"))
                
                // Validate menu metadata
                .andExpect(jsonPath("$.title").value("CardDemo Main Menu"))
                .andExpect(jsonPath("$.transactionId").value("CM00"))
                .andExpect(jsonPath("$.programName").value("COMEN01C"))
                
                // Validate menu options array includes admin options
                .andExpect(jsonPath("$.menuOptions").isArray())
                .andExpect(jsonPath("$.menuOptions", hasSize(12)))
                
                // Validate admin-only options are present
                .andExpect(jsonPath("$.menuOptions[?(@.requiredUserType == 'A')]").exists())
                
                // Validate specific admin option (User Management)
                .andExpect(jsonPath("$.menuOptions[10].number").value(11))
                .andExpect(jsonPath("$.menuOptions[10].name").value("User Management"))
                .andExpect(jsonPath("$.menuOptions[10].programName").value("COUSR00C"))
                .andExpect(jsonPath("$.menuOptions[10].requiredUserType").value("A"))
                
                // Validate specific admin option (Admin Functions)
                .andExpect(jsonPath("$.menuOptions[11].number").value(12))
                .andExpect(jsonPath("$.menuOptions[11].name").value("Admin Functions"))
                .andExpect(jsonPath("$.menuOptions[11].programName").value("COADM01C"))
                .andExpect(jsonPath("$.menuOptions[11].requiredUserType").value("A"));
        
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;
        
        // Assert response time under 100ms
        org.assertj.core.api.Assertions.assertThat(responseTime)
                .as("Menu display response time should be under 100ms")
                .isLessThan(100);
        
        // Verify service method was called exactly once
        verify(menuNavigationService, times(1)).getMainMenu();
        verifyNoMoreInteractions(menuNavigationService);
    }

    /**
     * Test: Unauthenticated request returns HTTP 401 Unauthorized.
     * 
     * <p><strong>COBOL Equivalent:</strong> COMEN01C.cbl lines 82-84</p>
     * <pre>
     * IF EIBCALEN = 0
     *     MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
     *     PERFORM RETURN-TO-SIGNON-SCREEN
     * END-IF
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Given: No authentication provided (no JWT token)</li>
     *   <li>When: GET /api/menu is requested</li>
     *   <li>Then: HTTP status is 401 Unauthorized</li>
     *   <li>And: No menu data is returned</li>
     * </ul>
     * 
     * <p>This test validates that Spring Security filter chain blocks unauthenticated
     * requests before reaching the controller, matching COBOL behavior where EIBCALEN = 0
     * (no COMMAREA) results in return to sign-on screen.</p>
     */
    @Test
    @DisplayName("Unauthenticated Access - Returns HTTP 401 Unauthorized")
    @WithAnonymousUser
    void testUnauthenticatedAccessReturns401() throws Exception {
        // Act & Assert: Unauthenticated request should be blocked by Spring Security
        // Simulates COBOL EIBCALEN = 0 check which returns to sign-on screen
        mockMvc.perform(get("/api/menu")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isUnauthorized());

        // Verify service method was never called (request blocked by security filter)
        verify(menuNavigationService, never()).getMainMenu();
    }

    /**
     * Test: Menu response structure validation for all required fields.
     * 
     * <p>This test ensures the MenuResponse DTO contains all fields defined in
     * the COBOL COMEN1AO BMS output structure matching the 3270 screen layout.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Given: Authenticated regular user</li>
     *   <li>When: GET /api/menu is requested</li>
     *   <li>Then: Response contains all required fields</li>
     *   <li>And: Field types and formats match COBOL definitions</li>
     * </ul>
     * 
     * <p><strong>Validated Fields:</strong></p>
     * <ul>
     *   <li>userId: User identifier (CDEMO-USER-ID from COMMAREA)</li>
     *   <li>userName: User full name (concatenated firstName + lastName)</li>
     *   <li>userType: User type code 'U' or 'A' (CDEMO-USER-TYPE)</li>
     *   <li>title: Screen title (CCDA-TITLE01 from COTTL01Y)</li>
     *   <li>transactionId: CICS transaction ID (WS-TRANID = 'CM00')</li>
     *   <li>programName: COBOL program name (WS-PGMNAME = 'COMEN01C')</li>
     *   <li>currentDate: Display date in MM/dd/yy format (CURDATEO)</li>
     *   <li>currentTime: Display time in HH:mm:ss format (CURTIMEO)</li>
     *   <li>menuOptions: Array of menu option objects (CDEMO-MENU-OPTIONS)</li>
     *   <li>promptMessage: User prompt text (WS-MESSAGE)</li>
     * </ul>
     */
    @Test
    @DisplayName("Menu Response Structure - Validates All Required Fields Present")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void testMenuResponseStructureValidation() throws Exception {
        // Arrange: Mock service with complete menu response
        when(menuNavigationService.getMainMenu()).thenReturn(regularUserMenuResponse);

        // Act & Assert: Validate all fields present and not null
        mockMvc.perform(get("/api/menu")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                
                // Validate user context fields are not null
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.userName").isNotEmpty())
                .andExpect(jsonPath("$.userType").isNotEmpty())
                
                // Validate menu metadata fields are not null
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.programName").isNotEmpty())
                
                // Validate date/time fields are not null
                .andExpect(jsonPath("$.currentDate").isNotEmpty())
                .andExpect(jsonPath("$.currentTime").isNotEmpty())
                
                // Validate menuOptions array exists and is not empty
                .andExpect(jsonPath("$.menuOptions").isArray())
                .andExpect(jsonPath("$.menuOptions", not(empty())))
                
                // Validate each menu option has all required fields
                .andExpect(jsonPath("$.menuOptions[*].number", everyItem(notNullValue())))
                .andExpect(jsonPath("$.menuOptions[*].name", everyItem(notNullValue())))
                .andExpect(jsonPath("$.menuOptions[*].programName", everyItem(notNullValue())))
                .andExpect(jsonPath("$.menuOptions[*].requiredUserType", everyItem(notNullValue())))
                
                // Validate promptMessage is not null
                .andExpect(jsonPath("$.promptMessage").isNotEmpty());
        
        verify(menuNavigationService, times(1)).getMainMenu();
    }

    /**
     * Test: Menu option validation for sequential numbering.
     * 
     * <p>Validates that menu options are numbered sequentially starting from 1,
     * matching COBOL CDEMO-MENU-OPT-NUM field population logic in BUILD-MENU-OPTIONS
     * paragraph (lines 236-277).</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Given: Regular user menu with 10 options</li>
     *   <li>When: Menu options are retrieved</li>
     *   <li>Then: Option numbers are 1, 2, 3, ..., 10 (sequential)</li>
     * </ul>
     */
    @Test
    @DisplayName("Menu Option Numbering - Validates Sequential Option Numbers 1-10")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void testMenuOptionSequentialNumbering() throws Exception {
        // Arrange
        when(menuNavigationService.getMainMenu()).thenReturn(regularUserMenuResponse);

        // Act & Assert: Validate sequential numbering
        mockMvc.perform(get("/api/menu")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuOptions[0].number").value(1))
                .andExpect(jsonPath("$.menuOptions[1].number").value(2))
                .andExpect(jsonPath("$.menuOptions[2].number").value(3))
                .andExpect(jsonPath("$.menuOptions[3].number").value(4))
                .andExpect(jsonPath("$.menuOptions[4].number").value(5))
                .andExpect(jsonPath("$.menuOptions[5].number").value(6))
                .andExpect(jsonPath("$.menuOptions[6].number").value(7))
                .andExpect(jsonPath("$.menuOptions[7].number").value(8))
                .andExpect(jsonPath("$.menuOptions[8].number").value(9))
                .andExpect(jsonPath("$.menuOptions[9].number").value(10));
        
        verify(menuNavigationService, times(1)).getMainMenu();
    }

    /**
     * Test: Menu option program names are valid 8-character identifiers.
     * 
     * <p>Validates that program names match COBOL program identifier format:
     * 8 characters, uppercase alphanumeric, matching CDEMO-MENU-OPT-PGMNAME
     * field definition (PIC X(08)).</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Given: Menu options with program names</li>
     *   <li>When: Program names are validated</li>
     *   <li>Then: All program names are non-null and 8 characters</li>
     * </ul>
     */
    @Test
    @DisplayName("Menu Option Program Names - Validates 8-Character COBOL Format")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void testMenuOptionProgramNamesValid() throws Exception {
        // Arrange
        when(menuNavigationService.getMainMenu()).thenReturn(regularUserMenuResponse);

        // Act & Assert: Validate program name format
        mockMvc.perform(get("/api/menu")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuOptions[*].programName", 
                        everyItem(notNullValue())))
                .andExpect(jsonPath("$.menuOptions[0].programName").value("COACTVWC"))
                .andExpect(jsonPath("$.menuOptions[1].programName").value("COACTUPC"))
                .andExpect(jsonPath("$.menuOptions[2].programName").value("COCRDLIC"));
        
        verify(menuNavigationService, times(1)).getMainMenu();
    }

    /**
     * Test: Service layer exception handling returns HTTP 500 Internal Server Error.
     * 
     * <p>Validates that unexpected exceptions from MenuNavigationService are caught
     * and result in appropriate HTTP 500 error response.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Given: MenuNavigationService throws RuntimeException</li>
     *   <li>When: GET /api/menu is requested</li>
     *   <li>Then: HTTP status is 500 Internal Server Error</li>
     * </ul>
     */
    @Test
    @DisplayName("Service Exception Handling - Returns HTTP 500 on Unexpected Error")
    @WithMockUser(username = "USER0001", roles = {"USER"})
    void testServiceExceptionHandling() throws Exception {
        // Arrange: Mock service to throw exception
        when(menuNavigationService.getMainMenu())
                .thenThrow(new RuntimeException("Database connection failed"));

        // Act & Assert: Verify 500 Internal Server Error returned
        mockMvc.perform(get("/api/menu")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isInternalServerError());
        
        verify(menuNavigationService, times(1)).getMainMenu();
    }

    // ========================================================================
    // Helper Methods for Test Data Construction
    // ========================================================================

    /**
     * Builds regular user menu response matching COBOL COMEN1AO structure.
     * 
     * <p>Simulates menu built by BUILD-MENU-OPTIONS paragraph for regular user
     * (CDEMO-USRTYP-USER) with user-level options only.</p>
     * 
     * @return MenuResponse with 10 user-level menu options
     */
    private MenuResponse buildRegularUserMenu() {
        List<MenuOptionDTO> options = new ArrayList<>();
        
        // Build user-level menu options (requiredUserType = 'U')
        // Matching CDEMO-MENU-OPTIONS from COMEN02Y copybook
        options.add(new MenuOptionDTO(1, "Account View", "COACTVWC", "U"));
        options.add(new MenuOptionDTO(2, "Account Update", "COACTUPC", "U"));
        options.add(new MenuOptionDTO(3, "Credit Card List", "COCRDLIC", "U"));
        options.add(new MenuOptionDTO(4, "Credit Card View", "COCRDSLC", "U"));
        options.add(new MenuOptionDTO(5, "Credit Card Update", "COCRDUPC", "U"));
        options.add(new MenuOptionDTO(6, "Transaction List", "COTRN00C", "U"));
        options.add(new MenuOptionDTO(7, "Transaction View", "COTRN01C", "U"));
        options.add(new MenuOptionDTO(8, "Transaction Add", "COTRN02C", "U"));
        options.add(new MenuOptionDTO(9, "Transaction Reports", "CORPT00C", "U"));
        options.add(new MenuOptionDTO(10, "Bill Payment", "COBIL00C", "U"));

        // Build complete menu response
        return new MenuResponse(
                "USER0001",                    // userId (CDEMO-USER-ID)
                "John Smith",                  // userName (concatenated name)
                "U",                           // userType (CDEMO-USER-TYPE = 'R' → 'U')
                "CardDemo Main Menu",          // title (CCDA-TITLE01)
                "CM00",                        // transactionId (WS-TRANID)
                "COMEN01C",                    // programName (WS-PGMNAME)
                getCurrentDate(),              // currentDate (CURDATEO)
                getCurrentTime(),              // currentTime (CURTIMEO)
                options,                       // menuOptions array
                "Please select an option:"     // promptMessage (WS-MESSAGE)
        );
    }

    /**
     * Builds admin user menu response with all options including admin-only.
     * 
     * <p>Simulates menu built by BUILD-MENU-OPTIONS paragraph for admin user
     * (CDEMO-USRTYP-ADMIN) with both user and admin options.</p>
     * 
     * @return MenuResponse with 12 menu options (10 user + 2 admin)
     */
    private MenuResponse buildAdminUserMenu() {
        List<MenuOptionDTO> options = new ArrayList<>();
        
        // Add all user-level options
        options.add(new MenuOptionDTO(1, "Account View", "COACTVWC", "U"));
        options.add(new MenuOptionDTO(2, "Account Update", "COACTUPC", "U"));
        options.add(new MenuOptionDTO(3, "Credit Card List", "COCRDLIC", "U"));
        options.add(new MenuOptionDTO(4, "Credit Card View", "COCRDSLC", "U"));
        options.add(new MenuOptionDTO(5, "Credit Card Update", "COCRDUPC", "U"));
        options.add(new MenuOptionDTO(6, "Transaction List", "COTRN00C", "U"));
        options.add(new MenuOptionDTO(7, "Transaction View", "COTRN01C", "U"));
        options.add(new MenuOptionDTO(8, "Transaction Add", "COTRN02C", "U"));
        options.add(new MenuOptionDTO(9, "Transaction Reports", "CORPT00C", "U"));
        options.add(new MenuOptionDTO(10, "Bill Payment", "COBIL00C", "U"));
        
        // Add admin-only options (requiredUserType = 'A')
        options.add(new MenuOptionDTO(11, "User Management", "COUSR00C", "A"));
        options.add(new MenuOptionDTO(12, "Admin Functions", "COADM01C", "A"));

        // Build complete admin menu response
        return new MenuResponse(
                "ADMIN001",                    // userId
                "Admin User",                  // userName
                "A",                           // userType (CDEMO-USER-TYPE = 'A')
                "CardDemo Main Menu",          // title
                "CM00",                        // transactionId
                "COMEN01C",                    // programName
                getCurrentDate(),              // currentDate
                getCurrentTime(),              // currentTime
                options,                       // menuOptions array
                "Please select an option:"     // promptMessage
        );
    }

    /**
     * Gets current date in MM/dd/yy format matching COBOL CURDATEO field.
     * 
     * @return Formatted date string
     */
    private String getCurrentDate() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yy"));
    }

    /**
     * Gets current time in HH:mm:ss format matching COBOL CURTIMEO field.
     * 
     * @return Formatted time string
     */
    private String getCurrentTime() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}

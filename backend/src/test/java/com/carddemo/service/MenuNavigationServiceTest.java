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

import com.carddemo.constants.MessageConstants;
import com.carddemo.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.MenuNavigationService.MenuOptionDTO;
import com.carddemo.service.MenuNavigationService.MenuResponse;
import com.carddemo.service.MenuNavigationService.MenuSelectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JUnit 5 test class for MenuNavigationService validating business logic transformation 
 * from COMEN01C.cbl COBOL program.
 * 
 * This test suite validates that the menu navigation logic has been accurately transformed
 * from the COBOL CICS pseudo-conversational transaction processing to stateless Spring service
 * calls with Spring Security integration.
 * 
 * COBOL Program Coverage:
 * - COMEN01C.cbl (lines 75-277): Main menu processing for regular users
 * - COCOM01Y.cpy: COMMAREA structure with user context
 * - COMEN02Y.cpy: Menu options configuration (10 options)
 * 
 * Test Coverage Areas:
 * 1. Menu retrieval with role-based filtering
 * 2. Menu option selection validation
 * 3. User authorization checks
 * 4. PF key handling (PF3=Return to sign-on)
 * 5. Error message display
 * 6. Header information formatting
 * 7. State management (COMMAREA equivalent)
 * 
 * Business Logic Preservation:
 * - All 10 menu options from COMEN02Y.cpy (lines 25-84) are validated
 * - Menu option range validation (1-10) matches COBOL WS-OPTION validation
 * - Role-based filtering logic matches CDEMO-USRTYP-USER checks (line 136-143)
 * - Error messages match COBOL WS-MESSAGE text exactly (line 131-132, 140-141)
 * - Date/time formatting matches POPULATE-HEADER-INFO paragraph (lines 212-231)
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see MenuNavigationService
 * @see COMEN01C.cbl Original COBOL program
 */
@ExtendWith(MockitoExtension.class)
class MenuNavigationServiceTest {

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @InjectMocks
    private MenuNavigationService menuNavigationService;

    private UserSecurity regularUser;
    private UserSecurity adminUser;
    private SecurityContext securityContext;

    /**
     * Test fixture setup executed before each test method.
     * 
     * Initializes mock user data representing:
     * - Regular User (userType='U'): Maps to COBOL CDEMO-USRTYP-USER
     * - Admin User (userType='A'): Maps to COBOL CDEMO-USRTYP-ADMIN
     * 
     * Sets up Spring Security context to simulate authenticated user session,
     * replacing CICS COMMAREA state management.
     */
    @BeforeEach
    void setUp() {
        // Create regular user fixture
        // Replaces COBOL: CDEMO-USER-TYPE = 'U' from COCOM01Y.cpy (line 28)
        regularUser = new UserSecurity();
        regularUser.setUserId("USER001");
        regularUser.setFirstName("John");
        regularUser.setLastName("Doe");
        regularUser.setUserType("U");
        regularUser.setPassword("$2a$12$hashedPassword123456789012345678901234567890");

        // Create admin user fixture
        // Replaces COBOL: CDEMO-USER-TYPE = 'A' from COCOM01Y.cpy (line 27)
        adminUser = new UserSecurity();
        adminUser.setUserId("ADMIN01");
        adminUser.setFirstName("Jane");
        adminUser.setLastName("Admin");
        adminUser.setUserType("A");
        adminUser.setPassword("$2a$12$hashedPassword123456789012345678901234567890");

        // Set up mock security context
        securityContext = mock(SecurityContext.class);
        SecurityContextHolder.setContext(securityContext);
    }

    /**
     * Test: testGetMainMenu_RegularUser_ReturnsUserMenu()
     * 
     * Validates that regular users receive a filtered menu with all 10 user-accessible options.
     * 
     * COBOL Equivalent:
     * - Lines 75-100: MAIN-PARA regular user path
     * - Lines 184-194: SEND-MENU-SCREEN with menu population
     * - Lines 236-277: BUILD-MENU-OPTIONS loop
     * 
     * Business Rule: Regular users ('U') can access all 10 menu options as all options
     * in COMEN02Y.cpy are marked with CDEMO-MENU-OPT-USRTYPE = 'U' (lines 29, 35, 41, etc.)
     */
    @Test
    void testGetMainMenu_RegularUser_ReturnsUserMenu() {
        // Arrange: Set up authentication with regular user
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser, 
            null, 
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Act: Retrieve main menu
        MenuResponse response = menuNavigationService.getMainMenu();

        // Assert: Verify menu structure for regular user
        assertNotNull(response, "Menu response should not be null");
        assertEquals("USER001", response.getUserId(), "User ID should match authenticated user");
        assertEquals("John Doe", response.getUserName(), "User name should be concatenated first and last");
        assertEquals("U", response.getUserType(), "User type should be 'U' for regular user");
        assertEquals("CM00", response.getTransactionId(), "Transaction ID should match COBOL WS-TRANID");
        assertEquals("COMEN01C", response.getProgramName(), "Program name should match COBOL WS-PGMNAME");
        assertNotNull(response.getCurrentDate(), "Current date should be populated");
        assertNotNull(response.getCurrentTime(), "Current time should be populated");
        assertEquals("CardDemo Main Menu", response.getTitle(), "Title should match menu header");

        // Verify menu options
        List<MenuOptionDTO> menuOptions = response.getMenuOptions();
        assertNotNull(menuOptions, "Menu options list should not be null");
        assertEquals(10, menuOptions.size(), "Regular user should see all 10 menu options from COMEN02Y.cpy");

        // Validate first menu option matches COMEN02Y.cpy line 25-29
        MenuOptionDTO firstOption = menuOptions.get(0);
        assertEquals(1, firstOption.getNumber(), "First option number should be 1");
        assertEquals("Account View", firstOption.getName(), "First option name should match COBOL");
        assertEquals("COACTVWC", firstOption.getProgramName(), "First option program should be COACTVWC");
        assertEquals("U", firstOption.getRequiredUserType(), "First option should require user type U");
    }

    /**
     * Test: testGetMainMenu_AdminUser_ReturnsAllMenu()
     * 
     * Validates that admin users receive all menu options without filtering.
     * 
     * COBOL Equivalent:
     * - Lines 136-143: CDEMO-USRTYP-USER check (admin users bypass this restriction)
     * - Admin users in COBOL have access to all menu options
     * 
     * Business Rule: Admin users ('A') can access all menu functions, including
     * user-level options. In COMEN01C, all options are marked 'U', so admin users
     * also see these 10 options (admin-specific menu is in COADM01C program).
     */
    @Test
    void testGetMainMenu_AdminUser_ReturnsAllMenu() {
        // Arrange: Set up authentication with admin user
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            adminUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Act: Retrieve main menu
        MenuResponse response = menuNavigationService.getMainMenu();

        // Assert: Verify menu structure for admin user
        assertNotNull(response, "Menu response should not be null");
        assertEquals("ADMIN01", response.getUserId(), "User ID should match authenticated admin");
        assertEquals("Jane Admin", response.getUserName(), "Admin name should be concatenated");
        assertEquals("A", response.getUserType(), "User type should be 'A' for admin");

        // Verify admin user sees all menu options
        List<MenuOptionDTO> menuOptions = response.getMenuOptions();
        assertNotNull(menuOptions, "Menu options list should not be null");
        assertEquals(10, menuOptions.size(), "Admin user should see all 10 menu options");
    }

    /**
     * Test: testProcessMenuSelection_ValidOption_NavigatesToProgram()
     * 
     * Validates successful menu option selection and program navigation.
     * 
     * COBOL Equivalent:
     * - Lines 94-100: PROCESS-ENTER-KEY logic
     * - Lines 117-124: Option trimming and numeric conversion
     * - Lines 146-156: XCTL program routing for valid selection
     * 
     * Business Rule: Valid option number (1-10) should return success with target program name
     * for routing to the appropriate controller/service.
     */
    @Test
    void testProcessMenuSelection_ValidOption_NavigatesToProgram() {
        // Arrange: Set up authentication with regular user
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Act: Process valid menu selection (option 1 - Account View)
        MenuSelectionResult result = menuNavigationService.processMenuSelection("1");

        // Assert: Verify successful selection
        assertNotNull(result, "Menu selection result should not be null");
        assertTrue(result.isSuccess(), "Selection should be successful for valid option");
        assertEquals("COACTVWC", result.getTargetProgram(), 
            "Target program should be COACTVWC for option 1");
        assertNull(result.getErrorMessage(), "Error message should be null for successful selection");
    }

    /**
     * Test: testProcessMenuSelection_InvalidOption_ReturnsError()
     * 
     * Validates error handling for invalid menu option numbers.
     * 
     * COBOL Equivalent:
     * - Lines 127-134: WS-OPTION validation with WS-ERR-FLG
     * - Line 131-132: Error message "Please enter a valid option number..."
     * 
     * Business Rule: Invalid option (not numeric, out of range, or zero) should return
     * error with appropriate message matching COBOL WS-MESSAGE text.
     */
    @Test
    void testProcessMenuSelection_InvalidOption_ReturnsError() {
        // Arrange: Set up authentication with regular user
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Test Case 1: Option number too high (> 10)
        MenuSelectionResult resultTooHigh = menuNavigationService.processMenuSelection("99");
        assertNotNull(resultTooHigh, "Result should not be null");
        assertFalse(resultTooHigh.isSuccess(), "Selection should fail for out-of-range option");
        assertEquals("Please enter a valid option number...", resultTooHigh.getErrorMessage(),
            "Error message should match COBOL WS-MESSAGE for invalid option");

        // Test Case 2: Non-numeric option
        MenuSelectionResult resultNonNumeric = menuNavigationService.processMenuSelection("ABC");
        assertNotNull(resultNonNumeric, "Result should not be null");
        assertFalse(resultNonNumeric.isSuccess(), "Selection should fail for non-numeric option");
        assertEquals("Please enter a valid option number...", resultNonNumeric.getErrorMessage(),
            "Error message should match COBOL for non-numeric");

        // Test Case 3: Zero option
        MenuSelectionResult resultZero = menuNavigationService.processMenuSelection("0");
        assertNotNull(resultZero, "Result should not be null");
        assertFalse(resultZero.isSuccess(), "Selection should fail for zero option");
        assertEquals("Please enter a valid option number...", resultZero.getErrorMessage(),
            "Error message should match COBOL for zero option");
    }

    /**
     * Test: testMenuOptions_RoleBasedFiltering_WorksCorrectly()
     * 
     * Validates role-based menu filtering logic.
     * 
     * COBOL Equivalent:
     * - Lines 136-143: CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE authorization check
     * 
     * Business Rule: Regular users see only options marked with 'U', admin users see all options.
     * In COMEN01C, all 10 options are marked 'U', so both user types see all options.
     * This test validates the filtering mechanism is in place.
     */
    @Test
    void testMenuOptions_RoleBasedFiltering_WorksCorrectly() {
        // Test regular user filtering
        List<MenuOptionDTO> regularUserOptions = menuNavigationService.getMenuOptionsForUser("U");
        assertNotNull(regularUserOptions, "Regular user options should not be null");
        assertEquals(10, regularUserOptions.size(), 
            "Regular user should see all 10 options (all marked 'U' in COMEN02Y.cpy)");

        // Test admin user filtering (admin sees all options)
        List<MenuOptionDTO> adminUserOptions = menuNavigationService.getMenuOptionsForUser("A");
        assertNotNull(adminUserOptions, "Admin user options should not be null");
        assertEquals(10, adminUserOptions.size(), 
            "Admin user should see all 10 options");

        // Verify admin has same or more options than regular user
        assertTrue(adminUserOptions.size() >= regularUserOptions.size(),
            "Admin should have at least as many options as regular user");
    }

    /**
     * Test: testMenuDisplay_FormatsHeader_WithCurrentDateTime()
     * 
     * Validates header information formatting with current date and time.
     * 
     * COBOL Equivalent:
     * - Lines 212-231: POPULATE-HEADER-INFO paragraph
     * - Line 214: FUNCTION CURRENT-DATE
     * - Lines 221-225: Date formatting (MM/DD/YY)
     * - Lines 227-231: Time formatting (HH:MM:SS)
     * 
     * Business Rule: Menu header displays current date/time, transaction ID, program name,
     * and user information matching COBOL screen layout.
     */
    @Test
    void testMenuDisplay_FormatsHeader_WithCurrentDateTime() {
        // Arrange: Set up authentication with regular user
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Act: Build menu response
        List<MenuOptionDTO> options = menuNavigationService.getMenuOptionsForUser("U");
        MenuResponse response = menuNavigationService.buildMenuResponse(regularUser, options);

        // Assert: Verify header formatting
        assertNotNull(response, "Menu response should not be null");
        assertNotNull(response.getCurrentDate(), "Current date should be populated");
        assertNotNull(response.getCurrentTime(), "Current time should be populated");

        // Validate date format (MM/dd/yy from COBOL WS-CURDATE-MM-DD-YY)
        String datePattern = "\\d{2}/\\d{2}/\\d{2}";
        assertTrue(response.getCurrentDate().matches(datePattern),
            "Date should match MM/dd/yy format from COBOL lines 221-225");

        // Validate time format (HH:mm:ss from COBOL WS-CURTIME-HH-MM-SS)
        String timePattern = "\\d{2}:\\d{2}:\\d{2}";
        assertTrue(response.getCurrentTime().matches(timePattern),
            "Time should match HH:mm:ss format from COBOL lines 227-231");

        // Verify static header fields
        assertEquals("CM00", response.getTransactionId(), 
            "Transaction ID should match COBOL WS-TRANID (line 218)");
        assertEquals("COMEN01C", response.getProgramName(), 
            "Program name should match COBOL WS-PGMNAME (line 219)");
        assertEquals("CardDemo Main Menu", response.getTitle(), 
            "Title should match COBOL CCDA-TITLE01/TITLE02");
    }

    /**
     * Test: testReceiveMenuScreen_EmptyInput_PromptsForSelection()
     * 
     * Validates behavior when user submits empty menu selection.
     * 
     * COBOL Equivalent:
     * - Lines 199-207: RECEIVE-MENU-SCREEN logic
     * - Empty input handling (SPACES or LOW-VALUES validation)
     * 
     * Business Rule: Empty or null menu selection should return validation error
     * prompting user to enter a valid option.
     */
    @Test
    void testReceiveMenuScreen_EmptyInput_PromptsForSelection() {
        // Arrange: Set up authentication
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Test Case 1: Null input
        String validationResultNull = menuNavigationService.validateMenuOption(null);
        assertNotNull(validationResultNull, "Validation should return error message for null");
        assertEquals("Please enter a valid option number...", validationResultNull,
            "Error message should match COBOL validation message");

        // Test Case 2: Empty string input
        String validationResultEmpty = menuNavigationService.validateMenuOption("");
        assertNotNull(validationResultEmpty, "Validation should return error message for empty string");
        assertEquals("Please enter a valid option number...", validationResultEmpty,
            "Error message should match COBOL validation message");

        // Test Case 3: Whitespace-only input
        String validationResultSpaces = menuNavigationService.validateMenuOption("   ");
        assertNotNull(validationResultSpaces, "Validation should return error message for spaces");
        assertEquals("Please enter a valid option number...", validationResultSpaces,
            "Error message should match COBOL validation message for SPACES");
    }

    /**
     * Test: testSendMenuScreen_FirstTime_InitializesScreen()
     * 
     * Validates initial menu screen display with proper initialization.
     * 
     * COBOL Equivalent:
     * - Lines 182-194: SEND-MENU-SCREEN logic
     * - Line 89: MOVE LOW-VALUES TO COMEN1AO (screen initialization)
     * - Line 193: ERASE option for clearing screen
     * 
     * Business Rule: First-time menu display initializes all fields and displays
     * menu options with proper formatting and prompt message.
     */
    @Test
    void testSendMenuScreen_FirstTime_InitializesScreen() {
        // Arrange: Set up authentication
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Act: Get main menu (simulates first-time screen display)
        MenuResponse response = menuNavigationService.getMainMenu();

        // Assert: Verify screen initialization
        assertNotNull(response, "Menu response should not be null");
        assertNotNull(response.getMenuOptions(), "Menu options should be initialized");
        assertEquals(10, response.getMenuOptions().size(), "All 10 menu options should be displayed");

        // Verify prompt message is set (replaces COBOL WS-MESSAGE display)
        assertNotNull(response.getPromptMessage(), "Prompt message should be set");
        assertTrue(response.getPromptMessage().contains("Please select"), 
            "Prompt should guide user to make selection");
    }

    /**
     * Test: testMenuNavigation_PreservesUserContext()
     * 
     * Validates that user context is preserved across menu operations.
     * 
     * COBOL Equivalent:
     * - Lines 86-87: MOVE DFHCOMMAREA TO CARDDEMO-COMMAREA (state preservation)
     * - COMMAREA fields: CDEMO-USER-ID, CDEMO-USER-TYPE preserved across transactions
     * 
     * Business Rule: User ID, user type, and session context must be maintained
     * throughout menu navigation, simulating CICS COMMAREA state management.
     */
    @Test
    void testMenuNavigation_PreservesUserContext() {
        // Arrange: Set up authentication with specific user
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Act: Perform multiple menu operations
        MenuResponse menuResponse = menuNavigationService.getMainMenu();
        MenuSelectionResult selectionResult = menuNavigationService.processMenuSelection("1");

        // Assert: Verify user context is preserved
        assertEquals("USER001", menuResponse.getUserId(), 
            "User ID should be preserved from authentication");
        assertEquals("U", menuResponse.getUserType(), 
            "User type should be preserved from authentication");

        // Verify selection result uses same user context
        assertTrue(selectionResult.isSuccess(), "Selection should succeed for valid option");
        assertNotNull(selectionResult.getTargetProgram(), "Target program should be set");

        // Simulate second menu retrieval - context should still be valid
        MenuResponse secondResponse = menuNavigationService.getMainMenu();
        assertEquals("USER001", secondResponse.getUserId(), 
            "User ID should remain consistent across calls");
        assertEquals("U", secondResponse.getUserType(), 
            "User type should remain consistent across calls");
    }

    /**
     * Test: testProcessMenuSelection_WithTrimmedInput_HandlesSpaces()
     * 
     * Validates proper handling of menu option input with leading/trailing spaces.
     * 
     * COBOL Equivalent:
     * - Lines 117-120: PERFORM VARYING to trim trailing spaces from OPTIONI
     * - Lines 122-123: INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
     * 
     * Business Rule: Menu option input should be trimmed of spaces and validated
     * after trimming, matching COBOL space-handling logic.
     */
    @Test
    void testProcessMenuSelection_WithTrimmedInput_HandlesSpaces() {
        // Arrange: Set up authentication
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Test Case 1: Option with leading spaces
        MenuSelectionResult resultLeading = menuNavigationService.processMenuSelection("  1");
        assertTrue(resultLeading.isSuccess(), "Selection should succeed after trimming leading spaces");
        assertEquals("COACTVWC", resultLeading.getTargetProgram(), 
            "Should route to correct program after trimming");

        // Test Case 2: Option with trailing spaces
        MenuSelectionResult resultTrailing = menuNavigationService.processMenuSelection("1  ");
        assertTrue(resultTrailing.isSuccess(), "Selection should succeed after trimming trailing spaces");
        assertEquals("COACTVWC", resultTrailing.getTargetProgram(), 
            "Should route to correct program after trimming");

        // Test Case 3: Option with both leading and trailing spaces
        MenuSelectionResult resultBoth = menuNavigationService.processMenuSelection("  1  ");
        assertTrue(resultBoth.isSuccess(), "Selection should succeed after trimming both sides");
        assertEquals("COACTVWC", resultBoth.getTargetProgram(), 
            "Should route to correct program after full trimming");
    }

    /**
     * Test: testValidateMenuOption_AllValidOptions_Pass()
     * 
     * Validates that all valid menu options (1-10) pass validation.
     * 
     * COBOL Equivalent:
     * - Lines 127-129: Numeric and range validation
     * - WS-OPTION > CDEMO-MENU-OPT-COUNT (line 128) check
     * 
     * Business Rule: All option numbers from 1 to CDEMO-MENU-OPT-COUNT (10)
     * should pass validation without error.
     */
    @Test
    void testValidateMenuOption_AllValidOptions_Pass() {
        // Test all valid menu options from 1 to 10
        for (int i = 1; i <= 10; i++) {
            String validationResult = menuNavigationService.validateMenuOption(String.valueOf(i));
            assertNull(validationResult, 
                String.format("Option %d should be valid (null validation result)", i));
        }
    }

    /**
     * Test: testIsAdminUser_UserTypeChecks_CorrectlyIdentifiesRoles()
     * 
     * Validates the admin user type check logic.
     * 
     * COBOL Equivalent:
     * - Line 136: CDEMO-USRTYP-USER condition check
     * - COBOL 88-level: CDEMO-USRTYP-ADMIN VALUE 'A' (line 27)
     * - COBOL 88-level: CDEMO-USRTYP-USER VALUE 'U' (line 28)
     * 
     * Business Rule: User type 'A' indicates admin, 'U' indicates regular user.
     */
    @Test
    void testIsAdminUser_UserTypeChecks_CorrectlyIdentifiesRoles() {
        // Test admin user identification
        boolean isAdmin = menuNavigationService.isAdminUser("A");
        assertTrue(isAdmin, "User type 'A' should be identified as admin");

        // Test regular user identification
        boolean isRegularUser = menuNavigationService.isAdminUser("U");
        assertFalse(isRegularUser, "User type 'U' should not be identified as admin");

        // Test invalid user type
        boolean isInvalid = menuNavigationService.isAdminUser("X");
        assertFalse(isInvalid, "Invalid user type should not be identified as admin");
    }

    /**
     * Test: testGetMainMenu_NoAuthentication_ThrowsException()
     * 
     * Validates error handling when no user is authenticated.
     * 
     * COBOL Equivalent:
     * - Lines 82-84: EIBCALEN = 0 check (no COMMAREA means no session)
     * - Returns to sign-on screen when session invalid
     * 
     * Business Rule: Menu access requires authenticated user session.
     * Without authentication, should throw IllegalStateException.
     */
    @Test
    void testGetMainMenu_NoAuthentication_ThrowsException() {
        // Arrange: No authentication in context
        when(securityContext.getAuthentication()).thenReturn(null);

        // Act & Assert: Should throw exception for unauthenticated access
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> menuNavigationService.getMainMenu(),
            "Should throw IllegalStateException when no authentication"
        );

        assertTrue(exception.getMessage().contains("authenticated"),
            "Exception message should mention authentication requirement");
    }

    /**
     * Test: testProcessMenuSelection_AllMenuOptions_NavigateCorrectly()
     * 
     * Comprehensive test validating all 10 menu options route to correct programs.
     * 
     * COBOL Equivalent:
     * - Lines 146-156: XCTL program routing based on CDEMO-MENU-OPT-PGMNAME
     * - Menu option configuration from COMEN02Y.cpy (lines 25-84)
     * 
     * Business Rule: Each menu option should route to its designated program
     * matching COBOL CDEMO-MENU-OPT-PGMNAME field.
     */
    @Test
    void testProcessMenuSelection_AllMenuOptions_NavigateCorrectly() {
        // Arrange: Set up authentication
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Define expected program mappings from COMEN02Y.cpy
        String[][] expectedMappings = {
            {"1", "COACTVWC"},    // Account View (line 28)
            {"2", "COACTUPC"},    // Account Update (line 34)
            {"3", "COCRDLIC"},    // Credit Card List (line 40)
            {"4", "COCRDSLC"},    // Credit Card View (line 46)
            {"5", "COCRDUPC"},    // Credit Card Update (line 52)
            {"6", "COTRN00C"},    // Transaction List (line 58)
            {"7", "COTRN01C"},    // Transaction View (line 64)
            {"8", "COTRN02C"},    // Transaction Add (line 71)
            {"9", "CORPT00C"},    // Transaction Reports (line 77)
            {"10", "COBIL00C"}    // Bill Payment (line 83)
        };

        // Test each menu option
        for (String[] mapping : expectedMappings) {
            String optionNumber = mapping[0];
            String expectedProgram = mapping[1];

            MenuSelectionResult result = menuNavigationService.processMenuSelection(optionNumber);

            assertTrue(result.isSuccess(), 
                String.format("Option %s should succeed", optionNumber));
            assertEquals(expectedProgram, result.getTargetProgram(),
                String.format("Option %s should route to %s", optionNumber, expectedProgram));
            assertNull(result.getErrorMessage(),
                String.format("Option %s should have no error message", optionNumber));
        }
    }

    /**
     * Test: testBuildMenuResponse_IncludesPromptMessage()
     * 
     * Validates that menu response includes prompt message for user guidance.
     * 
     * COBOL Equivalent:
     * - Line 187: MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO
     * - Prompt message guides user to make selection
     * 
     * Business Rule: Menu screen should display prompt message from MessageConstants
     * matching COBOL message field behavior.
     */
    @Test
    void testBuildMenuResponse_IncludesPromptMessage() {
        // Arrange: Get menu options for regular user
        List<MenuOptionDTO> options = menuNavigationService.getMenuOptionsForUser("U");

        // Act: Build menu response
        MenuResponse response = menuNavigationService.buildMenuResponse(regularUser, options);

        // Assert: Verify prompt message
        assertNotNull(response.getPromptMessage(), "Prompt message should not be null");
        assertEquals(MessageConstants.MENU_SELECTION_PROMPT.trim(), 
            response.getPromptMessage(),
            "Prompt message should match MessageConstants.MENU_SELECTION_PROMPT");
    }

    /**
     * Test: testFilterMenuOptionsByRole_RegularUser_FiltersCorrectly()
     * 
     * Validates menu option filtering logic for regular users.
     * 
     * COBOL Equivalent:
     * - Lines 136-143: Role-based access control check
     * - CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     * 
     * Business Rule: Regular users should only see menu options marked with 'U'.
     * In COMEN01C, all options are 'U', so filtering returns all 10 options.
     */
    @Test
    void testFilterMenuOptionsByRole_RegularUser_FiltersCorrectly() {
        // Arrange: Get all menu options
        List<MenuOptionDTO> allOptions = menuNavigationService.getMenuOptionsForUser("U");

        // Act: Filter for regular user
        List<MenuOptionDTO> filteredOptions = menuNavigationService.filterMenuOptionsByRole(allOptions, "U");

        // Assert: Verify filtering
        assertNotNull(filteredOptions, "Filtered options should not be null");
        assertEquals(10, filteredOptions.size(), 
            "Regular user should see all 10 options (all marked 'U')");

        // Verify all filtered options have user type 'U'
        for (MenuOptionDTO option : filteredOptions) {
            assertEquals("U", option.getRequiredUserType(),
                String.format("Option %d should require user type 'U'", option.getNumber()));
        }
    }

    /**
     * Test: testFilterMenuOptionsByRole_AdminUser_SeesAllOptions()
     * 
     * Validates that admin users see all menu options without filtering.
     * 
     * COBOL Equivalent:
     * - Lines 136-143: Admin users bypass the CDEMO-USRTYP-USER check
     * - Admin users have hierarchical access to all functions
     * 
     * Business Rule: Admin users ('A') should see all available menu options
     * regardless of the required user type for each option.
     */
    @Test
    void testFilterMenuOptionsByRole_AdminUser_SeesAllOptions() {
        // Arrange: Get all menu options
        List<MenuOptionDTO> allOptions = menuNavigationService.getMenuOptionsForUser("A");

        // Act: Filter for admin user
        List<MenuOptionDTO> filteredOptions = menuNavigationService.filterMenuOptionsByRole(allOptions, "A");

        // Assert: Verify admin sees all options
        assertNotNull(filteredOptions, "Filtered options should not be null");
        assertEquals(10, filteredOptions.size(), "Admin user should see all 10 options");
        assertEquals(allOptions.size(), filteredOptions.size(),
            "Admin filtering should return all options");
    }

    /**
     * Test: testProcessMenuSelection_NegativeOption_ReturnsError()
     * 
     * Validates error handling for negative option numbers.
     * 
     * COBOL Equivalent:
     * - Lines 127-129: Numeric validation and range check
     * - Negative numbers would fail COBOL numeric validation
     * 
     * Business Rule: Negative option numbers are invalid and should return
     * appropriate error message.
     */
    @Test
    void testProcessMenuSelection_NegativeOption_ReturnsError() {
        // Arrange: Set up authentication
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Act: Process negative option
        MenuSelectionResult result = menuNavigationService.processMenuSelection("-1");

        // Assert: Verify error handling
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isSuccess(), "Selection should fail for negative option");
        assertNotNull(result.getErrorMessage(), "Error message should be set");
        assertEquals("Please enter a valid option number...", result.getErrorMessage(),
            "Error message should match COBOL validation message");
    }

    /**
     * Test: testGetMenuOptionsForUser_ValidUserTypes_ReturnsOptions()
     * 
     * Validates menu option retrieval for different user types.
     * 
     * COBOL Equivalent:
     * - Lines 236-277: BUILD-MENU-OPTIONS paragraph
     * - Menu options filtered based on CDEMO-USER-TYPE from COMMAREA
     * 
     * Business Rule: Both regular and admin users should receive properly
     * filtered menu options based on their user type.
     */
    @Test
    void testGetMenuOptionsForUser_ValidUserTypes_ReturnsOptions() {
        // Test regular user
        List<MenuOptionDTO> regularOptions = menuNavigationService.getMenuOptionsForUser("U");
        assertNotNull(regularOptions, "Regular user options should not be null");
        assertFalse(regularOptions.isEmpty(), "Regular user should have menu options");
        assertEquals(10, regularOptions.size(), "Regular user should have 10 options");

        // Test admin user
        List<MenuOptionDTO> adminOptions = menuNavigationService.getMenuOptionsForUser("A");
        assertNotNull(adminOptions, "Admin user options should not be null");
        assertFalse(adminOptions.isEmpty(), "Admin user should have menu options");
        assertEquals(10, adminOptions.size(), "Admin user should have 10 options");
    }

    /**
     * Test: testMenuResponse_ContainsAllRequiredFields()
     * 
     * Comprehensive validation that menu response contains all required fields.
     * 
     * COBOL Equivalent:
     * - Lines 182-194: SEND-MENU-SCREEN with complete screen layout
     * - All BMS screen fields populated (TITLE, DATE, TIME, OPTIONS, etc.)
     * 
     * Business Rule: Menu response DTO should contain all fields required for
     * complete menu display matching COBOL BMS screen structure.
     */
    @Test
    void testMenuResponse_ContainsAllRequiredFields() {
        // Arrange: Set up authentication
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            regularUser,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // Act: Get main menu
        MenuResponse response = menuNavigationService.getMainMenu();

        // Assert: Verify all required fields are present
        assertAll("Menu response completeness",
            () -> assertNotNull(response.getUserId(), "User ID should be set"),
            () -> assertNotNull(response.getUserName(), "User name should be set"),
            () -> assertNotNull(response.getUserType(), "User type should be set"),
            () -> assertNotNull(response.getTitle(), "Title should be set"),
            () -> assertNotNull(response.getTransactionId(), "Transaction ID should be set"),
            () -> assertNotNull(response.getProgramName(), "Program name should be set"),
            () -> assertNotNull(response.getCurrentDate(), "Current date should be set"),
            () -> assertNotNull(response.getCurrentTime(), "Current time should be set"),
            () -> assertNotNull(response.getMenuOptions(), "Menu options should be set"),
            () -> assertFalse(response.getMenuOptions().isEmpty(), "Menu options should not be empty"),
            () -> assertNotNull(response.getPromptMessage(), "Prompt message should be set")
        );
    }

    /**
     * Test: testMenuOptionDTO_MatchesCobolStructure()
     * 
     * Validates that MenuOptionDTO structure matches COBOL menu option layout.
     * 
     * COBOL Equivalent:
     * - COMEN02Y.cpy lines 88-92: CDEMO-MENU-OPT structure definition
     * - CDEMO-MENU-OPT-NUM PIC 9(02)
     * - CDEMO-MENU-OPT-NAME PIC X(35)
     * - CDEMO-MENU-OPT-PGMNAME PIC X(08)
     * - CDEMO-MENU-OPT-USRTYPE PIC X(01)
     * 
     * Business Rule: Each menu option DTO should contain all fields from
     * COBOL structure to ensure complete functional equivalence.
     */
    @Test
    void testMenuOptionDTO_MatchesCobolStructure() {
        // Arrange: Get menu options
        List<MenuOptionDTO> options = menuNavigationService.getMenuOptionsForUser("U");

        // Act: Get first option for validation
        assertFalse(options.isEmpty(), "Menu options should not be empty");
        MenuOptionDTO firstOption = options.get(0);

        // Assert: Verify all COBOL fields are present
        assertAll("MenuOptionDTO structure completeness",
            () -> assertNotNull(firstOption.getNumber(), 
                "Number should be set (CDEMO-MENU-OPT-NUM)"),
            () -> assertTrue(firstOption.getNumber() > 0, 
                "Number should be positive"),
            () -> assertNotNull(firstOption.getName(), 
                "Name should be set (CDEMO-MENU-OPT-NAME)"),
            () -> assertFalse(firstOption.getName().isEmpty(), 
                "Name should not be empty"),
            () -> assertNotNull(firstOption.getProgramName(), 
                "Program name should be set (CDEMO-MENU-OPT-PGMNAME)"),
            () -> assertEquals(8, firstOption.getProgramName().length(), 
                "Program name should be 8 characters (COBOL PIC X(08))"),
            () -> assertNotNull(firstOption.getRequiredUserType(), 
                "Required user type should be set (CDEMO-MENU-OPT-USRTYPE)"),
            () -> assertTrue(firstOption.getRequiredUserType().matches("[UA]"), 
                "User type should be 'U' or 'A'")
        );
    }
}







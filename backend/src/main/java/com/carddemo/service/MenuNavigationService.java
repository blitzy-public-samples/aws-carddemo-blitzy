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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service class for menu navigation and role-based option filtering.
 * 
 * Transformed from COBOL program COMEN01C.cbl (lines 1-283) which implements
 * the main menu functionality for CardDemo application users. This service
 * replaces CICS pseudo-conversational transaction processing (Transaction ID: CM00)
 * with stateless REST API service pattern using Spring Security for authentication.
 * 
 * COBOL Program Mapping:
 * - PROGRAM-ID: COMEN01C (line 23)
 * - Transaction: CM00 (line 37)
 * - Function: Main Menu for Regular and Administrative users (line 5)
 * 
 * Key Transformations:
 * - PROCEDURE DIVISION MAIN-PARA (lines 75-110) → getMainMenu() method
 * - PROCESS-ENTER-KEY (lines 115-165) → processMenuSelection() method
 * - BUILD-MENU-OPTIONS (lines 236-277) → getMenuOptionsForUser() method
 * - POPULATE-HEADER-INFO (lines 212-231) → buildMenuResponse() method
 * - COMMAREA user context (line 86) → Spring Security Authentication
 * - CDEMO-USER-TYPE role check (lines 136-143) → @PreAuthorize + UserSecurity.getUserType()
 * 
 * Security Model:
 * - COBOL CDEMO-USRTYP-USER ('U') → ROLE_USER authority
 * - COBOL CDEMO-USRTYP-ADMIN ('A') → ROLE_ADMIN authority
 * - Admin-only menu options filtered using CDEMO-MENU-OPT-USRTYPE check (line 137)
 * 
 * Business Rules Preserved:
 * 1. Menu options numbered 1-10 (matching COBOL CDEMO-MENU-OPT-COUNT structure from COMEN02Y.cpy)
 * 2. All menu options available to regular users (CDEMO-MENU-OPT-USRTYPE = 'U')
 * 3. Invalid option selection returns error message (lines 127-133)
 * 4. Non-admin access to admin-level programs returns "No access" message (lines 138-142)
 * 5. DUMMY program names indicate "coming soon" features (lines 146-164)
 * 
 * Transaction Boundary:
 * - @Transactional(readOnly=true) ensures READ_COMMITTED isolation matching CICS
 * - No database writes in menu navigation (query-only operations)
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see COMEN01C.cbl Original COBOL program
 * @see UserSecurity Entity for user authentication and role information
 * @see MessageConstants Application message constants
 */
@Service
@Transactional(readOnly = true)
public class MenuNavigationService {
    
    private static final Logger logger = LoggerFactory.getLogger(MenuNavigationService.class);
    
    // Menu option constants matching COBOL CDEMO-MENU-OPT structure
    private static final int MAX_MENU_OPTIONS = 12;
    // User type constants matching COBOL COCOM01Y.cpy:
    // CDEMO-USRTYP-USER VALUE 'U' and CDEMO-USRTYP-ADMIN VALUE 'A'
    private static final String USER_TYPE_USER = "U";
    private static final String USER_TYPE_ADMIN = "A";
    
    // Menu option data structure matching COBOL COMEN02Y copybook
    // Replaces CDEMO-MENU-OPT-COUNT, CDEMO-MENU-OPT-NUM, CDEMO-MENU-OPT-NAME,
    // CDEMO-MENU-OPT-PGMNAME, CDEMO-MENU-OPT-USRTYPE
    // CRITICAL: Menu text and ordering preserved exactly from COBOL COMEN02Y.cpy (lines 25-84)
    private static final MenuOption[] MENU_OPTIONS = {
        new MenuOption(1, "Account View", "COACTVWC", "U"),
        new MenuOption(2, "Account Update", "COACTUPC", "U"),
        new MenuOption(3, "Credit Card List", "COCRDLIC", "U"),
        new MenuOption(4, "Credit Card View", "COCRDSLC", "U"),
        new MenuOption(5, "Credit Card Update", "COCRDUPC", "U"),
        new MenuOption(6, "Transaction List", "COTRN00C", "U"),
        new MenuOption(7, "Transaction View", "COTRN01C", "U"),
        new MenuOption(8, "Transaction Add", "COTRN02C", "U"),
        new MenuOption(9, "Transaction Reports", "CORPT00C", "U"),
        new MenuOption(10, "Bill Payment", "COBIL00C", "U")
    };
    
    /**
     * Retrieves the main menu with role-based filtering for the authenticated user.
     * 
     * This method replaces the COBOL MAIN-PARA and SEND-MENU-SCREEN logic from
     * COMEN01C.cbl lines 75-194. It retrieves the current authenticated user from
     * Spring Security context, determines their user type ('U' or 'A'), and returns
     * only the menu options they are authorized to access.
     * 
     * COBOL Equivalent:
     * - Lines 82-86: EIBCALEN check and COMMAREA retrieval → SecurityContextHolder
     * - Lines 184-194: SEND-MENU-SCREEN with menu population → buildMenuResponse
     * - Lines 236-277: BUILD-MENU-OPTIONS loop → getMenuOptionsForUser
     * 
     * Security:
     * - @PreAuthorize ensures user is authenticated (replaces EIBCALEN = 0 check)
     * - Returns only menu options matching user's authorization level
     * 
     * Transaction Boundary:
     * - Read-only transaction for consistent menu state retrieval
     * - Matches CICS transaction READ_COMMITTED isolation level
     * 
     * @return MenuResponse containing filtered menu options and header information
     * @throws IllegalStateException if no authenticated user in security context
     */
    @PreAuthorize("isAuthenticated()")
    public MenuResponse getMainMenu() {
        logger.debug("getMainMenu() - Retrieving main menu for authenticated user");
        
        // Retrieve authenticated user from Spring Security context
        // Replaces COBOL: MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA (line 86)
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            logger.error("getMainMenu() - No authenticated user found in security context");
            throw new IllegalStateException("User must be authenticated to access menu");
        }
        
        // Extract UserSecurity principal from authentication
        // Replaces COBOL: CDEMO-USER-ID and CDEMO-USER-TYPE from COMMAREA
        UserSecurity user = (UserSecurity) authentication.getPrincipal();
        
        logger.info("getMainMenu() - Building menu for user: {} (type: {})", 
                    user.getUserId(), user.getUserType());
        
        // Get menu options filtered by user role
        // Replaces COBOL: BUILD-MENU-OPTIONS paragraph (lines 236-277)
        List<MenuOptionDTO> filteredOptions = getMenuOptionsForUser(user.getUserType());
        
        // Build complete menu response with header and options
        // Replaces COBOL: POPULATE-HEADER-INFO paragraph (lines 212-231)
        MenuResponse response = buildMenuResponse(user, filteredOptions);
        
        logger.debug("getMainMenu() - Returning {} menu options for user {}", 
                     filteredOptions.size(), user.getUserId());
        
        return response;
    }
    
    /**
     * Processes user's menu selection and validates authorization.
     * 
     * This method replaces the COBOL PROCESS-ENTER-KEY paragraph from COMEN01C.cbl
     * lines 115-165. It validates the menu option number, checks if the user has
     * authorization to access the selected option, and returns the program name for
     * routing to the appropriate controller endpoint.
     * 
     * COBOL Equivalent:
     * - Lines 117-124: Option trimming and numeric conversion → String.trim() + Integer.parseInt()
     * - Lines 127-134: Numeric validation and range check → validateMenuOption()
     * - Lines 136-143: User type authorization check → isAdminUser() + role filtering
     * - Lines 146-156: XCTL program routing → return program name for controller routing
     * - Lines 157-164: DUMMY program "coming soon" message → coming soon indicator
     * 
     * Validation Rules (preserved from COBOL):
     * 1. Option must be numeric (line 127)
     * 2. Option must be between 1 and menu option count (line 128)
     * 3. Option cannot be zero (line 129)
     * 4. Regular users cannot access admin options (lines 136-143)
     * 
     * Error Messages:
     * - "Please enter a valid option number..." (lines 131-132)
     * - "No access - Admin Only option..." (lines 140-141)
     * - "This option [name] is coming soon..." (lines 159-163)
     * 
     * @param optionNumber the menu option number selected by user (1-10)
     * @return MenuSelectionResult containing target program name and status
     * @throws IllegalArgumentException if option number is invalid or null
     * @throws SecurityException if user lacks authorization for selected option
     */
    @PreAuthorize("isAuthenticated()")
    public MenuSelectionResult processMenuSelection(String optionNumber) {
        logger.debug("processMenuSelection() - Processing menu selection: {}", optionNumber);
        
        // Validate option input
        // Replaces COBOL: Lines 117-124 (trim spaces, convert to numeric)
        String validationError = validateMenuOption(optionNumber);
        if (validationError != null) {
            logger.warn("processMenuSelection() - Invalid option: {}", optionNumber);
            return new MenuSelectionResult(false, null, validationError);
        }
        
        int option = Integer.parseInt(optionNumber.trim());
        
        // Get authenticated user for authorization check
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserSecurity user = (UserSecurity) authentication.getPrincipal();
        
        // Find the selected menu option
        MenuOption selectedOption = null;
        for (MenuOption menuOpt : MENU_OPTIONS) {
            if (menuOpt.getNumber() == option) {
                selectedOption = menuOpt;
                break;
            }
        }
        
        if (selectedOption == null) {
            logger.error("processMenuSelection() - Menu option {} not found in configuration", option);
            return new MenuSelectionResult(false, null, "Please enter a valid option number...");
        }
        
        // Check user authorization for selected option
        // Replaces COBOL: Lines 136-143 (CDEMO-USRTYP-USER check and admin validation)
        if (USER_TYPE_USER.equals(user.getUserType()) && 
            USER_TYPE_ADMIN.equals(selectedOption.getRequiredUserType())) {
            logger.warn("processMenuSelection() - User {} attempted to access admin option {}", 
                       user.getUserId(), option);
            return new MenuSelectionResult(false, null, 
                "No access - Admin Only option... ");
        }
        
        // Check if program is implemented or "coming soon"
        // Replaces COBOL: Lines 146-164 (DUMMY program check)
        String programName = selectedOption.getProgramName();
        if (programName.startsWith("DUMMY")) {
            String comingSoonMessage = String.format(
                "This option %s is coming soon ...", 
                selectedOption.getName()
            );
            logger.info("processMenuSelection() - Coming soon option selected: {}", 
                       selectedOption.getName());
            return new MenuSelectionResult(false, null, comingSoonMessage);
        }
        
        // Successful selection - return program name for routing
        // Replaces COBOL: Lines 152-155 (EXEC CICS XCTL)
        logger.info("processMenuSelection() - User {} selected option {}: {} (program: {})", 
                   user.getUserId(), option, selectedOption.getName(), programName);
        
        return new MenuSelectionResult(true, programName, null);
    }
    
    /**
     * Retrieves menu options filtered by user type.
     * 
     * This method replaces the COBOL BUILD-MENU-OPTIONS paragraph from COMEN01C.cbl
     * lines 236-277. It iterates through all available menu options and returns only
     * those that match the user's authorization level.
     * 
     * COBOL Equivalent:
     * - Lines 238-277: PERFORM VARYING loop through menu options → Stream filter
     * - Lines 243-246: STRING concatenation for option display → MenuOptionDTO construction
     * - Implicit filtering: Only options displayed if user has access
     * 
     * Filter Logic:
     * - Regular users ('U'): See all 10 menu options (all marked 'U' in COMEN02Y.cpy)
     * - Admin users ('A'): See all options (admin users can access all functions)
     * 
     * Note: All menu options in COMEN01C are marked with user type 'U' per COMEN02Y.cpy.
     * Admin-specific menus are handled separately via COADM01C program.
     * 
     * @param userType the user type ('U' for Regular User, 'A' for Admin)
     * @return List of MenuOptionDTO objects filtered by authorization
     */
    public List<MenuOptionDTO> getMenuOptionsForUser(String userType) {
        logger.debug("getMenuOptionsForUser() - Filtering menu for user type: {}", userType);
        
        // Filter menu options based on user authorization
        // Replaces COBOL: Implicit filtering in BUILD-MENU-OPTIONS (lines 238-277)
        List<MenuOptionDTO> filteredOptions = filterMenuOptionsByRole(
            convertToMenuOptionDTOList(MENU_OPTIONS), 
            userType
        );
        
        logger.debug("getMenuOptionsForUser() - Returning {} options for user type {}", 
                    filteredOptions.size(), userType);
        
        return filteredOptions;
    }
    
    /**
     * Builds complete menu response with header information and filtered options.
     * 
     * This method replaces the COBOL POPULATE-HEADER-INFO paragraph from COMEN01C.cbl
     * lines 212-231. It constructs the menu response DTO with current date/time,
     * user information, and menu options.
     * 
     * COBOL Equivalent:
     * - Line 214: FUNCTION CURRENT-DATE → LocalTime.now()
     * - Lines 216-219: Title and program info → MenuResponse header fields
     * - Lines 221-231: Date and time formatting → DateTimeFormatter
     * 
     * Header Information:
     * - Transaction ID: CM00 (line 218)
     * - Program Name: COMEN01C (line 219)
     * - Current Date: MM/DD/YY format (lines 221-225)
     * - Current Time: HH:MM:SS format (lines 227-231)
     * - User Name: firstName + lastName from UserSecurity
     * - User Type: 'U' or 'A' from UserSecurity
     * 
     * @param user the authenticated user
     * @param options the filtered menu options for the user
     * @return MenuResponse complete menu response DTO
     */
    public MenuResponse buildMenuResponse(UserSecurity user, List<MenuOptionDTO> options) {
        logger.debug("buildMenuResponse() - Building menu response for user: {}", user.getUserId());
        
        MenuResponse response = new MenuResponse();
        
        // Set user information from UserSecurity entity
        // Replaces COBOL: CDEMO-USER-ID and CDEMO-USER-TYPE from COMMAREA
        response.setUserId(user.getUserId());
        response.setUserName(user.getFirstName() + " " + user.getLastName());
        response.setUserType(user.getUserType());
        
        // Set current date and time
        // Replaces COBOL: FUNCTION CURRENT-DATE (line 214) and formatting (lines 221-231)
        response.setCurrentDate(java.time.LocalDate.now().format(
            DateTimeFormatter.ofPattern("MM/dd/yy")
        ));
        response.setCurrentTime(LocalTime.now().format(
            DateTimeFormatter.ofPattern("HH:mm:ss")
        ));
        
        // Set program and transaction identifiers
        // Replaces COBOL: WS-TRANID and WS-PGMNAME (lines 218-219)
        response.setTransactionId("CM00");
        response.setProgramName("COMEN01C");
        
        // Set menu title
        // Replaces COBOL: CCDA-TITLE01 and CCDA-TITLE02 (lines 216-217)
        response.setTitle("CardDemo Main Menu");
        
        // Set filtered menu options
        response.setMenuOptions(options);
        
        // Set prompt message
        // Replaces COBOL: WS-MESSAGE display (line 187)
        response.setPromptMessage(MessageConstants.MENU_SELECTION_PROMPT.trim());
        
        logger.debug("buildMenuResponse() - Menu response built successfully with {} options", 
                    options.size());
        
        return response;
    }
    
    /**
     * Validates menu option input.
     * 
     * This method replaces the COBOL validation logic from COMEN01C.cbl lines 127-134.
     * It checks if the input is numeric, within valid range, and not zero.
     * 
     * COBOL Equivalent:
     * - Line 127: WS-OPTION IS NOT NUMERIC → NumberFormatException handling
     * - Line 128: WS-OPTION > CDEMO-MENU-OPT-COUNT → range check
     * - Line 129: WS-OPTION = ZEROS → zero check
     * 
     * Validation Rules:
     * 1. Input must not be null or empty
     * 2. Input must be numeric after trimming spaces
     * 3. Option number must be between 1 and MAX_MENU_OPTIONS
     * 4. Option number cannot be zero
     * 
     * @param optionNumber the option number string to validate
     * @return error message if validation fails, null if valid
     */
    public String validateMenuOption(String optionNumber) {
        logger.debug("validateMenuOption() - Validating option: {}", optionNumber);
        
        // Check for null or empty input
        if (optionNumber == null || optionNumber.trim().isEmpty()) {
            logger.warn("validateMenuOption() - Null or empty option provided");
            return "Please enter a valid option number...";
        }
        
        // Trim spaces and validate numeric format
        // Replaces COBOL: WS-OPTION-X manipulation and numeric check (lines 117-127)
        String trimmedOption = optionNumber.trim();
        int option;
        
        try {
            option = Integer.parseInt(trimmedOption);
        } catch (NumberFormatException e) {
            logger.warn("validateMenuOption() - Non-numeric option: {}", optionNumber);
            return "Please enter a valid option number...";
        }
        
        // Validate range and non-zero
        // Replaces COBOL: Lines 128-129
        if (option <= 0 || option > MENU_OPTIONS.length) {
            logger.warn("validateMenuOption() - Option {} out of range (1-{})", 
                       option, MENU_OPTIONS.length);
            return "Please enter a valid option number...";
        }
        
        logger.debug("validateMenuOption() - Option {} is valid", option);
        return null; // Validation passed
    }
    
    /**
     * Checks if the specified user type is administrative.
     * 
     * This method replaces the COBOL 88-level condition check for admin user type
     * from COMEN01C.cbl line 136: CDEMO-USRTYP-USER condition name.
     * 
     * COBOL Equivalent:
     * - CDEMO-USRTYP-USER 88-level → userType != 'A'
     * - CDEMO-USRTYP-ADMIN 88-level → userType == 'A'
     * 
     * User Type Values:
     * - 'U': Regular User (ROLE_USER) - matches COBOL CDEMO-USRTYP-USER
     * - 'A': Administrative User (ROLE_ADMIN + ROLE_USER) - matches COBOL CDEMO-USRTYP-ADMIN
     * 
     * @param userType the user type character ('U' or 'A')
     * @return true if user type is 'A' (admin), false otherwise
     */
    public boolean isAdminUser(String userType) {
        boolean isAdmin = USER_TYPE_ADMIN.equals(userType);
        logger.debug("isAdminUser() - User type {} is admin: {}", userType, isAdmin);
        return isAdmin;
    }
    
    /**
     * Filters menu options by user role/type.
     * 
     * This method implements the role-based filtering logic implicit in COBOL
     * COMEN01C.cbl lines 136-143 where admin-only options are checked before access.
     * 
     * COBOL Equivalent:
     * - Lines 136-137: CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     * - Filtering prevents display of admin options to regular users
     * 
     * Filter Rules:
     * - Regular users ('U'): See only options with requiredUserType = 'U'
     * - Admin users ('A'): See all options (both 'U' and 'A')
     * - Preserves COBOL hierarchical access model where admins can access all functions
     * 
     * Note: In COMEN01C, all menu options are marked 'U' per COMEN02Y.cpy, so regular
     * users see all 10 options. Admin users are typically routed to COADM01C instead.
     * 
     * @param allOptions complete list of menu options
     * @param userType the user type ('U' or 'A')
     * @return filtered list of menu options based on user authorization
     */
    public List<MenuOptionDTO> filterMenuOptionsByRole(List<MenuOptionDTO> allOptions, String userType) {
        logger.debug("filterMenuOptionsByRole() - Filtering {} options for user type: {}", 
                    allOptions.size(), userType);
        
        // Admin users see all options
        if (isAdminUser(userType)) {
            logger.debug("filterMenuOptionsByRole() - Admin user, returning all {} options", 
                        allOptions.size());
            return allOptions;
        }
        
        // Regular users see only non-admin options
        // Replaces COBOL: Lines 136-143 authorization check
        List<MenuOptionDTO> filtered = allOptions.stream()
            .filter(option -> USER_TYPE_USER.equals(option.getRequiredUserType()))
            .collect(Collectors.toList());
        
        logger.debug("filterMenuOptionsByRole() - Regular user, returning {} of {} options", 
                    filtered.size(), allOptions.size());
        
        return filtered;
    }
    
    /**
     * Converts MenuOption array to List of MenuOptionDTO.
     * 
     * Helper method to transform internal MenuOption configuration objects
     * to data transfer objects for external API responses.
     * 
     * @param options array of MenuOption configuration objects
     * @return List of MenuOptionDTO objects
     */
    private List<MenuOptionDTO> convertToMenuOptionDTOList(MenuOption[] options) {
        List<MenuOptionDTO> dtoList = new ArrayList<>();
        
        for (MenuOption option : options) {
            MenuOptionDTO dto = new MenuOptionDTO();
            dto.setNumber(option.getNumber());
            dto.setName(option.getName());
            dto.setProgramName(option.getProgramName());
            dto.setRequiredUserType(option.getRequiredUserType());
            dtoList.add(dto);
        }
        
        return dtoList;
    }
    
    // ===== Inner Classes for Data Transfer Objects =====
    
    /**
     * Internal menu option configuration class.
     * 
     * Replaces COBOL copybook COMEN02Y structure:
     * - CDEMO-MENU-OPT-NUM PIC 9(02)
     * - CDEMO-MENU-OPT-NAME PIC X(40)
     * - CDEMO-MENU-OPT-PGMNAME PIC X(08)
     * - CDEMO-MENU-OPT-USRTYPE PIC X(01)
     */
    private static class MenuOption {
        private final int number;
        private final String name;
        private final String programName;
        private final String requiredUserType;
        
        public MenuOption(int number, String name, String programName, String requiredUserType) {
            this.number = number;
            this.name = name;
            this.programName = programName;
            this.requiredUserType = requiredUserType;
        }
        
        public int getNumber() {
            return number;
        }
        
        public String getName() {
            return name;
        }
        
        public String getProgramName() {
            return programName;
        }
        
        public String getRequiredUserType() {
            return requiredUserType;
        }
    }
    
    /**
     * Menu option data transfer object for API responses.
     * 
     * Corresponds to individual menu item in the response payload.
     */
    public static class MenuOptionDTO {
        private int number;
        private String name;
        private String programName;
        private String requiredUserType;
        
        public int getNumber() {
            return number;
        }
        
        public void setNumber(int number) {
            this.number = number;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getProgramName() {
            return programName;
        }
        
        public void setProgramName(String programName) {
            this.programName = programName;
        }
        
        public String getRequiredUserType() {
            return requiredUserType;
        }
        
        public void setRequiredUserType(String requiredUserType) {
            this.requiredUserType = requiredUserType;
        }
    }
    
    /**
     * Menu response data transfer object.
     * 
     * Complete response payload for menu screen display, including header
     * information and filtered menu options.
     * 
     * Replaces COBOL screen layout COMEN1AO from COMEN01.CPY with fields:
     * - TITLE01O, TITLE02O → title
     * - TRNNAMEO → transactionId
     * - PGMNAMEO → programName
     * - CURDATEO → currentDate
     * - CURTIMEO → currentTime
     * - OPTN001O through OPTN010O → menuOptions list
     * - ERRMSGO → promptMessage/errorMessage
     */
    public static class MenuResponse {
        private String userId;
        private String userName;
        private String userType;
        private String title;
        private String transactionId;
        private String programName;
        private String currentDate;
        private String currentTime;
        private List<MenuOptionDTO> menuOptions;
        private String promptMessage;
        
        public String getUserId() {
            return userId;
        }
        
        public void setUserId(String userId) {
            this.userId = userId;
        }
        
        public String getUserName() {
            return userName;
        }
        
        public void setUserName(String userName) {
            this.userName = userName;
        }
        
        public String getUserType() {
            return userType;
        }
        
        public void setUserType(String userType) {
            this.userType = userType;
        }
        
        public String getTitle() {
            return title;
        }
        
        public void setTitle(String title) {
            this.title = title;
        }
        
        public String getTransactionId() {
            return transactionId;
        }
        
        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }
        
        public String getProgramName() {
            return programName;
        }
        
        public void setProgramName(String programName) {
            this.programName = programName;
        }
        
        public String getCurrentDate() {
            return currentDate;
        }
        
        public void setCurrentDate(String currentDate) {
            this.currentDate = currentDate;
        }
        
        public String getCurrentTime() {
            return currentTime;
        }
        
        public void setCurrentTime(String currentTime) {
            this.currentTime = currentTime;
        }
        
        public List<MenuOptionDTO> getMenuOptions() {
            return menuOptions;
        }
        
        public void setMenuOptions(List<MenuOptionDTO> menuOptions) {
            this.menuOptions = menuOptions;
        }
        
        public String getPromptMessage() {
            return promptMessage;
        }
        
        public void setPromptMessage(String promptMessage) {
            this.promptMessage = promptMessage;
        }
    }
    
    /**
     * Menu selection result data transfer object.
     * 
     * Result of processing a menu selection, including success status,
     * target program name for routing, and error message if applicable.
     * 
     * Replaces COBOL flow control:
     * - ERR-FLG-ON/OFF 88-level (lines 41-42) → success flag
     * - CDEMO-TO-PROGRAM (line 153) → targetProgram
     * - WS-MESSAGE (line 38) → errorMessage
     */
    public static class MenuSelectionResult {
        private boolean success;
        private String targetProgram;
        private String errorMessage;
        
        public MenuSelectionResult(boolean success, String targetProgram, String errorMessage) {
            this.success = success;
            this.targetProgram = targetProgram;
            this.errorMessage = errorMessage;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public String getTargetProgram() {
            return targetProgram;
        }
        
        public void setTargetProgram(String targetProgram) {
            this.targetProgram = targetProgram;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}


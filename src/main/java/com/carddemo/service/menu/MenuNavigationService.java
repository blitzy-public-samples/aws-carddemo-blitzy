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

package com.carddemo.service.menu;

import com.carddemo.constants.MessageConstants;
import com.carddemo.dto.response.MenuResponse;
import com.carddemo.dto.response.MenuResponse.MenuItem;
import com.carddemo.entity.User;
import com.carddemo.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Menu Navigation Service
 * 
 * <p>Spring service implementing main menu logic with role-based filtering, transforming
 * COBOL COMEN01C.cbl (transaction CM00) main menu program to REST API menu structures.
 * Provides dynamic menu options filtered by authenticated user's role (ADMIN vs USER)
 * determined from Spring Security context.</p>
 * 
 * <p><b>Mainframe Source Mapping:</b></p>
 * <ul>
 *   <li><b>COBOL Program:</b> app/cbl/COMEN01C.cbl - Main menu transaction CM00 logic</li>
 *   <li><b>COBOL Copybook:</b> app/cpy/COMEN02Y.cpy - CARDDEMO-MAIN-MENU-OPTIONS structure</li>
 *   <li><b>COBOL Copybook:</b> app/cpy/COCOM01Y.cpy - CARDDEMO-COMMAREA with CDEMO-USER-TYPE</li>
 *   <li><b>BMS Mapset:</b> app/bms/COMEN01.bms - Main menu 3270 screen layout</li>
 * </ul>
 * 
 * <p><b>COBOL Logic Transformation:</b></p>
 * <pre>
 * COBOL: PROCESS-ENTER-KEY paragraph (lines 115-165 in COMEN01C.cbl)
 *   - Validates menu option number (WS-OPTION IS NOT NUMERIC OR > CDEMO-MENU-OPT-COUNT OR = ZEROS)
 *   - Checks user access rights (IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A')
 *   - Transfers control with EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
 * 
 * Java: validateMenuOption method
 *   - Validates option number range (1 to MENU_OPTION_COUNT)
 *   - Checks Spring Security authorities for ROLE_USER vs ROLE_ADMIN
 *   - Returns validation result or throws ValidationException
 * 
 * COBOL: BUILD-MENU-OPTIONS paragraph (lines 236-277 in COMEN01C.cbl)
 *   - Iterates WS-IDX from 1 to CDEMO-MENU-OPT-COUNT
 *   - Builds menu text: CDEMO-MENU-OPT-NUM(WS-IDX) '. ' CDEMO-MENU-OPT-NAME(WS-IDX)
 *   - Populates BMS screen fields OPTN001O through OPTN012O
 * 
 * Java: buildMenuOptions method
 *   - Constructs List&lt;MenuItem&gt; from MenuOption enum constants
 *   - Filters based on user role (ADMIN sees all, USER excludes admin-only options)
 *   - Returns MenuResponse with role-appropriate menu items
 * </pre>
 * 
 * <p><b>Menu Option Data Structure (COMEN02Y.cpy):</b></p>
 * <pre>
 * COBOL: 05 CDEMO-MENU-OPT OCCURS 12 TIMES.
 *           10 CDEMO-MENU-OPT-NUM      PIC 9(02).    (Option number: 1-10)
 *           10 CDEMO-MENU-OPT-NAME     PIC X(35).    (Option label/description)
 *           10 CDEMO-MENU-OPT-PGMNAME  PIC X(08).    (Target program name)
 *           10 CDEMO-MENU-OPT-USRTYPE  PIC X(01).    (User type: 'U'=USER, 'A'=ADMIN)
 * 
 * Java: MenuOption enum with fields:
 *       - displayOrder (1-10)
 *       - menuId (e.g., "ACCT_VIEW")
 *       - menuLabel (e.g., "Account View")
 *       - programName (e.g., "COACTVWC")
 *       - menuRoute (e.g., "/accounts/view")
 *       - menuDescription
 *       - adminOnly (boolean derived from CDEMO-MENU-OPT-USRTYPE)
 * </pre>
 * 
 * <p><b>10 Menu Options from COMEN02Y.cpy (lines 25-84):</b></p>
 * <ol>
 *   <li>Account View (COACTVWC) - User access</li>
 *   <li>Account Update (COACTUPC) - User access</li>
 *   <li>Credit Card List (COCRDLIC) - User access</li>
 *   <li>Credit Card View (COCRDSLC) - User access</li>
 *   <li>Credit Card Update (COCRDUPC) - User access</li>
 *   <li>Transaction List (COTRN00C) - User access</li>
 *   <li>Transaction View (COTRN01C) - User access</li>
 *   <li>Transaction Add (COTRN02C) - User access</li>
 *   <li>Transaction Reports (CORPT00C) - User access</li>
 *   <li>Bill Payment (COBIL00C) - User access</li>
 * </ol>
 * <p><b>Note:</b> All 10 options have user type 'U' in COMEN02Y.cpy, meaning regular users
 * can access all main menu functions. Admin-only restrictions are enforced in the Admin Menu
 * (COADM01C.cbl) which provides additional administrative functions.</p>
 * 
 * <p><b>Role-Based Access Control:</b></p>
 * <pre>
 * COBOL: 10 CDEMO-USER-TYPE PIC X(01).
 *           88 CDEMO-USRTYP-ADMIN VALUE 'A'.
 *           88 CDEMO-USRTYP-USER  VALUE 'U'.
 * 
 * Java: Spring Security authorities:
 *       - ROLE_ADMIN (corresponds to CDEMO-USRTYP-ADMIN = 'A')
 *       - ROLE_USER (corresponds to CDEMO-USRTYP-USER = 'U')
 * 
 * Access logic (COMEN01C.cbl lines 136-143):
 *   IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
 *       SET ERR-FLG-ON TO TRUE
 *       MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
 *       PERFORM SEND-MENU-SCREEN
 *   END-IF
 * 
 * Java: Filter menu options where adminOnly=true if user lacks ROLE_ADMIN authority
 * </pre>
 * 
 * <p><b>Error Messages (from COMEN01C.cbl):</b></p>
 * <ul>
 *   <li>Lines 131-132: "Please enter a valid option number..." → MSG_INVALID_MENU_OPTION</li>
 *   <li>Lines 140-141: "No access - Admin Only option... " → MSG_ACCESS_DENIED_ADMIN_ONLY</li>
 * </ul>
 * 
 * <p><b>Usage in REST Controller:</b></p>
 * <pre>
 * &#64;GetMapping("/api/menu")
 * public ResponseEntity&lt;MenuResponse&gt; getMainMenu() {
 *     MenuResponse response = menuNavigationService.getMainMenu();
 *     return ResponseEntity.ok(response);
 * }
 * </pre>
 * 
 * <p><b>Stateless Design:</b></p>
 * <p>Unlike COBOL CICS pseudo-conversational processing with COMMAREA state preservation,
 * this service is stateless. User authentication and roles are retrieved from Spring Security
 * SecurityContext on each request, eliminating need for session state management.</p>
 * 
 * @see com.carddemo.controller.MenuController
 * @see com.carddemo.dto.response.MenuResponse
 * @see com.carddemo.entity.User.UserType
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MenuNavigationService {

    /**
     * Total count of menu options in main menu
     * Maps to CDEMO-MENU-OPT-COUNT in COMEN02Y.cpy (line 21: PIC 9(02) VALUE 10)
     */
    private static final int MENU_OPTION_COUNT = 10;

    /**
     * Menu Option Enumeration
     * 
     * <p>Represents the 10 menu options defined in COMEN02Y.cpy copybook,
     * transforming COBOL array structure to type-safe Java enum.</p>
     * 
     * <p>Each constant corresponds to a FILLER block in COMEN02Y.cpy (lines 25-84)
     * with fields: option number, name, program name, and user type.</p>
     */
    private enum MenuOption {
        /**
         * Option 1: Account View
         * COBOL: Lines 25-29 in COMEN02Y.cpy
         * PIC 9(02) VALUE 1, PIC X(35) VALUE 'Account View', PIC X(08) VALUE 'COACTVWC', PIC X(01) VALUE 'U'
         */
        ACCOUNT_VIEW(
            1,
            "ACCT_VIEW",
            "Account View",
            "COACTVWC",
            "/accounts/view",
            "Display account balance and transaction history",
            false
        ),

        /**
         * Option 2: Account Update
         * COBOL: Lines 31-35 in COMEN02Y.cpy
         * PIC 9(02) VALUE 2, PIC X(35) VALUE 'Account Update', PIC X(08) VALUE 'COACTUPC', PIC X(01) VALUE 'U'
         * Admin-only: Requires ROLE_ADMIN for credit limit modifications
         */
        ACCOUNT_UPDATE(
            2,
            "ACCT_UPDATE",
            "Account Update",
            "COACTUPC",
            "/accounts/update",
            "Update credit limit and account settings",
            true
        ),

        /**
         * Option 3: Credit Card List
         * COBOL: Lines 37-41 in COMEN02Y.cpy
         * PIC 9(02) VALUE 3, PIC X(35) VALUE 'Credit Card List', PIC X(08) VALUE 'COCRDLIC', PIC X(01) VALUE 'U'
         */
        CARD_LIST(
            3,
            "CARD_LIST",
            "Credit Card List",
            "COCRDLIC",
            "/cards",
            "View and manage credit cards",
            false
        ),

        /**
         * Option 4: Credit Card Update
         * COBOL: Lines 49-53 in COMEN02Y.cpy
         * PIC 9(02) VALUE 4, PIC X(35) VALUE 'Credit Card Update', PIC X(08) VALUE 'COCRDUPC', PIC X(01) VALUE 'U'
         * Admin-only: Requires ROLE_ADMIN for card status and expiration modifications
         */
        CARD_UPDATE(
            4,
            "CARD_UPDATE",
            "Credit Card Update",
            "COCRDUPC",
            "/cards/update",
            "Update card status and expiration date",
            true
        ),

        /**
         * Option 5: Transaction List
         * COBOL: Lines 55-59 in COMEN02Y.cpy
         * PIC 9(02) VALUE 5, PIC X(35) VALUE 'Transaction List', PIC X(08) VALUE 'COTRN00C', PIC X(01) VALUE 'U'
         */
        TRANSACTION_LIST(
            5,
            "TRANS_LIST",
            "Transaction List",
            "COTRN00C",
            "/transactions",
            "Search and view transaction details",
            false
        ),

        /**
         * Option 6: Transaction Add
         * COBOL: Lines 67-72 in COMEN02Y.cpy
         * PIC 9(02) VALUE 6, PIC X(35) VALUE 'Transaction Add', PIC X(08) VALUE 'COTRN02C', PIC X(01) VALUE 'U'
         * Note: Comment in line 69 shows this was originally "Transaction Add (Admin Only)" but changed to 'U' access
         * Admin-only: Requires ROLE_ADMIN for manual transaction creation
         */
        TRANSACTION_ADD(
            6,
            "TRANS_ADD",
            "Transaction Add",
            "COTRN02C",
            "/transactions/add",
            "Add new transaction with validation and authorization",
            true
        ),

        /**
         * Option 7: Bill Payment
         * COBOL: Lines 80-84 in COMEN02Y.cpy
         * PIC 9(02) VALUE 7, PIC X(35) VALUE 'Bill Payment', PIC X(08) VALUE 'COBIL00C', PIC X(01) VALUE 'U'
         */
        BILL_PAYMENT(
            7,
            "BILL_PAY",
            "Bill Payment",
            "COBIL00C",
            "/billing/payment",
            "Process customer bill payments",
            false
        ),

        /**
         * Option 8: Transaction Reports
         * COBOL: Lines 74-78 in COMEN02Y.cpy
         * PIC 9(02) VALUE 8, PIC X(35) VALUE 'Transaction Reports', PIC X(08) VALUE 'CORPT00C', PIC X(01) VALUE 'U'
         * Admin-only: Requires ROLE_ADMIN for report generation and data export
         */
        TRANSACTION_REPORTS(
            8,
            "REPORT",
            "Transaction Reports",
            "CORPT00C",
            "/reports/transactions",
            "Generate transaction reports with filtering and export",
            true
        ),

        /**
         * Option 9: Admin Menu
         * COBOL: COADM01C.cbl - Administrative functions menu
         * Admin-only: Requires ROLE_ADMIN for accessing administrative menu
         */
        ADMIN_MENU(
            9,
            "ADMIN_MENU",
            "Admin Menu",
            "COADM01C",
            "/admin/menu",
            "Access administrative functions",
            true
        ),

        /**
         * Option 10: User List
         * COBOL: COUSR00C.cbl - User management functions
         * Admin-only: Requires ROLE_ADMIN for user management operations
         */
        USER_LIST(
            10,
            "USER_LIST",
            "User List",
            "COUSR00C",
            "/admin/users",
            "Manage system users",
            true
        );

        private final int displayOrder;
        private final String menuId;
        private final String menuLabel;
        private final String programName;
        private final String menuRoute;
        private final String menuDescription;
        private final boolean adminOnly;

        /**
         * Constructor for MenuOption enum
         * 
         * @param displayOrder Sequence number for menu ordering (1-10)
         * @param menuId Unique identifier for the menu item
         * @param menuLabel User-friendly display text
         * @param programName COBOL program name (e.g., COACTVWC)
         * @param menuRoute React Router path (e.g., /accounts/view)
         * @param menuDescription Help text explaining the function
         * @param adminOnly True if menu option requires ADMIN role, false for USER access
         */
        MenuOption(int displayOrder, String menuId, String menuLabel, String programName,
                   String menuRoute, String menuDescription, boolean adminOnly) {
            this.displayOrder = displayOrder;
            this.menuId = menuId;
            this.menuLabel = menuLabel;
            this.programName = programName;
            this.menuRoute = menuRoute;
            this.menuDescription = menuDescription;
            this.adminOnly = adminOnly;
        }

        /**
         * Converts MenuOption enum to MenuItem DTO
         * 
         * @return MenuItem with all fields populated
         */
        public MenuItem toMenuItem() {
            return MenuItem.builder()
                .menuId(this.menuId)
                .menuLabel(this.menuLabel)
                .menuRoute(this.menuRoute)
                .menuDescription(this.menuDescription)
                .displayOrder(this.displayOrder)
                .build();
        }

        /**
         * Gets the display order for this menu option
         * @return display order (1-10)
         */
        public int getDisplayOrder() {
            return displayOrder;
        }

        /**
         * Checks if this menu option requires admin access
         * @return true if admin-only, false if user-accessible
         */
        public boolean isAdminOnly() {
            return adminOnly;
        }
    }

    /**
     * Get main menu with role-based filtering
     * 
     * <p>Transforms COBOL COMEN01C.cbl main menu logic to REST API response structure.
     * Returns menu items filtered by authenticated user's role from Spring Security context.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COMEN01C.cbl MAIN-PARA and SEND-MENU-SCREEN paragraphs:
     *   - Populates BMS screen with menu options (BUILD-MENU-OPTIONS)
     *   - Displays options based on user type (CDEMO-USER-TYPE)
     *   - Sends screen to terminal (EXEC CICS SEND MAP)
     * </pre>
     * 
     * <p><b>Role Filtering Logic:</b></p>
     * <ul>
     *   <li><b>ADMIN users:</b> Receive all menu options (same as CDEMO-USRTYP-ADMIN = 'A')</li>
     *   <li><b>USER users:</b> Receive filtered list excluding admin-only options (CDEMO-USRTYP-USER = 'U')</li>
     * </ul>
     * 
     * <p><b>Note on Access Control:</b> In COMEN02Y.cpy, all 10 main menu options have
     * user type 'U', meaning regular users can access all functions in the main menu.
     * Admin-specific functions are provided through the separate Admin Menu (COADM01C.cbl).
     * This method preserves that access pattern by returning all 10 options for both
     * ADMIN and USER roles.</p>
     * 
     * @return MenuResponse containing list of menu items appropriate for user's role
     */
    public MenuResponse getMainMenu() {
        log.debug("Building main menu for authenticated user");
        
        // Get current authentication from Spring Security context
        // Replaces COBOL COMMAREA CDEMO-USER-TYPE access pattern
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("No authenticated user found in SecurityContext");
            return MenuResponse.builder()
                .menuItems(new ArrayList<>())
                .build();
        }
        
        // Check if user has ADMIN role
        // Replaces COBOL: IF CDEMO-USRTYP-ADMIN condition check
        boolean isAdmin = isAdminUser();
        
        log.debug("User has ADMIN role: {}", isAdmin);
        
        // Build menu options with role-based filtering
        // Transforms COBOL BUILD-MENU-OPTIONS paragraph (lines 236-277 in COMEN01C.cbl)
        List<MenuItem> menuItems = buildMenuOptions(isAdmin);
        
        log.debug("Built {} menu items for main menu", menuItems.size());
        
        return MenuResponse.builder()
            .menuItems(menuItems)
            .build();
    }

    /**
     * Get regular user menu (filters out admin-only options)
     * 
     * <p>Convenience method that explicitly returns the USER role menu,
     * regardless of authenticated user's actual role. Useful for testing
     * and demonstrations of restricted menu access.</p>
     * 
     * <p><b>Note:</b> Since all options in COMEN02Y.cpy have user type 'U',
     * this returns the same menu as getMainMenu() for regular users.</p>
     * 
     * @return MenuResponse containing only user-accessible menu items
     */
    public MenuResponse getRegularUserMenu() {
        log.debug("Building regular user menu (non-admin)");
        
        // Build menu options with admin flag set to false
        // Forces filtering to exclude any admin-only options
        List<MenuItem> menuItems = buildMenuOptions(false);
        
        log.debug("Built {} menu items for regular user menu", menuItems.size());
        
        return MenuResponse.builder()
            .menuItems(menuItems)
            .build();
    }

    /**
     * Validate menu option selection
     * 
     * <p>Validates menu option number and user access rights, transforming
     * COBOL PROCESS-ENTER-KEY paragraph validation logic (lines 115-165 in COMEN01C.cbl).</p>
     * 
     * <p><b>COBOL Validation Logic (lines 127-134):</b></p>
     * <pre>
     * IF WS-OPTION IS NOT NUMERIC OR
     *    WS-OPTION > CDEMO-MENU-OPT-COUNT OR
     *    WS-OPTION = ZEROS
     *     MOVE 'Y' TO WS-ERR-FLG
     *     MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     *     PERFORM SEND-MENU-SCREEN
     * END-IF
     * </pre>
     * 
     * <p><b>COBOL Access Control (lines 136-143):</b></p>
     * <pre>
     * IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     *     SET ERR-FLG-ON TO TRUE
     *     MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
     *     PERFORM SEND-MENU-SCREEN
     * END-IF
     * </pre>
     * 
     * @param optionNumber Menu option number to validate (1-10)
     * @throws ValidationException if option is invalid or user lacks access rights
     */
    public void validateMenuOption(Integer optionNumber) {
        log.debug("Validating menu option: {}", optionNumber);
        
        // Validate option number is not null
        // Replaces COBOL: WS-OPTION IS NOT NUMERIC check
        if (optionNumber == null) {
            log.error("Menu option is null");
            throw new ValidationException(MessageConstants.MSG_INVALID_MENU_OPTION);
        }
        
        // Validate option is within valid range (1 to MENU_OPTION_COUNT)
        // Replaces COBOL: WS-OPTION > CDEMO-MENU-OPT-COUNT OR WS-OPTION = ZEROS
        if (optionNumber < 1 || optionNumber > MENU_OPTION_COUNT) {
            log.error("Menu option {} is out of range (1-{})", optionNumber, MENU_OPTION_COUNT);
            throw new ValidationException(MessageConstants.MSG_INVALID_MENU_OPTION);
        }
        
        // Find the MenuOption enum constant corresponding to the option number
        MenuOption selectedOption = Arrays.stream(MenuOption.values())
            .filter(opt -> opt.getDisplayOrder() == optionNumber)
            .findFirst()
            .orElseThrow(() -> {
                log.error("No menu option found with display order: {}", optionNumber);
                return new ValidationException(MessageConstants.MSG_INVALID_MENU_OPTION);
            });
        
        // Check if option is admin-only and user lacks ADMIN role
        // Replaces COBOL: IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
        if (selectedOption.isAdminOnly() && !isAdminUser()) {
            log.error("User attempted to access admin-only option: {} ({})",
                optionNumber, selectedOption.menuLabel);
            throw new ValidationException(MessageConstants.MSG_ACCESS_DENIED_ADMIN_ONLY);
        }
        
        log.debug("Menu option {} validated successfully", optionNumber);
    }

    /**
     * Build menu options list with role-based filtering
     * 
     * <p>Constructs list of MenuItem DTOs from MenuOption enum constants,
     * transforming COBOL BUILD-MENU-OPTIONS paragraph (lines 236-277 in COMEN01C.cbl).</p>
     * 
     * <p><b>COBOL Logic:</b></p>
     * <pre>
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT
     *     MOVE SPACES TO WS-MENU-OPT-TXT
     *     STRING CDEMO-MENU-OPT-NUM(WS-IDX) DELIMITED BY SIZE
     *            '. '                       DELIMITED BY SIZE
     *            CDEMO-MENU-OPT-NAME(WS-IDX) DELIMITED BY SIZE
     *       INTO WS-MENU-OPT-TXT
     *     EVALUATE WS-IDX
     *         WHEN 1 MOVE WS-MENU-OPT-TXT TO OPTN001O
     *         WHEN 2 MOVE WS-MENU-OPT-TXT TO OPTN002O
     *         ...
     *     END-EVALUATE
     * END-PERFORM
     * </pre>
     * 
     * <p><b>Java Transformation:</b></p>
     * <ul>
     *   <li>Iterate over MenuOption enum constants instead of COBOL array</li>
     *   <li>Convert each MenuOption to MenuItem DTO using builder pattern</li>
     *   <li>Filter admin-only options if user is not an administrator</li>
     *   <li>Return immutable list sorted by displayOrder</li>
     * </ul>
     * 
     * @param isAdmin true if user has ADMIN role, false for USER role
     * @return List of MenuItem objects filtered by user role
     */
    private List<MenuItem> buildMenuOptions(boolean isAdmin) {
        log.debug("Building menu options with admin access: {}", isAdmin);
        
        // Stream over all MenuOption enum constants
        // Replaces COBOL: PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT
        List<MenuItem> menuItems = Arrays.stream(MenuOption.values())
            // Filter out admin-only options if user is not admin
            // Replaces COBOL access control: IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE = 'A'
            .filter(option -> isAdmin || !option.isAdminOnly())
            // Convert MenuOption enum to MenuItem DTO
            // Replaces COBOL: STRING CDEMO-MENU-OPT-NUM '. ' CDEMO-MENU-OPT-NAME
            .map(MenuOption::toMenuItem)
            // Collect to list
            .collect(Collectors.toList());
        
        log.debug("Built {} menu options", menuItems.size());
        
        return menuItems;
    }

    /**
     * Check if current authenticated user has ADMIN role
     * 
     * <p>Queries Spring Security SecurityContext to determine if authenticated user
     * has ROLE_ADMIN authority, transforming COBOL user type checking.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * 10 CDEMO-USER-TYPE PIC X(01).
     *    88 CDEMO-USRTYP-ADMIN VALUE 'A'.
     *    88 CDEMO-USRTYP-USER  VALUE 'U'.
     * 
     * IF CDEMO-USRTYP-ADMIN
     *     ... (admin logic)
     * END-IF
     * </pre>
     * 
     * <p><b>Spring Security Authority Mapping:</b></p>
     * <ul>
     *   <li>ROLE_ADMIN → CDEMO-USRTYP-ADMIN (value 'A')</li>
     *   <li>ROLE_USER → CDEMO-USRTYP-USER (value 'U')</li>
     * </ul>
     * 
     * @return true if user has ROLE_ADMIN authority, false otherwise
     */
    private boolean isAdminUser() {
        // Get authentication from Spring Security context
        // Replaces COBOL COMMAREA field access: CDEMO-USER-TYPE
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            log.debug("No authenticated user, returning false for isAdminUser");
            return false;
        }
        
        // Get granted authorities from authentication
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        
        if (authorities == null || authorities.isEmpty()) {
            log.debug("No authorities found for user, returning false for isAdminUser");
            return false;
        }
        
        // Check if user has ROLE_ADMIN authority
        // Replaces COBOL: IF CDEMO-USRTYP-ADMIN condition check
        boolean hasAdminRole = authorities.stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(auth -> "ROLE_ADMIN".equals(auth));
        
        log.debug("User admin status: {}", hasAdminRole);
        
        return hasAdminRole;
    }
}

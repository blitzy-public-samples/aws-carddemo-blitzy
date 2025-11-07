/*
 * AdminMenuService.java
 * 
 * CardDemo Application - Administrative Menu Service
 * 
 * This service implements administrative menu privilege checking and option display
 * logic, transforming CICS COBOL program COADM01C.cbl to enforce ROLE_ADMIN access
 * controls for user management functions.
 * 
 * Original COBOL Source Mapping:
 * - COBOL Program: app/cbl/COADM01C.cbl - Admin Menu transaction CA00
 * - Copybook: app/cpy/COADM02Y.cpy - Admin menu options structure
 * - Copybook: app/cpy/COCOM01Y.cpy - COMMAREA communication area
 * 
 * Key Transformations:
 * - PROCESS-ENTER-KEY paragraph (lines 117-134) -> validateMenuOption() method
 * - BUILD-MENU-OPTIONS paragraph (lines 226-263) -> buildAdminMenuOptions() method
 * - RACF admin-only access checks -> Spring Security @PreAuthorize
 * - EXEC CICS XCTL program control -> REST endpoint routing in MenuResponse
 * - COMMAREA state management -> Stateless REST with Spring Security context
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
 */
package com.carddemo.service.menu;

import com.carddemo.constants.MessageConstants;
import com.carddemo.dto.response.MenuResponse;
import com.carddemo.entity.User;
import com.carddemo.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for administrative menu operations in the CardDemo application.
 * 
 * <p>This service class transforms the COBOL COADM01C.cbl program which implements
 * the administrative menu (transaction CA00) for admin users with user management
 * capabilities. It enforces ROLE_ADMIN access controls matching the original RACF
 * security patterns from the mainframe implementation.</p>
 * 
 * <p><b>COBOL Source Transformation:</b></p>
 * <pre>
 * COBOL Program: COADM01C.cbl
 *   - Transaction ID: CA00
 *   - Function: Admin Menu for Admin users
 *   - Lines 117-134: PROCESS-ENTER-KEY paragraph (menu option validation)
 *   - Lines 226-263: BUILD-MENU-OPTIONS paragraph (menu display construction)
 *   - Lines 138-145: XCTL to admin programs (COUSR00C-COUSR03C)
 * 
 * Admin Menu Options from COADM02Y.cpy:
 *   - CDEMO-ADMIN-OPT-COUNT = 4 (lines 20)
 *   - Option 1: User List (Security) -> COUSR00C
 *   - Option 2: User Add (Security) -> COUSR01C
 *   - Option 3: User Update (Security) -> COUSR02C
 *   - Option 4: User Delete (Security) -> COUSR03C
 * </pre>
 * 
 * <p><b>CICS to Spring Security Transformation:</b></p>
 * <pre>
 * COBOL: Implicit RACF admin-only access control in CA00 transaction
 * Java:  Spring Security @PreAuthorize or runtime authority checking
 * 
 * COBOL: EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
 * Java:  MenuResponse with menuRoute for React Router navigation
 * 
 * COBOL: IF CDEMO-USRTYP-ADMIN (88-level from COCOM01Y.cpy line 27)
 * Java:  isAdminUser() checking SecurityContext for ROLE_ADMIN
 * </pre>
 * 
 * <p><b>Menu Option Validation:</b></p>
 * <p>The validateMenuOption method replicates COBOL validation logic:</p>
 * <ul>
 *   <li>Checks if option is numeric (COBOL line 127: WS-OPTION IS NOT NUMERIC)</li>
 *   <li>Validates option > 0 (COBOL line 129: WS-OPTION = ZEROS)</li>
 *   <li>Validates option <= max count (COBOL line 128: WS-OPTION > CDEMO-ADMIN-OPT-COUNT)</li>
 *   <li>Throws ValidationException on invalid input (COBOL lines 130-133: error flag and message)</li>
 * </ul>
 * 
 * <p><b>Admin Menu Structure:</b></p>
 * <p>The buildAdminMenuOptions method constructs menu items matching COBOL display format:</p>
 * <pre>
 * COBOL Display Pattern (lines 233-236):
 *   STRING CDEMO-ADMIN-OPT-NUM(WS-IDX)  DELIMITED BY SIZE
 *          '. '                         DELIMITED BY SIZE
 *          CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE
 *     INTO WS-ADMIN-OPT-TXT
 * 
 * Java Equivalent:
 *   MenuItem with displayOrder, menuLabel, menuRoute fields
 * </pre>
 * 
 * <p><b>Role-Based Access Control:</b></p>
 * <p>This service is accessible only to users with ROLE_ADMIN authority, matching
 * the implicit RACF security from the mainframe where CA00 transaction was restricted
 * to administrative users. Regular users attempting to access admin functions will
 * receive AccessDeniedException.</p>
 * 
 * <p><b>Usage in Controller:</b></p>
 * <pre>
 * {@literal @}RestController
 * public class AdminController {
 *     
 *     {@literal @}GetMapping("/api/admin/menu")
 *     {@literal @}PreAuthorize("hasRole('ADMIN')")
 *     public ResponseEntity&lt;MenuResponse&gt; getAdminMenu() {
 *         MenuResponse response = adminMenuService.getAdminMenu();
 *         return ResponseEntity.ok(response);
 *     }
 * }
 * </pre>
 * 
 * @see com.carddemo.dto.response.MenuResponse
 * @see com.carddemo.constants.MessageConstants
 * @see com.carddemo.entity.User.UserType
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminMenuService {
    
    /**
     * Admin menu option count matching COADM02Y.cpy line 20.
     * COBOL: CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4.
     */
    private static final int ADMIN_OPT_COUNT = 4;
    
    /**
     * Admin menu option definitions matching COADM02Y.cpy lines 24-42.
     * 
     * <p>Structure replicates COBOL CDEMO-ADMIN-OPTIONS array:</p>
     * <pre>
     * 10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.
     *    15 CDEMO-ADMIN-OPT-NUM      PIC 9(02).
     *    15 CDEMO-ADMIN-OPT-NAME     PIC X(35).
     *    15 CDEMO-ADMIN-OPT-PGMNAME  PIC X(08).
     * </pre>
     * 
     * <p>Each AdminMenuOption contains:</p>
     * <ul>
     *   <li>optionNumber: Menu option number (1-4)</li>
     *   <li>optionName: Display name for menu item</li>
     *   <li>programName: Original COBOL program name</li>
     *   <li>menuId: Unique identifier for React components</li>
     *   <li>menuRoute: React Router path</li>
     *   <li>menuDescription: Detailed description for tooltips</li>
     * </ul>
     */
    private static final AdminMenuOption[] ADMIN_MENU_OPTIONS = {
        new AdminMenuOption(
            1,
            "User List (Security)",
            "COUSR00C",
            "USER_LIST",
            "/admin/users",
            "View and search user accounts with security access controls"
        ),
        new AdminMenuOption(
            2,
            "User Add (Security)",
            "COUSR01C",
            "USER_ADD",
            "/admin/users/add",
            "Create new user accounts with role assignment (Admin or Regular User)"
        ),
        new AdminMenuOption(
            3,
            "User Update (Security)",
            "COUSR02C",
            "USER_UPDATE",
            "/admin/users/update",
            "Modify existing user account details including password reset and role changes"
        ),
        new AdminMenuOption(
            4,
            "User Delete (Security)",
            "COUSR03C",
            "USER_DELETE",
            "/admin/users/delete",
            "Remove user accounts from the system (soft delete with audit trail)"
        )
    };
    
    /**
     * Retrieves the administrative menu for authenticated admin users.
     * 
     * <p>This method transforms the COBOL COADM01C.cbl main menu display logic,
     * replacing SEND-MENU-SCREEN paragraph (lines 172-184) that executed
     * POPULATE-HEADER-INFO and BUILD-MENU-OPTIONS.</p>
     * 
     * <p><b>COBOL Transformation:</b></p>
     * <pre>
     * SEND-MENU-SCREEN.
     *     PERFORM POPULATE-HEADER-INFO  -- Not needed in stateless REST
     *     PERFORM BUILD-MENU-OPTIONS     -- Transformed to buildAdminMenuOptions()
     *     MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO
     *     EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01')
     *          FROM(COADM1AO) ERASE
     *     END-EXEC.
     * </pre>
     * 
     * <p><b>Security Enforcement:</b></p>
     * <p>Validates that current user has ROLE_ADMIN authority matching the implicit
     * RACF security control from the mainframe CA00 transaction. Throws
     * AccessDeniedException if user is not an administrator.</p>
     * 
     * <p><b>Menu Item Construction:</b></p>
     * <p>Builds MenuResponse containing all 4 admin menu options from COADM02Y.cpy:</p>
     * <ol>
     *   <li>User List - View all users (COUSR00C -> /admin/users)</li>
     *   <li>User Add - Create new user (COUSR01C -> /admin/users/add)</li>
     *   <li>User Update - Modify user (COUSR02C -> /admin/users/update)</li>
     *   <li>User Delete - Remove user (COUSR03C -> /admin/users/delete)</li>
     * </ol>
     * 
     * @return MenuResponse containing list of admin menu items with menuId, menuLabel,
     *         menuRoute, menuDescription, and displayOrder for each option
     * @throws AccessDeniedException if current user does not have ROLE_ADMIN authority
     *         (matching COBOL implicit RACF admin-only access control)
     */
    public MenuResponse getAdminMenu() {
        log.debug("AdminMenuService.getAdminMenu() - Retrieving admin menu for authenticated user");
        
        // Validate admin privileges matching COBOL implicit RACF security check
        if (!isAdminUser()) {
            log.warn("AdminMenuService.getAdminMenu() - Non-admin user attempted to access admin menu");
            throw new AccessDeniedException(MessageConstants.MSG_ACCESS_DENIED_ADMIN_ONLY);
        }
        
        // Build admin menu options (transforms BUILD-MENU-OPTIONS paragraph lines 226-263)
        List<MenuResponse.MenuItem> menuItems = buildAdminMenuOptions();
        
        log.info("AdminMenuService.getAdminMenu() - Successfully built admin menu with {} options", 
                 menuItems.size());
        
        // Return MenuResponse (replaces EXEC CICS SEND MAP)
        return MenuResponse.builder()
                .menuItems(menuItems)
                .build();
    }
    
    /**
     * Validates menu option selection matching COBOL PROCESS-ENTER-KEY logic.
     * 
     * <p>This method transforms the PROCESS-ENTER-KEY paragraph from COADM01C.cbl
     * (lines 117-134) which validates user input and checks option number bounds.</p>
     * 
     * <p><b>COBOL Transformation:</b></p>
     * <pre>
     * PROCESS-ENTER-KEY.
     *     -- Lines 117-124: Extract and normalize option input
     *     PERFORM VARYING WS-IDX
     *             FROM LENGTH OF OPTIONI OF COADM1AI BY -1 UNTIL
     *             OPTIONI OF COADM1AI(WS-IDX:1) NOT = SPACES OR WS-IDX = 1
     *     END-PERFORM
     *     MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X
     *     INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
     *     MOVE WS-OPTION-X TO WS-OPTION
     *     
     *     -- Lines 127-134: Validation logic
     *     IF WS-OPTION IS NOT NUMERIC OR
     *        WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
     *        WS-OPTION = ZEROS
     *         MOVE 'Y' TO WS-ERR-FLG
     *         MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     *         PERFORM SEND-MENU-SCREEN
     *     END-IF
     * </pre>
     * 
     * <p><b>Validation Rules (matching COBOL lines 127-129):</b></p>
     * <ul>
     *   <li>Option must not be null (Java validation, implicit in COBOL)</li>
     *   <li>Option must not be zero (COBOL: WS-OPTION = ZEROS)</li>
     *   <li>Option must not exceed max count (COBOL: WS-OPTION > CDEMO-ADMIN-OPT-COUNT)</li>
     * </ul>
     * 
     * <p><b>Error Handling:</b></p>
     * <p>Throws ValidationException with message from MessageConstants.INVALID_MENU_OPTION,
     * matching COBOL error flag pattern (WS-ERR-FLG = 'Y') and error message display.</p>
     * 
     * @param optionNumber The menu option number to validate (1-4)
     * @throws ValidationException if option is null, zero, negative, or exceeds ADMIN_OPT_COUNT
     *         (matching COBOL validation error at lines 127-133)
     */
    public void validateMenuOption(Integer optionNumber) {
        log.debug("AdminMenuService.validateMenuOption() - Validating option: {}", optionNumber);
        
        // Null check (implicit in COBOL since PIC 9(02) cannot be null)
        if (optionNumber == null) {
            log.warn("AdminMenuService.validateMenuOption() - Option number is null");
            throw new ValidationException(MessageConstants.MSG_INVALID_MENU_OPTION);
        }
        
        // Validate numeric bounds matching COBOL lines 127-129
        // COBOL: IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR WS-OPTION = ZEROS
        if (optionNumber <= 0 || optionNumber > ADMIN_OPT_COUNT) {
            log.warn("AdminMenuService.validateMenuOption() - Invalid option number: {}. Must be between 1 and {}",
                     optionNumber, ADMIN_OPT_COUNT);
            
            // Throw exception matching COBOL error handling (lines 130-133)
            throw new ValidationException(
                String.format("Please enter a valid option number between 1 and %d", ADMIN_OPT_COUNT)
            );
        }
        
        log.debug("AdminMenuService.validateMenuOption() - Option {} validated successfully", optionNumber);
    }
    
    /**
     * Builds list of admin menu options for MenuResponse.
     * 
     * <p>This method transforms the BUILD-MENU-OPTIONS paragraph from COADM01C.cbl
     * (lines 226-263) which iterates through CDEMO-ADMIN-OPT array and constructs
     * menu display text for each option.</p>
     * 
     * <p><b>COBOL Transformation:</b></p>
     * <pre>
     * BUILD-MENU-OPTIONS.
     *     PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
     *                     WS-IDX > CDEMO-ADMIN-OPT-COUNT
     *         MOVE SPACES TO WS-ADMIN-OPT-TXT
     *         STRING CDEMO-ADMIN-OPT-NUM(WS-IDX)  DELIMITED BY SIZE
     *                '. '                         DELIMITED BY SIZE
     *                CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE
     *           INTO WS-ADMIN-OPT-TXT
     *         EVALUATE WS-IDX
     *             WHEN 1 MOVE WS-ADMIN-OPT-TXT TO OPTN001O
     *             WHEN 2 MOVE WS-ADMIN-OPT-TXT TO OPTN002O
     *             WHEN 3 MOVE WS-ADMIN-OPT-TXT TO OPTN003O
     *             WHEN 4 MOVE WS-ADMIN-OPT-TXT TO OPTN004O
     *             ...
     *         END-EVALUATE
     *     END-PERFORM.
     * </pre>
     * 
     * <p><b>Menu Item Mapping:</b></p>
     * <p>Each COBOL menu option is transformed to a MenuItem object:</p>
     * <table border="1">
     *   <tr>
     *     <th>COBOL Field</th>
     *     <th>MenuItem Property</th>
     *     <th>Example Value</th>
     *   </tr>
     *   <tr>
     *     <td>CDEMO-ADMIN-OPT-NUM</td>
     *     <td>displayOrder</td>
     *     <td>1</td>
     *   </tr>
     *   <tr>
     *     <td>CDEMO-ADMIN-OPT-NAME</td>
     *     <td>menuLabel</td>
     *     <td>"User List (Security)"</td>
     *   </tr>
     *   <tr>
     *     <td>CDEMO-ADMIN-OPT-PGMNAME</td>
     *     <td>(maps to menuRoute)</td>
     *     <td>"COUSR00C" -> "/admin/users"</td>
     *   </tr>
     *   <tr>
     *     <td>(derived)</td>
     *     <td>menuId</td>
     *     <td>"USER_LIST"</td>
     *   </tr>
     *   <tr>
     *     <td>(new)</td>
     *     <td>menuDescription</td>
     *     <td>"View and search user accounts..."</td>
     *   </tr>
     * </table>
     * 
     * <p><b>Program to Route Mapping:</b></p>
     * <p>COBOL program names are mapped to React Router paths:</p>
     * <ul>
     *   <li>COUSR00C (User List) -> /admin/users</li>
     *   <li>COUSR01C (User Add) -> /admin/users/add</li>
     *   <li>COUSR02C (User Update) -> /admin/users/update</li>
     *   <li>COUSR03C (User Delete) -> /admin/users/delete</li>
     * </ul>
     * 
     * <p>This mapping replaces EXEC CICS XCTL program control transfer (COBOL lines 142-145)
     * with client-side navigation using React Router.</p>
     * 
     * @return List of MenuItem objects representing all admin menu options from COADM02Y.cpy
     */
    protected List<MenuResponse.MenuItem> buildAdminMenuOptions() {
        log.debug("AdminMenuService.buildAdminMenuOptions() - Building admin menu options");
        
        // Initialize list (replaces COBOL array OPTN001O through OPTN010O)
        List<MenuResponse.MenuItem> menuItems = new ArrayList<>(ADMIN_OPT_COUNT);
        
        // Iterate through admin menu options (COBOL PERFORM VARYING WS-IDX lines 228-263)
        for (AdminMenuOption option : ADMIN_MENU_OPTIONS) {
            // Build MenuItem matching COBOL STRING operation (lines 233-236)
            MenuResponse.MenuItem menuItem = MenuResponse.MenuItem.builder()
                    .menuId(option.menuId)
                    .menuLabel(option.optionName)  // CDEMO-ADMIN-OPT-NAME
                    .menuRoute(option.menuRoute)    // Derived from CDEMO-ADMIN-OPT-PGMNAME
                    .menuDescription(option.menuDescription)
                    .displayOrder(option.optionNumber)  // CDEMO-ADMIN-OPT-NUM
                    .build();
            
            menuItems.add(menuItem);
            
            log.trace("AdminMenuService.buildAdminMenuOptions() - Added menu item: {} - {}",
                     option.optionNumber, option.optionName);
        }
        
        log.debug("AdminMenuService.buildAdminMenuOptions() - Built {} menu items", menuItems.size());
        
        return menuItems;
    }
    
    /**
     * Checks if the current authenticated user has admin privileges.
     * 
     * <p>This method replaces COBOL 88-level condition checking from COCOM01Y.cpy:</p>
     * <pre>
     * COBOL: 10 CDEMO-USER-TYPE PIC X(01).
     *           88 CDEMO-USRTYP-ADMIN VALUE 'A'.
     *           88 CDEMO-USRTYP-USER  VALUE 'U'.
     * 
     * Usage in COBOL: IF CDEMO-USRTYP-ADMIN
     * Java Usage: if (isAdminUser())
     * </pre>
     * 
     * <p><b>Spring Security Integration:</b></p>
     * <p>Retrieves current Authentication from SecurityContextHolder and checks
     * granted authorities for ROLE_ADMIN. This matches the mainframe RACF security
     * model where CA00 transaction (admin menu) was restricted to administrative users.</p>
     * 
     * <p><b>Authority Checking Logic:</b></p>
     * <ol>
     *   <li>Retrieve SecurityContext from SecurityContextHolder</li>
     *   <li>Get Authentication object (null if not authenticated)</li>
     *   <li>Check if Authentication is authenticated (isAuthenticated())</li>
     *   <li>Iterate through GrantedAuthority collection</li>
     *   <li>Return true if "ROLE_ADMIN" authority found</li>
     * </ol>
     * 
     * <p><b>Role Naming Convention:</b></p>
     * <p>Spring Security uses "ROLE_" prefix for authorities. UserType.ADMIN from
     * User entity is mapped to "ROLE_ADMIN" authority during authentication in
     * CustomUserDetailsService.</p>
     * 
     * @return true if current user has ROLE_ADMIN authority (matching CDEMO-USRTYP-ADMIN = 'A'),
     *         false otherwise (not authenticated or regular user)
     */
    protected boolean isAdminUser() {
        // Retrieve SecurityContext (replaces COBOL COMMAREA CDEMO-USER-TYPE access)
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        // Check authentication exists and is authenticated
        if (authentication == null || !authentication.isAuthenticated()) {
            log.debug("AdminMenuService.isAdminUser() - No authenticated user found");
            return false;
        }
        
        // Check for ROLE_ADMIN authority (matching CDEMO-USRTYP-ADMIN 88-level condition)
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority));
        
        log.debug("AdminMenuService.isAdminUser() - User {} has admin privileges: {}",
                 authentication.getName(), isAdmin);
        
        return isAdmin;
    }
    
    /**
     * Internal class representing admin menu option structure.
     * 
     * <p>This class replicates the COBOL CDEMO-ADMIN-OPT structure from COADM02Y.cpy:</p>
     * <pre>
     * COBOL: 10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.
     *           15 CDEMO-ADMIN-OPT-NUM      PIC 9(02).
     *           15 CDEMO-ADMIN-OPT-NAME     PIC X(35).
     *           15 CDEMO-ADMIN-OPT-PGMNAME  PIC X(08).
     * </pre>
     * 
     * <p>Extended with additional fields for React Router integration:</p>
     * <ul>
     *   <li>menuId: Unique identifier for React component keys</li>
     *   <li>menuRoute: React Router path replacing EXEC CICS XCTL</li>
     *   <li>menuDescription: Help text for tooltips and accessibility</li>
     * </ul>
     */
    private static class AdminMenuOption {
        private final int optionNumber;        // CDEMO-ADMIN-OPT-NUM
        private final String optionName;       // CDEMO-ADMIN-OPT-NAME
        private final String programName;      // CDEMO-ADMIN-OPT-PGMNAME
        private final String menuId;           // React component identifier
        private final String menuRoute;        // React Router path
        private final String menuDescription;  // Help text
        
        /**
         * Constructor for AdminMenuOption.
         * 
         * @param optionNumber Menu option number (1-4) matching CDEMO-ADMIN-OPT-NUM
         * @param optionName Display name matching CDEMO-ADMIN-OPT-NAME
         * @param programName Original COBOL program name (CDEMO-ADMIN-OPT-PGMNAME)
         * @param menuId Unique identifier for React components
         * @param menuRoute React Router path for navigation
         * @param menuDescription Descriptive help text
         */
        AdminMenuOption(int optionNumber, String optionName, String programName,
                       String menuId, String menuRoute, String menuDescription) {
            this.optionNumber = optionNumber;
            this.optionName = optionName;
            this.programName = programName;
            this.menuId = menuId;
            this.menuRoute = menuRoute;
            this.menuDescription = menuDescription;
        }
    }
}

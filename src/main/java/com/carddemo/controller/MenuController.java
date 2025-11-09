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

import com.carddemo.dto.response.MenuResponse;
import com.carddemo.service.menu.AdminMenuService;
import com.carddemo.service.menu.MenuNavigationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Menu Navigation Controller
 * 
 * <p>REST API controller for menu navigation operations replacing CICS transactions CM00 
 * (regular user menu from COMEN01C.cbl) and CA00 (admin menu from COADM01C.cbl). This controller
 * provides stateless HTTP endpoints for retrieving role-based menu structures that replace the
 * pseudo-conversational CICS COMMAREA navigation pattern with client-side React Router navigation.</p>
 * 
 * <p><b>Mainframe Source Mapping:</b></p>
 * <ul>
 *   <li><b>COBOL Program:</b> app/cbl/COMEN01C.cbl - Main menu transaction CM00 for regular users</li>
 *   <li><b>COBOL Program:</b> app/cbl/COADM01C.cbl - Admin menu transaction CA00 for administrators</li>
 *   <li><b>BMS Mapset:</b> app/bms/COMEN01M.bms - Main menu 3270 screen layout (OPTN001-OPTN012 fields)</li>
 *   <li><b>BMS Mapset:</b> app/bms/COADM01M.bms - Admin menu 3270 screen layout (OPTN001-OPTN009 fields)</li>
 *   <li><b>COBOL Copybook:</b> app/cpy/COMEN02Y.cpy - CARDDEMO-MAIN-MENU-OPTIONS structure (10 options)</li>
 *   <li><b>COBOL Copybook:</b> app/cpy/COADM02Y.cpy - CARDDEMO-ADMIN-OPTIONS structure (4 admin options)</li>
 * </ul>
 * 
 * <p><b>CICS Transaction Transformation:</b></p>
 * <pre>
 * COBOL CICS Pattern:
 *   Transaction CM00 (COMEN01C.cbl):
 *     - EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01') FROM(COMEN1AO) ERASE
 *     - User enters option number (1-10)
 *     - EXEC CICS RECEIVE MAP('COMEN1A') MAPSET('COMEN01') INTO(COMEN1AI)
 *     - EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) COMMAREA(CARDDEMO-COMMAREA)
 * 
 *   Transaction CA00 (COADM01C.cbl):
 *     - EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01') FROM(COADM1AO) ERASE
 *     - User enters admin option (1-4)
 *     - EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
 * 
 * REST API Pattern:
 *   GET /api/menu → MenuResponse (replaces CM00 SEND MAP)
 *     - Returns JSON with List&lt;MenuItem&gt; containing menu options 1-10
 *     - Client React app displays menu options
 *     - User clicks menu item
 *     - React Router navigates to menuRoute (e.g., /accounts/view)
 *     - No server-side state or COMMAREA needed
 * 
 *   GET /api/admin/menu → MenuResponse (replaces CA00 SEND MAP)
 *     - Returns JSON with List&lt;MenuItem&gt; containing admin options 1-4
 *     - React Router navigates to admin menuRoute (e.g., /admin/users)
 * </pre>
 * 
 * <p><b>COBOL Logic Transformation Details:</b></p>
 * 
 * <p><b>COMEN01C.cbl Main Flow (lines 75-110):</b></p>
 * <pre>
 * MAIN-PARA.
 *     IF EIBCALEN = 0
 *         PERFORM RETURN-TO-SIGNON-SCREEN     -- No COMMAREA = unauthenticated
 *     ELSE
 *         MOVE DFHCOMMAREA TO CARDDEMO-COMMAREA
 *         IF NOT CDEMO-PGM-REENTER
 *             PERFORM SEND-MENU-SCREEN        -- First entry: display menu
 *         ELSE
 *             PERFORM RECEIVE-MENU-SCREEN     -- Re-entry: process option
 *             EVALUATE EIBAID
 *                 WHEN DFHENTER
 *                     PERFORM PROCESS-ENTER-KEY   -- Option selected
 *                 WHEN DFHPF3
 *                     PERFORM RETURN-TO-SIGNON-SCREEN  -- Exit to login
 * 
 * Java Equivalent:
 *   - Authentication required via @PreAuthorize (replaces EIBCALEN = 0 check)
 *   - Single HTTP GET request replaces pseudo-conversational flow
 *   - No re-entry flag needed (stateless REST)
 *   - Menu selection handled by client-side React Router
 *   - PF3 (exit) handled by client navigation or logout link
 * </pre>
 * 
 * <p><b>PROCESS-ENTER-KEY Validation (COMEN01C.cbl lines 115-165):</b></p>
 * <pre>
 * COBOL:
 *   IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-MENU-OPT-COUNT OR WS-OPTION = ZEROS
 *       MOVE 'Please enter a valid option number...' TO WS-MESSAGE
 *       PERFORM SEND-MENU-SCREEN
 *   END-IF
 *   
 *   IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
 *       MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
 *       PERFORM SEND-MENU-SCREEN
 *   END-IF
 *   
 *   EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) COMMAREA(CARDDEMO-COMMAREA)
 * 
 * Java:
 *   - Validation moved to service layer (MenuNavigationService.validateMenuOption)
 *   - Role-based filtering removes admin-only options from response for USER role
 *   - Client displays only accessible menu items (no validation needed on click)
 *   - React Router handles navigation to menuRoute
 * </pre>
 * 
 * <p><b>Role-Based Security Enforcement:</b></p>
 * <pre>
 * COBOL RACF Security Pattern:
 *   - CA00 transaction implicitly restricted to admin users via RACF
 *   - CDEMO-USER-TYPE field in COMMAREA: 'A' = Admin, 'U' = User
 *   - Menu options filtered by CDEMO-MENU-OPT-USRTYPE(WS-OPTION)
 * 
 * Spring Security Pattern:
 *   - GET /api/menu: @PreAuthorize("hasRole('USER')") allows both USER and ADMIN
 *   - GET /api/admin/menu: @PreAuthorize("hasRole('ADMIN')") restricts to ADMIN only
 *   - Spring Security authorities: ROLE_USER, ROLE_ADMIN
 *   - SecurityContext populated by JwtAuthenticationFilter from JWT token
 *   - AccessDeniedException → HTTP 403 Forbidden (handled by GlobalExceptionHandler)
 * </pre>
 * 
 * <p><b>Menu Structure Response:</b></p>
 * <pre>
 * Regular User Menu (GET /api/menu) - 10 Options:
 *   1. Account View (COACTVWC) → /accounts/view
 *   2. Account Update (COACTUPC) → /accounts/update
 *   3. Credit Card List (COCRDLIC) → /cards
 *   4. Credit Card View (COCRDSLC) → /cards/view
 *   5. Credit Card Update (COCRDUPC) → /cards/update
 *   6. Transaction List (COTRN00C) → /transactions
 *   7. Transaction View (COTRN01C) → /transactions/view
 *   8. Transaction Add (COTRN02C) → /transactions/add
 *   9. Transaction Reports (CORPT00C) → /reports/transactions
 *  10. Bill Payment (COBIL00C) → /billing/payment
 * 
 * Admin Menu (GET /api/admin/menu) - 4 Options:
 *   1. User List (COUSR00C) → /admin/users
 *   2. User Add (COUSR01C) → /admin/users/add
 *   3. User Update (COUSR02C) → /admin/users/update
 *   4. User Delete (COUSR03C) → /admin/users/delete
 * </pre>
 * 
 * <p><b>Stateless Design Benefits:</b></p>
 * <ul>
 *   <li><b>No COMMAREA State:</b> Eliminates EXEC CICS RETURN TRANSID/COMMAREA pattern</li>
 *   <li><b>No Pseudo-Conversational Processing:</b> Single request-response cycle</li>
 *   <li><b>Scalability:</b> No server-side session state to maintain</li>
 *   <li><b>Client Control:</b> React app manages navigation and UI state</li>
 *   <li><b>JWT Authentication:</b> Stateless security via token in Authorization header</li>
 * </ul>
 * 
 * <p><b>Error Handling:</b></p>
 * <ul>
 *   <li><b>401 Unauthorized:</b> Missing or invalid JWT token (replaces EIBCALEN = 0 return to signon)</li>
 *   <li><b>403 Forbidden:</b> User lacks required role (replaces 'No access - Admin Only' message)</li>
 *   <li><b>500 Internal Server Error:</b> Unexpected service exceptions</li>
 * </ul>
 * 
 * <p><b>API Documentation:</b></p>
 * <p>Both endpoints are documented with OpenAPI 3.0 annotations (@Operation, @ApiResponses)
 * for Swagger UI interactive documentation at /swagger-ui.html, providing:</p>
 * <ul>
 *   <li>Endpoint descriptions with COBOL source references</li>
 *   <li>Response schema examples with MenuItem structure</li>
 *   <li>Security requirements (JWT Bearer token)</li>
 *   <li>Role requirements (USER vs ADMIN)</li>
 *   <li>Error response scenarios</li>
 * </ul>
 * 
 * <p><b>Usage Example:</b></p>
 * <pre>
 * // Client-side React code
 * async function fetchMenu() {
 *   const response = await axios.get('/api/menu', {
 *     headers: { Authorization: `Bearer ${jwtToken}` }
 *   });
 *   const menuItems = response.data.menuItems;
 *   // Display menu items in UI
 *   menuItems.forEach(item => {
 *     console.log(`${item.displayOrder}. ${item.menuLabel} → ${item.menuRoute}`);
 *   });
 * }
 * 
 * // User clicks menu item with menuRoute="/accounts/view"
 * // React Router navigates: history.push('/accounts/view')
 * // No server-side state needed
 * </pre>
 * 
 * @see com.carddemo.service.menu.MenuNavigationService Service implementing main menu logic
 * @see com.carddemo.service.menu.AdminMenuService Service implementing admin menu logic
 * @see com.carddemo.dto.response.MenuResponse Response DTO with menu items list
 * @see com.carddemo.security.JwtAuthenticationFilter JWT token processing for authentication
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Menu Navigation",
    description = "Menu navigation endpoints for regular users and administrators replacing CICS " +
                  "transactions CM00 (COMEN01C.cbl) and CA00 (COADM01C.cbl). Provides role-based " +
                  "menu structures for client-side React Router navigation, eliminating CICS " +
                  "pseudo-conversational COMMAREA state management with stateless REST API design."
)
public class MenuController {

    /**
     * Service for regular user menu navigation
     * Implements logic from COMEN01C.cbl (transaction CM00)
     */
    private final MenuNavigationService menuNavigationService;

    /**
     * Service for administrative menu navigation
     * Implements logic from COADM01C.cbl (transaction CA00)
     */
    private final AdminMenuService adminMenuService;

    /**
     * Get Regular User Menu
     * 
     * <p>Retrieves the main menu for authenticated regular users, replacing CICS transaction CM00
     * (COMEN01C.cbl) that displayed the main menu BMS screen (COMEN01.bms).</p>
     * 
     * <p><b>COBOL Transformation:</b></p>
     * <pre>
     * Original COBOL Flow (COMEN01C.cbl):
     *   SEND-MENU-SCREEN paragraph (lines 182-194):
     *     - PERFORM POPULATE-HEADER-INFO (date, time, transaction ID)
     *     - PERFORM BUILD-MENU-OPTIONS (constructs 10 menu options)
     *     - MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO
     *     - EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01') FROM(COMEN1AO) ERASE
     * 
     * Java Equivalent:
     *   - No header population needed (handled by client UI from system time)
     *   - MenuNavigationService.getRegularUserMenu() builds menu options
     *   - No error messages in menu response (separate error handling)
     *   - ResponseEntity.ok(MenuResponse) serializes to JSON
     * </pre>
     * 
     * <p><b>BUILD-MENU-OPTIONS Logic (lines 236-277):</b></p>
     * <pre>
     * COBOL:
     *   PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT
     *       STRING CDEMO-MENU-OPT-NUM(WS-IDX) DELIMITED BY SIZE
     *              '. ' DELIMITED BY SIZE
     *              CDEMO-MENU-OPT-NAME(WS-IDX) DELIMITED BY SIZE
     *         INTO WS-MENU-OPT-TXT
     *       EVALUATE WS-IDX
     *           WHEN 1  MOVE WS-MENU-OPT-TXT TO OPTN001O OF COMEN1AO
     *           WHEN 2  MOVE WS-MENU-OPT-TXT TO OPTN002O OF COMEN1AO
     *           ...
     *           WHEN 10 MOVE WS-MENU-OPT-TXT TO OPTN010O OF COMEN1AO
     *       END-EVALUATE
     *   END-PERFORM
     * 
     * Java:
     *   MenuResponse with List&lt;MenuItem&gt;:
     *     - Each MenuItem has displayOrder (1-10), menuLabel, menuRoute
     *     - No positional fields needed (JSON array maintains order)
     *     - Client iterates menuItems array to render menu
     * </pre>
     * 
     * <p><b>Menu Options from COMEN02Y.cpy (lines 25-84):</b></p>
     * <ol>
     *   <li>Account View - Display account balance and transaction history</li>
     *   <li>Account Update - Modify account credit limits and settings</li>
     *   <li>Credit Card List - View all cards associated with accounts</li>
     *   <li>Credit Card View - Display individual card details</li>
     *   <li>Credit Card Update - Modify card expiration and status</li>
     *   <li>Transaction List - Browse recent transactions with pagination</li>
     *   <li>Transaction View - Display detailed transaction information</li>
     *   <li>Transaction Add - Create new transaction entries</li>
     *   <li>Transaction Reports - Generate transaction reports with filters</li>
     *   <li>Bill Payment - Process account payments</li>
     * </ol>
     * 
     * <p><b>Role-Based Access:</b></p>
     * <ul>
     *   <li><b>@PreAuthorize("hasRole('USER')"):</b> Allows both ROLE_USER and ROLE_ADMIN
     *       (Spring Security hasRole implicitly allows higher roles)</li>
     *   <li><b>ADMIN users:</b> See all 10 options (CDEMO-USRTYP-ADMIN = 'A')</li>
     *   <li><b>USER users:</b> See same 10 options (all marked with CDEMO-MENU-OPT-USRTYPE = 'U')</li>
     *   <li><b>Note:</b> Original COMEN02Y.cpy marks all options as user-accessible ('U'),
     *       admin-only restrictions are in separate Admin Menu (COADM01C.cbl)</li>
     * </ul>
     * 
     * <p><b>Response Structure:</b></p>
     * <pre>
     * {
     *   "menuItems": [
     *     {
     *       "menuId": "ACCT_VIEW",
     *       "menuLabel": "Account View",
     *       "menuRoute": "/accounts/view",
     *       "menuDescription": "Display account balance and transaction history",
     *       "displayOrder": 1
     *     },
     *     {
     *       "menuId": "ACCT_UPDATE",
     *       "menuLabel": "Account Update",
     *       "menuRoute": "/accounts/update",
     *       "menuDescription": "Modify account credit limits and settings",
     *       "displayOrder": 2
     *     },
     *     ...
     *   ]
     * }
     * </pre>
     * 
     * <p><b>Client-Side Navigation:</b></p>
     * <pre>
     * // React Router integration
     * menuItems.map(item => (
     *   &lt;Link to={item.menuRoute}&gt;
     *     {item.displayOrder}. {item.menuLabel}
     *   &lt;/Link&gt;
     * ))
     * 
     * // Replaces COBOL EXEC CICS XCTL PROGRAM(target) with client-side routing
     * </pre>
     * 
     * <p><b>Error Scenarios:</b></p>
     * <ul>
     *   <li><b>401 Unauthorized:</b> Missing or expired JWT token (replaces EIBCALEN = 0 check)</li>
     *   <li><b>403 Forbidden:</b> User lacks USER role (should not occur with proper authentication)</li>
     * </ul>
     * 
     * <p><b>Performance:</b></p>
     * <ul>
     *   <li>Target response time: &lt; 50ms (menu structure is static, no database access)</li>
     *   <li>Cacheable on client side (menu structure rarely changes)</li>
     *   <li>Stateless processing (no COMMAREA state lookup overhead)</li>
     * </ul>
     * 
     * @return ResponseEntity containing MenuResponse with list of 10 menu items
     *         ordered by displayOrder (1-10), with HTTP 200 OK status
     */
    @GetMapping("/menu")
    @PreAuthorize("hasRole('USER')")
    @Operation(
        summary = "Get regular user menu",
        description = "Retrieves main menu options for authenticated regular users. Replaces CICS " +
                      "transaction CM00 (COMEN01C.cbl) main menu screen display with JSON menu structure " +
                      "containing 10 menu options filtered by user role. Returns menu items with " +
                      "displayOrder, menuLabel, menuRoute for React Router navigation. Requires " +
                      "authenticated user with USER or ADMIN role (JWT token in Authorization header)."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved regular user menu with 10 menu items ordered by " +
                          "displayOrder. Response contains menuItems array with menuId, menuLabel, " +
                          "menuRoute, menuDescription, and displayOrder for each option matching " +
                          "COMEN02Y.cpy menu structure from COBOL."
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Missing or invalid JWT token in Authorization header. " +
                          "Replaces COBOL EIBCALEN = 0 condition that returned to signon screen " +
                          "(COSGN00C.cbl). Client should redirect to /login for authentication."
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - User authenticated but lacks USER role. This scenario should " +
                          "not occur with proper authentication as all authenticated users have " +
                          "either USER or ADMIN role. Replaces COBOL RACF access control."
        )
    })
    public ResponseEntity<MenuResponse> getRegularMenu() {
        log.info("MenuController.getRegularMenu() - Retrieving regular user menu");
        log.debug("Endpoint: GET /api/menu - Replaces CICS transaction CM00 (COMEN01C.cbl)");
        
        // Delegate to MenuNavigationService to build role-based menu
        // Transforms COBOL SEND-MENU-SCREEN paragraph (lines 182-194 in COMEN01C.cbl)
        MenuResponse menuResponse = menuNavigationService.getRegularUserMenu();
        
        // Add null safety check for menu response and menu items list
        if (menuResponse != null && menuResponse.getMenuItems() != null) {
            log.info("MenuController.getRegularMenu() - Successfully retrieved {} menu items",
                     menuResponse.getMenuItems().size());
        } else {
            log.warn("MenuController.getRegularMenu() - Menu response or menu items is null");
        }
        log.debug("MenuController.getRegularMenu() - Returning MenuResponse with HTTP 200 OK");
        
        // Return ResponseEntity with HTTP 200 OK status
        // Replaces EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01') FROM(COMEN1AO) ERASE
        return ResponseEntity.ok(menuResponse);
    }

    /**
     * Get Administrator Menu
     * 
     * <p>Retrieves the administrative menu for authenticated admin users, replacing CICS 
     * transaction CA00 (COADM01C.cbl) that displayed the admin menu BMS screen (COADM01.bms).</p>
     * 
     * <p><b>COBOL Transformation:</b></p>
     * <pre>
     * Original COBOL Flow (COADM01C.cbl):
     *   SEND-MENU-SCREEN paragraph (lines 172-184):
     *     - PERFORM POPULATE-HEADER-INFO (date, time, transaction ID CA00)
     *     - PERFORM BUILD-MENU-OPTIONS (constructs 4 admin menu options)
     *     - MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO
     *     - EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01') FROM(COADM1AO) ERASE
     * 
     * Java Equivalent:
     *   - No header population needed (client UI handles display)
     *   - AdminMenuService.getAdminMenu() builds admin menu options
     *   - No error messages in menu response (separate validation)
     *   - ResponseEntity.ok(MenuResponse) serializes to JSON
     * </pre>
     * 
     * <p><b>BUILD-MENU-OPTIONS Logic (lines 226-263):</b></p>
     * <pre>
     * COBOL:
     *   PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT
     *       STRING CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE
     *              '. ' DELIMITED BY SIZE
     *              CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE
     *         INTO WS-ADMIN-OPT-TXT
     *       EVALUATE WS-IDX
     *           WHEN 1  MOVE WS-ADMIN-OPT-TXT TO OPTN001O OF COADM1AO
     *           WHEN 2  MOVE WS-ADMIN-OPT-TXT TO OPTN002O OF COADM1AO
     *           WHEN 3  MOVE WS-ADMIN-OPT-TXT TO OPTN003O OF COADM1AO
     *           WHEN 4  MOVE WS-ADMIN-OPT-TXT TO OPTN004O OF COADM1AO
     *       END-EVALUATE
     *   END-PERFORM
     * 
     * Java:
     *   MenuResponse with List&lt;MenuItem&gt;:
     *     - Each MenuItem has displayOrder (1-4), menuLabel, menuRoute
     *     - JSON array structure maintains order without positional fields
     * </pre>
     * 
     * <p><b>Admin Menu Options from COADM02Y.cpy (lines 24-42):</b></p>
     * <ol>
     *   <li>User List (Security) - View all user accounts with filtering and search</li>
     *   <li>User Add (Security) - Create new user accounts with role assignment</li>
     *   <li>User Update (Security) - Modify user details, reset passwords, change roles</li>
     *   <li>User Delete (Security) - Remove user accounts (soft delete with audit)</li>
     * </ol>
     * 
     * <p><b>Admin-Only Access Control:</b></p>
     * <ul>
     *   <li><b>@PreAuthorize("hasRole('ADMIN')"):</b> Restricts endpoint to ROLE_ADMIN users only</li>
     *   <li><b>RACF Equivalent:</b> CA00 transaction implicitly restricted to admin users</li>
     *   <li><b>USER role:</b> Receives HTTP 403 Forbidden (replaces 'No access - Admin Only' message)</li>
     *   <li><b>SecurityContext Check:</b> AdminMenuService.isAdminUser() validates ROLE_ADMIN authority</li>
     *   <li><b>Access Denied Message:</b> MessageConstants.MSG_ACCESS_DENIED_ADMIN_ONLY</li>
     * </ul>
     * 
     * <p><b>COBOL User Type Check Transformation:</b></p>
     * <pre>
     * COBOL (COCOM01Y.cpy lines 26-28):
     *   10 CDEMO-USER-TYPE PIC X(01).
     *      88 CDEMO-USRTYP-ADMIN VALUE 'A'.
     *      88 CDEMO-USRTYP-USER  VALUE 'U'.
     * 
     * COBOL Access Check (COMEN01C.cbl lines 136-143):
     *   IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
     *       SET ERR-FLG-ON TO TRUE
     *       MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
     *       PERFORM SEND-MENU-SCREEN
     *   END-IF
     * 
     * Java:
     *   Spring Security authority: ROLE_ADMIN (set by CustomUserDetailsService from User.userType)
     *   @PreAuthorize annotation enforces access before method execution
     *   AccessDeniedException → GlobalExceptionHandler → HTTP 403 Forbidden
     * </pre>
     * 
     * <p><b>Response Structure:</b></p>
     * <pre>
     * {
     *   "menuItems": [
     *     {
     *       "menuId": "USER_LIST",
     *       "menuLabel": "User List (Security)",
     *       "menuRoute": "/admin/users",
     *       "menuDescription": "View and search user accounts with security access controls",
     *       "displayOrder": 1
     *     },
     *     {
     *       "menuId": "USER_ADD",
     *       "menuLabel": "User Add (Security)",
     *       "menuRoute": "/admin/users/add",
     *       "menuDescription": "Create new user accounts with role assignment (Admin or Regular User)",
     *       "displayOrder": 2
     *     },
     *     {
     *       "menuId": "USER_UPDATE",
     *       "menuLabel": "User Update (Security)",
     *       "menuRoute": "/admin/users/update",
     *       "menuDescription": "Modify existing user account details including password reset and role changes",
     *       "displayOrder": 3
     *     },
     *     {
     *       "menuId": "USER_DELETE",
     *       "menuLabel": "User Delete (Security)",
     *       "menuRoute": "/admin/users/delete",
     *       "menuDescription": "Remove user accounts from the system (soft delete with audit trail)",
     *       "displayOrder": 4
     *     }
     *   ]
     * }
     * </pre>
     * 
     * <p><b>Program Control Transfer Mapping:</b></p>
     * <pre>
     * COBOL XCTL Pattern (COADM01C.cbl lines 138-145):
     *   IF NOT ERR-FLG-ON
     *       MOVE WS-TRANID TO CDEMO-FROM-TRANID
     *       MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
     *       MOVE ZEROS TO CDEMO-PGM-CONTEXT
     *       EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
     *            COMMAREA(CARDDEMO-COMMAREA)
     *       END-EXEC
     *   END-IF
     * 
     * React Router Navigation:
     *   &lt;Link to="/admin/users"&gt;User List (Security)&lt;/Link&gt;
     *   // Clicking navigates to /admin/users without server-side state
     *   // AdminController.getUserList() endpoint handles request
     * </pre>
     * 
     * <p><b>Target COBOL Programs:</b></p>
     * <ul>
     *   <li><b>COUSR00C:</b> User list display (transaction CU00) → GET /api/admin/users</li>
     *   <li><b>COUSR01C:</b> User creation (transaction CU01) → POST /api/admin/users</li>
     *   <li><b>COUSR02C:</b> User update (transaction CU02) → PUT /api/admin/users/{id}</li>
     *   <li><b>COUSR03C:</b> User deletion (transaction CU03) → DELETE /api/admin/users/{id}</li>
     * </ul>
     * 
     * <p><b>Error Scenarios:</b></p>
     * <ul>
     *   <li><b>401 Unauthorized:</b> Missing or expired JWT token</li>
     *   <li><b>403 Forbidden:</b> User authenticated but has USER role instead of ADMIN
     *       (replaces COBOL 'No access - Admin Only option' message from COMEN01C.cbl line 140)</li>
     *   <li><b>500 Internal Server Error:</b> Unexpected service exception</li>
     * </ul>
     * 
     * <p><b>Security Audit:</b></p>
     * <ul>
     *   <li>All admin menu access attempts logged via @Slf4j logger</li>
     *   <li>Failed access attempts (403) logged with user principal for security monitoring</li>
     *   <li>Matches COBOL RACF audit trail requirements for administrative access</li>
     * </ul>
     * 
     * <p><b>Performance:</b></p>
     * <ul>
     *   <li>Target response time: &lt; 50ms (static menu structure, no database access)</li>
     *   <li>Stateless processing (no COMMAREA state overhead)</li>
     *   <li>Role validation via Spring Security SecurityContext (in-memory authority check)</li>
     * </ul>
     * 
     * @return ResponseEntity containing MenuResponse with list of 4 admin menu items
     *         ordered by displayOrder (1-4), with HTTP 200 OK status
     * @throws org.springframework.security.access.AccessDeniedException if user lacks ADMIN role,
     *         handled by GlobalExceptionHandler returning HTTP 403 Forbidden response
     */
    @GetMapping("/admin/menu")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Get administrator menu",
        description = "Retrieves administrative menu options for authenticated admin users. Replaces " +
                      "CICS transaction CA00 (COADM01C.cbl) admin menu screen display with JSON menu " +
                      "structure containing 4 admin-only menu options for user management functions. " +
                      "Requires authenticated user with ADMIN role (JWT token in Authorization header). " +
                      "Regular users receive HTTP 403 Forbidden matching COBOL 'No access - Admin Only' message."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved admin menu with 4 menu items ordered by displayOrder. " +
                          "Response contains menuItems array with menuId, menuLabel, menuRoute, " +
                          "menuDescription, and displayOrder for each admin option matching COADM02Y.cpy " +
                          "menu structure from COBOL. User has ROLE_ADMIN authority (CDEMO-USRTYP-ADMIN = 'A')."
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Missing or invalid JWT token in Authorization header. " +
                          "Replaces COBOL EIBCALEN = 0 condition that returned to signon screen. " +
                          "Client should redirect to /login for authentication."
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - User authenticated with valid JWT token but has USER role instead " +
                          "of ADMIN role. Replaces COBOL 'No access - Admin Only option' message " +
                          "(COMEN01C.cbl lines 140-141) and RACF CA00 transaction access control. " +
                          "Client should display access denied message and navigate back to main menu."
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal Server Error - Unexpected exception occurred during menu retrieval. " +
                          "Replaces COBOL unexpected error handling. Check server logs for stack trace."
        )
    })
    public ResponseEntity<MenuResponse> getAdminMenu() {
        log.info("MenuController.getAdminMenu() - Retrieving admin menu for authenticated admin user");
        log.debug("Endpoint: GET /api/admin/menu - Replaces CICS transaction CA00 (COADM01C.cbl)");
        log.debug("Security: Requires ROLE_ADMIN (CDEMO-USRTYP-ADMIN = 'A' in COBOL)");
        
        // Delegate to AdminMenuService to build admin-only menu
        // Transforms COBOL SEND-MENU-SCREEN paragraph (lines 172-184 in COADM01C.cbl)
        // AdminMenuService validates ROLE_ADMIN authority and builds 4-option menu
        MenuResponse menuResponse = adminMenuService.getAdminMenu();
        
        // Add null safety check for menu response and menu items list
        if (menuResponse != null && menuResponse.getMenuItems() != null) {
            log.info("MenuController.getAdminMenu() - Successfully retrieved {} admin menu items",
                     menuResponse.getMenuItems().size());
        } else {
            log.warn("MenuController.getAdminMenu() - Menu response or menu items is null");
        }
        log.debug("MenuController.getAdminMenu() - Returning MenuResponse with HTTP 200 OK");
        log.debug("MenuController.getAdminMenu() - Admin menu options: User List, User Add, User Update, User Delete");
        
        // Return ResponseEntity with HTTP 200 OK status
        // Replaces EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01') FROM(COADM1AO) ERASE
        return ResponseEntity.ok(menuResponse);
    }
}

/*
 * MenuController.java
 *
 * Menu navigation REST controller providing main menu and admin menu endpoints
 * Replaces CICS BMS menu screens with REST API JSON responses
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
 * Converted from COBOL programs:
 *   - COMEN01C.cbl (lines 74-180): Main menu for regular users
 *   - COADM01C.cbl (lines 74-156): Admin menu for administrator users
 *
 * Original COBOL COMMAREA: COCOM01Y.cpy (lines 25-28)
 * Original COBOL menu copybooks:
 *   - COMEN02Y.cpy: Main menu options (10 options)
 *   - COADM02Y.cpy: Admin menu options (4 options)
 *
 * Conversion Notes:
 * - EXEC CICS SEND MAP('COMEN1A') replaced with JSON REST response
 * - EXEC CICS RECEIVE MAP('COMEN1A') replaced with REST API request
 * - COMMAREA user type validation (CDEMO-USRTYP-ADMIN) replaced with JWT role check
 * - BMS field attributes replaced with JSON structure
 * - Menu options stored as static configuration matching COBOL copybook data
 * - Admin access validation (lines 136-143 in COMEN01C.cbl) implemented via JWT token inspection
 */
package com.carddemo.controller;

import com.carddemo.security.JwtTokenProvider;
import com.carddemo.security.SecurityRoles;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.ArrayList;

/**
 * REST controller for menu navigation endpoints.
 * 
 * <p>This controller replaces COBOL menu programs COMEN01C (main menu) and COADM01C (admin menu)
 * with REST API endpoints returning menu options as JSON. The menu options are statically defined
 * matching the COBOL copybook structures.</p>
 * 
 * <p><b>COBOL Program Mapping:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Program</th>
 *     <th>Function</th>
 *     <th>REST Endpoint</th>
 *     <th>Lines Converted</th>
 *   </tr>
 *   <tr>
 *     <td>COMEN01C.cbl</td>
 *     <td>Main menu display and processing</td>
 *     <td>GET /api/menu/main</td>
 *     <td>74-180</td>
 *   </tr>
 *   <tr>
 *     <td>COADM01C.cbl</td>
 *     <td>Admin menu display and processing</td>
 *     <td>GET /api/menu/admin</td>
 *     <td>74-156</td>
 *   </tr>
 * </table>
 * 
 * <p><b>Security Implementation:</b></p>
 * <ul>
 *   <li>Main menu: Accessible to all authenticated users (ROLE_USER, ROLE_ADMIN)</li>
 *   <li>Admin menu: Restricted to ROLE_ADMIN only (replaces CDEMO-USRTYP-ADMIN check)</li>
 *   <li>JWT token validation via JwtTokenProvider.validateToken() and extractUserType()</li>
 *   <li>HTTP 403 Forbidden returned for non-admin accessing admin menu (matches COMEN01C.cbl lines 136-143)</li>
 * </ul>
 * 
 * <p><b>Menu Option Structure:</b></p>
 * <p>Each menu option contains:
 * <ul>
 *   <li><b>optionNumber:</b> Menu selection number (1-10 for main, 1-4 for admin)</li>
 *   <li><b>optionText:</b> Menu option display text</li>
 *   <li><b>programName:</b> Target program/controller name</li>
 *   <li><b>requiredRole:</b> Spring Security role required (ROLE_USER or ROLE_ADMIN)</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    /**
     * JWT token provider for admin menu access validation.
     * Used to extract user role from JWT token and verify admin privileges.
     */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Constructor-based dependency injection.
     *
     * @param jwtTokenProvider JWT token provider for role validation
     */
    public MenuController(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Get main menu options for regular users.
     * 
     * <p>This endpoint replaces COMEN01C.cbl SEND-MENU-SCREEN paragraph (lines 182-194) and
     * BUILD-MENU-OPTIONS paragraph (lines 236-277). Returns static menu configuration matching
     * COMEN02Y.cpy copybook data structure (lines 19-93).</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (COMEN01C.cbl):
     *   PERFORM BUILD-MENU-OPTIONS
     *   EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01') FROM(COMEN1AO) ERASE END-EXEC
     * 
     * Java (this method):
     *   MenuDto menuDto = getMainMenu();
     *   return ResponseEntity.ok(menuDto);
     * </pre>
     * 
     * <p><b>Menu Options Returned:</b></p>
     * <ol>
     *   <li>Account View (COACTVWC)</li>
     *   <li>Account Update (COACTUPC)</li>
     *   <li>Credit Card List (COCRDLIC)</li>
     *   <li>Credit Card View (COCRDSLC)</li>
     *   <li>Credit Card Update (COCRDUPC)</li>
     *   <li>Transaction List (COTRN00C)</li>
     *   <li>Transaction View (COTRN01C)</li>
     *   <li>Transaction Add (COTRN02C)</li>
     *   <li>Transaction Reports (CORPT00C)</li>
     *   <li>Bill Payment (COBIL00C)</li>
     * </ol>
     * 
     * <p><b>Response Format:</b></p>
     * <pre>
     * {
     *   "menuTitle": "CardDemo Main Menu",
     *   "options": [
     *     {
     *       "optionNumber": 1,
     *       "optionText": "Account View",
     *       "programName": "COACTVWC",
     *       "requiredRole": "ROLE_USER"
     *     },
     *     ...
     *   ]
     * }
     * </pre>
     * 
     * @return ResponseEntity containing MenuDto with list of 10 main menu options
     */
    @GetMapping("/main")
    public ResponseEntity<MenuDto> getMainMenu() {
        log.info("Main menu requested");
        
        // Build main menu options from COMEN02Y.cpy copybook structure
        List<MenuOptionDto> options = new ArrayList<>();
        
        // Option 1: Account View (COMEN02Y.cpy lines 25-29)
        options.add(new MenuOptionDto(
            1,
            "Account View",
            "COACTVWC",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 2: Account Update (COMEN02Y.cpy lines 31-35)
        options.add(new MenuOptionDto(
            2,
            "Account Update",
            "COACTUPC",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 3: Credit Card List (COMEN02Y.cpy lines 37-41)
        options.add(new MenuOptionDto(
            3,
            "Credit Card List",
            "COCRDLIC",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 4: Credit Card View (COMEN02Y.cpy lines 43-47)
        options.add(new MenuOptionDto(
            4,
            "Credit Card View",
            "COCRDSLC",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 5: Credit Card Update (COMEN02Y.cpy lines 49-53)
        options.add(new MenuOptionDto(
            5,
            "Credit Card Update",
            "COCRDUPC",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 6: Transaction List (COMEN02Y.cpy lines 55-59)
        options.add(new MenuOptionDto(
            6,
            "Transaction List",
            "COTRN00C",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 7: Transaction View (COMEN02Y.cpy lines 61-65)
        options.add(new MenuOptionDto(
            7,
            "Transaction View",
            "COTRN01C",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 8: Transaction Add (COMEN02Y.cpy lines 67-72)
        options.add(new MenuOptionDto(
            8,
            "Transaction Add",
            "COTRN02C",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 9: Transaction Reports (COMEN02Y.cpy lines 74-78)
        options.add(new MenuOptionDto(
            9,
            "Transaction Reports",
            "CORPT00C",
            SecurityRoles.ROLE_USER
        ));
        
        // Option 10: Bill Payment (COMEN02Y.cpy lines 80-84)
        options.add(new MenuOptionDto(
            10,
            "Bill Payment",
            "COBIL00C",
            SecurityRoles.ROLE_USER
        ));
        
        MenuDto menuDto = new MenuDto("CardDemo Main Menu", options);
        
        log.debug("Returning main menu with {} options", options.size());
        return ResponseEntity.ok(menuDto);
    }

    /**
     * Get admin menu options for administrator users only.
     * 
     * <p>This endpoint replaces COADM01C.cbl SEND-MENU-SCREEN paragraph (lines 172-184) and
     * BUILD-MENU-OPTIONS paragraph. Returns static admin menu configuration matching
     * COADM02Y.cpy copybook data structure (lines 19-48).</p>
     * 
     * <p><b>Admin Access Validation:</b></p>
     * <p>Implements COMEN01C.cbl lines 136-143 admin-only access check:
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
     * Java (this method):
     *   String userRole = jwtTokenProvider.extractUserType(token);
     *   if (!SecurityRoles.ROLE_ADMIN.equals(userRole)) {
     *       return ResponseEntity.status(HttpStatus.FORBIDDEN)
     *           .body(new ErrorResponse("No access - Admin Only option..."));
     *   }
     * </pre>
     * </p>
     * 
     * <p><b>Menu Options Returned:</b></p>
     * <ol>
     *   <li>User List (Security) - COUSR00C</li>
     *   <li>User Add (Security) - COUSR01C</li>
     *   <li>User Update (Security) - COUSR02C</li>
     *   <li>User Delete (Security) - COUSR03C</li>
     * </ol>
     * 
     * <p><b>Response Format:</b></p>
     * <pre>
     * Success (200 OK):
     * {
     *   "menuTitle": "CardDemo Admin Menu",
     *   "options": [
     *     {
     *       "optionNumber": 1,
     *       "optionText": "User List (Security)",
     *       "programName": "COUSR00C",
     *       "requiredRole": "ROLE_ADMIN"
     *     },
     *     ...
     *   ]
     * }
     * 
     * Forbidden (403 FORBIDDEN):
     * {
     *   "error": "No access - Admin Only option..."
     * }
     * </pre>
     * 
     * @param authorizationHeader the Authorization header containing JWT Bearer token
     * @return ResponseEntity containing MenuDto with admin menu options if user is admin,
     *         or HTTP 403 Forbidden with error message if user is not admin
     */
    @GetMapping("/admin")
    public ResponseEntity<?> getAdminMenu(@RequestHeader("Authorization") String authorizationHeader) {
        log.info("Admin menu requested");
        
        // Extract token from "Bearer <token>" format
        String token = extractTokenFromHeader(authorizationHeader);
        
        if (token == null) {
            log.warn("Admin menu access denied: Invalid or missing Authorization header");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("No access - Admin Only option..."));
        }
        
        // Validate token is not expired and has valid signature
        if (!jwtTokenProvider.validateToken(token)) {
            log.warn("Admin menu access denied: Invalid or expired JWT token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("No access - Admin Only option..."));
        }
        
        // Extract user role from JWT token claims
        String userRole = jwtTokenProvider.extractUserType(token);
        
        // Validate user has admin role (replaces CDEMO-USRTYP-ADMIN check from COMEN01C.cbl lines 136-143)
        if (!SecurityRoles.ROLE_ADMIN.equals(userRole)) {
            log.warn("Admin menu access denied: User role '{}' is not admin", userRole);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("No access - Admin Only option..."));
        }
        
        log.info("Admin menu access granted for user with role: {}", userRole);
        
        // Build admin menu options from COADM02Y.cpy copybook structure
        List<MenuOptionDto> options = new ArrayList<>();
        
        // Option 1: User List (COADM02Y.cpy lines 24-27)
        options.add(new MenuOptionDto(
            1,
            "User List (Security)",
            "COUSR00C",
            SecurityRoles.ROLE_ADMIN
        ));
        
        // Option 2: User Add (COADM02Y.cpy lines 29-32)
        options.add(new MenuOptionDto(
            2,
            "User Add (Security)",
            "COUSR01C",
            SecurityRoles.ROLE_ADMIN
        ));
        
        // Option 3: User Update (COADM02Y.cpy lines 34-37)
        options.add(new MenuOptionDto(
            3,
            "User Update (Security)",
            "COUSR02C",
            SecurityRoles.ROLE_ADMIN
        ));
        
        // Option 4: User Delete (COADM02Y.cpy lines 39-42)
        options.add(new MenuOptionDto(
            4,
            "User Delete (Security)",
            "COUSR03C",
            SecurityRoles.ROLE_ADMIN
        ));
        
        MenuDto menuDto = new MenuDto("CardDemo Admin Menu", options);
        
        log.debug("Returning admin menu with {} options", options.size());
        return ResponseEntity.ok(menuDto);
    }

    /**
     * Extracts JWT token from Authorization header.
     * 
     * <p>Removes "Bearer " prefix from header value to get raw token string.
     * Handles null, empty, or malformed headers gracefully.</p>
     * 
     * @param authorizationHeader the Authorization header value (format: "Bearer <token>")
     * @return the extracted JWT token string, or null if header is invalid
     */
    private String extractTokenFromHeader(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.trim().isEmpty()) {
            log.debug("Authorization header is null or empty");
            return null;
        }
        
        if (!authorizationHeader.startsWith("Bearer ")) {
            log.debug("Authorization header does not start with 'Bearer '");
            return null;
        }
        
        String token = authorizationHeader.substring(7).trim();
        
        if (token.isEmpty()) {
            log.debug("Token extracted from Authorization header is empty");
            return null;
        }
        
        return token;
    }

    /**
     * DTO representing a single menu option.
     * 
     * <p>Maps from COBOL menu option structure in COMEN02Y.cpy and COADM02Y.cpy:
     * <ul>
     *   <li>CDEMO-MENU-OPT-NUM (PIC 9(02)) → optionNumber</li>
     *   <li>CDEMO-MENU-OPT-NAME (PIC X(35)) → optionText</li>
     *   <li>CDEMO-MENU-OPT-PGMNAME (PIC X(08)) → programName</li>
     *   <li>CDEMO-MENU-OPT-USRTYPE (PIC X(01)) → requiredRole (converted to Spring Security role)</li>
     * </ul>
     * </p>
     */
    public static class MenuOptionDto {
        /**
         * Menu option selection number (1-based index).
         * Maps from CDEMO-MENU-OPT-NUM in COBOL copybooks.
         */
        private final int optionNumber;
        
        /**
         * Menu option display text.
         * Maps from CDEMO-MENU-OPT-NAME in COBOL copybooks.
         */
        private final String optionText;
        
        /**
         * Target program/controller name.
         * Maps from CDEMO-MENU-OPT-PGMNAME in COBOL copybooks.
         */
        private final String programName;
        
        /**
         * Required Spring Security role to access this option.
         * Converted from CDEMO-MENU-OPT-USRTYPE ('A' → ROLE_ADMIN, 'U' → ROLE_USER).
         */
        private final String requiredRole;

        public MenuOptionDto(int optionNumber, String optionText, String programName, String requiredRole) {
            this.optionNumber = optionNumber;
            this.optionText = optionText;
            this.programName = programName;
            this.requiredRole = requiredRole;
        }

        public int getOptionNumber() {
            return optionNumber;
        }

        public String getOptionText() {
            return optionText;
        }

        public String getProgramName() {
            return programName;
        }

        public String getRequiredRole() {
            return requiredRole;
        }
    }

    /**
     * DTO representing a complete menu with title and options.
     * 
     * <p>Replaces BMS map structure (COMEN1AO/COADM1AO) with JSON-serializable object.</p>
     */
    public static class MenuDto {
        /**
         * Menu title text displayed to user.
         */
        private final String menuTitle;
        
        /**
         * List of menu options available for selection.
         * Maps from CDEMO-MENU-OPTIONS OCCURS 12 TIMES in COBOL copybooks.
         */
        private final List<MenuOptionDto> options;

        public MenuDto(String menuTitle, List<MenuOptionDto> options) {
            this.menuTitle = menuTitle;
            this.options = options;
        }

        public String getMenuTitle() {
            return menuTitle;
        }

        public List<MenuOptionDto> getOptions() {
            return options;
        }
    }

    /**
     * DTO for error responses.
     * 
     * <p>Used to return standardized error messages matching COBOL error handling
     * patterns (WS-MESSAGE field in COBOL programs).</p>
     */
    public static class ErrorResponse {
        /**
         * Error message text.
         * Maps from WS-MESSAGE field in COBOL programs.
         */
        private final String error;

        public ErrorResponse(String error) {
            this.error = error;
        }

        public String getError() {
            return error;
        }
    }
}

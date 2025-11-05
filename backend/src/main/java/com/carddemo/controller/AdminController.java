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

import com.carddemo.service.AdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for administrative functions menu and operations.
 * 
 * <p>Transforms COBOL CICS program COADM01C.cbl (269 lines) which provides the
 * administrative menu interface for users with administrative privileges (USER-TYPE='A').
 * This controller exposes REST endpoints for admin menu display and administrative
 * function execution, enforcing role-based access control via Spring Security.</p>
 * 
 * <p><strong>COBOL Source Program: COADM01C.cbl</strong></p>
 * <ul>
 *   <li>Program ID: COADM01C</li>
 *   <li>Transaction ID: CA00 (Admin Menu transaction)</li>
 *   <li>Function: Admin Menu for Admin users</li>
 *   <li>BMS Mapset: COADM01 (Screen: COADM1A)</li>
 *   <li>Copybooks: COCOM01Y, COADM02Y, COADM01, COTTL01Y, CSDAT01Y, CSMSG01Y, CSUSR01Y</li>
 * </ul>
 * 
 * <p><strong>COBOL Program Flow:</strong></p>
 * <pre>
 * MAIN-PARA (lines 75-110):
 * - Validates COMMAREA (pseudo-conversational state)
 * - Routes to SEND-MENU-SCREEN (first entry) or RECEIVE-MENU-SCREEN (reentry)
 * - Handles PF3 key to return to signon screen (COSGN00C)
 * - Processes ENTER key to execute selected admin function
 * 
 * PROCESS-ENTER-KEY (lines 115-155):
 * - Parses option number from screen input (OPTIONI)
 * - Validates option range (1 to CDEMO-ADMIN-OPT-COUNT = 4)
 * - Executes CICS XCTL to selected admin program (COUSR00C-COUSR03C)
 * - Displays error for invalid option selection
 * 
 * BUILD-MENU-OPTIONS (lines 226-263):
 * - Constructs admin menu from COADM02Y copybook structure
 * - Formats option text: "1. User List (Security)"
 * - Maps to screen fields OPTN001O through OPTN010O
 * </pre>
 * 
 * <p><strong>Admin Menu Options (from COADM02Y.cpy):</strong></p>
 * <ol>
 *   <li><strong>User List (Security)</strong> - COUSR00C - Browse users with pagination</li>
 *   <li><strong>User Add (Security)</strong> - COUSR01C - Create new user accounts</li>
 *   <li><strong>User Update (Security)</strong> - COUSR02C - Modify user information</li>
 *   <li><strong>User Delete (Security)</strong> - COUSR03C - Remove user accounts</li>
 * </ol>
 * 
 * <p><strong>REST API Transformation:</strong></p>
 * <ul>
 *   <li>CICS Transaction CA00 → GET /api/admin (admin menu retrieval)</li>
 *   <li>CICS XCTL routing → POST /api/admin/actions (function execution)</li>
 *   <li>COMMAREA state → HTTP session management</li>
 *   <li>BMS SEND MAP → JSON response with menu structure</li>
 *   <li>BMS RECEIVE MAP → JSON request with selected option</li>
 * </ul>
 * 
 * <p><strong>Security Model (Section 0.9):</strong></p>
 * <ul>
 *   <li>All endpoints require ROLE_ADMIN authorization via @PreAuthorize</li>
 *   <li>Maps COBOL USER-TYPE='A' validation from CDEMO-USRTYP-ADMIN</li>
 *   <li>Only users with userType='A' can access admin functions</li>
 *   <li>Enforces exact access control pattern from mainframe USRSEC file</li>
 *   <li>HTTP 401 Unauthorized for missing authentication</li>
 *   <li>HTTP 403 Forbidden for non-admin users</li>
 * </ul>
 * 
 * <p><strong>Error Handling:</strong></p>
 * <ul>
 *   <li>Invalid option number → HTTP 400 Bad Request</li>
 *   <li>Missing authentication → HTTP 401 Unauthorized</li>
 *   <li>Insufficient privileges → HTTP 403 Forbidden</li>
 *   <li>Action execution failure → HTTP 500 Internal Server Error</li>
 * </ul>
 * 
 * <p><strong>Audit Trail (Section 0.9):</strong></p>
 * <ul>
 *   <li>Comprehensive logging of ALL administrative actions</li>
 *   <li>User ID captured from JWT for compliance tracking</li>
 *   <li>Timestamps for all operations</li>
 *   <li>Action type, target, and outcome recorded</li>
 * </ul>
 * 
 * <p><strong>Performance Requirements:</strong></p>
 * <ul>
 *   <li>Menu display: Sub-100ms response time</li>
 *   <li>Function routing: Sub-50ms delegation overhead</li>
 *   <li>No blocking operations in menu display</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0.0
 * @see com.carddemo.service.AdminService
 * @see com.carddemo.service.UserManagementService
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@Validated
public class AdminController {

    private final AdminService adminService;

    /**
     * Constructs AdminController with required service dependency.
     * 
     * <p>Constructor injection ensures immutable dependency and facilitates unit testing.
     * AdminService orchestrates all administrative business logic and delegates to
     * specialized services (UserManagementService, ReportMenuService).</p>
     * 
     * @param adminService service for administrative operations and menu management
     */
    @Autowired
    public AdminController(AdminService adminService) {
        this.adminService = adminService;
        log.info("AdminController initialized with AdminService");
    }

    /**
     * Retrieves the administrative menu with available options.
     * 
     * <p>Transforms COBOL paragraph BUILD-MENU-OPTIONS (lines 226-263) and
     * SEND-MENU-SCREEN (lines 172-184) which construct and display the admin menu
     * from COADM02Y copybook structure. Returns menu structure with formatted option
     * text, transaction context, and screen header information.</p>
     * 
     * <p><strong>COBOL Source Mapping:</strong></p>
     * <pre>
     * SEND-MENU-SCREEN:
     *   PERFORM POPULATE-HEADER-INFO
     *   PERFORM BUILD-MENU-OPTIONS
     *   MOVE WS-MESSAGE TO ERRMSGO OF COADM1AO
     *   EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01') FROM(COADM1AO) ERASE END-EXEC
     * 
     * BUILD-MENU-OPTIONS (lines 226-263):
     *   PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-ADMIN-OPT-COUNT
     *     MOVE SPACES TO WS-ADMIN-OPT-TXT
     *     STRING CDEMO-ADMIN-OPT-NUM(WS-IDX) '. ' CDEMO-ADMIN-OPT-NAME(WS-IDX)
     *       INTO WS-ADMIN-OPT-TXT
     *     EVALUATE WS-IDX
     *       WHEN 1 MOVE WS-ADMIN-OPT-TXT TO OPTN001O
     *       WHEN 2 MOVE WS-ADMIN-OPT-TXT TO OPTN002O
     *       ...
     * </pre>
     * 
     * <p><strong>Response Structure:</strong></p>
     * <pre>
     * {
     *   "transactionId": "CA00",
     *   "programName": "COADM01C",
     *   "currentDate": "MM/dd/yy",
     *   "currentTime": "HH:mm:ss",
     *   "title01": "AWS Mainframe Modernization",
     *   "title02": "CardDemo Admin Menu",
     *   "menuOptions": [
     *     {
     *       "optionNumber": 1,
     *       "optionName": "User List (Security)",
     *       "programName": "COUSR00C",
     *       "displayText": "1. User List (Security)"
     *     },
     *     ...
     *   ],
     *   "optionCount": 4,
     *   "promptMessage": "Please enter an option number..."
     * }
     * </pre>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>Requires ROLE_ADMIN authority per Section 0.9</li>
     *   <li>Maps COBOL CDEMO-USRTYP-ADMIN validation from lines 82-84</li>
     *   <li>Returns HTTP 403 Forbidden for non-admin users</li>
     * </ul>
     * 
     * <p><strong>HTTP Status Codes:</strong></p>
     * <ul>
     *   <li>200 OK - Admin menu successfully retrieved</li>
     *   <li>401 Unauthorized - Missing or invalid authentication token</li>
     *   <li>403 Forbidden - User lacks ROLE_ADMIN authority</li>
     *   <li>500 Internal Server Error - Unexpected error during menu construction</li>
     * </ul>
     * 
     * @return ResponseEntity containing admin menu structure with HTTP 200 OK status
     * @throws org.springframework.security.access.AccessDeniedException if user lacks ROLE_ADMIN
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getAdminMenu() {
        try {
            // Extract authenticated user for audit logging
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication != null ? authentication.getName() : "UNKNOWN";
            
            log.info("Admin menu requested by user: {}", username);
            
            // Delegate to AdminService which implements BUILD-MENU-OPTIONS logic
            Map<String, Object> adminMenu = adminService.getAdminMenu();
            
            // Add request metadata for audit trail
            adminMenu.put("requestedBy", username);
            adminMenu.put("requestTimestamp", LocalDateTime.now());
            
            log.debug("Admin menu successfully retrieved for user: {} with {} options", 
                     username, adminMenu.get("optionCount"));
            
            return ResponseEntity.ok(adminMenu);
            
        } catch (org.springframework.security.access.AccessDeniedException e) {
            log.warn("Access denied to admin menu - user lacks ROLE_ADMIN: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error retrieving admin menu: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "ERROR");
            errorResponse.put("message", "Failed to retrieve admin menu");
            errorResponse.put("error", e.getMessage());
            errorResponse.put("timestamp", LocalDateTime.now());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Executes an administrative function based on the selected option.
     * 
     * <p>Transforms COBOL paragraph PROCESS-ENTER-KEY (lines 115-155) which validates
     * the user's menu selection and routes control to the appropriate administrative
     * program via EXEC CICS XCTL. This endpoint delegates to AdminService based on
     * the option number.</p>
     * 
     * <p><strong>COBOL Source Mapping:</strong></p>
     * <pre>
     * PROCESS-ENTER-KEY (lines 115-155):
     *   PERFORM VARYING WS-IDX FROM LENGTH OF OPTIONI BY -1
     *     UNTIL OPTIONI OF COADM1AI(WS-IDX:1) NOT = SPACES OR WS-IDX = 1
     *   END-PERFORM
     *   MOVE OPTIONI OF COADM1AI(1:WS-IDX) TO WS-OPTION-X
     *   INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
     *   MOVE WS-OPTION-X TO WS-OPTION
     *   
     *   IF WS-OPTION IS NOT NUMERIC OR
     *      WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
     *      WS-OPTION = ZEROS
     *     MOVE 'Y' TO WS-ERR-FLG
     *     MOVE 'Please enter a valid option number...' TO WS-MESSAGE
     *     PERFORM SEND-MENU-SCREEN
     *   END-IF
     *   
     *   IF NOT ERR-FLG-ON
     *     IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
     *       MOVE WS-TRANID TO CDEMO-FROM-TRANID
     *       MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
     *       MOVE ZEROS TO CDEMO-PGM-CONTEXT
     *       EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
     *         COMMAREA(CARDDEMO-COMMAREA)
     *       END-EXEC
     *     END-IF
     *   END-IF
     * </pre>
     * 
     * <p><strong>Request Structure:</strong></p>
     * <pre>
     * {
     *   "optionNumber": 1,
     *   "selectedOption": "User List",
     *   "userId": "ADMIN001",
     *   "parameters": {
     *     "key": "value"
     *   }
     * }
     * </pre>
     * 
     * <p><strong>Response Structure:</strong></p>
     * <pre>
     * {
     *   "optionNumber": 1,
     *   "selectedOption": "User List",
     *   "userId": "ADMIN001",
     *   "executionTimestamp": "2024-01-15T14:32:01",
     *   "function": "User List",
     *   "targetProgram": "COUSR00C",
     *   "message": "User list function - Navigate to user list screen",
     *   "nextAction": "REDIRECT_TO_USER_LIST",
     *   "status": "SUCCESS",
     *   "statusCode": "00"
     * }
     * </pre>
     * 
     * <p><strong>Option to Program Routing:</strong></p>
     * <ul>
     *   <li>Option 1 → COUSR00C (User List) → UserManagementService.listUsers()</li>
     *   <li>Option 2 → COUSR01C (User Add) → UserManagementService.createUser()</li>
     *   <li>Option 3 → COUSR02C (User Update) → UserManagementService.updateUser()</li>
     *   <li>Option 4 → COUSR03C (User Delete) → UserManagementService.deleteUser()</li>
     * </ul>
     * 
     * <p><strong>Validation Rules (lines 127-134):</strong></p>
     * <ul>
     *   <li>Option number must be numeric (1-4)</li>
     *   <li>Option number must not be zero</li>
     *   <li>Option number must not exceed CDEMO-ADMIN-OPT-COUNT (4)</li>
     *   <li>Selected option must match available menu options</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>Requires ROLE_ADMIN authority</li>
     *   <li>User ID captured from JWT for audit trail</li>
     *   <li>All delegated operations also require ROLE_ADMIN</li>
     * </ul>
     * 
     * <p><strong>Audit Trail Logging:</strong></p>
     * <pre>
     * LOG: [2024-01-15 14:32:01] ADMIN [ADMIN001] executed admin function [1] [User List]
     * LOG: [2024-01-15 14:32:01] ADMIN [ADMIN001] delegated to UserManagementService.listUsers()
     * </pre>
     * 
     * <p><strong>HTTP Status Codes:</strong></p>
     * <ul>
     *   <li>200 OK - Action successfully executed</li>
     *   <li>400 Bad Request - Invalid option number or parameters</li>
     *   <li>401 Unauthorized - Missing or invalid authentication token</li>
     *   <li>403 Forbidden - User lacks ROLE_ADMIN authority</li>
     *   <li>500 Internal Server Error - Action execution failure</li>
     * </ul>
     * 
     * @param request AdminActionRequest containing option number and parameters
     * @return ResponseEntity containing execution result with HTTP 200 OK status
     * @throws IllegalArgumentException if option number is invalid or out of range
     * @throws org.springframework.security.access.AccessDeniedException if user lacks ROLE_ADMIN
     */
    @PostMapping("/actions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> executeAdminAction(
            @Valid @RequestBody AdminActionRequest request) {
        try {
            // Extract authenticated user for audit logging
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication != null ? authentication.getName() : "UNKNOWN";
            
            log.info("Admin action execution requested by user: {}, option: {}, selectedOption: {}", 
                     username, request.getOptionNumber(), request.getSelectedOption());
            
            // Set userId from authenticated context
            request.setUserId(username);
            
            // Convert AdminActionRequest to AdminRequest format expected by service
            AdminRequest serviceRequest = new AdminRequest();
            serviceRequest.setOptionNumber(request.getOptionNumber());
            serviceRequest.setSelectedOption(request.getSelectedOption());
            serviceRequest.setUserId(username);
            serviceRequest.setParameters(request.getParameters());
            
            // Delegate to AdminService which implements PROCESS-ENTER-KEY logic
            Map<String, Object> executionResult = adminService.executeAdminFunction(serviceRequest);
            
            // Add request metadata for audit trail
            executionResult.put("requestedBy", username);
            executionResult.put("requestTimestamp", LocalDateTime.now());
            
            log.info("Admin action executed successfully by user: {}, option: {}, function: {}", 
                     username, request.getOptionNumber(), executionResult.get("function"));
            
            return ResponseEntity.ok(executionResult);
            
        } catch (IllegalArgumentException e) {
            // COBOL validation error equivalent (lines 127-134 WS-ERR-FLG)
            log.warn("Invalid admin action request: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "ERROR");
            errorResponse.put("statusCode", "01");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", LocalDateTime.now());
            return ResponseEntity.badRequest().body(errorResponse);
            
        } catch (org.springframework.security.access.AccessDeniedException e) {
            log.warn("Access denied to admin action - user lacks ROLE_ADMIN: {}", e.getMessage());
            throw e;
            
        } catch (Exception e) {
            log.error("Error executing admin action: option={}, error={}", 
                     request.getOptionNumber(), e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "ERROR");
            errorResponse.put("statusCode", "99");
            errorResponse.put("message", "Failed to execute admin action");
            errorResponse.put("error", e.getMessage());
            errorResponse.put("timestamp", LocalDateTime.now());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Inner class representing an admin action request.
     * 
     * <p>Defines the structure for admin action execution requests. This class is defined
     * locally because the DTO is not available in depends_on_files. It matches the structure
     * expected by AdminService.executeAdminFunction() method.</p>
     * 
     * <p><strong>COBOL Source Context:</strong></p>
     * <p>Maps to COBOL screen input fields from COADM01 mapset:</p>
     * <ul>
     *   <li>optionNumber → OPTIONI (user's menu selection)</li>
     *   <li>selectedOption → CDEMO-ADMIN-OPT-NAME(WS-OPTION)</li>
     *   <li>userId → CDEMO-USER-ID from COMMAREA</li>
     * </ul>
     */
    public static class AdminActionRequest {
        
        @NotNull(message = "Option number is required")
        @Min(value = 1, message = "Option number must be at least 1")
        @Max(value = 10, message = "Option number must not exceed 10")
        private Integer optionNumber;
        
        private String selectedOption;
        private String userId;
        private Map<String, String> parameters;

        /**
         * Gets the selected option number.
         * 
         * @return option number selected by admin user (1-4 for standard menu)
         */
        public Integer getOptionNumber() {
            return optionNumber;
        }

        /**
         * Sets the selected option number.
         * 
         * @param optionNumber option number to set
         */
        public void setOptionNumber(Integer optionNumber) {
            this.optionNumber = optionNumber;
        }

        /**
         * Gets the selected option name.
         * 
         * @return option name (e.g., "User List", "User Add")
         */
        public String getSelectedOption() {
            return selectedOption;
        }

        /**
         * Sets the selected option name.
         * 
         * @param selectedOption option name to set
         */
        public void setSelectedOption(String selectedOption) {
            this.selectedOption = selectedOption;
        }

        /**
         * Gets the user ID.
         * 
         * @return user ID of admin user executing the action
         */
        public String getUserId() {
            return userId;
        }

        /**
         * Sets the user ID.
         * 
         * @param userId user ID to set
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * Gets additional parameters.
         * 
         * @return map of additional parameters for the action
         */
        public Map<String, String> getParameters() {
            return parameters;
        }

        /**
         * Sets additional parameters.
         * 
         * @param parameters parameters to set
         */
        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }
    }

    /**
     * Inner class representing an admin request for service layer.
     * 
     * <p>Defines the structure matching AdminService.executeAdminFunction() parameter.
     * This class bridges the controller's AdminActionRequest to the service layer's
     * expected input format.</p>
     */
    public static class AdminRequest {
        
        private Integer optionNumber;
        private String selectedOption;
        private String userId;
        private Map<String, String> parameters;

        /**
         * Gets the option number.
         * 
         * @return selected option number
         */
        public Integer getOptionNumber() {
            return optionNumber;
        }

        /**
         * Sets the option number.
         * 
         * @param optionNumber option number to set
         */
        public void setOptionNumber(Integer optionNumber) {
            this.optionNumber = optionNumber;
        }

        /**
         * Gets the selected option.
         * 
         * @return selected option name
         */
        public String getSelectedOption() {
            return selectedOption;
        }

        /**
         * Sets the selected option.
         * 
         * @param selectedOption option name to set
         */
        public void setSelectedOption(String selectedOption) {
            this.selectedOption = selectedOption;
        }

        /**
         * Gets the user ID.
         * 
         * @return user ID
         */
        public String getUserId() {
            return userId;
        }

        /**
         * Sets the user ID.
         * 
         * @param userId user ID to set
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * Gets the parameters.
         * 
         * @return parameters map
         */
        public Map<String, String> getParameters() {
            return parameters;
        }

        /**
         * Sets the parameters.
         * 
         * @param parameters parameters to set
         */
        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }
    }
}

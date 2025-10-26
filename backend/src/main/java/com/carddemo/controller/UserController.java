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

import com.carddemo.model.dto.UserDto;
import com.carddemo.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User administration REST controller handling user CRUD operations.
 * 
 * Converted from COBOL programs:
 * - COUSR00C.cbl: User list display with pagination
 * - COUSR01C.cbl: User add function with field validation
 * - COUSR02C.cbl: User update function
 * - COUSR03C.cbl: User delete function
 * 
 * Original function:
 * These COBOL programs provided complete user management capabilities in the mainframe
 * CICS environment with BMS screens for CRUD operations on the USRSEC VSAM file.
 * 
 * Conversion notes:
 * - BMS COUSR00-03 maps → REST API JSON request/response
 * - EXEC CICS SEND/RECEIVE MAP → HTTP GET/POST/PUT/DELETE
 * - VSAM USRSEC I/O → Delegated to UserService + JPA repository
 * - COBOL field validation → @Valid Bean Validation
 * - RACF security → Spring Security @PreAuthorize with ROLE_ADMIN
 * 
 * Per Section 0.7.2: Must produce bit-identical results to COBOL programs.
 * Per Section 0.7.9: All endpoints require ADMIN role (RACF-equivalent access control).
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    /**
     * GET /api/users - Retrieve list of all users with optional filtering.
     * 
     * Converted from COBOL program: COUSR00C.cbl
     * Original COBOL operation:
     * <pre>
     * EXEC CICS STARTBR
     *      DATASET   ('USRSEC')
     *      RIDFLD    (WS-USER-ID)
     * END-EXEC.
     * 
     * PERFORM UNTIL END-OF-FILE
     *   EXEC CICS READNEXT
     *        DATASET   ('USRSEC')
     *        INTO      (SEC-USER-DATA)
     *   END-EXEC
     *   
     *   IF FILTER-TYPE-REQUESTED
     *      IF SEC-USR-TYPE = REQUESTED-TYPE
     *         Display user
     *      END-IF
     *   ELSE
     *      Display user
     *   END-IF
     * END-PERFORM.
     * </pre>
     * 
     * @param userType Optional user type filter ('A', 'U', or 'O')
     * @return List of users matching filter (HTTP 200)
     */
    @GetMapping
    public ResponseEntity<List<UserDto>> getAllUsers(
            @RequestParam(required = false) String userType) {
        log.debug("GET /api/users - Retrieving users with userType filter: {}", userType);
        List<UserDto> users;
        if (userType != null && !userType.isEmpty()) {
            users = userService.getUsersByType(userType);
        } else {
            users = userService.getAllUsers();
        }
        return ResponseEntity.ok(users);
    }

    /**
     * GET /api/users/{userId} - Retrieve single user by ID.
     * 
     * Converted from COBOL program: COUSR00C.cbl detail view
     * Original COBOL operation:
     * <pre>
     * EXEC CICS READ
     *      DATASET   ('USRSEC')
     *      INTO      (SEC-USER-DATA)
     *      RIDFLD    (WS-USER-ID)
     *      KEYLENGTH (8)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * 
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *    Display user details
     * ELSE
     *    Display error
     * END-IF.
     * </pre>
     * 
     * @param userId User ID to retrieve
     * @return User details (HTTP 200) or 404 if not found
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserDto> getUserById(@PathVariable String userId) {
        log.debug("GET /api/users/{} - Retrieving user by ID", userId);
        UserDto user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    /**
     * POST /api/users - Create new user.
     * 
     * Converted from COBOL program: COUSR01C.cbl
     * Original COBOL operation:
     * <pre>
     * PERFORM VALIDATE-USER-FIELDS.
     * 
     * IF NO-ERRORS
     *    EXEC CICS WRITE
     *         DATASET   ('USRSEC')
     *         FROM      (SEC-USER-DATA)
     *         RIDFLD    (SEC-USR-ID)
     *         KEYLENGTH (8)
     *         RESP      (WS-RESP-CD)
     *    END-EXEC
     *    
     *    IF WS-RESP-CD = DFHRESP(DUPREC)
     *       MOVE 'User ID already exists' TO ERRMSG
     *    END-IF
     * END-IF.
     * </pre>
     * 
     * @param userDto User data to create
     * @return Created user (HTTP 201) or 400/409 on error
     */
    @PostMapping
    public ResponseEntity<UserDto> createUser(@Valid @RequestBody UserDto userDto) {
        log.debug("POST /api/users - Creating new user: {}", userDto.getUserId());
        UserDto createdUser = userService.createUser(userDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    /**
     * PUT /api/users/{userId} - Update existing user.
     * 
     * Converted from COBOL program: COUSR02C.cbl
     * Original COBOL operation:
     * <pre>
     * EXEC CICS READ
     *      DATASET   ('USRSEC')
     *      INTO      (SEC-USER-DATA)
     *      RIDFLD    (SEC-USR-ID)
     *      UPDATE
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * 
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *    PERFORM UPDATE-USER-FIELDS
     *    EXEC CICS REWRITE
     *         DATASET   ('USRSEC')
     *         FROM      (SEC-USER-DATA)
     *    END-EXEC
     * END-IF.
     * </pre>
     * 
     * @param userId User ID to update
     * @param userDto Updated user data
     * @return Updated user (HTTP 200) or 404/400 on error
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserDto> updateUser(@PathVariable String userId, 
                                              @Valid @RequestBody UserDto userDto) {
        log.debug("PUT /api/users/{} - Updating user", userId);
        UserDto updatedUser = userService.updateUser(userId, userDto);
        return ResponseEntity.ok(updatedUser);
    }

    /**
     * DELETE /api/users/{userId} - Delete user.
     * 
     * Converted from COBOL program: COUSR03C.cbl
     * Original COBOL operation:
     * <pre>
     * EXEC CICS DELETE
     *      DATASET   ('USRSEC')
     *      RIDFLD    (SEC-USR-ID)
     *      KEYLENGTH (8)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * 
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *    MOVE 'User deleted successfully' TO SUCCESSMSG
     * END-IF.
     * </pre>
     * 
     * @param userId User ID to delete
     * @return No content (HTTP 204) or 404 if not found
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        log.debug("DELETE /api/users/{} - Deleting user", userId);
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }
}

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

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.model.dto.UserDto;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * User administration service providing CRUD operations for user management.
 * 
 * Converted from COBOL programs:
 * - COUSR00C.cbl: User list display with pagination
 * - COUSR01C.cbl: User add function with field validation
 * - COUSR02C.cbl: User update function
 * - COUSR03C.cbl: User delete function
 * 
 * Original function:
 * These COBOL programs provided complete user management capabilities in the mainframe
 * CICS environment, including CRUD operations on the USRSEC VSAM file, field-level
 * validation, and role-based access control through RACF security.
 * 
 * Conversion notes:
 * - VSAM USRSEC file I/O → PostgreSQL user_security table via JPA repository
 * - COBOL field validation → Bean Validation annotations in UserDto
 * - RACF security → Spring Security with BCrypt password hashing
 * - COBOL error handling → Java exceptions (DataNotFoundException, BusinessException)
 * 
 * Per Section 0.7.2: Must produce bit-identical results to COBOL programs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Retrieve all users from database.
     * 
     * Converted from COBOL program: COUSR00C.cbl
     * Original COBOL operation: Browse USRSEC file sequentially
     * 
     * @return List of all users as UserDto
     */
    @Transactional(readOnly = true)
    public List<UserDto> getAllUsers() {
        log.debug("Retrieving all users");
        return userSecurityRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Retrieve users filtered by user type.
     * 
     * Converted from COBOL program: COUSR00C.cbl with type filter
     * Original COBOL operation: Browse USRSEC file with conditional display
     * 
     * @param userType User type to filter by ('A', 'U', or 'O')
     * @return List of users matching the specified type
     */
    @Transactional(readOnly = true)
    public List<UserDto> getUsersByType(String userType) {
        log.debug("Retrieving users by type: {}", userType);
        return userSecurityRepository.findAll().stream()
                .filter(user -> userType.equals(user.getUserType()))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Retrieve single user by ID.
     * 
     * Converted from COBOL program: COUSR00C.cbl
     * Original COBOL operation: READ USRSEC by key
     * 
     * @param userId User ID to retrieve
     * @return User as UserDto
     * @throws DataNotFoundException if user not found (COBOL file-status 23)
     */
    @Transactional(readOnly = true)
    public UserDto getUserById(String userId) {
        log.debug("Retrieving user by ID: {}", userId);
        UserSecurity user = userSecurityRepository.findById(userId)
                .orElseThrow(() -> new DataNotFoundException("User not found: " + userId));
        return toDto(user);
    }

    /**
     * Create new user with password hashing.
     * 
     * Converted from COBOL program: COUSR01C.cbl
     * Original COBOL operation: WRITE to USRSEC file
     * 
     * @param userDto User data to create
     * @return Created user as UserDto
     * @throws BusinessException if user ID already exists (COBOL file-status 22)
     */
    @Transactional
    public UserDto createUser(UserDto userDto) {
        log.debug("Creating new user: {}", userDto.getUserId());
        
        // Validate required fields (COBOL VALIDATE-REQUIRED-FIELDS paragraph)
        if (userDto.getUserId() == null || userDto.getUserId().trim().isEmpty()) {
            throw new BusinessException("BUS001", "User ID is required");
        }
        if (userDto.getUserType() == null || userDto.getUserType().trim().isEmpty()) {
            throw new BusinessException("BUS001", "User type is required");
        }
        if (userDto.getPassword() == null || userDto.getPassword().trim().isEmpty()) {
            throw new BusinessException("BUS001", "Password is required");
        }
        
        // Validate user type (COBOL VALIDATE-USER-TYPE paragraph)
        if (!isValidUserType(userDto.getUserType())) {
            throw new BusinessException("BUS001", "Invalid user type: " + userDto.getUserType());
        }
        
        // Check for duplicate user ID (COBOL DUPREC condition)
        // Maps to DFHRESP(DUPREC) → HTTP 409 Conflict (BUS003 error code)
        if (userSecurityRepository.existsById(userDto.getUserId())) {
            throw new BusinessException("BUS003", "User ID already exists: " + userDto.getUserId());
        }
        
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        UserSecurity user = UserSecurity.builder()
                .userId(userDto.getUserId())
                .userFirstName(userDto.getUserFirstName())
                .userLastName(userDto.getUserLastName())
                .userType(userDto.getUserType())
                .userPwdHash(passwordEncoder.encode(userDto.getPassword()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        try {
            user = userSecurityRepository.save(user);
            log.info("User created successfully: {}", user.getUserId());
            return toDto(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException("BUS003", "User ID already exists: " + userDto.getUserId());
        }
    }

    /**
     * Update existing user.
     * 
     * Converted from COBOL program: COUSR02C.cbl
     * Original COBOL operation: READ + REWRITE USRSEC file
     * 
     * @param userId User ID to update
     * @param userDto Updated user data
     * @return Updated user as UserDto
     * @throws DataNotFoundException if user not found
     */
    @Transactional
    public UserDto updateUser(String userId, UserDto userDto) {
        log.debug("Updating user: {}", userId);
        
        UserSecurity user = userSecurityRepository.findById(userId)
                .orElseThrow(() -> new DataNotFoundException("User not found: " + userId));
        
        // Update fields
        if (userDto.getUserFirstName() != null) {
            user.setUserFirstName(userDto.getUserFirstName());
        }
        if (userDto.getUserLastName() != null) {
            user.setUserLastName(userDto.getUserLastName());
        }
        if (userDto.getUserType() != null) {
            // Validate user type
            if (!isValidUserType(userDto.getUserType())) {
                throw new BusinessException("Invalid user type: " + userDto.getUserType());
            }
            user.setUserType(userDto.getUserType());
        }
        if (userDto.getPassword() != null && !userDto.getPassword().isEmpty()) {
            user.setUserPwdHash(passwordEncoder.encode(userDto.getPassword()));
        }
        
        user.setUpdatedAt(Timestamp.valueOf(LocalDateTime.now()));
        
        user = userSecurityRepository.save(user);
        log.info("User updated successfully: {}", user.getUserId());
        return toDto(user);
    }

    /**
     * Delete user.
     * 
     * Converted from COBOL program: COUSR03C.cbl
     * Original COBOL operation: DELETE from USRSEC file
     * 
     * @param userId User ID to delete
     * @throws DataNotFoundException if user not found
     */
    @Transactional
    public void deleteUser(String userId) {
        log.debug("Deleting user: {}", userId);
        
        if (!userSecurityRepository.existsById(userId)) {
            throw new DataNotFoundException("User not found: " + userId);
        }
        
        userSecurityRepository.deleteById(userId);
        log.info("User deleted successfully: {}", userId);
    }

    /**
     * Convert UserSecurity entity to UserDto (excluding password hash).
     * 
     * @param user UserSecurity entity
     * @return UserDto
     */
    private UserDto toDto(UserSecurity user) {
        return UserDto.builder()
                .userId(user.getUserId())
                .userFirstName(user.getUserFirstName())
                .userLastName(user.getUserLastName())
                .userType(user.getUserType())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(user.getUpdatedAt() != null ? user.getUpdatedAt().toLocalDateTime() : null)
                .lastLoginTs(user.getLastLoginTs() != null ? user.getLastLoginTs().toLocalDateTime() : null)
                .build();
    }

    /**
     * Validate user type matches COBOL allowed values.
     * 
     * Original COBOL validation:
     * IF SEC-USR-TYPE NOT = 'A' AND
     *    SEC-USR-TYPE NOT = 'U' AND
     *    SEC-USR-TYPE NOT = 'O'
     *    SET ERROR-FOUND TO TRUE
     * 
     * @param userType User type to validate
     * @return true if valid ('A', 'U', or 'O'), false otherwise
     */
    private boolean isValidUserType(String userType) {
        return "A".equals(userType) || "U".equals(userType) || "O".equals(userType);
    }
}

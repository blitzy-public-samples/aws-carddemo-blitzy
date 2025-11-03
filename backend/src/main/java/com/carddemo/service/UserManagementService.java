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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.service;

import com.carddemo.dto.request.UserManagementRequest;
import com.carddemo.dto.response.UserProfileResponse;
import com.carddemo.entity.UserSecurity;
import com.carddemo.exception.UserAlreadyExistsException;
import com.carddemo.exception.UserNotFoundException;
import com.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service class for comprehensive user CRUD operations and management functionality.
 * 
 * <p>Transformed from three COBOL CICS transaction programs:</p>
 * <ul>
 *   <li>COUSR00C.cbl - User list with pagination (10 users per page)</li>
 *   <li>COUSR02C.cbl - User add/update operations</li>
 *   <li>COUSR03C.cbl - User delete operations</li>
 * </ul>
 * 
 * <p><strong>COBOL Source Mapping:</strong></p>
 * <pre>
 * COUSR00C.cbl (User List):
 * - Transaction: CU00
 * - Operations: EXEC CICS STARTBR/READNEXT (sequential browse)
 * - PF7: Backward page navigation
 * - PF8: Forward page navigation
 * - Selection: 'U' for update, 'D' for delete
 * - Display: 10 users per screen page
 * 
 * COUSR02C.cbl (User Update):
 * - Transaction: CU02
 * - Operations: EXEC CICS READ UPDATE / EXEC CICS REWRITE
 * - Validation: userId, firstName, lastName, password, userType required
 * - Change detection: Only update if fields modified
 * - Password: Plain text 8 characters (COBOL) → BCrypt 60 characters (Java)
 * 
 * COUSR03C.cbl (User Delete):
 * - Transaction: CU03
 * - Operations: EXEC CICS READ UPDATE / EXEC CICS DELETE
 * - Validation: userId required
 * - Confirmation: PF5 to confirm deletion
 * </pre>
 * 
 * <p><strong>Security Requirements (Section 0.9):</strong></p>
 * <ul>
 *   <li>ALL methods require ROLE_ADMIN authorization via @PreAuthorize</li>
 *   <li>Password encryption using BCrypt with strength 12</li>
 *   <li>User type 'R' (Regular) and 'A' (Admin) preserved from COBOL</li>
 *   <li>No password exposure in response DTOs</li>
 * </ul>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <ul>
 *   <li>Isolation: READ_COMMITTED (CICS default equivalent)</li>
 *   <li>Propagation: REQUIRED (join existing or create new transaction)</li>
 *   <li>Rollback: Automatic on any RuntimeException</li>
 *   <li>CICS SYNCPOINT equivalent: @Transactional boundary</li>
 * </ul>
 * 
 * <p><strong>Performance Targets (Section 0.9):</strong></p>
 * <ul>
 *   <li>User lookup: < 100ms average response time</li>
 *   <li>User list with pagination: < 200ms for 10 records</li>
 *   <li>User create/update/delete: < 200ms with BCrypt encryption</li>
 *   <li>Concurrent user support: 150+ users minimum</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see UserSecurity
 * @see UserSecurityRepository
 * @see UserManagementRequest
 * @see UserProfileResponse
 */
@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructor-based dependency injection for UserManagementService.
     * 
     * <p>Replaces COBOL CALL statements with Spring IoC container managed dependencies.
     * Constructor injection ensures immutable dependencies and facilitates unit testing.</p>
     * 
     * @param userSecurityRepository JPA repository for user data access
     * @param passwordEncoder BCrypt password encoder for secure password hashing
     */
    @Autowired
    public UserManagementService(
            UserSecurityRepository userSecurityRepository,
            PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }


    /**
     * Creates a new user with BCrypt encrypted password.
     * 
     * <p>Transforms COUSR02C.cbl user creation logic with enhanced security</p>
     * 
     * @param request user creation request containing userId, firstName, lastName, password, userType
     * @return UserProfileResponse with created user details (password excluded)
     * @throws UserAlreadyExistsException if userId already exists
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    public UserProfileResponse createUser(UserManagementRequest request) {
        log.info("Creating new user with ID: {}", request.getUserId());
        
        String userId = request.getUserId();
        String firstName = request.getFirstName();
        String lastName = request.getLastName();
        String password = request.getPassword();
        String userType = request.getUserType();
        
        if (userSecurityRepository.existsByUserId(userId)) {
            log.warn("User creation failed: User ID '{}' already exists", userId);
            throw new UserAlreadyExistsException(
                "Cannot create user: User ID already exists in the system", userId);
        }
        
        UserSecurity newUser = new UserSecurity();
        newUser.setUserId(userId);
        newUser.setFirstName(firstName);
        newUser.setLastName(lastName);
        
        String hashedPassword = passwordEncoder.encode(password);
        newUser.setPassword(hashedPassword);
        log.debug("Password encrypted using BCrypt for user: {}", userId);
        
        newUser.setUserType(userType);
        
        UserSecurity savedUser = userSecurityRepository.save(newUser);
        log.info("User created successfully: {}", userId);
        
        return convertToUserProfileResponse(savedUser);
    }

    /**
     * Updates an existing user with optional password change.
     * 
     * @param userId user ID to update (must exist)
     * @param request update request containing modified fields
     * @return UserProfileResponse with updated user details
     * @throws UserNotFoundException if user does not exist
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    public UserProfileResponse updateUser(String userId, UserManagementRequest request) {
        log.info("Updating user with ID: {}", userId);
        
        UserSecurity existingUser = userSecurityRepository.findByUserId(userId)
            .orElseThrow(() -> {
                log.warn("User update failed: User ID '{}' not found", userId);
                return new UserNotFoundException(
                    "Cannot update user: User not found in the system", userId);
            });
        
        boolean modified = false;
        
        if (request.getFirstName() != null && 
            !request.getFirstName().equals(existingUser.getFirstName())) {
            existingUser.setFirstName(request.getFirstName());
            modified = true;
            log.debug("First name updated for user: {}", userId);
        }
        
        if (request.getLastName() != null && 
            !request.getLastName().equals(existingUser.getLastName())) {
            existingUser.setLastName(request.getLastName());
            modified = true;
            log.debug("Last name updated for user: {}", userId);
        }
        
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            String hashedPassword = passwordEncoder.encode(request.getPassword());
            existingUser.setPassword(hashedPassword);
            modified = true;
            log.debug("Password updated and re-encrypted for user: {}", userId);
        }
        
        if (request.getUserType() != null && 
            !request.getUserType().equals(existingUser.getUserType())) {
            existingUser.setUserType(request.getUserType());
            modified = true;
            log.debug("User type updated for user: {}", userId);
        }
        
        if (modified) {
            UserSecurity updatedUser = userSecurityRepository.save(existingUser);
            log.info("User updated successfully: {}", userId);
            return convertToUserProfileResponse(updatedUser);
        } else {
            log.info("No changes detected for user: {}", userId);
            return convertToUserProfileResponse(existingUser);
        }
    }

    /**
     * Deletes a user by user ID.
     * 
     * @param userId user ID to delete (must exist)
     * @throws UserNotFoundException if user does not exist
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    public void deleteUser(String userId) {
        log.info("Deleting user with ID: {}", userId);
        
        if (!userSecurityRepository.existsByUserId(userId)) {
            log.warn("User deletion failed: User ID '{}' not found", userId);
            throw new UserNotFoundException(
                "Cannot delete user: User not found in the system", userId);
        }
        
        userSecurityRepository.deleteById(userId);
        log.info("User deleted successfully: {}", userId);
    }

    /**
     * Retrieves paginated list of users.
     * 
     * @param pageable pagination parameters (page number, size, sort)
     * @return Page of UserProfileResponse objects (passwords excluded)
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listUsers(Pageable pageable) {
        log.info("Retrieving user list: page={}, size={}", 
            pageable.getPageNumber(), pageable.getPageSize());
        
        Page<UserSecurity> userPage = userSecurityRepository.findAll(pageable);
        
        log.debug("Retrieved {} users from page {} of {}",
            userPage.getNumberOfElements(), 
            userPage.getNumber() + 1,
            userPage.getTotalPages());
        
        Page<UserProfileResponse> responsePage = userPage.map(this::convertToUserProfileResponse);
        
        return responsePage;
    }

    /**
     * Retrieves a single user by user ID.
     * 
     * @param userId user ID to retrieve (must exist)
     * @return UserProfileResponse with user details (password excluded)
     * @throws UserNotFoundException if user does not exist
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public UserProfileResponse getUserById(String userId) {
        log.info("Retrieving user by ID: {}", userId);
        
        UserSecurity user = userSecurityRepository.findByUserId(userId)
            .orElseThrow(() -> {
                log.warn("User lookup failed: User ID '{}' not found", userId);
                return new UserNotFoundException(
                    "User not found with the specified user ID", userId);
            });
        
        log.debug("User retrieved successfully: {}", userId);
        return convertToUserProfileResponse(user);
    }

    /**
     * Searches users by partial user ID match with pagination.
     * 
     * @param searchUserId partial user ID to search for (null returns all users)
     * @param pageable pagination parameters
     * @return Page of matching UserProfileResponse objects
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> searchUsers(String searchUserId, Pageable pageable) {
        log.info("Searching users with partial ID: '{}', page={}, size={}",
            searchUserId, pageable.getPageNumber(), pageable.getPageSize());
        
        Page<UserSecurity> userPage;
        
        if (searchUserId == null || searchUserId.trim().isEmpty()) {
            userPage = userSecurityRepository.findAll(pageable);
        } else {
            List<UserSecurity> allUsers = userSecurityRepository.findAll();
            List<UserSecurity> filteredUsers = allUsers.stream()
                .filter(user -> user.getUserId().toUpperCase()
                    .contains(searchUserId.toUpperCase()))
                .collect(Collectors.toList());
            
            log.debug("Found {} users matching search criteria", filteredUsers.size());
            
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), filteredUsers.size());
            List<UserSecurity> pageContent = filteredUsers.subList(start, end);
            
            userPage = new org.springframework.data.domain.PageImpl<>(
                pageContent, pageable, filteredUsers.size());
        }
        
        return userPage.map(this::convertToUserProfileResponse);
    }

    /**
     * Changes a user's password with BCrypt encryption.
     * 
     * @param userId user ID whose password to change
     * @param oldPassword current password for verification
     * @param newPassword new password to set (will be BCrypt hashed)
     * @throws UserNotFoundException if user does not exist
     * @throws IllegalArgumentException if old password doesn't match
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    public void changePassword(String userId, String oldPassword, String newPassword) {
        log.info("Changing password for user: {}", userId);
        
        UserSecurity user = userSecurityRepository.findByUserId(userId)
            .orElseThrow(() -> {
                log.warn("Password change failed: User ID '{}' not found", userId);
                return new UserNotFoundException(
                    "Cannot change password: User not found", userId);
            });
        
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            log.warn("Password change failed: Incorrect old password for user '{}'", userId);
            throw new IllegalArgumentException("Old password is incorrect");
        }
        
        String hashedPassword = passwordEncoder.encode(newPassword);
        user.setPassword(hashedPassword);
        
        userSecurityRepository.save(user);
        log.info("Password changed successfully for user: {}", userId);
    }

    /**
     * Converts UserSecurity entity to UserProfileResponse DTO.
     * 
     * @param user UserSecurity entity to convert
     * @return UserProfileResponse DTO (password excluded)
     */
    private UserProfileResponse convertToUserProfileResponse(UserSecurity user) {
        UserProfileResponse response = new UserProfileResponse();
        response.setUserId(user.getUserId());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setUserType(user.getUserType());
        return response;
    }
}

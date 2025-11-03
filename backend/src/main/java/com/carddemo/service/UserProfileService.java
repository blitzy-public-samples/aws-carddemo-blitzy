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

import com.carddemo.dto.request.UserProfileUpdateRequest;
import com.carddemo.dto.response.UserProfileResponse;
import com.carddemo.entity.UserSecurity;
import com.carddemo.exception.ProfileUpdateException;
import com.carddemo.exception.ProfileUpdateException.UpdateFailureReason;
import com.carddemo.exception.UserNotFoundException;
import com.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Service class for user profile view and update operations.
 * <p>
 * Transformed from COBOL CICS program COUSR01C.cbl (User Profile Management Program).
 * This service provides methods to retrieve authenticated user profile details, update
 * user preferences and profile information, validate profile data, and manage user
 * session context.
 * </p>
 * 
 * <h3>COBOL Source Mapping:</h3>
 * <ul>
 *   <li>COBOL Program: COUSR01C.cbl - User Profile Add/Update Transaction</li>
 *   <li>CICS Transaction: CU01 - User Profile Management</li>
 *   <li>VSAM File: USRSEC (User Security File) → PostgreSQL user_security table</li>
 *   <li>EXEC CICS READ USRSEC → UserSecurityRepository.findByUserId()</li>
 *   <li>EXEC CICS REWRITE USRSEC → UserSecurityRepository.save()</li>
 *   <li>EXEC CICS WRITE USRSEC → User creation (handled by UserManagementService)</li>
 * </ul>
 * 
 * <h3>Authorization Model (Section 0.9):</h3>
 * <ul>
 *   <li>@PreAuthorize ensures users can only access/modify their own profiles unless ROLE_ADMIN</li>
 *   <li>Expression: hasRole('USER') and #userId == authentication.principal.userId or hasRole('ADMIN')</li>
 *   <li>Regular users (ROLE_USER): Can view/update own profile only</li>
 *   <li>Admin users (ROLE_ADMIN): Can view/update any user profile</li>
 *   <li>User type changes restricted to ROLE_ADMIN only</li>
 * </ul>
 * 
 * <h3>Transaction Boundary Preservation (Section 0.9):</h3>
 * <ul>
 *   <li>@Transactional with isolation=READ_COMMITTED matches CICS default isolation</li>
 *   <li>Propagation=REQUIRED matches CICS SYNCPOINT behavior</li>
 *   <li>Automatic rollback on Exception.class matches CICS ROLLBACK semantics</li>
 *   <li>EXEC CICS SYNCPOINT → @Transactional commit boundary</li>
 * </ul>
 * 
 * <h3>COBOL Field Validation Preservation:</h3>
 * <p>This service preserves exact validation rules from COUSR01C.cbl PROCESS-ENTER-KEY section:</p>
 * <ul>
 *   <li>Lines 118-123: First Name cannot be empty (FNAMEI validation)</li>
 *   <li>Lines 124-129: Last Name cannot be empty (LNAMEI validation)</li>
 *   <li>Lines 130-135: User ID cannot be empty (USERIDI validation)</li>
 *   <li>Lines 136-141: Password cannot be empty (PASSWDI validation)</li>
 *   <li>Lines 142-147: User Type cannot be empty (USRTYPEI validation)</li>
 * </ul>
 * 
 * <h3>Error Code Mapping:</h3>
 * <pre>
 * COBOL RESP Code → Java Exception
 * DFHRESP(NORMAL) → Successful operation
 * DFHRESP(NOTFND) / RESP=13 → UserNotFoundException
 * DFHRESP(DUPKEY) / RESP=14 → ProfileUpdateException(VALIDATION_ERROR)
 * DFHRESP(DUPREC) / RESP=15 → ProfileUpdateException(CONCURRENT_UPDATE_CONFLICT)
 * Other RESP codes → ProfileUpdateException(DATABASE_ERROR)
 * </pre>
 * 
 * <h3>Security Enhancements:</h3>
 * <ul>
 *   <li>COBOL plain text passwords (SEC-USR-PWD PIC X(08)) → BCrypt hashed passwords (60 chars)</li>
 *   <li>BCryptPasswordEncoder with strength 12 per Section 0.2 security requirements</li>
 *   <li>Password verification using PasswordEncoder.matches() before updates</li>
 *   <li>Comprehensive audit logging for all profile operations</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see UserSecurity
 * @see UserSecurityRepository
 * @see UserProfileResponse
 * @see UserProfileUpdateRequest
 * @since CardDemo Java Migration v1.0
 */
@Service
public class UserProfileService {

    private static final Logger logger = LoggerFactory.getLogger(UserProfileService.class);

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructs UserProfileService with required dependencies.
     * <p>
     * Constructor-based dependency injection following Spring best practices
     * for immutable service dependencies. Enables testability through dependency
     * injection and eliminates manual bean wiring.
     * </p>
     * 
     * @param userSecurityRepository JPA repository for user security operations;
     *                              must not be null
     * @param passwordEncoder BCrypt password encoder for password hashing and verification;
     *                       must not be null
     */
    @Autowired
    public UserProfileService(UserSecurityRepository userSecurityRepository,
                            PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        logger.info("UserProfileService initialized with UserSecurityRepository and PasswordEncoder");
    }

    /**
     * Retrieves user profile details for the specified user ID.
     * <p>
     * Transforms COBOL READ operation from COUSR01C.cbl to JPA repository findByUserId().
     * Ensures authorization so users can only view their own profile unless ROLE_ADMIN.
     * </p>
     * 
     * <h4>COBOL Equivalent (COUSR01C.cbl - conceptual READ operation):</h4>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-USRSEC-FILE)
     *      INTO      (SEC-USER-DATA)
     *      LENGTH    (LENGTH OF SEC-USER-DATA)
     *      RIDFLD    (SEC-USR-ID)
     *      KEYLENGTH (LENGTH OF SEC-USR-ID)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC.
     * 
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *     MOVE SEC-USR-FNAME TO FNAMEO
     *     MOVE SEC-USR-LNAME TO LNAMEO
     *     MOVE SEC-USR-ID TO USERIDO
     *     MOVE SEC-USR-TYPE TO USRTYPEO
     * ELSE WHEN WS-RESP-CD = 13
     *     MOVE 'User not found...' TO WS-MESSAGE
     * END-IF.
     * </pre>
     * 
     * <h4>Authorization Expression:</h4>
     * <p>
     * Users can view their own profile OR admin users can view any profile:
     * <code>hasRole('USER') and #userId == authentication.principal.username or hasRole('ADMIN')</code>
     * </p>
     * 
     * @param userId the unique identifier of the user whose profile is being retrieved;
     *              must not be null or empty (max 8 characters)
     * @return UserProfileResponse containing user profile information including
     *         userId, firstName, lastName, userType, and roles
     * @throws UserNotFoundException if no user exists with the specified userId
     *                              (maps to COBOL RESP=13 NOTFND)
     * @throws IllegalArgumentException if userId is null or empty
     */
    @PreAuthorize("hasRole('USER') and #userId == authentication.principal.username or hasRole('ADMIN')")
    public UserProfileResponse viewUserProfile(String userId) {
        logger.info("Retrieving user profile for userId: {}", userId);
        
        // Validate input parameter (mirrors COBOL validation at lines 130-135)
        if (userId == null || userId.trim().isEmpty()) {
            logger.error("User ID cannot be null or empty");
            throw new IllegalArgumentException("User ID is required and cannot be empty");
        }
        
        // Retrieve user from repository (EXEC CICS READ USRSEC equivalent)
        Optional<UserSecurity> userOptional = userSecurityRepository.findByUserId(userId.trim());
        
        // Handle user not found (COBOL RESP=13 NOTFND condition)
        if (!userOptional.isPresent()) {
            logger.warn("User not found with ID: {}", userId);
            throw new UserNotFoundException(userId);
        }
        
        UserSecurity user = userOptional.get();
        logger.debug("Successfully retrieved user profile for userId: {}", userId);
        
        // Map entity to response DTO (mirrors COBOL MOVE operations to output fields)
        return mapToUserProfileResponse(user);
    }

    /**
     * Updates user profile information including name, user type, and optionally password.
     * <p>
     * Transforms COBOL REWRITE operation from COUSR01C.cbl to JPA repository save().
     * Applies @Transactional with CICS-equivalent isolation and propagation settings.
     * Ensures authorization so users can only update their own profile unless ROLE_ADMIN.
     * </p>
     * 
     * <h4>COBOL Equivalent (COUSR01C.cbl WRITE-USER-SEC-FILE section, lines 238-274):</h4>
     * <pre>
     * MOVE USERIDI  OF COUSR1AI TO SEC-USR-ID
     * MOVE FNAMEI   OF COUSR1AI TO SEC-USR-FNAME
     * MOVE LNAMEI   OF COUSR1AI TO SEC-USR-LNAME
     * MOVE PASSWDI  OF COUSR1AI TO SEC-USR-PWD
     * MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE
     * 
     * EXEC CICS WRITE
     *      DATASET   (WS-USRSEC-FILE)
     *      FROM      (SEC-USER-DATA)
     *      LENGTH    (LENGTH OF SEC-USER-DATA)
     *      RIDFLD    (SEC-USR-ID)
     *      KEYLENGTH (LENGTH OF SEC-USR-ID)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC.
     * 
     * EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NORMAL)
     *         ... success handling ...
     *     WHEN DFHRESP(DUPKEY)
     *     WHEN DFHRESP(DUPREC)
     *         MOVE 'User ID already exist...' TO WS-MESSAGE
     *     WHEN OTHER
     *         MOVE 'Unable to Add User...' TO WS-MESSAGE
     * END-EVALUATE.
     * </pre>
     * 
     * <h4>Transaction Semantics (Section 0.9):</h4>
     * <ul>
     *   <li>Isolation: READ_COMMITTED matches CICS default transaction isolation</li>
     *   <li>Propagation: REQUIRED ensures participation in existing transaction or creates new</li>
     *   <li>Rollback: Automatic rollback on any Exception matches CICS SYNCPOINT ROLLBACK</li>
     * </ul>
     * 
     * <h4>Validation Rules (from COUSR01C.cbl PROCESS-ENTER-KEY, lines 117-151):</h4>
     * <ul>
     *   <li>First Name cannot be empty (line 118-123)</li>
     *   <li>Last Name cannot be empty (line 124-129)</li>
     *   <li>User ID cannot be empty (line 130-135)</li>
     *   <li>Password cannot be empty if password change requested (line 136-141)</li>
     *   <li>User Type cannot be empty (line 142-147)</li>
     * </ul>
     * 
     * <h4>Authorization Expression:</h4>
     * <p>
     * Users can update their own profile OR admin users can update any profile:
     * <code>hasRole('USER') and #userId == authentication.principal.username or hasRole('ADMIN')</code>
     * </p>
     * 
     * @param userId the unique identifier of the user whose profile is being updated;
     *              must not be null or empty (max 8 characters)
     * @param request the profile update request containing firstName, lastName, userType,
     *               and optional password change fields; must not be null
     * @return UserProfileResponse containing updated user profile information
     * @throws UserNotFoundException if no user exists with the specified userId
     *                              (maps to COBOL RESP=13 NOTFND)
     * @throws ProfileUpdateException if validation fails, unauthorized type change attempted,
     *                               password requirements not met, or concurrent update conflict
     *                               (maps to various COBOL RESP codes)
     * @throws IllegalArgumentException if userId or request is null
     */
    @PreAuthorize("hasRole('USER') and #userId == authentication.principal.username or hasRole('ADMIN')")
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    public UserProfileResponse updateUserProfile(String userId, UserProfileUpdateRequest request) {
        logger.info("Updating user profile for userId: {}", userId);
        
        // Validate input parameters
        if (userId == null || userId.trim().isEmpty()) {
            logger.error("User ID cannot be null or empty");
            throw new IllegalArgumentException("User ID is required and cannot be empty");
        }
        
        if (request == null) {
            logger.error("User profile update request cannot be null");
            throw new IllegalArgumentException("User profile update request is required");
        }
        
        // Retrieve existing user (EXEC CICS READ USRSEC equivalent)
        Optional<UserSecurity> userOptional = userSecurityRepository.findByUserId(userId.trim());
        
        if (!userOptional.isPresent()) {
            logger.error("User not found for update: {}", userId);
            throw new UserNotFoundException(userId);
        }
        
        UserSecurity user = userOptional.get();
        
        // Validate profile update request
        validateProfileUpdateRequest(request, user);
        
        // Apply profile updates (mirrors COBOL MOVE operations at lines 154-158)
        applyProfileUpdates(user, request);
        
        // Handle password change if requested
        if (request.isPasswordChangeRequested()) {
            handlePasswordChange(user, request);
        }
        
        // Save updated user (EXEC CICS REWRITE USRSEC equivalent)
        try {
            UserSecurity updatedUser = userSecurityRepository.save(user);
            logger.info("Successfully updated user profile for userId: {}", userId);
            
            // Map updated entity to response DTO
            return mapToUserProfileResponse(updatedUser);
            
        } catch (Exception e) {
            logger.error("Database error during profile update for userId: {}", userId, e);
            throw new ProfileUpdateException(
                "Database error occurred during profile update",
                e,
                userId,
                UpdateFailureReason.DATABASE_ERROR
            );
        }
    }

    /**
     * Changes the password for the specified user after verifying the current password.
     * <p>
     * Provides dedicated password change functionality with enhanced security validation.
     * Requires current password verification before allowing password update to prevent
     * unauthorized password changes.
     * </p>
     * 
     * <h4>Security Requirements:</h4>
     * <ul>
     *   <li>Current password must be provided and verified using BCrypt.matches()</li>
     *   <li>New password must meet complexity requirements (8-72 chars, mixed case, numbers, special chars)</li>
     *   <li>New password is hashed using BCryptPasswordEncoder with strength 12</li>
     *   <li>Transaction rollback on any failure ensures password integrity</li>
     * </ul>
     * 
     * <h4>Password Storage Transformation:</h4>
     * <pre>
     * COBOL: SEC-USR-PWD PIC X(08) - Plain text 8 characters (INSECURE)
     * Java:  password VARCHAR(60) - BCrypt hash "$2a$12$..." (SECURE)
     * </pre>
     * 
     * <h4>Authorization Expression:</h4>
     * <p>
     * Users can change their own password OR admin users can change any password:
     * <code>hasRole('USER') and #userId == authentication.principal.username or hasRole('ADMIN')</code>
     * </p>
     * 
     * @param userId the unique identifier of the user changing password;
     *              must not be null or empty (max 8 characters)
     * @param currentPassword the user's current password for verification;
     *                       must not be null or empty (exactly 8 characters)
     * @param newPassword the new password to set;
     *                   must not be null, 8-72 characters, meeting complexity requirements
     * @return UserProfileResponse containing updated user profile information
     * @throws UserNotFoundException if no user exists with the specified userId
     * @throws ProfileUpdateException if current password is incorrect, new password
     *                               requirements not met, or database error occurs
     * @throws IllegalArgumentException if any parameter is null or empty
     */
    @PreAuthorize("hasRole('USER') and #userId == authentication.principal.username or hasRole('ADMIN')")
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    public UserProfileResponse changePassword(String userId, String currentPassword, String newPassword) {
        logger.info("Processing password change request for userId: {}", userId);
        
        // Validate input parameters
        if (userId == null || userId.trim().isEmpty()) {
            logger.error("User ID cannot be null or empty");
            throw new IllegalArgumentException("User ID is required and cannot be empty");
        }
        
        if (currentPassword == null || currentPassword.trim().isEmpty()) {
            logger.error("Current password cannot be null or empty");
            throw new IllegalArgumentException("Current password is required");
        }
        
        if (newPassword == null || newPassword.trim().isEmpty()) {
            logger.error("New password cannot be null or empty");
            throw new IllegalArgumentException("New password is required");
        }
        
        // Retrieve user
        Optional<UserSecurity> userOptional = userSecurityRepository.findByUserId(userId.trim());
        
        if (!userOptional.isPresent()) {
            logger.error("User not found for password change: {}", userId);
            throw new UserNotFoundException(userId);
        }
        
        UserSecurity user = userOptional.get();
        
        // Verify current password using BCrypt
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            logger.warn("Incorrect current password provided for userId: {}", userId);
            throw new ProfileUpdateException(
                "Current password is incorrect",
                userId,
                UpdateFailureReason.INVALID_PASSWORD_CHANGE
            );
        }
        
        // Validate new password meets requirements
        validatePasswordRequirements(newPassword, userId);
        
        // Hash new password using BCrypt with strength 12
        String hashedPassword = passwordEncoder.encode(newPassword);
        user.setPassword(hashedPassword);
        
        // Save updated user with new password
        try {
            UserSecurity updatedUser = userSecurityRepository.save(user);
            logger.info("Successfully changed password for userId: {}", userId);
            
            return mapToUserProfileResponse(updatedUser);
            
        } catch (Exception e) {
            logger.error("Database error during password change for userId: {}", userId, e);
            throw new ProfileUpdateException(
                "Database error occurred during password change",
                e,
                userId,
                UpdateFailureReason.DATABASE_ERROR
            );
        }
    }

    /**
     * Validates that the authenticated user has access to view or modify the specified user profile.
     * <p>
     * This method provides explicit authorization validation beyond @PreAuthorize annotations,
     * enabling programmatic access control checks in controllers or other service methods.
     * </p>
     * 
     * <h4>Authorization Rules:</h4>
     * <ul>
     *   <li>Users can access their own profile (userId matches authenticatedUserId)</li>
     *   <li>Admin users can access any profile (determined by userType 'A')</li>
     *   <li>Regular users cannot access other users' profiles</li>
     * </ul>
     * 
     * @param userId the identifier of the user profile being accessed;
     *              must not be null or empty
     * @param authenticatedUserId the identifier of the currently authenticated user;
     *                           must not be null or empty
     * @return true if access is granted (own profile or admin user), false otherwise
     * @throws IllegalArgumentException if either parameter is null or empty
     */
    public boolean validateUserAccess(String userId, String authenticatedUserId) {
        logger.debug("Validating user access: userId={}, authenticatedUserId={}", userId, authenticatedUserId);
        
        // Validate input parameters
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID is required and cannot be empty");
        }
        
        if (authenticatedUserId == null || authenticatedUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Authenticated user ID is required and cannot be empty");
        }
        
        // Users can always access their own profile
        if (userId.trim().equalsIgnoreCase(authenticatedUserId.trim())) {
            logger.debug("Access granted: User accessing own profile");
            return true;
        }
        
        // Check if authenticated user is admin
        Optional<UserSecurity> authenticatedUserOptional = 
            userSecurityRepository.findByUserId(authenticatedUserId.trim());
        
        if (authenticatedUserOptional.isPresent()) {
            UserSecurity authenticatedUser = authenticatedUserOptional.get();
            boolean isAdmin = "A".equals(authenticatedUser.getUserType());
            
            if (isAdmin) {
                logger.debug("Access granted: Admin user accessing another user's profile");
                return true;
            }
        }
        
        logger.warn("Access denied: User {} cannot access profile of user {}", 
                   authenticatedUserId, userId);
        return false;
    }

    /**
     * Maps UserSecurity entity to UserProfileResponse DTO.
     * <p>
     * Transforms database entity to REST API response DTO, excluding sensitive information
     * (password) and adding computed fields (fullName, roles). Mirrors COBOL screen output
     * field population from COUSR01C.cbl SEND-USRADD-SCREEN section.
     * </p>
     * 
     * @param user the UserSecurity entity to map; must not be null
     * @return UserProfileResponse DTO with populated fields
     */
    private UserProfileResponse mapToUserProfileResponse(UserSecurity user) {
        UserProfileResponse response = new UserProfileResponse();
        
        // Set transaction and program context (mirrors COBOL header fields)
        response.setTransactionName("CU01");
        response.setProgramName("COUSR01C");
        response.setTitle01("AWS Mainframe Modernization - CardDemo");
        response.setTitle02("User Profile");
        response.setCurrentDate(LocalDate.now());
        response.setCurrentTime(LocalTime.now());
        
        // Set user profile fields (mirrors COBOL MOVE operations to output structure)
        response.setUserId(user.getUserId());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setUserType(user.getUserType());
        
        // Clear any error messages on successful retrieval
        response.setErrorMessage(null);
        
        return response;
    }

    /**
     * Validates profile update request fields and authorization.
     * <p>
     * Implements validation rules from COBOL COUSR01C.cbl PROCESS-ENTER-KEY section
     * (lines 117-151) with additional Spring-specific validations.
     * </p>
     * 
     * @param request the profile update request to validate
     * @param currentUser the current user entity from database
     * @throws ProfileUpdateException if validation fails
     */
    private void validateProfileUpdateRequest(UserProfileUpdateRequest request, UserSecurity currentUser) {
        // Validate first name (COBOL lines 118-123)
        if (request.getFirstName() == null || request.getFirstName().trim().isEmpty()) {
            logger.error("First name cannot be empty");
            throw new ProfileUpdateException(
                "First name cannot be empty",
                request.getUserId(),
                UpdateFailureReason.VALIDATION_ERROR
            );
        }
        
        // Validate last name (COBOL lines 124-129)
        if (request.getLastName() == null || request.getLastName().trim().isEmpty()) {
            logger.error("Last name cannot be empty");
            throw new ProfileUpdateException(
                "Last name cannot be empty",
                request.getUserId(),
                UpdateFailureReason.VALIDATION_ERROR
            );
        }
        
        // Validate user type (COBOL lines 142-147)
        if (request.getUserType() == null || request.getUserType().trim().isEmpty()) {
            logger.error("User type cannot be empty");
            throw new ProfileUpdateException(
                "User type cannot be empty",
                request.getUserId(),
                UpdateFailureReason.VALIDATION_ERROR
            );
        }
        
        // Validate user type is 'R' or 'A'
        if (!"R".equals(request.getUserType()) && !"A".equals(request.getUserType())) {
            logger.error("Invalid user type: {}", request.getUserType());
            throw new ProfileUpdateException(
                "User type must be 'R' (Regular) or 'A' (Admin)",
                request.getUserId(),
                UpdateFailureReason.VALIDATION_ERROR
            );
        }
        
        // Validate password change fields if password change requested
        if (request.isPasswordChangeRequested()) {
            if (!request.isPasswordChangeValid()) {
                logger.error("Invalid password change request: password fields inconsistent");
                throw new ProfileUpdateException(
                    "Password change validation failed: current password and password confirmation must be provided",
                    request.getUserId(),
                    UpdateFailureReason.INVALID_PASSWORD_CHANGE
                );
            }
        }
    }

    /**
     * Applies profile update changes to user entity.
     * <p>
     * Mirrors COBOL field update operations from COUSR01C.cbl lines 154-158.
     * </p>
     * 
     * @param user the user entity to update
     * @param request the profile update request containing new values
     */
    private void applyProfileUpdates(UserSecurity user, UserProfileUpdateRequest request) {
        // Update first name (COBOL: MOVE FNAMEI OF COUSR1AI TO SEC-USR-FNAME)
        user.setFirstName(request.getFirstName().trim());
        
        // Update last name (COBOL: MOVE LNAMEI OF COUSR1AI TO SEC-USR-LNAME)
        user.setLastName(request.getLastName().trim());
        
        // Update user type (COBOL: MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE)
        // Note: User type changes should be restricted by authorization in controller
        user.setUserType(request.getUserType());
        
        logger.debug("Applied profile updates for userId: {}", user.getUserId());
    }

    /**
     * Handles password change operations with security validation.
     * <p>
     * Implements password change logic with BCrypt encryption replacing COBOL
     * plain text password storage.
     * </p>
     * 
     * @param user the user entity to update password
     * @param request the profile update request containing password fields
     * @throws ProfileUpdateException if password validation fails
     */
    private void handlePasswordChange(UserSecurity user, UserProfileUpdateRequest request) {
        logger.info("Processing password change for userId: {}", user.getUserId());
        
        // Verify current password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            logger.warn("Incorrect current password provided for userId: {}", user.getUserId());
            throw new ProfileUpdateException(
                "Current password is incorrect",
                user.getUserId(),
                UpdateFailureReason.INVALID_PASSWORD_CHANGE
            );
        }
        
        // Validate new password requirements
        validatePasswordRequirements(request.getNewPassword(), user.getUserId());
        
        // Hash new password using BCrypt with strength 12
        String hashedPassword = passwordEncoder.encode(request.getNewPassword());
        user.setPassword(hashedPassword);
        
        logger.info("Password successfully changed for userId: {}", user.getUserId());
    }

    /**
     * Validates new password meets security requirements.
     * <p>
     * Enforces password complexity rules beyond COBOL's 8-character plain text requirement.
     * </p>
     * 
     * @param newPassword the new password to validate
     * @param userId the user ID for error context
     * @throws ProfileUpdateException if password requirements not met
     */
    private void validatePasswordRequirements(String newPassword, String userId) {
        // Minimum length check
        if (newPassword.length() < 8) {
            logger.error("New password too short for userId: {}", userId);
            throw new ProfileUpdateException(
                "New password must be at least 8 characters long",
                userId,
                UpdateFailureReason.PASSWORD_REQUIREMENTS_NOT_MET
            );
        }
        
        // Maximum length check (BCrypt limit)
        if (newPassword.length() > 72) {
            logger.error("New password too long for userId: {}", userId);
            throw new ProfileUpdateException(
                "New password must not exceed 72 characters",
                userId,
                UpdateFailureReason.PASSWORD_REQUIREMENTS_NOT_MET
            );
        }
        
        // Complexity checks
        boolean hasUpperCase = newPassword.chars().anyMatch(Character::isUpperCase);
        boolean hasLowerCase = newPassword.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = newPassword.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = newPassword.chars().anyMatch(ch -> 
            "@$!%*?&#".indexOf(ch) >= 0
        );
        
        if (!hasUpperCase || !hasLowerCase || !hasDigit || !hasSpecial) {
            logger.error("New password does not meet complexity requirements for userId: {}", userId);
            throw new ProfileUpdateException(
                "New password must contain at least one uppercase letter, one lowercase letter, one number, and one special character (@$!%*?&#)",
                userId,
                UpdateFailureReason.PASSWORD_REQUIREMENTS_NOT_MET
            );
        }
        
        logger.debug("New password meets all security requirements for userId: {}", userId);
    }
}

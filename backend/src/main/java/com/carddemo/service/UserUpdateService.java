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

import com.carddemo.entity.UserSecurity;
import com.carddemo.exception.UserNotFoundException;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.security.SecurityConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service class for updating user profiles and security information.
 * 
 * <p>Transformed from COBOL program COUSR02C.cbl which performs user update operations
 * against the USRSEC VSAM file. This service maintains functional equivalence with the
 * mainframe implementation while leveraging Spring Security and JPA for modern
 * cloud-native architecture.</p>
 * 
 * <p><strong>COBOL Program Mapping:</strong></p>
 * <ul>
 *   <li>Source: app/cbl/COUSR02C.cbl (User Update Program)</li>
 *   <li>Transaction ID: CU02 (CICS transaction for user updates)</li>
 *   <li>VSAM File: USRSEC (user security data)</li>
 *   <li>Primary Operations: EXEC CICS READ UPDATE, EXEC CICS REWRITE</li>
 * </ul>
 * 
 * <p><strong>Core Functionality:</strong></p>
 * <ol>
 *   <li>User Profile Updates - name, contact information changes</li>
 *   <li>Role Management - admin-only user type modifications</li>
 *   <li>Password Updates - BCrypt re-encryption for password changes</li>
 *   <li>Status Management - active/inactive/locked status transitions</li>
 *   <li>Authorization Checks - users can update own profile, admins can update any</li>
 * </ol>
 * 
 * <p><strong>Security Model (per Section 0.9):</strong></p>
 * <ul>
 *   <li>Role-based authorization using @PreAuthorize</li>
 *   <li>Self-update authorization: users can only modify their own profiles</li>
 *   <li>Admin-only operations: role changes, user status modifications</li>
 *   <li>Password encryption: BCrypt with strength 12</li>
 *   <li>User ID immutability: userId cannot be changed after creation</li>
 * </ul>
 * 
 * <p><strong>Transaction Semantics:</strong></p>
 * <ul>
 *   <li>Isolation Level: READ_COMMITTED (matches CICS default)</li>
 *   <li>Propagation: REQUIRED (joins existing transaction or creates new)</li>
 *   <li>Rollback: On any Exception (ensures data integrity)</li>
 *   <li>Equivalent to CICS SYNCPOINT for commit boundaries</li>
 * </ul>
 * 
 * <p><strong>Field Validation (per COBOL PIC clauses):</strong></p>
 * <ul>
 *   <li>userId: PIC X(08) - 8 characters max, immutable</li>
 *   <li>firstName: PIC X(20) - 20 characters max</li>
 *   <li>lastName: PIC X(20) - 20 characters max</li>
 *   <li>password: PIC X(08) COBOL → BCrypt 60 chars Java</li>
 *   <li>userType: PIC X(01) - 'A' (Admin), 'U' (User), 'R' (Regular)</li>
 * </ul>
 * 
 * <p><strong>COBOL Paragraph Mapping:</strong></p>
 * <pre>
 * COBOL COUSR02C.cbl               →  Java UserUpdateService
 * =====================================  ===============================
 * PROCESS-ENTER-KEY (lines 143-173)  →  updateUser() method
 * UPDATE-USER-INFO (lines 177-245)   →  updateUserProfile() method
 * READ-USER-SEC-FILE (lines 320-353) →  UserSecurityRepository.findByUserId()
 * UPDATE-USER-SEC-FILE (lines 358-390) → UserSecurityRepository.save()
 * </pre>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see com.carddemo.entity.UserSecurity
 * @see com.carddemo.repository.UserSecurityRepository
 * @see COUSR02C.cbl
 */
@Service
public class UserUpdateService {
    
    private static final Logger logger = LoggerFactory.getLogger(UserUpdateService.class);
    
    // Maximum field lengths per COBOL PIC clauses
    private static final int MAX_USER_ID_LENGTH = 8;
    private static final int MAX_FIRST_NAME_LENGTH = 20;
    private static final int MAX_LAST_NAME_LENGTH = 20;
    private static final int MIN_PASSWORD_LENGTH = 4;
    
    // Error messages matching COBOL WS-MESSAGE patterns
    private static final String ERR_USER_ID_EMPTY = "User ID can NOT be empty...";
    private static final String ERR_USER_ID_IMMUTABLE = "User ID cannot be modified";
    private static final String ERR_FIRST_NAME_EMPTY = "First Name can NOT be empty...";
    private static final String ERR_FIRST_NAME_TOO_LONG = "First Name cannot exceed 20 characters";
    private static final String ERR_LAST_NAME_EMPTY = "Last Name can NOT be empty...";
    private static final String ERR_LAST_NAME_TOO_LONG = "Last Name cannot exceed 20 characters";
    private static final String ERR_PASSWORD_EMPTY = "Password can NOT be empty...";
    private static final String ERR_PASSWORD_TOO_SHORT = "Password must be at least 4 characters";
    private static final String ERR_USER_TYPE_EMPTY = "User Type can NOT be empty...";
    private static final String ERR_USER_TYPE_INVALID = "User Type must be 'A' (Admin), 'U' (User), or 'R' (Regular)";
    private static final String ERR_NO_MODIFICATION = "Please modify to update ...";
    private static final String ERR_UNAUTHORIZED_UPDATE = "You are not authorized to update this user profile";
    private static final String ERR_UNAUTHORIZED_ROLE_CHANGE = "Only administrators can change user roles";
    
    @Autowired
    private UserSecurityRepository userSecurityRepository;
    
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    
    /**
     * Updates user information with comprehensive validation and authorization checks.
     * 
     * <p>This method provides the primary entry point for user update operations,
     * replicating the logic from COBOL COUSR02C.cbl UPDATE-USER-INFO paragraph
     * (lines 177-245) combined with PROCESS-ENTER-KEY validation (lines 143-173).</p>
     * 
     * <p><strong>COBOL Operation Mapping:</strong></p>
     * <pre>
     * COBOL (COUSR02C.cbl lines 215-244):
     *     MOVE USRIDINI OF COUSR2AI TO SEC-USR-ID
     *     PERFORM READ-USER-SEC-FILE
     *     
     *     IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME
     *         MOVE FNAMEI OF COUSR2AI TO SEC-USR-FNAME
     *         SET USR-MODIFIED-YES TO TRUE
     *     END-IF
     *     
     *     IF USR-MODIFIED-YES
     *         PERFORM UPDATE-USER-SEC-FILE
     *     END-IF
     * 
     * Java Equivalent:
     *     UserSecurity user = userSecurityRepository.findByUserId(userId)
     *         .orElseThrow(() -> new UserNotFoundException(userId));
     *     
     *     boolean modified = false;
     *     if (!updatedUser.getFirstName().equals(user.getFirstName())) {
     *         user.setFirstName(updatedUser.getFirstName());
     *         modified = true;
     *     }
     *     
     *     if (modified) {
     *         userSecurityRepository.save(user);
     *     }
     * </pre>
     * 
     * <p><strong>Validation Rules (COUSR02C.cbl lines 179-213):</strong></p>
     * <ul>
     *   <li>User ID must not be empty (line 180-185)</li>
     *   <li>First name must not be empty (line 186-191)</li>
     *   <li>Last name must not be empty (line 192-197)</li>
     *   <li>Password must not be empty (line 198-203)</li>
     *   <li>User type must not be empty (line 204-209)</li>
     *   <li>At least one field must be modified (line 239-242)</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>Users can update their own profiles (self-service)</li>
     *   <li>Administrators can update any user profile</li>
     *   <li>Role changes require ROLE_ADMIN authority</li>
     * </ul>
     * 
     * @param userId the user ID of the user to update (8 characters max)
     * @param updatedUser the UserSecurity object containing updated field values
     * @return the updated UserSecurity entity after save operation
     * @throws UserNotFoundException if user with specified ID is not found (DFHRESP(NOTFND))
     * @throws AccessDeniedException if user attempts to update another user's profile without admin rights
     * @throws IllegalArgumentException if any validation rule is violated
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public UserSecurity updateUser(String userId, UserSecurity updatedUser) {
        logger.info("Updating user with ID: {}", userId);
        
        // Validate userId is not empty (COUSR02C.cbl lines 180-185)
        if (userId == null || userId.trim().isEmpty()) {
            logger.error("User ID is empty");
            throw new IllegalArgumentException(ERR_USER_ID_EMPTY);
        }
        
        // Validate userId immutability - cannot change primary key
        if (updatedUser.getUserId() != null && !userId.equals(updatedUser.getUserId())) {
            logger.error("Attempt to change user ID from {} to {}", userId, updatedUser.getUserId());
            throw new IllegalArgumentException(ERR_USER_ID_IMMUTABLE);
        }
        
        // Check authorization: user must be updating their own profile or be an admin
        checkUpdateAuthorization(userId);
        
        // Read existing user from database (COUSR02C.cbl lines 216-217: PERFORM READ-USER-SEC-FILE)
        // Maps to EXEC CICS READ DATASET(USRSEC) RIDFLD(SEC-USR-ID) UPDATE
        UserSecurity existingUser = userSecurityRepository.findByUserId(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        
        // Track if any modifications were made (COUSR02C.cbl line 45: WS-USR-MODIFIED)
        boolean modified = false;
        
        // Update first name if changed (COUSR02C.cbl lines 219-222)
        if (updatedUser.getFirstName() != null) {
            validateFirstName(updatedUser.getFirstName());
            if (!updatedUser.getFirstName().equals(existingUser.getFirstName())) {
                existingUser.setFirstName(updatedUser.getFirstName());
                modified = true;
                logger.debug("First name updated for user {}", userId);
            }
        }
        
        // Update last name if changed (COUSR02C.cbl lines 223-226)
        if (updatedUser.getLastName() != null) {
            validateLastName(updatedUser.getLastName());
            if (!updatedUser.getLastName().equals(existingUser.getLastName())) {
                existingUser.setLastName(updatedUser.getLastName());
                modified = true;
                logger.debug("Last name updated for user {}", userId);
            }
        }
        
        // Update password if changed (COUSR02C.cbl lines 227-230)
        // CRITICAL: Password must be re-encrypted with BCrypt before storage
        if (updatedUser.getPassword() != null && !updatedUser.getPassword().isEmpty()) {
            validatePassword(updatedUser.getPassword());
            // Only re-hash if the provided password is not already a BCrypt hash
            String newPasswordHash;
            if (isAlreadyBCryptHash(updatedUser.getPassword())) {
                newPasswordHash = updatedUser.getPassword();
            } else {
                newPasswordHash = passwordEncoder.encode(updatedUser.getPassword());
            }
            
            if (!newPasswordHash.equals(existingUser.getPassword())) {
                existingUser.setPassword(newPasswordHash);
                modified = true;
                logger.debug("Password updated for user {}", userId);
            }
        }
        
        // Update user type if changed (COUSR02C.cbl lines 231-234)
        // CRITICAL: Role changes require admin authorization
        if (updatedUser.getUserType() != null) {
            validateUserType(updatedUser.getUserType());
            if (!updatedUser.getUserType().equals(existingUser.getUserType())) {
                // Check if user has admin role for role changes
                checkRoleChangeAuthorization();
                existingUser.setUserType(updatedUser.getUserType());
                modified = true;
                logger.debug("User type updated for user {} to {}", userId, updatedUser.getUserType());
            }
        }
        
        // Check if any modifications were made (COUSR02C.cbl lines 236-243)
        if (!modified) {
            logger.warn("No modifications detected for user {}", userId);
            throw new IllegalArgumentException(ERR_NO_MODIFICATION);
        }
        
        // Save updated user to database (COUSR02C.cbl lines 236-237: PERFORM UPDATE-USER-SEC-FILE)
        // Maps to EXEC CICS REWRITE DATASET(USRSEC) FROM(SEC-USER-DATA)
        UserSecurity savedUser = userSecurityRepository.save(existingUser);
        
        logger.info("Successfully updated user {} with {} field(s) modified", userId, 
            (modified ? "multiple" : "single"));
        
        return savedUser;
    }
    
    /**
     * Updates user profile information (name and contact details only).
     * 
     * <p>This method provides a focused interface for profile updates, excluding
     * security-sensitive fields like password and user type. Users can update
     * their own profiles, while administrators can update any profile.</p>
     * 
     * <p><strong>Authorization Rules:</strong></p>
     * <ul>
     *   <li>Authenticated users can update their own profiles (self-service)</li>
     *   <li>Users with ROLE_ADMIN can update any user profile</li>
     *   <li>AccessDeniedException thrown if user attempts to update another user's profile</li>
     * </ul>
     * 
     * <p><strong>Fields Updated:</strong></p>
     * <ul>
     *   <li>firstName - user's first name (max 20 characters)</li>
     *   <li>lastName - user's last name (max 20 characters)</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL (COUSR02C.cbl lines 219-226):
     *     IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME
     *         MOVE FNAMEI OF COUSR2AI TO SEC-USR-FNAME
     *         SET USR-MODIFIED-YES TO TRUE
     *     END-IF
     *     IF LNAMEI OF COUSR2AI NOT = SEC-USR-LNAME
     *         MOVE LNAMEI OF COUSR2AI TO SEC-USR-LNAME
     *         SET USR-MODIFIED-YES TO TRUE
     *     END-IF
     * </pre>
     * 
     * @param userId the user ID of the user to update
     * @param firstName new first name (max 20 characters)
     * @param lastName new last name (max 20 characters)
     * @return the updated UserSecurity entity
     * @throws UserNotFoundException if user with specified ID is not found
     * @throws AccessDeniedException if authorization check fails
     * @throws IllegalArgumentException if field validation fails
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public UserSecurity updateUserProfile(String userId, String firstName, String lastName) {
        logger.info("Updating profile for user: {}", userId);
        
        // Check authorization
        checkUpdateAuthorization(userId);
        
        // Retrieve existing user
        UserSecurity user = userSecurityRepository.findByUserId(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        
        // Track modifications
        boolean modified = false;
        
        // Update first name if provided and changed
        if (firstName != null) {
            validateFirstName(firstName);
            if (!firstName.equals(user.getFirstName())) {
                user.setFirstName(firstName);
                modified = true;
            }
        }
        
        // Update last name if provided and changed
        if (lastName != null) {
            validateLastName(lastName);
            if (!lastName.equals(user.getLastName())) {
                user.setLastName(lastName);
                modified = true;
            }
        }
        
        // Ensure at least one field was modified
        if (!modified) {
            logger.warn("No profile modifications detected for user {}", userId);
            throw new IllegalArgumentException(ERR_NO_MODIFICATION);
        }
        
        // Save and return updated user
        UserSecurity savedUser = userSecurityRepository.save(user);
        logger.info("Successfully updated profile for user {}", userId);
        
        return savedUser;
    }
    
    /**
     * Changes user role/type with strict admin-only authorization.
     * 
     * <p>This method is restricted to administrative users only via @PreAuthorize
     * annotation, implementing the role-based security model from Section 0.9.
     * Regular users cannot change their own or others' roles.</p>
     * 
     * <p><strong>Valid User Types (from COBOL SEC-USR-TYPE PIC X(01)):</strong></p>
     * <ul>
     *   <li>'A' - Administrative User (ROLE_ADMIN + ROLE_USER)</li>
     *   <li>'U' - Regular User (ROLE_USER)</li>
     *   <li>'R' - Regular User (alternative code, ROLE_USER)</li>
     * </ul>
     * 
     * <p><strong>Security Requirements:</strong></p>
     * <ul>
     *   <li>Caller must have ROLE_ADMIN authority</li>
     *   <li>Spring Security @PreAuthorize enforces authorization before method execution</li>
     *   <li>AccessDeniedException thrown by Spring Security if authorization fails</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL (COUSR02C.cbl lines 231-234):
     *     IF USRTYPEI OF COUSR2AI NOT = SEC-USR-TYPE
     *         MOVE USRTYPEI OF COUSR2AI TO SEC-USR-TYPE
     *         SET USR-MODIFIED-YES TO TRUE
     *     END-IF
     * </pre>
     * 
     * <p>Note: COBOL implementation does not enforce admin-only role changes.
     * This Java implementation adds that security requirement per Section 0.9.</p>
     * 
     * @param userId the user ID of the user whose role is being changed
     * @param newUserType the new user type code: 'A', 'U', or 'R'
     * @return the updated UserSecurity entity with new role
     * @throws UserNotFoundException if user with specified ID is not found
     * @throws AccessDeniedException if caller does not have ROLE_ADMIN (enforced by @PreAuthorize)
     * @throws IllegalArgumentException if newUserType is invalid
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public UserSecurity changeUserRole(String userId, String newUserType) {
        logger.info("Changing role for user {} to {}", userId, newUserType);
        
        // Validate user type
        validateUserType(newUserType);
        
        // Retrieve existing user
        UserSecurity user = userSecurityRepository.findByUserId(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        
        // Check if role is actually changing
        if (newUserType.equals(user.getUserType())) {
            logger.warn("New user type {} is same as current for user {}", newUserType, userId);
            throw new IllegalArgumentException(ERR_NO_MODIFICATION);
        }
        
        // Update user type
        user.setUserType(newUserType);
        
        // Save and return updated user
        UserSecurity savedUser = userSecurityRepository.save(user);
        logger.info("Successfully changed role for user {} to {}", userId, newUserType);
        
        return savedUser;
    }
    
    /**
     * Updates user password with BCrypt re-encryption.
     * 
     * <p>This method handles password updates with proper security measures,
     * ensuring all passwords are stored as BCrypt hashes per Section 0.9 security
     * requirements. Plain text passwords from input are hashed before storage.</p>
     * 
     * <p><strong>Password Security:</strong></p>
     * <ul>
     *   <li>Input passwords are plain text (from user input)</li>
     *   <li>Passwords are hashed using BCryptPasswordEncoder with strength 12</li>
     *   <li>BCrypt hashes are 60 characters (format: $2a$12$[salt][hash])</li>
     *   <li>COBOL stored plain text 8-char passwords - INSECURE (migrated to BCrypt)</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>Users can change their own passwords (self-service)</li>
     *   <li>Administrators can change any user's password</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL (COUSR02C.cbl lines 227-230):
     *     IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD
     *         MOVE PASSWDI OF COUSR2AI TO SEC-USR-PWD
     *         SET USR-MODIFIED-YES TO TRUE
     *     END-IF
     * 
     * Java Enhancement:
     *     String hashedPassword = passwordEncoder.encode(newPassword);
     *     user.setPassword(hashedPassword);
     * </pre>
     * 
     * @param userId the user ID of the user whose password is being changed
     * @param newPassword the new plain text password (will be hashed before storage)
     * @return the updated UserSecurity entity
     * @throws UserNotFoundException if user with specified ID is not found
     * @throws AccessDeniedException if authorization check fails
     * @throws IllegalArgumentException if password validation fails
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public UserSecurity updatePassword(String userId, String newPassword) {
        logger.info("Updating password for user: {}", userId);
        
        // Validate password
        validatePassword(newPassword);
        
        // Check authorization
        checkUpdateAuthorization(userId);
        
        // Retrieve existing user
        UserSecurity user = userSecurityRepository.findByUserId(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        
        // Hash the new password with BCrypt
        String hashedPassword = passwordEncoder.encode(newPassword);
        
        // Check if password is actually changing (compare hashed values)
        if (hashedPassword.equals(user.getPassword())) {
            logger.warn("New password hash is same as current for user {}", userId);
            throw new IllegalArgumentException(ERR_NO_MODIFICATION);
        }
        
        // Update password
        user.setPassword(hashedPassword);
        
        // Save and return updated user
        UserSecurity savedUser = userSecurityRepository.save(user);
        logger.info("Successfully updated password for user {}", userId);
        
        return savedUser;
    }
    
    /**
     * Changes user account status (active, inactive, locked).
     * 
     * <p>This method provides status management for user accounts, enabling
     * administrators to activate, deactivate, or lock user accounts. Currently
     * this is a placeholder for future enhancement, as the COBOL COUSR02C.cbl
     * implementation does not include status management.</p>
     * 
     * <p><strong>Future Enhancement:</strong></p>
     * <ul>
     *   <li>This method is prepared for future UserSecurity entity enhancements</li>
     *   <li>Will support status field: ACTIVE, INACTIVE, LOCKED</li>
     *   <li>Requires database schema update to add status column</li>
     *   <li>Currently throws UnsupportedOperationException</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>Only administrators can change user status (admin-only operation)</li>
     *   <li>Users cannot change their own status</li>
     * </ul>
     * 
     * <p><strong>No COBOL Equivalent:</strong></p>
     * <p>The COBOL COUSR02C.cbl program does not include user status management.
     * This is a new capability added in the Java implementation for enhanced
     * security controls.</p>
     * 
     * @param userId the user ID of the user whose status is being changed
     * @param newStatus the new status: "ACTIVE", "INACTIVE", or "LOCKED"
     * @return the updated UserSecurity entity
     * @throws UserNotFoundException if user with specified ID is not found
     * @throws AccessDeniedException if caller does not have ROLE_ADMIN
     * @throws UnsupportedOperationException until status field is added to UserSecurity entity
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public UserSecurity changeUserStatus(String userId, String newStatus) {
        logger.info("Changing status for user {} to {}", userId, newStatus);
        
        // Retrieve existing user
        UserSecurity user = userSecurityRepository.findByUserId(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        
        // Status management is a future enhancement
        // The current UserSecurity entity does not have a status field
        // This method is provided to satisfy the export schema requirements
        // but will be implemented when status field is added to the entity
        
        logger.warn("User status management not yet implemented - requires entity schema update");
        throw new UnsupportedOperationException(
            "User status management requires adding status field to UserSecurity entity");
    }
    
    // ========== Private Helper Methods ==========
    
    /**
     * Checks if the current authenticated user is authorized to update the specified user.
     * 
     * <p>Authorization rules:</p>
     * <ul>
     *   <li>Users can update their own profile (userId matches authenticated user)</li>
     *   <li>Administrators can update any user profile (ROLE_ADMIN authority)</li>
     *   <li>Other users cannot update different user profiles</li>
     * </ul>
     * 
     * @param userId the user ID being updated
     * @throws AccessDeniedException if authorization check fails
     */
    private void checkUpdateAuthorization(String userId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            logger.error("No authenticated user found in security context");
            throw new AccessDeniedException("User must be authenticated to update profiles");
        }
        
        String authenticatedUserId = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(auth -> SecurityConstants.ROLE_ADMIN.equals(auth.getAuthority()));
        
        // Allow if user is updating their own profile or is an admin
        if (!userId.equals(authenticatedUserId) && !isAdmin) {
            logger.error("User {} attempted to update user {} without authorization", 
                authenticatedUserId, userId);
            throw new AccessDeniedException(ERR_UNAUTHORIZED_UPDATE);
        }
        
        logger.debug("Authorization check passed for user {} updating user {}", 
            authenticatedUserId, userId);
    }
    
    /**
     * Checks if the current authenticated user has admin authority for role changes.
     * 
     * <p>This method is used in addition to @PreAuthorize to provide explicit
     * authorization checking with custom error messages.</p>
     * 
     * @throws AccessDeniedException if user does not have ROLE_ADMIN
     */
    private void checkRoleChangeAuthorization() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            logger.error("No authenticated user found in security context");
            throw new AccessDeniedException("User must be authenticated to change roles");
        }
        
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(auth -> SecurityConstants.ROLE_ADMIN.equals(auth.getAuthority()));
        
        if (!isAdmin) {
            logger.error("Non-admin user {} attempted to change user role", authentication.getName());
            throw new AccessDeniedException(ERR_UNAUTHORIZED_ROLE_CHANGE);
        }
        
        logger.debug("Admin authorization check passed for user {}", authentication.getName());
    }
    
    /**
     * Validates first name field per COBOL PIC X(20) constraints.
     * 
     * <p>Validation rules from COUSR02C.cbl lines 186-191:</p>
     * <ul>
     *   <li>Must not be null or empty</li>
     *   <li>Must not be spaces or low-values</li>
     *   <li>Must not exceed 20 characters (COBOL: SEC-USR-FNAME PIC X(20))</li>
     * </ul>
     * 
     * @param firstName the first name to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateFirstName(String firstName) {
        if (firstName == null || firstName.trim().isEmpty()) {
            logger.error("First name validation failed: empty or null");
            throw new IllegalArgumentException(ERR_FIRST_NAME_EMPTY);
        }
        
        if (firstName.length() > MAX_FIRST_NAME_LENGTH) {
            logger.error("First name validation failed: length {} exceeds maximum {}", 
                firstName.length(), MAX_FIRST_NAME_LENGTH);
            throw new IllegalArgumentException(ERR_FIRST_NAME_TOO_LONG);
        }
    }
    
    /**
     * Validates last name field per COBOL PIC X(20) constraints.
     * 
     * <p>Validation rules from COUSR02C.cbl lines 192-197:</p>
     * <ul>
     *   <li>Must not be null or empty</li>
     *   <li>Must not be spaces or low-values</li>
     *   <li>Must not exceed 20 characters (COBOL: SEC-USR-LNAME PIC X(20))</li>
     * </ul>
     * 
     * @param lastName the last name to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateLastName(String lastName) {
        if (lastName == null || lastName.trim().isEmpty()) {
            logger.error("Last name validation failed: empty or null");
            throw new IllegalArgumentException(ERR_LAST_NAME_EMPTY);
        }
        
        if (lastName.length() > MAX_LAST_NAME_LENGTH) {
            logger.error("Last name validation failed: length {} exceeds maximum {}", 
                lastName.length(), MAX_LAST_NAME_LENGTH);
            throw new IllegalArgumentException(ERR_LAST_NAME_TOO_LONG);
        }
    }
    
    /**
     * Validates password field per security requirements.
     * 
     * <p>Validation rules from COUSR02C.cbl lines 198-203 plus Java enhancements:</p>
     * <ul>
     *   <li>Must not be null or empty</li>
     *   <li>Must not be spaces or low-values</li>
     *   <li>Must be at least 4 characters (enhanced security requirement)</li>
     * </ul>
     * 
     * <p>Note: COBOL allowed 8-character plain text passwords.
     * Java implementation requires minimum 4 characters for plain text input,
     * which will be hashed to 60-character BCrypt format.</p>
     * 
     * @param password the password to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validatePassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            logger.error("Password validation failed: empty or null");
            throw new IllegalArgumentException(ERR_PASSWORD_EMPTY);
        }
        
        // Skip length validation if password is already a BCrypt hash
        if (!isAlreadyBCryptHash(password) && password.length() < MIN_PASSWORD_LENGTH) {
            logger.error("Password validation failed: length {} less than minimum {}", 
                password.length(), MIN_PASSWORD_LENGTH);
            throw new IllegalArgumentException(ERR_PASSWORD_TOO_SHORT);
        }
    }
    
    /**
     * Validates user type code per COBOL SEC-USR-TYPE PIC X(01) constraints.
     * 
     * <p>Validation rules from COUSR02C.cbl lines 204-209 plus SecurityConstants:</p>
     * <ul>
     *   <li>Must not be null or empty</li>
     *   <li>Must be exactly 1 character</li>
     *   <li>Must be 'A' (Admin), 'U' (User), or 'R' (Regular)</li>
     * </ul>
     * 
     * @param userType the user type code to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateUserType(String userType) {
        if (userType == null || userType.trim().isEmpty()) {
            logger.error("User type validation failed: empty or null");
            throw new IllegalArgumentException(ERR_USER_TYPE_EMPTY);
        }
        
        // Valid user types from SecurityConstants
        boolean isValid = SecurityConstants.USER_TYPE_ADMIN.equals(userType) ||
                         SecurityConstants.USER_TYPE_USER.equals(userType) ||
                         SecurityConstants.USER_TYPE_REGULAR.equals(userType);
        
        if (!isValid) {
            logger.error("User type validation failed: invalid value '{}'", userType);
            throw new IllegalArgumentException(ERR_USER_TYPE_INVALID);
        }
    }
    
    /**
     * Checks if a string is already a BCrypt hash.
     * 
     * <p>BCrypt hashes have a specific format: $2a$rounds$[22-char salt][31-char hash]</p>
     * <p>This method performs a simple pattern check to avoid double-hashing.</p>
     * 
     * @param password the password string to check
     * @return true if the string appears to be a BCrypt hash, false otherwise
     */
    private boolean isAlreadyBCryptHash(String password) {
        return password != null && password.startsWith("$2a$") && password.length() == 60;
    }
}

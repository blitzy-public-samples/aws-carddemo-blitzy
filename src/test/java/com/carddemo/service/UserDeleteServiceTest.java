/*
 * UserDeleteServiceTest.java
 * 
 * JUnit 5 unit test class for UserDeleteService verifying user deletion logic preservation
 * from COBOL program COUSR03C.cbl.
 * 
 * This test class validates the transformation of COBOL EXEC CICS DELETE DATASET(USRSEC)
 * operations to Java soft-delete pattern with enhanced business rule validations including:
 * - Prevention of last admin user deletion (system lockout protection)
 * - Prevention of self-deletion (admin deleting their own account)
 * - User existence validation matching COBOL RESP-CD 13 (NOTFND) condition
 * - Soft delete implementation setting deleted flag instead of physical record removal
 * - Audit trail recording with deletion timestamp and deleting user ID
 * - @Transactional boundary verification matching CICS SYNCPOINT behavior
 * 
 * COBOL Source Reference:
 * - app/cbl/COUSR03C.cbl: DELETE-USER-SEC-FILE paragraph (lines 250-280)
 * - app/cpy/CSUSR01Y.cpy: SEC-USER-DATA structure
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
package com.carddemo.service;

import com.carddemo.entity.User;
import com.carddemo.entity.User.UserType;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.user.UserDeleteService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test class for UserDeleteService verifying soft-delete user functionality.
 * 
 * <p>Test coverage areas:</p>
 * <ul>
 *   <li>Successful user soft deletion with audit trail</li>
 *   <li>User not found error handling (COBOL NOTFND condition)</li>
 *   <li>Business rule: Prevention of self-deletion</li>
 *   <li>Business rule: Prevention of last admin deletion</li>
 *   <li>Business rule: Prevention of deleting already deleted users</li>
 *   <li>Audit field population (deletedDate, deletedBy)</li>
 *   <li>Transaction rollback on errors</li>
 * </ul>
 * 
 * <p>COBOL Transformation Validation:</p>
 * <pre>
 * COBOL (COUSR03C.cbl):
 *     DELETE-USER-SEC-FILE.
 *         EXEC CICS DELETE
 *              DATASET(WS-USRSEC-FILE)
 *              RIDFLD(SEC-USR-ID)
 *              RESP(WS-RESP-CD)
 *              RESP2(WS-REAS-CD)
 *         END-EXEC
 *         
 *         EVALUATE WS-RESP-CD
 *             WHEN DFHRESP(NORMAL)
 *                 MOVE 'User deleted successfully' TO WS-MESSAGE
 *             WHEN DFHRESP(NOTFND)
 *                 MOVE 'User not found' TO WS-MESSAGE
 *         END-EVALUATE
 * 
 * Java (UserDeleteService):
 *     {@literal @}Transactional
 *     public void deleteUser(String userId) {
 *         User user = userRepository.findById(userId)
 *             .orElseThrow(() -&gt; new ResourceNotFoundException("User not found"));
 *         
 *         // Enhanced business rules not in COBOL
 *         validateNotLastAdmin(user);
 *         validateNotSelfDeletion(userId);
 *         
 *         // Soft delete instead of physical deletion
 *         user.setDeleted(true);
 *         user.setDeletedDate(LocalDateTime.now());
 *         user.setDeletedBy(getCurrentUsername());
 *         userRepository.save(user);
 *     }
 * </pre>
 * 
 * @see UserDeleteService
 * @see User
 * @see UserRepository
 * @since 1.0
 */
@DisplayName("UserDeleteService Unit Tests - COBOL COUSR03C.cbl Transformation")
public class UserDeleteServiceTest {

    /**
     * Mock UserRepository for database operations.
     * Replaces COBOL VSAM file I/O operations on USRSEC dataset.
     */
    @Mock
    private UserRepository userRepository;

    /**
     * Mock SecurityContext for authentication context.
     * Used to retrieve current authenticated user for self-deletion validation.
     */
    @Mock
    private SecurityContext securityContext;

    /**
     * Mock Authentication for authenticated user principal.
     * Provides current user ID for business rule validations.
     */
    @Mock
    private Authentication authentication;

    /**
     * Service under test with mocked dependencies injected.
     */
    @InjectMocks
    private UserDeleteService userDeleteService;

    /**
     * Test data: User ID for deletion operations.
     */
    private static final String TEST_USER_ID = "TESTUSER";

    /**
     * Test data: Admin user ID for authorization tests.
     */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /**
     * Test data: Regular user ID for role-based tests.
     */
    private static final String REGULAR_USER_ID = "USER0001";

    /**
     * Test data: Non-existent user ID for not found scenarios.
     */
    private static final String NON_EXISTENT_USER_ID = "NOUSER99";

    /**
     * Initialize mocks before each test method execution.
     * Replaces COBOL WORKING-STORAGE initialization.
     */
    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Setup SecurityContext for authentication tests
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    /**
     * Test successful user soft deletion with audit trail.
     * 
     * <p>Validates transformation of COBOL EXEC CICS DELETE DATASET(USRSEC)
     * with RESP(NORMAL) to Java soft-delete pattern.</p>
     * 
     * <p>COBOL equivalent (COUSR03C.cbl lines 250-255):</p>
     * <pre>
     * EXEC CICS DELETE
     *      DATASET(WS-USRSEC-FILE)
     *      RIDFLD(SEC-USR-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *     MOVE 'User deleted successfully' TO WS-MESSAGE
     * </pre>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>User entity retrieved from repository</li>
     *   <li>Deleted flag set to true</li>
     *   <li>DeletedDate set to current timestamp</li>
     *   <li>DeletedBy set to current authenticated user</li>
     *   <li>User entity saved to repository</li>
     *   <li>No exception thrown</li>
     * </ul>
     */
    @Test
    @DisplayName("Should successfully soft-delete user when valid user ID provided and business rules satisfied")
    public void testDeleteUserSuccess() {
        // Arrange: Setup test user (regular user, not last admin)
        User testUser = User.builder()
                .userId(TEST_USER_ID)
                .firstName("Test")
                .lastName("User")
                .userType(UserType.USER)
                .password("$2a$10$encoded.password.hash")
                .deleted(false)
                .build();

        // Mock repository responses
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(userRepository.countByUserTypeAndDeletedFalse(UserType.ADMIN)).thenReturn(5L);
        when(authentication.getName()).thenReturn(ADMIN_USER_ID);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Execute deletion
        assertThatNoException().isThrownBy(() -> userDeleteService.deleteUser(TEST_USER_ID));

        // Assert: Verify soft delete behavior
        verify(userRepository, times(1)).findById(TEST_USER_ID);
        verify(userRepository, times(1)).save(argThat(user -> 
            user.getUserId().equals(TEST_USER_ID) &&
            user.isDeleted() == true &&
            user.getDeletedDate() != null &&
            user.getDeletedBy() != null
        ));
        verify(userRepository, never()).deleteById(anyString());
    }

    /**
     * Test user not found scenario matching COBOL NOTFND condition.
     * 
     * <p>Validates transformation of COBOL RESP-CD 13 (NOTFND) error handling
     * to Java ResourceNotFoundException.</p>
     * 
     * <p>COBOL equivalent (COUSR03C.cbl lines 256-258):</p>
     * <pre>
     * WHEN DFHRESP(NOTFND)
     *     MOVE 'User not found. Please verify user ID.' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     * </pre>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>Repository returns Optional.empty() for non-existent user</li>
     *   <li>ResourceNotFoundException thrown with appropriate message</li>
     *   <li>Exception message contains "User not found"</li>
     *   <li>No database save operation attempted</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ResourceNotFoundException when user does not exist (COBOL NOTFND condition)")
    public void testDeleteUserNotFound() {
        // Arrange: Mock repository to return empty Optional
        when(userRepository.findById(NON_EXISTENT_USER_ID)).thenReturn(Optional.empty());

        // Act & Assert: Verify exception thrown matching COBOL NOTFND handling
        assertThatThrownBy(() -> userDeleteService.deleteUser(NON_EXISTENT_USER_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found")
                .hasMessageContaining(NON_EXISTENT_USER_ID);

        // Verify no save operation attempted
        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).deleteById(anyString());
    }

    /**
     * Test prevention of self-deletion business rule.
     * 
     * <p>This is an enhanced business rule not present in original COBOL program.
     * Prevents administrators from deleting their own accounts to avoid accidental
     * account lockout scenarios.</p>
     * 
     * <p>Business Rule: An authenticated admin user cannot delete their own user account.</p>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>Current authenticated user ID retrieved from SecurityContext</li>
     *   <li>Comparison performed between authenticated user and target deletion user</li>
     *   <li>ValidationException thrown if IDs match</li>
     *   <li>Exception message contains "cannot delete your own"</li>
     *   <li>No database modification performed</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when admin attempts to delete their own account")
    public void testDeleteUserPreventsSelfDeletion() {
        // Arrange: Setup admin user attempting to delete themselves
        User adminUser = User.builder()
                .userId(ADMIN_USER_ID)
                .firstName("Admin")
                .lastName("User")
                .userType(UserType.ADMIN)
                .password("$2a$10$encoded.password.hash")
                .deleted(false)
                .build();

        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(adminUser));
        when(authentication.getName()).thenReturn(ADMIN_USER_ID);  // Same user as deletion target
        when(userRepository.countByUserTypeAndDeletedFalse(UserType.ADMIN)).thenReturn(3L);

        // Act & Assert: Verify self-deletion prevention
        assertThatThrownBy(() -> userDeleteService.deleteUser(ADMIN_USER_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot delete your own");

        // Verify no database modification
        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).deleteById(anyString());
    }

    /**
     * Test prevention of last admin deletion business rule.
     * 
     * <p>This is an enhanced business rule not present in original COBOL program.
     * Ensures the system maintains at least one active administrator account to
     * prevent complete system lockout.</p>
     * 
     * <p>Business Rule: The system must always have at least one active (non-deleted)
     * administrator user. Attempting to delete the last admin results in error.</p>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>Repository query counts active admin users</li>
     *   <li>If count equals 1 and target user is admin, deletion prevented</li>
     *   <li>ValidationException thrown with appropriate message</li>
     *   <li>Exception message contains "Cannot delete last admin user"</li>
     *   <li>No database modification performed</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when attempting to delete last admin user")
    public void testDeleteUserPreventsLastAdminDeletion() {
        // Arrange: Setup last remaining admin user
        User lastAdminUser = User.builder()
                .userId(ADMIN_USER_ID)
                .firstName("Last")
                .lastName("Admin")
                .userType(UserType.ADMIN)
                .password("$2a$10$encoded.password.hash")
                .deleted(false)
                .build();

        when(userRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(lastAdminUser));
        when(userRepository.countByUserTypeAndDeletedFalse(UserType.ADMIN)).thenReturn(1L);  // Only 1 admin left
        when(authentication.getName()).thenReturn("OTHERADM");  // Different admin performing deletion

        // Act & Assert: Verify last admin deletion prevention
        assertThatThrownBy(() -> userDeleteService.deleteUser(ADMIN_USER_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cannot delete last admin user");

        // Verify no database modification
        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).deleteById(anyString());
    }

    /**
     * Test that deleting a regular user (non-admin) succeeds when multiple admins exist.
     * 
     * <p>Validates that last admin validation only applies to admin users and does not
     * affect regular user deletion operations.</p>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>Regular user deletion proceeds normally</li>
     *   <li>Admin count check performed but does not block deletion</li>
     *   <li>User marked as deleted with audit fields populated</li>
     *   <li>Repository save operation invoked</li>
     * </ul>
     */
    @Test
    @DisplayName("Should successfully delete regular user even when only one admin exists")
    public void testDeleteRegularUserSucceedsWithOneAdmin() {
        // Arrange: Setup regular user with one admin in system
        User regularUser = User.builder()
                .userId(REGULAR_USER_ID)
                .firstName("Regular")
                .lastName("User")
                .userType(UserType.USER)
                .password("$2a$10$encoded.password.hash")
                .deleted(false)
                .build();

        when(userRepository.findById(REGULAR_USER_ID)).thenReturn(Optional.of(regularUser));
        when(userRepository.countByUserTypeAndDeletedFalse(UserType.ADMIN)).thenReturn(1L);
        when(authentication.getName()).thenReturn(ADMIN_USER_ID);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Execute deletion
        assertThatNoException().isThrownBy(() -> userDeleteService.deleteUser(REGULAR_USER_ID));

        // Assert: Verify deletion succeeded
        verify(userRepository, times(1)).save(argThat(user -> 
            user.getUserId().equals(REGULAR_USER_ID) &&
            user.isDeleted() == true
        ));
    }

    /**
     * Test that attempting to delete an already deleted user throws exception.
     * 
     * <p>Validates idempotency protection preventing redundant deletion operations
     * and maintaining audit trail integrity.</p>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>User lookup succeeds but user has deleted flag = true</li>
     *   <li>ValidationException thrown indicating user already deleted</li>
     *   <li>No database modification attempted</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when attempting to delete already deleted user")
    public void testDeleteUserAlreadyDeleted() {
        // Arrange: Setup already deleted user
        User deletedUser = User.builder()
                .userId(TEST_USER_ID)
                .firstName("Deleted")
                .lastName("User")
                .userType(UserType.USER)
                .password("$2a$10$encoded.password.hash")
                .deleted(true)
                .deletedDate(LocalDateTime.now().minusDays(5))
                .deletedBy("ADMIN001")
                .build();

        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(deletedUser));
        when(authentication.getName()).thenReturn(ADMIN_USER_ID);

        // Act & Assert: Verify already deleted user handling
        assertThatThrownBy(() -> userDeleteService.deleteUser(TEST_USER_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already deleted");

        // Verify no additional save operation
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test that deletion audit fields are correctly populated.
     * 
     * <p>Validates soft-delete audit trail implementation including timestamp
     * and responsible user tracking for compliance and troubleshooting.</p>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>deleted flag set to true</li>
     *   <li>deletedDate set to current timestamp (within 1 second tolerance)</li>
     *   <li>deletedBy set to current authenticated user ID</li>
     *   <li>Original user data preserved (firstName, lastName, etc.)</li>
     * </ul>
     */
    @Test
    @DisplayName("Should correctly populate audit fields (deletedDate, deletedBy) during soft delete")
    public void testDeleteUserSetsAuditFields() {
        // Arrange: Setup test user and capture time before deletion
        User testUser = User.builder()
                .userId(TEST_USER_ID)
                .firstName("Test")
                .lastName("User")
                .userType(UserType.USER)
                .password("$2a$10$encoded.password.hash")
                .deleted(false)
                .build();

        LocalDateTime beforeDeletion = LocalDateTime.now();

        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(userRepository.countByUserTypeAndDeletedFalse(UserType.ADMIN)).thenReturn(2L);
        when(authentication.getName()).thenReturn(ADMIN_USER_ID);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Execute deletion
        userDeleteService.deleteUser(TEST_USER_ID);

        LocalDateTime afterDeletion = LocalDateTime.now();

        // Assert: Verify audit fields populated correctly
        verify(userRepository).save(argThat(user -> {
            boolean deletedFlagSet = user.isDeleted() == true;
            boolean deletedDateSet = user.getDeletedDate() != null &&
                    !user.getDeletedDate().isBefore(beforeDeletion) &&
                    !user.getDeletedDate().isAfter(afterDeletion);
            boolean deletedBySet = ADMIN_USER_ID.equals(user.getDeletedBy());
            
            return deletedFlagSet && deletedDateSet && deletedBySet;
        }));
    }

    /**
     * Test transaction rollback behavior on error conditions.
     * 
     * <p>Validates that @Transactional annotation on service method ensures
     * automatic rollback when RuntimeException thrown, matching COBOL CICS
     * SYNCPOINT ROLLBACK behavior on error conditions.</p>
     * 
     * <p>COBOL equivalent (implicit CICS behavior):</p>
     * <pre>
     * WHEN OTHER
     *     EXEC CICS SYNCPOINT ROLLBACK END-EXEC
     * </pre>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>Repository save throws RuntimeException</li>
     *   <li>Exception propagates to caller</li>
     *   <li>Spring @Transactional triggers automatic rollback</li>
     *   <li>Database remains in consistent state before operation</li>
     * </ul>
     */
    @Test
    @DisplayName("Should rollback transaction when repository save operation fails (CICS SYNCPOINT ROLLBACK)")
    public void testDeleteUserRollsBackOnError() {
        // Arrange: Setup user and mock save to throw exception
        User testUser = User.builder()
                .userId(TEST_USER_ID)
                .firstName("Test")
                .lastName("User")
                .userType(UserType.USER)
                .password("$2a$10$encoded.password.hash")
                .deleted(false)
                .build();

        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(userRepository.countByUserTypeAndDeletedFalse(UserType.ADMIN)).thenReturn(2L);
        when(authentication.getName()).thenReturn(ADMIN_USER_ID);
        when(userRepository.save(any(User.class)))
                .thenThrow(new RuntimeException("Database connection failed"));

        // Act & Assert: Verify exception propagates for rollback
        assertThatThrownBy(() -> userDeleteService.deleteUser(TEST_USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database connection failed");

        // Note: @Transactional rollback behavior is verified through Spring integration tests
        // This unit test confirms exception propagation which triggers rollback
    }

    /**
     * Test null or empty user ID validation.
     * 
     * <p>Validates input validation matching COBOL field validation patterns.</p>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>Null user ID throws IllegalArgumentException</li>
     *   <li>Empty string user ID throws IllegalArgumentException</li>
     *   <li>No database operations attempted</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw IllegalArgumentException when user ID is null or empty")
    public void testDeleteUserWithNullOrEmptyUserId() {
        // Act & Assert: Null user ID
        assertThatThrownBy(() -> userDeleteService.deleteUser(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID cannot be null or empty");

        // Act & Assert: Empty user ID
        assertThatThrownBy(() -> userDeleteService.deleteUser(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID cannot be null or empty");

        // Act & Assert: Whitespace-only user ID
        assertThatThrownBy(() -> userDeleteService.deleteUser("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID cannot be null or empty");

        // Verify no repository interaction
        verify(userRepository, never()).findById(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test successful deletion of admin user when multiple admins exist.
     * 
     * <p>Validates that admin users can be deleted as long as they are not
     * the last admin and not attempting self-deletion.</p>
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>Admin count verification passes (count &gt; 1)</li>
     *   <li>Self-deletion check passes (different admin performing deletion)</li>
     *   <li>Admin user successfully soft-deleted</li>
     *   <li>Audit fields populated correctly</li>
     * </ul>
     */
    @Test
    @DisplayName("Should successfully delete admin user when multiple admins exist and not self-deletion")
    public void testDeleteAdminUserSucceedsWithMultipleAdmins() {
        // Arrange: Setup admin user to delete (not last admin, not self-deletion)
        User adminToDelete = User.builder()
                .userId("ADMIN002")
                .firstName("Admin")
                .lastName("Two")
                .userType(UserType.ADMIN)
                .password("$2a$10$encoded.password.hash")
                .deleted(false)
                .build();

        when(userRepository.findById("ADMIN002")).thenReturn(Optional.of(adminToDelete));
        when(userRepository.countByUserTypeAndDeletedFalse(UserType.ADMIN)).thenReturn(3L);  // Multiple admins
        when(authentication.getName()).thenReturn(ADMIN_USER_ID);  // Different admin
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Execute deletion
        assertThatNoException().isThrownBy(() -> userDeleteService.deleteUser("ADMIN002"));

        // Assert: Verify successful deletion
        verify(userRepository).save(argThat(user ->
            user.getUserId().equals("ADMIN002") &&
            user.getUserType() == UserType.ADMIN &&
            user.isDeleted() == true &&
            user.getDeletedBy().equals(ADMIN_USER_ID)
        ));
    }
}

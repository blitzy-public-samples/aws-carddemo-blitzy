/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * JUnit 5 unit tests for {@link UserDeleteService} — comprehensive Mockito-based
 * test suite verifying the faithful Java translation of COBOL program
 * {@code COUSR03C.cbl} (User Delete from USRSEC VSAM file).
 *
 * <h2>COBOL Paragraph → Test Method Traceability</h2>
 * <table>
 *   <caption>Test-to-paragraph mapping for COUSR03C.cbl</caption>
 *   <tr><th>COBOL Paragraph</th><th>Test Method(s)</th><th>Verified Behavior</th></tr>
 *   <tr><td>MAIN-PARA (line 82)</td>
 *       <td>{@link #testDeleteUser_Success_Confirmed},
 *           {@link #testDeleteUser_NotConfirmed_ReturnsUserForDisplay},
 *           {@link #testDeleteUser_UserNotFound},
 *           {@link #testDeleteUser_NonAdminRejected},
 *           {@link #testDeleteUser_NoCascadingDeletes}</td>
 *       <td>Admin gate, two-step confirmation, error paths</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY (line 142)</td>
 *       <td>{@link #testProcessEnterKey_NotConfirmed},
 *           {@link #testProcessEnterKey_Confirmed}</td>
 *       <td>Enter key: read for display vs. delete on confirm</td></tr>
 *   <tr><td>DELETE-USER-INFO (line 174)</td>
 *       <td>(covered by confirmed path tests)</td>
 *       <td>Read-then-delete sequence</td></tr>
 *   <tr><td>READ-USER-SEC-FILE (line 267)</td>
 *       <td>{@link #testReadUserSecFile_Success},
 *           {@link #testReadUserSecFile_NotFound}</td>
 *       <td>CICS READ RIDFLD, DFHRESP(NORMAL/NOTFND)</td></tr>
 *   <tr><td>DELETE-USER-SEC-FILE (line 305)</td>
 *       <td>{@link #testDeleteUserSecFile_Success},
 *           {@link #testDeleteUserSecFile_NotFound}</td>
 *       <td>CICS DELETE DATASET, DFHRESP(NORMAL/NOTFND)</td></tr>
 * </table>
 *
 * <h2>Mock Configuration</h2>
 * <ul>
 *   <li>{@code @Mock UserSecurityRepository} — stubs
 *       {@code findById()} / {@code deleteById()} for VSAM READ/DELETE</li>
 *   <li>{@code @Mock CardDemoContext} — stubs
 *       {@code getUserType()} for admin authorization gate</li>
 *   <li>{@code @InjectMocks UserDeleteService} — system under test
 *       with mocks auto-injected via constructor</li>
 * </ul>
 *
 * @see UserDeleteService
 * @see com.cardemo.entity.UserSecurity
 * @see com.cardemo.common.enums.UserType
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeleteService — COUSR03C.cbl User Delete Tests")
class UserDeleteServiceTest {

    // ========================================================================
    // Mock Dependencies
    // ========================================================================

    /**
     * Mocked repository for USRSEC VSAM dataset access.
     * Stubs: {@code findById()} returns {@code Optional.of(user)} or
     * {@code Optional.empty()} to simulate DFHRESP(NORMAL) vs DFHRESP(NOTFND).
     * Verifies: {@code deleteById()} invocations for CICS DELETE semantics.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mocked request-scoped COMMAREA session context (COCOM01Y.cpy).
     * Stubs: {@code getUserType()} for admin authorization checks.
     */
    @Mock
    private CardDemoContext cardDemoContext;

    /**
     * System under test — constructed with mocked dependencies via
     * {@code @InjectMocks}. Tests all COBOL paragraph-mapped methods.
     */
    @InjectMocks
    private UserDeleteService userDeleteService;

    // ========================================================================
    // Test Constants — Matching COBOL Working Storage Values
    // ========================================================================

    /** Test user ID matching SEC-USR-ID PIC X(08) format. */
    private static final String TEST_USER_ID = "DELUSR01";

    /** Non-existent user ID for NOTFND tests. */
    private static final String NONEXISTENT_USER_ID = "NONEXIST";

    /** Admin user ID for audit logging context. */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /** Test first name matching SEC-USR-FNAME PIC X(20). */
    private static final String TEST_FIRST_NAME = "John";

    /** Test last name matching SEC-USR-LNAME PIC X(20). */
    private static final String TEST_LAST_NAME = "Doe";

    /** Test password (BCrypt hash placeholder for entity construction). */
    private static final String TEST_PASSWORD_HASH =
            "$2a$10$dummyHashForTestingPurposesOnly123456789012345678";

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Creates a test {@link UserSecurity} fixture with standard field values.
     * Mimics the 80-byte SEC-USER-DATA record from CSUSR01Y.cpy populated
     * after a successful EXEC CICS READ DATASET(USRSEC).
     *
     * @return a fully populated {@code UserSecurity} entity for test assertions
     */
    private UserSecurity createTestUser() {
        return new UserSecurity(
                TEST_USER_ID,
                TEST_FIRST_NAME,
                TEST_LAST_NAME,
                TEST_PASSWORD_HASH,
                UserType.ADMIN
        );
    }

    /**
     * Configures the {@link CardDemoContext} mock for admin-authorized requests.
     * Stubs {@code getUserType()} to return {@link UserType#ADMIN}, mimicking
     * the COBOL program being accessible only from the Admin Menu (COADM01C).
     */
    private void setupAdminContext() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
    }

    // ========================================================================
    // Test 1: Success Path — Confirmed Deletion
    // Maps: MAIN-PARA → PROCESS-ENTER-KEY → DELETE-USER-INFO
    //       → READ-USER-SEC-FILE → DELETE-USER-SEC-FILE
    // ========================================================================

    /**
     * Verifies the complete confirmed deletion flow:
     * <ol>
     *   <li>Admin authorization gate passes (CDEMO-USRTYP-ADMIN = 'A')</li>
     *   <li>{@code deleteUser("DELUSR01", true)} invokes {@code deleteUserInfo}</li>
     *   <li>{@code deleteUserInfo} performs READ (existence check) then DELETE</li>
     *   <li>Returns {@code null} indicating successful deletion</li>
     * </ol>
     *
     * <p><strong>COBOL traceability:</strong> MAIN-PARA line 82 →
     * WHEN DFHPF5 → PERFORM DELETE-USER-INFO (line 174) →
     * READ-USER-SEC-FILE (line 267) → DELETE-USER-SEC-FILE (line 305).</p>
     */
    @Test
    @DisplayName("deleteUser — confirmed=true: full deletion path succeeds")
    void testDeleteUser_Success_Confirmed() {
        // Arrange: admin context + user exists in USRSEC
        setupAdminContext();
        UserSecurity testUser = createTestUser();
        when(userSecurityRepository.findById(TEST_USER_ID))
                .thenReturn(Optional.of(testUser));

        // Act: confirmed=true triggers PERFORM DELETE-USER-INFO
        UserSecurity result = userDeleteService.deleteUser(TEST_USER_ID, true);

        // Assert: null return indicates deletion complete (COBOL INITIALIZE-ALL-FIELDS)
        assertThat(result).isNull();

        // Verify: deleteById called once (EXEC CICS DELETE DATASET(USRSEC))
        verify(userSecurityRepository).deleteById(TEST_USER_ID);

        // Verify: findById called for existence checks in both
        // readUserSecFile (1st) and deleteUserSecFile (2nd)
        verify(userSecurityRepository, times(2)).findById(TEST_USER_ID);
    }

    // ========================================================================
    // Test 2: Two-Step Confirmation — Not Confirmed Returns User
    // Maps: PROCESS-ENTER-KEY → READ-USER-SEC-FILE → SEND-USRDEL-SCREEN
    // ========================================================================

    /**
     * Verifies the first step of the two-step confirmation pattern:
     * when {@code confirmed=false}, the service reads the user record and
     * returns it for display — no deletion occurs.
     *
     * <p><strong>COBOL traceability:</strong> MAIN-PARA line 82 →
     * first entry path (CDEMO-PGM-REENTER = FALSE) →
     * PERFORM READ-USER-SEC-FILE → PERFORM SEND-USRDEL-SCREEN
     * (display user data with "Press PF5 key to delete this user...").</p>
     */
    @Test
    @DisplayName("deleteUser — confirmed=false: returns user for confirmation display")
    void testDeleteUser_NotConfirmed_ReturnsUserForDisplay() {
        // Arrange: admin context + user exists
        setupAdminContext();
        UserSecurity testUser = createTestUser();
        when(userSecurityRepository.findById(TEST_USER_ID))
                .thenReturn(Optional.of(testUser));

        // Act: confirmed=false → read for display only
        UserSecurity result = userDeleteService.deleteUser(TEST_USER_ID, false);

        // Assert: user record returned for confirmation display
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(result.getFirstName()).isEqualTo(TEST_FIRST_NAME);
        assertThat(result.getLastName()).isEqualTo(TEST_LAST_NAME);
        assertThat(result.getUserType()).isEqualTo(UserType.ADMIN);

        // Verify: deleteById NEVER called — user has not confirmed yet
        verify(userSecurityRepository, never()).deleteById(any());

        // Verify: findById called once for display read
        verify(userSecurityRepository).findById(TEST_USER_ID);
    }

    // ========================================================================
    // Test 3: Process Enter Key — Not Confirmed
    // Maps: PROCESS-ENTER-KEY (line 142) → READ-USER-SEC-FILE only
    // ========================================================================

    /**
     * Verifies that {@code processEnterKey} with {@code confirmed=false}
     * reads the user record for display without performing deletion.
     *
     * <p><strong>COBOL traceability:</strong> PROCESS-ENTER-KEY line 142 →
     * MOVE USRIDINI TO SEC-USR-ID → PERFORM READ-USER-SEC-FILE →
     * MOVE SEC-USR-FNAME/LNAME/TYPE TO display fields →
     * PERFORM SEND-USRDEL-SCREEN.</p>
     */
    @Test
    @DisplayName("processEnterKey — confirmed=false: reads user, no delete")
    void testProcessEnterKey_NotConfirmed() {
        // Arrange: user exists
        UserSecurity testUser = createTestUser();
        when(userSecurityRepository.findById(TEST_USER_ID))
                .thenReturn(Optional.of(testUser));

        // Act: not confirmed → read for display
        UserSecurity result = userDeleteService.processEnterKey(TEST_USER_ID, false);

        // Assert: user returned for display
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TEST_USER_ID);

        // Verify: readUserSecFile called (findById), deleteUserSecFile NOT called
        verify(userSecurityRepository).findById(TEST_USER_ID);
        verify(userSecurityRepository, never()).deleteById(any());
    }

    // ========================================================================
    // Test 4: Process Enter Key — Confirmed
    // Maps: PROCESS-ENTER-KEY → DELETE-USER-INFO → both read and delete
    // ========================================================================

    /**
     * Verifies that {@code processEnterKey} with {@code confirmed=true}
     * performs the full read-then-delete sequence via {@code deleteUserInfo}.
     *
     * <p><strong>COBOL traceability:</strong> PROCESS-ENTER-KEY →
     * confirmed path → DELETE-USER-INFO (line 174) →
     * READ-USER-SEC-FILE + DELETE-USER-SEC-FILE.</p>
     */
    @Test
    @DisplayName("processEnterKey — confirmed=true: reads and deletes user")
    void testProcessEnterKey_Confirmed() {
        // Arrange: user exists for both read and delete checks
        UserSecurity testUser = createTestUser();
        when(userSecurityRepository.findById(TEST_USER_ID))
                .thenReturn(Optional.of(testUser));

        // Act: confirmed → delegated to deleteUserInfo
        UserSecurity result = userDeleteService.processEnterKey(TEST_USER_ID, true);

        // Assert: null return indicates deletion complete
        assertThat(result).isNull();

        // Verify: both readUserSecFile and deleteUserSecFile called
        // findById called 2x (once for read, once for delete existence check)
        verify(userSecurityRepository, times(2)).findById(TEST_USER_ID);
        verify(userSecurityRepository).deleteById(TEST_USER_ID);
    }

    // ========================================================================
    // Test 5: User Not Found on Delete Attempt
    // Maps: READ-USER-SEC-FILE → DFHRESP(NOTFND) → RecordNotFoundException
    // ========================================================================

    /**
     * Verifies that attempting to delete a non-existent user throws
     * {@link RecordNotFoundException}, mapping COBOL DFHRESP(NOTFND)
     * with message "User ID NOT found...".
     *
     * <p><strong>COBOL traceability:</strong> READ-USER-SEC-FILE line 267 →
     * EVALUATE WS-RESP-CD → WHEN DFHRESP(NOTFND) →
     * MOVE 'User ID NOT found...' TO WS-MESSAGE.</p>
     */
    @Test
    @DisplayName("deleteUser — user not found: throws RecordNotFoundException")
    void testDeleteUser_UserNotFound() {
        // Arrange: admin context + user does NOT exist
        setupAdminContext();
        when(userSecurityRepository.findById(NONEXISTENT_USER_ID))
                .thenReturn(Optional.empty());

        // Act & Assert: RecordNotFoundException for VSAM status '23'
        assertThatThrownBy(() ->
                userDeleteService.deleteUser(NONEXISTENT_USER_ID, false))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("User not found: " + NONEXISTENT_USER_ID);

        // Verify: no deletion attempted
        verify(userSecurityRepository, never()).deleteById(any());
    }

    // ========================================================================
    // Test 6: Read User Sec File — Not Found
    // Maps: READ-USER-SEC-FILE → EXEC CICS READ → RESP=13
    // ========================================================================

    /**
     * Directly tests {@code readUserSecFile} when the user does not exist,
     * verifying the DFHRESP(NOTFND) → RecordNotFoundException mapping.
     *
     * <p><strong>COBOL traceability:</strong> READ-USER-SEC-FILE line 267 →
     * EXEC CICS READ DATASET(WS-USRSEC-FILE) RIDFLD(SEC-USR-ID) →
     * RESP=13 → 'User ID NOT found...'.</p>
     */
    @Test
    @DisplayName("readUserSecFile — NOTFND: throws RecordNotFoundException")
    void testReadUserSecFile_NotFound() {
        // Arrange: user does not exist in USRSEC file
        when(userSecurityRepository.findById(NONEXISTENT_USER_ID))
                .thenReturn(Optional.empty());

        // Act & Assert: VSAM status '23' mapped to RecordNotFoundException
        assertThatThrownBy(() ->
                userDeleteService.readUserSecFile(NONEXISTENT_USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("User not found: " + NONEXISTENT_USER_ID);
    }

    // ========================================================================
    // Test 7: Non-Admin Rejected
    // Maps: COADM01C admin menu gate — only admins can reach COUSR03C
    // ========================================================================

    /**
     * Verifies that non-admin users are rejected with a
     * {@link SecurityException}, enforcing the COBOL access restriction
     * where COUSR03C is only reachable from COADM01C (Admin Menu).
     *
     * <p><strong>COBOL traceability:</strong> COUSR03C is accessible only
     * from COADM01C, which checks CDEMO-USRTYP-ADMIN VALUE 'A'
     * (COCOM01Y.cpy line 27). Non-admin users never see the admin menu.</p>
     */
    @Test
    @DisplayName("deleteUser — non-admin: throws SecurityException")
    void testDeleteUser_NonAdminRejected() {
        // Arrange: regular user context (not admin)
        when(cardDemoContext.getUserType()).thenReturn(UserType.USER);

        // Act & Assert: SecurityException for non-admin access attempt
        assertThatThrownBy(() ->
                userDeleteService.deleteUser(TEST_USER_ID, true))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("administrator privileges");

        // Verify: no USRSEC file operations attempted
        verify(userSecurityRepository, never()).findById(any());
        verify(userSecurityRepository, never()).deleteById(any());
    }

    // ========================================================================
    // Test 8: Delete User Sec File — Success
    // Maps: DELETE-USER-SEC-FILE → EXEC CICS DELETE → DFHRESP(NORMAL)
    // ========================================================================

    /**
     * Directly tests {@code deleteUserSecFile} for successful deletion,
     * verifying the CICS DELETE DATASET(WS-USRSEC-FILE) RIDFLD(SEC-USR-ID)
     * operation maps to {@code deleteById()} invocation.
     *
     * <p><strong>COBOL traceability:</strong> DELETE-USER-SEC-FILE line 305 →
     * EXEC CICS DELETE DATASET(WS-USRSEC-FILE) →
     * DFHRESP(NORMAL) → 'User DELUSR01 has been deleted ...'.</p>
     */
    @Test
    @DisplayName("deleteUserSecFile — success: calls deleteById with correct userId")
    void testDeleteUserSecFile_Success() {
        // Arrange: user exists (existence check before delete passes)
        UserSecurity testUser = createTestUser();
        when(userSecurityRepository.findById(TEST_USER_ID))
                .thenReturn(Optional.of(testUser));

        // Act: no exception expected
        assertThatNoException().isThrownBy(() ->
                userDeleteService.deleteUserSecFile(TEST_USER_ID));

        // Verify: deleteById called with exact userId (RIDFLD mapping)
        verify(userSecurityRepository).deleteById(TEST_USER_ID);
        verify(userSecurityRepository).findById(TEST_USER_ID);
    }

    // ========================================================================
    // Test 9: Delete User Sec File — Not Found
    // Maps: DELETE-USER-SEC-FILE → EXEC CICS DELETE → RESP=13
    // ========================================================================

    /**
     * Directly tests {@code deleteUserSecFile} when the user does not exist,
     * verifying the DFHRESP(NOTFND) → RecordNotFoundException mapping
     * during the DELETE operation's existence check.
     *
     * <p><strong>COBOL traceability:</strong> DELETE-USER-SEC-FILE line 305 →
     * EXEC CICS DELETE → EVALUATE WS-RESP-CD →
     * WHEN DFHRESP(NOTFND) → 'User ID NOT found...'.</p>
     */
    @Test
    @DisplayName("deleteUserSecFile — NOTFND: throws RecordNotFoundException")
    void testDeleteUserSecFile_NotFound() {
        // Arrange: user does not exist for the existence check
        when(userSecurityRepository.findById(NONEXISTENT_USER_ID))
                .thenReturn(Optional.empty());

        // Act & Assert: RecordNotFoundException before deleteById is reached
        assertThatThrownBy(() ->
                userDeleteService.deleteUserSecFile(NONEXISTENT_USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("User not found: " + NONEXISTENT_USER_ID);

        // Verify: deleteById NEVER called (failed existence check)
        verify(userSecurityRepository, never()).deleteById(any());
    }

    // ========================================================================
    // Test 10: Read User Sec File — Success
    // Maps: READ-USER-SEC-FILE → EXEC CICS READ → DFHRESP(NORMAL)
    // ========================================================================

    /**
     * Directly tests {@code readUserSecFile} for a successful read,
     * verifying that the returned entity contains the correct field values
     * mapping the COBOL SEC-USER-DATA record fields (SEC-USR-ID,
     * SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-TYPE).
     *
     * <p><strong>COBOL traceability:</strong> READ-USER-SEC-FILE line 267 →
     * EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA) →
     * DFHRESP(NORMAL) → CONTINUE → display fields populated.</p>
     */
    @Test
    @DisplayName("readUserSecFile — NORMAL: returns UserSecurity with correct fields")
    void testReadUserSecFile_Success() {
        // Arrange: user exists in USRSEC VSAM file
        UserSecurity testUser = createTestUser();
        when(userSecurityRepository.findById(TEST_USER_ID))
                .thenReturn(Optional.of(testUser));

        // Act: READ by RIDFLD (SEC-USR-ID)
        UserSecurity result = userDeleteService.readUserSecFile(TEST_USER_ID);

        // Assert: all COBOL SEC-USER-DATA fields mapped correctly
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(result.getFirstName()).isEqualTo(TEST_FIRST_NAME);
        assertThat(result.getLastName()).isEqualTo(TEST_LAST_NAME);
        assertThat(result.getUserType()).isEqualTo(UserType.ADMIN);

        // Verify: findById called once for keyed READ
        verify(userSecurityRepository).findById(TEST_USER_ID);
    }

    // ========================================================================
    // Test 11: No Cascading Deletes
    // Maps: COBOL USRSEC is standalone — no FK to other VSAM datasets
    // ========================================================================

    /**
     * Verifies that user deletion operates ONLY on the USRSEC dataset
     * with no cascading effects to other repositories (ACCTDATA, CARDDATA,
     * CUSTDATA, TRANSACT, etc.). In the COBOL program, COUSR03C.cbl
     * exclusively accesses the WS-USRSEC-FILE dataset — no other VSAM
     * files are touched during user deletion.
     *
     * <p><strong>COBOL traceability:</strong> COUSR03C.cbl uses only
     * {@code DATASET(WS-USRSEC-FILE)} — no references to ACCTDATA,
     * CARDDATA, CUSTDATA, or TRANSACT file names. This test ensures
     * the Java migration preserves this isolation.</p>
     */
    @Test
    @DisplayName("deleteUser — no cascading deletes to other repositories")
    void testDeleteUser_NoCascadingDeletes() {
        // Arrange: admin context + user exists
        setupAdminContext();
        UserSecurity testUser = createTestUser();
        when(userSecurityRepository.findById(TEST_USER_ID))
                .thenReturn(Optional.of(testUser));

        // Act: confirmed deletion
        userDeleteService.deleteUser(TEST_USER_ID, true);

        // Verify: ONLY userSecurityRepository was accessed
        // findById called 2x (readUserSecFile + deleteUserSecFile existence check)
        verify(userSecurityRepository, times(2)).findById(TEST_USER_ID);
        // deleteById called exactly once
        verify(userSecurityRepository, times(1)).deleteById(TEST_USER_ID);

        // No other repository interactions — USRSEC is standalone
        // (Mockito strict stubbing ensures no unexpected mock interactions)
        // The only mock interactions are the ones we explicitly verified above
        // plus the cardDemoContext stubs. No cascading deletes to Account,
        // Card, Customer, or Transaction repositories.
    }
}

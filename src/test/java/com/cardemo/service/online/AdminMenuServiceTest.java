/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
import com.cardemo.common.message.MessageConstants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminMenuService} verifying 100% business logic parity
 * with the COBOL program {@code COADM01C.cbl} — admin-only menu navigation.
 *
 * <h2>Test Coverage Mapping (COADM01C.cbl → AdminMenuService)</h2>
 * <pre>
 * Test 1: EVALUATE Option 1 → COUSR00C (User List)      → {@link #testAdminAccess_Option1_UserList()}
 * Test 2: EVALUATE Option 2 → COUSR01C (User Add)       → {@link #testAdminAccess_Option2_UserAdd()}
 * Test 3: EVALUATE Option 3 → COUSR02C (User Update)    → {@link #testAdminAccess_Option3_UserUpdate()}
 * Test 4: EVALUATE Option 4 → COUSR03C (User Delete)    → {@link #testAdminAccess_Option4_UserDelete()}
 * Test 5: Non-admin user gate → SecurityException        → {@link #testNonAdminAccess_Rejected()}
 * Test 6: WS-OPTION=0 or &gt;4 → InvalidOption error    → {@link #testInvalidOption_OutOfRange()}
 * Test 7: Admin role check passes                        → {@link #testValidateAdminAccess_AdminUser()}
 * Test 8: Regular user role check fails                  → {@link #testValidateAdminAccess_RegularUser()}
 * </pre>
 *
 * <h2>Mock Configuration</h2>
 * <ul>
 *   <li>{@code @Mock CardDemoContext} — mocks the COMMAREA session context
 *       (COCOM01Y.cpy). {@code getUserType()} is stubbed to return
 *       {@link UserType#ADMIN} for authorized tests and {@link UserType#USER}
 *       for rejection tests.</li>
 *   <li>{@code @InjectMocks AdminMenuService} — constructs the service under
 *       test with the mocked context, replacing COBOL {@code DFHCOMMAREA}
 *       linkage section injection.</li>
 * </ul>
 *
 * <h2>COBOL Source References</h2>
 * <ul>
 *   <li>COADM01C.cbl — Main admin menu program (MAIN-PARA, PROCESS-ENTER-KEY)</li>
 *   <li>COADM02Y.cpy — Admin menu option table (4 options, OCCURS 9)</li>
 *   <li>COCOM01Y.cpy — COMMAREA with CDEMO-USER-TYPE 88-level conditions</li>
 *   <li>CSMSG01Y.cpy — CCDA-MSG-INVALID-KEY message constant</li>
 * </ul>
 *
 * @see AdminMenuService
 * @see CardDemoContext
 * @see UserType
 * @see MessageConstants
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMenuService — COADM01C.cbl admin menu unit tests")
class AdminMenuServiceTest {

    /**
     * Mocked request-scoped session context bean.
     * Replaces the COBOL CARDDEMO-COMMAREA (COCOM01Y.cpy) for test isolation.
     * Stubbed methods: {@code getUserType()}, {@code isAdmin()},
     * {@code isReenterContext()}.
     */
    @Mock
    private CardDemoContext cardDemoContext;

    /**
     * Service under test — admin menu handler translating COADM01C.cbl.
     * Constructed by Mockito with the mocked {@link #cardDemoContext}.
     */
    @InjectMocks
    private AdminMenuService adminMenuService;

    // ========================================================================
    // Tests 1–4: Admin option routing (PROCESS-ENTER-KEY paragraph)
    // Maps COADM02Y.cpy options 1–4 → COUSR00C through COUSR03C
    // COBOL: EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
    // ========================================================================

    /**
     * Test 1: Admin selects option 1 — routes to User List program COUSR00C.
     *
     * <p>Maps to COADM01C.cbl PROCESS-ENTER-KEY paragraph (line 115):
     * when {@code WS-OPTION = 1}, the program XCTLs to
     * {@code CDEMO-ADMIN-OPT-PGMNAME(1)} which is "COUSR00C"
     * (from COADM02Y.cpy line 27).</p>
     *
     * <p>Verifies that the COMMAREA navigation fields are set correctly:
     * {@code CDEMO-FROM-TRANID = "CA00"}, {@code CDEMO-FROM-PROGRAM = "COADM01C"},
     * {@code CDEMO-PGM-CONTEXT = 0 (ENTER)}, and
     * {@code CDEMO-TO-PROGRAM = "COUSR00C"}.</p>
     */
    @Test
    @DisplayName("Option 1 routes admin to User List program COUSR00C")
    void testAdminAccess_Option1_UserList() {
        // Arrange: admin user in reenter context (ready to process option)
        setupAdminReenterContext();

        // Act: select option 1 — User List (Security)
        String result = adminMenuService.mainPara(1);

        // Assert: routing target matches COUSR00C (COADM02Y.cpy line 27)
        assertThat(result).isEqualTo("COUSR00C");

        // Verify COMMAREA navigation fields were set (COADM01C.cbl lines 139–145)
        verify(cardDemoContext).setToProgram("COUSR00C");
        verify(cardDemoContext).setFromTranId("CA00");
        verify(cardDemoContext).setFromProgram("COADM01C");
        verify(cardDemoContext).setPgmContext(CardDemoContext.PGM_ENTER);
    }

    /**
     * Test 2: Admin selects option 2 — routes to User Add program COUSR01C.
     *
     * <p>Maps to COADM01C.cbl PROCESS-ENTER-KEY paragraph:
     * {@code CDEMO-ADMIN-OPT-PGMNAME(2)} = "COUSR01C"
     * (from COADM02Y.cpy line 32).</p>
     */
    @Test
    @DisplayName("Option 2 routes admin to User Add program COUSR01C")
    void testAdminAccess_Option2_UserAdd() {
        // Arrange: admin user in reenter context
        setupAdminReenterContext();

        // Act: select option 2 — User Add (Security)
        String result = adminMenuService.mainPara(2);

        // Assert: routing target matches COUSR01C (COADM02Y.cpy line 32)
        assertThat(result).isEqualTo("COUSR01C");

        // Verify COMMAREA navigation fields
        verify(cardDemoContext).setToProgram("COUSR01C");
        verify(cardDemoContext).setFromTranId("CA00");
        verify(cardDemoContext).setFromProgram("COADM01C");
        verify(cardDemoContext).setPgmContext(CardDemoContext.PGM_ENTER);
    }

    /**
     * Test 3: Admin selects option 3 — routes to User Update program COUSR02C.
     *
     * <p>Maps to COADM01C.cbl PROCESS-ENTER-KEY paragraph:
     * {@code CDEMO-ADMIN-OPT-PGMNAME(3)} = "COUSR02C"
     * (from COADM02Y.cpy line 37).</p>
     */
    @Test
    @DisplayName("Option 3 routes admin to User Update program COUSR02C")
    void testAdminAccess_Option3_UserUpdate() {
        // Arrange: admin user in reenter context
        setupAdminReenterContext();

        // Act: select option 3 — User Update (Security)
        String result = adminMenuService.mainPara(3);

        // Assert: routing target matches COUSR02C (COADM02Y.cpy line 37)
        assertThat(result).isEqualTo("COUSR02C");

        // Verify COMMAREA navigation fields
        verify(cardDemoContext).setToProgram("COUSR02C");
        verify(cardDemoContext).setFromTranId("CA00");
        verify(cardDemoContext).setFromProgram("COADM01C");
        verify(cardDemoContext).setPgmContext(CardDemoContext.PGM_ENTER);
    }

    /**
     * Test 4: Admin selects option 4 — routes to User Delete program COUSR03C.
     *
     * <p>Maps to COADM01C.cbl PROCESS-ENTER-KEY paragraph:
     * {@code CDEMO-ADMIN-OPT-PGMNAME(4)} = "COUSR03C"
     * (from COADM02Y.cpy line 42).</p>
     */
    @Test
    @DisplayName("Option 4 routes admin to User Delete program COUSR03C")
    void testAdminAccess_Option4_UserDelete() {
        // Arrange: admin user in reenter context
        setupAdminReenterContext();

        // Act: select option 4 — User Delete (Security)
        String result = adminMenuService.mainPara(4);

        // Assert: routing target matches COUSR03C (COADM02Y.cpy line 42)
        assertThat(result).isEqualTo("COUSR03C");

        // Verify COMMAREA navigation fields
        verify(cardDemoContext).setToProgram("COUSR03C");
        verify(cardDemoContext).setFromTranId("CA00");
        verify(cardDemoContext).setFromProgram("COADM01C");
        verify(cardDemoContext).setPgmContext(CardDemoContext.PGM_ENTER);
    }

    // ========================================================================
    // Test 5: Non-admin access rejection
    // COADM01C.cbl is admin-only — reachable only via admin menu in COBOL.
    // In Java, this is enforced explicitly by validateAdminAccess().
    // ========================================================================

    /**
     * Test 5: Non-admin user is rejected from admin menu with SecurityException.
     *
     * <p>In the COBOL application, COADM01C is only reachable via XCTL from
     * the main menu (COMEN01C) when {@code CDEMO-USRTYP-ADMIN VALUE 'A'}
     * (COCOM01Y.cpy lines 26–28). CICS program-level security enforces this.
     * In Java, {@link AdminMenuService#validateAdminAccess()} makes this
     * check explicit.</p>
     *
     * <p>Mocks {@code getUserType()} to return {@link UserType#USER} and
     * verifies that attempting to access any admin menu option throws
     * {@link SecurityException}.</p>
     */
    @Test
    @DisplayName("Non-admin user rejected from admin menu with SecurityException")
    void testNonAdminAccess_Rejected() {
        // Arrange: regular (non-admin) user
        when(cardDemoContext.getUserType()).thenReturn(UserType.USER);

        // Act & Assert: admin menu access is denied
        assertThatThrownBy(() -> adminMenuService.mainPara(1))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Admin access required");

        // Verify the authorization check was performed (called multiple times:
        // once in the if-condition, once in the log message, once in the exception)
        verify(cardDemoContext, atLeastOnce()).getUserType();
    }

    // ========================================================================
    // Test 6: Invalid option out of range
    // Maps to COADM01C.cbl PROCESS-ENTER-KEY validation (lines 127–134):
    //   IF WS-OPTION IS NOT NUMERIC OR
    //      WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
    //      WS-OPTION = ZEROS
    // ========================================================================

    /**
     * Test 6: Invalid option numbers (0 or &gt;4) throw IllegalArgumentException.
     *
     * <p>Maps to COADM01C.cbl PROCESS-ENTER-KEY validation (lines 127–134):
     * the COBOL program checks that {@code WS-OPTION} is numeric, not zero,
     * and does not exceed {@code CDEMO-ADMIN-OPT-COUNT} (4 from COADM02Y.cpy
     * line 20). Invalid options produce the error message
     * "Please enter a valid option number...".</p>
     *
     * <p>Also verifies the {@link MessageConstants#INVALID_KEY_MESSAGE} constant
     * (CCDA-MSG-INVALID-KEY from CSMSG01Y.cpy) is defined for the separate
     * EVALUATE OTHER branch (COADM01C.cbl line 101) handling unexpected
     * program context.</p>
     */
    @Test
    @DisplayName("Invalid option outside range 1–4 throws IllegalArgumentException")
    void testInvalidOption_OutOfRange() {
        // Arrange: admin user in reenter context
        setupAdminReenterContext();

        // Act & Assert: option 0 — maps to COBOL "WS-OPTION = ZEROS" (line 129)
        assertThatThrownBy(() -> adminMenuService.mainPara(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid option number");

        // Act & Assert: option 5 — exceeds CDEMO-ADMIN-OPT-COUNT=4 (COADM02Y.cpy)
        assertThatThrownBy(() -> adminMenuService.mainPara(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid option number");

        // Verify INVALID_KEY_MESSAGE constant (CSMSG01Y.cpy CCDA-MSG-INVALID-KEY)
        // is properly defined for the EVALUATE OTHER branch (line 101)
        assertThat(MessageConstants.INVALID_KEY_MESSAGE)
                .startsWith("Invalid key pressed");
    }

    // ========================================================================
    // Tests 7–8: validateAdminAccess() direct tests
    // Verifies the explicit admin role gate extracted from implicit COBOL
    // CICS program-level security enforcement.
    // ========================================================================

    /**
     * Test 7: validateAdminAccess() passes without exception for admin user.
     *
     * <p>Verifies that when {@code getUserType()} returns {@link UserType#ADMIN},
     * the method completes normally and returns {@code true} (from
     * {@link CardDemoContext#isAdmin()}). This maps to the implicit
     * authorization gate in COADM01C.cbl where the admin menu program is
     * only reachable when {@code CDEMO-USRTYP-ADMIN} is true.</p>
     */
    @Test
    @DisplayName("validateAdminAccess passes for admin user without exception")
    void testValidateAdminAccess_AdminUser() {
        // Arrange: admin user context
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.isAdmin()).thenReturn(true);

        // Act & Assert: no exception thrown for admin user
        assertThatNoException().isThrownBy(
                () -> adminMenuService.validateAdminAccess());

        // Verify the isAdmin convenience method was invoked
        verify(cardDemoContext).isAdmin();
    }

    /**
     * Test 8: validateAdminAccess() throws SecurityException for regular user.
     *
     * <p>Verifies that when {@code getUserType()} returns {@link UserType#USER},
     * the method throws {@link SecurityException} with a message indicating
     * admin access is required. This is the Java equivalent of the COBOL
     * CICS program-level security rejection.</p>
     */
    @Test
    @DisplayName("validateAdminAccess throws SecurityException for regular user")
    void testValidateAdminAccess_RegularUser() {
        // Arrange: regular (non-admin) user context
        when(cardDemoContext.getUserType()).thenReturn(UserType.USER);

        // Act & Assert: SecurityException thrown for non-admin
        assertThatThrownBy(() -> adminMenuService.validateAdminAccess())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Admin access required");

        // Verify the authorization check was performed (called multiple times:
        // once in the if-condition, once in the log message, once in the exception)
        verify(cardDemoContext, atLeastOnce()).getUserType();
    }

    // ========================================================================
    // Private test helpers
    // ========================================================================

    /**
     * Sets up mocks for an admin user in reenter context.
     *
     * <p>This is the common mock arrangement for tests that exercise the
     * PROCESS-ENTER-KEY paragraph (tests 1–4, 6). Configures:</p>
     * <ul>
     *   <li>{@code getUserType()} → {@link UserType#ADMIN} — passes
     *       the admin role gate</li>
     *   <li>{@code isAdmin()} → {@code true} — used by
     *       {@code validateAdminAccess()} return value</li>
     *   <li>{@code isReenterContext()} → {@code true} — enters the
     *       option-processing branch (COADM01C.cbl lines 91–104)</li>
     * </ul>
     *
     * <p>Note: {@code isEnterContext()} is NOT stubbed — it returns
     * {@code false} by default on a Mockito mock (primitive boolean),
     * which correctly skips the initial-display branch
     * (COADM01C.cbl lines 87–90).</p>
     */
    private void setupAdminReenterContext() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.isReenterContext()).thenReturn(true);
    }
}

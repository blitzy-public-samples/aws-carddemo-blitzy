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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MainMenuService} — COMEN01C.cbl parity verification.
 *
 * <p>Tests the main menu router translated from COMEN01C.cbl PROCEDURE DIVISION,
 * verifying role-based option filtering (← BUILD-MENU-OPTIONS paragraph),
 * XCTL dispatch routing for all 10 options (← PROCESS-ENTER-KEY paragraph),
 * and input validation for out-of-range and invalid key scenarios
 * (← EVALUATE WS-OPTION branches).</p>
 *
 * <p>Menu option data sourced from COMEN02Y.cpy: 10 options, all with
 * CDEMO-MENU-OPT-USRTYPE = 'U' (accessible to both admin and regular users).</p>
 *
 * <p>Mocking strategy: {@link CardDemoContext} is mocked via {@code @Mock}
 * to simulate the 1024-byte CARDDEMO-COMMAREA (COCOM01Y.cpy) session state.
 * {@link MainMenuService} is injected with the mock via {@code @InjectMocks}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MainMenuService — COMEN01C.cbl Main Menu Router Tests")
class MainMenuServiceTest {

    @Mock
    private CardDemoContext cardDemoContext;

    @InjectMocks
    private MainMenuService mainMenuService;

    /**
     * Test 1: Admin user sees all 10 menu options.
     *
     * <p>Maps to: BUILD-MENU-OPTIONS paragraph in COMEN01C.cbl.
     * COMEN02Y.cpy defines CDEMO-MENU-OPT-COUNT = 10 with all options having
     * CDEMO-MENU-OPT-USRTYPE = 'U'. Admin users see all options because the
     * filter condition {@code isAdmin || opt.userType() != adminTypeCode}
     * evaluates to {@code true || (any)} = {@code true}.</p>
     */
    @Test
    @DisplayName("Admin user sees all 10 menu options (← BUILD-MENU-OPTIONS, COMEN02Y.cpy)")
    void testAdminUserSeesAllOptions() {
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        List<String> options = mainMenuService.getMenuOptions();

        assertThat(options).hasSize(10)
                .containsExactly(
                        "1. Account View",
                        "2. Account Update",
                        "3. Credit Card List",
                        "4. Credit Card View",
                        "5. Credit Card Update",
                        "6. Transaction List",
                        "7. Transaction View",
                        "8. Transaction Add",
                        "9. Transaction Reports",
                        "10. Bill Payment"
                );
    }

    /**
     * Test 2: Regular user sees options — verifies role-based filtering logic.
     *
     * <p>Maps to: MAIN-PARA → EVALUATE CDEMO-USRTYP in COMEN01C.cbl (lines 136-143).
     * Since all 10 options in COMEN02Y.cpy have CDEMO-MENU-OPT-USRTYPE = 'U',
     * regular users also see all 10 options. The filter
     * {@code isAdmin || opt.userType() != adminTypeCode} evaluates to
     * {@code false || ('U' != 'A')} = {@code true} for every option.</p>
     */
    @Test
    @DisplayName("Regular user sees all 10 options — all have userType 'U' in COMEN02Y.cpy")
    void testRegularUserSeesLimitedOptions() {
        when(cardDemoContext.isAdmin()).thenReturn(false);
        when(cardDemoContext.getUserType()).thenReturn(UserType.USER);

        List<String> options = mainMenuService.getMenuOptions();

        // All 10 options in COMEN02Y.cpy have CDEMO-MENU-OPT-USRTYPE = 'U',
        // so regular users see all options — no admin-only options exist in the data
        assertThat(options).hasSize(10)
                .contains("1. Account View", "10. Bill Payment")
                .doesNotContain("11. Admin Menu");
    }

    /**
     * Test 3: Option 1 routes to COACTVWC (Account View).
     *
     * <p>Maps to: PROCESS-ENTER-KEY → EVALUATE WS-OPTION → WHEN 1 in COMEN01C.cbl.
     * Verifies XCTL dispatch sets navigation context (fromTranId, fromProgram,
     * pgmContext) and returns the target program name.</p>
     */
    @Test
    @DisplayName("Option 1 routes to COACTVWC — Account View (← XCTL dispatch)")
    void testValidOptionRouting_AccountView() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        String target = mainMenuService.mainPara(1);

        assertThat(target).isEqualTo("COACTVWC");
        // Verify COMMAREA navigation context is set (← COMEN01C.cbl XCTL prep)
        verify(cardDemoContext).setFromTranId("CM00");
        verify(cardDemoContext).setFromProgram("COMEN01C");
        verify(cardDemoContext).setPgmContext(CardDemoContext.PGM_ENTER);
    }

    /**
     * Test 4: Option 2 routes to COACTUPC (Account Update).
     *
     * <p>Maps to: PROCESS-ENTER-KEY → EVALUATE WS-OPTION → WHEN 2 in COMEN01C.cbl.
     * XCTL to COACTUPC for account update functionality.</p>
     */
    @Test
    @DisplayName("Option 2 routes to COACTUPC — Account Update (← XCTL dispatch)")
    void testValidOptionRouting_AccountUpdate() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        String target = mainMenuService.mainPara(2);

        assertThat(target).isEqualTo("COACTUPC");
    }

    /**
     * Test 5: Option 3 routes to COCRDLIC (Credit Card List).
     *
     * <p>Maps to: PROCESS-ENTER-KEY → EVALUATE WS-OPTION → WHEN 3 in COMEN01C.cbl.
     * XCTL to COCRDLIC for credit card list/search functionality.</p>
     */
    @Test
    @DisplayName("Option 3 routes to COCRDLIC — Credit Card List (← XCTL dispatch)")
    void testValidOptionRouting_CreditCardList() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        String target = mainMenuService.mainPara(3);

        assertThat(target).isEqualTo("COCRDLIC");
    }

    /**
     * Test 6: Transaction List routing verification.
     *
     * <p>Maps to: PROCESS-ENTER-KEY → EVALUATE WS-OPTION → WHEN 6 in COMEN01C.cbl.
     * Option 6 in the menu data (COMEN02Y.cpy) routes to COTRN00C — the
     * Transaction List program.</p>
     */
    @Test
    @DisplayName("Option 6 routes to COTRN00C — Transaction List (← XCTL dispatch)")
    void testValidOptionRouting_TransactionList() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        String target = mainMenuService.mainPara(6);

        assertThat(target).isEqualTo("COTRN00C");
    }

    /**
     * Test 7: Transaction Reports routing verification.
     *
     * <p>Maps to: PROCESS-ENTER-KEY → EVALUATE WS-OPTION → WHEN 9 in COMEN01C.cbl.
     * Option 9 in the menu data (COMEN02Y.cpy) routes to CORPT00C — the
     * Transaction Reports program.</p>
     */
    @Test
    @DisplayName("Option 9 routes to CORPT00C — Transaction Reports (← XCTL dispatch)")
    void testValidOptionRouting_Reports() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        String target = mainMenuService.mainPara(9);

        assertThat(target).isEqualTo("CORPT00C");
    }

    /**
     * Test 8: Bill Payment routing verification.
     *
     * <p>Maps to: PROCESS-ENTER-KEY → EVALUATE WS-OPTION → WHEN 10 in COMEN01C.cbl.
     * Option 10 in the menu data (COMEN02Y.cpy) routes to COBIL00C — the
     * Bill Payment program.</p>
     */
    @Test
    @DisplayName("Option 10 routes to COBIL00C — Bill Payment (← XCTL dispatch)")
    void testValidOptionRouting_BillPayment() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        String target = mainMenuService.mainPara(10);

        assertThat(target).isEqualTo("COBIL00C");
    }

    /**
     * Test 9: Admin user passes validation for all options.
     *
     * <p>Maps to: PROCESS-ENTER-KEY lines 136-143 in COMEN01C.cbl — role-based
     * authorization check. Admin users pass the {@code validateOption()} check
     * for every option since the filter is {@code isAdmin || ...}.</p>
     *
     * <p>Uses {@code assertThatNoException} to verify that the validation and
     * routing dispatch complete without throwing any unchecked exceptions.</p>
     */
    @Test
    @DisplayName("Admin user passes validation for all options (← PROCESS-ENTER-KEY line 136)")
    void testAdminMenuOption_AdminUser() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        // Admin passes role validation for boundary options
        assertThatNoException().isThrownBy(() -> mainMenuService.validateOption(1));
        assertThatNoException().isThrownBy(() -> mainMenuService.validateOption(10));

        // Explicit null check: no error message for valid options
        assertThat(mainMenuService.validateOption(5)).isNull();

        // mainPara routes successfully for admin
        assertThat(mainMenuService.mainPara(10)).isEqualTo("COBIL00C");
    }

    /**
     * Test 10: Regular user accessing option 7 — role-based filter verification.
     *
     * <p>Maps to: COMEN01C.cbl lines 136-143 (EVALUATE CDEMO-USRTYP).
     * In the COBOL source, a non-admin selecting an admin-only option would
     * be rejected. However, all 10 options in COMEN02Y.cpy have
     * CDEMO-MENU-OPT-USRTYPE = 'U', so the admin-only rejection path is
     * not triggered for any currently defined option.</p>
     *
     * <p>This test verifies that regular users can access option 7
     * (Transaction View → COTRN01C) since it has userType 'U'.</p>
     */
    @Test
    @DisplayName("Regular user accessing option 7 — succeeds since userType='U' (← line 137)")
    void testAdminMenuOption_RegularUser_Rejected() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.USER);

        // All 10 options in COMEN02Y.cpy have CDEMO-MENU-OPT-USRTYPE = 'U',
        // so the admin-only rejection path (COMEN01C.cbl lines 136-143) is
        // not triggered. Option 7 (Transaction View) is accessible to all users.
        String validationResult = mainMenuService.validateOption(7);
        assertThat(validationResult).isNull();

        // mainPara returns the expected target program for regular user
        String target = mainMenuService.mainPara(7);
        assertThat(target).isEqualTo("COTRN01C");
    }

    /**
     * Test 11: Out-of-range and invalid options are handled gracefully.
     *
     * <p>Maps to: PROCESS-ENTER-KEY lines 127-134 in COMEN01C.cbl — the
     * EVALUATE WS-OPTION WHEN OTHER branch handles invalid option numbers.
     * Also covers the EVALUATE EIBAID WHEN OTHER branch for unrecognized
     * AID keys (negative option values).</p>
     *
     * <p>Verifies that {@code validateOption()} returns descriptive error
     * messages for out-of-range inputs, that {@code mainPara()} returns null
     * (redisplay menu) for invalid options, and that the
     * {@link MessageConstants#INVALID_KEY_MESSAGE} constant is properly
     * defined for unrecognized key handling.</p>
     */
    @Test
    @DisplayName("Out-of-range and invalid options handled gracefully (← EVALUATE WS-OPTION WHEN OTHER)")
    void testInvalidOption_OutOfRange() {
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);

        // Range validation: option must be 1-10 (← COMEN02Y.cpy CDEMO-MENU-OPT-COUNT)
        String errorMsg = mainMenuService.validateOption(0);
        assertThat(errorMsg).isNotNull().contains("valid option");

        errorMsg = mainMenuService.validateOption(11);
        assertThat(errorMsg).isNotNull().contains("valid option");

        // mainPara returns null for out-of-range (redisplay menu screen)
        assertThat(mainMenuService.mainPara(11)).isNull();

        // Negative option represents unrecognized AID key
        // (← COMEN01C.cbl EVALUATE EIBAID WHEN OTHER)
        // Service logs INVALID_KEY_MESSAGE (← CSMSG01Y.cpy CCDA-MSG-INVALID-KEY)
        assertThat(MessageConstants.INVALID_KEY_MESSAGE).contains("Invalid key");
        assertThat(mainMenuService.mainPara(-1)).isNull();

        // Null context protection: service requires non-null CardDemoContext
        assertThatThrownBy(() -> new MainMenuService(null).mainPara(1))
                .isInstanceOf(NullPointerException.class);
    }
}

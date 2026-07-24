package com.carddemo.common.constant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification locking the CardDemo admin and main menu option tables.
 *
 * :purpose: Verify, with spec-literal fidelity, that {@link MenuOptions} reproduces the
 *     COBOL copybooks ``COADM02Y`` (group ``01 CARDDEMO-ADMIN-MENU-OPTIONS``) and
 *     ``COMEN02Y`` (group ``01 CARDDEMO-MAIN-MENU-OPTIONS``): the declared option counts,
 *     the ordered 35-character display labels, the 8-character legacy program ids that
 *     back api-gateway menu routing, the per-row user-type, and list immutability.
 * :output: JUnit 5 / AssertJ assertions only; the class holds no state and touches no
 *     database, Spring context, or other external resource (pure JDK logic).
 */
final class MenuOptionsTest {

    @Test
    @DisplayName("Count constants equal their list sizes (admin=4, main=10)")
    void countConstantsMatchListSizes() {
        assertThat(MenuOptions.CDEMO_ADMIN_OPT_COUNT).isEqualTo(4);
        assertThat(MenuOptions.ADMIN_MENU_OPTIONS).hasSize(4);
        assertThat(MenuOptions.CDEMO_MENU_OPT_COUNT).isEqualTo(10);
        assertThat(MenuOptions.MAIN_MENU_OPTIONS).hasSize(10);
    }

    @Test
    @DisplayName("Admin program ids preserve COBOL order (COUSR00C..COUSR03C)")
    void adminProgramOrderIsPreserved() {
        assertThat(MenuOptions.ADMIN_MENU_OPTIONS.stream()
                        .map(MenuOptions.MenuOption::programName)
                        .toList())
                .containsExactly("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");
    }

    @Test
    @DisplayName("Main program ids preserve COBOL order (COACTVWC..COBIL00C)")
    void mainProgramOrderIsPreserved() {
        assertThat(MenuOptions.MAIN_MENU_OPTIONS.stream()
                        .map(MenuOptions.MenuOption::programName)
                        .toList())
                .containsExactly("COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC", "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");
    }

    @Test
    @DisplayName("Every option label is exactly 35 characters (PIC X(35))")
    void everyOptionNameIs35Chars() {
        assertThat(MenuOptions.ADMIN_MENU_OPTIONS)
                .allSatisfy(o -> assertThat(o.optionName()).hasSize(35));
        assertThat(MenuOptions.MAIN_MENU_OPTIONS)
                .allSatisfy(o -> assertThat(o.optionName()).hasSize(35));
    }

    @Test
    @DisplayName("Every program id is exactly 8 characters (PIC X(08))")
    void everyProgramNameIs8Chars() {
        assertThat(MenuOptions.ADMIN_MENU_OPTIONS)
                .allSatisfy(o -> assertThat(o.programName()).hasSize(8));
        assertThat(MenuOptions.MAIN_MENU_OPTIONS)
                .allSatisfy(o -> assertThat(o.programName()).hasSize(8));
    }

    @Test
    @DisplayName("Main menu user-types are all \"U\"")
    void mainMenuUserTypesAreAllU() {
        assertThat(MenuOptions.MAIN_MENU_OPTIONS)
                .allSatisfy(o -> assertThat(o.userType()).isEqualTo("U"));
    }

    @Test
    @DisplayName("Admin menu user-types are null (COADM02Y has no USRTYPE field)")
    void adminMenuUserTypesAreNull() {
        assertThat(MenuOptions.ADMIN_MENU_OPTIONS)
                .allSatisfy(o -> assertThat(o.userType()).isNull());
    }

    @Test
    @DisplayName("Option 8 uses the active Transaction Add label, not the commented (Admin Only) alternate")
    void option8UsesActiveTransactionAddLabel() {
        assertThat(MenuOptions.MAIN_MENU_OPTIONS.get(7).optionName())
                .isEqualTo("Transaction Add                    ")
                .hasSize(35);
        assertThat(MenuOptions.MAIN_MENU_OPTIONS.get(7).optionName())
                .doesNotContain("Admin Only");
    }

    @Test
    @DisplayName("Admin labels are verbatim and ordered")
    void adminLabelsAreVerbatim() {
        assertThat(MenuOptions.ADMIN_MENU_OPTIONS.stream()
                        .map(MenuOptions.MenuOption::optionName)
                        .toList())
                .containsExactly(
                        "User List (Security)               ",
                        "User Add (Security)                ",
                        "User Update (Security)             ",
                        "User Delete (Security)             ");
    }

    @Test
    @DisplayName("Main labels are verbatim and ordered")
    void mainLabelsAreVerbatim() {
        assertThat(MenuOptions.MAIN_MENU_OPTIONS.stream()
                        .map(MenuOptions.MenuOption::optionName)
                        .toList())
                .containsExactly(
                        "Account View                       ",
                        "Account Update                     ",
                        "Credit Card List                   ",
                        "Credit Card View                   ",
                        "Credit Card Update                 ",
                        "Transaction List                   ",
                        "Transaction View                   ",
                        "Transaction Add                    ",
                        "Transaction Reports                ",
                        "Bill Payment                       ");
    }

    @Test
    @DisplayName("Both option tables are immutable (List.of)")
    void listsAreImmutable() {
        assertThatThrownBy(() -> MenuOptions.ADMIN_MENU_OPTIONS.add(
                        new MenuOptions.MenuOption(99, "x", "y", null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> MenuOptions.MAIN_MENU_OPTIONS.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

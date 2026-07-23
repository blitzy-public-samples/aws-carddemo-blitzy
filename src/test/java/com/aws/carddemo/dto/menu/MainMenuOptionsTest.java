package com.aws.carddemo.dto.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MainMenuOptions}.
 *
 * <p>Oracle: legacy/cpy/COMEN02Y.cpy ({@code CARDDEMO-MAIN-MENU-OPTIONS}); source-branch
 * {@code app/cpy/COMEN02Y.cpy}. These tests lock the compile-time main-menu option list
 * translated one-for-one from the COBOL working-storage copybook against the verified
 * COBOL byte layout.</p>
 *
 * <p>What this guards, and why it matters:</p>
 * <ul>
 *   <li><strong>Count vs. capacity split.</strong> COBOL {@code CDEMO-MENU-OPT-COUNT PIC 9(02)
 *       VALUE 10} yields {@link MainMenuOptions#OPTION_COUNT} = 10 populated options, while the
 *       redefined array {@code CDEMO-MENU-OPT OCCURS 12 TIMES} yields
 *       {@link MainMenuOptions#MAX_OPTIONS} = 12 capacity. These are asserted separately to
 *       preserve that distinction.</li>
 *   <li><strong>Option-8 active label.</strong> In the copybook, option 8's name line appears
 *       twice: a commented-out {@code * 'Transaction Add (Admin Only)       '} (INACTIVE) and the
 *       active {@code 'Transaction Add                    '}. The stored display name MUST be
 *       {@code "Transaction Add"}, never {@code "Transaction Add (Admin Only)"}. A dedicated test
 *       enforces this.</li>
 *   <li><strong>User-type gating.</strong> Every one of the 10 options carries
 *       {@code CDEMO-MENU-OPT-USRTYPE = "U"}, which feeds the A/U role gating handled downstream
 *       by the menu service and Spring Security.</li>
 * </ul>
 *
 * <p>Pure unit test: no Spring context, no database, no Testcontainers. The class under test is
 * instantiated directly via {@code new MainMenuOptions()} and every assertion is made against
 * public return values / record components.</p>
 */
class MainMenuOptionsTest {

    /** Class under test; a plain-Java model with an implicit public no-arg constructor. */
    private final MainMenuOptions options = new MainMenuOptions();

    /**
     * The COBOL oracle, expressed as the exact ordered option data
     * ({@code CDEMO-MENU-OPTIONS-DATA}). Names are stored trimmed of the {@code PIC X(35)}
     * trailing padding; program names are the {@code PIC X(08)} targets; every user type is
     * {@code "U"} ({@code PIC X(01)}).
     */
    private static final List<MainMenuOptions.MainMenuOption> EXPECTED_OPTIONS = List.of(
            new MainMenuOptions.MainMenuOption(1, "Account View", "COACTVWC", "U"),
            new MainMenuOptions.MainMenuOption(2, "Account Update", "COACTUPC", "U"),
            new MainMenuOptions.MainMenuOption(3, "Credit Card List", "COCRDLIC", "U"),
            new MainMenuOptions.MainMenuOption(4, "Credit Card View", "COCRDSLC", "U"),
            new MainMenuOptions.MainMenuOption(5, "Credit Card Update", "COCRDUPC", "U"),
            new MainMenuOptions.MainMenuOption(6, "Transaction List", "COTRN00C", "U"),
            new MainMenuOptions.MainMenuOption(7, "Transaction View", "COTRN01C", "U"),
            new MainMenuOptions.MainMenuOption(8, "Transaction Add", "COTRN02C", "U"),
            new MainMenuOptions.MainMenuOption(9, "Transaction Reports", "CORPT00C", "U"),
            new MainMenuOptions.MainMenuOption(10, "Bill Payment", "COBIL00C", "U"));

    @Test
    @DisplayName("Count constant is 10 (VALUE 10) and capacity constant is 12 (OCCURS 12 TIMES)")
    void constantsReflectCobolCountAndCapacity() {
        // Populated count (COBOL CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10) via accessor and constant.
        assertThat(options.getOptionCount()).isEqualTo(10);
        assertThat(MainMenuOptions.OPTION_COUNT).isEqualTo(10);

        // Array capacity (COBOL CDEMO-MENU-OPT OCCURS 12 TIMES) via accessor and constant.
        assertThat(options.getMaxOptions()).isEqualTo(12);
        assertThat(MainMenuOptions.MAX_OPTIONS).isEqualTo(12);

        // Field widths from COBOL pictures: CDEMO-MENU-OPT-NAME PIC X(35), CDEMO-MENU-OPT-PGMNAME PIC X(08).
        assertThat(MainMenuOptions.NAME_WIDTH).isEqualTo(35);
        assertThat(MainMenuOptions.PROGRAM_NAME_WIDTH).isEqualTo(8);
    }

    @Test
    @DisplayName("Options list has exactly 10 entries, matching the populated-option count")
    void optionsListSizeMatchesPopulatedCount() {
        assertThat(options.getOptions()).hasSize(10);
        // The list size must stay consistent with the reported populated count.
        assertThat(options.getOptions()).hasSize(options.getOptionCount());
    }

    @Test
    @DisplayName("All 10 options match the COBOL oracle exactly, in order")
    void optionsMatchCobolOracleExactlyInOrder() {
        // Records provide value equality, so exact element-by-element comparison also verifies order.
        assertThat(options.getOptions()).containsExactlyElementsOf(EXPECTED_OPTIONS);
    }

    @Test
    @DisplayName("Option 8 stores the active 'Transaction Add' label, never the commented '(Admin Only)' variant")
    void optionEightIsActiveTransactionAddLabelNotAdminOnly() {
        // In COMEN02Y.cpy the '(Admin Only)' label is a COBOL comment (leading '*'); the active
        // VALUE is plain 'Transaction Add'. This is the most parity-critical single assertion.
        MainMenuOptions.MainMenuOption optionEight = options.getOptions().get(7);
        assertThat(optionEight.number()).isEqualTo(8);
        assertThat(optionEight.name()).isEqualTo("Transaction Add");
        assertThat(optionEight.name()).doesNotContain("Admin Only");
        assertThat(optionEight.programName()).isEqualTo("COTRN02C");
    }

    @Test
    @DisplayName("Option numbers are exactly 1..10 in order")
    void optionNumbersAreSequentialOneThroughTen() {
        assertThat(options.getOptions())
                .extracting(MainMenuOptions.MainMenuOption::number)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    @DisplayName("Every option requires user type 'U'")
    void everyOptionRequiresUserTypeU() {
        // All 10 options carry CDEMO-MENU-OPT-USRTYPE = 'U'; this drives downstream A/U role gating.
        assertThat(options.getOptions())
                .allSatisfy(option -> assertThat(option.requiredUserType()).isEqualTo("U"));
    }

    @Test
    @DisplayName("Program names are 8 chars and user types are 1 char (COBOL X(08)/X(01) widths)")
    void programAndUserTypeFieldWidthsMatchCobolPictures() {
        assertThat(options.getOptions()).allSatisfy(option -> {
            assertThat(option.programName()).hasSize(8);
            assertThat(option.requiredUserType()).hasSize(1);
        });
    }

    @Test
    @DisplayName("Options list is immutable")
    void optionsListIsImmutable() {
        // The production model exposes the seeded options via List.of(...), which is unmodifiable.
        assertThatThrownBy(() -> options.getOptions()
                .add(new MainMenuOptions.MainMenuOption(11, "Should Fail", "XXXXXXXX", "U")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

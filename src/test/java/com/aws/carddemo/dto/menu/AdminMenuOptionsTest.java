package com.aws.carddemo.dto.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AdminMenuOptions}.
 *
 * <p>Oracle: legacy/cpy/COADM02Y.cpy (CARDDEMO-ADMIN-MENU-OPTIONS).</p>
 *
 * <p>These tests lock the admin-menu option list produced by the compile-time
 * model {@link AdminMenuOptions} against the COBOL working-storage copybook
 * {@code COADM02Y} it translates. The admin menu renders without any database
 * round-trip, so the four seeded rows (option number, display label, and the
 * eight-character {@code COUSR0xC} target program that routes each option) ARE
 * the menu contract; any drift from the copybook is a defect and must be caught
 * here.</p>
 *
 * <p>The COBOL {@code OCCURS 9 TIMES} declares an array capacity of nine
 * ({@link AdminMenuOptions#MAX_OPTIONS}) while {@code CDEMO-ADMIN-OPT-COUNT
 * PIC 9(02) VALUE 4} records that only four rows are populated
 * ({@link AdminMenuOptions#OPTION_COUNT}); both are asserted separately to
 * preserve that COBOL distinction.</p>
 *
 * <p>This is a pure unit test: it instantiates {@link AdminMenuOptions}
 * directly and touches no database, Spring context, or Testcontainers.</p>
 */
class AdminMenuOptionsTest {

    /**
     * Count and capacity constants match the COBOL oracle: four populated
     * options ({@code CDEMO-ADMIN-OPT-COUNT VALUE 4}) within a nine-element
     * array capacity ({@code CDEMO-ADMIN-OPT OCCURS 9 TIMES}). Asserted via the
     * instance accessors (primary) and the public static constants (for
     * completeness).
     */
    @Test
    void option_count_and_capacity_match_cobol_oracle() {
        AdminMenuOptions opts = new AdminMenuOptions();

        // Populated rows: COBOL CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4.
        assertThat(opts.getOptionCount()).isEqualTo(4);
        // Array capacity: COBOL CDEMO-ADMIN-OPT OCCURS 9 TIMES.
        assertThat(opts.getMaxOptions()).isEqualTo(9);

        // The same values are also exposed as public static final constants.
        assertThat(AdminMenuOptions.OPTION_COUNT).isEqualTo(4);
        assertThat(AdminMenuOptions.MAX_OPTIONS).isEqualTo(9);
    }

    /**
     * The populated option list has exactly {@code OPTION_COUNT} elements, and
     * its size is consistent with {@link AdminMenuOptions#getOptionCount()}.
     */
    @Test
    void options_list_size_matches_populated_count() {
        AdminMenuOptions opts = new AdminMenuOptions();

        assertThat(opts.getOptions()).hasSize(4);
        assertThat(opts.getOptions()).hasSize(opts.getOptionCount());
    }

    /**
     * The four options match the COBOL oracle exactly, in copybook order.
     * Record value-equality verifies the option number, trimmed display name,
     * and eight-character target program of every row simultaneously.
     */
    @Test
    void options_match_cobol_oracle_exactly_in_order() {
        AdminMenuOptions opts = new AdminMenuOptions();

        List<AdminMenuOptions.AdminMenuOption> expected = List.of(
                option(1, "User List (Security)", "COUSR00C"),
                option(2, "User Add (Security)", "COUSR01C"),
                option(3, "User Update (Security)", "COUSR02C"),
                option(4, "User Delete (Security)", "COUSR03C"));

        assertThat(opts.getOptions()).containsExactlyElementsOf(expected);
    }

    /**
     * Structural invariants of the option rows: the numbers are exactly
     * {@code 1, 2, 3, 4} in order, and every target program name is eight
     * characters wide (COBOL {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)}).
     */
    @Test
    void option_numbers_and_program_name_widths_are_invariant() {
        AdminMenuOptions opts = new AdminMenuOptions();

        assertThat(opts.getOptions())
                .extracting(AdminMenuOptions.AdminMenuOption::number)
                .containsExactly(1, 2, 3, 4);

        // COBOL CDEMO-ADMIN-OPT-PGMNAME PIC X(08): every program id is 8 chars.
        assertThat(opts.getOptions())
                .allSatisfy(o -> assertThat(o.programName()).hasSize(8));
    }

    /**
     * The list returned by {@link AdminMenuOptions#getOptions()} is immutable:
     * attempting to add an element throws {@link UnsupportedOperationException}.
     * This protects the compile-time menu contract from accidental mutation.
     */
    @Test
    void options_list_is_immutable() {
        AdminMenuOptions opts = new AdminMenuOptions();
        List<AdminMenuOptions.AdminMenuOption> options = opts.getOptions();
        AdminMenuOptions.AdminMenuOption extra =
                option(99, "Should Not Be Added", "COUSRXXC");

        assertThatThrownBy(() -> options.add(extra))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Builds one expected {@link AdminMenuOptions.AdminMenuOption} row, keeping
     * the oracle assertions above concise and readable.
     *
     * @param number      1-based option number
     * @param name        trimmed display label
     * @param programName eight-character target program
     * @return the constructed option row
     */
    private static AdminMenuOptions.AdminMenuOption option(int number, String name, String programName) {
        return new AdminMenuOptions.AdminMenuOption(number, name, programName);
    }
}

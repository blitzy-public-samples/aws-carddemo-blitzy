package com.aws.carddemo.dto.menu;

import java.util.List;

/**
 * Admin-menu option list for CardDemo.
 *
 * <p>Origin: legacy/cpy/COADM02Y.cpy (CARDDEMO-ADMIN-MENU-OPTIONS).</p>
 *
 * <p>Faithful Java translation of the COBOL working-storage copybook
 * {@code COADM02Y} (group {@code CARDDEMO-ADMIN-MENU-OPTIONS}), which the
 * admin-menu program {@code COADM01C} includes via {@code COPY COADM02Y} to
 * drive the numbered administration menu. In the target application this model
 * is consumed by the admin-menu service and controller to render the option
 * list and to route the chosen option to its target program/route.</p>
 *
 * <p>These are compile-time menu definitions: the seeded options ARE the menu
 * contract carried in the copybook, so the admin menu renders without any
 * database round-trip. Role gating (admin-only visibility) is enforced by the
 * menu service and Spring Security, not by this model.</p>
 *
 * <p>COBOL layout reproduced (verbatim reference):</p>
 * <pre>
 * 01 CARDDEMO-ADMIN-MENU-OPTIONS.
 *    05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 4.
 *    05 CDEMO-ADMIN-OPTIONS-DATA.        (four seeded option rows)
 *    05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.
 *       10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.
 *          15 CDEMO-ADMIN-OPT-NUM     PIC 9(02).
 *          15 CDEMO-ADMIN-OPT-NAME    PIC X(35).
 *          15 CDEMO-ADMIN-OPT-PGMNAME PIC X(08).
 * </pre>
 *
 * <p>The COBOL {@code OCCURS 9 TIMES} declares an array capacity of nine
 * (see {@link #MAX_OPTIONS}), while {@code CDEMO-ADMIN-OPT-COUNT} records that
 * only four rows are populated (see {@link #OPTION_COUNT}); the four seeded
 * rows are preserved here in their original order and numbering.</p>
 */
public final class AdminMenuOptions {

    /** Array capacity from COBOL {@code CDEMO-ADMIN-OPT OCCURS 9 TIMES}. */
    public static final int MAX_OPTIONS = 9;

    /** Populated-option count from COBOL {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}. */
    public static final int OPTION_COUNT = 4;

    /** Display-name field width from COBOL {@code CDEMO-ADMIN-OPT-NAME PIC X(35)}. */
    public static final int NAME_WIDTH = 35;

    /** Program-name field width from COBOL {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)}. */
    public static final int PROGRAM_NAME_WIDTH = 8;

    /**
     * Immutable, seeded admin-menu options (numbers 1..4).
     *
     * <p>Display names are stored trimmed of the trailing spaces that pad the
     * COBOL {@code X(35)} field; the logical field width is documented by
     * {@link #NAME_WIDTH}. Program names are the exact eight-character
     * {@code X(08)} values.</p>
     */
    private static final List<AdminMenuOption> OPTIONS = List.of(
            new AdminMenuOption(1, "User List (Security)",   "COUSR00C"),
            new AdminMenuOption(2, "User Add (Security)",    "COUSR01C"),
            new AdminMenuOption(3, "User Update (Security)", "COUSR02C"),
            new AdminMenuOption(4, "User Delete (Security)", "COUSR03C"));

    /**
     * Returns the immutable list of populated admin-menu options.
     *
     * <p>The returned list is already unmodifiable (produced by
     * {@link List#of(Object...)}) and contains exactly {@link #OPTION_COUNT}
     * options in their COBOL-defined order.</p>
     *
     * @return the immutable, ordered list of populated admin-menu options
     */
    public List<AdminMenuOption> getOptions() {
        return OPTIONS;
    }

    /**
     * Returns the number of populated menu options.
     *
     * @return the populated-option count (COBOL {@code CDEMO-ADMIN-OPT-COUNT}), always {@value #OPTION_COUNT}
     */
    public int getOptionCount() {
        return OPTION_COUNT;
    }

    /**
     * Returns the COBOL array capacity for menu options.
     *
     * @return the maximum option capacity (COBOL {@code OCCURS 9 TIMES}), always {@value #MAX_OPTIONS}
     */
    public int getMaxOptions() {
        return MAX_OPTIONS;
    }

    /**
     * One admin-menu option.
     *
     * <p>Mirrors COBOL {@code CDEMO-ADMIN-OPT}, a three-field record whose
     * components are preserved exactly:</p>
     * <ul>
     *   <li>{@code CDEMO-ADMIN-OPT-NUM PIC 9(02)} - the 1-based option number,</li>
     *   <li>{@code CDEMO-ADMIN-OPT-NAME PIC X(35)} - the display label (stored trimmed),</li>
     *   <li>{@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} - the eight-character target program.</li>
     * </ul>
     *
     * @param number      1-based option number (COBOL {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)})
     * @param name        display label, {@code X(35)} width stored trimmed (COBOL {@code CDEMO-ADMIN-OPT-NAME})
     * @param programName eight-character target program (COBOL {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)})
     */
    public record AdminMenuOption(int number, String name, String programName) {
    }
}

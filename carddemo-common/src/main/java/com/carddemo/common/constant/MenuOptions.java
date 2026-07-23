package com.carddemo.common.constant;

import java.util.List;

/**
 * Verbatim CardDemo admin and main menu option tables transformed from the COBOL
 * copybooks ``COADM02Y`` and ``COMEN02Y``; back api-gateway menu routing and
 * role-gating.
 *
 * :output: Two immutable option tables, :java:field:`ADMIN_MENU_OPTIONS` (4 rows)
 *     and :java:field:`MAIN_MENU_OPTIONS` (10 rows), each row a :java:type:`MenuOption`
 *     mapping a 35-character display label to an 8-character legacy program id and,
 *     for the main menu, an allowed user-type.
 */
public final class MenuOptions {

    /** Non-instantiable constant holder. */
    private MenuOptions() {
    }

    /**
     * A single menu option row.
     *
     * :param optionNumber: one-based option number (COBOL ``CDEMO-*-OPT-NUM``).
     * :param optionName: 35-character display label (COBOL ``CDEMO-*-OPT-NAME``, ``PIC X(35)``).
     * :param programName: 8-character legacy program id (COBOL ``CDEMO-*-OPT-PGMNAME``, ``PIC X(08)``).
     * :param userType: allowed user-type (COBOL ``CDEMO-MENU-OPT-USRTYPE``, ``PIC X(01)``);
     *     ``null`` for admin rows, which have no user-type field in the copybook.
     */
    public record MenuOption(int optionNumber, String optionName, String programName, String userType) {
    }

    /** Declared admin option count (COBOL ``CDEMO-ADMIN-OPT-COUNT``). */
    public static final int CDEMO_ADMIN_OPT_COUNT = 4;

    /** Declared main option count (COBOL ``CDEMO-MENU-OPT-COUNT``). */
    public static final int CDEMO_MENU_OPT_COUNT = 10;

    /**
     * Admin menu option table (COBOL ``CARDDEMO-ADMIN-MENU-OPTIONS``).
     *
     * :output: Immutable list of the 4 populated admin rows; ``userType`` is ``null``
     *     because the admin copybook declares no user-type field.
     */
    public static final List<MenuOption> ADMIN_MENU_OPTIONS = List.of(
            new MenuOption(1, "User List (Security)               ", "COUSR00C", null),
            new MenuOption(2, "User Add (Security)                ", "COUSR01C", null),
            new MenuOption(3, "User Update (Security)             ", "COUSR02C", null),
            new MenuOption(4, "User Delete (Security)             ", "COUSR03C", null));

    /**
     * Main menu option table (COBOL ``CARDDEMO-MAIN-MENU-OPTIONS``).
     *
     * :output: Immutable list of the 10 populated main rows; every ``userType`` is ``"U"``.
     */
    public static final List<MenuOption> MAIN_MENU_OPTIONS = List.of(
            new MenuOption(1, "Account View                       ", "COACTVWC", "U"),
            new MenuOption(2, "Account Update                     ", "COACTUPC", "U"),
            new MenuOption(3, "Credit Card List                   ", "COCRDLIC", "U"),
            new MenuOption(4, "Credit Card View                   ", "COCRDSLC", "U"),
            new MenuOption(5, "Credit Card Update                 ", "COCRDUPC", "U"),
            new MenuOption(6, "Transaction List                   ", "COTRN00C", "U"),
            new MenuOption(7, "Transaction View                   ", "COTRN01C", "U"),
            new MenuOption(8, "Transaction Add                    ", "COTRN02C", "U"),
            new MenuOption(9, "Transaction Reports                ", "CORPT00C", "U"),
            new MenuOption(10, "Bill Payment                       ", "COBIL00C", "U"));
}

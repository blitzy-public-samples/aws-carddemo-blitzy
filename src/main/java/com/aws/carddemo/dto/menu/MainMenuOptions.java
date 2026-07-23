package com.aws.carddemo.dto.menu;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Main-menu option list for CardDemo.
 *
 * <p>Origin: legacy/cpy/COMEN02Y.cpy (CARDDEMO-MAIN-MENU-OPTIONS).</p>
 *
 * <p>These are compile-time menu definitions translated one-for-one from the
 * COBOL working-storage copybook {@code COMEN02Y} (group
 * {@code CARDDEMO-MAIN-MENU-OPTIONS}), which was included via {@code COPY COMEN02Y}
 * by the main-menu program {@code COMEN01C}. Because the options are seeded as
 * COBOL {@code VALUE} clauses (not read from a file), the main menu renders
 * without any database round-trip; this model preserves that behavior by holding
 * the options as an immutable in-memory list.</p>
 *
 * <p>The {@code requiredUserType} value ("U") relates to the A/U user-type
 * semantics modeled in {@code com.aws.carddemo.dto.CardDemoContext} (which stores
 * the user type as a raw {@code String} as well): an admin ("A") sees all options,
 * while a standard user ("U") sees the "U" options. This class only carries the
 * translated data verbatim &mdash; role gating is enforced by the menu service and
 * Spring Security, never here.</p>
 *
 * <p>Faithful-translation notes:</p>
 * <ul>
 *   <li>{@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} is the number of populated
 *       options ({@link #OPTION_COUNT}); the redefined array
 *       {@code CDEMO-MENU-OPT OCCURS 12 TIMES} has capacity 12
 *       ({@link #MAX_OPTIONS}).</li>
 *   <li>Display names are stored trimmed; the underlying COBOL field is
 *       {@code CDEMO-MENU-OPT-NAME PIC X(35)} ({@link #NAME_WIDTH}).</li>
 *   <li>For option 8 the copybook contained a commented-out alternative label
 *       ({@code * 'Transaction Add (Admin Only)       '}); the active COBOL
 *       {@code VALUE} is {@code 'Transaction Add                    '}, so the
 *       stored display name is {@code "Transaction Add"}.</li>
 * </ul>
 *
 * <p>This is a plain-Java model with explicit accessors (no Lombok) and depends
 * only on {@link java.util.List}.</p>
 */
@Component
public final class MainMenuOptions {

    /** Array capacity from COBOL {@code CDEMO-MENU-OPT OCCURS 12 TIMES}. */
    public static final int MAX_OPTIONS = 12;

    /** Populated-option count from COBOL {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10}. */
    public static final int OPTION_COUNT = 10;

    /** Display-name field width: {@code CDEMO-MENU-OPT-NAME PIC X(35)}. */
    public static final int NAME_WIDTH = 35;

    /** Program-name field width: {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)}. */
    public static final int PROGRAM_NAME_WIDTH = 8;

    /**
     * Immutable, seeded main-menu options (numbers 1..10), preserving the exact
     * order, display text, 8-character program names, and 1-character user type
     * from the COBOL {@code CDEMO-MENU-OPTIONS-DATA} initializers.
     */
    private static final List<MainMenuOption> OPTIONS = List.of(
            new MainMenuOption(1,  "Account View",        "COACTVWC", "U"),
            new MainMenuOption(2,  "Account Update",      "COACTUPC", "U"),
            new MainMenuOption(3,  "Credit Card List",    "COCRDLIC", "U"),
            new MainMenuOption(4,  "Credit Card View",    "COCRDSLC", "U"),
            new MainMenuOption(5,  "Credit Card Update",  "COCRDUPC", "U"),
            new MainMenuOption(6,  "Transaction List",    "COTRN00C", "U"),
            new MainMenuOption(7,  "Transaction View",    "COTRN01C", "U"),
            new MainMenuOption(8,  "Transaction Add",     "COTRN02C", "U"),
            new MainMenuOption(9,  "Transaction Reports", "CORPT00C", "U"),
            new MainMenuOption(10, "Bill Payment",        "COBIL00C", "U"));

    /**
     * Returns the immutable list of populated main-menu options.
     *
     * @return the immutable list of populated main-menu options
     *         (size {@value #OPTION_COUNT})
     */
    public List<MainMenuOption> getOptions() {
        return OPTIONS;
    }

    /**
     * Returns the number of populated options.
     *
     * @return the number of populated options (COBOL {@code CDEMO-MENU-OPT-COUNT},
     *         value {@value #OPTION_COUNT})
     */
    public int getOptionCount() {
        return OPTION_COUNT;
    }

    /**
     * Returns the COBOL array capacity.
     *
     * @return the COBOL array capacity (COBOL {@code CDEMO-MENU-OPT OCCURS 12 TIMES},
     *         value {@value #MAX_OPTIONS})
     */
    public int getMaxOptions() {
        return MAX_OPTIONS;
    }

    /**
     * One main-menu option.
     *
     * <p>Mirrors COBOL {@code CDEMO-MENU-OPT}: {@code CDEMO-MENU-OPT-NUM PIC 9(02)},
     * {@code CDEMO-MENU-OPT-NAME PIC X(35)}, {@code CDEMO-MENU-OPT-PGMNAME PIC X(08)},
     * {@code CDEMO-MENU-OPT-USRTYPE PIC X(01)}.</p>
     *
     * @param number           1-based option number (COBOL {@code CDEMO-MENU-OPT-NUM})
     * @param name             display label, X(35) width (COBOL {@code CDEMO-MENU-OPT-NAME})
     * @param programName      8-char target program (COBOL {@code CDEMO-MENU-OPT-PGMNAME})
     * @param requiredUserType 1-char required user type, "U"/"A" (COBOL {@code CDEMO-MENU-OPT-USRTYPE})
     */
    public record MainMenuOption(int number, String name, String programName, String requiredUserType) {
    }
}

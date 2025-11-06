/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.constants;

/**
 * CICS AID (Attention Identifier) Key Constants
 * 
 * <p>This class provides constants for CICS attention identifier keys and their
 * semantic mappings to modern REST API and React UI navigation actions.</p>
 * 
 * <p>Original CICS Context:</p>
 * <ul>
 *   <li>CICS programs use EIBAID (Execute Interface Block - Attention Identifier) 
 *       to determine which key the user pressed on a 3270 terminal</li>
 *   <li>Each key has a specific hexadecimal value that CICS recognizes</li>
 *   <li>Common usage patterns include PF3 for Exit, PF7/PF8 for scrolling</li>
 * </ul>
 * 
 * <p>Modern Spring Boot / React Context:</p>
 * <ul>
 *   <li>These constants map to HTTP request parameters or headers indicating user intent</li>
 *   <li>React components use ACTION_* constants for button clicks and navigation</li>
 *   <li>REST controllers can interpret these values to maintain original program flow</li>
 * </ul>
 * 
 * <p>Transformation from COBOL:</p>
 * <pre>
 * COBOL Original:
 *   WHEN EIBAID IS EQUAL TO DFHENTER
 *     PERFORM PROCESS-ENTER-KEY
 *   WHEN EIBAID IS EQUAL TO DFHPF3
 *     PERFORM EXIT-SCREEN
 * 
 * Java/Spring Boot Equivalent:
 *   if (AIDKeyConstants.DFHENTER.equals(aidKey)) {
 *     processEnterKey();
 *   } else if (AIDKeyConstants.DFHPF3.equals(aidKey)) {
 *     exitScreen();
 *   }
 * 
 * React Component Equivalent:
 *   &lt;Button onClick={() => handleAction(AIDKeyConstants.ACTION_BACK)}&gt;
 *     Exit (PF3)
 *   &lt;/Button&gt;
 * </pre>
 * 
 * @since 1.0
 * @see <a href="https://www.ibm.com/docs/en/cics-ts">CICS Transaction Server Documentation</a>
 */
public final class AIDKeyConstants {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private AIDKeyConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // ========================================================================
    // CICS Attention Identifier Key Constants
    // ========================================================================

    /**
     * CICS DFHENTER - Enter Key
     * 
     * <p>Hexadecimal value: 0x7D</p>
     * 
     * <p>Original CICS Usage: User pressed the ENTER key to submit data or
     * proceed to the next screen. Typically triggers data validation and
     * processing logic.</p>
     * 
     * <p>Modern REST/React Mapping: Form submission, default action button,
     * or HTTP POST request. Maps to {@link #ACTION_SUBMIT}.</p>
     */
    public static final String DFHENTER = "ENTER";

    /**
     * CICS DFHCLEAR - Clear Key
     * 
     * <p>Hexadecimal value: 0x6D</p>
     * 
     * <p>Original CICS Usage: User pressed the CLEAR key to reset the screen
     * to its initial state, clearing all user input fields.</p>
     * 
     * <p>Modern REST/React Mapping: Form reset button, clear filters action,
     * or cancel without saving. Maps to {@link #ACTION_CLEAR}.</p>
     */
    public static final String DFHCLEAR = "CLEAR";

    /**
     * CICS DFHPA1 - Program Attention Key 1
     * 
     * <p>Hexadecimal value: 0x6C</p>
     * 
     * <p>Original CICS Usage: User pressed PA1, typically used for help or
     * attention-getting functions without transmitting data.</p>
     * 
     * <p>Modern REST/React Mapping: Help button or info dialog trigger.
     * Maps to {@link #ACTION_HELP}.</p>
     */
    public static final String DFHPA1 = "PA1";

    /**
     * CICS DFHPA2 - Program Attention Key 2
     * 
     * <p>Hexadecimal value: 0x6E</p>
     * 
     * <p>Original CICS Usage: User pressed PA2, typically used for alternate
     * help or cancel operations without transmitting modified data.</p>
     * 
     * <p>Modern REST/React Mapping: Secondary help or cancel action.
     * Maps to {@link #ACTION_CANCEL}.</p>
     */
    public static final String DFHPA2 = "PA2";

    /**
     * CICS DFHPF1 - Program Function Key 1
     * 
     * <p>Hexadecimal value: 0xF1</p>
     * 
     * <p>Original CICS Usage: User pressed PF1. Application-specific function,
     * often used for Help or application menu.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button or
     * help menu trigger.</p>
     */
    public static final String DFHPF1 = "PF1";

    /**
     * CICS DFHPF2 - Program Function Key 2
     * 
     * <p>Hexadecimal value: 0xF2</p>
     * 
     * <p>Original CICS Usage: User pressed PF2. Application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF2 = "PF2";

    /**
     * CICS DFHPF3 - Program Function Key 3 (Exit/Back)
     * 
     * <p>Hexadecimal value: 0xF3</p>
     * 
     * <p>Original CICS Usage: User pressed PF3. Standard convention for
     * exiting the current screen and returning to the previous screen or menu.</p>
     * 
     * <p>Modern REST/React Mapping: Back button, navigation to previous screen,
     * or cancel operation. Maps to {@link #ACTION_BACK} or {@link #ACTION_EXIT}.</p>
     */
    public static final String DFHPF3 = "PF3";

    /**
     * CICS DFHPF4 - Program Function Key 4
     * 
     * <p>Hexadecimal value: 0xF4</p>
     * 
     * <p>Original CICS Usage: User pressed PF4. Application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF4 = "PF4";

    /**
     * CICS DFHPF5 - Program Function Key 5
     * 
     * <p>Hexadecimal value: 0xF5</p>
     * 
     * <p>Original CICS Usage: User pressed PF5. Application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF5 = "PF5";

    /**
     * CICS DFHPF6 - Program Function Key 6
     * 
     * <p>Hexadecimal value: 0xF6</p>
     * 
     * <p>Original CICS Usage: User pressed PF6. Application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF6 = "PF6";

    /**
     * CICS DFHPF7 - Program Function Key 7 (Page Up/Scroll Up)
     * 
     * <p>Hexadecimal value: 0xF7</p>
     * 
     * <p>Original CICS Usage: User pressed PF7. Standard convention for
     * scrolling up or viewing the previous page of multi-page displays.</p>
     * 
     * <p>Modern REST/React Mapping: Previous page button in pagination controls,
     * scroll up action. Maps to {@link #ACTION_PAGE_UP}.</p>
     */
    public static final String DFHPF7 = "PF7";

    /**
     * CICS DFHPF8 - Program Function Key 8 (Page Down/Scroll Down)
     * 
     * <p>Hexadecimal value: 0xF8</p>
     * 
     * <p>Original CICS Usage: User pressed PF8. Standard convention for
     * scrolling down or viewing the next page of multi-page displays
     * (e.g., 7 cards per page, 10 transactions per page).</p>
     * 
     * <p>Modern REST/React Mapping: Next page button in pagination controls,
     * scroll down action. Maps to {@link #ACTION_PAGE_DOWN}.</p>
     */
    public static final String DFHPF8 = "PF8";

    /**
     * CICS DFHPF9 - Program Function Key 9
     * 
     * <p>Hexadecimal value: 0xF9</p>
     * 
     * <p>Original CICS Usage: User pressed PF9. Application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF9 = "PF9";

    /**
     * CICS DFHPF10 - Program Function Key 10
     * 
     * <p>Hexadecimal value: 0x7A</p>
     * 
     * <p>Original CICS Usage: User pressed PF10. Application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF10 = "PF10";

    /**
     * CICS DFHPF11 - Program Function Key 11
     * 
     * <p>Hexadecimal value: 0x7B</p>
     * 
     * <p>Original CICS Usage: User pressed PF11. Application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF11 = "PF11";

    /**
     * CICS DFHPF12 - Program Function Key 12
     * 
     * <p>Hexadecimal value: 0x7C</p>
     * 
     * <p>Original CICS Usage: User pressed PF12. Often used for refresh or
     * cancel operations in some applications.</p>
     * 
     * <p>Modern REST/React Mapping: Refresh button, cancel action, or
     * application-specific function. May map to {@link #ACTION_REFRESH}.</p>
     */
    public static final String DFHPF12 = "PF12";

    /**
     * CICS DFHPF13 - Program Function Key 13
     * 
     * <p>Hexadecimal value: 0xC1</p>
     * 
     * <p>Original CICS Usage: User pressed PF13. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF13 = "PF13";

    /**
     * CICS DFHPF14 - Program Function Key 14
     * 
     * <p>Hexadecimal value: 0xC2</p>
     * 
     * <p>Original CICS Usage: User pressed PF14. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF14 = "PF14";

    /**
     * CICS DFHPF15 - Program Function Key 15
     * 
     * <p>Hexadecimal value: 0xC3</p>
     * 
     * <p>Original CICS Usage: User pressed PF15. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF15 = "PF15";

    /**
     * CICS DFHPF16 - Program Function Key 16
     * 
     * <p>Hexadecimal value: 0xC4</p>
     * 
     * <p>Original CICS Usage: User pressed PF16. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF16 = "PF16";

    /**
     * CICS DFHPF17 - Program Function Key 17
     * 
     * <p>Hexadecimal value: 0xC5</p>
     * 
     * <p>Original CICS Usage: User pressed PF17. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF17 = "PF17";

    /**
     * CICS DFHPF18 - Program Function Key 18
     * 
     * <p>Hexadecimal value: 0xC6</p>
     * 
     * <p>Original CICS Usage: User pressed PF18. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF18 = "PF18";

    /**
     * CICS DFHPF19 - Program Function Key 19
     * 
     * <p>Hexadecimal value: 0xC7</p>
     * 
     * <p>Original CICS Usage: User pressed PF19. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF19 = "PF19";

    /**
     * CICS DFHPF20 - Program Function Key 20
     * 
     * <p>Hexadecimal value: 0xC8</p>
     * 
     * <p>Original CICS Usage: User pressed PF20. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF20 = "PF20";

    /**
     * CICS DFHPF21 - Program Function Key 21
     * 
     * <p>Hexadecimal value: 0xC9</p>
     * 
     * <p>Original CICS Usage: User pressed PF21. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF21 = "PF21";

    /**
     * CICS DFHPF22 - Program Function Key 22
     * 
     * <p>Hexadecimal value: 0x4A</p>
     * 
     * <p>Original CICS Usage: User pressed PF22. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF22 = "PF22";

    /**
     * CICS DFHPF23 - Program Function Key 23
     * 
     * <p>Hexadecimal value: 0x4B</p>
     * 
     * <p>Original CICS Usage: User pressed PF23. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF23 = "PF23";

    /**
     * CICS DFHPF24 - Program Function Key 24
     * 
     * <p>Hexadecimal value: 0x4C</p>
     * 
     * <p>Original CICS Usage: User pressed PF24. Extended function key,
     * application-specific function.</p>
     * 
     * <p>Modern REST/React Mapping: Application-specific action button.</p>
     */
    public static final String DFHPF24 = "PF24";

    // ========================================================================
    // Semantic Action Constants for REST API and React UI
    // ========================================================================

    /**
     * ACTION_SUBMIT - Form submission or primary action
     * 
     * <p>Semantic equivalent of ENTER key in web context.</p>
     * 
     * <p>Usage in REST APIs: POST requests, form data submission,
     * data validation and processing.</p>
     * 
     * <p>Usage in React: Primary action buttons, form submit buttons,
     * "Save", "Update", "Create" operations.</p>
     * 
     * <p>Maps from: {@link #DFHENTER}</p>
     */
    public static final String ACTION_SUBMIT = "ACTION_SUBMIT";

    /**
     * ACTION_BACK - Return to previous screen or cancel operation
     * 
     * <p>Semantic equivalent of PF3 key in web context.</p>
     * 
     * <p>Usage in REST APIs: Navigation parameter indicating return
     * to previous view without saving changes.</p>
     * 
     * <p>Usage in React: Back button, breadcrumb navigation, "Cancel"
     * buttons that return to previous screen.</p>
     * 
     * <p>Maps from: {@link #DFHPF3}</p>
     */
    public static final String ACTION_BACK = "ACTION_BACK";

    /**
     * ACTION_PAGE_UP - Navigate to previous page in paginated list
     * 
     * <p>Semantic equivalent of PF7 key in web context.</p>
     * 
     * <p>Usage in REST APIs: Pagination parameter to retrieve previous
     * page of results (e.g., cards 1-7 when currently viewing cards 8-14).</p>
     * 
     * <p>Usage in React: Previous page button, scroll up control,
     * "Previous" navigation in card lists (7 per page) or
     * transaction lists (10 per page).</p>
     * 
     * <p>Maps from: {@link #DFHPF7}</p>
     */
    public static final String ACTION_PAGE_UP = "ACTION_PAGE_UP";

    /**
     * ACTION_PAGE_DOWN - Navigate to next page in paginated list
     * 
     * <p>Semantic equivalent of PF8 key in web context.</p>
     * 
     * <p>Usage in REST APIs: Pagination parameter to retrieve next
     * page of results (e.g., cards 8-14 when currently viewing cards 1-7).</p>
     * 
     * <p>Usage in React: Next page button, scroll down control,
     * "Next" navigation in card lists (7 per page) or
     * transaction lists (10 per page).</p>
     * 
     * <p>Maps from: {@link #DFHPF8}</p>
     */
    public static final String ACTION_PAGE_DOWN = "ACTION_PAGE_DOWN";

    /**
     * ACTION_CLEAR - Reset form to initial state
     * 
     * <p>Semantic equivalent of CLEAR key in web context.</p>
     * 
     * <p>Usage in REST APIs: Parameter indicating form reset or
     * filter clearing operation.</p>
     * 
     * <p>Usage in React: Clear/Reset button, "Clear Filters" action,
     * form field reset to default values.</p>
     * 
     * <p>Maps from: {@link #DFHCLEAR}</p>
     */
    public static final String ACTION_CLEAR = "ACTION_CLEAR";

    /**
     * ACTION_CANCEL - Cancel current operation without saving
     * 
     * <p>Semantic equivalent of PA2 key in web context.</p>
     * 
     * <p>Usage in REST APIs: Parameter indicating cancellation of
     * current operation, no data persistence.</p>
     * 
     * <p>Usage in React: Cancel button in dialogs, discard changes
     * and return to previous screen.</p>
     * 
     * <p>Maps from: {@link #DFHPA2}</p>
     */
    public static final String ACTION_CANCEL = "ACTION_CANCEL";

    /**
     * ACTION_EXIT - Exit application or module
     * 
     * <p>Semantic equivalent of PF3 key when used for application exit.</p>
     * 
     * <p>Usage in REST APIs: Parameter indicating session termination
     * or logout action.</p>
     * 
     * <p>Usage in React: Exit button, logout action, close application.</p>
     * 
     * <p>Maps from: {@link #DFHPF3}</p>
     */
    public static final String ACTION_EXIT = "ACTION_EXIT";

    /**
     * ACTION_REFRESH - Refresh current view with latest data
     * 
     * <p>Semantic equivalent of PF12 key in web context.</p>
     * 
     * <p>Usage in REST APIs: Parameter indicating data refresh request,
     * re-query current view.</p>
     * 
     * <p>Usage in React: Refresh button, reload current data,
     * "Update" button to fetch latest records.</p>
     * 
     * <p>Maps from: {@link #DFHPF12}</p>
     */
    public static final String ACTION_REFRESH = "ACTION_REFRESH";

    /**
     * ACTION_HELP - Display help or information dialog
     * 
     * <p>Semantic equivalent of PA1 key in web context.</p>
     * 
     * <p>Usage in REST APIs: Parameter to retrieve help content or
     * context-sensitive information.</p>
     * 
     * <p>Usage in React: Help button, info icon, tooltip trigger,
     * help dialog display.</p>
     * 
     * <p>Maps from: {@link #DFHPA1}</p>
     */
    public static final String ACTION_HELP = "ACTION_HELP";
}

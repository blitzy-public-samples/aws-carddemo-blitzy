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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Main Menu Router Service — faithful translation of COMEN01C.cbl.
 *
 * <p>This service replaces the COBOL program {@code COMEN01C} (PROGRAM-ID
 * COMEN01C, transaction ID CM00) which serves as the main menu router for
 * the CardDemo application. It maps CICS XCTL dispatch logic to method-based
 * routing with role-based option filtering (Admin sees admin menu options,
 * regular users see standard menu options only).</p>
 *
 * <h2>COBOL Paragraph to Java Method Traceability</h2>
 * <pre>
 *   MAIN-PARA               (line  75) → {@link #mainPara(int)}
 *   PROCESS-ENTER-KEY       (line 115) → {@link #processEnterKey(int)} (private helper)
 *   RETURN-TO-SIGNON-SCREEN (line 170) → sign-on return logic within {@link #mainPara(int)}
 *   SEND-MENU-SCREEN        (line 182) → controller responsibility (HTTP response)
 *   RECEIVE-MENU-SCREEN     (line 199) → controller receives HTTP request
 *   POPULATE-HEADER-INFO    (line 212) → controller/context provides header metadata
 *   BUILD-MENU-OPTIONS      (line 236) → {@link #getMenuOptions()}
 * </pre>
 *
 * <h2>Menu Options (from COMEN02Y.cpy CARDDEMO-MAIN-MENU-OPTIONS)</h2>
 * <p>The 10 menu options are defined in copybook COMEN02Y.cpy with
 * {@code CDEMO-MENU-OPT-COUNT = 10} and the {@code CDEMO-MENU-OPTIONS} array
 * ({@code OCCURS 12 TIMES}, 2 slots unused). Each option has four fields:</p>
 * <ul>
 *   <li>{@code CDEMO-MENU-OPT-NUM}     — option number (1–10)</li>
 *   <li>{@code CDEMO-MENU-OPT-NAME}    — display name (PIC X(35))</li>
 *   <li>{@code CDEMO-MENU-OPT-PGMNAME} — target COBOL program (PIC X(08))</li>
 *   <li>{@code CDEMO-MENU-OPT-USRTYPE} — required user type ('U'=all, 'A'=admin)</li>
 * </ul>
 *
 * <h2>Role-Based Access Control (COMEN01C.cbl lines 136–143)</h2>
 * <p>If a regular user ({@link UserType#USER}) selects an option whose
 * {@code CDEMO-MENU-OPT-USRTYPE} is {@code 'A'}, access is denied with the
 * message "No access - Admin Only option...". Admin users
 * ({@link UserType#ADMIN}) can access all options regardless of the
 * option's user type flag.</p>
 *
 * @see com.cardemo.common.context.CardDemoContext
 * @see com.cardemo.common.enums.UserType
 * @see com.cardemo.common.message.MessageConstants
 */
@Service
public class MainMenuService {

    private static final Logger logger = LoggerFactory.getLogger(MainMenuService.class);

    // ========================================================================
    // Working-Storage Constants (← COMEN01C.cbl lines 36–39)
    // ========================================================================

    /**
     * Program name constant.
     * Maps to COBOL: {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} (line 36).
     */
    private static final String PROGRAM_NAME = "COMEN01C";

    /**
     * Transaction ID constant.
     * Maps to COBOL: {@code WS-TRANID PIC X(04) VALUE 'CM00'} (line 37).
     */
    private static final String TRANSACTION_ID = "CM00";

    /**
     * Sign-on program name for return navigation.
     * Referenced in COMEN01C.cbl lines 83, 97, and 172–174.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    // ========================================================================
    // Menu Option Data Structure (← COMEN02Y.cpy)
    // ========================================================================

    /**
     * Represents a single menu option entry from COMEN02Y.cpy.
     *
     * <p>Maps the COBOL {@code CDEMO-MENU-OPT} group ({@code OCCURS 12 TIMES}):</p>
     * <pre>
     *   15 CDEMO-MENU-OPT-NUM      PIC 9(02)   → {@code number}
     *   15 CDEMO-MENU-OPT-NAME     PIC X(35)   → {@code name}
     *   15 CDEMO-MENU-OPT-PGMNAME  PIC X(08)   → {@code programName}
     *   15 CDEMO-MENU-OPT-USRTYPE  PIC X(01)   → {@code userType}
     * </pre>
     *
     * @param number      the 1-based option number
     * @param name        the display label for this option
     * @param programName the COBOL program name (XCTL target)
     * @param userType    the minimum user type required ('U' = any, 'A' = admin)
     */
    private record MenuOption(int number, String name, String programName,
                              char userType) {
    }

    /**
     * Complete menu option definitions translated from COMEN02Y.cpy lines 25–84.
     *
     * <p>CDEMO-MENU-OPT-COUNT = 10 (line 21). The COBOL array has 12 slots
     * ({@code OCCURS 12 TIMES}), but only 10 are populated with valid data.
     * All current options have user type 'U' (accessible by all users).
     * The filtering infrastructure supports 'A' (admin-only) options.</p>
     */
    private static final List<MenuOption> MENU_OPTIONS = List.of(
            new MenuOption(1, "Account View", "COACTVWC", 'U'),
            new MenuOption(2, "Account Update", "COACTUPC", 'U'),
            new MenuOption(3, "Credit Card List", "COCRDLIC", 'U'),
            new MenuOption(4, "Credit Card View", "COCRDSLC", 'U'),
            new MenuOption(5, "Credit Card Update", "COCRDUPC", 'U'),
            new MenuOption(6, "Transaction List", "COTRN00C", 'U'),
            new MenuOption(7, "Transaction View", "COTRN01C", 'U'),
            new MenuOption(8, "Transaction Add", "COTRN02C", 'U'),
            new MenuOption(9, "Transaction Reports", "CORPT00C", 'U'),
            new MenuOption(10, "Bill Payment", "COBIL00C", 'U')
    );

    /**
     * Total number of active menu options.
     * Maps to COBOL: {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} (line 21).
     */
    private static final int MENU_OPTION_COUNT = MENU_OPTIONS.size();

    // ========================================================================
    // Dependencies
    // ========================================================================

    /**
     * Request-scoped session context bean mirroring the 1024-byte COMMAREA.
     * Injected via constructor — carries user type, navigation state, and
     * pseudo-conversational flow context across the request lifecycle.
     */
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs the MainMenuService with its required dependency.
     *
     * <p>In the COBOL program, the COMMAREA is received via
     * {@code DFHCOMMAREA} linkage section. In Java, the equivalent
     * request-scoped {@link CardDemoContext} is injected by Spring.</p>
     *
     * @param cardDemoContext the request-scoped session context bean
     */
    public MainMenuService(CardDemoContext cardDemoContext) {
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Public API — Exposed Methods
    // ========================================================================

    /**
     * Main menu entry point — faithful translation of MAIN-PARA (line 75).
     *
     * <p>Implements the full pseudo-conversational flow control from
     * COMEN01C.cbl's PROCEDURE DIVISION:</p>
     * <ol>
     *   <li><strong>No session</strong> (EIBCALEN=0, line 82): redirect to
     *       sign-on program COSGN00C</li>
     *   <li><strong>First entry</strong> (CDEMO-PGM-ENTER=0, line 87): mark
     *       context as re-enter and return {@code null} to display menu</li>
     *   <li><strong>Re-entry, option=0</strong> (PF3, line 96): return sign-on
     *       program for backward navigation</li>
     *   <li><strong>Re-entry, option&lt;0</strong> (invalid key, line 99):
     *       log invalid key message and return {@code null}</li>
     *   <li><strong>Re-entry, option&gt;0</strong> (ENTER key, line 94):
     *       validate option, set navigation context, return target program</li>
     * </ol>
     *
     * @param option the selected menu option number.
     *        Use 1 through 10 for menu selections,
     *        0 for PF3 (return to sign-on),
     *        negative values for unrecognized key input
     * @return target COBOL program name (e.g., "COACTVWC") for routing on
     *         success, sign-on program name ("COSGN00C") for PF3/no-session,
     *         or {@code null} if the menu should be redisplayed (first entry,
     *         validation error, or coming-soon option)
     */
    public String mainPara(int option) {
        // ================================================================
        // No session context check (EIBCALEN = 0, lines 82–84)
        // COBOL: IF EIBCALEN = 0
        //            MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
        //            PERFORM RETURN-TO-SIGNON-SCREEN
        // ================================================================
        if (cardDemoContext.getUserType() == null) {
            cardDemoContext.setFromProgram(SIGNON_PROGRAM);
            cardDemoContext.setToProgram(SIGNON_PROGRAM);
            logger.warn("No session context (EIBCALEN=0 equivalent) "
                    + "- redirecting to sign-on program: {}", SIGNON_PROGRAM);
            return SIGNON_PROGRAM;
        }

        // ================================================================
        // First entry check (lines 87–90)
        // COBOL: IF NOT CDEMO-PGM-REENTER
        //            SET CDEMO-PGM-REENTER TO TRUE
        //            MOVE LOW-VALUES TO COMEN1AO
        //            PERFORM SEND-MENU-SCREEN
        // ================================================================
        if (cardDemoContext.isEnterContext()) {
            cardDemoContext.setPgmContext(CardDemoContext.PGM_REENTER);
            logger.info("Main menu initial entry for user '{}' (type: {}) "
                    + "- displaying menu screen",
                    cardDemoContext.getUserId(), cardDemoContext.getUserType());
            return null;
        }

        // ================================================================
        // Re-entry: process user input (lines 91–104)
        // COBOL: ELSE → PERFORM RECEIVE-MENU-SCREEN → EVALUATE EIBAID
        // ================================================================
        logger.debug("Main menu re-entry processing: option={}, isReenter={}",
                option, cardDemoContext.isReenterContext());

        // PF3: Return to sign-on screen (lines 96–98)
        // COBOL: WHEN DFHPF3
        //            MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        //            PERFORM RETURN-TO-SIGNON-SCREEN
        if (option == 0) {
            cardDemoContext.setToProgram(SIGNON_PROGRAM);
            logger.info("PF3 equivalent received - returning to sign-on: {}",
                    SIGNON_PROGRAM);
            return SIGNON_PROGRAM;
        }

        // Invalid/unrecognized key (lines 99–102)
        // COBOL: WHEN OTHER
        //            MOVE 'Y' TO WS-ERR-FLG
        //            MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
        //            PERFORM SEND-MENU-SCREEN
        if (option < 0) {
            logger.warn("Unrecognized key/input: option={}. Message: {}",
                    option, MessageConstants.INVALID_KEY_MESSAGE.trim());
            return null;
        }

        // ENTER key with option: PROCESS-ENTER-KEY (line 95 → 115)
        // COBOL: WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY
        return processEnterKey(option);
    }

    /**
     * Returns the list of available menu option display strings filtered by
     * the current user's role.
     *
     * <p>Translates the BUILD-MENU-OPTIONS paragraph (line 236). Iterates
     * through the CDEMO-MENU-OPTIONS array and builds formatted option
     * strings. Admin users ({@link UserType#ADMIN}) see all options;
     * regular users ({@link UserType#USER}) see only options where
     * {@code CDEMO-MENU-OPT-USRTYPE} is not 'A'.</p>
     *
     * <p>The formatting matches the COBOL STRING statement (lines 243–246):</p>
     * <pre>
     *   STRING CDEMO-MENU-OPT-NUM(WS-IDX) '. '
     *          CDEMO-MENU-OPT-NAME(WS-IDX)
     *     INTO WS-MENU-OPT-TXT
     * </pre>
     *
     * @return unmodifiable list of formatted menu option strings
     *         (e.g., "1. Account View", "2. Account Update", etc.)
     *         filtered by the current user's role
     */
    public List<String> getMenuOptions() {
        boolean isAdmin = cardDemoContext.isAdmin();
        char adminTypeCode = UserType.ADMIN.getCode();

        logger.debug("Building menu options for user type: {} (admin={})",
                cardDemoContext.getUserType(), isAdmin);

        // BUILD-MENU-OPTIONS paragraph (lines 236–277):
        // PERFORM VARYING WS-IDX FROM 1 BY 1
        //         UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT
        //   STRING CDEMO-MENU-OPT-NUM(WS-IDX) '. '
        //          CDEMO-MENU-OPT-NAME(WS-IDX) INTO WS-MENU-OPT-TXT
        //   EVALUATE WS-IDX → assign to OPTN001O–OPTN012O
        //
        // Role-based filtering (PROCESS-ENTER-KEY lines 136–143):
        // Admin users see all options; regular users skip admin-only options
        return MENU_OPTIONS.stream()
                .filter(opt -> isAdmin || opt.userType() != adminTypeCode)
                .map(opt -> opt.number() + ". " + opt.name())
                .toList();
    }

    /**
     * Validates the given menu option number for range and role authorization.
     *
     * <p>Combines the two validation checks from PROCESS-ENTER-KEY
     * (lines 127–143):</p>
     * <ol>
     *   <li><strong>Range check</strong> (lines 127–134): option must be
     *       between 1 and {@code CDEMO-MENU-OPT-COUNT} (10)</li>
     *   <li><strong>Role check</strong> (lines 136–143): if the user is a
     *       regular user ({@link UserType#USER}) and the selected option
     *       requires admin access ({@code CDEMO-MENU-OPT-USRTYPE = 'A'}),
     *       access is denied</li>
     * </ol>
     *
     * @param option the menu option number to validate
     * @return {@code null} if the option is valid, or an error message string
     *         matching the COBOL WS-MESSAGE content for the specific failure
     */
    public String validateOption(int option) {
        // Range validation (lines 127–134)
        // COBOL: IF WS-OPTION IS NOT NUMERIC OR
        //        WS-OPTION > CDEMO-MENU-OPT-COUNT OR
        //        WS-OPTION = ZEROS
        //            MOVE 'Y' TO WS-ERR-FLG
        //            MOVE 'Please enter a valid option number...' TO WS-MESSAGE
        if (option < 1 || option > MENU_OPTION_COUNT) {
            logger.debug("Option {} failed range validation (valid: 1-{})",
                    option, MENU_OPTION_COUNT);
            return "Please enter a valid option number...";
        }

        MenuOption selected = MENU_OPTIONS.get(option - 1);

        // Role-based access validation (lines 136–143)
        // COBOL: IF CDEMO-USRTYP-USER AND
        //        CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
        //            SET ERR-FLG-ON TO TRUE
        //            MOVE 'No access - Admin Only option... ' TO WS-MESSAGE
        UserType currentType = cardDemoContext.getUserType();
        if (currentType == UserType.USER
                && selected.userType() == UserType.ADMIN.getCode()) {
            logger.debug("Option {} ({}) denied for user type {}",
                    option, selected.name(), UserType.USER);
            return "No access - Admin Only option... ";
        }

        logger.debug("Option {} ({}) validated successfully",
                option, selected.name());
        return null;
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    /**
     * Processes the Enter key menu option selection.
     *
     * <p>Faithful translation of the PROCESS-ENTER-KEY paragraph (line 115).
     * Validates the option, checks role authorization, sets navigation context
     * fields on the COMMAREA, and returns the target program name for
     * XCTL dispatch.</p>
     *
     * <p>The COBOL flow at lines 145–165 handles two cases:</p>
     * <ul>
     *   <li>If the target program name does NOT start with "DUMMY": set
     *       navigation context and XCTL to the target program</li>
     *   <li>If the target IS "DUMMY": display "coming soon" message and
     *       redisplay the menu</li>
     * </ul>
     *
     * @param option the validated option number (1-based)
     * @return target program name for XCTL dispatch, or {@code null} to
     *         redisplay the menu screen (validation failure or coming-soon)
     */
    private String processEnterKey(int option) {
        // Delegate to shared validation logic
        String validationError = validateOption(option);
        if (validationError != null) {
            logger.warn("Menu option validation failed: option={}, error='{}'",
                    option, validationError);
            return null;
        }

        MenuOption selected = MENU_OPTIONS.get(option - 1);

        // Check for DUMMY program placeholder (lines 145–165)
        // COBOL: IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
        if (!selected.programName().startsWith("DUMMY")) {
            // Set navigation context for XCTL dispatch (lines 147–151)
            // COBOL: MOVE WS-TRANID  TO CDEMO-FROM-TRANID
            //        MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
            //        MOVE ZEROS      TO CDEMO-PGM-CONTEXT
            //        EXEC CICS XCTL PROGRAM(...) COMMAREA(...) END-EXEC
            cardDemoContext.setFromTranId(TRANSACTION_ID);
            cardDemoContext.setFromProgram(PROGRAM_NAME);
            cardDemoContext.setPgmContext(CardDemoContext.PGM_ENTER);

            logger.info("XCTL dispatch: option={} name='{}' -> program='{}'",
                    option, selected.name(), selected.programName());
            return selected.programName();
        }

        // DUMMY program path (lines 157–164)
        // COBOL: STRING 'This option ' CDEMO-MENU-OPT-NAME(WS-OPTION)
        //               'is coming soon ...' INTO WS-MESSAGE
        //        PERFORM SEND-MENU-SCREEN
        logger.info("Option {} ({}) is coming soon - program '{}' not available",
                option, selected.name(), selected.programName());
        return null;
    }
}

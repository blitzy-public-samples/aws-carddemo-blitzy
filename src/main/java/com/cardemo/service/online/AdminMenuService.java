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
 * Admin menu service — faithful Java translation of COADM01C.cbl.
 *
 * <p>This service implements the admin-only menu for user management navigation.
 * The COBOL program {@code COADM01C} is a CICS pseudo-conversational program that
 * presents admin menu options and dispatches to the corresponding user management
 * programs (COUSR00C through COUSR03C) via {@code EXEC CICS XCTL}.</p>
 *
 * <h2>COBOL Paragraph → Java Method Traceability</h2>
 * <pre>
 * MAIN-PARA              (line 75)  → {@link #mainPara(int)}
 * PROCESS-ENTER-KEY      (line 115) → {@link #processEnterKey(int)} (private)
 * RETURN-TO-SIGNON-SCREEN(line 160) → {@link #returnToSignonScreen()} (private)
 * BUILD-MENU-OPTIONS     (line 226) → {@link #getAdminMenuOptions()}
 * SEND-MENU-SCREEN       (line 172) → handled by controller layer (headless)
 * RECEIVE-MENU-SCREEN    (line 189) → handled by controller layer (headless)
 * POPULATE-HEADER-INFO   (line 202) → handled by controller layer (headless)
 * </pre>
 *
 * <h2>Admin Menu Options (from COADM02Y.cpy)</h2>
 * <pre>
 * Option 1: User List (Security)   → COUSR00C
 * Option 2: User Add (Security)    → COUSR01C
 * Option 3: User Update (Security) → COUSR02C
 * Option 4: User Delete (Security) → COUSR03C
 * </pre>
 *
 * <h2>COMMAREA Usage</h2>
 * <p>The COBOL COMMAREA (COCOM01Y.cpy) fields used by this program:</p>
 * <ul>
 *   <li>{@code CDEMO-FROM-TRANID} — set to "CA00" when dispatching</li>
 *   <li>{@code CDEMO-FROM-PROGRAM} — set to "COADM01C" when dispatching</li>
 *   <li>{@code CDEMO-TO-PROGRAM} — set for return-to-signon navigation</li>
 *   <li>{@code CDEMO-PGM-CONTEXT} — 0 (enter) or 1 (re-enter) for
 *       pseudo-conversational flow</li>
 *   <li>{@code CDEMO-USER-TYPE} — verified as 'A' (admin) before processing</li>
 * </ul>
 *
 * @see com.cardemo.common.context.CardDemoContext
 * @see com.cardemo.common.enums.UserType
 * @see com.cardemo.common.message.MessageConstants
 */
@Service
public class AdminMenuService {

    private static final Logger logger = LoggerFactory.getLogger(AdminMenuService.class);

    // ========================================================================
    // COBOL Working Storage constants (COADM01C.cbl lines 35-48)
    // ========================================================================

    /**
     * Program name constant.
     * Maps to COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'}.
     */
    private static final String WS_PGMNAME = "COADM01C";

    /**
     * Transaction ID constant.
     * Maps to COBOL {@code WS-TRANID PIC X(04) VALUE 'CA00'}.
     */
    private static final String WS_TRANID = "CA00";

    /**
     * Number of valid admin menu options.
     * Maps to COBOL {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4}
     * from COADM02Y.cpy line 20.
     */
    private static final int ADMIN_OPT_COUNT = 4;

    // ========================================================================
    // Admin menu option tables (from COADM02Y.cpy lines 22-48)
    // Maps CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA
    // with CDEMO-ADMIN-OPT OCCURS 9 TIMES (only first 4 populated)
    // ========================================================================

    /**
     * Admin option program names. Each entry maps to
     * {@code CDEMO-ADMIN-OPT-PGMNAME PIC X(08)} from COADM02Y.cpy.
     * Used by PROCESS-ENTER-KEY (line 143) for XCTL dispatch target.
     */
    private static final String[] ADMIN_OPT_PGMNAMES = {
        "COUSR00C",  // Option 1: User List (Security)
        "COUSR01C",  // Option 2: User Add (Security)
        "COUSR02C",  // Option 3: User Update (Security)
        "COUSR03C"   // Option 4: User Delete (Security)
    };

    /**
     * Admin option display names. Each entry maps to
     * {@code CDEMO-ADMIN-OPT-NAME PIC X(35)} from COADM02Y.cpy.
     * Used by BUILD-MENU-OPTIONS (line 226) to populate screen fields.
     */
    private static final String[] ADMIN_OPT_NAMES = {
        "User List (Security)",
        "User Add (Security)",
        "User Update (Security)",
        "User Delete (Security)"
    };

    /**
     * Signon program name for return-to-signon flow.
     * Maps to COBOL literal {@code 'COSGN00C'} used at lines 83 and 97.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    // ========================================================================
    // Injected dependencies
    // ========================================================================

    /**
     * Request-scoped session context bean mirroring the CARDDEMO-COMMAREA.
     * Injected via constructor (replaces COBOL COMMAREA passing via XCTL).
     */
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs the admin menu service with required dependencies.
     *
     * <p>Spring performs constructor injection automatically when there is
     * a single constructor — no {@code @Autowired} annotation required.
     * This replaces the COBOL LINKAGE SECTION DFHCOMMAREA (line 67).</p>
     *
     * @param cardDemoContext the request-scoped session context carrying
     *                        user identity and navigation state
     */
    public AdminMenuService(CardDemoContext cardDemoContext) {
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Public API — COBOL paragraph translations
    // ========================================================================

    /**
     * Main entry point for admin menu processing.
     *
     * <p>Faithfully translates COADM01C.cbl {@code MAIN-PARA} (line 75).
     * Handles the pseudo-conversational flow control:</p>
     * <ol>
     *   <li><strong>Enter context</strong> (first entry, {@code CDEMO-PGM-ENTER}):
     *       Initializes the menu display cycle by setting the program context to
     *       re-enter mode. Returns {@code null} to indicate initial menu display
     *       with no navigation. Maps lines 87–90.</li>
     *   <li><strong>Re-enter context</strong> ({@code CDEMO-PGM-REENTER}):
     *       Processes the user's selected option number:
     *       <ul>
     *         <li>Negative value: PF3 equivalent — return to signon screen
     *             (maps DFHPF3 at line 96)</li>
     *         <li>1–4: Valid admin option — dispatches to target program
     *             (maps DFHENTER at line 94)</li>
     *         <li>0 or &gt;4: Invalid — throws exception with error message
     *             (maps lines 127–134)</li>
     *       </ul>
     *   </li>
     *   <li><strong>Unexpected context</strong>: Throws exception with invalid
     *       key message (maps WHEN OTHER at lines 99–102).</li>
     * </ol>
     *
     * @param option the menu option number selected by the user. Valid values:
     *               negative for return (PF3), 1–4 for admin options.
     *               Value 0 is treated as invalid per COBOL logic
     *               ({@code WS-OPTION = ZEROS} check at line 129).
     * @return target program name for routing (e.g., "COUSR00C" for user list,
     *         "COSGN00C" for return to signon), or {@code null} for initial
     *         menu display (enter context)
     * @throws SecurityException if the current user is not an admin
     * @throws IllegalArgumentException if the option number is invalid or
     *         the program context is in an unexpected state
     */
    public String mainPara(int option) {
        // Verify admin role — implicit gate in COBOL (only reachable from admin menu)
        validateAdminAccess();

        // First entry path (COADM01C.cbl lines 87-90):
        // IF NOT CDEMO-PGM-REENTER → SET CDEMO-PGM-REENTER TO TRUE,
        // MOVE LOW-VALUES TO COADM1AO, PERFORM SEND-MENU-SCREEN
        if (cardDemoContext.isEnterContext()) {
            cardDemoContext.setPgmContext(CardDemoContext.PGM_REENTER);
            logger.info("Admin menu initial display for user '{}'",
                    cardDemoContext.getUserId());
            return null;
        }

        // Re-entry path — process user input (COADM01C.cbl lines 91-104):
        // PERFORM RECEIVE-MENU-SCREEN → EVALUATE EIBAID
        if (cardDemoContext.isReenterContext()) {
            // PF3 equivalent — return to signon (COADM01C.cbl lines 96-98):
            // WHEN DFHPF3 → MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM,
            //                PERFORM RETURN-TO-SIGNON-SCREEN
            if (option < 0) {
                return returnToSignonScreen();
            }
            // ENTER key equivalent — process option (COADM01C.cbl line 95):
            // WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY
            return processEnterKey(option);
        }

        // Unexpected program context — maps to WHEN OTHER (lines 99-102):
        // MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
        logger.warn("Unexpected program context in admin menu. "
                + "Expected enter ({}) or re-enter ({}) but got: {}",
                CardDemoContext.PGM_ENTER, CardDemoContext.PGM_REENTER,
                cardDemoContext.getPgmContext());
        throw new IllegalArgumentException(MessageConstants.INVALID_KEY_MESSAGE);
    }

    /**
     * Returns the list of admin menu option display strings.
     *
     * <p>Translates the COBOL {@code BUILD-MENU-OPTIONS} paragraph
     * (COADM01C.cbl line 226) combined with the option definitions from
     * COADM02Y.cpy. The COBOL logic iterates {@code CDEMO-ADMIN-OPT-COUNT}
     * times (4), formatting each option as
     * "{@code CDEMO-ADMIN-OPT-NUM. CDEMO-ADMIN-OPT-NAME}" and placing it
     * into the corresponding screen field (OPTN001O through OPTN004O).</p>
     *
     * <p>In the headless Java service, we return the formatted option list
     * for the controller/API layer to present. The returned list is
     * unmodifiable (via {@link List#of}).</p>
     *
     * @return unmodifiable list of 4 admin menu option display strings:
     *         "User List (Security)", "User Add (Security)",
     *         "User Update (Security)", "User Delete (Security)"
     */
    public List<String> getAdminMenuOptions() {
        return List.of(
                ADMIN_OPT_NAMES[0],
                ADMIN_OPT_NAMES[1],
                ADMIN_OPT_NAMES[2],
                ADMIN_OPT_NAMES[3]
        );
    }

    /**
     * Validates that the current user has administrator privileges.
     *
     * <p>Translates the implicit admin-only authorization gate in COADM01C.cbl.
     * In the COBOL application, the admin menu program (COADM01C) is only
     * reachable via XCTL from the main menu (COMEN01C) when the user type is
     * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} (COCOM01Y.cpy lines 26–28). The
     * COBOL program itself does not contain an explicit admin check because
     * CICS program-level security and the menu routing enforce it.</p>
     *
     * <p>In the Java translation, this method makes the authorization check
     * explicit. It verifies {@link CardDemoContext#getUserType()} against
     * {@link UserType#ADMIN} and uses the convenience method
     * {@link CardDemoContext#isAdmin()} for the return value.</p>
     *
     * @return {@code true} if the current user has admin privileges
     *         (always {@code true} on successful return)
     * @throws SecurityException if the current user does not have admin
     *         privileges ({@link CardDemoContext#getUserType()} is not
     *         {@link UserType#ADMIN})
     */
    public boolean validateAdminAccess() {
        if (cardDemoContext.getUserType() != UserType.ADMIN) {
            logger.warn("Non-admin user attempted admin menu access. User type: {}",
                    cardDemoContext.getUserType());
            throw new SecurityException(
                    "Admin access required. Current user type: "
                            + cardDemoContext.getUserType());
        }
        boolean isAdmin = cardDemoContext.isAdmin();
        logger.debug("Admin access validated: isAdmin={}", isAdmin);
        return isAdmin;
    }

    // ========================================================================
    // Private methods — COBOL paragraph translations
    // ========================================================================

    /**
     * Processes the ENTER key action with the selected option number.
     *
     * <p>Faithfully translates COADM01C.cbl {@code PROCESS-ENTER-KEY}
     * paragraph (line 115). The COBOL logic:</p>
     * <ol>
     *   <li>Trims trailing spaces from the screen input field
     *       ({@code OPTIONI OF COADM1AI}), replaces remaining spaces with
     *       zeros, and converts to numeric {@code WS-OPTION} (lines 117–124)
     *       — handled by the controller/parsing layer in Java.</li>
     *   <li>Validates the option is numeric, within range (1 to
     *       {@code CDEMO-ADMIN-OPT-COUNT}=4), and not zero (lines 127–134).
     *       Invalid options produce error message
     *       "Please enter a valid option number...".</li>
     *   <li>For valid options where the program name does not start with
     *       "DUMMY" (line 138): sets COMMAREA navigation fields
     *       ({@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM},
     *       {@code CDEMO-PGM-CONTEXT=0}) and XCTLs to the target program
     *       (lines 139–145).</li>
     *   <li>For "DUMMY" options (not applicable — all 4 options are real
     *       programs): displays "This option is coming soon..." message
     *       (lines 147–154).</li>
     * </ol>
     *
     * @param option the menu option number (must be 1–4)
     * @return the target program name for routing
     * @throws IllegalArgumentException if the option is outside valid range
     */
    private String processEnterKey(int option) {
        // Validate option range (COADM01C.cbl lines 127-134):
        // IF WS-OPTION IS NOT NUMERIC OR
        //    WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
        //    WS-OPTION = ZEROS
        //     MOVE 'Please enter a valid option number...' TO WS-MESSAGE
        if (option < 1 || option > ADMIN_OPT_COUNT) {
            logger.warn("Invalid admin menu option: {}. Valid range: 1-{}",
                    option, ADMIN_OPT_COUNT);
            throw new IllegalArgumentException(
                    "Please enter a valid option number...");
        }

        String targetProgram = ADMIN_OPT_PGMNAMES[option - 1];

        // Set navigation context (COADM01C.cbl lines 139-141):
        // MOVE WS-TRANID    TO CDEMO-FROM-TRANID
        // MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
        // MOVE ZEROS         TO CDEMO-PGM-CONTEXT
        cardDemoContext.setFromTranId(WS_TRANID);
        cardDemoContext.setFromProgram(WS_PGMNAME);
        cardDemoContext.setPgmContext(CardDemoContext.PGM_ENTER);

        // Set target program (maps XCTL at line 143):
        // EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
        cardDemoContext.setToProgram(targetProgram);

        logger.info("Dispatching admin option {} ('{}') -> program '{}'",
                option, ADMIN_OPT_NAMES[option - 1], targetProgram);
        return targetProgram;
    }

    /**
     * Returns navigation to the signon screen.
     *
     * <p>Faithfully translates COADM01C.cbl {@code RETURN-TO-SIGNON-SCREEN}
     * paragraph (line 160). The COBOL logic:</p>
     * <ol>
     *   <li>Checks if {@code CDEMO-TO-PROGRAM} is LOW-VALUES or SPACES
     *       (line 162) — if so, defaults to "COSGN00C".</li>
     *   <li>XCTLs to the target program (lines 165-167).</li>
     * </ol>
     *
     * <p>In the PF3 flow (line 97), {@code CDEMO-TO-PROGRAM} is pre-set to
     * "COSGN00C" before this paragraph is called, so the default assignment
     * is a safety net.</p>
     *
     * @return the signon program name ("COSGN00C")
     */
    private String returnToSignonScreen() {
        // COADM01C.cbl line 97: MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        // Line 162-164: IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
        //                   MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        cardDemoContext.setToProgram(SIGNON_PROGRAM);
        logger.info("Returning to signon screen via program '{}'", SIGNON_PROGRAM);
        return SIGNON_PROGRAM;
    }
}

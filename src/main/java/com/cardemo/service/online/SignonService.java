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
import com.cardemo.common.exception.AuthenticationException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Sign-on authentication service — faithful translation of COSGN00C.cbl.
 *
 * <p>This service translates the COBOL CICS program {@code COSGN00C.cbl}
 * (Sign-On Screen for the CardDemo Application) into a Spring-managed service
 * bean. Every PROCEDURE DIVISION paragraph is mapped to a Java method that
 * preserves identical business logic and control flow.</p>
 *
 * <h2>COBOL Paragraph → Java Method Traceability</h2>
 * <table>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>MAIN-PARA</td><td>73</td><td>{@link #mainPara(String, String)}</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>108</td><td>{@link #processEnterKey(String, String)}</td></tr>
 *   <tr><td>SEND-SIGNON-SCREEN</td><td>145</td><td>(presentation — headless; replaced by exception/return)</td></tr>
 *   <tr><td>SEND-PLAIN-TEXT</td><td>162</td><td>(presentation — headless; replaced by return)</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>177</td><td>{@link #populateHeaderInfo()}</td></tr>
 *   <tr><td>READ-USER-SEC-FILE</td><td>209</td><td>{@link #readUserSecFile(String)}</td></tr>
 * </table>
 *
 * <h2>Authentication Flow</h2>
 * <ol>
 *   <li>{@code mainPara} orchestrates the sign-on flow, checking session state
 *       and routing to the appropriate handler</li>
 *   <li>{@code processEnterKey} validates user ID and password are non-blank,
 *       uppercases both (matching COBOL {@code FUNCTION UPPER-CASE}), then
 *       calls {@code readUserSecFile} for database lookup</li>
 *   <li>{@code readUserSecFile} looks up the user in the USRSEC dataset
 *       via {@link UserSecurityRepository#findById(Object)}</li>
 *   <li>Password verification uses {@link PasswordEncoder#matches(CharSequence, String)}
 *       (backed by BCrypt) — <strong>CRITICAL CHANGE</strong> from COBOL plaintext
 *       comparison ({@code IF SEC-USR-PWD = WS-USER-PWD} at line 223)</li>
 *   <li>On success, {@link CardDemoContext} is populated with user session state
 *       matching the 1024-byte COMMAREA fields from COCOM01Y.cpy</li>
 *   <li>Routing to admin menu ({@code COADM01C}) or main menu ({@code COMEN01C})
 *       is determined by {@link UserType} (line 230: {@code IF CDEMO-USRTYP-ADMIN})</li>
 * </ol>
 *
 * <h2>Error Handling</h2>
 * <ul>
 *   <li>Blank user ID → {@link AuthenticationException} with "Please enter User ID ..."</li>
 *   <li>Blank password → {@link AuthenticationException} with "Please enter Password ..."</li>
 *   <li>User not found (VSAM RESP=13 NOTFND) → {@link AuthenticationException}
 *       with "User not found. Try again ..."</li>
 *   <li>Wrong password → {@link AuthenticationException}
 *       with "Wrong Password. Try again ..."</li>
 *   <li>Unexpected I/O error (VSAM RESP=OTHER) → {@link AuthenticationException}
 *       with "Unable to verify the User ..." and root cause</li>
 * </ul>
 *
 * <h2>Security Migration Notes</h2>
 * <p>The original COBOL program stored and compared passwords as plaintext
 * 8-character strings ({@code SEC-USR-PWD PIC X(08)}). In this Java migration,
 * passwords are stored as BCrypt hashes (60–72 characters) and verified using
 * {@link PasswordEncoder} (backed by BCrypt). The COBOL {@code FUNCTION UPPER-CASE} behavior
 * is preserved — user input is uppercased before hashing/matching, maintaining
 * case-insensitive password semantics identical to the mainframe.</p>
 *
 * @see com.cardemo.common.context.CardDemoContext
 * @see com.cardemo.entity.UserSecurity
 * @see com.cardemo.common.enums.UserType
 */
@Service
public class SignonService {

    private static final Logger logger = LoggerFactory.getLogger(SignonService.class);

    // ========================================================================
    // COBOL WORKING-STORAGE equivalents (COSGN00C.cbl lines 36–37)
    // ========================================================================

    /**
     * Program name constant.
     * Maps to COBOL: {@code 05 WS-PGMNAME PIC X(08) VALUE 'COSGN00C'.}
     */
    private static final String WS_PGMNAME = "COSGN00C";

    /**
     * Transaction ID constant.
     * Maps to COBOL: {@code 05 WS-TRANID PIC X(04) VALUE 'CC00'.}
     */
    private static final String WS_TRANID = "CC00";

    /**
     * Target program for admin users after successful authentication.
     * Maps to COBOL: {@code EXEC CICS XCTL PROGRAM ('COADM01C')} (line 232).
     */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /**
     * Target program for regular users after successful authentication.
     * Maps to COBOL: {@code EXEC CICS XCTL PROGRAM ('COMEN01C')} (line 237).
     */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * Date formatter for header display date (MM/DD/YY).
     * Maps to COBOL: {@code WS-CURDATE-MM-DD-YY} (lines 186–190).
     */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MM/dd/yy");

    /**
     * Time formatter for header display time (HH:MM:SS).
     * Maps to COBOL: {@code WS-CURTIME-HH-MM-SS} (lines 192–196).
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // ========================================================================
    // Injected Dependencies (constructor injection)
    // ========================================================================

    /**
     * Spring Data JPA repository for the USRSEC VSAM dataset.
     * Provides {@code findById(String)} for keyed user lookup.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Password encoder (BCrypt implementation) for secure password verification.
     * Replaces COBOL plaintext comparison: {@code IF SEC-USR-PWD = WS-USER-PWD}.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Request-scoped session context bean mirroring the 1024-byte COMMAREA.
     * Populated on successful authentication with user identity and routing info.
     */
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs the {@code SignonService} with all required dependencies.
     *
     * <p>Uses constructor injection (not field injection) for testability and
     * immutability. All dependencies are final and set once at construction.</p>
     *
     * @param userSecurityRepository the JPA repository for USRSEC dataset access
     * @param passwordEncoder        the password encoder (BCrypt) for verification
     * @param cardDemoContext         the request-scoped session context bean
     */
    public SignonService(UserSecurityRepository userSecurityRepository,
                         PasswordEncoder passwordEncoder,
                         CardDemoContext cardDemoContext) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // MAIN-PARA — COSGN00C.cbl line 73
    // ========================================================================

    /**
     * Main entry point for the sign-on authentication flow.
     *
     * <p>Translates the {@code MAIN-PARA} paragraph of COSGN00C.cbl (line 73).
     * This method orchestrates the overall sign-on logic:</p>
     * <ol>
     *   <li>Initializes error flag and message area
     *       (lines 75–78: {@code SET ERR-FLG-OFF}, {@code MOVE SPACES TO WS-MESSAGE})</li>
     *   <li>Checks if this is the initial entry ({@code IF EIBCALEN = 0}, line 80) —
     *       if so, initializes the context and returns (screen display in COBOL)</li>
     *   <li>If credentials are provided, processes the ENTER key action
     *       ({@code WHEN DFHENTER}, line 86)</li>
     *   <li>If the user is already authenticated and no credentials are provided,
     *       handles the PF3 exit scenario ({@code WHEN DFHPF3}, line 88) with the
     *       thank-you message from {@link MessageConstants#THANK_YOU_MESSAGE}</li>
     *   <li>For any other unexpected state, logs the invalid key message from
     *       {@link MessageConstants#INVALID_KEY_MESSAGE} and throws
     *       {@link AuthenticationException}</li>
     * </ol>
     *
     * <p>After successful processing, the {@link CardDemoContext} contains the
     * authenticated user's identity and routing information, equivalent to the
     * COBOL {@code EXEC CICS RETURN COMMAREA(CARDDEMO-COMMAREA)} at line 98.</p>
     *
     * @param userId   the user ID entered on the sign-on screen (may be {@code null}
     *                 for initial entry); maps to {@code USERIDI OF COSGN0AI}
     * @param password the password entered on the sign-on screen (may be {@code null}
     *                 for initial entry); maps to {@code PASSWDI OF COSGN0AI}
     * @throws AuthenticationException if authentication fails for any reason
     *         (blank fields, user not found, wrong password, unexpected error)
     */
    public void mainPara(String userId, String password) {
        logger.debug("MAIN-PARA: Sign-on flow initiated");

        // SET ERR-FLG-OFF TO TRUE (line 75)
        // MOVE SPACES TO WS-MESSAGE ERRMSGO OF COSGN0AO (lines 77-78)
        // Error flag and message are managed via exception throwing in Java

        // IF EIBCALEN = 0 (line 80) — check for initial entry (no session context)
        if (cardDemoContext.getUserId() == null
                && cardDemoContext.getFromProgram() == null) {
            // First-time entry — COBOL: MOVE LOW-VALUES TO COSGN0AO (line 81)
            // Initialize screen context and return (display sign-on screen)
            populateHeaderInfo();
            logger.info("Sign-on screen initialized for new session");
            return;
        }

        // EVALUATE EIBAID (line 85) — determine which action to take
        // WHEN DFHENTER (line 86) — user submitted credentials
        if (userId != null && !userId.isBlank()) {
            // PERFORM PROCESS-ENTER-KEY (line 87)
            processEnterKey(userId, password);
            return;
        }

        // WHEN DFHPF3 (line 88) — user requested exit
        if (cardDemoContext.getUserType() != null) {
            // MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE (line 89)
            // PERFORM SEND-PLAIN-TEXT (line 90) — in headless mode, log and return
            String thankYouMsg = MessageConstants.THANK_YOU_MESSAGE.trim();
            logger.info("User '{}' session exit: {}",
                    cardDemoContext.getUserId(), thankYouMsg);
            return;
        }

        // WHEN OTHER (line 91) — invalid/unrecognized action
        // MOVE 'Y' TO WS-ERR-FLG (line 92)
        // MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE (line 93)
        String invalidKeyMsg = MessageConstants.INVALID_KEY_MESSAGE.trim();
        logger.warn("Invalid sign-on state encountered: {}", invalidKeyMsg);
        throw new AuthenticationException(invalidKeyMsg);
    }

    // ========================================================================
    // PROCESS-ENTER-KEY — COSGN00C.cbl line 108
    // ========================================================================

    /**
     * Processes the ENTER key action: validates credentials and authenticates.
     *
     * <p>Translates the {@code PROCESS-ENTER-KEY} paragraph of COSGN00C.cbl
     * (line 108). This method implements the complete credential validation
     * and authentication sequence:</p>
     * <ol>
     *   <li>Validates that user ID is not blank (lines 118–122:
     *       {@code WHEN USERIDI = SPACES OR LOW-VALUES})</li>
     *   <li>Validates that password is not blank (lines 123–127:
     *       {@code WHEN PASSWDI = SPACES OR LOW-VALUES})</li>
     *   <li>Uppercases both fields (lines 132–136:
     *       {@code MOVE FUNCTION UPPER-CASE(...)})</li>
     *   <li>Looks up the user in the USRSEC dataset via
     *       {@link #readUserSecFile(String)} (line 139:
     *       {@code PERFORM READ-USER-SEC-FILE})</li>
     *   <li>Verifies the password using {@link PasswordEncoder} (BCrypt-backed,
     *       replaces COBOL plaintext comparison at line 223:
     *       {@code IF SEC-USR-PWD = WS-USER-PWD})</li>
     *   <li>On success, populates {@link CardDemoContext} with session state
     *       (lines 224–228) and determines routing based on user type
     *       (lines 230–239)</li>
     * </ol>
     *
     * @param userId   the user ID entered on the sign-on screen; must not be
     *                 blank; maps to {@code USERIDI OF COSGN0AI}
     * @param password the password entered on the sign-on screen; must not be
     *                 blank; maps to {@code PASSWDI OF COSGN0AI}
     * @throws AuthenticationException if credentials are blank, user is not found,
     *         password does not match, or an unexpected error occurs
     */
    public void processEnterKey(String userId, String password) {
        logger.debug("PROCESS-ENTER-KEY: Processing credentials for sign-on");

        // EVALUATE TRUE (line 117)
        // WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES (line 118)
        if (userId == null || userId.isBlank()) {
            // MOVE 'Y' TO WS-ERR-FLG (line 119)
            // MOVE 'Please enter User ID ...' TO WS-MESSAGE (line 120)
            logger.warn("Sign-on validation failed: user ID is blank");
            throw new AuthenticationException("Please enter User ID ...");
        }

        // WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES (line 123)
        if (password == null || password.isBlank()) {
            // MOVE 'Y' TO WS-ERR-FLG (line 124)
            // MOVE 'Please enter Password ...' TO WS-MESSAGE (line 125)
            logger.warn("Sign-on validation failed: password is blank");
            throw new AuthenticationException("Please enter Password ...");
        }

        // MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
        //     WS-USER-ID, CDEMO-USER-ID (lines 132-134)
        String normalizedUserId = userId.toUpperCase().trim();

        // MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO
        //     WS-USER-PWD (lines 135-136)
        String normalizedPassword = password.toUpperCase();

        // Set CDEMO-USER-ID early (line 134) — matches COBOL behavior
        cardDemoContext.setUserId(normalizedUserId);

        // IF NOT ERR-FLG-ON (line 138)
        //     PERFORM READ-USER-SEC-FILE (line 139)
        UserSecurity user = readUserSecFile(normalizedUserId);

        // ================================================================
        // Password Verification — CRITICAL CHANGE from COBOL
        // COBOL (line 223): IF SEC-USR-PWD = WS-USER-PWD (plaintext)
        // Java: BCrypt hash verification via passwordEncoder.matches()
        // ================================================================
        if (!passwordEncoder.matches(normalizedPassword, user.getPassword())) {
            // MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE (lines 242-243)
            logger.warn("Authentication failed: wrong password for user '{}'",
                    normalizedUserId);
            throw new AuthenticationException("Wrong Password. Try again ...");
        }

        // ================================================================
        // Authentication successful — populate COMMAREA (lines 224-228)
        // ================================================================

        // MOVE WS-TRANID TO CDEMO-FROM-TRANID (line 224)
        cardDemoContext.setFromTranId(WS_TRANID);

        // MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM (line 225)
        cardDemoContext.setFromProgram(WS_PGMNAME);

        // MOVE WS-USER-ID TO CDEMO-USER-ID (line 226) — already set above
        cardDemoContext.setUserId(normalizedUserId);

        // MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE (line 227)
        // Maps from SEC-USR-TYPE 'A'/'U' → UserType.ADMIN/USER
        UserType userType = user.getUserType();
        cardDemoContext.setUserType(userType);

        // MOVE ZEROS TO CDEMO-PGM-CONTEXT (line 228)
        // Value 0 = CDEMO-PGM-ENTER (88-level condition)
        cardDemoContext.setPgmContext(CardDemoContext.PGM_ENTER);

        // ================================================================
        // Route to next program based on user type (lines 230-239)
        // IF CDEMO-USRTYP-ADMIN → XCTL to COADM01C
        // ELSE → XCTL to COMEN01C
        // ================================================================
        if (userType == UserType.ADMIN) {
            // EXEC CICS XCTL PROGRAM ('COADM01C') COMMAREA(...) (lines 231-234)
            cardDemoContext.setToProgram(ADMIN_PROGRAM);
            logger.info("Authentication successful: admin user '{}' routed to {}",
                    normalizedUserId, ADMIN_PROGRAM);
        } else if (userType == UserType.USER) {
            // EXEC CICS XCTL PROGRAM ('COMEN01C') COMMAREA(...) (lines 236-239)
            cardDemoContext.setToProgram(MAIN_MENU_PROGRAM);
            logger.info("Authentication successful: user '{}' routed to {}",
                    normalizedUserId, MAIN_MENU_PROGRAM);
        } else {
            // Defensive — should never occur with valid SEC-USR-TYPE data
            cardDemoContext.setToProgram(MAIN_MENU_PROGRAM);
            logger.warn("Unknown user type '{}' for user '{}', "
                    + "defaulting to main menu", userType, normalizedUserId);
        }
    }

    // ========================================================================
    // READ-USER-SEC-FILE — COSGN00C.cbl line 209
    // ========================================================================

    /**
     * Reads a user security record from the USRSEC dataset.
     *
     * <p>Translates the {@code READ-USER-SEC-FILE} paragraph of COSGN00C.cbl
     * (line 209). Maps the CICS keyed READ operation to a JPA
     * {@link UserSecurityRepository#findById(Object)} call:</p>
     * <pre>
     *     EXEC CICS READ
     *          DATASET   (WS-USRSEC-FILE)
     *          INTO      (SEC-USER-DATA)
     *          LENGTH    (LENGTH OF SEC-USER-DATA)
     *          RIDFLD    (WS-USER-ID)
     *          KEYLENGTH (LENGTH OF WS-USER-ID)
     *          RESP      (WS-RESP-CD)
     *          RESP2     (WS-REAS-CD)
     *     END-EXEC.
     * </pre>
     *
     * <p>The CICS RESP codes map to Java outcomes:</p>
     * <ul>
     *   <li>RESP=0 (normal read) → return the found {@link UserSecurity} entity</li>
     *   <li>RESP=13 (NOTFND) → throw {@link AuthenticationException} with
     *       "User not found. Try again ..." (line 249)</li>
     *   <li>RESP=OTHER → throw {@link AuthenticationException} with
     *       "Unable to verify the User ..." and the root cause (line 254)</li>
     * </ul>
     *
     * @param userId the user ID to look up (uppercase, trimmed); maps to
     *               COBOL {@code WS-USER-ID PIC X(08)} used as RIDFLD
     * @return the {@link UserSecurity} entity for the given user ID
     * @throws AuthenticationException if the user is not found (RESP=13)
     *         or an unexpected error occurs (RESP=OTHER)
     */
    public UserSecurity readUserSecFile(String userId) {
        logger.debug("READ-USER-SEC-FILE: Looking up USRSEC for userId='{}'", userId);

        // EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
        //      RIDFLD(WS-USER-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        Optional<UserSecurity> userOpt;
        try {
            userOpt = userSecurityRepository.findById(userId);
        } catch (AuthenticationException e) {
            // Re-throw any authentication exceptions unchanged
            throw e;
        } catch (Exception e) {
            // EVALUATE WS-RESP-CD ... WHEN OTHER (lines 252-256)
            // Unexpected I/O error during CICS READ
            logger.error("Unexpected error reading USRSEC for userId='{}': {}",
                    userId, e.getMessage(), e);
            throw new AuthenticationException(
                    "Unable to verify the User ...", e);
        }

        // EVALUATE WS-RESP-CD (line 221)
        if (!userOpt.isPresent()) {
            // WHEN 13 (DFHRESP NOTFND) — lines 247-251
            // User ID does not exist in the USRSEC VSAM dataset
            logger.warn("User '{}' not found in USRSEC dataset", userId);
            throw new AuthenticationException(
                    "User not found. Try again ...");
        }

        // WHEN 0 (normal read) — line 222
        UserSecurity user = userOpt.get();
        logger.debug("USRSEC record found: userId='{}', userType={}",
                user.getUserId(), user.getUserType());
        return user;
    }

    // ========================================================================
    // POPULATE-HEADER-INFO — COSGN00C.cbl line 177
    // ========================================================================

    /**
     * Populates header information in the session context.
     *
     * <p>Translates the {@code POPULATE-HEADER-INFO} paragraph of COSGN00C.cbl
     * (line 177). In the COBOL program, this paragraph populates BMS map
     * output fields with the current date/time, transaction ID, and program
     * name for display on the sign-on screen header:</p>
     * <pre>
     *     MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA        (line 179)
     *     MOVE CCDA-TITLE01          TO TITLE01O OF COSGN0AO   (line 181)
     *     MOVE WS-TRANID             TO TRNNAMEO OF COSGN0AO   (line 183)
     *     MOVE WS-PGMNAME            TO PGMNAMEO OF COSGN0AO   (line 184)
     *     MOVE WS-CURDATE-MM-DD-YY   TO CURDATEO OF COSGN0AO   (line 190)
     *     MOVE WS-CURTIME-HH-MM-SS   TO CURTIMEO OF COSGN0AO   (line 196)
     * </pre>
     *
     * <p>In the headless service layer, BMS map output fields do not apply.
     * Instead, this method populates the {@link CardDemoContext} with the
     * program identity and current date/time for use by the controller/response
     * layer. The date and time formatting preserves the COBOL display format
     * ({@code MM/DD/YY} and {@code HH:MM:SS}).</p>
     */
    public void populateHeaderInfo() {
        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA (line 179)
        LocalDate currentDate = LocalDate.now();
        LocalTime currentTime = LocalTime.now();

        // MOVE WS-TRANID TO TRNNAMEO OF COSGN0AO (line 183)
        cardDemoContext.setFromTranId(WS_TRANID);

        // MOVE WS-PGMNAME TO PGMNAMEO OF COSGN0AO (line 184)
        cardDemoContext.setFromProgram(WS_PGMNAME);

        // MOVE ZEROS TO CDEMO-PGM-CONTEXT — initialize to ENTER state
        cardDemoContext.setPgmContext(CardDemoContext.PGM_ENTER);

        // Log formatted date/time matching COBOL display format:
        // WS-CURDATE-MM-DD-YY (line 190) and WS-CURTIME-HH-MM-SS (line 196)
        String formattedDate = currentDate.format(DATE_FORMATTER);
        String formattedTime = currentTime.format(TIME_FORMATTER);

        logger.debug("POPULATE-HEADER-INFO: tranId='{}', pgmName='{}', "
                        + "date='{}', time='{}'",
                WS_TRANID, WS_PGMNAME, formattedDate, formattedTime);
    }
}

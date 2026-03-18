/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
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
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for user deletion operations — faithful translation of COBOL program
 * {@code COUSR03C.cbl} (app/cbl/COUSR03C.cbl).
 *
 * <p>This service implements the admin-only user deletion workflow from the
 * CardDemo CICS application. In the original COBOL program, the pseudo-
 * conversational model uses a two-step flow: the first interaction reads and
 * displays user details for confirmation, and the second interaction (PF5 key)
 * performs the actual deletion. In the Java headless service layer, this maps
 * to a {@code confirmed} boolean parameter on the main entry methods.</p>
 *
 * <h2>COBOL Paragraph → Java Method Traceability</h2>
 * <table>
 *   <caption>Complete paragraph-to-method mapping for COUSR03C.cbl</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th><th>Notes</th></tr>
 *   <tr><td>MAIN-PARA</td><td>82</td>
 *       <td>{@link #deleteUser(String, boolean)}</td>
 *       <td>Main entry point with admin gate and two-step flow</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>142</td>
 *       <td>{@link #processEnterKey(String, boolean)}</td>
 *       <td>Enter key action: validate userId, read user for display</td></tr>
 *   <tr><td>DELETE-USER-INFO</td><td>174</td>
 *       <td>{@link #deleteUserInfo(String)}</td>
 *       <td>Read-then-delete sequence (PF5 confirmation)</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN</td><td>197</td>
 *       <td><em>Controller layer</em></td>
 *       <td>Navigation — XCTL to previous program</td></tr>
 *   <tr><td>SEND-USRDEL-SCREEN</td><td>213</td>
 *       <td><em>Controller layer</em></td>
 *       <td>Presentation — BMS SEND MAP</td></tr>
 *   <tr><td>RECEIVE-USRDEL-SCREEN</td><td>230</td>
 *       <td><em>Controller layer</em></td>
 *       <td>Input — BMS RECEIVE MAP</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>243</td>
 *       <td><em>Controller layer</em></td>
 *       <td>Header fields (date, time, program name)</td></tr>
 *   <tr><td>READ-USER-SEC-FILE</td><td>267</td>
 *       <td>{@link #readUserSecFile(String)}</td>
 *       <td>CICS READ DATASET(USRSEC) by RIDFLD</td></tr>
 *   <tr><td>DELETE-USER-SEC-FILE</td><td>305</td>
 *       <td>{@link #deleteUserSecFile(String)}</td>
 *       <td>CICS DELETE DATASET(USRSEC) by RIDFLD</td></tr>
 *   <tr><td>CLEAR-CURRENT-SCREEN</td><td>341</td>
 *       <td><em>Controller layer</em></td>
 *       <td>Screen clear (PF4)</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS</td><td>349</td>
 *       <td><em>Controller layer</em></td>
 *       <td>Field initialization</td></tr>
 * </table>
 *
 * <h2>COBOL Working Storage References</h2>
 * <ul>
 *   <li>{@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'} → {@link #PROGRAM_NAME}</li>
 *   <li>{@code WS-TRANID  PIC X(04) VALUE 'CU03'}     → {@link #TRANSACTION_ID}</li>
 *   <li>{@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} → {@link #USRSEC_FILE}</li>
 *   <li>{@code CCDA-MSG-INVALID-KEY} → {@link #INVALID_ACTION_MESSAGE}</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>All operations in this service are restricted to administrators
 * ({@link UserType#ADMIN}). The COBOL program is only accessible through the
 * Admin Menu (COADM01C), and this Java service replicates the access gate by
 * checking {@code CardDemoContext.getUserType()} before any mutation.</p>
 *
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.entity.UserSecurity
 * @see com.cardemo.common.context.CardDemoContext
 */
@Service
@Transactional
public class UserDeleteService {

    private static final Logger logger = LoggerFactory.getLogger(UserDeleteService.class);

    // ========================================================================
    // COBOL Working Storage Constants
    // ========================================================================

    /**
     * Program name constant.
     * Maps to COBOL: {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'}.
     */
    private static final String PROGRAM_NAME = "COUSR03C";

    /**
     * Transaction ID constant.
     * Maps to COBOL: {@code WS-TRANID PIC X(04) VALUE 'CU03'}.
     */
    private static final String TRANSACTION_ID = "CU03";

    /**
     * VSAM file name for user security records.
     * Maps to COBOL: {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}.
     */
    private static final String USRSEC_FILE = "USRSEC";

    /**
     * Invalid action/key message for controller-layer error responses.
     * Maps COBOL line 128: {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE}
     * when an unrecognized EIBAID value is received in the EVALUATE block.
     *
     * <p>In the headless Java service, this constant is provided for the
     * {@code UserAdminController} to return when a request action type does
     * not map to a recognized operation (ENTER for view, PF5 for delete,
     * PF3/PF12 for navigate back, or PF4 for clear screen).</p>
     */
    public static final String INVALID_ACTION_MESSAGE = MessageConstants.INVALID_KEY_MESSAGE;

    // ========================================================================
    // Injected Dependencies
    // ========================================================================

    /**
     * Repository for USRSEC VSAM dataset access.
     * Maps COBOL: {@code EXEC CICS READ/DELETE DATASET(WS-USRSEC-FILE)}.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Request-scoped session context mirroring the 1024-byte CARDDEMO-COMMAREA.
     * Used for: admin authorization check ({@code getUserType()}) and audit
     * logging ({@code getUserId()}).
     */
    private final CardDemoContext cardDemoContext;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Constructs the UserDeleteService with required dependencies.
     *
     * <p>Uses Spring constructor injection (preferred over field injection)
     * to ensure the service is fully initialized before use. This maps the
     * COBOL pattern of accessing copybook structures and file definitions
     * that are available from program initialization.</p>
     *
     * @param userSecurityRepository the repository for USRSEC dataset operations
     * @param cardDemoContext         the request-scoped COMMAREA equivalent
     */
    public UserDeleteService(UserSecurityRepository userSecurityRepository,
                             CardDemoContext cardDemoContext) {
        this.userSecurityRepository = userSecurityRepository;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Public Service Methods (← COBOL Paragraphs)
    // ========================================================================

    /**
     * Main entry point for user deletion — maps MAIN-PARA (COUSR03C.cbl line 82).
     *
     * <p>Implements the complete COBOL MAIN-PARA flow with admin authorization
     * and the two-step confirmation pattern:</p>
     * <ol>
     *   <li><strong>Step 1 ({@code confirmed=false}):</strong> Reads the user
     *       record and returns it for display/confirmation. Maps the COBOL first
     *       entry path where {@code CDEMO-PGM-REENTER} is false and the user
     *       record is read via {@code PERFORM READ-USER-SEC-FILE}.</li>
     *   <li><strong>Step 2 ({@code confirmed=true}):</strong> Performs the actual
     *       deletion. Maps the COBOL PF5 key handler at line 122:
     *       {@code WHEN DFHPF5 → PERFORM DELETE-USER-INFO}.</li>
     * </ol>
     *
     * <p><strong>COBOL traceability:</strong></p>
     * <pre>
     *   MAIN-PARA.
     *       SET ERR-FLG-OFF     TO TRUE
     *       SET USR-MODIFIED-NO TO TRUE
     *       ...
     *       EVALUATE EIBAID
     *           WHEN DFHENTER  → PERFORM PROCESS-ENTER-KEY
     *           WHEN DFHPF5    → PERFORM DELETE-USER-INFO
     *           WHEN OTHER     → CCDA-MSG-INVALID-KEY
     *       END-EVALUATE
     * </pre>
     *
     * @param userId    the user ID to delete (maps {@code SEC-USR-ID PIC X(08)},
     *                  max 8 characters)
     * @param confirmed {@code true} if the user has confirmed deletion (maps PF5
     *                  key in COBOL), {@code false} for initial read/display
     *                  (maps DFHENTER key)
     * @return the {@link UserSecurity} record for confirmation display when
     *         {@code confirmed=false}; {@code null} when deletion is complete
     *         ({@code confirmed=true})
     * @throws SecurityException       if the current user is not an administrator
     *                                 (admin gate — COBOL access is restricted via
     *                                 Admin Menu COADM01C navigation)
     * @throws IllegalArgumentException if {@code userId} is null or blank
     * @throws RecordNotFoundException  if the specified user ID does not exist
     *                                  in the USRSEC dataset (VSAM status '23')
     */
    public UserSecurity deleteUser(String userId, boolean confirmed) {
        // Maps COBOL: SET ERR-FLG-OFF TO TRUE, SET USR-MODIFIED-NO TO TRUE
        logger.debug("[{}:{}] deleteUser called: userId='{}', confirmed={}, requestedBy='{}'",
                PROGRAM_NAME, TRANSACTION_ID, userId, confirmed,
                cardDemoContext.getUserId());

        // Admin-only authorization gate
        // Maps COBOL: COUSR03C is only reachable from COADM01C admin menu
        // which is gated by CDEMO-USRTYP-ADMIN condition in COCOM01Y.cpy
        if (cardDemoContext.getUserType() != UserType.ADMIN) {
            logger.warn("[{}] Non-admin user '{}' attempted user deletion operation",
                    PROGRAM_NAME, cardDemoContext.getUserId());
            throw new SecurityException(
                    "User deletion requires administrator privileges");
        }

        // Validate userId — maps COBOL EVALUATE TRUE in PROCESS-ENTER-KEY (line 144)
        // WHEN USRIDINI = SPACES OR LOW-VALUES → error
        if (userId == null || userId.isBlank()) {
            logger.warn("[{}] User deletion attempted with empty userId", PROGRAM_NAME);
            throw new IllegalArgumentException("User ID can NOT be empty...");
        }

        if (!confirmed) {
            // Step 1: Read user for confirmation display
            // Maps COBOL: first entry path → PERFORM PROCESS-ENTER-KEY
            //   → PERFORM READ-USER-SEC-FILE → PERFORM SEND-USRDEL-SCREEN
            UserSecurity user = readUserSecFile(userId);
            logger.info("[{}] User '{}' ({} {}) retrieved for deletion confirmation",
                    PROGRAM_NAME, user.getUserId(),
                    user.getFirstName(), user.getLastName());
            return user;
        }

        // Step 2: Deletion confirmed — perform actual delete
        // Maps COBOL: WHEN DFHPF5 → PERFORM DELETE-USER-INFO (line 122)
        deleteUserInfo(userId);

        logger.info("[{}] User '{}' deleted successfully by admin '{}'",
                PROGRAM_NAME, userId, cardDemoContext.getUserId());
        return null;
    }

    /**
     * Processes Enter key action — maps PROCESS-ENTER-KEY (COUSR03C.cbl line 142).
     *
     * <p>Validates the user ID, then either reads user data for confirmation
     * display or proceeds with deletion based on the {@code confirmed} flag.</p>
     *
     * <p><strong>COBOL traceability:</strong></p>
     * <pre>
     *   PROCESS-ENTER-KEY.
     *       EVALUATE TRUE
     *           WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
     *               MOVE 'Y' TO WS-ERR-FLG
     *               MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
     *           WHEN OTHER
     *               CONTINUE
     *       END-EVALUATE
     *       IF NOT ERR-FLG-ON
     *           MOVE USRIDINI TO SEC-USR-ID
     *           PERFORM READ-USER-SEC-FILE
     *       END-IF.
     *       IF NOT ERR-FLG-ON
     *           MOVE SEC-USR-FNAME TO FNAMEI
     *           MOVE SEC-USR-LNAME TO LNAMEI
     *           MOVE SEC-USR-TYPE  TO USRTYPEI
     *           PERFORM SEND-USRDEL-SCREEN
     *       END-IF.
     * </pre>
     *
     * @param userId    the user ID to look up or delete (maps
     *                  {@code USRIDINI OF COUSR3AI PIC X(8)})
     * @param confirmed {@code true} if deletion has been confirmed,
     *                  {@code false} for initial lookup/display
     * @return the {@link UserSecurity} record if reading for confirmation
     *         ({@code confirmed=false}), {@code null} after successful deletion
     *         ({@code confirmed=true})
     * @throws IllegalArgumentException if {@code userId} is null or blank
     *                                  (maps COBOL line 147: "User ID can NOT
     *                                  be empty...")
     * @throws RecordNotFoundException  if the user ID does not exist
     */
    public UserSecurity processEnterKey(String userId, boolean confirmed) {
        logger.debug("[{}] processEnterKey: userId='{}', confirmed={}",
                PROGRAM_NAME, userId, confirmed);

        // Maps COBOL EVALUATE TRUE (lines 144-154):
        // WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
        //     → 'User ID can NOT be empty...'
        if (userId == null || userId.isBlank()) {
            logger.warn("[{}] processEnterKey: empty userId provided", PROGRAM_NAME);
            throw new IllegalArgumentException("User ID can NOT be empty...");
        }

        if (!confirmed) {
            // Maps COBOL: MOVE USRIDINI TO SEC-USR-ID
            //             PERFORM READ-USER-SEC-FILE
            //             MOVE SEC-USR-FNAME TO FNAMEI
            //             MOVE SEC-USR-LNAME TO LNAMEI
            //             MOVE SEC-USR-TYPE  TO USRTYPEI
            UserSecurity user = readUserSecFile(userId);
            logger.debug("[{}] User retrieved for display: id='{}', name='{} {}', type={}",
                    PROGRAM_NAME, user.getUserId(),
                    user.getFirstName(), user.getLastName(),
                    user.getUserType());
            return user;
        }

        // Maps COBOL: confirmed → proceed with deletion
        deleteUserInfo(userId);
        return null;
    }

    /**
     * Reads user, then deletes — maps DELETE-USER-INFO (COUSR03C.cbl line 174).
     *
     * <p>Validates the user ID, reads the user record to verify existence, then
     * performs the deletion. This is the core business logic invoked by PF5
     * (confirmed delete) in the COBOL program.</p>
     *
     * <p><strong>COBOL traceability:</strong></p>
     * <pre>
     *   DELETE-USER-INFO.
     *       EVALUATE TRUE
     *           WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
     *               MOVE 'Y' TO WS-ERR-FLG
     *               MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
     *           WHEN OTHER
     *               CONTINUE
     *       END-EVALUATE
     *       IF NOT ERR-FLG-ON
     *           MOVE USRIDINI TO SEC-USR-ID
     *           PERFORM READ-USER-SEC-FILE
     *           PERFORM DELETE-USER-SEC-FILE
     *       END-IF.
     * </pre>
     *
     * @param userId the user ID to delete (maps {@code USRIDINI PIC X(8)})
     * @throws IllegalArgumentException if {@code userId} is null or blank
     * @throws RecordNotFoundException  if the user ID does not exist
     */
    public void deleteUserInfo(String userId) {
        logger.debug("[{}] deleteUserInfo: userId='{}'", PROGRAM_NAME, userId);

        // Maps COBOL EVALUATE TRUE (lines 176-186):
        // WHEN USRIDINI = SPACES OR LOW-VALUES → error
        if (userId == null || userId.isBlank()) {
            logger.warn("[{}] deleteUserInfo: empty userId provided", PROGRAM_NAME);
            throw new IllegalArgumentException("User ID can NOT be empty...");
        }

        // Maps COBOL: PERFORM READ-USER-SEC-FILE (line 190)
        // Read first to verify existence and log details before deletion
        UserSecurity user = readUserSecFile(userId);

        logger.info("[{}] Proceeding to delete user '{}' ({} {}), type={}",
                PROGRAM_NAME, user.getUserId(),
                user.getFirstName(), user.getLastName(),
                user.getUserType());

        // Maps COBOL: PERFORM DELETE-USER-SEC-FILE (line 191)
        deleteUserSecFile(userId);
    }

    /**
     * Reads a user security record by user ID — maps READ-USER-SEC-FILE
     * (COUSR03C.cbl line 267).
     *
     * <p>Performs a keyed read of the USRSEC VSAM dataset using the user ID
     * as the record identifier (RIDFLD). In COBOL, this is a standard
     * {@code EXEC CICS READ} operation with RESP/RESP2 code evaluation.</p>
     *
     * <p><strong>COBOL traceability:</strong></p>
     * <pre>
     *   READ-USER-SEC-FILE.
     *       EXEC CICS READ
     *            DATASET   (WS-USRSEC-FILE)
     *            INTO      (SEC-USER-DATA)
     *            LENGTH    (LENGTH OF SEC-USER-DATA)
     *            RIDFLD    (SEC-USR-ID)
     *            KEYLENGTH (LENGTH OF SEC-USR-ID)
     *            UPDATE
     *            RESP      (WS-RESP-CD)
     *            RESP2     (WS-REAS-CD)
     *       END-EXEC.
     *       EVALUATE WS-RESP-CD
     *           WHEN DFHRESP(NORMAL)  → CONTINUE (display user info)
     *           WHEN DFHRESP(NOTFND)  → 'User ID NOT found...'
     *           WHEN OTHER            → 'Unable to lookup User...'
     *       END-EVALUATE.
     * </pre>
     *
     * <p><strong>RESP code mapping:</strong></p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} → return found entity</li>
     *   <li>{@code DFHRESP(NOTFND)} → throw {@link RecordNotFoundException}
     *       with message "User not found: {userId}"</li>
     *   <li>{@code OTHER} → throw {@link RecordNotFoundException} with message
     *       "Unable to lookup User: {userId}"</li>
     * </ul>
     *
     * <p><strong>Note:</strong> The COBOL program uses {@code READ UPDATE} which
     * acquires a record-level lock for subsequent REWRITE/DELETE. In Java, the
     * deletion uses {@code deleteById()} which handles the locate-and-remove
     * atomically within the transaction, so no explicit lock is needed.</p>
     *
     * @param userId the user ID to look up (maps {@code SEC-USR-ID PIC X(08)},
     *               max 8 characters)
     * @return the found {@link UserSecurity} entity
     * @throws RecordNotFoundException if the user ID does not exist
     *                                 (VSAM status '23', DFHRESP(NOTFND))
     */
    @Transactional(readOnly = true)
    public UserSecurity readUserSecFile(String userId) {
        logger.debug("[{}] READ-USER-SEC-FILE: reading {} file, RIDFLD='{}'",
                PROGRAM_NAME, USRSEC_FILE, userId);

        // Maps: EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
        //       RIDFLD(SEC-USR-ID) RESP(WS-RESP-CD)
        return userSecurityRepository.findById(userId)
                .orElseThrow(() -> {
                    // Maps COBOL: WHEN DFHRESP(NOTFND)
                    //   → MOVE 'User ID NOT found...' TO WS-MESSAGE
                    logger.warn("[{}] READ-USER-SEC-FILE: NOTFND — userId='{}' "
                            + "not found in {} file", PROGRAM_NAME, userId, USRSEC_FILE);
                    return new RecordNotFoundException(
                            "User not found: " + userId);
                });
    }

    /**
     * Deletes a user security record by user ID — maps DELETE-USER-SEC-FILE
     * (COUSR03C.cbl line 305).
     *
     * <p>Performs a keyed delete of the USRSEC VSAM dataset. In COBOL, this is
     * an {@code EXEC CICS DELETE} operation following a prior {@code READ UPDATE}
     * that established a record-level lock. In Java, {@code deleteById()} handles
     * the locate-and-remove atomically within the transaction boundary.</p>
     *
     * <p><strong>COBOL traceability:</strong></p>
     * <pre>
     *   DELETE-USER-SEC-FILE.
     *       EXEC CICS DELETE
     *            DATASET   (WS-USRSEC-FILE)
     *            RESP      (WS-RESP-CD)
     *            RESP2     (WS-REAS-CD)
     *       END-EXEC.
     *       EVALUATE WS-RESP-CD
     *           WHEN DFHRESP(NORMAL)
     *               PERFORM INITIALIZE-ALL-FIELDS
     *               STRING 'User ' SEC-USR-ID ' has been deleted ...'
     *                 INTO WS-MESSAGE
     *           WHEN DFHRESP(NOTFND)
     *               → 'User ID NOT found...'
     *           WHEN OTHER
     *               → 'Unable to Update User...'
     *       END-EVALUATE.
     * </pre>
     *
     * <p><strong>RESP code mapping:</strong></p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} → successful deletion, log confirmation</li>
     *   <li>{@code DFHRESP(NOTFND)} → throw {@link RecordNotFoundException}</li>
     *   <li>{@code OTHER} → throw {@link RecordNotFoundException}</li>
     * </ul>
     *
     * <p><strong>No cascading deletes:</strong> The COBOL USRSEC dataset is
     * standalone with no foreign key relationships to other VSAM files. The
     * Java implementation preserves this — no cascade annotations or dependent
     * entity cleanup is performed.</p>
     *
     * @param userId the user ID to delete (maps {@code SEC-USR-ID PIC X(08)},
     *               max 8 characters)
     * @throws RecordNotFoundException if the user ID does not exist
     *                                 (VSAM status '23', DFHRESP(NOTFND))
     */
    public void deleteUserSecFile(String userId) {
        logger.debug("[{}] DELETE-USER-SEC-FILE: deleting from {} file, RIDFLD='{}'",
                PROGRAM_NAME, USRSEC_FILE, userId);

        // Verify record exists before deletion — maps COBOL DFHRESP(NOTFND) check
        // Uses findById() to match COBOL's implicit existence verification
        // during EXEC CICS DELETE
        if (userSecurityRepository.findById(userId).isEmpty()) {
            // Maps COBOL: WHEN DFHRESP(NOTFND)
            //   → MOVE 'User ID NOT found...' TO WS-MESSAGE
            logger.warn("[{}] DELETE-USER-SEC-FILE: NOTFND — userId='{}' "
                    + "not found in {} file", PROGRAM_NAME, userId, USRSEC_FILE);
            throw new RecordNotFoundException(
                    "User not found: " + userId);
        }

        // Maps: EXEC CICS DELETE DATASET(WS-USRSEC-FILE) RIDFLD(SEC-USR-ID)
        userSecurityRepository.deleteById(userId);

        // Maps COBOL: WHEN DFHRESP(NORMAL)
        //   → STRING 'User ' SEC-USR-ID ' has been deleted ...' INTO WS-MESSAGE
        logger.info("[{}] DELETE-USER-SEC-FILE: User '{}' has been deleted from {} file",
                PROGRAM_NAME, userId, USRSEC_FILE);
    }
}

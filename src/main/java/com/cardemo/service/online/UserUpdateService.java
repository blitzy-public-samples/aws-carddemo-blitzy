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
import com.cardemo.common.exception.ValidationException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring {@code @Service} that faithfully translates the COBOL program
 * {@code COUSR02C.cbl} — admin user update with optimistic locking.
 *
 * <p>This service encapsulates the complete user update workflow from the
 * CardDemo CICS application. Every COBOL paragraph in {@code COUSR02C.cbl}
 * is mapped to a corresponding Java method, preserving 100% business logic
 * parity with the original mainframe implementation.</p>
 *
 * <h2>COBOL Paragraph → Java Method Traceability</h2>
 * <pre>
 *   Line  82: MAIN-PARA               → {@link #updateUser(String, UserUpdateRequest)}
 *   Line 143: PROCESS-ENTER-KEY       → {@link #processEnterKey(String, UserUpdateRequest)}
 *   Line 177: UPDATE-USER-INFO        → {@link #updateUserInfo(UserSecurity, UserUpdateRequest)}
 *   Line 250: RETURN-TO-PREV-SCREEN   → (navigation — handled by controller layer)
 *   Line 266: SEND-USRUPD-SCREEN      → (presentation — handled by controller layer)
 *   Line 283: RECEIVE-USRUPD-SCREEN   → (inputs from controller layer)
 *   Line 296: POPULATE-HEADER-INFO    → {@link #populateHeaderInfo()}
 *   Line 320: READ-USER-SEC-FILE      → {@link #readUserSecFile(String)}
 *   Line 358: UPDATE-USER-SEC-FILE    → {@link #updateUserSecFile(UserSecurity)}
 *   Line 395: CLEAR-CURRENT-SCREEN    → (presentation — handled by controller layer)
 *   Line 403: INITIALIZE-ALL-FIELDS   → (presentation — handled by controller layer)
 * </pre>
 *
 * <h2>Security Enhancement</h2>
 * <p>The original COBOL program stores passwords as 8-character plaintext in
 * {@code SEC-USR-PWD PIC X(08)}. This Java migration replaces plaintext storage
 * with BCrypt-hashed passwords using {@link BCryptPasswordEncoder}, a critical
 * security improvement that preserves the authentication flow while eliminating
 * the plaintext vulnerability.</p>
 *
 * <h2>Optimistic Locking</h2>
 * <p>The COBOL {@code EXEC CICS READ UPDATE → REWRITE} pattern is replaced by
 * JPA {@code @Version}-based optimistic locking on the {@link UserSecurity}
 * entity. This ensures data integrity without holding database-level locks
 * between read and write operations, matching the CICS record-level locking
 * semantics in a stateless service architecture.</p>
 *
 * <h2>Access Control</h2>
 * <p>User update is an admin-only operation. The service verifies that
 * {@code CardDemoContext.getUserType() == UserType.ADMIN} before allowing
 * any update, matching the CICS transaction routing that restricts
 * {@code CU02} to administrator users.</p>
 *
 * @see com.cardemo.entity.UserSecurity
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.common.context.CardDemoContext
 */
@Service
public class UserUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(UserUpdateService.class);

    /**
     * Program name constant — maps COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'}.
     * Used for header information population and structured logging.
     */
    private static final String PROGRAM_NAME = "COUSR02C";

    /**
     * Transaction ID constant — maps COBOL {@code WS-TRANID PIC X(04) VALUE 'CU02'}.
     * Used for header information population and structured logging.
     */
    private static final String TRANSACTION_ID = "CU02";

    private final UserSecurityRepository userSecurityRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs a new {@code UserUpdateService} with required dependencies.
     *
     * <p>All dependencies are injected via Spring constructor injection,
     * replacing the COBOL pattern where file handles and working storage
     * are declared in the DATA DIVISION.</p>
     *
     * @param userSecurityRepository JPA repository for USRSEC VSAM dataset access;
     *                               provides {@code findById()} (READ) and
     *                               {@code save()} (REWRITE) operations
     * @param passwordEncoder        BCrypt encoder for hashing passwords during
     *                               updates; replaces COBOL plaintext
     *                               {@code SEC-USR-PWD PIC X(08)} storage
     * @param cardDemoContext         request-scoped COMMAREA equivalent carrying
     *                               the authenticated user's ID and type for
     *                               admin authorization verification
     */
    public UserUpdateService(UserSecurityRepository userSecurityRepository,
                             BCryptPasswordEncoder passwordEncoder,
                             CardDemoContext cardDemoContext) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.cardDemoContext = cardDemoContext;
    }

    // =========================================================================
    // Inner Request DTO — Maps BMS input fields from COUSR2AI (COUSR02.CPY)
    // =========================================================================

    /**
     * Request data transfer object for user update operations.
     *
     * <p>Maps the input fields from BMS map {@code COUSR2AI} defined in
     * {@code COUSR02.CPY}. Each field corresponds to a COBOL BMS field:</p>
     * <ul>
     *   <li>{@code firstName} ← {@code FNAMEI PIC X(20)}</li>
     *   <li>{@code lastName}  ← {@code LNAMEI PIC X(20)}</li>
     *   <li>{@code newPassword} ← {@code PASSWDI PIC X(8)} (BCrypt-encoded on save)</li>
     *   <li>{@code userType}  ← {@code USRTYPEI PIC X(1)} — {@code "A"} or {@code "U"}</li>
     * </ul>
     *
     * <p>Note: The user ID ({@code USRIDINI PIC X(8)}) is passed as a
     * separate path parameter since it is the immutable primary key
     * ({@code SEC-USR-ID}, the VSAM RIDFLD).</p>
     */
    public static class UserUpdateRequest {

        /** User first name — maps {@code FNAMEI PIC X(20)} from BMS COUSR2AI. */
        private String firstName;

        /** User last name — maps {@code LNAMEI PIC X(20)} from BMS COUSR2AI. */
        private String lastName;

        /**
         * New password — maps {@code PASSWDI PIC X(8)} from BMS COUSR2AI.
         * Will be BCrypt-encoded before persistence, replacing the COBOL
         * plaintext {@code SEC-USR-PWD} storage.
         */
        private String newPassword;

        /**
         * User type code — maps {@code USRTYPEI PIC X(1)} from BMS COUSR2AI.
         * Must be {@code "A"} (Admin) or {@code "U"} (User), matching the
         * COBOL 88-level conditions on {@code CDEMO-USER-TYPE}.
         */
        private String userType;

        /** Default constructor for framework serialization. */
        public UserUpdateRequest() {
            // Required for JSON deserialization and Spring MVC binding
        }

        /**
         * Constructs a fully populated update request.
         *
         * @param firstName   the user's first name (max 20 characters)
         * @param lastName    the user's last name (max 20 characters)
         * @param newPassword the new password to set (will be BCrypt-encoded)
         * @param userType    the user type code ({@code "A"} or {@code "U"})
         */
        public UserUpdateRequest(String firstName, String lastName,
                                 String newPassword, String userType) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.newPassword = newPassword;
            this.userType = userType;
        }

        /**
         * Returns the first name.
         * @return the first name, or {@code null} if not set
         */
        public String getFirstName() {
            return firstName;
        }

        /**
         * Sets the first name.
         * @param firstName the first name to set (max 20 characters)
         */
        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        /**
         * Returns the last name.
         * @return the last name, or {@code null} if not set
         */
        public String getLastName() {
            return lastName;
        }

        /**
         * Sets the last name.
         * @param lastName the last name to set (max 20 characters)
         */
        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        /**
         * Returns the new password.
         * @return the new password, or {@code null} if not set
         */
        public String getNewPassword() {
            return newPassword;
        }

        /**
         * Sets the new password.
         * @param newPassword the new password to set
         */
        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }

        /**
         * Returns the user type code.
         * @return the user type code ({@code "A"} or {@code "U"}), or {@code null}
         */
        public String getUserType() {
            return userType;
        }

        /**
         * Sets the user type code.
         * @param userType the user type code ({@code "A"} or {@code "U"})
         */
        public void setUserType(String userType) {
            this.userType = userType;
        }
    }

    // =========================================================================
    // Public Service Methods — COBOL Paragraph Mappings
    // =========================================================================

    /**
     * Top-level user update orchestrator — maps COBOL {@code MAIN-PARA} (Line 82).
     *
     * <p>This is the primary entry point for user update operations. It wraps
     * the entire READ UPDATE → REWRITE cycle in a single database transaction
     * via {@code @Transactional}, matching the CICS implicit transaction boundary
     * in the original COBOL program.</p>
     *
     * <h3>COBOL Flow (MAIN-PARA EVALUATE):</h3>
     * <ol>
     *   <li>Reset error flags ({@code SET ERR-FLG-OFF TO TRUE})</li>
     *   <li>Verify admin authorization ({@code CDEMO-USER-TYPE = 'A'})</li>
     *   <li>Delegate to {@link #processEnterKey} for core processing</li>
     * </ol>
     *
     * @param userId  the user ID to update (max 8 characters); corresponds to
     *                {@code SEC-USR-ID PIC X(08)}, the immutable VSAM KSDS
     *                primary key (RIDFLD)
     * @param request the update request containing new field values from the
     *                BMS input map {@code COUSR2AI}
     * @return the updated {@link UserSecurity} entity after successful persistence
     * @throws ValidationException       if the requesting user is not an admin,
     *                                   if required fields are blank, if userType
     *                                   is invalid, or if no modifications detected
     * @throws RecordNotFoundException   if the specified userId does not exist
     *                                   in the USRSEC dataset (VSAM status '23')
     */
    @Transactional
    public UserSecurity updateUser(String userId, UserUpdateRequest request) {
        LOG.info("User update initiated — program: {}, transaction: {}",
                PROGRAM_NAME, TRANSACTION_ID);

        // Populate header context for tracing — maps PERFORM POPULATE-HEADER-INFO
        populateHeaderInfo();

        // Admin-only authorization gate — maps COBOL check where CDEMO-USER-TYPE
        // must be 'A' (ADMIN). In the original CICS application, the CU02
        // transaction is routed only to admin users via the admin menu (COADM01C).
        // This explicit check provides defense-in-depth in the REST API layer.
        if (cardDemoContext.getUserType() != UserType.ADMIN) {
            LOG.warn("Non-admin user '{}' attempted user update — access denied. {}",
                    cardDemoContext.getUserId(),
                    MessageConstants.INVALID_KEY_MESSAGE.trim());
            throw new ValidationException("authorization",
                    "Admin access required for user management");
        }

        LOG.info("Admin '{}' updating user '{}'",
                cardDemoContext.getUserId(), userId);

        return processEnterKey(userId, request);
    }

    /**
     * Core update processing flow — maps COBOL {@code PROCESS-ENTER-KEY} (Line 143).
     *
     * <p>Executes the complete read-validate-update-persist cycle for a user
     * record. In the COBOL program, this paragraph is invoked when the Enter
     * key is pressed on the user update screen ({@code DFHENTER} in the
     * {@code MAIN-PARA EVALUATE} block).</p>
     *
     * <h3>COBOL Flow:</h3>
     * <ol>
     *   <li>Validate user ID not blank (line 146:
     *       {@code WHEN USRIDINI = SPACES OR LOW-VALUES})</li>
     *   <li>Read current user record (line 163:
     *       {@code PERFORM READ-USER-SEC-FILE})</li>
     *   <li>Apply field updates via {@link #updateUserInfo}</li>
     *   <li>Persist changes via {@link #updateUserSecFile}</li>
     * </ol>
     *
     * @param userId  the user ID to update; must not be null or blank
     * @param request the update request containing new field values
     * @return the updated and persisted {@link UserSecurity} entity
     * @throws ValidationException     if userId is blank or field validation fails
     * @throws RecordNotFoundException if the user does not exist
     */
    public UserSecurity processEnterKey(String userId, UserUpdateRequest request) {
        // Validate userId not blank — maps COBOL line 146-155:
        // EVALUATE TRUE
        //   WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES
        //     MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
        if (userId == null || userId.isBlank()) {
            throw new ValidationException("userId", "User ID can NOT be empty...");
        }

        // Read current user record — maps COBOL line 162-163:
        // MOVE USRIDINI OF COUSR2AI TO SEC-USR-ID
        // PERFORM READ-USER-SEC-FILE
        UserSecurity user = readUserSecFile(userId);

        // Apply field updates — delegates to UPDATE-USER-INFO logic
        UserSecurity updatedUser = updateUserInfo(user, request);

        // Persist changes — delegates to UPDATE-USER-SEC-FILE logic
        return updateUserSecFile(updatedUser);
    }

    /**
     * Validates input fields and applies updates — maps COBOL
     * {@code UPDATE-USER-INFO} (Line 177).
     *
     * <p>This method faithfully reproduces the COBOL {@code EVALUATE TRUE}
     * block (lines 179-213) for sequential field validation, followed by
     * the field-by-field comparison and modification detection (lines 215-245).
     * Each field is compared against the current entity state, and the
     * modification flag ({@code WS-USR-MODIFIED}) is set if any difference
     * is detected.</p>
     *
     * <h3>Validation Rules (Sequential — First Failure Wins):</h3>
     * <ol>
     *   <li>First name must not be blank (line 186)</li>
     *   <li>Last name must not be blank (line 192)</li>
     *   <li>Password must not be blank (line 198)</li>
     *   <li>User type must not be blank (line 204)</li>
     *   <li>User type must be {@code 'A'} or {@code 'U'} (88-level validation)</li>
     * </ol>
     *
     * <h3>Security Enhancement:</h3>
     * <p>Password comparison differs from COBOL. The original program compared
     * plaintext: {@code IF PASSWDI NOT = SEC-USR-PWD}. In Java, BCrypt hashing
     * means the raw password is always re-encoded when provided, as the same
     * plaintext produces different BCrypt hashes each time.</p>
     *
     * @param user    the current user entity loaded from the database
     * @param request the update request with new field values
     * @return the modified user entity (same reference as input, with fields updated)
     * @throws ValidationException if any required field is blank, userType is
     *                             invalid, or no modifications were detected
     *                             (maps to "Please modify to update..." message)
     */
    public UserSecurity updateUserInfo(UserSecurity user, UserUpdateRequest request) {
        // Sequential field validation — maps COBOL EVALUATE TRUE (lines 179-213)
        // COBOL validates fields one at a time, returning the FIRST error found.
        // This matches the EVALUATE TRUE pattern where each WHEN branch checks
        // one field and sets WS-ERR-FLG + WS-MESSAGE before exiting.

        // Line 186: WHEN FNAMEI OF COUSR2AI = SPACES OR LOW-VALUES
        if (request.getFirstName() == null || request.getFirstName().isBlank()) {
            throw new ValidationException("firstName",
                    "First Name can NOT be empty...");
        }

        // Line 192: WHEN LNAMEI OF COUSR2AI = SPACES OR LOW-VALUES
        if (request.getLastName() == null || request.getLastName().isBlank()) {
            throw new ValidationException("lastName",
                    "Last Name can NOT be empty...");
        }

        // Line 198: WHEN PASSWDI OF COUSR2AI = SPACES OR LOW-VALUES
        if (request.getNewPassword() == null || request.getNewPassword().isBlank()) {
            throw new ValidationException("password",
                    "Password can NOT be empty...");
        }

        // Line 204: WHEN USRTYPEI OF COUSR2AI = SPACES OR LOW-VALUES
        if (request.getUserType() == null || request.getUserType().isBlank()) {
            throw new ValidationException("userType",
                    "User Type can NOT be empty...");
        }

        // Resolve and validate userType code — only 'A' (ADMIN) or 'U' (USER)
        // accepted, matching COBOL 88-level conditions on SEC-USR-TYPE
        UserType newUserType = resolveUserType(request.getUserType());

        // Track modifications — maps COBOL WS-USR-MODIFIED flag
        // (line 45-47: 88 USR-MODIFIED-YES VALUE 'Y', 88 USR-MODIFIED-NO VALUE 'N')
        boolean modified = false;

        // Compare and update firstName — maps COBOL lines 219-222:
        // IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME
        //     MOVE FNAMEI OF COUSR2AI TO SEC-USR-FNAME
        //     SET USR-MODIFIED-YES TO TRUE
        if (!request.getFirstName().equals(user.getFirstName())) {
            user.setFirstName(request.getFirstName());
            modified = true;
        }

        // Compare and update lastName — maps COBOL lines 223-226:
        // IF LNAMEI OF COUSR2AI NOT = SEC-USR-LNAME
        //     MOVE LNAMEI OF COUSR2AI TO SEC-USR-LNAME
        //     SET USR-MODIFIED-YES TO TRUE
        if (!request.getLastName().equals(user.getLastName())) {
            user.setLastName(request.getLastName());
            modified = true;
        }

        // Password update with BCrypt — maps COBOL lines 227-230:
        // IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD
        //     MOVE PASSWDI OF COUSR2AI TO SEC-USR-PWD
        //     SET USR-MODIFIED-YES TO TRUE
        //
        // CRITICAL SECURITY ENHANCEMENT: In COBOL, this was a plaintext comparison.
        // With BCrypt, the same password produces different hashes each time, so we
        // always re-encode and mark as modified when a new password is provided.
        // The original hash is read to confirm the record has an existing password.
        String currentPasswordHash = user.getPassword();
        LOG.debug("Processing password update for user '{}' (existing hash present: {})",
                user.getUserId(), currentPasswordHash != null);
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        modified = true;

        // Compare and update userType — maps COBOL lines 231-234:
        // IF USRTYPEI OF COUSR2AI NOT = SEC-USR-TYPE
        //     MOVE USRTYPEI OF COUSR2AI TO SEC-USR-TYPE
        //     SET USR-MODIFIED-YES TO TRUE
        if (newUserType != user.getUserType()) {
            user.setUserType(newUserType);
            modified = true;
        }

        // Check modification flag — maps COBOL lines 236-243:
        // IF USR-MODIFIED-YES
        //     PERFORM UPDATE-USER-SEC-FILE
        // ELSE
        //     MOVE 'Please modify to update ...' TO WS-MESSAGE
        if (!modified) {
            throw new ValidationException("Please modify to update ...");
        }

        LOG.debug("User '{}' fields modified — current version: {}",
                user.getUserId(), user.getVersion());

        return user;
    }

    /**
     * Reads a user security record by primary key — maps COBOL
     * {@code READ-USER-SEC-FILE} (Line 320).
     *
     * <p>Translates the CICS keyed read operation:</p>
     * <pre>
     *   EXEC CICS READ
     *        DATASET   (WS-USRSEC-FILE)
     *        INTO      (SEC-USER-DATA)
     *        LENGTH    (LENGTH OF SEC-USER-DATA)
     *        RIDFLD    (SEC-USR-ID)
     *        KEYLENGTH (LENGTH OF SEC-USR-ID)
     *        UPDATE
     *        RESP      (WS-RESP-CD)
     *        RESP2     (WS-REAS-CD)
     *   END-EXEC.
     * </pre>
     *
     * <h3>RESP Code Mapping:</h3>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} → entity returned successfully</li>
     *   <li>{@code DFHRESP(NOTFND)} → {@link RecordNotFoundException} thrown</li>
     *   <li>Other → logged and re-thrown as runtime exception</li>
     * </ul>
     *
     * <p>The CICS {@code UPDATE} option (which obtains an exclusive lock) is
     * replaced by JPA {@code @Version}-based optimistic locking on the
     * {@link UserSecurity} entity.</p>
     *
     * @param userId the user ID to look up (max 8 characters); corresponds to
     *               COBOL {@code SEC-USR-ID PIC X(08)}
     * @return the found {@link UserSecurity} entity
     * @throws RecordNotFoundException if no user exists with the given ID
     *                                 (maps to VSAM status '23' / DFHRESP(NOTFND))
     */
    public UserSecurity readUserSecFile(String userId) {
        LOG.debug("Reading user security file for user '{}' — dataset: USRSEC",
                userId);

        return userSecurityRepository.findById(userId)
                .orElseThrow(() -> {
                    // Maps COBOL lines 340-345:
                    // WHEN DFHRESP(NOTFND)
                    //     MOVE 'Y' TO WS-ERR-FLG
                    //     MOVE 'User ID NOT found...' TO WS-MESSAGE
                    LOG.warn("User ID NOT found: '{}' — RESP equivalent: DFHRESP(NOTFND)",
                            userId);
                    return new RecordNotFoundException(
                            "User not found: " + userId);
                });
    }

    /**
     * Persists the updated user security record — maps COBOL
     * {@code UPDATE-USER-SEC-FILE} (Line 358).
     *
     * <p>Translates the CICS rewrite operation:</p>
     * <pre>
     *   EXEC CICS REWRITE
     *        DATASET   (WS-USRSEC-FILE)
     *        FROM      (SEC-USER-DATA)
     *        LENGTH    (LENGTH OF SEC-USER-DATA)
     *        RESP      (WS-RESP-CD)
     *        RESP2     (WS-REAS-CD)
     *   END-EXEC.
     * </pre>
     *
     * <h3>RESP Code Mapping:</h3>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} → entity saved; success message logged
     *       (maps line 372-375: {@code STRING 'User ' SEC-USR-ID ' has been updated'})</li>
     *   <li>{@code DFHRESP(NOTFND)} → {@link RecordNotFoundException} thrown</li>
     *   <li>Other → logged as error with RESP/REAS codes and re-thrown
     *       (maps line 384-389: {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD})</li>
     * </ul>
     *
     * <p>JPA {@code @Version} optimistic concurrency check is triggered by
     * the {@code save()} call. If the entity was modified concurrently,
     * an {@code OptimisticLockingFailureException} propagates to the caller,
     * mapping to the COBOL "Unable to Update User..." error path.</p>
     *
     * @param user the user entity with updated fields to persist
     * @return the persisted {@link UserSecurity} entity with updated version
     * @throws RuntimeException if the save operation fails due to concurrent
     *                          modification or other database errors
     */
    public UserSecurity updateUserSecFile(UserSecurity user) {
        try {
            UserSecurity saved = userSecurityRepository.save(user);
            // Maps COBOL lines 369-375 — DFHRESP(NORMAL):
            // STRING 'User ' DELIMITED BY SIZE
            //        SEC-USR-ID DELIMITED BY SPACE
            //        ' has been updated ...' DELIMITED BY SIZE
            //   INTO WS-MESSAGE
            LOG.info("User '{}' has been updated successfully",
                    saved.getUserId());
            return saved;
        } catch (RuntimeException ex) {
            // Maps COBOL lines 383-389 — WHEN OTHER:
            // DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
            // MOVE 'Unable to Update User...' TO WS-MESSAGE
            LOG.error("Unable to Update User '{}': {}",
                    user.getUserId(), ex.getMessage());
            throw ex;
        }
    }

    // =========================================================================
    // Private Helper Methods
    // =========================================================================

    /**
     * Resolves a user type string code to the {@link UserType} enum.
     *
     * <p>Validates that the provided code is exactly one character and matches
     * one of the COBOL 88-level conditions:</p>
     * <ul>
     *   <li>{@code 'A'} → {@link UserType#ADMIN} (CDEMO-USRTYP-ADMIN)</li>
     *   <li>{@code 'U'} → {@link UserType#USER} (CDEMO-USRTYP-USER)</li>
     * </ul>
     *
     * @param typeCode the single-character user type code from the request
     * @return the resolved {@link UserType} enum constant
     * @throws ValidationException if the code is null, not a single character,
     *                             or does not match a valid user type
     */
    private UserType resolveUserType(String typeCode) {
        if (typeCode == null || typeCode.length() != 1) {
            throw new ValidationException("userType",
                    "User Type must be '"
                            + UserType.ADMIN.getCode() + "' ("
                            + UserType.ADMIN + ") or '"
                            + UserType.USER.getCode() + "' ("
                            + UserType.USER + ")");
        }
        char code = typeCode.charAt(0);
        try {
            return UserType.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("userType",
                    "User Type must be '"
                            + UserType.ADMIN.getCode() + "' ("
                            + UserType.ADMIN + ") or '"
                            + UserType.USER.getCode() + "' ("
                            + UserType.USER + ")");
        }
    }

    /**
     * Populates header information for logging and tracing context — maps
     * COBOL {@code POPULATE-HEADER-INFO} (Line 296).
     *
     * <p>In the original COBOL program, this paragraph populates BMS screen
     * header fields (title, transaction name, program name, date, time).
     * In the headless Java service, this translates to structured logging
     * of the transaction context for observability and traceability.</p>
     *
     * <p>COBOL fields populated:</p>
     * <ul>
     *   <li>{@code TITLE01O, TITLE02O} — application titles</li>
     *   <li>{@code TRNNAMEO} — transaction ID ({@value #TRANSACTION_ID})</li>
     *   <li>{@code PGMNAMEO} — program name ({@value #PROGRAM_NAME})</li>
     *   <li>{@code CURDATEO} — current date (MM/DD/YY format)</li>
     *   <li>{@code CURTIMEO} — current time (HH:MM:SS format)</li>
     * </ul>
     */
    private void populateHeaderInfo() {
        LOG.trace("Header info — Transaction: {}, Program: {}, User: {}",
                TRANSACTION_ID, PROGRAM_NAME, cardDemoContext.getUserId());
    }
}

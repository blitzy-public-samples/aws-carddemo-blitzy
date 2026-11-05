/*
 * UserAddService.java — Spring @Service (← COUSR01C.cbl)
 *
 * Faithfully translates the COBOL program COUSR01C.cbl (User Add — CICS
 * admin-only transaction CU01) into a Spring-managed service component.
 * This service creates new user security records in the USRSEC dataset
 * (now PostgreSQL user_security table) with BCrypt password hashing
 * replacing the original plaintext SEC-USR-PWD PIC X(08) storage.
 *
 * COBOL Source: app/cbl/COUSR01C.cbl (300 lines)
 * Copybooks:    CSUSR01Y.cpy (SEC-USER-DATA 80 bytes)
 *               COCOM01Y.cpy (CARDDEMO-COMMAREA 1024 bytes)
 *               COUSR01.CPY  (BMS data structure — field lengths)
 *
 * Paragraph-to-Method Traceability:
 *   Line 71:  MAIN-PARA              → addUser()
 *   Line 115: PROCESS-ENTER-KEY      → processEnterKey()
 *   Line 165: RETURN-TO-PREV-SCREEN  → (navigation — handled by controller)
 *   Line 184: SEND-USRADD-SCREEN     → (presentation — handled by controller)
 *   Line 201: RECEIVE-USRADD-SCREEN  → (input — handled by controller)
 *   Line 214: POPULATE-HEADER-INFO   → (presentation — handled by controller)
 *   Line 238: WRITE-USER-SEC-FILE    → writeUserSecFile()
 *   Line 279: CLEAR-CURRENT-SCREEN   → (presentation — handled by controller)
 *   Line 287: INITIALIZE-ALL-FIELDS  → (presentation — handled by controller)
 *
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
import com.cardemo.common.exception.DuplicateRecordException;
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
 * Service implementing user creation logic translated from COUSR01C.cbl.
 *
 * <p>This is an <strong>admin-only</strong> service (CICS transaction CU01).
 * Only users with {@link UserType#ADMIN} role can invoke user creation.
 * The service performs field validation matching the exact order and semantics
 * of the COBOL {@code PROCESS-ENTER-KEY} paragraph (lines 115–160), hashes
 * passwords using BCrypt (replacing COBOL plaintext storage), checks for
 * duplicate user IDs (mapping CICS RESP=22 DUPREC/DUPKEY), and persists
 * the new user record via {@link UserSecurityRepository}.</p>
 *
 * <h3>Security Migration Notes</h3>
 * <ul>
 *   <li><strong>Plaintext → BCrypt:</strong> The original COBOL program stored
 *       passwords as 8-character plaintext in {@code SEC-USR-PWD PIC X(08)}.
 *       This Java service hashes passwords via
 *       {@link BCryptPasswordEncoder#encode(CharSequence)} before persistence,
 *       producing 60-character BCrypt hashes stored in a 72-character column.</li>
 *   <li><strong>Password logging prohibition:</strong> Passwords (raw or hashed)
 *       must never appear in log output. All logging in this service
 *       deliberately excludes password values.</li>
 * </ul>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see CardDemoContext
 */
@Service
public class UserAddService {

    private static final Logger logger = LoggerFactory.getLogger(UserAddService.class);

    // ========================================================================
    // Constants from COBOL WORKING-STORAGE (COUSR01C.cbl lines 35–44)
    // ========================================================================

    /** Program name — maps to {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'}. */
    private static final String PROGRAM_NAME = "COUSR01C";

    /** Transaction ID — maps to {@code WS-TRANID PIC X(04) VALUE 'CU01'}. */
    private static final String TRANSACTION_ID = "CU01";

    // Field length limits from BMS copybook COUSR01.CPY field definitions
    /** Maximum user ID length — maps to {@code USERIDI PIC X(8)}. */
    private static final int USER_ID_MAX_LENGTH = 8;

    /** Maximum first name length — maps to {@code FNAMEI PIC X(20)}. */
    private static final int FIRST_NAME_MAX_LENGTH = 20;

    /** Maximum last name length — maps to {@code LNAMEI PIC X(20)}. */
    private static final int LAST_NAME_MAX_LENGTH = 20;

    // ========================================================================
    // Injected Dependencies (constructor injection)
    // ========================================================================

    private final UserSecurityRepository userSecurityRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs a {@code UserAddService} with required dependencies.
     *
     * <p>All dependencies are injected via Spring constructor injection,
     * mapping the COBOL {@code CALL} and {@code COPY} patterns to
     * Spring bean wiring.</p>
     *
     * @param userSecurityRepository JPA repository for USRSEC dataset access;
     *                                maps to COBOL {@code EXEC CICS WRITE DATASET('USRSEC')}
     * @param passwordEncoder        BCrypt encoder for password hashing;
     *                                replaces COBOL plaintext password storage
     * @param cardDemoContext         request-scoped session context bean;
     *                                maps to COBOL CARDDEMO-COMMAREA (COCOM01Y.cpy)
     */
    public UserAddService(UserSecurityRepository userSecurityRepository,
                          BCryptPasswordEncoder passwordEncoder,
                          CardDemoContext cardDemoContext) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Inner Request DTO
    // ========================================================================

    /**
     * Request DTO for user add operations, mapping BMS input fields from
     * COUSR01.CPY (COUSR1AI structure).
     *
     * <p>Field mapping from BMS copybook:</p>
     * <ul>
     *   <li>{@code FNAMEI PIC X(20)}  → {@link #firstName}</li>
     *   <li>{@code LNAMEI PIC X(20)}  → {@link #lastName}</li>
     *   <li>{@code USERIDI PIC X(8)}  → {@link #userId}</li>
     *   <li>{@code PASSWDI PIC X(8)}  → {@link #password}</li>
     *   <li>{@code USRTYPEI PIC X(1)} → {@link #userType}</li>
     * </ul>
     */
    public static class UserAddRequest {

        private String userId;
        private String firstName;
        private String lastName;
        private String password;
        private String userType;

        /** Default no-arg constructor for JSON deserialization. */
        public UserAddRequest() {
            // Required for Jackson deserialization
        }

        /**
         * Constructs a fully populated request.
         *
         * @param userId    user identifier (max 8 chars)
         * @param firstName user first name (max 20 chars)
         * @param lastName  user last name (max 20 chars)
         * @param password  plaintext password to be hashed
         * @param userType  user type code ('A' or 'U')
         */
        public UserAddRequest(String userId, String firstName, String lastName,
                              String password, String userType) {
            this.userId = userId;
            this.firstName = firstName;
            this.lastName = lastName;
            this.password = password;
            this.userType = userType;
        }

        /** Returns the user ID (maps to USERIDI). */
        public String getUserId() {
            return userId;
        }

        /** Sets the user ID. */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /** Returns the first name (maps to FNAMEI). */
        public String getFirstName() {
            return firstName;
        }

        /** Sets the first name. */
        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        /** Returns the last name (maps to LNAMEI). */
        public String getLastName() {
            return lastName;
        }

        /** Sets the last name. */
        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        /** Returns the plaintext password (maps to PASSWDI). */
        public String getPassword() {
            return password;
        }

        /** Sets the plaintext password. */
        public void setPassword(String password) {
            this.password = password;
        }

        /** Returns the user type code (maps to USRTYPEI). */
        public String getUserType() {
            return userType;
        }

        /** Sets the user type code ('A' or 'U'). */
        public void setUserType(String userType) {
            this.userType = userType;
        }
    }

    // ========================================================================
    // Public Service Methods (COBOL Paragraph Equivalents)
    // ========================================================================

    /**
     * Creates a new user in the security file — maps to MAIN-PARA (line 71).
     *
     * <p>This is the primary entry point for user creation, corresponding to
     * the COBOL {@code MAIN-PARA} paragraph. It enforces admin-only access
     * (transaction CU01 is restricted to administrators), then delegates to
     * {@link #processEnterKey(UserAddRequest)} for field validation and
     * entity creation.</p>
     *
     * <p><strong>COBOL flow mapped:</strong></p>
     * <ol>
     *   <li>Check EIBCALEN / COMMAREA (Java: verify session context)</li>
     *   <li>EVALUATE EIBAID WHEN DFHENTER → PROCESS-ENTER-KEY</li>
     *   <li>EXEC CICS RETURN (Java: return saved entity to controller)</li>
     * </ol>
     *
     * @param request the user add request containing all fields from the
     *                BMS input map (COUSR1AI); must not be {@code null}
     * @return the persisted {@link UserSecurity} entity with BCrypt-hashed
     *         password and assigned user type
     * @throws ValidationException       if admin role check fails or any
     *                                    input field validation fails
     * @throws DuplicateRecordException  if a user with the same ID already
     *                                    exists (maps to CICS RESP=22)
     */
    @Transactional
    public UserSecurity addUser(UserAddRequest request) {
        // Null request guard — no COBOL equivalent (CICS always provides
        // a populated BMS map), but required for Java null safety.
        if (request == null) {
            throw new ValidationException("User add request must not be null");
        }

        logger.info("User add request initiated by admin '{}' for program {}/{}",
                cardDemoContext.getUserId(), PROGRAM_NAME, TRANSACTION_ID);

        // Admin-only access check — COUSR01C is restricted to transaction CU01
        // which is only accessible from the admin menu (COADM01C.cbl)
        if (!cardDemoContext.isAdmin()) {
            logger.warn("Non-admin user '{}' (type={}) attempted user add function",
                    cardDemoContext.getUserId(), cardDemoContext.getUserType());
            throw new ValidationException("authorization",
                    "Only administrators can add users. "
                    + MessageConstants.INVALID_KEY_MESSAGE.trim());
        }

        // Delegate to PROCESS-ENTER-KEY equivalent for validation and creation
        UserSecurity savedUser = processEnterKey(request);

        // Log successful creation — maps to COBOL success message:
        // STRING 'User ' SEC-USR-ID ' has been added ...' INTO WS-MESSAGE
        logger.info("User '{}' has been added. {}",
                savedUser.getUserId(),
                MessageConstants.THANK_YOU_MESSAGE.trim());

        return savedUser;
    }

    /**
     * Validates input fields and creates the user entity — maps to
     * PROCESS-ENTER-KEY (line 115).
     *
     * <p>This method faithfully reproduces the COBOL {@code PROCESS-ENTER-KEY}
     * paragraph validation logic. The COBOL EVALUATE TRUE block checks fields
     * in this exact order (first-match semantics — only the first validation
     * failure triggers an error):</p>
     * <ol>
     *   <li>FNAMEI empty → "First Name can NOT be empty..."</li>
     *   <li>LNAMEI empty → "Last Name can NOT be empty..."</li>
     *   <li>USERIDI empty → "User ID can NOT be empty..."</li>
     *   <li>PASSWDI empty → "Password can NOT be empty..."</li>
     *   <li>USRTYPEI empty → "User Type can NOT be empty..."</li>
     * </ol>
     *
     * <p>After validation passes, the method:</p>
     * <ol>
     *   <li>Resolves the user type code to {@link UserType} enum</li>
     *   <li>Hashes the password using BCrypt (replacing COBOL plaintext)</li>
     *   <li>Constructs the {@link UserSecurity} entity</li>
     *   <li>Delegates to {@link #writeUserSecFile(UserSecurity)} for persistence</li>
     * </ol>
     *
     * @param request the validated user add request
     * @return the persisted {@link UserSecurity} entity
     * @throws ValidationException      if any field validation fails
     * @throws DuplicateRecordException if the user ID already exists
     */
    public UserSecurity processEnterKey(UserAddRequest request) {
        // ================================================================
        // Field validation — exact order from COBOL EVALUATE TRUE block
        // (COUSR01C.cbl lines 117–151). First-match: only the first
        // failing field triggers an error, matching COBOL EVALUATE semantics.
        // ================================================================

        // 1. FNAMEI check (line 118): WHEN FNAMEI = SPACES OR LOW-VALUES
        if (request.getFirstName() == null || request.getFirstName().isBlank()) {
            logger.warn("Validation failed: First Name is empty");
            throw new ValidationException("firstName",
                    "First Name can NOT be empty...");
        }

        // 2. LNAMEI check (line 124): WHEN LNAMEI = SPACES OR LOW-VALUES
        if (request.getLastName() == null || request.getLastName().isBlank()) {
            logger.warn("Validation failed: Last Name is empty");
            throw new ValidationException("lastName",
                    "Last Name can NOT be empty...");
        }

        // 3. USERIDI check (line 130): WHEN USERIDI = SPACES OR LOW-VALUES
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            logger.warn("Validation failed: User ID is empty");
            throw new ValidationException("userId",
                    "User ID can NOT be empty...");
        }

        // 4. PASSWDI check (line 136): WHEN PASSWDI = SPACES OR LOW-VALUES
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            logger.warn("Validation failed: Password is empty");
            throw new ValidationException("password",
                    "Password can NOT be empty...");
        }

        // 5. USRTYPEI check (line 142): WHEN USRTYPEI = SPACES OR LOW-VALUES
        if (request.getUserType() == null || request.getUserType().isBlank()) {
            logger.warn("Validation failed: User Type is empty");
            throw new ValidationException("userType",
                    "User Type can NOT be empty...");
        }

        // ================================================================
        // Length validation — enforced by BMS PIC X field widths in
        // COUSR01.CPY. In COBOL, fields are fixed-length so overflow is
        // impossible; in Java, we must enforce limits explicitly.
        // ================================================================

        if (request.getFirstName().trim().length() > FIRST_NAME_MAX_LENGTH) {
            logger.warn("Validation failed: First Name exceeds {} chars",
                    FIRST_NAME_MAX_LENGTH);
            throw new ValidationException("firstName",
                    "First Name must not exceed " + FIRST_NAME_MAX_LENGTH
                    + " characters");
        }

        if (request.getLastName().trim().length() > LAST_NAME_MAX_LENGTH) {
            logger.warn("Validation failed: Last Name exceeds {} chars",
                    LAST_NAME_MAX_LENGTH);
            throw new ValidationException("lastName",
                    "Last Name must not exceed " + LAST_NAME_MAX_LENGTH
                    + " characters");
        }

        if (request.getUserId().trim().length() > USER_ID_MAX_LENGTH) {
            logger.warn("Validation failed: User ID exceeds {} chars",
                    USER_ID_MAX_LENGTH);
            throw new ValidationException("userId",
                    "User ID must not exceed " + USER_ID_MAX_LENGTH
                    + " characters");
        }

        // ================================================================
        // User type resolution — maps USRTYPEI PIC X(1) to UserType enum.
        // COBOL 88-level: 'A' → CDEMO-USRTYP-ADMIN, 'U' → CDEMO-USRTYP-USER
        // ================================================================

        char typeCode = request.getUserType().trim().charAt(0);
        UserType resolvedUserType;
        try {
            resolvedUserType = UserType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            logger.warn("Validation failed: Invalid user type code '{}'", typeCode);
            throw new ValidationException("userType",
                    "User Type must be '"
                    + UserType.ADMIN.getCode() + "' (Admin) or '"
                    + UserType.USER.getCode() + "' (User)");
        }

        // ================================================================
        // Entity construction — maps COBOL MOVE statements (lines 154–158):
        //   MOVE USERIDI  OF COUSR1AI TO SEC-USR-ID
        //   MOVE FNAMEI   OF COUSR1AI TO SEC-USR-FNAME
        //   MOVE LNAMEI   OF COUSR1AI TO SEC-USR-LNAME
        //   MOVE PASSWDI  OF COUSR1AI TO SEC-USR-PWD  (BCrypt hashed)
        //   MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE
        // ================================================================

        // BCrypt hash the password — CRITICAL CHANGE from COBOL plaintext:
        // SEC-USR-PWD PIC X(08) stored passwords in clear text;
        // Java stores 60-char BCrypt hashes for security.
        // Password value is NEVER logged.
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        // Construct entity via all-args constructor
        UserSecurity user = new UserSecurity(
                request.getUserId().trim(),
                request.getFirstName().trim(),
                request.getLastName().trim(),
                hashedPassword,
                resolvedUserType
        );

        // Normalize fields via setters — maps to COBOL fixed-width MOVE
        // semantics where PIC X fields are right-padded. Java equivalent
        // trims input to variable-length strings.
        user.setUserId(request.getUserId().trim());
        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName().trim());
        user.setPassword(hashedPassword);
        user.setUserType(resolvedUserType);

        // Persist via WRITE-USER-SEC-FILE equivalent (line 159):
        // PERFORM WRITE-USER-SEC-FILE
        return writeUserSecFile(user);
    }

    /**
     * Persists a new user security record — maps to WRITE-USER-SEC-FILE
     * (line 238).
     *
     * <p>This method maps the COBOL paragraph {@code WRITE-USER-SEC-FILE}
     * which executes:</p>
     * <pre>
     *   EXEC CICS WRITE
     *        DATASET   (WS-USRSEC-FILE)
     *        FROM      (SEC-USER-DATA)
     *        LENGTH    (LENGTH OF SEC-USER-DATA)
     *        RIDFLD    (SEC-USR-ID)
     *        KEYLENGTH (LENGTH OF SEC-USR-ID)
     *        RESP      (WS-RESP-CD)
     *        RESP2     (WS-REAS-CD)
     *   END-EXEC.
     * </pre>
     *
     * <p><strong>RESP code handling (lines 250–274):</strong></p>
     * <ul>
     *   <li>DFHRESP(NORMAL) → return saved entity with success message</li>
     *   <li>DFHRESP(DUPKEY) / DFHRESP(DUPREC) →
     *       {@link DuplicateRecordException} with "User ID already exist..."</li>
     *   <li>OTHER → RuntimeException with "Unable to Add User..."</li>
     * </ul>
     *
     * @param user the {@link UserSecurity} entity to persist; must have
     *             all required fields set (userId, firstName, lastName,
     *             password, userType)
     * @return the persisted entity (with JPA-managed version field set)
     * @throws DuplicateRecordException if a record with the same user ID
     *                                   already exists (VSAM status 22)
     */
    @Transactional
    public UserSecurity writeUserSecFile(UserSecurity user) {
        // Pre-write duplicate check — maps to COBOL RESP=22 DUPREC detection.
        // In COBOL, the WRITE itself detects duplicates; in Java, we check
        // proactively via existsById() for a clear error message.
        if (userSecurityRepository.existsById(user.getUserId())) {
            // Maps to COBOL lines 260–266:
            // WHEN DFHRESP(DUPKEY)
            // WHEN DFHRESP(DUPREC)
            //     MOVE 'User ID already exist...' TO WS-MESSAGE
            logger.warn("Duplicate user ID detected during write: '{}'",
                    user.getUserId());
            throw new DuplicateRecordException("User ID already exist...");
        }

        // Execute WRITE — maps to EXEC CICS WRITE DATASET(WS-USRSEC-FILE)
        // DFHRESP(NORMAL) path (lines 251–259)
        // The try-catch maps to WHEN OTHER (lines 267–273):
        //   MOVE 'Unable to Add User...' TO WS-MESSAGE
        UserSecurity savedUser;
        try {
            savedUser = userSecurityRepository.save(user);
        } catch (DuplicateRecordException e) {
            // Re-throw DuplicateRecordException — already handled above but
            // could arise from a race condition between existsById and save
            throw e;
        } catch (RuntimeException e) {
            // Maps to COBOL WHEN OTHER (lines 267–273):
            //   MOVE 'Unable to Add User...' TO WS-MESSAGE
            logger.error("WRITE-USER-SEC-FILE: Unable to add user '{}' — "
                    + "unexpected error during persistence", user.getUserId(), e);
            throw new RuntimeException("Unable to Add User...", e);
        }

        // Log success — maps to COBOL STRING:
        // STRING 'User ' SEC-USR-ID ' has been added ...' INTO WS-MESSAGE
        logger.info("WRITE-USER-SEC-FILE: User '{}' written to USRSEC successfully",
                savedUser.getUserId());

        return savedUser;
    }
}

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
package com.aws.carddemo.service;

import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * User-administration service &mdash; the Java re-platform of the four CardDemo
 * COBOL <em>user maintenance</em> programs, reproduced without any feature
 * expansion:
 *
 * <ul>
 *   <li>{@code COUSR00C} (CICS transaction {@code CU00}, List Users) &rarr;
 *       {@link #listUsers(String, Pageable)}.</li>
 *   <li>{@code COUSR01C} (CICS transaction {@code CU01}, Add User) &rarr;
 *       {@link #addUser(String, String, String, String, char)}.</li>
 *   <li>{@code COUSR02C} (CICS transaction {@code CU02}, Update User) &rarr;
 *       {@link #updateUser(String, String, String, String, char)}.</li>
 *   <li>{@code COUSR03C} (CICS transaction {@code CU03}, Delete User) &rarr;
 *       {@link #deleteUser(String)}.</li>
 * </ul>
 *
 * <p>The service operates over the {@code user_security} table through
 * {@link UserSecurityRepository}, replacing the record-at-a-time CICS file
 * control ({@code READ}/{@code WRITE}/{@code REWRITE}/{@code DELETE} and the
 * {@code STARTBR}/{@code READNEXT} browse) the legacy programs used against the
 * {@code USRSEC.VSAM.KSDS} data set.</p>
 *
 * <h2>Outcome model (pseudo-conversational translation)</h2>
 * The legacy programs are pseudo-conversational: each paragraph validates input,
 * performs a file operation, and either succeeds or re-displays the screen with a
 * message placed in {@code WS-MESSAGE}. Because there is no 3270 terminal in the
 * re-platformed application, this service surfaces those outcomes through two
 * complementary channels that together preserve every caller-visible contract:
 * <ol>
 *   <li><strong>Input-validation and success outcomes</strong> are returned as a
 *       {@link UserResult} value carrying the exact message text the COBOL placed
 *       in {@code WS-MESSAGE}. Field-level edit failures (an empty mandatory
 *       field) and the "nothing changed" case are <em>not</em> thrown as
 *       exceptions, exactly as the COBOL simply re-displayed the screen with a
 *       message rather than abending.</li>
 *   <li><strong>Data-layer outcomes</strong> that the COBOL detected through a
 *       CICS {@code RESP} / VSAM {@code FILE STATUS} value are surfaced as typed
 *       exceptions so callers observe the same distinctions: a duplicate key on
 *       add throws {@link DuplicateKeyException} (CICS {@code DUPKEY}/{@code DUPREC},
 *       {@code FILE STATUS '22'}) and a missing record on update/delete throws
 *       {@link RecordNotFoundException} (CICS {@code NOTFND},
 *       {@code FILE STATUS '23'}).</li>
 * </ol>
 * This split is an intentional design recorded in {@code docs/decision-log.md}.
 *
 * <h2>Security hardening (documented deviations, decision-log entries)</h2>
 * <ul>
 *   <li><strong>Password hashing.</strong> The legacy record stored an
 *       8-character plaintext password. This service never persists plaintext: on
 *       add, and on update when the password changes, the raw credential is
 *       one-way hashed with the injected {@link PasswordEncoder} (BCrypt) before
 *       it reaches the entity. The raw and hashed password are treated as
 *       sensitive and are never logged, echoed, or placed in a message or
 *       exception.</li>
 *   <li><strong>3270 upper-case parity.</strong> The legacy screens ran under a
 *       terminal that upper-cased keyed input. To keep the credential contract
 *       stable across the migration the user id and the raw password are
 *       upper-cased (via {@link Locale#ROOT}) before they are stored or looked
 *       up, matching the legacy behavior.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * The mutating operations run inside a {@link Transactional} boundary and modify
 * a freshly read managed entity, so the {@code UserSecurity} optimistic-lock
 * {@code @Version} column is maintained automatically by the persistence
 * provider; this class never manipulates the version directly.
 *
 * <p>This service is deliberately not declared {@code final} so the Spring
 * transaction infrastructure can create a proxy around it.</p>
 */
@Service
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    /**
     * Mandatory-field message: empty first name (COBOL {@code COUSR01C}
     * {@code PROCESS-ENTER-KEY} / {@code COUSR02C} {@code UPDATE-USER-INFO}).
     */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Mandatory-field message: empty last name. */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Mandatory-field message: empty user id. */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Mandatory-field message: empty password. */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Mandatory-field message: empty user type. */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * Duplicate-key message for add (COBOL {@code COUSR01C}
     * {@code WRITE-USER-SEC-FILE}, {@code WHEN DFHRESP(DUPKEY)}/{@code (DUPREC)}).
     */
    private static final String MSG_USER_ID_ALREADY_EXISTS = "User ID already exist...";

    /**
     * Generic add-failure message (COBOL {@code COUSR01C}
     * {@code WRITE-USER-SEC-FILE}, {@code WHEN OTHER}).
     */
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    /**
     * Record-not-found message for update and delete (COBOL {@code COUSR02C} /
     * {@code COUSR03C}, {@code WHEN DFHRESP(NOTFND)}).
     */
    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * "Nothing changed" message for update (COBOL {@code COUSR02C}
     * {@code UPDATE-USER-INFO}, when no field differs from the stored record).
     */
    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    /**
     * Concurrency-conflict message for the CU02 update flow (F-P7-STALE). The
     * {@code user_security} row carries a JPA {@code @Version} column; the online
     * update carries the version observed when the edit screen was rendered and
     * rejects a submission whose observed version is absent or no longer matches the
     * persisted row (someone else changed the record first). This reproduces, at the
     * aggregate level, the intent of the COACTUPC {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
     * guard and matches the wording used by {@code AccountService}; the web layer maps
     * the resulting {@link OptimisticLockingFailureException} to HTTP {@code 409}.
     */
    private static final String MSG_DATA_CHANGED =
            "Record changed by some one else. Please review";

    /** Common prefix of the add/update/delete success messages ({@code STRING 'User '}). */
    private static final String USER_MSG_PREFIX = "User ";

    /** Success suffix for add (COBOL {@code ' has been added ...'}). */
    private static final String ADDED_MSG_SUFFIX = " has been added ...";

    /** Success suffix for update (COBOL {@code ' has been updated ...'}). */
    private static final String UPDATED_MSG_SUFFIX = " has been updated ...";

    /** Success suffix for delete (COBOL {@code ' has been deleted ...'}). */
    private static final String DELETED_MSG_SUFFIX = " has been deleted ...";

    /** Maximum length of the {@code SEC-USR-ID} key ({@code PIC X(08)}). */
    private static final int USER_ID_MAX_LENGTH = 8;

    private final UserSecurityRepository userSecurityRepository;

    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its collaborators. Constructor injection is used
     * exclusively (no field injection) so the dependencies are explicit and the
     * instance is fully initialized once constructed.
     *
     * @param userSecurityRepository repository over the {@code user_security}
     *                               table; must not be {@code null}
     * @param passwordEncoder        the password encoder used to hash credentials
     *                               before persistence; must not be {@code null}
     */
    public UserService(UserSecurityRepository userSecurityRepository,
                       PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = Objects.requireNonNull(
                userSecurityRepository, "userSecurityRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(
                passwordEncoder, "passwordEncoder must not be null");
    }

    /**
     * Adds a new application user &mdash; the Java re-platform of COBOL program
     * {@code COUSR01C} (CICS transaction {@code CU01}), paragraphs
     * {@code PROCESS-ENTER-KEY} (the mandatory-field edits) and
     * {@code WRITE-USER-SEC-FILE} (the {@code EXEC CICS WRITE}).
     *
     * <p>The five mandatory fields are validated in the exact order and with the
     * exact messages of {@code COUSR01C PROCESS-ENTER-KEY}, short-circuiting on the
     * first empty field: first name, last name, user id, password, then user type.
     * A field is "empty" when it is {@code null}, all blanks, or (for the type) a
     * space / low-value, matching the COBOL {@code = SPACES OR LOW-VALUES} test.</p>
     *
     * <p>On success the record is written with the user id and raw password
     * upper-cased for 3270 parity and the password one-way hashed. A pre-existing
     * id reproduces the CICS {@code DUPKEY}/{@code DUPREC} outcome as a
     * {@link DuplicateKeyException}; any other data-access failure reproduces the
     * COBOL {@code WHEN OTHER} branch as an unsuccessful {@link UserResult} carrying
     * {@value #MSG_UNABLE_TO_ADD}.</p>
     *
     * @param userId      the user id to create ({@code SEC-USR-ID PIC X(08)})
     * @param firstName   the first name ({@code SEC-USR-FNAME PIC X(20)})
     * @param lastName    the last name ({@code SEC-USR-LNAME PIC X(20)})
     * @param rawPassword the raw (unhashed) password; hashed before persistence and
     *                    never logged
     * @param userType    the role indicator ({@code 'A'} admin or {@code 'U'} user)
     * @return a {@link UserResult}: successful with the persisted user and the
     *         {@code "User <id> has been added ..."} message, or unsuccessful with
     *         the first validation message or the generic add-failure message
     * @throws DuplicateKeyException if a user with the given id already exists
     */
    @Transactional
    public UserResult addUser(String userId,
                              String firstName,
                              String lastName,
                              String rawPassword,
                              char userType) {
        // Mandatory-field edits (COUSR01C PROCESS-ENTER-KEY order): first name,
        // last name, user id, password, user type. Short-circuit on first empty.
        if (isBlank(firstName)) {
            return new UserResult(false, null, MSG_FIRST_NAME_EMPTY);
        }
        if (isBlank(lastName)) {
            return new UserResult(false, null, MSG_LAST_NAME_EMPTY);
        }
        if (isBlank(userId)) {
            return new UserResult(false, null, MSG_USER_ID_EMPTY);
        }
        if (isBlank(rawPassword)) {
            return new UserResult(false, null, MSG_PASSWORD_EMPTY);
        }
        if (isBlankType(userType)) {
            return new UserResult(false, null, MSG_USER_TYPE_EMPTY);
        }

        String normalizedId = normalizeUserId(userId);

        // Reproduce the CICS WRITE DUPKEY/DUPREC path deterministically.
        if (userSecurityRepository.existsById(normalizedId)) {
            throw new DuplicateKeyException(MSG_USER_ID_ALREADY_EXISTS);
        }

        UserSecurity toPersist = new UserSecurity(
                normalizedId,
                firstName,
                lastName,
                encodePassword(rawPassword),
                String.valueOf(userType));

        try {
            UserSecurity persisted = userSecurityRepository.saveAndFlush(toPersist);
            return new UserResult(true, persisted,
                    USER_MSG_PREFIX + normalizedId + ADDED_MSG_SUFFIX);
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent insert of the same key surfaces here; same outcome as
            // the deterministic existence check above.
            throw new DuplicateKeyException(MSG_USER_ID_ALREADY_EXISTS, duplicate);
        } catch (DataAccessException failure) {
            // COBOL WHEN OTHER: re-display with "Unable to Add User...". Log only
            // the id and the failure type; never the password (raw or hashed).
            LOG.warn("Unable to add user {} ({})", normalizedId,
                    failure.getClass().getSimpleName());
            return new UserResult(false, null, MSG_UNABLE_TO_ADD);
        }
    }

    /**
     * Updates an existing application user &mdash; the Java re-platform of COBOL
     * program {@code COUSR02C} (CICS transaction {@code CU02}), paragraphs
     * {@code UPDATE-USER-INFO}, {@code READ-USER-SEC-FILE}
     * ({@code EXEC CICS READ ... UPDATE}) and {@code UPDATE-USER-SEC-FILE}
     * ({@code EXEC CICS REWRITE}).
     *
     * <p>The five mandatory fields are validated in the exact order and with the
     * exact messages of {@code COUSR02C UPDATE-USER-INFO}, which &mdash; unlike the
     * add screen &mdash; checks the user id first: user id, first name, last name,
     * password, then user type.</p>
     *
     * <p>The record is then read by id; a missing record reproduces the CICS
     * {@code NOTFND} outcome as {@link RecordNotFoundException}. Each field is
     * compared with the stored value exactly as {@code UPDATE-USER-INFO} does
     * ({@code IF <field> NOT = SEC-USR-<field>}); the password is compared against
     * the stored hash. If no field differs, the COBOL "nothing changed" path is
     * reproduced as an unsuccessful {@link UserResult} carrying
     * {@value #MSG_PLEASE_MODIFY} and no write occurs. Otherwise only the changed
     * fields are applied (the password is re-hashed only when it actually changes)
     * and the record is saved.</p>
     *
     * @param userId      the id of the user to update
     * @param firstName   the desired first name
     * @param lastName    the desired last name
     * @param rawPassword the desired raw (unhashed) password; hashed before
     *                    persistence when changed and never logged
     * @param userType    the desired role indicator ({@code 'A'} or {@code 'U'})
     * @param expectedVersion the {@code user_security} {@code @Version} the edit screen
     *                    observed when it was rendered, carried back on save; a
     *                    {@code null} (absent/unparseable) value or a value that no
     *                    longer matches the persisted row is rejected as a conflict
     *                    (F-P7-STALE) before any change is applied
     * @return a {@link UserResult}: successful with the saved user and the
     *         {@code "User <id> has been updated ..."} message, or unsuccessful with
     *         the first validation message or the "nothing changed" message
     * @throws RecordNotFoundException if no user with the given id exists
     * @throws OptimisticLockingFailureException if the observed version is absent or
     *         does not match the persisted row (stale edit form); mapped to HTTP
     *         {@code 409} by {@code GlobalExceptionHandler}
     */
    @Transactional
    public UserResult updateUser(String userId,
                                 String firstName,
                                 String lastName,
                                 String rawPassword,
                                 char userType,
                                 Long expectedVersion) {
        // Mandatory-field edits (COUSR02C UPDATE-USER-INFO order): user id, first
        // name, last name, password, user type. Short-circuit on first empty.
        if (isBlank(userId)) {
            return new UserResult(false, null, MSG_USER_ID_EMPTY);
        }
        if (isBlank(firstName)) {
            return new UserResult(false, null, MSG_FIRST_NAME_EMPTY);
        }
        if (isBlank(lastName)) {
            return new UserResult(false, null, MSG_LAST_NAME_EMPTY);
        }
        if (isBlank(rawPassword)) {
            return new UserResult(false, null, MSG_PASSWORD_EMPTY);
        }
        if (isBlankType(userType)) {
            return new UserResult(false, null, MSG_USER_TYPE_EMPTY);
        }

        String normalizedId = normalizeUserId(userId);

        UserSecurity existing = userSecurityRepository.findById(normalizedId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_USER_ID_NOT_FOUND));

        // Stale-form guard (F-P7-STALE): the CU02 edit screen echoes the user_security @Version it
        // observed at fetch time; that observed version is carried back on save. A valid observed
        // version is MANDATORY here -- an absent (null) version means the client either never carried
        // the X-CardDemo-User-Version header or carried an unparseable value, and cannot prove the
        // operator edited the row they are about to overwrite. A mismatch means another
        // administrator committed a change after this form was rendered. In either case the write is
        // rejected as a 409 conflict rather than allowed to silently overwrite fresh state; the rare,
        // legitimate flow always echoes and returns the numeric version, so only stale or
        // contract-violating submissions are rejected. This runs before change detection so a stale
        // form is rejected regardless of which fields it carries.
        if (expectedVersion == null || !expectedVersion.equals(existing.getVersion())) {
            throw new OptimisticLockingFailureException(MSG_DATA_CHANGED);
        }

        // Change detection (COUSR02C UPDATE-USER-INFO: IF <field> NOT = stored).
        boolean modified = false;

        if (fieldChanged(existing.getSecUsrFname(), firstName)) {
            existing.setSecUsrFname(firstName);
            modified = true;
        }
        if (fieldChanged(existing.getSecUsrLname(), lastName)) {
            existing.setSecUsrLname(lastName);
            modified = true;
        }
        String desiredType = String.valueOf(userType);
        if (fieldChanged(existing.getSecUsrType(), desiredType)) {
            existing.setSecUsrType(desiredType);
            modified = true;
        }
        if (passwordChanged(rawPassword, existing.getSecUsrPwd())) {
            existing.setSecUsrPwd(encodePassword(rawPassword));
            modified = true;
        }

        if (!modified) {
            // COBOL: no field differed -> re-display with "Please modify to update ...".
            return new UserResult(false, existing, MSG_PLEASE_MODIFY);
        }

        UserSecurity saved = userSecurityRepository.save(existing);
        return new UserResult(true, saved,
                USER_MSG_PREFIX + normalizedId + UPDATED_MSG_SUFFIX);
    }

    /**
     * Deletes an existing application user &mdash; the Java re-platform of COBOL
     * program {@code COUSR03C} (CICS transaction {@code CU03}), paragraphs
     * {@code READ-USER-SEC-FILE} ({@code EXEC CICS READ ... UPDATE}) and
     * {@code DELETE-USER-SEC-FILE} ({@code EXEC CICS DELETE}).
     *
     * <p>An empty user id reproduces the COBOL {@code 'User ID can NOT be empty...'}
     * edit as an unsuccessful {@link UserResult}. The record is then read by id; a
     * missing record reproduces the CICS {@code NOTFND} outcome as
     * {@link RecordNotFoundException}. On success the record is removed and the
     * {@code "User <id> has been deleted ..."} message is returned.</p>
     *
     * @param userId the id of the user to delete
     * @return a {@link UserResult}: successful with the removed user and the delete
     *         message, or unsuccessful with the empty-id validation message
     * @throws RecordNotFoundException if no user with the given id exists
     */
    @Transactional
    public UserResult deleteUser(String userId) {
        if (isBlank(userId)) {
            return new UserResult(false, null, MSG_USER_ID_EMPTY);
        }

        String normalizedId = normalizeUserId(userId);

        UserSecurity existing = userSecurityRepository.findById(normalizedId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_USER_ID_NOT_FOUND));

        userSecurityRepository.delete(existing);
        return new UserResult(true, existing,
                USER_MSG_PREFIX + normalizedId + DELETED_MSG_SUFFIX);
    }

    /**
     * Lists application users in ascending user-id order &mdash; the Java
     * re-platform of COBOL program {@code COUSR00C} (CICS transaction
     * {@code CU00}), which browses {@code USRSEC} with
     * {@code STARTBR}/{@code READNEXT}/{@code READPREV} (paged forward with
     * {@code PF8} and backward with {@code PF7}).
     *
     * <p>The ascending key browse is <strong>paginated in the database</strong>
     * exactly as the card and transaction lists are (AAP &sect;0.4.3): the requested
     * page is fetched with a single {@code LIMIT}/{@code OFFSET} query and its total
     * with a single {@code COUNT}, so the whole {@code user_security} table is never
     * loaded into memory. When {@code startUserId} is blank the page is served by
     * the inherited {@link UserSecurityRepository#findAll(Pageable)}. When
     * {@code startUserId} is supplied (non-blank) it reproduces the legacy
     * {@code STARTBR} positioning: the listing begins at the first user id greater
     * than or equal to that (normalized, upper-cased) key, served by
     * {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqual(String, Pageable)}.
     * Because the {@code SEC-USR-ID} key is a fixed-width, upper-cased
     * {@code PIC X(08)} value, the {@code >=} predicate reproduces the KSDS
     * reposition exactly (lexicographic ordering equals key ordering).</p>
     *
     * <p>The ascending {@code secUsrId} ordering is enforced here regardless of any
     * sort carried on the incoming {@link Pageable} (via
     * {@link #withSecUsrIdAscending(Pageable)}), preserving the fixed legacy browse
     * sequence; only the page number and page size are taken from the caller.</p>
     *
     * @param startUserId optional inclusive start key reproducing the COBOL
     *                    {@code STARTBR} position; {@code null} or blank starts at
     *                    the first record
     * @param pageable    the page window (page number and size) to return; its sort
     *                    is overridden with the fixed ascending user-id order
     * @return a {@link Page} of {@link UserSecurity} records ordered by
     *         {@code secUsrId} ascending, reporting the full matching total
     */
    @Transactional(readOnly = true)
    public Page<UserSecurity> listUsers(String startUserId, Pageable pageable) {
        Pageable ordered = withSecUsrIdAscending(pageable);
        if (isBlank(startUserId)) {
            return userSecurityRepository.findAll(ordered);
        }
        return userSecurityRepository.findBySecUsrIdGreaterThanEqual(
                normalizeUserId(startUserId), ordered);
    }

    /**
     * Counts application users at or after an optional start key, without loading
     * any rows. This supports the {@code COUSR00C} backward/forward paging
     * ({@code PROCESS-PF7-KEY}/{@code PROCESS-PF8-KEY}), which must know the grand
     * total (to detect the bottom boundary) and the number of users at or after the
     * page anchor (to derive the current page index) &mdash; both as efficient
     * {@code COUNT} queries rather than as full-table reads.
     *
     * <p>When {@code startUserId} is blank the inherited
     * {@link UserSecurityRepository#count()} grand total is returned; otherwise the
     * {@code >=}-anchored
     * {@link UserSecurityRepository#countBySecUsrIdGreaterThanEqual(String)} is used
     * with the same normalized (upper-cased) key as {@link #listUsers}, so the two
     * are always consistent.</p>
     *
     * @param startUserId optional inclusive start key; {@code null} or blank counts
     *                    every user
     * @return the number of users with {@code secUsrId >=} the (normalized) key, or
     *         the grand total when the key is blank
     */
    @Transactional(readOnly = true)
    public long countUsers(String startUserId) {
        if (isBlank(startUserId)) {
            return userSecurityRepository.count();
        }
        return userSecurityRepository.countBySecUsrIdGreaterThanEqual(
                normalizeUserId(startUserId));
    }

    /**
     * Builds a {@link Pageable} that keeps the caller's page number and size but
     * forces ascending {@code secUsrId} ordering, reproducing the {@code COUSR00C}
     * primary-key browse regardless of the sort the caller supplied. A {@code null}
     * or unpaged request is turned into a single, key-ordered page over all rows.
     *
     * @param pageable the caller's paging request; may be {@code null} or unpaged
     * @return a {@link Pageable} sorted ascending by {@code secUsrId}
     */
    private static Pageable withSecUsrIdAscending(Pageable pageable) {
        Sort bySecUsrId = Sort.by(Sort.Direction.ASC, "secUsrId");
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, Integer.MAX_VALUE, bySecUsrId);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), bySecUsrId);
    }

    /**
     * Normalizes a user id for storage and lookup: trims surrounding whitespace and
     * upper-cases with {@link Locale#ROOT} (3270 parity). The id is not truncated;
     * length is enforced by the {@code sec_usr_id} column.
     *
     * @param userId the raw user id (validated non-blank by the caller)
     * @return the normalized user id
     */
    private static String normalizeUserId(String userId) {
        return userId.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Encodes a raw password for storage: upper-cased ({@link Locale#ROOT}, 3270
     * parity) then one-way hashed with the injected {@link PasswordEncoder}. The
     * input and output are sensitive and are never logged.
     *
     * @param rawPassword the raw password (validated non-blank by the caller)
     * @return the hashed password
     */
    private String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword.toUpperCase(Locale.ROOT));
    }

    /**
     * Determines whether a desired password differs from the stored hash,
     * reproducing the COBOL {@code IF PASSWDI NOT = SEC-USR-PWD} test in the hashed
     * world: the (upper-cased) raw password is matched against the stored hash and a
     * non-match means the password changed. A missing stored hash is treated as
     * changed so the credential is (re)hashed.
     *
     * @param rawPassword the desired raw password (validated non-blank by the caller)
     * @param storedHash  the currently stored password hash (may be {@code null})
     * @return {@code true} if the password should be treated as changed
     */
    private boolean passwordChanged(String rawPassword, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return true;
        }
        return !passwordEncoder.matches(rawPassword.toUpperCase(Locale.ROOT), storedHash);
    }

    /**
     * Field-equality test matching the COBOL fixed-width comparison, where a
     * {@code PIC X(n)} field is space-padded so trailing blanks are not significant.
     * A {@code null} is treated as an empty value.
     *
     * @param current  the stored value
     * @param incoming the incoming value
     * @return {@code true} if the incoming value differs from the stored value
     */
    private static boolean fieldChanged(String current, String incoming) {
        String left = current == null ? "" : current.stripTrailing();
        String right = incoming == null ? "" : incoming.stripTrailing();
        return !left.equals(right);
    }

    /**
     * Emptiness test for a text field matching the COBOL
     * {@code = SPACES OR LOW-VALUES} condition: {@code null} or all-whitespace is
     * empty.
     *
     * @param value the value to test
     * @return {@code true} if the value is {@code null} or blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Emptiness test for the single-character user type matching the COBOL
     * {@code = SPACES OR LOW-VALUES} condition: a space or a low-value (NUL, that
     * is {@code (char) 0}) is empty.
     *
     * @param value the type character to test
     * @return {@code true} if the character is a space or a low-value
     */
    private static boolean isBlankType(char value) {
        return value == ' ' || value == '\u0000';
    }

    /**
     * Immutable result of a mutating user operation (add, update, delete).
     *
     * <p>It reproduces the legacy {@code WS-MESSAGE}/screen outcome: {@code success}
     * distinguishes a completed operation from a re-displayed screen carrying a
     * validation or "nothing changed" message, {@code user} is the affected record
     * (the persisted entity on success, or {@code null} when validation failed
     * before any record was resolved), and {@code message} is the exact
     * caller-visible text the COBOL program produced.</p>
     *
     * <p>The referenced {@link UserSecurity} never exposes its password through
     * {@link UserSecurity#toString()}, so logging a {@code UserResult} cannot leak
     * a credential.</p>
     *
     * @param success {@code true} if the operation completed and persisted a
     *                change; {@code false} for a validation or "nothing changed"
     *                outcome
     * @param user    the affected user record, or {@code null} when none was
     *                resolved
     * @param message the exact caller-visible message text
     */
    public record UserResult(boolean success, UserSecurity user, String message) {
    }
}

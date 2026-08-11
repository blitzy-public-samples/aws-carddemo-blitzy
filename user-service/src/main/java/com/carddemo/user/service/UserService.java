/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.user.service;

import org.springframework.beans.factory.annotation.Autowired;
import com.carddemo.common.security.SessionPrincipalIndex;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.AddUserRequestDto;
import com.carddemo.common.dto.UpdateUserRequestDto;
import com.carddemo.common.dto.UserListResponseDto;
import com.carddemo.common.dto.UserResponseDto;
import com.carddemo.common.dto.UserWriteResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.user.mapper.UserMapper;
import com.carddemo.user.repository.UserRepository;
import com.carddemo.common.security.UserIdNormalizer;

import jakarta.persistence.OptimisticLockException;

/**
 * Administrator-only user-CRUD business logic.
 * :purpose: Re-platforms the legacy CICS COBOL programs ``COUSR00C``/``COUSR01C``/
 *     ``COUSR02C``/``COUSR03C`` (transactions ``CU00``-``CU03``) that manage the ``USRSEC``
 *     security-user file: list/paging, add, read-for-display, update, and delete. Persists
 *     through {@link UserRepository}, maps entities and DTOs through {@link UserMapper}, and
 *     hashes credentials through the injected BCrypt-family {@link PasswordEncoder}; the raw
 *     password is handled only here and never stored or logged in clear text.
 * :output: {@link UserResponseDto} projections (id, first name, last name, user type) and
 *     verbatim outcome messages; failures surface as {@link RecordNotFoundException} (not
 *     found) or {@link CardDemoException} (all other rejections).
 * :note: Administration that changes what a user is allowed to do, or the credential that
 *     proves who they are, also revokes that user's live sessions through {@link
 *     SessionPrincipalIndex}. On the 3270 the next transaction re-read ``USRSEC``, so a
 *     maintenance action took effect immediately; a cached session context would otherwise
 *     keep a deleted user signed on, or keep a demoted administrator's authority alive, until
 *     the session timeout elapsed. Revocation is deliberately performed while the unit of work
 *     is still open: should the commit then fail, an already-revoked user is merely asked to
 *     sign on again, whereas revoking only after commit would leave a window in which the
 *     stale authority is still honoured. Renaming a user changes no authority and revokes
 *     nothing.
 */
@Service
public class UserService {

    /** :purpose: Logger used to surface success and read-for-display prompt messages. */
    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    /** :purpose: Fixed user-list page size, matching the legacy ``OCCURS 10 TIMES`` screen array. */
    private static final int PAGE_SIZE = 10;

    /**
     * :purpose: Rows fetched per browse: one screen plus the single look-ahead record
     *     ``COUSR00C`` reads to decide ``CDEMO-CU00-NEXT-PAGE-FLG``.
     * :note: ``PROCESS-PAGE-FORWARD`` fills ten rows and then performs one further
     *     ``READNEXT``, setting ``NEXT-PAGE-YES`` only when that eleventh record exists
     *     (L307-L316). Inferring the flag from the page being full instead reported a
     *     forward page after a browse that ended exactly on a page boundary, and the
     *     screen then asked for a page that was not there.
     */
    private static final int LOOK_AHEAD_SIZE = PAGE_SIZE + 1;

    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid values are U and D";
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";
    private static final String MSG_BROWSE_TOP = "You are at the top of the page...";
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";
    private static final String MSG_REACHED_TOP = "You have reached the top of the page...";
    private static final String MSG_UNABLE_LOOKUP = "Unable to lookup User...";

    /**
     * :purpose: Request-payload property names published in ``ErrorResponse.fieldErrors``
     *  so the screen marks and cursors to the control its edit refused, which is the
     *  transport form of the legacy ``MOVE -1 TO <field>L``.
     */
    private static final String FIELD_FIRSTNAME = "firstName";

    /** :purpose: ``fieldErrors`` key of the last-name property. */
    private static final String FIELD_LASTNAME = "lastName";

    /** :purpose: ``fieldErrors`` key of the user-id property. */
    private static final String FIELD_USERID = "userId";

    /** :purpose: ``fieldErrors`` key of the password property. */
    private static final String FIELD_PASSWORD = "password";

    /** :purpose: ``fieldErrors`` key of the user-type property. */
    private static final String FIELD_USERTYPE = "userType";

    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** :purpose: SQL state PostgreSQL raises for a unique or primary-key violation. */
    private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

    private static final String MSG_USER_PREFIX = "User ";
    private static final String MSG_ADDED_SUFFIX = " has been added ...";
    private static final String MSG_USER_ALREADY_EXISTS = "User ID already exist...";
    private static final String MSG_UNABLE_ADD = "Unable to Add User...";

    private static final String MSG_PRESS_PF5_UPDATE = "Press PF5 key to save your updates ...";
    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";
    private static final String MSG_UPDATED_SUFFIX = " has been updated ...";
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";
    private static final String MSG_UNABLE_UPDATE = "Unable to Update User...";

    private static final String MSG_PRESS_PF5_DELETE = "Press PF5 key to delete this user ...";
    private static final String MSG_DELETED_SUFFIX = " has been deleted ...";

    /** :purpose: Audit reason recorded when a user's own record is removed. */
    private static final String REASON_USER_DELETED = "USER_DELETED";

    /** :purpose: Audit reason recorded when ``SEC-USR-TYPE`` changes, altering authority. */
    private static final String REASON_ROLE_CHANGED = "ROLE_CHANGED";

    /** :purpose: Audit reason recorded when the stored credential is replaced. */
    private static final String REASON_CREDENTIAL_CHANGED = "CREDENTIAL_CHANGED";

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionPrincipalIndex sessionPrincipalIndex;


    /**
     * :purpose: Construct the service with its collaborators via constructor injection.
     * :param userRepository: Spring Data JPA repository over the ``security_users`` store.
     * :param userMapper: mapper between {@link SecurityUser} entities and the user DTOs.
     * :param passwordEncoder: encoder used to hash and verify user credentials.
     * :param sessionPrincipalIndex: principal-to-session index used to invalidate the
     *     live sessions of a user whose authority or credential changed, or who was
     *     deleted. Inert when no session store is configured, in which case there are no
     *     shared sessions to revoke.
     */
    @Autowired
    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       SessionPrincipalIndex sessionPrincipalIndex) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionPrincipalIndex = sessionPrincipalIndex;
    }


    /**
     * :purpose: Invalidate every live session of a user so a maintenance action takes
     *     effect on the caller's very next request instead of at session expiry.
     * :param userId: the maintained user id.
     * :param reason: short machine-readable reason recorded in the audit trail.
     */
    private void revokeSessions(String userId, String reason) {
        sessionPrincipalIndex.revokeSessions(userId, reason);
    }


    /**
     * :purpose: Report whether a request value is empty, modeling the legacy
     *     ``= SPACES OR LOW-VALUES`` test as ``null`` or whitespace-only.
     * :param value: the value to test.
     * :returns: ``true`` when ``value`` is ``null`` or blank after trimming.
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * :purpose: Perform the keyed read of a single security user, translating a data
     *     access failure to the browse/lookup failure outcome and an absent record to
     *     the not-found outcome.
     * :param userId: the user id to look up; folded to its canonical stored form before the
     *     keyed read, so a caller may address a user under any casing.
     * :returns: the managed {@link SecurityUser} for ``userId``.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the keyed read fails unexpectedly.
     */
    private SecurityUser readUser(String userId) {
        Optional<SecurityUser> found;
        try {
            found = userRepository.findBySecUsrId(UserIdNormalizer.normalize(userId));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        return found.orElseThrow(() -> new RecordNotFoundException(MSG_USER_NOT_FOUND));
    }

    /**
     * :purpose: List security users one ascending page at a time (``COUSR00C`` / ``CU00``),
     *     ordered by user id, and surface the paging banner the legacy screen displays.
     * :param pageNumber: zero-based page index (page size is ten).
     * :returns: the page of users together with its paging cursors and, when the page holds
     *     no rows, the ``WS-MESSAGE`` banner ``COUSR00C`` moves to ``ERRMSGO`` (L526).
     * :raises CardDemoException: when the requested page precedes the first page, or when
     *     the ordered browse fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserListResponseDto listUsers(int pageNumber) {
        // A page index before the first page has no legacy analogue -- the 3270 screen can
        // only ask for the page before the current one -- so it reports the same top
        // boundary the PF7 key reports (COUSR00C L251) rather than failing unexpectedly.
        if (pageNumber < 0) {
            throw new CardDemoException(MSG_ALREADY_TOP);
        }
        Page<SecurityUser> page;
        try {
            page = userRepository.findAllByOrderBySecUsrIdAsc(PageRequest.of(pageNumber, PAGE_SIZE));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        List<SecurityUser> rows = page.getContent();
        UserListResponseDto response = toListResponse(rows, pageNumber);
        response.setNextPage(page.hasNext());
        if (rows.isEmpty()) {
            response.setMessage(pageNumber == 0 ? MSG_BROWSE_TOP : MSG_REACHED_BOTTOM);
        }
        return response;
    }

    /**
     * :purpose: Assemble the user-list response from a page of rows, populating the
     *     ``CDEMO-CU00-USRID-FIRST``/``-LAST`` cursors the PF7 and PF8 keys carry in the
     *     COMMAREA.
     * :param rows: the security users on this page, in ascending id order.
     * :param pageNumber: zero-based index of this page.
     * :returns: the populated response, without a banner or selection.
     */
    private UserListResponseDto toListResponse(List<SecurityUser> rows, int pageNumber) {
        UserListResponseDto response = new UserListResponseDto();
        response.setUsers(userMapper.toResponseList(rows));
        response.setPageNumber(pageNumber);
        if (!rows.isEmpty()) {
            response.setUserIdFirst(rows.get(0).getSecUsrId());
            response.setUserIdLast(rows.get(rows.size() - 1).getSecUsrId());
        }
        return response;
    }

    /**
     * :purpose: List users from a starting id (``COUSR00C`` ENTER with the ``Search User ID``
     *     field filled, L218-L221): the browse is positioned at the first user id greater
     *     than or equal to the entered key, exactly as the legacy ``STARTBR`` GTEQ does, and
     *     the first ten rows from that position are returned.
     * :param startUserId: the entered browse-start user id; a blank value restarts the browse
     *     at the first record (the legacy ``LOW-VALUES`` key).
     * :returns: the page of users from that position together with its paging cursors.
     * :raises CardDemoException: when the ordered browse fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserListResponseDto listUsersFrom(String startUserId) {
        if (startUserId == null || startUserId.isBlank()) {
            return listUsers(0);
        }
        List<SecurityUser> found;
        try {
            found = userRepository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                    UserIdNormalizer.normalize(startUserId), Pageable.ofSize(LOOK_AHEAD_SIZE));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        boolean hasFollowingRow = found.size() > PAGE_SIZE;
        List<SecurityUser> rows = hasFollowingRow ? found.subList(0, PAGE_SIZE) : found;
        UserListResponseDto response = toListResponse(rows, 0);
        response.setNextPage(hasFollowingRow);
        if (rows.isEmpty()) {
            response.setMessage(MSG_REACHED_BOTTOM);
        }
        return response;
    }

    /**
     * :purpose: Page forward from the last displayed user id (``COUSR00C`` PF8), returning
     *     the next ascending slice of users.
     * :param lastUserId: the last user id shown on the current page (exclusive lower bound).
     * :returns: the next page of users in ascending id order.
     * :raises CardDemoException: when no users follow ``lastUserId`` (bottom boundary) or the
     *     browse fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserListResponseDto pageForward(String lastUserId) {
        List<SecurityUser> found;
        try {
            found = userRepository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(
                    UserIdNormalizer.normalize(lastUserId), Pageable.ofSize(LOOK_AHEAD_SIZE));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        if (found.isEmpty()) {
            throw new CardDemoException(MSG_ALREADY_BOTTOM);
        }
        boolean hasFollowingRow = found.size() > PAGE_SIZE;
        List<SecurityUser> next = hasFollowingRow ? found.subList(0, PAGE_SIZE) : found;
        UserListResponseDto response = toListResponse(next, 0);
        response.setNextPage(hasFollowingRow);
        if (next.size() < PAGE_SIZE) {
            response.setMessage(MSG_REACHED_BOTTOM);
        }
        return response;
    }

    /**
     * :purpose: Page backward from the first displayed user id (``COUSR00C`` PF7), returning
     *     the previous slice re-ordered ascending for display.
     * :param firstUserId: the first user id shown on the current page (exclusive upper bound).
     * :returns: the previous page of users in ascending id order.
     * :raises CardDemoException: when no users precede ``firstUserId`` (top boundary) or the
     *     browse fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserListResponseDto pageBackward(String firstUserId) {
        List<SecurityUser> previous;
        try {
            previous = userRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
                    UserIdNormalizer.normalize(firstUserId), Pageable.ofSize(PAGE_SIZE));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        if (previous.isEmpty()) {
            throw new CardDemoException(MSG_ALREADY_TOP);
        }
        List<SecurityUser> ascending = new ArrayList<>(previous);
        Collections.reverse(ascending);
        UserListResponseDto response = toListResponse(ascending, 0);
        // Paging back always leaves a forward page available (COUSR00C SET NEXT-PAGE-YES).
        response.setNextPage(true);
        if (previous.size() < PAGE_SIZE) {
            response.setMessage(MSG_REACHED_TOP);
        }
        return response;
    }

    /**
     * :purpose: Resolve a user-list row selection to its action (``COUSR00C``
     *     PROCESS-ENTER-KEY): ``U``/``u`` selects update and ``D``/``d`` selects delete.
     * :param selection: the entered row selection flag.
     * :returns: the canonical upper-case action code ``"U"`` (update) or ``"D"`` (delete).
     * :raises CardDemoException: when ``selection`` is neither an update nor a delete flag.
     */
    public String resolveSelection(String selection) {
        if (selection != null) {
            String trimmed = selection.trim();
            if ("U".equalsIgnoreCase(trimmed)) {
                return "U";
            }
            if ("D".equalsIgnoreCase(trimmed)) {
                return "D";
            }
        }
        throw new CardDemoException(MSG_INVALID_SELECTION);
    }

    /**
     * :purpose: Add a new security user (``COUSR01C`` / ``CU01``): validate the entered
     *     fields, reject a duplicate id, encode the credential, and persist as one unit of work.
     * :param request: the entered user id, first name, last name, user type, and the raw
     *     password, which is encoded here before persistence and never stored or logged in clear
     *     text.
     * :returns: the persisted user projection plus the verbatim ``COUSR01C`` outcome message
     *     ``'User <id> has been added ...'``.
     * :raises CardDemoException: when a required field is empty, the user type is not a
     *     recognised code, the user id already exists, or the insert fails unexpectedly.
     * :note: The duplicate-id check is written twice over. The pre-check keeps the verbatim
     *     legacy literal for the ordinary case, and the primary-key violation raised by a
     *     concurrent inserter that won the race between the pre-check and the flush is translated
     *     to the SAME literal, so two simultaneous creates yield exactly one row and one 400 --
     *     never two "created" answers for one stored row.
     */
    @Transactional
    public UserWriteResponseDto addUser(AddUserRequestDto request) {
        if (request == null || isBlank(request.getFirstName())) {
            throw new CardDemoException(MSG_FIRST_NAME_EMPTY).onField(FIELD_FIRSTNAME);
        }
        if (isBlank(request.getLastName())) {
            throw new CardDemoException(MSG_LAST_NAME_EMPTY).onField(FIELD_LASTNAME);
        }
        if (isBlank(request.getUserId())) {
            throw new CardDemoException(MSG_USER_ID_EMPTY).onField(FIELD_USERID);
        }
        String rawPassword = request.getPassword();
        if (isBlank(rawPassword)) {
            throw new CardDemoException(MSG_PASSWORD_EMPTY).onField(FIELD_PASSWORD);
        }
        if (isBlank(request.getUserType())) {
            throw new CardDemoException(MSG_USER_TYPE_EMPTY).onField(FIELD_USERTYPE);
        }

        // Folded to the canonical stored form BEFORE the duplicate check and before the insert.
        // The legacy terminal's UCTRAN attribute made a lower-case id unrepresentable, and
        // COSGN00C upper-cases the entered id at sign-on [app/cbl/COSGN00C.cbl:L132]; storing an
        // id verbatim would let an administrator create a user that no sign-on could ever match,
        // and would also let 'qat001' and 'QAT001' coexist as two rows the auth path cannot tell
        // apart.
        String userId = UserIdNormalizer.normalize(request.getUserId());
        if (userRepository.existsBySecUsrId(userId)) {
            throw new CardDemoException(MSG_USER_ALREADY_EXISTS).onField(FIELD_USERID);
        }

        SecurityUser user = userMapper.toEntity(request);
        user.setSecUsrPwd(passwordEncoder.encode(rawPassword));

        SecurityUser saved;
        try {
            // saveAndFlush, not save: the INSERT must reach the database inside this try so a
            // concurrent claim of the same primary key is reported as the duplicate-id
            // outcome here rather than escaping the boundary as a commit-time failure.
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueKeyViolation(ex)) {
                LOG.warn("Concurrent create rejected for duplicate user id {}", userId);
                throw new CardDemoException(MSG_USER_ALREADY_EXISTS, ex);
            }
            throw new CardDemoException(MSG_UNABLE_ADD, ex);
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_ADD, ex);
        }

        String message = MSG_USER_PREFIX + userId + MSG_ADDED_SUFFIX;
        LOG.info(message);
        return toWriteResponse(saved, message);
    }

    /**
     * :purpose: Report whether a data-integrity failure is a unique/primary-key violation,
     *     which for ``security_users`` can only mean the user id is already taken. Any other
     *     integrity failure is a different fault and must not be reported as a duplicate id.
     * :param ex: the data-integrity failure raised by the persistence provider.
     * :returns: ``true`` when the failure carries SQL state ``23505`` (unique violation) or
     *     is already classified as a duplicate key by Spring's exception translation.
     */
    private boolean isUniqueKeyViolation(DataIntegrityViolationException ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && SQL_STATE_UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * :purpose: Project a persisted security user together with the verbatim legacy
     *     message the corresponding screen displays.
     * :param user: the persisted security user.
     * :param message: the verbatim legacy outcome or prompt message.
     * :returns: the populated write response; the stored credential is never projected.
     */
    private UserWriteResponseDto toWriteResponse(SecurityUser user, String message) {
        return new UserWriteResponseDto(user.getSecUsrId(), user.getSecUsrFname(),
                user.getSecUsrLname(), user.getSecUsrType(), message);
    }

    /**
     * :purpose: Read a single security user for display (``COUSR02C``/``COUSR03C`` keyed read).
     * :param userId: the user id to look up.
     * :returns: the user projection (id, first name, last name, user type).
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the keyed read fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserResponseDto getUser(String userId) {
        return userMapper.toResponse(readUser(userId));
    }

    /**
     * :purpose: Read a user for the update screen (``COUSR02C`` read-for-display), surfacing
     *     the update confirmation prompt.
     * :param userId: the user id to look up.
     * :returns: the user projection to pre-fill the update form, carrying the verbatim
     *     ``COUSR02C`` prompt ``'Press PF5 key to save your updates ...'``.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the keyed read fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserWriteResponseDto getUserForUpdate(String userId) {
        UserWriteResponseDto response = toWriteResponse(readUser(userId), MSG_PRESS_PF5_UPDATE);
        LOG.info(MSG_PRESS_PF5_UPDATE);
        return response;
    }

    /**
     * :purpose: Read a user for the delete screen (``COUSR03C`` read-for-display), surfacing
     *     the delete confirmation prompt.
     * :param userId: the user id to look up.
     * :returns: the user projection to confirm before deletion, carrying the verbatim
     *     ``COUSR03C`` prompt ``'Press PF5 key to delete this user ...'``.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the keyed read fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserWriteResponseDto getUserForDelete(String userId) {
        UserWriteResponseDto response = toWriteResponse(readUser(userId), MSG_PRESS_PF5_DELETE);
        LOG.info(MSG_PRESS_PF5_DELETE);
        return response;
    }

    /**
     * :purpose: Update an existing security user (``COUSR02C`` / ``CU02``): validate the
     *     entered fields, read the current record, apply only the changed fields, re-encode the
     *     credential when it changes, and persist as one unit of work.
     * :param userId: the user id to update (the lookup key, supplied in the path); folded to
     *     its canonical stored form, so the user may be addressed under any casing.
     * :param request: the entered first name, last name, user type, and OPTIONALLY the raw
     *     password. A supplied password is compared against the stored hash and re-encoded only
     *     when it differs; an absent or blank one leaves the stored credential untouched, so a
     *     name or role change is not also a forced credential reset. The raw value is never stored
     *     or logged in clear text.
     * :returns: the updated user projection plus the verbatim ``COUSR02C`` outcome message
     *     ``'User <id> has been updated ...'``.
     * :note: The password is optional on this call: an absent or blank value leaves the stored
     *     credential untouched, so a name-only or role-only edit is possible even though the hash
     *     is never sent to the client. A supplied value replaces the credential only when it does
     *     not already match it.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when a required field is empty, no field changed, or the
     *     update fails unexpectedly. A user type outside the two codes the role model recognises
     *     is refused by the ``chk_sec_usr_type`` database constraint and reported with the legacy
     *     write-failure literal.
     * :raises OptimisticLockConflictException: when another writer changed the same record
     *     between this read and the flush, so the two edits cannot both be applied.
     */
    @Transactional
    public UserWriteResponseDto updateUser(String userId, UpdateUserRequestDto request) {
        if (isBlank(userId)) {
            throw new CardDemoException(MSG_USER_ID_EMPTY).onField(FIELD_USERID);
        }
        // Every later use of the id -- the keyed read, the session revocation key, and the outcome
        // message -- must use the one canonical form, or a caller addressing the user in lower case
        // would revoke sessions under a key no session was indexed by.
        String normalizedUserId = UserIdNormalizer.normalize(userId);
        if (request == null || isBlank(request.getFirstName())) {
            throw new CardDemoException(MSG_FIRST_NAME_EMPTY).onField(FIELD_FIRSTNAME);
        }
        if (isBlank(request.getLastName())) {
            throw new CardDemoException(MSG_LAST_NAME_EMPTY).onField(FIELD_LASTNAME);
        }
        // COUSR02C L169 pre-fills PASSWD from SEC-USR-PWD under the mapset's DRK
        // attribute, so the operator sees an empty box that already holds the current
        // password; L227-228 then rewrites the credential only IF PASSWDI NOT =
        // SEC-USR-PWD. Storing the password as a one-way hash (AAP 0.6.7) makes that
        // pre-fill impossible: there is no plaintext to send, and shipping the hash to
        // the browser would put credential material on the wire. An omitted password
        // therefore carries the meaning the pre-fill gave it -- leave the credential
        // alone -- which reproduces the legacy outcome that an update touching no
        // password preserves it. The emptiness edit stays reachable on the add path,
        // where COUSR01C L138 genuinely requires a password.
        String rawPassword = request.getPassword();
        boolean passwordSupplied = !isBlank(rawPassword);
        if (isBlank(request.getUserType())) {
            throw new CardDemoException(MSG_USER_TYPE_EMPTY).onField(FIELD_USERTYPE);
        }

        SecurityUser user = readUser(normalizedUserId);

        boolean modified = false;
        if (!Objects.equals(request.getFirstName(), user.getSecUsrFname())) {
            modified = true;
        }
        if (!Objects.equals(request.getLastName(), user.getSecUsrLname())) {
            modified = true;
        }
        // An absent password is not a change, and must not be mistaken for one: comparing a blank
        // value against the stored hash would report "changed" and re-encode the blank as the new
        // credential, locking the user out.
        // The comparison runs only against a supplied value, so an omitted password can
        // never be read as "differs from the stored hash" and can never trigger a write.
        boolean passwordChanged =
                passwordSupplied && !passwordEncoder.matches(rawPassword, user.getSecUsrPwd());
        if (passwordChanged) {
            modified = true;
        }
        boolean roleChanged = !Objects.equals(request.getUserType(), user.getSecUsrType());
        if (roleChanged) {
            modified = true;
        }

        if (!modified) {
            throw new CardDemoException(MSG_PLEASE_MODIFY);
        }

        userMapper.apply(request, user);
        if (passwordChanged) {
            user.setSecUsrPwd(passwordEncoder.encode(rawPassword));
        }

        SecurityUser saved;
        try {
            // The explicit flush runs the @Version check inside this transaction, so a
            // concurrent update of the same record is reported here as the legacy conflict
            // outcome instead of silently overwriting the other writer's credential.
            saved = userRepository.saveAndFlush(user);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
            LOG.warn("Optimistic lock conflict updating user {}", normalizedUserId);
            throw new OptimisticLockConflictException(ex);
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_UPDATE, ex);
        }

        // A changed user type changes the granted authority carried by the session
        // context, and a changed credential invalidates the proof the session was
        // issued against: in both cases the live sessions must stop authorizing. The
        // role change is reported in preference to the credential change because it is
        // the authorization-relevant one.
        if (roleChanged) {
            revokeSessions(normalizedUserId, REASON_ROLE_CHANGED);
        } else if (passwordChanged) {
            revokeSessions(normalizedUserId, REASON_CREDENTIAL_CHANGED);
        }

        String message = MSG_USER_PREFIX + normalizedUserId + MSG_UPDATED_SUFFIX;
        LOG.info(message);
        return toWriteResponse(saved, message);
    }

    /**
     * :purpose: Delete an existing security user (``COUSR03C`` / ``CU03``): validate the id,
     *     read the current record, and delete it as one unit of work.
     *     Every live session of the deleted user is revoked, so the removal takes effect
     *     immediately rather than at session expiry.
     * :param userId: the user id to delete.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the id is empty or the delete fails unexpectedly.
     */
    @Transactional
    public void deleteUser(String userId) {
        if (isBlank(userId)) {
            throw new CardDemoException(MSG_USER_ID_EMPTY).onField(FIELD_USERID);
        }
        // As on the update path: the revocation key and the log record must both use the canonical
        // form the sessions were indexed under.
        String normalizedUserId = UserIdNormalizer.normalize(userId);

        SecurityUser user = readUser(normalizedUserId);

        try {
            userRepository.delete(user);
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_UPDATE, ex);
        }

        revokeSessions(normalizedUserId, REASON_USER_DELETED);

        LOG.info(MSG_USER_PREFIX + normalizedUserId + MSG_DELETED_SUFFIX);
    }
}

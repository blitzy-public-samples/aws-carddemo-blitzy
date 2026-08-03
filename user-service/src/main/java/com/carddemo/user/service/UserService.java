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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.user.mapper.UserMapper;
import com.carddemo.user.repository.UserRepository;

/**
 * Administrator-only user-CRUD business logic.
 *
 * :purpose: Re-platforms the legacy CICS COBOL programs ``COUSR00C``/``COUSR01C``/
 *     ``COUSR02C``/``COUSR03C`` (transactions ``CU00``-``CU03``) that manage the
 *     ``USRSEC`` security-user file: list/paging, add, read-for-display, update, and
 *     delete. Persists through {@link UserRepository}, maps entities and DTOs through
 *     {@link UserMapper}, and hashes credentials through the injected BCrypt-family
 *     {@link PasswordEncoder}; the raw password is handled only here and never stored
 *     or logged in clear text.
 * :output: {@link UserResponseDto} projections (id, first name, last name, user type)
 *     and verbatim outcome messages; failures surface as {@link RecordNotFoundException}
 *     (not found) or {@link CardDemoException} (all other rejections).
 */
@Service
public class UserService {

    /** :purpose: Logger used to surface success and read-for-display prompt messages. */
    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    /** :purpose: Fixed user-list page size, matching the legacy ``OCCURS 10 TIMES`` screen array. */
    private static final int PAGE_SIZE = 10;

    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid values are U and D";
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";
    private static final String MSG_BROWSE_TOP = "You are at the top of the page...";
    private static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";
    private static final String MSG_REACHED_TOP = "You have reached the top of the page...";
    private static final String MSG_UNABLE_LOOKUP = "Unable to lookup User...";

    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

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

    /**
     * :purpose: Principal-to-session index used to invalidate the shared Redis sessions of a
     *     user whose authority or credential just changed, so a change that removes access
     *     takes effect immediately instead of at the next natural session expiry. May be
     *     ``null`` when no session store is wired (unit tests), in which case revocation is
     *     a no-op.
     */
    private final SessionPrincipalIndex sessionPrincipalIndex;

    /**
     * :purpose: Construct the service with its collaborators via constructor injection.
     * :param userRepository: Spring Data JPA repository over the ``security_users`` store.
     * :param userMapper: mapper between {@link SecurityUser} entities and the user DTOs.
     * :param passwordEncoder: encoder used to hash and verify user credentials.
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
     * :purpose: Construct the service without a session index, so a unit test can exercise
     *     the CRUD contract without a Redis-backed session store. Session revocation is a
     *     no-op in that configuration.
     * :param userRepository: the security-user repository.
     * :param userMapper: the entity/DTO mapper.
     * :param passwordEncoder: the shared delegating password encoder.
     */
    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionPrincipalIndex = null;
    }
    /**
     * :purpose: Invalidate every live session of a user so a maintenance action takes
     *     effect on the caller's very next request instead of at session expiry.
     * :param userId: the maintained user id.
     * :param reason: short machine-readable reason recorded in the audit trail.
     */
    private void revokeSessions(String userId, String reason) {
        if (sessionPrincipalIndex == null) {
            return;
        }
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
     * :param userId: the user id to look up; trimmed before the keyed read.
     * :returns: the managed {@link SecurityUser} for ``userId``.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the keyed read fails unexpectedly.
     */
    private SecurityUser readUser(String userId) {
        Optional<SecurityUser> found;
        try {
            found = userRepository.findBySecUsrId(userId == null ? null : userId.trim());
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
     * :purpose: Page forward from the last displayed user id (``COUSR00C`` PF8), returning
     *     the next ascending slice of users.
     * :param lastUserId: the last user id shown on the current page (exclusive lower bound).
     * :returns: the next page of users in ascending id order.
     * :raises CardDemoException: when no users follow ``lastUserId`` (bottom boundary) or the
     *     browse fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserListResponseDto pageForward(String lastUserId) {
        List<SecurityUser> next;
        try {
            next = userRepository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(lastUserId, Pageable.ofSize(PAGE_SIZE));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        if (next.isEmpty()) {
            throw new CardDemoException(MSG_ALREADY_BOTTOM);
        }
        UserListResponseDto response = toListResponse(next, 0);
        // A short page means the browse hit end-of-file, so PF8 can advance no further.
        response.setNextPage(next.size() == PAGE_SIZE);
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
            previous = userRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(firstUserId, Pageable.ofSize(PAGE_SIZE));
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
     *     fields, reject a duplicate id, encode the credential, and persist as one unit of
     *     work.
     * :param request: the entered user id, first name, last name, and user type.
     * :param rawPassword: the entered raw password, encoded here before persistence and
     *     never stored or logged in clear text.
     * :returns: the persisted user projection plus the verbatim ``COUSR01C`` outcome
     *     message ``'User <id> has been added ...'``.
     * :raises CardDemoException: when a required field is empty, the user id already
     *     exists, or the insert fails unexpectedly.
     */
    @Transactional
    public UserWriteResponseDto addUser(AddUserRequestDto request, String rawPassword) {
        if (request == null || isBlank(request.getFirstName())) {
            throw new CardDemoException(MSG_FIRST_NAME_EMPTY);
        }
        if (isBlank(request.getLastName())) {
            throw new CardDemoException(MSG_LAST_NAME_EMPTY);
        }
        if (isBlank(request.getUserId())) {
            throw new CardDemoException(MSG_USER_ID_EMPTY);
        }
        if (isBlank(rawPassword)) {
            throw new CardDemoException(MSG_PASSWORD_EMPTY);
        }
        if (isBlank(request.getUserType())) {
            throw new CardDemoException(MSG_USER_TYPE_EMPTY);
        }

        String userId = request.getUserId().trim();
        if (userRepository.existsBySecUsrId(userId)) {
            throw new CardDemoException(MSG_USER_ALREADY_EXISTS);
        }

        SecurityUser user = userMapper.toEntity(request);
        user.setSecUsrPwd(passwordEncoder.encode(rawPassword));

        SecurityUser saved;
        try {
            saved = userRepository.save(user);
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_ADD, ex);
        }

        String message = MSG_USER_PREFIX + userId + MSG_ADDED_SUFFIX;
        LOG.info(message);
        return toWriteResponse(saved, message);
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
     *     entered fields, read the current record, apply only the changed fields, re-encode
     *     the credential when it changes, and persist as one unit of work.
     * :param userId: the user id to update (the lookup key, supplied separately).
     * :param request: the entered first name, last name, and user type.
     * :param rawPassword: the entered raw password, compared against the stored hash and
     *     re-encoded only when it changes; never stored or logged in clear text.
     * :returns: the updated user projection plus the verbatim ``COUSR02C`` outcome
     *     message ``'User <id> has been updated ...'``.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when a required field is empty, no field changed, or the
     *     update fails unexpectedly.
     */
    @Transactional
    public UserWriteResponseDto updateUser(String userId, UpdateUserRequestDto request, String rawPassword) {
        if (isBlank(userId)) {
            throw new CardDemoException(MSG_USER_ID_EMPTY);
        }
        if (request == null || isBlank(request.getFirstName())) {
            throw new CardDemoException(MSG_FIRST_NAME_EMPTY);
        }
        if (isBlank(request.getLastName())) {
            throw new CardDemoException(MSG_LAST_NAME_EMPTY);
        }
        if (isBlank(rawPassword)) {
            throw new CardDemoException(MSG_PASSWORD_EMPTY);
        }
        if (isBlank(request.getUserType())) {
            throw new CardDemoException(MSG_USER_TYPE_EMPTY);
        }

        SecurityUser user = readUser(userId);

        boolean modified = false;
        if (!Objects.equals(request.getFirstName(), user.getSecUsrFname())) {
            modified = true;
        }
        if (!Objects.equals(request.getLastName(), user.getSecUsrLname())) {
            modified = true;
        }
        boolean passwordChanged = !passwordEncoder.matches(rawPassword, user.getSecUsrPwd());
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
            saved = userRepository.save(user);
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_UPDATE, ex);
        }

        // A changed user type changes the granted authority carried by the session
        // context, and a changed credential invalidates the proof the session was
        // issued against: in both cases the live sessions must stop authorizing. The
        // role change is reported in preference to the credential change because it is
        // the authorization-relevant one.
        if (roleChanged) {
            revokeSessions(userId, REASON_ROLE_CHANGED);
        } else if (passwordChanged) {
            revokeSessions(userId, REASON_CREDENTIAL_CHANGED);
        }

        String message = MSG_USER_PREFIX + userId.trim() + MSG_UPDATED_SUFFIX;
        LOG.info(message);
        return toWriteResponse(saved, message);
    }

    /**
     * :purpose: Delete an existing security user (``COUSR03C`` / ``CU03``): validate the id,
     *     read the current record, and delete it as one unit of work.
     * :param userId: the user id to delete.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the id is empty or the delete fails unexpectedly.
     */
    @Transactional
    public void deleteUser(String userId) {
        if (isBlank(userId)) {
            throw new CardDemoException(MSG_USER_ID_EMPTY);
        }

        SecurityUser user = readUser(userId);

        try {
            userRepository.delete(user);
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_UPDATE, ex);
        }

        revokeSessions(userId, REASON_USER_DELETED);

        LOG.info(MSG_USER_PREFIX + userId.trim() + MSG_DELETED_SUFFIX);
    }
}

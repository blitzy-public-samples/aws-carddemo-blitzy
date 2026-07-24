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
import com.carddemo.common.dto.UserResponseDto;
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

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * :purpose: Construct the service with its collaborators via constructor injection.
     * :param userRepository: Spring Data JPA repository over the ``security_users`` store.
     * :param userMapper: mapper between {@link SecurityUser} entities and the user DTOs.
     * :param passwordEncoder: encoder used to hash and verify user credentials.
     */
    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
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
     *     ordered by user id.
     * :param pageNumber: zero-based page index (page size is ten).
     * :returns: the users on the requested page, or an empty list when the page holds none.
     * :raises CardDemoException: when the ordered browse fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public List<UserResponseDto> listUsers(int pageNumber) {
        Page<SecurityUser> page;
        try {
            page = userRepository.findAllByOrderBySecUsrIdAsc(PageRequest.of(pageNumber, PAGE_SIZE));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        if (page.getContent().isEmpty()) {
            if (pageNumber == 0) {
                LOG.info(MSG_BROWSE_TOP);
            } else {
                LOG.info(MSG_REACHED_BOTTOM);
            }
        }
        return userMapper.toResponseList(page.getContent());
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
    public List<UserResponseDto> pageForward(String lastUserId) {
        List<SecurityUser> next;
        try {
            next = userRepository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(lastUserId, Pageable.ofSize(PAGE_SIZE));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        if (next.isEmpty()) {
            throw new CardDemoException(MSG_ALREADY_BOTTOM);
        }
        return userMapper.toResponseList(next);
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
    public List<UserResponseDto> pageBackward(String firstUserId) {
        List<SecurityUser> previous;
        try {
            previous = userRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(firstUserId, Pageable.ofSize(PAGE_SIZE));
        } catch (DataAccessException ex) {
            throw new CardDemoException(MSG_UNABLE_LOOKUP, ex);
        }
        if (previous.isEmpty()) {
            throw new CardDemoException(MSG_ALREADY_TOP);
        }
        if (previous.size() < PAGE_SIZE) {
            LOG.info(MSG_REACHED_TOP);
        }
        List<SecurityUser> ascending = new ArrayList<>(previous);
        Collections.reverse(ascending);
        return userMapper.toResponseList(ascending);
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
     * :returns: the persisted user projection (id, first name, last name, user type).
     * :raises CardDemoException: when a required field is empty, the user id already
     *     exists, or the insert fails unexpectedly.
     */
    @Transactional
    public UserResponseDto addUser(AddUserRequestDto request, String rawPassword) {
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

        LOG.info(MSG_USER_PREFIX + userId + MSG_ADDED_SUFFIX);
        return userMapper.toResponse(saved);
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
     * :returns: the user projection to pre-fill the update form.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the keyed read fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserResponseDto getUserForUpdate(String userId) {
        UserResponseDto response = userMapper.toResponse(readUser(userId));
        LOG.info(MSG_PRESS_PF5_UPDATE);
        return response;
    }

    /**
     * :purpose: Read a user for the delete screen (``COUSR03C`` read-for-display), surfacing
     *     the delete confirmation prompt.
     * :param userId: the user id to look up.
     * :returns: the user projection to confirm before deletion.
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when the keyed read fails unexpectedly.
     */
    @Transactional(readOnly = true)
    public UserResponseDto getUserForDelete(String userId) {
        UserResponseDto response = userMapper.toResponse(readUser(userId));
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
     * :returns: the updated user projection (id, first name, last name, user type).
     * :raises RecordNotFoundException: when no user exists for ``userId``.
     * :raises CardDemoException: when a required field is empty, no field changed, or the
     *     update fails unexpectedly.
     */
    @Transactional
    public UserResponseDto updateUser(String userId, UpdateUserRequestDto request, String rawPassword) {
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
        if (!Objects.equals(request.getUserType(), user.getSecUsrType())) {
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

        LOG.info(MSG_USER_PREFIX + userId.trim() + MSG_UPDATED_SUFFIX);
        return userMapper.toResponse(saved);
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

        LOG.info(MSG_USER_PREFIX + userId.trim() + MSG_DELETED_SUFFIX);
    }
}

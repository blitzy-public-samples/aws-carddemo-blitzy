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
package com.carddemo.user.controller;

import com.carddemo.common.constant.Messages;
import com.carddemo.common.dto.AddUserRequestDto;
import com.carddemo.common.dto.UpdateUserRequestDto;
import com.carddemo.common.dto.UserListResponseDto;
import com.carddemo.common.dto.UserResponseDto;
import com.carddemo.common.dto.UserWriteResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * :purpose: Administrator-only user-CRUD REST endpoints re-platformed from the CICS
 *     programs ``COUSR00C``-``COUSR03C`` (transactions ``CU00``-``CU03``): list, add,
 *     view, update, and delete. This is a thin controller that delegates every
 *     operation to {@link UserService} and returns the shared user DTOs; every
 *     endpoint is gated to the ``ROLE_ADMIN`` authority.
 */
@RestController
@RequestMapping("/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    /** :purpose: Service collaborator holding all user-management business logic. */
    private final UserService userService;

    /** :purpose: Legacy page-back action (``DFHPF7``), shared vocabulary with COTRN00C. */
    private static final String ACTION_PF7 = "PF7";

    /** :purpose: Legacy page-forward action (``DFHPF8``), shared vocabulary with COTRN00C. */
    private static final String ACTION_PF8 = "PF8";

    /** :purpose: Accepted alias of {@link #ACTION_PF7} retained for existing clients. */
    private static final String ALIAS_BACKWARD = "backward";

    /** :purpose: Accepted alias of {@link #ACTION_PF8} retained for existing clients. */
    private static final String ALIAS_FORWARD = "forward";

    /** :purpose: Row-selection flag transferring to the update program (``COUSR02C``). */
    private static final String SELECTION_UPDATE = "U";

    /**
     * :purpose: Construct the controller with its service collaborator via constructor injection.
     * :param userService: the user-management service holding all business logic.
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * :purpose: List users (10 per page, ascending by id) exactly as ``COUSR00C`` /
     *     ``CU00`` does: keyset paging driven by the legacy ``PF7``/``PF8`` actions with a
     *     ``cursor``, otherwise a browse positioned at ``userId`` or page-based via
     *     ``page``, plus the row-selection handling that the legacy screen uses to reach
     *     the update and delete programs.
     * :param userId: optional browse-start user id, the ``Search User ID`` field
     *     (``USRIDIN``) the legacy screen positions its ``STARTBR`` with (L218-L221).
     *     Honoured only when no paging action is supplied, because ``PF7``/``PF8`` browse
     *     from the COMMAREA cursors rather than from the search field.
     * :param page: zero-based page index for page-based listing; defaults to ``0``.
     * :param direction: optional paging action. The legacy vocabulary is ``"PF7"``
     *     (page back) and ``"PF8"`` (page forward); ``"backward"`` and ``"forward"`` are
     *     accepted aliases. Any other non-blank value is an unrecognised key press.
     * :param cursor: optional user id anchoring keyset paging when ``direction`` is supplied.
     * :param selection: optional row-selection flag, ``U`` (update, ``COUSR02C``) or ``D``
     *     (delete, ``COUSR03C``), matching ``CDEMO-CU00-USR-SEL-FLG``.
     * :param selectedUserId: the user id of the selected row (``CDEMO-CU00-USR-SELECTED``).
     * :returns: the page of users with its paging cursors, the resolved selection, and the
     *     legacy banner when one applies.
     * :raises CardDemoException: when a paging boundary is reached, the paging action is not
     *     a recognised key, the row selection is neither ``U`` nor ``D``, or the ordered
     *     browse fails.
     * :raises RecordNotFoundException: when a selected row names a user that does not exist.
     */
    @GetMapping
    public UserListResponseDto listUsers(
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "selection", required = false) String selection,
            @RequestParam(name = "selectedUserId", required = false) String selectedUserId) {
        UserListResponseDto response = pageUsers(userId, page, direction, cursor);
        applySelection(response, selection, selectedUserId);
        return response;
    }

    /**
     * :purpose: Resolve the requested paging action to the corresponding browse, preserving
     *     the legacy ``PF7``/``PF8`` vocabulary and rejecting an unrecognised action with the
     *     verbatim ``CSMSG01Y`` invalid-key message rather than silently ignoring it.
     * :param userId: the browse-start user id, honoured only in the no-action case.
     * :param page: zero-based page index used when no paging action and no start id are
     *     supplied.
     * :param direction: the requested paging action, possibly ``null``.
     * :param cursor: the user id anchoring keyset paging.
     * :returns: the page of users.
     * :raises CardDemoException: when the paging action is not a recognised key.
     */
    private UserListResponseDto pageUsers(String userId, int page, String direction, String cursor) {
        if (direction == null || direction.isBlank()) {
            return userId == null || userId.isBlank()
                    ? userService.listUsers(page)
                    : userService.listUsersFrom(userId);
        }
        String action = direction.trim();
        boolean forward = ACTION_PF8.equalsIgnoreCase(action) || ALIAS_FORWARD.equalsIgnoreCase(action);
        boolean backward = ACTION_PF7.equalsIgnoreCase(action) || ALIAS_BACKWARD.equalsIgnoreCase(action);
        if (!forward && !backward) {
            throw new CardDemoException(Messages.CCDA_MSG_INVALID_KEY);
        }
        if (cursor == null || cursor.isBlank()) {
            // Without a cursor there is no browse position, so the request degrades to the
            // page-based listing exactly as COUSR00C starts its browse at LOW-VALUES.
            return userService.listUsers(page);
        }
        return forward ? userService.pageForward(cursor) : userService.pageBackward(cursor);
    }

    /**
     * :purpose: Apply the ``COUSR00C`` row selection: validate the selection flag, confirm
     *     the selected user exists, and carry the read-for-display prompt of the program the
     *     legacy screen would have transferred to (``COUSR02C`` for ``U``, ``COUSR03C`` for
     *     ``D``).
     * :param response: the list response to enrich in place.
     * :param selection: the row-selection flag, possibly ``null``.
     * :param selectedUserId: the user id of the selected row.
     * :raises CardDemoException: when the selection is neither ``U`` nor ``D``.
     * :raises RecordNotFoundException: when the selected user does not exist.
     */
    private void applySelection(UserListResponseDto response, String selection, String selectedUserId) {
        if (selection == null || selection.isBlank()) {
            return;
        }
        String action = userService.resolveSelection(selection);
        response.setSelectedAction(action);
        response.setSelectedUserId(selectedUserId == null ? null : selectedUserId.trim());
        UserWriteResponseDto selected = SELECTION_UPDATE.equals(action)
                ? userService.getUserForUpdate(selectedUserId)
                : userService.getUserForDelete(selectedUserId);
        response.setMessage(selected.getMessage());
    }

    /**
     * :purpose: Add a new user.
     * :param request: the new user's id, first name, last name, user type, and raw password.
     * :returns: the created user wrapped in a ``201 Created`` response.
     * :raises CardDemoException: on a duplicate id, an empty field, or a persistence failure.
     * :note: The credential is read from the request body, never from the query string, so it
     *     is not recorded by an access log or a tracing span.
     */
    @PostMapping
    public ResponseEntity<UserWriteResponseDto> addUser(
            @Valid @RequestBody AddUserRequestDto request) {
        UserWriteResponseDto created = userService.addUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * :purpose: Fetch a single user for display.
     * :param id: the user id to look up.
     * :returns: the requested user.
     * :raises RecordNotFoundException: when no user exists for ``id``.
     */
    @GetMapping("/{id}")
    public UserResponseDto getUser(@PathVariable("id") String id) {
        return userService.getUser(id);
    }

    /**
     * :purpose: Update an existing user.
     * :param id: the user id to update.
     * :param request: the new first name, last name, user type, and raw password.
     * :returns: the updated user.
     * :raises RecordNotFoundException: when no user exists for ``id``.
     * :raises CardDemoException: when no field changed, a required field is empty, or the update fails.
     * :note: The credential is read from the request BODY, never from the query string, for
     *     the reason given on {@link #addUser(AddUserRequestDto)}.
     */
    @PutMapping("/{id}")
    public UserWriteResponseDto updateUser(
            @PathVariable("id") String id,
            @Valid @RequestBody UpdateUserRequestDto request) {
        return userService.updateUser(id, request);
    }

    /**
     * :purpose: Delete a user.
     * :param id: the user id to delete.
     * :returns: an empty ``204 No Content`` response.
     * :raises RecordNotFoundException: when no user exists for ``id``.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable("id") String id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}

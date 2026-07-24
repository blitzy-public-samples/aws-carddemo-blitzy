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

import com.carddemo.common.dto.AddUserRequestDto;
import com.carddemo.common.dto.UpdateUserRequestDto;
import com.carddemo.common.dto.UserResponseDto;
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

import java.util.List;

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

    /**
     * :purpose: Construct the controller with its service collaborator via constructor injection.
     * :param userService: the user-management service holding all business logic.
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * :purpose: List users (10 per page, ascending by id); supports PF7-back / PF8-forward
     *     paging via ``direction`` + ``cursor``, else page-based via ``page``.
     * :param page: zero-based page index for page-based listing; defaults to ``0``.
     * :param direction: optional paging direction, ``"forward"`` (PF8) or ``"backward"`` (PF7).
     * :param cursor: optional user id anchoring keyset paging when ``direction`` is supplied.
     * :returns: the users on the requested page.
     * :raises CardDemoException: when a paging boundary is reached or the ordered browse fails.
     */
    @GetMapping
    public List<UserResponseDto> listUsers(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "cursor", required = false) String cursor) {
        if ("forward".equalsIgnoreCase(direction) && cursor != null && !cursor.isBlank()) {
            return userService.pageForward(cursor);
        }
        if ("backward".equalsIgnoreCase(direction) && cursor != null && !cursor.isBlank()) {
            return userService.pageBackward(cursor);
        }
        return userService.listUsers(page);
    }

    /**
     * :purpose: Add a new user.
     * :param request: the new user's id, first name, last name, and user type.
     * :param password: the raw password to encode, supplied separately from the request body.
     * :returns: the created user wrapped in a ``201 Created`` response.
     * :raises CardDemoException: on a duplicate id, an empty field, or a persistence failure.
     */
    @PostMapping
    public ResponseEntity<UserResponseDto> addUser(
            @Valid @RequestBody AddUserRequestDto request,
            @RequestParam(name = "password", required = false) String password) {
        UserResponseDto created = userService.addUser(request, password);
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
     * :param request: the new first name, last name, and user type.
     * :param password: the raw password to verify and re-encode, supplied separately from the request body.
     * :returns: the updated user.
     * :raises RecordNotFoundException: when no user exists for ``id``.
     * :raises CardDemoException: when no field changed, a required field is empty, or the update fails.
     */
    @PutMapping("/{id}")
    public UserResponseDto updateUser(
            @PathVariable("id") String id,
            @Valid @RequestBody UpdateUserRequestDto request,
            @RequestParam(name = "password", required = false) String password) {
        return userService.updateUser(id, request, password);
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

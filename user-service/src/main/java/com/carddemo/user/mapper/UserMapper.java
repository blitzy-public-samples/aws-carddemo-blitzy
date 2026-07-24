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
package com.carddemo.user.mapper;

import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.AddUserRequestDto;
import com.carddemo.common.dto.UpdateUserRequestDto;
import com.carddemo.common.dto.UserResponseDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * :purpose: Maps the shared {@link SecurityUser} entity to and from the shared user
 *     request and response DTOs, re-expressing the field-move logic of the legacy
 *     administrator-only user-CRUD programs ``COUSR00C``/``COUSR01C``/``COUSR02C``/
 *     ``COUSR03C`` (CICS transactions ``CU00``-``CU03``). It copies only the
 *     non-secret fields (id, first name, last name, user type) and never reads or
 *     writes the password hash, so the encoded credential never leaves the server.
 * :output: New or mutated {@code SecurityUser} entities and {@code UserResponseDto}
 *     instances carrying the id, first name, last name, and raw user-type code.
 */
@Component
public class UserMapper {

    /**
     * :purpose: Build a new {@link SecurityUser} entity from an add-user request,
     *     re-expressing the ``COUSR01C`` add field-moves (id/first-name/last-name/
     *     type). The password is intentionally left unset; the service encodes and
     *     assigns it before persistence.
     * :param request: the add-user request carrying the entered user id, first name,
     *     last name, and user type.
     * :returns: a new {@code SecurityUser} populated with id, first name, last name,
     *     and user type, or ``null`` when {@code request} is ``null``.
     */
    public SecurityUser toEntity(AddUserRequestDto request) {
        if (request == null) {
            return null;
        }
        SecurityUser user = new SecurityUser();
        user.setSecUsrId(request.getUserId());
        user.setSecUsrFname(request.getFirstName());
        user.setSecUsrLname(request.getLastName());
        user.setSecUsrType(request.getUserType());
        return user;
    }

    /**
     * :purpose: Apply an update-user request to an existing managed {@link SecurityUser},
     *     re-expressing the ``COUSR02C`` update field-moves. Mutates first name, last
     *     name, and user type in place. The primary-key user id is the lookup key and
     *     is never changed, and the password is left to the service to re-encode.
     * :param request: the update-user request carrying the new first name, last name,
     *     and user type.
     * :param user: the existing managed entity to update in place.
     */
    public void apply(UpdateUserRequestDto request, SecurityUser user) {
        if (request == null || user == null) {
            return;
        }
        user.setSecUsrFname(request.getFirstName());
        user.setSecUsrLname(request.getLastName());
        user.setSecUsrType(request.getUserType());
    }

    /**
     * :purpose: Convert a {@link SecurityUser} entity to its response DTO for display,
     *     re-expressing the ``COUSR02C``/``COUSR03C`` read-for-display field-moves. The
     *     password is never read and never placed on the response.
     * :param user: the security-user entity to convert.
     * :returns: a {@code UserResponseDto} carrying the id, first name, last name, and
     *     user type, or ``null`` when {@code user} is ``null``.
     */
    public UserResponseDto toResponse(SecurityUser user) {
        if (user == null) {
            return null;
        }
        UserResponseDto response = new UserResponseDto();
        response.setUserId(user.getSecUsrId());
        response.setFirstName(user.getSecUsrFname());
        response.setLastName(user.getSecUsrLname());
        response.setUserType(user.getSecUsrType());
        return response;
    }

    /**
     * :purpose: Convert a list of {@link SecurityUser} entities to response DTOs,
     *     re-expressing the ``COUSR00C`` per-row list field-moves (id/first-name/
     *     last-name/type, no password). Paging is a service/repository concern and is
     *     not performed here.
     * :param users: the security-user entities to convert; may be ``null``.
     * :returns: a new list of response DTOs, empty when {@code users} is ``null`` or
     *     empty.
     */
    public List<UserResponseDto> toResponseList(List<SecurityUser> users) {
        List<UserResponseDto> responses = new ArrayList<>();
        if (users == null) {
            return responses;
        }
        for (SecurityUser user : users) {
            responses.add(toResponse(user));
        }
        return responses;
    }
}

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
package com.carddemo.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * :purpose: Inbound DTO for the update-user screen (COUSR02C, CICS CU02). Carries the
 *     editable profile fields plus the raw password the service verifies and re-encodes;
 *     the user id is the lookup key supplied in the path and is never changed here.
 * :output: A mutable carrier of the first name, last name, user type, and raw password.
 * :note: Every field width mirrors the ``CSUSR01Y`` record layout so an oversized value is
 *     refused with HTTP 400 rather than reaching the column. Presence edits stay in the
 *     service, preserving the verbatim ``COUSR02C`` literals and their order.
 * :note: The password travels in the request BODY and is write-only. It must never be
 *     accepted as a query parameter, because a URL is recorded verbatim by access logs and
 *     by tracing spans (CWE-598).
 */
public class UpdateUserRequestDto {

    /** :purpose: the new first name (``SEC-USR-FNAME`` ``PIC X(20)``). */
    @Size(max = 20, message = "First Name must be at most 20 characters")
    private String firstName;

    /** :purpose: the new last name (``SEC-USR-LNAME`` ``PIC X(20)``). */
    @Size(max = 20, message = "Last Name must be at most 20 characters")
    private String lastName;

    /** :purpose: the new user type code 'A'/'U' (``SEC-USR-TYPE`` ``PIC X(01)``). */
    @Size(max = 1, message = "User Type must be at most 1 character")
    private String userType;

    /**
     * :purpose: the entered raw password (``SEC-USR-PWD`` ``PIC X(08)``), compared against
     *     the stored hash and re-encoded only when it changes; never returned on a response.
     * :note: OPTIONAL. An absent or blank value means "leave the stored credential as it
     *     is". ``COUSR02C`` re-displayed the plaintext credential in ``PASSWDO`` on the
     *     ENTER turn, so its ``PASSWDI`` was always populated by the time PF5 ran and its
     *     field-by-field compare then found it equal; the hash is deliberately never sent
     *     to a client here, so an empty field is the same statement of intent.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 8, message = "Password must be at most 8 characters")
    private String password;

    /**
     * :purpose: Create an empty UpdateUserRequestDto. Required for JSON (Jackson) serialization.
     */
    public UpdateUserRequestDto() {
    }

    /**
     * :purpose: Return the new first name (``SEC-USR-FNAME``).
     * :output: the ``firstName`` value.
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * :purpose: Set the new first name (``SEC-USR-FNAME``).
     * :param firstName: the ``firstName`` value.
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * :purpose: Return the new last name (``SEC-USR-LNAME``).
     * :output: the ``lastName`` value.
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * :purpose: Set the new last name (``SEC-USR-LNAME``).
     * :param lastName: the ``lastName`` value.
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * :purpose: Return the new user type code 'A'/'U' (``SEC-USR-TYPE``).
     * :output: the ``userType`` value.
     */
    public String getUserType() {
        return userType;
    }

    /**
     * :purpose: Set the new user type code 'A'/'U' (``SEC-USR-TYPE``).
     * :param userType: the ``userType`` value.
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }

    /**
     * :purpose: Return the entered raw password (``SEC-USR-PWD``).
     * :output: the ``password`` value; ``null`` when the caller supplied none.
     */
    public String getPassword() {
        return password;
    }

    /**
     * :purpose: Set the entered raw password (``SEC-USR-PWD``).
     * :param password: the ``password`` value.
     */
    public void setPassword(String password) {
        this.password = password;
    }

}

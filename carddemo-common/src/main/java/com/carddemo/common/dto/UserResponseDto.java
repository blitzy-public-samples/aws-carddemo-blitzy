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

/**
 * :purpose: Outbound DTO for the administrator user screens (COUSR00C/COUSR01C/COUSR02C/COUSR03C, CICS CU00-CU03). Carries only the non-secret user fields; the encoded password is never surfaced.
 * :output: A mutable carrier of the user id, first name, last name, and user type.
 */
public class UserResponseDto {

    /** :purpose: the user id (``SEC-USR-ID``). */
    private String userId;

    /** :purpose: the user first name (``SEC-USR-FNAME``). */
    private String firstName;

    /** :purpose: the user last name (``SEC-USR-LNAME``). */
    private String lastName;

    /** :purpose: the raw user type code 'A'/'U' (``SEC-USR-TYPE``). */
    private String userType;

    /**
     * :purpose: Create an empty UserResponseDto. Required for JSON (Jackson) serialization.
     */
    public UserResponseDto() {
    }

    /**
     * :purpose: Return the user id (``SEC-USR-ID``).
     * :output: the ``userId`` value.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * :purpose: Set the user id (``SEC-USR-ID``).
     * :param userId: the ``userId`` value.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * :purpose: Return the user first name (``SEC-USR-FNAME``).
     * :output: the ``firstName`` value.
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * :purpose: Set the user first name (``SEC-USR-FNAME``).
     * :param firstName: the ``firstName`` value.
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * :purpose: Return the user last name (``SEC-USR-LNAME``).
     * :output: the ``lastName`` value.
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * :purpose: Set the user last name (``SEC-USR-LNAME``).
     * :param lastName: the ``lastName`` value.
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * :purpose: Return the raw user type code 'A'/'U' (``SEC-USR-TYPE``).
     * :output: the ``userType`` value.
     */
    public String getUserType() {
        return userType;
    }

    /**
     * :purpose: Set the raw user type code 'A'/'U' (``SEC-USR-TYPE``).
     * :param userType: the ``userType`` value.
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }

}

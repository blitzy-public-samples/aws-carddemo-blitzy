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
 * :purpose: Carry the outcome of an administrator user write together with the
 *     user-facing message the legacy screen displays. ``COUSR01C`` reports
 *     ``'User <id> has been added ...'``, ``COUSR02C`` reports
 *     ``'User <id> has been updated ...'`` and its read-for-display reports
 *     ``'Press PF5 key to save your updates ...'``, and ``COUSR03C``'s read-for-display
 *     reports ``'Press PF5 key to delete this user ...'``; each is moved to
 *     ``ERRMSGO`` on the corresponding mapset.
 * :output: A JSON object of ``{userId, firstName, lastName, userType, message}``.
 * :note: Kept separate from {@link UserResponseDto} so the read contracts
 *     ``GET /users/{id}`` and the user-list rows keep exactly the four projected
 *     fields and gain no message property.
 */
public class UserWriteResponseDto {

    /** :purpose: The affected user id (``SEC-USR-ID``). */
    private String userId;

    /** :purpose: The user's first name (``SEC-USR-FNAME``). */
    private String firstName;

    /** :purpose: The user's last name (``SEC-USR-LNAME``). */
    private String lastName;

    /** :purpose: The user's type (``SEC-USR-TYPE``): ``A`` administrator or ``U`` user. */
    private String userType;

    /** :purpose: The verbatim legacy outcome or prompt message. */
    private String message;

    /**
     * :purpose: Build an empty response for framework deserialization.
     */
    public UserWriteResponseDto() {
    }

    /**
     * :purpose: Build a fully populated write response.
     * :param userId: the affected user id.
     * :param firstName: the user's first name.
     * :param lastName: the user's last name.
     * :param userType: the user's type.
     * :param message: the verbatim legacy outcome or prompt message.
     */
    public UserWriteResponseDto(String userId, String firstName, String lastName,
                                String userType, String message) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.userType = userType;
        this.message = message;
    }

    /**
     * :purpose: Read the affected user id.
     * :output: the user id.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * :purpose: Set the affected user id.
     * :param userId: the user id.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * :purpose: Read the user's first name.
     * :output: the first name.
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * :purpose: Set the user's first name.
     * :param firstName: the first name.
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * :purpose: Read the user's last name.
     * :output: the last name.
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * :purpose: Set the user's last name.
     * :param lastName: the last name.
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * :purpose: Read the user's type.
     * :output: ``A`` or ``U``.
     */
    public String getUserType() {
        return userType;
    }

    /**
     * :purpose: Set the user's type.
     * :param userType: ``A`` or ``U``.
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }

    /**
     * :purpose: Read the verbatim legacy message.
     * :output: the message text.
     */
    public String getMessage() {
        return message;
    }

    /**
     * :purpose: Set the verbatim legacy message.
     * :param message: the message text.
     */
    public void setMessage(String message) {
        this.message = message;
    }
}

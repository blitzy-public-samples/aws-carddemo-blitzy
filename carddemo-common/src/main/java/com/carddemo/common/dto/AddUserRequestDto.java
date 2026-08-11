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

import com.carddemo.common.validation.SingleByteText;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * :purpose: Inbound DTO for the add-user screen (COUSR01C, CICS CU01). Carries the entered
 *     user id, the non-secret profile fields, and the raw password the service encodes before
 *     it is persisted.
 * :output: A mutable carrier of the user id, first name, last name, user type, and raw
 *     password.
 * :note: Every field width mirrors the ``CSUSR01Y`` record layout, so a value that could
 *     never have been keyed into the 3270 map is refused with HTTP 400 instead of reaching the
 *     column and failing as a server error. The presence edits stay in the service so the
 *     verbatim ``COUSR01C`` "can NOT be empty..." literals keep their legacy order --
 *     ``@Size(max = ...)`` never fires for an absent or blank value.
 * :note: The password travels in the request BODY and is write-only: it is never echoed on
 *     any response DTO. It must never be accepted as a query parameter, because a URL is
 *     recorded verbatim by access logs and by client/server tracing spans (CWE-598).
 */
/*
 * Boundary encoding guard. The fields of this request are persisted and then
 * rendered into the 80-byte USRSEC record (CSUSR01Y),
 * every one of which is a BYTE-width contract a downstream consumer parses by
 * offset. Text that needs more than one byte per character therefore cannot
 * survive that rendering intact - it either loses the character or shifts every
 * following field - so it is refused here, at the only point where the operator
 * can still be told which field to correct.
 */
@SingleByteText
public class AddUserRequestDto {

    /**
     * :purpose: the entered user id (``SEC-USR-ID`` ``PIC X(08)``).
     * :note: The character set is restricted to letters and digits, which is what an EBCDIC
     *     3270 terminal could key into this field. Without it an administrator could create
     *     the id ``U+0410 CYRILLIC CAPITAL A`` + ``DMIN001``: a distinct row that signs on
     *     with ``ROLE_ADMIN`` yet renders identically to the legitimate ``ADMIN001`` in every
     *     log line, dashboard panel and report, because the audit trail keys on the user id
     *     alone (CWE-1007). The pattern tolerates an absent, empty or all-blank value so the
     *     verbatim ``COUSR01C`` presence edit in the service still owns that case and keeps
     *     its legacy literal; ``@Size`` still owns anything longer than the field width.
     */
    @Size(max = 8, message = "User ID must be at most 8 characters")
    @Pattern(regexp = "\\s*[A-Za-z0-9]{0,8}\\s*",
             message = "User ID must contain only letters and digits")
    private String userId;

    /** :purpose: the entered first name (``SEC-USR-FNAME`` ``PIC X(20)``). */
    @Size(max = 20, message = "First Name must be at most 20 characters")
    private String firstName;

    /** :purpose: the entered last name (``SEC-USR-LNAME`` ``PIC X(20)``). */
    @Size(max = 20, message = "Last Name must be at most 20 characters")
    private String lastName;

    /** :purpose: the entered user type code 'A'/'U' (``SEC-USR-TYPE`` ``PIC X(01)``). */
    @Size(max = 1, message = "User Type must be at most 1 character")
    private String userType;

    /**
     * :purpose: the entered raw password (``SEC-USR-PWD`` ``PIC X(08)``), encoded by the
     *     service before persistence and never returned on a response.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 8, message = "Password must be at most 8 characters")
    private String password;

    /**
     * :purpose: Create an empty AddUserRequestDto. Required for JSON (Jackson) serialization.
     */
    public AddUserRequestDto() {
    }

    /**
     * :purpose: Return the entered user id (``SEC-USR-ID``).
     * :output: the ``userId`` value.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * :purpose: Set the entered user id (``SEC-USR-ID``).
     * :param userId: the ``userId`` value.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * :purpose: Return the entered first name (``SEC-USR-FNAME``).
     * :output: the ``firstName`` value.
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * :purpose: Set the entered first name (``SEC-USR-FNAME``).
     * :param firstName: the ``firstName`` value.
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * :purpose: Return the entered last name (``SEC-USR-LNAME``).
     * :output: the ``lastName`` value.
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * :purpose: Set the entered last name (``SEC-USR-LNAME``).
     * :param lastName: the ``lastName`` value.
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * :purpose: Return the entered user type code 'A'/'U' (``SEC-USR-TYPE``).
     * :output: the ``userType`` value.
     */
    public String getUserType() {
        return userType;
    }

    /**
     * :purpose: Set the entered user type code 'A'/'U' (``SEC-USR-TYPE``).
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

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
package com.carddemo.dto.response;

import com.carddemo.entity.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Summary Data Transfer Object for list display.
 * 
 * <p>This DTO provides a lightweight representation of user information suitable for
 * list views and table displays, specifically designed to support pagination with
 * 10 users per page as per the original COBOL display pattern.</p>
 * 
 * <h3>COBOL Source Mapping</h3>
 * <p>Transforms the COBOL SEC-USER-DATA structure from CSUSR01Y.cpy copybook:</p>
 * <ul>
 *   <li>SEC-USR-ID (PIC X(08)) → userId (String)</li>
 *   <li>SEC-USR-FNAME (PIC X(20)) → firstName (String)</li>
 *   <li>SEC-USR-LNAME (PIC X(20)) → lastName (String)</li>
 *   <li>SEC-USR-TYPE (PIC X(01)) → userType (String) - 'A' for Admin, 'U' for User</li>
 *   <li>SEC-USR-PWD (PIC X(08)) → EXPLICITLY EXCLUDED for security</li>
 * </ul>
 * 
 * <h3>Security Compliance</h3>
 * <p>This DTO implements critical security measures to prevent SEC-USR-PWD exposure:</p>
 * <ul>
 *   <li><b>No password field</b>: Password is completely excluded from the DTO structure</li>
 *   <li><b>API response protection</b>: Prevents password leakage in JSON responses</li>
 *   <li><b>Browser console safety</b>: Ensures passwords never appear in client-side logs</li>
 *   <li><b>Client storage protection</b>: Prevents password caching in browser storage</li>
 * </ul>
 * 
 * <h3>Usage Context</h3>
 * <p>This DTO serves as the element type for {@code UserListResponse.users} list,
 * replacing the COBOL array display pattern (USER-REC OCCURS 10 TIMES from COUSR00C.cbl)
 * with a JSON array of summary objects. Used by React UserListComponent for table
 * row rendering with user selection actions:</p>
 * <ul>
 *   <li>'U' selection: Routes to user update service method</li>
 *   <li>'D' selection: Routes to user delete service method</li>
 * </ul>
 * 
 * <h3>Field Mapping Details</h3>
 * <p>The COBOL program COUSR00C displays user records with these fields:</p>
 * <ul>
 *   <li>USER-ID (PIC X(08)): Maps directly to userId</li>
 *   <li>USER-NAME (PIC X(25)): Combined display field; source separated into firstName and lastName</li>
 *   <li>USER-TYPE (PIC X(08)): Formatted type description; source is single character code</li>
 * </ul>
 * 
 * @see com.carddemo.entity.User
 * @see com.carddemo.dto.response.UserListResponse
 * @see com.carddemo.service.user.UserListService
 * @since CardDemo v1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserSummaryDTO {

    /**
     * User identifier from SEC-USR-ID field.
     * 
     * <p>Corresponds to COBOL field: SEC-USR-ID PIC X(08)</p>
     * <p>Maximum length: 8 characters</p>
     * <p>Required field for user identification and selection actions.</p>
     */
    @JsonProperty("userId")
    private String userId;

    /**
     * User's first name from SEC-USR-FNAME field.
     * 
     * <p>Corresponds to COBOL field: SEC-USR-FNAME PIC X(20)</p>
     * <p>Maximum length: 20 characters</p>
     * <p>Combined with lastName in COBOL display as USER-NAME (PIC X(25)).</p>
     */
    @JsonProperty("firstName")
    private String firstName;

    /**
     * User's last name from SEC-USR-LNAME field.
     * 
     * <p>Corresponds to COBOL field: SEC-USR-LNAME PIC X(20)</p>
     * <p>Maximum length: 20 characters</p>
     * <p>Combined with firstName in COBOL display as USER-NAME (PIC X(25)).</p>
     */
    @JsonProperty("lastName")
    private String lastName;

    /**
     * User type indicator from SEC-USR-TYPE field.
     * 
     * <p>Corresponds to COBOL field: SEC-USR-TYPE PIC X(01)</p>
     * <p>Valid values:</p>
     * <ul>
     *   <li>'A' - Administrative user with full system access</li>
     *   <li>'U' - Regular user with limited access permissions</li>
     * </ul>
     * 
     * <p>In COBOL display (COUSR00C.cbl), this is expanded to USER-TYPE PIC X(08)
     * showing formatted descriptions like "Admin" or "User".</p>
     * 
     * <p>This field directly supports role-based access control and determines
     * available menu options and operations for the user.</p>
     */
    @JsonProperty("userType")
    private String userType;

    /**
     * Static factory method to convert User entity to UserSummaryDTO.
     * 
     * <p>This method performs the transformation from the full User entity to a
     * lightweight summary DTO, explicitly excluding the password field for security.
     * This prevents SEC-USR-PWD exposure in API responses, browser console logs,
     * and client-side storage.</p>
     * 
     * <h4>Security Rationale</h4>
     * <p>Password exclusion is critical for preventing:</p>
     * <ul>
     *   <li>Password exposure in REST API JSON responses</li>
     *   <li>Password leakage through browser developer console</li>
     *   <li>Password caching in browser local/session storage</li>
     *   <li>Password visibility in network traffic monitoring</li>
     *   <li>Password logging in application or web server logs</li>
     * </ul>
     * 
     * <h4>COBOL Transformation Pattern</h4>
     * <p>Replaces COBOL data movement pattern:</p>
     * <pre>
     * MOVE SEC-USR-ID TO USER-ID(WS-IDX)
     * STRING SEC-USR-FNAME DELIMITED BY SPACE
     *        ' '
     *        SEC-USR-LNAME DELIMITED BY SPACE
     *        INTO USER-NAME(WS-IDX)
     * MOVE SEC-USR-TYPE TO USER-TYPE(WS-IDX)
     * * Password explicitly NOT moved for security
     * </pre>
     * 
     * <h4>Usage Example</h4>
     * <pre>
     * User user = userRepository.findById(userId)
     *     .orElseThrow(() -&gt; new ResourceNotFoundException("User not found"));
     * UserSummaryDTO summaryDTO = UserSummaryDTO.fromUser(user);
     * // summaryDTO contains all user info EXCEPT password
     * </pre>
     * 
     * @param user the User entity to convert (must not be null)
     * @return UserSummaryDTO containing user summary information without password
     * @throws NullPointerException if user parameter is null
     * @see com.carddemo.entity.User
     */
    public static UserSummaryDTO fromUser(User user) {
        if (user == null) {
            throw new NullPointerException("User entity cannot be null for DTO conversion");
        }

        return UserSummaryDTO.builder()
                .userId(user.getUserId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .userType(user.getUserType().getDisplayName())
                .build();
        // Password field explicitly excluded - never include SEC-USR-PWD in response DTOs
    }

    /**
     * Gets the user identifier.
     * 
     * @return user ID (8 characters max)
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Gets the user's first name.
     * 
     * @return first name (20 characters max)
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Gets the user's last name.
     * 
     * @return last name (20 characters max)
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Gets the user type indicator.
     * 
     * @return user type ('A' for Admin, 'U' for User)
     */
    public String getUserType() {
        return userType;
    }
}

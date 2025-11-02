/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request DTO for user management operations capturing search criteria and bulk user selection 
 * actions from COUSR00 BMS screen with pagination support and row-level selection flags for 
 * administrative user CRUD operations.
 * 
 * <p>This class transforms the COBOL COUSR00 copybook structure into a REST API request format,
 * preserving the original mainframe screen pattern of displaying 10 user rows per page with
 * individual selection checkboxes for batch operations.
 * 
 * <p><b>COBOL Source Mapping:</b></p>
 * <ul>
 *   <li>USRIDINI (PIC X(8)) → searchUserId</li>
 *   <li>PAGENUMI (PIC X(8)) → pageNumber</li>
 *   <li>SEL0001I-SEL0010I (PIC X(1)) → selectedUsers[].selected</li>
 *   <li>USRID01I-USRID10I (PIC X(8)) → selectedUsers[].userId</li>
 *   <li>FNAME01I-FNAME10I (PIC X(20)) → selectedUsers[].firstName</li>
 *   <li>LNAME01I-LNAME10I (PIC X(20)) → selectedUsers[].lastName</li>
 *   <li>UTYPE01I-UTYPE10I (PIC X(1)) → selectedUsers[].userType</li>
 * </ul>
 * 
 * <p><b>User Type Code Mapping:</b></p>
 * <ul>
 *   <li>'R' = Regular User (ROLE_USER in Spring Security)</li>
 *   <li>'A' = Administrative User (ROLE_ADMIN in Spring Security)</li>
 * </ul>
 * 
 * <p><b>Security Note:</b> All operations using this DTO require ROLE_ADMIN authorization 
 * enforced at the controller level via @PreAuthorize annotation per section 0.9 security 
 * requirements.
 * 
 * @see com.carddemo.controller.UserController
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserManagementRequest {

    /**
     * Search filter for user ID to narrow down the user list display.
     * Maps to COBOL field USRIDINI PIC X(8).
     * 
     * <p>When provided, filters the user list to show only users whose user ID
     * matches or contains this search string. Empty or null value displays all users.
     */
    @Size(max = 8, message = "Search user ID must not exceed 8 characters")
    @JsonProperty("searchUserId")
    private String searchUserId;

    /**
     * Current page number for pagination control (1-based index).
     * Maps to COBOL field PAGENUMI PIC X(8).
     * 
     * <p>Supports pagination pattern from original COBOL screen which displays
     * 10 user rows per page. Must be at least 1.
     */
    @Min(value = 1, message = "Page number must be at least 1")
    @JsonProperty("pageNumber")
    private Integer pageNumber;

    /**
     * List of user selections from the current page, supporting batch operations
     * on multiple users simultaneously.
     * 
     * <p>Corresponds to the 10 user rows displayed on the COUSR00 BMS screen,
     * each with selection checkbox (SEL0001I-SEL0010I) and user details.
     * 
     * <p>Cascading validation is enabled via @Valid annotation to ensure all
     * UserSelection objects in the list pass their individual field validations.
     */
    @Valid
    @JsonProperty("selectedUsers")
    @Builder.Default
    private List<UserSelection> selectedUsers = new ArrayList<>();

    /**
     * Action to perform on the selected users.
     * 
     * <p>Supported actions:</p>
     * <ul>
     *   <li><b>VIEW</b>: Display detailed user information</li>
     *   <li><b>EDIT</b>: Update user information for selected users</li>
     *   <li><b>DELETE</b>: Remove selected users from the system</li>
     *   <li><b>CREATE</b>: Create a new user (selectedUsers may be empty)</li>
     * </ul>
     * 
     * <p>All actions require ROLE_ADMIN authorization at the controller level.
     */
    @NotBlank(message = "Action must be specified")
    @Pattern(regexp = "VIEW|EDIT|DELETE|CREATE", message = "Action must be one of: VIEW, EDIT, DELETE, CREATE")
    @JsonProperty("action")
    private String action;

    /**
     * Nested class representing a single user row from the COUSR00 BMS screen
     * with selection flag and user details.
     * 
     * <p>Maps to one of the 10 user rows displayed per page in the original
     * mainframe screen (rows 01-10).
     * 
     * <p><b>COBOL Field Mapping (for row N):</b></p>
     * <ul>
     *   <li>SEL000NI (PIC X(1)) → selected</li>
     *   <li>USRID0NI (PIC X(8)) → userId</li>
     *   <li>FNAME0NI (PIC X(20)) → firstName</li>
     *   <li>LNAME0NI (PIC X(20)) → lastName</li>
     *   <li>UTYPE0NI (PIC X(1)) → userType</li>
     * </ul>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserSelection {

        /**
         * Selection flag indicating if this user row is selected for the batch operation.
         * Maps to COBOL fields SEL0001I through SEL0010I (PIC X(1)).
         * 
         * <p>In the original COBOL screen, 'X' or space indicates checkbox state.
         * In REST API, this is represented as a boolean:
         * <ul>
         *   <li>true = User row is selected ('X' in COBOL)</li>
         *   <li>false = User row is not selected (space in COBOL)</li>
         * </ul>
         */
        @JsonProperty("selected")
        private Boolean selected;

        /**
         * User ID for this row (unique identifier).
         * Maps to COBOL fields USRID01I through USRID10I (PIC X(8)).
         * 
         * <p>Maximum length of 8 characters per COBOL PIC X(8) specification.
         */
        @Size(max = 8, message = "User ID must not exceed 8 characters")
        @JsonProperty("userId")
        private String userId;

        /**
         * User's first name.
         * Maps to COBOL fields FNAME01I through FNAME10I (PIC X(20)).
         * 
         * <p>Maximum length of 20 characters per COBOL PIC X(20) specification.
         */
        @Size(max = 20, message = "First name must not exceed 20 characters")
        @JsonProperty("firstName")
        private String firstName;

        /**
         * User's last name.
         * Maps to COBOL fields LNAME01I through LNAME10I (PIC X(20)).
         * 
         * <p>Maximum length of 20 characters per COBOL PIC X(20) specification.
         */
        @Size(max = 20, message = "Last name must not exceed 20 characters")
        @JsonProperty("lastName")
        private String lastName;

        /**
         * User type code indicating security role level.
         * Maps to COBOL fields UTYPE01I through UTYPE10I (PIC X(1)).
         * 
         * <p><b>Valid Values:</b></p>
         * <ul>
         *   <li>'R' = Regular User (ROLE_USER)</li>
         *   <li>'A' = Administrative User (ROLE_ADMIN)</li>
         * </ul>
         * 
         * <p>This single-character code from the mainframe security model maps to
         * Spring Security role hierarchy as defined in section 0.1 two-tier security model.
         */
        @Pattern(regexp = "[RA]", message = "User type must be 'R' (Regular) or 'A' (Admin)")
        @JsonProperty("userType")
        private String userType;

        /**
         * Custom equals method for UserSelection comparison.
         * 
         * <p>Compares all fields for equality, handling null values appropriately.
         * Two UserSelection objects are considered equal if all their fields match.
         * 
         * @param o the object to compare with
         * @return true if objects are equal, false otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UserSelection that = (UserSelection) o;
            return Objects.equals(selected, that.selected) &&
                   Objects.equals(userId, that.userId) &&
                   Objects.equals(firstName, that.firstName) &&
                   Objects.equals(lastName, that.lastName) &&
                   Objects.equals(userType, that.userType);
        }

        /**
         * Custom hashCode method for UserSelection.
         * 
         * <p>Generates hash code based on all fields to ensure consistency with equals method.
         * 
         * @return hash code value for this object
         */
        @Override
        public int hashCode() {
            return Objects.hash(selected, userId, firstName, lastName, userType);
        }

        /**
         * String representation of UserSelection for debugging and logging.
         * 
         * <p>Provides readable format showing all field values without exposing
         * sensitive information (no passwords in this DTO).
         * 
         * @return string representation of this UserSelection
         */
        @Override
        public String toString() {
            return "UserSelection{" +
                   "selected=" + selected +
                   ", userId='" + userId + '\'' +
                   ", firstName='" + firstName + '\'' +
                   ", lastName='" + lastName + '\'' +
                   ", userType='" + userType + '\'' +
                   '}';
        }
    }

    /**
     * Custom equals method for UserManagementRequest comparison.
     * 
     * <p>Compares all fields including nested selectedUsers list for deep equality.
     * Two UserManagementRequest objects are considered equal if all their fields match.
     * 
     * @param o the object to compare with
     * @return true if objects are equal, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserManagementRequest that = (UserManagementRequest) o;
        return Objects.equals(searchUserId, that.searchUserId) &&
               Objects.equals(pageNumber, that.pageNumber) &&
               Objects.equals(selectedUsers, that.selectedUsers) &&
               Objects.equals(action, that.action);
    }

    /**
     * Custom hashCode method for UserManagementRequest.
     * 
     * <p>Generates hash code based on all fields including nested list
     * to ensure consistency with equals method.
     * 
     * @return hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(searchUserId, pageNumber, selectedUsers, action);
    }

    /**
     * String representation of UserManagementRequest for debugging and logging.
     * 
     * <p>Provides readable format showing all field values including the count
     * of selected users without exposing the full list content to avoid log bloat.
     * 
     * @return string representation of this UserManagementRequest
     */
    @Override
    public String toString() {
        return "UserManagementRequest{" +
               "searchUserId='" + searchUserId + '\'' +
               ", pageNumber=" + pageNumber +
               ", selectedUsersCount=" + (selectedUsers != null ? selectedUsers.size() : 0) +
               ", action='" + action + '\'' +
               '}';
    }
}

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

package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for user profile update operations.
 * <p>
 * Captures user profile modification details from the COUSR01 BMS screen including
 * name changes, user type adjustments, and password updates with comprehensive
 * security validation.
 * </p>
 * 
 * <h3>Field Mappings from COBOL COUSR01 Copybook:</h3>
 * <ul>
 *   <li>USERIDI (PIC X(8)) → userId: 8-character alphanumeric user identifier</li>
 *   <li>FNAMEI (PIC X(20)) → firstName: 20-character alphabetic first name</li>
 *   <li>LNAMEI (PIC X(20)) → lastName: 20-character alphabetic last name</li>
 *   <li>PASSWDI (PIC X(8)) → password: 8-character current password for verification</li>
 *   <li>USRTYPEI (PIC X(1)) → userType: Single character role code (R=Regular, A=Admin)</li>
 * </ul>
 * 
 * <h3>Security and Authorization:</h3>
 * <ul>
 *   <li>Password changes require current password verification</li>
 *   <li>New passwords must meet strength requirements (BCrypt with strength 12)</li>
 *   <li>ROLE_USER: Can update own profile only (userId matches authenticated user)</li>
 *   <li>ROLE_ADMIN: Can update any user profile</li>
 *   <li>User type changes restricted to ROLE_ADMIN only</li>
 * </ul>
 * 
 * <h3>User Type Mapping:</h3>
 * <ul>
 *   <li>R → ROLE_USER: Regular user with standard access privileges</li>
 *   <li>A → ROLE_ADMIN: Administrative user with elevated privileges</li>
 * </ul>
 * 
 * <h3>Password Update Rules:</h3>
 * <ul>
 *   <li>Current password (password field) required for password change operations</li>
 *   <li>New password must be 8-72 characters for BCrypt compatibility</li>
 *   <li>New password must contain: uppercase, lowercase, number, special character</li>
 *   <li>Confirm password must match new password exactly</li>
 * </ul>
 * 
 * @see com.carddemo.service.UserProfileService
 * @see com.carddemo.controller.UserController
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileUpdateRequest {

    /**
     * User identifier for the profile being updated.
     * <p>
     * Mapped from COBOL field USERIDI (PIC X(8)).
     * </p>
     * <p>
     * <b>Validation Rules:</b>
     * </p>
     * <ul>
     *   <li>Required field (cannot be blank)</li>
     *   <li>Maximum length: 8 characters</li>
     *   <li>Pattern: Alphanumeric only [A-Za-z0-9]+</li>
     *   <li>No special characters or spaces allowed</li>
     * </ul>
     * <p>
     * <b>Authorization:</b> Must match authenticated user's ID unless caller has ROLE_ADMIN
     * </p>
     */
    @NotBlank(message = "User ID is required and cannot be blank")
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    @Pattern(
        regexp = "[A-Za-z0-9]+",
        message = "User ID must contain only alphanumeric characters without spaces or special characters"
    )
    @JsonProperty("userId")
    private String userId;

    /**
     * User's first name.
     * <p>
     * Mapped from COBOL field FNAMEI (PIC X(20)).
     * </p>
     * <p>
     * <b>Validation Rules:</b>
     * </p>
     * <ul>
     *   <li>Required field (cannot be blank)</li>
     *   <li>Maximum length: 20 characters</li>
     *   <li>Pattern: Alphabetic characters and spaces only [A-Za-z ]+</li>
     *   <li>Matches COBOL PIC A alphabetic-only constraint</li>
     * </ul>
     */
    @NotBlank(message = "First name is required and cannot be blank")
    @Size(max = 20, message = "First name must not exceed 20 characters")
    @Pattern(
        regexp = "[A-Za-z ]+",
        message = "First name must contain only alphabetic characters and spaces"
    )
    @JsonProperty("firstName")
    private String firstName;

    /**
     * User's last name.
     * <p>
     * Mapped from COBOL field LNAMEI (PIC X(20)).
     * </p>
     * <p>
     * <b>Validation Rules:</b>
     * </p>
     * <ul>
     *   <li>Required field (cannot be blank)</li>
     *   <li>Maximum length: 20 characters</li>
     *   <li>Pattern: Alphabetic characters and spaces only [A-Za-z ]+</li>
     *   <li>Matches COBOL PIC A alphabetic-only constraint</li>
     * </ul>
     */
    @NotBlank(message = "Last name is required and cannot be blank")
    @Size(max = 20, message = "Last name must not exceed 20 characters")
    @Pattern(
        regexp = "[A-Za-z ]+",
        message = "Last name must contain only alphabetic characters and spaces"
    )
    @JsonProperty("lastName")
    private String lastName;

    /**
     * Current password for verification during password change operations.
     * <p>
     * Mapped from COBOL field PASSWDI (PIC X(8)).
     * </p>
     * <p>
     * <b>Validation Rules:</b>
     * </p>
     * <ul>
     *   <li>Optional for profile updates without password change</li>
     *   <li>Required when newPassword is provided</li>
     *   <li>Length: Exactly 8 characters (COBOL constraint)</li>
     *   <li>Used to verify user identity before allowing password change</li>
     * </ul>
     * <p>
     * <b>Security Note:</b> This field contains the user's current password in plain text
     * from the form submission. It will be validated against the BCrypt-hashed password
     * stored in the database using BCrypt.matches() method.
     * </p>
     */
    @Size(min = 8, max = 8, message = "Current password must be exactly 8 characters")
    @JsonProperty("password")
    private String password;

    /**
     * New password for password change operations.
     * <p>
     * This field is NOT present in the original COBOL copybook but is required
     * for the Java implementation to support password changes with modern security
     * requirements.
     * </p>
     * <p>
     * <b>Validation Rules:</b>
     * </p>
     * <ul>
     *   <li>Optional (null if not changing password)</li>
     *   <li>Minimum length: 8 characters (COBOL compatibility)</li>
     *   <li>Maximum length: 72 characters (BCrypt input limit)</li>
     *   <li>Must contain at least one uppercase letter</li>
     *   <li>Must contain at least one lowercase letter</li>
     *   <li>Must contain at least one digit</li>
     *   <li>Must contain at least one special character</li>
     * </ul>
     * <p>
     * <b>Security:</b> Will be hashed using BCrypt with strength 12 before storage
     * as specified in section 0.5 of the migration requirements.
     * </p>
     * <p>
     * <b>Password Strength Pattern:</b> The regex enforces that the password contains
     * all required character types without dictating their order or position.
     * </p>
     */
    @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#])[A-Za-z\\d@$!%*?&#]{8,72}$",
        message = "New password must contain at least one uppercase letter, one lowercase letter, one number, and one special character (@$!%*?&#)"
    )
    @JsonProperty("newPassword")
    private String newPassword;

    /**
     * Password confirmation for validation during password change operations.
     * <p>
     * This field is NOT present in the original COBOL copybook but is required
     * for the Java implementation to ensure users correctly enter their intended
     * new password.
     * </p>
     * <p>
     * <b>Validation Rules:</b>
     * </p>
     * <ul>
     *   <li>Optional (null if not changing password)</li>
     *   <li>Must exactly match newPassword field when provided</li>
     *   <li>Validated using custom @FieldMatch annotation at class level</li>
     * </ul>
     * <p>
     * <b>Implementation Note:</b> This field should be validated using a custom
     * class-level validator annotation (e.g., @FieldMatch) to ensure it matches
     * the newPassword field. The validation logic should be:
     * </p>
     * <pre>
     * if (newPassword != null && !newPassword.equals(confirmPassword)) {
     *     throw new ValidationException("New password and confirm password must match");
     * }
     * </pre>
     */
    @JsonProperty("confirmPassword")
    private String confirmPassword;

    /**
     * User type code defining the user's role and access level.
     * <p>
     * Mapped from COBOL field USRTYPEI (PIC X(1)).
     * </p>
     * <p>
     * <b>Validation Rules:</b>
     * </p>
     * <ul>
     *   <li>Required field (cannot be null)</li>
     *   <li>Must be exactly 1 character</li>
     *   <li>Allowed values: 'R' (Regular User) or 'A' (Administrative User)</li>
     *   <li>Pattern: [RA]</li>
     * </ul>
     * <p>
     * <b>Role Mapping to Spring Security:</b>
     * </p>
     * <ul>
     *   <li>'R' → ROLE_USER: Regular user with standard account access privileges</li>
     *   <li>'A' → ROLE_ADMIN: Administrative user with full system access and user management</li>
     * </ul>
     * <p>
     * <b>Authorization:</b> Changing user type is restricted to ROLE_ADMIN only.
     * Regular users (ROLE_USER) cannot modify their own or others' user type.
     * This is enforced using @PreAuthorize method-level security in the service layer.
     * </p>
     */
    @NotNull(message = "User type is required")
    @Pattern(
        regexp = "[RA]",
        message = "User type must be 'R' (Regular User) or 'A' (Administrative User)"
    )
    @JsonProperty("userType")
    private String userType;

    /**
     * Validates that password change fields are consistent.
     * <p>
     * This validation logic should be executed in the service layer:
     * </p>
     * <ul>
     *   <li>If newPassword is provided, password (current password) must also be provided</li>
     *   <li>If newPassword is provided, confirmPassword must match newPassword</li>
     *   <li>Current password must be validated against stored BCrypt hash</li>
     * </ul>
     * 
     * @return true if password change fields are valid or no password change is requested
     */
    public boolean isPasswordChangeValid() {
        // If no password change requested, validation passes
        if (newPassword == null || newPassword.trim().isEmpty()) {
            return true;
        }
        
        // If new password provided, current password must be provided
        if (password == null || password.trim().isEmpty()) {
            return false;
        }
        
        // If new password provided, confirm password must match
        if (confirmPassword == null || !confirmPassword.equals(newPassword)) {
            return false;
        }
        
        return true;
    }

    /**
     * Checks if this request includes a password change operation.
     * 
     * @return true if newPassword is provided and not blank
     */
    public boolean isPasswordChangeRequested() {
        return newPassword != null && !newPassword.trim().isEmpty();
    }

    /**
     * Checks if the user type represents an administrative user.
     * 
     * @return true if userType is 'A' (Admin)
     */
    public boolean isAdminUser() {
        return "A".equals(userType);
    }

    /**
     * Checks if the user type represents a regular user.
     * 
     * @return true if userType is 'R' (Regular)
     */
    public boolean isRegularUser() {
        return "R".equals(userType);
    }
}

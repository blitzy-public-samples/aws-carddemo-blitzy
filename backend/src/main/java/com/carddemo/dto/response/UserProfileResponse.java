/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
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
 *
 * UserProfileResponse.java - Response DTO for User Profile View Operations
 *
 * Transformation Source: COBOL BMS Copybook COUSR01.CPY (output structure COUSR1AO)
 * Original CICS Program: COUSR01C.cbl - User Profile Management Program
 * Original Transaction: CU01 - User Profile View/Update
 *
 * PURPOSE:
 * Response DTO for user profile view and management operations, returning user account
 * details and role information. Supports GET /api/users/{id} endpoint for user profile
 * retrieval and PUT /api/users/{id} for profile updates.
 *
 * FIELD MAPPINGS FROM COBOL COUSR1AO OUTPUT STRUCTURE:
 * - TRNNAMEO (PIC X(4))  -> transactionName (String, max 4)
 * - TITLE01O (PIC X(40)) -> title01 (String, max 40)
 * - CURDATEO (PIC X(8))  -> currentDate (LocalDate with MM/dd/yyyy format)
 * - PGMNAMEO (PIC X(8))  -> programName (String, max 8)
 * - TITLE02O (PIC X(40)) -> title02 (String, max 40)
 * - CURTIMEO (PIC X(8))  -> currentTime (LocalTime with HH:mm:ss format)
 * - FNAMEO   (PIC X(20)) -> firstName (String, max 20)
 * - LNAMEO   (PIC X(20)) -> lastName (String, max 20)
 * - USERIDO  (PIC X(8))  -> userId (String, max 8, alphanumeric validation)
 * - USRTYPEO (PIC X(1))  -> userType (String, 1 char, R=Regular/A=Admin)
 * - ERRMSGO  (PIC X(78)) -> errorMessage (String, max 78)
 * - PASSWDO  (PIC X(8))  -> **EXCLUDED FOR SECURITY** - passwords never exposed in responses
 *
 * SECURITY MODEL TRANSFORMATION:
 * This DTO implements the two-tier role model transformation from COBOL USRSEC file
 * to Spring Security authorities:
 * - COBOL userType 'R' (Regular User)        -> Spring Security ROLE_USER
 * - COBOL userType 'A' (Administrative User) -> Spring Security ROLE_ADMIN
 *
 * CRITICAL SECURITY REQUIREMENTS (Agent Action Plan Section 0.9):
 * 1. Password field (PASSWDO) is NEVER included in response DTOs per security best practices
 * 2. Maintains exact two-tier role model from COBOL for Spring Security integration
 * 3. Role information exposed via getRoles() method for frontend authorization checks
 * 4. UserType enum provides type-safe representation of R/A values with role mapping
 *
 * CONVENIENCE ENHANCEMENTS:
 * - fullName: Computed property combining firstName and lastName
 * - roles: List<String> containing Spring Security role names based on userType
 *
 * BUSINESS LOGIC PRESERVATION:
 * Preserves exact field types, lengths, and formatting rules from original COBOL
 * screen layout per Agent Action Plan Section 0.9 requirements. Enables role-based
 * access control with @PreAuthorize method-level security matching COBOL program-level
 * authorization patterns.
 */
package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * UserProfileResponse DTO
 * <p>
 * Response data transfer object for user profile information returned by
 * GET /api/users/{id} and PUT /api/users/{id} endpoints.
 * <p>
 * Transformed from COBOL BMS copybook COUSR01.CPY output structure to support
 * modern REST API JSON responses while preserving exact business logic and
 * security model from mainframe application.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Transaction name identifier from COBOL TRNNAMEO field (PIC X(4))
     * Typically contains the CICS transaction code (e.g., "CU01")
     */
    @JsonProperty("transactionName")
    @Size(max = 4, message = "Transaction name must not exceed 4 characters")
    private String transactionName;

    /**
     * Primary screen title from COBOL TITLE01O field (PIC X(40))
     * Used for screen header display in original 3270 interface
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title01 must not exceed 40 characters")
    private String title01;

    /**
     * Current date from COBOL CURDATEO field (PIC X(8))
     * Original format: MM/dd/yy, converted to LocalDate for JSON serialization
     * Formatted as MM/dd/yyyy in JSON responses
     */
    @JsonProperty("currentDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "MM/dd/yyyy")
    private LocalDate currentDate;

    /**
     * Program name from COBOL PGMNAMEO field (PIC X(8))
     * Contains the executing COBOL program identifier (e.g., "COUSR01C")
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name must not exceed 8 characters")
    private String programName;

    /**
     * Secondary screen title from COBOL TITLE02O field (PIC X(40))
     * Used for additional screen context in original interface
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title02 must not exceed 40 characters")
    private String title02;

    /**
     * Current time from COBOL CURTIMEO field (PIC X(8))
     * Original format: HH:MM:SS, converted to LocalTime for JSON serialization
     */
    @JsonProperty("currentTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    private LocalTime currentTime;

    /**
     * User's first name from COBOL FNAMEO field (PIC X(20))
     * Part of user profile information from USRSEC file
     */
    @JsonProperty("firstName")
    @Size(max = 20, message = "First name must not exceed 20 characters")
    private String firstName;

    /**
     * User's last name from COBOL LNAMEO field (PIC X(20))
     * Part of user profile information from USRSEC file
     */
    @JsonProperty("lastName")
    @Size(max = 20, message = "Last name must not exceed 20 characters")
    private String lastName;

    /**
     * User identifier from COBOL USERIDO field (PIC X(8))
     * Primary key for user authentication in USRSEC file
     * Must be 8-character alphanumeric matching COBOL SEC-USR-ID format
     */
    @JsonProperty("userId")
    @Size(min = 1, max = 8, message = "User ID must be between 1 and 8 characters")
    @Pattern(regexp = "^[A-Za-z0-9]{1,8}$", 
             message = "User ID must contain only alphanumeric characters")
    private String userId;

    /**
     * User type/role indicator from COBOL USRTYPEO field (PIC X(1))
     * <p>
     * Valid values (case-sensitive):
     * - 'R' = Regular User (maps to Spring Security ROLE_USER)
     * - 'A' = Administrative User (maps to Spring Security ROLE_ADMIN)
     * <p>
     * This field maintains the two-tier role model from the COBOL USRSEC file
     * and is used to compute the roles list for Spring Security integration.
     */
    @JsonProperty("userType")
    @Size(min = 1, max = 1, message = "User type must be exactly 1 character")
    @Pattern(regexp = "^[RA]$", 
             message = "User type must be either 'R' (Regular) or 'A' (Admin)")
    private String userType;

    /**
     * Error message from COBOL ERRMSGO field (PIC X(78))
     * Contains validation or processing error messages to display to user
     * Empty/null indicates successful operation
     */
    @JsonProperty("errorMessage")
    @Size(max = 78, message = "Error message must not exceed 78 characters")
    private String errorMessage;

    /**
     * Computed full name property (convenience method)
     * <p>
     * Combines firstName and lastName with space separator.
     * Returns empty string if both names are null or empty.
     * Handles null values gracefully.
     *
     * @return Full name as "firstName lastName" or empty string
     */
    @JsonProperty("fullName")
    public String getFullName() {
        if (firstName == null && lastName == null) {
            return "";
        }
        
        String first = (firstName != null) ? firstName.trim() : "";
        String last = (lastName != null) ? lastName.trim() : "";
        
        if (first.isEmpty() && last.isEmpty()) {
            return "";
        } else if (first.isEmpty()) {
            return last;
        } else if (last.isEmpty()) {
            return first;
        } else {
            return first + " " + last;
        }
    }

    /**
     * Computed roles property for Spring Security integration (convenience method)
     * <p>
     * Maps COBOL userType to Spring Security authority names following
     * the two-tier role model from the mainframe USRSEC file:
     * <ul>
     *   <li>'R' (Regular User) -> ["ROLE_USER"]</li>
     *   <li>'A' (Admin User) -> ["ROLE_ADMIN", "ROLE_USER"] - Admins inherit user permissions</li>
     *   <li>null/invalid -> ["ROLE_USER"] - Default to minimal privileges</li>
     * </ul>
     * <p>
     * This enables frontend authorization checks and UI rendering based on roles
     * per Agent Action Plan Section 0.9 security preservation requirements.
     *
     * @return List of Spring Security role names for authorization
     */
    @JsonProperty("roles")
    public List<String> getRoles() {
        List<String> roles = new ArrayList<>();
        
        if (userType == null || userType.trim().isEmpty()) {
            // Default to minimal privileges if userType is not set
            roles.add("ROLE_USER");
        } else {
            String type = userType.trim().toUpperCase();
            switch (type) {
                case "A":
                    // Administrative users have both ADMIN and USER roles
                    // Admins inherit all regular user permissions
                    roles.add("ROLE_ADMIN");
                    roles.add("ROLE_USER");
                    break;
                case "R":
                    // Regular users have USER role only
                    roles.add("ROLE_USER");
                    break;
                default:
                    // Unknown user type defaults to minimal privileges
                    roles.add("ROLE_USER");
                    break;
            }
        }
        
        return roles;
    }

    /**
     * UserTypeEnum - Type-safe enumeration for COBOL user type values
     * <p>
     * Provides type-safe representation of the single-character user type field
     * from COBOL USRSEC file with explicit mapping to Spring Security roles.
     * <p>
     * This enum can be used in service layer for type-safe user type handling
     * and role assignment logic.
     */
    public enum UserTypeEnum {
        /**
         * Regular User (COBOL value 'R')
         * Maps to Spring Security ROLE_USER authority
         * Standard application user with read/write access to own data
         */
        REGULAR_USER('R', "ROLE_USER"),

        /**
         * Administrative User (COBOL value 'A')
         * Maps to Spring Security ROLE_ADMIN authority
         * Privileged user with access to administrative functions and all user data
         */
        ADMIN_USER('A', "ROLE_ADMIN");

        private final char code;
        private final String springSecurityRole;

        /**
         * Constructor for UserTypeEnum
         *
         * @param code The single-character COBOL user type code ('R' or 'A')
         * @param springSecurityRole The corresponding Spring Security role name
         */
        UserTypeEnum(char code, String springSecurityRole) {
            this.code = code;
            this.springSecurityRole = springSecurityRole;
        }

        /**
         * Get the COBOL single-character code for this user type
         *
         * @return 'R' for Regular User or 'A' for Admin User
         */
        public char getCode() {
            return code;
        }

        /**
         * Get the COBOL code as a String
         *
         * @return "R" for Regular User or "A" for Admin User
         */
        public String getCodeAsString() {
            return String.valueOf(code);
        }

        /**
         * Get the Spring Security role name for this user type
         *
         * @return "ROLE_USER" for Regular or "ROLE_ADMIN" for Admin
         */
        public String getSpringSecurityRole() {
            return springSecurityRole;
        }

        /**
         * Convert from COBOL character code to enum value
         *
         * @param code The single-character COBOL user type code
         * @return Corresponding UserTypeEnum value
         * @throws IllegalArgumentException if code is not 'R' or 'A'
         */
        public static UserTypeEnum fromCode(char code) {
            char upperCode = Character.toUpperCase(code);
            for (UserTypeEnum type : values()) {
                if (type.code == upperCode) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid user type code: " + code 
                + ". Valid values are 'R' (Regular) or 'A' (Admin)");
        }

        /**
         * Convert from COBOL string code to enum value
         *
         * @param code The single-character COBOL user type code as String
         * @return Corresponding UserTypeEnum value
         * @throws IllegalArgumentException if code is null, empty, or not 'R' or 'A'
         */
        public static UserTypeEnum fromCode(String code) {
            if (code == null || code.trim().isEmpty()) {
                throw new IllegalArgumentException("User type code cannot be null or empty");
            }
            return fromCode(code.trim().charAt(0));
        }

        /**
         * Check if a given code represents an administrative user
         *
         * @param code The user type code to check
         * @return true if code is 'A' (case-insensitive), false otherwise
         */
        public static boolean isAdmin(String code) {
            if (code == null || code.trim().isEmpty()) {
                return false;
            }
            return code.trim().equalsIgnoreCase("A");
        }

        /**
         * Check if a given code represents a regular user
         *
         * @param code The user type code to check
         * @return true if code is 'R' (case-insensitive), false otherwise
         */
        public static boolean isRegularUser(String code) {
            if (code == null || code.trim().isEmpty()) {
                return false;
            }
            return code.trim().equalsIgnoreCase("R");
        }
    }
}

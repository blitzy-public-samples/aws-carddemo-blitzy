/*
 * SecurityRoles.java
 *
 * Security role constants for CardDemo application
 * Replaces RACF (Resource Access Control Facility) security model from mainframe
 *
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
 *
 * Converted from COBOL copybook: COCOM01Y.cpy
 * Original COBOL fields: CDEMO-USER-TYPE (PIC X(01))
 * Original 88-level conditions:
 *   - CDEMO-USRTYP-ADMIN VALUE 'A' (line 27)
 *   - CDEMO-USRTYP-USER VALUE 'U' (line 28)
 *
 * Conversion Notes:
 * - COBOL single-character user type codes ('A', 'U') converted to Spring Security
 *   role names with ROLE_ prefix convention
 * - Added ROLE_OPERATOR for operational staff (not present in original COBOL)
 * - Bidirectional mapping methods provided for backward compatibility with
 *   COBOL user type logic in external interfaces
 * - All role constants used with Spring Security @PreAuthorize annotations
 *   on REST controller methods
 */
package com.carddemo.security;

import org.slf4j.LoggerFactory;

/**
 * Security role constants defining user authorization levels for the CardDemo application.
 * 
 * <p>This class replaces the RACF (Resource Access Control Facility) security model from the
 * mainframe environment. It maps COBOL user type codes to Spring Security granted authorities
 * using the standard ROLE_ prefix convention.</p>
 * 
 * <p><b>RACF to Spring Security Migration:</b></p>
 * <ul>
 *   <li>RACF resource profiles → Spring Security role-based access control (RBAC)</li>
 *   <li>RACF user profiles → Spring Security UserDetails (stored in user_security table)</li>
 *   <li>RACF access rules → Spring Security @PreAuthorize annotations</li>
 *   <li>RACF user roles → Spring Security granted authorities</li>
 * </ul>
 * 
 * <p><b>COBOL User Type Mapping:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Type</th>
 *     <th>COBOL Condition</th>
 *     <th>Spring Security Role</th>
 *     <th>Access Level</th>
 *   </tr>
 *   <tr>
 *     <td>'A'</td>
 *     <td>CDEMO-USRTYP-ADMIN</td>
 *     <td>ROLE_ADMIN</td>
 *     <td>Full system administration access</td>
 *   </tr>
 *   <tr>
 *     <td>'U'</td>
 *     <td>CDEMO-USRTYP-USER</td>
 *     <td>ROLE_USER</td>
 *     <td>Standard user access to account and transaction functions</td>
 *   </tr>
 *   <tr>
 *     <td>'O'</td>
 *     <td>N/A (new)</td>
 *     <td>ROLE_OPERATOR</td>
 *     <td>Operational staff access for monitoring and support</td>
 *   </tr>
 * </table>
 * 
 * <p><b>Usage Example:</b></p>
 * <pre>
 * &#64;PreAuthorize("hasRole('" + SecurityRoles.ROLE_ADMIN + "')")
 * public ResponseEntity&lt;UserDto&gt; deleteUser(@PathVariable String userId) {
 *     // Admin-only operation
 * }
 * 
 * &#64;PreAuthorize("hasAnyRole('" + SecurityRoles.ROLE_ADMIN + "', '" + SecurityRoles.ROLE_USER + "')")
 * public ResponseEntity&lt;AccountDto&gt; getAccount(@PathVariable Long accountId) {
 *     // Accessible by both admin and regular users
 * }
 * </pre>
 * 
 * <p><b>Role Hierarchy:</b></p>
 * <pre>
 * ROLE_ADMIN > ROLE_OPERATOR > ROLE_USER
 * </pre>
 * 
 * @see org.springframework.security.access.prepost.PreAuthorize
 * @see org.springframework.security.core.authority.SimpleGrantedAuthority
 */
public final class SecurityRoles {

    /**
     * Logger for security-related audit events.
     * Used to track role mapping operations and security violations.
     */
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(SecurityRoles.class);

    /**
     * Administrator role constant.
     * 
     * <p>Grants full system administration access including:</p>
     * <ul>
     *   <li>User management (create, update, delete users)</li>
     *   <li>System configuration and settings</li>
     *   <li>Access to all reports and administrative functions</li>
     *   <li>Batch job management and monitoring</li>
     * </ul>
     * 
     * <p>Maps from COBOL: CDEMO-USRTYP-ADMIN VALUE 'A'</p>
     * <p>Routes to: COADM01C (Admin Menu)</p>
     */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * Standard user role constant.
     * 
     * <p>Grants standard user access including:</p>
     * <ul>
     *   <li>View account information</li>
     *   <li>View and post transactions</li>
     *   <li>View card information</li>
     *   <li>View billing statements</li>
     *   <li>Generate user-level reports</li>
     * </ul>
     * 
     * <p>Maps from COBOL: CDEMO-USRTYP-USER VALUE 'U'</p>
     * <p>Routes to: COMEN01C (Main Menu)</p>
     */
    public static final String ROLE_USER = "ROLE_USER";

    /**
     * Operator role constant.
     * 
     * <p>Grants operational staff access including:</p>
     * <ul>
     *   <li>System health monitoring</li>
     *   <li>View-only access to transactions and accounts</li>
     *   <li>Batch job status monitoring</li>
     *   <li>Support and troubleshooting functions</li>
     * </ul>
     * 
     * <p>Maps from COBOL: 'O' (New role added for Java migration)</p>
     * <p>Note: This role did not exist in the original COBOL system and was added
     * to support operational staff who need monitoring capabilities without full
     * administrative or user privileges.</p>
     */
    public static final String ROLE_OPERATOR = "ROLE_OPERATOR";

    /**
     * Default role assigned to new users or when role cannot be determined.
     * 
     * <p>Defaults to ROLE_USER to provide least-privilege access.</p>
     * <p>Administrators must explicitly promote users to ROLE_ADMIN or ROLE_OPERATOR.</p>
     */
    public static final String DEFAULT_ROLE = ROLE_USER;

    /**
     * COBOL user type code for administrator.
     * Original value from COCOM01Y.cpy line 27: CDEMO-USRTYP-ADMIN VALUE 'A'
     */
    private static final String COBOL_USER_TYPE_ADMIN = "A";

    /**
     * COBOL user type code for regular user.
     * Original value from COCOM01Y.cpy line 28: CDEMO-USRTYP-USER VALUE 'U'
     */
    private static final String COBOL_USER_TYPE_USER = "U";

    /**
     * COBOL user type code for operator.
     * New code added for Java migration (not present in original COBOL).
     */
    private static final String COBOL_USER_TYPE_OPERATOR = "O";

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static members.
     */
    private SecurityRoles() {
        throw new UnsupportedOperationException("SecurityRoles is a utility class and cannot be instantiated");
    }

    /**
     * Converts COBOL user type code to Spring Security role name.
     * 
     * <p>This method provides backward compatibility with the mainframe system's
     * single-character user type codes. It is used when authenticating users whose
     * credentials are stored with COBOL-style type codes.</p>
     * 
     * <p><b>Conversion Table:</b></p>
     * <ul>
     *   <li>'A' → ROLE_ADMIN</li>
     *   <li>'U' → ROLE_USER</li>
     *   <li>'O' → ROLE_OPERATOR</li>
     * </ul>
     * 
     * <p><b>Usage in Authentication:</b></p>
     * <pre>
     * String userType = userSecurityEntity.getUserType(); // 'A', 'U', or 'O'
     * String springRole = SecurityRoles.fromUserType(userType);
     * authorities.add(new SimpleGrantedAuthority(springRole));
     * </pre>
     * 
     * @param cobolUserType the single-character COBOL user type code ('A', 'U', or 'O').
     *                      Input is case-insensitive and will be trimmed of whitespace.
     * @return the corresponding Spring Security role name with ROLE_ prefix.
     *         Returns DEFAULT_ROLE if the input is null, empty, or unrecognized.
     */
    public static String fromUserType(String cobolUserType) {
        // Handle null or empty input
        if (cobolUserType == null || cobolUserType.trim().isEmpty()) {
            logger.warn("Null or empty COBOL user type provided. Defaulting to {}", DEFAULT_ROLE);
            return DEFAULT_ROLE;
        }

        // Normalize input: trim and convert to uppercase
        String normalizedType = cobolUserType.trim().toUpperCase();

        // Map COBOL user type to Spring Security role
        switch (normalizedType) {
            case COBOL_USER_TYPE_ADMIN:
                logger.debug("Mapped COBOL user type '{}' to {}", normalizedType, ROLE_ADMIN);
                return ROLE_ADMIN;

            case COBOL_USER_TYPE_USER:
                logger.debug("Mapped COBOL user type '{}' to {}", normalizedType, ROLE_USER);
                return ROLE_USER;

            case COBOL_USER_TYPE_OPERATOR:
                logger.debug("Mapped COBOL user type '{}' to {}", normalizedType, ROLE_OPERATOR);
                return ROLE_OPERATOR;

            default:
                logger.warn("Unrecognized COBOL user type '{}'. Defaulting to {}", 
                           normalizedType, DEFAULT_ROLE);
                return DEFAULT_ROLE;
        }
    }

    /**
     * Converts Spring Security role name to COBOL user type code.
     * 
     * <p>This method supports backward compatibility with external systems that expect
     * COBOL-style single-character user type codes. It is used when exporting user data
     * to downstream systems or generating reports in legacy formats.</p>
     * 
     * <p><b>Conversion Table:</b></p>
     * <ul>
     *   <li>ROLE_ADMIN → 'A'</li>
     *   <li>ROLE_USER → 'U'</li>
     *   <li>ROLE_OPERATOR → 'O'</li>
     * </ul>
     * 
     * <p><b>Usage in External Interface:</b></p>
     * <pre>
     * String springRole = user.getRole(); // "ROLE_ADMIN"
     * String cobolType = SecurityRoles.toUserType(springRole);
     * externalRecord.setUserType(cobolType); // Sets 'A'
     * </pre>
     * 
     * @param springSecurityRole the Spring Security role name (with or without ROLE_ prefix).
     *                           Input is case-insensitive and will be trimmed of whitespace.
     * @return the corresponding single-character COBOL user type code ('A', 'U', or 'O').
     *         Returns 'U' (user) if the input is null, empty, or unrecognized.
     */
    public static String toUserType(String springSecurityRole) {
        // Handle null or empty input
        if (springSecurityRole == null || springSecurityRole.trim().isEmpty()) {
            logger.warn("Null or empty Spring Security role provided. Defaulting to '{}'", 
                       COBOL_USER_TYPE_USER);
            return COBOL_USER_TYPE_USER;
        }

        // Normalize input: trim and convert to uppercase
        String normalizedRole = springSecurityRole.trim().toUpperCase();

        // Remove ROLE_ prefix if present for flexible input handling
        if (normalizedRole.startsWith("ROLE_")) {
            normalizedRole = normalizedRole.substring(5);
        }

        // Map Spring Security role to COBOL user type
        switch (normalizedRole) {
            case "ADMIN":
                logger.debug("Mapped Spring role '{}' to COBOL user type '{}'", 
                           springSecurityRole, COBOL_USER_TYPE_ADMIN);
                return COBOL_USER_TYPE_ADMIN;

            case "USER":
                logger.debug("Mapped Spring role '{}' to COBOL user type '{}'", 
                           springSecurityRole, COBOL_USER_TYPE_USER);
                return COBOL_USER_TYPE_USER;

            case "OPERATOR":
                logger.debug("Mapped Spring role '{}' to COBOL user type '{}'", 
                           springSecurityRole, COBOL_USER_TYPE_OPERATOR);
                return COBOL_USER_TYPE_OPERATOR;

            default:
                logger.warn("Unrecognized Spring Security role '{}'. Defaulting to COBOL user type '{}'", 
                           springSecurityRole, COBOL_USER_TYPE_USER);
                return COBOL_USER_TYPE_USER;
        }
    }

    /**
     * Validates if the provided string is a valid Spring Security role.
     * 
     * <p>A valid role is one of: ROLE_ADMIN, ROLE_USER, or ROLE_OPERATOR.</p>
     * 
     * @param role the role name to validate (with or without ROLE_ prefix)
     * @return true if the role is valid, false otherwise
     */
    public static boolean isValidRole(String role) {
        if (role == null || role.trim().isEmpty()) {
            return false;
        }

        String normalizedRole = role.trim().toUpperCase();
        
        // Handle with or without ROLE_ prefix
        if (normalizedRole.startsWith("ROLE_")) {
            return normalizedRole.equals(ROLE_ADMIN) 
                || normalizedRole.equals(ROLE_USER) 
                || normalizedRole.equals(ROLE_OPERATOR);
        } else {
            return normalizedRole.equals("ADMIN") 
                || normalizedRole.equals("USER") 
                || normalizedRole.equals("OPERATOR");
        }
    }

    /**
     * Validates if the provided string is a valid COBOL user type code.
     * 
     * <p>A valid COBOL user type is one of: 'A', 'U', or 'O'.</p>
     * 
     * @param cobolUserType the COBOL user type code to validate
     * @return true if the user type is valid, false otherwise
     */
    public static boolean isValidUserType(String cobolUserType) {
        if (cobolUserType == null || cobolUserType.trim().isEmpty()) {
            return false;
        }

        String normalizedType = cobolUserType.trim().toUpperCase();
        
        return normalizedType.equals(COBOL_USER_TYPE_ADMIN)
            || normalizedType.equals(COBOL_USER_TYPE_USER)
            || normalizedType.equals(COBOL_USER_TYPE_OPERATOR);
    }

    /**
     * Returns all valid Spring Security role names.
     * 
     * <p>Useful for populating dropdown lists or validating role assignments.</p>
     * 
     * @return array containing all valid role names
     */
    public static String[] getAllRoles() {
        return new String[] { ROLE_ADMIN, ROLE_USER, ROLE_OPERATOR };
    }

    /**
     * Returns all valid COBOL user type codes.
     * 
     * <p>Useful for external interface validation or data migration.</p>
     * 
     * @return array containing all valid COBOL user type codes
     */
    public static String[] getAllUserTypes() {
        return new String[] { 
            COBOL_USER_TYPE_ADMIN, 
            COBOL_USER_TYPE_USER, 
            COBOL_USER_TYPE_OPERATOR 
        };
    }
}

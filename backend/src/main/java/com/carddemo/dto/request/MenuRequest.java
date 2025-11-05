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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for main menu navigation capturing user menu option selections from COMEN01 BMS screen.
 * 
 * <p>This class transforms the COBOL COMEN01 BMS copybook menu selection fields into a modern
 * Java DTO for REST API consumption. It consolidates menu option fields (OPTN001I through OPTN012I)
 * and the numeric selection field (OPTIONI) into a simplified request structure for menu navigation
 * routing.</p>
 * 
 * <p><strong>COBOL Source Mapping:</strong></p>
 * <ul>
 *   <li>OPTN001I - OPTN012I (PIC X(40)) → selectedOption field</li>
 *   <li>OPTIONI (PIC X(2)) → optionNumber field</li>
 *   <li>PGMNAMEI (PIC X(8)) → fromProgram field</li>
 *   <li>TRNNAMEI (PIC X(4)) → transactionCode field</li>
 * </ul>
 * 
 * <p><strong>Role-Based Menu Filtering (Section 0.1 Two-Tier Security Model):</strong></p>
 * <ul>
 *   <li><strong>Regular Users (userType='U'):</strong> Options 1-3
 *     <ul>
 *       <li>1. Account View</li>
 *       <li>2. Card List</li>
 *       <li>3. Transaction View</li>
 *     </ul>
 *   </li>
 *   <li><strong>Administrative Users (userType='A'):</strong> All Options 1-5
 *     <ul>
 *       <li>1-3. Same as Regular Users</li>
 *       <li>4. User Management</li>
 *       <li>5. Reports</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <p><strong>Navigation Mapping (Section 0.3 Transaction Transformation):</strong></p>
 * <p>This DTO replaces CICS XCTL program transfer with React Router navigation paths.
 * The selected option number routes to corresponding React components via the MenuController
 * endpoint processing logic.</p>
 * 
 * <p><strong>Validation Strategy:</strong></p>
 * <ul>
 *   <li>Bean Validation annotations ensure only valid menu choices are submitted</li>
 *   <li>Pattern validation on userType restricts values to 'A' (Admin) or 'U' (User)</li>
 *   <li>Size constraints preserve COBOL PIC clause field length restrictions</li>
 *   <li>Min/Max constraints enforce valid menu option range (1-5)</li>
 * </ul>
 * 
 * <p><strong>Backward Compatibility:</strong></p>
 * <p>The fromProgram and transactionCode fields maintain compatibility with CICS
 * transaction context for audit logging and program flow tracking. The transactionCode
 * typically contains "CM00" representing the COMEN01C CICS transaction ID.</p>
 * 
 * @see com.carddemo.controller.MenuController
 * @see com.carddemo.service.MenuNavigationService
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuRequest {

    /**
     * The text description of the selected menu option (OPTN001I - OPTN012I equivalent).
     * 
     * <p>This field captures the descriptive text of the menu option chosen by the user.
     * In the original BMS screen, this would be one of the 12 menu option text fields
     * (OPTN001I through OPTN012I), each defined as PIC X(40).</p>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>"Account View and Management"</li>
     *   <li>"Card List and Operations"</li>
     *   <li>"Transaction History Review"</li>
     *   <li>"User Administration Functions"</li>
     *   <li>"Report Generation and Export"</li>
     * </ul>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>@NotBlank: Must not be null, empty, or whitespace-only</li>
     *   <li>@Size(max=40): Enforces COBOL PIC X(40) field length limit</li>
     * </ul>
     */
    @NotBlank(message = "Selected option must not be blank")
    @Size(max = 40, message = "Selected option must not exceed 40 characters")
    @JsonProperty("selectedOption")
    private String selectedOption;

    /**
     * The numeric menu choice entered by the user (OPTIONI equivalent).
     * 
     * <p>This field maps to the COBOL OPTIONI field (PIC X(2)) which captures the
     * numeric menu selection (1-12 in COBOL, restricted to 1-5 in Java for current
     * implementation scope).</p>
     * 
     * <p><strong>Valid Range by User Type:</strong></p>
     * <ul>
     *   <li><strong>Regular User (userType='U'):</strong> 1-3 only</li>
     *   <li><strong>Administrative User (userType='A'):</strong> 1-5</li>
     * </ul>
     * 
     * <p>Note: Business logic in MenuNavigationService validates the option number
     * against the user's role to enforce proper authorization per section 0.9 security
     * model preservation requirements.</p>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>@NotNull: Must be present in request</li>
     *   <li>@Min(1): Minimum valid option number</li>
     *   <li>@Max(5): Maximum valid option number for administrative users</li>
     * </ul>
     */
    @NotNull(message = "Option number must not be null")
    @Min(value = 1, message = "Option number must be at least 1")
    @Max(value = 5, message = "Option number must not exceed 5")
    @JsonProperty("optionNumber")
    private Integer optionNumber;

    /**
     * The user identifier for session context (USRSEC USER-ID equivalent).
     * 
     * <p>This field identifies the authenticated user making the menu selection request.
     * It maps to the USER-ID field from the USRSEC VSAM file (PIC X(8)) and is used
     * for authorization checks and audit logging.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Verifies user session validity via Spring Security context</li>
     *   <li>Used for role-based menu option filtering</li>
     *   <li>Captured in audit logs for menu navigation tracking</li>
     *   <li>Links menu request to authenticated user's security profile</li>
     * </ul>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>@NotBlank: Must not be null, empty, or whitespace-only</li>
     *   <li>@Size(max=8): Enforces COBOL PIC X(8) field length limit from USRSEC</li>
     * </ul>
     */
    @NotBlank(message = "User ID must not be blank")
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    @JsonProperty("userId")
    private String userId;

    /**
     * The user type indicator for role-based authorization (USRSEC USER-TYPE equivalent).
     * 
     * <p>This field maps to the USER-TYPE field from the USRSEC VSAM file and implements
     * the two-tier security model defined in section 0.1 of the Agent Action Plan.</p>
     * 
     * <p><strong>Valid Values (COBOL 88-level condition equivalent):</strong></p>
     * <ul>
     *   <li><strong>'A':</strong> Administrative User (ROLE_ADMIN in Spring Security)
     *     <ul>
     *       <li>Access to all menu options (1-5)</li>
     *       <li>User management capabilities</li>
     *       <li>Report generation access</li>
     *       <li>Administrative functions</li>
     *     </ul>
     *   </li>
     *   <li><strong>'U':</strong> Regular User (ROLE_USER in Spring Security)
     *     <ul>
     *       <li>Access to options 1-3 only</li>
     *       <li>Account view and card operations</li>
     *       <li>Transaction history review</li>
     *       <li>No administrative access</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * <p><strong>Pattern Validation:</strong></p>
     * <p>The regex pattern [AU] enforces that only 'A' (Administrative) or 'U' (User/Regular)
     * values are accepted, implementing the COBOL 88-level condition name pattern from the
     * original USRSEC file structure where USER-TYPE PIC X(1) has defined valid values.</p>
     * 
     * <p><strong>Security Integration:</strong></p>
     * <p>This field is validated against the Spring Security authentication context to ensure
     * the user's claimed role matches their authenticated authorities (ROLE_USER or ROLE_ADMIN)
     * per section 0.9 security model preservation requirements.</p>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>@NotBlank: Must not be null, empty, or whitespace-only</li>
     *   <li>@Pattern(regexp="[AU]"): Restricts to 'A' or 'U' only</li>
     * </ul>
     */
    @NotBlank(message = "User type must not be blank")
    @Pattern(regexp = "[AU]", message = "User type must be 'A' (Administrative) or 'U' (Regular User)")
    @JsonProperty("userType")
    private String userType;

    /**
     * The calling program name for backward compatibility (PGMNAMEI equivalent).
     * 
     * <p>This optional field maintains compatibility with CICS program flow tracking
     * by preserving the calling program name (PGMNAMEI field, PIC X(8) from COMEN01
     * BMS copybook). In the mainframe CICS environment, this field would contain the
     * name of the program that invoked the menu (e.g., "COSGN00C" after successful login).</p>
     * 
     * <p><strong>Purpose:</strong></p>
     * <ul>
     *   <li>Audit trail preservation - tracks navigation path through application</li>
     *   <li>Backward compatibility with CICS XCTL program transfer context</li>
     *   <li>Debugging and troubleshooting user workflow issues</li>
     *   <li>Supports program flow analysis in logs</li>
     * </ul>
     * 
     * <p><strong>Common Values:</strong></p>
     * <ul>
     *   <li>"COSGN00C" - User navigated from sign-on screen</li>
     *   <li>"COACTVWC" - User returned from account view</li>
     *   <li>"COCRDLIC" - User returned from card list</li>
     *   <li>"COTRN00C" - User returned from transaction history</li>
     * </ul>
     * 
     * <p>This field is optional and may be null or empty in REST API calls where
     * program context is not relevant or tracked via other mechanisms (e.g., HTTP Referer,
     * session history).</p>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>@Size(max=8): Enforces COBOL PIC X(8) field length limit if provided</li>
     * </ul>
     */
    @Size(max = 8, message = "From program must not exceed 8 characters")
    @JsonProperty("fromProgram")
    private String fromProgram;

    /**
     * The CICS transaction code for audit logging (TRNNAMEI equivalent).
     * 
     * <p>This optional field preserves the CICS transaction identifier for audit trail
     * completeness and backward compatibility. It maps to the TRNNAMEI field (PIC X(4))
     * from the COMEN01 BMS copybook.</p>
     * 
     * <p><strong>Standard Value:</strong></p>
     * <ul>
     *   <li><strong>"CM00"</strong> - COMEN01C menu program transaction ID</li>
     * </ul>
     * 
     * <p><strong>Purpose:</strong></p>
     * <ul>
     *   <li>Maintains transaction traceability from CICS to REST API architecture</li>
     *   <li>Supports regulatory compliance audit trail requirements (section 0.9)</li>
     *   <li>Enables correlation of REST API calls to original CICS transaction patterns</li>
     *   <li>Facilitates migration validation by linking new calls to legacy transactions</li>
     * </ul>
     * 
     * <p><strong>Migration Context:</strong></p>
     * <p>Per section 0.3 of the Agent Action Plan, CICS Transaction ID (CC00, CM00, etc.)
     * maps to REST API endpoint paths (/api/auth, /api/menu, etc.). This field preserves
     * the original transaction identifier for systems that require transaction-level
     * audit trails matching the mainframe implementation.</p>
     * 
     * <p>This field is optional and typically populated by client code that needs to
     * maintain CICS transaction context for audit or compatibility purposes. Modern
     * REST API consumers may omit this field if transaction tracking is not required.</p>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>@Size(max=4): Enforces COBOL PIC X(4) field length limit if provided</li>
     * </ul>
     */
    @Size(max = 4, message = "Transaction code must not exceed 4 characters")
    @JsonProperty("transactionCode")
    private String transactionCode;

}

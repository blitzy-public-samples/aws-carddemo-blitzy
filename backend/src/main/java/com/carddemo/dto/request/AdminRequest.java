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
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request DTO for administrative function selections from COADM01 BMS screen.
 * 
 * <p>This class represents the request payload for administrative operations in the CardDemo
 * application, migrated from the COBOL COADM01 BMS copybook structure. It captures admin menu
 * option choices and associated parameters for executing authorized administrative functions.</p>
 * 
 * <p><strong>COBOL Source Mapping:</strong></p>
 * <ul>
 *   <li>OPTN001I-OPTN012I (PIC X(40)) → selectedOption field</li>
 *   <li>OPTIONI (PIC X(2)) → optionNumber field</li>
 *   <li>Admin function context → parameters map</li>
 * </ul>
 * 
 * <p><strong>Security Requirements:</strong></p>
 * <ul>
 *   <li>All administrative functions require ROLE_ADMIN authorization (Section 0.9)</li>
 *   <li>Controller methods must use @PreAuthorize("hasRole('ADMIN')")</li>
 *   <li>userId field enables audit trail for administrative actions</li>
 * </ul>
 * 
 * <p><strong>Validation Rules:</strong></p>
 * <ul>
 *   <li>selectedOption: Required, max 40 characters (matches PIC X(40) from COBOL)</li>
 *   <li>optionNumber: Optional, must be between 1-8 if provided (matches menu options 1-8)</li>
 *   <li>userId: Optional but recommended for audit tracking</li>
 *   <li>parameters: Optional key-value pairs for function-specific arguments</li>
 * </ul>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * AdminRequest request = new AdminRequest();
 * request.setSelectedOption("User Management");
 * request.setOptionNumber(1);
 * request.setUserId("ADMIN001");
 * request.setParameters(Map.of("action", "list_users", "pageSize", 20));
 * </pre>
 * 
 * @see com.carddemo.controller.AdminController
 * @see com.carddemo.service.AdminService
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminRequest {

    /**
     * The selected administrative option text from the menu.
     * 
     * <p>Corresponds to one of the OPTN001I through OPTN008I fields from the COADM01 BMS
     * screen. This field captures the descriptive text of the selected admin function
     * (e.g., "User Management", "System Configuration", "Report Generation").</p>
     * 
     * <p><strong>COBOL Mapping:</strong> OPTN001I-OPTN008I PIC X(40)</p>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>@NotBlank: Field cannot be null or empty</li>
     *   <li>@Size(max=40): Maximum length matches COBOL PIC X(40) constraint</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>"User Management"</li>
     *   <li>"Account Administration"</li>
     *   <li>"System Reports"</li>
     *   <li>"Batch Job Management"</li>
     * </ul>
     */
    @NotBlank(message = "Selected option is required")
    @Size(max = 40, message = "Selected option must not exceed 40 characters")
    @JsonProperty("selectedOption")
    private String selectedOption;

    /**
     * The numeric menu option number selected by the administrator.
     * 
     * <p>Corresponds to the OPTIONI field (PIC X(2)) from COADM01 BMS screen. This field
     * provides an alternative numeric representation of the selected menu option, supporting
     * both text-based and numeric menu selection patterns.</p>
     * 
     * <p><strong>COBOL Mapping:</strong> OPTIONI PIC X(2)</p>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>@Min(1): Must be at least 1 (first menu option)</li>
     *   <li>@Max(8): Must not exceed 8 (matches available admin menu options)</li>
     *   <li>Optional field (can be null if only selectedOption is provided)</li>
     * </ul>
     * 
     * <p><strong>Menu Option Mapping:</strong></p>
     * <ul>
     *   <li>1 → User Management</li>
     *   <li>2 → Account Administration</li>
     *   <li>3 → Card Management</li>
     *   <li>4 → Transaction Reports</li>
     *   <li>5 → System Configuration</li>
     *   <li>6 → Batch Job Control</li>
     *   <li>7 → Audit Log Review</li>
     *   <li>8 → Database Maintenance</li>
     * </ul>
     * 
     * <p><strong>Note:</strong> While COADM01.CPY defines 12 option fields (OPTN001I-OPTN012I),
     * only options 1-8 are validated as active administrative functions. Options 9-12 may be
     * used for navigation or display purposes in the BMS screen layout.</p>
     */
    @Min(value = 1, message = "Option number must be at least 1")
    @Max(value = 8, message = "Option number must not exceed 8")
    @JsonProperty("optionNumber")
    private Integer optionNumber;

    /**
     * The user ID of the administrator making the request.
     * 
     * <p>This field is essential for audit trail purposes and security logging. It identifies
     * which administrator initiated the administrative action, enabling compliance with
     * regulatory requirements for administrative activity tracking.</p>
     * 
     * <p><strong>Security Context:</strong></p>
     * <ul>
     *   <li>Should match the authenticated user from JWT token</li>
     *   <li>Used for audit logging of all administrative actions</li>
     *   <li>Required for ROLE_ADMIN authorization enforcement</li>
     *   <li>Maps to USRSEC file USER-ID field in COBOL implementation</li>
     * </ul>
     * 
     * <p><strong>COBOL Mapping:</strong> Derived from CICS user context (EIBRESP user ID)</p>
     * 
     * <p><strong>Audit Trail Usage:</strong></p>
     * <pre>
     * LOG: [2024-01-15 14:32:01] ADMIN [ADMIN001] executed [User Management] function
     * </pre>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>"ADMIN001"</li>
     *   <li>"SYSADMIN"</li>
     *   <li>"DBADMIN"</li>
     * </ul>
     */
    @JsonProperty("userId")
    private String userId;

    /**
     * Optional parameters for administrative function execution.
     * 
     * <p>This flexible map allows different administrative functions to pass function-specific
     * parameters without modifying the base AdminRequest structure. Supports extensibility
     * where various admin operations (user management, report generation, system configuration)
     * can provide additional context or arguments.</p>
     * 
     * <p><strong>Design Pattern:</strong> Follows the Command Pattern, where parameters
     * provide arguments to the selected administrative command.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong> Maps to optional COMMAREA fields passed to
     * administrative CICS transactions for additional context.</p>
     * 
     * <p><strong>Common Parameter Examples:</strong></p>
     * <ul>
     *   <li><strong>User Management:</strong>
     *     <ul>
     *       <li>"action" → "list_users" | "create_user" | "delete_user" | "reset_password"</li>
     *       <li>"targetUserId" → User ID to operate on</li>
     *       <li>"pageNumber" → Pagination page number</li>
     *       <li>"pageSize" → Records per page</li>
     *     </ul>
     *   </li>
     *   <li><strong>Report Generation:</strong>
     *     <ul>
     *       <li>"reportType" → "transaction" | "account" | "audit"</li>
     *       <li>"startDate" → Report start date (ISO 8601 format)</li>
     *       <li>"endDate" → Report end date (ISO 8601 format)</li>
     *       <li>"format" → "PDF" | "CSV" | "XLSX"</li>
     *     </ul>
     *   </li>
     *   <li><strong>System Configuration:</strong>
     *     <ul>
     *       <li>"configKey" → Configuration parameter name</li>
     *       <li>"configValue" → New configuration value</li>
     *       <li>"effectiveDate" → When configuration takes effect</li>
     *     </ul>
     *   </li>
     *   <li><strong>Batch Job Management:</strong>
     *     <ul>
     *       <li>"jobName" → Name of batch job to control</li>
     *       <li>"action" → "start" | "stop" | "restart" | "status"</li>
     *       <li>"jobParameters" → Map of job-specific parameters</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * <p><strong>Implementation Note:</strong> Default to empty HashMap rather than null
     * to prevent NullPointerException in service layer when accessing parameters.</p>
     * 
     * <p><strong>Usage Example:</strong></p>
     * <pre>
     * Map&lt;String, Object&gt; params = new HashMap&lt;&gt;();
     * params.put("action", "list_users");
     * params.put("roleFilter", "ROLE_ADMIN");
     * params.put("pageSize", 25);
     * params.put("sortBy", "userId");
     * params.put("sortDirection", "ASC");
     * adminRequest.setParameters(params);
     * </pre>
     */
    @JsonProperty("parameters")
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * Sets the parameters map for administrative function arguments.
     * 
     * <p>This setter ensures that a null map is converted to an empty HashMap, preventing
     * NullPointerException in service layer code that accesses parameters. This defensive
     * programming approach aligns with the fail-safe design principles of the migration.</p>
     * 
     * @param parameters the parameters map to set, or null to use empty map
     */
    public void setParameters(Map<String, Object> parameters) {
        this.parameters = (parameters != null) ? parameters : new HashMap<>();
    }
}

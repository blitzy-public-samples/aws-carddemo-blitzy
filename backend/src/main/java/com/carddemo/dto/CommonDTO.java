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
package com.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Common Data Transfer Object representing the shared COMMAREA structure used across all 
 * CardDemo transactions for maintaining user context, navigation state, and basic entity 
 * information during multi-screen workflows.
 * 
 * <p>This DTO maps from the COBOL CARDDEMO-COMMAREA structure defined in the COCOM01Y.cpy 
 * copybook. It serves as the equivalent of CICS pseudo-conversational processing state 
 * management in the modern RESTful API architecture.</p>
 * 
 * <p>In the legacy COBOL/CICS application, COMMAREA was passed between transactions to 
 * preserve state across multiple screen interactions. In the Java/Spring Boot implementation, 
 * this DTO can be used as:
 * <ul>
 *   <li>Base class for request/response DTOs requiring user context</li>
 *   <li>Composition field in multi-step workflow DTOs</li>
 *   <li>Session state holder for transaction routing information</li>
 * </ul>
 * </p>
 * 
 * <p>Structure Overview:</p>
 * <ul>
 *   <li><b>General Information:</b> Transaction routing, user credentials, program context</li>
 *   <li><b>Customer Information:</b> Customer ID and full name components</li>
 *   <li><b>Account Information:</b> Account ID and status</li>
 *   <li><b>Card Information:</b> Card number for card-related transactions</li>
 *   <li><b>Navigation Information:</b> Last accessed BMS map and mapset for screen flow tracking</li>
 * </ul>
 * 
 * @see com.carddemo.entity.Customer
 * @see com.carddemo.entity.Account
 * @see com.carddemo.entity.Card
 * @since CardDemo Java Migration v1.0
 * @author CardDemo Migration Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommonDTO {

    // =====================================================================
    // GENERAL INFORMATION SECTION
    // Maps to CDEMO-GENERAL-INFO in COBOL copybook
    // =====================================================================

    /**
     * Source transaction ID from which the current transaction was initiated.
     * Maps to CDEMO-FROM-TRANID (PIC X(04)) in COBOL.
     * Used for transaction flow tracking and navigation history.
     */
    @JsonProperty("from_transaction_id")
    @Size(max = 4, message = "From transaction ID must not exceed 4 characters")
    private String fromTransactionId;

    /**
     * Source program name from which control was transferred.
     * Maps to CDEMO-FROM-PROGRAM (PIC X(08)) in COBOL.
     * Preserves program call stack for debugging and audit trails.
     */
    @JsonProperty("from_program")
    @Size(max = 8, message = "From program name must not exceed 8 characters")
    private String fromProgram;

    /**
     * Target transaction ID for the next transaction in the workflow.
     * Maps to CDEMO-TO-TRANID (PIC X(04)) in COBOL.
     * Enables programmatic navigation control in multi-step processes.
     */
    @JsonProperty("to_transaction_id")
    @Size(max = 4, message = "To transaction ID must not exceed 4 characters")
    private String toTransactionId;

    /**
     * Target program name to which control will be transferred.
     * Maps to CDEMO-TO-PROGRAM (PIC X(08)) in COBOL.
     * Used for dynamic program routing and workflow orchestration.
     */
    @JsonProperty("to_program")
    @Size(max = 8, message = "To program name must not exceed 8 characters")
    private String toProgram;

    /**
     * User ID of the currently authenticated user.
     * Maps to CDEMO-USER-ID (PIC X(08)) in COBOL.
     * Mandatory field for all authenticated transactions.
     * Used for authorization checks and audit logging.
     */
    @JsonProperty("user_id")
    @NotBlank(message = "User ID is mandatory")
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    private String userId;

    /**
     * User type indicating the role/privilege level of the authenticated user.
     * Maps to CDEMO-USER-TYPE (PIC X(01)) in COBOL.
     * Valid values:
     * <ul>
     *   <li>'A' - Administrative user (CDEMO-USRTYP-ADMIN)</li>
     *   <li>'U' - Regular user (CDEMO-USRTYP-USER)</li>
     * </ul>
     * Corresponds to Spring Security roles ROLE_ADMIN and ROLE_USER.
     */
    @JsonProperty("user_type")
    @NotBlank(message = "User type is mandatory")
    @Pattern(regexp = "[AU]", message = "User type must be 'A' (Admin) or 'U' (User)")
    @Size(min = 1, max = 1, message = "User type must be exactly 1 character")
    private String userType;

    /**
     * Program context indicator for pseudo-conversational state management.
     * Maps to CDEMO-PGM-CONTEXT (PIC 9(01)) in COBOL.
     * Valid values:
     * <ul>
     *   <li>0 - Initial entry to program (CDEMO-PGM-ENTER)</li>
     *   <li>1 - Re-entry to program after previous interaction (CDEMO-PGM-REENTER)</li>
     * </ul>
     * Used to determine if this is the first invocation or a continuation of a workflow.
     */
    @JsonProperty("program_context")
    @Min(value = 0, message = "Program context must be 0 (Enter) or 1 (Reenter)")
    @Max(value = 1, message = "Program context must be 0 (Enter) or 1 (Reenter)")
    private Integer programContext;

    // =====================================================================
    // CUSTOMER INFORMATION SECTION
    // Maps to CDEMO-CUSTOMER-INFO in COBOL copybook
    // =====================================================================

    /**
     * Unique customer identifier.
     * Maps to CDEMO-CUST-ID (PIC 9(09)) in COBOL.
     * 9-digit numeric customer ID from the customer master file.
     */
    @JsonProperty("customer_id")
    @Positive(message = "Customer ID must be a positive number")
    private Long customerId;

    /**
     * Customer first name.
     * Maps to CDEMO-CUST-FNAME (PIC X(25)) in COBOL.
     * Stored in COMMAREA for display on screens requiring customer context.
     */
    @JsonProperty("customer_first_name")
    @Size(max = 25, message = "Customer first name must not exceed 25 characters")
    private String customerFirstName;

    /**
     * Customer middle name or initial.
     * Maps to CDEMO-CUST-MNAME (PIC X(25)) in COBOL.
     * Optional field for full name display and reporting.
     */
    @JsonProperty("customer_middle_name")
    @Size(max = 25, message = "Customer middle name must not exceed 25 characters")
    private String customerMiddleName;

    /**
     * Customer last name (surname).
     * Maps to CDEMO-CUST-LNAME (PIC X(25)) in COBOL.
     * Used for customer identification and greeting displays.
     */
    @JsonProperty("customer_last_name")
    @Size(max = 25, message = "Customer last name must not exceed 25 characters")
    private String customerLastName;

    // =====================================================================
    // ACCOUNT INFORMATION SECTION
    // Maps to CDEMO-ACCOUNT-INFO in COBOL copybook
    // =====================================================================

    /**
     * Unique account identifier.
     * Maps to CDEMO-ACCT-ID (PIC 9(11)) in COBOL.
     * 11-digit numeric account ID for account-related transactions.
     */
    @JsonProperty("account_id")
    @Positive(message = "Account ID must be a positive number")
    private Long accountId;

    /**
     * Account status code.
     * Maps to CDEMO-ACCT-STATUS (PIC X(01)) in COBOL.
     * Single character code indicating account state (Active, Closed, Suspended, etc.).
     */
    @JsonProperty("account_status")
    @Size(max = 1, message = "Account status must be exactly 1 character")
    private String accountStatus;

    // =====================================================================
    // CARD INFORMATION SECTION
    // Maps to CDEMO-CARD-INFO in COBOL copybook
    // =====================================================================

    /**
     * Credit card number.
     * Maps to CDEMO-CARD-NUM (PIC 9(16)) in COBOL.
     * 16-digit numeric card number for card-related operations.
     * Note: In production, this should be masked or tokenized for PCI-DSS compliance.
     */
    @JsonProperty("card_number")
    @Pattern(regexp = "\\d{0,16}", message = "Card number must be up to 16 digits")
    @Size(max = 16, message = "Card number must not exceed 16 characters")
    private String cardNumber;

    // =====================================================================
    // NAVIGATION INFORMATION SECTION
    // Maps to CDEMO-MORE-INFO in COBOL copybook
    // =====================================================================

    /**
     * Last accessed BMS map name.
     * Maps to CDEMO-LAST-MAP (PIC X(7)) in COBOL.
     * Used for screen flow tracking and back navigation functionality.
     * In React UI, corresponds to the last component/route accessed.
     */
    @JsonProperty("last_map")
    @Size(max = 7, message = "Last map name must not exceed 7 characters")
    private String lastMap;

    /**
     * Last accessed BMS mapset name.
     * Maps to CDEMO-LAST-MAPSET (PIC X(7)) in COBOL.
     * Groups related maps/screens for navigation context.
     * In React UI, corresponds to the last screen module accessed.
     */
    @JsonProperty("last_mapset")
    @Size(max = 7, message = "Last mapset name must not exceed 7 characters")
    private String lastMapset;

    // =====================================================================
    // HELPER METHODS
    // Business logic methods equivalent to COBOL 88-level condition names
    // =====================================================================

    /**
     * Checks if the current user is an administrative user.
     * Equivalent to COBOL condition: CDEMO-USRTYP-ADMIN (88 level, VALUE 'A').
     * 
     * @return true if userType is 'A' (Admin), false otherwise
     */
    public boolean isAdminUser() {
        return "A".equals(userType);
    }

    /**
     * Checks if the current user is a regular user.
     * Equivalent to COBOL condition: CDEMO-USRTYP-USER (88 level, VALUE 'U').
     * 
     * @return true if userType is 'U' (User), false otherwise
     */
    public boolean isRegularUser() {
        return "U".equals(userType);
    }

    /**
     * Checks if this is the initial entry to the program.
     * Equivalent to COBOL condition: CDEMO-PGM-ENTER (88 level, VALUE 0).
     * 
     * @return true if programContext is 0 (initial entry), false otherwise
     */
    public boolean isInitialContext() {
        return programContext != null && programContext == 0;
    }

    /**
     * Checks if this is a re-entry to the program after a previous interaction.
     * Equivalent to COBOL condition: CDEMO-PGM-REENTER (88 level, VALUE 1).
     * 
     * @return true if programContext is 1 (re-entry), false otherwise
     */
    public boolean isReentryContext() {
        return programContext != null && programContext == 1;
    }

    /**
     * Constructs a full customer name by concatenating first, middle, and last names.
     * Handles null values gracefully by skipping null components.
     * 
     * @return formatted full name with proper spacing, or empty string if all components are null
     */
    public String getFullCustomerName() {
        StringBuilder fullName = new StringBuilder();
        
        if (customerFirstName != null && !customerFirstName.trim().isEmpty()) {
            fullName.append(customerFirstName.trim());
        }
        
        if (customerMiddleName != null && !customerMiddleName.trim().isEmpty()) {
            if (fullName.length() > 0) {
                fullName.append(" ");
            }
            fullName.append(customerMiddleName.trim());
        }
        
        if (customerLastName != null && !customerLastName.trim().isEmpty()) {
            if (fullName.length() > 0) {
                fullName.append(" ");
            }
            fullName.append(customerLastName.trim());
        }
        
        return fullName.toString();
    }

    /**
     * Checks if customer information is populated in this COMMAREA.
     * Useful for determining if customer context is available for transaction processing.
     * 
     * @return true if customerId is populated, false otherwise
     */
    public boolean hasCustomerInfo() {
        return customerId != null && customerId > 0;
    }

    /**
     * Checks if account information is populated in this COMMAREA.
     * Useful for determining if account context is available for transaction processing.
     * 
     * @return true if accountId is populated, false otherwise
     */
    public boolean hasAccountInfo() {
        return accountId != null && accountId > 0;
    }

    /**
     * Checks if card information is populated in this COMMAREA.
     * Useful for determining if card context is available for transaction processing.
     * 
     * @return true if cardNumber is populated with valid digits, false otherwise
     */
    public boolean hasCardInfo() {
        return cardNumber != null && !cardNumber.trim().isEmpty() && cardNumber.matches("\\d+");
    }
}

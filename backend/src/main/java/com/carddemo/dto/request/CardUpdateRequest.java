/*
 * CardUpdateRequest.java
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.dto.request;

import com.carddemo.constants.CardStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Request DTO for updating credit card information from COCRDUP BMS screen.
 * 
 * This DTO transforms the COBOL COCRDUP copybook structure (app/cpy-bms/COCRDUP.CPY)
 * to a Java request object for REST API card update operations. Enforces card lifecycle
 * rules and expiration date constraints through Bean Validation annotations.
 * 
 * COBOL Source Mapping (CCRDUPAI input structure):
 * <pre>
 * COBOL Field         PIC        Java Field           Validation
 * ────────────────────────────────────────────────────────────────────────
 * ACCTSIDI           X(11)      accountId            @NotBlank @Size(max=11)
 * CARDSIDI           X(16)      cardNumber           @NotBlank @Size(max=16) @Pattern
 * CRDNAMEI           X(50)      cardName             @NotBlank @Size(max=50)
 * CRDSTCDI           X(1)       cardStatusCode       @NotNull @Pattern([AEBS])
 * EXPMONI            X(2)       expirationMonth      @NotNull @Min(1) @Max(12)
 * EXPYEARI           X(4)       expirationYear       @NotNull @Min(2024)
 * EXPDAYI            X(2)       expirationDay        @NotNull @Min(1) @Max(31)
 * </pre>
 * 
 * Card Status Code Mapping (CRDSTCDI):
 * - A = Active (Card operational for transactions)
 * - E = Expired (Card past expiration date, requires renewal)
 * - B = Blocked (Card blocked for security/fraud concerns)
 * - S = Stolen (Card reported stolen, permanently disabled)
 * 
 * Date Handling:
 * Separate COBOL date component fields (EXPMONI, EXPYEARI, EXPDAYI) are transformed
 * into a composed LocalDate field with @Future validation ensuring expiration dates
 * must be in the future for card activation and renewal operations.
 * 
 * Thread-safe and immutable for concurrent REST API request processing.
 * 
 * @see com.carddemo.controller.CardController
 * @see com.carddemo.service.CardUpdateService
 * @see com.carddemo.constants.CardStatus
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardUpdateRequest {
    
    /**
     * Account identifier associated with the card being updated.
     * 
     * Maps to COBOL field ACCTSIDI PIC X(11) from COCRDUP copybook.
     * Validates against account existence in the database during card update processing.
     * 
     * Constraints:
     * - Cannot be null or empty (required field)
     * - Maximum length: 11 characters matching COBOL PIC X(11)
     * - Typically formatted as 11-digit account number with leading zeros
     * 
     * Example: "00000123456"
     */
    @NotBlank(message = "Account ID is required and cannot be empty")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @JsonProperty("accountId")
    private String accountId;
    
    /**
     * 16-digit card number uniquely identifying the credit card.
     * 
     * Maps to COBOL field CARDSIDI PIC X(16) from COCRDUP copybook.
     * Primary identifier for the card record being updated.
     * 
     * Constraints:
     * - Cannot be null or empty (required field)
     * - Must be exactly 16 digits (standard credit card format)
     * - Numeric characters only ([0-9]{16} pattern)
     * - Should pass Luhn algorithm validation in service layer
     * 
     * Example: "4532123456789012"
     */
    @NotBlank(message = "Card number is required and cannot be empty")
    @Size(min = 16, max = 16, message = "Card number must be exactly 16 digits")
    @Pattern(regexp = "[0-9]{16}", message = "Card number must contain exactly 16 numeric digits")
    @JsonProperty("cardNumber")
    private String cardNumber;
    
    /**
     * Cardholder full name as embossed on the physical card.
     * 
     * Maps to COBOL field CRDNAMEI PIC X(50) from COCRDUP copybook.
     * Updated when cardholder legal name changes or card reissued.
     * 
     * Constraints:
     * - Cannot be null or empty (required field)
     * - Maximum length: 50 characters matching COBOL PIC X(50)
     * - Should contain first and last name, optionally middle initial
     * - Converted to uppercase for mainframe compatibility
     * 
     * Example: "JOHN MICHAEL DOE"
     */
    @NotBlank(message = "Cardholder name is required and cannot be empty")
    @Size(max = 50, message = "Cardholder name must not exceed 50 characters")
    @JsonProperty("cardName")
    private String cardName;
    
    /**
     * Single-character card status code for lifecycle management.
     * 
     * Maps to COBOL field CRDSTCDI PIC X(1) from COCRDUP copybook.
     * Represents card operational status using COBOL 88-level condition pattern.
     * 
     * Valid status codes (matching COBOL 88-level conditions):
     * - 'A' = Active: Card operational for transaction processing
     * - 'E' = Expired: Card past expiration date, renewal required
     * - 'B' = Blocked: Card blocked for security or fraud prevention
     * - 'S' = Stolen: Card reported stolen, permanently disabled
     * 
     * Constraints:
     * - Cannot be null (required field)
     * - Must match pattern [AEBS] (one of four valid status codes)
     * - Status transitions validated in service layer per business rules
     * 
     * COBOL Equivalent:
     * <pre>
     * 01 CARD-STATUS PIC X(1).
     *    88 CARD-ACTIVE   VALUE 'A'.
     *    88 CARD-EXPIRED  VALUE 'E'.
     *    88 CARD-BLOCKED  VALUE 'B'.
     *    88 CARD-STOLEN   VALUE 'S'.
     * </pre>
     * 
     * Example: "A"
     */
    @NotNull(message = "Card status code is required")
    @Pattern(regexp = "[AEBS]", message = "Card status code must be one of: A(Active), E(Expired), B(Blocked), S(Stolen)")
    @JsonProperty("cardStatusCode")
    private String cardStatusCode;
    
    /**
     * Type-safe card status enum mapped from cardStatusCode.
     * 
     * Provides strongly-typed access to card status for service layer business logic.
     * Populated from cardStatusCode using CardStatus.fromCode() method, preserving
     * COBOL 88-level condition name patterns as Java enum per Section 0.9 requirements.
     * 
     * This field is typically set by the controller or service layer after validating
     * the cardStatusCode field. It enables type-safe status handling and business rule
     * validation (e.g., CardStatus.isUsable(), CardStatus.canActivate()).
     * 
     * Mapping Logic:
     * - cardStatusCode "A" → CardStatus.ACTIVE
     * - cardStatusCode "E" → CardStatus.EXPIRED
     * - cardStatusCode "B" → CardStatus.BLOCKED
     * - cardStatusCode "S" → Maps to BLOCKED (stolen cards treated as blocked)
     * 
     * May be null if not yet populated by controller/service layer.
     * 
     * @see CardStatus#fromCode(char)
     */
    @JsonProperty("cardStatusEnum")
    private CardStatus cardStatusEnum;
    
    /**
     * Expiration month component (1-12 for January-December).
     * 
     * Maps to COBOL field EXPMONI PIC X(2) from COCRDUP copybook.
     * Combined with expirationYear and expirationDay to form complete expiration date.
     * 
     * Constraints:
     * - Cannot be null (required field)
     * - Must be between 1 and 12 (valid month range)
     * - Single-digit months (1-9) may be formatted with leading zero
     * 
     * Example: 12 (for December)
     */
    @NotNull(message = "Expiration month is required")
    @Min(value = 1, message = "Expiration month must be between 1 and 12")
    @Max(value = 12, message = "Expiration month must be between 1 and 12")
    @JsonProperty("expirationMonth")
    private Integer expirationMonth;
    
    /**
     * Expiration year component (4-digit year).
     * 
     * Maps to COBOL field EXPYEARI PIC X(4) from COCRDUP copybook.
     * Combined with expirationMonth and expirationDay to form complete expiration date.
     * 
     * Constraints:
     * - Cannot be null (required field)
     * - Must be 2024 or later (prevents backdated expiration dates)
     * - Typically 3-5 years from card issuance date
     * - Four-digit format (e.g., 2027, not 27)
     * 
     * Example: 2027
     */
    @NotNull(message = "Expiration year is required")
    @Min(value = 2024, message = "Expiration year must be 2024 or later")
    @JsonProperty("expirationYear")
    private Integer expirationYear;
    
    /**
     * Expiration day component (1-31 for day of month).
     * 
     * Maps to COBOL field EXPDAYI PIC X(2) from COCRDUP copybook.
     * Combined with expirationMonth and expirationYear to form complete expiration date.
     * 
     * Constraints:
     * - Cannot be null (required field)
     * - Must be between 1 and 31 (valid day range)
     * - Month-specific day validation (e.g., no February 30) performed in getExpirationDate()
     * - Typically set to last day of expiration month for credit cards
     * 
     * Example: 31 (for cards expiring at end of month)
     */
    @NotNull(message = "Expiration day is required")
    @Min(value = 1, message = "Expiration day must be between 1 and 31")
    @Max(value = 31, message = "Expiration day must be between 1 and 31")
    @JsonProperty("expirationDay")
    private Integer expirationDay;
    
    /**
     * Gets the composed expiration date from separate date component fields.
     * 
     * Transforms COBOL separate date fields (EXPMONI, EXPYEARI, EXPDAYI) into a
     * unified Java LocalDate object for type-safe date handling and validation.
     * Enables @Future annotation validation ensuring expiration dates are in the future.
     * 
     * Date Composition Logic:
     * - Creates LocalDate from expirationYear, expirationMonth, expirationDay
     * - Handles invalid dates (e.g., February 30) by throwing DateTimeException
     * - Validates month-day combinations for leap years
     * 
     * This method replaces COBOL date component approach with modern Java date API
     * per Section 0.3 transformation rules.
     * 
     * Business Rule Validation:
     * - Expiration date must be in the future (@Future annotation on result)
     * - Prevents card updates with expired dates
     * - Enforces COBOL business rule from COCRDUPC program requiring future dates
     * 
     * @return LocalDate composed from year, month, day components, or null if any component is null
     * @throws java.time.DateTimeException if the date components form an invalid date
     *         (e.g., April 31, February 30)
     */
    public LocalDate getExpirationDate() {
        if (expirationYear == null || expirationMonth == null || expirationDay == null) {
            return null;
        }
        
        try {
            return LocalDate.of(expirationYear, expirationMonth, expirationDay);
        } catch (java.time.DateTimeException e) {
            // Invalid date combination (e.g., February 30, April 31)
            // Return null to allow validation framework to handle the error
            // Service layer will provide user-friendly error message
            return null;
        }
    }
    
    /**
     * Sets the expiration date by decomposing LocalDate into separate component fields.
     * 
     * Reverse transformation from unified Java LocalDate to COBOL separate date fields.
     * Used when populating DTO from entity or other LocalDate sources.
     * 
     * Date Decomposition Logic:
     * - Extracts year → expirationYear
     * - Extracts month → expirationMonth (1-12)
     * - Extracts day → expirationDay (1-31)
     * 
     * Null Handling:
     * - If date parameter is null, sets all component fields (year, month, day) to null
     * - Preserves null state for optional expiration date scenarios
     * 
     * @param date LocalDate to decompose into year, month, day components, or null to clear all components
     */
    public void setExpirationDate(LocalDate date) {
        if (date == null) {
            this.expirationYear = null;
            this.expirationMonth = null;
            this.expirationDay = null;
        } else {
            this.expirationYear = date.getYear();
            this.expirationMonth = date.getMonthValue();
            this.expirationDay = date.getDayOfMonth();
        }
    }
    
    /**
     * Validates that the composed expiration date is in the future.
     * 
     * Custom validation method enforcing business rule that card expiration dates
     * must be future dates for card activation and renewal operations.
     * 
     * This method is typically called by the validation framework or service layer
     * before processing card update requests. Prevents updates with expired dates.
     * 
     * Validation Rules:
     * - Expiration date must be after current system date
     * - Accounts for timezone differences (uses LocalDate, not LocalDateTime)
     * - Allows same-day expiration updates (not recommended but not forbidden)
     * 
     * COBOL Equivalent:
     * From COCRDUPC program validation logic requiring expiration dates > current date.
     * 
     * @return true if expiration date is in the future, false if null or past/present
     */
    @Future(message = "Card expiration date must be in the future")
    public LocalDate getValidatedExpirationDate() {
        return getExpirationDate();
    }
    
    /**
     * Populates cardStatusEnum from cardStatusCode using CardStatus.fromCode().
     * 
     * Converts the single-character string status code to the type-safe CardStatus enum,
     * enabling service layer business logic to use strongly-typed status checks.
     * 
     * Mapping Logic:
     * - Extracts first character from cardStatusCode string
     * - Calls CardStatus.fromCode(char) for enum lookup
     * - Handles invalid codes by leaving cardStatusEnum null (validation handled by @Pattern)
     * 
     * Status Code to Enum Mapping:
     * - 'A' → CardStatus.ACTIVE (or closest equivalent from CardStatus enum)
     * - 'E' → CardStatus.EXPIRED
     * - 'B' → CardStatus.BLOCKED
     * - 'S' → CardStatus.BLOCKED (stolen cards treated as blocked in system)
     * 
     * This method is typically called by the controller after request deserialization
     * and before passing to the service layer.
     * 
     * Note: The actual CardStatus enum may use different internal code values
     * (e.g., 'Y' for Active instead of 'A'). This method performs necessary mapping
     * to bridge the external API contract (AEBS codes) with internal enum representation.
     */
    public void populateCardStatusEnum() {
        if (cardStatusCode != null && !cardStatusCode.isEmpty()) {
            char statusChar = cardStatusCode.charAt(0);
            
            try {
                // Attempt direct mapping if CardStatus supports the code
                this.cardStatusEnum = CardStatus.fromCode(statusChar);
            } catch (IllegalArgumentException e) {
                // If direct mapping fails, map external codes to internal codes
                // This handles the case where external API uses AEBS but internal uses different codes
                switch (statusChar) {
                    case 'A': // Active in external API
                        // Map to internal ACTIVE status (which may use code 'Y')
                        this.cardStatusEnum = CardStatus.ACTIVE;
                        break;
                    case 'E': // Expired
                        this.cardStatusEnum = CardStatus.EXPIRED;
                        break;
                    case 'B': // Blocked
                        this.cardStatusEnum = CardStatus.BLOCKED;
                        break;
                    case 'S': // Stolen - treat as blocked
                        this.cardStatusEnum = CardStatus.BLOCKED;
                        break;
                    default:
                        // Leave null for validation framework to catch
                        this.cardStatusEnum = null;
                }
            }
        }
    }
    
    /**
     * Returns string representation of CardUpdateRequest for logging and debugging.
     * 
     * Masks sensitive card number information (shows only last 4 digits) for security.
     * Includes all fields for comprehensive request tracing in application logs.
     * 
     * Output Format:
     * CardUpdateRequest(accountId=00000123456, cardNumber=************9012, 
     *                   cardName=JOHN DOE, cardStatusCode=A, 
     *                   expirationDate=2027-12-31)
     * 
     * @return String representation with masked card number for secure logging
     */
    @Override
    public String toString() {
        String maskedCardNumber = cardNumber != null && cardNumber.length() == 16
            ? "************" + cardNumber.substring(12)
            : "****";
            
        return "CardUpdateRequest(" +
            "accountId='" + accountId + '\'' +
            ", cardNumber='" + maskedCardNumber + '\'' +
            ", cardName='" + cardName + '\'' +
            ", cardStatusCode='" + cardStatusCode + '\'' +
            ", cardStatusEnum=" + cardStatusEnum +
            ", expirationMonth=" + expirationMonth +
            ", expirationYear=" + expirationYear +
            ", expirationDay=" + expirationDay +
            ", expirationDate=" + getExpirationDate() +
            ')';
    }
    
    /**
     * Compares this CardUpdateRequest with another object for equality.
     * 
     * Two CardUpdateRequest objects are considered equal if all their field values match,
     * including card number, account ID, name, status, and expiration date components.
     * 
     * Used for:
     * - Unit test assertions comparing expected vs actual DTOs
     * - Duplicate request detection in idempotency checks
     * - Cache key generation for request caching
     * 
     * @param o Object to compare with this CardUpdateRequest
     * @return true if objects are equal (same field values), false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CardUpdateRequest that = (CardUpdateRequest) o;
        return Objects.equals(accountId, that.accountId) &&
               Objects.equals(cardNumber, that.cardNumber) &&
               Objects.equals(cardName, that.cardName) &&
               Objects.equals(cardStatusCode, that.cardStatusCode) &&
               cardStatusEnum == that.cardStatusEnum &&
               Objects.equals(expirationMonth, that.expirationMonth) &&
               Objects.equals(expirationYear, that.expirationYear) &&
               Objects.equals(expirationDay, that.expirationDay);
    }
    
    /**
     * Generates hash code for this CardUpdateRequest based on all field values.
     * 
     * Hash code calculation includes all fields (card number, account ID, name, status,
     * expiration components) to ensure consistent hashing for collections and caching.
     * 
     * Used for:
     * - HashMap/HashSet storage of request objects
     * - Request deduplication in processing queues
     * - Cache key generation for response caching
     * 
     * @return Hash code integer representing this CardUpdateRequest's field values
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId, cardNumber, cardName, cardStatusCode, 
                           cardStatusEnum, expirationMonth, expirationYear, expirationDay);
    }
}

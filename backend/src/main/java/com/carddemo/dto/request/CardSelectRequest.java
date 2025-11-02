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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for credit card selection and retrieval operations.
 * 
 * <p>This class captures account and card identifiers from the COCRDSL BMS screen
 * (Card Select Screen) for card detail view and subsequent operations. It supports
 * both individual card lookup by card number and account-based card list retrieval
 * by account ID.</p>
 * 
 * <p>Transformed from COBOL copybook: app/cpy-bms/COCRDSL.CPY</p>
 * 
 * <h3>Field Mappings from COBOL BMS Fields:</h3>
 * <ul>
 *   <li>ACCTSIDI PIC X(11) → accountId (String, 11 numeric characters)</li>
 *   <li>CARDSIDI PIC X(16) → cardNumber (String, 16 numeric characters)</li>
 *   <li>CRDNAMEI PIC X(50) → cardName (String, max 50 characters, optional filter)</li>
 *   <li>CRDSTCDI PIC X(1) → cardStatusCode (String, 1 uppercase letter, optional filter)</li>
 *   <li>EXPMONI PIC X(2) → expirationMonth (Integer, 1-12, optional filter)</li>
 *   <li>EXPYEARI PIC X(4) → expirationYear (Integer, 2020-2099, optional filter)</li>
 * </ul>
 * 
 * <h3>Pagination Support:</h3>
 * <p>Includes pageNumber and pageSize fields for pagination matching the COBOL screen
 * display pattern of 7 cards per page as specified in Section 0.1 of the migration plan.</p>
 * 
 * <h3>Usage Scenarios:</h3>
 * <ol>
 *   <li><b>Individual Card Lookup:</b> Populate cardNumber to retrieve specific card details</li>
 *   <li><b>Account Card List:</b> Populate accountId to retrieve all cards for an account</li>
 *   <li><b>Filtered Search:</b> Combine accountId with optional filter criteria (cardName,
 *       cardStatusCode, expirationMonth, expirationYear) for refined searches</li>
 * </ol>
 * 
 * @see com.carddemo.controller.CardController
 * @see com.carddemo.service.CardDetailService
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardSelectRequest {

    /**
     * Account identifier for retrieving all cards associated with an account.
     * 
     * <p>Corresponds to ACCTSIDI field in COCRDSL BMS copybook.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field (cannot be blank when used for account-based retrieval)</li>
     *   <li>Must be exactly 11 characters in length</li>
     *   <li>Must contain only numeric digits (0-9)</li>
     *   <li>PIC clause enforcement: COBOL PIC X(11) → @Size(max=11)</li>
     * </ul>
     * 
     * <p><b>Example:</b> "00012345678"</p>
     */
    @JsonProperty("accountId")
    @NotBlank(message = "Account ID cannot be blank")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @Pattern(regexp = "^[0-9]{11}$", message = "Account ID must be exactly 11 numeric digits")
    private String accountId;

    /**
     * Credit card number for individual card lookup operations.
     * 
     * <p>Corresponds to CARDSIDI field in COCRDSL BMS copybook.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional field (can be null for account-based list retrieval)</li>
     *   <li>When provided, must be exactly 16 characters in length</li>
     *   <li>Must contain only numeric digits (0-9)</li>
     *   <li>PIC clause enforcement: COBOL PIC X(16) → @Size(max=16)</li>
     * </ul>
     * 
     * <p><b>Example:</b> "4111111111111111"</p>
     * 
     * <p><b>Note:</b> This field is optional to support both individual card selection
     * and account-based card list retrieval scenarios.</p>
     */
    @JsonProperty("cardNumber")
    @Size(max = 16, message = "Card number must not exceed 16 characters")
    @Pattern(regexp = "^[0-9]{16}$", message = "Card number must be exactly 16 numeric digits", 
             groups = {ValidationOnPresent.class})
    private String cardNumber;

    /**
     * Card holder name or card description for filtering card lists.
     * 
     * <p>Corresponds to CRDNAMEI field in COCRDSL BMS copybook.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional filter field</li>
     *   <li>Maximum length: 50 characters</li>
     *   <li>PIC clause enforcement: COBOL PIC X(50) → @Size(max=50)</li>
     * </ul>
     * 
     * <p><b>Example:</b> "JOHN DOE VISA PLATINUM"</p>
     * 
     * <p><b>Use Case:</b> When retrieving multiple cards for an account, this field
     * can be used to filter results by card name or description.</p>
     */
    @JsonProperty("cardName")
    @Size(max = 50, message = "Card name must not exceed 50 characters")
    private String cardName;

    /**
     * Card status code for filtering cards by their current status.
     * 
     * <p>Corresponds to CRDSTCDI field in COCRDSL BMS copybook.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional filter field</li>
     *   <li>Must be a single uppercase letter (A-Z)</li>
     *   <li>PIC clause enforcement: COBOL PIC X(1) → @Size(max=1)</li>
     * </ul>
     * 
     * <p><b>Valid Status Codes (from CardStatus enum):</b></p>
     * <ul>
     *   <li>'A' - Active</li>
     *   <li>'E' - Expired</li>
     *   <li>'B' - Blocked</li>
     * </ul>
     * 
     * <p><b>Example:</b> "A" (Active cards only)</p>
     * 
     * <p><b>Use Case:</b> Filter card lists to show only cards with specific status,
     * such as displaying only active cards to users.</p>
     */
    @JsonProperty("cardStatusCode")
    @Pattern(regexp = "^[A-Z]$", message = "Card status code must be a single uppercase letter (A-Z)", 
             groups = {ValidationOnPresent.class})
    private String cardStatusCode;

    /**
     * Expiration month for filtering cards by expiration date.
     * 
     * <p>Corresponds to EXPMONI field in COCRDSL BMS copybook.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional filter field</li>
     *   <li>Must be between 1 and 12 (inclusive) representing calendar months</li>
     *   <li>PIC clause reference: COBOL PIC X(2) → Integer with @Min/@Max constraints</li>
     * </ul>
     * 
     * <p><b>Valid Range:</b> 1 (January) to 12 (December)</p>
     * 
     * <p><b>Example:</b> 12 (December)</p>
     * 
     * <p><b>Use Case:</b> Combined with expirationYear to filter cards expiring in a
     * specific month/year, useful for expiration notification processing.</p>
     */
    @JsonProperty("expirationMonth")
    @Min(value = 1, message = "Expiration month must be at least 1 (January)")
    @Max(value = 12, message = "Expiration month must be at most 12 (December)")
    private Integer expirationMonth;

    /**
     * Expiration year for filtering cards by expiration date.
     * 
     * <p>Corresponds to EXPYEARI field in COCRDSL BMS copybook.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional filter field</li>
     *   <li>Must be between 2020 and 2099 (inclusive) representing valid card expiration years</li>
     *   <li>PIC clause reference: COBOL PIC X(4) → Integer with @Min/@Max constraints</li>
     * </ul>
     * 
     * <p><b>Valid Range:</b> 2020 to 2099</p>
     * 
     * <p><b>Example:</b> 2025</p>
     * 
     * <p><b>Rationale:</b> The minimum year of 2020 prevents unrealistic historical dates,
     * while maximum of 2099 covers reasonable future card issuance horizon and prevents
     * date calculation errors from unrealistic values.</p>
     * 
     * <p><b>Use Case:</b> Combined with expirationMonth to filter cards expiring in a
     * specific month/year for batch expiration processing and renewal campaigns.</p>
     */
    @JsonProperty("expirationYear")
    @Min(value = 2020, message = "Expiration year must be at least 2020")
    @Max(value = 2099, message = "Expiration year must be at most 2099")
    private Integer expirationYear;

    /**
     * Page number for paginated card list results.
     * 
     * <p>Supports pagination matching the COBOL screen display pattern specified in
     * Section 0.1 of the migration plan.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional field (defaults to 0 if not specified)</li>
     *   <li>Zero-based page numbering (0 = first page, 1 = second page, etc.)</li>
     *   <li>Must be non-negative</li>
     * </ul>
     * 
     * <p><b>Default:</b> 0 (first page)</p>
     * 
     * <p><b>Example:</b> 2 (third page of results)</p>
     * 
     * <p><b>Implementation Note:</b> Works in conjunction with pageSize to implement
     * pagination equivalent to the COBOL screen's sequential display pattern.</p>
     */
    @JsonProperty("pageNumber")
    @Min(value = 0, message = "Page number must be non-negative (zero-based indexing)")
    private Integer pageNumber;

    /**
     * Number of cards to display per page in paginated results.
     * 
     * <p>Supports pagination matching the COBOL screen display pattern of 7 cards per page
     * as specified in Section 0.1 of the migration plan.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional field (defaults to 7 if not specified, matching COBOL screen pattern)</li>
     *   <li>Must be positive (at least 1)</li>
     *   <li>Recommended maximum: 100 to prevent performance degradation</li>
     * </ul>
     * 
     * <p><b>Default:</b> 7 (matching COBOL COCRDSL screen display capacity)</p>
     * 
     * <p><b>Example:</b> 7 (seven cards per page, preserving COBOL screen behavior)</p>
     * 
     * <p><b>Migration Note:</b> The default of 7 cards per page directly corresponds to
     * the BMS screen layout capacity in the original COBOL application, maintaining
     * functional equivalence per Section 0.2 requirements.</p>
     */
    @JsonProperty("pageSize")
    @Min(value = 1, message = "Page size must be at least 1")
    @Max(value = 100, message = "Page size must not exceed 100 to ensure reasonable response times")
    private Integer pageSize;

    /**
     * Marker interface for conditional validation.
     * 
     * <p>This validation group is used for fields that should only be validated
     * when they are present (not null). For optional fields like cardNumber and
     * cardStatusCode, pattern validation should only apply when a value is provided.</p>
     * 
     * <p><b>Usage:</b> Applied to @Pattern annotations with groups parameter to
     * enable validation only when field has a non-null value.</p>
     * 
     * <p><b>Example:</b> cardNumber pattern validation only executes when cardNumber
     * is not null, allowing for optional card number in account-based searches.</p>
     */
    public interface ValidationOnPresent {
        // Marker interface for validation groups
    }
}

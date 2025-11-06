/*
 * CardResponse.java
 *
 * Card information response Data Transfer Object (DTO) containing card details 
 * with PCI DSS Level 1 compliant card number masking for secure API responses.
 *
 * This class transforms VSAM KSDS card master file record (CVACT02Y.cpy CARD-RECORD)
 * to JSON response format for RESTful API endpoints, replacing mainframe CICS 
 * transaction screen output with modern cloud-native HTTP responses.
 *
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
 */
package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Card Response DTO
 * 
 * Represents card information in API responses with PCI DSS compliant security measures.
 * This DTO maps the COBOL CARD-RECORD structure from CVACT02Y.cpy to JSON format for
 * Spring Boot REST API endpoints.
 * 
 * <h2>COBOL Source Mapping</h2>
 * Maps VSAM KSDS card master file record (RECLN 150) to JSON response:
 * <pre>
 * COBOL Field                    Java Field         Type Transformation
 * ────────────────────────────────────────────────────────────────────────
 * CARD-NUM (PIC X(16))          cardNumber         String (MASKED)
 * CARD-ACCT-ID (PIC 9(11))      accountId          Long
 * CARD-CVV-CD (PIC 9(03))       cvv                String
 * CARD-EMBOSSED-NAME (PIC X(50)) embossedName      String
 * CARD-EXPIRAION-DATE (PIC X(10)) expirationDate   LocalDate (yyyy-MM-dd)
 * CARD-ACTIVE-STATUS (PIC X(01)) activeStatus      String ('Y' or 'N')
 * </pre>
 * 
 * <h2>PCI DSS Compliance Requirements</h2>
 * <ul>
 *   <li><b>Card Number Masking:</b> Full Primary Account Number (PAN) MUST NEVER be 
 *       transmitted in API responses. Only last 4 digits visible (e.g., "************1234").
 *       Masking performed by service layer using StringUtils.maskCardNumber() before 
 *       DTO mapping.</li>
 *   <li><b>CVV Display Restrictions:</b> Card Verification Value should only be returned 
 *       for authorized update operations by administrative users. CVV MUST be masked or 
 *       omitted in list views to comply with PCI DSS Requirement 3.2.</li>
 *   <li><b>Storage Prohibition:</b> Full unmasked PAN and CVV MUST NEVER be stored in 
 *       browser localStorage, sessionStorage, cookies, or any client-side cache.</li>
 *   <li><b>Transmission Security:</b> All API endpoints serving this DTO MUST use HTTPS/TLS 
 *       encryption (TLS 1.2 or higher) to protect data in transit.</li>
 * </ul>
 * 
 * <h2>BMS Mapset Correspondence</h2>
 * <ul>
 *   <li><b>COCRDLI.bms (CCLI transaction):</b> Card list display showing 7 cards per page
 *       with pagination. Transformed to GET /api/cards endpoint with Pageable support.</li>
 *   <li><b>COCRDSL.bms (CCDL transaction):</b> Card detail view screen displaying single
 *       card information. Transformed to GET /api/cards/{cardNumber} endpoint.</li>
 *   <li><b>COCRDUP.bms (CCUP transaction):</b> Card update screen for modifying card
 *       attributes. Transformed to PUT /api/cards/{cardNumber} endpoint.</li>
 * </ul>
 * 
 * <h2>COBOL Program Transformation</h2>
 * <ul>
 *   <li><b>COCRDLIC.cbl (CCLI transaction):</b> Card list program using VSAM STARTBR/READNEXT
 *       browse operation. Replaced by CardListService.getCardsByAccount() using Spring Data
 *       JPA Pageable query with page size 7 to match BMS pagination pattern.</li>
 *   <li><b>COCRDSLC.cbl (CCDL transaction):</b> Card detail retrieval program using VSAM
 *       READ operation. Replaced by CardDetailService.getCardDetails() using 
 *       CardRepository.findByCardNumber() JPA query method.</li>
 *   <li><b>COCRDUPC.cbl (CCUP transaction):</b> Card update program using VSAM REWRITE
 *       operation with @Transactional boundary. Replaced by CardUpdateService.updateCard()
 *       using CardRepository.save() with Spring transaction management.</li>
 * </ul>
 * 
 * <h2>Data Validation Rules</h2>
 * <ul>
 *   <li><b>Card Number:</b> Must be masked format (12 asterisks + 4 digits). Service layer
 *       validates full 16-digit format before masking.</li>
 *   <li><b>Account ID:</b> Must be valid 11-digit account number with existing foreign key
 *       relationship to Account entity. Replaces VSAM cross-reference file CXACAIX with
 *       PostgreSQL foreign key constraint.</li>
 *   <li><b>CVV:</b> 3-digit numeric code. Only returned for authenticated users with proper
 *       authorization.</li>
 *   <li><b>Embossed Name:</b> Cardholder name as printed on physical card, max 50 characters.
 *       Must match customer first and last name from linked account.</li>
 *   <li><b>Expiration Date:</b> Must be future date. Expired cards with activeStatus='N'
 *       cannot be used for transactions. Service layer validates date is after current date.</li>
 *   <li><b>Active Status:</b> Single character 'Y' (active) or 'N' (inactive). Inactive cards
 *       are blocked from transaction authorization in TransactionAddService.</li>
 * </ul>
 * 
 * <h2>API Usage Examples</h2>
 * <ul>
 *   <li><b>GET /api/cards:</b> Returns paginated List&lt;CardResponse&gt; with 7 cards per page,
 *       served by CardListService to CardController, consumed by React CardListComponent.</li>
 *   <li><b>GET /api/cards/{cardNumber}:</b> Returns single CardResponse with full card details,
 *       served by CardDetailService to CardController, consumed by React CardDetailComponent.</li>
 *   <li><b>PUT /api/cards/{cardNumber}:</b> Returns updated CardResponse after successful
 *       modification, served by CardUpdateService to CardController, consumed by React
 *       CardUpdateComponent.</li>
 * </ul>
 * 
 * <h2>Foreign Key Relationships</h2>
 * The accountId field establishes referential integrity with the Account entity, replacing
 * the mainframe VSAM cross-reference file CXACAIX.cpy with PostgreSQL foreign key constraint:
 * <pre>
 * ALTER TABLE card ADD CONSTRAINT fk_card_account 
 *   FOREIGN KEY (account_id) REFERENCES account(account_id);
 * </pre>
 * This ensures data consistency and enables efficient card-to-account navigation through
 * JPA @ManyToOne relationship in the Card entity.
 * 
 * <h2>React Component Integration</h2>
 * This DTO is consumed by React frontend components:
 * <ul>
 *   <li><b>CardListComponent.jsx:</b> Displays paginated card list in table format, showing
 *       masked card numbers, embossed names, and expiration dates with status indicators.</li>
 *   <li><b>CardDetailComponent.jsx:</b> Shows complete card details in read-only view with
 *       formatted dates and highlighted status values.</li>
 *   <li><b>CardUpdateComponent.jsx:</b> Provides form for updating card attributes with
 *       client-side validation matching server-side Bean Validation constraints.</li>
 * </ul>
 * 
 * @see com.carddemo.entity.Card JPA entity representing card table
 * @see com.carddemo.service.card.CardListService Service for card list operations
 * @see com.carddemo.service.card.CardDetailService Service for card detail operations
 * @see com.carddemo.service.card.CardUpdateService Service for card update operations
 * @see com.carddemo.controller.CardController REST controller for card endpoints
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "cardNumber",
    "accountId",
    "cvv",
    "embossedName",
    "expirationDate",
    "activeStatus"
})
public class CardResponse {

    /**
     * Masked Card Number (Primary Account Number - PAN)
     * 
     * <p>Contains the masked 16-digit card number displaying only the last 4 digits
     * for PCI DSS Level 1 compliance. Format: "************1234" where asterisks
     * replace the first 12 digits of the actual card number.
     * 
     * <p><b>COBOL Source:</b> CARD-NUM (PIC X(16)) from CVACT02Y.cpy CARD-RECORD
     * 
     * <p><b>Security Requirements:</b>
     * <ul>
     *   <li>Full unmasked PAN MUST NEVER appear in API responses</li>
     *   <li>Masking performed by service layer before DTO construction</li>
     *   <li>Only authorized users can view even masked format</li>
     *   <li>Complies with PCI DSS Requirement 3.3 for display of PAN</li>
     * </ul>
     * 
     * <p><b>Validation Rules:</b>
     * <ul>
     *   <li>Must match pattern: 12 asterisks followed by 4 digits</li>
     *   <li>Last 4 digits used for card identification in UI</li>
     *   <li>Service layer validates full 16-digit format before masking</li>
     * </ul>
     * 
     * <p><b>Example Values:</b>
     * <ul>
     *   <li>"************1234" - Visa card ending in 1234</li>
     *   <li>"************5678" - Mastercard ending in 5678</li>
     *   <li>"************9012" - Amex card ending in 9012</li>
     * </ul>
     * 
     * @see com.carddemo.util.StringUtils#maskCardNumber(String) Masking utility method
     */
    @JsonProperty("cardNumber")
    private String cardNumber;

    /**
     * Account ID (Foreign Key)
     * 
     * <p>11-digit account identifier linking this card to its parent account record.
     * Replaces VSAM cross-reference file CXACAIX with PostgreSQL foreign key constraint
     * ensuring referential integrity.
     * 
     * <p><b>COBOL Source:</b> CARD-ACCT-ID (PIC 9(11)) from CVACT02Y.cpy CARD-RECORD
     * 
     * <p><b>Database Mapping:</b>
     * <ul>
     *   <li>PostgreSQL column: account_id BIGINT NOT NULL</li>
     *   <li>Foreign key constraint: REFERENCES account(account_id)</li>
     *   <li>Index: idx_card_account_id for efficient lookups</li>
     * </ul>
     * 
     * <p><b>Referential Integrity:</b>
     * <ul>
     *   <li>Must reference existing account in account table</li>
     *   <li>Enables card-to-account navigation in JPA entities</li>
     *   <li>Cascade rules prevent orphaned card records</li>
     * </ul>
     * 
     * <p><b>Business Rules:</b>
     * <ul>
     *   <li>Multiple cards can link to same account (one-to-many)</li>
     *   <li>Each card must have exactly one parent account</li>
     *   <li>Account must be active for card to process transactions</li>
     * </ul>
     * 
     * <p><b>Example Values:</b>
     * <ul>
     *   <li>12345678901L - Primary account</li>
     *   <li>98765432109L - Secondary account</li>
     * </ul>
     */
    @JsonProperty("accountId")
    private Long accountId;

    /**
     * Card Verification Value (CVV)
     * 
     * <p>3-digit security code printed on the card for transaction verification.
     * Sensitive data that should only be returned for authorized operations.
     * 
     * <p><b>COBOL Source:</b> CARD-CVV-CD (PIC 9(03)) from CVACT02Y.cpy CARD-RECORD
     * 
     * <p><b>Security Requirements:</b>
     * <ul>
     *   <li>PCI DSS Requirement 3.2: CVV must not be stored after authorization</li>
     *   <li>Only returned for card update operations by admin users</li>
     *   <li>Must be masked (e.g., "***") in list views</li>
     *   <li>NEVER logged or stored in client-side cache</li>
     * </ul>
     * 
     * <p><b>Validation Rules:</b>
     * <ul>
     *   <li>Must be exactly 3 numeric digits</li>
     *   <li>Valid range: 000-999</li>
     *   <li>Required for card creation and updates</li>
     * </ul>
     * 
     * <p><b>Usage Context:</b>
     * <ul>
     *   <li>CardDetailService: Returns actual CVV for authorized users</li>
     *   <li>CardListService: Returns masked CVV ("***") in list views</li>
     *   <li>CardUpdateService: Accepts CVV for validation during updates</li>
     * </ul>
     * 
     * <p><b>Example Values:</b>
     * <ul>
     *   <li>"123" - Valid CVV for card creation/update</li>
     *   <li>"***" - Masked CVV for display in lists</li>
     * </ul>
     */
    @JsonProperty("cvv")
    private String cvv;

    /**
     * Embossed Name (Cardholder Name)
     * 
     * <p>Cardholder name as printed/embossed on the physical card, typically matching
     * the customer's first and last name from the linked account.
     * 
     * <p><b>COBOL Source:</b> CARD-EMBOSSED-NAME (PIC X(50)) from CVACT02Y.cpy CARD-RECORD
     * 
     * <p><b>Validation Rules:</b>
     * <ul>
     *   <li>Maximum length: 50 characters</li>
     *   <li>Must contain only letters, spaces, hyphens, and apostrophes</li>
     *   <li>Should match customer name from linked account</li>
     *   <li>Converted to uppercase for consistency with physical card</li>
     * </ul>
     * 
     * <p><b>Business Rules:</b>
     * <ul>
     *   <li>Name must match account holder for primary cards</li>
     *   <li>Authorized user names allowed for secondary cards</li>
     *   <li>Format typically: "FIRSTNAME LASTNAME" in uppercase</li>
     * </ul>
     * 
     * <p><b>Display Format:</b>
     * <ul>
     *   <li>React components display in Title Case for readability</li>
     *   <li>Physical cards show in ALL CAPS</li>
     *   <li>Trimmed of leading/trailing spaces before display</li>
     * </ul>
     * 
     * <p><b>Example Values:</b>
     * <ul>
     *   <li>"JOHN DOE" - Primary cardholder</li>
     *   <li>"JANE SMITH-WILLIAMS" - Hyphenated name</li>
     *   <li>"MARY O'BRIEN" - Name with apostrophe</li>
     * </ul>
     */
    @JsonProperty("embossedName")
    private String embossedName;

    /**
     * Card Expiration Date
     * 
     * <p>Date when the card expires and can no longer be used for transactions.
     * Stored in ISO 8601 format (yyyy-MM-dd) for consistent date handling across
     * Java backend and React frontend.
     * 
     * <p><b>COBOL Source:</b> CARD-EXPIRAION-DATE (PIC X(10)) from CVACT02Y.cpy CARD-RECORD
     * 
     * <p><b>Date Format Transformation:</b>
     * <ul>
     *   <li>COBOL: Alphanumeric field "YYYY-MM-DD" (PIC X(10))</li>
     *   <li>Java: LocalDate with @JsonFormat(pattern = "yyyy-MM-dd")</li>
     *   <li>JSON: ISO 8601 string "2025-12-31"</li>
     *   <li>React: JavaScript Date object parsed from ISO string</li>
     * </ul>
     * 
     * <p><b>Validation Rules:</b>
     * <ul>
     *   <li>Must be future date (after current date)</li>
     *   <li>Typically set to last day of expiry month</li>
     *   <li>Cards expire at end of month shown (e.g., 12/2025 = 2025-12-31)</li>
     *   <li>Service layer validates date is not past before allowing transactions</li>
     * </ul>
     * 
     * <p><b>Business Rules:</b>
     * <ul>
     *   <li>Expired cards automatically set to activeStatus='N'</li>
     *   <li>Warning issued 60 days before expiration</li>
     *   <li>Replacement cards issued 30 days before expiration</li>
     *   <li>Transactions blocked on expiration date</li>
     * </ul>
     * 
     * <p><b>Display Format:</b>
     * <ul>
     *   <li>BMS Screen: MM/YY format (e.g., "12/25")</li>
     *   <li>React Component: "MM/YYYY" format (e.g., "12/2025")</li>
     *   <li>API JSON: "yyyy-MM-dd" format (e.g., "2025-12-31")</li>
     * </ul>
     * 
     * <p><b>Example Values:</b>
     * <ul>
     *   <li>2025-12-31 - Card expires end of December 2025</li>
     *   <li>2026-06-30 - Card expires end of June 2026</li>
     * </ul>
     */
    @JsonProperty("expirationDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expirationDate;

    /**
     * Card Active Status
     * 
     * <p>Single character flag indicating whether the card is active and can be used
     * for transactions. Inactive cards are blocked from authorization.
     * 
     * <p><b>COBOL Source:</b> CARD-ACTIVE-STATUS (PIC X(01)) from CVACT02Y.cpy CARD-RECORD
     * 
     * <p><b>Valid Values:</b>
     * <ul>
     *   <li><b>"Y"</b> - Active: Card can be used for transactions</li>
     *   <li><b>"N"</b> - Inactive: Card is blocked, cannot authorize transactions</li>
     * </ul>
     * 
     * <p><b>Status Change Triggers:</b>
     * <ul>
     *   <li>Expiration date passed → Auto-set to 'N'</li>
     *   <li>Card reported lost/stolen → Admin sets to 'N'</li>
     *   <li>Fraud detection alert → System sets to 'N'</li>
     *   <li>Account closure → All cards set to 'N'</li>
     *   <li>Card replacement issued → Old card set to 'N'</li>
     * </ul>
     * 
     * <p><b>Business Rules:</b>
     * <ul>
     *   <li>Only active cards ('Y') can authorize transactions</li>
     *   <li>Status change requires admin authorization</li>
     *   <li>Audit log records all status changes</li>
     *   <li>Inactive cards still visible in card lists</li>
     * </ul>
     * 
     * <p><b>Display Representation:</b>
     * <ul>
     *   <li>React UI: "Active" or "Inactive" badge with color coding</li>
     *   <li>Active cards: Green badge with checkmark icon</li>
     *   <li>Inactive cards: Red badge with X icon</li>
     * </ul>
     * 
     * <p><b>Transaction Authorization:</b>
     * TransactionAddService validates activeStatus='Y' before processing transactions.
     * Transactions attempted with inactive cards return error:
     * "Card is inactive and cannot be used for transactions"
     * 
     * <p><b>Example Values:</b>
     * <ul>
     *   <li>"Y" - Card is active, can process transactions</li>
     *   <li>"N" - Card is inactive, transactions blocked</li>
     * </ul>
     */
    @JsonProperty("activeStatus")
    private String activeStatus;

    /**
     * Default Constructor
     * 
     * <p>Provided by Lombok @NoArgsConstructor annotation.
     * Required for Jackson JSON deserialization and JPA entity conversion.
     */
    // Generated by Lombok @NoArgsConstructor

    /**
     * All-Arguments Constructor
     * 
     * <p>Provided by Lombok @AllArgsConstructor annotation.
     * Enables construction with all fields for testing and explicit initialization.
     * 
     * @param cardNumber Masked 16-character card number
     * @param accountId 11-digit account identifier (foreign key)
     * @param cvv 3-digit card verification value
     * @param embossedName Cardholder name on card (max 50 chars)
     * @param expirationDate Card expiry date (ISO 8601 format)
     * @param activeStatus Card status flag ('Y' or 'N')
     */
    // Generated by Lombok @AllArgsConstructor

    /**
     * Builder Pattern Constructor
     * 
     * <p>Provided by Lombok @Builder annotation.
     * Enables fluent construction for readable service layer code:
     * 
     * <pre>
     * CardResponse response = CardResponse.builder()
     *     .cardNumber(maskedNumber)
     *     .accountId(accountId)
     *     .cvv(cvv)
     *     .embossedName(name)
     *     .expirationDate(expiryDate)
     *     .activeStatus(status)
     *     .build();
     * </pre>
     * 
     * Used extensively in CardListService, CardDetailService, and CardUpdateService
     * when mapping Card entities to CardResponse DTOs for API responses.
     * 
     * @return CardResponse.CardResponseBuilder instance for fluent construction
     */
    // Generated by Lombok @Builder

    /**
     * Getter Methods
     * 
     * <p>Generated by Lombok @Data annotation.
     * Provides standard JavaBean getter methods for all fields:
     * <ul>
     *   <li>getCardNumber() - Returns masked card number</li>
     *   <li>getAccountId() - Returns account foreign key</li>
     *   <li>getCvv() - Returns card verification value</li>
     *   <li>getEmbossedName() - Returns cardholder name</li>
     *   <li>getExpirationDate() - Returns expiration date</li>
     *   <li>getActiveStatus() - Returns status flag</li>
     * </ul>
     * 
     * Used by Jackson for JSON serialization and by React components for data access.
     */
    // Generated by Lombok @Data

    /**
     * Setter Methods
     * 
     * <p>Generated by Lombok @Data annotation.
     * Provides standard JavaBean setter methods for all fields:
     * <ul>
     *   <li>setCardNumber(String) - Sets masked card number</li>
     *   <li>setAccountId(Long) - Sets account foreign key</li>
     *   <li>setCvv(String) - Sets card verification value</li>
     *   <li>setEmbossedName(String) - Sets cardholder name</li>
     *   <li>setExpirationDate(LocalDate) - Sets expiration date</li>
     *   <li>setActiveStatus(String) - Sets status flag</li>
     * </ul>
     * 
     * Used by Jackson for JSON deserialization and by service layer for DTO construction.
     */
    // Generated by Lombok @Data

    /**
     * equals() and hashCode() Methods
     * 
     * <p>Generated by Lombok @Data annotation.
     * Provides proper equality comparison and hash code generation based on all fields.
     * 
     * Used for DTO comparison in tests and collection operations.
     */
    // Generated by Lombok @Data

    /**
     * toString() Method
     * 
     * <p>Generated by Lombok @Data annotation.
     * Provides string representation including all field values for debugging.
     * 
     * <b>Security Note:</b> CVV and full card number are never included in toString()
     * output in production logging. Service layer masks sensitive data before logging.
     */
    // Generated by Lombok @Data
}

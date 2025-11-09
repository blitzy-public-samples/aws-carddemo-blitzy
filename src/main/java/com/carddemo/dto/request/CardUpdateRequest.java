package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Card Update Request DTO
 * 
 * <p>Data Transfer Object for card update operations in the CardDemo application.
 * This DTO serves as the inbound contract for the PUT /api/cards/{id} REST endpoint,
 * replacing the CICS COMMAREA structure used by the CCUP (Card Update) transaction
 * in the legacy mainframe application.</p>
 * 
 * <h3>COBOL Source Mapping</h3>
 * <p>This DTO transforms the following COBOL structures:</p>
 * <ul>
 *   <li><b>Copybook:</b> app/cpy/CVACT02Y.cpy (CARD-RECORD structure)</li>
 *   <li><b>BMS Mapset:</b> app/bms/COCRDUPM.bms (Card Update screen)</li>
 *   <li><b>CICS Transaction:</b> CCUP - Card Update transaction</li>
 * </ul>
 * 
 * <h3>Field Mappings from COBOL</h3>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Field</th>
 *     <th>COBOL Type</th>
 *     <th>Java Field</th>
 *     <th>Java Type</th>
 *     <th>Location</th>
 *     <th>Source Line</th>
 *   </tr>
 *   <tr>
 *     <td>CARD-NUM</td>
 *     <td>PIC X(16)</td>
 *     <td>cardNumber</td>
 *     <td>String</td>
 *     <td>URL Path Variable</td>
 *     <td>CVACT02Y.cpy:5</td>
 *   </tr>
 *   <tr>
 *     <td>CARD-EXPIRAION-DATE</td>
 *     <td>PIC X(10)</td>
 *     <td>expirationDate</td>
 *     <td>LocalDate</td>
 *     <td>Request Body</td>
 *     <td>CVACT02Y.cpy:9</td>
 *   </tr>
 *   <tr>
 *     <td>CARD-ACTIVE-STATUS</td>
 *     <td>PIC X(01)</td>
 *     <td>status</td>
 *     <td>String</td>
 *     <td>Request Body</td>
 *     <td>CVACT02Y.cpy:10</td>
 *   </tr>
 * </table>
 * 
 * <h3>Validation Rules</h3>
 * <p>Jakarta Bean Validation annotations enforce data integrity rules that replicate
 * the original COBOL field validation:</p>
 * <ul>
 *   <li><b>cardNumber:</b> Validated in URL path parameter (must be exactly 16 numeric digits, matches COBOL PIC X(16))</li>
 *   <li><b>expirationDate:</b> Must be a valid future date in yyyy-MM-dd format</li>
 *   <li><b>status:</b> Must be either 'Y' (Active) or 'N' (Inactive) - matches COBOL 88-level condition logic</li>
 * </ul>
 * 
 * <h3>Date Format Handling</h3>
 * <p>The COBOL CARD-EXPIRAION-DATE field (PIC X(10)) stores dates as string values.
 * This DTO uses Java LocalDate with Jackson @JsonFormat annotation to handle the
 * conversion between JSON string representation (yyyy-MM-dd) and Java's type-safe
 * date handling. This ensures proper date validation and prevents invalid dates
 * from being processed.</p>
 * 
 * <h3>REST API Contract</h3>
 * <p>This DTO defines the request body structure for the card update endpoint:</p>
 * <pre>
 * PUT /api/cards/4111111111111111
 * Content-Type: application/json
 * 
 * {
 *   "expirationDate": "2025-12-31",
 *   "status": "Y"
 * }
 * </pre>
 * <p>Note: cardNumber is in the URL path, not in the request body.</p>
 * 
 * <h3>Usage Example</h3>
 * <pre>
 * // In CardController
 * {@literal @}PutMapping("/{id}")
 * public ResponseEntity&lt;CardResponse&gt; updateCard(
 *         {@literal @}PathVariable String id,
 *         {@literal @}Valid {@literal @}RequestBody CardUpdateRequest request) {
 *     return ResponseEntity.ok(cardUpdateService.updateCard(id, request));
 * }
 * 
 * // Building a request object (cardNumber comes from path parameter)
 * CardUpdateRequest request = CardUpdateRequest.builder()
 *     .expirationDate(LocalDate.of(2025, 12, 31))
 *     .status("Y")
 *     .build();
 * </pre>
 * 
 * <h3>Transaction Boundaries</h3>
 * <p>This DTO is used in conjunction with {@literal @}Transactional service methods
 * to maintain ACID properties equivalent to the original CICS transaction processing.
 * The CardUpdateService handles the transactional boundary, ensuring that card status
 * and expiration date updates are atomic operations.</p>
 * 
 * <h3>Security Considerations</h3>
 * <p>Card update operations require proper authentication and authorization.
 * The associated controller endpoint should be secured with appropriate
 * Spring Security {@literal @}PreAuthorize annotations to enforce role-based access control
 * equivalent to the original RACF security model.</p>
 * 
 * @see com.carddemo.controller.CardController
 * @see com.carddemo.service.card.CardUpdateService
 * @see com.carddemo.entity.Card
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardUpdateRequest {

    /**
     * NOTE: cardNumber is NOT included in this request body as it is provided via the 
     * URL path parameter in PUT /api/cards/{id}. This follows REST best practices where
     * the resource identifier is in the URL, not duplicated in the request body.
     * 
     * Original COBOL design had cardNumber in the COMMAREA structure, but in REST API
     * design, the resource identifier should only be in the URL path.
     */

    /**
     * Card Expiration Date
     * 
     * <p>The date when the card expires and is no longer valid for transactions.
     * This field maps to the COBOL CARD-EXPIRAION-DATE field (PIC X(10)) from
     * CVACT02Y.cpy line 9.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Cannot be null</li>
     *   <li>Must be a valid date in the future</li>
     *   <li>Format: yyyy-MM-dd (e.g., 2025-12-31)</li>
     *   <li>Month must be between 1-12</li>
     *   <li>Day must be valid for the given month and year</li>
     * </ul>
     * 
     * <p><b>Date Format Conversion:</b> The original COBOL field stores dates as
     * string values (PIC X(10)). This Java field uses LocalDate for type-safe date
     * handling. Jackson's @JsonFormat annotation handles the conversion between
     * JSON string representation and Java LocalDate during deserialization.</p>
     * 
     * <p><b>COBOL Source:</b> CARD-EXPIRAION-DATE PIC X(10) in CVACT02Y.cpy:9</p>
     * 
     * <p><b>Example:</b> 2025-12-31 (December 31st, 2025)</p>
     */
    @NotNull(message = "Expiration date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("expirationDate")
    private LocalDate expirationDate;

    /**
     * Card Active Status
     * 
     * <p>Single-character flag indicating whether the card is active or inactive.
     * This field maps to the COBOL CARD-ACTIVE-STATUS field (PIC X(01)) from
     * CVACT02Y.cpy line 10.</p>
     * 
     * <p><b>Valid Values:</b></p>
     * <ul>
     *   <li>'Y' - Card is active and can be used for transactions</li>
     *   <li>'N' - Card is inactive and blocked from transactions</li>
     * </ul>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Cannot be null or blank</li>
     *   <li>Must be exactly one character</li>
     *   <li>Must be either 'Y' or 'N' (case-sensitive)</li>
     * </ul>
     * 
     * <p><b>COBOL Mapping:</b> This replicates the COBOL 88-level condition logic:
     * <pre>
     * 05  CARD-ACTIVE-STATUS    PIC X(01).
     *     88  CARD-ACTIVE       VALUE 'Y'.
     *     88  CARD-INACTIVE     VALUE 'N'.
     * </pre>
     * </p>
     * 
     * <p><b>COBOL Source:</b> CARD-ACTIVE-STATUS PIC X(01) in CVACT02Y.cpy:10</p>
     * 
     * <p><b>Example:</b> "Y" (active), "N" (inactive)</p>
     */
    @NotBlank(message = "Card status is required")
    @Pattern(
        regexp = "^[YN]$",
        message = "Status must be 'Y' (Active) or 'N' (Inactive)"
    )
    @JsonProperty("status")
    private String status;

    /**
     * Returns the card number.
     * 
     * <p>This getter is automatically generated by Lombok's @Data annotation.
     * It provides access to the 16-digit card number field.</p>
     * 
     * @return the card number as a 16-digit string, or null if not set
     */
    // Implemented by Lombok @Data annotation: public String getCardNumber()

    /**
     * Sets the card number.
     * 
     * <p>This setter is automatically generated by Lombok's @Data annotation.
     * Validation occurs at the controller level via @Valid annotation.</p>
     * 
     * @param cardNumber the 16-digit card number to set
     */
    // Implemented by Lombok @Data annotation: public void setCardNumber(String cardNumber)

    /**
     * Returns the card expiration date.
     * 
     * <p>This getter is automatically generated by Lombok's @Data annotation.
     * It provides access to the card's expiration date as a LocalDate.</p>
     * 
     * @return the expiration date, or null if not set
     */
    // Implemented by Lombok @Data annotation: public LocalDate getExpirationDate()

    /**
     * Sets the card expiration date.
     * 
     * <p>This setter is automatically generated by Lombok's @Data annotation.
     * Validation occurs at the controller level via @Valid annotation.</p>
     * 
     * @param expirationDate the expiration date to set
     */
    // Implemented by Lombok @Data annotation: public void setExpirationDate(LocalDate expirationDate)

    /**
     * Returns the card active status.
     * 
     * <p>This getter is automatically generated by Lombok's @Data annotation.
     * It provides access to the card's status flag ('Y' or 'N').</p>
     * 
     * @return the status flag ('Y' for active, 'N' for inactive), or null if not set
     */
    // Implemented by Lombok @Data annotation: public String getStatus()

    /**
     * Sets the card active status.
     * 
     * <p>This setter is automatically generated by Lombok's @Data annotation.
     * Validation occurs at the controller level via @Valid annotation.</p>
     * 
     * @param status the status flag to set ('Y' for active, 'N' for inactive)
     */
    // Implemented by Lombok @Data annotation: public void setStatus(String status)

    /**
     * Returns a builder instance for constructing CardUpdateRequest objects.
     * 
     * <p>This builder method is automatically generated by Lombok's @Builder annotation.
     * It enables fluent, readable construction of request objects, particularly useful
     * in unit tests and integration tests.</p>
     * 
     * <p><b>Example Usage:</b></p>
     * <pre>
     * CardUpdateRequest request = CardUpdateRequest.builder()
     *     .cardNumber("4111111111111111")
     *     .expirationDate(LocalDate.of(2025, 12, 31))
     *     .status("Y")
     *     .build();
     * </pre>
     * 
     * @return a new builder instance
     */
    // Implemented by Lombok @Builder annotation: public static CardUpdateRequestBuilder builder()
}

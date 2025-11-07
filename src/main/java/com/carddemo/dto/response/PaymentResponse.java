package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Bill Payment Confirmation Response DTO
 * 
 * <p>This Data Transfer Object represents the response returned after successful bill payment 
 * processing in the CardDemo application. It provides payment confirmation details including 
 * the transaction identifier, updated account balance, and a success confirmation message.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>This DTO transforms the CICS CB00 transaction success feedback from COBOL program 
 * COBIL00C.cbl (lines 527-531) to a structured JSON response for RESTful HTTP 200 OK responses.</p>
 * 
 * <p>COBOL success message pattern from COBIL00C.cbl:</p>
 * <pre>
 * STRING 'Payment successful. '     DELIMITED BY SIZE
 *   ' Your Transaction ID is ' DELIMITED BY SIZE
 *        TRAN-ID  DELIMITED BY SPACE
 *        '.' DELIMITED BY SIZE
 *   INTO WS-MESSAGE
 * </pre>
 * 
 * <h2>Field Semantics</h2>
 * <ul>
 *   <li><strong>transactionId</strong>: 16-character transaction identifier (TRAN-ID from CVTRA05Y.cpy 
 *       PIC X(16)) generated during payment processing to uniquely identify the payment transaction</li>
 *   <li><strong>updatedBalance</strong>: Current account balance after payment deduction (ACCT-CURR-BAL 
 *       from CVACT01Y.cpy PIC S9(10)V99), maintaining COBOL COMP-3 monetary precision with BigDecimal 
 *       scale=2 and RoundingMode.HALF_UP</li>
 *   <li><strong>confirmationMessage</strong>: Human-readable success message formatted as 
 *       "Payment successful. Your Transaction ID is {transactionId}." matching the original COBOL 
 *       message pattern for consistency with legacy system behavior</li>
 * </ul>
 * 
 * <h2>Usage Context</h2>
 * <p>This response DTO is returned by:</p>
 * <ul>
 *   <li><code>BillPaymentService.processPayment(PaymentRequest)</code> method after successful 
 *       payment transaction completion</li>
 *   <li><code>BillingController POST /api/billing/payment</code> endpoint as HTTP 200 OK response body</li>
 * </ul>
 * 
 * <h2>BigDecimal Precision Requirements</h2>
 * <p>The updatedBalance field uses BigDecimal to preserve exact monetary precision matching COBOL 
 * COMP-3 packed decimal arithmetic:</p>
 * <ul>
 *   <li>Precision: 12 total digits (10 integer + 2 decimal)</li>
 *   <li>Scale: 2 decimal places (matching COBOL V99)</li>
 *   <li>Rounding: HALF_UP mode (matching COBOL rounding behavior)</li>
 *   <li>JSON Serialization: String format to prevent floating-point precision loss during 
 *       serialization/deserialization</li>
 * </ul>
 * 
 * <h2>JSON Structure</h2>
 * <pre>
 * {
 *   "transactionId": "1234567890123456",
 *   "updatedBalance": "9876.54",
 *   "confirmationMessage": "Payment successful. Your Transaction ID is 1234567890123456."
 * }
 * </pre>
 * 
 * <h2>Design Patterns</h2>
 * <ul>
 *   <li><strong>Data Transfer Object (DTO)</strong>: Separates API contract from internal entity 
 *       representations, enabling API versioning without impacting domain model</li>
 *   <li><strong>Builder Pattern</strong>: Provides fluent API for constructing response objects 
 *       with optional fields via Lombok @Builder annotation</li>
 *   <li><strong>Immutability</strong>: While Lombok @Data provides setters, this class should be 
 *       treated as immutable after construction using the builder pattern</li>
 * </ul>
 * 
 * @see com.carddemo.service.billing.BillPaymentService
 * @see com.carddemo.controller.BillingController
 * @see com.carddemo.dto.request.PaymentRequest
 * @since 1.0
 * @version CardDemo Spring Boot Migration v1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"transactionId", "updatedBalance", "confirmationMessage"})
public class PaymentResponse {

    /**
     * Payment Transaction Identifier
     * 
     * <p>16-character unique identifier for the payment transaction, generated during payment 
     * processing. This identifier can be used for transaction lookup, audit trail tracking, 
     * and customer service inquiries.</p>
     * 
     * <p><strong>COBOL Mapping:</strong> TRAN-ID from CVTRA05Y.cpy (PIC X(16))</p>
     * 
     * <p><strong>Format:</strong> Alphanumeric string, exactly 16 characters</p>
     * 
     * <p><strong>Example:</strong> "TXN1234567890123"</p>
     * 
     * <p><strong>Validation:</strong> Must be exactly 16 characters, non-null for successful payments</p>
     */
    @JsonProperty("transactionId")
    private String transactionId;

    /**
     * Updated Account Balance After Payment
     * 
     * <p>The current account balance after the payment amount has been deducted. This value reflects 
     * the account's monetary balance immediately following the successful payment transaction.</p>
     * 
     * <p><strong>COBOL Mapping:</strong> ACCT-CURR-BAL from CVACT01Y.cpy (PIC S9(10)V99 COMP-3)</p>
     * 
     * <p><strong>Precision:</strong></p>
     * <ul>
     *   <li>Total Digits: 12 (10 integer + 2 decimal)</li>
     *   <li>Scale: 2 decimal places</li>
     *   <li>Rounding Mode: HALF_UP (matching COBOL arithmetic)</li>
     *   <li>Range: -9999999999.99 to 9999999999.99</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> Serialized as string to prevent floating-point 
     * precision loss in JSON parsing. The @JsonFormat annotation ensures proper string representation.</p>
     * 
     * <p><strong>Example:</strong> "9876.54" (credit balance), "-123.45" (debit balance)</p>
     * 
     * <p><strong>Business Rules:</strong></p>
     * <ul>
     *   <li>Positive values indicate credit balance (available funds)</li>
     *   <li>Negative values indicate debit balance (amount owed)</li>
     *   <li>Always includes exactly 2 decimal places for cents</li>
     *   <li>Calculated as: previous balance - payment amount</li>
     * </ul>
     */
    @JsonProperty("updatedBalance")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal updatedBalance;

    /**
     * Payment Confirmation Message
     * 
     * <p>Human-readable success message confirming the payment transaction and providing the 
     * transaction ID for customer reference. This message maintains consistency with the original 
     * COBOL system's user feedback pattern.</p>
     * 
     * <p><strong>COBOL Mapping:</strong> WS-MESSAGE from COBIL00C.cbl (lines 527-531)</p>
     * 
     * <p><strong>Format Pattern:</strong> 
     * "Payment successful. Your Transaction ID is {transactionId}."</p>
     * 
     * <p><strong>Example:</strong> 
     * "Payment successful. Your Transaction ID is TXN1234567890123."</p>
     * 
     * <p><strong>Construction:</strong> This message is typically constructed by the service layer 
     * using String.format() or StringBuilder with the transaction ID embedded:</p>
     * <pre>
     * String message = String.format(
     *     "Payment successful. Your Transaction ID is %s.", 
     *     transactionId
     * );
     * </pre>
     * 
     * <p><strong>Localization Note:</strong> For internationalization support, this message should 
     * be retrieved from message resource bundles (messages.properties) with the transaction ID as 
     * a parameter, though the current implementation maintains the English-only message from the 
     * original COBOL system.</p>
     */
    @JsonProperty("confirmationMessage")
    private String confirmationMessage;

    /**
     * Creates a PaymentResponse with formatted confirmation message
     * 
     * <p>This is a convenience factory method that constructs a PaymentResponse with the 
     * confirmation message automatically formatted according to the COBOL pattern.</p>
     * 
     * @param transactionId the 16-character payment transaction identifier
     * @param updatedBalance the account balance after payment deduction (scale=2)
     * @return fully populated PaymentResponse with formatted confirmation message
     * 
     * @throws IllegalArgumentException if transactionId is null or not exactly 16 characters
     * @throws IllegalArgumentException if updatedBalance is null
     * 
     * @since 1.0
     */
    public static PaymentResponse create(String transactionId, BigDecimal updatedBalance) {
        if (transactionId == null || transactionId.length() != 16) {
            throw new IllegalArgumentException(
                "Transaction ID must be exactly 16 characters, got: " + 
                (transactionId == null ? "null" : transactionId.length())
            );
        }
        if (updatedBalance == null) {
            throw new IllegalArgumentException("Updated balance cannot be null");
        }

        String confirmationMessage = String.format(
            "Payment successful. Your Transaction ID is %s.",
            transactionId
        );

        return PaymentResponse.builder()
            .transactionId(transactionId)
            .updatedBalance(updatedBalance.setScale(2, java.math.RoundingMode.HALF_UP))
            .confirmationMessage(confirmationMessage)
            .build();
    }
}

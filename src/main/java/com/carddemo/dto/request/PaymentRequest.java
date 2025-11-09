package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Payment Request DTO for Bill Payment Processing
 * <p>
 * This Data Transfer Object represents the inbound payment request for the bill payment
 * functionality, replacing the CICS COMMAREA structure used in the CB00 (COBIL00C) transaction.
 * It serves as the request contract for the POST /api/billing/payment REST endpoint in the
 * stateless Spring Boot architecture.
 * </p>
 * 
 * <h3>COBOL Source File References</h3>
 * <ul>
 *   <li><b>CVTRA05Y.cpy (line 10)</b>: TRAN-AMT PIC S9(09)V99 - Transaction amount field
 *       representing payment amount with 9 integer digits and 2 decimal places</li>
 *   <li><b>CVACT01Y.cpy (line 5, 7)</b>: ACCT-ID PIC 9(11), ACCT-CURR-BAL PIC S9(10)V99 -
 *       Account identifier and current balance for payment processing and balance reduction</li>
 *   <li><b>COBIL00M.bms</b>: Bill payment screen mapset with ACTIDIN field for account ID input
 *       and CURBAL field for balance display</li>
 * </ul>
 * 
 * <h3>Field Validation Rules</h3>
 * <ul>
 *   <li><b>accountId</b>: Required (NOT NULL), 11-digit account identifier matching COBOL PIC 9(11)</li>
 *   <li><b>amount</b>: Required (NOT NULL), positive monetary value with minimum $0.01, maximum 9 integer
 *       digits and exactly 2 decimal places, preserving COBOL COMP-3 packed decimal precision</li>
 *   <li><b>paymentReference</b>: Optional text field (max 200 characters) for audit trail, payment memo,
 *       or reference number tracking</li>
 * </ul>
 * 
 * <h3>COBOL COMP-3 to BigDecimal Precision Mapping</h3>
 * <p>
 * The amount field uses Java BigDecimal with explicit scale=2 and RoundingMode.HALF_UP to preserve
 * the exact precision and rounding behavior of COBOL COMP-3 packed decimal fields (PIC S9(09)V99).
 * This ensures:
 * </p>
 * <ul>
 *   <li>Exact decimal precision matching mainframe monetary calculations</li>
 *   <li>No floating-point precision loss during JSON parsing or arithmetic operations</li>
 *   <li>Identical rounding behavior to COBOL for payment amount processing</li>
 *   <li>Accurate account balance reduction calculations maintaining financial data integrity</li>
 * </ul>
 * 
 * <h3>Business Constraints</h3>
 * <ul>
 *   <li><b>Positive Payment Amount</b>: Payment amounts must be greater than zero. Negative values
 *       (representing refunds or credits) are handled through separate transaction types.</li>
 *   <li><b>Minimum Payment Threshold</b>: Minimum payment amount is $0.01 to prevent zero-value
 *       or micro-payment transactions that could cause processing overhead.</li>
 *   <li><b>Maximum Payment Amount</b>: Maximum payment is $999,999,999.99 (9 integer digits) matching
 *       COBOL field capacity and preventing overflow in downstream systems.</li>
 *   <li><b>Payment Reference Length</b>: Reference text limited to 200 characters to balance
 *       audit trail completeness with database storage efficiency.</li>
 * </ul>
 * 
 * <h3>Transaction Processing Context</h3>
 * <p>
 * This DTO is used in the bill payment workflow which performs atomic operations:
 * </p>
 * <ol>
 *   <li>Validate payment request (field validation via Bean Validation annotations)</li>
 *   <li>Retrieve account record and verify sufficient balance</li>
 *   <li>Reduce account balance by payment amount (ACCT-CURR-BAL -= amount)</li>
 *   <li>Create payment transaction record with TRAN-AMT field populated</li>
 *   <li>Write audit log entry with optional paymentReference</li>
 * </ol>
 * <p>
 * All operations occur within a single @Transactional boundary ensuring ACID properties
 * matching the original CICS SYNCPOINT behavior for atomic multi-file updates.
 * </p>
 * 
 * <h3>Usage Example</h3>
 * <pre>
 * PaymentRequest request = PaymentRequest.builder()
 *     .accountId(12345678901L)
 *     .amount(new BigDecimal("150.75").setScale(2, RoundingMode.HALF_UP))
 *     .paymentReference("Payment for invoice INV-2024-001")
 *     .build();
 * 
 * // Controller endpoint usage:
 * // POST /api/billing/payment
 * // Request Body: {"accountId": 12345678901, "amount": 150.75, "paymentReference": "Payment for invoice INV-2024-001"}
 * </pre>
 * 
 * <h3>REST API Integration</h3>
 * <ul>
 *   <li><b>Endpoint</b>: POST /api/billing/payment</li>
 *   <li><b>Controller</b>: BillingController.processPayment()</li>
 *   <li><b>Service</b>: BillPaymentService.processPayment()</li>
 *   <li><b>Original CICS Transaction</b>: CB00 (COBIL00C.cbl)</li>
 *   <li><b>Request Format</b>: JSON with Content-Type: application/json</li>
 *   <li><b>Validation</b>: Jakarta Bean Validation annotations enforce constraints before service invocation</li>
 * </ul>
 * 
 * @see com.carddemo.controller.BillingController
 * @see com.carddemo.service.billing.BillPaymentService
 * @see com.carddemo.entity.Account
 * @see com.carddemo.entity.Transaction
 * 
 * @author CardDemo Development Team
 * @version 1.0
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRequest {

    /**
     * Account Identifier for Payment Processing
     * <p>
     * 11-digit account identifier matching COBOL ACCT-ID PIC 9(11) field from CVACT01Y.cpy.
     * This field identifies the target account for payment processing and balance reduction.
     * </p>
     * 
     * <h4>Validation Rules</h4>
     * <ul>
     *   <li>Required field - cannot be null</li>
     *   <li>Must be a valid 11-digit account number</li>
     *   <li>Account must exist in the system and be active</li>
     * </ul>
     * 
     * <h4>COBOL Mapping</h4>
     * <ul>
     *   <li><b>Source Field</b>: ACCT-ID PIC 9(11) (CVACT01Y.cpy line 5)</li>
     *   <li><b>BMS Field</b>: ACTIDIN field in COBIL00M.bms (line 85, LENGTH=11)</li>
     *   <li><b>Data Type</b>: Numeric, unsigned, 11 digits</li>
     * </ul>
     * 
     * @see com.carddemo.entity.Account#accountId
     */
    @JsonProperty("accountId")
    @NotNull(message = "Account ID is required for payment processing")
    private Long accountId;

    /**
     * Payment Amount with COBOL COMP-3 Precision
     * <p>
     * Monetary payment amount matching COBOL TRAN-AMT PIC S9(09)V99 field from CVTRA05Y.cpy.
     * Uses Java BigDecimal with scale=2 and RoundingMode.HALF_UP to preserve exact COBOL
     * packed decimal precision for accurate financial calculations.
     * </p>
     * 
     * <h4>Validation Rules</h4>
     * <ul>
     *   <li>Required field - cannot be null</li>
     *   <li>Must be a positive value (greater than zero)</li>
     *   <li>Minimum amount: $0.01 (prevents zero-value transactions)</li>
     *   <li>Maximum: 9 integer digits and exactly 2 decimal places (999,999,999.99)</li>
     *   <li>Precision: Exactly 2 decimal places required</li>
     * </ul>
     * 
     * <h4>COBOL Mapping</h4>
     * <ul>
     *   <li><b>Source Field</b>: TRAN-AMT PIC S9(09)V99 (CVTRA05Y.cpy line 10)</li>
     *   <li><b>Related Field</b>: ACCT-CURR-BAL PIC S9(10)V99 (CVACT01Y.cpy line 7) for balance reduction</li>
     *   <li><b>Data Type</b>: Signed numeric with 9 integer digits and 2 decimal places (implied V)</li>
     *   <li><b>COMP-3 Format</b>: Packed decimal ensuring exact precision without floating-point errors</li>
     * </ul>
     * 
     * <h4>Precision Preservation</h4>
     * <ul>
     *   <li><b>Scale</b>: Fixed at 2 decimal places matching COBOL V99</li>
     *   <li><b>Rounding Mode</b>: HALF_UP matching COBOL default rounding behavior</li>
     *   <li><b>Arithmetic</b>: All calculations use BigDecimal methods (add, subtract) to maintain precision</li>
     *   <li><b>JSON Serialization</b>: JsonFormat ensures string representation with 2 decimal places</li>
     * </ul>
     * 
     * <h4>Business Rules</h4>
     * <ul>
     *   <li>Payment amount must not exceed current account balance (verified in service layer)</li>
     *   <li>Negative amounts not allowed (use separate refund transaction type)</li>
     *   <li>Zero amounts rejected to prevent no-op transactions</li>
     * </ul>
     * 
     * @see com.carddemo.entity.Transaction#amount
     * @see com.carddemo.entity.Account#currentBalance
     */
    @JsonProperty("amount")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @NotNull(message = "Payment amount is required")
    @Positive(message = "Payment amount must be positive")
    @DecimalMin(value = "0.01", message = "Minimum payment amount is $0.01")
    @Digits(integer = 9, fraction = 2, message = "Payment amount must have maximum 9 integer digits and exactly 2 decimal places")
    private BigDecimal amount;

    /**
     * Optional Payment Reference for Audit Trail
     * <p>
     * Optional text field for payment reference number, invoice identifier, memo text, or other
     * tracking information. This field is stored with the payment transaction record for audit
     * purposes and payment reconciliation.
     * </p>
     * 
     * <h4>Validation Rules</h4>
     * <ul>
     *   <li>Optional field - may be null or empty</li>
     *   <li>Maximum length: 200 characters</li>
     *   <li>Accepts alphanumeric text, spaces, and common punctuation</li>
     * </ul>
     * 
     * <h4>Usage Scenarios</h4>
     * <ul>
     *   <li>External invoice number reference (e.g., "Invoice INV-2024-001")</li>
     *   <li>Payment memo or note (e.g., "Payment for statement ending 12/31/2023")</li>
     *   <li>Check number for check payments (e.g., "Check #1234")</li>
     *   <li>External payment system reference (e.g., "PayPal Txn: 9ABC123XYZ")</li>
     * </ul>
     * 
     * <h4>Data Storage</h4>
     * <ul>
     *   <li>Stored in transaction record audit fields or separate audit log table</li>
     *   <li>Available for reporting and reconciliation queries</li>
     *   <li>Indexed for search performance if needed for payment lookup</li>
     * </ul>
     * 
     * @see com.carddemo.entity.Transaction#description
     */
    @JsonProperty("paymentReference")
    @Size(max = 200, message = "Payment reference cannot exceed 200 characters")
    private String paymentReference;

    /**
     * Utility method to ensure payment amount has correct scale
     * <p>
     * This method ensures the payment amount BigDecimal has exactly 2 decimal places with
     * HALF_UP rounding mode, matching COBOL COMP-3 packed decimal behavior. Should be called
     * after deserialization or before arithmetic operations to guarantee precision.
     * </p>
     * 
     * @return PaymentRequest instance with normalized amount scale
     */
    public PaymentRequest normalizeAmount() {
        if (this.amount != null) {
            this.amount = this.amount.setScale(2, RoundingMode.HALF_UP);
        }
        return this;
    }

    /**
     * Validation helper to check if payment amount is within valid range
     * <p>
     * Validates that the payment amount falls within the business-acceptable range:
     * minimum $0.01 and maximum $999,999,999.99 (matching COBOL PIC S9(09)V99 capacity).
     * </p>
     * 
     * @return true if amount is within valid range, false otherwise
     */
    public boolean isAmountValid() {
        if (this.amount == null) {
            return false;
        }
        
        BigDecimal minAmount = new BigDecimal("0.01");
        BigDecimal maxAmount = new BigDecimal("999999999.99");
        
        return this.amount.compareTo(minAmount) >= 0 && 
               this.amount.compareTo(maxAmount) <= 0;
    }

    /**
     * Helper method to check if payment reference is provided
     * <p>
     * Convenience method to determine if a payment reference was included with the request.
     * Useful for conditional audit logging or reporting logic.
     * </p>
     * 
     * @return true if paymentReference is not null and not empty, false otherwise
     */
    public boolean hasPaymentReference() {
        return this.paymentReference != null && !this.paymentReference.trim().isEmpty();
    }
}

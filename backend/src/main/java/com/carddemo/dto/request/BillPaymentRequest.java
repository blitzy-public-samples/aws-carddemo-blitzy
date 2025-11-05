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
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Request DTO for credit card bill payment processing.
 * 
 * This class captures payment confirmation and account balance information from the
 * COBIL00 BMS screen (app/cpy-bms/COBIL00.CPY). It provides precise decimal validation
 * for financial transaction amounts and ensures multi-step validation with rollback
 * capability per section 0.9 requirements.
 * 
 * Field mappings from COBOL BMS copybook COBIL00:
 * - accountId: ACTIDINI PIC X(11) - Credit card account identifier
 * - currentBalance: CURBALI PIC X(14) - Current account balance (COMP-3 precision preserved)
 * - confirmation: CONFIRMI PIC X(1) - Payment confirmation flag (Y/N)
 * 
 * Additional fields for complete payment processing:
 * - paymentAmount: Payment amount to process (BigDecimal for precision)
 * - paymentDate: Scheduled payment date (current or future)
 * - payeeId: Optional payee identifier for payment tracking
 * - payeeName: Optional payee name for display purposes
 * - memo: Optional payment memo/description
 * 
 * All monetary fields use BigDecimal with scale=2 and RoundingMode.HALF_UP to maintain
 * COBOL COMP-3 packed decimal precision and prevent floating-point rounding errors in
 * financial calculations per section 0.9 critical numeric precision requirements.
 * 
 * @see com.carddemo.service.BillPaymentService
 * @see com.carddemo.controller.BillPaymentController
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillPaymentRequest {

    /**
     * Credit card account identifier.
     * Mapped from COBOL field: ACTIDINI PIC X(11)
     * 
     * Must be a non-blank alphanumeric string with maximum length of 11 characters.
     * This field identifies the account from which the bill payment will be processed.
     */
    @NotBlank(message = "Account ID is required and cannot be blank")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @JsonProperty("accountId")
    private String accountId;

    /**
     * Current account balance before payment processing.
     * Mapped from COBOL field: CURBALI PIC X(14)
     * 
     * Represents the account balance with COMP-3 decimal precision preserved using
     * BigDecimal with scale=2. Maximum 12 integer digits and 2 fractional digits.
     * Must be non-negative (0.00 or greater).
     * 
     * This field is used for validation to ensure sufficient funds exist before
     * processing the payment transaction.
     */
    @NotNull(message = "Current balance is required")
    @DecimalMin(value = "0.00", message = "Current balance cannot be negative")
    @Digits(integer = 12, fraction = 2, message = "Current balance must have at most 12 integer digits and 2 decimal places")
    @JsonProperty("currentBalance")
    private BigDecimal currentBalance;

    /**
     * Payment confirmation flag.
     * Mapped from COBOL field: CONFIRMI PIC X(1)
     * 
     * Valid values:
     * - 'Y': Payment confirmed and should be processed
     * - 'N': Payment not confirmed or cancelled
     * 
     * This field implements the confirmation step required by multi-step validation
     * and rollback capability per section 0.9 requirements.
     */
    @NotNull(message = "Confirmation is required")
    @Pattern(regexp = "[YN]", message = "Confirmation must be 'Y' (Yes) or 'N' (No)")
    @JsonProperty("confirmation")
    private String confirmation;

    /**
     * Payment amount to be processed.
     * Not present in original COBOL copybook but required for payment processing.
     * 
     * Uses BigDecimal with scale=2 and RoundingMode.HALF_UP to preserve COBOL COMP-3
     * packed decimal precision per section 0.9 critical requirements. Must be positive
     * (minimum 0.01) and have at most 12 integer digits and 2 decimal places.
     * 
     * This amount will be validated against currentBalance to ensure sufficient funds
     * exist before processing the payment transaction.
     */
    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be at least 0.01")
    @Digits(integer = 12, fraction = 2, message = "Payment amount must have at most 12 integer digits and 2 decimal places")
    @JsonProperty("paymentAmount")
    private BigDecimal paymentAmount;

    /**
     * Scheduled payment date.
     * 
     * Must be either the current date or a future date. Prevents backdated payment
     * scheduling while allowing same-day and future-dated bill payments. Validates
     * business rule that payment date cannot be in the past, ensuring temporal
     * integrity of payment scheduling.
     */
    @FutureOrPresent(message = "Payment date must be today or a future date")
    @JsonProperty("paymentDate")
    private LocalDate paymentDate;

    /**
     * Optional payee identifier for payment tracking.
     * 
     * Used to identify the payee in the system for payment routing and tracking
     * purposes. May reference an external payee registry or internal payee master data.
     */
    @Size(max = 20, message = "Payee ID must not exceed 20 characters")
    @JsonProperty("payeeId")
    private String payeeId;

    /**
     * Optional payee name for display purposes.
     * 
     * Human-readable payee name displayed on payment confirmations, statements,
     * and transaction history. Does not need to match payeeId exactly.
     */
    @Size(max = 50, message = "Payee name must not exceed 50 characters")
    @JsonProperty("payeeName")
    private String payeeName;

    /**
     * Optional payment memo or description.
     * 
     * Free-text field for user-entered payment description, reference number,
     * or additional payment details. Appears on statements and transaction history
     * for payment identification purposes.
     */
    @Size(max = 100, message = "Memo must not exceed 100 characters")
    @JsonProperty("memo")
    private String memo;

    /**
     * Sets the current balance with proper COMP-3 precision preservation.
     * 
     * Applies scale=2 and RoundingMode.HALF_UP to maintain exact financial
     * calculation accuracy matching mainframe decimal arithmetic per section 0.9
     * critical numeric precision requirements.
     * 
     * @param currentBalance the current account balance
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        if (currentBalance != null) {
            this.currentBalance = currentBalance.setScale(2, RoundingMode.HALF_UP);
        } else {
            this.currentBalance = null;
        }
    }

    /**
     * Sets the payment amount with proper COMP-3 precision preservation.
     * 
     * Applies scale=2 and RoundingMode.HALF_UP to maintain exact financial
     * calculation accuracy matching mainframe decimal arithmetic per section 0.9
     * critical numeric precision requirements.
     * 
     * @param paymentAmount the payment amount to process
     */
    public void setPaymentAmount(BigDecimal paymentAmount) {
        if (paymentAmount != null) {
            this.paymentAmount = paymentAmount.setScale(2, RoundingMode.HALF_UP);
        } else {
            this.paymentAmount = null;
        }
    }

    /**
     * Validates that the payment amount does not exceed the current balance.
     * 
     * This business rule validation ensures sufficient funds exist before
     * processing the payment transaction. Uses BigDecimal.compareTo() for
     * precise decimal comparison without floating-point errors.
     * 
     * @return true if payment amount is less than or equal to current balance
     */
    public boolean hasSufficientBalance() {
        if (currentBalance == null || paymentAmount == null) {
            return false;
        }
        return paymentAmount.compareTo(currentBalance) <= 0;
    }

    /**
     * Checks if the payment is confirmed by the user.
     * 
     * @return true if confirmation field is 'Y', false otherwise
     */
    public boolean isConfirmed() {
        return "Y".equals(confirmation);
    }

    /**
     * Calculates the remaining balance after payment processing.
     * 
     * Uses BigDecimal.subtract() to maintain precision during calculation.
     * Returns the result with scale=2 and RoundingMode.HALF_UP to preserve
     * COMP-3 decimal precision.
     * 
     * @return the remaining balance after payment, or null if current balance or payment amount is null
     */
    public BigDecimal calculateRemainingBalance() {
        if (currentBalance == null || paymentAmount == null) {
            return null;
        }
        return currentBalance.subtract(paymentAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Custom toString() implementation for secure logging.
     * 
     * Masks sensitive financial information (balance and payment amounts) to
     * prevent exposure in logs while providing useful debugging information.
     * 
     * @return string representation with masked sensitive data
     */
    @Override
    public String toString() {
        return "BillPaymentRequest{" +
                "accountId='" + accountId + '\'' +
                ", currentBalance=" + (currentBalance != null ? "***.**" : "null") +
                ", confirmation='" + confirmation + '\'' +
                ", paymentAmount=" + (paymentAmount != null ? "***.**" : "null") +
                ", paymentDate=" + paymentDate +
                ", payeeId='" + payeeId + '\'' +
                ", payeeName='" + payeeName + '\'' +
                ", memo='" + memo + '\'' +
                '}';
    }
}

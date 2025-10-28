package com.carddemo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Data Transfer Object for Billing Statement data.
 * 
 * <p>This DTO represents a complete billing statement for a credit card account,
 * containing all financial calculations, transaction summaries, and payment information.
 * </p>
 * 
 * <p><strong>COBOL Origin:</strong> Derived from billing logic in COBIL00C.cbl and
 * statement formatting structures in COSTM01.CPY.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2025-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingStatementDto {

    /**
     * Account identifier for this statement.
     * Corresponds to COBOL PIC 9(11) ACCT-ID
     */
    private Long accountId;

    /**
     * Statement generation date.
     * Replaces COBOL timestamp handling from WS-TIMESTAMP
     */
    private LocalDate statementDate;

    /**
     * Statement period start date.
     */
    private LocalDate periodStartDate;

    /**
     * Statement period end date.
     */
    private LocalDate periodEndDate;

    /**
     * Payment due date (typically 21 days after statement date).
     */
    private LocalDate dueDate;

    /**
     * Previous balance at start of statement period.
     * Preserves COBOL COMP-3 precision using BigDecimal with scale 2.
     */
    private BigDecimal previousBalance;

    /**
     * Total new charges (debits) during statement period.
     * Sum of all debit transactions, preserving COMP-3 precision.
     */
    private BigDecimal newCharges;

    /**
     * Total payments and credits during statement period.
     * Sum of all credit transactions, preserving COMP-3 precision.
     */
    private BigDecimal paymentsAndCredits;

    /**
     * Interest charged for this statement period.
     * Calculated using exact COBOL formula with HALF_UP rounding.
     */
    private BigDecimal interestCharged;

    /**
     * Late payment fee charged (if applicable).
     * Fixed $35.00 fee if payment received after due date.
     */
    private BigDecimal lateFee;

    /**
     * New balance for this statement.
     * Formula: previousBalance + newCharges - paymentsAndCredits + interestCharged + lateFee
     */
    private BigDecimal newBalance;

    /**
     * Minimum payment due.
     * Greater of $25.00 or 3% of new balance.
     */
    private BigDecimal minimumPaymentDue;

    /**
     * Credit limit for the account.
     * From ACCT-CREDIT-LIMIT field.
     */
    private BigDecimal creditLimit;

    /**
     * Available credit (creditLimit - newBalance).
     */
    private BigDecimal availableCredit;

    /**
     * List of transactions included in this statement period.
     * For statement itemization and category breakdown.
     */
    private List<TransactionDto> transactions;

    /**
     * Annual Percentage Rate (APR) applied to this account.
     * Used for interest calculations.
     */
    private BigDecimal annualPercentageRate;

    /**
     * Number of days in the statement period.
     * Used for daily interest calculations.
     */
    private Integer daysInPeriod;
}

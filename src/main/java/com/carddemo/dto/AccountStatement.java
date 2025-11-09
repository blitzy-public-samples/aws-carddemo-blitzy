package com.carddemo.dto;

import com.carddemo.dto.response.StatementDetail;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Account Statement DTO for Spring Batch statement generation.
 * 
 * <p>This DTO represents a complete account statement ready for PDF generation,
 * containing account information, customer details, and all transactions for
 * the statement period. Serves as the item type flowing through the
 * StatementGenerationJob pipeline.</p>
 * 
 * <p><b>COBOL Transformation:</b> This DTO consolidates data from multiple
 * COBOL copybooks used in CBSTM03A.CBL and CBSTM03B.CBL:</p>
 * <ul>
 *   <li>Account data from CVACT01Y.cpy (ACCT-ID, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT)</li>
 *   <li>Customer data from CVCUS01Y.cpy (CUST-FIRST-NAME, CUST-LAST-NAME, addresses)</li>
 *   <li>Transaction data from CVTRA05Y.cpy via StatementDetail objects</li>
 * </ul>
 * 
 * <p><b>Zero Transaction Handling:</b> Unlike the original COBOL which processes
 * transaction-driven reports, this design explicitly supports accounts with zero
 * transactions, ensuring every account receives a statement even if no activity
 * occurred during the statement period.</p>
 * 
 * <p><b>Usage in Batch Job:</b></p>
 * <pre>
 * ItemReader reads Account entities
 *     ↓
 * ItemProcessor creates AccountStatement (with transactions fetched)
 *     ↓
 * ItemWriter generates PDF from AccountStatement
 * </pre>
 * 
 * @see com.carddemo.batch.job.StatementGenerationJob
 * @see com.carddemo.batch.processor.StatementDetailProcessor
 * @see com.carddemo.dto.response.StatementDetail
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountStatement {
    
    /**
     * Account identifier (11-digit numeric).
     * Maps from ACCT-ID in CVACT01Y.cpy.
     * Primary key for statement generation and PDF filename.
     */
    private Long accountId;
    
    /**
     * Customer full name for statement header.
     * Concatenated from CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME.
     * Format: "FirstName MiddleName LastName" or "FirstName LastName"
     */
    private String customerName;
    
    /**
     * Customer address line 1 for statement mailing address.
     * Maps from CUST-ADDR-LINE-1 (50 characters).
     */
    private String addressLine1;
    
    /**
     * Customer address line 2 for statement mailing address.
     * Maps from CUST-ADDR-LINE-2 (50 characters).
     * May be null or empty string.
     */
    private String addressLine2;
    
    /**
     * Customer address line 3 for statement mailing address (city, state, zip).
     * Maps from CUST-ADDR-LINE-3 (50 characters).
     * Typically contains: "City, ST ZIP"
     */
    private String addressLine3;
    
    /**
     * Account current balance with 2 decimal precision.
     * Maps from ACCT-CURR-BAL (PIC S9(10)V99 COMP-3).
     * Used in statement header summary section.
     */
    private BigDecimal currentBalance;
    
    /**
     * Account credit limit with 2 decimal precision.
     * Maps from ACCT-CREDIT-LIMIT (PIC S9(10)V99 COMP-3).
     * Used in statement header summary section.
     */
    private BigDecimal creditLimit;
    
    /**
     * List of transaction details for this account's statement period.
     * Each StatementDetail contains transaction date, amount, merchant info, etc.
     * 
     * <p><b>IMPORTANT:</b> This list CAN be empty for accounts with zero transactions.
     * The statement generation logic must handle empty lists gracefully, generating
     * a statement showing $0.00 total with "No transactions this period" message.</p>
     * 
     * <p>Initialized to empty ArrayList by default to prevent null pointer exceptions.</p>
     */
    @Builder.Default
    private List<StatementDetail> transactions = new ArrayList<>();
    
    /**
     * Check if this account has any transactions for the statement period.
     * 
     * @return true if transactions list is empty, false otherwise
     */
    public boolean hasNoTransactions() {
        return transactions == null || transactions.isEmpty();
    }
    
    /**
     * Get the count of transactions in this statement.
     * 
     * @return number of transactions (0 if list is null or empty)
     */
    public int getTransactionCount() {
        return (transactions != null) ? transactions.size() : 0;
    }
}

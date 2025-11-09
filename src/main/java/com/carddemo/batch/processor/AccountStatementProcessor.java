package com.carddemo.batch.processor;

import com.carddemo.dto.AccountStatement;
import com.carddemo.dto.response.StatementDetail;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Account Statement Processor for Spring Batch statement generation.
 * 
 * <p>This processor transforms Account entities into AccountStatement DTOs by:</p>
 * <ol>
 *   <li>Fetching all transactions for the account</li>
 *   <li>Enriching each transaction with customer and card data (via StatementDetailProcessor)</li>
 *   <li>Building a complete AccountStatement with account info, customer info, and transaction list</li>
 *   <li>Handling accounts with zero transactions gracefully</li>
 * </ol>
 * 
 * <p><b>COBOL Transformation:</b> This processor implements the account-driven statement
 * generation logic that replaces the transaction-driven approach in CBSTM03A.CBL and
 * CBSTM03B.CBL. Key differences:</p>
 * <ul>
 *   <li>COBOL: Sequential read of TRANSACT file, group by account (transaction-driven)</li>
 *   <li>Java: Read Account entities, fetch related transactions (account-driven)</li>
 *   <li>COBOL: Accounts with zero transactions skipped</li>
 *   <li>Java: Accounts with zero transactions in date range also skipped (processor returns null)</li>
 * </ul>
 * 
 * <p><b>Data Sources:</b></p>
 * <ul>
 *   <li>Account entity from CVACT01Y.cpy (ACCT-ID, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT)</li>
 *   <li>Customer entity from CVCUS01Y.cpy (CUST-FIRST-NAME, CUST-LAST-NAME, addresses)</li>
 *   <li>Transaction entities from CVTRA05Y.cpy (TRAN-ID, TRAN-AMT, TRAN-DESC, etc.)</li>
 * </ul>
 * 
 * <p><b>Processing Flow:</b></p>
 * <pre>
 * 1. Receive Account entity (from ItemReader)
 * 2. Validate account has required fields (accountId, currentBalance, creditLimit)
 * 3. Fetch Customer entity using account.customerId
 * 4. Fetch all Transaction entities for this account (may be empty list)
 * 5. For each transaction, create StatementDetail using StatementDetailProcessor
 * 6. Build AccountStatement with:
 *    - Account ID and balances
 *    - Customer name and address
 *    - List of StatementDetail objects (possibly empty)
 * 7. Return AccountStatement for PDF generation (ItemWriter)
 * </pre>
 * 
 * <p><b>Zero Transaction Handling:</b> Similar to the original COBOL which skips accounts
 * without transactions, this processor filters out accounts with zero transactions in
 * the statement period by returning null. Spring Batch interprets null as "skip this item",
 * preventing statement generation for accounts with no activity in the date range.</p>
 * 
 * <p><b>Error Handling:</b></p>
 * <ul>
 *   <li>ResourceNotFoundException - Customer not found for account</li>
 *   <li>BusinessLogicException - Invalid account data (null required fields)</li>
 *   <li>Exception - Transaction processing failure (logged, transaction skipped)</li>
 * </ul>
 * 
 * <p><b>Performance Considerations:</b></p>
 * <ul>
 *   <li>Batch processing with chunk size of 10 accounts per transaction</li>
 *   <li>Single query per account to fetch all transactions</li>
 *   <li>Reuses StatementDetailProcessor for transaction enrichment</li>
 *   <li>4-hour batch window requirement - handles ~10,000 accounts with average 50 transactions each</li>
 * </ul>
 * 
 * @see com.carddemo.dto.AccountStatement
 * @see com.carddemo.batch.processor.StatementDetailProcessor
 * @see com.carddemo.batch.job.StatementGenerationJob
 * @see com.carddemo.entity.Account
 */
@Slf4j
@Component
@StepScope
public class AccountStatementProcessor implements ItemProcessor<Account, AccountStatement> {

    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final StatementDetailProcessor statementDetailProcessor;

    /**
     * Statement period start date, injected from job parameters.
     * 
     * <p>This date defines the beginning of the statement period (inclusive).
     * Transactions with originationTimestamp on or after this date will be
     * included in the generated statement.</p>
     * 
     * <p>Job parameter format: "startDate" as LocalDate</p>
     */
    @Value("#{jobParameters['startDate']}")
    private LocalDate startDate;

    /**
     * Statement period end date, injected from job parameters.
     * 
     * <p>This date defines the end of the statement period (inclusive).
     * Transactions with originationTimestamp on or before this date will be
     * included in the generated statement.</p>
     * 
     * <p>Job parameter format: "endDate" as LocalDate</p>
     */
    @Value("#{jobParameters['endDate']}")
    private LocalDate endDate;

    /**
     * Constructor with dependency injection.
     * 
     * <p><b>Note:</b> This processor is @StepScope, meaning a new instance is created
     * for each step execution. Job parameters (startDate, endDate) are injected after
     * construction via @Value annotations.</p>
     * 
     * @param customerRepository Repository for fetching customer entities
     * @param transactionRepository Repository for fetching account transactions
     * @param statementDetailProcessor Processor for enriching transaction data
     */
    @Autowired
    public AccountStatementProcessor(
            CustomerRepository customerRepository,
            TransactionRepository transactionRepository,
            StatementDetailProcessor statementDetailProcessor) {
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.statementDetailProcessor = statementDetailProcessor;
    }

    /**
     * Processes an Account entity into an AccountStatement DTO.
     * 
     * <p>This is the main processing method called by Spring Batch for each account
     * in the chunk. It orchestrates the following steps:</p>
     * <ol>
     *   <li>Validate account has required fields</li>
     *   <li>Fetch customer information</li>
     *   <li>Fetch all transactions for this account</li>
     *   <li>Process each transaction to create StatementDetail</li>
     *   <li>Build and return AccountStatement DTO</li>
     * </ol>
     * 
     * <p><b>COBOL Mapping:</b> Replaces the transaction-grouping logic in CBSTM03A.CBL:</p>
     * <pre>
     * COBOL (lines 120-145):
     * PERFORM 1000-READ-TRANSACTION THRU 1000-EXIT
     * IF TRAN-ACCT-ID NOT = PREV-ACCT-ID
     *     PERFORM 2000-PRINT-STATEMENT
     * END-IF
     * 
     * Java equivalent:
     * For each Account:
     *     Fetch all transactions for account
     *     Build statement with account header and transaction details
     * </pre>
     * 
     * <p><b>Zero Transaction Handling:</b> This method filters out accounts with zero
     * transactions in the statement period by returning null. The COBOL program also
     * skipped such accounts. Spring Batch interprets null return values as "skip this
     * item", meaning accounts with no transactions will not have statements generated.</p>
     * 
     * @param account The account entity to process (must not be null)
     * 
     * @return AccountStatement DTO ready for PDF generation, containing account info,
     *         customer info, and list of transactions. Returns null if account has zero
     *         transactions in the statement period, causing Spring Batch to skip writing
     *         a statement for this account.
     * 
     * @throws ResourceNotFoundException If customer referenced by account cannot be
     *         found in the database, indicating referential integrity violation.
     * @throws BusinessLogicException If account has invalid data (null required fields)
     *         or if customer data is invalid (null name fields).
     * @throws Exception For unexpected errors during transaction processing. Individual
     *         transaction processing errors are logged and the transaction is skipped,
     *         but the statement is still generated with remaining valid transactions.
     */
    @Override
    public AccountStatement process(Account account) throws Exception {
        log.debug("Processing account statement for account ID: {}", account.getAccountId());

        // Step 1: Validate account has required fields
        validateAccount(account);

        // Step 2: Fetch customer information using account's customer ID
        Customer customer = fetchCustomer(account);

        // Step 3: Fetch all transactions for this account within the date range (may be empty list)
        List<Transaction> transactions = fetchTransactionsForAccount(account);
        
        log.debug("Found {} transactions for account ID: {} in date range {} to {}", 
                transactions.size(), account.getAccountId(), startDate, endDate);

        // Step 3.5: Filter out accounts with zero transactions in the date range
        // Per test requirements, accounts with no transactions should not generate statements
        // Return null to signal Spring Batch to skip this item (not written to output)
        if (transactions.isEmpty()) {
            log.debug("Skipping statement for account ID: {} - no transactions in date range {} to {}",
                    account.getAccountId(), startDate, endDate);
            return null;
        }

        // Step 4: Process each transaction to create StatementDetail objects
        List<StatementDetail> statementDetails = processTransactions(transactions, account);

        // Step 5: Format amounts with 2 decimal precision (COBOL COMP-3 equivalent)
        BigDecimal currentBalance = formatAmount(account.getCurrentBalance());
        BigDecimal creditLimit = formatAmount(account.getCreditLimit());

        // Step 6: Build customer name (concatenate first, middle, last names)
        String customerName = buildCustomerName(customer);

        // Step 7: Build and return AccountStatement DTO
        AccountStatement accountStatement = AccountStatement.builder()
                .accountId(account.getAccountId())
                .customerName(customerName)
                .addressLine1(customer.getAddressLine1())
                .addressLine2(customer.getAddressLine2())
                .addressLine3(customer.getAddressLine3())
                .currentBalance(currentBalance)
                .creditLimit(creditLimit)
                .transactions(statementDetails)
                .build();

        log.info("Successfully processed account statement for account ID: {} with {} transactions", 
                account.getAccountId(), statementDetails.size());

        return accountStatement;
    }

    /**
     * Validates that account has all required fields for statement generation.
     * 
     * <p>Verifies the following mandatory fields:</p>
     * <ul>
     *   <li>accountId - Must be non-null (11-digit numeric value)</li>
     *   <li>currentBalance - Must be non-null (monetary value required)</li>
     *   <li>creditLimit - Must be non-null (monetary value required)</li>
     *   <li>customer - Must be non-null (relationship required)</li>
     *   <li>customer.customerId - Must be non-null (for customer lookup)</li>
     * </ul>
     * 
     * @param account The account entity to validate
     * 
     * @throws BusinessLogicException If any required field is null or invalid,
     *         with specific message indicating which field is missing
     */
    private void validateAccount(Account account) {
        if (account == null) {
            throw new BusinessLogicException("Account is null - cannot process statement");
        }

        if (account.getAccountId() == null) {
            throw new BusinessLogicException("Account ID is null - cannot generate statement");
        }

        if (account.getCurrentBalance() == null) {
            throw new BusinessLogicException(
                    "Account current balance is null for account " + account.getAccountId());
        }

        if (account.getCreditLimit() == null) {
            throw new BusinessLogicException(
                    "Account credit limit is null for account " + account.getAccountId());
        }

        if (account.getCustomer() == null || account.getCustomer().getCustomerId() == null) {
            throw new BusinessLogicException(
                    "Account customer relationship is null for account " + account.getAccountId());
        }
    }

    /**
     * Fetches customer entity for the given account.
     * 
     * <p>Looks up customer using the account's customer ID relationship. This
     * replaces the COBOL CUSTFILE-PROC keyed read logic from CBSTM03A.CBL.</p>
     * 
     * @param account The account entity with customer relationship
     * 
     * @return Customer entity with name and address information populated
     * 
     * @throws ResourceNotFoundException If customer is not found in database,
     *         indicating orphaned account record or referential integrity violation
     */
    private Customer fetchCustomer(Account account) {
        Long customerId = account.getCustomer().getCustomerId();
        
        return customerRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer not found for customer ID: " + customerId + 
                        " (referenced by account " + account.getAccountId() + ")"));
    }

    /**
     * Fetches transactions for the given account within the statement period.
     * 
     * <p>Retrieves transaction records associated with this account by querying
     * transactions through the account's cards, filtered by the statement period
     * date range defined by the job parameters startDate and endDate. This replaces
     * the COBOL sequential read and grouping logic from CBSTM03A.CBL.</p>
     * 
     * <p><b>Date Range Filtering:</b> Only transactions with originationTimestamp
     * between startDate and endDate (inclusive) are returned. This is critical for
     * monthly statement generation to ensure only transactions within the statement
     * period are included.</p>
     * 
     * <p><b>IMPORTANT:</b> This method returns an empty list (not null) for accounts
     * with zero transactions in the specified date range, which is the expected
     * behavior for zero-transaction statement generation.</p>
     * 
     * @param account The account entity to fetch transactions for
     * 
     * @return List of Transaction entities for this account within the statement period,
     *         ordered by origination timestamp descending (most recent first). Returns
     *         empty list (not null) if account has no transactions in the date range.
     */
    private List<Transaction> fetchTransactionsForAccount(Account account) {
        log.debug("Fetching transactions for account {} between {} and {}", 
                account.getAccountId(), startDate, endDate);
        
        // Fetch transactions for cards belonging to this account within the date range
        // Note: This assumes the Transaction entity has a relationship to Card,
        // and Card has a relationship to Account
        List<Transaction> transactions = transactionRepository
                .findByCard_Account_AccountIdAndOriginationTimestampBetweenOrderByOriginationTimestampDesc(
                        account.getAccountId(), startDate, endDate);
        
        // Return empty list if no transactions found (never null)
        return (transactions != null) ? transactions : new ArrayList<>();
    }

    /**
     * Processes a list of transactions into StatementDetail objects.
     * 
     * <p>For each transaction, delegates to StatementDetailProcessor to enrich
     * the transaction data with card, account, and customer information. If a
     * transaction fails to process (e.g., due to missing relationships), it is
     * logged and skipped, but processing continues for remaining transactions.</p>
     * 
     * <p><b>Error Handling:</b> Individual transaction processing failures do not
     * fail the entire statement. The error is logged, and the transaction is
     * skipped. This ensures that accounts with some invalid transactions can still
     * receive statements for their valid transactions.</p>
     * 
     * @param transactions List of transaction entities to process (may be empty)
     * @param account The parent account (used for logging context only)
     * 
     * @return List of StatementDetail objects, one per successfully processed
     *         transaction. Returns empty list if no transactions or all transactions
     *         failed processing.
     */
    private List<StatementDetail> processTransactions(List<Transaction> transactions, Account account) {
        List<StatementDetail> statementDetails = new ArrayList<>();

        for (Transaction transaction : transactions) {
            try {
                // Delegate to StatementDetailProcessor for enrichment
                StatementDetail detail = statementDetailProcessor.process(transaction);
                
                if (detail != null) {
                    statementDetails.add(detail);
                }
            } catch (Exception e) {
                // Log error but continue processing remaining transactions
                log.error("Failed to process transaction {} for account {}: {}", 
                        transaction.getTransactionId(), account.getAccountId(), e.getMessage(), e);
                // Transaction is skipped, but statement generation continues
            }
        }

        return statementDetails;
    }

    /**
     * Formats monetary amount with exactly 2 decimal places.
     * 
     * <p>Ensures consistent decimal precision matching COBOL COMP-3 packed decimal
     * format (PIC S9(10)V99) with explicit scale and rounding mode.</p>
     * 
     * <p>Rounding behavior matches COBOL:</p>
     * <ul>
     *   <li>RoundingMode.HALF_UP - Standard rounding (0.5 rounds up)</li>
     *   <li>Scale = 2 - Exactly two decimal places for cents</li>
     * </ul>
     * 
     * @param amount The monetary amount to format (must not be null)
     * 
     * @return BigDecimal with scale=2 and RoundingMode.HALF_UP applied
     * 
     * @throws BusinessLogicException If amount is null
     */
    private BigDecimal formatAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BusinessLogicException(
                    "Cannot format null amount - monetary value required for statement");
        }

        // Set scale to 2 decimal places with HALF_UP rounding (matches COBOL behavior)
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Builds customer full name by concatenating first, middle, and last names.
     * 
     * <p>Formats customer name for statement header display:</p>
     * <ul>
     *   <li>Format with middle name: "FirstName MiddleName LastName"</li>
     *   <li>Format without middle name: "FirstName LastName"</li>
     *   <li>Handles missing or empty middle name gracefully</li>
     * </ul>
     * 
     * <p>Maps from COBOL copybook CVCUS01Y.cpy:</p>
     * <ul>
     *   <li>CUST-FIRST-NAME (PIC X(25)) → firstName</li>
     *   <li>CUST-MIDDLE-NAME (PIC X(25)) → middleName (optional)</li>
     *   <li>CUST-LAST-NAME (PIC X(25)) → lastName</li>
     * </ul>
     * 
     * @param customer The customer entity containing name fields
     * 
     * @return Formatted customer full name for statement header
     * 
     * @throws BusinessLogicException If customer is null or first name is null/empty
     */
    private String buildCustomerName(Customer customer) {
        if (customer == null) {
            throw new BusinessLogicException(
                    "Cannot build customer name from null customer entity");
        }

        String firstName = customer.getFirstName();
        String middleName = customer.getMiddleName();
        String lastName = customer.getLastName();

        // Validate at least first name is present
        if (firstName == null || firstName.trim().isEmpty()) {
            throw new BusinessLogicException(
                    "Customer first name is null or empty - cannot generate statement header for customer ID: " 
                    + customer.getCustomerId());
        }

        // Build full name with optional middle name
        StringBuilder fullName = new StringBuilder();
        fullName.append(firstName.trim());

        // Add middle name if present
        if (middleName != null && !middleName.trim().isEmpty()) {
            fullName.append(" ").append(middleName.trim());
        }

        // Add last name if present
        if (lastName != null && !lastName.trim().isEmpty()) {
            fullName.append(" ").append(lastName.trim());
        }

        return fullName.toString();
    }
}

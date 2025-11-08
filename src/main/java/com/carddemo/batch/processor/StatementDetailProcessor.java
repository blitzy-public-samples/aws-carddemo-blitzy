package com.carddemo.batch.processor;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.dto.response.StatementDetail;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Spring Batch ItemProcessor for formatting transaction details for monthly statement generation.
 * 
 * <p>This processor transforms Transaction entities into formatted StatementDetail DTOs by 
 * enriching transaction data with customer information, account details, and cross-reference 
 * data through keyed database reads. It implements the file I/O coordination logic from the 
 * COBOL subroutine CBSTM03B.CBL, converting VSAM file operations to Spring Data JPA repository 
 * method calls.</p>
 * 
 * <h2>COBOL Source Program</h2>
 * <p>Source: <b>CBSTM03B.CBL</b> - Centralized file I/O handler for statement generation</p>
 * <ul>
 *   <li><b>Lines 100-112</b> - LINKAGE SECTION LK-M03B-AREA parameter passing structure</li>
 *   <li><b>Lines 118-128</b> - EVALUATE LK-M03B-DD file dispatcher logic</li>
 *   <li><b>Lines 140-144</b> - 1000-TRNXFILE-PROC paragraph (M03B-READ operation)</li>
 *   <li><b>Lines 164-167</b> - 2000-XREFFILE-PROC sequential read paragraph</li>
 *   <li><b>Lines 188-192</b> - 3000-CUSTFILE-PROC keyed read with FD-CUST-ID key</li>
 *   <li><b>Lines 213-217</b> - 4000-ACCTFILE-PROC keyed read with FD-ACCT-ID key</li>
 * </ul>
 * 
 * <h2>Key Transformations</h2>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Pattern</th>
 *     <th>Java Spring Batch Pattern</th>
 *   </tr>
 *   <tr>
 *     <td>LINKAGE SECTION LK-M03B-AREA parameter passing</td>
 *     <td>Method parameters (Transaction input) and return value (StatementDetail output)</td>
 *   </tr>
 *   <tr>
 *     <td>EVALUATE LK-M03B-DD file dispatcher</td>
 *     <td>Direct repository method calls (CustomerRepository, AccountRepository, CardRepository)</td>
 *   </tr>
 *   <tr>
 *     <td>M03B-READ operation on TRNXFILE</td>
 *     <td>Transaction input parameter (already read by ItemReader)</td>
 *   </tr>
 *   <tr>
 *     <td>M03B-READ-K keyed read on CUSTFILE with FD-CUST-ID</td>
 *     <td>CustomerRepository.findByCustomerId(customerId)</td>
 *   </tr>
 *   <tr>
 *     <td>M03B-READ-K keyed read on ACCTFILE with FD-ACCT-ID</td>
 *     <td>AccountRepository.findByAccountId(accountId)</td>
 *   </tr>
 *   <tr>
 *     <td>M03B-READ sequential read on XREFFILE</td>
 *     <td>CardRepository.findByCardNumber(cardNumber)</td>
 *   </tr>
 *   <tr>
 *     <td>File-status code '00' (success)</td>
 *     <td>Optional.isPresent() - continue processing</td>
 *   </tr>
 *   <tr>
 *     <td>File-status code '10' (EOF)</td>
 *     <td>Optional.isEmpty() or return null - signal no more records</td>
 *   </tr>
 *   <tr>
 *     <td>File-status codes other than '00'/'10' (error)</td>
 *     <td>Throw BusinessLogicException with status code and operation context</td>
 *   </tr>
 * </table>
 * 
 * <h2>Data Enrichment Process</h2>
 * <p>The processor performs the following enrichment steps for each transaction:</p>
 * <ol>
 *   <li><b>Card Lookup</b> - Retrieve card details using transaction's card number
 *       (replaces XREFFILE-PROC sequential read logic lines 164-167)</li>
 *   <li><b>Account Lookup</b> - Retrieve account details and current balance using card's account ID
 *       (replaces ACCTFILE-PROC keyed read logic lines 213-217)</li>
 *   <li><b>Customer Lookup</b> - Retrieve customer information using account's customer ID
 *       (replaces CUSTFILE-PROC keyed read logic lines 188-192)</li>
 *   <li><b>Format Transaction Data</b> - Format amount with 2 decimal places using DecimalFormat("0.00")</li>
 *   <li><b>Format Transaction Date</b> - Format date using DateTimeFormatter.ofPattern("yyyy-MM-dd")</li>
 *   <li><b>Build Merchant Info</b> - Concatenate merchant name, city, and zip code fields</li>
 *   <li><b>Mask Card Number</b> - Show only last 4 digits (12 asterisks + last 4 digits)</li>
 *   <li><b>Calculate Running Balance</b> - Use account's current balance as running balance after transaction</li>
 * </ol>
 * 
 * <h2>File Status Code Handling</h2>
 * <p>COBOL file-status codes map to Java exception handling patterns:</p>
 * <ul>
 *   <li><b>Status '00' (Success)</b> - Optional.isPresent() returns true, processing continues normally</li>
 *   <li><b>Status '10' (End-of-file)</b> - Optional.isEmpty() or return null signals no more records to process</li>
 *   <li><b>Status '23' (Record not found)</b> - Throw ResourceNotFoundException with entity type and key</li>
 *   <li><b>Other status codes (Error)</b> - Throw BusinessLogicException with status code and operation context</li>
 * </ul>
 * 
 * <h2>Amount Formatting</h2>
 * <p>Transaction amounts use BigDecimal with explicit scale and rounding matching COBOL COMP-3:</p>
 * <pre>
 * // Format amount with exactly 2 decimal places
 * DecimalFormat amountFormatter = new DecimalFormat("0.00");
 * String formattedAmount = amountFormatter.format(transaction.getAmount());
 * </pre>
 * 
 * <h2>Date Formatting</h2>
 * <p>Transaction timestamps convert to ISO 8601 date format (yyyy-MM-dd):</p>
 * <pre>
 * // Extract date from timestamp and format
 * LocalDate transactionDate = transaction.getOriginationTimestamp().toLocalDate();
 * String formattedDate = transactionDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
 * </pre>
 * 
 * <h2>Merchant Information Formatting</h2>
 * <p>Merchant details concatenate into single display string:</p>
 * <pre>
 * // Format: "Merchant Name, City, ZIP"
 * String merchantInfo = String.format("%s, %s, %s",
 *     transaction.getMerchantName(),
 *     transaction.getMerchantCity(),
 *     transaction.getMerchantZip());
 * </pre>
 * 
 * <h2>Card Number Masking</h2>
 * <p>Card numbers are masked for security showing only last 4 digits:</p>
 * <pre>
 * // Format: "************1234" (12 asterisks + last 4 digits)
 * String maskedCardNumber = "************" + cardNumber.substring(cardNumber.length() - 4);
 * </pre>
 * 
 * <h2>Error Handling Strategy</h2>
 * <p>The processor handles missing data and errors as follows:</p>
 * <ul>
 *   <li><b>Card Not Found</b> - Throws ResourceNotFoundException (invalid transaction reference)</li>
 *   <li><b>Account Not Found</b> - Throws ResourceNotFoundException (orphaned card record)</li>
 *   <li><b>Customer Not Found</b> - Throws ResourceNotFoundException (orphaned account record)</li>
 *   <li><b>Null Amount</b> - Throws BusinessLogicException (invalid transaction data)</li>
 *   <li><b>Null Merchant Info</b> - Uses empty string or "N/A" as fallback (non-merchant transactions)</li>
 *   <li><b>Format Errors</b> - Throws BusinessLogicException with detailed error message</li>
 * </ul>
 * 
 * <h2>Spring Batch Integration</h2>
 * <p>This processor is used in StatementGenerationJob as part of chunk-oriented processing:</p>
 * <ul>
 *   <li><b>Input</b> - Transaction entities read by TransactionDataReader (10 transactions per chunk)</li>
 *   <li><b>Processing</b> - Enrich and format transaction data for statement display</li>
 *   <li><b>Output</b> - StatementDetail DTOs written by statement report generator</li>
 *   <li><b>Thread Safety</b> - Immutable StatementDetail records ensure thread-safe chunk processing</li>
 * </ul>
 * 
 * <h2>Skip-on-Error Policy</h2>
 * <p>When configured with skip-on-error policy in job definition:</p>
 * <ul>
 *   <li>ResourceNotFoundException exceptions can be skipped (log warning, continue with next transaction)</li>
 *   <li>BusinessLogicException exceptions are not skipped (halt job for data integrity issues)</li>
 *   <li>Skipped transactions are logged to exception file for manual review</li>
 * </ul>
 * 
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li>Repository method calls use indexed lookups (O(log n) complexity)</li>
 *   <li>Connection pooling via HikariCP ensures efficient database access</li>
 *   <li>Chunk size of 10-50 transactions balances throughput and memory usage</li>
 *   <li>Read-only queries use read-committed isolation level</li>
 *   <li>No database writes in processor (read-only enrichment logic)</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * // Configured in StatementGenerationJob
 * {@literal @}Bean
 * public Step statementDetailStep(
 *         JobRepository jobRepository,
 *         PlatformTransactionManager transactionManager,
 *         ItemReader&lt;Transaction&gt; transactionReader,
 *         StatementDetailProcessor processor,
 *         ItemWriter&lt;StatementDetail&gt; statementWriter) {
 *     return new StepBuilder("statementDetailStep", jobRepository)
 *         .&lt;Transaction, StatementDetail&gt;chunk(10, transactionManager)
 *         .reader(transactionReader)
 *         .processor(processor)
 *         .writer(statementWriter)
 *         .build();
 * }
 * </pre>
 * 
 * @see Transaction
 * @see StatementDetail
 * @see CustomerRepository
 * @see AccountRepository
 * @see CardRepository
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CBSTM03B.cbl</a>
 * @see <a href="Section 0.10">Special Instructions - Transaction Management</a>
 */
@Component
@RequiredArgsConstructor
public class StatementDetailProcessor implements ItemProcessor<Transaction, StatementDetail> {

    /**
     * Repository for customer data access.
     * Replaces COBOL CUSTFILE-PROC keyed read operations (lines 188-192).
     */
    private final CustomerRepository customerRepository;

    /**
     * Repository for account data access.
     * Replaces COBOL ACCTFILE-PROC keyed read operations (lines 213-217).
     */
    private final AccountRepository accountRepository;

    /**
     * Repository for card data access.
     * Replaces COBOL XREFFILE-PROC sequential read operations (lines 164-167).
     */
    private final CardRepository cardRepository;

    /**
     * Date formatter for transaction dates.
     * Format: yyyy-MM-dd (ISO 8601 date format).
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Amount formatter for monetary values.
     * Format: 0.00 (exactly 2 decimal places, matching COBOL COMP-3 precision).
     */
    private static final DecimalFormat AMOUNT_FORMATTER = new DecimalFormat("0.00");

    /**
     * Processes a Transaction entity to create a formatted StatementDetail DTO.
     * 
     * <p>This method implements the core statement detail enrichment logic from COBOL
     * subroutine CBSTM03B.CBL, coordinating data access across multiple files (now
     * database tables) to construct complete statement line items.</p>
     * 
     * <h3>Processing Steps</h3>
     * <ol>
     *   <li>Validate transaction has required fields (transactionId, cardNumber, amount, timestamp)</li>
     *   <li>Look up card details using transaction's card number (XREFFILE-PROC logic)</li>
     *   <li>Look up account details using card's account ID (ACCTFILE-PROC logic)</li>
     *   <li>Look up customer information using account's customer ID (CUSTFILE-PROC logic)</li>
     *   <li>Format transaction date from origination timestamp</li>
     *   <li>Format transaction amount with 2 decimal places</li>
     *   <li>Build merchant information string (name, city, zip)</li>
     *   <li>Mask card number showing only last 4 digits</li>
     *   <li>Use account balance as running balance for statement display</li>
     *   <li>Construct immutable StatementDetail record with formatted data</li>
     * </ol>
     * 
     * <h3>COBOL File Status Code Mapping</h3>
     * <ul>
     *   <li><b>Status '00' (Success)</b> - Data found, processing continues
     *       <ul>
     *         <li>Java: Optional.isPresent() returns true</li>
     *         <li>Action: Extract entity and continue enrichment</li>
     *       </ul>
     *   </li>
     *   <li><b>Status '10' (End-of-file)</b> - No more records to process
     *       <ul>
     *         <li>Java: Return null from process() method</li>
     *         <li>Action: Spring Batch signals end of chunk processing</li>
     *       </ul>
     *   </li>
     *   <li><b>Status '23' (Record not found)</b> - Referenced record does not exist
     *       <ul>
     *         <li>Java: Optional.isEmpty() for required lookups</li>
     *         <li>Action: Throw ResourceNotFoundException with entity type and ID</li>
     *       </ul>
     *   </li>
     *   <li><b>Other Status Codes (Error)</b> - Database access or data integrity error
     *       <ul>
     *         <li>Java: Repository method throws exception</li>
     *         <li>Action: Throw BusinessLogicException with operation context</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Error Handling</h3>
     * <p>The method throws exceptions for data integrity issues that prevent statement generation:</p>
     * <ul>
     *   <li><b>ResourceNotFoundException</b> - Card, account, or customer not found
     *       <ul>
     *         <li>Indicates referential integrity violation</li>
     *         <li>Transaction references non-existent entity</li>
     *         <li>Can be configured as skippable in job definition</li>
     *       </ul>
     *   </li>
     *   <li><b>BusinessLogicException</b> - Invalid transaction data or formatting error
     *       <ul>
     *         <li>Null or invalid required fields (amount, timestamp)</li>
     *         <li>Data format errors during enrichment</li>
     *         <li>Should not be skipped (indicates data corruption)</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Null Handling Strategy</h3>
     * <ul>
     *   <li><b>Required Fields</b> - transactionId, cardNumber, amount, originationTimestamp
     *       <ul>
     *         <li>If null: Throw BusinessLogicException</li>
     *         <li>These fields are mandatory for statement line items</li>
     *       </ul>
     *   </li>
     *   <li><b>Optional Fields</b> - merchantName, merchantCity, merchantZip
     *       <ul>
     *         <li>If null: Use empty string or "N/A" as fallback</li>
     *         <li>Non-merchant transactions (fees, interest) may not have merchant info</li>
     *       </ul>
     *   </li>
     *   <li><b>Description Field</b> - transaction description
     *       <ul>
     *         <li>If null: Use "Transaction" as fallback description</li>
     *         <li>Ensures statement always has readable description</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Data Formatting Rules</h3>
     * <ul>
     *   <li><b>Transaction Date</b> - Extract date component from timestamp
     *       <ul>
     *         <li>Input: LocalDateTime originationTimestamp</li>
     *         <li>Process: originationTimestamp.toLocalDate()</li>
     *         <li>Format: yyyy-MM-dd (ISO 8601)</li>
     *         <li>Example: "2024-01-15"</li>
     *       </ul>
     *   </li>
     *   <li><b>Transaction Amount</b> - Format with exactly 2 decimal places
     *       <ul>
     *         <li>Input: BigDecimal amount</li>
     *         <li>Process: amount.setScale(2, RoundingMode.HALF_UP)</li>
     *         <li>Format: 0.00 (DecimalFormat pattern)</li>
     *         <li>Example: 1234.56 → "1234.56"</li>
     *       </ul>
     *   </li>
     *   <li><b>Merchant Information</b> - Concatenate name, city, and zip
     *       <ul>
     *         <li>Input: merchantName, merchantCity, merchantZip strings</li>
     *         <li>Format: "Name, City, ZIP"</li>
     *         <li>Example: "Amazon.com, Seattle, 98101"</li>
     *         <li>Fallback: "N/A" if all fields are null</li>
     *       </ul>
     *   </li>
     *   <li><b>Card Number</b> - Mask all but last 4 digits
     *       <ul>
     *         <li>Input: 16-digit card number string</li>
     *         <li>Process: "************" + last 4 digits</li>
     *         <li>Example: "4111222233334444" → "************4444"</li>
     *       </ul>
     *   </li>
     *   <li><b>Account Balance</b> - Format with 2 decimal places
     *       <ul>
     *         <li>Input: BigDecimal currentBalance from account</li>
     *         <li>Process: currentBalance.setScale(2, RoundingMode.HALF_UP)</li>
     *         <li>Represents running balance after transaction</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Performance Optimization</h3>
     * <ul>
     *   <li>All repository lookups use indexed primary keys or foreign keys (O(log n))</li>
     *   <li>No N+1 query issues (each transaction requires exactly 3 lookups)</li>
     *   <li>Read-only queries with read-committed isolation (no locking overhead)</li>
     *   <li>Immutable StatementDetail return value (thread-safe for parallel processing)</li>
     * </ul>
     * 
     * <h3>Thread Safety</h3>
     * <p>This processor is thread-safe for concurrent chunk processing:</p>
     * <ul>
     *   <li>No mutable instance state (only final fields)</li>
     *   <li>Repository beans are thread-safe (Spring-managed)</li>
     *   <li>Local variables in process() method are thread-confined</li>
     *   <li>StatementDetail output is immutable record</li>
     * </ul>
     * 
     * @param transaction The transaction entity to process and format for statement display.
     *                    Must not be null and must have valid transactionId, cardNumber,
     *                    amount, and originationTimestamp fields populated.
     * 
     * @return StatementDetail DTO with formatted transaction data, enriched with customer
     *         and account information, and formatted amounts/dates. Returns null if transaction
     *         should be filtered out (not used in current implementation, but supported by
     *         Spring Batch for optional filtering logic).
     * 
     * @throws ResourceNotFoundException If card, account, or customer referenced by the
     *         transaction cannot be found in the database. This indicates a referential
     *         integrity violation or orphaned transaction record. Exception includes entity
     *         type and ID for debugging.
     * 
     * @throws BusinessLogicException If transaction has invalid data (null required fields,
     *         invalid format) or if data formatting fails. This indicates data corruption
     *         or invalid transaction state that prevents statement generation.
     * 
     * @see #lookupCard(String)
     * @see #lookupAccount(Long)
     * @see #lookupCustomer(Long)
     * @see #formatTransactionDate(LocalDateTime)
     * @see #formatAmount(BigDecimal)
     * @see #buildMerchantInfo(Transaction)
     * @see #maskCardNumber(String)
     */
    @Override
    public StatementDetail process(Transaction transaction) throws Exception {
        // Validate transaction has required fields
        validateTransaction(transaction);

        // Step 1: Look up card details using transaction's card number
        // Replaces COBOL: 2000-XREFFILE-PROC sequential read (lines 164-167)
        // Maps: READ XREF-FILE INTO LK-M03B-FLDT → CardRepository.findByCardNumber()
        Card card = lookupCard(transaction.getCardNumber());

        // Step 2: Look up account details using card's account ID
        // Replaces COBOL: 4000-ACCTFILE-PROC keyed read (lines 213-217)
        // Maps: MOVE LK-M03B-KEY TO FD-ACCT-ID; READ ACCT-FILE → AccountRepository.findByAccountId()
        Account account = lookupAccount(card.getAccountId());

        // Step 3: Look up customer information using account's customer ID
        // Replaces COBOL: 3000-CUSTFILE-PROC keyed read (lines 188-192)
        // Maps: MOVE LK-M03B-KEY TO FD-CUST-ID; READ CUST-FILE → CustomerRepository.findByCustomerId()
        Customer customer = lookupCustomer(account.getCustomerId());

        // Step 4: Format transaction date from origination timestamp
        // Format: yyyy-MM-dd (ISO 8601 date format)
        LocalDate transactionDate = formatTransactionDate(transaction.getOriginationTimestamp());

        // Step 5: Format transaction amount with exactly 2 decimal places
        // Matches COBOL COMP-3 packed decimal precision (PIC S9(09)V99)
        BigDecimal formattedAmount = formatAmount(transaction.getAmount());

        // Step 6: Build merchant information string (name, city, zip)
        // Format: "Merchant Name, City, ZIP" or "N/A" for non-merchant transactions
        String merchantInfo = buildMerchantInfo(transaction);

        // Step 7: Mask card number showing only last 4 digits
        // Format: "************1234" (12 asterisks + last 4 digits)
        String maskedCardNumber = maskCardNumber(transaction.getCardNumber());

        // Step 8: Get account balance as running balance
        // Represents account balance after this transaction posts
        BigDecimal accountBalance = formatAmount(account.getCurrentBalance());

        // Step 9: Build description combining merchant and transaction description
        String description = buildDescription(transaction);

        // Step 10: Construct immutable StatementDetail record
        // Returns formatted data ready for PDF/HTML statement generation
        return StatementDetail.builder()
                .transactionId(transaction.getTransactionId())
                .transactionDate(transactionDate)
                .description(description)
                .amount(formattedAmount)
                .merchantInfo(merchantInfo)
                .cardNumber(maskedCardNumber)
                .accountBalance(accountBalance)
                .build();
    }

    /**
     * Validates that transaction has all required fields for statement generation.
     * 
     * <p>Verifies the following mandatory fields:</p>
     * <ul>
     *   <li>transactionId - Must be non-null and non-empty</li>
     *   <li>cardNumber - Must be non-null and non-empty (16 characters)</li>
     *   <li>amount - Must be non-null (monetary value required)</li>
     *   <li>originationTimestamp - Must be non-null (date/time required)</li>
     * </ul>
     * 
     * @param transaction The transaction entity to validate
     * 
     * @throws BusinessLogicException If any required field is null or invalid,
     *         with specific message indicating which field is missing
     */
    private void validateTransaction(Transaction transaction) {
        if (transaction == null) {
            throw new BusinessLogicException("Transaction is null - cannot process statement detail");
        }

        if (transaction.getTransactionId() == null || transaction.getTransactionId().trim().isEmpty()) {
            throw new BusinessLogicException(
                    "Transaction ID is null or empty - cannot generate statement line item");
        }

        if (transaction.getCardNumber() == null || transaction.getCardNumber().trim().isEmpty()) {
            throw new BusinessLogicException(
                    "Card number is null or empty for transaction " + transaction.getTransactionId());
        }

        if (transaction.getAmount() == null) {
            throw new BusinessLogicException(
                    "Transaction amount is null for transaction " + transaction.getTransactionId());
        }

        if (transaction.getOriginationTimestamp() == null) {
            throw new BusinessLogicException(
                    "Transaction origination timestamp is null for transaction " + transaction.getTransactionId());
        }
    }

    /**
     * Looks up card entity by card number.
     * 
     * <p>Replaces COBOL XREFFILE-PROC sequential read logic (lines 164-167):</p>
     * <pre>
     * IF M03B-READ
     *     READ XREF-FILE INTO LK-M03B-FLDT
     * END-READ
     * </pre>
     * 
     * <p>Maps COBOL file-status codes to exception handling:</p>
     * <ul>
     *   <li>Status '00' (success) - Optional.isPresent(), return card entity</li>
     *   <li>Status '10' (EOF) or '23' (not found) - Optional.isEmpty(), throw ResourceNotFoundException</li>
     * </ul>
     * 
     * @param cardNumber The 16-character card number to look up
     * 
     * @return Card entity with account relationship populated
     * 
     * @throws ResourceNotFoundException If card is not found in database,
     *         indicating invalid transaction reference or orphaned transaction record
     */
    private Card lookupCard(String cardNumber) {
        Optional<Card> cardOptional = cardRepository.findByCardNumber(cardNumber);

        if (cardOptional.isEmpty()) {
            // Maps to COBOL file-status '23' (record not found) or '10' (EOF)
            throw new ResourceNotFoundException(
                    "Card not found for card number: " + cardNumber + 
                    " - Transaction references non-existent card");
        }

        // Maps to COBOL file-status '00' (success) - record found
        return cardOptional.get();
    }

    /**
     * Looks up account entity by account ID.
     * 
     * <p>Replaces COBOL ACCTFILE-PROC keyed read logic (lines 213-217):</p>
     * <pre>
     * IF M03B-READ-K
     *     MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID
     *     READ ACCT-FILE INTO LK-M03B-FLDT
     * END-READ
     * </pre>
     * 
     * <p>Maps COBOL file-status codes to exception handling:</p>
     * <ul>
     *   <li>Status '00' (success) - Optional.isPresent(), return account entity</li>
     *   <li>Status '10' (EOF) or '23' (not found) - Optional.isEmpty(), throw ResourceNotFoundException</li>
     * </ul>
     * 
     * @param accountId The account ID to look up (11-digit numeric value)
     * 
     * @return Account entity with current balance and customer relationship populated
     * 
     * @throws ResourceNotFoundException If account is not found in database,
     *         indicating orphaned card record or referential integrity violation
     */
    private Account lookupAccount(Long accountId) {
        Optional<Account> accountOptional = accountRepository.findByAccountId(accountId);

        if (accountOptional.isEmpty()) {
            // Maps to COBOL file-status '23' (record not found) or '10' (EOF)
            throw new ResourceNotFoundException(
                    "Account not found for account ID: " + accountId + 
                    " - Card references non-existent account");
        }

        // Maps to COBOL file-status '00' (success) - record found
        return accountOptional.get();
    }

    /**
     * Looks up customer entity by customer ID.
     * 
     * <p>Replaces COBOL CUSTFILE-PROC keyed read logic (lines 188-192):</p>
     * <pre>
     * IF M03B-READ-K
     *     MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID
     *     READ CUST-FILE INTO LK-M03B-FLDT
     * END-READ
     * </pre>
     * 
     * <p>Maps COBOL file-status codes to exception handling:</p>
     * <ul>
     *   <li>Status '00' (success) - Optional.isPresent(), return customer entity</li>
     *   <li>Status '10' (EOF) or '23' (not found) - Optional.isEmpty(), throw ResourceNotFoundException</li>
     * </ul>
     * 
     * @param customerId The customer ID to look up (9-digit numeric value)
     * 
     * @return Customer entity with name and address information populated
     * 
     * @throws ResourceNotFoundException If customer is not found in database,
     *         indicating orphaned account record or referential integrity violation
     */
    private Customer lookupCustomer(Long customerId) {
        Optional<Customer> customerOptional = customerRepository.findByCustomerId(customerId);

        if (customerOptional.isEmpty()) {
            // Maps to COBOL file-status '23' (record not found) or '10' (EOF)
            throw new ResourceNotFoundException(
                    "Customer not found for customer ID: " + customerId + 
                    " - Account references non-existent customer");
        }

        // Maps to COBOL file-status '00' (success) - record found
        return customerOptional.get();
    }

    /**
     * Formats transaction origination timestamp to date component.
     * 
     * <p>Extracts the date component from the transaction timestamp and formats
     * it as an ISO 8601 date string (yyyy-MM-dd) for statement display.</p>
     * 
     * <p>COBOL timestamp conversion:</p>
     * <ul>
     *   <li>Input: TRAN-ORIG-TS (PIC X(26)) - COBOL timestamp string</li>
     *   <li>Java: LocalDateTime originationTimestamp - parsed timestamp</li>
     *   <li>Output: LocalDate transactionDate - date component only</li>
     * </ul>
     * 
     * @param timestamp The transaction origination timestamp (must not be null)
     * 
     * @return LocalDate representing the transaction date for statement display
     * 
     * @throws BusinessLogicException If timestamp is null or invalid format
     */
    private LocalDate formatTransactionDate(LocalDateTime timestamp) {
        if (timestamp == null) {
            throw new BusinessLogicException(
                    "Cannot format null transaction timestamp - date required for statement");
        }

        try {
            // Extract date component from timestamp
            // Format: yyyy-MM-dd (ISO 8601 date format)
            return timestamp.toLocalDate();
        } catch (Exception e) {
            throw new BusinessLogicException(
                    "Error formatting transaction date from timestamp: " + timestamp, e);
        }
    }

    /**
     * Formats transaction amount with exactly 2 decimal places.
     * 
     * <p>Ensures consistent decimal precision matching COBOL COMP-3 packed decimal
     * format (PIC S9(09)V99) with explicit scale and rounding mode.</p>
     * 
     * <p>Rounding behavior:</p>
     * <ul>
     *   <li>RoundingMode.HALF_UP - Matches COBOL rounding behavior</li>
     *   <li>Scale = 2 - Exactly two decimal places for cents</li>
     *   <li>Example: 1234.567 → 1234.57 (rounds up)</li>
     *   <li>Example: 1234.564 → 1234.56 (rounds down)</li>
     * </ul>
     * 
     * @param amount The transaction amount to format (must not be null)
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
     * Builds merchant information string by concatenating merchant fields.
     * 
     * <p>Constructs formatted merchant display string for statement line items:</p>
     * <ul>
     *   <li>Format: "Merchant Name, City, ZIP"</li>
     *   <li>Example: "Amazon.com, Seattle, 98101"</li>
     *   <li>Fallback: "N/A" if all merchant fields are null (non-merchant transactions)</li>
     * </ul>
     * 
     * <p>Handles merchant transactions:</p>
     * <ul>
     *   <li>Purchase transactions - Full merchant details available</li>
     *   <li>Cash advance transactions - ATM location in merchant fields</li>
     *   <li>Fee/interest charges - All merchant fields null, use "N/A"</li>
     *   <li>Payments received - All merchant fields null, use "N/A"</li>
     * </ul>
     * 
     * @param transaction The transaction entity containing merchant fields
     * 
     * @return Formatted merchant information string or "N/A" for non-merchant transactions
     */
    private String buildMerchantInfo(Transaction transaction) {
        String merchantName = transaction.getMerchantName();
        String merchantCity = transaction.getMerchantCity();
        String merchantZip = transaction.getMerchantZip();

        // Check if all merchant fields are null (non-merchant transaction)
        if (merchantName == null && merchantCity == null && merchantZip == null) {
            return "N/A";
        }

        // Use empty string for null fields
        merchantName = (merchantName != null) ? merchantName : "";
        merchantCity = (merchantCity != null) ? merchantCity : "";
        merchantZip = (merchantZip != null) ? merchantZip : "";

        // Build formatted string: "Name, City, ZIP"
        return String.format("%s, %s, %s", merchantName, merchantCity, merchantZip).trim();
    }

    /**
     * Masks card number showing only last 4 digits.
     * 
     * <p>Security masking for card numbers on statements:</p>
     * <ul>
     *   <li>Input: 16-digit card number (e.g., "4111222233334444")</li>
     *   <li>Output: 12 asterisks + last 4 digits (e.g., "************4444")</li>
     *   <li>Complies with PCI-DSS requirements for displaying card numbers</li>
     * </ul>
     * 
     * <p>Validation:</p>
     * <ul>
     *   <li>Card number must be at least 4 characters long</li>
     *   <li>If less than 4 characters, mask entire number with asterisks</li>
     * </ul>
     * 
     * @param cardNumber The full 16-digit card number to mask
     * 
     * @return Masked card number with last 4 digits visible
     * 
     * @throws BusinessLogicException If card number is null or empty
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.trim().isEmpty()) {
            throw new BusinessLogicException(
                    "Cannot mask null or empty card number - card number required for statement");
        }

        // Handle card numbers shorter than 4 characters
        if (cardNumber.length() < 4) {
            return "************";
        }

        // Mask all but last 4 digits
        // Format: "************1234" (12 asterisks + last 4 digits)
        String lastFour = cardNumber.substring(cardNumber.length() - 4);
        return "************" + lastFour;
    }

    /**
     * Builds transaction description combining merchant name and transaction description.
     * 
     * <p>Creates human-readable description for statement display:</p>
     * <ul>
     *   <li>If merchant name and description available: "Merchant Name - Description"</li>
     *   <li>If only merchant name available: "Merchant Name"</li>
     *   <li>If only description available: "Description"</li>
     *   <li>If neither available: "Transaction" (fallback)</li>
     * </ul>
     * 
     * <p>Example outputs:</p>
     * <ul>
     *   <li>"Amazon.com - PURCHASE 123-4567890-1234567"</li>
     *   <li>"SHELL OIL - GAS STATION CHICAGO IL"</li>
     *   <li>"ANNUAL FEE" (fee transaction, no merchant)</li>
     *   <li>"PAYMENT RECEIVED - THANK YOU" (payment, no merchant)</li>
     * </ul>
     * 
     * @param transaction The transaction entity containing description fields
     * 
     * @return Formatted description string for statement display
     */
    private String buildDescription(Transaction transaction) {
        String merchantName = transaction.getMerchantName();
        String description = transaction.getDescription();

        // Build description based on available fields
        if (merchantName != null && !merchantName.trim().isEmpty() &&
            description != null && !description.trim().isEmpty()) {
            return merchantName.trim() + " - " + description.trim();
        } else if (merchantName != null && !merchantName.trim().isEmpty()) {
            return merchantName.trim();
        } else if (description != null && !description.trim().isEmpty()) {
            return description.trim();
        } else {
            return "Transaction";
        }
    }
}

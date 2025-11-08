package com.carddemo.batch.processor;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring Batch ItemProcessor for daily transaction validation and processing.
 * 
 * Implements exact COBOL business logic from CBTRN02C.cbl batch program for validating
 * and posting daily transaction records. This processor transforms DailyTransactionInput
 * records into validated Transaction entities with proper balance calculations.
 * 
 * COBOL Program Mapping:
 * - Source: app/cbl/CBTRN02C.cbl
 * - Main Processing Loop: Lines 202-219
 * - Validation Logic: 1500-VALIDATE-TRAN paragraph (lines 370-422)
 * - Transaction Posting: 2000-POST-TRANSACTION paragraph (lines 424-444)
 * 
 * Validation Rules (from COBOL 1500-VALIDATE-TRAN):
 * 1. Card Number Validation (1500-A-LOOKUP-XREF, lines 380-392):
 *    - Verify card number exists in XREF file (CardRepository)
 *    - Failure Code: 100 "INVALID CARD NUMBER FOUND"
 * 
 * 2. Account Validation (1500-B-LOOKUP-ACCT, lines 393-422):
 *    - Verify account record exists for card's account ID
 *    - Failure Code: 101 "ACCOUNT RECORD NOT FOUND"
 * 
 * 3. Credit Limit Validation (lines 403-413):
 *    - Calculate: WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
 *    - Check: ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
 *    - Failure Code: 102 "OVERLIMIT TRANSACTION"
 * 
 * 4. Expiration Date Validation (lines 414-420):
 *    - Check: ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS
 *    - Failure Code: 103 "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
 * 
 * Balance Update Logic (from COBOL 2800-UPDATE-ACCOUNT-REC, lines 545-560):
 * - ADD DALYTRAN-AMT TO ACCT-CURR-BAL (line 547)
 * - IF DALYTRAN-AMT >= 0: ADD TO ACCT-CURR-CYC-CREDIT (lines 548-549)
 * - ELSE: ADD TO ACCT-CURR-CYC-DEBIT (lines 550-551)
 * 
 * Transaction Category Balance Logic (from COBOL 2700-UPDATE-TCATBAL, lines 467-542):
 * - Read existing balance record or create new
 * - ADD DALYTRAN-AMT TO TRAN-CAT-BAL (lines 508, 527)
 * 
 * Decimal Precision:
 * All BigDecimal operations use scale=2 and RoundingMode.HALF_UP to match COBOL
 * COMP-3 packed decimal precision per section 0.10 special instruction #7.
 * 
 * Error Handling:
 * - Returns null for validation failures (Spring Batch skips record)
 * - Logs rejection reason with failure code and description
 * - Matches COBOL WS-VALIDATION-FAIL-REASON codes (lines 181-182)
 * 
 * Integration:
 * Used in DailyTransactionProcessingJob as part of chunk-oriented processing.
 * Supports transactional batch operations with automatic rollback on errors.
 * 
 * @see Transaction
 * @see Account
 * @see Card
 * @see TransactionCategoryBalance
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionProcessor implements ItemProcessor<DailyTransactionInput, Transaction> {

    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Process a single daily transaction input record.
     * 
     * Maps to COBOL main processing loop (lines 202-219) that calls:
     * - 1500-VALIDATE-TRAN: Validate transaction (returns null if validation fails)
     * - 2000-POST-TRANSACTION: Post transaction and update balances
     * 
     * @param item the daily transaction input record to process
     * @return enriched Transaction entity if validation passes, null if validation fails
     * @throws Exception if processing error occurs
     */
    @Override
    public Transaction process(DailyTransactionInput item) throws Exception {
        log.debug("Processing transaction: transactionId={}, cardNumber={}, amount={}",
                item.getTransactionId(), item.getCardNumber(), item.getAmount());

        // Step 1: Validate transaction (COBOL 1500-VALIDATE-TRAN)
        ValidationResult validationResult = validateTransaction(item);
        
        if (!validationResult.isValid()) {
            // Log rejection matching COBOL WS-VALIDATION-FAIL-REASON and DESC (lines 181-182)
            log.warn("Transaction rejected: transactionId={}, failureCode={}, reason={}",
                    item.getTransactionId(), 
                    validationResult.getFailureCode(),
                    validationResult.getFailureReason());
            
            // Return null to skip this record (Spring Batch will count as filtered/skipped)
            // Matches COBOL logic at lines 213-216 that writes reject record instead of posting
            return null;
        }

        // Step 2: Post transaction and update balances (COBOL 2000-POST-TRANSACTION)
        Transaction transaction = postTransaction(item, validationResult.getCard(), validationResult.getAccount());
        
        log.info("Transaction processed successfully: transactionId={}, cardNumber={}, amount={}",
                transaction.getTransactionId(), transaction.getCardNumber(), transaction.getAmount());
        
        return transaction;
    }

    /**
     * Validate transaction according to COBOL validation rules.
     * 
     * Implements COBOL 1500-VALIDATE-TRAN paragraph (lines 370-422):
     * - PERFORM 1500-A-LOOKUP-XREF (card validation)
     * - PERFORM 1500-B-LOOKUP-ACCT (account validation, credit limit, expiration)
     * 
     * @param item the transaction input to validate
     * @return ValidationResult containing validation status, failure details, and retrieved entities
     */
    private ValidationResult validateTransaction(DailyTransactionInput item) {
        // 1500-A-LOOKUP-XREF: Card number validation (lines 380-392)
        Optional<Card> cardOpt = cardRepository.findByCardNumber(item.getCardNumber());
        
        if (!cardOpt.isPresent()) {
            // COBOL lines 385-387: INVALID KEY, code 100
            return ValidationResult.failure(
                    100,
                    "INVALID CARD NUMBER FOUND"
            );
        }
        
        Card card = cardOpt.get();
        
        // 1500-B-LOOKUP-ACCT: Account lookup (lines 393-422)
        Optional<Account> accountOpt = accountRepository.findByAccountId(card.getAccountId());
        
        if (!accountOpt.isPresent()) {
            // COBOL lines 396-399: INVALID KEY, code 101
            return ValidationResult.failure(
                    101,
                    "ACCOUNT RECORD NOT FOUND"
            );
        }
        
        Account account = accountOpt.get();
        
        // Credit limit validation (lines 403-413)
        // COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
        BigDecimal tempBalance = account.getCurrentCycleCredit()
                .subtract(account.getCurrentCycleDebit())
                .add(item.getAmount())
                .setScale(2, RoundingMode.HALF_UP);
        
        // IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL (line 407)
        if (account.getCreditLimit().compareTo(tempBalance) < 0) {
            // COBOL lines 410-412: Over limit, code 102
            return ValidationResult.failure(
                    102,
                    "OVERLIMIT TRANSACTION"
            );
        }
        
        // Expiration date validation (lines 414-420)
        // IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
        // Convert transaction timestamp to LocalDate for comparison
        LocalDateTime transactionTimestamp = item.getOriginationTimestamp();
        if (transactionTimestamp != null && account.getExpirationDate() != null) {
            if (account.getExpirationDate().toLocalDate().isBefore(transactionTimestamp.toLocalDate())) {
                // COBOL lines 417-419: Expired account, code 103
                return ValidationResult.failure(
                        103,
                        "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
                );
            }
        }
        
        // All validations passed (COBOL line 211: WS-VALIDATION-FAIL-REASON = 0)
        return ValidationResult.success(card, account);
    }

    /**
     * Post transaction and update account/category balances.
     * 
     * Implements COBOL 2000-POST-TRANSACTION paragraph (lines 424-444):
     * - Populate transaction record fields (lines 425-438)
     * - PERFORM 2700-UPDATE-TCATBAL (update category balance)
     * - PERFORM 2800-UPDATE-ACCOUNT-REC (update account balance)
     * - PERFORM 2900-WRITE-TRANSACTION-FILE (write transaction record)
     * 
     * @param item the transaction input
     * @param card the validated card entity
     * @param account the validated account entity
     * @return Transaction entity with all fields populated
     */
    private Transaction postTransaction(DailyTransactionInput item, Card card, Account account) {
        // Update transaction category balance (COBOL 2700-UPDATE-TCATBAL, lines 467-542)
        updateCategoryBalance(item, account);
        
        // Update account balance (COBOL 2800-UPDATE-ACCOUNT-REC, lines 545-560)
        updateAccountBalance(item, account);
        
        // Build transaction record (COBOL lines 425-438)
        // PERFORM Z-GET-DB2-FORMAT-TIMESTAMP at line 437
        LocalDateTime processingTimestamp = LocalDateTime.now();
        
        Transaction transaction = Transaction.builder()
                .transactionId(item.getTransactionId())              // DALYTRAN-ID TO TRAN-ID (line 425)
                .typeCode(item.getTypeCode())                        // DALYTRAN-TYPE-CD TO TRAN-TYPE-CD (line 426)
                .categoryCode(item.getCategoryCode())                // DALYTRAN-CAT-CD TO TRAN-CAT-CD (line 427)
                .transactionSource(item.getTransactionSource())      // DALYTRAN-SOURCE TO TRAN-SOURCE (line 428)
                .description(item.getDescription())                  // DALYTRAN-DESC TO TRAN-DESC (line 429)
                .amount(item.getAmount())                            // DALYTRAN-AMT TO TRAN-AMT (line 430)
                .merchantId(item.getMerchantId())                    // DALYTRAN-MERCHANT-ID TO TRAN-MERCHANT-ID (line 431)
                .merchantName(item.getMerchantName())                // DALYTRAN-MERCHANT-NAME TO TRAN-MERCHANT-NAME (line 432)
                .merchantCity(item.getMerchantCity())                // DALYTRAN-MERCHANT-CITY TO TRAN-MERCHANT-CITY (line 433)
                .merchantZip(item.getMerchantZip())                  // DALYTRAN-MERCHANT-ZIP TO TRAN-MERCHANT-ZIP (line 434)
                .cardNumber(item.getCardNumber())                    // DALYTRAN-CARD-NUM TO TRAN-CARD-NUM (line 435)
                .originationTimestamp(item.getOriginationTimestamp()) // DALYTRAN-ORIG-TS TO TRAN-ORIG-TS (line 436)
                .processingTimestamp(processingTimestamp)            // DB2-FORMAT-TS TO TRAN-PROC-TS (line 438)
                .build();
        
        return transaction;
    }

    /**
     * Update transaction category balance.
     * 
     * Implements COBOL 2700-UPDATE-TCATBAL paragraph (lines 467-542):
     * - Read TCATBAL-FILE with composite key (accountId, typeCode, categoryCode)
     * - If INVALID KEY: create new record (2700-A-CREATE-TCATBAL-REC, lines 503-524)
     * - Else: update existing record (2700-B-UPDATE-TCATBAL-REC, lines 526-542)
     * - ADD DALYTRAN-AMT TO TRAN-CAT-BAL (lines 508, 527)
     * 
     * @param item the transaction input
     * @param account the account entity
     */
    private void updateCategoryBalance(DailyTransactionInput item, Account account) {
        // Build composite key: XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD (lines 469-471)
        Long accountId = account.getAccountId();
        String typeCode = item.getTypeCode();
        String categoryCode = item.getCategoryCode();
        
        // READ TCATBAL-FILE (lines 474-479)
        Optional<TransactionCategoryBalance> balanceOpt = 
                transactionCategoryBalanceRepository.findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        accountId, typeCode, categoryCode);
        
        TransactionCategoryBalance balance;
        
        if (!balanceOpt.isPresent()) {
            // INVALID KEY: create new record (COBOL lines 475-478, 495-496)
            log.debug("TCATBAL record not found for key: accountId={}, typeCode={}, categoryCode={}.. Creating.",
                    accountId, typeCode, categoryCode);
            
            // 2700-A-CREATE-TCATBAL-REC (lines 503-524)
            // INITIALIZE TRAN-CAT-BAL-RECORD (line 504)
            balance = new TransactionCategoryBalance();
            balance.setAccountId(accountId);                      // XREF-ACCT-ID TO TRANCAT-ACCT-ID (line 505)
            balance.setTransactionTypeCode(typeCode);             // DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD (line 506)
            balance.setCategoryCode(categoryCode);                // DALYTRAN-CAT-CD TO TRANCAT-CD (line 507)
            balance.setBalance(item.getAmount().setScale(2, RoundingMode.HALF_UP));  // ADD DALYTRAN-AMT (line 508)
        } else {
            // Record exists: update (COBOL lines 497-498)
            // 2700-B-UPDATE-TCATBAL-REC (lines 526-542)
            balance = balanceOpt.get();
            // ADD DALYTRAN-AMT TO TRAN-CAT-BAL (line 527)
            BigDecimal newBalance = balance.getBalance()
                    .add(item.getAmount())
                    .setScale(2, RoundingMode.HALF_UP);
            balance.setBalance(newBalance);
        }
        
        // Save (WRITE or REWRITE in COBOL)
        transactionCategoryBalanceRepository.save(balance);
        
        log.debug("Updated category balance: accountId={}, typeCode={}, categoryCode={}, newBalance={}",
                accountId, typeCode, categoryCode, balance.getBalance());
    }

    /**
     * Update account balance and cycle credit/debit counters.
     * 
     * Implements COBOL 2800-UPDATE-ACCOUNT-REC paragraph (lines 545-560):
     * - ADD DALYTRAN-AMT TO ACCT-CURR-BAL (line 547)
     * - IF DALYTRAN-AMT >= 0: ADD TO ACCT-CURR-CYC-CREDIT (lines 548-549)
     * - ELSE: ADD TO ACCT-CURR-CYC-DEBIT (lines 550-551)
     * - REWRITE FD-ACCTFILE-REC (line 554)
     * 
     * @param item the transaction input
     * @param account the account entity to update
     */
    private void updateAccountBalance(DailyTransactionInput item, Account account) {
        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL (line 547)
        BigDecimal newBalance = account.getCurrentBalance()
                .add(item.getAmount())
                .setScale(2, RoundingMode.HALF_UP);
        account.setCurrentBalance(newBalance);
        
        // Update cycle credit or debit based on transaction amount sign (lines 548-552)
        if (item.getAmount().compareTo(BigDecimal.ZERO) >= 0) {
            // IF DALYTRAN-AMT >= 0: ADD TO ACCT-CURR-CYC-CREDIT (lines 548-549)
            BigDecimal newCycleCredit = account.getCurrentCycleCredit()
                    .add(item.getAmount())
                    .setScale(2, RoundingMode.HALF_UP);
            account.setCurrentCycleCredit(newCycleCredit);
        } else {
            // ELSE: ADD TO ACCT-CURR-CYC-DEBIT (lines 550-551)
            BigDecimal newCycleDebit = account.getCurrentCycleDebit()
                    .add(item.getAmount())
                    .setScale(2, RoundingMode.HALF_UP);
            account.setCurrentCycleDebit(newCycleDebit);
        }
        
        // REWRITE FD-ACCTFILE-REC (line 554)
        accountRepository.save(account);
        
        log.debug("Updated account balance: accountId={}, newBalance={}, cycleCredit={}, cycleDebit={}",
                account.getAccountId(), newBalance, account.getCurrentCycleCredit(), account.getCurrentCycleDebit());
    }

    /**
     * Internal class to hold validation results.
     * 
     * Encapsulates validation outcome and provides access to retrieved entities
     * for subsequent processing. Maps to COBOL WS-VALIDATION-FAIL-REASON and
     * WS-VALIDATION-FAIL-REASON-DESC fields (lines 181-182).
     */
    private static class ValidationResult {
        private final boolean valid;
        private final int failureCode;
        private final String failureReason;
        private final Card card;
        private final Account account;

        private ValidationResult(boolean valid, int failureCode, String failureReason, 
                                 Card card, Account account) {
            this.valid = valid;
            this.failureCode = failureCode;
            this.failureReason = failureReason;
            this.card = card;
            this.account = account;
        }

        /**
         * Create a successful validation result.
         * 
         * @param card the validated card entity
         * @param account the validated account entity
         * @return ValidationResult with valid=true
         */
        static ValidationResult success(Card card, Account account) {
            return new ValidationResult(true, 0, null, card, account);
        }

        /**
         * Create a failed validation result.
         * 
         * @param failureCode the COBOL failure code (100, 101, 102, 103)
         * @param failureReason the COBOL failure reason description
         * @return ValidationResult with valid=false
         */
        static ValidationResult failure(int failureCode, String failureReason) {
            return new ValidationResult(false, failureCode, failureReason, null, null);
        }

        boolean isValid() {
            return valid;
        }

        int getFailureCode() {
            return failureCode;
        }

        String getFailureReason() {
            return failureReason;
        }

        Card getCard() {
            return card;
        }

        Account getAccount() {
            return account;
        }
    }

    /**
     * Input DTO for daily transaction processing.
     * 
     * Represents the structure of daily transaction input records read from
     * DALYTRAN-FILE in COBOL (CVTRA06Y.cpy copybook).
     * 
     * Maps COBOL DALYTRAN-RECORD fields to Java properties with proper data types.
     */
    public static class DailyTransactionInput {
        private String transactionId;           // DALYTRAN-ID PIC X(16)
        private String typeCode;                // DALYTRAN-TYPE-CD PIC X(02)
        private String categoryCode;            // DALYTRAN-CAT-CD PIC 9(04)
        private String transactionSource;       // DALYTRAN-SOURCE PIC X(10)
        private String description;             // DALYTRAN-DESC PIC X(100)
        private BigDecimal amount;              // DALYTRAN-AMT PIC S9(09)V99
        private String merchantId;              // DALYTRAN-MERCHANT-ID PIC 9(09)
        private String merchantName;            // DALYTRAN-MERCHANT-NAME PIC X(50)
        private String merchantCity;            // DALYTRAN-MERCHANT-CITY PIC X(50)
        private String merchantZip;             // DALYTRAN-MERCHANT-ZIP PIC X(10)
        private String cardNumber;              // DALYTRAN-CARD-NUM PIC X(16)
        private LocalDateTime originationTimestamp; // DALYTRAN-ORIG-TS PIC X(26)

        // Getters and setters
        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }

        public String getTypeCode() {
            return typeCode;
        }

        public void setTypeCode(String typeCode) {
            this.typeCode = typeCode;
        }

        public String getCategoryCode() {
            return categoryCode;
        }

        public void setCategoryCode(String categoryCode) {
            this.categoryCode = categoryCode;
        }

        public String getTransactionSource() {
            return transactionSource;
        }

        public void setTransactionSource(String transactionSource) {
            this.transactionSource = transactionSource;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public String getMerchantId() {
            return merchantId;
        }

        public void setMerchantId(String merchantId) {
            this.merchantId = merchantId;
        }

        public String getMerchantName() {
            return merchantName;
        }

        public void setMerchantName(String merchantName) {
            this.merchantName = merchantName;
        }

        public String getMerchantCity() {
            return merchantCity;
        }

        public void setMerchantCity(String merchantCity) {
            this.merchantCity = merchantCity;
        }

        public String getMerchantZip() {
            return merchantZip;
        }

        public void setMerchantZip(String merchantZip) {
            this.merchantZip = merchantZip;
        }

        public String getCardNumber() {
            return cardNumber;
        }

        public void setCardNumber(String cardNumber) {
            this.cardNumber = cardNumber;
        }

        public LocalDateTime getOriginationTimestamp() {
            return originationTimestamp;
        }

        public void setOriginationTimestamp(LocalDateTime originationTimestamp) {
            this.originationTimestamp = originationTimestamp;
        }
    }
}

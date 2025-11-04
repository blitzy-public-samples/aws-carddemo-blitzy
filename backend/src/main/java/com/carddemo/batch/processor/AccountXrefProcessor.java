/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.processor;

import com.carddemo.entity.Account;
import com.carddemo.entity.AccountXref;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Batch ItemProcessor for validating and building cross-reference entries from Card entities.
 * 
 * <p>This processor transforms the COBOL CBACT02C.cbl batch program logic that reads card data
 * and builds cross-reference relationships between cards, accounts, and customers. In the mainframe
 * implementation, these relationships were maintained through VSAM alternate indexes (XREF, CXACAIX).
 * In the modernized architecture, they are explicitly stored as cross-reference entities with foreign
 * key constraints in PostgreSQL.</p>
 * 
 * <p><strong>COBOL Source Transformation:</strong></p>
 * <ul>
 *   <li>Source: app/cbl/CBACT02C.cbl - Card file read and cross-reference build program</li>
 *   <li>Replaces: VSAM alternate index automatic cross-reference creation</li>
 *   <li>Implements: Explicit validation and cross-reference entry creation</li>
 * </ul>
 * 
 * <p><strong>Business Logic:</strong></p>
 * <ol>
 *   <li>Validate Card has non-null Account reference (card.getAccount() != null)</li>
 *   <li>Validate Account has non-null Customer reference (account.getCustomer() != null)</li>
 *   <li>Create AccountXref entry mapping Customer to Account (customerId, accountId)</li>
 *   <li>Create CardXref entry mapping Card to Account and Customer (cardNumber, customerId, accountId)</li>
 *   <li>Return XrefEntry wrapper containing both cross-reference objects</li>
 * </ol>
 * 
 * <p><strong>Validation Rules:</strong></p>
 * <ul>
 *   <li>Card must have valid Account reference (not null)</li>
 *   <li>Account must have valid Customer reference (not null)</li>
 *   <li>Missing relationships throw ValidationException</li>
 *   <li>Invalid records are skipped per batch job skip policy (limit: 100 errors)</li>
 * </ul>
 * 
 * <p><strong>Error Handling:</strong></p>
 * <ul>
 *   <li>ValidationException: Thrown for missing card-account or account-customer relationships</li>
 *   <li>Skip Limit: Up to 100 validation exceptions can be skipped before job failure</li>
 *   <li>Retry: Not applicable - validation errors are not transient failures</li>
 *   <li>Logging: All validation failures logged with card number and failure reason</li>
 * </ul>
 * 
 * <p><strong>Integration with Spring Batch:</strong></p>
 * <ul>
 *   <li>Used by: AccountXrefBuildJob (transforms CBACT02C.jcl batch job)</li>
 *   <li>Input: Card entities from CardItemReader (sequential card file read)</li>
 *   <li>Output: XrefEntry wrapper objects for AccountXrefItemWriter persistence</li>
 *   <li>Chunk Size: Processes cards in configurable chunks (typical: 1000 records)</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li>Lazy-loaded relationships: Card.account and Account.customer must be fetched</li>
 *   <li>Reader configuration: CardItemReader should join-fetch account and customer</li>
 *   <li>Processing Time: Sub-millisecond per card (simple validation logic)</li>
 *   <li>Memory Usage: Minimal - processes one card at a time in chunk</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.batch.job.AccountXrefBuildJob
 * @see com.carddemo.batch.reader.CardItemReader
 * @see com.carddemo.batch.writer.AccountXrefItemWriter
 */
@Component
public class AccountXrefProcessor implements ItemProcessor<Card, AccountXrefProcessor.XrefEntry> {
    
    private static final Logger logger = LoggerFactory.getLogger(AccountXrefProcessor.class);
    
    /**
     * Process a Card entity to create cross-reference entries for account-customer relationships.
     * 
     * <p>This method implements the core business logic from COBOL CBACT02C.cbl for building
     * cross-reference entries. It validates the complete card-to-account-to-customer relationship
     * chain and creates both AccountXref and CardXref entries for maintaining referential integrity.</p>
     * 
     * <p><strong>Processing Steps:</strong></p>
     * <ol>
     *   <li>Validate card has associated account (card.getAccount() != null)</li>
     *   <li>Validate account has associated customer (account.getCustomer() != null)</li>
     *   <li>Extract relationship identifiers (customerId, accountId, cardNumber)</li>
     *   <li>Create AccountXref entry (customer-to-account mapping)</li>
     *   <li>Create CardXref entry (card-to-account-to-customer mapping)</li>
     *   <li>Return XrefEntry wrapper for atomic persistence</li>
     * </ol>
     * 
     * <p><strong>Validation Failures:</strong></p>
     * <p>When validation fails, this processor throws ValidationException with detailed context:</p>
     * <ul>
     *   <li>Missing Account: "Card has no account: [cardNumber]"</li>
     *   <li>Missing Customer: "Account has no customer: [accountId]"</li>
     * </ul>
     * 
     * <p><strong>COBOL Logic Preservation:</strong></p>
     * <p>In COBOL CBACT02C.cbl, the program sequentially reads CARDFILE and displays each record.
     * The cross-reference building was implicit through VSAM alternate indexes. This Java
     * implementation makes the cross-reference creation explicit with validation rules that
     * ensure referential integrity before persistence.</p>
     * 
     * @param card Card entity with lazy-loaded account and customer relationships
     * @return XrefEntry wrapper containing both AccountXref and CardXref entries
     * @throws ValidationException if card has no account or account has no customer
     * @throws Exception for unexpected processing errors (triggers batch job error handling)
     */
    @Override
    public XrefEntry process(Card card) throws Exception {
        // Validate card has account reference
        if (card.getAccount() == null) {
            String errorMessage = String.format("Card has no account: %s", 
                maskCardNumber(card.getCardNumber()));
            logger.error("Cross-reference validation failed: {}", errorMessage);
            throw new ValidationException(errorMessage);
        }
        
        Account account = card.getAccount();
        
        // Validate account has customer reference
        if (account.getCustomer() == null) {
            String errorMessage = String.format("Account has no customer: %d", account.getAccountId());
            logger.error("Cross-reference validation failed: {}", errorMessage);
            throw new ValidationException(errorMessage);
        }
        
        // Extract identifiers for cross-reference entries
        Long customerId = account.getCustomer().getCustomerId();
        Long accountId = account.getAccountId();
        String cardNumber = card.getCardNumber();
        
        // Create AccountXref entry (customer-to-account mapping) with composite key
        AccountXref.AccountXrefId accountXrefId = new AccountXref.AccountXrefId(customerId, accountId);
        AccountXref accountXref = new AccountXref();
        accountXref.setId(accountXrefId);
        accountXref.setCreatedDate(java.time.LocalDateTime.now());
        
        // Create CardXref entry (card-to-account-to-customer mapping) with composite key
        CardXref.CardXrefId cardXrefId = new CardXref.CardXrefId(cardNumber, customerId, accountId);
        CardXref cardXref = new CardXref();
        cardXref.setId(cardXrefId);
        
        logger.debug("Created cross-reference entries for card {} -> account {} -> customer {}",
            maskCardNumber(cardNumber), accountId, customerId);
        
        return new XrefEntry(accountXref, cardXref);
    }
    
    /**
     * Masks credit card number for secure logging per PCI-DSS requirements.
     * Shows only the last 4 digits, masking the first 12 digits with asterisks.
     * 
     * @param cardNumber 16-character card number
     * @return Masked card number in format "**** **** **** 1234"
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
    }
    
    /**
     * Wrapper class containing both AccountXref and CardXref entries for atomic persistence.
     * 
     * <p>This wrapper ensures that both cross-reference entries are created and persisted
     * together as a single atomic operation. If either persistence fails, both are rolled back
     * to maintain data consistency.</p>
     * 
     * <p><strong>Usage Pattern:</strong></p>
     * <pre>
     * XrefEntry entry = processor.process(card);
     * AccountXref accountXref = entry.getAccountXref();
     * CardXref cardXref = entry.getCardXref();
     * // Persist both entries atomically in ItemWriter
     * </pre>
     */
    public static class XrefEntry {
        private AccountXref accountXref;
        private CardXref cardXref;
        
        /**
         * Default constructor for Spring Batch framework.
         */
        public XrefEntry() {
        }
        
        /**
         * Constructor with both cross-reference entries.
         * 
         * @param accountXref Customer-to-account cross-reference entry
         * @param cardXref Card-to-account-to-customer cross-reference entry
         */
        public XrefEntry(AccountXref accountXref, CardXref cardXref) {
            this.accountXref = accountXref;
            this.cardXref = cardXref;
        }
        
        public AccountXref getAccountXref() {
            return accountXref;
        }
        
        public void setAccountXref(AccountXref accountXref) {
            this.accountXref = accountXref;
        }
        
        public CardXref getCardXref() {
            return cardXref;
        }
        
        public void setCardXref(CardXref cardXref) {
            this.cardXref = cardXref;
        }
        
        @Override
        public String toString() {
            return "XrefEntry{" +
                    "accountXref=" + accountXref +
                    ", cardXref=" + cardXref +
                    '}';
        }
    }
    

    /**
     * Custom validation exception for cross-reference relationship validation failures.
     * 
     * <p>Thrown when card-to-account or account-to-customer relationships are incomplete or invalid.
     * This exception triggers Spring Batch skip logic, allowing the batch job to continue processing
     * up to the configured skip limit (typically 100 errors) before failing the entire job.</p>
     * 
     * <p><strong>Skip Policy:</strong></p>
     * <ul>
     *   <li>Skippable: Yes (ValidationException is configured as skippable in batch job)</li>
     *   <li>Skip Limit: 100 exceptions before job failure</li>
     *   <li>Logged: Yes, all validation failures are logged with context</li>
     * </ul>
     */
    public static class ValidationException extends Exception {
        private static final long serialVersionUID = 1L;
        
        /**
         * Constructor with error message.
         * 
         * @param message Detailed validation failure message
         */
        public ValidationException(String message) {
            super(message);
        }
        
        /**
         * Constructor with error message and cause.
         * 
         * @param message Detailed validation failure message
         * @param cause Root cause of validation failure
         */
        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

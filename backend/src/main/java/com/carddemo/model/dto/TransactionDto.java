package com.carddemo.model.dto;

import com.carddemo.model.entity.Transaction;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for Transaction entity used in REST API responses.
 * 
 * Converted from COBOL copybook: CVTRA05Y.cpy (TRAN-RECORD)
 * Original COBOL structure: Transaction record layout with 350-byte fixed length
 * 
 * This DTO separates the external API representation from the internal JPA entity,
 * providing clean REST API contracts for transaction management endpoints while
 * excluding internal fields (version) and enabling field-level transformations
 * like card number masking for security.
 * 
 * Purpose:
 * - Provide JSON serialization-friendly representation of transaction data
 * - Enable card number masking for PCI-DSS compliance in API responses
 * - Convert Timestamp fields to modern LocalDateTime API for better client handling
 * - Exclude JPA-specific fields (version) from external API surface
 * - Preserve COBOL COMP-3 precision for financial amounts using BigDecimal
 * 
 * Used by:
 * - TransactionController.listTransactions() - GET /api/transactions (COTRN00C.cbl)
 * - TransactionController.getTransactionById() - GET /api/transactions/{id} (COTRN01C.cbl)
 * - TransactionController.postTransaction() - POST /api/transactions (COTRN02C.cbl)
 * - TransactionService for entity-to-DTO conversion
 * 
 * Conversion notes:
 * - Entity Timestamp fields converted to LocalDateTime for JSON serialization
 * - Card number optionally masked (show only last 4 digits) for security
 * - Version field excluded (internal JPA optimistic locking, not exposed to API)
 * - BigDecimal amount formatted with scale 2 in JSON output
 * - Timestamps formatted as ISO 8601 strings in JSON (yyyy-MM-dd'T'HH:mm:ss)
 * 
 * Field mapping from COBOL TRAN-RECORD (CVTRA05Y.cpy):
 * - TRAN-ID (PIC X(16)) → transId
 * - TRAN-CARD-NUM (PIC X(16)) → transCardNum (masked for security)
 * - TRAN-TYPE-CD (PIC X(02)) → transTypeCd
 * - TRAN-CAT-CD (PIC 9(04)) → transCatCd
 * - TRAN-SOURCE (PIC X(10)) → transSource
 * - TRAN-DESC (PIC X(100)) → transDesc
 * - TRAN-AMT (PIC S9(09)V99 COMP-3) → transAmt (BigDecimal, scale 2)
 * - TRAN-MERCHANT-ID (PIC 9(09)) → transMerchantId
 * - TRAN-MERCHANT-NAME (PIC X(50)) → transMerchantName
 * - TRAN-MERCHANT-CITY (PIC X(50)) → transMerchantCity
 * - TRAN-MERCHANT-ZIP (PIC X(10)) → transMerchantZip
 * - TRAN-ORIG-TS (PIC X(26)) → transOrigTs (LocalDateTime)
 * - TRAN-PROC-TS (PIC X(26)) → transProcTs (LocalDateTime)
 * - (Audit field) → createdAt (LocalDateTime)
 * 
 * @see Transaction
 * @see com.carddemo.controller.TransactionController
 * @see com.carddemo.service.TransactionService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDto {

    /**
     * Transaction identifier.
     * 
     * Source: Transaction.transId (COBOL TRAN-ID PIC X(16))
     * Maximum length: 16 characters
     * Format: Alphanumeric transaction reference number
     * 
     * Primary key for transaction lookup and reference in API responses.
     * Uniquely identifies each transaction across all cards and accounts.
     */
    private String transId;

    /**
     * Card number associated with transaction.
     * 
     * Source: Transaction.transCardNum (COBOL TRAN-CARD-NUM PIC X(16))
     * Maximum length: 16 characters
     * Format: 16-digit card number (may be masked for security)
     * 
     * SECURITY NOTE: This field contains sensitive PII (Payment Card Number).
     * The fromEntity() factory method automatically masks this field to show
     * only the last 4 digits (e.g., "************1234") per PCI-DSS requirements
     * for API responses.
     * 
     * Card number masking protects cardholder data in:
     * - API responses sent to frontend clients
     * - Application logs and debugging output
     * - Non-production environments
     * 
     * Unmasked values should only be used in:
     * - Secure backend processing
     * - Payment network communications
     * - Authorized administrative functions with proper access controls
     */
    private String transCardNum;

    /**
     * Transaction type code.
     * 
     * Source: Transaction.transTypeCd (COBOL TRAN-TYPE-CD PIC X(02))
     * Maximum length: 2 characters
     * 
     * Valid values from TRANTYPE reference table:
     * - '01' = Purchase
     * - '02' = Cash Advance
     * - '03' = Balance Transfer
     * - '04' = Payment
     * - '05' = Fee
     * - '06' = Interest Charge
     * - '07' = Credit Adjustment
     * - '08' = Debit Adjustment
     * 
     * Displayed in transaction lists and detail views to categorize transaction types.
     */
    private String transTypeCd;

    /**
     * Transaction category code.
     * 
     * Source: Transaction.transCatCd (COBOL TRAN-CAT-CD PIC 9(04))
     * Maximum value: 9999 (4 digits)
     * 
     * Merchant category code (MCC) from TRANCATG reference table.
     * Examples: 5411 (Grocery), 5812 (Restaurant), 5541 (Gas Station), 6011 (ATM).
     * 
     * Used for transaction categorization in spending analysis and reporting.
     */
    private Integer transCatCd;

    /**
     * Transaction source/channel indicator.
     * 
     * Source: Transaction.transSource (COBOL TRAN-SOURCE PIC X(10))
     * Maximum length: 10 characters
     * 
     * Values: 'POS', 'ATM', 'ONLINE', 'PHONE', 'MAIL', 'MOBILE'
     * 
     * Identifies the channel through which the transaction was initiated.
     */
    private String transSource;

    /**
     * Transaction description.
     * 
     * Source: Transaction.transDesc (COBOL TRAN-DESC PIC X(100))
     * Maximum length: 100 characters
     * 
     * Free-form merchant or system-generated description displayed on statements
     * and transaction history.
     * 
     * Examples: "WALMART SUPERCENTER #1234", "ATM WITHDRAWAL", "ONLINE PURCHASE"
     */
    private String transDesc;

    /**
     * Transaction amount.
     * 
     * Source: Transaction.transAmt (COBOL TRAN-AMT PIC S9(09)V99 COMP-3)
     * Precision: 11 total digits, 2 decimal places (BigDecimal scale 2)
     * Range: -999,999,999.99 to +999,999,999.99
     * 
     * Monetary amount in account currency (typically USD).
     * Positive = charges/debits (purchases, fees, interest)
     * Negative = credits (payments, refunds, adjustments)
     * 
     * CRITICAL: Uses BigDecimal with scale 2 to preserve exact COBOL COMP-3
     * packed decimal precision per Section 0.7.2 requirement for bit-identical
     * financial calculations. Any rounding or precision loss would violate
     * financial accuracy requirements.
     * 
     * Jackson @JsonFormat annotation ensures proper JSON serialization with
     * 2 decimal places (e.g., "123.45" not "123.4" or "123.450").
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal transAmt;

    /**
     * Merchant identifier.
     * 
     * Source: Transaction.transMerchantId (COBOL TRAN-MERCHANT-ID PIC 9(09))
     * Maximum length: 9 characters
     * Format: 9-digit numeric string (preserves leading zeros)
     * 
     * Payment network-assigned merchant identifier.
     * Example: "000123456" (leading zeros preserved)
     */
    private String transMerchantId;

    /**
     * Merchant name.
     * 
     * Source: Transaction.transMerchantName (COBOL TRAN-MERCHANT-NAME PIC X(50))
     * Maximum length: 50 characters
     * 
     * Name of merchant where transaction occurred.
     * Example: "WALMART SUPERCENTER", "STARBUCKS COFFEE"
     */
    private String transMerchantName;

    /**
     * Merchant city.
     * 
     * Source: Transaction.transMerchantCity (COBOL TRAN-MERCHANT-CITY PIC X(50))
     * Maximum length: 50 characters
     * 
     * City where merchant is located.
     * Example: "NEW YORK", "LOS ANGELES", "CHICAGO"
     */
    private String transMerchantCity;

    /**
     * Merchant ZIP code.
     * 
     * Source: Transaction.transMerchantZip (COBOL TRAN-MERCHANT-ZIP PIC X(10))
     * Maximum length: 10 characters
     * Format: 5-digit ZIP (e.g., "12345") or ZIP+4 (e.g., "12345-6789")
     * 
     * Postal code of merchant location for geographic analysis and fraud detection.
     */
    private String transMerchantZip;

    /**
     * Transaction origination timestamp.
     * 
     * Source: Transaction.transOrigTs (COBOL TRAN-ORIG-TS PIC X(26))
     * Format: ISO 8601 timestamp (yyyy-MM-dd'T'HH:mm:ss) in JSON
     * 
     * Date and time when transaction was initiated at merchant location.
     * This is the actual transaction date/time from the merchant's perspective.
     * 
     * Used for statement date filtering, grace period calculations, and
     * billing cycle cutoff determination.
     * 
     * Converted from entity's java.sql.Timestamp to java.time.LocalDateTime
     * for modern Java date/time API and better JSON serialization.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime transOrigTs;

    /**
     * Transaction processing timestamp.
     * 
     * Source: Transaction.transProcTs (COBOL TRAN-PROC-TS PIC X(26))
     * Format: ISO 8601 timestamp (yyyy-MM-dd'T'HH:mm:ss) in JSON
     * 
     * Date and time when transaction was processed and posted to account.
     * May differ from transOrigTs due to batch settlement delays.
     * 
     * Represents when transaction was received from payment network and
     * posted to account balances by batch processing.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime transProcTs;

    /**
     * Record creation timestamp.
     * 
     * Source: Transaction.createdAt (audit field, not in COBOL copybook)
     * Format: ISO 8601 timestamp (yyyy-MM-dd'T'HH:mm:ss) in JSON
     * 
     * Timestamp when transaction record was first created in database.
     * Used for audit tracking and transaction reporting.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * Static factory method to convert Transaction entity to TransactionDto.
     * 
     * This method creates a DTO from the JPA entity, performing the following transformations:
     * 
     * 1. Card Number Masking: Automatically masks card number to show only last 4 digits
     *    for PCI-DSS compliance (e.g., "4111111111111234" → "************1234")
     * 
     * 2. Timestamp Conversion: Converts java.sql.Timestamp to LocalDateTime using
     *    toLocalDateTime() method for modern Java date/time API
     * 
     * 3. Field Exclusion: Excludes JPA-specific fields like 'version' (optimistic locking)
     *    and 'card' (relationship navigation) that are not needed in API responses
     * 
     * 4. Precision Preservation: Copies BigDecimal amount with exact scale 2 precision
     *    to maintain COBOL COMP-3 financial calculation accuracy
     * 
     * Usage:
     * <pre>
     * Transaction entity = transactionRepository.findById(transId).orElseThrow();
     * TransactionDto dto = TransactionDto.fromEntity(entity);
     * return ResponseEntity.ok(dto);
     * </pre>
     * 
     * Card Number Masking Logic:
     * - If card number is null or empty: returns null
     * - If card number length <= 4: returns as-is (no masking for short values)
     * - Otherwise: replaces all but last 4 digits with asterisks (*)
     * 
     * Example:
     * - Input: "4111111111111234"
     * - Output: "************1234"
     * 
     * Note: Card masking is applied by default for security. If unmasked card numbers
     * are needed (e.g., for secure backend processing), use entity.getTransCardNum()
     * directly instead of the DTO field.
     * 
     * @param entity Transaction entity to convert (must not be null)
     * @return TransactionDto with masked card number and converted timestamps
     * @throws NullPointerException if entity is null
     */
    public static TransactionDto fromEntity(Transaction entity) {
        if (entity == null) {
            throw new NullPointerException("Transaction entity cannot be null for DTO conversion");
        }

        return TransactionDto.builder()
                .transId(entity.getTransId())
                .transCardNum(maskCardNumber(entity.getTransCardNum()))
                .transTypeCd(entity.getTransTypeCd())
                .transCatCd(entity.getTransCatCd())
                .transSource(entity.getTransSource())
                .transDesc(entity.getTransDesc())
                .transAmt(entity.getTransAmt())
                .transMerchantId(entity.getTransMerchantId())
                .transMerchantName(entity.getTransMerchantName())
                .transMerchantCity(entity.getTransMerchantCity())
                .transMerchantZip(entity.getTransMerchantZip())
                .transOrigTs(entity.getTransOrigTs() != null ? entity.getTransOrigTs().toLocalDateTime() : null)
                .transProcTs(entity.getTransProcTs() != null ? entity.getTransProcTs().toLocalDateTime() : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    /**
     * Masks card number for PCI-DSS compliance in API responses.
     * 
     * Replaces all digits except the last 4 with asterisks (*) to protect
     * sensitive cardholder data while maintaining transaction identification capability.
     * 
     * Masking rules:
     * - Null or empty input: returns null
     * - Length <= 4: returns unmasked (too short to mask securely)
     * - Length > 4: returns "*" repeated (length - 4) times + last 4 digits
     * 
     * Examples:
     * - "4111111111111234" → "************1234" (16-digit card)
     * - "378282246310005" → "***********0005" (15-digit Amex)
     * - "1234" → "1234" (too short, no masking)
     * - null → null (null-safe handling)
     * 
     * PCI-DSS Compliance Note:
     * Per PCI-DSS requirement 3.3, the Primary Account Number (PAN) must be
     * masked when displayed. This method implements the industry-standard
     * practice of showing only the last 4 digits for transaction identification
     * while protecting the full card number.
     * 
     * @param cardNumber Full 16-digit card number to mask
     * @return Masked card number with only last 4 digits visible, or null if input is null
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty()) {
            return null;
        }
        
        int length = cardNumber.length();
        if (length <= 4) {
            // Card number too short to mask securely, return as-is
            return cardNumber;
        }
        
        // Create masked string: asterisks for all but last 4 digits
        String lastFourDigits = cardNumber.substring(length - 4);
        String maskedPrefix = "*".repeat(length - 4);
        return maskedPrefix + lastFourDigits;
    }
}

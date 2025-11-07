package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Data Transfer Object for individual statement line items containing formatted 
 * transaction details for monthly statement generation.
 * 
 * <p>This immutable record represents a single transaction line on a customer 
 * statement with formatted amounts (2 decimal places), dates (yyyy-MM-dd), 
 * merchant information (name, city, zip concatenated), and running account 
 * balance after transaction posting.</p>
 * 
 * <h2>Source Data Mapping</h2>
 * <p>This DTO transforms data from the following COBOL copybook structures:</p>
 * <ul>
 *   <li><strong>CVTRA05Y.cpy (TRAN-RECORD)</strong> - Transaction master record (350 bytes)
 *     <ul>
 *       <li>TRAN-ID (PIC X(16)) → transactionId</li>
 *       <li>TRAN-ORIG-TS (PIC X(26)) → transactionDate</li>
 *       <li>TRAN-DESC (PIC X(100)) → description (part)</li>
 *       <li>TRAN-AMT (PIC S9(09)V99) → amount</li>
 *       <li>TRAN-MERCHANT-NAME (PIC X(50)) → merchantInfo (part)</li>
 *       <li>TRAN-MERCHANT-CITY (PIC X(50)) → merchantInfo (part)</li>
 *       <li>TRAN-MERCHANT-ZIP (PIC X(10)) → merchantInfo (part)</li>
 *       <li>TRAN-CARD-NUM (PIC X(16)) → cardNumber (masked)</li>
 *     </ul>
 *   </li>
 *   <li><strong>CVACT01Y.cpy (ACCOUNT-RECORD)</strong> - Account master record (300 bytes)
 *     <ul>
 *       <li>ACCT-CURR-BAL (PIC S9(10)V99) → accountBalance (running balance)</li>
 *     </ul>
 *   </li>
 *   <li><strong>CVCUS01Y.cpy (CUSTOMER-RECORD)</strong> - Customer context for statement</li>
 * </ul>
 * 
 * <h2>Mainframe Batch Program Source</h2>
 * <p>This DTO format matches the statement output produced by:</p>
 * <ul>
 *   <li><strong>CBSTM03A.cbl</strong> - Main statement generation batch program</li>
 *   <li><strong>CBSTM03B.cbl</strong> - File I/O helper subroutine</li>
 * </ul>
 * <p>Statement format preserves the COBOL layout from ST-LINE14 structure:</p>
 * <pre>
 * 05  ST-LINE14.
 *     10  ST-TRANID      PIC X(16).
 *     10  ST-TRANDT      PIC X(49).
 *     10  ST-TRANAMT     PIC Z(9).99-.
 * </pre>
 * 
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe for concurrent Spring Batch 
 * chunk-oriented processing. All fields are final and all accessor methods 
 * return immutable or primitive types.</p>
 * 
 * <h2>Usage in Spring Batch</h2>
 * <p>Used as output type for StatementDetailProcessor ItemProcessor that 
 * transforms Transaction entities into formatted statement records for 
 * PDF/HTML report generation using JasperReports or iText libraries.</p>
 * 
 * <h2>JSON Serialization</h2>
 * <p>BigDecimal amounts are serialized as strings to prevent JavaScript 
 * Number precision loss when consuming this DTO in frontend applications.</p>
 * 
 * @see <a href="https://docs.spring.io/spring-batch/docs/current/reference/html/index.html">Spring Batch Documentation</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatementDetail(
    
    /**
     * Transaction identifier (16 characters).
     * Maps from TRAN-ID field in CVTRA05Y.cpy.
     * Format: Alphanumeric, padded with spaces if needed.
     */
    String transactionId,
    
    /**
     * Transaction origination date.
     * Formatted from TRAN-ORIG-TS timestamp field.
     * Format: yyyy-MM-dd (ISO 8601 date format).
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate transactionDate,
    
    /**
     * Transaction description combining merchant name and transaction details.
     * Concatenated from TRAN-MERCHANT-NAME and TRAN-DESC fields.
     * Format: "Merchant Name - Description"
     */
    String description,
    
    /**
     * Transaction amount with 2 decimal precision.
     * Serialized as string to prevent JavaScript Number precision loss.
     * Maps from TRAN-AMT (PIC S9(09)V99 COMP-3) with exact decimal preservation.
     * Format: "-9999999999.99" (negative sign prefix for debits).
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    BigDecimal amount,
    
    /**
     * Formatted merchant information concatenating name, city, and zip.
     * Format: "Merchant Name, City, ZIP"
     * Example: "Amazon.com, Seattle, 98101"
     */
    String merchantInfo,
    
    /**
     * Masked card number showing only last 4 digits.
     * Maps from TRAN-CARD-NUM with masking applied.
     * Format: "************1234" (12 asterisks + last 4 digits)
     */
    String cardNumber,
    
    /**
     * Running account balance after this transaction posts.
     * Calculated by applying transaction amount to previous balance.
     * Serialized as string to prevent JavaScript Number precision loss.
     * Maps from ACCT-CURR-BAL with precision matching COBOL PIC S9(10)V99.
     * Format: "9999999999.99" (10 integer digits, 2 decimal places)
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    BigDecimal accountBalance
    
) {
    
    /**
     * Compact constructor for validation and normalization.
     * Ensures all BigDecimal values have consistent scale of 2 decimal places
     * matching COBOL COMP-3 precision from mainframe source.
     * 
     * @param transactionId Transaction identifier (required, non-null)
     * @param transactionDate Transaction date (required, non-null)
     * @param description Transaction description (required, non-null)
     * @param amount Transaction amount with 2 decimal scale (required, non-null)
     * @param merchantInfo Formatted merchant information (required, non-null)
     * @param cardNumber Masked card number (required, non-null)
     * @param accountBalance Running balance with 2 decimal scale (required, non-null)
     */
    public StatementDetail {
        // Ensure amounts have consistent 2 decimal place scale (COBOL COMP-3 precision)
        if (amount != null) {
            amount = amount.setScale(2, RoundingMode.HALF_UP);
        }
        if (accountBalance != null) {
            accountBalance = accountBalance.setScale(2, RoundingMode.HALF_UP);
        }
    }
    
    /**
     * Static factory method to build StatementDetail from enriched transaction data.
     * 
     * <p>This method accepts primitive types and performs all necessary formatting
     * transformations to create a properly formatted statement detail record.</p>
     * 
     * <h3>Data Transformation Steps:</h3>
     * <ol>
     *   <li>Extract transaction ID from source (16 chars)</li>
     *   <li>Parse transaction timestamp to LocalDate</li>
     *   <li>Concatenate merchant name with transaction description</li>
     *   <li>Convert COMP-3 amount to BigDecimal with scale=2</li>
     *   <li>Format merchant information (name, city, zip)</li>
     *   <li>Mask card number (show last 4 digits only)</li>
     *   <li>Calculate running balance by applying transaction to previous balance</li>
     * </ol>
     * 
     * @param transactionId Transaction identifier (16 characters)
     * @param transactionTimestamp Transaction origination timestamp (ISO 8601 format)
     * @param transactionDescription Transaction description text
     * @param transactionAmount Transaction amount (positive for credits, negative for debits)
     * @param merchantName Merchant name
     * @param merchantCity Merchant city
     * @param merchantZip Merchant ZIP code
     * @param cardNumber Full card number (16 digits)
     * @param previousBalance Account balance before this transaction
     * @return Fully formatted StatementDetail instance ready for report rendering
     */
    public static StatementDetail fromTransactionData(
            String transactionId,
            String transactionTimestamp,
            String transactionDescription,
            BigDecimal transactionAmount,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String cardNumber,
            BigDecimal previousBalance) {
        
        LocalDate txnDate = parseTransactionDate(transactionTimestamp);
        String description = formatDescription(merchantName, transactionDescription);
        BigDecimal amount = formatAmount(transactionAmount);
        String merchantInfo = formatMerchantInfo(merchantName, merchantCity, merchantZip);
        String maskedCard = maskCardNumber(cardNumber);
        BigDecimal balance = calculateRunningBalance(previousBalance, transactionAmount);
        
        return new StatementDetail(
            transactionId,
            txnDate,
            description,
            amount,
            merchantInfo,
            maskedCard,
            balance
        );
    }
    
    /**
     * Builder class for constructing StatementDetail instances with fluent API.
     * 
     * <p>Provides an alternative construction pattern for complex scenarios where
     * incremental field assignment is preferred.</p>
     * 
     * <h3>Usage Example:</h3>
     * <pre>{@code
     * StatementDetail detail = StatementDetail.builder()
     *     .transactionId("TXN1234567890123")
     *     .transactionDate(LocalDate.of(2023, 12, 15))
     *     .description("Amazon.com - Purchase")
     *     .amount(new BigDecimal("125.99"))
     *     .merchantInfo("Amazon.com, Seattle, 98101")
     *     .cardNumber("************4532")
     *     .accountBalance(new BigDecimal("5874.01"))
     *     .build();
     * }</pre>
     * 
     * @return New Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for StatementDetail with fluent API.
     * Thread-safe for use in concurrent batch processing.
     */
    public static class Builder {
        private String transactionId;
        private LocalDate transactionDate;
        private String description;
        private BigDecimal amount;
        private String merchantInfo;
        private String cardNumber;
        private BigDecimal accountBalance;
        
        private Builder() {}
        
        public Builder transactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }
        
        public Builder transactionDate(LocalDate transactionDate) {
            this.transactionDate = transactionDate;
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }
        
        public Builder merchantInfo(String merchantInfo) {
            this.merchantInfo = merchantInfo;
            return this;
        }
        
        public Builder cardNumber(String cardNumber) {
            this.cardNumber = cardNumber;
            return this;
        }
        
        public Builder accountBalance(BigDecimal accountBalance) {
            this.accountBalance = accountBalance;
            return this;
        }
        
        /**
         * Builds the immutable StatementDetail instance.
         * 
         * @return New StatementDetail with all fields set
         * @throws IllegalStateException if required fields are null
         */
        public StatementDetail build() {
            return new StatementDetail(
                transactionId,
                transactionDate,
                description,
                amount,
                merchantInfo,
                cardNumber,
                accountBalance
            );
        }
    }
    
    // ===================================================================
    // Private Helper Methods for Data Formatting
    // ===================================================================
    
    /**
     * Formats BigDecimal amount with exactly 2 decimal places.
     * Applies HALF_UP rounding mode to match COBOL COMP-3 rounding behavior.
     * 
     * @param amount Raw transaction amount
     * @return Amount with 2 decimal scale, null if input is null
     */
    private static BigDecimal formatAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * Parses transaction timestamp string to LocalDate.
     * Handles COBOL timestamp format from TRAN-ORIG-TS field (26 characters).
     * Format: "YYYY-MM-DDTHH:MM:SS.mmmmmm" (ISO 8601 with microseconds)
     * 
     * @param timestamp Transaction timestamp string
     * @return Parsed LocalDate (date portion only), or current date if parse fails
     */
    private static LocalDate parseTransactionDate(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return LocalDate.now();
        }
        
        try {
            // Handle ISO 8601 timestamp format (extract date portion)
            if (timestamp.length() >= 10) {
                return LocalDate.parse(timestamp.substring(0, 10), 
                    DateTimeFormatter.ISO_LOCAL_DATE);
            }
            return LocalDate.now();
        } catch (Exception e) {
            // Fallback to current date if parsing fails
            return LocalDate.now();
        }
    }
    
    /**
     * Masks card number showing only the last 4 digits.
     * Replaces first 12 digits with asterisks for security.
     * Format: "************1234"
     * 
     * @param cardNumber Full 16-digit card number
     * @return Masked card number, or empty string if input is null/invalid
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "************0000";
        }
        
        // Show only last 4 digits
        String lastFour = cardNumber.substring(Math.max(0, cardNumber.length() - 4));
        return "************" + lastFour;
    }
    
    /**
     * Formats merchant information by concatenating name, city, and ZIP code.
     * Handles null values gracefully with empty string substitution.
     * Format: "Merchant Name, City, ZIP"
     * 
     * @param name Merchant name (TRAN-MERCHANT-NAME, 50 chars)
     * @param city Merchant city (TRAN-MERCHANT-CITY, 50 chars)
     * @param zip Merchant ZIP code (TRAN-MERCHANT-ZIP, 10 chars)
     * @return Formatted merchant information string
     */
    private static String formatMerchantInfo(String name, String city, String zip) {
        StringBuilder sb = new StringBuilder();
        
        if (name != null && !name.trim().isEmpty()) {
            sb.append(name.trim());
        }
        
        if (city != null && !city.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(city.trim());
        }
        
        if (zip != null && !zip.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(zip.trim());
        }
        
        return sb.toString();
    }
    
    /**
     * Formats transaction description by combining merchant name and description.
     * Format: "Merchant Name - Description"
     * 
     * @param merchantName Merchant name
     * @param description Transaction description
     * @return Combined description string
     */
    private static String formatDescription(String merchantName, String description) {
        StringBuilder sb = new StringBuilder();
        
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            sb.append(merchantName.trim());
        }
        
        if (description != null && !description.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" - ");
            }
            sb.append(description.trim());
        }
        
        return sb.toString();
    }
    
    /**
     * Calculates running account balance after applying transaction amount.
     * Transaction amounts are signed (negative for debits, positive for credits).
     * Maintains 2 decimal place precision matching COBOL COMP-3 arithmetic.
     * 
     * @param previousBalance Account balance before transaction
     * @param transactionAmount Transaction amount (signed)
     * @return New account balance after transaction, with 2 decimal scale
     */
    private static BigDecimal calculateRunningBalance(
            BigDecimal previousBalance, 
            BigDecimal transactionAmount) {
        
        if (previousBalance == null) {
            previousBalance = BigDecimal.ZERO;
        }
        if (transactionAmount == null) {
            transactionAmount = BigDecimal.ZERO;
        }
        
        // Add transaction amount to previous balance (amount already signed correctly)
        BigDecimal newBalance = previousBalance.add(transactionAmount);
        return newBalance.setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * Formats date to yyyy-MM-dd string format for display.
     * Used internally for date formatting in non-JSON contexts.
     * 
     * @param date LocalDate to format
     * @return Formatted date string, or empty string if null
     */
    private static String formatDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
    
    /**
     * Formats date-time timestamp to yyyy-MM-dd string format.
     * Extracts date portion from timestamp for statement display.
     * 
     * @param timestamp LocalDateTime to format
     * @return Formatted date string, or empty string if null
     */
    private static String formatDate(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "";
        }
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}

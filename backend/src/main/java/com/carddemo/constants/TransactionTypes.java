package com.carddemo.constants;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Transaction Type Constants
 * 
 * Enumeration of all transaction type codes and descriptions transformed from
 * COBOL CVTRA03Y.cpy copybook and transaction type reference data.
 * 
 * This enum preserves the COBOL 88-level condition name pattern as specified
 * in Section 0.9 of the Agent Action Plan. Transaction type codes are maintained
 * exactly for database and external system compatibility per Section 0.2 requirements.
 * 
 * COBOL Source Structure (CVTRA03Y.cpy):
 * <pre>
 * 01  TRAN-TYPE-RECORD.
 *     05  TRAN-TYPE           PIC X(02).
 *     05  TRAN-TYPE-DESC      PIC X(50).
 * </pre>
 * 
 * Reference Data Source: app/data/ASCII/trantype.txt
 * 
 * @version 1.0
 * @since 2024
 */
public enum TransactionTypes {
    
    /**
     * Purchase Transaction - Code: 01
     * Represents a standard purchase transaction against a credit card account
     */
    PURCHASE("01", "Purchase"),
    
    /**
     * Payment Transaction - Code: 02
     * Represents a payment applied to reduce account balance
     */
    PAYMENT("02", "Payment"),
    
    /**
     * Credit Transaction - Code: 03
     * Represents a credit adjustment to the account (positive adjustment)
     */
    CREDIT("03", "Credit"),
    
    /**
     * Authorization Transaction - Code: 04
     * Represents an authorization hold on available credit
     */
    AUTHORIZATION("04", "Authorization"),
    
    /**
     * Refund Transaction - Code: 05
     * Represents a refund of a previous purchase
     */
    REFUND("05", "Refund"),
    
    /**
     * Reversal Transaction - Code: 06
     * Represents a reversal of a previous transaction
     */
    REVERSAL("06", "Reversal"),
    
    /**
     * Adjustment Transaction - Code: 07
     * Represents a manual adjustment to the account balance
     */
    ADJUSTMENT("07", "Adjustment");
    
    /**
     * The 2-character transaction type code from COBOL PIC X(02)
     */
    private final String code;
    
    /**
     * The transaction type description (max 50 characters from COBOL PIC X(50))
     */
    private final String description;
    
    /**
     * Static lookup map for efficient code-based lookups
     * Initialized once at class loading time for thread safety
     */
    private static final Map<String, TransactionTypes> CODE_MAP = 
        Arrays.stream(values())
              .collect(Collectors.toMap(TransactionTypes::getCode, Function.identity()));
    
    /**
     * Static lookup map for efficient description-based lookups
     * Initialized once at class loading time for thread safety
     */
    private static final Map<String, TransactionTypes> DESCRIPTION_MAP = 
        Arrays.stream(values())
              .collect(Collectors.toMap(
                  type -> type.getDescription().toUpperCase(), 
                  Function.identity()
              ));
    
    /**
     * Constructor for TransactionTypes enum
     * 
     * @param code The 2-character transaction type code (COBOL PIC X(02))
     * @param description The transaction type description (COBOL PIC X(50))
     */
    TransactionTypes(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    /**
     * Gets the 2-character transaction type code
     * 
     * Preserves COBOL TRAN-TYPE PIC X(02) field mapping for database
     * compatibility and external system interfaces.
     * 
     * @return The transaction type code (e.g., "01", "02", "03")
     */
    public String getCode() {
        return code;
    }
    
    /**
     * Gets the transaction type description
     * 
     * Preserves COBOL TRAN-TYPE-DESC PIC X(50) field mapping from
     * reference data source (trantype.txt).
     * 
     * @return The transaction type description (e.g., "Purchase", "Payment")
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Retrieves a TransactionTypes enum by its 2-character code
     * 
     * This method provides reverse lookup functionality from code to enum,
     * essential for database record mapping and external system integration.
     * Uses an internal static map for O(1) lookup performance.
     * 
     * Thread-safe: The lookup map is immutable after class initialization.
     * 
     * @param code The 2-character transaction type code to lookup (e.g., "01")
     * @return Optional containing the matching TransactionTypes, or empty if not found
     * @throws IllegalArgumentException if code is null
     */
    public static Optional<TransactionTypes> getByCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Transaction type code cannot be null");
        }
        return Optional.ofNullable(CODE_MAP.get(code));
    }
    
    /**
     * Retrieves a TransactionTypes enum by its description
     * 
     * This method provides reverse lookup functionality from description to enum.
     * The lookup is case-insensitive for user input flexibility.
     * Uses an internal static map for O(1) lookup performance.
     * 
     * Thread-safe: The lookup map is immutable after class initialization.
     * 
     * @param description The transaction type description to lookup (case-insensitive)
     * @return Optional containing the matching TransactionTypes, or empty if not found
     * @throws IllegalArgumentException if description is null
     */
    public static Optional<TransactionTypes> getByDescription(String description) {
        if (description == null) {
            throw new IllegalArgumentException("Transaction type description cannot be null");
        }
        return Optional.ofNullable(DESCRIPTION_MAP.get(description.toUpperCase()));
    }
    
    /**
     * Validates whether a given code represents a valid transaction type
     * 
     * This method is essential for input validation in REST API endpoints
     * and batch processing to ensure data integrity before database operations.
     * 
     * @param code The 2-character transaction type code to validate
     * @return true if the code is valid, false otherwise (including null input)
     */
    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        return CODE_MAP.containsKey(code);
    }
    
    /**
     * Validates and retrieves a TransactionTypes enum by code, throwing exception if invalid
     * 
     * This method is useful in scenarios where invalid codes should halt processing
     * (e.g., batch jobs, critical transaction processing).
     * 
     * @param code The 2-character transaction type code to validate and retrieve
     * @return The matching TransactionTypes enum
     * @throws IllegalArgumentException if code is null or invalid
     */
    public static TransactionTypes validateAndGet(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Transaction type code cannot be null");
        }
        return getByCode(code)
            .orElseThrow(() -> new IllegalArgumentException(
                String.format("Invalid transaction type code: '%s'. Valid codes are: %s", 
                    code, 
                    String.join(", ", CODE_MAP.keySet()))
            ));
    }
    
    /**
     * Checks if this transaction type represents a debit (negative impact on balance)
     * 
     * Business logic helper method to categorize transaction types by their
     * impact on account balance. Useful for balance calculation and reporting.
     * 
     * @return true if transaction decreases available balance, false otherwise
     */
    public boolean isDebit() {
        return this == PURCHASE || this == AUTHORIZATION;
    }
    
    /**
     * Checks if this transaction type represents a credit (positive impact on balance)
     * 
     * Business logic helper method to categorize transaction types by their
     * impact on account balance. Useful for balance calculation and reporting.
     * 
     * @return true if transaction increases available balance, false otherwise
     */
    public boolean isCredit() {
        return this == PAYMENT || this == CREDIT || this == REFUND;
    }
    
    /**
     * Checks if this transaction type is an adjustment or reversal
     * 
     * Business logic helper method to identify administrative transactions
     * that may require special processing or authorization.
     * 
     * @return true if transaction is an adjustment or reversal, false otherwise
     */
    public boolean isAdjustment() {
        return this == ADJUSTMENT || this == REVERSAL;
    }
    
    /**
     * Checks if this transaction type requires authorization
     * 
     * Business logic helper method to determine if transaction processing
     * requires real-time authorization from payment networks.
     * 
     * @return true if transaction requires authorization, false otherwise
     */
    public boolean requiresAuthorization() {
        return this == PURCHASE || this == AUTHORIZATION;
    }
    
    /**
     * Returns a formatted display string for UI presentation
     * 
     * Format: "Code - Description" (e.g., "01 - Purchase")
     * Useful for dropdown lists and transaction display in React components.
     * 
     * @return Formatted string combining code and description
     */
    public String toDisplayString() {
        return String.format("%s - %s", code, description);
    }
    
    /**
     * Returns all valid transaction type codes as an array
     * 
     * Useful for validation, dropdown population, and batch processing
     * where all valid codes need to be enumerated.
     * 
     * @return Array of all valid 2-character transaction type codes
     */
    public static String[] getAllCodes() {
        return Arrays.stream(values())
                     .map(TransactionTypes::getCode)
                     .toArray(String[]::new);
    }
    
    /**
     * Returns all transaction type descriptions as an array
     * 
     * Useful for UI dropdown population and reporting requirements.
     * 
     * @return Array of all transaction type descriptions
     */
    public static String[] getAllDescriptions() {
        return Arrays.stream(values())
                     .map(TransactionTypes::getDescription)
                     .toArray(String[]::new);
    }
    
    /**
     * Returns a string representation of this transaction type
     * 
     * Overridden to provide meaningful string representation for logging
     * and debugging purposes.
     * 
     * @return String in format "TransactionType{code='XX', description='YYYY'}"
     */
    @Override
    public String toString() {
        return String.format("TransactionType{code='%s', description='%s'}", code, description);
    }
}


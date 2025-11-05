package com.carddemo.constants;

import java.math.BigDecimal;

/**
 * Account Constraints Constants
 * 
 * Defines validation constraints and business rules for account management
 * derived from COBOL copybooks CVACT01Y.cpy and CVACT02Y.cpy.
 * 
 * This class preserves COBOL business rules for:
 * - Field length limits matching COBOL PIC clauses
 * - Numeric precision requirements for COMP-3 decimal equivalence
 * - Balance thresholds and credit limit constraints
 * - Account status validation rules
 * - Format validation patterns
 * 
 * All monetary values use BigDecimal with scale 2 and RoundingMode.HALF_UP
 * to maintain exact decimal precision matching COBOL PIC S9(10)V99 COMP-3
 * packed decimal semantics per Section 0.9 requirements.
 * 
 * Source: app/cpy/CVACT01Y.cpy (Account Record Structure)
 * Source: app/cpy/CVACT02Y.cpy (Card Record Structure)
 * 
 * @version 1.0
 * @since 2024-01-01
 */
public final class AccountConstraints {

    /**
     * Private constructor to prevent instantiation of constants class.
     * This class should only be used for accessing static constant values.
     */
    private AccountConstraints() {
        throw new UnsupportedOperationException("AccountConstraints is a utility class and cannot be instantiated");
    }

    // ========================================================================
    // ACCOUNT ID CONSTRAINTS
    // Derived from COBOL: ACCT-ID PIC 9(11)
    // ========================================================================

    /**
     * Account ID field length in digits.
     * Corresponds to COBOL PIC 9(11) - 11-digit numeric account identifier.
     */
    public static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Minimum valid account ID value (00000000001).
     * Ensures account IDs start from 1, not 0.
     */
    public static final long ACCOUNT_ID_MIN_VALUE = 1L;

    /**
     * Maximum valid account ID value (99999999999).
     * Represents the largest 11-digit numeric value.
     */
    public static final long ACCOUNT_ID_MAX_VALUE = 99999999999L;

    /**
     * Regular expression pattern for account ID validation.
     * Matches exactly 11 digits.
     */
    public static final String ACCOUNT_ID_PATTERN = "^\\d{11}$";

    // ========================================================================
    // ACCOUNT STATUS CONSTRAINTS
    // Derived from COBOL: ACCT-ACTIVE-STATUS PIC X(01)
    // ========================================================================

    /**
     * Active status field length in characters.
     * Corresponds to COBOL PIC X(01) - single character status code.
     */
    public static final int ACTIVE_STATUS_LENGTH = 1;

    /**
     * Active status code indicating account is active.
     * Corresponds to COBOL 88-level condition: ACCOUNT-ACTIVE VALUE 'Y'.
     */
    public static final String ACTIVE_STATUS_ACTIVE = "Y";

    /**
     * Inactive status code indicating account is inactive.
     * Corresponds to COBOL 88-level condition: ACCOUNT-INACTIVE VALUE 'N'.
     */
    public static final String ACTIVE_STATUS_INACTIVE = "N";

    /**
     * Regular expression pattern for active status validation.
     * Matches only 'Y' (active) or 'N' (inactive).
     */
    public static final String ACTIVE_STATUS_PATTERN = "^[YN]$";

    // ========================================================================
    // BALANCE CONSTRAINTS
    // Derived from COBOL: ACCT-CURR-BAL PIC S9(10)V99
    // ========================================================================

    /**
     * Balance field precision (total number of digits).
     * COBOL PIC S9(10)V99 = 10 integer digits + 2 decimal digits = 12 total.
     */
    public static final int BALANCE_PRECISION = 12;

    /**
     * Balance field scale (number of decimal places).
     * COBOL V99 indicates 2 decimal places for cents.
     */
    public static final int BALANCE_SCALE = 2;

    /**
     * Minimum allowed account balance.
     * Accounts can have negative balances (overdraft), but limited to -$99,999,999.99.
     * This represents the most negative value for COBOL PIC S9(10)V99.
     */
    public static final BigDecimal MIN_BALANCE = new BigDecimal("-9999999999.99");

    /**
     * Maximum allowed account balance.
     * Maximum positive value for COBOL PIC S9(10)V99: $9,999,999,999.99.
     */
    public static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999.99");

    // ========================================================================
    // CREDIT LIMIT CONSTRAINTS
    // Derived from COBOL: ACCT-CREDIT-LIMIT PIC S9(10)V99
    // ========================================================================

    /**
     * Credit limit field precision (total number of digits).
     * COBOL PIC S9(10)V99 = 10 integer digits + 2 decimal digits = 12 total.
     */
    public static final int CREDIT_LIMIT_PRECISION = 12;

    /**
     * Credit limit field scale (number of decimal places).
     * COBOL V99 indicates 2 decimal places for cents.
     */
    public static final int CREDIT_LIMIT_SCALE = 2;

    /**
     * Minimum allowed credit limit.
     * Credit limits must be non-negative (zero or positive).
     * Zero credit limit indicates no credit extension available.
     */
    public static final BigDecimal MIN_CREDIT_LIMIT = BigDecimal.ZERO;

    /**
     * Maximum allowed credit limit.
     * Maximum value for COBOL PIC S9(10)V99: $9,999,999,999.99.
     * Represents the highest credit extension possible.
     */
    public static final BigDecimal MAX_CREDIT_LIMIT = new BigDecimal("9999999999.99");

    // ========================================================================
    // CASH CREDIT LIMIT CONSTRAINTS
    // Derived from COBOL: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
    // ========================================================================

    /**
     * Minimum allowed cash credit limit.
     * Cash advances must be non-negative (zero or positive).
     * Zero indicates no cash advance capability.
     */
    public static final BigDecimal MIN_CASH_CREDIT_LIMIT = BigDecimal.ZERO;

    /**
     * Maximum allowed cash credit limit.
     * Maximum value for COBOL PIC S9(10)V99: $9,999,999,999.99.
     * Typically less than or equal to regular credit limit.
     */
    public static final BigDecimal MAX_CASH_CREDIT_LIMIT = new BigDecimal("9999999999.99");

    // ========================================================================
    // ADDRESS CONSTRAINTS
    // Derived from COBOL: ACCT-ADDR-ZIP PIC X(10)
    // ========================================================================

    /**
     * ZIP code field length in characters.
     * Corresponds to COBOL PIC X(10) - supports ZIP+4 format with hyphen.
     * Examples: "12345", "12345-6789"
     */
    public static final int ZIP_CODE_LENGTH = 10;

    /**
     * Regular expression pattern for ZIP code validation.
     * Matches 5-digit ZIP or 9-digit ZIP+4 with optional hyphen.
     * Valid formats: "12345" or "12345-6789"
     */
    public static final String ZIP_CODE_PATTERN = "^\\d{5}(-\\d{4})?$";

    // ========================================================================
    // GROUP ID CONSTRAINTS
    // Derived from COBOL: ACCT-GROUP-ID PIC X(10)
    // ========================================================================

    /**
     * Account group ID field length in characters.
     * Corresponds to COBOL PIC X(10) - alphanumeric group identifier.
     */
    public static final int GROUP_ID_LENGTH = 10;

    // ========================================================================
    // DATE FIELD CONSTRAINTS
    // Derived from COBOL: ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE PIC X(10)
    // ========================================================================

    /**
     * Date field length in characters.
     * Corresponds to COBOL PIC X(10) - supports ISO 8601 format YYYY-MM-DD.
     * All account date fields use this length: open date, expiration date, reissue date.
     */
    public static final int DATE_LENGTH = 10;

    // ========================================================================
    // BUSINESS RULE CONSTANTS
    // Additional constraints for account management operations
    // ========================================================================

    /**
     * Default credit limit for new accounts with no credit history.
     * Standard starting credit limit of $1,000.00.
     */
    public static final BigDecimal DEFAULT_CREDIT_LIMIT = BigDecimal.valueOf(1000.00);

    /**
     * Default cash credit limit for new accounts.
     * Typically 20% of regular credit limit, set to $200.00 for standard new accounts.
     */
    public static final BigDecimal DEFAULT_CASH_CREDIT_LIMIT = BigDecimal.valueOf(200.00);

    /**
     * Minimum credit limit increase amount.
     * Credit limit increases must be at least $100.00.
     */
    public static final BigDecimal MIN_CREDIT_LIMIT_INCREASE = BigDecimal.valueOf(100.00);

    /**
     * Maximum single transaction amount without additional authorization.
     * Transactions exceeding $5,000.00 require additional verification.
     */
    public static final BigDecimal MAX_SINGLE_TRANSACTION_AMOUNT = BigDecimal.valueOf(5000.00);

    /**
     * Threshold balance for account closure eligibility.
     * Accounts can only be closed if balance is within +/- $0.01 of zero.
     */
    public static final BigDecimal CLOSURE_BALANCE_THRESHOLD = BigDecimal.valueOf(0.01);

    /**
     * Overdraft protection limit.
     * Maximum negative balance allowed without account suspension: -$100.00.
     */
    public static final BigDecimal OVERDRAFT_LIMIT = BigDecimal.valueOf(-100.00);
}

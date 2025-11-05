package com.carddemo.constants;

/**
 * Enumeration defining all possible balance type values for AccountBalance entity tracking
 * different balance categories.
 * 
 * <p>This enum transforms COBOL 88-level condition name patterns to Java type-safe constants
 * per Section 0.9 COBOL-Specific Construct Preservation requirements.</p>
 * 
 * <p>Balance Types:</p>
 * <ul>
 *   <li><b>CURRENT ('C')</b>: Current actual balance from Account.currentBalance - the real-time
 *       balance reflecting all posted transactions</li>
 *   <li><b>AVAILABLE ('A')</b>: Available balance calculated as current balance minus pending
 *       charges - the amount available for new transactions</li>
 *   <li><b>PENDING ('P')</b>: Pending transactions not yet posted to the account - authorization
 *       holds and unposted debits/credits</li>
 *   <li><b>HISTORICAL ('H')</b>: End-of-day or end-of-period historical balance snapshot - used
 *       for audit trails, reconciliation, and statement generation</li>
 * </ul>
 * 
 * <p>Each enum value contains:</p>
 * <ul>
 *   <li>Single-character code matching PostgreSQL char(1) database column</li>
 *   <li>Display name for user interface presentation</li>
 *   <li>Detailed description explaining the balance category</li>
 * </ul>
 * 
 * <p><b>Database Mapping:</b></p>
 * <pre>
 * PostgreSQL Column: balance_type CHAR(1)
 * JPA Mapping: @Enumerated with custom AttributeConverter for char storage
 * </pre>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * // AccountBalance entity field
 * private BalanceType balanceType = BalanceType.CURRENT;
 * 
 * // Batch job creating historical snapshots (CBACT03C)
 * accountBalance.setBalanceType(BalanceType.HISTORICAL);
 * 
 * // Balance calculation service querying current balances
 * List&lt;AccountBalance&gt; balances = repository.findByBalanceType(BalanceType.CURRENT);
 * 
 * // Reconciliation query for end-of-day snapshots
 * List&lt;AccountBalance&gt; eodBalances = repository.findByBalanceTypeAndDate(
 *     BalanceType.HISTORICAL, 
 *     LocalDate.now().minusDays(1)
 * );
 * </pre>
 * 
 * <p><b>Batch Job Integration:</b></p>
 * <ul>
 *   <li>CBACT03C (Account Balance Job): Creates HISTORICAL balance snapshots at end-of-day</li>
 *   <li>CBSTM03A (Statement Generation): Queries HISTORICAL balances for statement periods</li>
 *   <li>CBTRN02C (Daily Transaction Processing): Updates CURRENT and calculates AVAILABLE</li>
 * </ul>
 * 
 * <p>This enum ensures type-safe balance categorization throughout the application, preventing
 * invalid balance type codes and providing clear semantics for balance tracking operations.</p>
 * 
 * @author CardDemo Development Team
 * @version 1.0
 * @since 1.0
 */
public enum BalanceType {
    
    /**
     * Current actual balance from Account.currentBalance.
     * 
     * <p>Represents the real-time balance of the account including all posted transactions.
     * This is the primary balance type used for most account operations and is updated
     * immediately when transactions are posted.</p>
     * 
     * <p>Updated by:</p>
     * <ul>
     *   <li>Transaction posting operations (COTRN02C → TransactionCreationService)</li>
     *   <li>Payment processing (COBIL00C → BillPaymentService)</li>
     *   <li>Daily transaction batch processing (CBTRN02C)</li>
     * </ul>
     */
    CURRENT('C', "Current", "Current actual balance from Account.currentBalance"),
    
    /**
     * Available balance (current minus pending charges).
     * 
     * <p>Represents the amount available for new transactions, calculated as the current
     * balance minus any pending authorization holds or unposted debits. This balance type
     * is used for transaction authorization decisions and overdraft prevention.</p>
     * 
     * <p>Calculation:</p>
     * <pre>
     * Available Balance = Current Balance - Sum(Pending Debits) + Sum(Pending Credits)
     * </pre>
     * 
     * <p>Used by:</p>
     * <ul>
     *   <li>Transaction authorization service to verify sufficient funds</li>
     *   <li>Card update service to display available credit</li>
     *   <li>Account view service to show spendable balance</li>
     * </ul>
     */
    AVAILABLE('A', "Available", "Available balance (current minus pending charges)"),
    
    /**
     * Pending transactions not yet posted.
     * 
     * <p>Represents the total amount of transactions that have been authorized but not yet
     * posted to the account. These are temporary holds that reduce the available balance
     * but do not affect the current balance until posting occurs.</p>
     * 
     * <p>Includes:</p>
     * <ul>
     *   <li>Authorization holds from point-of-sale transactions</li>
     *   <li>Pre-authorized debits awaiting settlement</li>
     *   <li>Pending credits from deposits or refunds</li>
     * </ul>
     * 
     * <p>Cleared by:</p>
     * <ul>
     *   <li>Daily transaction processing batch job (CBTRN02C) when posting transactions</li>
     *   <li>Transaction reversal operations</li>
     *   <li>Authorization timeout (automatic release after hold period)</li>
     * </ul>
     */
    PENDING('P', "Pending", "Pending transactions not yet posted"),
    
    /**
     * End-of-day or end-of-period historical snapshot.
     * 
     * <p>Represents a point-in-time balance snapshot taken at the end of a business day
     * or statement period. These snapshots are immutable and used for:</p>
     * <ul>
     *   <li>Regulatory compliance and audit trails</li>
     *   <li>Statement generation and customer reporting</li>
     *   <li>Balance reconciliation and discrepancy investigation</li>
     *   <li>Historical trend analysis and reporting</li>
     *   <li>Interest calculation based on daily balances</li>
     * </ul>
     * 
     * <p>Created by:</p>
     * <ul>
     *   <li>CBACT03C (Account Balance Calculation Job) - daily snapshots</li>
     *   <li>CBSTM03A (Statement Generation Job) - statement period snapshots</li>
     *   <li>CBACT04C (Interest Calculation Job) - balance history for interest computation</li>
     * </ul>
     * 
     * <p>Retention:</p>
     * <p>Historical balance records are retained according to regulatory requirements,
     * typically 7 years for financial audit purposes.</p>
     */
    HISTORICAL('H', "Historical", "End-of-day/period historical snapshot");
    
    /**
     * Single-character code used for database storage.
     * 
     * <p>This code is stored in the PostgreSQL database as CHAR(1) and must match
     * the codes used in the COBOL VSAM files for backward compatibility with
     * data migration and external system interfaces.</p>
     */
    private final char code;
    
    /**
     * Human-readable display name for user interface presentation.
     * 
     * <p>This name is used in UI components, reports, and API responses to provide
     * clear identification of the balance type to end users and system operators.</p>
     */
    private final String displayName;
    
    /**
     * Detailed description explaining the balance category and its usage.
     * 
     * <p>This description provides context for developers, business analysts, and
     * system administrators to understand the purpose and behavior of each balance type.</p>
     */
    private final String description;
    
    /**
     * Private constructor to initialize enum constants with their attributes.
     * 
     * @param code Single-character code for database storage ('C', 'A', 'P', 'H')
     * @param displayName Human-readable name for UI presentation
     * @param description Detailed explanation of the balance type
     */
    BalanceType(char code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }
    
    /**
     * Gets the single-character code used for database storage.
     * 
     * <p>This method returns the character code that is stored in the PostgreSQL
     * database balance_type column. The code is used for compact storage and
     * compatibility with legacy VSAM data formats.</p>
     * 
     * @return Character code ('C', 'A', 'P', or 'H')
     */
    public char getCode() {
        return code;
    }
    
    /**
     * Gets the human-readable display name for user interface presentation.
     * 
     * <p>This method returns the display name suitable for showing to end users
     * in UI components, dropdown lists, reports, and API responses.</p>
     * 
     * @return Display name (e.g., "Current", "Available", "Pending", "Historical")
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets the detailed description explaining the balance category.
     * 
     * <p>This method returns a comprehensive description of what the balance type
     * represents, how it is calculated, and when it is used. Useful for help text,
     * documentation, and system administration interfaces.</p>
     * 
     * @return Detailed description of the balance type
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Converts a database character code to the corresponding BalanceType enum value.
     * 
     * <p>This method performs reverse lookup from the single-character database code
     * to the enum constant. It is used when reading balance records from the database
     * and converting the char(1) column value to a type-safe enum.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * char dbCode = resultSet.getString("balance_type").charAt(0);
     * BalanceType type = BalanceType.fromCode(dbCode);
     * </pre>
     * 
     * @param code Single-character code from database ('C', 'A', 'P', or 'H')
     * @return Corresponding BalanceType enum constant
     * @throws IllegalArgumentException if the code does not match any defined balance type
     */
    public static BalanceType fromCode(char code) {
        for (BalanceType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid balance type code: " + code);
    }
    
    /**
     * Converts a database string code to the corresponding BalanceType enum value.
     * 
     * <p>This method provides a convenient overload for converting String values
     * (such as those read from VARCHAR columns or external interfaces) to the enum.
     * It validates that the string is exactly one character before delegating to
     * {@link #fromCode(char)}.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * String dbCode = resultSet.getString("balance_type");
     * BalanceType type = BalanceType.fromString(dbCode);
     * </pre>
     * 
     * @param code String containing a single character code ('C', 'A', 'P', or 'H')
     * @return Corresponding BalanceType enum constant
     * @throws IllegalArgumentException if the code is null, empty, not single-character,
     *         or does not match any defined balance type
     */
    public static BalanceType fromString(String code) {
        if (code == null || code.length() != 1) {
            throw new IllegalArgumentException("Invalid balance type code: " + code);
        }
        return fromCode(code.charAt(0));
    }
}

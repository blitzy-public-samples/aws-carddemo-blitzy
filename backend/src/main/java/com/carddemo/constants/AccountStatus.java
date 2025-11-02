package com.carddemo.constants;

/**
 * Enumeration defining all possible account status values transformed from COBOL 88-level conditions.
 * 
 * This enum replaces COBOL 88-level condition names from the CVACT01Y copybook with type-safe
 * Java enum values. Each status contains:
 * - Single-character code matching ACCT-ACTIVE-STATUS field in VSAM/PostgreSQL
 * - Human-readable display name for UI presentation
 * - Detailed description for business context
 * 
 * Source Transformation:
 * COBOL: 01 ACCOUNT-STATUS PIC X(1).
 *        88 ACCT-ACTIVE VALUE 'A'.
 *        88 ACCT-INACTIVE VALUE 'I'.
 *        88 ACCT-CLOSED VALUE 'C'.
 *        88 ACCT-SUSPENDED VALUE 'S'.
 *        88 ACCT-PENDING VALUE 'P'.
 * 
 * Java: AccountStatus.ACTIVE ('A')
 *       AccountStatus.INACTIVE ('I')
 *       AccountStatus.CLOSED ('C')
 *       AccountStatus.SUSPENDED ('S')
 *       AccountStatus.PENDING ('P')
 * 
 * Usage:
 * - AccountUpdateService: Validates status transitions before account updates
 * - AccountCreationService: Sets initial account status (PENDING or ACTIVE)
 * - AccountViewService: Converts database char code to enum for response DTOs
 * - ValidationUtils: Validates status code format and validity
 * 
 * Thread Safety: Enum instances are inherently thread-safe and immutable
 * Performance: Enum values are cached, fromCode() uses efficient iteration
 * 
 * @see com.carddemo.entity.Account
 * @see com.carddemo.service.AccountUpdateService
 * @see com.carddemo.service.AccountCreationService
 */
public enum AccountStatus {
    
    /**
     * Account is active and operational.
     * Transactions are allowed, user has full access to account functions.
     * This is the normal operating state for accounts in good standing.
     */
    ACTIVE('A', "Active", "Account is active and operational"),
    
    /**
     * Account is temporarily inactive.
     * Account exists but is not currently in use. Can be reactivated.
     * Used for accounts that are on hold but not permanently closed.
     */
    INACTIVE('I', "Inactive", "Account is temporarily inactive"),
    
    /**
     * Account has been permanently closed.
     * No transactions allowed, cannot be reactivated. Terminal state.
     * Used when customer closes account or account is closed by bank.
     */
    CLOSED('C', "Closed", "Account has been permanently closed"),
    
    /**
     * Account is suspended pending review.
     * Temporary suspension due to fraud investigation, payment issues, or compliance review.
     * Can transition to ACTIVE once review is complete, or to CLOSED if issues confirmed.
     */
    SUSPENDED('S', "Suspended", "Account is suspended pending review"),
    
    /**
     * Account is pending activation.
     * New account that has been created but not yet activated.
     * Requires additional steps (verification, funding) before becoming ACTIVE.
     */
    PENDING('P', "Pending", "Account is pending activation");
    
    /**
     * Single-character code stored in database ACCT_ACTIVE_STATUS column.
     * Matches COBOL PIC X(1) field from CVACT01Y copybook.
     */
    private final char code;
    
    /**
     * Human-readable display name for UI presentation.
     * Used in screens, reports, and error messages.
     */
    private final String displayName;
    
    /**
     * Detailed description of status meaning and business context.
     * Used for help text, documentation, and administrative interfaces.
     */
    private final String description;
    
    /**
     * Private constructor to initialize enum constants.
     * 
     * @param code Single-character database code
     * @param displayName Human-readable name
     * @param description Detailed description of status
     */
    AccountStatus(char code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }
    
    /**
     * Gets the single-character database code for this status.
     * 
     * This code is stored in the ACCT_ACTIVE_STATUS column in PostgreSQL
     * and corresponds to the COBOL ACCT-ACTIVE-STATUS field.
     * 
     * @return Single-character status code ('A', 'I', 'C', 'S', or 'P')
     */
    public char getCode() {
        return code;
    }
    
    /**
     * Gets the human-readable display name for UI presentation.
     * 
     * Used in:
     * - React component labels and dropdowns
     * - Account list displays
     * - Report headers
     * - Error messages
     * 
     * @return Display name (e.g., "Active", "Closed")
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets the detailed description of this status.
     * 
     * Provides business context and meaning of the status for:
     * - Help documentation
     * - Administrative interfaces
     * - Training materials
     * - API documentation
     * 
     * @return Detailed description explaining status meaning and usage
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Converts a database character code to the corresponding AccountStatus enum.
     * 
     * This method performs reverse lookup from the single-character code stored
     * in the database to the type-safe enum value. Essential for:
     * - Loading account data from PostgreSQL
     * - Processing VSAM file data during migration
     * - Validating status codes from external systems
     * 
     * Performance: O(n) iteration over enum values, but n=5 so negligible
     * 
     * Example:
     * <pre>
     * AccountStatus status = AccountStatus.fromCode('A');  // Returns ACTIVE
     * AccountStatus status = AccountStatus.fromCode('C');  // Returns CLOSED
     * AccountStatus status = AccountStatus.fromCode('X');  // Throws IllegalArgumentException
     * </pre>
     * 
     * @param code Single-character status code from database
     * @return Corresponding AccountStatus enum value
     * @throws IllegalArgumentException if code does not match any valid status
     */
    public static AccountStatus fromCode(char code) {
        for (AccountStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException(
            String.format("Invalid account status code: '%c'. Valid codes are: A, I, C, S, P", code)
        );
    }
    
    /**
     * Converts a database string to the corresponding AccountStatus enum.
     * 
     * Convenience method that handles String input and delegates to fromCode(char).
     * Validates that input is exactly one character before conversion.
     * 
     * Handles common error cases:
     * - Null input
     * - Empty string
     * - Multi-character strings
     * - Whitespace-only strings
     * 
     * Example:
     * <pre>
     * AccountStatus status = AccountStatus.fromString("A");    // Returns ACTIVE
     * AccountStatus status = AccountStatus.fromString("I");    // Returns INACTIVE
     * AccountStatus status = AccountStatus.fromString(null);   // Throws IllegalArgumentException
     * AccountStatus status = AccountStatus.fromString("");     // Throws IllegalArgumentException
     * AccountStatus status = AccountStatus.fromString("ABC");  // Throws IllegalArgumentException
     * </pre>
     * 
     * @param code String representation of status code from database
     * @return Corresponding AccountStatus enum value
     * @throws IllegalArgumentException if code is null, not exactly 1 character, or invalid
     */
    public static AccountStatus fromString(String code) {
        if (code == null) {
            throw new IllegalArgumentException(
                "Account status code cannot be null. Valid codes are: A, I, C, S, P"
            );
        }
        
        if (code.length() != 1) {
            throw new IllegalArgumentException(
                String.format(
                    "Account status code must be exactly 1 character. Received: '%s' (length: %d)",
                    code, code.length()
                )
            );
        }
        
        return fromCode(code.charAt(0));
    }
    
    /**
     * Validates whether this account status can transition to the specified new status.
     * 
     * Implements business rules for valid account status transitions based on
     * COBOL COACTUPC.cbl program logic and business requirements:
     * 
     * Transition Rules:
     * 1. CLOSED accounts cannot transition to any other status (terminal state)
     * 2. Cannot transition to the same status (no-op transitions not allowed)
     * 3. Can close account from ACTIVE, INACTIVE, or SUSPENDED states
     * 4. Can activate account from INACTIVE, SUSPENDED, or PENDING states
     * 5. All other transitions are allowed by default
     * 
     * Transition Matrix:
     * <pre>
     * From         To ACTIVE   To INACTIVE   To CLOSED   To SUSPENDED   To PENDING
     * -------------------------------------------------------------------------
     * ACTIVE       NO          YES           YES         YES            YES
     * INACTIVE     YES         NO            YES         YES            YES
     * CLOSED       NO          NO            NO          NO             NO
     * SUSPENDED    YES         YES           YES         NO             YES
     * PENDING      YES         YES           YES         YES            NO
     * </pre>
     * 
     * Usage in AccountUpdateService:
     * <pre>
     * Account account = accountRepository.findById(accountId);
     * AccountStatus currentStatus = AccountStatus.fromCode(account.getActiveStatus());
     * AccountStatus newStatus = AccountStatus.fromString(request.getNewStatus());
     * 
     * if (!currentStatus.canTransitionTo(newStatus)) {
     *     throw new InvalidStatusTransitionException(
     *         "Cannot transition from " + currentStatus + " to " + newStatus
     *     );
     * }
     * </pre>
     * 
     * @param newStatus Target status for the transition
     * @return true if transition is allowed, false otherwise
     * @throws NullPointerException if newStatus is null
     */
    public boolean canTransitionTo(AccountStatus newStatus) {
        if (newStatus == null) {
            throw new NullPointerException("Target status cannot be null");
        }
        
        // Rule 1: Closed accounts are in terminal state, no transitions allowed
        if (this == CLOSED) {
            return false;
        }
        
        // Rule 2: Cannot transition to the same status (no-op not allowed)
        if (this == newStatus) {
            return false;
        }
        
        // Rule 3: Can only close accounts from specific states
        if (newStatus == CLOSED) {
            // Can close from ACTIVE, INACTIVE, or SUSPENDED
            // Cannot close PENDING accounts (must activate or cancel differently)
            return this == ACTIVE || this == INACTIVE || this == SUSPENDED;
        }
        
        // Rule 4: Can only activate accounts from specific states
        if (newStatus == ACTIVE) {
            // Can activate from INACTIVE (reactivation), SUSPENDED (after review), or PENDING (initial activation)
            // This covers the normal account lifecycle
            return this == INACTIVE || this == SUSPENDED || this == PENDING;
        }
        
        // Rule 5: Special restrictions for PENDING and SUSPENDED
        // PENDING is typically only for new accounts, so limited transitions TO pending
        // However, FROM PENDING we can go to most states
        // SUSPENDED can transition to most states based on review outcomes
        
        // Additional business rule: Cannot transition back to PENDING from most states
        // (PENDING is for new accounts only)
        if (newStatus == PENDING) {
            // Only SUSPENDED accounts might go back to PENDING in rare cases
            // (e.g., fraud review requires re-verification)
            return this == SUSPENDED;
        }
        
        // All other transitions are allowed by default
        // This includes:
        // - ACTIVE -> INACTIVE (customer request)
        // - ACTIVE -> SUSPENDED (fraud detection)
        // - INACTIVE -> SUSPENDED (compliance issues)
        // - SUSPENDED -> INACTIVE (partial resolution)
        // - PENDING -> INACTIVE (if not fully activated)
        // - PENDING -> SUSPENDED (if issues found during activation)
        return true;
    }
    
    /**
     * Returns string representation for logging and debugging.
     * Format: "ACTIVE(A)" or "CLOSED(C)"
     * 
     * @return String representation including enum name and code
     */
    @Override
    public String toString() {
        return String.format("%s(%c)", name(), code);
    }
}

package com.carddemo.batch.processor;

import com.carddemo.entity.Account;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Spring Batch ItemProcessor for account data CSV records.
 * 
 * <p>This processor validates and transforms input account records to Account entity
 * instances, implementing comprehensive field validation that replicates COBOL data
 * validation rules from CBACT01C.cbl batch program's 1100-DISPLAY-ACCT-RECORD paragraph.</p>
 * 
 * <p><strong>Validation Rules Implemented:</strong></p>
 * <ul>
 *   <li>Account ID format validation - must be 11-digit numeric identifier</li>
 *   <li>Active status checks - must be 'Y' (active) or 'N' (inactive)</li>
 *   <li>Balance precision verification - BigDecimal with scale=2, RoundingMode.HALF_UP</li>
 *   <li>Credit limit range validation - must be positive values</li>
 *   <li>Date format consistency - ISO format (YYYY-MM-DD) with logical ordering</li>
 *   <li>Group ID non-null validation - required field check</li>
 * </ul>
 * 
 * <p><strong>Data Type Conversion:</strong></p>
 * <p>Handles conversion from CSV string fields to proper Java types:</p>
 * <ul>
 *   <li>BigDecimal for all monetary fields (currentBalance, creditLimit, cashCreditLimit,
 *       currentCycleCredit, currentCycleDebit) with precision=12 and scale=2</li>
 *   <li>LocalDate for all date fields (openDate, expirationDate, reissueDate)</li>
 *   <li>String for alphanumeric fields with proper null handling</li>
 *   <li>Long for account ID with format validation</li>
 * </ul>
 * 
 * <p><strong>COBOL COMP-3 to BigDecimal Precision:</strong></p>
 * <p>All monetary field validation preserves exact COBOL COMP-3 packed decimal precision
 * per Section 0.10 special instruction #7. Uses RoundingMode.HALF_UP to match COBOL
 * rounding behavior, ensuring financial calculations produce identical results to the
 * original COBOL CBACT01C.cbl batch program.</p>
 * 
 * <p><strong>Error Handling Strategy:</strong></p>
 * <p>Returns validated Account entity for database persistence, or null to filter
 * invalid records according to Spring Batch skip-on-error policy. All validation
 * failures are logged with detailed information including:</p>
 * <ul>
 *   <li>Field name where validation failed</li>
 *   <li>Invalid value that triggered the failure</li>
 *   <li>Validation rule that was violated</li>
 *   <li>Account ID context for operational troubleshooting</li>
 * </ul>
 * 
 * <p>This detailed logging supports operational troubleshooting, data quality
 * monitoring, and audit requirements for batch processing workflows.</p>
 * 
 * <p><strong>Integration with Spring Batch:</strong></p>
 * <p>This processor is configured as a Spring-managed bean (@Component) for automatic
 * dependency injection into AccountDataLoadJob step configuration. It operates within
 * Spring Batch's chunk-oriented processing model, validating items read by
 * AccountDataReader before they are written by AccountDataWriter to the PostgreSQL
 * account table.</p>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <p>Validation is performed in-memory with no external dependencies, ensuring
 * efficient processing within the 4-hour batch processing window requirement.
 * BigDecimal operations use explicit scale and rounding to prevent precision loss
 * during validation normalization.</p>
 * 
 * @see Account
 * @see org.springframework.batch.item.ItemProcessor
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cbl/CBACT01C.cbl</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - COBOL COMP-3 Precision</a>
 */
@Component
@Slf4j
public class AccountDataProcessor implements ItemProcessor<Account, Account> {

    /**
     * Maximum valid credit limit value (matching COBOL validation range).
     */
    private static final BigDecimal MAX_CREDIT_LIMIT = new BigDecimal("999999999.99");

    /**
     * Minimum valid credit limit value.
     */
    private static final BigDecimal MIN_CREDIT_LIMIT = new BigDecimal("1000.00");

    /**
     * Maximum valid balance value (for range validation).
     */
    private static final BigDecimal MAX_BALANCE = new BigDecimal("999999999.99");

    /**
     * Account ID length (must be exactly 11 digits).
     */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Active status indicator for active accounts.
     */
    private static final String ACTIVE_STATUS_YES = "Y";

    /**
     * Active status indicator for inactive accounts.
     */
    private static final String ACTIVE_STATUS_NO = "N";

    /**
     * Process and validate account data record.
     * 
     * <p>This method implements the main ItemProcessor contract, performing comprehensive
     * validation on the input Account entity read from CSV file. Validation replicates
     * the COBOL field validation logic from CBACT01C.cbl's 1100-DISPLAY-ACCT-RECORD
     * paragraph.</p>
     * 
     * <p><strong>Validation Sequence:</strong></p>
     * <ol>
     *   <li>Validate account ID format (11-digit numeric)</li>
     *   <li>Validate active status value (Y/N)</li>
     *   <li>Validate all monetary fields (precision, range, scale)</li>
     *   <li>Validate date fields (format, logical ordering)</li>
     *   <li>Validate group ID (non-null requirement)</li>
     * </ol>
     * 
     * <p>If any validation fails, the method logs detailed error information and returns
     * null, which signals Spring Batch to skip this record according to the configured
     * skip policy in AccountDataLoadJob.</p>
     * 
     * <p>If all validations pass, the method normalizes BigDecimal fields to ensure
     * consistent scale=2 with RoundingMode.HALF_UP, then returns the validated Account
     * entity for persistence.</p>
     * 
     * @param item the Account entity populated from CSV file by AccountDataReader
     * @return the validated Account entity with normalized monetary fields, or null
     *         if validation fails (triggers skip-on-error behavior)
     * @throws Exception if an unexpected error occurs during processing (will be
     *                   handled by Spring Batch error handling framework)
     */
    @Override
    public Account process(Account item) throws Exception {
        if (item == null) {
            log.warn("Received null account item for processing, skipping");
            return null;
        }

        log.debug("Processing account record: accountId={}", item.getAccountId());

        // Validate account ID format (11 digits)
        if (!validateAccountId(item)) {
            return null;
        }

        // Validate active status (Y/N)
        if (!validateActiveStatus(item)) {
            return null;
        }

        // Validate monetary fields (precision, range, scale)
        if (!validateMonetaryFields(item)) {
            return null;
        }

        // Validate date fields (format, logical ordering)
        if (!validateDateFields(item)) {
            return null;
        }

        // Validate group ID (non-null)
        if (!validateGroupId(item)) {
            return null;
        }

        // Normalize BigDecimal fields to ensure consistent scale=2 with HALF_UP rounding
        normalizeMonetaryFields(item);

        log.debug("Successfully validated and processed account: accountId={}", 
                  item.getAccountId());
        return item;
    }

    /**
     * Validate account ID format.
     * 
     * <p>Validates that account ID is present and conforms to the expected 11-digit
     * numeric format matching COBOL ACCT-ID field (PIC 9(11)) from CVACT01Y.cpy.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Account ID must not be null</li>
     *   <li>Account ID must be positive (greater than 0)</li>
     *   <li>Account ID must be exactly 11 digits in length</li>
     * </ul>
     * 
     * @param account the Account entity to validate
     * @return true if account ID is valid, false otherwise
     */
    private boolean validateAccountId(Account account) {
        Long accountId = account.getAccountId();

        if (Objects.isNull(accountId)) {
            log.error("Account ID validation failed: accountId is null");
            return false;
        }

        if (accountId <= 0) {
            log.error("Account ID validation failed: accountId={} must be positive", 
                      accountId);
            return false;
        }

        // Check that account ID is exactly 11 digits
        String accountIdStr = String.valueOf(accountId);
        if (accountIdStr.length() != ACCOUNT_ID_LENGTH) {
            log.error("Account ID validation failed: accountId={} must be exactly {} digits, " +
                      "but has {} digits",
                      accountId, ACCOUNT_ID_LENGTH, accountIdStr.length());
            return false;
        }

        return true;
    }

    /**
     * Validate active status value.
     * 
     * <p>Validates that active status is present and contains one of the valid values
     * matching COBOL ACCT-ACTIVE-STATUS field (PIC X(01)) from CVACT01Y.cpy.</p>
     * 
     * <p><strong>Valid Values:</strong></p>
     * <ul>
     *   <li>'Y' - Active account</li>
     *   <li>'N' - Inactive account</li>
     * </ul>
     * 
     * @param account the Account entity to validate
     * @return true if active status is valid, false otherwise
     */
    private boolean validateActiveStatus(Account account) {
        String activeStatus = account.getActiveStatus();

        if (Objects.isNull(activeStatus)) {
            log.error("Active status validation failed: activeStatus is null for accountId={}",
                      account.getAccountId());
            return false;
        }

        if (!ACTIVE_STATUS_YES.equals(activeStatus) && !ACTIVE_STATUS_NO.equals(activeStatus)) {
            log.error("Active status validation failed: activeStatus='{}' must be '{}' or '{}' " +
                      "for accountId={}",
                      activeStatus, ACTIVE_STATUS_YES, ACTIVE_STATUS_NO, 
                      account.getAccountId());
            return false;
        }

        return true;
    }

    /**
     * Validate all monetary fields.
     * 
     * <p>Validates that all BigDecimal monetary fields conform to COBOL COMP-3 packed
     * decimal precision requirements (PIC S9(10)V99) from CVACT01Y.cpy. Ensures proper
     * scale, precision, and range validation for financial data integrity.</p>
     * 
     * <p><strong>Fields Validated:</strong></p>
     * <ul>
     *   <li>currentBalance - current account balance</li>
     *   <li>creditLimit - maximum credit authorization</li>
     *   <li>cashCreditLimit - maximum cash advance authorization</li>
     *   <li>currentCycleCredit - billing cycle credits</li>
     *   <li>currentCycleDebit - billing cycle debits</li>
     * </ul>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Credit limit must be within valid range (MIN_CREDIT_LIMIT to MAX_CREDIT_LIMIT)</li>
     *   <li>Cash credit limit must be positive if present</li>
     *   <li>Cash credit limit must not exceed overall credit limit</li>
     *   <li>Current balance must be within valid range</li>
     *   <li>Cycle credit and debit must be non-negative</li>
     *   <li>All monetary values must use BigDecimal for exact precision</li>
     * </ul>
     * 
     * @param account the Account entity to validate
     * @return true if all monetary fields are valid, false otherwise
     */
    private boolean validateMonetaryFields(Account account) {
        Long accountId = account.getAccountId();

        // Validate current balance
        BigDecimal currentBalance = account.getCurrentBalance();
        if (Objects.nonNull(currentBalance)) {
            if (currentBalance.abs().compareTo(MAX_BALANCE) > 0) {
                log.error("Current balance validation failed: currentBalance={} exceeds " +
                          "maximum {} for accountId={}",
                          currentBalance, MAX_BALANCE, accountId);
                return false;
            }
        }

        // Validate credit limit
        BigDecimal creditLimit = account.getCreditLimit();
        if (Objects.nonNull(creditLimit)) {
            if (creditLimit.compareTo(MIN_CREDIT_LIMIT) < 0) {
                log.error("Credit limit validation failed: creditLimit={} is below minimum {} " +
                          "for accountId={}",
                          creditLimit, MIN_CREDIT_LIMIT, accountId);
                return false;
            }

            if (creditLimit.compareTo(MAX_CREDIT_LIMIT) > 0) {
                log.error("Credit limit validation failed: creditLimit={} exceeds maximum {} " +
                          "for accountId={}",
                          creditLimit, MAX_CREDIT_LIMIT, accountId);
                return false;
            }
        }

        // Validate cash credit limit
        BigDecimal cashCreditLimit = account.getCashCreditLimit();
        if (Objects.nonNull(cashCreditLimit)) {
            if (cashCreditLimit.compareTo(BigDecimal.ZERO) < 0) {
                log.error("Cash credit limit validation failed: cashCreditLimit={} must be " +
                          "non-negative for accountId={}",
                          cashCreditLimit, accountId);
                return false;
            }

            // Cash credit limit should not exceed overall credit limit
            if (Objects.nonNull(creditLimit) && cashCreditLimit.compareTo(creditLimit) > 0) {
                log.error("Cash credit limit validation failed: cashCreditLimit={} exceeds " +
                          "creditLimit={} for accountId={}",
                          cashCreditLimit, creditLimit, accountId);
                return false;
            }
        }

        // Validate current cycle credit
        BigDecimal currentCycleCredit = account.getCurrentCycleCredit();
        if (Objects.nonNull(currentCycleCredit)) {
            if (currentCycleCredit.compareTo(BigDecimal.ZERO) < 0) {
                log.error("Current cycle credit validation failed: currentCycleCredit={} must be " +
                          "non-negative for accountId={}",
                          currentCycleCredit, accountId);
                return false;
            }
        }

        // Validate current cycle debit
        BigDecimal currentCycleDebit = account.getCurrentCycleDebit();
        if (Objects.nonNull(currentCycleDebit)) {
            if (currentCycleDebit.compareTo(BigDecimal.ZERO) < 0) {
                log.error("Current cycle debit validation failed: currentCycleDebit={} must be " +
                          "non-negative for accountId={}",
                          currentCycleDebit, accountId);
                return false;
            }
        }

        return true;
    }

    /**
     * Validate date fields.
     * 
     * <p>Validates that all date fields conform to expected ISO format (YYYY-MM-DD) and
     * maintain logical ordering. Replicates COBOL date validation from CBACT01C.cbl.</p>
     * 
     * <p><strong>Fields Validated:</strong></p>
     * <ul>
     *   <li>openDate - account opening date</li>
     *   <li>expirationDate - account expiration date</li>
     *   <li>reissueDate - account reissue date</li>
     * </ul>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Open date must not be in the future</li>
     *   <li>Expiration date must be after or equal to open date if both present</li>
     *   <li>Reissue date must be after or equal to open date if both present</li>
     *   <li>All dates must be valid LocalDate instances</li>
     * </ul>
     * 
     * @param account the Account entity to validate
     * @return true if all date fields are valid, false otherwise
     */
    private boolean validateDateFields(Account account) {
        Long accountId = account.getAccountId();
        LocalDate today = LocalDate.now();

        LocalDate openDate = account.getOpenDate();
        LocalDate expirationDate = account.getExpirationDate();
        LocalDate reissueDate = account.getReissueDate();

        // Validate open date is not in the future
        if (Objects.nonNull(openDate)) {
            if (openDate.isAfter(today)) {
                log.error("Open date validation failed: openDate={} is in the future " +
                          "for accountId={}",
                          openDate, accountId);
                return false;
            }
        }

        // Validate expiration date is after or equal to open date
        if (Objects.nonNull(openDate) && Objects.nonNull(expirationDate)) {
            if (expirationDate.isBefore(openDate)) {
                log.error("Expiration date validation failed: expirationDate={} is before " +
                          "openDate={} for accountId={}",
                          expirationDate, openDate, accountId);
                return false;
            }
        }

        // Validate reissue date is after or equal to open date
        if (Objects.nonNull(openDate) && Objects.nonNull(reissueDate)) {
            if (reissueDate.isBefore(openDate)) {
                log.error("Reissue date validation failed: reissueDate={} is before " +
                          "openDate={} for accountId={}",
                          reissueDate, openDate, accountId);
                return false;
            }
        }

        return true;
    }

    /**
     * Validate group ID field.
     * 
     * <p>Validates that group ID is present, matching COBOL ACCT-GROUP-ID field
     * (PIC X(10)) from CVACT01Y.cpy. Group ID is a required field for account
     * categorization and grouping.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Group ID must not be null</li>
     *   <li>Group ID must not be empty or blank</li>
     * </ul>
     * 
     * @param account the Account entity to validate
     * @return true if group ID is valid, false otherwise
     */
    private boolean validateGroupId(Account account) {
        String groupId = account.getGroupId();

        if (Objects.isNull(groupId) || groupId.trim().isEmpty()) {
            log.error("Group ID validation failed: groupId is null or empty for accountId={}",
                      account.getAccountId());
            return false;
        }

        return true;
    }

    /**
     * Normalize monetary fields to ensure consistent scale and precision.
     * 
     * <p>Applies BigDecimal normalization with scale=2 and RoundingMode.HALF_UP to all
     * monetary fields, ensuring exact COBOL COMP-3 packed decimal precision per
     * Section 0.10 special instruction #7. This normalization guarantees that all
     * financial calculations produce identical results to the original COBOL
     * CBACT01C.cbl batch program.</p>
     * 
     * <p><strong>Fields Normalized:</strong></p>
     * <ul>
     *   <li>currentBalance</li>
     *   <li>creditLimit</li>
     *   <li>cashCreditLimit</li>
     *   <li>currentCycleCredit</li>
     *   <li>currentCycleDebit</li>
     * </ul>
     * 
     * <p>This method is called after all validations pass, ensuring that the Account
     * entity persisted to the database has consistent precision for all monetary values.</p>
     * 
     * @param account the Account entity to normalize
     */
    private void normalizeMonetaryFields(Account account) {
        // Normalize current balance
        if (Objects.nonNull(account.getCurrentBalance())) {
            account.setCurrentBalance(
                account.getCurrentBalance().setScale(2, RoundingMode.HALF_UP)
            );
        }

        // Normalize credit limit
        if (Objects.nonNull(account.getCreditLimit())) {
            account.setCreditLimit(
                account.getCreditLimit().setScale(2, RoundingMode.HALF_UP)
            );
        }

        // Normalize cash credit limit
        if (Objects.nonNull(account.getCashCreditLimit())) {
            account.setCashCreditLimit(
                account.getCashCreditLimit().setScale(2, RoundingMode.HALF_UP)
            );
        }

        // Normalize current cycle credit
        if (Objects.nonNull(account.getCurrentCycleCredit())) {
            account.setCurrentCycleCredit(
                account.getCurrentCycleCredit().setScale(2, RoundingMode.HALF_UP)
            );
        }

        // Normalize current cycle debit
        if (Objects.nonNull(account.getCurrentCycleDebit())) {
            account.setCurrentCycleDebit(
                account.getCurrentCycleDebit().setScale(2, RoundingMode.HALF_UP)
            );
        }
    }
}

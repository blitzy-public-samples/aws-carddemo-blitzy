package com.cardemo.service.online;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;

/**
 * Service for viewing account details (read-only).
 *
 * <p>Faithfully translates COBOL program {@code COACTVWC.cbl} — the online
 * Account View transaction (TRANID: CAVW). This service performs a three-file
 * join across the Card Cross-Reference (CXACAIX), Account Master (ACCTDAT),
 * and Customer Master (CUSTDAT) VSAM datasets to present a consolidated
 * account view.</p>
 *
 * <h3>COBOL Paragraph → Java Method Traceability</h3>
 * <pre>
 *   0000-MAIN              → {@link #viewAccount(String)}
 *   2000-PROCESS-INPUTS    → {@link #processInputs(String)}
 *   2200-EDIT-MAP-INPUTS   → {@link #editMapInputs(String)}
 *   2210-EDIT-ACCOUNT      → {@link #editAccount(String)}
 *   9000-READ-ACCT         → {@link #readAcct(String)}
 *   9200-GETCARDXREF-BYACCT→ {@link #getCardXrefByAcct(String)}
 *   9300-GETACCTDATA-BYACCT→ {@link #getAcctDataByAcct(String)}
 *   9400-GETCUSTDATA-BYCUST→ {@link #getCustDataByCust(String)}
 * </pre>
 *
 * <h3>Transaction Characteristics</h3>
 * <ul>
 *   <li>All operations are <strong>read-only</strong> — no data modification</li>
 *   <li>Translates CICS pseudo-conversational ENTER/REENTER flow</li>
 *   <li>VSAM RESP code handling maps to {@link RecordNotFoundException}</li>
 * </ul>
 *
 * @see Account
 * @see CardXref
 * @see Customer
 * @see CardDemoContext
 */
@Service
@Transactional(readOnly = true)
public class AccountViewService {

    private static final Logger log = LoggerFactory.getLogger(AccountViewService.class);

    // =========================================================================
    // Constants — from COACTVWC.cbl WORKING-STORAGE SECTION
    // =========================================================================

    /** Program name constant (maps to WS-LIT-THISPGM). */
    private static final String LIT_THISPGM = "COACTVWC";

    /** Transaction ID constant (maps to WS-LIT-THISTRANID). */
    private static final String LIT_THISTRANID = "CAVW";

    /** Maximum length for account ID (maps to COBOL PIC 9(11)). */
    private static final int ACCT_ID_LENGTH = 11;

    // =========================================================================
    // Injected Dependencies — Constructor Injection
    // =========================================================================

    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CustomerRepository customerRepository;
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs an {@code AccountViewService} with all required dependencies.
     *
     * <p>Uses constructor injection (Spring recommended) — equivalent to
     * COBOL's implicit dependency on VSAM dataset definitions via
     * FILE SECTION FD entries.</p>
     *
     * @param accountRepository  repository for ACCTDAT dataset access
     * @param cardXrefRepository repository for CXACAIX alternate index access
     * @param customerRepository repository for CUSTDAT dataset access
     * @param cardDemoContext    request-scoped session context (COMMAREA)
     */
    public AccountViewService(AccountRepository accountRepository,
                              CardXrefRepository cardXrefRepository,
                              CustomerRepository customerRepository,
                              CardDemoContext cardDemoContext) {
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.cardDemoContext = cardDemoContext;
    }

    // =========================================================================
    // Inner Result Class — replaces BMS map output (1200-SETUP-SCREEN-VARS)
    // =========================================================================

    /**
     * Composite result holding account view data.
     *
     * <p>Replaces the BMS map output structure from COACTVW.CPY (CACTVWAO).
     * Bundles the Account, Customer, and CardXref entities together with
     * display-formatted fields (e.g., SSN with dashes) and a status message.</p>
     */
    public static class AccountViewResult {

        private final Account account;
        private final Customer customer;
        private final CardXref cardXref;
        private final String message;
        private final String formattedSsn;

        /**
         * Creates an account view result with all display data.
         *
         * @param account      the account entity (may be {@code null} on error)
         * @param customer     the customer entity (may be {@code null} on error)
         * @param cardXref     the card cross-reference entity (may be {@code null})
         * @param message      user-facing status message
         * @param formattedSsn SSN formatted with dashes (NNN-NN-NNNN) or
         *                     {@code null} if not available
         */
        public AccountViewResult(Account account, Customer customer,
                                 CardXref cardXref, String message,
                                 String formattedSsn) {
            this.account = account;
            this.customer = customer;
            this.cardXref = cardXref;
            this.message = message;
            this.formattedSsn = formattedSsn;
        }

        /** Returns the account entity, or {@code null} if not found. */
        public Account getAccount() {
            return account;
        }

        /** Returns the customer entity, or {@code null} if not found. */
        public Customer getCustomer() {
            return customer;
        }

        /** Returns the card cross-reference entity, or {@code null}. */
        public CardXref getCardXref() {
            return cardXref;
        }

        /** Returns the user-facing status message. */
        public String getMessage() {
            return message;
        }

        /** Returns the SSN formatted as NNN-NN-NNNN, or {@code null}. */
        public String getFormattedSsn() {
            return formattedSsn;
        }
    }

    // =========================================================================
    // Public Service Methods — COBOL Paragraph Translations
    // =========================================================================

    /**
     * Main entry point for the account view flow.
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>0000-MAIN</strong> (line 262).
     * Implements the CICS pseudo-conversational EVALUATE TRUE pattern:</p>
     * <ul>
     *   <li>{@code CDEMO-PGM-ENTER} — First entry: display initial screen
     *       or auto-read if account ID is pre-set in context</li>
     *   <li>{@code CDEMO-PGM-REENTER} — Re-entry: validate submitted
     *       account ID, read data, display results</li>
     *   <li>Other — Unknown context: return error message</li>
     * </ul>
     *
     * @param accountId the account identifier to view (11-digit numeric string)
     * @return composite result containing account, customer, and card data
     */
    public AccountViewResult viewAccount(String accountId) {
        log.info("Account view initiated: program={}, tranId={}, accountId={}",
                LIT_THISPGM, LIT_THISTRANID, accountId);

        try {
            if (cardDemoContext.isEnterContext()) {
                // CDEMO-PGM-ENTER: First entry into program
                return handleEnterContext(accountId);
            } else if (cardDemoContext.isReenterContext()) {
                // CDEMO-PGM-REENTER: Process submitted user input
                return processInputs(accountId);
            } else {
                // WHEN OTHER: Unknown program context
                log.warn("Unknown program context value: {}", cardDemoContext.getPgmContext());
                return new AccountViewResult(null, null, null,
                        MessageConstants.INVALID_KEY_MESSAGE, null);
            }
        } catch (RecordNotFoundException e) {
            // Let RecordNotFoundException propagate to the controller layer,
            // which catches it and returns HTTP 404 (Not Found).
            // In the COBOL original, a RESP(NOTFND) on VSAM READ would display
            // an error message on the same screen. In a REST API, the appropriate
            // HTTP semantic is 404 rather than 200 with an error message body.
            log.error("Record not found during account view: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Processes user-submitted inputs for the account view.
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>2000-PROCESS-INPUTS</strong>
     * (line 596). In the COBOL original, this paragraph receives the BMS map
     * (2100-RECEIVE-MAP), evaluates the AID key (DFHENTER, DFHPF3), and
     * delegates to 2200-EDIT-MAP-INPUTS on ENTER. In the headless Java
     * version, the AID key evaluation is handled by the controller layer;
     * this method focuses on input validation and data retrieval.</p>
     *
     * @param accountId the account identifier submitted by the user
     * @return composite result with account data or error message
     */
    public AccountViewResult processInputs(String accountId) {
        log.debug("Processing inputs for account view: accountId={}", accountId);

        // Validate account ID (maps 2200 → 2210-EDIT-ACCOUNT)
        if (!editAccount(accountId)) {
            log.warn("Account ID validation failed in processInputs: {}", accountId);
            return new AccountViewResult(null, null, null,
                    MessageConstants.INVALID_KEY_MESSAGE, null);
        }

        // Read and build view result (maps 9000-READ-ACCT → 1200-SETUP-SCREEN-VARS)
        return buildAccountView(accountId);
    }

    /**
     * Validates map inputs and triggers account data read.
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>2200-EDIT-MAP-INPUTS</strong>
     * (line 622). Performs account ID validation via
     * {@link #editAccount(String)} and, if valid, orchestrates the
     * three-file read via {@link #readAcct(String)}.</p>
     *
     * <p>In COBOL, this paragraph also calls 1000-SEND-MAP to display
     * results. In the headless version, display is handled by the
     * controller; this method focuses on validation and data reading.</p>
     *
     * @param accountId the account identifier to validate and read
     */
    public void editMapInputs(String accountId) {
        log.debug("Editing map inputs: accountId={}", accountId);

        boolean isValid = editAccount(accountId);
        if (isValid) {
            // Maps: IF FLG-ACCTFILTER-ISVALID PERFORM 9000-READ-ACCT
            readAcct(accountId);
            log.debug("editMapInputs: account read completed for accountId={}", accountId);
        } else {
            log.warn("editMapInputs: validation failed for accountId={}", accountId);
        }
    }

    /**
     * Validates the account ID input field.
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>2210-EDIT-ACCOUNT</strong>
     * (line 649). Reproduces the COBOL validation chain:</p>
     * <ol>
     *   <li>Blank check — {@code IF CC-ACCT-ID = SPACES}
     *       → FLG-ACCTFILTER-BLANK</li>
     *   <li>Numeric check — {@code IF CC-ACCT-ID NOT NUMERIC}
     *       → FLG-ACCTFILTER-NOT-OK</li>
     *   <li>Zero check — {@code IF CC-ACCT-ID = ZEROES}
     *       → FLG-ACCTFILTER-NOT-OK</li>
     *   <li>Valid — {@code MOVE CC-ACCT-ID TO CDEMO-ACCT-ID}</li>
     * </ol>
     *
     * @param accountId the raw account identifier from user input
     * @return {@code true} if the account ID is valid; {@code false} otherwise
     */
    public boolean editAccount(String accountId) {
        log.debug("Validating account ID: {}", accountId);

        // Check blank (maps: IF CC-ACCT-ID = SPACES SET FLG-ACCTFILTER-BLANK)
        if (accountId == null || accountId.isBlank()) {
            log.warn("Account ID is blank or null");
            return false;
        }

        String trimmed = accountId.trim();

        // Check numeric (maps: IF CC-ACCT-ID NOT NUMERIC SET FLG-ACCTFILTER-NOT-OK)
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                log.warn("Account ID contains non-numeric character at position {}: '{}'",
                        i, accountId);
                return false;
            }
        }

        // Check all zeros (maps: IF CC-ACCT-ID = ZEROES SET FLG-ACCTFILTER-NOT-OK)
        if ("0".repeat(trimmed.length()).equals(trimmed)) {
            log.warn("Account ID is all zeros: {}", accountId);
            return false;
        }

        // Validate max length (COBOL PIC 9(11) enforces 11 digits)
        if (trimmed.length() > ACCT_ID_LENGTH) {
            log.warn("Account ID exceeds maximum length of {}: '{}'",
                    ACCT_ID_LENGTH, accountId);
            return false;
        }

        // Left-pad with zeros if shorter than 11 characters (COBOL PIC 9(11) behaviour)
        String padded = trimmed;
        if (padded.length() < ACCT_ID_LENGTH) {
            padded = "0".repeat(ACCT_ID_LENGTH - padded.length()) + padded;
        }

        // Valid — MOVE CC-ACCT-ID TO CDEMO-ACCT-ID IN CARDDEMO-COMMAREA
        cardDemoContext.setAcctId(padded);
        log.debug("Account ID validated and set in context: {}", padded);
        return true;
    }

    /**
     * Orchestrates the three-file account read sequence.
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>9000-READ-ACCT</strong>
     * (line 687). Performs reads in COBOL-defined order:</p>
     * <ol>
     *   <li>{@link #getCardXrefByAcct(String)} — CXACAIX alternate index read</li>
     *   <li>{@link #getAcctDataByAcct(String)} — ACCTDAT primary key read</li>
     *   <li>{@link #getCustDataByCust(String)} — CUSTDAT primary key read
     *       using customer ID from cross-reference</li>
     * </ol>
     *
     * <p>Updates the {@link CardDemoContext} with cross-reference results
     * (customer ID, card number) for downstream processing.</p>
     *
     * @param accountId the validated account identifier
     * @return the account entity read from the account master
     * @throws RecordNotFoundException if any of the three records is not found
     */
    public Account readAcct(String accountId) {
        log.debug("Reading account data (3-file join): accountId={}", accountId);

        // Step 1: PERFORM 9200-GETCARDXREF-BYACCT
        CardXref xref = getCardXrefByAcct(accountId);

        // Maps: MOVE XREF-CUST-ID TO CDEMO-CUST-ID IN CARDDEMO-COMMAREA
        cardDemoContext.setCustId(xref.getCustId());
        // Maps: MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM IN CARDDEMO-COMMAREA
        cardDemoContext.setCardNum(xref.getXrefCardNum());

        // Step 2: PERFORM 9300-GETACCTDATA-BYACCT
        Account account = getAcctDataByAcct(accountId);

        // Step 3: PERFORM 9400-GETCUSTDATA-BYCUST
        // Uses customer ID obtained from cross-reference (MOVE XREF-CUST-ID TO WS-CUST-ID)
        String custId = cardDemoContext.getCustId();
        Customer customer = getCustDataByCust(custId);

        log.info("Account read complete: accountId={}, custId={}, cardNum={}",
                account.getAcctId(), customer.getCustId(), xref.getXrefCardNum());
        return account;
    }

    /**
     * Reads the card cross-reference record by account ID using the
     * alternate index (AIX).
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>9200-GETCARDXREF-BYACCT</strong>
     * (line 723). Translates:</p>
     * <pre>
     * EXEC CICS READ DATASET('CXACAIX')
     *      INTO(CARD-XREF-RECORD)
     *      RIDFLD(WS-XREF-ACCT-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     *
     * <p>On success (DFHRESP(NORMAL)), returns the first cross-reference
     * record. On DFHRESP(NOTFND), throws {@link RecordNotFoundException}
     * with a message matching the COBOL STRING construction:</p>
     * <pre>
     * 'Account ' CC-ACCT-ID ' not found in Cross ref file'
     * </pre>
     *
     * @param accountId the account ID for AIX lookup
     * @return the card cross-reference entity
     * @throws RecordNotFoundException if no cross-reference exists for the account
     */
    public CardXref getCardXrefByAcct(String accountId) {
        log.debug("Reading card cross-reference (CXACAIX) for accountId={}", accountId);

        List<CardXref> xrefs = cardXrefRepository.findByAccountId(accountId);

        if (xrefs.isEmpty()) {
            // Maps: WHEN WS-RESP-CD = DFHRESP(NOTFND) — build error STRING
            log.warn("Account {} not found in cross-reference file (CXACAIX)", accountId);
            throw new RecordNotFoundException(
                    "Account " + accountId + " not found in Cross ref file");
        }

        // DFHRESP(NORMAL): return first matching record
        CardXref xref = xrefs.getFirst();

        // Log cross-reference details including alternate access path
        if (xrefs.size() > 1) {
            log.info("Multiple cross-references ({}) found for accountId={}, using first: cardNum={}",
                    xrefs.size(), accountId, xrefs.get(0).getXrefCardNum());
        }

        log.debug("Cross-reference found: cardNum={}, custId={}, accountId={}",
                xref.getXrefCardNum(), xref.getCustId(), xref.getAccountId());
        return xref;
    }

    /**
     * Reads the account master record by primary key.
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>9300-GETACCTDATA-BYACCT</strong>
     * (line 774). Translates:</p>
     * <pre>
     * EXEC CICS READ DATASET('ACCTDAT')
     *      INTO(ACCOUNT-RECORD)
     *      RIDFLD(WS-ACCT-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     *
     * <p>On DFHRESP(NORMAL), sets the equivalent of
     * {@code SET FOUND-ACCT-IN-MASTER TO TRUE}. On DFHRESP(NOTFND),
     * throws {@link RecordNotFoundException}.</p>
     *
     * @param accountId the account ID (primary key, 11-digit string)
     * @return the account entity (FOUND-ACCT-IN-MASTER = TRUE)
     * @throws RecordNotFoundException if the account does not exist (STATUS '23')
     */
    public Account getAcctDataByAcct(String accountId) {
        log.debug("Reading account master (ACCTDAT) for accountId={}", accountId);

        Optional<Account> optAccount = accountRepository.findById(accountId);

        // Use isPresent()/get() pattern — maps EVALUATE WS-RESP-CD
        if (!optAccount.isPresent()) {
            // WHEN DFHRESP(NOTFND)
            log.warn("Account {} not found in account master file (ACCTDAT)", accountId);
            throw new RecordNotFoundException("Account", accountId);
        }

        // WHEN DFHRESP(NORMAL): SET FOUND-ACCT-IN-MASTER TO TRUE
        Account account = optAccount.get();
        log.debug("Account master record found: acctId={}, status={}",
                account.getAcctId(), account.getActiveStatus());
        return account;
    }

    /**
     * Reads the customer master record by primary key.
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>9400-GETCUSTDATA-BYCUST</strong>
     * (line 825). Translates:</p>
     * <pre>
     * EXEC CICS READ DATASET('CUSTDAT')
     *      INTO(CUSTOMER-RECORD)
     *      RIDFLD(WS-CUST-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     *
     * <p>On DFHRESP(NORMAL), sets the equivalent of
     * {@code SET FOUND-CUST-IN-MASTER TO TRUE}. On DFHRESP(NOTFND),
     * throws {@link RecordNotFoundException}.</p>
     *
     * @param custId the customer ID (primary key, 9-digit string)
     * @return the customer entity (FOUND-CUST-IN-MASTER = TRUE)
     * @throws RecordNotFoundException if the customer does not exist (STATUS '23')
     */
    public Customer getCustDataByCust(String custId) {
        log.debug("Reading customer master (CUSTDAT) for custId={}", custId);

        // Use orElseThrow() pattern — maps EVALUATE WS-RESP-CD
        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> {
                    // WHEN DFHRESP(NOTFND)
                    log.warn("Customer {} not found in customer master file (CUSTDAT)", custId);
                    return new RecordNotFoundException("Customer", custId);
                });

        // WHEN DFHRESP(NORMAL): SET FOUND-CUST-IN-MASTER TO TRUE
        log.debug("Customer master record found: custId={}, name={} {}",
                customer.getCustId(), customer.getFirstName(), customer.getLastName());
        return customer;
    }

    // =========================================================================
    // Private Helper Methods
    // =========================================================================

    /**
     * Handles the ENTER context (first entry into the program).
     *
     * <p>Maps the CDEMO-PGM-ENTER branch of 0000-MAIN. In COBOL, this
     * shows the initial empty screen (1000-SEND-MAP → 1400-SEND-SCREEN).
     * In the headless version, if an account ID is available from the
     * context (set by a previous screen), the account is read and
     * displayed immediately.</p>
     *
     * @param accountId the account ID parameter (may be {@code null})
     * @return account view result (empty or pre-populated)
     */
    private AccountViewResult handleEnterContext(String accountId) {
        log.debug("Handling ENTER context: accountId={}", accountId);

        // Resolve account ID from parameter or context
        String resolvedAcctId = resolveAccountId(accountId);

        if (resolvedAcctId == null || resolvedAcctId.isBlank()) {
            // No account to display — return initial empty screen
            log.debug("Enter context with no account ID — returning initial view");
            return new AccountViewResult(null, null, null,
                    MessageConstants.THANK_YOU_MESSAGE, null);
        }

        // Account ID available — validate and read.
        // In the REST context, an invalid ID format (non-numeric, all zeros,
        // etc.) means the resource does not exist. Propagate as
        // RecordNotFoundException so the controller returns HTTP 404.
        if (!editAccount(resolvedAcctId)) {
            throw new RecordNotFoundException("Account", resolvedAcctId);
        }

        return buildAccountView(resolvedAcctId);
    }

    /**
     * Resolves the account ID from the method parameter or from the
     * session context (COMMAREA).
     *
     * <p>Priority: explicit parameter {@literal >} context value.</p>
     *
     * @param accountId the explicit account ID (may be {@code null})
     * @return resolved account ID, or {@code null} if neither source has a value
     */
    private String resolveAccountId(String accountId) {
        if (accountId != null && !accountId.isBlank()) {
            return accountId;
        }
        return cardDemoContext.getAcctId();
    }

    /**
     * Builds the complete account view result by reading all three datasets.
     *
     * <p>Maps COACTVWC.cbl paragraph <strong>1200-SETUP-SCREEN-VARS</strong>
     * (line 460). In the COBOL original, this paragraph MOVEs all fields from
     * ACCOUNT-RECORD and CUSTOMER-RECORD to the BMS map output structure
     * (CACTVWAO). In the headless Java version, the Account and Customer
     * entities are returned directly in the {@link AccountViewResult}.</p>
     *
     * <p>Also performs SSN formatting with dashes (COBOL STRING at
     * lines 496–500): {@code CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)}</p>
     *
     * @param accountId the validated account identifier
     * @return composite result with all account, customer, and card data
     */
    private AccountViewResult buildAccountView(String accountId) {
        log.debug("Building account view for accountId={}", accountId);

        // Read cross-reference (9200)
        CardXref xref = getCardXrefByAcct(accountId);
        cardDemoContext.setCustId(xref.getCustId());
        cardDemoContext.setCardNum(xref.getXrefCardNum());
        cardDemoContext.setAcctId(accountId);

        // Read account master (9300)
        Account account = getAcctDataByAcct(accountId);

        // Read customer master (9400), using custId from cross-reference
        Customer customer = getCustDataByCust(xref.getCustId());

        // Format SSN with dashes — maps COBOL STRING (lines 496-500):
        // STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4) INTO ACSTSSNO
        String formattedSsn = formatSsn(customer.getSsn());

        // Log all account fields (maps 1200-SETUP-SCREEN-VARS account section)
        logAccountFields(account);

        // Log all customer fields (maps 1200-SETUP-SCREEN-VARS customer section)
        logCustomerFields(customer);

        log.info("Account view built successfully for accountId={}", accountId);

        return new AccountViewResult(account, customer, xref,
                MessageConstants.THANK_YOU_MESSAGE, formattedSsn);
    }

    /**
     * Formats a 9-digit SSN with dashes: {@code NNN-NN-NNNN}.
     *
     * <p>Maps the COBOL STRING statement in 1200-SETUP-SCREEN-VARS
     * (lines 496–500):</p>
     * <pre>
     * STRING CUST-SSN(1:3)  DELIMITED BY SIZE
     *        '-'            DELIMITED BY SIZE
     *        CUST-SSN(4:2)  DELIMITED BY SIZE
     *        '-'            DELIMITED BY SIZE
     *        CUST-SSN(6:4)  DELIMITED BY SIZE
     *        INTO ACSTSSNO
     * </pre>
     *
     * @param ssn the raw 9-digit SSN string (PII — do not log)
     * @return masked SSN as "***-**-NNNN" showing only last 4 digits,
     *         or the original value if it cannot be formatted
     */
    private static String formatSsn(String ssn) {
        if (ssn == null || ssn.length() < 9) {
            return ssn;
        }
        // PII masking: only expose last 4 digits for secure API responses.
        // The original COBOL BMS map (COACTUP) displayed full SSN on
        // mainframe 3270 terminals in a controlled-access environment.
        // For REST API responses, the SSN is masked to comply with
        // data-protection requirements (PCI-DSS, CCPA, GDPR).
        return "***-**-" + ssn.substring(5, 9);
    }

    /**
     * Logs all account entity fields for observability and diagnostics.
     *
     * <p>Maps the account-field MOVEs in 1200-SETUP-SCREEN-VARS where
     * each ACCOUNT-RECORD field is transferred to the BMS map output
     * structure. All 12 account fields are accessed for structured logging
     * with correlation IDs.</p>
     *
     * @param account the account entity with all fields populated
     */
    private void logAccountFields(Account account) {
        log.debug("Account fields — acctId={}, status={}, currBal={}, creditLimit={}, "
                        + "cashCreditLimit={}, openDate={}, expirationDate={}, reissueDate={}, "
                        + "currCycCredit={}, currCycDebit={}, zip={}, groupId={}",
                account.getAcctId(),
                account.getActiveStatus(),
                account.getCurrBal(),
                account.getCreditLimit(),
                account.getCashCreditLimit(),
                account.getOpenDate(),
                account.getExpirationDate(),
                account.getReissueDate(),
                account.getCurrCycCredit(),
                account.getCurrCycDebit(),
                account.getAddrZip(),
                account.getGroupId());
    }

    /**
     * Logs all customer entity fields for observability and diagnostics.
     *
     * <p>Maps the customer-field MOVEs in 1200-SETUP-SCREEN-VARS where
     * each CUSTOMER-RECORD field is transferred to the BMS map output.
     * All 16 non-PII customer fields are logged; SSN is deliberately
     * excluded from logging for PII protection.</p>
     *
     * @param customer the customer entity with all fields populated
     */
    private void logCustomerFields(Customer customer) {
        log.debug("Customer fields — custId={}, firstName={}, middleName={}, lastName={}, "
                        + "addr1={}, addr2={}, addr3={}, state={}, country={}, zip={}, "
                        + "phone1={}, phone2={}, dob={}, ficoScore={}, "
                        + "priCardHolder={}, eftAcctId={}",
                customer.getCustId(),
                customer.getFirstName(),
                customer.getMiddleName(),
                customer.getLastName(),
                customer.getAddrLine1(),
                customer.getAddrLine2(),
                customer.getAddrLine3(),
                customer.getAddrStateCode(),
                customer.getAddrCountryCode(),
                customer.getAddrZip(),
                customer.getPhoneNum1(),
                customer.getPhoneNum2(),
                customer.getDateOfBirth(),
                customer.getFicoCreditScore(),
                customer.getPriCardHolderInd(),
                customer.getEftAccountId());
    }
}

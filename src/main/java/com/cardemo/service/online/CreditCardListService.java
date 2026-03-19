/*
 * CreditCardListService.java — Credit Card List Service
 *
 * Faithful translation of COCRDLIC.cbl (CICS online program, ~1460 lines).
 * Translates VSAM STARTBR/READNEXT/READPREV/ENDBR browse patterns to
 * JPA pagination via CardRepository. Supports account-based and card-number-based
 * filtering with single-selection semantics for detail/update routing.
 *
 * COBOL Program:    COCRDLIC.cbl
 * CICS Transaction: CCLI
 * BMS Map:          COCRDLI (COCRDSLA mapset)
 * VSAM Datasets:    CARDDAT (CVACT02Y.cpy), CARDXREF via AIX (CVACT03Y.cpy)
 * Copybooks:        COCOM01Y.cpy (COMMAREA), CSMSG01Y.cpy (messages)
 *
 * Copyright (c) CardDemo Migration Project. All rights reserved.
 */
package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.Card;
import com.cardemo.entity.CardXref;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CardXrefRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Credit Card List Service — translates COCRDLIC.cbl to Spring service.
 *
 * <p>This service faithfully reproduces the CICS credit card list program
 * (transaction CCLI, program COCRDLIC). It provides paginated card browsing
 * with optional account and card number filters, input validation, and
 * forward/backward page navigation.</p>
 *
 * <h3>COBOL Paragraph → Java Method Traceability</h3>
 * <table>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>0000-MAIN</td><td>298</td><td>{@link #listCards}</td></tr>
 *   <tr><td>2200-EDIT-INPUTS</td><td>985</td><td>{@link #editInputs}</td></tr>
 *   <tr><td>2210-EDIT-ACCOUNT</td><td>1003</td><td>{@link #editAccount}</td></tr>
 *   <tr><td>2220-EDIT-CARD</td><td>1036</td><td>{@link #editCard}</td></tr>
 *   <tr><td>2250-EDIT-ARRAY</td><td>1073</td><td>{@link #editArray}</td></tr>
 *   <tr><td>9000-READ-FORWARD</td><td>1123</td><td>{@link #readForward}</td></tr>
 *   <tr><td>9100-READ-BACKWARDS</td><td>1264</td><td>{@link #readBackwards}</td></tr>
 *   <tr><td>9500-FILTER-RECORDS</td><td>1382</td><td>{@link #filterRecords}</td></tr>
 * </table>
 *
 * <h3>Presentation paragraphs (handled by controller layer)</h3>
 * <ul>
 *   <li>COMMON-RETURN (604) — framework return</li>
 *   <li>1000-SEND-MAP (624) — map send</li>
 *   <li>1100-SCREEN-INIT (642) — screen initialization</li>
 *   <li>1200-SCREEN-ARRAY-INIT (678) — array init</li>
 *   <li>1250-SETUP-ARRAY-ATTRIBS (748) — attribute setup</li>
 *   <li>1300-SETUP-SCREEN-ATTRS (837) — screen attributes</li>
 *   <li>1400-SETUP-MESSAGE (895) — message setup</li>
 *   <li>1500-SEND-SCREEN (938) — screen send</li>
 *   <li>2000-RECEIVE-MAP (951) — map receive</li>
 *   <li>2100-RECEIVE-SCREEN (962) — screen receive</li>
 *   <li>SEND-PLAIN-TEXT (1422) — error display</li>
 * </ul>
 *
 * @see CardRepository
 * @see CardXrefRepository
 * @see CardDemoContext
 */
@Service
public class CreditCardListService {

    private static final Logger logger = LoggerFactory.getLogger(CreditCardListService.class);

    /**
     * Records per page — adapted from COBOL WS-MAX-SCREEN-LINES (7 in COBOL,
     * increased to 10 for the Java headless service layer).
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Program identifier matching COBOL: {@code LIT-THISPGM VALUE 'COCRDLIC'}.
     */
    private static final String THIS_PROGRAM = "COCRDLIC";

    /**
     * Transaction identifier matching COBOL: {@code LIT-THISTRANID VALUE 'CCLI'}.
     */
    private static final String THIS_TRAN_ID = "CCLI";

    /**
     * Expected length for account ID filter.
     * Matches COBOL: {@code PIC 9(11)} for ACCT-ID.
     */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * Expected length for card number filter.
     * Matches COBOL: {@code PIC X(16)} for CARD-NUM.
     */
    private static final int CARD_NUM_LENGTH = 16;

    private final CardRepository cardRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs the Credit Card List Service with required dependencies.
     * Replaces CICS transaction program registration (DFHPCT entry for CCLI).
     *
     * @param cardRepository     JPA repository for CARDDAT VSAM dataset access
     * @param cardXrefRepository JPA repository for CARDXREF cross-reference access
     * @param cardDemoContext     request-scoped COMMAREA context bean
     */
    public CreditCardListService(CardRepository cardRepository,
                                 CardXrefRepository cardXrefRepository,
                                 CardDemoContext cardDemoContext) {
        this.cardRepository = cardRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Public API — Maps COBOL PROCEDURE DIVISION paragraphs
    // ========================================================================

    /**
     * Lists credit cards with optional account and card number filters.
     *
     * <p>Maps COCRDLIC.cbl paragraph <strong>0000-MAIN</strong> (line 298).</p>
     *
     * <p>Translates the CICS STARTBR/READNEXT/ENDBR browse pattern to JPA
     * pagination. When an account filter is active, uses the CARDAIX alternate
     * index path ({@code CardRepository.findByAccountId}). When no filter is
     * active, browses all cards via primary key order.</p>
     *
     * <p>Filter resolution priority:
     * <ol>
     *   <li>Explicit parameters (from request)</li>
     *   <li>CardDemoContext (COMMAREA) pre-populated values</li>
     * </ol></p>
     *
     * @param accountFilter account ID filter (11-digit string; null/blank = no filter)
     * @param cardFilter    card number filter (16-char string; null/blank = no filter)
     * @param page          zero-based page number (PF8 increments, PF7 decrements)
     * @return paginated list of matching Card entities sorted by card number
     * @throws RecordNotFoundException if account filter is active but no cards
     *                                 exist for the specified account
     */
    @Transactional(readOnly = true)
    public Page<Card> listCards(String accountFilter, String cardFilter, int page) {
        return listCards(accountFilter, cardFilter, page, PAGE_SIZE);
    }

    /**
     * Lists credit cards with configurable page size.
     *
     * <p>Overloaded variant that accepts a custom page size parameter, allowing
     * REST API consumers to control result set size. The default COBOL-compatible
     * page size of {@value #PAGE_SIZE} is used when calling the three-argument
     * variant.</p>
     *
     * @param accountFilter account ID filter (11-char string; null/blank = no filter)
     * @param cardFilter    card number filter (16-char string; null/blank = no filter)
     * @param page          zero-based page number (PF8 increments, PF7 decrements)
     * @param size          page size (number of records per page); must be &gt;= 1
     * @return paginated list of matching Card entities sorted by card number
     */
    @Transactional(readOnly = true)
    public Page<Card> listCards(String accountFilter, String cardFilter,
            int page, int size) {
        logger.info("Credit card list: program={}, tranId={}, user={}, userType={}, "
                        + "fromProgram={}, pgmContext={}",
                THIS_PROGRAM, THIS_TRAN_ID,
                cardDemoContext.getUserId(),
                cardDemoContext.getUserType(),
                cardDemoContext.getFromProgram(),
                cardDemoContext.getPgmContext());

        // Resolve effective filters from parameters or COMMAREA context
        String effectiveAcctFilter = resolveAccountFilter(accountFilter);
        String effectiveCardFilter = resolveCardFilter(cardFilter);

        logger.debug("Effective filters: account='{}', card='{}', isAdmin={}",
                maskForLog(effectiveAcctFilter),
                maskForLog(effectiveCardFilter),
                cardDemoContext.isAdmin());

        // Normalize page number to non-negative value
        int safePage = Math.max(0, page);

        // Constrain size to reasonable bounds (1–100) to prevent excessive memory usage
        int safeSize = Math.max(1, Math.min(100, size));

        // Build pageable matching VSAM KSDS natural key ordering (ascending CARD-NUM)
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("cardNum"));

        // Validate account cross-references when account filter is active
        // Maps STARTBR on CARDAIX path — NOTFND = no cards for this account
        if (isFilterActive(effectiveAcctFilter)) {
            validateAccountCrossReferences(effectiveAcctFilter);
        }

        // Execute card query with appropriate filter strategy
        Page<Card> result = executeCardQuery(
                effectiveAcctFilter, effectiveCardFilter, pageable);

        // Log results for observability and traceability
        logCardResults(result, effectiveAcctFilter, effectiveCardFilter, safePage);

        return result;
    }

    /**
     * Validates account and card number filter inputs before card list retrieval.
     *
     * <p>Maps COCRDLIC.cbl paragraphs:</p>
     * <ul>
     *   <li><strong>2200-EDIT-INPUTS</strong> (line 985) — orchestrator</li>
     *   <li><strong>2210-EDIT-ACCOUNT</strong> (line 1003) — account validation</li>
     *   <li><strong>2220-EDIT-CARD</strong> (line 1036) — card number validation</li>
     * </ul>
     *
     * <p>Validation rules (matching COBOL):</p>
     * <ul>
     *   <li>Account filter: blank = valid (no filter); non-blank must be exactly
     *       11 numeric digits</li>
     *   <li>Card filter: blank = valid (no filter); non-blank must be exactly
     *       16 numeric digits</li>
     * </ul>
     *
     * @param accountFilter account ID filter to validate
     * @param cardFilter    card number filter to validate
     * @return list of validation error messages; empty list if all inputs valid
     */
    public List<String> editInputs(String accountFilter, String cardFilter) {
        logger.debug("Validating card list inputs: account='{}', card='{}'",
                maskForLog(accountFilter), maskForLog(cardFilter));

        ArrayList<String> errors = new ArrayList<>();

        // 2210-EDIT-ACCOUNT — validate account filter
        if (!editAccount(accountFilter)) {
            errors.add("Account ID: MUST BE A " + ACCOUNT_ID_LENGTH + " DIGIT NUMBER");
        }

        // 2220-EDIT-CARD — validate card number filter
        if (!editCard(cardFilter)) {
            errors.add("Card Number: MUST BE A " + CARD_NUM_LENGTH + " DIGIT NUMBER");
        }

        if (!errors.isEmpty()) {
            logger.info("Input validation failed with {} error(s): {}", errors.size(), errors);
        } else {
            logger.debug("Input validation passed: {}",
                    MessageConstants.THANK_YOU_MESSAGE.trim());
        }

        return errors;
    }

    /**
     * Reads card records forward (next page).
     *
     * <p>Maps COCRDLIC.cbl paragraph <strong>9000-READ-FORWARD</strong> (line 1123).</p>
     *
     * <p>COBOL: STARTBR GTEQ → READNEXT loop with 9500-FILTER-RECORDS → ENDBR.<br/>
     * Java: Fetch page N with ascending card number sort order.</p>
     *
     * @param accountFilter account ID filter
     * @param cardFilter    card number filter
     * @param page          zero-based page number to read forward to
     * @return paginated card results for the requested page
     * @throws RecordNotFoundException if no cards exist for the active account filter
     */
    @Transactional(readOnly = true)
    public Page<Card> readForward(String accountFilter, String cardFilter, int page) {
        logger.debug("Read forward (PF8): page={}, account='{}', card='{}'",
                page, maskForLog(accountFilter), maskForLog(cardFilter));
        return listCards(accountFilter, cardFilter, page);
    }

    /**
     * Reads card records backwards (previous page).
     *
     * <p>Maps COCRDLIC.cbl paragraph <strong>9100-READ-BACKWARDS</strong> (line 1264).</p>
     *
     * <p>COBOL: STARTBR GTEQ → READPREV loop filling array bottom-to-top → ENDBR.<br/>
     * Java: Fetch page N (caller decrements page number for backward navigation).</p>
     *
     * @param accountFilter account ID filter
     * @param cardFilter    card number filter
     * @param page          zero-based page number to read backwards to
     * @return paginated card results for the requested page
     * @throws RecordNotFoundException if no cards exist for the active account filter
     */
    @Transactional(readOnly = true)
    public Page<Card> readBackwards(String accountFilter, String cardFilter, int page) {
        logger.debug("Read backwards (PF7): page={}, account='{}', card='{}'",
                page, maskForLog(accountFilter), maskForLog(cardFilter));
        int safePage = Math.max(0, page);
        return listCards(accountFilter, cardFilter, safePage);
    }

    // ========================================================================
    // Private helper methods — COBOL paragraph translations
    // ========================================================================

    /**
     * Validates the account ID filter value.
     * Maps COCRDLIC.cbl paragraph <strong>2210-EDIT-ACCOUNT</strong> (line 1003).
     *
     * <p>COBOL rule: {@code IF WS-ACCTFILTER-N NOT NUMERIC} →
     * error "MUST BE A 11 DIGIT NUMBER".</p>
     *
     * @param accountId the account filter string to validate
     * @return {@code true} if blank (no filter) or valid 11-digit number;
     *         {@code false} if non-numeric or wrong length
     */
    private boolean editAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return true;
        }
        String trimmed = accountId.trim();
        if (trimmed.length() != ACCOUNT_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates the card number filter value.
     * Maps COCRDLIC.cbl paragraph <strong>2220-EDIT-CARD</strong> (line 1036).
     *
     * <p>COBOL rule: {@code IF CC-CARD-NUM-N NOT NUMERIC} →
     * error "MUST BE A 16 DIGIT NUMBER".</p>
     *
     * @param cardNum the card number filter string to validate
     * @return {@code true} if blank (no filter) or valid 16-digit number;
     *         {@code false} if non-numeric or wrong length
     */
    private boolean editCard(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            return true;
        }
        String trimmed = cardNum.trim();
        if (trimmed.length() != CARD_NUM_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a selection array for single-selection enforcement.
     * Maps COCRDLIC.cbl paragraph <strong>2250-EDIT-ARRAY</strong> (line 1073).
     *
     * <p>COBOL behaviour: tallies 'S' (select for detail view) and 'U' (select
     * for update) entries in the WS-CC-ACCT-ID-SEL-N array (7 OCCURS in COBOL).
     * Enforces that exactly one valid selection code is present.</p>
     *
     * <p>Error conditions (matching COBOL):</p>
     * <ul>
     *   <li>Invalid action code (not 'S', 'U', or blank) → returns -1</li>
     *   <li>Multiple selections → returns -1</li>
     *   <li>No selection → returns -1</li>
     * </ul>
     *
     * @param selections list of selection codes from the screen row array
     * @return zero-based index of the selected card, or -1 if no valid single selection
     */
    int editArray(List<String> selections) {
        if (selections == null || selections.isEmpty()) {
            return -1;
        }

        int selectedIndex = -1;
        int selectionCount = 0;

        for (int i = 0; i < selections.size(); i++) {
            String sel = selections.get(i);
            if (sel != null && !sel.isBlank()) {
                String trimmed = sel.trim().toUpperCase();
                if ("S".equals(trimmed) || "U".equals(trimmed)) {
                    selectionCount++;
                    selectedIndex = i;
                } else {
                    // Invalid action code — maps COBOL: "INVALID ACTION CODE"
                    logger.warn("Invalid selection code '{}' at index {}: {}",
                            sel, i, MessageConstants.INVALID_KEY_MESSAGE.trim());
                    return -1;
                }
            }
        }

        if (selectionCount == 0) {
            logger.debug("No card selection made");
            return -1;
        }

        if (selectionCount > 1) {
            // Maps COBOL: "PLEASE SELECT ONLY ONE RECORD"
            logger.info("Multiple selections detected (count={}); only one allowed",
                    selectionCount);
            return -1;
        }

        return selectedIndex;
    }

    /**
     * Applies record-level filtering matching COBOL browse filter logic.
     * Maps COCRDLIC.cbl paragraph <strong>9500-FILTER-RECORDS</strong> (line 1382).
     *
     * <p>Filter rules (both are exact match, matching COBOL):</p>
     * <ul>
     *   <li>Account filter: {@code CARD-ACCT-ID = WS-ACCTFILTER}</li>
     *   <li>Card filter: {@code CARD-NUM = CC-CARD-NUM}</li>
     * </ul>
     *
     * @param card          the card record to test
     * @param accountFilter the account filter (null/blank = not applied)
     * @param cardFilter    the card number filter (null/blank = not applied)
     * @return {@code true} if the card passes all active filters
     */
    private boolean filterRecords(Card card, String accountFilter, String cardFilter) {
        if (card == null) {
            return false;
        }

        // Account filter — maps: IF CARD-ACCT-ID-N NOT = WS-ACCTFILTER-N
        if (isFilterActive(accountFilter)) {
            String cardAcctId = card.getAccountId();
            if (cardAcctId == null || !cardAcctId.equals(accountFilter.trim())) {
                return false;
            }
        }

        // Card number filter — maps: IF CARD-NUM NOT = CC-CARD-NUM-N
        if (isFilterActive(cardFilter)) {
            String cardNum = card.getCardNum();
            if (cardNum == null || !cardNum.equals(cardFilter.trim())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Resolves the effective account filter from explicit parameter or COMMAREA.
     *
     * <p>When the explicit parameter is blank, falls back to
     * {@code CardDemoContext.getAcctId()} which carries the pre-populated
     * account ID from the COBOL COMMAREA (CDEMO-ACCT-ID).</p>
     *
     * @param explicitFilter the explicit account filter from the request
     * @return the effective account filter to use
     */
    private String resolveAccountFilter(String explicitFilter) {
        if (isFilterActive(explicitFilter)) {
            return explicitFilter;
        }
        String contextAcctId = cardDemoContext.getAcctId();
        if (isFilterActive(contextAcctId)) {
            logger.debug("Using account filter from COMMAREA: '{}'",
                    maskForLog(contextAcctId));
            return contextAcctId;
        }
        return explicitFilter;
    }

    /**
     * Resolves the effective card filter from explicit parameter or COMMAREA.
     *
     * <p>When the explicit parameter is blank, falls back to
     * {@code CardDemoContext.getCardNum()} which carries the pre-populated
     * card number from the COBOL COMMAREA (CDEMO-CARD-NUM).</p>
     *
     * @param explicitFilter the explicit card filter from the request
     * @return the effective card filter to use
     */
    private String resolveCardFilter(String explicitFilter) {
        if (isFilterActive(explicitFilter)) {
            return explicitFilter;
        }
        String contextCardNum = cardDemoContext.getCardNum();
        if (isFilterActive(contextCardNum)) {
            logger.debug("Using card filter from COMMAREA: '{}'",
                    maskForLog(contextCardNum));
            return contextCardNum;
        }
        return explicitFilter;
    }

    /**
     * Validates that the account has card cross-reference entries.
     *
     * <p>Maps COBOL: STARTBR on CARDAIX path — DFHRESP(NOTFND) is returned
     * when no cards are associated with the account ID via the alternate index.
     * In Java, this pre-validates via the cross-reference table before executing
     * the main card query.</p>
     *
     * @param accountFilter the account ID to validate
     * @throws RecordNotFoundException if no cross-reference entries exist
     *                                 for the account (maps VSAM status '23')
     */
    private void validateAccountCrossReferences(String accountFilter) {
        String trimmedAcct = accountFilter.trim();

        List<CardXref> xrefs = cardXrefRepository.findByAccountId(trimmedAcct);

        // Use Optional to enforce non-empty cross-reference — maps NOTFND handling
        Optional<CardXref> firstXref = xrefs.isEmpty()
                ? Optional.empty()
                : Optional.of(xrefs.get(0));

        CardXref validXref = firstXref.orElseThrow(() -> {
            logger.warn("No cross-reference records for account '{}'",
                    maskForLog(accountFilter));
            return new RecordNotFoundException(
                    "No cards found for account: " + trimmedAcct);
        });

        // Log cross-reference details for traceability
        logger.debug("Account cross-reference validated: cardNum='{}', accountId='{}'",
                maskForLog(validXref.getXrefCardNum()),
                validXref.getAccountId());
        logger.debug("Total cross-references for account '{}': {}",
                maskForLog(accountFilter), xrefs.size());
    }

    /**
     * Executes the card query with the appropriate filter strategy.
     *
     * <p>Translates the COBOL STARTBR/READNEXT browse logic:</p>
     * <ul>
     *   <li>Account filter active → CARDAIX path → {@code findByAccountId(Pageable)}</li>
     *   <li>No filter → KSDS primary key path → {@code findAll(Pageable)}</li>
     *   <li>Combined filters → fetch by account, then filter by card in memory</li>
     * </ul>
     *
     * @param accountFilter effective account filter
     * @param cardFilter    effective card filter
     * @param pageable      pagination specification
     * @return the paginated card results
     */
    private Page<Card> executeCardQuery(String accountFilter,
                                        String cardFilter,
                                        Pageable pageable) {
        boolean acctActive = isFilterActive(accountFilter);
        boolean cardActive = isFilterActive(cardFilter);

        Page<Card> result;

        if (acctActive) {
            // Account filter → AIX path: STARTBR on CARDAIX alternate index
            result = cardRepository.findByAccountId(accountFilter.trim(), pageable);

            // Additional card number filtering when both filters are active
            // Maps combined 9500-FILTER-RECORDS conditions
            if (cardActive && result.hasContent()) {
                List<Card> filtered = new ArrayList<>();
                for (Card card : result.getContent()) {
                    if (filterRecords(card, accountFilter, cardFilter)) {
                        filtered.add(card);
                    }
                }
                result = new PageImpl<>(filtered, pageable, filtered.size());
            }
        } else {
            // No account filter → primary key path: browse all cards
            result = cardRepository.findAll(pageable);

            // Card number filtering applied in memory after fetch
            // Maps 9500-FILTER-RECORDS card filter condition
            if (cardActive && result.hasContent()) {
                List<Card> filtered = new ArrayList<>();
                for (Card card : result.getContent()) {
                    if (filterRecords(card, accountFilter, cardFilter)) {
                        filtered.add(card);
                    }
                }
                result = new PageImpl<>(filtered, pageable, filtered.size());
            }
        }

        return result;
    }

    /**
     * Logs card query results for observability and traceability.
     * Replaces COBOL SEND-PLAIN-TEXT diagnostic paragraphs.
     *
     * @param result        the paginated card results
     * @param accountFilter the account filter applied
     * @param cardFilter    the card filter applied
     * @param page          the page number retrieved
     */
    private void logCardResults(Page<Card> result, String accountFilter,
                                String cardFilter, int page) {
        if (result.isEmpty()) {
            logger.warn("No cards found: account='{}', card='{}', page={}",
                    maskForLog(accountFilter), maskForLog(cardFilter), page);
        } else {
            logger.info("Card list result: {} records, page {}/{}, "
                            + "hasNext={}, hasPrevious={}",
                    result.getNumberOfElements(), page + 1,
                    result.getTotalPages(), result.hasNext(), result.hasPrevious());

            // Log individual card details for traceability
            for (Card card : result.getContent()) {
                logger.debug("  Card: num='{}', account='{}', status='{}'",
                        maskForLog(card.getCardNum()),
                        card.getAccountId(),
                        card.getActiveStatus());
            }
        }
    }

    /**
     * Checks whether a filter string is active (non-null and non-blank).
     *
     * <p>Maps COBOL: {@code IF WS-ACCTFILTER-N > ZERO} (numeric > 0 means
     * non-blank/non-zero). In Java, we check for null and blank to cover
     * both empty string and whitespace-only inputs.</p>
     *
     * @param filter the filter value to check
     * @return {@code true} if the filter is active (non-null, non-blank)
     */
    private boolean isFilterActive(String filter) {
        return filter != null && !filter.isBlank();
    }

    /**
     * Masks a value for log output to protect PII (card numbers, account IDs).
     * Shows only the last 4 characters, replacing the rest with asterisks.
     *
     * @param value the value to mask
     * @return masked string (e.g., "****5740") or "(none)" for null/blank
     */
    private String maskForLog(String value) {
        if (value == null || value.isBlank()) {
            return "(none)";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "*".repeat(trimmed.length() - 4)
                + trimmed.substring(trimmed.length() - 4);
    }
}

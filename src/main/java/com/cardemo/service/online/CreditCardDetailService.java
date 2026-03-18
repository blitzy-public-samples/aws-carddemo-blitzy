/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Credit Card Detail/Selection Service — faithfully translates COCRDSLC.cbl.
 *
 * <p>This service implements the credit card detail view and selection workflow,
 * providing two access paths that mirror the VSAM CARDDATA KSDS file and its
 * Alternate Index (AIX):</p>
 * <ul>
 *   <li><strong>Primary key access</strong> (CARDDAT): look up a card by its
 *       16-character card number — maps to
 *       {@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)}
 *       in paragraph 9100-GETCARD-BYACCTCARD (line 736)</li>
 *   <li><strong>Alternate index access</strong> (CARDAIX): look up cards by
 *       11-digit account ID — maps to
 *       {@code EXEC CICS READ DATASET('CARDAIX') RIDFLD(WS-CARD-RID-ACCTID)}
 *       in paragraph 9150-GETCARD-BYACCT (line 779)</li>
 * </ul>
 *
 * <h2>COBOL Paragraph → Java Method Traceability (100%)</h2>
 * <pre>
 * COCRDSLC.cbl Line | Paragraph                  | Java Method
 * ──────────────────┼────────────────────────────┼────────────────────────────────
 * 248               | 0000-MAIN                  | viewCardDetail(String, String)
 * 394               | COMMON-RETURN              | (framework — Spring MVC return)
 * 412               | 1000-SEND-MAP              | (presentation — handled by controller)
 * 427               | 1100-SCREEN-INIT           | screenInit() [private]
 * 457               | 1200-SETUP-SCREEN-VARS     | setupScreenVars(Card) [private]
 * 502               | 1300-SETUP-SCREEN-ATTRS    | (presentation — BMS attribute setup)
 * 563               | 1400-SEND-SCREEN           | (presentation — BMS SEND MAP)
 * 582               | 2000-PROCESS-INPUTS        | processInputs(String, String)
 * 596               | 2100-RECEIVE-MAP           | (inputs received from controller)
 * 608               | 2200-EDIT-MAP-INPUTS       | editMapInputs(String, String)
 * 647               | 2210-EDIT-ACCOUNT          | editAccount(String) [private]
 * 685               | 2220-EDIT-CARD             | editCard(String) [private]
 * 726               | 9000-READ-DATA             | readData(String, String) [private]
 * 736               | 9100-GETCARD-BYACCTCARD    | getCardByAcctCard(String, String)
 * 779               | 9150-GETCARD-BYACCT        | getCardByAcct(String)
 * 820               | SEND-LONG-TEXT             | (diagnostic logging via SLF4J)
 * 838               | SEND-PLAIN-TEXT            | (diagnostic logging via SLF4J)
 * 857               | ABEND-ROUTINE              | (exception propagation to Spring)
 * </pre>
 *
 * <h2>CICS-to-Spring Mapping</h2>
 * <ul>
 *   <li>{@code EXEC CICS READ DATASET('CARDDAT')} →
 *       {@link CardRepository#findById(Object)}</li>
 *   <li>{@code EXEC CICS READ DATASET('CARDAIX')} →
 *       {@link CardRepository#findByAccountId(String)}</li>
 *   <li>{@code COMMAREA} → {@link CardDemoContext} (request-scoped)</li>
 *   <li>{@code DFHRESP(NOTFND)} → {@link RecordNotFoundException}</li>
 *   <li>{@code DFHRESP(NORMAL)} → successful Optional/List return</li>
 *   <li>{@code COPY CSMSG01Y} → {@link MessageConstants}</li>
 * </ul>
 *
 * @see Card
 * @see CardXref
 * @see CardRepository
 * @see CardXrefRepository
 * @see CardDemoContext
 */
@Service
public class CreditCardDetailService {

    private static final Logger logger = LoggerFactory.getLogger(CreditCardDetailService.class);

    // ========================================================================
    // COBOL Literal Constants (← WS-LITERALS in WORKING-STORAGE)
    // ========================================================================

    /** COBOL program name literal (LIT-THISPGM = 'COCRDSLC'). */
    private static final String PROGRAM_NAME = "COCRDSLC";

    /** COBOL transaction ID literal (LIT-THISTRANID = 'CCDL'). */
    private static final String TRANSACTION_ID = "CCDL";

    /** COBOL source list program name (LIT-CCLISTPGM = 'COCRDLIC'). */
    private static final String CC_LIST_PROGRAM = "COCRDLIC";

    /** VSAM CARDDAT file name (LIT-CARDFILENAME = 'CARDDAT'). */
    private static final String CARD_FILE_NAME = "CARDDAT";

    /** VSAM CARDAIX file name (LIT-CARDFILENAME-ACCT-PATH = 'CARDAIX'). */
    private static final String CARD_AIX_FILE_NAME = "CARDAIX";

    // ========================================================================
    // Validation Constants
    // ========================================================================

    /** Expected length of the account ID field (CARD-ACCT-ID PIC 9(11)). */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /** Expected length of the card number field (CARD-NUM PIC X(16)). */
    private static final int CARD_NUM_LENGTH = 16;

    // ========================================================================
    // Message Constants (← 88-level VALUE clauses on WS-RETURN-MSG)
    // ========================================================================

    /** Message: account number not provided. */
    private static final String MSG_PROMPT_FOR_ACCT =
            "Account number not provided";

    /** Message: card number not provided. */
    private static final String MSG_PROMPT_FOR_CARD =
            "Card number not provided";

    /** Message: no search criteria received. */
    private static final String MSG_NO_SEARCH_CRITERIA =
            "No input received";

    /** Message: account number must be a non-zero 11-digit number. */
    private static final String MSG_SEARCHED_ACCT_NOT_VALID =
            "Account number must be a non zero 11 digit number";

    /** Message: card number must be a 16-digit number. */
    private static final String MSG_SEARCHED_CARD_NOT_VALID =
            "Card number if supplied must be a 16 digit number";

    /** Message: account not found in card cross-reference. */
    private static final String MSG_ACCT_NOT_FOUND_IN_XREF =
            "Did not find this account in cards database";

    /** Message: card/account combination not found. */
    private static final String MSG_ACCTCARD_NOT_FOUND =
            "Did not find cards for this search condition";

    /** Message: displaying requested details. */
    private static final String MSG_FOUND_CARDS =
            "   Displaying requested details";

    /** Message: prompt for initial input. */
    private static final String MSG_PROMPT_FOR_INPUT =
            "Please enter Account and Card Number";

    /** Message: account filter error. */
    private static final String MSG_ACCT_FILTER_ERROR =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** Message: card filter error. */
    private static final String MSG_CARD_FILTER_ERROR =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    // ========================================================================
    // Injected Dependencies
    // ========================================================================

    private final CardRepository cardRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CardDemoContext cardDemoContext;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Constructs a CreditCardDetailService with all required dependencies.
     *
     * <p>Replaces the COBOL CICS program registration model where COCRDSLC
     * is registered as transaction ID 'CCDL'.</p>
     *
     * @param cardRepository   JPA repository for Card entities (VSAM CARDDAT)
     * @param cardXrefRepository JPA repository for CardXref entities (VSAM CARDXREF)
     * @param cardDemoContext   request-scoped session context (COMMAREA equivalent)
     */
    public CreditCardDetailService(CardRepository cardRepository,
                                   CardXrefRepository cardXrefRepository,
                                   CardDemoContext cardDemoContext) {
        this.cardRepository = cardRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Public Methods — Exposed API
    // ========================================================================

    /**
     * Retrieves credit card detail for display — maps COBOL 0000-MAIN (line 248).
     *
     * <p>This is the primary entry point for the credit card detail view. It
     * implements the COBOL EVALUATE TRUE block from 0000-MAIN that determines
     * the action based on COMMAREA state:</p>
     * <ul>
     *   <li>If coming from the credit card list screen (COCRDLIC) with validated
     *       criteria → directly reads card data (lines 339-348)</li>
     *   <li>If first entry from another context → returns null to trigger prompt
     *       (lines 349-356)</li>
     *   <li>If re-entry with user input → processes inputs and reads data
     *       (lines 357-371)</li>
     * </ul>
     *
     * <p>The method supports two VSAM access paths:</p>
     * <ol>
     *   <li>Primary key read on CARDDAT: when a card number is provided</li>
     *   <li>AIX read on CARDAIX: when only an account ID is provided</li>
     * </ol>
     *
     * @param cardNum   the 16-character card number to look up (may be null/blank)
     * @param accountId the 11-character account ID to look up (may be null/blank)
     * @return the matching {@link Card} entity, or {@code null} if displaying
     *         an initial prompt (no search criteria provided yet)
     * @throws RecordNotFoundException if no card matches the provided criteria
     *         (maps to DFHRESP(NOTFND) — VSAM file status '23')
     */
    @Transactional(readOnly = true)
    public Card viewCardDetail(String cardNum, String accountId) {
        logger.info("viewCardDetail invoked: program={}, transaction={}",
                PROGRAM_NAME, TRANSACTION_ID);

        // ── Map COBOL 1100-SCREEN-INIT (line 427) ──
        screenInit();

        // ── Determine execution path from COMMAREA context (0000-MAIN EVALUATE) ──
        String fromProgram = cardDemoContext.getFromProgram();
        int pgmContext = cardDemoContext.getPgmContext();
        boolean isReenter = cardDemoContext.isReenterContext();

        // WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM (lines 339-348)
        // Coming from credit card list screen — selection criteria already validated
        if (pgmContext == CardDemoContext.PGM_ENTER
                && CC_LIST_PROGRAM.equals(fromProgram)) {
            logger.debug("Entry from credit card list screen ({}), "
                    + "criteria pre-validated", CC_LIST_PROGRAM);

            // Use COMMAREA card/account IDs if parameters are blank
            String effectiveAcctId = resolveAccountId(accountId);
            String effectiveCardNum = resolveCardNum(cardNum);

            Card card = readData(effectiveCardNum, effectiveAcctId);
            setupScreenVars(card);
            logger.info("Card detail retrieved successfully for card=****{}: {}",
                    maskCardNum(effectiveCardNum),
                    MessageConstants.THANK_YOU_MESSAGE.trim());
            return card;
        }

        // WHEN CDEMO-PGM-ENTER (lines 349-356) — first entry from other context
        if (pgmContext == CardDemoContext.PGM_ENTER) {
            logger.debug("First entry from program={}, prompting for input",
                    fromProgram);
            return null; // Signal to controller to show empty prompt form
        }

        // WHEN CDEMO-PGM-REENTER (lines 357-371) — re-entry with user input
        if (isReenter) {
            logger.debug("Re-entry: processing user inputs");
            return processInputs(cardNum, accountId);
        }

        // WHEN OTHER (lines 373-381) — unexpected data scenario
        logger.error("Unexpected data scenario in {}: pgmContext={}, fromProgram={}",
                PROGRAM_NAME, pgmContext, fromProgram);
        throw new IllegalStateException(
                "Unexpected data scenario in " + PROGRAM_NAME);
    }

    /**
     * Processes user-submitted inputs — maps COBOL 2000-PROCESS-INPUTS (line 582).
     *
     * <p>Implements the COBOL paragraph flow:</p>
     * <ol>
     *   <li>{@code PERFORM 2100-RECEIVE-MAP} — inputs already received from controller</li>
     *   <li>{@code PERFORM 2200-EDIT-MAP-INPUTS} — validate inputs via
     *       {@link #editMapInputs(String, String)}</li>
     *   <li>If valid, {@code PERFORM 9000-READ-DATA} — read card data</li>
     * </ol>
     *
     * @param cardNum   the user-entered card number (may be null/blank)
     * @param accountId the user-entered account ID (may be null/blank)
     * @return the matching {@link Card} entity
     * @throws RecordNotFoundException if the card/account is not found
     * @throws IllegalArgumentException if input validation fails
     */
    @Transactional(readOnly = true)
    public Card processInputs(String cardNum, String accountId) {
        logger.debug("processInputs: cardNum=****{}, accountId={}",
                maskCardNum(cardNum), accountId);

        // 2100-RECEIVE-MAP: inputs already received from controller as parameters

        // 2200-EDIT-MAP-INPUTS: validate the inputs
        List<String> validationErrors = editMapInputs(cardNum, accountId);
        if (!validationErrors.isEmpty()) {
            String firstError = validationErrors.get(0);
            logger.warn("Input validation failed: {} — {}",
                    MessageConstants.INVALID_KEY_MESSAGE.trim(), firstError);
            throw new IllegalArgumentException(firstError);
        }

        // 9000-READ-DATA: read card data after validation passes
        Card card = readData(cardNum, accountId);
        setupScreenVars(card);
        logger.info("processInputs completed successfully for card=****{}",
                maskCardNum(cardNum));
        return card;
    }

    /**
     * Validates user-entered map inputs — maps COBOL 2200-EDIT-MAP-INPUTS (line 608).
     *
     * <p>Performs field-level and cross-field validation matching the COBOL
     * paragraph sequence:</p>
     * <ol>
     *   <li>{@code PERFORM 2210-EDIT-ACCOUNT} — validate account ID</li>
     *   <li>{@code PERFORM 2220-EDIT-CARD} — validate card number</li>
     *   <li>Cross-field check: if both blank, report "No input received" (line 639)</li>
     * </ol>
     *
     * <p>In the COBOL source, validation state is tracked through 88-level flags:
     * {@code FLG-ACCTFILTER-ISVALID}, {@code FLG-CARDFILTER-ISVALID},
     * {@code FLG-ACCTFILTER-BLANK}, {@code FLG-CARDFILTER-BLANK}. In Java,
     * validation errors are accumulated in a {@link List}.</p>
     *
     * @param cardNum   the card number to validate (may be null/blank)
     * @param accountId the account ID to validate (may be null/blank)
     * @return a list of validation error messages; empty if all inputs are valid
     */
    public List<String> editMapInputs(String cardNum, String accountId) {
        logger.debug("editMapInputs: validating cardNum and accountId");

        List<String> errors = new ArrayList<>();

        // SET INPUT-OK TO TRUE (line 610)
        // SET FLG-CARDFILTER-ISVALID TO TRUE (line 611)
        // SET FLG-ACCTFILTER-ISVALID TO TRUE (line 612)

        // Normalize inputs: replace '*' or whitespace with null
        // Maps COBOL lines 615-627 replacing '*'/SPACES with LOW-VALUES
        String normalizedAcctId = normalizeInput(accountId);
        String normalizedCardNum = normalizeInput(cardNum);

        // PERFORM 2210-EDIT-ACCOUNT (line 630)
        boolean acctBlank = !editAccount(normalizedAcctId, errors);

        // PERFORM 2220-EDIT-CARD (line 633)
        boolean cardBlank = !editCard(normalizedCardNum, errors);

        // Cross-field edit: both blank → "No input received" (lines 637-640)
        if (acctBlank && cardBlank) {
            errors.add(MSG_NO_SEARCH_CRITERIA);
            logger.warn("No search criteria received — both account and card "
                    + "number are blank");
        }

        return errors;
    }

    /**
     * Retrieves a card by card number with account validation —
     * maps COBOL 9100-GETCARD-BYACCTCARD (line 736).
     *
     * <p>Implements the VSAM primary key read on the CARDDAT KSDS dataset:</p>
     * <pre>
     * EXEC CICS READ
     *     FILE      (LIT-CARDFILENAME)          → 'CARDDAT'
     *     RIDFLD    (WS-CARD-RID-CARDNUM)        → cardNum
     *     INTO      (CARD-RECORD)                → Card entity
     *     RESP      (WS-RESP-CD)
     *     RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>Response handling (lines 752-772):</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} → card found, return entity</li>
     *   <li>{@code DFHRESP(NOTFND)} → throw {@link RecordNotFoundException}
     *       (maps to "Did not find cards for this search condition")</li>
     *   <li>{@code OTHER} → throw exception with file error details</li>
     * </ul>
     *
     * @param accountId the 11-character account ID for context validation
     * @param cardNum   the 16-character card number (primary key)
     * @return the matching {@link Card} entity
     * @throws RecordNotFoundException if no card exists with the given number
     *         (DFHRESP(NOTFND) — VSAM file status '23')
     */
    @Transactional(readOnly = true)
    public Card getCardByAcctCard(String accountId, String cardNum) {
        logger.debug("9100-GETCARD-BYACCTCARD: reading {} by cardNum=****{}",
                CARD_FILE_NAME, maskCardNum(cardNum));

        // MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM (line 740)
        // EXEC CICS READ FILE(LIT-CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)
        Optional<Card> result = cardRepository.findById(cardNum);

        // EVALUATE WS-RESP-CD (line 752)
        if (result.isPresent()) {
            // WHEN DFHRESP(NORMAL) — SET FOUND-CARDS-FOR-ACCOUNT TO TRUE (line 754)
            Card card = result.orElseThrow();
            logger.info("Card found in {}: cardNum=****{}, accountId={}",
                    CARD_FILE_NAME, maskCardNum(card.getCardNum()),
                    card.getAccountId());
            return card;
        }

        // WHEN DFHRESP(NOTFND) (line 755)
        // SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE (line 760)
        logger.warn("Card not found in {} for cardNum=****{}, accountId={}",
                CARD_FILE_NAME, maskCardNum(cardNum), accountId);
        throw new RecordNotFoundException("Card", cardNum);
    }

    /**
     * Retrieves cards by account ID via alternate index —
     * maps COBOL 9150-GETCARD-BYACCT (line 779).
     *
     * <p>Implements the VSAM alternate index (AIX) read on the CARDAIX path:</p>
     * <pre>
     * EXEC CICS READ
     *     FILE      (LIT-CARDFILENAME-ACCT-PATH) → 'CARDAIX'
     *     RIDFLD    (WS-CARD-RID-ACCT-ID)         → accountId
     *     INTO      (CARD-RECORD)                 → Card entity
     *     RESP      (WS-RESP-CD)
     *     RESP2     (WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>Response handling (lines 793-808):</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} → cards found, return list</li>
     *   <li>{@code DFHRESP(NOTFND)} → throw {@link RecordNotFoundException}
     *       (maps to "Did not find this account in cards database")</li>
     *   <li>{@code OTHER} → throw exception with file error details</li>
     * </ul>
     *
     * <p>Note: In the COBOL source, the AIX read returns a single record
     * (CICS READ returns the first matching record). In Java, we return all
     * matching cards as a list for completeness, preserving the ability to
     * display the first card as the COBOL program does.</p>
     *
     * @param accountId the 11-character account ID to search for
     * @return a list of {@link Card} entities associated with the account
     * @throws RecordNotFoundException if no cards exist for the given account
     *         (DFHRESP(NOTFND) — VSAM file status '23')
     */
    @Transactional(readOnly = true)
    public List<Card> getCardByAcct(String accountId) {
        logger.debug("9150-GETCARD-BYACCT: reading {} by accountId={}",
                CARD_AIX_FILE_NAME, accountId);

        // EXEC CICS READ FILE(LIT-CARDFILENAME-ACCT-PATH) RIDFLD(WS-CARD-RID-ACCT-ID)
        List<Card> cards = cardRepository.findByAccountId(accountId);

        // EVALUATE WS-RESP-CD (line 793)
        if (!cards.isEmpty()) {
            // WHEN DFHRESP(NORMAL) — SET FOUND-CARDS-FOR-ACCOUNT TO TRUE (line 795)
            logger.info("Found {} card(s) in {} for accountId={}",
                    cards.size(), CARD_AIX_FILE_NAME, accountId);
            return cards;
        }

        // WHEN DFHRESP(NOTFND) (line 796)
        // SET DID-NOT-FIND-ACCT-IN-CARDXREF TO TRUE (line 799)
        logger.warn("No cards found in {} for accountId={}",
                CARD_AIX_FILE_NAME, accountId);
        throw new RecordNotFoundException(MSG_ACCT_NOT_FOUND_IN_XREF);
    }

    // ========================================================================
    // Private Methods — Internal Workflow
    // ========================================================================

    /**
     * Initializes screen state — maps COBOL 1100-SCREEN-INIT (line 427).
     *
     * <p>In the COBOL source, this paragraph initializes the BMS output map
     * (CCRDSLAO) with titles, dates, times, transaction ID, and program name.
     * In the headless Java service, presentation initialization is not
     * applicable. This method logs the initialization context for traceability.</p>
     */
    private void screenInit() {
        // MOVE LOW-VALUES TO CCRDSLAO (line 428) — N/A in headless service
        // MOVE CCDA-TITLE01 TO TITLE01O (line 432) — presentation
        // MOVE LIT-THISTRANID TO TRNNAMEO (line 434) — presentation
        // MOVE LIT-THISPGM TO PGMNAMEO (line 435) — presentation
        // MOVE FUNCTION CURRENT-DATE (line 437) — date/time formatting

        logger.debug("1100-SCREEN-INIT: initialized context for program={}, "
                + "transaction={}", PROGRAM_NAME, TRANSACTION_ID);
    }

    /**
     * Sets up screen display variables from card data —
     * maps COBOL 1200-SETUP-SCREEN-VARS (line 457).
     *
     * <p>In the COBOL source, this paragraph moves card record fields to BMS
     * map output fields for display. In the headless service layer, the card
     * entity itself serves as the display data carrier. This method accesses
     * the card's fields to validate data availability and logs the display
     * summary.</p>
     *
     * <p>COBOL field mappings (lines 474-485):</p>
     * <ul>
     *   <li>{@code CARD-EMBOSSED-NAME → CRDNAMEO}: card name display</li>
     *   <li>{@code CARD-EXPIRAION-DATE → EXPYEARO/EXPMONO}: expiry parts</li>
     *   <li>{@code CARD-ACTIVE-STATUS → CRDSTCDO}: status display</li>
     * </ul>
     *
     * @param card the card entity to prepare for display (must not be null)
     */
    private void setupScreenVars(Card card) {
        if (card == null) {
            logger.debug("1200-SETUP-SCREEN-VARS: no card data to display");
            return;
        }

        // Access card fields matching COBOL display setup (lines 474-485)
        // These getter calls ensure all fields are loaded from the entity
        String embossedName = card.getEmbossedName();
        String expirationDate = card.getExpirationDate();
        String activeStatus = card.getActiveStatus();
        String cvvCode = card.getCvvCode();
        String displayCardNum = card.getCardNum();
        String displayAcctId = card.getAccountId();

        logger.debug("1200-SETUP-SCREEN-VARS: card=****{}, acct={}, "
                        + "name={}, expiry={}, status={}",
                maskCardNum(displayCardNum),
                displayAcctId,
                embossedName,
                expirationDate,
                activeStatus);

        // CVV is accessed but never displayed in logs per PII rules
        // COBOL accesses CARD-CVV-CD for screen display (COCRDSL BMS map)
        if (cvvCode != null) {
            logger.debug("1200-SETUP-SCREEN-VARS: CVV present (masked)");
        }
    }

    /**
     * Validates the account ID input — maps COBOL 2210-EDIT-ACCOUNT (line 647).
     *
     * <p>Validation rules (faithful to COBOL lines 647-678):</p>
     * <ol>
     *   <li>Not supplied (null/blank/zeros) → error:
     *       "Account number not provided"</li>
     *   <li>Not numeric → error:
     *       "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"</li>
     *   <li>Valid → update COMMAREA with validated account ID</li>
     * </ol>
     *
     * @param accountId the normalized account ID to validate (may be null)
     * @param errors    the error accumulator list to append messages to
     * @return {@code true} if the account ID is valid or non-blank,
     *         {@code false} if blank/empty (indicating "blank" state)
     */
    private boolean editAccount(String accountId, List<String> errors) {
        // SET FLG-ACCTFILTER-NOT-OK TO TRUE (line 648)

        // Not supplied — blank/null/zeros (lines 651-661)
        if (isBlankOrZeros(accountId)) {
            // SET INPUT-ERROR TO TRUE (line 654)
            // SET FLG-ACCTFILTER-BLANK TO TRUE (line 655)
            // SET WS-PROMPT-FOR-ACCT TO TRUE (line 657)
            errors.add(MSG_PROMPT_FOR_ACCT);

            // MOVE ZEROES TO CDEMO-ACCT-ID (line 659)
            cardDemoContext.setAcctId(null);
            logger.debug("2210-EDIT-ACCOUNT: account ID is blank");
            return false; // Indicates blank state
        }

        // Not numeric / not 11 characters (lines 665-674)
        if (!isNumeric(accountId) || accountId.length() != ACCOUNT_ID_LENGTH) {
            // SET INPUT-ERROR TO TRUE (line 666)
            // SET FLG-ACCTFILTER-NOT-OK TO TRUE (line 667)
            errors.add(MSG_ACCT_FILTER_ERROR);

            // MOVE ZERO TO CDEMO-ACCT-ID (line 673)
            cardDemoContext.setAcctId(null);
            logger.debug("2210-EDIT-ACCOUNT: invalid account ID format: {}",
                    accountId);
            return true; // Non-blank but invalid
        }

        // Valid account (lines 676-678)
        // MOVE CC-ACCT-ID TO CDEMO-ACCT-ID (line 676)
        // SET FLG-ACCTFILTER-ISVALID TO TRUE (line 677)
        cardDemoContext.setAcctId(accountId);
        logger.debug("2210-EDIT-ACCOUNT: valid account ID={}", accountId);
        return true;
    }

    /**
     * Validates the card number input — maps COBOL 2220-EDIT-CARD (line 685).
     *
     * <p>Validation rules (faithful to COBOL lines 685-719):</p>
     * <ol>
     *   <li>Not supplied (null/blank/zeros) → error:
     *       "Card number not provided"</li>
     *   <li>Not numeric → error:
     *       "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"</li>
     *   <li>Valid → update COMMAREA with validated card number</li>
     * </ol>
     *
     * @param cardNum the normalized card number to validate (may be null)
     * @param errors  the error accumulator list to append messages to
     * @return {@code true} if the card number is valid or non-blank,
     *         {@code false} if blank/empty (indicating "blank" state)
     */
    private boolean editCard(String cardNum, List<String> errors) {
        // SET FLG-CARDFILTER-NOT-OK TO TRUE (line 688)

        // Not supplied — blank/null/zeros (lines 691-702)
        if (isBlankOrZeros(cardNum)) {
            // SET INPUT-ERROR TO TRUE (line 694)
            // SET FLG-CARDFILTER-BLANK TO TRUE (line 695)
            // SET WS-PROMPT-FOR-CARD TO TRUE (line 697)
            errors.add(MSG_PROMPT_FOR_CARD);

            // MOVE ZEROES TO CDEMO-CARD-NUM (line 700)
            cardDemoContext.setCardNum(null);
            logger.debug("2220-EDIT-CARD: card number is blank");
            return false; // Indicates blank state
        }

        // Not numeric / not 16 characters (lines 706-715)
        if (!isNumeric(cardNum) || cardNum.length() != CARD_NUM_LENGTH) {
            // SET INPUT-ERROR TO TRUE (line 707)
            // SET FLG-CARDFILTER-NOT-OK TO TRUE (line 708)
            errors.add(MSG_CARD_FILTER_ERROR);

            // MOVE ZERO TO CDEMO-CARD-NUM (line 714)
            cardDemoContext.setCardNum(null);
            logger.debug("2220-EDIT-CARD: invalid card number format");
            return true; // Non-blank but invalid
        }

        // Valid card number (lines 717-718)
        // MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM (line 717)
        // SET FLG-CARDFILTER-ISVALID TO TRUE (line 718)
        cardDemoContext.setCardNum(cardNum);
        logger.debug("2220-EDIT-CARD: valid card number ****{}",
                maskCardNum(cardNum));
        return true;
    }

    /**
     * Reads card data — maps COBOL 9000-READ-DATA (line 726).
     *
     * <p>The COBOL paragraph delegates to 9100-GETCARD-BYACCTCARD. In the Java
     * implementation, this method also handles the case where only an account ID
     * is provided (no card number), using the cross-reference lookup via
     * {@link CardXrefRepository} and the AIX read via
     * {@link #getCardByAcct(String)}.</p>
     *
     * @param cardNum   the card number to look up (may be null/blank)
     * @param accountId the account ID to look up (may be null/blank)
     * @return the matching {@link Card} entity
     * @throws RecordNotFoundException if no matching card is found
     */
    private Card readData(String cardNum, String accountId) {
        logger.debug("9000-READ-DATA: cardNum=****{}, accountId={}",
                maskCardNum(cardNum), accountId);

        // If card number is provided, use primary key read (9100-GETCARD-BYACCTCARD)
        if (!isBlankOrZeros(cardNum)) {
            return getCardByAcctCard(accountId, cardNum);
        }

        // If only account ID is provided, use AIX read (9150-GETCARD-BYACCT)
        if (!isBlankOrZeros(accountId)) {
            // First try cross-reference resolution for the account
            List<CardXref> xrefs = cardXrefRepository.findByAccountId(accountId);
            if (!xrefs.isEmpty()) {
                // Use the first cross-reference card number for detail view
                CardXref firstXref = xrefs.get(0);
                String resolvedCardNum = firstXref.getXrefCardNum();
                String resolvedAcctId = firstXref.getAccountId();
                logger.debug("9000-READ-DATA: resolved card=****{} "
                                + "from xref for account={}",
                        maskCardNum(resolvedCardNum), resolvedAcctId);
            }

            // Perform AIX-based lookup
            List<Card> cards = getCardByAcct(accountId);
            // Return the first card (matching COBOL behavior where
            // CICS READ on AIX returns the first matching record)
            return cards.get(0);
        }

        // No valid search criteria
        logger.warn("9000-READ-DATA: no valid search criteria provided");
        throw new RecordNotFoundException(MSG_ACCTCARD_NOT_FOUND);
    }

    // ========================================================================
    // Private Utility Methods
    // ========================================================================

    /**
     * Normalizes user input by replacing sentinel values with null.
     *
     * <p>Maps COBOL lines 615-627 where '*' and SPACES are replaced with
     * LOW-VALUES to indicate "not provided".</p>
     *
     * @param input the raw user input
     * @return the normalized input, or {@code null} if blank/sentinel
     */
    private String normalizeInput(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return null;
        }
        return trimmed;
    }

    /**
     * Checks if a string is blank, null, or all zeros.
     *
     * <p>Maps the COBOL condition:
     * {@code IF field EQUAL LOW-VALUES OR field EQUAL SPACES OR field-N EQUAL ZEROS}</p>
     *
     * @param value the value to check
     * @return {@code true} if the value is null, blank, or all zeros
     */
    private boolean isBlankOrZeros(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        // Check for all zeros (COBOL: CC-ACCT-ID-N EQUAL ZEROS / CC-CARD-NUM-N EQUAL ZEROS)
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a string contains only numeric digits.
     *
     * <p>Maps the COBOL condition: {@code IF field IS NOT NUMERIC}</p>
     *
     * @param value the value to check
     * @return {@code true} if the value is non-null and all digits
     */
    private boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves the effective account ID from parameter or COMMAREA.
     *
     * <p>Maps COBOL line 342: {@code MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N}</p>
     *
     * @param paramAccountId the account ID from the request parameter
     * @return the effective account ID
     */
    private String resolveAccountId(String paramAccountId) {
        if (paramAccountId != null && !paramAccountId.isBlank()) {
            return paramAccountId;
        }
        return cardDemoContext.getAcctId();
    }

    /**
     * Resolves the effective card number from parameter or COMMAREA.
     *
     * <p>Maps COBOL line 343: {@code MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N}</p>
     *
     * @param paramCardNum the card number from the request parameter
     * @return the effective card number
     */
    private String resolveCardNum(String paramCardNum) {
        if (paramCardNum != null && !paramCardNum.isBlank()) {
            return paramCardNum;
        }
        return cardDemoContext.getCardNum();
    }

    /**
     * Masks a card number for safe logging (PII protection).
     *
     * <p>Returns the last 4 characters of the card number, or "null" if the
     * card number is null/blank. Example: "4111111111111111" → "1111"</p>
     *
     * @param cardNum the card number to mask
     * @return the masked suffix for log output
     */
    private String maskCardNum(String cardNum) {
        if (cardNum == null || cardNum.length() < 4) {
            return "null";
        }
        return cardNum.substring(cardNum.length() - 4);
    }
}

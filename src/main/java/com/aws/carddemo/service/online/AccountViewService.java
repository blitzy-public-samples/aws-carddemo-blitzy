/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.online;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COACTVWForm;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Account-view online service.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COACTVWC.cbl} (CICS COBOL program
 * {@code COACTVWC}, transaction id {@code CAVW}). This class is the faithful Java
 * migration of the online "Account View" program, preserving the COBOL control
 * flow one-for-one: each numbered business {@code PARAGRAPH} becomes exactly one
 * method (AAP &sect;0.3.3, service + repository layers; AAP &sect;0.4.1,
 * online-programs table {@code COACTVWC.cbl -> AccountViewService +
 * AccountController} for transaction {@code CAVW}).</p>
 *
 * <h2>Multi-file / alternate-index read-path archetype</h2>
 * <p>{@code COACTVWC} is the <em>alternate-index read chain</em> archetype of the
 * AWS CardDemo online tier. Given an account id it walks three files in order:
 * the card cross-reference (read by account through the {@code CXACAIX} alternate
 * index, which yields the customer id and card number), then the account master
 * (read by account id), then the customer master (read by the customer id
 * discovered in the cross-reference). Each not-found short-circuits the chain
 * (AAP &sect;0.6.2, VSAM alternate index &rarr; Spring Data derived query).</p>
 *
 * <h2>The presentation split</h2>
 * <p>{@code COACTVWC}'s paragraphs divide cleanly into business logic (kept here)
 * and 3270/BMS presentation (owned by the paired {@code AccountController} and the
 * Thymeleaf view {@code src/main/resources/templates/COACTVW.html}). This service
 * therefore implements only the business paragraphs and deliberately contains no
 * {@code SEND}/{@code RECEIVE}, screen-header, or attribute/colour logic. It
 * populates the <em>data</em> fields of {@link COACTVWForm}; the controller renders
 * the screen, sets attributes/messages, and performs the pseudo-conversational
 * {@code RETURN}.</p>
 * <table border="1">
 *   <caption>COACTVWC paragraph &rarr; Java mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Owner</th><th>Java member</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td><td>service</td><td>{@link #mainEntry(COACTVWForm, PfKey)}</td></tr>
 *   <tr><td>{@code 2000-PROCESS-INPUTS}</td><td>service</td><td>{@link #processInputs(COACTVWForm)}</td></tr>
 *   <tr><td>{@code 2200-EDIT-MAP-INPUTS}</td><td>service</td>
 *       <td>{@link #editMapInputs(COACTVWForm, CardWorkArea)}</td></tr>
 *   <tr><td>{@code 2210-EDIT-ACCOUNT}</td><td>service</td><td>{@link #editAccount(CardWorkArea)}</td></tr>
 *   <tr><td>{@code 9000-READ-ACCT}</td><td>service</td><td>{@link #readAcct(COACTVWForm)}</td></tr>
 *   <tr><td>{@code 9200-GETCARDXREF-BYACCT}</td><td>service</td>
 *       <td>{@link #getCardXrefByAccount(Long)}</td></tr>
 *   <tr><td>{@code 9300-GETACCTDATA-BYACCT}</td><td>service</td>
 *       <td>{@link #getAcctDataByAccount(Long)}</td></tr>
 *   <tr><td>{@code 9400-GETCUSTDATA-BYCUST}</td><td>service</td>
 *       <td>{@link #getCustDataByCust(Long)}</td></tr>
 *   <tr><td>{@code 2100-RECEIVE-MAP}</td><td>controller</td><td>&mdash; (form binding)</td></tr>
 *   <tr><td>{@code 1000-SEND-MAP} .. {@code 1400-SEND-SCREEN}</td><td>controller</td>
 *       <td>&mdash; (view render, header, attributes, re-enter flip)</td></tr>
 *   <tr><td>{@code SEND-PLAIN-TEXT} / {@code SEND-LONG-TEXT} / {@code ABEND-ROUTINE}</td>
 *       <td>controller</td><td>&mdash; (surfaced by {@code GlobalExceptionHandler})</td></tr>
 * </table>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>{@code COACTVWC} is pseudo-conversational. The COBOL {@code EVALUATE TRUE}
 * in {@code 0000-MAIN} switches on the within-program enter/re-enter flag
 * ({@code CDEMO-PGM-CONTEXT}); on <em>enter</em> it prompts for an account id, on
 * <em>re-enter</em> it edits the input and, when valid, reads and displays the
 * account. The flag is carried across interactions by the session-scoped
 * {@link CardDemoContext} ({@code COMMAREA} replacement). Two deliberate,
 * documented deviations from a literal transliteration:</p>
 * <ul>
 *   <li><b>First-entry &amp; re-enter flip owned by the controller.</b> The COBOL
 *       first-entry test ({@code EIBCALEN = 0}, or arrival from the menu while not
 *       re-entering) and the flip to re-enter (COBOL {@code SET CDEMO-PGM-REENTER}
 *       inside {@code 1400-SEND-SCREEN}) are boundary/presentation concerns handled
 *       by the controller. This service reads the flag through
 *       {@link CardDemoContext#isProgramEnter()} / {@link CardDemoContext#isProgramReenter()}
 *       and does not flip it.</li>
 *   <li><b>No session wipe.</b> The COBOL {@code INITIALIZE CARDDEMO-COMMAREA} on
 *       first entry is intentionally not reproduced as a field wipe, because that
 *       would clobber the Spring session identity; the equivalent behaviour (a
 *       blank prompt with no stale data on first entry, and correct back-navigation
 *       on {@code PF3}) is preserved by the state machine below.</li>
 * </ul>
 *
 * <h2>Exception parity (AAP &sect;0.6.5)</h2>
 * <p>The COBOL read paragraphs translate a CICS {@code NOTFND} response into an
 * {@code INPUT-ERROR} with a message that the program then re-displays. In this
 * migration the not-found path is expressed as a thrown
 * {@link RecordNotFoundException} carrying the COBOL message text; the web-tier
 * {@code GlobalExceptionHandler} (not referenced here) surfaces it as the
 * BMS-equivalent error line. The live COBOL builds its not-found message
 * dynamically from CICS {@code RESP}/{@code RESP2} diagnostic codes, which have no
 * portable Java equivalent; this service therefore uses each file's declared
 * descriptive not-found message ({@code DID-NOT-FIND-*} working-storage
 * condition), which is the human-readable COBOL message text.</p>
 *
 * <h2>Decimal and record fidelity (AAP &sect;0.6.1)</h2>
 * <p>Monetary values are read from the {@link Account} entity as
 * {@link BigDecimal} and rendered into the form's pre-formatted {@code String}
 * money fields via {@link BigDecimal#toPlainString()}; no {@code float}/
 * {@code double} is ever used. Presentation edit-masking is a template concern.</p>
 *
 * <p>This service performs read-only access: it never writes, saves, or rewrites,
 * and declares no write transaction (AAP &sect;0.4.1, "Read-only (no writes)").</p>
 */
@Service
public class AccountViewService {

    // --- Program identity literals (WS-LITERALS in COACTVWC.cbl) ------------

    /** {@code LIT-THISPGM} - this program's name. */
    private static final String THIS_PROGRAM = "COACTVWC";

    /** {@code LIT-THISTRANID} - this program's CICS transaction id. */
    private static final String THIS_TRANID = "CAVW";

    /** {@code LIT-THISMAPSET} - this program's BMS mapset. */
    private static final String THIS_MAPSET = "COACTVW";

    /** {@code LIT-THISMAP} - this program's BMS map. */
    private static final String THIS_MAP = "CACTVWA";

    /** {@code LIT-MENUPGM} - fallback (main menu) program for {@code PF3} return. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** {@code LIT-MENUTRANID} - fallback (main menu) transaction id for {@code PF3} return. */
    private static final String MENU_TRANID = "CM00";

    // --- Screen messages (WS-INFO-MSG / WS-RETURN-MSG 88-levels) ------------

    /**
     * {@code WS-PROMPT-FOR-INPUT} - informational prompt shown on every rendering
     * of the screen (COACTVWC.cbl L113-114). {@code WS-INFORM-OUTPUT} is declared
     * in working storage but never set by the program, so the information line is
     * always this prompt.
     */
    private static final String PROMPT_FOR_INPUT_MSG =
            "Enter or update id of account to display";

    /**
     * {@code WS-PROMPT-FOR-ACCT} - "account not supplied" message set by
     * {@code 2210-EDIT-ACCOUNT} (COACTVWC.cbl L121-122). In practice the
     * cross-field edit in {@code 2200-EDIT-MAP-INPUTS} overrides it with
     * {@link #NO_SEARCH_CRITERIA_MSG}; it is retained here for paragraph fidelity.
     */
    private static final String ACCT_NOT_PROVIDED_MSG = "Account number not provided";

    /**
     * {@code NO-SEARCH-CRITERIA-RECEIVED} - final message for a blank / {@code '*'}
     * / spaces account filter, applied by the cross-field edit (COACTVWC.cbl
     * L123-124, L381-382).
     */
    private static final String NO_SEARCH_CRITERIA_MSG = "No input received";

    /**
     * Invalid-account-filter message. This is the exact <em>inline</em> literal
     * executed by {@code 2210-EDIT-ACCOUNT} (COACTVWC.cbl L672) when the filter is
     * not numeric or is all zeroes. It is reproduced byte-for-byte, including the
     * <b>double space</b> between "must" and "be" and the hyphen in "non-zero".
     * (The similarly-worded working-storage 88-level
     * {@code SEARCHED-ACCT-NOT-NUMERIC} is <em>not</em> the literal the code uses.)
     */
    private static final String ACCT_FILTER_INVALID_MSG =
            "Account Filter must  be a non-zero 11 digit number";

    /**
     * {@code DID-NOT-FIND-ACCT-IN-CARDXREF} - cross-reference not-found message
     * (COACTVWC.cbl L129-130).
     */
    private static final String XREF_NOT_FOUND_MSG =
            "Did not find this account in account card xref file";

    /**
     * {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} - account-master not-found message
     * (COACTVWC.cbl L131-132).
     */
    private static final String ACCT_NOT_FOUND_MSG =
            "Did not find this account in account master file";

    /**
     * {@code DID-NOT-FIND-CUST-IN-CUSTDAT} - customer-master not-found message
     * (COACTVWC.cbl L133-134).
     */
    private static final String CUST_NOT_FOUND_MSG =
            "Did not find associated customer in master file";

    /**
     * {@code 'UNEXPECTED DATA SCENARIO'} - message for the {@code WHEN OTHER}
     * branch of the {@code 0000-MAIN} {@code EVALUATE} (COACTVWC.cbl L379-385).
     */
    private static final String UNEXPECTED_DATA_SCENARIO_MSG = "UNEXPECTED DATA SCENARIO";

    /** Fixed COBOL width of the account id ({@code PIC 9(11)}). */
    private static final int ACCT_ID_WIDTH = 11;

    /** Fixed COBOL width of the customer id ({@code PIC 9(09)}). */
    private static final int CUST_ID_WIDTH = 9;

    /** Fixed COBOL width of the Social Security Number ({@code PIC 9(09)}). */
    private static final int SSN_WIDTH = 9;

    /** Fixed COBOL width of the FICO credit score ({@code PIC 9(03)}). */
    private static final int FICO_WIDTH = 3;

    // --- Injected collaborators (constructor injection, all final) ----------

    /**
     * Session-scoped conversation state, the {@code COPY COCOM01Y} /
     * {@code CARDDEMO-COMMAREA} replacement carrying the selected account,
     * customer, card, and navigation hand-off across interactions.
     */
    private final CardDemoContext context;

    /**
     * Card cross-reference repository (VSAM {@code CCXREF}). Its
     * {@code findByXrefAcctId} derived query reproduces the {@code CXACAIX}
     * alternate index used by {@code 9200-GETCARDXREF-BYACCT}.
     */
    private final CardXrefRepository cardXrefRepository;

    /** Account master repository (VSAM {@code ACCTDAT}) for {@code 9300-GETACCTDATA-BYACCT}. */
    private final AccountRepository accountRepository;

    /** Customer master repository (VSAM {@code CUSTDAT}) for {@code 9400-GETCUSTDATA-BYCUST}. */
    private final CustomerRepository customerRepository;

    /**
     * Creates the account-view service with its session context and the three
     * repositories required to walk the alternate-index read chain.
     *
     * <p>The constructor only assigns fields (it invokes no overridable method and
     * does not leak {@code this}), keeping it free of the {@code this-escape}
     * warning under the zero-warning {@code -Xlint:all} build.</p>
     *
     * @param context            the session-scoped conversation state
     * @param cardXrefRepository the card cross-reference repository (alternate-index reads)
     * @param accountRepository  the account master repository
     * @param customerRepository the customer master repository
     */
    public AccountViewService(CardDemoContext context,
                              CardXrefRepository cardXrefRepository,
                              AccountRepository accountRepository,
                              CustomerRepository customerRepository) {
        this.context = context;
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Entry point reproducing {@code 0000-MAIN}: the pseudo-conversational state
     * machine for the account-view transaction ({@code CAVW}).
     *
     * <p>Mirrors the COBOL sequence: initialise the per-interaction work area,
     * remap the attention key (only {@code ENTER} and {@code PF3} are valid; any
     * other key is coerced to {@code ENTER}), then evaluate:</p>
     * <ul>
     *   <li><b>{@code PF3}</b> ({@code WHEN CCARD-AID-PFK03}) &rarr; hand back to the
     *       calling program, or the main menu when no caller is recorded
     *       ({@link RoutingAction#REDIRECT}); see {@link #handlePf3Return()}.</li>
     *   <li><b>enter</b> ({@code WHEN CDEMO-PGM-ENTER}) &rarr; display the empty
     *       screen with the input prompt ({@link RoutingAction#SHOW_SCREEN}).</li>
     *   <li><b>re-enter</b> ({@code WHEN CDEMO-PGM-REENTER}) &rarr; edit the input
     *       ({@link #processInputs(COACTVWForm)}); on an input error re-display the
     *       screen with the error line, otherwise read the account
     *       ({@link #readAcct(COACTVWForm)}) and display it.</li>
     *   <li><b>otherwise</b> ({@code WHEN OTHER}) &rarr; the abend path, surfaced as
     *       an {@link IllegalStateException} carrying {@code 'UNEXPECTED DATA
     *       SCENARIO'}.</li>
     * </ul>
     *
     * <p>The information line is always {@link #PROMPT_FOR_INPUT_MSG}, matching
     * {@code 1200-SETUP-SCREEN-VARS} (the alternate {@code WS-INFORM-OUTPUT} text is
     * never set by the program).</p>
     *
     * @param form  the account-view form (its {@code acctsid} carries the input
     *              filter; data fields are populated here on a successful read)
     * @param pfKey the attention key the operator pressed, as resolved by the
     *              controller ({@code YYYY-STORE-PFKEY} equivalent)
     * @return the routing outcome for the controller to act on
     * @throws RecordNotFoundException if the read chain cannot locate a required record
     * @throws IllegalStateException   for the {@code WHEN OTHER} abend scenario
     */
    public AccountViewResult mainEntry(COACTVWForm form, PfKey pfKey) {
        // INITIALIZE CC-WORK-AREA: a fresh per-interaction work area (the account
        // filter is carried between the edit paragraphs via this holder). The
        // return message starts cleared (COBOL SET WS-RETURN-MSG-OFF TO TRUE); the
        // AccountViewResult built below is the message carrier.
        // Remap PF keys: only ENTER and PF3 are valid at this point; any other AID
        // is coerced to ENTER (COBOL SET PFK-INVALID / IF PFK-INVALID SET ENTER).
        PfKey effectiveKey =
                (pfKey == PfKey.ENTER || pfKey == PfKey.PFK03) ? pfKey : PfKey.ENTER;

        // EVALUATE TRUE
        if (effectiveKey == PfKey.PFK03) {
            // WHEN CCARD-AID-PFK03 -> XCTL to caller or menu.
            return handlePf3Return();
        }
        if (context.isProgramEnter()) {
            // WHEN CDEMO-PGM-ENTER -> gather selection criteria (prompt screen).
            return AccountViewResult.showScreen(PROMPT_FOR_INPUT_MSG);
        }
        if (context.isProgramReenter()) {
            // WHEN CDEMO-PGM-REENTER -> edit the received input, then read.
            String errorMessage = processInputs(form);
            if (errorMessage != null && !errorMessage.isBlank()) {
                // INPUT-ERROR -> re-display with the error line (and the prompt).
                return AccountViewResult.showScreenWithMessages(errorMessage, PROMPT_FOR_INPUT_MSG);
            }
            // No error -> walk the read chain and display the account details.
            readAcct(form);
            return AccountViewResult.showScreen(PROMPT_FOR_INPUT_MSG);
        }
        // WHEN OTHER -> unexpected scenario (COBOL abend path). pgmContext is always
        // enter or re-enter, so this is defensive, preserving the COBOL structure.
        throw new IllegalStateException(UNEXPECTED_DATA_SCENARIO_MSG);
    }

    /**
     * Handles the {@code PF3} branch of {@code 0000-MAIN}
     * ({@code WHEN CCARD-AID-PFK03}): prepare the navigation hand-off back to the
     * calling program, or to the main menu when no caller is recorded.
     *
     * <p>Reproduces the COBOL exactly: the target transaction id / program are the
     * recorded caller ({@code CDEMO-FROM-TRANID} / {@code CDEMO-FROM-PROGRAM}) when
     * present, otherwise the main-menu defaults ({@code CM00} / {@code COMEN01C}).
     * The origin is then reset to this program so the target can navigate back, the
     * user type is set to regular user, the program context is set to enter, and the
     * last map/mapset are recorded. The controller performs the actual redirect
     * (COBOL {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}).</p>
     *
     * @return a {@link RoutingAction#REDIRECT} outcome
     */
    private AccountViewResult handlePf3Return() {
        // Target tran id: caller if known (not LOW-VALUES / SPACES), else main menu.
        String toTranid = isBlankField(context.getFromTranid()) ? MENU_TRANID : context.getFromTranid();
        // Target program: caller if known, else main menu.
        String toProgram =
                isBlankField(context.getFromProgram()) ? MENU_PROGRAM : context.getFromProgram();
        context.setToTranid(toTranid);
        context.setToProgram(toProgram);

        // Record this program as the origin for the target's back-navigation.
        context.setFromTranid(THIS_TRANID);
        context.setFromProgram(THIS_PROGRAM);

        // SET CDEMO-USRTYP-USER; SET CDEMO-PGM-ENTER; record last map/mapset.
        context.setUser();
        context.markEnter();
        context.setLastMapset(THIS_MAPSET);
        context.setLastMap(THIS_MAP);

        return AccountViewResult.redirect();
    }

    /**
     * Reproduces {@code 2000-PROCESS-INPUTS}: orchestrate the input edits for a
     * re-entry interaction.
     *
     * <p>The COBOL first performs {@code 2100-RECEIVE-MAP} (a CICS
     * {@code RECEIVE MAP}) and then {@code 2200-EDIT-MAP-INPUTS}. In this migration
     * the receive is the controller's form binding, so this method allocates the
     * per-interaction work area (COBOL {@code CC-WORK-AREA}) and delegates to
     * {@link #editMapInputs(COACTVWForm, CardWorkArea)}. The COBOL trailer of this
     * paragraph copies the message to {@code CCARD-ERROR-MSG} and stores the
     * next program / mapset / map for the return; those are routing/presentation
     * hints now conveyed by {@link AccountViewResult} and the controller, so they
     * are intentionally not replicated here as dead writes.</p>
     *
     * @param form the account-view form whose {@code acctsid} carries the filter
     * @return the validation error message, or {@code null} when the input is
     *         acceptable ({@code INPUT-OK})
     */
    public String processInputs(COACTVWForm form) {
        CardWorkArea workArea = new CardWorkArea();
        return editMapInputs(form, workArea);
    }

    /**
     * Reproduces {@code 2200-EDIT-MAP-INPUTS}: normalise the account filter and run
     * the field and cross-field edits.
     *
     * <p>Mirrors the COBOL exactly: an account filter of {@code '*'} or spaces is
     * treated as "no filter" (COBOL {@code MOVE LOW-VALUES TO CC-ACCT-ID}); any
     * other value is stored verbatim into the work area. The individual field edit
     * {@link #editAccount(CardWorkArea)} then runs. Finally the cross-field edit
     * applies: when the filter was blank, the message is set to
     * {@link #NO_SEARCH_CRITERIA_MSG} ("No input received"), overriding the
     * "not provided" message produced by {@link #editAccount(CardWorkArea)}
     * (COBOL {@code IF FLG-ACCTFILTER-BLANK SET NO-SEARCH-CRITERIA-RECEIVED}).</p>
     *
     * @param form     the account-view form (source of the {@code acctsid} filter)
     * @param workArea the per-interaction work area holding {@code CC-ACCT-ID}
     * @return the resulting error message, or {@code null} when the filter is valid
     */
    public String editMapInputs(COACTVWForm form, CardWorkArea workArea) {
        // Replace '*' / spaces with the "blank" (LOW-VALUES) sentinel; otherwise
        // carry the trimmed filter as CC-ACCT-ID (ACCTSIDI is a PIC 9(11) numeric
        // symbolic-map field, so a shorter numeric entry is zero-filled downstream).
        String rawFilter = form.getAcctsid();
        String filter;
        if (isWildcardOrBlank(rawFilter)) {
            filter = "";
        } else {
            filter = rawFilter.trim();
        }
        workArea.setAcctId(filter);

        // INDIVIDUAL FIELD EDIT (2210-EDIT-ACCOUNT).
        String message = editAccount(workArea);

        // CROSS-FIELD EDIT: a blank filter yields "No input received", overriding
        // the "Account number not provided" message from 2210.
        if (filter.isEmpty()) {
            message = NO_SEARCH_CRITERIA_MSG;
        }
        return message;
    }

    /**
     * Reproduces {@code 2210-EDIT-ACCOUNT}: validate the account-id filter and, when
     * valid, store it into the session context as {@code CDEMO-ACCT-ID}.
     *
     * <p>The three COBOL outcomes are preserved:</p>
     * <ul>
     *   <li><b>Not supplied</b> ({@code CC-ACCT-ID = LOW-VALUES / SPACES}) &rarr; set
     *       the selected account id to zero and return
     *       {@link #ACCT_NOT_PROVIDED_MSG} ("Account number not provided"). (The
     *       caller's cross-field edit overrides this with "No input received".)</li>
     *   <li><b>Not numeric, or all zeroes</b> ({@code IS NOT NUMERIC OR ZEROES})
     *       &rarr; set the selected account id to zero and return
     *       {@link #ACCT_FILTER_INVALID_MSG}. Because the BMS field is
     *       {@code PIC 9(11)}, a shorter all-digit entry is a valid zero-filled
     *       11-digit number and is accepted; only non-digits or an all-zero value
     *       are rejected.</li>
     *   <li><b>Valid</b> &rarr; parse the digits and store the value as the selected
     *       account id ({@code MOVE CC-ACCT-ID TO CDEMO-ACCT-ID}); return
     *       {@code null} (no error).</li>
     * </ul>
     *
     * @param workArea the work area holding {@code CC-ACCT-ID}
     * @return the error message, or {@code null} when the account id is valid
     */
    public String editAccount(CardWorkArea workArea) {
        String candidate = workArea.getAcctId();

        // Not supplied (LOW-VALUES / SPACES).
        if (candidate == null || candidate.isBlank()) {
            context.setAcctId(0L);
            return ACCT_NOT_PROVIDED_MSG;
        }

        // Not numeric, or all zeroes (a shorter all-digit value is zero-filled and
        // therefore acceptable, matching the PIC 9(11) numeric field semantics).
        if (!isEligibleAccountNumber(candidate)) {
            context.setAcctId(0L);
            return ACCT_FILTER_INVALID_MSG;
        }

        // Valid: store the selected account id.
        context.setAcctId(Long.parseLong(candidate));
        return null;
    }


    /**
     * Reproduces {@code 9000-READ-ACCT}: walk the three-file read chain and populate
     * the screen's data fields.
     *
     * <p>Using the selected account id ({@code CDEMO-ACCT-ID}) the method reads, in
     * order: the card cross-reference by account through the {@code CXACAIX}
     * alternate index ({@link #getCardXrefByAccount(Long)}), which yields the
     * customer id and card number stored back into the context; the account master
     * by account id ({@link #getAcctDataByAccount(Long)}); and the customer master by
     * the discovered customer id ({@link #getCustDataByCust(Long)}). In the COBOL a
     * not-found at any step sets {@code INPUT-ERROR} and short-circuits the chain via
     * {@code GO TO 9000-READ-ACCT-EXIT}; here each reader throws
     * {@link RecordNotFoundException}, which naturally short-circuits the remaining
     * reads and is surfaced by the web-tier {@code GlobalExceptionHandler}. On
     * success the account and customer fields are copied to the form (the
     * {@code 1200-SETUP-SCREEN-VARS} data moves).</p>
     *
     * @param form the account-view form to populate with the retrieved data
     * @throws RecordNotFoundException if the cross-reference, account, or customer
     *                                 record cannot be found
     */
    public void readAcct(COACTVWForm form) {
        Long acctId = context.getAcctId();

        // 9200-GETCARDXREF-BYACCT (alternate index CXACAIX) -> customer id + card num.
        CardXref cardXref = getCardXrefByAccount(acctId);
        context.setCustId(cardXref.getXrefCustId());
        context.setCardNum(cardXref.getXrefCardNum());

        // 9300-GETACCTDATA-BYACCT (by account id).
        Account account = getAcctDataByAccount(acctId);

        // 9400-GETCUSTDATA-BYCUST (by the customer id from the cross-reference).
        Customer customer = getCustDataByCust(context.getCustId());

        // 1200-SETUP-SCREEN-VARS data moves (account then customer).
        populateAccountFields(form, account);
        populateCustomerFields(form, customer);

        // Echo the account id (COBOL MOVE CC-ACCT-ID TO ACCTSIDO), zero-filled to the
        // PIC 9(11) width.
        form.setAcctsid(formatAcctId(acctId));
    }

    /**
     * Reproduces {@code 9200-GETCARDXREF-BYACCT}: read the card cross-reference by
     * account through the {@code CXACAIX} alternate index.
     *
     * <p>The COBOL {@code EXEC CICS READ DATASET('CXACAIX')} becomes the Spring Data
     * derived query {@link CardXrefRepository#findByXrefAcctId(Long)} (AAP
     * &sect;0.6.2). A CICS {@code NORMAL} response corresponds to at least one match;
     * because the alternate-index read returns the first record on the path, the
     * first match is used. A {@code NOTFND} response (an empty result) becomes a
     * {@link RecordNotFoundException} carrying {@link #XREF_NOT_FOUND_MSG}.</p>
     *
     * @param acctId the selected account id ({@code WS-CARD-RID-ACCT-ID})
     * @return the first matching cross-reference record (yielding the customer id
     *         and card number)
     * @throws RecordNotFoundException when no cross-reference exists for the account
     */
    public CardXref getCardXrefByAccount(Long acctId) {
        List<CardXref> matches = cardXrefRepository.findByXrefAcctId(acctId);
        if (matches.isEmpty()) {
            throw new RecordNotFoundException(XREF_NOT_FOUND_MSG);
        }
        return matches.get(0);
    }

    /**
     * Reproduces {@code 9300-GETACCTDATA-BYACCT}: read the account master by its
     * primary key.
     *
     * <p>The COBOL {@code EXEC CICS READ DATASET('ACCTDAT')} becomes
     * {@link AccountRepository#findById(Object)}. A {@code NORMAL} response sets
     * {@code FOUND-ACCT-IN-MASTER}; a {@code NOTFND} response becomes a
     * {@link RecordNotFoundException} carrying {@link #ACCT_NOT_FOUND_MSG}.</p>
     *
     * @param acctId the selected account id
     * @return the account master record
     * @throws RecordNotFoundException when no account exists for the id
     */
    public Account getAcctDataByAccount(Long acctId) {
        return accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(ACCT_NOT_FOUND_MSG));
    }

    /**
     * Reproduces {@code 9400-GETCUSTDATA-BYCUST}: read the customer master by its
     * primary key.
     *
     * <p>The COBOL {@code EXEC CICS READ DATASET('CUSTDAT')} becomes
     * {@link CustomerRepository#findById(Object)}. A {@code NORMAL} response sets
     * {@code FOUND-CUST-IN-MASTER}; a {@code NOTFND} response becomes a
     * {@link RecordNotFoundException} carrying {@link #CUST_NOT_FOUND_MSG}.</p>
     *
     * @param custId the customer id discovered from the cross-reference
     * @return the customer master record
     * @throws RecordNotFoundException when no customer exists for the id
     */
    public Customer getCustDataByCust(Long custId) {
        return customerRepository.findById(custId)
                .orElseThrow(() -> new RecordNotFoundException(CUST_NOT_FOUND_MSG));
    }


    // --- Screen population helpers (1200-SETUP-SCREEN-VARS data moves) -------

    /**
     * Copies the account-master fields onto the form, reproducing the account
     * portion of {@code 1200-SETUP-SCREEN-VARS} ({@code FOUND-ACCT-IN-MASTER}
     * branch).
     *
     * <p>Monetary values ({@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT},
     * {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-CURR-CYC-CREDIT},
     * {@code ACCT-CURR-CYC-DEBIT}) are rendered from {@link BigDecimal} into the
     * form's pre-formatted {@code String} money fields via
     * {@link #formatAmount(BigDecimal)}; no floating-point type is used. Dates are
     * rendered as ISO {@code yyyy-MM-dd}, matching the COBOL {@code PIC X(10)} date
     * layout.</p>
     *
     * @param form    the form to populate
     * @param account the account master record
     */
    private void populateAccountFields(COACTVWForm form, Account account) {
        form.setAcsttus(account.getActiveStatus());
        form.setAcurbal(formatAmount(account.getCurrBal()));
        form.setAcrdlim(formatAmount(account.getCreditLimit()));
        form.setAcshlim(formatAmount(account.getCashCreditLimit()));
        form.setAcrcycr(formatAmount(account.getCurrCycCredit()));
        form.setAcrcydb(formatAmount(account.getCurrCycDebit()));
        form.setAdtopen(formatDate(account.getOpenDate()));
        form.setAexpdt(formatDate(account.getExpiraionDate()));
        form.setAreisdt(formatDate(account.getReissueDate()));
        form.setAaddgrp(account.getGroupId());
    }

    /**
     * Copies the customer-master fields onto the form, reproducing the customer
     * portion of {@code 1200-SETUP-SCREEN-VARS} ({@code FOUND-CUST-IN-MASTER}
     * branch).
     *
     * <p>Two COBOL specifics are preserved: the SSN is formatted
     * {@code XXX-XX-XXXX} exactly as the COBOL {@code STRING} of
     * {@code CUST-SSN(1:3)}, {@code (4:2)} and {@code (6:4)}; and &mdash; a deliberate
     * legacy quirk &mdash; customer <em>address line 3</em> ({@code CUST-ADDR-LINE-3}) is
     * moved to the screen's <em>city</em> field ({@code ACSCITYO}), not to an
     * address-line field. Both behaviours are reproduced verbatim.</p>
     *
     * @param form     the form to populate
     * @param customer the customer master record
     */
    private void populateCustomerFields(COACTVWForm form, Customer customer) {
        form.setAcstnum(formatCustId(customer.getCustId()));
        form.setAcstssn(formatSsn(customer.getSsn()));
        form.setAcstfco(formatFico(customer.getFicoCreditScore()));
        form.setAcstdob(formatDate(customer.getDateOfBirth()));
        form.setAcsfnam(customer.getFirstName());
        form.setAcsmnam(customer.getMiddleName());
        form.setAcslnam(customer.getLastName());
        form.setAcsadl1(customer.getAddrLine1());
        form.setAcsadl2(customer.getAddrLine2());
        // Legacy quirk: address line 3 populates the CITY field (COACTVWC.cbl:
        // MOVE CUST-ADDR-LINE-3 TO ACSCITYO).
        form.setAcscity(customer.getAddrLine3());
        form.setAcsstte(customer.getAddrStateCd());
        form.setAcszipc(customer.getAddrZip());
        form.setAcsctry(customer.getAddrCountryCd());
        form.setAcsphn1(customer.getPhoneNum1());
        form.setAcsphn2(customer.getPhoneNum2());
        form.setAcsgovt(customer.getGovtIssuedId());
        form.setAcseftc(customer.getEftAccountId());
        form.setAcspflg(customer.getPriCardHolderInd());
    }

    // --- Formatting helpers -------------------------------------------------

    /**
     * Renders a monetary {@link BigDecimal} to its display string using
     * {@link BigDecimal#toPlainString()} (value-faithful, no exponent, no
     * floating-point). A {@code null} value renders as the empty string.
     *
     * @param amount the monetary value, or {@code null}
     * @return the plain-string representation, or {@code ""} when {@code null}
     */
    private static String formatAmount(BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }

    /**
     * Renders a {@link LocalDate} as ISO {@code yyyy-MM-dd}, matching the COBOL
     * {@code PIC X(10)} date layout. A {@code null} date renders as the empty
     * string.
     *
     * @param date the date value, or {@code null}
     * @return the ISO date string, or {@code ""} when {@code null}
     */
    private static String formatDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /**
     * Formats a Social Security Number as {@code XXX-XX-XXXX}, reproducing the COBOL
     * {@code STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)} over the
     * zero-padded 9-digit {@code PIC 9(09)} value. A {@code null} SSN renders as the
     * empty string.
     *
     * @param ssn the 9-digit SSN, or {@code null}
     * @return the masked-format SSN, or {@code ""} when {@code null}
     */
    private static String formatSsn(Long ssn) {
        if (ssn == null) {
            return "";
        }
        String digits = zeroPad(ssn, SSN_WIDTH);
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5, 9);
    }

    /**
     * Formats the FICO credit score to the COBOL {@code PIC 9(03)} width (three
     * digits, zero-padded). A {@code null} score renders as the empty string.
     *
     * @param fico the FICO credit score, or {@code null}
     * @return the 3-digit score string, or {@code ""} when {@code null}
     */
    private static String formatFico(Integer fico) {
        return fico == null ? "" : zeroPad(fico.longValue(), FICO_WIDTH);
    }

    /**
     * Formats the customer id to the COBOL {@code PIC 9(09)} width (nine digits,
     * zero-padded), matching {@code MOVE CUST-ID TO ACSTNUMO}. A {@code null} id
     * renders as the empty string.
     *
     * @param custId the customer id, or {@code null}
     * @return the 9-digit id string, or {@code ""} when {@code null}
     */
    private static String formatCustId(Long custId) {
        return custId == null ? "" : zeroPad(custId, CUST_ID_WIDTH);
    }

    /**
     * Formats the account id to the COBOL {@code PIC 9(11)} width (eleven digits,
     * zero-padded), reproducing the echo {@code MOVE CC-ACCT-ID TO ACCTSIDO}. A
     * {@code null} id renders as the empty string.
     *
     * @param acctId the account id, or {@code null}
     * @return the 11-digit id string, or {@code ""} when {@code null}
     */
    private static String formatAcctId(Long acctId) {
        return acctId == null ? "" : zeroPad(acctId, ACCT_ID_WIDTH);
    }

    /**
     * Left-pads the decimal representation of a non-negative value with zeroes to
     * the given fixed width, reproducing COBOL {@code PIC 9(n)} display semantics.
     *
     * @param value the value to render
     * @param width the fixed field width
     * @return the zero-padded decimal string
     */
    private static String zeroPad(long value, int width) {
        return String.format("%0" + width + "d", value);
    }

    // --- Validation helpers -------------------------------------------------

    /**
     * Tests whether a raw account filter denotes "no filter": {@code null}, empty
     * / all-whitespace (COBOL {@code SPACES} / {@code LOW-VALUES}), or the wildcard
     * {@code '*'} (COBOL {@code IF ACCTSIDI = '*' OR SPACES}).
     *
     * @param rawFilter the raw filter value from the form
     * @return {@code true} when the filter is blank or the wildcard
     */
    private static boolean isWildcardOrBlank(String rawFilter) {
        if (rawFilter == null) {
            return true;
        }
        String trimmed = rawFilter.trim();
        return trimmed.isEmpty() || "*".equals(trimmed);
    }

    /**
     * Tests whether a supplied account filter is an acceptable account number,
     * reproducing the COBOL {@code NOT (CC-ACCT-ID IS NOT NUMERIC OR ZEROES)}
     * condition for the {@code PIC 9(11)} field: composed solely of digits, no wider
     * than eleven digits, and not all zeroes.
     *
     * @param candidate the trimmed filter value (assumed non-blank)
     * @return {@code true} when the value is a non-zero all-digit number of at most
     *         eleven digits
     */
    private static boolean isEligibleAccountNumber(String candidate) {
        return isAllDigits(candidate)
                && candidate.length() <= ACCT_ID_WIDTH
                && !isAllZeros(candidate);
    }

    /**
     * Tests whether every character of a non-empty string is an ASCII digit
     * ({@code '0'}..{@code '9'}), matching COBOL {@code PIC 9} numeric semantics.
     *
     * @param value the value to test
     * @return {@code true} when the value is non-empty and all digits
     */
    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether every character of a non-empty string is the digit {@code '0'},
     * matching the COBOL {@code EQUAL ZEROES} test.
     *
     * @param value the value to test
     * @return {@code true} when the value is non-empty and entirely zeroes
     */
    private static boolean isAllZeros(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether a navigation hand-off field is unset, reproducing the COBOL
     * {@code EQUAL LOW-VALUES OR EQUAL SPACES} test used by the {@code PF3} branch.
     *
     * @param field the field to test
     * @return {@code true} when the field is {@code null} or blank
     */
    private static boolean isBlankField(String field) {
        return field == null || field.isBlank();
    }

    // --- Result contract ----------------------------------------------------

    /**
     * The routing action a controller must take after a business method returns,
     * modelling the two terminal behaviours of {@code 0000-MAIN}.
     */
    public enum RoutingAction {

        /**
         * Hand off to another program (COBOL {@code EXEC CICS XCTL}); the controller
         * redirects to {@link CardDemoContext#getToProgram()} /
         * {@link CardDemoContext#getToTranid()}.
         */
        REDIRECT,

        /**
         * Render the account-view screen (COBOL {@code 1000-SEND-MAP} /
         * {@code 1400-SEND-SCREEN}); the controller displays the form, applies the
         * messages, and flips the program context to re-enter.
         */
        SHOW_SCREEN
    }

    /**
     * Immutable outcome of a business method: the {@link RoutingAction} to take plus
     * the two screen message lines that {@code COACTVWC} maintains &mdash; the error /
     * return line ({@code WS-RETURN-MSG} &rarr; {@code ERRMSGO}) and the information
     * line ({@code WS-INFO-MSG} &rarr; {@code INFOMSGO}). Unlike the single-message
     * menu result, the account-view screen shows both lines, so both are carried
     * here; the controller maps the return line to the error colour and the
     * information line to the informational colour.
     *
     * @param action        the routing action for the controller
     * @param returnMessage the error / return message line (never {@code null};
     *                      empty when there is no error)
     * @param infoMessage   the informational message line (never {@code null};
     *                      empty when there is no information)
     */
    public record AccountViewResult(RoutingAction action, String returnMessage, String infoMessage) {

        /**
         * Canonical constructor validating the action and normalising {@code null}
         * message lines to the empty string so consumers never see {@code null}.
         *
         * @param action        the routing action; must not be {@code null}
         * @param returnMessage the error / return line (normalised to {@code ""} when {@code null})
         * @param infoMessage   the information line (normalised to {@code ""} when {@code null})
         */
        public AccountViewResult {
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            if (returnMessage == null) {
                returnMessage = "";
            }
            if (infoMessage == null) {
                infoMessage = "";
            }
        }

        /**
         * Creates a {@link RoutingAction#REDIRECT} outcome with no messages (the
         * COBOL {@code PF3} {@code XCTL} hand-off).
         *
         * @return a redirect result
         */
        public static AccountViewResult redirect() {
            return new AccountViewResult(RoutingAction.REDIRECT, "", "");
        }

        /**
         * Creates a {@link RoutingAction#SHOW_SCREEN} outcome carrying only the
         * information line (no error), for the prompt and successful-display paths.
         *
         * @param infoMessage the information line to display
         * @return a show-screen result with no error line
         */
        public static AccountViewResult showScreen(String infoMessage) {
            return new AccountViewResult(RoutingAction.SHOW_SCREEN, "", infoMessage);
        }

        /**
         * Creates a {@link RoutingAction#SHOW_SCREEN} outcome carrying both an error
         * / return line and an information line, for the input-error re-display path.
         *
         * @param returnMessage the error / return line to display
         * @param infoMessage   the information line to display
         * @return a show-screen result with both message lines
         */
        public static AccountViewResult showScreenWithMessages(String returnMessage, String infoMessage) {
            return new AccountViewResult(RoutingAction.SHOW_SCREEN, returnMessage, infoMessage);
        }
    }
}


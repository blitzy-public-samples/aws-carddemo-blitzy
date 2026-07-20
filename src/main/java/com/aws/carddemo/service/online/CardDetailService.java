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

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COCRDSLForm;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.CardRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Credit-card detail (view) online service, the Java migration of the CICS COBOL program
 * {@code COCRDSLC} (the AWS CardDemo "Credit Card Detail / View" screen).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COCRDSLC.cbl} &mdash; program {@code COCRDSLC},
 * CICS transaction id {@code CCDL}, BMS mapset {@code COCRDSL} / map {@code CCRDSLA}. This
 * service preserves the original program's control flow one-for-one: each business COBOL
 * paragraph becomes exactly one Java method (AAP &sect;0.3.3, &sect;0.4.1). It is a
 * <em>read-only</em> view &mdash; it never writes the card store.</p>
 *
 * <h2>COCRDSEC / CDV1 traceability note (100%-coverage requirement, AAP &sect;0.4.1, &sect;0.6.10)</h2>
 * <p>The card-detail <em>security</em> variant {@code COCRDSEC} (CICS transaction
 * {@code CDV1}) has <strong>no</strong> COBOL {@code .cbl} source: a grep of
 * {@code legacy/cbl/COCRDSLC.cbl} for {@code COCRDSEC} returns empty. It exists only in the
 * CICS resource definition file {@code legacy/csd/CARDDEMO.CSD} as
 * {@code DEFINE PROGRAM(COCRDSEC)} / {@code DEFINE TRANSACTION(CDV1)}. In the Spring Boot
 * migration it is <strong>not</strong> a standalone service; it is realized as URL/method
 * authorization in {@code com.aws.carddemo.config.SecurityConfig} (the modern equivalent of
 * the RACF/CICS transaction-level protection that {@code CDV1} provided over the card-detail
 * screen). This note records that mapping here so the bidirectional traceability matrix
 * reaches 100% coverage with no gaps; the 1:1 code source for this service remains
 * {@code COCRDSLC.cbl}.</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code 0000-MAIN} &rarr; {@link #mainEntry(COCRDSLForm, PfKey)}</li>
 *   <li>{@code 2000-PROCESS-INPUTS} &rarr; {@link #processInputs(COCRDSLForm, CardWorkArea, ProcessingState)}</li>
 *   <li>{@code 2200-EDIT-MAP-INPUTS} &rarr; {@link #editMapInputs(COCRDSLForm, CardWorkArea, ProcessingState)}</li>
 *   <li>{@code 2210-EDIT-ACCOUNT} &rarr; {@link #editAccount(CardWorkArea, ProcessingState)}</li>
 *   <li>{@code 2220-EDIT-CARD} &rarr; {@link #editCard(CardWorkArea, ProcessingState)}</li>
 *   <li>{@code 9000-READ-DATA} &rarr; {@link #readData(CardWorkArea, ProcessingState, COCRDSLForm)}</li>
 *   <li>{@code 9100-GETCARD-BYACCTCARD} &rarr; {@link #getCardByAccountCard(CardWorkArea, ProcessingState, COCRDSLForm)}</li>
 *   <li>{@code 9150-GETCARD-BYACCT} &rarr; {@link #getCardByAccount(CardWorkArea, ProcessingState, COCRDSLForm)}</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code 1000-SEND-MAP}, {@code 1100-SCREEN-INIT},
 * {@code 1200-SETUP-SCREEN-VARS}, {@code 1300-SETUP-SCREEN-ATTRS}, {@code 1400-SEND-SCREEN},
 * {@code 2100-RECEIVE-MAP}, {@code SEND-LONG-TEXT} and {@code SEND-PLAIN-TEXT} are
 * intentionally <em>not</em> implemented here: the 3270/BMS map send/receive, the screen
 * header (title/date/time), the field colors/cursor/protection attributes and the PF-key
 * legend are presentation concerns owned by the paired {@code CardController}. This service
 * contains business logic only &mdash; it validates input, reads the card, decides routing,
 * populates the model with the retrieved card data, and produces messages &mdash; and never
 * performs terminal I/O. The one piece of pseudo-conversational <em>state</em> that the COBOL
 * sets inside {@code 1400-SEND-SCREEN} ({@code SET CDEMO-PGM-REENTER TO TRUE}, line 567) is
 * conversation state rather than rendering, so it is lifted into this service (see
 * {@link #buildShowResult(COCRDSLForm, ProcessingState)}), consistent with the sibling menu
 * services.</p>
 *
 * <p>The {@code ABEND-ROUTINE} (and the {@code WHEN OTHER} "unexpected data scenario" branch
 * of {@code 0000-MAIN}) becomes a thrown unchecked exception surfaced by the web-tier
 * {@code GlobalExceptionHandler}; that handler is deliberately not imported here.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried navigation state across CICS returns
 * becomes the session-scoped {@link CardDemoContext}, injected here. First entry into the
 * transaction (COBOL {@code EIBCALEN = 0}) is reproduced by {@link CardDemoContext#isNew()};
 * the {@code XCTL}/{@code RETURN TRANSID} hand-off is reproduced through the context's
 * {@code from*}/{@code to*} program fields, which the controller consults when redirecting.
 * The transient {@code CC-WORK-AREA} ({@code CVCRD01Y}) and the {@code WS-MISC-STORAGE}
 * edit flags are modelled as per-request locals ({@link CardWorkArea} and the private
 * {@link ProcessingState}) created in {@link #mainEntry(COCRDSLForm, PfKey)} and threaded
 * through the helper methods, so this singleton service holds no mutable request state and
 * is thread-safe.</p>
 *
 * <h2>Data access (AAP &sect;0.6.2, &sect;0.6.5)</h2>
 * <p>The VSAM {@code READ CARDDAT} by card number becomes {@link CardRepository#findById(Object)}
 * and the alternate-index ({@code CARDAIX}) {@code READ} by account becomes
 * {@link CardRepository#findByCardAcctId(Long)}. A CICS {@code NOTFND} response becomes a
 * {@link RecordNotFoundException}; a genuine data-access failure (the COBOL {@code WHEN OTHER}
 * file-error path) is a Spring {@code DataAccessException} that propagates to the central
 * handler.</p>
 */
@Service
public class CardDetailService {

    // ------------------------------------------------------------------------
    // Literals and constants (COBOL WS-LITERALS, legacy/cbl/COCRDSLC.cbl L160-192)
    // ------------------------------------------------------------------------

    /** {@code LIT-THISPGM} - this program name ({@code COCRDSLC}). */
    public static final String LIT_THISPGM = "COCRDSLC";

    /** {@code LIT-THISTRANID} - this CICS transaction id ({@code CCDL}). */
    public static final String LIT_THISTRANID = "CCDL";

    /** {@code LIT-THISMAPSET} - this BMS mapset ({@code COCRDSL}). */
    public static final String LIT_THISMAPSET = "COCRDSL";

    /** {@code LIT-THISMAP} - this BMS map ({@code CCRDSLA}). */
    public static final String LIT_THISMAP = "CCRDSLA";

    /** {@code LIT-CCLISTPGM} - the credit-card list program ({@code COCRDLIC}). */
    public static final String LIT_CCLISTPGM = "COCRDLIC";

    /** {@code LIT-CCLISTTRANID} - the credit-card list transaction id ({@code CCLI}). */
    public static final String LIT_CCLISTTRANID = "CCLI";

    /** {@code LIT-MENUPGM} - the main-menu program ({@code COMEN01C}). */
    public static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-MENUTRANID} - the main-menu transaction id ({@code CM00}). */
    public static final String LIT_MENUTRANID = "CM00";

    /** {@code LIT-MENUMAPSET} - the main-menu mapset ({@code COMEN01}). */
    public static final String LIT_MENUMAPSET = "COMEN01";

    /** {@code LIT-MENUMAP} - the main-menu map ({@code COMEN1A}). */
    public static final String LIT_MENUMAP = "COMEN1A";

    // ------------------------------------------------------------------------
    // Informational messages (COBOL WS-INFO-MSG 88-levels, L124-132)
    // ------------------------------------------------------------------------

    /**
     * {@code FOUND-CARDS-FOR-ACCOUNT} - shown when the requested card is found. The three
     * leading spaces are part of the original literal and are preserved verbatim
     * ({@code '   Displaying requested details'}).
     */
    public static final String FOUND_CARDS_FOR_ACCOUNT = "   Displaying requested details";

    /** {@code WS-PROMPT-FOR-INPUT} - initial prompt to gather the search criteria. */
    public static final String WS_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    // ------------------------------------------------------------------------
    // Return / error messages (COBOL WS-RETURN-MSG 88-levels, L134-156)
    // ------------------------------------------------------------------------

    /**
     * {@code WS-EXIT-MESSAGE} - set when PF3 is pressed to exit. The COBOL literal is
     * blank-padded to 75 bytes; only the significant text is retained here.
     */
    public static final String WS_EXIT_MESSAGE = "PF03 pressed.Exiting";

    /** {@code WS-PROMPT-FOR-ACCT} - the account filter was left blank. */
    public static final String WS_PROMPT_FOR_ACCT = "Account number not provided";

    /** {@code WS-PROMPT-FOR-CARD} - the card filter was left blank. */
    public static final String WS_PROMPT_FOR_CARD = "Card number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED} - neither account nor card filter supplied. */
    public static final String NO_SEARCH_CRITERIA_RECEIVED = "No input received";

    /**
     * {@code SEARCHED-ACCT-ZEROES} / {@code SEARCHED-ACCT-NOT-NUMERIC} - the account
     * validation message declared by the copybook 88-levels.
     *
     * <p><b>Parity note (documented quirk):</b> both 88-levels carry this exact literal, but
     * program {@code COCRDSLC} never actually {@code SET}s either condition. Its
     * {@code 2210-EDIT-ACCOUNT} paragraph instead {@code MOVE}s the inline literal held in
     * {@link #ACCT_FILTER_NOT_11_DIGITS} for the non-numeric case and reuses
     * {@link #WS_PROMPT_FOR_ACCT} for the blank/zeroes case. This constant is retained
     * verbatim for traceability (agent-prompt Phase 4) but is not emitted at runtime, so
     * runtime behavior is identical to the COBOL (AAP &sect;0.7.1, 100% parity including
     * quirks).</p>
     */
    public static final String SEARCHED_ACCT_MSG = "Account number must be a non zero 11 digit number";

    /**
     * {@code SEARCHED-CARD-NOT-NUMERIC} - the card validation message declared by the
     * copybook 88-level.
     *
     * <p><b>Parity note (documented quirk):</b> as with {@link #SEARCHED_ACCT_MSG}, this
     * 88-level literal is declared but never {@code SET}; {@code 2220-EDIT-CARD} moves the
     * inline literal held in {@link #CARD_FILTER_NOT_16_DIGITS} instead. Retained verbatim
     * for traceability.</p>
     */
    public static final String SEARCHED_CARD_MSG = "Card number if supplied must be a 16 digit number";

    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF} - alternate-index (by-account) read miss. */
    public static final String DID_NOT_FIND_ACCT_IN_CARDXREF = "Did not find this account in cards database";

    /** {@code DID-NOT-FIND-ACCTCARD-COMBO} - primary (by-card-number) read miss. */
    public static final String DID_NOT_FIND_ACCTCARD_COMBO = "Did not find cards for this search condition";

    /** {@code XREF-READ-ERROR} - generic card-file read error (COBOL {@code WHEN OTHER}). */
    public static final String XREF_READ_ERROR = "Error reading Card Data File";

    /**
     * Inline literal moved by {@code 2210-EDIT-ACCOUNT} for a non-numeric account filter
     * ({@code legacy/cbl/COCRDSLC.cbl} L669-671). This is the message actually emitted at
     * runtime (see the parity note on {@link #SEARCHED_ACCT_MSG}).
     */
    public static final String ACCT_FILTER_NOT_11_DIGITS =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Inline literal moved by {@code 2220-EDIT-CARD} for a non-numeric card filter
     * ({@code legacy/cbl/COCRDSLC.cbl} L707-709). This is the message actually emitted at
     * runtime (see the parity note on {@link #SEARCHED_CARD_MSG}).
     */
    public static final String CARD_FILTER_NOT_16_DIGITS =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * Abend text for the {@code WHEN OTHER} branch of {@code 0000-MAIN}
     * ({@code legacy/cbl/COCRDSLC.cbl} L384-386): an unexpected pseudo-conversational state.
     */
    public static final String UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    /** Fixed width of the account filter field ({@code CC-ACCT-ID PIC X(11)}). */
    private static final int ACCT_ID_WIDTH = 11;

    /** Fixed width of the card filter field ({@code CC-CARD-NUM PIC X(16)}). */
    private static final int CARD_NUM_WIDTH = 16;

    // ------------------------------------------------------------------------
    // Collaborators (constructor-injected, immutable)
    // ------------------------------------------------------------------------

    /**
     * Session-scoped conversational context (the COBOL {@code COCOM01Y} COMMAREA). Injected
     * as a session-scoped proxy so that per-user navigation and selection state survive
     * across requests, exactly as the CICS COMMAREA survived across pseudo-conversational
     * returns.
     */
    private final CardDemoContext context;

    /**
     * Spring Data repository over the {@code CARDDAT} store, replacing the VSAM
     * {@code EXEC CICS READ} file access of the original program.
     */
    private final CardRepository cardRepository;

    /**
     * Creates the service with its collaborators.
     *
     * @param context        the session-scoped conversational context (COMMAREA replacement);
     *                       must not be {@code null}
     * @param cardRepository the card repository (CARDDAT access); must not be {@code null}
     */
    public CardDetailService(CardDemoContext context, CardRepository cardRepository) {
        this.context = context;
        this.cardRepository = cardRepository;
    }

    // ------------------------------------------------------------------------
    // 0000-MAIN
    // ------------------------------------------------------------------------

    /**
     * Main entry point - migration of paragraph {@code 0000-MAIN}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 248-408).
     *
     * <p>Reproduces the pseudo-conversational state machine exactly:</p>
     * <ol>
     *   <li>Initialize the transient work area and edit flags (COBOL {@code INITIALIZE
     *       CC-WORK-AREA WS-MISC-STORAGE}, lines 256-258) and clear the return message
     *       ({@code SET WS-RETURN-MSG-OFF TO TRUE}, line 264).</li>
     *   <li>First-entry detection (lines 268-279): when there is no carried state
     *       ({@link CardDemoContext#isNew()}, the {@code EIBCALEN = 0} test) or the flow
     *       arrived fresh from the main menu ({@code CDEMO-FROM-PROGRAM = 'COMEN01C' AND NOT
     *       CDEMO-PGM-REENTER}), initialize the conversational context
     *       ({@link #initializeContext()}).</li>
     *   <li>PF-key validation (lines 293-303): only {@code ENTER} and {@code PF03} are valid;
     *       any other key defaults to {@code ENTER}. The raw {@code EIBAID}-to-{@link PfKey}
     *       mapping ({@code YYYY-STORE-PFKEY}) has already been performed by the controller,
     *       so the resolved {@link PfKey} is passed in.</li>
     *   <li>Dispatch ({@code EVALUATE TRUE}, lines 305-383):
     *     <ul>
     *       <li>{@code WHEN CCARD-AID-PFK03} &rarr; {@link #handleExit(CardWorkArea)} (the
     *           {@code XCTL} back to the caller or the main menu).</li>
     *       <li>{@code WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = 'COCRDLIC'} &rarr; the
     *           selection was already validated by the card-list screen, so copy the selected
     *           account/card onto the work area and read directly
     *           ({@link #readData(CardWorkArea, ProcessingState, COCRDSLForm)}).</li>
     *       <li>{@code WHEN CDEMO-PGM-ENTER} (any other origin) &rarr; show the empty search
     *           screen to gather criteria.</li>
     *       <li>{@code WHEN CDEMO-PGM-REENTER} &rarr; process the submitted inputs
     *           ({@link #processInputs(COCRDSLForm, CardWorkArea, ProcessingState)}); on an
     *           input error re-show the screen with the message, otherwise read and show.</li>
     *       <li>{@code WHEN OTHER} &rarr; the unexpected-data-scenario abend (line 384), a
     *           thrown {@link IllegalStateException} surfaced by the web-tier handler.</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>The trailing {@code IF INPUT-ERROR} guard of the COBOL (lines 387-393) that re-shows
     * the screen for an error that "slipped through" is subsumed here: every branch that can
     * set an input error already routes through
     * {@link #buildShowResult(COCRDSLForm, ProcessingState)}, which carries the error message.</p>
     *
     * @param form the bound card-detail form supplying the account/card search filters
     *             ({@code ACCTSIDI}/{@code CARDSIDI}); its display fields are populated on a
     *             successful read and its message fields on every screen re-display; must not
     *             be {@code null}
     * @param aid  the resolved attention identifier the operator pressed; {@code null} is
     *             tolerated and treated as {@link PfKey#OTHER} (which defaults to
     *             {@code ENTER})
     * @return the routing outcome: a redirect (PF3 exit) or a screen re-display carrying the
     *         appropriate informational or error message
     * @throws RecordNotFoundException when the requested card is not found (CICS
     *                                 {@code NOTFND})
     * @throws IllegalStateException   for the unexpected-data-scenario abend
     */
    public CardDetailResult mainEntry(COCRDSLForm form, PfKey aid) {
        // INITIALIZE CC-WORK-AREA / WS-MISC-STORAGE (lines 256-258); the transient state is
        // held in per-request locals so the singleton service stays thread-safe.
        CardWorkArea work = new CardWorkArea();
        ProcessingState state = new ProcessingState();
        // SET WS-RETURN-MSG-OFF TO TRUE (line 264): no return message yet.
        state.setReturnMsg(null);

        // First-entry detection (lines 268-279). Evaluate the from-menu test BEFORE any reset,
        // because initializeContext() clears the from-program.
        boolean firstEntry = context.isNew();
        boolean fromMenuEnter = LIT_MENUPGM.equals(context.getFromProgram())
                && !context.isProgramReenter();
        if (firstEntry || fromMenuEnter) {
            initializeContext();
        }

        // Validate the AID (lines 293-303): only ENTER and PF03 are honored; anything else
        // (including a null/unmapped key) is coerced to ENTER.
        PfKey pressed = (aid == null) ? PfKey.OTHER : aid;
        if (pressed != PfKey.ENTER && pressed != PfKey.PFK03) {
            pressed = PfKey.ENTER;
        }

        // EVALUATE TRUE (lines 305-383).
        if (pressed == PfKey.PFK03) {
            // WHEN CCARD-AID-PFK03: XCTL back to the caller or the main menu.
            return handleExit(work);
        }

        // Resolve the card number carried on the context by the card-list hand-off
        // (COBOL CDEMO-CARD-NUM). On the mainframe the 3270 card list guarantees this
        // selection before the XCTL, so the WHEN CDEMO-FROM-PROGRAM = LIT-CCLISTPGM arm
        // always had a valid PAN. In the web tier the session context is client-influenced
        // (a cold/bookmarked GET, a replayed or forged request, or a stale session can present
        // CDEMO-FROM-PROGRAM = COCRDLIC with no actual selection), so guard it: an absent
        // selection must NOT reach the keyed read (a null key would make
        // CardRepository.findById raise IllegalArgumentException -> HTTP 500, which no COBOL
        // path produces). See review finding #47.
        final String selectedCardNum = toFixedDigits(context.getCardNum(), CARD_NUM_WIDTH);
        if (context.isProgramEnter() && LIT_CCLISTPGM.equals(context.getFromProgram())
                && selectedCardNum != null) {
            // WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM (lines 337-347):
            // arriving from the card-list screen with an already-validated selection.
            state.setInputError(false);                             // SET INPUT-OK TO TRUE
            work.setAcctId(formatFixedDigits(                       // MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N
                    context.getAcctId(), ACCT_ID_WIDTH));
            work.setCardNum(selectedCardNum);                       // MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N
            readData(work, state, form);                            // PERFORM 9000-READ-DATA
            return buildShowResult(form, state);                    // PERFORM 1000-SEND-MAP
        }

        if (context.isProgramEnter()) {
            // WHEN CDEMO-PGM-ENTER (any other origin, lines 349-353), and the review-finding-#47
            // guard above (a from-list hop with no resolvable selection): show the search screen
            // so the operator can supply criteria - the source-equivalent prompt for "no
            // selection", never a keyed read on an absent key.
            return buildShowResult(form, state);                    // PERFORM 1000-SEND-MAP
        }

        if (context.isProgramReenter()) {
            // WHEN CDEMO-PGM-REENTER (lines 357-371): validate the submitted inputs.
            processInputs(form, work, state);                       // PERFORM 2000-PROCESS-INPUTS
            if (!state.isInputError()) {
                readData(work, state, form);                        // PERFORM 9000-READ-DATA
            }
            return buildShowResult(form, state);                    // PERFORM 1000-SEND-MAP
        }

        // WHEN OTHER (lines 373-382): unexpected pseudo-conversational state -> abend.
        throw new IllegalStateException(UNEXPECTED_DATA_SCENARIO);
    }

    /**
     * Handles the PF3 exit branch - the {@code WHEN CCARD-AID-PFK03} arm of {@code 0000-MAIN}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 307-335).
     *
     * <p>Resolves the hand-off target: the {@code to} transaction/program default to the
     * remembered {@code from} transaction/program, falling back to the main-menu
     * transaction/program ({@code CM00}/{@code COMEN01C}) when those are blank (COBOL
     * {@code LOW-VALUES OR SPACES}, lines 311-325). It then records this program as the new
     * {@code from} origin, marks the user type and program context for the target (COBOL
     * {@code SET CDEMO-USRTYP-USER}/{@code SET CDEMO-PGM-ENTER}, lines 326-327), and stores the
     * last mapset/map. The COBOL {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} (lines
     * 329-332) becomes the returned {@link RoutingAction#REDIRECT}, executed by the controller
     * using {@link CardDemoContext#getToProgram()}. The exit prompt {@link #WS_EXIT_MESSAGE}
     * ({@code 'PF03 pressed.Exiting'}) is carried on the result.</p>
     *
     * @param work the transient work area (unused by this branch beyond parity symmetry with
     *             the other dispatch arms)
     * @return a {@link RoutingAction#REDIRECT} outcome carrying the exit message
     */
    private CardDetailResult handleExit(CardWorkArea work) {
        // Lines 311-317: to-tranid <- from-tranid, or the menu tranid when from is blank.
        if (isBlankOrLowValues(context.getFromTranid())) {
            context.setToTranid(LIT_MENUTRANID);
        } else {
            context.setToTranid(context.getFromTranid());
        }
        // Lines 319-325: to-program <- from-program, or the menu program when from is blank.
        if (isBlankOrLowValues(context.getFromProgram())) {
            context.setToProgram(LIT_MENUPGM);
        } else {
            context.setToProgram(context.getFromProgram());
        }
        // Lines 326-333: record this program as the origin for the next hop and prime the
        // target's expected state (user type + fresh program-enter context + last map/mapset).
        context.setFromTranid(LIT_THISTRANID);
        context.setFromProgram(LIT_THISPGM);
        context.setUser();
        context.markEnter();
        context.setLastMapset(LIT_THISMAPSET);
        context.setLastMap(LIT_THISMAP);
        // Reference the work area so the symmetry with the other arms is explicit and the
        // parameter contract stays uniform across the dispatch helpers.
        work.setNextProg(LIT_THISPGM);
        return CardDetailResult.redirect(WS_EXIT_MESSAGE);
    }

    /**
     * Initializes the conversational context - the {@code INITIALIZE CARDDEMO-COMMAREA}
     * of {@code 0000-MAIN} ({@code legacy/cbl/COCRDSLC.cbl} lines 273-274).
     *
     * <p>Resets the navigation ({@code from}/{@code to} transaction and program), the
     * selection (account id/status, card number, customer id and names) and the last
     * map/mapset, then sets the program context to enter ({@link CardDemoContext#markEnter()})
     * and marks the context initialized so subsequent requests in the same session are treated
     * as re-entry.</p>
     *
     * <p><b>Documented deviation (AAP &sect;0.6.7):</b> the COBOL {@code INITIALIZE} also zeroes
     * {@code CDEMO-USER-ID}/{@code CDEMO-USER-TYPE}. Those are <em>not</em> cleared here because
     * the authenticated identity is owned by Spring Security, not by the COMMAREA; clearing
     * them would break the authentication continuity that RACF/CICS provided out-of-band on the
     * mainframe. This preserves the observable navigation behavior while respecting the modern
     * security model.</p>
     */
    private void initializeContext() {
        context.setFromTranid(null);
        context.setFromProgram(null);
        context.setToTranid(null);
        context.setToProgram(null);
        context.setAcctId(null);
        context.setAcctStatus(null);
        context.setCardNum(null);
        context.setCustId(null);
        context.setCustFirstName(null);
        context.setCustMiddleName(null);
        context.setCustLastName(null);
        context.setLastMap(null);
        context.setLastMapset(null);
        context.markEnter();
        context.markInitialized();
    }

    // ------------------------------------------------------------------------
    // 2000-PROCESS-INPUTS / 2200 / 2210 / 2220
    // ------------------------------------------------------------------------

    /**
     * Processes the submitted screen inputs - migration of paragraph
     * {@code 2000-PROCESS-INPUTS} ({@code legacy/cbl/COCRDSLC.cbl} lines 582-593).
     *
     * <p>The COBOL first performs {@code 2100-RECEIVE-MAP} (the CICS {@code RECEIVE MAP});
     * that terminal read is a presentation concern already performed by the controller (the
     * bound {@link COCRDSLForm} <em>is</em> the received map), so it is not repeated here. This
     * method then edits the map inputs ({@link #editMapInputs(COCRDSLForm, CardWorkArea,
     * ProcessingState)}) and copies the resulting return message onto the work area's error
     * buffer, and records the next program/mapset/map routing hints (COBOL lines 588-591).</p>
     *
     * @param form  the bound form supplying the account/card filters
     * @param work  the transient work area to populate
     * @param state the per-request edit state
     */
    private void processInputs(COCRDSLForm form, CardWorkArea work, ProcessingState state) {
        // PERFORM 2100-RECEIVE-MAP is the controller's RECEIVE MAP; skipped here.
        editMapInputs(form, work, state);                 // PERFORM 2200-EDIT-MAP-INPUTS
        work.setErrorMsg(state.getReturnMsg());           // MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        work.setNextProg(LIT_THISPGM);                    // MOVE LIT-THISPGM TO CCARD-NEXT-PROG
        work.setNextMapset(LIT_THISMAPSET);               // MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET
        work.setNextMap(LIT_THISMAP);                     // MOVE LIT-THISMAP TO CCARD-NEXT-MAP
    }

    /**
     * Edits the map inputs - migration of paragraph {@code 2200-EDIT-MAP-INPUTS}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 608-643).
     *
     * <p>Sets the optimistic starting state ({@code SET INPUT-OK}, both filters
     * {@code ISVALID}, lines 610-612), then normalizes each filter: a wildcard {@code '*'} or
     * spaces becomes {@code LOW-VALUES} (modelled as {@code null}), otherwise the raw field is
     * copied to the work area (lines 615-627). It then runs the individual field edits
     * ({@link #editAccount(CardWorkArea, ProcessingState)} and
     * {@link #editCard(CardWorkArea, ProcessingState)}) and finally applies the cross-field
     * rule: when <em>both</em> filters are blank it unconditionally sets
     * {@link #NO_SEARCH_CRITERIA_RECEIVED} ({@code 'No input received'}, lines 636-639),
     * overriding any per-field message.</p>
     *
     * @param form  the bound form supplying {@code ACCTSIDI}/{@code CARDSIDI}
     * @param work  the transient work area to populate ({@code CC-ACCT-ID}/{@code CC-CARD-NUM})
     * @param state the per-request edit state
     */
    private void editMapInputs(COCRDSLForm form, CardWorkArea work, ProcessingState state) {
        // Lines 610-612: optimistic starting state.
        state.setInputError(false);                        // SET INPUT-OK TO TRUE
        state.setCardFilterFlag(FilterFlag.ISVALID);       // SET FLG-CARDFILTER-ISVALID TO TRUE
        state.setAcctFilterFlag(FilterFlag.ISVALID);       // SET FLG-ACCTFILTER-ISVALID TO TRUE

        // Lines 615-620: account filter '*'/spaces -> LOW-VALUES (null), else copy.
        String acctFilter = form.getAcctsid();
        if (isWildcard(acctFilter) || isBlankOrLowValues(acctFilter)) {
            work.setAcctId(null);                          // MOVE LOW-VALUES TO CC-ACCT-ID
        } else {
            work.setAcctId(acctFilter);                    // MOVE ACCTSIDI TO CC-ACCT-ID
        }

        // Lines 622-627: card filter '*'/spaces -> LOW-VALUES (null), else copy.
        String cardFilter = form.getCardsid();
        if (isWildcard(cardFilter) || isBlankOrLowValues(cardFilter)) {
            work.setCardNum(null);                         // MOVE LOW-VALUES TO CC-CARD-NUM
        } else {
            work.setCardNum(cardFilter);                   // MOVE CARDSIDI TO CC-CARD-NUM
        }

        // Lines 630-634: individual field edits.
        editAccount(work, state);                          // PERFORM 2210-EDIT-ACCOUNT
        editCard(work, state);                             // PERFORM 2220-EDIT-CARD

        // Lines 636-639: cross-field edit - both blank means no criteria at all. The COBOL
        // SETs this unconditionally (no WS-RETURN-MSG-OFF guard), overriding any per-field
        // message, so the final message is 'No input received'.
        if (state.getAcctFilterFlag() == FilterFlag.BLANK
                && state.getCardFilterFlag() == FilterFlag.BLANK) {
            state.setReturnMsg(NO_SEARCH_CRITERIA_RECEIVED);
        }
    }

    /**
     * Edits the account filter - migration of paragraph {@code 2210-EDIT-ACCOUNT}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 647-681).
     *
     * <p>Starts pessimistic ({@code SET FLG-ACCTFILTER-NOT-OK}, line 648). If the value is not
     * supplied - {@code LOW-VALUES}/spaces (modelled as {@code null}/blank) or the numeric
     * redefinition equals zero (an all-zero 11-digit value) - the filter is flagged blank, the
     * input is marked in error, the "not provided" prompt is set <em>only if</em> no message is
     * already pending (COBOL {@code IF WS-RETURN-MSG-OFF}), and {@code CDEMO-ACCT-ID} is zeroed
     * (lines 652-664). If the value is present but not a clean 11-digit number, the non-numeric
     * inline message ({@link #ACCT_FILTER_NOT_11_DIGITS}) is set (again only if no message is
     * pending) and {@code CDEMO-ACCT-ID} is zeroed (lines 668-677). Otherwise the value is
     * valid and is moved to {@code CDEMO-ACCT-ID} with the filter marked valid (lines 678-680).</p>
     *
     * <p><b>Parity note:</b> the COBOL uses the inline literal {@link #ACCT_FILTER_NOT_11_DIGITS}
     * for the non-numeric case; the copybook 88-level {@link #SEARCHED_ACCT_MSG} is declared but
     * never {@code SET} by this program, and is therefore not emitted (see its Javadoc).</p>
     *
     * @param work  the transient work area holding {@code CC-ACCT-ID}
     * @param state the per-request edit state
     */
    private void editAccount(CardWorkArea work, ProcessingState state) {
        state.setAcctFilterFlag(FilterFlag.NOT_OK);        // SET FLG-ACCTFILTER-NOT-OK (line 648)

        String acctId = work.getAcctId();
        boolean blank = isBlankOrLowValues(acctId);        // CC-ACCT-ID = LOW-VALUES OR SPACES
        boolean elevenDigits = isFixedWidthNumeric(acctId, ACCT_ID_WIDTH);
        boolean zeros = elevenDigits && isAllZeros(acctId); // CC-ACCT-ID-N = ZEROS

        // Lines 651-664: not supplied.
        if (blank || zeros) {
            state.setInputError(true);                     // SET INPUT-ERROR
            state.setAcctFilterFlag(FilterFlag.BLANK);     // SET FLG-ACCTFILTER-BLANK
            if (state.isReturnMsgOff()) {                  // IF WS-RETURN-MSG-OFF
                state.setReturnMsg(WS_PROMPT_FOR_ACCT);    // SET WS-PROMPT-FOR-ACCT
            }
            context.setAcctId(0L);                         // MOVE ZEROES TO CDEMO-ACCT-ID
            return;                                        // GO TO 2210-EDIT-ACCOUNT-EXIT
        }

        // Lines 668-677: not numeric / not 11 digits.
        if (!elevenDigits) {                               // CC-ACCT-ID IS NOT NUMERIC
            state.setInputError(true);                     // SET INPUT-ERROR
            state.setAcctFilterFlag(FilterFlag.NOT_OK);    // SET FLG-ACCTFILTER-NOT-OK
            if (state.isReturnMsgOff()) {                  // IF WS-RETURN-MSG-OFF
                state.setReturnMsg(ACCT_FILTER_NOT_11_DIGITS);
            }
            context.setAcctId(0L);                         // MOVE ZERO TO CDEMO-ACCT-ID
            return;                                        // GO TO 2210-EDIT-ACCOUNT-EXIT
        }

        // Lines 678-680: valid.
        context.setAcctId(Long.parseLong(acctId));         // MOVE CC-ACCT-ID TO CDEMO-ACCT-ID
        state.setAcctFilterFlag(FilterFlag.ISVALID);       // SET FLG-ACCTFILTER-ISVALID
    }

    /**
     * Edits the card filter - migration of paragraph {@code 2220-EDIT-CARD}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 685-722).
     *
     * <p>Structurally identical to {@link #editAccount(CardWorkArea, ProcessingState)} but for
     * the 16-digit card number: a not-supplied value flags the filter blank, marks the input in
     * error, sets the "not provided" prompt ({@link #WS_PROMPT_FOR_CARD}) when no message is
     * pending, and zeroes {@code CDEMO-CARD-NUM} (lines 693-706); a present-but-not-16-digit
     * value sets the non-numeric inline message ({@link #CARD_FILTER_NOT_16_DIGITS}) and zeroes
     * {@code CDEMO-CARD-NUM} (lines 710-719); a valid value is moved to {@code CDEMO-CARD-NUM}
     * with the filter marked valid (lines 720-721).</p>
     *
     * <p><b>Mapping note:</b> {@code CDEMO-CARD-NUM} is {@code PIC 9(16)}. The Java context
     * stores it as a {@link String}; a valid selection becomes the 16-digit string, while the
     * COBOL {@code MOVE ZEROES}/{@code MOVE ZERO} (no selection) maps to {@code null}, which the
     * downstream display treats identically to the COBOL {@code CDEMO-CARD-NUM = 0} test.</p>
     *
     * @param work  the transient work area holding {@code CC-CARD-NUM}
     * @param state the per-request edit state
     */
    private void editCard(CardWorkArea work, ProcessingState state) {
        state.setCardFilterFlag(FilterFlag.NOT_OK);        // SET FLG-CARDFILTER-NOT-OK (line 688)

        String cardNum = work.getCardNum();
        boolean blank = isBlankOrLowValues(cardNum);       // CC-CARD-NUM = LOW-VALUES OR SPACES
        boolean sixteenDigits = isFixedWidthNumeric(cardNum, CARD_NUM_WIDTH);
        boolean zeros = sixteenDigits && isAllZeros(cardNum); // CC-CARD-NUM-N = ZEROS

        // Lines 693-706: not supplied.
        if (blank || zeros) {
            state.setInputError(true);                     // SET INPUT-ERROR
            state.setCardFilterFlag(FilterFlag.BLANK);     // SET FLG-CARDFILTER-BLANK
            if (state.isReturnMsgOff()) {                  // IF WS-RETURN-MSG-OFF
                state.setReturnMsg(WS_PROMPT_FOR_CARD);    // SET WS-PROMPT-FOR-CARD
            }
            context.setCardNum(null);                      // MOVE ZEROES TO CDEMO-CARD-NUM
            return;                                        // GO TO 2220-EDIT-CARD-EXIT
        }

        // Lines 710-719: not numeric / not 16 digits.
        if (!sixteenDigits) {                              // CC-CARD-NUM IS NOT NUMERIC
            state.setInputError(true);                     // SET INPUT-ERROR
            state.setCardFilterFlag(FilterFlag.NOT_OK);    // SET FLG-CARDFILTER-NOT-OK
            if (state.isReturnMsgOff()) {                  // IF WS-RETURN-MSG-OFF
                state.setReturnMsg(CARD_FILTER_NOT_16_DIGITS);
            }
            context.setCardNum(null);                      // MOVE ZERO TO CDEMO-CARD-NUM
            return;                                        // GO TO 2220-EDIT-CARD-EXIT
        }

        // Lines 720-721: valid.
        context.setCardNum(cardNum);                       // MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM
        state.setCardFilterFlag(FilterFlag.ISVALID);       // SET FLG-CARDFILTER-ISVALID
    }

    // ------------------------------------------------------------------------
    // 9000-READ-DATA / 9100-GETCARD-BYACCTCARD / 9150-GETCARD-BYACCT (AAP 0.6.2)
    // ------------------------------------------------------------------------

    /**
     * Reads the requested card - migration of paragraph {@code 9000-READ-DATA}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 726-734).
     *
     * <p>The COBOL orchestrator performs only {@code 9100-GETCARD-BYACCTCARD} (read by card
     * number); it does <em>not</em> perform {@code 9150-GETCARD-BYACCT}. That structure is
     * reproduced exactly here.</p>
     *
     * <p><b>Parity note (documented quirk):</b> paragraph {@code 9150-GETCARD-BYACCT} is defined
     * in the source but never performed by any control path, so
     * {@link #getCardByAccount(CardWorkArea, ProcessingState, COCRDSLForm)} is likewise never
     * reached at runtime. It is retained for 1:1 traceability (AAP &sect;0.6.10) and is not
     * invoked from this method, preserving the original (dead-code) behavior.</p>
     *
     * @param work  the transient work area holding the resolved {@code CC-CARD-NUM}
     * @param state the per-request read state
     * @param form  the bound form whose display fields are populated on a successful read
     * @throws RecordNotFoundException when the card is not found (CICS {@code NOTFND})
     */
    private void readData(CardWorkArea work, ProcessingState state, COCRDSLForm form) {
        getCardByAccountCard(work, state, form);           // PERFORM 9100-GETCARD-BYACCTCARD
    }

    /**
     * Reads the card by card number - migration of paragraph {@code 9100-GETCARD-BYACCTCARD}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 736-774).
     *
     * <p>The COBOL {@code EXEC CICS READ FILE(CARDDAT) RIDFLD(WS-CARD-RID-CARDNUM)} (a keyed VSAM
     * read on the primary key) becomes {@link CardRepository#findById(Object)} on
     * {@code CC-CARD-NUM}. The response is mapped exactly per the COBOL {@code EVALUATE
     * WS-RESP-CD}:</p>
     * <ul>
     *   <li>{@code WHEN NORMAL} &rarr; set {@link #FOUND_CARDS_FOR_ACCOUNT} and copy the card
     *       fields onto the form (the {@code IF FOUND-CARDS-FOR-ACCOUNT} MOVEs of
     *       {@code 1200-SETUP-SCREEN-VARS}, lines 474-487, co-located with the read because the
     *       entity is in hand here).</li>
     *   <li>{@code WHEN NOTFND} &rarr; flag the input in error, mark both filters
     *       {@code NOT-OK}, set {@link #DID_NOT_FIND_ACCTCARD_COMBO} when no message is pending
     *       ({@code IF WS-RETURN-MSG-OFF}), and raise {@link RecordNotFoundException}. The
     *       COBOL fell through to re-display the screen; the modern design surfaces the miss as
     *       a typed exception handled by the web-tier {@code GlobalExceptionHandler} (AAP
     *       &sect;0.6.5).</li>
     *   <li>{@code WHEN OTHER} (a genuine file error) &rarr; the COBOL built
     *       {@link #XREF_READ_ERROR}; here a Spring {@code DataAccessException} thrown by the
     *       repository propagates unchanged to the same central handler, which renders that
     *       message. It is deliberately not caught in this service.</li>
     * </ul>
     *
     * @param work  the transient work area holding {@code CC-CARD-NUM}
     * @param state the per-request read state
     * @param form  the bound form to populate on success
     * @throws RecordNotFoundException when the card is not found
     */
    private void getCardByAccountCard(CardWorkArea work, ProcessingState state, COCRDSLForm form) {
        // MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM; EXEC CICS READ FILE(CARDDAT) ... (lines 740-750).
        Optional<Card> found = cardRepository.findById(work.getCardNum());
        if (found.isPresent()) {
            // WHEN NORMAL (line 754).
            state.setInfoMsg(FOUND_CARDS_FOR_ACCOUNT);     // SET FOUND-CARDS-FOR-ACCOUNT
            populateCardData(form, found.get());           // 1200 IF FOUND-CARDS-FOR-ACCOUNT MOVEs
            return;
        }
        // WHEN NOTFND (lines 755-762).
        state.setInputError(true);                         // SET INPUT-ERROR
        state.setAcctFilterFlag(FilterFlag.NOT_OK);        // SET FLG-ACCTFILTER-NOT-OK
        state.setCardFilterFlag(FilterFlag.NOT_OK);        // SET FLG-CARDFILTER-NOT-OK
        if (state.isReturnMsgOff()) {                      // IF WS-RETURN-MSG-OFF
            state.setReturnMsg(DID_NOT_FIND_ACCTCARD_COMBO); // SET DID-NOT-FIND-ACCTCARD-COMBO
        }
        // ABEND-ROUTINE equivalent: surface the miss to the web-tier GlobalExceptionHandler.
        throw new RecordNotFoundException(DID_NOT_FIND_ACCTCARD_COMBO);
    }

    /**
     * Reads the card by account via the {@code CARDAIX} alternate index - migration of
     * paragraph {@code 9150-GETCARD-BYACCT} ({@code legacy/cbl/COCRDSLC.cbl} lines 779-810).
     *
     * <p><b>Dead-code parity note:</b> this paragraph is defined in the source but is
     * <em>never performed</em> by any control path (see
     * {@link #readData(CardWorkArea, ProcessingState, COCRDSLForm)}). It is migrated here solely
     * to satisfy the 100%-coverage traceability requirement (AAP &sect;0.6.10); it is not called
     * at runtime, exactly as in the COBOL.</p>
     *
     * <p>The COBOL {@code EXEC CICS READ FILE(CARDAIX) RIDFLD(WS-CARD-RID-ACCT-ID)} - a read over
     * the by-account alternate index - becomes {@link CardRepository#findByCardAcctId(Long)}. The
     * {@code EVALUATE WS-RESP-CD} arms map as: {@code NORMAL} &rarr; {@link #FOUND_CARDS_FOR_ACCOUNT}
     * and card-field population; {@code NOTFND} &rarr; input error, {@code FLG-ACCTFILTER-NOT-OK},
     * {@link #DID_NOT_FIND_ACCT_IN_CARDXREF} (set unconditionally here - the source has no
     * {@code WS-RETURN-MSG-OFF} guard on this arm, unlike {@code 9100}) then
     * {@link RecordNotFoundException}; a genuine file error propagates as a Spring
     * {@code DataAccessException}.</p>
     *
     * @param work  the transient work area holding {@code CC-ACCT-ID}
     * @param state the per-request read state
     * @param form  the bound form to populate on success
     * @throws RecordNotFoundException when no card exists for the account
     */
    private void getCardByAccount(CardWorkArea work, ProcessingState state, COCRDSLForm form) {
        // EXEC CICS READ FILE(CARDAIX) RIDFLD(WS-CARD-RID-ACCT-ID) ... (lines 783-791).
        List<Card> cards = cardRepository.findByCardAcctId(work.getAcctIdNumeric());
        if (!cards.isEmpty()) {
            // WHEN NORMAL (line 795).
            state.setInfoMsg(FOUND_CARDS_FOR_ACCOUNT);     // SET FOUND-CARDS-FOR-ACCOUNT
            populateCardData(form, cards.get(0));
            return;
        }
        // WHEN NOTFND (lines 796-799): message set unconditionally (no WS-RETURN-MSG-OFF guard).
        state.setInputError(true);                         // SET INPUT-ERROR
        state.setAcctFilterFlag(FilterFlag.NOT_OK);        // SET FLG-ACCTFILTER-NOT-OK
        state.setReturnMsg(DID_NOT_FIND_ACCT_IN_CARDXREF); // SET DID-NOT-FIND-ACCT-IN-CARDXREF
        throw new RecordNotFoundException(DID_NOT_FIND_ACCT_IN_CARDXREF);
    }

    /**
     * Copies the retrieved card's display fields onto the form - the {@code IF
     * FOUND-CARDS-FOR-ACCOUNT} MOVEs of {@code 1200-SETUP-SCREEN-VARS}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 474-487).
     *
     * <p>Maps {@code CARD-EMBOSSED-NAME} &rarr; {@code CRDNAMEO} and {@code CARD-ACTIVE-STATUS}
     * &rarr; {@code CRDSTCDO}. The COBOL split of {@code CARD-EXPIRAION-DATE} into
     * {@code CARD-EXPIRY-MONTH}/{@code CARD-EXPIRY-YEAR} (via {@code CARD-EXPIRAION-DATE-X}) is
     * reproduced from the entity's {@link java.time.LocalDate}: the month becomes a
     * two-digit {@code EXPMONO} and the year a four-digit {@code EXPYEARO}. A {@code null}
     * expiry date leaves both fields untouched (blank), matching a low-values date.</p>
     *
     * @param form the form to populate
     * @param card the card just read
     */
    private void populateCardData(COCRDSLForm form, Card card) {
        form.setCrdname(card.getCardEmbossedName());       // MOVE CARD-EMBOSSED-NAME TO CRDNAMEO
        form.setCrdstcd(card.getCardActiveStatus());       // MOVE CARD-ACTIVE-STATUS TO CRDSTCDO
        LocalDate expiry = card.getCardExpiraionDate();
        if (expiry != null) {
            form.setExpmon(String.format("%02d", expiry.getMonthValue())); // MOVE CARD-EXPIRY-MONTH
            form.setExpyear(String.format("%04d", expiry.getYear()));      // MOVE CARD-EXPIRY-YEAR
        }
    }

    // ------------------------------------------------------------------------
    // 1000/1200/1400 send-map (non-presentation state and message set-up only)
    // ------------------------------------------------------------------------

    /**
     * Prepares the screen re-display - the non-presentation portion of {@code 1000-SEND-MAP}
     * /{@code 1200-SETUP-SCREEN-VARS}/{@code 1400-SEND-SCREEN}
     * ({@code legacy/cbl/COCRDSLC.cbl} lines 457-497, 567).
     *
     * <p>This method performs only the parts of the send path that are <em>not</em> terminal
     * rendering (the actual BMS {@code SEND MAP}, colors, cursor and PF-key legend belong to the
     * controller):</p>
     * <ol>
     *   <li>Marks the pseudo-conversational context as re-entrant - the COBOL {@code SET
     *       CDEMO-PGM-REENTER TO TRUE} inside {@code 1400-SEND-SCREEN} (line 567) - so the next
     *       request is treated as a re-entry (AAP &sect;0.6.8).</li>
     *   <li>Echoes the resolved search key back onto the form (lines 462-472): the account is
     *       shown when {@code CDEMO-ACCT-ID} is non-zero and the card when {@code CDEMO-CARD-NUM}
     *       is non-zero, otherwise the field is blanked ({@code MOVE LOW-VALUES}).</li>
     *   <li>Defaults the informational line to {@link #WS_PROMPT_FOR_INPUT} when empty (the
     *       {@code IF WS-NO-INFO-MESSAGE} test, lines 490-492) and copies both message buffers
     *       onto the form ({@code ERRMSGO}/{@code INFOMSGO}, lines 494-497).</li>
     * </ol>
     *
     * <p>The outcome is a {@link RoutingAction#SHOW_SCREEN} result whose severity distinguishes
     * an error line (a failed edit or a message set by a read) from an informational line (the
     * "found" confirmation or the initial prompt).</p>
     *
     * @param form  the bound form to finalize for display
     * @param state the per-request state carrying the messages and error flag
     * @return the screen-display routing outcome
     */
    private CardDetailResult buildShowResult(COCRDSLForm form, ProcessingState state) {
        // 1400-SEND-SCREEN line 567: SET CDEMO-PGM-REENTER TO TRUE.
        context.markReenter();

        // 1200 echo (lines 458-472): show the resolved key unless it was zero/blank.
        Long ctxAcct = context.getAcctId();
        if (ctxAcct != null && ctxAcct != 0L) {
            form.setAcctsid(formatFixedDigits(ctxAcct, ACCT_ID_WIDTH)); // MOVE CC-ACCT-ID TO ACCTSIDO
        } else {
            form.setAcctsid(null);                                      // MOVE LOW-VALUES TO ACCTSIDO
        }
        String ctxCard = context.getCardNum();
        if (!isBlankOrLowValues(ctxCard)) {
            form.setCardsid(ctxCard);                                   // MOVE CC-CARD-NUM TO CARDSIDO
        } else {
            form.setCardsid(null);                                      // MOVE LOW-VALUES TO CARDSIDO
        }

        // 1200 message set-up (lines 490-497): default the info line to the prompt when empty.
        if (isBlankOrLowValues(state.getInfoMsg())) {                   // IF WS-NO-INFO-MESSAGE
            state.setInfoMsg(WS_PROMPT_FOR_INPUT);                      // SET WS-PROMPT-FOR-INPUT
        }
        form.setErrmsg(state.getReturnMsg());                          // MOVE WS-RETURN-MSG TO ERRMSGO
        form.setInfomsg(state.getInfoMsg());                           // MOVE WS-INFO-MSG TO INFOMSGO

        // 1000-SEND-MAP always re-shows the same map; the severity drives the error vs. info line.
        if (state.isInputError()) {
            return CardDetailResult.error(state.getReturnMsg());
        }
        return CardDetailResult.information(state.getInfoMsg());
    }

    // ------------------------------------------------------------------------
    // Private helpers (COBOL inline field tests)
    // ------------------------------------------------------------------------

    /**
     * Tests whether a field is unset in COBOL terms - {@code LOW-VALUES}, {@code SPACES}, empty
     * or absent. {@code LOW-VALUES} bytes are modelled as the NUL character, so a value made up
     * only of spaces and/or NULs (or {@code null}/empty) is treated as blank.
     *
     * @param value the value to test
     * @return {@code true} when the value carries no meaningful content
     */
    private static boolean isBlankOrLowValues(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests for the wildcard filter {@code '*'} (the COBOL {@code IF ACCTSIDI = '*'} /
     * {@code CARDSIDI = '*'} tests, lines 615/622). Trailing spaces are ignored so a padded
     * {@code '*'} field still matches.
     *
     * @param value the filter value
     * @return {@code true} when the significant content is a single asterisk
     */
    private static boolean isWildcard(String value) {
        return value != null && "*".equals(value.stripTrailing());
    }

    /**
     * Tests the COBOL {@code IS NUMERIC} class condition for a fixed-width field: the value must
     * be present, exactly {@code width} characters long, and composed entirely of digits. A
     * shorter value or one padded with spaces (a partially typed field) is therefore
     * <em>not</em> numeric, matching the mainframe field-class test.
     *
     * @param value the value to test
     * @param width the required fixed width ({@code PIC 9(width)})
     * @return {@code true} when the value is exactly {@code width} digits
     */
    private static boolean isFixedWidthNumeric(String value, int width) {
        return value != null && value.length() == width && isAllDigits(value);
    }

    /**
     * Tests the COBOL {@code = ZEROS} condition: a non-empty value made up entirely of the
     * character {@code '0'}.
     *
     * @param value the value to test
     * @return {@code true} when every character is {@code '0'}
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
     * Returns {@code true} when the value is non-empty and every character is a digit.
     *
     * @param value the value to test
     * @return {@code true} when the value is all digits
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
     * Reproduces the COBOL {@code MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N} onto a fixed-width
     * numeric picture: an all-digit value shorter than {@code width} is left-padded with
     * {@code '0'} (numeric right-justification with zero fill), otherwise it is returned as-is.
     * A {@code null}/blank value yields {@code null} (no selection).
     *
     * @param value the source value (the selected card number carried in the context)
     * @param width the target fixed width
     * @return the width-normalized value, or {@code null} when there is no value
     */
    private static String toFixedDigits(String value, int width) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isAllDigits(trimmed) && trimmed.length() < width) {
            StringBuilder padded = new StringBuilder(width);
            for (int i = trimmed.length(); i < width; i++) {
                padded.append('0');
            }
            return padded.append(trimmed).toString();
        }
        return trimmed;
    }

    /**
     * Reproduces the COBOL {@code MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N} display formatting: a
     * numeric value rendered as a zero-filled, {@code width}-digit string (the fixed-width
     * {@code PIC 9(width)} representation shown on the map).
     *
     * @param value the numeric value ({@code null} is treated as zero)
     * @param width the target fixed width
     * @return the zero-padded fixed-width string
     */
    private static String formatFixedDigits(Long value, int width) {
        long resolved = (value == null) ? 0L : value;
        return String.format("%0" + width + "d", resolved);
    }

    // ------------------------------------------------------------------------
    // Nested types
    // ------------------------------------------------------------------------

    /**
     * The card-detail filter-edit flag - the Java form of the COBOL {@code WS-EDIT-ACCT-FLAG}
     * and {@code WS-EDIT-CARD-FLAG} 88-levels ({@code legacy/cbl/COCRDSLC.cbl} lines 100-118).
     *
     * <ul>
     *   <li>{@link #NOT_OK} - {@code FLG-ACCTFILTER-NOT-OK} / {@code FLG-CARDFILTER-NOT-OK}: the
     *       filter is invalid (the pessimistic starting state and the not-numeric outcome).</li>
     *   <li>{@link #ISVALID} - {@code FLG-ACCTFILTER-ISVALID} / {@code FLG-CARDFILTER-ISVALID}:
     *       the filter holds a well-formed value.</li>
     *   <li>{@link #BLANK} - {@code FLG-ACCTFILTER-BLANK} / {@code FLG-CARDFILTER-BLANK}: the
     *       filter was left empty; used by the cross-field "no input received" rule.</li>
     * </ul>
     */
    private enum FilterFlag {
        /** The filter is not valid ({@code FLG-*-NOT-OK}). */
        NOT_OK,
        /** The filter holds a valid value ({@code FLG-*-ISVALID}). */
        ISVALID,
        /** The filter was left blank ({@code FLG-*-BLANK}). */
        BLANK
    }

    /**
     * Per-request edit/read state - the Java form of the transient {@code WS-MISC-STORAGE} flags
     * and message buffers of {@code COCRDSLC} ({@code legacy/cbl/COCRDSLC.cbl} lines 96-158).
     *
     * <p>A fresh instance is created for every {@link #mainEntry(COCRDSLForm, PfKey)} call and
     * threaded through the paragraph methods, so the singleton {@code @Service} never holds
     * mutable request state and remains thread-safe (AAP &sect;0.6.8). It carries the input-error
     * flag ({@code WS-INPUT-FLAG}), the two filter flags, the informational message
     * ({@code WS-INFO-MSG}), the return/error message ({@code WS-RETURN-MSG}) and a "card found"
     * marker ({@code FOUND-CARDS-FOR-ACCOUNT}).</p>
     */
    private static final class ProcessingState {

        /** {@code WS-INPUT-FLAG}: {@code INPUT-ERROR} when {@code true}, else {@code INPUT-OK}. */
        private boolean inputError;

        /** {@code WS-EDIT-ACCT-FLAG}: the account-filter edit outcome. */
        private FilterFlag acctFilterFlag = FilterFlag.NOT_OK;

        /** {@code WS-EDIT-CARD-FLAG}: the card-filter edit outcome. */
        private FilterFlag cardFilterFlag = FilterFlag.NOT_OK;

        /** {@code WS-RETURN-MSG}: the error line ({@code null}/blank means {@code WS-RETURN-MSG-OFF}). */
        private String returnMsg;

        /** {@code WS-INFO-MSG}: the informational line. */
        private String infoMsg;

        boolean isInputError() {
            return inputError;
        }

        void setInputError(boolean inputError) {
            this.inputError = inputError;
        }

        FilterFlag getAcctFilterFlag() {
            return acctFilterFlag;
        }

        void setAcctFilterFlag(FilterFlag acctFilterFlag) {
            this.acctFilterFlag = acctFilterFlag;
        }

        FilterFlag getCardFilterFlag() {
            return cardFilterFlag;
        }

        void setCardFilterFlag(FilterFlag cardFilterFlag) {
            this.cardFilterFlag = cardFilterFlag;
        }

        String getReturnMsg() {
            return returnMsg;
        }

        void setReturnMsg(String returnMsg) {
            this.returnMsg = returnMsg;
        }

        String getInfoMsg() {
            return infoMsg;
        }

        void setInfoMsg(String infoMsg) {
            this.infoMsg = infoMsg;
        }

        /**
         * The COBOL {@code WS-RETURN-MSG-OFF} 88-level test ({@code VALUE SPACES}): {@code true}
         * when no error message is pending.
         *
         * @return {@code true} when the return message is absent or blank
         */
        boolean isReturnMsgOff() {
            return isBlankOrLowValues(returnMsg);
        }
    }

    /**
     * The routing action a {@link CardDetailResult} carries - the Java form of the COBOL
     * terminal outcome of {@code 0000-MAIN}: either an {@code EXEC CICS XCTL} hand-off to
     * another transaction, or a re-display of this transaction's own screen.
     */
    public enum RoutingAction {
        /** Hand off to another transaction ({@code EXEC CICS XCTL}); the controller redirects. */
        REDIRECT,
        /** Re-display this transaction's screen ({@code 1000-SEND-MAP}). */
        SHOW_SCREEN
    }

    /**
     * The severity of the message a {@link CardDetailResult} carries, so the controller can
     * route it to the correct 3270 field - the error line ({@code ERRMSGO}) or the
     * informational line ({@code INFOMSGO}).
     */
    public enum MessageSeverity {
        /** No message (used with a {@link RoutingAction#REDIRECT}). */
        NONE,
        /** An error message, shown on the error line ({@code WS-RETURN-MSG}). */
        ERROR,
        /** An informational message, shown on the info line ({@code WS-INFO-MSG}). */
        INFORMATION
    }

    /**
     * Immutable outcome of {@link CardDetailService#mainEntry(COCRDSLForm, PfKey)} describing how
     * the web tier should proceed. It carries the routing action, the message text and the
     * message severity, decoupling this business service from any presentation concern.
     *
     * @param action   the routing action; never {@code null}
     * @param message  the message text; normalized to an empty string when {@code null}
     * @param severity the message severity; normalized to {@link MessageSeverity#NONE} when
     *                 {@code null}
     */
    public record CardDetailResult(RoutingAction action, String message, MessageSeverity severity) {

        /**
         * Canonical constructor enforcing the invariants: a non-null action, and non-null
         * message/severity (normalized rather than rejected so callers may pass {@code null}).
         */
        public CardDetailResult {
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            if (message == null) {
                message = "";
            }
            if (severity == null) {
                severity = MessageSeverity.NONE;
            }
        }

        /**
         * Creates a redirect outcome (the COBOL {@code XCTL} hand-off) carrying an informational
         * exit message.
         *
         * @param message the message to carry (e.g. {@link CardDetailService#WS_EXIT_MESSAGE})
         * @return a {@link RoutingAction#REDIRECT} result
         */
        public static CardDetailResult redirect(String message) {
            return new CardDetailResult(RoutingAction.REDIRECT, message, MessageSeverity.NONE);
        }

        /**
         * Creates a screen re-display outcome carrying an error message (shown on the error
         * line).
         *
         * @param message the error text ({@code WS-RETURN-MSG})
         * @return a {@link RoutingAction#SHOW_SCREEN} result with {@link MessageSeverity#ERROR}
         */
        public static CardDetailResult error(String message) {
            return new CardDetailResult(RoutingAction.SHOW_SCREEN, message, MessageSeverity.ERROR);
        }

        /**
         * Creates a screen re-display outcome carrying an informational message (shown on the
         * info line).
         *
         * @param message the informational text ({@code WS-INFO-MSG})
         * @return a {@link RoutingAction#SHOW_SCREEN} result with {@link MessageSeverity#INFORMATION}
         */
        public static CardDetailResult information(String message) {
            return new CardDetailResult(RoutingAction.SHOW_SCREEN, message, MessageSeverity.INFORMATION);
        }
    }
}

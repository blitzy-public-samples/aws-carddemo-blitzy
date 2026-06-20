/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.service.online;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.CardUpdateScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online service that reproduces the COBOL CICS program <strong>COCRDUPC</strong> (transaction
 * {@code CCUP}, &quot;Card Update&quot;) with 100% behavioral parity.
 *
 * <p>COCRDUPC is a <em>STYLE&nbsp;A</em> numbered-paragraph, pseudo-conversational program that
 * implements a <strong>multi-step confirm-then-save state machine</strong> with
 * <strong>optimistic-update</strong> semantics: the row is read for display, the operator edits the
 * mutable fields, the program validates and asks for confirmation, and only when the operator
 * confirms (PF5) is the row re-read inside the update transaction, compared field-by-field against
 * the originally fetched snapshot, and rewritten. This mirrors the COBOL {@code READ ... UPDATE}
 * &rarr; {@code 9300-CHECK-CHANGE-IN-REC} &rarr; {@code REWRITE} cycle and the {@code SYNCPOINT
 * ROLLBACK}-on-failure behavior, which is realized here through Spring's declarative transaction
 * management on {@link #processCardUpdate}.
 *
 * <h2>State machine ({@code CCUP-CHANGE-ACTION})</h2>
 *
 * <ul>
 *   <li>{@link #DETAILS_NOT_FETCHED} &mdash; keys not yet resolved; prompt for account &amp; card.
 *   <li>{@link #SHOW_DETAILS} &mdash; card details fetched and displayed for editing.
 *   <li>{@link #CHANGES_NOT_OK} &mdash; field-level edit error(s) present; redisplay with message.
 *   <li>{@link #CHANGES_OK_NOT_CONFIRMED} &mdash; edits validated; prompt to press PF5 to save.
 *   <li>{@link #CHANGES_OKAYED_AND_DONE} &mdash; rewrite committed successfully.
 *   <li>{@link #CHANGES_OKAYED_LOCK_ERROR} &mdash; could not lock the row for update.
 *   <li>{@link #CHANGES_OKAYED_BUT_FAILED} &mdash; locked, but the rewrite failed.
 * </ul>
 *
 * <h2>COBOL paragraph &rarr; Java method traceability</h2>
 *
 * <table border="1">
 *   <caption>Control-flow mapping (perform/branch order preserved)</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td><td>{@link #processCardUpdate}</td></tr>
 *   <tr><td>{@code 1100-RECEIVE-MAP}</td><td>{@link #receiveMap}</td></tr>
 *   <tr><td>{@code 1200-EDIT-MAP-INPUTS}</td><td>{@link #editMapInputs}</td></tr>
 *   <tr><td>{@code 1210-EDIT-ACCOUNT}</td><td>{@link #editAccount}</td></tr>
 *   <tr><td>{@code 1220-EDIT-CARD}</td><td>{@link #editCard}</td></tr>
 *   <tr><td>{@code 1230-EDIT-NAME}</td><td>{@link #editName}</td></tr>
 *   <tr><td>{@code 1240-EDIT-CARDSTATUS}</td><td>{@link #editCardStatus}</td></tr>
 *   <tr><td>{@code 1250-EDIT-EXPIRY-MON}</td><td>{@link #editExpiryMonth}</td></tr>
 *   <tr><td>{@code 1260-EDIT-EXPIRY-YEAR}</td><td>{@link #editExpiryYear}</td></tr>
 *   <tr><td>{@code 2000-DECIDE-ACTION}</td><td>{@link #decideAction}</td></tr>
 *   <tr><td>{@code 3000/3100/3200/3250-SEND-MAP}</td>
 *       <td>{@link #sendMap}, {@link #populateHeader}, {@link #setupScreenVars}, {@link #setupInfoMsg}</td></tr>
 *   <tr><td>{@code 9000-READ-DATA}</td><td>{@link #readData}</td></tr>
 *   <tr><td>{@code 9100-GETCARD-BYACCTCARD}</td><td>{@link #getCardByAcctCard}</td></tr>
 *   <tr><td>{@code 9200-WRITE-PROCESSING}</td><td>{@link #writeProcessing}</td></tr>
 *   <tr><td>{@code 9300-CHECK-CHANGE-IN-REC}</td><td>{@link #checkChangeInRec}</td></tr>
 * </table>
 *
 * <p>The bean is a stateless singleton: it holds no mutable instance fields. All per-invocation
 * working values live in a method-local {@link WorkState}, while the cross-turn state machine flag
 * and the originally fetched (&quot;OLD&quot;) snapshot are carried on the {@link CardUpdateScreen}
 * (mirroring the COBOL {@code WS-THIS-PROGCOMMAREA} that travels in the CICS COMMAREA).
 */
@Service
public class CardUpdateService {

  // ---------------------------------------------------------------------------------------------
  // Program / transaction / routing literals (<- COCRDUPC WS-LITERALS)
  // ---------------------------------------------------------------------------------------------

  /** This program's transaction id ({@code LIT-THISTRANID}). */
  static final String TRAN_ID = "CCUP";

  /** This program's name ({@code LIT-THISPGM}). */
  static final String PGM_NAME = "COCRDUPC";

  /** This program's mapset, trimmed ({@code LIT-THISMAPSET} = {@code 'COCRDUP '}). */
  static final String LIT_THIS_MAPSET = "COCRDUP";

  /** This program's map ({@code LIT-THISMAP}). */
  static final String LIT_THIS_MAP = "CCRDUPA";

  /** Card-list program ({@code LIT-CCLISTPGM}) &mdash; the typical caller. */
  static final String LIT_CARD_LIST_PGM = "COCRDLIC";

  /** Card-list mapset, trimmed ({@code LIT-CCLISTMAPSET}). */
  static final String LIT_CARD_LIST_MAPSET = "COCRDLI";

  /** Main-menu program ({@code LIT-MENUPGM}). */
  static final String LIT_MENU_PGM = "COMEN01C";

  /** Main-menu transaction id ({@code LIT-MENUTRANID}). */
  static final String LIT_MENU_TRAN = "CM00";

  /** Card file name used for {@link IoStatusException} reporting ({@code LIT-CARDFILENAME}). */
  static final String LIT_CARD_FILE_NAME = "CARDDAT";

  /** Screen header title line 1 (mirror of {@code CCDA-TITLE01}). */
  static final String TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

  /** Screen header title line 2 (mirror of {@code CCDA-TITLE02}). */
  static final String TITLE_LINE_2 = "              CardDemo                  ";

  /** Date mask {@code MM/DD/YY} used for the header current-date field. */
  static final String DATE_MASK = "MM/dd/yy";

  /** Time mask {@code HH:MM:SS} used for the header current-time field. */
  static final String TIME_MASK = "HH:mm:ss";

  /** Function-key legend line 1 (BMS {@code FKEYS} INITIAL). */
  static final String FKEYS_LINE = "ENTER=Process F3=Exit";

  /** Function-key legend line 2 (BMS {@code FKEYSC} INITIAL). */
  static final String FKEYSC_LINE = "F5=Save F12=Cancel";

  // ---------------------------------------------------------------------------------------------
  // State machine values (<- CCUP-CHANGE-ACTION 88-levels). Stored as canonical strings on the
  // screen carrier. DETAILS-NOT-FETCHED corresponds to LOW-VALUES/SPACES (null/blank carrier).
  // ---------------------------------------------------------------------------------------------

  /** {@code CCUP-DETAILS-NOT-FETCHED} (LOW-VALUES / SPACES). */
  static final String DETAILS_NOT_FETCHED = "DETAILS-NOT-FETCHED";

  /** {@code CCUP-SHOW-DETAILS} ('S'). */
  static final String SHOW_DETAILS = "SHOW-DETAILS";

  /** {@code CCUP-CHANGES-NOT-OK} ('E'). */
  static final String CHANGES_NOT_OK = "CHANGES-NOT-OK";

  /** {@code CCUP-CHANGES-OK-NOT-CONFIRMED} ('N'). */
  static final String CHANGES_OK_NOT_CONFIRMED = "CHANGES-OK-NOT-CONFIRMED";

  /** {@code CCUP-CHANGES-OKAYED-AND-DONE} ('C'). */
  static final String CHANGES_OKAYED_AND_DONE = "CHANGES-OKAYED-AND-DONE";

  /** {@code CCUP-CHANGES-OKAYED-LOCK-ERROR} ('L'). */
  static final String CHANGES_OKAYED_LOCK_ERROR = "CHANGES-OKAYED-LOCK-ERROR";

  /** {@code CCUP-CHANGES-OKAYED-BUT-FAILED} ('F'). */
  static final String CHANGES_OKAYED_BUT_FAILED = "CHANGES-OKAYED-BUT-FAILED";

  // ---------------------------------------------------------------------------------------------
  // Byte-for-byte message literals (<- COCRDUPC working storage). Field-edit / not-found messages.
  // ---------------------------------------------------------------------------------------------

  /** {@code WS-PROMPT-FOR-ACCT}. */
  static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

  /** Account filter not an 11-digit number (direct MOVE literal in {@code 1210-EDIT-ACCOUNT}). */
  static final String MSG_ACCT_NOT_NUMERIC = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

  /** {@code WS-PROMPT-FOR-CARD}. */
  static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

  /** Card filter not a 16-digit number (direct MOVE literal in {@code 1220-EDIT-CARD}). */
  static final String MSG_CARD_NOT_NUMERIC = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

  /** {@code WS-PROMPT-FOR-NAME}. */
  static final String MSG_PROMPT_FOR_NAME = "Card name not provided";

  /** {@code WS-NAME-MUST-BE-ALPHA}. */
  static final String MSG_NAME_MUST_BE_ALPHA = "Card name can only contain alphabets and spaces";

  /** {@code CARD-STATUS-MUST-BE-YES-NO}. */
  static final String MSG_CARD_STATUS_YES_NO = "Card Active Status must be Y or N";

  /** {@code CARD-EXPIRY-MONTH-NOT-VALID}. */
  static final String MSG_EXPIRY_MONTH_NOT_VALID = "Card expiry month must be between 1 and 12";

  /** {@code CARD-EXPIRY-YEAR-NOT-VALID}. */
  static final String MSG_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

  /** {@code DID-NOT-FIND-ACCTCARD-COMBO}. */
  static final String MSG_DID_NOT_FIND_ACCTCARD = "Did not find cards for this search condition";

  /** {@code COULD-NOT-LOCK-FOR-UPDATE}. */
  static final String MSG_COULD_NOT_LOCK = "Could not lock record for update";

  /** {@code DATA-WAS-CHANGED-BEFORE-UPDATE}. */
  static final String MSG_DATA_WAS_CHANGED = "Record changed by some one else. Please review";

  /** {@code LOCKED-BUT-UPDATE-FAILED}. */
  static final String MSG_LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

  /** {@code NO-CHANGES-DETECTED}. */
  static final String MSG_NO_CHANGES_DETECTED =
      "No change detected with respect to values fetched.";

  /** {@code 2000-DECIDE-ACTION} {@code WHEN OTHER} abend reason ({@code '0001'}). */
  static final String MSG_UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

  // ---------------------------------------------------------------------------------------------
  // Byte-for-byte information messages (<- WS-INFO-MSG 88-levels), selected by state in 3250.
  // ---------------------------------------------------------------------------------------------

  /** {@code PROMPT-FOR-SEARCH-KEYS}. */
  static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

  /** {@code FOUND-CARDS-FOR-ACCOUNT}. */
  static final String INFO_FOUND_CARDS = "Details of selected card shown above";

  /** {@code PROMPT-FOR-CHANGES}. */
  static final String INFO_PROMPT_FOR_CHANGES = "Update card details presented above.";

  /** {@code PROMPT-FOR-CONFIRMATION}. */
  static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

  /** {@code CONFIRM-UPDATE-SUCCESS}. */
  static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

  /** {@code INFORM-FAILURE}. */
  static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

  // ---------------------------------------------------------------------------------------------
  // Validation bounds (<- VALID-MONTH / VALID-YEAR 88-levels).
  // ---------------------------------------------------------------------------------------------

  private static final int MONTH_MIN = 1;
  private static final int MONTH_MAX = 12;
  private static final int YEAR_MIN = 1950;
  private static final int YEAR_MAX = 2099;

  /** Fixed COBOL field widths used for fixed-width parity comparisons. */
  private static final int W_NAME = 50;

  private static final int W_CVV = 3;
  private static final int W_YEAR = 4;
  private static final int W_MON = 2;
  private static final int W_DAY = 2;
  private static final int W_STATUS = 1;
  private static final int W_ACCT = 11;
  private static final int W_CARD = 16;

  /** Sole collaborator: the card data store ({@code CARDDAT} VSAM KSDS &rarr; JPA repository). */
  private final CardRepository cardRepository;

  /**
   * Creates the service with its required collaborator.
   *
   * @param cardRepository repository for the {@code card} table (never {@code null})
   */
  public CardUpdateService(CardRepository cardRepository) {
    this.cardRepository = cardRepository;
  }

  // ===============================================================================================
  // 0000-MAIN  (top of PROCEDURE DIVISION)
  // ===============================================================================================

  /**
   * Entry point reproducing {@code 0000-MAIN}: validates the attention id (AID), dispatches to the
   * correct pseudo-conversational branch, and either returns the next program to transfer control
   * to (XCTL parity) or {@code null} to redisplay the {@code CCUP} screen.
   *
   * <p>The method is {@link Transactional} so that the {@code READ ... UPDATE} &rarr; {@code
   * REWRITE} cycle performed under PF5 confirmation participates in a single transaction; an
   * unexpected data-access failure that escapes (e.g. {@link IoStatusException}) triggers a
   * rollback, matching the COBOL {@code SYNCPOINT ROLLBACK} / abend posture.
   *
   * @param screen the {@code CCUP} screen contract carrying inputs, the cross-turn state flag and
   *     the originally fetched (OLD) snapshot; must not be {@code null}
   * @param commarea the navigation / session COMMAREA; must not be {@code null}
   * @param aid the operator's attention id (ENTER / PF3 / PF5 / PF12 / &hellip;); a {@code null}
   *     value is treated as ENTER
   * @return the program name to XCTL to when leaving the screen, or {@code null} to redisplay
   */
  @Transactional
  public String processCardUpdate(
      CardUpdateScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // Ensure error message is cleared at the start of each turn (SET WS-RETURN-MSG-OFF).
    final WorkState ws = new WorkState();
    CardWorkArea.Aid effectiveAid = (aid == null) ? CardWorkArea.Aid.ENTER : aid;
    String state = currentState(screen);

    // ---- Remap/validate the AID (YYYY-STORE-PFKEY + AID validity check) ----
    boolean pfkValid =
        effectiveAid == CardWorkArea.Aid.ENTER
            || effectiveAid == CardWorkArea.Aid.PFK03
            || (effectiveAid == CardWorkArea.Aid.PFK05 && CHANGES_OK_NOT_CONFIRMED.equals(state))
            || (effectiveAid == CardWorkArea.Aid.PFK12 && !DETAILS_NOT_FETCHED.equals(state));
    if (!pfkValid) {
      // PFK-INVALID -> behave as ENTER (SET CCARD-AID-ENTER TO TRUE).
      effectiveAid = CardWorkArea.Aid.ENTER;
    }

    final boolean fromCardList = LIT_CARD_LIST_PGM.equals(trim(commarea.getFromProgram()));
    final boolean fromMenu = LIT_MENU_PGM.equals(trim(commarea.getFromProgram()));
    final boolean lastWasCardList = LIT_CARD_LIST_MAPSET.equals(trim(commarea.getLastMapset()));

    // ===== EVALUATE TRUE (dispatch) =====

    // (1) PF3 exit, or "done / failed" while having arrived from the card-list screen -> XCTL out.
    if (effectiveAid == CardWorkArea.Aid.PFK03
        || (CHANGES_OKAYED_AND_DONE.equals(state) && lastWasCardList)
        || (isChangesFailed(state) && lastWasCardList)) {
      return exitToCaller(commarea, lastWasCardList);
    }

    // (2) Arrived from the card-list screen (keys already known) OR cancelling from it ->
    // fetch+show.
    if ((commarea.isPgmEnter() && fromCardList)
        || (effectiveAid == CardWorkArea.Aid.PFK12 && fromCardList)) {
      commarea.setPgmReenter();
      ws.acctFilterValid = true;
      ws.cardFilterValid = true;
      ws.acctIdNum = nz(commarea.getAcctId());
      ws.cardIdNum = nz(commarea.getCardNum());
      ws.cardKey = toCardKey(ws.cardIdNum);
      readData(screen, ws);
      // COBOL sets SHOW-DETAILS unconditionally here (not gated on the read result).
      state = SHOW_DETAILS;
      screen.setUpdateState(state);
      sendMap(screen, commarea, ws, state);
      return null;
    }

    // (3) Fresh entry from the menu (or first dispatch) -> prompt for the search keys.
    if ((DETAILS_NOT_FETCHED.equals(state) && commarea.isPgmEnter())
        || (fromMenu && !commarea.isPgmReenter())) {
      resetScreenState(screen);
      state = DETAILS_NOT_FETCHED;
      screen.setUpdateState(state);
      sendMap(screen, commarea, ws, state);
      commarea.setPgmReenter();
      return null;
    }

    // (4) Update finished (committed or failed) on a standalone entry -> reset keys, prompt afresh.
    if (CHANGES_OKAYED_AND_DONE.equals(state) || isChangesFailed(state)) {
      resetScreenState(screen);
      commarea.setAcctId(0L);
      commarea.setCardNum(0L);
      commarea.setPgmEnter();
      state = DETAILS_NOT_FETCHED;
      screen.setUpdateState(state);
      sendMap(screen, commarea, ws, state);
      commarea.setPgmReenter();
      return null;
    }

    // (5) WHEN OTHER -> the card data has been presented; process the operator's inputs.
    receiveMap(screen, ws);
    state = editMapInputs(screen, ws, state);
    state = decideAction(screen, commarea, ws, state, effectiveAid);
    screen.setUpdateState(state);
    sendMap(screen, commarea, ws, state);
    return null;
  }

  // ===============================================================================================
  // 1100-RECEIVE-MAP
  // ===============================================================================================

  /**
   * Reproduces {@code 1100-RECEIVE-MAP}: copies the editable screen fields into the per-call
   * working area, translating the BMS &quot;cleared field&quot; sentinels ({@code '*'} or SPACES)
   * to &quot;not supplied&quot; (COBOL LOW-VALUES). The protected expiry <em>day</em> is copied
   * verbatim because it always carries the OLD value (it is display-only on this screen).
   *
   * @param screen the screen contract holding the raw input fields
   * @param ws the per-call working state to populate
   */
  private void receiveMap(CardUpdateScreen screen, WorkState ws) {
    ws.acctRaw = normalizeStar(screen.getAcctSid());
    ws.cardRaw = normalizeStar(screen.getCardSid());
    ws.newName = normalizeStar(screen.getCrdName());
    ws.newStatus = normalizeStar(screen.getCrdStcd());
    ws.newExpMon = normalizeStar(screen.getExpMon());
    ws.newExpYear = normalizeStar(screen.getExpYear());
    // EXPDAY is moved directly (no '*' translation); it carries the OLD day.
    ws.newExpDay = trimToNull(screen.getExpDay());

    // Best-effort numeric resolution of the (protected, round-tripped) keys for display & re-read.
    ws.acctIdNum = parseDigitsOrZero(ws.acctRaw);
    ws.cardIdNum = parseDigitsOrZero(ws.cardRaw);
    ws.cardKey = toCardKey(ws.cardIdNum);
  }

  // ===============================================================================================
  // 1200-EDIT-MAP-INPUTS
  // ===============================================================================================

  /**
   * Reproduces {@code 1200-EDIT-MAP-INPUTS}. When the details have not yet been fetched only the
   * search keys are validated ({@code 1210}/{@code 1220}); otherwise the operator's edits are
   * compared against the OLD snapshot and, if anything changed, the four mutable fields are
   * validated in the exact COBOL order {@code 1230 -> 1240 -> 1250 -> 1260}.
   *
   * @param screen the screen contract (source of the OLD snapshot)
   * @param ws the per-call working state
   * @param state the current state-machine value
   * @return the (possibly updated) state-machine value
   */
  private String editMapInputs(CardUpdateScreen screen, WorkState ws, String state) {
    // SET INPUT-OK TO TRUE (no error yet).
    ws.inputError = false;

    if (DETAILS_NOT_FETCHED.equals(state)) {
      editAccount(ws);
      editCard(ws);
      // MOVE LOW-VALUES TO CCUP-NEW-CARDDATA (no detail edits in this pass).
      return state;
    }

    // Details already on screen: the keys are considered valid; rebuild NEW vs OLD and compare.
    ws.acctFilterValid = true;
    ws.cardFilterValid = true;

    if (isNoChange(screen, ws)) {
      ws.noChangesDetected = true;
      // SET NO-CHANGES-DETECTED TO TRUE assigns the literal to WS-RETURN-MSG (unconditional).
      ws.returnMsg = MSG_NO_CHANGES_DETECTED;
    }

    if (ws.noChangesDetected
        || CHANGES_OK_NOT_CONFIRMED.equals(state)
        || CHANGES_OKAYED_AND_DONE.equals(state)) {
      // All four field edits are implicitly valid; do not re-validate. State is unchanged.
      return state;
    }

    // Something changed and we are not (yet) confirming: validate each field in order.
    state = CHANGES_NOT_OK;
    editName(ws);
    editCardStatus(ws);
    editExpiryMonth(ws);
    editExpiryYear(ws);
    if (!ws.inputError) {
      state = CHANGES_OK_NOT_CONFIRMED;
    }
    return state;
  }

  // ===============================================================================================
  // 1210-EDIT-ACCOUNT
  // ===============================================================================================

  /**
   * Reproduces {@code 1210-EDIT-ACCOUNT}: the account filter must be supplied and be an 11-digit
   * number. A blank/zero value yields {@link #MSG_PROMPT_FOR_ACCT}; a non-11-digit value yields
   * {@link #MSG_ACCT_NOT_NUMERIC}. The first failing field's message wins.
   *
   * @param ws the per-call working state
   */
  private void editAccount(WorkState ws) {
    ws.acctFilterValid = false;
    if (isNotSupplied(ws.acctRaw)) {
      ws.inputError = true;
      setReturnMsgIfOff(ws, MSG_PROMPT_FOR_ACCT);
      ws.acctIdNum = 0L;
      return;
    }
    if (ws.acctRaw.matches("[0-9]{" + W_ACCT + "}")) {
      ws.acctIdNum = Long.parseLong(ws.acctRaw);
      ws.acctFilterValid = true;
      return;
    }
    ws.inputError = true;
    setReturnMsgIfOff(ws, MSG_ACCT_NOT_NUMERIC);
    ws.acctIdNum = 0L;
  }

  // ===============================================================================================
  // 1220-EDIT-CARD
  // ===============================================================================================

  /**
   * Reproduces {@code 1220-EDIT-CARD}: the card filter must be supplied and be a 16-digit number. A
   * blank/zero value yields {@link #MSG_PROMPT_FOR_CARD}; a non-16-digit value yields {@link
   * #MSG_CARD_NOT_NUMERIC}.
   *
   * @param ws the per-call working state
   */
  private void editCard(WorkState ws) {
    ws.cardFilterValid = false;
    if (isNotSupplied(ws.cardRaw)) {
      ws.inputError = true;
      setReturnMsgIfOff(ws, MSG_PROMPT_FOR_CARD);
      ws.cardIdNum = 0L;
      ws.cardKey = null;
      return;
    }
    if (ws.cardRaw.matches("[0-9]{" + W_CARD + "}")) {
      ws.cardIdNum = Long.parseLong(ws.cardRaw);
      ws.cardKey = ws.cardRaw; // already exactly 16 digits
      ws.cardFilterValid = true;
      return;
    }
    ws.inputError = true;
    setReturnMsgIfOff(ws, MSG_CARD_NOT_NUMERIC);
    ws.cardIdNum = 0L;
    ws.cardKey = null;
  }

  // ===============================================================================================
  // 1230-EDIT-NAME
  // ===============================================================================================

  /**
   * Reproduces {@code 1230-EDIT-NAME}: the embossed name must be supplied and may contain only
   * alphabetic characters and spaces (COBOL {@code INSPECT ... CONVERTING} of A-Z/a-z to spaces,
   * then a zero-length-after-trim test).
   *
   * @param ws the per-call working state
   */
  private void editName(WorkState ws) {
    if (isNotSupplied(ws.newName)) {
      ws.inputError = true;
      setReturnMsgIfOff(ws, MSG_PROMPT_FOR_NAME);
      return;
    }
    if (isAlphaOrSpace(ws.newName)) {
      return; // valid
    }
    ws.inputError = true;
    setReturnMsgIfOff(ws, MSG_NAME_MUST_BE_ALPHA);
  }

  // ===============================================================================================
  // 1240-EDIT-CARDSTATUS
  // ===============================================================================================

  /**
   * Reproduces {@code 1240-EDIT-CARDSTATUS}: the active-status flag must be supplied and be exactly
   * {@code 'Y'} or {@code 'N'} (the {@code FLG-YES-NO-VALID} 88-level, case-sensitive). Blank and
   * invalid both yield {@link #MSG_CARD_STATUS_YES_NO}.
   *
   * @param ws the per-call working state
   */
  private void editCardStatus(WorkState ws) {
    if (isNotSupplied(ws.newStatus)) {
      ws.inputError = true;
      setReturnMsgIfOff(ws, MSG_CARD_STATUS_YES_NO);
      return;
    }
    if ("Y".equals(ws.newStatus) || "N".equals(ws.newStatus)) {
      return; // valid
    }
    ws.inputError = true;
    setReturnMsgIfOff(ws, MSG_CARD_STATUS_YES_NO);
  }

  // ===============================================================================================
  // 1250-EDIT-EXPIRY-MON
  // ===============================================================================================

  /**
   * Reproduces {@code 1250-EDIT-EXPIRY-MON}: the expiry month must be supplied and be numeric in
   * the range 1&ndash;12 ({@code VALID-MONTH}). Blank and invalid both yield {@link
   * #MSG_EXPIRY_MONTH_NOT_VALID}.
   *
   * @param ws the per-call working state
   */
  private void editExpiryMonth(WorkState ws) {
    if (isNotSupplied(ws.newExpMon)) {
      ws.inputError = true;
      setReturnMsgIfOff(ws, MSG_EXPIRY_MONTH_NOT_VALID);
      return;
    }
    Integer mon = toIntOrNull(ws.newExpMon);
    if (mon != null && mon >= MONTH_MIN && mon <= MONTH_MAX) {
      return; // valid
    }
    ws.inputError = true;
    setReturnMsgIfOff(ws, MSG_EXPIRY_MONTH_NOT_VALID);
  }

  // ===============================================================================================
  // 1260-EDIT-EXPIRY-YEAR
  // ===============================================================================================

  /**
   * Reproduces {@code 1260-EDIT-EXPIRY-YEAR}: the expiry year must be supplied and be numeric in
   * the range 1950&ndash;2099 ({@code VALID-YEAR}). Blank and invalid both yield {@link
   * #MSG_EXPIRY_YEAR_NOT_VALID}.
   *
   * @param ws the per-call working state
   */
  private void editExpiryYear(WorkState ws) {
    if (isNotSupplied(ws.newExpYear)) {
      ws.inputError = true;
      setReturnMsgIfOff(ws, MSG_EXPIRY_YEAR_NOT_VALID);
      return;
    }
    Integer year = toIntOrNull(ws.newExpYear);
    if (year != null && year >= YEAR_MIN && year <= YEAR_MAX) {
      return; // valid
    }
    ws.inputError = true;
    setReturnMsgIfOff(ws, MSG_EXPIRY_YEAR_NOT_VALID);
  }

  // ===============================================================================================
  // 2000-DECIDE-ACTION
  // ===============================================================================================

  /**
   * Reproduces {@code 2000-DECIDE-ACTION} ({@code EVALUATE TRUE}). Drives the read, the
   * confirm-prompt transition, the PF5-triggered write, and the post-write outcome mapping.
   *
   * @param screen the screen contract
   * @param commarea the navigation COMMAREA
   * @param ws the per-call working state
   * @param state the current state-machine value
   * @param aid the (already validated/coerced) attention id
   * @return the next state-machine value
   */
  private String decideAction(
      CardUpdateScreen screen,
      CardDemoCommarea commarea,
      WorkState ws,
      String state,
      CardWorkArea.Aid aid) {

    // WHEN DETAILS-NOT-FETCHED / WHEN PF12 (cancel) -> (re)fetch the card.
    if (DETAILS_NOT_FETCHED.equals(state) || aid == CardWorkArea.Aid.PFK12) {
      if (ws.acctFilterValid && ws.cardFilterValid) {
        readData(screen, ws);
        if (ws.foundCard) {
          state = SHOW_DETAILS;
        }
      }
      return state;
    }

    // WHEN SHOW-DETAILS -> ask for confirmation unless there was an error or nothing changed.
    if (SHOW_DETAILS.equals(state)) {
      if (ws.inputError || ws.noChangesDetected) {
        return state; // CONTINUE
      }
      return CHANGES_OK_NOT_CONFIRMED;
    }

    // WHEN CHANGES-NOT-OK -> CONTINUE (redisplay with field errors).
    if (CHANGES_NOT_OK.equals(state)) {
      return state;
    }

    // WHEN CHANGES-OK-NOT-CONFIRMED AND PF5 -> perform the write and map the outcome.
    if (CHANGES_OK_NOT_CONFIRMED.equals(state) && aid == CardWorkArea.Aid.PFK05) {
      WriteOutcome outcome = writeProcessing(screen, ws);
      switch (outcome) {
        case COULD_NOT_LOCK:
          return CHANGES_OKAYED_LOCK_ERROR;
        case LOCKED_BUT_FAILED:
          return CHANGES_OKAYED_BUT_FAILED;
        case DATA_WAS_CHANGED:
          return SHOW_DETAILS;
        default:
          return CHANGES_OKAYED_AND_DONE;
      }
    }

    // WHEN CHANGES-OK-NOT-CONFIRMED (no PF5) -> CONTINUE (re-prompt confirm).
    if (CHANGES_OK_NOT_CONFIRMED.equals(state)) {
      return state;
    }

    // WHEN CHANGES-OKAYED-AND-DONE -> show details; reset keys when there is no caller transaction.
    if (CHANGES_OKAYED_AND_DONE.equals(state)) {
      if (isBlankCobol(commarea.getFromTranId())) {
        commarea.setAcctId(0L);
        commarea.setCardNum(0L);
        commarea.setAcctStatus(null);
      }
      return SHOW_DETAILS;
    }

    // WHEN OTHER -> abend '0001' "UNEXPECTED DATA SCENARIO". Online parity: surface a generic error
    // and redisplay rather than terminating (a @ControllerAdvice would translate a true abend).
    setReturnMsgIfOff(ws, MSG_UNEXPECTED_DATA_SCENARIO);
    return state;
  }

  // ===============================================================================================
  // 9000-READ-DATA  /  9100-GETCARD-BYACCTCARD
  // ===============================================================================================

  /**
   * Reproduces {@code 9000-READ-DATA}: captures the OLD account/card keys and, when the card is
   * found by {@link #getCardByAcctCard}, snapshots its CVV, (upper-cased) embossed name, expiry
   * year/month/day and active status into the screen's OLD carrier fields.
   *
   * @param screen the screen contract (destination of the OLD snapshot)
   * @param ws the per-call working state
   */
  private void readData(CardUpdateScreen screen, WorkState ws) {
    // INITIALIZE CCUP-OLD-DETAILS + record the keys under which we searched.
    clearOldSnapshot(screen);

    Card card = getCardByAcctCard(ws);
    if (ws.foundCard && card != null) {
      String exp = nvl(card.getCardExpiraionDate());
      screen.setOldCardCvvCd(nvl(card.getCardCvvCd()));
      screen.setOldCrdName(upperCase(nvl(card.getCardEmbossedName())));
      screen.setOldExpYear(sub(exp, 0, W_YEAR));
      screen.setOldExpMon(sub(exp, 5, 7));
      screen.setOldExpDay(sub(exp, 8, 10));
      screen.setOldCrdStcd(nvl(card.getCardActiveStatus()));
    }
  }

  /**
   * Reproduces {@code 9100-GETCARD-BYACCTCARD}: reads the {@code CARDDAT} file by card number only
   * (the account path is commented out in the COBOL). A present row sets {@code
   * FOUND-CARDS-FOR-ACCOUNT}; an absent row (FILE STATUS {@code '23'}) sets the &quot;not
   * found&quot; redisplay message; any other data-access failure is escalated to {@link
   * IoStatusException} (abend-equivalent).
   *
   * @param ws the per-call working state
   * @return the located {@link Card}, or {@code null} when not found
   */
  private Card getCardByAcctCard(WorkState ws) {
    ws.foundCard = false;
    final String key = ws.cardKey;
    final Optional<Card> found;
    try {
      found = (key == null) ? Optional.empty() : cardRepository.findById(key);
    } catch (DataAccessException ex) {
      // WHEN OTHER (file error) -> abend-equivalent typed exception; rolls back the transaction.
      ws.inputError = true;
      throw new IoStatusException(LIT_CARD_FILE_NAME, "READ", null, ex);
    }
    if (found.isEmpty()) {
      // WHEN NOTFND -> field-level redisplay (no exception).
      ws.inputError = true;
      ws.acctFilterValid = false;
      ws.cardFilterValid = false;
      setReturnMsgIfOff(ws, MSG_DID_NOT_FIND_ACCTCARD);
      return null;
    }
    ws.foundCard = true;
    return found.get();
  }

  // ===============================================================================================
  // 9200-WRITE-PROCESSING  (optimistic update)
  // ===============================================================================================

  /**
   * Reproduces {@code 9200-WRITE-PROCESSING}. Re-reads the row inside the active transaction
   * (lock-for-update parity), verifies via {@link #checkChangeInRec} that no concurrent change
   * occurred, then rewrites the editable fields. The card number, account id, CVV and expiry
   * <em>day</em> are not user-editable on this screen and are preserved; the expiry date is rebuilt
   * as {@code NEW-YEAR-NEW-MONTH-OLD-DAY}.
   *
   * <p>A missing/unreadable row maps to {@link WriteOutcome#COULD_NOT_LOCK}; a concurrent change
   * maps to {@link WriteOutcome#DATA_WAS_CHANGED} (with the OLD snapshot refreshed); a persistence
   * failure maps to {@link WriteOutcome#LOCKED_BUT_FAILED}. The exception is caught (not
   * propagated) so the screen can show the COBOL status message, matching the COBOL flow which sets
   * a flag rather than abending on a failed REWRITE.
   *
   * @param screen the screen contract (source of OLD snapshot / NEW expiry day)
   * @param ws the per-call working state (source of the NEW editable values)
   * @return the write outcome
   */
  private WriteOutcome writeProcessing(CardUpdateScreen screen, WorkState ws) {
    final Card locked;
    try {
      Optional<Card> opt =
          (ws.cardKey == null) ? Optional.empty() : cardRepository.findById(ws.cardKey);
      if (opt.isEmpty()) {
        ws.inputError = true;
        setReturnMsgIfOff(ws, MSG_COULD_NOT_LOCK);
        return WriteOutcome.COULD_NOT_LOCK;
      }
      locked = opt.get();
    } catch (DataAccessException ex) {
      // Could not lock the record for update (non-NORMAL RESP on READ ... UPDATE).
      ws.inputError = true;
      setReturnMsgIfOff(ws, MSG_COULD_NOT_LOCK);
      return WriteOutcome.COULD_NOT_LOCK;
    }

    // Did someone change the record while we were out?
    if (checkChangeInRec(screen, locked)) {
      setReturnMsgIfOff(ws, MSG_DATA_WAS_CHANGED);
      return WriteOutcome.DATA_WAS_CHANGED;
    }

    // Prepare the update: preserve keys/CVV/day; set the NEW name, status and rebuilt expiry date.
    locked.setCardAcctId(ws.acctIdNum);
    locked.setCardEmbossedName(nvl(ws.newName));
    locked.setCardActiveStatus(nvl(ws.newStatus));
    locked.setCardExpiraionDate(buildExpiry(ws.newExpYear, ws.newExpMon, screen.getOldExpDay()));

    try {
      cardRepository.save(locked);
    } catch (DataAccessException ex) {
      // Locked, but the rewrite failed.
      setReturnMsgIfOff(ws, MSG_LOCKED_BUT_UPDATE_FAILED);
      return WriteOutcome.LOCKED_BUT_FAILED;
    }
    return WriteOutcome.OK;
  }

  // ===============================================================================================
  // 9300-CHECK-CHANGE-IN-REC
  // ===============================================================================================

  /**
   * Reproduces {@code 9300-CHECK-CHANGE-IN-REC}: upper-cases the re-read embossed name and compares
   * CVV, name, expiry year/month/day and active status against the OLD snapshot. When everything
   * matches it is safe to rewrite; otherwise the OLD snapshot is refreshed from the re-read row so
   * the redisplay reflects the concurrent change.
   *
   * @param screen the screen contract (holds and is refreshed with the OLD snapshot)
   * @param card the freshly re-read row
   * @return {@code true} when the row changed since it was fetched, {@code false} when unchanged
   */
  private boolean checkChangeInRec(CardUpdateScreen screen, Card card) {
    final String reCvv = nvl(card.getCardCvvCd());
    final String reName = upperCase(nvl(card.getCardEmbossedName()));
    final String exp = nvl(card.getCardExpiraionDate());
    final String reYear = sub(exp, 0, W_YEAR);
    final String reMon = sub(exp, 5, 7);
    final String reDay = sub(exp, 8, 10);
    final String reStatus = nvl(card.getCardActiveStatus());

    boolean unchanged =
        sameFixed(reCvv, screen.getOldCardCvvCd(), W_CVV)
            && sameFixed(reName, screen.getOldCrdName(), W_NAME)
            && sameFixed(reYear, screen.getOldExpYear(), W_YEAR)
            && sameFixed(reMon, screen.getOldExpMon(), W_MON)
            && sameFixed(reDay, screen.getOldExpDay(), W_DAY)
            && sameFixed(reStatus, screen.getOldCrdStcd(), W_STATUS);

    if (unchanged) {
      return false;
    }

    // Refresh the OLD snapshot from the re-read values (MOVE re-read fields back into CCUP-OLD-*).
    screen.setOldCardCvvCd(reCvv);
    screen.setOldCrdName(reName);
    screen.setOldExpYear(reYear);
    screen.setOldExpMon(reMon);
    screen.setOldExpDay(reDay);
    screen.setOldCrdStcd(reStatus);
    return true;
  }

  // ===============================================================================================
  // 3000 / 3100 / 3200 / 3250 - SEND-MAP family
  // ===============================================================================================

  /**
   * Reproduces {@code 3000-SEND-MAP}: initialises the header, sets up the variable fields and the
   * information/error messages for the current state.
   *
   * @param screen the screen contract to populate
   * @param commarea the navigation COMMAREA
   * @param ws the per-call working state
   * @param state the current state-machine value
   */
  private void sendMap(
      CardUpdateScreen screen, CardDemoCommarea commarea, WorkState ws, String state) {
    populateHeader(screen);
    setupScreenVars(screen, commarea, ws, state);
    setupInfoMsg(screen, commarea, ws, state);
  }

  /**
   * Reproduces {@code 3100-SCREEN-INIT}: header fields (transaction/program names, both title
   * lines, current date and time) plus the static function-key legends.
   *
   * @param screen the screen contract to populate
   */
  private void populateHeader(CardUpdateScreen screen) {
    LocalDateTime now = LocalDateTime.now();
    screen.setTrnName(TRAN_ID);
    screen.setPgmName(PGM_NAME);
    screen.setTitle01(TITLE_LINE_1);
    screen.setTitle02(TITLE_LINE_2);
    screen.setCurDate(now.format(DateTimeFormatter.ofPattern(DATE_MASK)));
    screen.setCurTime(now.format(DateTimeFormatter.ofPattern(TIME_MASK)));
    screen.setFkeys(FKEYS_LINE);
    screen.setFkeysc(FKEYSC_LINE);
  }

  /**
   * Reproduces {@code 3200-SETUP-SCREEN-VARS}: blanks everything on a program-enter; otherwise
   * echoes the resolved account/card keys and selects the name/status/expiry values to show based
   * on the state (DETAILS-NOT-FETCHED &rarr; blanks; SHOW-DETAILS / OTHER &rarr; OLD; CHANGES-MADE
   * &rarr; NEW values but always the OLD expiry day).
   *
   * @param screen the screen contract to populate
   * @param commarea the navigation COMMAREA
   * @param ws the per-call working state
   * @param state the current state-machine value
   */
  private void setupScreenVars(
      CardUpdateScreen screen, CardDemoCommarea commarea, WorkState ws, String state) {
    if (commarea.isPgmEnter()) {
      screen.setAcctSid("");
      screen.setCardSid("");
      screen.setCrdName("");
      screen.setCrdStcd("");
      screen.setExpMon("");
      screen.setExpYear("");
      screen.setExpDay("");
      return;
    }

    screen.setAcctSid(ws.acctIdNum == 0L ? "" : format(ws.acctIdNum, W_ACCT));
    screen.setCardSid(ws.cardIdNum == 0L ? "" : format(ws.cardIdNum, W_CARD));

    if (DETAILS_NOT_FETCHED.equals(state)) {
      screen.setCrdName("");
      screen.setCrdStcd("");
      screen.setExpDay("");
      screen.setExpMon("");
      screen.setExpYear("");
    } else if (SHOW_DETAILS.equals(state)) {
      screen.setCrdName(nvl(screen.getOldCrdName()));
      screen.setCrdStcd(nvl(screen.getOldCrdStcd()));
      screen.setExpDay(nvl(screen.getOldExpDay()));
      screen.setExpMon(nvl(screen.getOldExpMon()));
      screen.setExpYear(nvl(screen.getOldExpYear()));
    } else if (isChangesMade(state)) {
      screen.setCrdName(nvl(ws.newName));
      screen.setCrdStcd(nvl(ws.newStatus));
      screen.setExpMon(nvl(ws.newExpMon));
      screen.setExpYear(nvl(ws.newExpYear));
      // Expiry day is preserved (display-only): always from the OLD snapshot.
      screen.setExpDay(nvl(screen.getOldExpDay()));
    } else {
      screen.setCrdName(nvl(screen.getOldCrdName()));
      screen.setCrdStcd(nvl(screen.getOldCrdStcd()));
      screen.setExpDay(nvl(screen.getOldExpDay()));
      screen.setExpMon(nvl(screen.getOldExpMon()));
      screen.setExpYear(nvl(screen.getOldExpYear()));
    }
  }

  /**
   * Reproduces {@code 3250-SETUP-INFOMSG}: selects the information message by state (program-enter
   * is treated as the search-keys prompt) and moves the working return message into the screen's
   * error line.
   *
   * @param screen the screen contract to populate
   * @param commarea the navigation COMMAREA
   * @param ws the per-call working state
   * @param state the current state-machine value
   */
  private void setupInfoMsg(
      CardUpdateScreen screen, CardDemoCommarea commarea, WorkState ws, String state) {
    final String info;
    if (commarea.isPgmEnter() || DETAILS_NOT_FETCHED.equals(state)) {
      info = INFO_PROMPT_FOR_SEARCH_KEYS;
    } else if (SHOW_DETAILS.equals(state)) {
      info = INFO_FOUND_CARDS;
    } else if (CHANGES_NOT_OK.equals(state)) {
      info = INFO_PROMPT_FOR_CHANGES;
    } else if (CHANGES_OK_NOT_CONFIRMED.equals(state)) {
      info = INFO_PROMPT_FOR_CONFIRMATION;
    } else if (CHANGES_OKAYED_AND_DONE.equals(state)) {
      info = INFO_CONFIRM_UPDATE_SUCCESS;
    } else if (CHANGES_OKAYED_LOCK_ERROR.equals(state) || CHANGES_OKAYED_BUT_FAILED.equals(state)) {
      info = INFO_INFORM_FAILURE;
    } else {
      info = INFO_PROMPT_FOR_SEARCH_KEYS;
    }
    screen.setInfoMsg(info);
    screen.setErrMsg(nvl(ws.returnMsg));
  }

  // ===============================================================================================
  // Exit / state-reset helpers
  // ===============================================================================================

  /**
   * Reproduces the {@code 0000-MAIN} PF3/exit branch: computes the return routing (caller program /
   * transaction, falling back to the main menu), records this program as the &quot;from&quot;
   * context, optionally clears the keys when arriving from the card list, and returns the program
   * to transfer control to.
   *
   * @param commarea the navigation COMMAREA to update in place
   * @param lastWasCardList whether the previous screen was the card-list screen
   * @return the program name to XCTL to
   */
  private String exitToCaller(CardDemoCommarea commarea, boolean lastWasCardList) {
    String toTran =
        isBlankCobol(commarea.getFromTranId()) ? LIT_MENU_TRAN : trim(commarea.getFromTranId());
    String toPgm =
        isBlankCobol(commarea.getFromProgram()) ? LIT_MENU_PGM : trim(commarea.getFromProgram());
    commarea.setToTranId(toTran);
    commarea.setToProgram(toPgm);
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    if (lastWasCardList) {
      commarea.setAcctId(0L);
      commarea.setCardNum(0L);
    }
    // COBOL parity: SET CDEMO-USRTYP-USER TO TRUE on exit (preserved verbatim from COCRDUPC).
    commarea.setUsrTypUser();
    commarea.setPgmEnter();
    commarea.setLastMapset(LIT_THIS_MAPSET);
    commarea.setLastMap(LIT_THIS_MAP);
    return toPgm;
  }

  /**
   * Resets the cross-turn carrier state (mirrors {@code INITIALIZE WS-THIS-PROGCOMMAREA}): clears
   * the OLD snapshot and the state-machine flag back to DETAILS-NOT-FETCHED.
   *
   * @param screen the screen contract whose carriers are cleared
   */
  private void resetScreenState(CardUpdateScreen screen) {
    screen.setUpdateState(DETAILS_NOT_FETCHED);
    clearOldSnapshot(screen);
  }

  /**
   * Clears the OLD snapshot carrier fields (mirrors {@code INITIALIZE CCUP-OLD-DETAILS}).
   *
   * @param screen the screen contract whose OLD carriers are cleared
   */
  private void clearOldSnapshot(CardUpdateScreen screen) {
    screen.setOldCardCvvCd(null);
    screen.setOldCrdName(null);
    screen.setOldCrdStcd(null);
    screen.setOldExpMon(null);
    screen.setOldExpYear(null);
    screen.setOldExpDay(null);
  }

  // ===============================================================================================
  // State predicates
  // ===============================================================================================

  /**
   * Resolves the current state from the screen carrier, defaulting a {@code null}/blank carrier to
   * {@link #DETAILS_NOT_FETCHED} (the COBOL LOW-VALUES/SPACES default).
   *
   * @param screen the screen contract
   * @return the canonical current state value
   */
  private String currentState(CardUpdateScreen screen) {
    String s = screen.getUpdateState();
    return (s == null || s.isBlank()) ? DETAILS_NOT_FETCHED : s;
  }

  /**
   * {@code CCUP-CHANGES-MADE} (any of {@code 'E','N','C','L','F'}).
   *
   * @param state the state value
   * @return whether the state denotes that changes were made
   */
  private boolean isChangesMade(String state) {
    return CHANGES_NOT_OK.equals(state)
        || CHANGES_OK_NOT_CONFIRMED.equals(state)
        || CHANGES_OKAYED_AND_DONE.equals(state)
        || CHANGES_OKAYED_LOCK_ERROR.equals(state)
        || CHANGES_OKAYED_BUT_FAILED.equals(state);
  }

  /**
   * {@code CCUP-CHANGES-FAILED} (either {@code 'L'} or {@code 'F'}).
   *
   * @param state the state value
   * @return whether the state denotes a failed write
   */
  private boolean isChangesFailed(String state) {
    return CHANGES_OKAYED_LOCK_ERROR.equals(state) || CHANGES_OKAYED_BUT_FAILED.equals(state);
  }

  // ===============================================================================================
  // Field comparison helpers
  // ===============================================================================================

  /**
   * Reproduces the {@code 1200} no-change test: compares the upper-cased, fixed-width concatenation
   * of the NEW values (name, expiry year/month/day, status) against the OLD snapshot.
   *
   * @param screen the screen contract (source of the OLD snapshot)
   * @param ws the per-call working state (source of the NEW values)
   * @return {@code true} when the NEW values equal the OLD snapshot
   */
  private boolean isNoChange(CardUpdateScreen screen, WorkState ws) {
    String newData =
        upperCase(
            fixedWidth(ws.newName, W_NAME)
                + fixedWidth(ws.newExpYear, W_YEAR)
                + fixedWidth(ws.newExpMon, W_MON)
                + fixedWidth(ws.newExpDay, W_DAY)
                + fixedWidth(ws.newStatus, W_STATUS));
    String oldData =
        upperCase(
            fixedWidth(screen.getOldCrdName(), W_NAME)
                + fixedWidth(screen.getOldExpYear(), W_YEAR)
                + fixedWidth(screen.getOldExpMon(), W_MON)
                + fixedWidth(screen.getOldExpDay(), W_DAY)
                + fixedWidth(screen.getOldCrdStcd(), W_STATUS));
    return newData.equals(oldData);
  }

  /**
   * Compares two values as fixed-width COBOL fields (trailing-space-insensitive, since both are the
   * same declared width).
   *
   * @param a first value
   * @param b second value
   * @param width the declared field width
   * @return whether the two normalise to the same fixed-width content
   */
  private boolean sameFixed(String a, String b, int width) {
    return fixedWidth(a, width).equals(fixedWidth(b, width));
  }

  // ===============================================================================================
  // String / numeric utilities (local COBOL-semantics helpers; no shared util dependency)
  // ===============================================================================================

  /**
   * Sets the working return message only if it is currently &quot;off&quot; (empty), reproducing
   * the COBOL {@code IF WS-RETURN-MSG-OFF} guard so that the first failing field's message wins.
   *
   * @param ws the per-call working state
   * @param msg the message to set when none is set yet
   */
  private void setReturnMsgIfOff(WorkState ws, String msg) {
    if (ws.returnMsg == null || ws.returnMsg.isEmpty()) {
      ws.returnMsg = msg;
    }
  }

  /**
   * Translates the BMS cleared-field sentinels to {@code null}: a value equal to {@code '*'} or
   * that is blank becomes {@code null} (COBOL LOW-VALUES); otherwise the trimmed value is returned.
   *
   * @param value the raw screen field value
   * @return the normalised value, or {@code null} when not supplied
   */
  private String normalizeStar(String value) {
    if (value == null) {
      return null;
    }
    String t = value.strip();
    if (t.isEmpty() || "*".equals(t)) {
      return null;
    }
    return t;
  }

  /**
   * COBOL &quot;not supplied&quot; test: {@code null}, blank (SPACES/LOW-VALUES) or all-zeros
   * (ZEROS).
   *
   * @param value the value to test
   * @return whether the value should be treated as not supplied
   */
  private boolean isNotSupplied(String value) {
    if (value == null) {
      return true;
    }
    String t = value.strip();
    if (t.isEmpty()) {
      return true;
    }
    return isAllZeros(t);
  }

  /**
   * Tests whether a non-empty string consists solely of {@code '0'} characters.
   *
   * @param t the (already trimmed) value
   * @return whether every character is {@code '0'}
   */
  private boolean isAllZeros(String t) {
    for (int i = 0; i < t.length(); i++) {
      if (t.charAt(i) != '0') {
        return false;
      }
    }
    return !t.isEmpty();
  }

  /**
   * Tests whether every character is an ASCII letter or a space (COBOL alphabetic-name rule).
   *
   * @param value the value to test
   * @return whether the value contains only letters and spaces
   */
  private boolean isAlphaOrSpace(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
      if (!letter && c != ' ') {
        return false;
      }
    }
    return true;
  }

  /**
   * Parses a value as an {@code Integer} when it is purely ASCII digits, otherwise {@code null}.
   *
   * @param value the value to parse
   * @return the parsed integer, or {@code null} when not all-digits
   */
  private Integer toIntOrNull(String value) {
    if (value == null) {
      return null;
    }
    String t = value.strip();
    if (t.isEmpty() || !t.matches("[0-9]+")) {
      return null;
    }
    try {
      return Integer.parseInt(t);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Parses a value as a {@code long} when it is purely ASCII digits, otherwise returns {@code 0}.
   *
   * @param value the value to parse
   * @return the parsed value, or {@code 0} when not all-digits / out of range
   */
  private long parseDigitsOrZero(String value) {
    if (value == null) {
      return 0L;
    }
    String t = value.strip();
    if (t.isEmpty() || !t.matches("[0-9]+")) {
      return 0L;
    }
    try {
      return Long.parseLong(t);
    } catch (NumberFormatException ex) {
      return 0L;
    }
  }

  /**
   * Builds the 16-character, zero-padded card-number key used for repository lookups (mirrors the
   * COBOL zoned-numeric {@code CARD-NUM} key), or {@code null} when the value is zero.
   *
   * @param cardNum the numeric card number
   * @return the 16-character key, or {@code null} when {@code cardNum == 0}
   */
  private String toCardKey(long cardNum) {
    return cardNum == 0L ? null : format(cardNum, W_CARD);
  }

  /**
   * Rebuilds the {@code YYYY-MM-DD} expiry string (COBOL {@code STRING year '-' mon '-' day}).
   *
   * @param year the (NEW) expiry year
   * @param mon the (NEW) expiry month
   * @param day the (preserved OLD) expiry day
   * @return the assembled 10-character expiry string
   */
  private String buildExpiry(String year, String mon, String day) {
    return fixedWidth(year, W_YEAR) + "-" + fixedWidth(mon, W_MON) + "-" + fixedWidth(day, W_DAY);
  }

  /**
   * Upper-cases ASCII letters (COBOL {@code INSPECT ... CONVERTING} a-z to A-Z parity).
   *
   * @param value the value to upper-case (may be {@code null})
   * @return the upper-cased value, or {@code null} when the input was {@code null}
   */
  private String upperCase(String value) {
    if (value == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= 'a' && c <= 'z') {
        c = (char) (c - ('a' - 'A'));
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Normalises a value to exactly {@code width} characters: right-trims then right-pads with
   * spaces, or truncates when longer. A {@code null} value becomes {@code width} spaces.
   *
   * @param value the value to normalise
   * @param width the target width
   * @return the fixed-width representation
   */
  private String fixedWidth(String value, int width) {
    String v = (value == null) ? "" : rtrim(value);
    if (v.length() >= width) {
      return v.substring(0, width);
    }
    StringBuilder sb = new StringBuilder(width);
    sb.append(v);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }

  /**
   * Formats a non-negative value as a zero-padded fixed-width decimal string.
   *
   * @param value the value to format
   * @param width the target width
   * @return the zero-padded representation
   */
  private String format(long value, int width) {
    return String.format("%0" + width + "d", value);
  }

  /**
   * Extracts the substring {@code [from, to)} of {@code value}, clamped to its bounds and right-
   * trimmed, returning an empty string when out of range or {@code null}.
   *
   * @param value the source value
   * @param from the inclusive start index
   * @param to the exclusive end index
   * @return the (right-trimmed) substring, or an empty string
   */
  private String sub(String value, int from, int to) {
    if (value == null) {
      return "";
    }
    int len = value.length();
    if (from >= len) {
      return "";
    }
    int end = Math.min(to, len);
    return rtrim(value.substring(from, end));
  }

  /**
   * Removes trailing spaces from a value.
   *
   * @param value the value to right-trim (must not be {@code null})
   * @return the value with trailing spaces removed
   */
  private String rtrim(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(0, end);
  }

  /**
   * Trims a value to {@code null} (returns {@code null} for {@code null}/blank, else the trimmed
   * value).
   *
   * @param value the value to trim
   * @return the trimmed value or {@code null}
   */
  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String t = value.strip();
    return t.isEmpty() ? null : t;
  }

  /**
   * Null-safe accessor returning an empty string for {@code null}.
   *
   * @param value the value
   * @return the value, or an empty string when {@code null}
   */
  private String nvl(String value) {
    return value == null ? "" : value;
  }

  /**
   * Null-safe conversion of a boxed account/card number to a primitive ({@code null} &rarr; 0).
   *
   * @param value the boxed value
   * @return the primitive value, or {@code 0} when {@code null}
   */
  private long nz(Long value) {
    return value == null ? 0L : value;
  }

  /**
   * Trims a value, returning an empty string for {@code null}.
   *
   * @param value the value to trim
   * @return the trimmed value (never {@code null})
   */
  private String trim(String value) {
    return value == null ? "" : value.strip();
  }

  /**
   * COBOL blank test for a routing field: {@code true} for {@code null} or blank
   * (LOW-VALUES/SPACES).
   *
   * @param value the value to test
   * @return whether the value is blank
   */
  private boolean isBlankCobol(String value) {
    return value == null || value.strip().isEmpty();
  }

  // ===============================================================================================
  // Per-invocation working state & write outcome
  // ===============================================================================================

  /**
   * Per-invocation mutable working area (the COBOL {@code WS-MISC-STORAGE} working values). A new
   * instance is created for every call so the service bean stays stateless and singleton-safe.
   */
  private static final class WorkState {
    /** Resolved numeric account id ({@code CC-ACCT-ID-N} / {@code CDEMO-ACCT-ID}); 0 when none. */
    private long acctIdNum;

    /** Resolved numeric card number ({@code CC-CARD-NUM-N}); 0 when none. */
    private long cardIdNum;

    /** 16-character card-number key for repository lookups; {@code null} when none. */
    private String cardKey;

    /** Raw (star/blank-normalised) account filter input. */
    private String acctRaw;

    /** Raw (star/blank-normalised) card filter input. */
    private String cardRaw;

    /** NEW embossed name from the screen. */
    private String newName;

    /** NEW active-status flag from the screen. */
    private String newStatus;

    /** NEW expiry month from the screen. */
    private String newExpMon;

    /** NEW expiry year from the screen. */
    private String newExpYear;

    /** NEW expiry day from the screen (carries the OLD value; display-only). */
    private String newExpDay;

    /** Account filter validity ({@code FLG-ACCTFILTER-ISVALID}). */
    private boolean acctFilterValid;

    /** Card filter validity ({@code FLG-CARDFILTER-ISVALID}). */
    private boolean cardFilterValid;

    /** Aggregate input-error flag ({@code INPUT-ERROR}). */
    private boolean inputError;

    /** No-change-detected flag ({@code NO-CHANGES-DETECTED}). */
    private boolean noChangesDetected;

    /** Card-found flag ({@code FOUND-CARDS-FOR-ACCOUNT}). */
    private boolean foundCard;

    /**
     * Working return (error) message ({@code WS-RETURN-MSG}); {@code null}/empty when
     * &quot;off&quot;.
     */
    private String returnMsg;
  }

  /**
   * Outcome of the optimistic write ({@code 9200-WRITE-PROCESSING}), mapped to the state machine by
   * {@link #decideAction}.
   */
  private enum WriteOutcome {
    /** The rewrite succeeded. */
    OK,
    /** The row could not be locked for update (missing / unreadable). */
    COULD_NOT_LOCK,
    /** The row changed since it was fetched. */
    DATA_WAS_CHANGED,
    /** The row was locked but the rewrite failed. */
    LOCKED_BUT_FAILED
  }
}

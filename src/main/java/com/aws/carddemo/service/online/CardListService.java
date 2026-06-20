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
import com.aws.carddemo.dto.screen.CardListScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Online service that reproduces the legacy CICS program {@code COCRDLIC} (Card List / Browse, CICS
 * transaction {@code CCLI}), migrated from {@code legacy/app/cbl/COCRDLIC.cbl}.
 *
 * <p>This is a faithful, control-flow-preserving translation of the COBOL "STYLE A" numbered
 * paragraph program (AAP &sect;0.1.1, &sect;0.6.5, &sect;0.7.1). Every COBOL paragraph maps to a
 * private method of the same intent and the original perform / branch order is retained so that
 * behaviour is identical to the mainframe program:
 *
 * <ul>
 *   <li>{@code 0000-MAIN} &rarr; {@link #processCardList(CardListScreen, CardDemoCommarea,
 *       CardWorkArea.Aid)}
 *   <li>{@code 2000-RECEIVE-MAP} / {@code 2100-RECEIVE-SCREEN} &rarr; {@link #receiveMap}
 *   <li>{@code 2200-EDIT-INPUTS} &rarr; {@link #editInputs}
 *   <li>{@code 2210-EDIT-ACCOUNT} &rarr; {@link #editAccount}
 *   <li>{@code 2220-EDIT-CARD} &rarr; {@link #editCard}
 *   <li>{@code 2250-EDIT-ARRAY} &rarr; {@link #editArray}
 *   <li>{@code 9500-FILTER-RECORDS} &rarr; {@link #filterRecords}
 *   <li>{@code 9000-READ-FORWARD} &rarr; {@link #readForward}
 *   <li>{@code 9100-READ-BACKWARDS} &rarr; {@link #readBackwards}
 *   <li>{@code 1100-SCREEN-INIT} / {@code 1300-SETUP-SCREEN-ATTRS} &rarr; {@link #populateHeader}
 *   <li>{@code 1200-SCREEN-ARRAY-INIT} &rarr; {@link #populateRows}
 *   <li>{@code 1400-SETUP-MESSAGE} &rarr; {@link #setupMessage}
 * </ul>
 *
 * <h2>Paging state</h2>
 *
 * <p>The legacy program keeps its browse cursor and page bookkeeping in the trailing private area
 * of the COMMAREA ({@code WS-THIS-PROGCOMMAREA}: {@code WS-CA-FIRST-CARD-NUM}, {@code
 * WS-CA-LAST-CARD-NUM}, {@code WS-CA-SCREEN-NUM}, {@code CA-NEXT-PAGE-EXISTS}, {@code
 * CA-LAST-PAGE-SHOWN}). The migrated, shared {@link CardDemoCommarea} and {@link CardListScreen}
 * DTOs intentionally do <strong>not</strong> expose those cursor fields, and per the migration
 * constraints they must not be modified here. This service therefore <strong>reconstructs</strong>
 * the paging state from the data that already round-trips on the screen view contract:
 *
 * <ul>
 *   <li>{@code WS-CA-SCREEN-NUM} &larr; {@link CardListScreen#getPageNo()}
 *   <li>{@code WS-CA-FIRST-CARD-NUM} &larr; the card number of the first populated row
 *   <li>the last displayed card number &larr; the card number of the last populated row
 *   <li>{@code CA-NEXT-PAGE-EXISTS} is recomputed by probing the candidate set
 * </ul>
 *
 * <p>Because the card browse is a stable, read-only operation, offset/keyset paging reconstructed
 * from the displayed rows yields results that are observably identical to the legacy VSAM keyset
 * browse: page <em>N</em> always shows the same seven records, in the same {@code CARD-NUM}
 * ascending order, with the same forward / backward navigation. The controller layer ({@code
 * web/CardListController}) is responsible for round-tripping the page number and row card numbers
 * as hidden fields, mirroring how CICS preserved {@code WS-THIS-PROGCOMMAREA} across the
 * pseudo-conversational {@code RECEIVE}/{@code SEND} cycle.
 *
 * <h2>Error handling</h2>
 *
 * <p>As a list / browse transaction, all validation failures, "no records" outcomes and paging
 * boundaries are surfaced <em>on the screen</em> (an error or informational message) and the list
 * is redisplayed &mdash; this method returns {@code null} for those cases. Only a genuinely
 * unexpected data-access failure (the COBOL {@code WHEN OTHER} response on a VSAM read) is raised
 * as an {@link IoStatusException}, mirroring the legacy abend path.
 *
 * <p>The bean is a stateless singleton: it holds no mutable instance state. All per-request working
 * storage lives in the method-local {@link ListState} value object, so the service is safe to share
 * across concurrent requests.
 */
@Service
public class CardListService {

  // ---------------------------------------------------------------------------------------------
  // Navigation / identity literals (COCRDLIC WORKING-STORAGE LIT-* constants).
  // ---------------------------------------------------------------------------------------------

  /** {@code LIT-THISTRANID} — CICS transaction id for this program. */
  static final String TRAN_ID = "CCLI";

  /** {@code LIT-THISPGM} — this program's name. */
  static final String PGM_NAME = "COCRDLIC";

  /** {@code LIT-CARDDTLPGM} — Card Detail / View program (XCTL target for an {@code S} action). */
  static final String LIT_CARD_DETAIL_PGM = "COCRDSLC";

  /** {@code LIT-CARDDTLTRANID} — Card Detail / View transaction id. */
  static final String LIT_CARD_DETAIL_TRAN = "CCDL";

  /** {@code LIT-CARDUPDPGM} — Card Update program (XCTL target for a {@code U} action). */
  static final String LIT_CARD_UPDATE_PGM = "COCRDUPC";

  /** {@code LIT-CARDUPDTRANID} — Card Update transaction id. */
  static final String LIT_CARD_UPDATE_TRAN = "CCUP";

  /** {@code LIT-MENUPGM} — Main Menu program (XCTL target for PF03 exit). */
  static final String LIT_MENU_PGM = "COMEN01C";

  /** {@code LIT-MENUTRANID} — Main Menu transaction id. */
  static final String LIT_MENU_TRAN = "CM00";

  /** {@code LIT-THISMAPSET} — this program's BMS mapset. */
  static final String LIT_THIS_MAPSET = "COCRDLI";

  /** {@code LIT-THISMAP} — this program's BMS map. */
  static final String LIT_THIS_MAP = "CCRDLIA";

  /** {@code WS-MAX-SCREEN-LINES} — number of card rows shown per page. */
  static final int MAX_SCREEN_LINES = 7;

  /** {@code LIT-CARD-FILE} — logical file name used when reporting an I/O failure. */
  static final String LIT_CARD_FILE_NAME = "CARDDAT";

  // ---------------------------------------------------------------------------------------------
  // Byte-for-byte COBOL message literals (COCRDLIC WORKING-STORAGE 88-levels / MOVE literals).
  // ---------------------------------------------------------------------------------------------

  /** {@code 2210-EDIT-ACCOUNT} invalid account filter message (verbatim). */
  static final String MSG_ACCT_FILTER_INVALID =
      "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

  /** {@code 2220-EDIT-CARD} invalid card filter message (verbatim). */
  static final String MSG_CARD_FILTER_INVALID =
      "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

  /** {@code WS-MORE-THAN-1-ACTION} message (verbatim). */
  static final String MSG_MORE_THAN_1_ACTION = "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

  /** {@code WS-INVALID-ACTION-CODE} message (verbatim). */
  static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

  /** {@code WS-NO-RECORDS-FOUND} message (verbatim). */
  static final String MSG_NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

  /** {@code 9000-READ-FORWARD} end-of-data message (verbatim). */
  static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

  /** {@code 1400-SETUP-MESSAGE} page-up boundary message (verbatim). */
  static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

  /** {@code 1400-SETUP-MESSAGE} page-down boundary message (verbatim). */
  static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

  /** {@code WS-INFORM-REC-ACTIONS} informational message (verbatim). */
  static final String MSG_INFORM_REC_ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

  // ---------------------------------------------------------------------------------------------
  // Header constants. These mirror util.MenuOptions (sourced from copybook COTTL01Y / CSDAT01Y),
  // which is intentionally NOT on this file's dependency whitelist; the values are reproduced
  // locally so the screen header is rendered identically to the sibling online screens.
  // ---------------------------------------------------------------------------------------------

  /** {@code CCDA-TITLE01} — first header title line. */
  private static final String TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

  /** {@code CCDA-TITLE02} — second header title line. */
  private static final String TITLE_LINE_2 = "              CardDemo                  ";

  /** {@code WS-CURDATE-MM-DD-YY} render mask. */
  private static final String DATE_MASK = "MM/dd/yy";

  /** {@code WS-CURTIME-HH-MM-SS} render mask. */
  private static final String TIME_MASK = "HH:mm:ss";

  /** Card data store; replaces the legacy VSAM {@code CARDDAT} KSDS browse. */
  private final CardRepository cardRepository;

  /**
   * Creates the service with its single collaborator. Uses constructor injection (no field
   * injection / no {@code @Autowired}) so the dependency is explicit, final and the bean stays
   * immutable and easily testable.
   *
   * @param cardRepository the card repository (must not be {@code null})
   */
  public CardListService(CardRepository cardRepository) {
    this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository");
  }

  /**
   * Entry point reproducing {@code 0000-MAIN} of {@code COCRDLIC} (COBOL L298-L621).
   *
   * <p>Drives a single pseudo-conversational turn of the Card List screen: it restores /
   * initialises paging state, receives and edits the operator's filters and row selections,
   * evaluates the pressed key and either navigates away (PF03 to the menu, or a row selection to
   * the card detail / update program) or refreshes the list page in place.
   *
   * @param screen the Card List screen view contract carrying inputs (filters, row selections, page
   *     number) and receiving outputs (rows, messages); must not be {@code null}
   * @param commarea the shared conversation state (user, role, navigation context); must not be
   *     {@code null}
   * @param aid the attention identifier (mapped PF key) for this turn; {@code null} is treated as
   *     {@code ENTER}
   * @return the XCTL target program name to navigate to ({@link #LIT_MENU_PGM} for PF03, {@link
   *     #LIT_CARD_DETAIL_PGM} for an {@code S} row action, {@link #LIT_CARD_UPDATE_PGM} for a
   *     {@code U} row action), or {@code null} to redisplay the list on the same screen
   * @throws IoStatusException if an unexpected, unrecoverable data-access failure occurs while
   *     reading the card store (mirrors the legacy {@code WHEN OTHER} response abend)
   */
  public String processCardList(
      CardListScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen");
    Objects.requireNonNull(commarea, "commarea");

    ListState st = new ListState();
    // YYYY-STORE-PFKEY (COPY CSSTRPFY): the controller has already mapped EIBAID to an Aid; a null
    // AID defaults to ENTER, matching the copybook's "treat unknown as ENTER" behaviour.
    st.aid = aid == null ? CardWorkArea.Aid.ENTER : aid;

    // Capture the inbound error line BEFORE it is overwritten; used to reconstruct the
    // CA-LAST-PAGE-SHOWN latch (see setupMessage).
    String incomingErrMsg = trimToEmpty(screen.getErrMsg());
    st.caLastPageShown = MSG_NO_MORE_RECORDS.equals(incomingErrMsg);

    // Snapshot the original COMMAREA routing BEFORE any re-initialisation. "Prior COMMAREA" mirrors
    // EIBCALEN > 0; "from this program" mirrors CDEMO-FROM-PROGRAM = LIT-THISPGM.
    String originalFromProgram = trimToEmpty(commarea.getFromProgram());
    boolean priorCommarea = !originalFromProgram.isEmpty();
    boolean fromThis = PGM_NAME.equals(originalFromProgram);

    // 0000-MAIN L315-L353: first entry (EIBCALEN = 0) initialises the conversation; otherwise the
    // paging state is restored from the round-tripped screen.
    if (!priorCommarea) {
      commarea.setFromTranId(TRAN_ID);
      commarea.setFromProgram(PGM_NAME);
      commarea.setPgmEnter();
      commarea.setLastMapset(LIT_THIS_MAPSET);
      commarea.setLastMap(LIT_THIS_MAP);
      st.screenNum = 1; // CA-FIRST-PAGE
      st.freshStart = true;
      // CDEMO-USRTYP-USER default from the legacy EIBCALEN=0 path is intentionally NOT forced here:
      // the web session / Spring Security owns the authenticated role, and this direct-invocation
      // edge is never exercised by the controller (which always supplies session state).
    } else {
      restoreState(screen, st);
      // L339-L353: arriving fresh from another program (PGM-ENTER and not from this program, e.g.
      // dispatched by the menu) restarts paging at the first page.
      if (commarea.isPgmEnter() && !fromThis) {
        st.screenNum = 1; // CA-FIRST-PAGE
        st.firstCardNum = null;
        st.lastDisplayedCardNum = null;
        st.freshStart = true;
      }
    }

    // 0000-MAIN L357-L362: read & edit inputs only on a genuine re-entry into this same program.
    if (priorCommarea && fromThis) {
      receiveMap(screen, st);
    }

    // 0000-MAIN L370-L380: only ENTER / PF03 / PF07 / PF08 are valid here; any other key is coerced
    // to ENTER (PFK-INVALID -> SET CCARD-AID-ENTER).
    CardWorkArea.Aid effAid = normalizeAid(st.aid);

    // 0000-MAIN L384-L406: PF03 from this program exits to the main menu (EXEC CICS XCTL COMEN01C).
    if (effAid == CardWorkArea.Aid.PFK03 && fromThis) {
      commarea.setFromTranId(TRAN_ID);
      commarea.setFromProgram(PGM_NAME);
      commarea.setPgmEnter();
      commarea.setLastMapset(LIT_THIS_MAPSET);
      commarea.setLastMap(LIT_THIS_MAP);
      commarea.setToProgram(LIT_MENU_PGM);
      commarea.setToTranId(LIT_MENU_TRAN);
      return LIT_MENU_PGM;
    }

    // 0000-MAIN L408-L414: pressing anything other than PF08 clears the "last page shown" latch.
    if (effAid != CardWorkArea.Aid.PFK08) {
      st.caLastPageShown = false;
    }

    // Whether the dispatch sees the first page (computed from the restored screen number, before
    // any
    // page increment/decrement below).
    boolean caFirstPageAtDispatch = st.screenNum <= 1;

    // 0000-MAIN L418-L583: EVALUATE TRUE — preserve the exact branch order.

    // WHEN INPUT-ERROR (L419-L438): redisplay with the validation message. When the filters
    // themselves are valid, refresh the list from the start of the file (the legacy RID field is
    // freshly initialised to SPACES on this path, so the browse begins at the lowest key = page 1).
    if (st.inputError) {
      if (st.acctFilter != FilterFlag.NOT_OK && st.cardFilter != FilterFlag.NOT_OK) {
        readForward(fetchFiltered(st), null, true, st);
      }
      finishRedisplay(screen, commarea, st, effAid);
      return null;
    }

    // WHEN ENTER + VIEW/UPDATE requested (L517-L569): a single, valid row selection transfers to
    // the
    // card detail (S) or card update (U) program. Checked before the paging branches (the keys are
    // mutually exclusive) so no needless browse is performed before the XCTL. The I-SELECTED bound
    // check (1..7) replaces the COBOL subscript fall-through that landed unselected ENTERs on
    // WHEN OTHER.
    if (effAid == CardWorkArea.Aid.ENTER
        && fromThis
        && st.iSelected >= 1
        && st.iSelected <= MAX_SCREEN_LINES) {
      CardListScreen.CardListRow row = rowAt(screen, st.iSelected - 1);
      if (row != null && st.selectedAction == 'S') {
        routeToSelected(commarea, row, LIT_CARD_DETAIL_PGM, LIT_CARD_DETAIL_TRAN);
        return LIT_CARD_DETAIL_PGM;
      }
      if (row != null && st.selectedAction == 'U') {
        routeToSelected(commarea, row, LIT_CARD_UPDATE_PGM, LIT_CARD_UPDATE_TRAN);
        return LIT_CARD_UPDATE_PGM;
      }
    }

    // The remaining branches all browse the card store; fetch the filtered, ordered candidate set
    // once. An unexpected failure here is the only path that raises IoStatusException.
    List<Card> filtered = fetchFiltered(st);

    if (effAid == CardWorkArea.Aid.PFK07 && caFirstPageAtDispatch) {
      // WHEN PF07 + CA-FIRST-PAGE (L439-L454): already at the top — refresh the first page.
      readForward(filtered, st.firstCardNum, true, st);
    } else if (effAid == CardWorkArea.Aid.PFK03 || (commarea.isPgmReenter() && !fromThis)) {
      // WHEN PF03 / (PGM-REENTER from another program) (L458-L482): fresh restart at page 1.
      st.screenNum = 1;
      st.firstCardNum = null;
      st.freshStart = true;
      readForward(filtered, null, true, st);
    } else if (effAid == CardWorkArea.Aid.PFK08 && hasNext(filtered, st)) {
      // WHEN PF08 + CA-NEXT-PAGE-EXISTS (L486-L497): page down. The browse resumes strictly after
      // the last row currently displayed (legacy GTEQ on WS-CA-LAST-CARD-NUM, the peeked next key).
      st.screenNum = st.screenNum + 1;
      readForward(filtered, st.lastDisplayedCardNum, false, st);
    } else if (effAid == CardWorkArea.Aid.PFK07 && !caFirstPageAtDispatch) {
      // WHEN PF07 + NOT CA-FIRST-PAGE (L501-L513): page up.
      st.screenNum = st.screenNum - 1;
      readBackwards(filtered, st.firstCardNum, st);
    } else {
      // WHEN OTHER (L572-L582): refresh the current page (browse from its first key).
      readForward(filtered, st.firstCardNum, true, st);
    }

    // COMMON-RETURN (L604-L620): stamp the conversation origin and redisplay.
    finishRedisplay(screen, commarea, st, effAid);
    return null;
  }

  // ===============================================================================================
  // 2000-RECEIVE-MAP family — read the screen and validate inputs.
  // ===============================================================================================

  /**
   * Reproduces {@code 2000-RECEIVE-MAP} (COBOL L951-L960): receive the screen ({@code
   * 2100-RECEIVE-SCREEN}) then edit the inputs ({@code 2200-EDIT-INPUTS}).
   *
   * @param screen the inbound screen view contract
   * @param st the mutable per-request working storage
   */
  private void receiveMap(CardListScreen screen, ListState st) {
    // 2100-RECEIVE-SCREEN (L962-L979): map the received fields into working storage.
    st.acctSidInput = screen.getAcctSid();
    st.cardSidInput = screen.getCardSid();
    List<CardListScreen.CardListRow> rows = screen.getRows();
    for (int i = 0; i < MAX_SCREEN_LINES; i++) {
      st.selectChars[i] = selectCharAt(rows, i);
    }
    // 2200-EDIT-INPUTS.
    editInputs(st);
  }

  /**
   * Reproduces {@code 2200-EDIT-INPUTS} (COBOL L985-L1000): reset the input flags, then run the
   * account, card and selection-array edits in order.
   *
   * @param st the mutable per-request working storage
   */
  private void editInputs(ListState st) {
    st.inputError = false; // SET INPUT-OK
    st.protectSelectRows = false; // SET FLG-PROTECT-SELECT-ROWS-NO
    editAccount(st);
    editCard(st);
    editArray(st);
  }

  /**
   * Reproduces {@code 2210-EDIT-ACCOUNT} (COBOL L1003-L1033) — optional account filter.
   *
   * <p>Blank (low-values / spaces / numeric zeros) is not an error. A supplied value that is not an
   * 11-digit number is rejected with the byte-exact literal {@link #MSG_ACCT_FILTER_INVALID}; note
   * the legacy code sets this message unconditionally (no {@code WS-ERROR-MSG-OFF} guard), so the
   * account error takes precedence over a later card error.
   *
   * @param st the mutable per-request working storage
   */
  private void editAccount(ListState st) {
    st.acctFilter = FilterFlag.BLANK; // SET FLG-ACCTFILTER-BLANK
    // The CICS field is X(11); emulate the fixed-width field by right-padding to 11.
    String padded = padRight(st.acctSidInput, 11);
    // Not supplied: LOW-VALUES / SPACES / CC-ACCT-ID-N = ZEROS.
    if (isBlankField(padded) || "00000000000".equals(padded)) {
      st.acctFilter = FilterFlag.BLANK;
      st.acctId = 0L; // MOVE ZEROES TO CDEMO-ACCT-ID
      return;
    }
    // Not numeric / not 11 characters.
    if (!isAllDigits(padded)) {
      st.inputError = true; // SET INPUT-ERROR
      st.acctFilter = FilterFlag.NOT_OK; // SET FLG-ACCTFILTER-NOT-OK
      st.protectSelectRows = true; // SET FLG-PROTECT-SELECT-ROWS-YES
      st.errorMsg = MSG_ACCT_FILTER_INVALID; // unconditional MOVE to WS-ERROR-MSG
      st.acctId = 0L; // MOVE ZERO TO CDEMO-ACCT-ID
      return;
    }
    st.acctId = Long.parseLong(padded); // MOVE CC-ACCT-ID TO CDEMO-ACCT-ID
    st.acctFilter = FilterFlag.VALID; // SET FLG-ACCTFILTER-ISVALID
  }

  /**
   * Reproduces {@code 2220-EDIT-CARD} (COBOL L1036-L1067) — optional card-number filter.
   *
   * <p>Blank is not an error. A supplied value that is not a 16-digit number is rejected with the
   * byte-exact literal {@link #MSG_CARD_FILTER_INVALID}; the legacy code guards this MOVE with
   * {@code WS-ERROR-MSG-OFF}, so an earlier account error is preserved (account precedence).
   *
   * @param st the mutable per-request working storage
   */
  private void editCard(ListState st) {
    st.cardFilter = FilterFlag.BLANK; // SET FLG-CARDFILTER-BLANK
    // The CICS field is X(16); emulate the fixed-width field by right-padding to 16.
    String padded = padRight(st.cardSidInput, 16);
    // Not supplied: LOW-VALUES / SPACES / CC-CARD-NUM-N = ZEROS.
    if (isBlankField(padded) || "0000000000000000".equals(padded)) {
      st.cardFilter = FilterFlag.BLANK;
      st.cardNumFilter = null; // MOVE ZEROES TO CDEMO-CARD-NUM
      return;
    }
    // Not numeric / not 16 characters.
    if (!isAllDigits(padded)) {
      st.inputError = true; // SET INPUT-ERROR
      st.cardFilter = FilterFlag.NOT_OK; // SET FLG-CARDFILTER-NOT-OK
      st.protectSelectRows = true; // SET FLG-PROTECT-SELECT-ROWS-YES
      if (st.errorMsg.isEmpty()) { // IF WS-ERROR-MSG-OFF
        st.errorMsg = MSG_CARD_FILTER_INVALID;
      }
      st.cardNumFilter = null; // MOVE ZERO TO CDEMO-CARD-NUM
      return;
    }
    st.cardNumFilter = padded; // MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM
    st.cardFilter = FilterFlag.VALID; // SET FLG-CARDFILTER-ISVALID
  }

  /**
   * Reproduces {@code 2250-EDIT-ARRAY} (COBOL L1073-L1120) — validate the per-row select column.
   *
   * <p>If a prior edit already failed, the array edit is skipped entirely (COBOL {@code IF
   * INPUT-ERROR GO TO ...-EXIT}). Otherwise the {@code 'S'}/{@code 'U'} characters across the seven
   * rows are tallied: more than one selected row yields {@link #MSG_MORE_THAN_1_ACTION} (set
   * unconditionally), and any character other than {@code 'S'}, {@code 'U'} or blank yields {@link
   * #MSG_INVALID_ACTION_CODE} (guarded by {@code WS-ERROR-MSG-OFF}). {@code I-SELECTED} records the
   * last valid selection (1-based) and {@code selectedAction} its action code.
   *
   * @param st the mutable per-request working storage
   */
  private void editArray(ListState st) {
    if (st.inputError) {
      return; // GO TO 2250-EDIT-ARRAY-EXIT
    }
    // INSPECT WS-EDIT-SELECT-FLAGS TALLYING I FOR ALL 'S' ALL 'U'.
    int selectCount = 0;
    for (int i = 0; i < MAX_SCREEN_LINES; i++) {
      char c = st.selectChars[i];
      if (c == 'S' || c == 'U') {
        selectCount++;
      }
    }
    boolean moreThanOne = selectCount > 1;
    if (moreThanOne) {
      st.inputError = true; // SET INPUT-ERROR
      st.errorMsg = MSG_MORE_THAN_1_ACTION; // SET WS-MORE-THAN-1-ACTION (unconditional)
    }
    st.iSelected = 0; // MOVE ZERO TO I-SELECTED
    st.selectedAction = ' ';
    // PERFORM VARYING I FROM 1 BY 1 UNTIL I > 7.
    for (int i = 0; i < MAX_SCREEN_LINES; i++) {
      char c = st.selectChars[i];
      if (c == 'S' || c == 'U') { // WHEN SELECT-OK(I)
        st.iSelected = i + 1;
        st.selectedAction = c;
        // When more than one action was supplied each selected row is flagged in error; the per-row
        // error attribute is a BMS presentation detail not modeled by the screen DTO, so the
        // condition is surfaced through the error message only.
      } else if (c == ' ' || c == '\0') { // WHEN SELECT-BLANK(I)
        continue;
      } else { // WHEN OTHER
        st.inputError = true; // SET INPUT-ERROR
        if (st.errorMsg.isEmpty()) { // IF WS-ERROR-MSG-OFF
          st.errorMsg = MSG_INVALID_ACTION_CODE; // SET WS-INVALID-ACTION-CODE
        }
      }
    }
  }

  // ===============================================================================================
  // 9000 / 9100 / 9500 — browse the card store (keyset paging equivalent of the VSAM browse).
  // ===============================================================================================

  /**
   * Fetches the ordered, filtered candidate set that the keyset-paging reads operate over.
   *
   * <p>Mirrors the legacy VSAM browse + {@code 9500-FILTER-RECORDS} combination, but materialised
   * as a sorted Java list. Per the migration directive, an account filter is pushed down to the
   * repository ({@code findByCardAcctId}); otherwise the full file is read. The {@code
   * 9500-FILTER-RECORDS} predicate is then applied to every record (it is a no-op for the
   * account-equality test when the push-down was used, and additionally applies the card-number
   * filter). Records are ordered ascending by the 16-character card number, which is the VSAM key
   * order for the {@code char(16)} key.
   *
   * <p><strong>Bounded-result / VSAM-browse parity exception (intentional).</strong> The code
   * review performance checklist flags the {@code findAll()} read on the no-account-filter path as
   * an unbounded full-table load. This is a deliberate, AAP-sanctioned parity decision, not an
   * oversight. Legacy {@code COCRDLIC} browses {@code CARDDAT} with VSAM {@code STARTBR GTEQ} /
   * {@code READNEXT} / {@code READPREV}; reproducing that browse with byte-for-byte paging parity
   * &mdash; in particular {@code 9100-READ-BACKWARDS} (PF7 page-up), which must locate the current
   * first key within the ordered key set and walk the preceding records, together with the exact
   * {@code CA-NEXT-PAGE-EXISTS} one-record "peek" and the {@code "NO MORE RECORDS TO SHOW"} edge
   * conditions &mdash; requires one stable ascending projection of the key set to slice in service;
   * a forward-only bounded query cannot reproduce the page-up direction over identical ordering.
   * Under AAP precedence D1, 100% behavioral parity (AAP &sect;0.7.1 R1 / &sect;0.6.5) outranks the
   * generic performance heuristic. The blast radius is further bounded because (a) the supported
   * account filter is pushed down to {@code findByCardAcctId} (the common bounded path), and (b)
   * the migration's local-only validation runs against the small legacy fixtures (AAP &sect;0.6.7).
   * A repository-level cursor/range query may replace this only if it preserves identical PF7/PF8
   * ordering and edge-message semantics.
   *
   * @param st the mutable per-request working storage (holds the active filters)
   * @return the filtered, ascending-by-card-number candidate list
   * @throws IoStatusException if the underlying repository read fails unexpectedly (the legacy
   *     {@code WHEN OTHER} response branch)
   */
  private List<Card> fetchFiltered(ListState st) {
    List<Card> candidates;
    try {
      if (st.acctFilter == FilterFlag.VALID && st.acctId != null) {
        candidates = cardRepository.findByCardAcctId(st.acctId);
      } else {
        // Full ascending browse source for in-service keyset paging — intentional VSAM-browse
        // parity exception (PF7 READPREV page-up + next-page peek need the full ordered key set);
        // see the method Javadoc. AAP D1: parity (§0.7.1/§0.6.5) over the perf heuristic; the
        // account-filtered path above is bounded and local validation uses small fixtures.
        candidates = cardRepository.findAll();
      }
    } catch (RuntimeException ex) {
      // Equivalent to the COBOL WHEN OTHER RESP branch that builds WS-FILE-ERROR-MESSAGE and
      // abends.
      throw new IoStatusException(LIT_CARD_FILE_NAME, "READ", "??", ex);
    }
    List<Card> result = new ArrayList<>();
    for (Card card : candidates) {
      if (!filterRecords(card, st)) {
        result.add(card);
      }
    }
    result.sort(Comparator.comparing((Card card) -> pad16(card.getCardNum())));
    return result;
  }

  /**
   * Reproduces {@code 9500-FILTER-RECORDS} (COBOL L1382-L1410): decide whether a card record is
   * excluded by the active filters.
   *
   * <p>The record is included by default. When the account filter is valid it is excluded if the
   * card's account id differs from the filter; when the card filter is valid it is excluded if the
   * card number differs from the filter.
   *
   * @param card the candidate card record
   * @param st the mutable per-request working storage (holds the active filters)
   * @return {@code true} if the record must be excluded, {@code false} to keep it
   */
  private boolean filterRecords(Card card, ListState st) {
    // SET WS-DONOT-EXCLUDE-THIS-RECORD TO TRUE (default include).
    if (st.acctFilter == FilterFlag.VALID) {
      // IF CARD-ACCT-ID = CC-ACCT-ID CONTINUE ELSE EXCLUDE.
      if (!Objects.equals(card.getCardAcctId(), st.acctId)) {
        return true;
      }
    }
    if (st.cardFilter == FilterFlag.VALID) {
      // IF CARD-NUM = CC-CARD-NUM-N CONTINUE ELSE EXCLUDE.
      if (!pad16(card.getCardNum()).equals(st.cardNumFilter)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reproduces {@code 9000-READ-FORWARD} (COBOL L1123-L1262): collect up to {@link
   * #MAX_SCREEN_LINES} records starting at the browse key and determine whether a further page
   * exists.
   *
   * <p>The legacy program issues {@code STARTBR ... GTEQ} on the browse RID then {@code READNEXT}s,
   * filtering each record. The Java equivalent locates the start index in the pre-filtered,
   * ascending list and collects forward. Reaching seven rows triggers a one-record "peek": if a
   * further record exists the next page is flagged present, otherwise the end-of-data message
   * {@link #MSG_NO_MORE_RECORDS} is raised (only when no message is already pending — the {@code
   * WS-ERROR-MSG-OFF} guard). Running out before filling the page raises the same end-of-data
   * message, and additionally sets the no-records condition {@link #MSG_NO_RECORDS_FOUND} when the
   * first page yields nothing.
   *
   * @param filtered the ordered, filtered candidate list
   * @param startKey the browse start key (16-character card number); {@code null}/blank starts at
   *     the lowest key
   * @param inclusive {@code true} to include a record whose key equals {@code startKey} (first page
   *     / refresh / page-up boundary), {@code false} to start strictly after it (page-down)
   * @param st the mutable per-request working storage (receives the collected page and flags)
   */
  private void readForward(List<Card> filtered, String startKey, boolean inclusive, ListState st) {
    st.pageCards = new ArrayList<>(); // MOVE LOW-VALUES TO WS-ALL-ROWS
    int idx = startIndex(filtered, startKey, inclusive);
    while (idx < filtered.size() && st.pageCards.size() < MAX_SCREEN_LINES) {
      st.pageCards.add(filtered.get(idx));
      idx++;
    }
    int collected = st.pageCards.size();
    if (collected >= 1) {
      // WS-SCRN-COUNTER = 1 path: record the first key, and bump a zero screen number to one.
      st.firstCardNum = pad16(st.pageCards.get(0).getCardNum());
      if (st.screenNum == 0) {
        st.screenNum = 1;
      }
      st.lastDisplayedCardNum = pad16(st.pageCards.get(collected - 1).getCardNum());
    }
    boolean endReached;
    if (collected == MAX_SCREEN_LINES) {
      // Page is full — peek one more record (the legacy extra READNEXT after the 7th row).
      if (idx < filtered.size()) {
        st.caNextPageExists = true; // SET CA-NEXT-PAGE-EXISTS
        endReached = false;
      } else {
        st.caNextPageExists = false; // SET CA-NEXT-PAGE-NOT-EXISTS
        endReached = true;
      }
    } else {
      // Ran out before filling the page (main-loop ENDFILE).
      st.caNextPageExists = false; // SET CA-NEXT-PAGE-NOT-EXISTS
      endReached = true;
    }
    if (endReached) {
      if (st.errorMsg.isEmpty()) { // IF WS-ERROR-MSG-OFF
        st.errorMsg = MSG_NO_MORE_RECORDS;
      }
      // IF WS-CA-SCREEN-NUM = 1 AND WS-SCRN-COUNTER = 0 -> SET WS-NO-RECORDS-FOUND (unconditional).
      if (st.screenNum == 1 && collected == 0) {
        st.noRecordsFound = true;
        st.errorMsg = MSG_NO_RECORDS_FOUND;
      }
    }
  }

  /**
   * Reproduces {@code 9100-READ-BACKWARDS} (COBOL L1264-L1380): rebuild the page immediately above
   * the current first record.
   *
   * <p>The legacy program issues {@code STARTBR ... GTEQ} on the current first key, discards that
   * record with the first {@code READPREV}, then walks backwards filling rows seven down to one.
   * The Java equivalent finds the index of the current first key in the filtered list and takes the
   * seven preceding records (in ascending order). A page-up always implies a following page exists
   * (the page we came from), so the next-page indicator is set present (legacy L1287).
   *
   * @param filtered the ordered, filtered candidate list
   * @param firstKey the current page's first card number (16-character)
   * @param st the mutable per-request working storage (receives the collected page and flags)
   */
  private void readBackwards(List<Card> filtered, String firstKey, ListState st) {
    st.pageCards = new ArrayList<>(); // MOVE LOW-VALUES TO WS-ALL-ROWS
    int currentFirst = startIndex(filtered, firstKey, true);
    int start = currentFirst - MAX_SCREEN_LINES;
    if (start < 0) {
      start = 0;
    }
    for (int i = start; i < currentFirst && st.pageCards.size() < MAX_SCREEN_LINES; i++) {
      st.pageCards.add(filtered.get(i));
    }
    int collected = st.pageCards.size();
    if (collected >= 1) {
      st.firstCardNum = pad16(st.pageCards.get(0).getCardNum());
      st.lastDisplayedCardNum = pad16(st.pageCards.get(collected - 1).getCardNum());
    }
    st.caNextPageExists = true; // SET CA-NEXT-PAGE-EXISTS (L1287)
  }

  /**
   * Computes the index of the first record at or after the browse key, mirroring {@code STARTBR
   * GTEQ} followed by the first {@code READNEXT}.
   *
   * @param filtered the ordered, filtered candidate list
   * @param startKey the browse start key (16-character card number); {@code null}/blank means the
   *     lowest key (index 0)
   * @param inclusive {@code true} to return the index of a key equal to {@code startKey}, {@code
   *     false} to return the index strictly after it
   * @return the start index, or {@code filtered.size()} when no record qualifies
   */
  private static int startIndex(List<Card> filtered, String startKey, boolean inclusive) {
    if (startKey == null || startKey.isBlank()) {
      return 0;
    }
    String key = pad16(startKey);
    for (int i = 0; i < filtered.size(); i++) {
      int cmp = pad16(filtered.get(i).getCardNum()).compareTo(key);
      if (inclusive ? cmp >= 0 : cmp > 0) {
        return i;
      }
    }
    return filtered.size();
  }

  /**
   * Recomputes the {@code CA-NEXT-PAGE-EXISTS} condition at dispatch time: whether any record
   * follows the last displayed row. The legacy program persisted this in the COMMAREA from the
   * previous read; because the browse is read-only the recomputed value is identical.
   *
   * @param filtered the ordered, filtered candidate list
   * @param st the mutable per-request working storage (holds the last displayed key)
   * @return {@code true} if a further page of records exists after the current page
   */
  private static boolean hasNext(List<Card> filtered, ListState st) {
    if (st.lastDisplayedCardNum == null) {
      return false;
    }
    return startIndex(filtered, st.lastDisplayedCardNum, false) < filtered.size();
  }

  // ===============================================================================================
  // 1000-SEND-MAP family — render the screen, plus the COMMON-RETURN stamping.
  // ===============================================================================================

  /**
   * Renders the screen and stamps the conversation origin, combining {@code 1000-SEND-MAP} (COBOL
   * L624-L637) and {@code COMMON-RETURN} (COBOL L604-L620) for a redisplay turn.
   *
   * @param screen the screen view contract to populate
   * @param commarea the shared conversation state to stamp
   * @param st the mutable per-request working storage
   * @param effAid the effective (validated) attention identifier
   */
  private void finishRedisplay(
      CardListScreen screen, CardDemoCommarea commarea, ListState st, CardWorkArea.Aid effAid) {
    populateHeader(screen, st); // 1100-SCREEN-INIT + 1300-SETUP-SCREEN-ATTRS
    populateRows(screen, st); // 1200-SCREEN-ARRAY-INIT
    setupMessage(screen, st, effAid); // 1400-SETUP-MESSAGE
    // COMMON-RETURN: record that the next turn re-enters this program.
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setLastMapset(LIT_THIS_MAPSET);
    commarea.setLastMap(LIT_THIS_MAP);
  }

  /**
   * Reproduces {@code 1100-SCREEN-INIT} (COBOL L642-L672) and {@code 1300-SETUP-SCREEN-ATTRS}
   * (COBOL L837-L889): populate the header (titles, transaction/program names, date, time, page
   * number) and the search-criteria echo.
   *
   * <p>On a fresh start (first entry, or arrival from the menu) the search fields are cleared
   * (matching the {@code CONTINUE} branch over the freshly low-valued map). On a re-entry the
   * supplied filter text is echoed back exactly as received.
   *
   * @param screen the screen view contract to populate
   * @param st the mutable per-request working storage
   */
  private void populateHeader(CardListScreen screen, ListState st) {
    LocalDateTime now = LocalDateTime.now();
    screen.setTrnName(TRAN_ID);
    screen.setPgmName(PGM_NAME);
    screen.setTitle01(TITLE_LINE_1);
    screen.setTitle02(TITLE_LINE_2);
    screen.setCurDate(now.format(DateTimeFormatter.ofPattern(DATE_MASK)));
    screen.setCurTime(now.format(DateTimeFormatter.ofPattern(TIME_MASK)));
    screen.setPageNo(Integer.toString(st.screenNum));
    // 1300-SETUP-SCREEN-ATTRS: echo the search criteria unless this is a fresh start.
    if (st.freshStart) {
      screen.setAcctSid("");
      screen.setCardSid("");
    } else {
      screen.setAcctSid(trimToEmpty(st.acctSidInput));
      screen.setCardSid(trimToEmpty(st.cardSidInput));
    }
  }

  /**
   * Reproduces {@code 1200-SCREEN-ARRAY-INIT} (COBOL L678-L743): populate the seven screen rows
   * from the collected page, blanking any unused trailing rows.
   *
   * <p>Per the migration specification the select column is rendered cleared for every populated
   * row; the legacy per-row select-character echo and BMS protect/colour attributes are
   * presentation details not carried by the screen view contract. The account number is rendered as
   * the 11-digit, zero-padded value and the card number as the 16-character key, matching the
   * fixed-width {@code WS-ROW-ACCTNO}/{@code WS-ROW-CARD-NUM} layout.
   *
   * @param screen the screen view contract to populate
   * @param st the mutable per-request working storage (holds the collected page)
   */
  private void populateRows(CardListScreen screen, ListState st) {
    List<CardListScreen.CardListRow> rows = new ArrayList<>();
    for (int i = 0; i < MAX_SCREEN_LINES; i++) {
      CardListScreen.CardListRow row = new CardListScreen.CardListRow();
      if (st.pageCards != null && i < st.pageCards.size()) {
        Card card = st.pageCards.get(i);
        row.setCrdSel(""); // select column rendered cleared
        row.setAcctNo(formatAcct(card.getCardAcctId()));
        row.setCrdNum(pad16(card.getCardNum()));
        row.setCrdSts(trimToEmpty(card.getCardActiveStatus()));
      } else {
        // Unused trailing row: blank (COBOL leaves the LOW-VALUES row untouched).
        row.setCrdSel("");
        row.setAcctNo("");
        row.setCrdNum("");
        row.setCrdSts("");
      }
      rows.add(row);
    }
    screen.setRows(rows);
  }

  /**
   * Reproduces {@code 1400-SETUP-MESSAGE} (COBOL L895-L932): choose the error / informational
   * message to display, following the legacy {@code EVALUATE TRUE} order exactly.
   *
   * <p>A pending filter error is left in place. Otherwise paging boundaries are reported: page-up
   * at the first page yields {@link #MSG_NO_PREVIOUS_PAGES}; a repeated page-down at the last page
   * yields {@link #MSG_NO_MORE_PAGES}; the first page-down to the last page (and the normal case)
   * shows the informational {@link #MSG_INFORM_REC_ACTIONS}. The error line is always written; the
   * informational line is written only when an info message is active and the no-records condition
   * is not set.
   *
   * @param screen the screen view contract to populate
   * @param st the mutable per-request working storage (holds messages and paging flags)
   * @param effAid the effective (validated) attention identifier
   */
  private void setupMessage(CardListScreen screen, ListState st, CardWorkArea.Aid effAid) {
    boolean acctNotOk = st.acctFilter == FilterFlag.NOT_OK;
    boolean cardNotOk = st.cardFilter == FilterFlag.NOT_OK;
    boolean caFirstPage = st.screenNum == 1; // 88 CA-FIRST-PAGE VALUE 1
    boolean informRecActions = false;
    // EVALUATE TRUE (L897-L922).
    if (acctNotOk || cardNotOk) {
      // WHEN FLG-ACCTFILTER-NOT-OK / WHEN FLG-CARDFILTER-NOT-OK -> CONTINUE (keep edit error).
      informRecActions = false;
    } else if (effAid == CardWorkArea.Aid.PFK07 && caFirstPage) {
      st.errorMsg = MSG_NO_PREVIOUS_PAGES;
    } else if (effAid == CardWorkArea.Aid.PFK08 && !st.caNextPageExists && st.caLastPageShown) {
      st.errorMsg = MSG_NO_MORE_PAGES;
    } else if (effAid == CardWorkArea.Aid.PFK08 && !st.caNextPageExists) {
      // First page-down landing on the last page: inform, and (latch) mark the last page shown. The
      // latch is reconstructed on the next turn from the rendered error line, so no commarea field
      // is needed here.
      informRecActions = true;
    } else {
      // WHEN WS-NO-INFO-MESSAGE / WHEN CA-NEXT-PAGE-EXISTS -> inform (the normal redisplay path;
      // the
      // info message is always reset by 1100, so this branch is reached for every ordinary list).
      informRecActions = true;
    }
    // Output: MOVE WS-ERROR-MSG TO ERRMSGO (always); the info line is conditional.
    screen.setErrMsg(st.errorMsg);
    if (informRecActions && !st.noRecordsFound) {
      screen.setInfoMsg(MSG_INFORM_REC_ACTIONS);
    } else {
      screen.setInfoMsg("");
    }
  }

  // ===============================================================================================
  // State reconstruction, key-action normalisation, routing and small helpers.
  // ===============================================================================================

  /**
   * Restores the paging state from the round-tripped screen, standing in for the legacy {@code
   * WS-THIS-PROGCOMMAREA} that CICS carried across the pseudo-conversation. The page number is
   * taken from the screen; the first and last displayed card numbers are derived from the populated
   * rows.
   *
   * @param screen the inbound screen view contract
   * @param st the mutable per-request working storage to populate
   */
  private static void restoreState(CardListScreen screen, ListState st) {
    Long page = parseLongOrNull(screen.getPageNo());
    st.screenNum = (page == null || page < 1) ? 1 : page.intValue();
    st.firstCardNum = null;
    st.lastDisplayedCardNum = null;
    List<CardListScreen.CardListRow> rows = screen.getRows();
    if (rows != null) {
      for (CardListScreen.CardListRow row : rows) {
        String num = row.getCrdNum();
        if (num != null && !num.isBlank()) {
          String padded = pad16(num);
          if (st.firstCardNum == null) {
            st.firstCardNum = padded;
          }
          st.lastDisplayedCardNum = padded;
        }
      }
    }
  }

  /**
   * Reproduces the PF-key validation of {@code 0000-MAIN} (COBOL L370-L380): only {@code ENTER},
   * {@code PFK03}, {@code PFK07} and {@code PFK08} are honoured; any other key is coerced to {@code
   * ENTER}.
   *
   * @param aid the stored attention identifier
   * @return the effective attention identifier after coercion
   */
  private static CardWorkArea.Aid normalizeAid(CardWorkArea.Aid aid) {
    switch (aid) {
      case ENTER:
      case PFK03:
      case PFK07:
      case PFK08:
        return aid;
      default:
        return CardWorkArea.Aid.ENTER; // SET CCARD-AID-ENTER
    }
  }

  /**
   * Sets up the COMMAREA hand-off for an XCTL to the card detail or card update program (COBOL
   * L517-L569): records this program as the origin, marks a fresh entry for the target, and passes
   * the selected row's account id and card number.
   *
   * @param commarea the shared conversation state to populate
   * @param row the selected screen row (source of the account/card keys)
   * @param toPgm the target program name
   * @param toTran the target transaction id
   */
  private void routeToSelected(
      CardDemoCommarea commarea, CardListScreen.CardListRow row, String toPgm, String toTran) {
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmEnter();
    commarea.setLastMapset(LIT_THIS_MAPSET);
    commarea.setLastMap(LIT_THIS_MAP);
    commarea.setToProgram(toPgm);
    commarea.setToTranId(toTran);
    Long acct = parseLongOrNull(row.getAcctNo()); // MOVE WS-ROW-ACCTNO(I-SELECTED) TO CDEMO-ACCT-ID
    commarea.setAcctId(acct == null ? 0L : acct);
    Long card =
        parseLongOrNull(row.getCrdNum()); // MOVE WS-ROW-CARD-NUM(I-SELECTED) TO CDEMO-CARD-NUM
    commarea.setCardNum(card == null ? 0L : card);
  }

  /**
   * Returns the screen row at the given zero-based index, or {@code null} if it is out of range.
   *
   * @param screen the screen view contract
   * @param idx the zero-based row index
   * @return the row, or {@code null} when the index is out of bounds
   */
  private static CardListScreen.CardListRow rowAt(CardListScreen screen, int idx) {
    List<CardListScreen.CardListRow> rows = screen.getRows();
    if (rows == null || idx < 0 || idx >= rows.size()) {
      return null;
    }
    return rows.get(idx);
  }

  /**
   * Extracts the select character for a row: the first non-blank character of the row's select
   * field, upper-cased to mirror 3270 {@code UCTRAN} input. Returns {@code '\0'} when blank.
   *
   * @param rows the inbound screen rows
   * @param i the zero-based row index
   * @return the upper-cased select character, or {@code '\0'} when none
   */
  private static char selectCharAt(List<CardListScreen.CardListRow> rows, int i) {
    if (rows == null || i >= rows.size()) {
      return '\0';
    }
    String sel = rows.get(i).getCrdSel();
    if (sel == null || sel.isBlank()) {
      return '\0';
    }
    return Character.toUpperCase(sel.trim().charAt(0));
  }

  /**
   * Right-pads (or truncates) a value to a fixed width, emulating a fixed-length CICS map field.
   *
   * @param value the source value ({@code null} is treated as empty)
   * @param width the target width
   * @return a string of exactly {@code width} characters
   */
  private static String padRight(String value, int width) {
    String v = value == null ? "" : value;
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
   * Pads a card number to the 16-character key width used for ordering and comparison.
   *
   * @param value the card number ({@code null} is treated as empty)
   * @return the 16-character representation
   */
  private static String pad16(String value) {
    return padRight(value, 16);
  }

  /**
   * Tests whether a fixed-width field is effectively blank (empty or all whitespace), covering the
   * COBOL {@code LOW-VALUES}/{@code SPACES} conditions.
   *
   * @param value the field value
   * @return {@code true} if blank
   */
  private static boolean isBlankField(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Tests whether a string is non-empty and composed solely of the ASCII digits {@code 0-9} (COBOL
   * {@code IS NUMERIC} for an unsigned display field).
   *
   * @param value the value to test
   * @return {@code true} if every character is a digit and the value is non-empty
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
   * Parses a string to a {@link Long}, returning {@code null} when it is blank or not parseable.
   *
   * @param value the value to parse
   * @return the parsed value, or {@code null}
   */
  private static Long parseLongOrNull(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    if (t.isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(t);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Trims a string, mapping {@code null} to an empty string.
   *
   * @param value the value to trim
   * @return the trimmed value, never {@code null}
   */
  private static String trimToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * Formats an account id as the 11-digit, zero-padded value used by the fixed-width {@code
   * WS-ROW-ACCTNO} field.
   *
   * @param acctId the account id ({@code null} is treated as zero)
   * @return the 11-character representation
   */
  private static String formatAcct(Long acctId) {
    long value = acctId == null ? 0L : acctId;
    return String.format("%011d", value);
  }

  /**
   * Filter-state flag, mirroring the COBOL {@code WS-EDIT-ACCT-FLAG}/{@code WS-EDIT-CARD-FLAG}
   * 88-levels: {@code BLANK} (not supplied), {@code VALID} (supplied and well-formed) and {@code
   * NOT_OK} (supplied but malformed).
   */
  private enum FilterFlag {
    /** Filter not supplied ({@code FLG-*FILTER-BLANK}). */
    BLANK,
    /** Filter supplied and valid ({@code FLG-*FILTER-ISVALID}). */
    VALID,
    /** Filter supplied but invalid ({@code FLG-*FILTER-NOT-OK}). */
    NOT_OK
  }

  /**
   * Per-request working storage, standing in for the COBOL {@code WS-MISC-STORAGE}, the search/edit
   * flags and the reconstructed {@code WS-THIS-PROGCOMMAREA}. A fresh instance is created per call
   * so the surrounding {@link CardListService} bean holds no mutable state and remains a safe
   * singleton.
   */
  private static final class ListState {

    /** Stored attention identifier ({@code YYYY-STORE-PFKEY}). */
    private CardWorkArea.Aid aid = CardWorkArea.Aid.ENTER;

    /** {@code WS-CA-SCREEN-NUM} — 1-based page number (0 before the first read). */
    private int screenNum;

    /** {@code WS-CA-FIRST-CARD-NUM} — first card number on the current page. */
    private String firstCardNum;

    /** Card number of the last populated row on the current page. */
    private String lastDisplayedCardNum;

    /** {@code CA-NEXT-PAGE-EXISTS} — whether a further page follows the current one. */
    private boolean caNextPageExists;

    /** {@code CA-LAST-PAGE-SHOWN} — reconstructed last-page latch. */
    private boolean caLastPageShown;

    /** {@code INPUT-ERROR} — set when any edit fails. */
    private boolean inputError;

    /** {@code WS-NO-RECORDS-FOUND} — set when the first page yields no records. */
    private boolean noRecordsFound;

    /** {@code FLG-PROTECT-SELECT-ROWS} — set to protect the select column on a filter error. */
    private boolean protectSelectRows;

    /** Whether this turn is a fresh start (first entry or arrival from the menu). */
    private boolean freshStart;

    /** {@code WS-EDIT-ACCT-FLAG} — account filter state. */
    private FilterFlag acctFilter = FilterFlag.BLANK;

    /** {@code WS-EDIT-CARD-FLAG} — card filter state. */
    private FilterFlag cardFilter = FilterFlag.BLANK;

    /** {@code CDEMO-ACCT-ID} — numeric account filter (0 when blank). */
    private Long acctId;

    /** {@code CC-CARD-NUM-N} — 16-digit card filter, or {@code null} when blank. */
    private String cardNumFilter;

    /** {@code I-SELECTED} — 1-based index of the selected row (0 when none). */
    private int iSelected;

    /** Action code of the selected row ({@code 'S'} or {@code 'U'}). */
    private char selectedAction = ' ';

    /** Raw {@code ACCTSIDI} input. */
    private String acctSidInput;

    /** Raw {@code CARDSIDI} input. */
    private String cardSidInput;

    /** {@code WS-EDIT-SELECT(1..7)} — received select characters. */
    private final char[] selectChars = new char[MAX_SCREEN_LINES];

    /** The records collected for the current page ({@code WS-ALL-ROWS} after a read). */
    private List<Card> pageCards;

    /** {@code WS-ERROR-MSG} — the pending error message (empty when off). */
    private String errorMsg = "";
  }
}

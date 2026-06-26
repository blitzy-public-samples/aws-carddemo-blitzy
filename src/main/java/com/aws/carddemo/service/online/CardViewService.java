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
import com.aws.carddemo.dto.screen.CardViewScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Online <strong>card detail / view</strong> business-logic service, migrated from the legacy CICS
 * COBOL program {@code COCRDSLC} (CICS transaction {@code CCDL}; behavioral specification {@code
 * legacy/app/cbl/COCRDSLC.cbl}).
 *
 * <p>This service reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.7.1) &mdash; the pseudo-conversational, read-only single-card lookup screen. {@code
 * COCRDSLC} is a <em>STYLE&nbsp;A</em> numbered-paragraph program: on first entry it paints the
 * search screen, on re-entry it validates the account and card filters the operator typed, and when
 * the filters are valid it reads the card and displays its detail. The lookup is performed
 * <strong>by card number only</strong> ({@link CardRepository#findById(Object)}); the account
 * number is validated as a filter but is <em>not</em> a lookup key (mirroring the COBOL {@code
 * 9100-GETCARD-BYACCTCARD} {@code READ FILE('CARDDAT') RIDFLD(CC-CARD-NUM)} at L736-750).
 *
 * <p><strong>Layering.</strong> The {@link com.aws.carddemo.web web} controller layer (sibling
 * {@code CardViewController}, not yet created) owns the HTTP request/response, the {@link
 * CardDemoCommarea} session state, BMS-equivalent screen rendering ({@code EXEC CICS SEND}/{@code
 * RECEIVE}), the screen header / date-time / attribute / colour / cursor painting (COBOL {@code
 * 1100-SCREEN-INIT} and {@code 1300-SETUP-SCREEN-ATTRS}), and the resolution of the raw 3270
 * attention identifier into a {@link CardWorkArea.Aid}. This service is invoked with those
 * already-resolved inputs and returns the next program to route to (the {@code EXEC CICS XCTL}
 * target) or {@code null} to redisplay the current screen.
 *
 * <p><strong>Read-only / display semantics</strong> (agent prompt rule&nbsp;7). Not-found and
 * input-validation conditions are surfaced as on-screen messages and a redisplay (the COBOL {@code
 * SEND MAP} then {@code RETURN}); they are <em>never</em> thrown. Only a genuinely unexpected
 * data-access failure (the COBOL {@code WHEN OTHER} file-error branch, L762-771) is translated to
 * {@link IoStatusException} so the {@code GlobalExceptionHandler} can surface it with CICS
 * RESP/abend parity (AAP &sect;0.6.4, &sect;0.6.6). FILE STATUS {@code '23'} (record not found)
 * maps to {@link Optional#empty()} and a user-facing message, not an exception.
 *
 * <p><strong>Statelessness.</strong> The class is a Spring {@link Service} singleton and holds
 * <em>no</em> mutable instance state (agent prompt rule&nbsp;4): the only field is the injected,
 * {@code final} {@link CardRepository}. All pseudo-conversational state is carried in the {@link
 * CardDemoCommarea} and the {@link CardViewScreen}; the per-invocation working values that the
 * COBOL kept in {@code WORKING-STORAGE} (the {@code WS-INPUT-FLAG}, {@code WS-EDIT-*-FLAG}, {@code
 * WS-RETURN-MSG}, {@code WS-INFO-MSG} and the {@code CC-ACCT-ID}/{@code CC-CARD-NUM} work fields)
 * are modeled by a method-local {@link WorkingState} instance created fresh in {@link
 * #processCardView(CardViewScreen, CardDemoCommarea, CardWorkArea.Aid)}.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7; feeds
 * {@code docs/traceability-matrix.md}). Each numbered paragraph maps to exactly one method and the
 * original perform/branch order is preserved:
 *
 * <ul>
 *   <li>{@code 0000-MAIN} (L248-408) &rarr; {@link #processCardView(CardViewScreen,
 *       CardDemoCommarea, CardWorkArea.Aid)} (the PF-key remap L291-299 and the {@code
 *       RETURN-TO-CALLER} XCTL of the PFK03 branch L305-334 are inlined here)
 *   <li>{@code 2000-PROCESS-INPUTS} (L582-591) &rarr; {@link #processInputs(CardViewScreen,
 *       CardDemoCommarea, WorkingState)}
 *   <li>{@code 2100-RECEIVE-MAP} (L596-603) &rarr; controller-owned (HTTP form binding); not
 *       modeled here
 *   <li>{@code 2200-EDIT-MAP-INPUTS} (L608-641) &rarr; {@link #editMapInputs(CardViewScreen,
 *       CardDemoCommarea, WorkingState)}
 *   <li>{@code 2210-EDIT-ACCOUNT} (L647-679) &rarr; {@link #editAccount(CardDemoCommarea,
 *       WorkingState)}
 *   <li>{@code 2220-EDIT-CARD} (L685-720) &rarr; {@link #editCard(CardDemoCommarea, WorkingState)}
 *   <li>{@code 9000-READ-DATA} (L726-730) &rarr; {@link #readData(CardViewScreen, WorkingState)}
 *   <li>{@code 9100-GETCARD-BYACCTCARD} (L736-773) &rarr; {@link #getCardByAcctCard(CardViewScreen,
 *       WorkingState)}
 *   <li>{@code 9150-GETCARD-BYACCT} (L779-812) &rarr; <strong>intentionally omitted</strong>: it is
 *       the {@code CARDAIX} alternate-index path and is unreachable dead code in {@code COCRDSLC}
 *       (never the target of a {@code PERFORM}); see {@link #readData(CardViewScreen,
 *       WorkingState)}
 *   <li>{@code 1000-SEND-MAP}/{@code 1200-SETUP-SCREEN-VARS} message outputs (L412-421, L457-497)
 *       and {@code 1400-SEND-SCREEN} {@code SET CDEMO-PGM-REENTER} (L567) &rarr; {@link
 *       #sendMap(CardViewScreen, CardDemoCommarea, WorkingState)}; the found-card field population
 *       (L474-484) &rarr; {@link #populateScreen(CardViewScreen, Card)}
 * </ul>
 *
 * <p><strong>Message parity.</strong> Every COBOL message literal is reproduced byte-for-byte as a
 * named constant (agent prompt rule&nbsp;6). The shared {@code util.Messages} invalid-key constant
 * is deliberately <em>not</em> used: {@code COCRDSLC} coerces every unmapped attention key to
 * {@code ENTER} (L291-299) and shows no "invalid key" message, so the constant does not apply here.
 */
@Service
public class CardViewService {

  // ===== Program identity (COCRDSLC WS-LITERALS, L162-190) =======================================

  /** CICS transaction id of this program ({@code LIT-THISTRANID}, L165-166). */
  static final String TRAN_ID = "CCDL";

  /** Program name of this program ({@code LIT-THISPGM}, L163-164). */
  static final String PGM_NAME = "COCRDSLC";

  /**
   * BMS mapset of this screen, as stored into the 7-byte {@code CDEMO-LAST-MAPSET} ({@code
   * LIT-THISMAPSET} {@code 'COCRDSL '} truncated to {@code PIC X(7)}, L167-168, L328).
   */
  static final String LIT_THIS_MAPSET = "COCRDSL";

  /** BMS map of this screen ({@code LIT-THISMAP}, L169-170, L329). */
  static final String LIT_THIS_MAP = "CCRDSLA";

  /**
   * Card-list program that may invoke this screen ({@code LIT-CCLISTPGM}, L171-172). When {@code
   * CDEMO-FROM-PROGRAM} equals this value on a first ({@code PGM-ENTER}) entry, the search criteria
   * were already validated by the card-list screen and are read immediately (L339-348).
   */
  static final String LIT_CARD_LIST_PGM = "COCRDLIC";

  /**
   * Default caller program (the main menu) routed to on PF3 exit when {@code CDEMO-FROM-PROGRAM} is
   * blank ({@code LIT-MENUPGM}, L179-180, L316-318).
   */
  static final String LIT_MENU_PGM = "COMEN01C";

  /**
   * Default caller transaction id (the main menu) used on PF3 exit when {@code CDEMO-FROM-TRANID}
   * is blank ({@code LIT-MENUTRANID}, L181-182, L309-311).
   */
  static final String LIT_MENU_TRAN = "CM00";

  /**
   * Logical card file name used in the {@link IoStatusException} on an unexpected I/O error ({@code
   * LIT-CARDFILENAME} {@code 'CARDDAT '}, L187-188, used as {@code ERROR-FILE} at L768).
   */
  static final String LIT_CARD_FILE_NAME = "CARDDAT";

  // ===== Field widths (COCRDSLC CICS-OUTPUT-EDIT-VARS / CC-WORK-AREA)
  // =============================

  /** Exact digit length of the account filter ({@code CARD-ACCT-ID PIC 9(11)}, L73-75). */
  static final int ACCT_FILTER_LEN = 11;

  /** Exact digit length of the card filter ({@code CARD-CARD-NUM PIC 9(16)}, L79-81). */
  static final int CARD_FILTER_LEN = 16;

  // ===== Message literals (reproduced byte-for-byte; agent prompt rule 6) ========================
  // The literals are the exact COBOL VALUE/MOVE text, WITHOUT field-width padding (matching the two
  // filter literals quoted verbatim in the agent prompt). WS-INFO-MSG values feed INFOMSG; the
  // WS-RETURN-MSG values feed ERRMSG (see sendMap / COMMON-RETURN L395).

  /**
   * Informational prompt shown when no search criteria have been entered ({@code
   * WS-PROMPT-FOR-INPUT}, L131-132). Drives the {@code INFOMSG} line.
   */
  static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

  /**
   * Informational message shown when a card was found and its detail is displayed ({@code
   * FOUND-CARDS-FOR-ACCOUNT}, L129-130). The three leading spaces are preserved verbatim. Drives
   * the {@code INFOMSG} line.
   */
  static final String MSG_FOUND_CARDS = "   Displaying requested details";

  /**
   * Error message shown when the account filter is blank or zero ({@code WS-PROMPT-FOR-ACCT},
   * L138-139). Drives the {@code ERRMSG} line.
   */
  static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

  /**
   * Error message shown when the card filter is blank or zero ({@code WS-PROMPT-FOR-CARD},
   * L140-141). Drives the {@code ERRMSG} line.
   */
  static final String MSG_PROMPT_FOR_CARD = "Card number not provided";

  /**
   * Error message shown when both the account and card filters are blank ({@code
   * NO-SEARCH-CRITERIA-RECEIVED}, L142-143). Drives the {@code ERRMSG} line.
   */
  static final String MSG_NO_SEARCH_CRITERIA = "No input received";

  /**
   * Error message shown when the account filter is supplied but is not an 11-digit number. Migrated
   * byte-for-byte from the inline {@code MOVE} literal at COCRDSLC L669-671.
   */
  static final String MSG_ACCT_FILTER_INVALID =
      "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

  /**
   * Error message shown when the card filter is supplied but is not a 16-digit number. Migrated
   * byte-for-byte from the inline {@code MOVE} literal at COCRDSLC L710-712.
   */
  static final String MSG_CARD_FILTER_INVALID =
      "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

  /**
   * Error message shown when the requested card is not found ({@code DID-NOT-FIND-ACCTCARD-COMBO},
   * L153-154). Reached on FILE STATUS {@code '23'} ({@link Optional#empty()}). Drives the {@code
   * ERRMSG} line.
   */
  static final String MSG_DID_NOT_FIND_CARD = "Did not find cards for this search condition";

  /**
   * Generic message for the defensive {@code WHEN OTHER} dispatch branch ({@code 'UNEXPECTED DATA
   * SCENARIO'}, L377). In the legacy program this drove a plain-text abend send; online, it is
   * shown as an error and the screen is redisplayed (the abend itself maps to
   * {@code @ControllerAdvice} / {@code GlobalExceptionHandler} parity per AAP &sect;0.6.6).
   */
  static final String MSG_UNEXPECTED_DATA = "UNEXPECTED DATA SCENARIO";

  // ===== Collaborators ===========================================================================

  /** Card data store; replaces VSAM {@code CARDDAT} KSDS access (copybook {@code CVACT02Y}). */
  private final CardRepository cardRepository;

  /**
   * Creates the service with its single collaborator. Constructor injection (no field injection, no
   * {@code @Autowired}) keeps the {@code final} dependency immutable and the service trivially
   * unit-testable.
   *
   * @param cardRepository the card repository (VSAM {@code CARDDAT} replacement); must not be
   *     {@code null}
   */
  public CardViewService(CardRepository cardRepository) {
    this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository must not be null");
  }

  /**
   * Entry point reproducing {@code COCRDSLC} paragraph {@code 0000-MAIN} (L248-408): the
   * pseudo-conversational dispatch for the card detail/view screen (CICS transaction {@code CCDL}).
   *
   * <p>The controller layer resolves the raw 3270 attention identifier into {@code aid}, binds the
   * submitted form onto {@code screen} (the {@code 2100-RECEIVE-MAP} equivalent), and supplies the
   * carried session state in {@code commarea}. This method decides, exactly as the COBOL {@code
   * EVALUATE TRUE} did, whether to exit to the caller, read-and-display immediately, gather search
   * criteria, or validate-then-read:
   *
   * <ol>
   *   <li><strong>PF3</strong> &rarr; exit via {@code XCTL} to the caller or the main menu (returns
   *       the next program name).
   *   <li><strong>First entry from the card-list program</strong> ({@code COCRDLIC}) &rarr;
   *       criteria already validated there, so read the card and display it.
   *   <li><strong>First entry from any other caller</strong> &rarr; paint the empty search screen.
   *   <li><strong>Re-entry</strong> &rarr; validate the typed filters; on success read and display,
   *       otherwise redisplay with the validation message.
   * </ol>
   *
   * <p>Per the read-only/display contract, validation and not-found conditions populate the screen
   * message lines and return {@code null} to redisplay; they are never thrown. Only an unexpected
   * data-access failure is translated to {@link IoStatusException} (see {@link
   * #getCardByAcctCard(CardViewScreen, WorkingState)}).
   *
   * @param screen the card-view screen DTO, already bound from the submitted form; receives the
   *     echoed/looked-up field values and the info/error message lines; must not be {@code null}
   * @param commarea the pseudo-conversational session/navigation state; read for the entry context
   *     and caller routing, and updated in place; must not be {@code null}
   * @param aid the resolved attention identifier for this submit; unmapped/unknown keys are treated
   *     as {@link CardWorkArea.Aid#ENTER} exactly as the COBOL coerced them; may be {@code null}
   *     (treated as {@code ENTER})
   * @return the next program name to {@code XCTL} to when navigation leaves this screen (PF3 exit),
   *     or {@code null} to redisplay the current screen with the populated message lines
   */
  public String processCardView(
      CardViewScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // --- 0000-MAIN prologue (L254-264) ---------------------------------------------------------
    // Fresh per-invocation working state (the COBOL INITIALIZE of CC-WORK-AREA / WS-MISC-STORAGE);
    // the @Service singleton holds no mutable state of its own. Clear the on-screen error line and
    // leave the working return message OFF (WS-RETURN-MSG = LOW-VALUES) so the "first message wins"
    // latch in the edit paragraphs behaves exactly as the COBOL.
    WorkingState state = new WorkingState();
    screen.setErrMsg("");

    // --- Remap / validate the AID (L291-299) ---------------------------------------------------
    // The only attention keys honored here are ENTER and PF3; every other key (and a null AID) is
    // coerced to ENTER (COBOL: "SET PFK-INVALID; IF ENTER OR PFK03 SET PFK-VALID; IF PFK-INVALID
    // SET CCARD-AID-ENTER").
    boolean exitRequested = aid == CardWorkArea.Aid.PFK03;

    // --- 0000-MAIN EVALUATE TRUE (L304-381) ----------------------------------------------------
    if (exitRequested) {
      // WHEN CCARD-AID-PFK03 (L305-334): return to the calling program or the main menu.
      return exitToCaller(commarea);
    }

    if (commarea.isPgmEnter() && LIT_CARD_LIST_PGM.equals(safeTrim(commarea.getFromProgram()))) {
      // WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-CCLISTPGM (L339-348): came from the card
      // list with already-validated criteria. SET INPUT-OK, copy the carried account/card filters
      // into the work fields, read immediately, then display.
      state.ccAcctId = formatZoned(commarea.getAcctId(), ACCT_FILTER_LEN);
      state.ccCardNum = formatZoned(commarea.getCardNum(), CARD_FILTER_LEN);
      readData(screen, state);
      return sendMap(screen, commarea, state);
    }

    if (commarea.isPgmEnter()) {
      // WHEN CDEMO-PGM-ENTER (L349-356): first entry from some other context — paint the search
      // screen so the operator can supply criteria (no read). The info line defaults to the search
      // prompt inside sendMap (1200 WS-PROMPT-FOR-INPUT).
      return sendMap(screen, commarea, state);
    }

    if (commarea.isPgmReenter()) {
      // WHEN CDEMO-PGM-REENTER (L357-371): validate the typed filters; on success read then
      // display, otherwise redisplay with the validation message.
      processInputs(screen, commarea, state);
      if (!state.inputError) {
        readData(screen, state);
      }
      return sendMap(screen, commarea, state);
    }

    // WHEN OTHER (L373-380): unexpected pseudo-conversational state. The legacy program staged an
    // abend ('UNEXPECTED DATA SCENARIO') and sent a plain-text screen; online this maps to a
    // generic error redisplay (the abend itself surfaces through GlobalExceptionHandler /
    // @ControllerAdvice, AAP §0.6.6). Defensive: isPgmEnter()/isPgmReenter() are exhaustive for a
    // well-formed COMMAREA, so this branch is effectively unreachable in normal flow.
    screen.setErrMsg(MSG_UNEXPECTED_DATA);
    return null;
  }

  /**
   * Reproduces the {@code 0000-MAIN} PF3 branch / return-to-caller logic (L305-334): build the
   * {@code XCTL} target and stamp the COMMAREA so the caller knows control returned from {@code
   * COCRDSLC}.
   *
   * <p>The target program/transaction are computed from the <em>original</em> from-program/tranid
   * first (defaulting to the main menu {@code COMEN01C}/{@code CM00} when blank), and only then are
   * the from-fields overwritten with this program's identity. The user type is reset to standard
   * user and the context to a fresh entry, mirroring {@code SET CDEMO-USRTYP-USER} / {@code SET
   * CDEMO-PGM-ENTER} (L326-327), and the last map/mapset are recorded (L328-329).
   *
   * @param commarea the session state, updated in place with the routing/identity fields
   * @return the program name to {@code XCTL} to ({@code CDEMO-TO-PROGRAM})
   */
  private String exitToCaller(CardDemoCommarea commarea) {
    // L309-321: target = original from-* if present, else the main menu defaults.
    String toTranId = blankToDefault(commarea.getFromTranId(), LIT_MENU_TRAN);
    String toProgram = blankToDefault(commarea.getFromProgram(), LIT_MENU_PGM);
    commarea.setToTranId(toTranId);
    commarea.setToProgram(toProgram);

    // L323-324: record that control is returning from this program/transaction.
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);

    // L326-329: reset role to standard user, mark the caller's entry as a fresh one, and record
    // the last rendered map/mapset.
    commarea.setUsrTypUser();
    commarea.setPgmEnter();
    commarea.setLastMapset(LIT_THIS_MAPSET);
    commarea.setLastMap(LIT_THIS_MAP);

    // L331-334: EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) — the controller performs the forward.
    return toProgram;
  }

  /**
   * Reproduces {@code 2000-PROCESS-INPUTS} (L582-591): receive the map and edit its inputs.
   *
   * <p>The {@code 2100-RECEIVE-MAP} step (L596-602) is owned by the controller, which binds the
   * submitted form onto the screen DTO before this service is invoked; the service therefore reads
   * those values directly in {@link #editMapInputs(CardViewScreen, CardDemoCommarea,
   * WorkingState)}. The trailing COBOL moves (L587-590) stage the error line and the {@code
   * CCARD-NEXT-PROG/MAPSET/MAP} routing fields; the error line is rendered by {@link
   * #sendMap(CardViewScreen, CardDemoCommarea, WorkingState)} and the next-map routing belongs to
   * the controller's {@code CardWorkArea} navigation, so nothing further is staged here.
   *
   * @param screen the screen DTO carrying the submitted account/card filters
   * @param commarea the session state; receives the validated numeric account/card values
   * @param state the per-invocation working state
   */
  private void processInputs(CardViewScreen screen, CardDemoCommarea commarea, WorkingState state) {
    editMapInputs(screen, commarea, state);
  }

  /**
   * Reproduces {@code 2200-EDIT-MAP-INPUTS} (L608-641): reset the validation flags, collapse the
   * {@code '*'}/blank sentinels to "not supplied", run the per-field edits in order, then apply the
   * cross-field rule.
   *
   * @param screen the screen DTO carrying the submitted account/card filters
   * @param commarea the session state; receives the validated numeric account/card values
   * @param state the per-invocation working state
   */
  private void editMapInputs(CardViewScreen screen, CardDemoCommarea commarea, WorkingState state) {
    // L610-612: SET INPUT-OK / FLG-*FILTER-ISVALID — start the pass clean.
    state.inputError = false;
    state.acctFilterBlank = false;
    state.cardFilterBlank = false;

    // L615-627: a field equal to '*' or SPACES is treated as LOW-VALUES (not supplied).
    state.ccAcctId = normalizeFilter(screen.getAcctSid());
    state.ccCardNum = normalizeFilter(screen.getCardSid());

    // L630-634: individual field edits, in the original perform order.
    editAccount(commarea, state);
    editCard(commarea, state);

    // L636-640: cross-field edit. When BOTH filters are blank the COBOL sets
    // NO-SEARCH-CRITERIA-RECEIVED unconditionally, so it overrides the per-field prompt already
    // latched by editAccount.
    if (state.acctFilterBlank && state.cardFilterBlank) {
      state.returnMsg = MSG_NO_SEARCH_CRITERIA;
    }
  }

  /**
   * Reproduces {@code 2210-EDIT-ACCOUNT} (L647-679): validate the optional account filter.
   *
   * <p>Three outcomes, matching the COBOL branches: <em>not supplied</em> (LOW-VALUES/SPACES or an
   * all-zero 11-digit value, {@code CC-ACCT-ID-N = ZEROS}) latches {@link #MSG_PROMPT_FOR_ACCT} and
   * zeroes {@code CDEMO-ACCT-ID}; <em>supplied but not an 11-digit number</em> latches {@link
   * #MSG_ACCT_FILTER_INVALID} and zeroes {@code CDEMO-ACCT-ID}; otherwise the numeric account id is
   * stored. Both error branches honor the "first message wins" latch ({@code WS-RETURN-MSG-OFF}).
   *
   * @param commarea the session state; receives {@code CDEMO-ACCT-ID}
   * @param state the per-invocation working state
   */
  private void editAccount(CardDemoCommarea commarea, WorkingState state) {
    // L651-661: not supplied (LOW-VALUES/SPACES) or CC-ACCT-ID-N = ZEROS.
    if (state.ccAcctId == null || isZonedZero(state.ccAcctId, ACCT_FILTER_LEN)) {
      state.inputError = true;
      state.acctFilterBlank = true;
      if (state.returnMsg == null) { // WS-RETURN-MSG-OFF — first message wins
        state.returnMsg = MSG_PROMPT_FOR_ACCT;
      }
      commarea.setAcctId(0L); // MOVE ZEROES TO CDEMO-ACCT-ID
      return; // GO TO 2210-EDIT-ACCOUNT-EXIT
    }
    // L665-674: supplied but not numeric / not 11 characters.
    if (!isAllDigits(state.ccAcctId, ACCT_FILTER_LEN)) {
      state.inputError = true;
      if (state.returnMsg == null) {
        state.returnMsg = MSG_ACCT_FILTER_INVALID;
      }
      commarea.setAcctId(0L); // MOVE ZERO TO CDEMO-ACCT-ID
      return; // GO TO 2210-EDIT-ACCOUNT-EXIT
    }
    // L675-678: valid — store the numeric account id (MOVE CC-ACCT-ID TO CDEMO-ACCT-ID).
    commarea.setAcctId(Long.parseLong(state.ccAcctId));
  }

  /**
   * Reproduces {@code 2220-EDIT-CARD} (L685-720): validate the optional card filter, mirroring
   * {@link #editAccount(CardDemoCommarea, WorkingState)} but for the 16-digit card number.
   *
   * @param commarea the session state; receives {@code CDEMO-CARD-NUM}
   * @param state the per-invocation working state
   */
  private void editCard(CardDemoCommarea commarea, WorkingState state) {
    // L691-702: not supplied (LOW-VALUES/SPACES) or CC-CARD-NUM-N = ZEROS.
    if (state.ccCardNum == null || isZonedZero(state.ccCardNum, CARD_FILTER_LEN)) {
      state.inputError = true;
      state.cardFilterBlank = true;
      if (state.returnMsg == null) { // WS-RETURN-MSG-OFF — first message wins
        state.returnMsg = MSG_PROMPT_FOR_CARD;
      }
      commarea.setCardNum(0L); // MOVE ZEROES TO CDEMO-CARD-NUM
      return; // GO TO 2220-EDIT-CARD-EXIT
    }
    // L706-715: supplied but not numeric / not 16 characters.
    if (!isAllDigits(state.ccCardNum, CARD_FILTER_LEN)) {
      state.inputError = true;
      if (state.returnMsg == null) {
        state.returnMsg = MSG_CARD_FILTER_INVALID;
      }
      commarea.setCardNum(0L); // MOVE ZERO TO CDEMO-CARD-NUM
      return; // GO TO 2220-EDIT-CARD-EXIT
    }
    // L716-718: valid — store the numeric card number (MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM).
    commarea.setCardNum(Long.parseLong(state.ccCardNum));
  }

  /**
   * Reproduces {@code 9000-READ-DATA} (L726-730): delegate to the by-card-number read.
   *
   * <p>The COBOL {@code 9150-GETCARD-BYACCT} alternate-index path (L779-812, which reads {@code
   * CARDAIX} by account id) is <strong>intentionally omitted</strong>: it is never the target of a
   * {@code PERFORM} anywhere in {@code COCRDSLC} and is therefore unreachable dead code (AAP
   * &sect;0.2.2 reference-only / dead-code exclusion). The single live read is {@code
   * 9100-GETCARD-BYACCTCARD}, by card number.
   *
   * @param screen the screen DTO; populated with the card detail on a successful read
   * @param state the per-invocation working state holding the card-number read key
   */
  private void readData(CardViewScreen screen, WorkingState state) {
    getCardByAcctCard(screen, state);
  }

  /**
   * Reproduces {@code 9100-GETCARD-BYACCTCARD} (L736-773): read the card file by card number and
   * map the outcome onto the screen.
   *
   * <ul>
   *   <li><strong>Found</strong> ({@code DFHRESP(NORMAL)}, L753-754): latch {@link
   *       #MSG_FOUND_CARDS} on the info line and populate the detail fields.
   *   <li><strong>Not found</strong> ({@code DFHRESP(NOTFND)}, FILE STATUS {@code '23'}, L755-761):
   *       {@link Optional#empty()} — flag input error and, if no message is latched yet, show
   *       {@link #MSG_DID_NOT_FIND_CARD} on the error line; the screen is redisplayed (never
   *       thrown).
   *   <li><strong>Other</strong> ({@code WHEN OTHER}, L762-771): an unexpected data-access failure
   *       is translated to {@link IoStatusException} so {@code GlobalExceptionHandler} surfaces it
   *       with CICS RESP/abend parity (AAP &sect;0.6.4, &sect;0.6.6).
   * </ul>
   *
   * @param screen the screen DTO; populated with the card detail on a successful read
   * @param state the per-invocation working state holding the card-number read key
   */
  private void getCardByAcctCard(CardViewScreen screen, WorkingState state) {
    // L740: MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM — the read is keyed by card number only.
    Optional<Card> found;
    try {
      found = cardRepository.findById(state.ccCardNum);
    } catch (DataAccessException ex) {
      // WHEN OTHER (L762-771): unexpected file I/O. The 2-byte FILE STATUS has no equivalent for a
      // JDBC failure, so the documented placeholder is used; the cause is preserved for diagnosis.
      throw new IoStatusException(LIT_CARD_FILE_NAME, "READ", "??", ex);
    }

    if (found.isPresent()) {
      // WHEN DFHRESP(NORMAL) (L753-754): SET FOUND-CARDS-FOR-ACCOUNT (info line) + populate detail.
      state.infoMsg = MSG_FOUND_CARDS;
      populateScreen(screen, found.get());
    } else {
      // WHEN DFHRESP(NOTFND) (L755-761): record-not-found is a user-facing condition, not an error.
      state.inputError = true;
      if (state.returnMsg == null) {
        state.returnMsg = MSG_DID_NOT_FIND_CARD;
      }
    }
  }

  /**
   * Reproduces the found-card field population of {@code 1200-SETUP-SCREEN-VARS} (L474-484): copy
   * the located {@link Card} onto the screen DTO, preserving the legacy field widths and formats.
   *
   * <p>The account id is rendered as an 11-digit zero-padded string and the card number as its
   * 16-character key, per the agent specification for this helper. (The COBOL echoed the typed
   * {@code CC-ACCT-ID}/{@code CC-CARD-NUM} filters here; because the card is read by number, the
   * card number is identical, and the account differs only in the pathological case where the
   * operator typed an account that does not own the located card — a case the read-by-card-number
   * flow does not guard, mirroring the dead {@code 9150} account path.)
   *
   * <p>The expiry month/year are parsed from {@code CARD-EXPIRAION-DATE} (legacy misspelling
   * preserved in the entity getter {@link Card#getCardExpiraionDate()}); the stored format is
   * {@code "YYYY-MM-DD"}, so the year is characters 1-4 and the month is characters 6-7. Null or
   * short values are guarded and yield empty month/year fields.
   *
   * @param screen the screen DTO to populate
   * @param card the located card record
   */
  private void populateScreen(CardViewScreen screen, Card card) {
    screen.setAcctSid(formatZoned(card.getCardAcctId(), ACCT_FILTER_LEN));
    screen.setCardSid(card.getCardNum());
    screen.setCrdName(card.getCardEmbossedName());
    screen.setCrdStcd(card.getCardActiveStatus());

    String expiry = card.getCardExpiraionDate();
    if (expiry != null && expiry.length() >= 7) {
      screen.setExpYear(expiry.substring(0, 4)); // YYYY
      screen.setExpMon(expiry.substring(5, 7)); // MM (skip the '-' separator at index 4)
    } else {
      screen.setExpYear("");
      screen.setExpMon("");
    }
  }

  /**
   * Reproduces the message-and-flag finalization of the {@code 1000-SEND-MAP} flow that this
   * service owns: the {@code 1200-SETUP-SCREEN-VARS} message defaulting (L489-496) and the {@code
   * 1400-SEND-SCREEN} re-arming of the pseudo-conversational flag (L567).
   *
   * <p>The remaining {@code 1000-SEND-MAP} steps — header/date-time initialization ({@code
   * 1100-SCREEN-INIT}), attribute/colour/cursor setup ({@code 1300-SETUP-SCREEN-ATTRS}), the screen
   * field echo of the typed filters, and the actual {@code EXEC CICS SEND MAP} ({@code
   * 1400-SEND-SCREEN}) — are owned by the controller, which renders the BMS-equivalent screen. The
   * service signals "redisplay the current screen" by returning {@code null}.
   *
   * @param screen the screen DTO; receives the info and error message lines
   * @param commarea the session state; its context is re-armed to a re-entry
   * @param state the per-invocation working state holding the staged messages
   * @return always {@code null} — the caller stays on the card-view screen
   */
  private String sendMap(CardViewScreen screen, CardDemoCommarea commarea, WorkingState state) {
    // L489-492: when no info message has been set, default the info line to the search prompt
    // (SET WS-PROMPT-FOR-INPUT). L496: INFOMSGO = WS-INFO-MSG.
    screen.setInfoMsg(isBlankField(state.infoMsg) ? MSG_PROMPT_FOR_INPUT : state.infoMsg);
    // L494: ERRMSGO = WS-RETURN-MSG (an unset/"OFF" message renders as a cleared error line).
    screen.setErrMsg(state.returnMsg == null ? "" : state.returnMsg);
    // L567: SET CDEMO-PGM-REENTER — every map send re-arms the pseudo-conversational flag so the
    // next submit is processed as a re-entry.
    commarea.setPgmReenter();
    return null;
  }

  // ===== Pure helpers (COBOL field-edit / formatting primitives) =================================

  /**
   * Null-safe trim used for COMMAREA routing comparisons (the COBOL fields are fixed-width and
   * space-padded; the migrated POJO stores plain strings).
   *
   * @param value the value to trim
   * @return the trimmed value, or {@code null} when {@code value} is {@code null}
   */
  static String safeTrim(String value) {
    return value == null ? null : value.trim();
  }

  /**
   * Returns {@code defaultValue} when {@code value} is {@code null} or blank (the COBOL {@code
   * LOW-VALUES}/{@code SPACES} test), otherwise the trimmed value. Used by the PF3 exit routing.
   *
   * @param value the candidate value
   * @param defaultValue the fallback used when {@code value} is blank
   * @return the resolved, trimmed value
   */
  static String blankToDefault(String value, String defaultValue) {
    return isBlankField(value) ? defaultValue : value.trim();
  }

  /**
   * Collapses a screen filter field to its "not supplied" form, mirroring {@code
   * 2200-EDIT-MAP-INPUTS} (L615-627): a {@code null}, blank, or {@code '*'} field becomes {@code
   * null} (LOW-VALUES); any other value is returned trimmed.
   *
   * @param raw the raw screen field value
   * @return the normalized filter, or {@code null} when not supplied
   */
  static String normalizeFilter(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty() || "*".equals(trimmed)) {
      return null;
    }
    return trimmed;
  }

  /**
   * Mirrors a COBOL {@code IS NUMERIC} test on a fixed-width zoned field: the value must be exactly
   * {@code len} characters long and consist solely of ASCII digits.
   *
   * @param value the value to test
   * @param len the exact required length
   * @return {@code true} when {@code value} is exactly {@code len} digits
   */
  static boolean isAllDigits(String value, int len) {
    if (value == null || value.length() != len) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Mirrors the COBOL {@code CC-...-N = ZEROS} test on a fixed-width zoned-numeric field: the value
   * is exactly {@code len} characters and every character is {@code '0'} (so its numeric value is
   * zero). Such an all-zero filter is treated as "not supplied".
   *
   * @param value the value to test
   * @param len the exact field width
   * @return {@code true} when {@code value} is exactly {@code len} zero digits
   */
  static boolean isZonedZero(String value, int len) {
    if (value == null || value.length() != len) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      if (value.charAt(i) != '0') {
        return false;
      }
    }
    return true;
  }

  /**
   * Formats a numeric identifier as a fixed-width, zero-padded zoned-decimal string, mirroring the
   * COBOL {@code MOVE} of a {@code PIC 9(n)} value into a fixed display field. A {@code null} value
   * is treated as zero (a fresh COMMAREA numeric is {@code ZEROES}, never {@code null}).
   *
   * @param value the numeric value (may be {@code null})
   * @param len the field width
   * @return the zero-padded, {@code len}-character string
   */
  static String formatZoned(Long value, int len) {
    long v = value == null ? 0L : value;
    return String.format("%0" + len + "d", v);
  }

  /**
   * Mirrors the COBOL {@code WS-NO-INFO-MESSAGE} / {@code WS-RETURN-MSG-OFF} test: a {@code null}
   * or whitespace-only string is "blank" (SPACES/LOW-VALUES).
   *
   * @param value the value to test
   * @return {@code true} when {@code value} is {@code null} or contains only whitespace
   */
  static boolean isBlankField(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Per-invocation working state mirroring the COCRDSLC {@code WORKING-STORAGE} fields that flow
   * between the numbered paragraphs. A fresh instance is created for every {@link
   * #processCardView(CardViewScreen, CardDemoCommarea, CardWorkArea.Aid)} call, so the {@link
   * Service} singleton itself remains stateless (agent prompt rule&nbsp;4).
   */
  private static final class WorkingState {

    /**
     * {@code CC-ACCT-ID} ({@code PIC X(11)}, CVCRD01Y) &mdash; the account filter as received.
     * {@code null} models the COBOL {@code LOW-VALUES}/{@code SPACES} "not supplied" state.
     */
    private String ccAcctId;

    /**
     * {@code CC-CARD-NUM} ({@code PIC X(16)}, CVCRD01Y) &mdash; the card filter; also the {@code
     * CARDDAT} read key ({@code WS-CARD-RID-CARDNUM}, L740). {@code null} models {@code
     * LOW-VALUES}/{@code SPACES}.
     */
    private String ccCardNum;

    /**
     * {@code WS-RETURN-MSG} ({@code PIC X(75)}, L134) &mdash; the error line. {@code null} models
     * the {@code WS-RETURN-MSG-OFF} (low-values) state, which the COBOL tests to implement
     * "first-message-wins" message latching.
     */
    private String returnMsg;

    /** {@code WS-INFO-MSG} ({@code PIC X(40)}, L126) &mdash; the informational line. */
    private String infoMsg;

    /**
     * {@code WS-INPUT-FLAG}: {@code true} == {@code INPUT-ERROR}, {@code false} == {@code
     * INPUT-OK}.
     */
    private boolean inputError;

    /** {@code FLG-ACCTFILTER-BLANK} (L58) &mdash; the account filter was blank/zero. */
    private boolean acctFilterBlank;

    /** {@code FLG-CARDFILTER-BLANK} (L62) &mdash; the card filter was blank/zero. */
    private boolean cardFilterBlank;
  }
}

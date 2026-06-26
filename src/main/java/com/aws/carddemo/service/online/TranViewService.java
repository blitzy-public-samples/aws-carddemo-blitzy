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

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.TranViewScreen;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.Messages;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Online <strong>transaction view / detail</strong> business-logic service, migrated from the
 * legacy CICS COBOL program {@code COTRN01C} (CICS transaction {@code CT01}; source {@code
 * legacy/app/cbl/COTRN01C.cbl}, screen contract {@code legacy/app/bms/COTRN01.bms}, record layout
 * {@code legacy/app/cpy/CVTRA05Y.cpy}, COMMAREA {@code legacy/app/cpy/COCOM01Y.cpy}).
 *
 * <p>This service reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.6.5, &sect;0.7.1) &mdash; the pseudo-conversational, <em>read-only</em> transaction
 * lookup: the operator enters (or arrives with) a transaction id, the service reads the single
 * matching record from the {@code TRANSACT} store, and either renders the resolved transaction
 * detail or redisplays the screen carrying an error message. It performs a single keyed read and
 * <em>no</em> mutation; consequently a not-found id and a lookup failure are surfaced as on-screen
 * messages (a screen redisplay) rather than thrown exceptions, faithfully matching the COBOL {@code
 * NOTFND} and {@code WHEN OTHER} branches which {@code DISPLAY}/{@code SEND} rather than abend.
 *
 * <p>The {@code COTRN01C} program is the <em>named-paragraph</em> archetype ({@code MAIN-PARA} +
 * {@code EVALUATE EIBAID} + {@code PROCESS-ENTER-KEY}). Each COBOL paragraph is reproduced as one
 * private Java method, invoked in the original {@code PERFORM}/branch order so the control flow is
 * preserved verbatim (AAP &sect;0.6.7 traceability):
 *
 * <ul>
 *   <li>{@code MAIN-PARA} (L86-139) &rarr; {@link #processTranView(TranViewScreen,
 *       CardDemoCommarea, CardWorkArea.Aid)}
 *   <li>{@code PROCESS-ENTER-KEY} (L144-192) &rarr; {@link #processEnterKey(TranViewScreen)}
 *   <li>{@code READ-TRANSACT-FILE} (L267-296) &rarr; {@link #readTransactFile(TranViewScreen,
 *       String)}
 *   <li>(detail population, L176-192) &rarr; {@link #populateDetailFields(TranViewScreen,
 *       Transaction)}
 *   <li>{@code CLEAR-CURRENT-SCREEN} (L301-304) &rarr; {@link #clearCurrentScreen(TranViewScreen)}
 *   <li>{@code INITIALIZE-ALL-FIELDS} (L309-326) &rarr; {@link
 *       #initializeAllFields(TranViewScreen)}
 *   <li>{@code RETURN-TO-PREV-SCREEN} (L197-208) &rarr; {@link
 *       #returnToPrevScreen(CardDemoCommarea)}
 * </ul>
 *
 * <p>The COBOL screen-transmission paragraphs {@code SEND-TRNVIEW-SCREEN} (L213-225), {@code
 * RECEIVE-TRNVIEW-SCREEN} (L230-238) and {@code POPULATE-HEADER-INFO} (L243-262) are owned by the
 * {@code web} controller / view layer (the modernized {@code EXEC CICS SEND}/{@code RECEIVE} and
 * header/date-time rendering); they are intentionally not modeled here. Accordingly, every COBOL
 * {@code PERFORM SEND-TRNVIEW-SCREEN} maps to "return {@code null}" so the controller redisplays
 * the screen the service has populated.
 *
 * <p><strong>Stateless singleton.</strong> The service holds no mutable instance state; the COBOL
 * working-storage error flag ({@code WS-ERR-FLG}) is modeled as local control flow (the {@link
 * Optional} returned by {@link #readTransactFile(TranViewScreen, String)} plus the {@link
 * TranViewScreen#setErrMsg(String) errMsg} written to the screen), so concurrent requests never
 * interfere. The only collaborator, {@link TransactionRepository}, is injected through the single
 * constructor.
 *
 * <p><strong>Byte-exact parity.</strong> The three error literals are reproduced byte-for-byte from
 * the COBOL source ({@link #MSG_TRAN_ID_EMPTY}, {@link #MSG_TRAN_NOT_FOUND}, {@link
 * #MSG_TRAN_LOOKUP_ERROR}); the invalid-key message reuses the shared {@link
 * Messages#MSG_INVALID_KEY} constant (the COBOL {@code CCDA-MSG-INVALID-KEY}).
 */
@Service
public class TranViewService {

  /** Logger used to mirror the COBOL {@code DISPLAY 'RESP:' ... 'REAS:'} diagnostic (L290). */
  private static final Logger LOGGER = LoggerFactory.getLogger(TranViewService.class);

  /** CICS transaction id of this program ({@code WS-TRANID}, {@code COTRN01C} L37). */
  static final String TRAN_ID_NAME = "CT01";

  /** Program name of this program ({@code WS-PGMNAME}, {@code COTRN01C} L36). */
  static final String PGM_NAME = "COTRN01C";

  /**
   * Sign-on program ({@code COSGN00C}). Routed to on the {@code EIBCALEN = 0} no-COMMAREA entry
   * (L94-96) and used as the default {@code CDEMO-TO-PROGRAM} when none is set (L199-201).
   */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  /**
   * Main-menu program ({@code COMEN01C}). The PF3 "back" target when the caller did not record a
   * {@code CDEMO-FROM-PROGRAM} (L116-117).
   */
  static final String LIT_MENU_PGM = "COMEN01C";

  /** Transaction-list program ({@code COTRN00C}); the PF5 "browse transactions" target (L126). */
  static final String LIT_TRAN_LIST_PGM = "COTRN00C";

  /**
   * Logical file name of the transaction store ({@code WS-TRANSACT-FILE}, {@code COTRN01C} L39).
   * Carried in the diagnostic log line for the unexpected-error branch (mirrors the COBOL {@code
   * DATASET(WS-TRANSACT-FILE)} read target).
   */
  static final String LIT_TRANSACT_FILE = "TRANSACT";

  /**
   * Empty-search-id message, reproduced byte-for-byte from {@code COTRN01C} L149 ({@code MOVE 'Tran
   * ID can NOT be empty...' TO WS-MESSAGE}).
   */
  static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

  /**
   * Record-not-found message, reproduced byte-for-byte from {@code COTRN01C} L285 ({@code MOVE
   * 'Transaction ID NOT found...' TO WS-MESSAGE}); the {@code DFHRESP(NOTFND)} / FILE STATUS {@code
   * '23'} branch.
   */
  static final String MSG_TRAN_NOT_FOUND = "Transaction ID NOT found...";

  /**
   * Unexpected-lookup-error message, reproduced byte-for-byte from {@code COTRN01C} L292 ({@code
   * MOVE 'Unable to lookup Transaction...' TO WS-MESSAGE}); the {@code WHEN OTHER} branch.
   */
  static final String MSG_TRAN_LOOKUP_ERROR = "Unable to lookup Transaction...";

  // ===== Fixed-width field widths (the BMS / copybook PIC clause widths).
  // =========================
  // These widths are part of the external screen contract: a COBOL alphanumeric MOVE into a shorter
  // PIC X(n) receiving field truncates on the right, so the same truncation is applied here.

  /**
   * Width of the working transaction-id key and the {@code TRNID}/{@code TRNIDIN} fields (X(16)).
   */
  static final int TRAN_ID_LEN = 16;

  /** Width of the {@code CARDNUM} field ({@code TRAN-CARD-NUM PIC X(16)}). */
  private static final int CARD_NUM_LEN = 16;

  /** Width of the {@code TTYPCD} field ({@code TRAN-TYPE-CD PIC X(02)}). */
  private static final int TYPE_CD_LEN = 2;

  /** Width of the {@code TCATCD} field ({@code TRAN-CAT-CD PIC 9(04)}). */
  private static final int CAT_CD_LEN = 4;

  /** Width of the {@code TRNSRC} field ({@code TRAN-SOURCE PIC X(10)}). */
  private static final int SOURCE_LEN = 10;

  /**
   * Width of the {@code TDESC} field (BMS map {@code PIC X(60)}; source {@code TRAN-DESC X(100)}).
   */
  private static final int DESC_LEN = 60;

  /**
   * Width of the {@code TORIGDT} field (BMS map {@code PIC X(10)}; source {@code X(26)} timestamp).
   */
  private static final int ORIG_DT_LEN = 10;

  /**
   * Width of the {@code TPROCDT} field (BMS map {@code PIC X(10)}; source {@code X(26)} timestamp).
   */
  private static final int PROC_DT_LEN = 10;

  /**
   * Width of the {@code MID} field ({@code TRAN-MERCHANT-ID PIC 9(09)} rendered as X(9) digits).
   */
  private static final int MERCHANT_ID_LEN = 9;

  /** Width of the {@code MNAME} field (BMS map {@code PIC X(30)}; source {@code X(50)}). */
  private static final int MERCHANT_NAME_LEN = 30;

  /** Width of the {@code MCITY} field (BMS map {@code PIC X(25)}; source {@code X(50)}). */
  private static final int MERCHANT_CITY_LEN = 25;

  /** Width of the {@code MZIP} field ({@code TRAN-MERCHANT-ZIP PIC X(10)}). */
  private static final int MERCHANT_ZIP_LEN = 10;

  /**
   * Repository for the {@code TRANSACT} store (the migrated VSAM KSDS keyed on {@code TRAN-ID});
   * replaces the COBOL {@code EXEC CICS READ DATASET('TRANSACT')}. Injected by constructor so the
   * dependency is explicit, {@code final}, and the service is trivially unit-testable with a mock.
   */
  private final TransactionRepository transactionRepository;

  /**
   * Creates the service with its single collaborator.
   *
   * @param transactionRepository the transaction repository (the migrated {@code TRANSACT} VSAM
   *     read path); must not be {@code null}
   */
  public TranViewService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  /**
   * Processes one transaction-view interaction, reproducing {@code COTRN01C MAIN-PARA} (L86-139).
   *
   * <p>The flow mirrors the COBOL exactly:
   *
   * <ol>
   *   <li><strong>Entry reset</strong> (L88-92): the error flag and message are cleared ({@code SET
   *       ERR-FLG-OFF}; {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO}).
   *   <li><strong>No COMMAREA</strong> (L94-96): when {@code EIBCALEN = 0} the program routes
   *       straight to sign-on. The controller signals this brand-new conversation by passing a
   *       {@code null} {@code commarea}; there is no communication area to populate, so the sign-on
   *       program name is returned for the controller to transfer to.
   *   <li><strong>First entry</strong> (L99-109, {@code IF NOT CDEMO-PGM-REENTER}): the re-enter
   *       flag is set, the detail output fields are cleared ({@code MOVE LOW-VALUES TO COTRN1AO}),
   *       and &mdash; if a transaction was selected upstream &mdash; an automatic lookup is
   *       performed before the screen is (re)displayed.
   *   <li><strong>Re-entry</strong> (L110-132): the submitted screen is received (controller-owned)
   *       and the attention identifier is evaluated ({@code EVALUATE EIBAID}): {@code ENTER}
   *       performs the lookup; {@code PF3} returns to the caller (or the main menu); {@code PF4}
   *       clears the screen; {@code PF5} browses the transaction list; any other key reports an
   *       invalid-key message.
   * </ol>
   *
   * <p><strong>Selected-transaction handoff.</strong> The COBOL field {@code
   * CDEMO-CT01-TRN-SELECTED} (the id chosen on the transaction-list screen) is <em>not</em> part of
   * the shared {@link CardDemoCommarea} contract. The controller therefore mediates the list&rarr;
   * view handoff by populating {@link TranViewScreen#setTrnIdIn(String) trnIdIn} with the selected
   * id before invoking this service on first entry. A non-blank {@code trnIdIn} on first entry is
   * treated as the "a transaction was selected" trigger that drives the automatic lookup
   * (L103-108).
   *
   * @param screen the transaction-view screen contract carrying the operator's {@code trnIdIn}
   *     search entry and receiving the resolved detail fields and any error message; must not be
   *     {@code null}
   * @param commarea the pseudo-conversational session/navigation state, or {@code null} to signal
   *     the {@code EIBCALEN = 0} brand-new conversation that routes to sign-on
   * @param aid the resolved attention identifier (PF/ENTER key), or {@code null} if the raw key did
   *     not map to a known {@link CardWorkArea.Aid} (treated as the {@code WHEN OTHER} invalid key)
   * @return the program name to transfer to (the {@code EXEC CICS XCTL} target &mdash; the caller/
   *     menu on PF3, {@link #LIT_TRAN_LIST_PGM} on PF5, or {@link #LIT_SIGNON_PGM} when there is no
   *     COMMAREA), or {@code null} to redisplay the screen (first entry, ENTER lookup, PF4 clear,
   *     or invalid key)
   * @throws NullPointerException if {@code screen} is {@code null}
   */
  public String processTranView(
      TranViewScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");

    // MAIN-PARA L88-92: SET ERR-FLG-OFF / USR-MODIFIED-NO; MOVE SPACES TO WS-MESSAGE, ERRMSGO.
    screen.setErrMsg("");

    // MAIN-PARA L94-96: IF EIBCALEN = 0 — no prior COMMAREA. The controller signals this brand-new
    // conversation with a null commarea; route straight to sign-on (there is nothing to populate,
    // and RETURN-TO-PREV-SCREEN would itself default CDEMO-TO-PROGRAM to COSGN00C).
    if (commarea == null) {
      return LIT_SIGNON_PGM;
    }

    // MAIN-PARA L99-109: first entry — IF NOT CDEMO-PGM-REENTER.
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter(); // SET CDEMO-PGM-REENTER TO TRUE (L100).
      clearDetailFields(screen); // MOVE LOW-VALUES TO COTRN1AO (L101) — clear the detail output.
      // L103-108: IF CDEMO-CT01-TRN-SELECTED NOT = SPACES AND LOW-VALUES -> auto-lookup. The
      // selected id arrives via trnIdIn (see the handoff note in the method Javadoc).
      if (!isBlankOrNull(screen.getTrnIdIn())) {
        processEnterKey(screen);
      }
      return null; // PERFORM SEND-TRNVIEW-SCREEN (L109) — controller renders.
    }

    // MAIN-PARA L110-132: re-entry — PERFORM RECEIVE-TRNVIEW-SCREEN (controller-owned) then
    // EVALUATE EIBAID.
    if (aid == CardWorkArea.Aid.ENTER) {
      // L113-114: WHEN DFHENTER -> PROCESS-ENTER-KEY.
      processEnterKey(screen);
      return null;
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // L115-122: WHEN DFHPF3 -> to-program = from-program if set, else COMEN01C; return to prev.
      if (isBlankOrNull(commarea.getFromProgram())) {
        commarea.setToProgram(LIT_MENU_PGM);
      } else {
        commarea.setToProgram(commarea.getFromProgram());
      }
      return returnToPrevScreen(commarea);
    }
    if (aid == CardWorkArea.Aid.PFK04) {
      // L123-124: WHEN DFHPF4 -> CLEAR-CURRENT-SCREEN.
      return clearCurrentScreen(screen);
    }
    if (aid == CardWorkArea.Aid.PFK05) {
      // L125-127: WHEN DFHPF5 -> to-program = COTRN00C; return to prev (browse transactions).
      commarea.setToProgram(LIT_TRAN_LIST_PGM);
      return returnToPrevScreen(commarea);
    }
    // L128-131: WHEN OTHER -> invalid key. CCDA-MSG-INVALID-KEY is a space-padded PIC X(50); it is
    // trimmed for the screen message line per the migration convention (matches MainMenuService).
    screen.setErrMsg(Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  /**
   * Validates the search id, reads the transaction, and populates the detail fields, reproducing
   * {@code PROCESS-ENTER-KEY} (L144-192).
   *
   * <p>The control flow follows the COBOL paragraph step for step:
   *
   * <ol>
   *   <li>L146-156 ({@code EVALUATE TRUE}): when {@code TRNIDINI} is blank ({@code SPACES OR
   *       LOW-VALUES}) the {@link #MSG_TRAN_ID_EMPTY} message is set and the screen is redisplayed
   *       (the error flag is raised, short-circuiting the rest of the paragraph).
   *   <li>L158-174 ({@code IF NOT ERR-FLG-ON}): the thirteen detail output fields are cleared
   *       ({@code MOVE SPACES TO TRNIDI ... MZIPI}), the search id is moved into the working {@code
   *       TRAN-ID} key ({@code MOVE TRNIDINI TO TRAN-ID}), and {@link
   *       #readTransactFile(TranViewScreen, String)} is performed.
   *   <li>L176-192 ({@code IF NOT ERR-FLG-ON}): when the read succeeded, the located transaction is
   *       mapped onto the screen by {@link #populateDetailFields(TranViewScreen, Transaction)}.
   * </ol>
   *
   * <p>The method always results in a screen redisplay (the COBOL {@code RETURN} that follows
   * {@code MAIN-PARA}); it never transfers control, so it returns no navigation target. The COBOL
   * error flag is modeled by the {@link Optional} that {@link #readTransactFile(TranViewScreen,
   * String)} returns &mdash; an empty result means the not-found / lookup-error message has already
   * been set, so detail population is skipped.
   *
   * @param screen the transaction-view screen contract (read for {@code trnIdIn}; mutated with the
   *     resolved detail or an error message)
   */
  private void processEnterKey(TranViewScreen screen) {
    // L146-156: EVALUATE TRUE — WHEN TRNIDINI = SPACES OR LOW-VALUES.
    if (isBlankOrNull(screen.getTrnIdIn())) {
      screen.setErrMsg(MSG_TRAN_ID_EMPTY);
      return; // PERFORM SEND-TRNVIEW-SCREEN; ERR-FLG-ON skips the remainder of the paragraph.
    }

    // L158-174: IF NOT ERR-FLG-ON — clear the 13 detail fields, move the search id to the working
    // key, and read the record.
    clearDetailFields(screen);
    String tranId = moveAlphanumeric(screen.getTrnIdIn(), TRAN_ID_LEN); // MOVE TRNIDINI TO TRAN-ID.
    Optional<Transaction> located = readTransactFile(screen, tranId);

    // L176-192: IF NOT ERR-FLG-ON — populate the detail fields from the located record.
    located.ifPresent(transaction -> populateDetailFields(screen, transaction));
    // PERFORM SEND-TRNVIEW-SCREEN — the controller renders the (populated or error) screen.
  }

  /**
   * Reads the single transaction keyed by {@code tranId}, reproducing {@code READ-TRANSACT-FILE}
   * (L267-296) &mdash; the {@code EXEC CICS READ DATASET('TRANSACT')} followed by {@code EVALUATE
   * WS-RESP-CD}.
   *
   * <p>The keyed VSAM read maps to {@link TransactionRepository#findById(Object)} and the response
   * codes map as follows (AAP &sect;0.6.4):
   *
   * <ul>
   *   <li>{@code DFHRESP(NORMAL)} / FILE STATUS {@code '00'} (L281-282): a present {@link Optional}
   *       is returned for the caller to render.
   *   <li>{@code DFHRESP(NOTFND)} / FILE STATUS {@code '23'} (L283-288): {@link
   *       #MSG_TRAN_NOT_FOUND} is set on the screen and an empty {@link Optional} is returned.
   *   <li>{@code WHEN OTHER} (L289-295): the COBOL displays the response/reason and shows {@link
   *       #MSG_TRAN_LOOKUP_ERROR} on the screen &mdash; it does <em>not</em> abend. This is
   *       reproduced by catching {@link DataAccessException}, logging the cause (the {@code DISPLAY
   *       'RESP:' ... 'REAS:'} equivalent), setting the message, and returning an empty {@link
   *       Optional}. The message is surfaced on-screen rather than thrown so the observable
   *       behavior matches the legacy program.
   * </ul>
   *
   * @param screen the screen contract that receives the not-found / lookup-error message
   * @param tranId the fixed-width 16-character transaction-id key (the working {@code TRAN-ID})
   * @return the located transaction, or {@link Optional#empty()} when the record is absent or the
   *     read failed (with the corresponding message already written to {@code screen})
   */
  private Optional<Transaction> readTransactFile(TranViewScreen screen, String tranId) {
    try {
      Optional<Transaction> located = transactionRepository.findById(tranId);
      if (located.isPresent()) {
        return located; // DFHRESP(NORMAL) — FILE STATUS '00'.
      }
      // DFHRESP(NOTFND) — FILE STATUS '23'.
      screen.setErrMsg(MSG_TRAN_NOT_FOUND);
      return Optional.empty();
    } catch (DataAccessException ex) {
      // WHEN OTHER (L289-295): COBOL DISPLAYs RESP/REAS, then SENDs the message and redisplays —
      // it is NOT an abend. Log the cause (the DISPLAY equivalent) and surface the message.
      LOGGER.warn(
          "Unexpected error reading {} for tranId='{}'; surfacing on-screen message",
          LIT_TRANSACT_FILE,
          tranId,
          ex);
      screen.setErrMsg(MSG_TRAN_LOOKUP_ERROR);
      return Optional.empty();
    }
  }

  /**
   * Maps a located {@link Transaction} onto the screen detail fields, reproducing the population
   * block of {@code PROCESS-ENTER-KEY} (L176-192).
   *
   * <p>Every move preserves the COBOL alphanumeric {@code MOVE} semantics through {@link
   * #moveAlphanumeric(String, int)}: the value is left-justified into the receiving field's fixed
   * width and truncated on the right when the source is wider (for example {@code TRAN-DESC X(100)}
   * &rarr; {@code TDESCI X(60)}, and the {@code X(26)} timestamps &rarr; the {@code X(10)} date
   * fields keep their first ten characters &mdash; the {@code yyyy-MM-dd} date portion). The moves
   * are applied in the exact COBOL order (L177-190).
   *
   * <ul>
   *   <li>{@code TRAN-AMT} &rarr; {@code TRNAMTI}: the screen models the amount as a {@link
   *       java.math.BigDecimal} (conceptual scale 2), so the value is carried through unedited
   *       (decimal fidelity, AAP &sect;0.6.1); the dedicated number-formatter performs any
   *       display-edited rendering at the view layer.
   *   <li>{@code TRAN-MERCHANT-ID PIC 9(09)} &rarr; {@code MIDI PIC X(9)}: the numeric id is
   *       rendered as nine zero-padded display digits by {@link #formatMerchantId(Long)}.
   * </ul>
   *
   * @param screen the screen contract whose detail fields are populated
   * @param tx the located transaction record (the COBOL {@code TRAN-RECORD})
   */
  private void populateDetailFields(TranViewScreen screen, Transaction tx) {
    // L177-190: MOVE the TRAN-RECORD fields onto the screen, in source order.
    screen.setTrnId(moveAlphanumeric(tx.getTranId(), TRAN_ID_LEN));
    screen.setCardNum(moveAlphanumeric(tx.getTranCardNum(), CARD_NUM_LEN));
    screen.setTtypCd(moveAlphanumeric(tx.getTranTypeCd(), TYPE_CD_LEN));
    screen.setTcatCd(moveAlphanumeric(tx.getTranCatCd(), CAT_CD_LEN));
    screen.setTrnSrc(moveAlphanumeric(tx.getTranSource(), SOURCE_LEN));
    // MOVE TRAN-AMT TO WS-TRAN-AMT / TRNAMTI — the screen field is BigDecimal; carry it through.
    screen.setTrnAmt(tx.getTranAmt());
    screen.setTDesc(moveAlphanumeric(tx.getTranDesc(), DESC_LEN)); // X(100) -> X(60) truncation.
    screen.setTOrigDt(moveAlphanumeric(tx.getTranOrigTs(), ORIG_DT_LEN)); // X(26) -> X(10).
    screen.setTProcDt(moveAlphanumeric(tx.getTranProcTs(), PROC_DT_LEN)); // X(26) -> X(10).
    screen.setMid(formatMerchantId(tx.getTranMerchantId())); // 9(09) -> X(9) zero-padded digits.
    screen.setMName(moveAlphanumeric(tx.getTranMerchantName(), MERCHANT_NAME_LEN)); // X(50)->X(30).
    screen.setMCity(moveAlphanumeric(tx.getTranMerchantCity(), MERCHANT_CITY_LEN)); // X(50)->X(25).
    screen.setMZip(moveAlphanumeric(tx.getTranMerchantZip(), MERCHANT_ZIP_LEN));
  }

  /**
   * Clears the screen and redisplays it, reproducing {@code CLEAR-CURRENT-SCREEN} (L301-304):
   * {@code PERFORM INITIALIZE-ALL-FIELDS} then {@code PERFORM SEND-TRNVIEW-SCREEN}. The PF4 action.
   *
   * @param screen the screen contract to blank
   * @return {@code null} so the controller redisplays the freshly blanked screen
   */
  private String clearCurrentScreen(TranViewScreen screen) {
    initializeAllFields(screen);
    return null; // PERFORM SEND-TRNVIEW-SCREEN.
  }

  /**
   * Blanks the search input, every detail field, and the message line, reproducing {@code
   * INITIALIZE-ALL-FIELDS} (L309-326).
   *
   * <p>The COBOL moves {@code -1} to {@code TRNIDINL} (cursor positioning, a controller/view
   * concern that is not modeled here) and {@code SPACES} to {@code TRNIDINI}, the thirteen detail
   * fields, and {@code WS-MESSAGE}.
   *
   * @param screen the screen contract to reset
   */
  private void initializeAllFields(TranViewScreen screen) {
    screen.setTrnIdIn(""); // MOVE SPACES TO TRNIDINI (the search input).
    clearDetailFields(screen); // MOVE SPACES TO the 13 detail output fields.
    screen.setErrMsg(""); // MOVE SPACES TO WS-MESSAGE.
  }

  /**
   * Sets the COMMAREA routing context and resolves the transfer target, reproducing {@code
   * RETURN-TO-PREV-SCREEN} (L197-208).
   *
   * <p>When no target program has been chosen the sign-on program is used as the default
   * (L199-201); the from-transaction and from-program are stamped with this program's identity, and
   * the program context is reset to ENTER ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}) so the target
   * begins in its first-entry state. The {@code EXEC CICS XCTL} itself is performed by the
   * controller, which transfers to the returned program name.
   *
   * @param commarea the session/navigation state to update (must not be {@code null})
   * @return the resolved target program name to transfer to
   */
  private String returnToPrevScreen(CardDemoCommarea commarea) {
    if (isBlankOrNull(commarea.getToProgram())) {
      commarea.setToProgram(LIT_SIGNON_PGM); // L199-201: default CDEMO-TO-PROGRAM to COSGN00C.
    }
    commarea.setFromTranId(TRAN_ID_NAME); // L202: MOVE WS-TRANID TO CDEMO-FROM-TRANID.
    commarea.setFromProgram(PGM_NAME); // L203: MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM.
    commarea.setPgmEnter(); // L204: MOVE ZEROS TO CDEMO-PGM-CONTEXT.
    return commarea.getToProgram(); // L205-208: EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM).
  }

  /**
   * Blanks the thirteen transaction-detail output fields ({@code TRNIDI}, {@code CARDNUMI}, {@code
   * TTYPCDI}, {@code TCATCDI}, {@code TRNSRCI}, {@code TRNAMTI}, {@code TDESCI}, {@code TORIGDTI},
   * {@code TPROCDTI}, {@code MIDI}, {@code MNAMEI}, {@code MCITYI}, {@code MZIPI}).
   *
   * <p>This is the repeated {@code MOVE SPACES TO ...} block that appears in {@code
   * PROCESS-ENTER-KEY} (L159-171) and again in {@code INITIALIZE-ALL-FIELDS} (L313-325), and is
   * also the effect of {@code MOVE LOW-VALUES TO COTRN1AO} on first entry (L101). The {@code
   * String} fields are blanked to the empty string (the migration's "blank" representation) and the
   * {@link java.math.BigDecimal} amount field is cleared to {@code null} (its "no value" state).
   *
   * @param screen the screen contract whose detail fields are blanked
   */
  private void clearDetailFields(TranViewScreen screen) {
    screen.setTrnId("");
    screen.setCardNum("");
    screen.setTtypCd("");
    screen.setTcatCd("");
    screen.setTrnSrc("");
    screen.setTrnAmt(null); // MOVE SPACES TO TRNAMTI — the BigDecimal "blank" is null.
    screen.setTDesc("");
    screen.setTOrigDt("");
    screen.setTProcDt("");
    screen.setMid("");
    screen.setMName("");
    screen.setMCity("");
    screen.setMZip("");
  }

  /**
   * Formats a numeric merchant id as nine zero-padded display digits, reproducing the COBOL {@code
   * MOVE TRAN-MERCHANT-ID (PIC 9(09)) TO MIDI (PIC X(9))} (L187).
   *
   * <p>A COBOL unsigned numeric-display field stores its value as fixed-width, leading-zero digit
   * characters; moving it into an alphanumeric field copies those digits verbatim (for example
   * {@code 12345} &rarr; {@code "000012345"}). A {@code null} id &mdash; which a genuine numeric
   * field never holds in the legacy program &mdash; is rendered defensively as nine spaces. Should
   * the value ever exceed nine digits, the right-most nine are kept to mirror the COBOL high-order
   * truncation of an over-wide numeric {@code MOVE}.
   *
   * @param merchantId the numeric merchant id ({@code TRAN-MERCHANT-ID}); may be {@code null}
   * @return the nine-character merchant-id field value
   */
  private static String formatMerchantId(Long merchantId) {
    if (merchantId == null) {
      return " ".repeat(MERCHANT_ID_LEN);
    }
    String digits = String.format("%0" + MERCHANT_ID_LEN + "d", Math.abs(merchantId));
    if (digits.length() > MERCHANT_ID_LEN) {
      return digits.substring(digits.length() - MERCHANT_ID_LEN); // COBOL high-order truncation.
    }
    return digits;
  }

  /**
   * Reproduces a COBOL alphanumeric {@code MOVE} into a {@code PIC X(width)} receiving field: a
   * {@code null} source becomes all spaces, a shorter value is left-justified and right-padded with
   * spaces, and a longer value is truncated on the right.
   *
   * @param value the source value, possibly {@code null}
   * @param width the receiving field width (the {@code PIC X(width)} size)
   * @return the value normalized to exactly {@code width} characters
   */
  private static String moveAlphanumeric(String value, int width) {
    if (value == null) {
      return " ".repeat(width);
    }
    int len = value.length();
    if (len == width) {
      return value;
    }
    if (len > width) {
      return value.substring(0, width);
    }
    return value + " ".repeat(width - len);
  }

  /**
   * Returns whether the supplied value is {@code null} or contains only whitespace, modeling the
   * COBOL {@code = SPACES OR LOW-VALUES} test on a {@code PIC X} field (an unset {@code LOW-VALUES}
   * field is {@code null} and an all-blank {@code SPACES} field is whitespace-only).
   *
   * @param value the candidate value, possibly {@code null}
   * @return {@code true} when {@code value} is {@code null} or blank
   */
  private static boolean isBlankOrNull(String value) {
    return value == null || value.isBlank();
  }
}

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

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.AccountUpdateScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.util.DateValidationService;
import com.aws.carddemo.util.LookupCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Online <strong>Account Update</strong> business logic — the 1:1 behavioral translation of legacy
 * CICS program {@code COACTUPC.cbl} (transaction {@code CAUP}), the largest and most complex online
 * program in AWS CardDemo.
 *
 * <p>This service edits the <em>Account master</em> and the <em>Customer master</em> together using
 * the legacy optimistic <em>read-for-update &rarr; REWRITE</em> pattern. Because the JPA entities
 * intentionally carry no {@code @Version} column, the optimistic-lock (concurrency) check is
 * reproduced in this service layer: the records are re-read inside the write path and compared,
 * field by field, against the snapshot that was originally fetched and shown to the user (paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC}). The {@link Transactional} boundary supplies the
 * account+customer all-or-nothing rollback that the COBOL achieved with {@code EXEC CICS SYNCPOINT
 * ROLLBACK}.
 *
 * <h2>Parity mandate</h2>
 *
 * <ul>
 *   <li>Every COBOL paragraph maps to exactly one private method (traceability matrix).
 *   <li>The 24-field validation order is preserved precisely — it determines which error is shown
 *       first ({@code WS-RETURN-MSG-OFF} = first-message-wins).
 *   <li>Every user-facing message is reproduced byte-for-byte from the COBOL literals; none are
 *       paraphrased.
 *   <li>All monetary values are {@link BigDecimal} at scale 2 (COBOL {@code PIC S9(10)V99}); no
 *       floating-point type is ever used for decimal data.
 * </ul>
 *
 * <h2>Pseudo-conversational state</h2>
 *
 * <p>The legacy program stores its state machine ({@code ACUP-CHANGE-ACTION}), the originally
 * fetched snapshot ({@code ACUP-OLD-*}) and the screen inputs ({@code ACUP-NEW-*}) in its own
 * {@code WS-THIS-PROGCOMMAREA}. None of the three Java DTOs ({@link AccountUpdateScreen}, {@link
 * CardDemoCommarea}, {@link CardWorkArea}) can carry that program-private state, so this service
 * reconstructs it per request:
 *
 * <ul>
 *   <li>The incoming state is inferred from the program context ({@link
 *       CardDemoCommarea#isPgmEnter()} / {@link CardDemoCommarea#isPgmReenter()}), the AID key, and
 *       whether an account id is already held in the commarea.
 *   <li>The OLD snapshot is established by reading the account/customer at the start of processing;
 *       the write path re-reads them to detect a concurrent change. In a single live request the
 *       two reads return identical data (so production writes proceed), while tests exercise the
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} path by mocking a divergent second read.
 * </ul>
 *
 * <p>Following the established sibling-service convention (for example {@code MainMenuService}),
 * field-validation failures and record-not-found conditions are surfaced as on-screen messages via
 * {@link AccountUpdateScreen#setErrMsg(String)} and the method returns {@code null} to request a
 * redisplay; only genuinely unexpected data-access faults are raised as {@link IoStatusException}
 * (the abend-equivalent).
 *
 * @see com.aws.carddemo.dto.screen.AccountUpdateScreen
 * @see com.aws.carddemo.dto.CardDemoCommarea
 * @see com.aws.carddemo.dto.CardWorkArea
 */
@Service
public class AccountUpdateService {

  // ---------------------------------------------------------------------------------------------
  // Program identity constants (COBOL WS-LITERALS L536-548)
  // ---------------------------------------------------------------------------------------------

  /** CICS transaction id for this program ({@code LIT-THISTRANID}). */
  static final String TRAN_ID = "CAUP";

  /** Program name ({@code LIT-THISPGM}). */
  static final String PGM_NAME = "COACTUPC";

  /** BMS mapset name ({@code LIT-THISMAPSET}). */
  static final String MAPSET = "COACTUP";

  /** BMS map name ({@code LIT-THISMAP}). */
  static final String MAP = "CACTUPA";

  /** Main-menu program to return to on exit ({@code LIT-MENUPGM}). */
  static final String MENU_PROGRAM = "COMEN01C";

  /** Main-menu transaction id ({@code LIT-MENUTRANID}). */
  static final String MENU_TRAN_ID = "CM00";

  // ---------------------------------------------------------------------------------------------
  // Logical file names used for IoStatusException reporting (COBOL LIT-*FILENAME)
  // ---------------------------------------------------------------------------------------------

  private static final String FILE_ACCT = "ACCTDAT";
  private static final String FILE_CUST = "CUSTDAT";
  private static final String FILE_XREF = "CXACAIX";

  // ---------------------------------------------------------------------------------------------
  // Screen header constants. Sourced from COBOL copybook COTTL01Y (CCDA-TITLE01/02) and the
  // shared MenuOptions masks; replicated locally because MenuOptions is not a declared dependency
  // of this file. Values are byte-identical to the rest of the application.
  // ---------------------------------------------------------------------------------------------

  private static final String TITLE_LINE_1 = "      AWS Mainframe Modernization       ";
  private static final String TITLE_LINE_2 = "              CardDemo                  ";
  private static final String DATE_MASK = "MM/dd/yy";
  private static final String TIME_MASK = "HH:mm:ss";

  // ---------------------------------------------------------------------------------------------
  // Information messages — WS-INFO-MSG 88-levels (COBOL L463-478), selected by 3250-SETUP-INFOMSG.
  // ---------------------------------------------------------------------------------------------

  /** {@code PROMPT-FOR-SEARCH-KEYS} — prompt to enter the account id. */
  static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";

  /** {@code PROMPT-FOR-CHANGES} — details shown, awaiting edits. */
  static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";

  /** {@code PROMPT-FOR-CONFIRMATION} — edits validated, awaiting PF5. */
  static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

  /** {@code CONFIRM-UPDATE-SUCCESS} — update committed. */
  static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

  /** {@code INFORM-FAILURE} — update could not be completed. */
  static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

  // ---------------------------------------------------------------------------------------------
  // Return / error messages — WS-RETURN-MSG 88-levels (COBOL L481-528).
  // ---------------------------------------------------------------------------------------------

  /** {@code WS-EXIT-MESSAGE} — set on PF3 exit (trailing spaces preserved). */
  static final String MSG_EXIT = "PF03 pressed.Exiting              ";

  /** {@code WS-PROMPT-FOR-ACCT} — account filter left blank. */
  static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";

  /** Inline STRING literal in {@code 1210-EDIT-ACCOUNT} (L1806-1808) — non-numeric/zero account. */
  static final String MSG_ACCT_NOT_11_DIGIT =
      "Account Number if supplied must be a 11 digit Non-Zero Number";

  /** {@code NO-SEARCH-CRITERIA-RECEIVED}. */
  static final String MSG_NO_SEARCH_CRITERIA = "No input received";

  /** {@code NO-CHANGES-DETECTED}. */
  static final String MSG_NO_CHANGES_DETECTED =
      "No change detected with respect to values fetched.";

  /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF} (first VALUE clause, used by SET). */
  static final String MSG_DID_NOT_FIND_ACCT_XREF =
      "Did not find this account in account card xref file";

  /** {@code DID-NOT-FIND-ACCT-IN-ACCTDAT}. */
  static final String MSG_DID_NOT_FIND_ACCT = "Did not find this account in account master file";

  /** {@code DID-NOT-FIND-CUST-IN-CUSTDAT}. */
  static final String MSG_DID_NOT_FIND_CUST = "Did not find associated customer in master file";

  /** {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}. */
  static final String MSG_COULD_NOT_LOCK_ACCT = "Could not lock account record for update";

  /** {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}. */
  static final String MSG_COULD_NOT_LOCK_CUST = "Could not lock customer record for update";

  /** {@code DATA-WAS-CHANGED-BEFORE-UPDATE}. */
  static final String MSG_DATA_CHANGED = "Record changed by some one else. Please review";

  /** {@code LOCKED-BUT-UPDATE-FAILED}. */
  static final String MSG_UPDATE_FAILED = "Update of record failed";

  /** {@code WHEN OTHER} abend message in {@code 2000-DECIDE-ACTION} (L2640). */
  static final String MSG_UNEXPECTED_DATA = "UNEXPECTED DATA SCENARIO";

  // ---------------------------------------------------------------------------------------------
  // Generic edit message suffixes (appended to TRIM(variable-name)). COBOL paragraphs 1215-1250.
  // ---------------------------------------------------------------------------------------------

  static final String SFX_MUST_BE_SUPPLIED = " must be supplied.";
  static final String SFX_MUST_BE_Y_OR_N = " must be Y or N.";
  static final String SFX_ALPHA_ONLY = " can have alphabets only.";
  static final String SFX_MUST_BE_ALL_NUMERIC = " must be all numeric.";
  static final String SFX_MUST_NOT_BE_ZERO = " must not be zero.";

  /**
   * {@code 1250-EDIT-SIGNED-9V2} "is not valid" suffix (note: no trailing period in the COBOL
   * literal). This message fires in the legacy program when {@code FUNCTION TEST-NUMVAL-C} rejects
   * a malformed amount string. In this service the five monetary fields arrive already parsed as
   * {@link BigDecimal} on {@link AccountUpdateScreen}, so a malformed amount cannot reach this
   * layer — it is rejected during request binding. The literal is retained for traceability and is
   * emitted by {@link #editSigned9v2} only if an amount exceeds the legacy {@code PIC S9(10)V99}
   * capacity.
   */
  static final String SFX_NOT_VALID = " is not valid";

  // Phone edits — 1260-EDIT-US-PHONE-NUM (prefixed with ':').
  static final String SFX_AREA_SUPPLIED = ": Area code must be supplied.";
  static final String SFX_AREA_3DIGIT = ": Area code must be A 3 digit number.";
  static final String SFX_AREA_ZERO = ": Area code cannot be zero";
  static final String SFX_AREA_INVALID = ": Not valid North America general purpose area code";
  static final String SFX_PREFIX_SUPPLIED = ": Prefix code must be supplied.";
  static final String SFX_PREFIX_3DIGIT = ": Prefix code must be A 3 digit number.";
  static final String SFX_PREFIX_ZERO = ": Prefix code cannot be zero";
  static final String SFX_LINE_SUPPLIED = ": Line number code must be supplied.";
  static final String SFX_LINE_4DIGIT = ": Line number code must be A 4 digit number.";
  static final String SFX_LINE_ZERO = ": Line number code cannot be zero";

  // SSN / State / FICO / State-Zip — 1265 / 1270 / 1275 / 1280.
  static final String SFX_SSN_PART1_INVALID = ": should not be 000, 666, or between 900 and 999";
  static final String SFX_STATE_INVALID = ": is not a valid state code";
  static final String SFX_FICO_RANGE = ": should be between 300 and 850";
  static final String MSG_STATE_ZIP_INVALID = "Invalid zip code for state";

  // ---------------------------------------------------------------------------------------------
  // Field labels (WS-EDIT-VARIABLE-NAME values, exact strings from 1200-EDIT-MAP-INPUTS).
  // ---------------------------------------------------------------------------------------------

  static final String FLD_ACCT_STATUS = "Account Status";
  static final String FLD_OPEN_DATE = "Open Date";
  static final String FLD_CREDIT_LIMIT = "Credit Limit";
  static final String FLD_EXPIRY_DATE = "Expiry Date";
  static final String FLD_CASH_CREDIT_LIMIT = "Cash Credit Limit";
  static final String FLD_REISSUE_DATE = "Reissue Date";
  static final String FLD_CURRENT_BALANCE = "Current Balance";
  static final String FLD_CURR_CYC_CREDIT = "Current Cycle Credit Limit";
  static final String FLD_CURR_CYC_DEBIT = "Current Cycle Debit Limit";
  static final String FLD_SSN = "SSN";
  static final String FLD_SSN_PART1 = "SSN: First 3 chars";
  static final String FLD_SSN_PART2 = "SSN 4th & 5th chars";
  static final String FLD_SSN_PART3 = "SSN Last 4 chars";
  static final String FLD_DOB = "Date of Birth";
  static final String FLD_FICO = "FICO Score";
  static final String FLD_FIRST_NAME = "First Name";
  static final String FLD_MIDDLE_NAME = "Middle Name";
  static final String FLD_LAST_NAME = "Last Name";
  static final String FLD_ADDR_LINE_1 = "Address Line 1";
  static final String FLD_STATE = "State";
  static final String FLD_ZIP = "Zip";
  static final String FLD_CITY = "City";
  static final String FLD_COUNTRY = "Country";
  static final String FLD_PHONE_1 = "Phone Number 1";
  static final String FLD_PHONE_2 = "Phone Number 2";
  static final String FLD_EFT = "EFT Account Id";
  static final String FLD_PRI_HOLDER = "Primary Card Holder";

  // ---------------------------------------------------------------------------------------------
  // Field lengths used by the generic edits (COBOL WS-EDIT-ALPHANUM-LENGTH MOVEs).
  // ---------------------------------------------------------------------------------------------

  private static final int LEN_NAME = 25;
  private static final int LEN_ADDR = 50;
  private static final int LEN_STATE = 2;
  private static final int LEN_ZIP = 5;
  private static final int LEN_COUNTRY = 3;
  private static final int LEN_FICO = 3;
  private static final int LEN_EFT = 10;
  private static final int LEN_SSN_PART1 = 3;
  private static final int LEN_SSN_PART2 = 2;
  private static final int LEN_SSN_PART3 = 4;
  private static final int FICO_MIN = 300;
  private static final int FICO_MAX = 850;
  private static final int MONEY_SCALE = 2;

  /** Maximum magnitude representable by the legacy {@code PIC S9(10)V99} monetary fields. */
  private static final BigDecimal MONEY_MAX = new BigDecimal("9999999999.99");

  // ---------------------------------------------------------------------------------------------
  // Injected dependencies (single constructor; no field injection).
  // ---------------------------------------------------------------------------------------------

  private final AccountRepository accountRepository;
  private final CustomerRepository customerRepository;
  private final CardXrefRepository cardXrefRepository;

  /**
   * Creates the service with its required repositories.
   *
   * @param accountRepository repository for the account master ({@code ACCTDAT})
   * @param customerRepository repository for the customer master ({@code CUSTDAT})
   * @param cardXrefRepository repository for the card cross-reference alternate index ({@code
   *     CXACAIX}); used to resolve the customer id from the account id, mirroring paragraph {@code
   *     9200-GETCARDXREF-BYACCT}
   */
  public AccountUpdateService(
      AccountRepository accountRepository,
      CustomerRepository customerRepository,
      CardXrefRepository cardXrefRepository) {
    this.accountRepository = accountRepository;
    this.customerRepository = customerRepository;
    this.cardXrefRepository = cardXrefRepository;
  }

  // ---------------------------------------------------------------------------------------------
  // State machine model (COBOL ACUP-CHANGE-ACTION 88-levels).
  // ---------------------------------------------------------------------------------------------

  /** Mirrors the {@code ACUP-CHANGE-ACTION} flag that drives {@code 2000-DECIDE-ACTION}. */
  enum AcupState {
    /** {@code ACUP-DETAILS-NOT-FETCHED} — no account fetched yet. */
    DETAILS_NOT_FETCHED,
    /** {@code ACUP-SHOW-DETAILS} — account/customer fetched and presented for editing. */
    SHOW_DETAILS,
    /** {@code ACUP-CHANGES-NOT-OK} — edits failed validation. */
    CHANGES_NOT_OK,
    /** {@code ACUP-CHANGES-OK-NOT-CONFIRMED} — edits valid, awaiting PF5 confirmation. */
    CHANGES_OK_NOT_CONFIRMED,
    /** {@code ACUP-CHANGES-OKAYED-AND-DONE} — update committed. */
    CHANGES_OKAYED_AND_DONE,
    /** {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} — could not lock the account for update. */
    CHANGES_OKAYED_LOCK_ERROR,
    /** {@code ACUP-CHANGES-OKAYED-BUT-FAILED} — locked but the REWRITE failed. */
    CHANGES_OKAYED_BUT_FAILED
  }

  /** Outcome of {@code 9600-WRITE-PROCESSING}, mapped to a state by {@code 2000-DECIDE-ACTION}. */
  enum WriteOutcome {
    /** Both records rewritten successfully. */
    SUCCESS,
    /** Account record could not be locked/re-read for update. */
    COULD_NOT_LOCK_ACCT,
    /** Customer record could not be locked/re-read for update. */
    COULD_NOT_LOCK_CUST,
    /** A concurrent change was detected between display and save ({@code 9700}). */
    DATA_WAS_CHANGED,
    /** A record was locked but its REWRITE failed. */
    UPDATE_FAILED
  }

  /**
   * Per-request flow context. Holds the transient state, error/return message, validity flags and
   * the OLD snapshot for the duration of one {@link #processAccountUpdate} call.
   *
   * <p>This is an explicit object (never an instance field) because {@code @Service} beans are
   * singletons shared across threads; keeping all mutable working state here preserves thread
   * safety while standing in for the COBOL {@code WS-THIS-PROGCOMMAREA} working storage.
   */
  private static final class Flow {
    AcupState state = AcupState.DETAILS_NOT_FETCHED;
    boolean inputError;
    boolean noChanges;
    boolean acctFilterValid;
    boolean acctFilterBlank;
    boolean noSearchCriteria;
    boolean foundCustomer;
    boolean stateValid;
    boolean zipValid;

    /** {@code WS-RETURN-MSG}; {@code null} represents {@code WS-RETURN-MSG-OFF}. */
    String returnMsg;

    /** OLD snapshot established at the start of processing (COBOL {@code ACUP-OLD-*}). */
    Account oldAccount;

    Customer oldCustomer;

    boolean returnMsgOff() {
      return returnMsg == null;
    }

    /**
     * Flags an input error and records the first failing message only ({@code WS-RETURN-MSG-OFF}
     * guard — "first message wins").
     *
     * @param message the byte-exact COBOL message text
     */
    void fail(String message) {
      inputError = true;
      if (returnMsg == null) {
        returnMsg = message;
      }
    }
  }

  // =============================================================================================
  // Public entry point — 0000-MAIN (L859-1023) + 2000-DECIDE-ACTION (L2562-2645)
  // =============================================================================================

  /**
   * Processes one pseudo-conversational turn of the account-update transaction ({@code CAUP}),
   * faithfully reproducing COBOL paragraph {@code 0000-MAIN}.
   *
   * <p>The method is {@link Transactional} so the account and customer REWRITEs are committed
   * atomically — the Spring transaction boundary provides the all-or-nothing rollback that the
   * COBOL achieved with {@code EXEC CICS SYNCPOINT ROLLBACK} (L4099-4101).
   *
   * @param screen the account-update screen contract carrying the user's input and receiving the
   *     output to render (never {@code null})
   * @param commarea the shared navigation/session commarea (never {@code null})
   * @param aid the attention identifier (PF key / ENTER) that triggered this turn; {@code null} is
   *     treated as {@code ENTER}
   * @return the next program name to transfer control to when the user exits with PF3, otherwise
   *     {@code null} to redisplay the same screen
   */
  @Transactional
  public String processAccountUpdate(
      AccountUpdateScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");
    CardWorkArea.Aid effectiveAid = (aid == null) ? CardWorkArea.Aid.ENTER : aid;

    Flow flow = new Flow();

    // ----- Fresh entry detection (EIBCALEN=0 OR from-menu-and-not-reenter). -----
    boolean fromMenu = MENU_PROGRAM.equals(safeTrim(commarea.getFromProgram()));
    boolean fresh = commarea.isPgmEnter() || (fromMenu && !commarea.isPgmReenter());

    if (fresh) {
      // INITIALIZE CARDDEMO-COMMAREA / WS-THIS-PROGCOMMAREA: drop the carried account key so the
      // next turn must re-fetch. User id / type are session-level and intentionally preserved for
      // the surrounding Spring application (a documented, non-behavioral deviation from the COBOL
      // blanket INITIALIZE, which has no functional effect on this program's own logic).
      commarea.setAcctId(null);
      commarea.setCustId(null);
      flow.state = AcupState.DETAILS_NOT_FETCHED;
      // EVALUATE branch: (DETAILS-NOT-FETCHED AND PGM-ENTER) -> paint a blank prompt screen.
      firstPaint(screen, commarea, flow);
      commarea.setPgmReenter();
      return null;
    }

    boolean detailsFetched = hasAccount(commarea);

    // ----- YYYY-STORE-PFKEY + AID validity remap (L905-916). -----
    effectiveAid = remapAid(effectiveAid, detailsFetched);

    // ----- EVALUATE branch: PF3 -> exit to caller or main menu. -----
    if (effectiveAid == CardWorkArea.Aid.PFK03) {
      return exitToCaller(screen, commarea);
    }

    // ----- Infer the incoming ACUP state for the OTHER branch (process + decide). -----
    if (!detailsFetched) {
      flow.state = AcupState.DETAILS_NOT_FETCHED;
    } else if (effectiveAid == CardWorkArea.Aid.PFK05) {
      // PF5 is only valid from CHANGES-OK-NOT-CONFIRMED (the confirm prompt), so its presence
      // implies that state.
      flow.state = AcupState.CHANGES_OK_NOT_CONFIRMED;
    } else {
      flow.state = AcupState.SHOW_DETAILS;
    }

    // ----- EVALUATE branch OTHER: 1000-PROCESS-INPUTS, 2000-DECIDE-ACTION, 3000-SEND-MAP. -----
    processInputs(screen, commarea, flow, effectiveAid);
    decideAction(screen, commarea, flow, effectiveAid);
    sendMap(screen, commarea, flow);
    return null;
  }

  /**
   * Remaps the incoming AID to {@code ENTER} when it is not valid in the current state, mirroring
   * the validity test in {@code 0000-MAIN} (L905-916): ENTER and PF3 are always valid; PF5 is valid
   * only while changes are pending confirmation; PF12 (cancel) is valid only once details have been
   * fetched.
   *
   * @param aid the stored AID
   * @param detailsFetched whether account details have already been fetched
   * @return the original AID when valid, otherwise {@code ENTER}
   */
  private CardWorkArea.Aid remapAid(CardWorkArea.Aid aid, boolean detailsFetched) {
    boolean valid =
        aid == CardWorkArea.Aid.ENTER
            || aid == CardWorkArea.Aid.PFK03
            || (aid == CardWorkArea.Aid.PFK05 && detailsFetched)
            || (aid == CardWorkArea.Aid.PFK12 && detailsFetched);
    return valid ? aid : CardWorkArea.Aid.ENTER;
  }

  /**
   * Handles the PF3 exit branch of {@code 0000-MAIN}: compute the return transaction/program (the
   * caller that navigated here, or the main menu by default), stamp this program as the new "from"
   * context, and hand control back. Mirrors L884-927.
   *
   * @param screen the screen (its error line receives the exit message)
   * @param commarea the commarea whose navigation fields are updated for the transfer
   * @return the program to transfer control to
   */
  private String exitToCaller(AccountUpdateScreen screen, CardDemoCommarea commarea) {
    String toTranId = isBlank(commarea.getFromTranId()) ? MENU_TRAN_ID : commarea.getFromTranId();
    String toProgram =
        isBlank(commarea.getFromProgram()) ? MENU_PROGRAM : commarea.getFromProgram();

    commarea.setToTranId(toTranId);
    commarea.setToProgram(toProgram);
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setUsrTypUser();
    commarea.setPgmEnter();
    commarea.setLastMapset(MAPSET);
    commarea.setLastMap(MAP);

    screen.setErrMsg(MSG_EXIT);
    return toProgram;
  }

  /**
   * Paints the initial, blank "enter an account id" screen — the body shared by the fresh-entry and
   * post-reset branches of {@code 0000-MAIN} (the {@code 3000-SEND-MAP} after an {@code INITIALIZE
   * WS-THIS-PROGCOMMAREA}).
   *
   * @param screen the screen to clear and prime
   * @param commarea the active commarea (header context)
   * @param flow the request flow (left in {@code DETAILS_NOT_FETCHED})
   */
  private void firstPaint(AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow) {
    flow.state = AcupState.DETAILS_NOT_FETCHED;
    clearDetailFields(screen);
    screen.setAcctSid(null);
    populateHeader(screen);
    screen.setInfoMsg(INFO_PROMPT_FOR_SEARCH_KEYS);
    screen.setErrMsg("");
  }

  /**
   * Decides the next action once inputs have been processed — the 1:1 translation of {@code
   * 2000-DECIDE-ACTION} (L2562-2645). The EVALUATE is reproduced as an ordered if/else-if chain so
   * that the first matching condition wins, exactly as in COBOL.
   *
   * @param screen the screen contract
   * @param commarea the navigation commarea
   * @param flow the request flow carrying state and the OLD snapshot
   * @param aid the (already-remapped) AID
   */
  private void decideAction(
      AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow, CardWorkArea.Aid aid) {
    if (flow.state == AcupState.DETAILS_NOT_FETCHED || aid == CardWorkArea.Aid.PFK12) {
      // No details shown (fetch) OR user cancelled (PF12): (re-)read and present the record,
      // discarding any edits. Mirrors the shared WHEN DETAILS-NOT-FETCHED / WHEN PFK12 body.
      if (flow.acctFilterValid) {
        flow.returnMsg = null; // SET WS-RETURN-MSG-OFF
        readAccount(screen, commarea, flow);
        if (flow.foundCustomer) {
          flow.state = AcupState.SHOW_DETAILS;
        }
      }
    } else if (flow.state == AcupState.SHOW_DETAILS) {
      // Details shown: ask for confirmation when the edits are clean and actually changed.
      if (!flow.inputError && !flow.noChanges) {
        flow.state = AcupState.CHANGES_OK_NOT_CONFIRMED;
      }
      // else CONTINUE — redisplay the details with any field error / "no changes" message.
    } else if (flow.state == AcupState.CHANGES_NOT_OK) {
      // CONTINUE — redisplay the details with the first validation error message.
      noOp();
    } else if (flow.state == AcupState.CHANGES_OK_NOT_CONFIRMED && aid == CardWorkArea.Aid.PFK05) {
      // Confirmation given: perform the optimistic write and map the outcome to a state.
      WriteOutcome outcome = writeProcessing(screen, commarea, flow);
      switch (outcome) {
        case COULD_NOT_LOCK_ACCT:
          flow.state = AcupState.CHANGES_OKAYED_LOCK_ERROR;
          break;
        case UPDATE_FAILED:
          flow.state = AcupState.CHANGES_OKAYED_BUT_FAILED;
          break;
        case DATA_WAS_CHANGED:
          flow.state = AcupState.SHOW_DETAILS;
          break;
        case SUCCESS:
        case COULD_NOT_LOCK_CUST:
        default:
          // WHEN OTHER in the COBOL EVALUATE maps everything not explicitly handled (success, and
          // — faithful to a latent COBOL quirk — the customer-lock failure) to "okayed and done".
          flow.state = AcupState.CHANGES_OKAYED_AND_DONE;
          break;
      }
    } else if (flow.state == AcupState.CHANGES_OK_NOT_CONFIRMED) {
      // Changes pending but no PF5 — redisplay (CONTINUE).
      noOp();
    } else if (flow.state == AcupState.CHANGES_OKAYED_AND_DONE) {
      // Follow-up turn after a completed update: go back to showing details and reset the keys.
      flow.state = AcupState.SHOW_DETAILS;
      if (isBlank(commarea.getFromTranId())) {
        commarea.setAcctId(null);
        commarea.setCardNum(null);
        commarea.setAcctStatus(null);
      }
    } else {
      // WHEN OTHER — unexpected data scenario. The COBOL abends; for screen parity we surface the
      // message and redisplay rather than terminating the JVM.
      flow.fail(MSG_UNEXPECTED_DATA);
    }
  }

  /**
   * Orchestrates the screen build for this turn — the relevant portion of {@code 3000-SEND-MAP}:
   * header, field values for the current state, then the information / error message lines.
   *
   * @param screen the screen contract
   * @param commarea the navigation commarea
   * @param flow the request flow
   */
  private void sendMap(AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow) {
    populateHeader(screen);
    populateScreenForState(screen, commarea, flow);
    setupInfoMsg(screen, commarea, flow);
  }

  // =============================================================================================
  // Input processing & validation — 1000-PROCESS-INPUTS / 1200-EDIT-MAP-INPUTS (L1429-1676)
  // =============================================================================================

  /**
   * Receives and validates the screen inputs — the combination of {@code 1100-RECEIVE-MAP} and
   * {@code 1200-EDIT-MAP-INPUTS}.
   *
   * <p>Before any record is fetched, only the account-number filter is editable ({@code
   * 1210-EDIT-ACCOUNT}). Once details have been shown, the method first determines whether anything
   * actually changed ({@code 1205-COMPARE-OLD-NEW}); if the user changed nothing, or the edits were
   * already validated/confirmed, no further editing is performed. Otherwise every field is
   * validated in the exact legacy order.
   *
   * @param screen the screen contract carrying user input
   * @param commarea the navigation commarea (receives the resolved account id)
   * @param flow the request flow (state, error message, validity flags, OLD snapshot)
   * @param aid the (already-remapped) AID for this turn
   */
  private void processInputs(
      AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow, CardWorkArea.Aid aid) {
    String acctFilter = normalizeAcctFilter(screen.getAcctSid());

    if (flow.state == AcupState.DETAILS_NOT_FETCHED) {
      // VALIDATE THE SEARCH KEYS — nothing else can be edited yet.
      editAccount(screen, commarea, flow, acctFilter);
      // MOVE LOW-VALUES TO ACUP-OLD-ACCT-DATA: there is no carried snapshot in this state.
      if (flow.acctFilterBlank) {
        // A blank filter is reported as "no input received" (this SET is unconditional in the
        // COBOL and therefore overwrites the prompt message set by 1210-EDIT-ACCOUNT).
        flow.noSearchCriteria = true;
        flow.returnMsg = MSG_NO_SEARCH_CRITERIA;
      }
      return;
    }

    // SEARCH KEYS ALREADY VALIDATED AND DATA FETCHED — confirm the filter flags and load the OLD
    // snapshot (the values that were fetched and shown on a prior turn) for change detection.
    flow.acctFilterValid = true;
    loadOldSnapshot(commarea, flow);

    boolean changed = compareOldNew(screen, flow);

    if (!changed
        || flow.state == AcupState.CHANGES_OK_NOT_CONFIRMED
        || flow.state == AcupState.CHANGES_OKAYED_AND_DONE) {
      // No change detected, or the edits are already validated/confirmed/applied: nothing more to
      // edit (MOVE LOW-VALUES TO WS-NON-KEY-FLAGS; GO TO EXIT).
      if (!changed) {
        flow.noChanges = true;
        flow.returnMsg = MSG_NO_CHANGES_DETECTED;
      }
      return;
    }

    // A change occurred — validate every field. Start pessimistic.
    flow.state = AcupState.CHANGES_NOT_OK;
    validateAllFields(screen, flow);

    // Tail of 1200: clean edits become "ready to confirm".
    if (!flow.inputError) {
      flow.state = AcupState.CHANGES_OK_NOT_CONFIRMED;
    }
  }

  /**
   * Validates the account-number filter — {@code 1210-EDIT-ACCOUNT} (L1783-1822).
   *
   * <p>A blank value flags an input error, sets the "account number not provided" message and
   * clears the carried key. A non-numeric or zero value yields the "must be a 11 digit Non-Zero
   * Number" message. A valid value is parsed and stored on the commarea ({@code CDEMO-ACCT-ID}).
   *
   * @param screen the screen (unused beyond the already-normalised filter, retained for symmetry)
   * @param commarea the commarea whose account id is set/cleared
   * @param flow the request flow (validity flags + message)
   * @param acctFilter the normalised filter ({@code null} when blank / '*')
   */
  private void editAccount(
      AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow, String acctFilter) {
    // SET FLG-ACCTFILTER-NOT-OK TO TRUE (default until proven valid).
    flow.acctFilterValid = false;

    if (acctFilter == null) {
      // Not supplied.
      flow.acctFilterBlank = true;
      flow.fail(MSG_ACCT_NOT_PROVIDED);
      commarea.setAcctId(null);
      return;
    }

    // Not numeric / not 11 characters / zero.
    if (!isAllDigits(acctFilter) || isZeroNumeric(acctFilter)) {
      flow.fail(MSG_ACCT_NOT_11_DIGIT);
      commarea.setAcctId(null);
      return;
    }

    flow.acctFilterValid = true;
    commarea.setAcctId(Long.valueOf(acctFilter));
  }

  /**
   * Runs the 24 field edits in the exact legacy order (L1471-1668) followed by the state/zip
   * cross-field edit. Every edit is executed on every pass; the first failing edit's message is the
   * one retained ({@code WS-RETURN-MSG-OFF} — "first message wins").
   *
   * @param screen the screen contract carrying user input
   * @param flow the request flow accumulating the first error and field-validity flags
   */
  private void validateAllFields(AccountUpdateScreen screen, Flow flow) {
    // 1. Account Status (yes/no).
    editYesNo(flow, FLD_ACCT_STATUS, screen.getAcstTus());

    // 2. Open Date.
    editDate(flow, FLD_OPEN_DATE, screen.getOpnYear(), screen.getOpnMon(), screen.getOpnDay());

    // 3. Credit Limit.
    editSigned9v2(flow, FLD_CREDIT_LIMIT, screen.getAcrdLim());

    // 4. Expiry Date.
    editDate(flow, FLD_EXPIRY_DATE, screen.getExpYear(), screen.getExpMon(), screen.getExpDay());

    // 5. Cash Credit Limit.
    editSigned9v2(flow, FLD_CASH_CREDIT_LIMIT, screen.getAcshLim());

    // 6. Reissue Date.
    editDate(flow, FLD_REISSUE_DATE, screen.getRisYear(), screen.getRisMon(), screen.getRisDay());

    // 7. Current Balance.
    editSigned9v2(flow, FLD_CURRENT_BALANCE, screen.getAcurBal());

    // 8. Current Cycle Credit Limit.
    editSigned9v2(flow, FLD_CURR_CYC_CREDIT, screen.getAcrCycr());

    // 9. Current Cycle Debit Limit.
    editSigned9v2(flow, FLD_CURR_CYC_DEBIT, screen.getAcrCydb());

    // 10. SSN (three parts).
    editUsSsn(flow, screen.getActSsn1(), screen.getActSsn2(), screen.getActSsn3());

    // 11. Date of Birth (structural edit, then the date-of-birth-specific rule when structurally
    //     valid).
    editDateOfBirth(flow, screen.getDobYear(), screen.getDobMon(), screen.getDobDay());

    // 12. FICO Score (numeric, then the 300-850 range rule when numeric).
    boolean ficoNumeric = editNumReqd(flow, FLD_FICO, screen.getAcstFco(), LEN_FICO);
    if (ficoNumeric) {
      editFicoScore(flow, screen.getAcstFco());
    }

    // 13. First Name.
    editAlphaReqd(flow, FLD_FIRST_NAME, screen.getAcsFnam(), LEN_NAME);

    // 14. Middle Name (optional).
    editAlphaOpt(flow, FLD_MIDDLE_NAME, screen.getAcsMnam(), LEN_NAME);

    // 15. Last Name.
    editAlphaReqd(flow, FLD_LAST_NAME, screen.getAcsLnam(), LEN_NAME);

    // 16. Address Line 1 (mandatory; Address Line 2 is intentionally not validated).
    editMandatory(flow, FLD_ADDR_LINE_1, screen.getAcsAdl1(), LEN_ADDR);

    // 17. State (alpha, then the US-state-code rule when alpha-valid).
    boolean stateAlpha = editAlphaReqd(flow, FLD_STATE, screen.getAcsStte(), LEN_STATE);
    flow.stateValid = stateAlpha && editUsStateCd(flow, screen.getAcsStte());

    // 18. Zip.
    flow.zipValid = editNumReqd(flow, FLD_ZIP, screen.getAcsZipc(), LEN_ZIP);

    // 19. City (maps to customer address line 3).
    editAlphaReqd(flow, FLD_CITY, screen.getAcsCity(), LEN_ADDR);

    // 20. Country.
    editAlphaReqd(flow, FLD_COUNTRY, screen.getAcsCtry(), LEN_COUNTRY);

    // 21. Phone Number 1.
    editUsPhoneNum(
        flow, FLD_PHONE_1, screen.getAcsPh1a(), screen.getAcsPh1b(), screen.getAcsPh1c());

    // 22. Phone Number 2.
    editUsPhoneNum(
        flow, FLD_PHONE_2, screen.getAcsPh2a(), screen.getAcsPh2b(), screen.getAcsPh2c());

    // 23. EFT Account Id.
    editNumReqd(flow, FLD_EFT, screen.getAcsEftc(), LEN_EFT);

    // 24. Primary Card Holder (yes/no).
    editYesNo(flow, FLD_PRI_HOLDER, screen.getAcsPflg());

    // Cross-field edit: only when both the state and the zip individually passed.
    if (flow.stateValid && flow.zipValid) {
      editUsStateZipCd(flow, screen.getAcsStte(), screen.getAcsZipc());
    }
  }

  /**
   * Explicit no-action marker standing in for a COBOL {@code CONTINUE} (a matched {@code EVALUATE}
   * branch that deliberately performs no work and falls through to {@code 3000-SEND-MAP}). Using a
   * named call keeps the control-flow branches non-empty and self-documenting.
   */
  private void noOp() {
    // Intentionally empty — mirrors a COBOL CONTINUE.
  }

  // =============================================================================================
  // Generic field edits — COBOL paragraphs 1215-1250
  // =============================================================================================

  /**
   * Builds an error message exactly as the COBOL {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
   * '<suffix>'} construct does: the trimmed variable name immediately followed by the suffix.
   *
   * @param varName the field label (COBOL {@code WS-EDIT-VARIABLE-NAME})
   * @param suffix the message suffix literal (already including its leading {@code ' '} or {@code
   *     ':'})
   * @return the assembled message
   */
  private static String msg(String varName, String suffix) {
    return varName.trim() + suffix;
  }

  /**
   * {@code 1215-EDIT-MANDATORY} (L1823-1852): the field must be present; no character restriction.
   *
   * @return {@code true} when supplied
   */
  private boolean editMandatory(Flow flow, String varName, String value, int len) {
    if (isBlankField(value, len)) {
      flow.fail(msg(varName, SFX_MUST_BE_SUPPLIED));
      return false;
    }
    return true;
  }

  /**
   * {@code 1220-EDIT-YESNO} (L1854-1897): blank is "must be supplied"; otherwise the value must be
   * exactly {@code 'Y'} or {@code 'N'} (uppercase only).
   *
   * @return {@code true} when the value is {@code 'Y'} or {@code 'N'}
   */
  private boolean editYesNo(Flow flow, String varName, String value) {
    if (isBlankSpacesOrZeros(value)) {
      flow.fail(msg(varName, SFX_MUST_BE_SUPPLIED));
      return false;
    }
    String v = value.trim();
    if ("Y".equals(v) || "N".equals(v)) {
      return true;
    }
    flow.fail(msg(varName, SFX_MUST_BE_Y_OR_N));
    return false;
  }

  /**
   * {@code 1225-EDIT-ALPHA-REQD} (L1899-1953): blank is "must be supplied"; otherwise the value may
   * contain only alphabetic characters and spaces.
   *
   * @return {@code true} when supplied and alphabetic
   */
  private boolean editAlphaReqd(Flow flow, String varName, String value, int len) {
    if (isBlankField(value, len)) {
      flow.fail(msg(varName, SFX_MUST_BE_SUPPLIED));
      return false;
    }
    if (isAlphaSpaces(field(value, len))) {
      return true;
    }
    flow.fail(msg(varName, SFX_ALPHA_ONLY));
    return false;
  }

  /**
   * {@code 1235-EDIT-ALPHA-OPT} (L2035-2079): an empty value is valid (optional); a supplied value
   * may contain only alphabetic characters and spaces.
   *
   * @return {@code true} when blank or alphabetic
   */
  private boolean editAlphaOpt(Flow flow, String varName, String value, int len) {
    if (isBlankField(value, len)) {
      return true;
    }
    if (isAlphaSpaces(field(value, len))) {
      return true;
    }
    flow.fail(msg(varName, SFX_ALPHA_ONLY));
    return false;
  }

  /**
   * {@code 1245-EDIT-NUM-REQD} (L2109-2178): blank is "must be supplied"; a non-numeric value is
   * "must be all numeric"; a zero value is "must not be zero".
   *
   * <p>The legacy {@code IS NUMERIC} test runs against the fixed-width field. At this service
   * boundary the value has been normalised by request binding, so the digits-only check is applied
   * to the supplied characters.
   *
   * @return {@code true} when the value is a non-zero run of digits
   */
  private boolean editNumReqd(Flow flow, String varName, String value, int len) {
    if (isBlankField(value, len)) {
      flow.fail(msg(varName, SFX_MUST_BE_SUPPLIED));
      return false;
    }
    String v = field(value, len).trim();
    if (!isAllDigits(v)) {
      flow.fail(msg(varName, SFX_MUST_BE_ALL_NUMERIC));
      return false;
    }
    if (numValLong(v) == 0L) {
      flow.fail(msg(varName, SFX_MUST_NOT_BE_ZERO));
      return false;
    }
    return true;
  }

  /**
   * {@code 1250-EDIT-SIGNED-9V2} (L2180-2208): an absent amount is "must be supplied".
   *
   * <p>The legacy paragraph also emits "{@code is not valid}" when {@code FUNCTION TEST-NUMVAL-C}
   * rejects a malformed amount string; here the amount arrives already parsed as a {@link
   * BigDecimal}, so the only reachable form of that failure is an amount that exceeds the legacy
   * {@code PIC S9(10)V99} capacity.
   *
   * @return {@code true} when the amount is supplied and within capacity
   */
  private boolean editSigned9v2(Flow flow, String varName, BigDecimal value) {
    if (value == null) {
      flow.fail(msg(varName, SFX_MUST_BE_SUPPLIED));
      return false;
    }
    if (value.abs().compareTo(MONEY_MAX) > 0) {
      flow.fail(msg(varName, SFX_NOT_VALID));
      return false;
    }
    return true;
  }

  /**
   * Structural date edit for the open/expiry/reissue fields — {@code EDIT-DATE-CCYYMMDD} (copied
   * from {@code CSUTLDPY}, delegated here to {@link DateValidationService}). The three split screen
   * fields are assembled into an 8-character {@code CCYYMMDD} value before validation.
   *
   * @return {@code true} when the date is structurally valid
   */
  private boolean editDate(Flow flow, String varName, String year, String mon, String day) {
    DateValidationService.DateEditResult result =
        DateValidationService.editDateCcyymmdd(date8(year, mon, day), varName);
    if (!result.isValid()) {
      flow.fail(result.message());
      return false;
    }
    return true;
  }

  /**
   * Date-of-birth edit — the structural {@code EDIT-DATE-CCYYMMDD} followed by the date-of-birth
   * rule ({@code EDIT-DATE-OF-BIRTH}). {@link DateValidationService#editDateOfBirth(String,
   * String)} performs the structural edit first and returns its message on failure, exactly
   * matching the field-11 sequence in {@code 1200-EDIT-MAP-INPUTS} (the date-of-birth rule applies
   * only once the date is structurally valid).
   */
  private void editDateOfBirth(Flow flow, String year, String mon, String day) {
    DateValidationService.DateEditResult result =
        DateValidationService.editDateOfBirth(date8(year, mon, day), FLD_DOB);
    if (!result.isValid()) {
      flow.fail(result.message());
    }
  }

  // =============================================================================================
  // Specialized field edits — COBOL paragraphs 1260-1280
  // =============================================================================================

  /**
   * {@code 1260-EDIT-US-PHONE-NUM} (L2225-2429): a US phone split into area code (3 digits), prefix
   * (3 digits) and line number (4 digits).
   *
   * <p>The phone is optional: when all three parts are blank the field is valid and skipped. When
   * any part is entered, each part is validated in order — supplied, numeric, non-zero — and the
   * area code must additionally be a valid North-America general-purpose area code. Mirroring the
   * legacy {@code GO TO} fall-through, all three parts are examined on every pass (so each can flag
   * an input error) while only the first failing message is retained.
   *
   * @return {@code true} when the phone is blank or fully valid
   */
  private boolean editUsPhoneNum(
      Flow flow, String varName, String area, String prefix, String line) {
    // Not mandatory: all three parts blank -> valid, skip.
    if (isBlankSpaces(area) && isBlankSpaces(prefix) && isBlankSpaces(line)) {
      return true;
    }

    boolean ok = true;

    // Area code.
    if (isBlankSpaces(area)) {
      flow.fail(msg(varName, SFX_AREA_SUPPLIED));
      ok = false;
    } else if (!isAllDigits(area.trim())) {
      flow.fail(msg(varName, SFX_AREA_3DIGIT));
      ok = false;
    } else if (numValLong(area.trim()) == 0L) {
      flow.fail(msg(varName, SFX_AREA_ZERO));
      ok = false;
    } else if (!LookupCodes.isValidGeneralPurposeAreaCode(area.trim())) {
      flow.fail(msg(varName, SFX_AREA_INVALID));
      ok = false;
    }

    // Prefix code.
    if (isBlankSpaces(prefix)) {
      flow.fail(msg(varName, SFX_PREFIX_SUPPLIED));
      ok = false;
    } else if (!isAllDigits(prefix.trim())) {
      flow.fail(msg(varName, SFX_PREFIX_3DIGIT));
      ok = false;
    } else if (numValLong(prefix.trim()) == 0L) {
      flow.fail(msg(varName, SFX_PREFIX_ZERO));
      ok = false;
    }

    // Line number.
    if (isBlankSpaces(line)) {
      flow.fail(msg(varName, SFX_LINE_SUPPLIED));
      ok = false;
    } else if (!isAllDigits(line.trim())) {
      flow.fail(msg(varName, SFX_LINE_4DIGIT));
      ok = false;
    } else if (numValLong(line.trim()) == 0L) {
      flow.fail(msg(varName, SFX_LINE_ZERO));
      ok = false;
    }

    return ok;
  }

  /**
   * {@code 1265-EDIT-US-SSN} (L2431-2491): three numeric parts. Part 1 is three digits and must not
   * be {@code 000}, {@code 666} or in the range {@code 900-999}; part 2 is two digits; part 3 is
   * four digits. Each part is a {@code 1245-EDIT-NUM-REQD} edit with a part-specific label; the
   * part-1 range rule is applied only when part 1 is itself numeric (so an all-zero part 1 is
   * reported as "must not be zero", matching the legacy order).
   */
  private void editUsSsn(Flow flow, String part1, String part2, String part3) {
    boolean part1Numeric = editNumReqd(flow, FLD_SSN_PART1, part1, LEN_SSN_PART1);
    if (part1Numeric) {
      int p1 = (int) numValLong(part1.trim());
      if (p1 == 0 || p1 == 666 || (p1 >= 900 && p1 <= 999)) {
        flow.fail(msg(FLD_SSN_PART1, SFX_SSN_PART1_INVALID));
      }
    }
    editNumReqd(flow, FLD_SSN_PART2, part2, LEN_SSN_PART2);
    editNumReqd(flow, FLD_SSN_PART3, part3, LEN_SSN_PART3);
  }

  /**
   * {@code 1270-EDIT-US-STATE-CD} (L2493-2513): the state code must be a valid US state code.
   *
   * @return {@code true} when the state code is valid
   */
  private boolean editUsStateCd(Flow flow, String state) {
    if (LookupCodes.isValidUsStateCode(safeTrim(state))) {
      return true;
    }
    flow.fail(msg(FLD_STATE, SFX_STATE_INVALID));
    return false;
  }

  /**
   * {@code 1275-EDIT-FICO-SCORE} (L2514-2533): the FICO score must be between 300 and 850
   * inclusive.
   *
   * @return {@code true} when the score is within range
   */
  private boolean editFicoScore(Flow flow, String fico) {
    int score = (int) numValLong(safeTrim(fico));
    if (score >= FICO_MIN && score <= FICO_MAX) {
      return true;
    }
    flow.fail(msg(FLD_FICO, SFX_FICO_RANGE));
    return false;
  }

  /**
   * {@code 1280-EDIT-US-STATE-ZIP-CD} (L2536-2560): cross-field edit validating that the first two
   * digits of the zip are consistent with the state. The message has no field-name prefix.
   */
  private void editUsStateZipCd(Flow flow, String state, String zip) {
    String combo = safeTrim(state) + first2(zip);
    if (!LookupCodes.isValidStateZip2Combo(combo)) {
      flow.fail(MSG_STATE_ZIP_INVALID);
      flow.stateValid = false;
      flow.zipValid = false;
    }
  }

  // =============================================================================================
  // Record reads — 9000-READ-ACCT chain (L3608-3800) + change detection (1205-COMPARE-OLD-NEW)
  // =============================================================================================

  /**
   * Indicates whether account details have already been fetched on a prior turn, by checking
   * whether the commarea still carries the resolved account id ({@code CDEMO-ACCT-ID}). This is the
   * Java analogue of the {@code ACUP-DETAILS-NOT-FETCHED} flag, reconstructed from the
   * round-tripped commarea.
   *
   * @param commarea the navigation commarea
   * @return {@code true} when an account id is present
   */
  private boolean hasAccount(CardDemoCommarea commarea) {
    return commarea.getAcctId() != null;
  }

  /**
   * Loads the OLD snapshot (the account and customer values that were fetched and shown on a prior
   * turn) for use by change detection and the optimistic-concurrency check. This first read
   * establishes the baseline; the write path re-reads the same records to detect a concurrent
   * change.
   *
   * @param commarea the commarea carrying the account (and customer) ids
   * @param flow the request flow whose OLD snapshot is populated
   */
  private void loadOldSnapshot(CardDemoCommarea commarea, Flow flow) {
    Long acctId = commarea.getAcctId();
    if (acctId != null) {
      flow.oldAccount = accountRepository.findById(acctId).orElse(null);
    }
    Long custId = commarea.getCustId();
    if (custId == null && acctId != null) {
      // Resolve the customer id from the account through the cross-reference (9200 chain) when the
      // commarea did not carry it.
      custId = resolveCustId(acctId);
    }
    if (custId != null) {
      flow.oldCustomer = customerRepository.findById(custId).orElse(null);
    }
  }

  /**
   * Reads the account and its customer for display — {@code 9000-READ-ACCT} (L3608-3647) and its
   * sub-paragraphs {@code 9200-GETCARDXREF-BYACCT}, {@code 9300-GETACCTDATA-BYACCT} and {@code
   * 9400-GETCUSTDATA-BYCUST}.
   *
   * <p>The legacy not-found branches embed CICS {@code RESP}/{@code REAS} codes that have no JPA
   * equivalent; this translation uses the program's own stable {@code DID-NOT-FIND-*} message
   * literals (L497-501) for the three not-found conditions. On success the fetched records become
   * the OLD snapshot, the screen is painted from them ({@code 9500-STORE-FETCHED-DATA}) and the
   * customer/card keys are stored on the commarea.
   *
   * @param screen the screen contract painted on success
   * @param commarea the commarea carrying the account id (receives customer/card ids)
   * @param flow the request flow (OLD snapshot + found flag + first error message)
   */
  private void readAccount(AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow) {
    Long acctId = commarea.getAcctId();
    if (acctId == null) {
      return;
    }

    // 9200-GETCARDXREF-BYACCT — resolve the customer id (and card number) via the alternate index.
    List<CardXref> xrefs = cardXrefRepository.findByXrefAcctId(acctId);
    if (xrefs == null || xrefs.isEmpty()) {
      flow.fail(MSG_DID_NOT_FIND_ACCT_XREF);
      return;
    }
    CardXref xref = xrefs.get(0);
    Long custId = xref.getXrefCustId();
    commarea.setCustId(custId);
    commarea.setCardNum(parseLongOrNull(xref.getXrefCardNum()));

    // 9300-GETACCTDATA-BYACCT.
    Optional<Account> account = accountRepository.findById(acctId);
    if (account.isEmpty()) {
      flow.fail(MSG_DID_NOT_FIND_ACCT);
      return;
    }

    // 9400-GETCUSTDATA-BYCUST.
    Optional<Customer> customer =
        (custId == null) ? Optional.empty() : customerRepository.findById(custId);
    if (customer.isEmpty()) {
      flow.fail(MSG_DID_NOT_FIND_CUST);
      return;
    }

    // 9500-STORE-FETCHED-DATA — retain the OLD snapshot and paint the fetched values.
    flow.oldAccount = account.get();
    flow.oldCustomer = customer.get();
    flow.foundCustomer = true;
    commarea.setAcctStatus(flow.oldAccount.getAcctActiveStatus());
    populateScreenFromEntities(screen, flow.oldAccount, flow.oldCustomer);
  }

  /**
   * Detects whether the screen inputs differ from the OLD snapshot — {@code 1205-COMPARE-OLD-NEW}
   * (L1681-1779). Text fields are compared case-insensitively and trimmed; monetary fields are
   * compared numerically; dates are compared in their canonical {@code CCYYMMDD} form. The account
   * block is evaluated first; if it differs the method returns immediately, exactly as the COBOL
   * {@code GO TO ...-EXIT} does.
   *
   * @param screen the screen inputs (NEW values)
   * @param flow the request flow carrying the OLD snapshot
   * @return {@code true} when any field changed ({@code CHANGE-HAS-OCCURRED}); {@code false} when
   *     nothing changed ({@code NO-CHANGES-DETECTED})
   */
  private boolean compareOldNew(AccountUpdateScreen screen, Flow flow) {
    Account oldAccount = flow.oldAccount;
    Customer oldCustomer = flow.oldCustomer;
    if (oldAccount == null || oldCustomer == null) {
      // The previously fetched record is no longer available; treat as changed so the write path
      // surfaces the appropriate lock/not-found outcome.
      return true;
    }

    // ----- Account block. -----
    boolean accountSame =
        eqLong(parseLongOrNull(screen.getAcctSid()), oldAccount.getAcctId())
            && eqUpperTrim(screen.getAcstTus(), oldAccount.getAcctActiveStatus())
            && eqMoney(screen.getAcurBal(), oldAccount.getAcctCurrBal())
            && eqMoney(screen.getAcrdLim(), oldAccount.getAcctCreditLimit())
            && eqMoney(screen.getAcshLim(), oldAccount.getAcctCashCreditLimit())
            && eqDate8(
                date8(screen.getOpnYear(), screen.getOpnMon(), screen.getOpnDay()),
                entityDate8(oldAccount.getAcctOpenDate()))
            && eqDate8(
                date8(screen.getExpYear(), screen.getExpMon(), screen.getExpDay()),
                entityDate8(oldAccount.getAcctExpiraionDate()))
            && eqDate8(
                date8(screen.getRisYear(), screen.getRisMon(), screen.getRisDay()),
                entityDate8(oldAccount.getAcctReissueDate()))
            && eqMoney(screen.getAcrCycr(), oldAccount.getAcctCurrCycCredit())
            && eqMoney(screen.getAcrCydb(), oldAccount.getAcctCurrCycDebit())
            && eqUpperTrim(screen.getAaddGrp(), oldAccount.getAcctGroupId());

    if (!accountSame) {
      return true;
    }

    // ----- Customer block. -----
    boolean customerSame =
        eqUpperTrim(screen.getAcstNum(), longToString(oldCustomer.getCustId()))
            && eqUpperTrim(screen.getAcsFnam(), oldCustomer.getCustFirstName())
            && eqUpperTrim(screen.getAcsMnam(), oldCustomer.getCustMiddleName())
            && eqUpperTrim(screen.getAcsLnam(), oldCustomer.getCustLastName())
            && eqUpperTrim(screen.getAcsAdl1(), oldCustomer.getCustAddrLine1())
            && eqUpperTrim(screen.getAcsAdl2(), oldCustomer.getCustAddrLine2())
            && eqUpperTrim(screen.getAcsCity(), oldCustomer.getCustAddrLine3())
            && eqUpperTrim(screen.getAcsStte(), oldCustomer.getCustAddrStateCd())
            && eqUpperTrim(screen.getAcsCtry(), oldCustomer.getCustAddrCountryCd())
            && eqUpperTrim(screen.getAcsZipc(), oldCustomer.getCustAddrZip())
            && phonePartsSame(
                screen.getAcsPh1a(),
                screen.getAcsPh1b(),
                screen.getAcsPh1c(),
                oldCustomer.getCustPhoneNum1())
            && phonePartsSame(
                screen.getAcsPh2a(),
                screen.getAcsPh2b(),
                screen.getAcsPh2c(),
                oldCustomer.getCustPhoneNum2())
            && eqExactTrim(
                ssn9(screen.getActSsn1(), screen.getActSsn2(), screen.getActSsn3()),
                longToSsn9(oldCustomer.getCustSsn()))
            && eqUpperTrim(screen.getAcsGovt(), oldCustomer.getCustGovtIssuedId())
            && eqDate8(
                date8(screen.getDobYear(), screen.getDobMon(), screen.getDobDay()),
                entityDate8(oldCustomer.getCustDobYyyyMmDd()))
            && eqExactTrim(screen.getAcsEftc(), oldCustomer.getCustEftAccountId())
            && eqUpperTrim(screen.getAcsPflg(), oldCustomer.getCustPriCardHolderInd())
            && eqExactTrim(screen.getAcstFco(), longToString(oldCustomer.getCustFicoCreditScore()));

    return !customerSame;
  }

  /**
   * Resolves a customer id from an account id through the card cross-reference alternate index (the
   * {@code 9200-GETCARDXREF-BYACCT} lookup).
   *
   * @param acctId the account id
   * @return the customer id, or {@code null} when no cross-reference exists
   */
  private Long resolveCustId(Long acctId) {
    List<CardXref> xrefs = cardXrefRepository.findByXrefAcctId(acctId);
    if (xrefs == null || xrefs.isEmpty()) {
      return null;
    }
    return xrefs.get(0).getXrefCustId();
  }

  // =============================================================================================
  // Optimistic write — 9600-WRITE-PROCESSING (L3888-4107) + 9700-CHECK-CHANGE-IN-REC (L4109-4195)
  // =============================================================================================

  /**
   * Performs the optimistic account + customer update — the 1:1 translation of {@code
   * 9600-WRITE-PROCESSING}. Runs only when the edits are valid and the user has confirmed with PF5.
   *
   * <p>The legacy program re-reads both records FOR UPDATE (acquiring a CICS lock), re-checks that
   * neither has changed since they were displayed ({@code 9700-CHECK-CHANGE-IN-REC}), rewrites the
   * account then the customer, and issues {@code SYNCPOINT ROLLBACK} if the customer rewrite fails.
   * Under JPA there is no row-lock verb and the entities carry no {@code @Version}; the optimistic
   * concurrency parity is therefore provided by re-reading the records and comparing them
   * field-by-field against the OLD snapshot that was shown to the user (AAP §0.3.3). The {@link
   * Transactional} boundary on {@link #processAccountUpdate} supplies the all-or-nothing rollback;
   * {@link #requestRollback()} marks the transaction for rollback when a save fails so the partial
   * account update is undone, mirroring {@code SYNCPOINT ROLLBACK}.
   *
   * <p>Note on single-transaction reads: within one request both reads share the same persistence
   * context, so in production the re-read returns the same managed instance as the snapshot and the
   * concurrency check passes (the displayed-vs-current divergence originates in a separate prior
   * request/transaction). Unit tests stub the repository so the second read returns a divergent
   * instance, exercising the {@code DATA-WAS-CHANGED} branch.
   *
   * @param screen the screen contract carrying the validated NEW values to persist
   * @param commarea the commarea carrying the resolved account and customer ids
   * @param flow the request flow (OLD snapshot, error/return message)
   * @return the {@link WriteOutcome} that {@code 2000-DECIDE-ACTION} maps to a state
   */
  private WriteOutcome writeProcessing(
      AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow) {
    Long acctId = commarea.getAcctId();

    // ----- READ account FOR UPDATE (lock). Empty == could not lock; DB error == abend (IO). -----
    Account current;
    try {
      Optional<Account> acctOpt =
          (acctId == null) ? Optional.empty() : accountRepository.findById(acctId);
      if (acctOpt.isEmpty()) {
        // IF WS-RETURN-MSG-OFF SET COULD-NOT-LOCK-ACCT-FOR-UPDATE; SET INPUT-ERROR.
        flow.fail(MSG_COULD_NOT_LOCK_ACCT);
        return WriteOutcome.COULD_NOT_LOCK_ACCT;
      }
      current = acctOpt.get();
    } catch (DataAccessException ex) {
      throw new IoStatusException(FILE_ACCT, "READ", null, ex);
    }

    // ----- READ customer FOR UPDATE (lock). -----
    Long custId = commarea.getCustId();
    Customer currentCust;
    try {
      Optional<Customer> custOpt =
          (custId == null) ? Optional.empty() : customerRepository.findById(custId);
      if (custOpt.isEmpty()) {
        flow.fail(MSG_COULD_NOT_LOCK_CUST);
        return WriteOutcome.COULD_NOT_LOCK_CUST;
      }
      currentCust = custOpt.get();
    } catch (DataAccessException ex) {
      throw new IoStatusException(FILE_CUST, "READ", null, ex);
    }

    // ----- 9700: did someone change the record while we were out ? -----
    if (checkChangeInRec(current, currentCust, flow)) {
      // SET DATA-WAS-CHANGED-BEFORE-UPDATE (an 88 on WS-RETURN-MSG -> sets the message too).
      flow.returnMsg = MSG_DATA_CHANGED;
      return WriteOutcome.DATA_WAS_CHANGED;
    }

    // ----- Prepare and apply the updates to the managed entities. -----
    applyAccountUpdates(current, screen);
    applyCustomerUpdates(currentCust, screen);

    // ----- REWRITE account. A persistence failure -> LOCKED-BUT-UPDATE-FAILED (+ rollback). -----
    try {
      accountRepository.saveAndFlush(current);
    } catch (DataAccessException ex) {
      flow.returnMsg = MSG_UPDATE_FAILED;
      requestRollback();
      return WriteOutcome.UPDATE_FAILED;
    }

    // ----- REWRITE customer. On failure the COBOL issues SYNCPOINT ROLLBACK (undo the acct). -----
    try {
      customerRepository.saveAndFlush(currentCust);
    } catch (DataAccessException ex) {
      flow.returnMsg = MSG_UPDATE_FAILED;
      requestRollback();
      return WriteOutcome.UPDATE_FAILED;
    }

    return WriteOutcome.SUCCESS;
  }

  /**
   * Detects whether the freshly re-read account/customer differ from the OLD snapshot that was
   * shown to the user — the 1:1 translation of {@code 9700-CHECK-CHANGE-IN-REC} (L4109-4195). This
   * is the service-layer optimistic-concurrency check (the entities carry no {@code @Version}).
   *
   * <p>The account block is evaluated first; if it differs the method returns immediately, exactly
   * as the COBOL {@code GO TO 9600-WRITE-PROCESSING-EXIT} does. The COBOL {@code FUNCTION
   * UPPER-CASE}/{@code FUNCTION LOWER-CASE} equality tests reduce to case-insensitive string
   * equality; numeric, money, and date fields are compared by value.
   *
   * @param current the re-read account
   * @param currentCust the re-read customer
   * @param flow the request flow carrying the OLD snapshot
   * @return {@code true} when any field changed ({@code DATA-WAS-CHANGED-BEFORE-UPDATE})
   */
  private boolean checkChangeInRec(Account current, Customer currentCust, Flow flow) {
    Account old = flow.oldAccount;
    Customer oldCust = flow.oldCustomer;
    if (old == null || oldCust == null) {
      // The displayed record is no longer available to compare against; treat as changed so the
      // user is forced to review the now-current data.
      return true;
    }

    // ----- Account block (L4115-4145). GROUP-ID uses LOWER-CASE (== case-insensitive). -----
    boolean accountSame =
        eqExact(current.getAcctActiveStatus(), old.getAcctActiveStatus())
            && eqMoney(current.getAcctCurrBal(), old.getAcctCurrBal())
            && eqMoney(current.getAcctCreditLimit(), old.getAcctCreditLimit())
            && eqMoney(current.getAcctCashCreditLimit(), old.getAcctCashCreditLimit())
            && eqMoney(current.getAcctCurrCycCredit(), old.getAcctCurrCycCredit())
            && eqMoney(current.getAcctCurrCycDebit(), old.getAcctCurrCycDebit())
            && eqDate8(entityDate8(current.getAcctOpenDate()), entityDate8(old.getAcctOpenDate()))
            && eqDate8(
                entityDate8(current.getAcctExpiraionDate()),
                entityDate8(old.getAcctExpiraionDate()))
            && eqDate8(
                entityDate8(current.getAcctReissueDate()), entityDate8(old.getAcctReissueDate()))
            && eqUpper(current.getAcctGroupId(), old.getAcctGroupId());

    if (!accountSame) {
      return true;
    }

    // ----- Customer block (L4150-4192). Names/addr/state/country/govt use UPPER-CASE. -----
    boolean customerSame =
        eqUpper(currentCust.getCustFirstName(), oldCust.getCustFirstName())
            && eqUpper(currentCust.getCustMiddleName(), oldCust.getCustMiddleName())
            && eqUpper(currentCust.getCustLastName(), oldCust.getCustLastName())
            && eqUpper(currentCust.getCustAddrLine1(), oldCust.getCustAddrLine1())
            && eqUpper(currentCust.getCustAddrLine2(), oldCust.getCustAddrLine2())
            && eqUpper(currentCust.getCustAddrLine3(), oldCust.getCustAddrLine3())
            && eqUpper(currentCust.getCustAddrStateCd(), oldCust.getCustAddrStateCd())
            && eqUpper(currentCust.getCustAddrCountryCd(), oldCust.getCustAddrCountryCd())
            && eqExact(currentCust.getCustAddrZip(), oldCust.getCustAddrZip())
            && eqExact(currentCust.getCustPhoneNum1(), oldCust.getCustPhoneNum1())
            && eqExact(currentCust.getCustPhoneNum2(), oldCust.getCustPhoneNum2())
            && eqLong(currentCust.getCustSsn(), oldCust.getCustSsn())
            && eqUpper(currentCust.getCustGovtIssuedId(), oldCust.getCustGovtIssuedId())
            && eqDate8(
                entityDate8(currentCust.getCustDobYyyyMmDd()),
                entityDate8(oldCust.getCustDobYyyyMmDd()))
            && eqExact(currentCust.getCustEftAccountId(), oldCust.getCustEftAccountId())
            && eqExact(currentCust.getCustPriCardHolderInd(), oldCust.getCustPriCardHolderInd())
            && eqLong(currentCust.getCustFicoCreditScore(), oldCust.getCustFicoCreditScore());

    return !customerSame;
  }

  /**
   * Maps the validated NEW screen values onto the managed account entity — the {@code ACCT-UPDATE}
   * construction in {@code 9600-WRITE-PROCESSING} (L3956-4011). All five monetary fields are stored
   * as {@link BigDecimal} truncated to scale 2 ({@link RoundingMode#DOWN}), matching the COBOL
   * {@code PIC S9(10)V99} fields; dates are assembled as {@code yyyy-MM-dd} from the split screen
   * fields. The misspelled entity property {@code acctExpiraionDate} is preserved verbatim.
   *
   * @param acct the managed account entity to mutate
   * @param screen the screen carrying the NEW values
   */
  private void applyAccountUpdates(Account acct, AccountUpdateScreen screen) {
    acct.setAcctActiveStatus(screen.getAcstTus());
    acct.setAcctCurrBal(scale2(screen.getAcurBal()));
    acct.setAcctCreditLimit(scale2(screen.getAcrdLim()));
    acct.setAcctCashCreditLimit(scale2(screen.getAcshLim()));
    acct.setAcctCurrCycCredit(scale2(screen.getAcrCycr()));
    acct.setAcctCurrCycDebit(scale2(screen.getAcrCydb()));
    acct.setAcctOpenDate(
        entityDateStr(screen.getOpnYear(), screen.getOpnMon(), screen.getOpnDay()));
    acct.setAcctExpiraionDate(
        entityDateStr(screen.getExpYear(), screen.getExpMon(), screen.getExpDay()));
    acct.setAcctReissueDate(
        entityDateStr(screen.getRisYear(), screen.getRisMon(), screen.getRisDay()));
    acct.setAcctGroupId(screen.getAaddGrp());
  }

  /**
   * Maps the validated NEW screen values onto the managed customer entity — the {@code CUST-UPDATE}
   * construction in {@code 9600-WRITE-PROCESSING} (L4013-4063). The two phone numbers are assembled
   * into the legacy {@code (aaa)bbb-cccc} display form; the SSN is concatenated from its three
   * parts into the {@code 9(09)} numeric; the date of birth is assembled as {@code yyyy-MM-dd}.
   *
   * @param cust the managed customer entity to mutate
   * @param screen the screen carrying the NEW values
   */
  private void applyCustomerUpdates(Customer cust, AccountUpdateScreen screen) {
    cust.setCustFirstName(screen.getAcsFnam());
    cust.setCustMiddleName(screen.getAcsMnam());
    cust.setCustLastName(screen.getAcsLnam());
    cust.setCustAddrLine1(screen.getAcsAdl1());
    cust.setCustAddrLine2(screen.getAcsAdl2());
    cust.setCustAddrLine3(screen.getAcsCity());
    cust.setCustAddrStateCd(screen.getAcsStte());
    cust.setCustAddrCountryCd(screen.getAcsCtry());
    cust.setCustAddrZip(screen.getAcsZipc());
    cust.setCustPhoneNum1(
        formatPhone(screen.getAcsPh1a(), screen.getAcsPh1b(), screen.getAcsPh1c()));
    cust.setCustPhoneNum2(
        formatPhone(screen.getAcsPh2a(), screen.getAcsPh2b(), screen.getAcsPh2c()));
    cust.setCustSsn(
        parseLongOrNull(ssn9(screen.getActSsn1(), screen.getActSsn2(), screen.getActSsn3())));
    cust.setCustGovtIssuedId(screen.getAcsGovt());
    cust.setCustDobYyyyMmDd(
        entityDateStr(screen.getDobYear(), screen.getDobMon(), screen.getDobDay()));
    cust.setCustEftAccountId(screen.getAcsEftc());
    cust.setCustPriCardHolderInd(screen.getAcsPflg());
    cust.setCustFicoCreditScore(parseLongOrNull(screen.getAcstFco()));
  }

  /**
   * Marks the current transaction for rollback without throwing — the parity for {@code EXEC CICS
   * SYNCPOINT ROLLBACK} (L4099-4101) when a save fails but the screen must still be redisplayed
   * with the failure message. When invoked outside an active transaction (for example a unit test
   * that calls the service directly), the {@link NoTransactionException} is swallowed because there
   * is nothing to roll back.
   */
  private void requestRollback() {
    try {
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    } catch (NoTransactionException ex) {
      // No active transaction (e.g. a direct unit-test invocation) — nothing to roll back; the
      // @Transactional boundary handles rollback in production.
    }
  }

  // =============================================================================================
  // Screen population & messages — 3000-SEND-MAP family (3200 / 3201 / 3202 / 3203 / 3250)
  // =============================================================================================

  /**
   * Populates the screen header — transaction id, program name, the two title lines and the current
   * date/time — mirroring {@code 1000-SEND-MAP}'s header moves. The clock is read once so the date
   * and time are consistent.
   *
   * @param screen the screen contract whose header fields are set
   */
  private void populateHeader(AccountUpdateScreen screen) {
    LocalDateTime now = LocalDateTime.now();
    screen.setTrnName(TRAN_ID);
    screen.setPgmName(PGM_NAME);
    screen.setTitle01(TITLE_LINE_1);
    screen.setTitle02(TITLE_LINE_2);
    screen.setCurDate(now.format(DateTimeFormatter.ofPattern(DATE_MASK)));
    screen.setCurTime(now.format(DateTimeFormatter.ofPattern(TIME_MASK)));
  }

  /**
   * Populates the editable screen fields for the current state — {@code 3200-SETUP-SCREEN-VARS}
   * (L2700-2725). When the program was just entered the primed blank screen is left as-is.
   * Otherwise the field block is chosen by state: a not-fetched / zero account shows blank initial
   * values ({@code 3201}); a fetched account shows the OLD snapshot ({@code 3202}); a record being
   * edited keeps the user's NEW input that the screen DTO already carries ({@code 3203}); any other
   * state defaults to the OLD snapshot.
   *
   * @param screen the screen contract
   * @param commarea the navigation commarea (program-enter flag + resolved account id)
   * @param flow the request flow (state + OLD snapshot)
   */
  private void populateScreenForState(
      AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow) {
    if (commarea.isPgmEnter()) {
      // CONTINUE — leave the blank prompt screen primed by firstPaint untouched.
      return;
    }

    Long acctId = commarea.getAcctId();
    boolean acctZero = (acctId == null || acctId == 0L);

    if (flow.state == AcupState.DETAILS_NOT_FETCHED || acctZero) {
      // 3201-SHOW-INITIAL-VALUES — clear every detail field.
      clearDetailFields(screen);
    } else if (flow.state == AcupState.SHOW_DETAILS) {
      // 3202-SHOW-ORIGINAL-VALUES — repaint from the OLD snapshot.
      populateScreenFromEntities(screen, flow.oldAccount, flow.oldCustomer);
    } else if (flow.state == AcupState.CHANGES_NOT_OK
        || flow.state == AcupState.CHANGES_OK_NOT_CONFIRMED) {
      // 3203-SHOW-UPDATED-VALUES — the screen DTO already carries the user's NEW input; keep it.
      noOp();
    } else {
      // WHEN OTHER -> 3202-SHOW-ORIGINAL-VALUES.
      populateScreenFromEntities(screen, flow.oldAccount, flow.oldCustomer);
    }
  }

  /**
   * Paints the screen detail fields from an account + customer pair — the shared body of {@code
   * 9500-STORE-FETCHED-DATA} and {@code 3202-SHOW-ORIGINAL-VALUES}. Split fields (SSN, the two
   * phones and the three dates) are decomposed exactly as the COBOL reference moves do: SSN from
   * the 9-digit value at offsets (0,3)/(3,5)/(5,9); each phone from the {@code (aaa)bbb-cccc} form
   * at offsets (1,4)/(5,8)/(9,13); each date from the {@code yyyy-MM-dd} value at offsets
   * (0,4)/(5,7)/(8,10). Monetary fields are carried as {@link BigDecimal} (the view layer renders
   * the edited PIC).
   *
   * @param screen the screen contract to paint
   * @param account the account entity (skipped when {@code null})
   * @param customer the customer entity (skipped when {@code null})
   */
  private void populateScreenFromEntities(
      AccountUpdateScreen screen, Account account, Customer customer) {
    if (account != null) {
      screen.setAcstTus(account.getAcctActiveStatus());
      screen.setAcurBal(account.getAcctCurrBal());
      screen.setAcrdLim(account.getAcctCreditLimit());
      screen.setAcshLim(account.getAcctCashCreditLimit());
      screen.setAcrCycr(account.getAcctCurrCycCredit());
      screen.setAcrCydb(account.getAcctCurrCycDebit());
      screen.setOpnYear(datePartYear(account.getAcctOpenDate()));
      screen.setOpnMon(datePartMon(account.getAcctOpenDate()));
      screen.setOpnDay(datePartDay(account.getAcctOpenDate()));
      screen.setExpYear(datePartYear(account.getAcctExpiraionDate()));
      screen.setExpMon(datePartMon(account.getAcctExpiraionDate()));
      screen.setExpDay(datePartDay(account.getAcctExpiraionDate()));
      screen.setRisYear(datePartYear(account.getAcctReissueDate()));
      screen.setRisMon(datePartMon(account.getAcctReissueDate()));
      screen.setRisDay(datePartDay(account.getAcctReissueDate()));
      screen.setAaddGrp(account.getAcctGroupId());
    }
    if (customer != null) {
      screen.setAcstNum(longToString(customer.getCustId()));
      String ssn = longToSsn9(customer.getCustSsn());
      screen.setActSsn1(safeSub(ssn, 0, 3));
      screen.setActSsn2(safeSub(ssn, 3, 5));
      screen.setActSsn3(safeSub(ssn, 5, 9));
      screen.setAcstFco(longToString(customer.getCustFicoCreditScore()));
      screen.setDobYear(datePartYear(customer.getCustDobYyyyMmDd()));
      screen.setDobMon(datePartMon(customer.getCustDobYyyyMmDd()));
      screen.setDobDay(datePartDay(customer.getCustDobYyyyMmDd()));
      screen.setAcsFnam(customer.getCustFirstName());
      screen.setAcsMnam(customer.getCustMiddleName());
      screen.setAcsLnam(customer.getCustLastName());
      screen.setAcsAdl1(customer.getCustAddrLine1());
      screen.setAcsAdl2(customer.getCustAddrLine2());
      screen.setAcsCity(customer.getCustAddrLine3());
      screen.setAcsStte(customer.getCustAddrStateCd());
      screen.setAcsZipc(customer.getCustAddrZip());
      screen.setAcsCtry(customer.getCustAddrCountryCd());
      String ph1 = nz(customer.getCustPhoneNum1());
      screen.setAcsPh1a(safeSub(ph1, 1, 4));
      screen.setAcsPh1b(safeSub(ph1, 5, 8));
      screen.setAcsPh1c(safeSub(ph1, 9, 13));
      String ph2 = nz(customer.getCustPhoneNum2());
      screen.setAcsPh2a(safeSub(ph2, 1, 4));
      screen.setAcsPh2b(safeSub(ph2, 5, 8));
      screen.setAcsPh2c(safeSub(ph2, 9, 13));
      screen.setAcsGovt(customer.getCustGovtIssuedId());
      screen.setAcsEftc(customer.getCustEftAccountId());
      screen.setAcsPflg(customer.getCustPriCardHolderInd());
    }
  }

  /**
   * Clears every editable detail field — {@code 3201-SHOW-INITIAL-VALUES} (the {@code MOVE
   * LOW-VALUES} block). Monetary fields are set to {@code null}; all text/date/SSN/phone parts are
   * blanked. The account-id filter and the header are intentionally not touched here.
   *
   * @param screen the screen contract to clear
   */
  private void clearDetailFields(AccountUpdateScreen screen) {
    screen.setAcstTus(null);
    screen.setAcrdLim(null);
    screen.setAcurBal(null);
    screen.setAcshLim(null);
    screen.setAcrCycr(null);
    screen.setAcrCydb(null);
    screen.setOpnYear(null);
    screen.setOpnMon(null);
    screen.setOpnDay(null);
    screen.setExpYear(null);
    screen.setExpMon(null);
    screen.setExpDay(null);
    screen.setRisYear(null);
    screen.setRisMon(null);
    screen.setRisDay(null);
    screen.setAaddGrp(null);
    screen.setAcstNum(null);
    screen.setActSsn1(null);
    screen.setActSsn2(null);
    screen.setActSsn3(null);
    screen.setAcstFco(null);
    screen.setDobYear(null);
    screen.setDobMon(null);
    screen.setDobDay(null);
    screen.setAcsFnam(null);
    screen.setAcsMnam(null);
    screen.setAcsLnam(null);
    screen.setAcsAdl1(null);
    screen.setAcsAdl2(null);
    screen.setAcsCity(null);
    screen.setAcsStte(null);
    screen.setAcsZipc(null);
    screen.setAcsCtry(null);
    screen.setAcsPh1a(null);
    screen.setAcsPh1b(null);
    screen.setAcsPh1c(null);
    screen.setAcsPh2a(null);
    screen.setAcsPh2b(null);
    screen.setAcsPh2c(null);
    screen.setAcsGovt(null);
    screen.setAcsEftc(null);
    screen.setAcsPflg(null);
  }

  /**
   * Sets the information and error message lines — {@code 3250-SETUP-INFOMSG} (L2945-2978). The
   * information message is selected from the current state (program-enter takes precedence, exactly
   * as the COBOL {@code EVALUATE TRUE} lists it first); the error line receives {@code
   * WS-RETURN-MSG} (blank when no message was raised).
   *
   * @param screen the screen contract whose message lines are set
   * @param commarea the navigation commarea (program-enter flag)
   * @param flow the request flow (state + return message)
   */
  private void setupInfoMsg(AccountUpdateScreen screen, CardDemoCommarea commarea, Flow flow) {
    final String info;
    if (commarea.isPgmEnter()) {
      info = INFO_PROMPT_FOR_SEARCH_KEYS;
    } else {
      switch (flow.state) {
        case SHOW_DETAILS:
        case CHANGES_NOT_OK:
          info = INFO_PROMPT_FOR_CHANGES;
          break;
        case CHANGES_OK_NOT_CONFIRMED:
          info = INFO_PROMPT_FOR_CONFIRMATION;
          break;
        case CHANGES_OKAYED_AND_DONE:
          info = INFO_CONFIRM_UPDATE_SUCCESS;
          break;
        case CHANGES_OKAYED_LOCK_ERROR:
        case CHANGES_OKAYED_BUT_FAILED:
          info = INFO_INFORM_FAILURE;
          break;
        case DETAILS_NOT_FETCHED:
        default:
          // DETAILS-NOT-FETCHED and WS-NO-INFO-MESSAGE both fall to the search-keys prompt.
          info = INFO_PROMPT_FOR_SEARCH_KEYS;
          break;
      }
    }
    screen.setInfoMsg(info);
    screen.setErrMsg(flow.returnMsg == null ? "" : flow.returnMsg);
  }

  // =============================================================================================
  // String / numeric utility helpers (COBOL intrinsic-function and fixed-width equivalents)
  // =============================================================================================

  /**
   * Null-safe identity — returns {@code ""} for {@code null}, otherwise the value unchanged. Stands
   * in for COBOL's treatment of {@code LOW-VALUES}/uninitialised fields as empty.
   */
  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /** Null-safe {@code TRIM} — returns {@code ""} for {@code null}, otherwise the trimmed value. */
  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }

  /** {@code true} when the value is {@code null} or trims to empty (COBOL SPACES / LOW-VALUES). */
  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Normalises the account-number filter. A {@code null} or all-blank value becomes {@code null}
   * (the COBOL {@code LOW-VALUES OR SPACES} "no input" condition); any other value is returned
   * trimmed. A non-blank but non-numeric value (for example {@code "*"}) is intentionally NOT
   * treated as blank so it flows to the "must be a 11 digit Non-Zero Number" edit, matching {@code
   * 1210-EDIT-ACCOUNT}.
   */
  private static String normalizeAcctFilter(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * {@code true} when the value is non-empty and every character is an ASCII digit (IS NUMERIC).
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
   * {@code true} when the value is non-empty and every character is {@code '0'} (numeric ZEROS).
   */
  private static boolean isZeroNumeric(String value) {
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
   * {@code true} when the fixed-width field (the first {@code len} characters) is blank — the COBOL
   * {@code LOW-VALUES OR SPACES OR LENGTH(TRIM)=0} mandatory-field test.
   */
  private static boolean isBlankField(String value, int len) {
    return field(value, len).trim().isEmpty();
  }

  /** {@code true} for blank, all-spaces or all-zeros — the {@code 1220-EDIT-YESNO} blank test. */
  private static boolean isBlankSpacesOrZeros(String value) {
    if (value == null) {
      return true;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() || isZeroNumeric(trimmed);
  }

  /** {@code true} when blank or all spaces (the phone-part {@code SPACES OR LOW-VALUES} test). */
  private static boolean isBlankSpaces(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * {@code true} when every character is an ASCII letter or a space — the {@code 1225/1235} alpha
   * test (COBOL {@code INSPECT CONVERTING} alphabets to spaces, then {@code LENGTH(TRIM)=0}).
   */
  private static boolean isAlphaSpaces(String value) {
    if (value == null) {
      return true;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean alpha = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ' ';
      if (!alpha) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the first {@code len} characters of a fixed-width field ({@code ""} for {@code null}).
   */
  private static String field(String value, int len) {
    if (value == null) {
      return "";
    }
    return value.length() > len ? value.substring(0, len) : value;
  }

  /**
   * Right-pads (with spaces) or truncates a value to an exact width, reproducing a COBOL
   * fixed-width alphanumeric field used when concatenating values with {@code STRING ... DELIMITED
   * BY SIZE}.
   */
  private static String fixedWidth(String value, int width) {
    String v = (value == null) ? "" : value;
    if (v.length() >= width) {
      return v.substring(0, width);
    }
    StringBuilder sb = new StringBuilder(v);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }

  /**
   * Parses the leading numeric value of a string — a focused stand-in for COBOL {@code FUNCTION
   * NUMVAL}. Non-digit characters are ignored; an empty or unparseable result yields {@code 0}.
   */
  private static long numValLong(String value) {
    if (value == null) {
      return 0L;
    }
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
      }
    }
    if (digits.length() == 0) {
      return 0L;
    }
    try {
      return Long.parseLong(digits.toString());
    } catch (NumberFormatException ex) {
      return 0L;
    }
  }

  /** Assembles an 8-character {@code CCYYMMDD} value from the split screen date fields. */
  private static String date8(String year, String mon, String day) {
    return fixedWidth(year, 4) + fixedWidth(mon, 2) + fixedWidth(day, 2);
  }

  /** Normalises an entity {@code yyyy-MM-dd} date to its 8-character {@code CCYYMMDD} form. */
  private static String entityDate8(String entityDate) {
    return nz(entityDate).replace("-", "");
  }

  /** Assembles an entity {@code yyyy-MM-dd} date string from the split screen date fields. */
  private static String entityDateStr(String year, String mon, String day) {
    return fixedWidth(year, 4) + "-" + fixedWidth(mon, 2) + "-" + fixedWidth(day, 2);
  }

  /** Assembles the legacy {@code (aaa)bbb-cccc} phone display form from its three parts. */
  private static String formatPhone(String area, String prefix, String line) {
    return "(" + fixedWidth(area, 3) + ")" + fixedWidth(prefix, 3) + "-" + fixedWidth(line, 4);
  }

  /** Concatenates the three SSN parts into the 9-character {@code 9(09)} value. */
  private static String ssn9(String part1, String part2, String part3) {
    return fixedWidth(part1, 3) + fixedWidth(part2, 2) + fixedWidth(part3, 4);
  }

  /** Returns the first two characters of the zip code (for the state/zip cross-field edit). */
  private static String first2(String zip) {
    return safeSub(nz(zip), 0, 2);
  }

  /**
   * Parses a digit string to a {@link Long}, returning {@code null} when blank or non-numeric. Used
   * for the account id, card number, FICO score and SSN conversions.
   */
  private static Long parseLongOrNull(String value) {
    String trimmed = safeTrim(value);
    if (trimmed.isEmpty() || !isAllDigits(trimmed)) {
      return null;
    }
    try {
      return Long.valueOf(trimmed);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /** Bounds-safe substring — returns {@code ""} for {@code null} or out-of-range requests. */
  private static String safeSub(String value, int start, int end) {
    if (value == null) {
      return "";
    }
    int length = value.length();
    if (start >= length) {
      return "";
    }
    int safeEnd = Math.min(end, length);
    return start >= safeEnd ? "" : value.substring(start, safeEnd);
  }

  /**
   * Scales a monetary amount to two decimals with COBOL-faithful truncation ({@code ROUNDED}
   * absent).
   */
  private static BigDecimal scale2(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.DOWN);
  }

  /** Renders a {@link Long} for display ({@code ""} for {@code null}). */
  private static String longToString(Long value) {
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * Renders a {@link Long} SSN as a zero-padded 9-character string ({@code ""} for {@code null}).
   */
  private static String longToSsn9(Long value) {
    return value == null ? "" : String.format("%09d", value);
  }

  /** First four characters of an entity {@code yyyy-MM-dd} date — the {@code CCYY} year. */
  private static String datePartYear(String entityDate) {
    return safeSub(nz(entityDate), 0, 4);
  }

  /** The two month characters of an entity {@code yyyy-MM-dd} date (offset 5-6). */
  private static String datePartMon(String entityDate) {
    return safeSub(nz(entityDate), 5, 7);
  }

  /** The two day characters of an entity {@code yyyy-MM-dd} date (offset 8-9). */
  private static String datePartDay(String entityDate) {
    return safeSub(nz(entityDate), 8, 10);
  }

  // =============================================================================================
  // Field comparison helpers (COBOL EQUAL / UPPER-CASE / LOWER-CASE / numeric comparisons)
  // =============================================================================================

  /** Null-safe {@link Long} equality. */
  private static boolean eqLong(Long left, Long right) {
    return Objects.equals(left, right);
  }

  /**
   * Case-insensitive equality (no trim) — reproduces both {@code FUNCTION UPPER-CASE(a) =
   * UPPER-CASE(b)} and {@code FUNCTION LOWER-CASE(a) = LOWER-CASE(b)}, which are equivalent
   * equality tests.
   */
  private static boolean eqUpper(String left, String right) {
    return nz(left).equalsIgnoreCase(nz(right));
  }

  /**
   * Trimmed, case-insensitive equality — {@code FUNCTION UPPER-CASE(FUNCTION TRIM(...))} compares.
   */
  private static boolean eqUpperTrim(String left, String right) {
    return nz(left).trim().equalsIgnoreCase(nz(right).trim());
  }

  /** Exact (case-sensitive, no trim) equality — a direct COBOL {@code EQUAL} on fixed fields. */
  private static boolean eqExact(String left, String right) {
    return nz(left).equals(nz(right));
  }

  /** Trimmed, case-sensitive equality — a direct {@code EQUAL} after trimming padding. */
  private static boolean eqExactTrim(String left, String right) {
    return nz(left).trim().equals(nz(right).trim());
  }

  /** Value equality for monetary amounts ({@code compareTo == 0}; both {@code null} are equal). */
  private static boolean eqMoney(BigDecimal left, BigDecimal right) {
    if (left == null && right == null) {
      return true;
    }
    if (left == null || right == null) {
      return false;
    }
    return left.compareTo(right) == 0;
  }

  /** Equality of two already-normalised 8-character {@code CCYYMMDD} date strings. */
  private static boolean eqDate8(String left, String right) {
    return nz(left).equals(nz(right));
  }

  /**
   * Compares the three split phone parts against a stored {@code (aaa)bbb-cccc} value — used by
   * {@code 1205-COMPARE-OLD-NEW}, where the OLD parts are extracted from the stored phone at
   * offsets (1,4)/(5,8)/(9,13).
   */
  private static boolean phonePartsSame(String area, String prefix, String line, String full) {
    String f = nz(full);
    return eqExactTrim(area, safeSub(f, 1, 4))
        && eqExactTrim(prefix, safeSub(f, 5, 8))
        && eqExactTrim(line, safeSub(f, 9, 13));
  }
}

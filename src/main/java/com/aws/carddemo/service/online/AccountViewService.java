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
import com.aws.carddemo.dto.screen.AccountViewScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Online <strong>account-view</strong> business-logic service, migrated with 100% behavioral parity
 * from the legacy CICS COBOL program {@code COACTVWC} (CICS transaction {@code CAVW}; source {@code
 * legacy/app/cbl/COACTVWC.cbl}, 941 lines, <em>STYLE A</em> numbered-paragraph architecture).
 *
 * <p>This service reproduces &mdash; per Agent Action Plan &sect;0.1.1 / &sect;0.7.1, zero
 * functional regression &mdash; the pseudo-conversational "view a single account" transaction: on
 * first entry it prepares a blank screen prompting for an account filter; on re-entry it validates
 * the operator-supplied account number, then performs the three keyed reads (card cross-reference
 * &rarr; account master &rarr; customer master) and paints the resulting 37-field detail screen, or
 * redisplays the screen carrying a validation / not-found message. It is a strictly
 * <strong>read-only</strong> view: it performs no writes, holds no locks, and never mutates
 * persistent state.
 *
 * <p>The {@link com.aws.carddemo.web web} controller layer (sibling {@code AccountViewController})
 * owns the HTTP request/response, the {@link CardDemoCommarea} session state, the BMS-equivalent
 * screen rendering ({@code EXEC CICS SEND}/{@code RECEIVE}), the header block ({@code
 * trnName}/{@code title01}/{@code curDate}/{@code pgmName}/{@code title02}/{@code curTime}), and
 * the resolution of the raw 3270 attention identifier into a {@link CardWorkArea.Aid}. This service
 * is invoked with those already-resolved inputs and returns the next program to route to (the
 * {@code EXEC CICS XCTL} target) when the operator exits, or {@code null} to redisplay the current
 * screen.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7):
 *
 * <ul>
 *   <li>{@code 0000-MAIN} (L262-410) &rarr; {@link #processAccountView(AccountViewScreen,
 *       CardDemoCommarea, CardWorkArea.Aid)}
 *   <li>{@code 0000-MAIN} PF3 / {@code WHEN CCARD-AID-PFK03} exit branch (L324-352) &rarr; {@link
 *       #handleExit(CardDemoCommarea)}
 *   <li>{@code 1000-SEND-MAP} first paint (L416-425) + {@code 1200} prompt setup (L462-463) &rarr;
 *       {@link #handleFirstEntry(AccountViewScreen, CardDemoCommarea)}
 *   <li>{@code 0000-MAIN} {@code WHEN CDEMO-PGM-REENTER} branch (L361-374) &rarr; {@link
 *       #handleReentry(AccountViewScreen, CardDemoCommarea)}
 *   <li>{@code 2000-PROCESS-INPUTS} (L596-605) + {@code 2200-EDIT-MAP-INPUTS} (L622-643) &rarr;
 *       {@link #processInputs(AccountViewScreen, CardDemoCommarea)}
 *   <li>{@code 2210-EDIT-ACCOUNT} (L649-681) &rarr; {@link #editAccountFilter(String,
 *       AccountViewScreen, CardDemoCommarea)}
 *   <li>{@code 9000-READ-ACCT} (L687-718) &rarr; {@link #readAccount(AccountViewScreen,
 *       CardDemoCommarea)}
 *   <li>{@code 9200-GETCARDXREF-BYACCT} (L721-772) &rarr; {@link #getCardXrefByAcct(Long,
 *       AccountViewScreen)}
 *   <li>{@code 9300-GETACCTDATA-BYACCT} (L774-822) &rarr; {@link #getAccountById(Long,
 *       AccountViewScreen)}
 *   <li>{@code 9400-GETCUSTDATA-BYCUST} (L825-872) &rarr; {@link #getCustomerById(Long,
 *       AccountViewScreen)}
 *   <li>{@code 1200-SETUP-SCREEN-VARS} field moves (L471-523) &rarr; {@link
 *       #populateScreen(AccountViewScreen, Account, Customer)}
 *   <li>{@code 1100-SCREEN-INIT}, {@code 1300-SETUP-SCREEN-ATTRS}, {@code 1400-SEND-SCREEN}, {@code
 *       2100-RECEIVE-MAP} &rarr; controller-owned (HTTP/BMS rendering and AID capture); not modeled
 *       here
 * </ul>
 *
 * <p><strong>Parity notes.</strong>
 *
 * <ul>
 *   <li><em>Messages are byte-exact.</em> Every validation and not-found message is reproduced from
 *       the {@code COACTVWC} working-storage literals and {@code STRING} blocks verbatim, including
 *       deliberate double spaces and punctuation quirks (see {@link #MSG_ACCT_FILTER_INVALID} and
 *       the {@code build*NotFoundMessage} helpers). These are validated character-for-character.
 *   <li><em>Not-found is a redisplay, never an exception.</em> A missing cross-reference, account,
 *       or customer (the COBOL {@code DFHRESP(NOTFND)} branch, FILE STATUS {@code '23'}) sets the
 *       on-screen error message and re-prompts (AAP &sect;0.6.4). Only an unexpected data-access
 *       failure (the {@code WHEN OTHER} / abnormal status branch) is escalated as an {@link
 *       IoStatusException}.
 *   <li><em>Decimals never lose fidelity.</em> The five monetary fields are carried as {@link
 *       java.math.BigDecimal} end-to-end (AAP &sect;0.6.1); no {@code float}/{@code double} is
 *       used.
 *   <li><em>Clean short-circuit reads.</em> {@link #readAccount} stops at the first of the three
 *       reads to fail, matching the agent specification and the COBOL {@code 9200} short-circuit
 *       (L697-699); see {@link #readAccount} for the treatment of the dormant {@code 9300}/{@code
 *       9400} condition-name checks (L704/L713).
 * </ul>
 *
 * <p>This is a stateless Spring singleton: it keeps <strong>no</strong> mutable instance fields
 * beyond its injected repositories, so it is safe to share across request threads. All
 * per-interaction state lives in the {@link AccountViewScreen} and {@link CardDemoCommarea}
 * arguments (mirroring the COBOL working storage and {@code DFHCOMMAREA}).
 */
@Service
public class AccountViewService {

  // ---------------------------------------------------------------------------------------------
  // Program identity constants (mirror the COACTVWC WORKING-STORAGE LIT-* literals, L60-72).
  // ---------------------------------------------------------------------------------------------

  /** CICS transaction id of this program ({@code LIT-THISTRANID}, {@code COACTVWC}). */
  static final String TRAN_ID = "CAVW";

  /** Program name of this program ({@code LIT-THISPGM}, {@code COACTVWC}). */
  static final String PGM_NAME = "COACTVWC";

  /** BMS mapset of the account-view screen ({@code LIT-THISMAPSET}). */
  static final String MAPSET = "COACTVW";

  /** BMS map of the account-view screen ({@code LIT-THISMAP}). */
  static final String MAP = "CACTVWA";

  /**
   * Default return program when no caller context is present ({@code LIT-MENUPGM}, the main menu).
   */
  static final String MENU_PROGRAM = "COMEN01C";

  /** Default return transaction id when no caller context is present ({@code LIT-MENUTRANID}). */
  static final String MENU_TRAN_ID = "CM00";

  // ---------------------------------------------------------------------------------------------
  // Message literals (byte-exact from COACTVWC WORKING-STORAGE 88-levels and inline MOVEs).
  // ---------------------------------------------------------------------------------------------

  /**
   * Informational prompt painted in the screen {@code infoMsg} field on every send ({@code
   * WS-PROMPT-FOR-INPUT}, {@code COACTVWC} L113-114). Reproduced byte-for-byte. The companion
   * {@code WS-INFORM-OUTPUT} 88-level (L115-116) is declared in the COBOL but never set, so this
   * prompt is the only informational message the program ever displays.
   */
  static final String MSG_PROMPT_FOR_INPUT = "Enter or update id of account to display";

  /**
   * Error message when the account filter is blank / low-values at the field-level edit ({@code
   * WS-PROMPT-FOR-ACCT}, {@code COACTVWC} L121-122). In the full flow this is immediately
   * overwritten by {@link #MSG_NO_INPUT} via the unguarded cross-field edit (L640-642); it is
   * retained as a distinct constant to mirror the COBOL 88-level exactly.
   */
  static final String MSG_PROMPT_ACCT = "Account number not provided";

  /**
   * Error message produced by the cross-field edit when no search criteria were supplied ({@code
   * NO-SEARCH-CRITERIA-RECEIVED}, {@code COACTVWC} L123-124). This is the message ultimately shown
   * for a blank filter because {@code 2200-EDIT-MAP-INPUTS} sets it unconditionally after {@code
   * 2210-EDIT-ACCOUNT} (L640-642).
   */
  static final String MSG_NO_INPUT = "No input received";

  /**
   * Error message when the account filter is non-numeric or zero. Reproduced byte-for-byte from the
   * inline literal at {@code COACTVWC} L671-672, <strong>including the two spaces between "must"
   * and "be"</strong>, to guarantee byte parity (AAP &sect;0.7.1). The doubled space is a real
   * artifact of the legacy literal and is preserved verbatim &mdash; do not "correct" it.
   */
  static final String MSG_ACCT_FILTER_INVALID =
      "Account Filter must  be a non-zero 11 digit number";

  /**
   * Catch-all message for the {@code WHEN OTHER} branch of the main {@code EVALUATE} ({@code
   * COACTVWC} L379-380). In the COBOL this drives {@code SEND-PLAIN-TEXT}; here it is surfaced as a
   * screen-level error and redisplay (never a thrown exception), preserving the externally
   * observable behavior of re-prompting the operator.
   */
  static final String MSG_UNEXPECTED = "UNEXPECTED DATA SCENARIO";

  // ---------------------------------------------------------------------------------------------
  // Not-found message construction constants.
  //
  // The COBOL builds the three not-found messages with STRING ... DELIMITED BY SIZE from CICS
  // RESP/REAS codes (ERROR-RESP / ERROR-RESP2, PIC X(10), fed from WS-RESP-CD / WS-REAS-CD,
  // PIC S9(09) COMP). For DFHRESP(NOTFND) the response code is 13 and the reason is 0. There is no
  // CICS layer in the Spring target, so these codes are reproduced as fixed values rendered in the
  // COBOL display form (nine zero-padded digits, matching the numeric->alphanumeric MOVE's
  // significant digits). The pure storage artifacts of the legacy build (the PIC X(10) inter-field
  // trailing space and the WS-RETURN-MSG PIC X(75) truncation) carry no business meaning and have
  // no Java/CICS equivalent, so they are intentionally not reproduced; the literal text fragments,
  // however, are byte-exact (AAP &sect;0.7.1).
  // ---------------------------------------------------------------------------------------------

  /** CICS {@code DFHRESP(NOTFND)} response code, rendered into the not-found messages. */
  static final int RESP_NOTFND = 13;

  /** CICS reason code accompanying {@code NOTFND} (always zero), rendered into the messages. */
  static final int REAS_DEFAULT = 0;

  // ---------------------------------------------------------------------------------------------
  // Logical file names used when escalating an unexpected data-access failure to IoStatusException.
  // These mirror the COACTVWC LIT-*NAME literals naming the VSAM datasets / access paths.
  // ---------------------------------------------------------------------------------------------

  /** Card cross-reference account alternate-index path ({@code LIT-CARDXREFNAME-ACCT-PATH}). */
  static final String FILE_CARDXREF = "CXACAIX";

  /** Account master dataset ({@code LIT-ACCTFILENAME}). */
  static final String FILE_ACCT = "ACCTDAT";

  /** Customer master dataset ({@code LIT-CUSTFILENAME}). */
  static final String FILE_CUST = "CUSTDAT";

  /**
   * Read operation name recorded on an escalated I/O failure ({@code ERROR-OPNAME} = {@code
   * 'READ'}).
   */
  static final String OP_READ = "READ";

  /**
   * Placeholder FILE STATUS reported when a {@link DataAccessException} (an abnormal, non-{@code
   * '00'}/{@code '23'} condition &mdash; the COBOL {@code WHEN OTHER} branch) is escalated. JPA
   * does not expose a two-byte VSAM status, so an unknown-status sentinel is used.
   */
  static final String IO_STATUS_UNKNOWN = "??";

  // ---------------------------------------------------------------------------------------------
  // Injected collaborators (the migrated FILE SECTION / VSAM datasets). Final and constructor-set,
  // so this service is immutable and thread-safe.
  // ---------------------------------------------------------------------------------------------

  private final AccountRepository accountRepository;
  private final CustomerRepository customerRepository;
  private final CardXrefRepository cardXrefRepository;

  /**
   * Creates the account-view service with its three read-only data sources.
   *
   * @param accountRepository repository for the account master ({@code ACCTDAT})
   * @param customerRepository repository for the customer master ({@code CUSTDAT})
   * @param cardXrefRepository repository for the card cross-reference ({@code CXACAIX} / {@code
   *     CARDXREF}); supplies the account-to-customer linkage
   */
  public AccountViewService(
      AccountRepository accountRepository,
      CustomerRepository customerRepository,
      CardXrefRepository cardXrefRepository) {
    this.accountRepository = accountRepository;
    this.customerRepository = customerRepository;
    this.cardXrefRepository = cardXrefRepository;
  }

  /**
   * Processes one account-view interaction, reproducing the control flow of {@code COACTVWC
   * 0000-MAIN} (L262-410).
   *
   * <p>The COBOL clears the return message, normalizes the attention key (only {@code ENTER} and
   * {@code PFK03} are valid here; any other key is remapped to {@code ENTER}, L306-314), then runs
   * an {@code EVALUATE TRUE} with four branches: PF3 exit, first-entry paint, re-entry processing,
   * and an unexpected-context catch-all. This method mirrors that structure exactly.
   *
   * @param screen the account-view screen DTO carrying the operator's account-filter input and
   *     receiving the painted account/customer detail and any message; must not be {@code null}
   * @param commarea the pseudo-conversational session state ({@code DFHCOMMAREA} equivalent),
   *     carrying user identity/role and the from/to navigation context; must not be {@code null}
   * @param aid the resolved attention identifier for this submission (as captured by the
   *     controller); a {@code null} or out-of-context key is treated as {@code ENTER}
   * @return the next program to route to (the {@code EXEC CICS XCTL} target) when the operator
   *     exits with PF3, or {@code null} to redisplay the current screen (the controller then
   *     re-renders {@code screen} with its {@code infoMsg}/{@code errMsg})
   */
  public String processAccountView(
      AccountViewScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // COACTVWC L278: SET WS-RETURN-MSG-OFF TO TRUE -- ensure the error message is cleared at entry.
    screen.setErrMsg("");

    // COACTVWC L306-314: validate the AID. Only ENTER and PFK03 are meaningful on this screen;
    // PFK-INVALID is the default and any non-valid key (CLEAR, PA1/PA2, PFK01-02, PFK04-12) is
    // remapped to ENTER. PFK03 alone survives as itself.
    CardWorkArea.Aid effectiveAid =
        (aid == CardWorkArea.Aid.PFK03) ? CardWorkArea.Aid.PFK03 : CardWorkArea.Aid.ENTER;

    // COACTVWC L323-383: EVALUATE TRUE over the (normalized AID, program context).
    if (effectiveAid == CardWorkArea.Aid.PFK03) {
      // WHEN CCARD-AID-PFK03 (L324-352): exit to the caller / main menu.
      return handleExit(commarea);
    }
    if (commarea.isPgmEnter()) {
      // WHEN CDEMO-PGM-ENTER (L353-360): first entry -- paint the blank filter screen.
      return handleFirstEntry(screen, commarea);
    }
    if (commarea.isPgmReenter()) {
      // WHEN CDEMO-PGM-REENTER (L361-374): a submission -- validate, read, and redisplay.
      return handleReentry(screen, commarea);
    }
    // WHEN OTHER (L375-382): the program context is neither ENTER nor REENTER (corrupt state).
    // The COBOL builds an abend culprit/code and sends plain text; here we surface the same
    // externally observable message and redisplay rather than abending the request.
    screen.setErrMsg(MSG_UNEXPECTED);
    return null;
  }

  /**
   * Handles the PF3 exit branch of {@code 0000-MAIN} ({@code WHEN CCARD-AID-PFK03}, {@code
   * COACTVWC} L324-352): computes the return target, stamps this program as the new "from" context,
   * forces the standard user role, resets the program context to ENTER for the target, records the
   * last map, and returns the program to transfer control to.
   *
   * <p>The return target is read <em>before</em> the "from" fields are overwritten, exactly as in
   * the COBOL: when the inbound {@code CDEMO-FROM-TRANID}/{@code CDEMO-FROM-PROGRAM} are blank or
   * low-values the defaults {@link #MENU_TRAN_ID}/{@link #MENU_PROGRAM} are used, otherwise the
   * caller's own transaction/program are used so PF3 returns to wherever the operator came from.
   *
   * @param commarea the session state to update with the navigation hand-off
   * @return the program name to {@code XCTL} to (never {@code null})
   */
  private String handleExit(CardDemoCommarea commarea) {
    // Read the caller context FIRST (L328-339) before stamping our own (L341-342).
    String fromTranId = commarea.getFromTranId();
    String fromProgram = commarea.getFromProgram();
    String toTranId = isBlankOrLowValues(fromTranId) ? MENU_TRAN_ID : fromTranId;
    String toProgram = isBlankOrLowValues(fromProgram) ? MENU_PROGRAM : fromProgram;
    commarea.setToTranId(toTranId);
    commarea.setToProgram(toProgram);

    // L341-347: this program becomes the "from" context for the screen we are returning to,
    // the role is forced to standard user, the target re-enters fresh, and the last map is noted.
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setUsrTypUser();
    commarea.setPgmEnter();
    commarea.setLastMapset(MAPSET);
    commarea.setLastMap(MAP);

    // L349-352: EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) -- the controller performs the forward.
    return toProgram;
  }

  /**
   * Handles the first-entry branch of {@code 0000-MAIN} ({@code WHEN CDEMO-PGM-ENTER}, {@code
   * COACTVWC} L353-360), which performs {@code 1000-SEND-MAP} for the initial paint.
   *
   * <p>On a fresh entry the search criteria are initialized to blank ({@code
   * 1200-SETUP-SCREEN-VARS} L462-466 clears {@code ACCTSIDO}) and the informational prompt {@link
   * #MSG_PROMPT_FOR_INPUT} is shown ({@code WS-PROMPT-FOR-INPUT}, L463). Sending the map flips the
   * program context to re-entry ({@code 1400-SEND-SCREEN} L581: {@code SET CDEMO-PGM-REENTER}) so
   * the operator's next submission is processed as input.
   *
   * @param screen the screen to prepare for the initial paint
   * @param commarea the session state whose context is advanced to re-entry
   * @return {@code null} (redisplay the freshly prompted screen)
   */
  private String handleFirstEntry(AccountViewScreen screen, CardDemoCommarea commarea) {
    // 1200-SETUP-SCREEN-VARS (L462-466): initialize the search criteria to blank.
    screen.setAcctSid("");
    // 1200-SETUP-SCREEN-VARS (L463, L527-529): show the input prompt in the info line.
    screen.setInfoMsg(MSG_PROMPT_FOR_INPUT);
    // 1400-SEND-SCREEN (L581): SET CDEMO-PGM-REENTER so the next submission re-enters as input.
    commarea.setPgmReenter();
    return null;
  }

  /**
   * Handles the re-entry branch of {@code 0000-MAIN} ({@code WHEN CDEMO-PGM-REENTER}, {@code
   * COACTVWC} L361-374): processes the submitted input and, only when it is valid, performs the
   * account read; either way the screen is then (re)sent.
   *
   * <p>The COBOL structure is {@code PERFORM 2000-PROCESS-INPUTS; IF INPUT-ERROR PERFORM
   * 1000-SEND-MAP ELSE PERFORM 9000-READ-ACCT, 1000-SEND-MAP}. Both arms converge on {@code
   * 1000-SEND-MAP}, whose {@code 1200-SETUP-SCREEN-VARS} restores the default info prompt
   * (L527-529) and whose {@code 1400-SEND-SCREEN} re-asserts the re-entry context (L581). When
   * input is valid, {@link #readAccount} either fully populates the detail fields or leaves the
   * screen carrying a not-found error message.
   *
   * @param screen the screen carrying the operator's account-filter input and receiving the result
   * @param commarea the session state (account/customer/card ids are recorded during the read)
   * @return {@code null} (redisplay the screen, populated or carrying a message)
   */
  private String handleReentry(AccountViewScreen screen, CardDemoCommarea commarea) {
    // 2000-PROCESS-INPUTS (L362): validate the operator's account filter.
    boolean inputError = processInputs(screen, commarea);

    // L364-373: only read when the input passed every edit (INPUT-OK); on INPUT-ERROR the COBOL
    // skips the read and re-sends the map with the validation message already in place.
    if (!inputError) {
      readAccount(screen, commarea);
    }

    // Both arms perform 1000-SEND-MAP: restore the default info prompt (1200 L527-529) and keep
    // the re-entry context (1400 L581). The error/not-found text, when present, is already in
    // screen.errMsg (the WS-RETURN-MSG mirror).
    screen.setInfoMsg(MSG_PROMPT_FOR_INPUT);
    commarea.setPgmReenter();
    return null;
  }

  /**
   * Processes the submitted screen input, reproducing {@code 2000-PROCESS-INPUTS} (L596-605) and
   * the field/cross-field edits of {@code 2200-EDIT-MAP-INPUTS} (L622-643).
   *
   * <p>The COBOL receives the map (controller-owned here), normalizes the account filter (an
   * explicit {@code '*'} or all-spaces becomes low-values, L628-633), performs the field-level edit
   * {@code 2210-EDIT-ACCOUNT}, then applies the cross-field edit: if the filter was blank it
   * unconditionally sets {@code NO-SEARCH-CRITERIA-RECEIVED} (L640-642), <em>overwriting</em> the
   * {@code WS-PROMPT-FOR-ACCT} message that {@code 2210} placed for the blank case. The net visible
   * message for a blank filter is therefore {@link #MSG_NO_INPUT}.
   *
   * @param screen the screen supplying {@code acctSid} and receiving any validation message
   * @param commarea the session state whose {@code acctId} is set (to the parsed value, or zero on
   *     error) by the field edit
   * @return {@code true} if any edit failed ({@code INPUT-ERROR}); {@code false} if the filter is a
   *     valid, non-zero account number ({@code INPUT-OK})
   */
  private boolean processInputs(AccountViewScreen screen, CardDemoCommarea commarea) {
    // 2200-EDIT-MAP-INPUTS L628-633: replace an explicit '*' or all-spaces filter with low-values.
    String filter = normalizeFilter(screen.getAcctSid());

    // 2210-EDIT-ACCOUNT (L636-637): the individual field edit.
    boolean inputError = editAccountFilter(filter, screen, commarea);

    // 2200-EDIT-MAP-INPUTS L640-642: CROSS-FIELD EDIT -- if the filter was blank, force the
    // "No input received" message. This MOVE is UNGUARDED in the COBOL, so it overwrites the
    // WS-PROMPT-FOR-ACCT message set by 2210 for the blank case. (A blank filter is exactly the
    // normalized-to-null sentinel produced above, i.e. the COBOL FLG-ACCTFILTER-BLANK state.)
    if (filter == null) {
      screen.setErrMsg(MSG_NO_INPUT);
    }

    return inputError;
  }

  /**
   * Performs the field-level account-filter edit, reproducing {@code 2210-EDIT-ACCOUNT} (L649-681)
   * in its exact validation order.
   *
   * <p>Order, preserved precisely:
   *
   * <ol>
   *   <li><strong>Blank / low-values</strong> (L654-661): flag input error, and &mdash; only if no
   *       message is yet set ({@code WS-RETURN-MSG-OFF}, L658) &mdash; place {@link
   *       #MSG_PROMPT_ACCT}; set the account id to zero.
   *   <li><strong>Not numeric or zero</strong> (L666-676): flag input error, and &mdash; only if no
   *       message is yet set (L670) &mdash; place {@link #MSG_ACCT_FILTER_INVALID}; set the account
   *       id to zero.
   *   <li><strong>Valid</strong> (L677-679): store the parsed account number on the commarea.
   * </ol>
   *
   * @param filter the normalized account filter ({@code null} represents the COBOL low-values /
   *     blank state produced by {@link #normalizeFilter})
   * @param screen the screen receiving any validation message
   * @param commarea the session state whose {@code acctId} is set
   * @return {@code true} on a blank or invalid filter ({@code INPUT-ERROR}); {@code false} when the
   *     filter is a valid, non-zero account number
   */
  private boolean editAccountFilter(
      String filter, AccountViewScreen screen, CardDemoCommarea commarea) {
    // L654-661: CC-ACCT-ID = LOW-VALUES or SPACES -> blank.
    if (filter == null) {
      if (isReturnMsgOff(screen)) {
        screen.setErrMsg(MSG_PROMPT_ACCT);
      }
      commarea.setAcctId(0L);
      return true;
    }

    // L666-676: CC-ACCT-ID NOT NUMERIC OR EQUAL ZEROES -> invalid.
    //
    // The legacy field CC-ACCT-ID is PIC X(11) and is fed by the BMS map field ACCTSID, which is
    // physically eleven columns wide; the 3270 hardware therefore made it impossible for COBOL to
    // ever receive more than eleven characters here, so 2210-EDIT-ACCOUNT had no length test. On
    // the
    // web the same field arrives as an unbounded request parameter, so a length guard is added to
    // restore that physical bound. An over-eleven-character value is, by definition, not "a
    // non-zero
    // 11 digit number", so it is folded into this exact same invalid branch and surfaces the
    // identical MSG_ACCT_FILTER_INVALID rather than being passed on to Long.parseLong below (where
    // an
    // over-Long value would otherwise throw NumberFormatException and abend with HTTP 500). Eleven
    // digits always fit in a long (max long is nineteen digits), so this never rejects a legal id.
    if (filter.length() > 11 || !isAllDigits(filter) || isAllZeros(filter)) {
      if (isReturnMsgOff(screen)) {
        screen.setErrMsg(MSG_ACCT_FILTER_INVALID);
      }
      commarea.setAcctId(0L);
      return true;
    }

    // L677-679: a valid, non-zero account number -> store it for the read.
    commarea.setAcctId(Long.parseLong(filter));
    return false;
  }

  /**
   * Drives the three keyed reads needed to display an account, reproducing {@code 9000-READ-ACCT}
   * (L687-718).
   *
   * <p>The reads run in a fixed order &mdash; card cross-reference ({@code 9200}), then account
   * master ({@code 9300}), then customer master ({@code 9400}) &mdash; and <strong>short-circuit on
   * the first failure</strong>, exactly as specified. The COBOL short-circuits cleanly after {@code
   * 9200} via {@code IF FLG-ACCTFILTER-NOT-OK GO TO 9000-READ-ACCT-EXIT} (L697-699). Its
   * post-{@code 9300}/{@code 9400} guards (L704 {@code DID-NOT-FIND-ACCT-IN-ACCTDAT}, L713 {@code
   * DID-NOT-FIND-CUST-IN-CUSTDAT}) test 88-level condition names defined on {@code WS-RETURN-MSG}
   * against the static literals {@code 'Did not find ...'} &mdash; but {@code 9300}/{@code 9400}
   * populate {@code WS-RETURN-MSG} with the dynamic {@code STRING} messages instead, so those
   * guards never fire (dormant code). Applying the clean short-circuit to all three reads matches
   * the agent specification and the {@code 9200} path; it can only diverge from the literal COBOL
   * on referentially-inconsistent data (an account or customer missing for a cross-reference that
   * does exist), which the referential integrity of the seed/production data never produces, and
   * the operator-visible error message is identical in every realistic scenario.
   *
   * <p>On full success this populates all 37 detail fields ({@link #populateScreen}); on any
   * not-found it leaves the screen carrying the corresponding not-found message and the detail
   * fields untouched (mirroring the COBOL, which only moves account/customer data under {@code
   * FOUND-*} flags, L471/L493).
   *
   * @param screen the screen receiving the populated detail or a not-found message
   * @param commarea the session state supplying the validated {@code acctId} and receiving the
   *     resolved {@code custId}/{@code cardNum} from the cross-reference
   */
  private void readAccount(AccountViewScreen screen, CardDemoCommarea commarea) {
    // L689: SET WS-NO-INFO-MESSAGE -- the info line is reset; the default prompt is restored by the
    // caller's send-map step. L691: the validated account id is the read key (RIDFLD).
    Long acctId = commarea.getAcctId();

    // 9200-GETCARDXREF-BYACCT (L693-694).
    CardXref xref = getCardXrefByAcct(acctId, screen);
    if (xref == null) {
      // L697-699: IF FLG-ACCTFILTER-NOT-OK GO TO 9000-READ-ACCT-EXIT.
      return;
    }
    // L739-740: on a normal cross-reference read, carry the customer id and card number forward.
    commarea.setCustId(xref.getXrefCustId());
    commarea.setCardNum(parseCardNum(xref.getXrefCardNum()));

    // 9300-GETACCTDATA-BYACCT (L701-702).
    Account account = getAccountById(acctId, screen);
    if (account == null) {
      // L704-706 (clean short-circuit; see method Javadoc).
      return;
    }

    // L708: the customer id resolved from the cross-reference is the customer read key.
    Long custId = commarea.getCustId();

    // 9400-GETCUSTDATA-BYCUST (L710-711).
    Customer customer = getCustomerById(custId, screen);
    if (customer == null) {
      // L713-715 (clean short-circuit; see method Javadoc).
      return;
    }

    // Both FOUND-ACCT-IN-MASTER and FOUND-CUST-IN-MASTER are now true -> paint the full screen.
    populateScreen(screen, account, customer);
  }

  /**
   * Reads the card cross-reference by account id, reproducing {@code 9200-GETCARDXREF-BYACCT}
   * (L721-772).
   *
   * <p>This is the keyed read of the {@code CARDXREF} account alternate-index path ({@code
   * CXACAIX}). The legacy {@code EXEC CICS READ} returns the first record on that key; the Spring
   * equivalent {@link CardXrefRepository#findByXrefAcctId(Long)} returns the matching records, of
   * which the first is taken. An empty result is the {@code DFHRESP(NOTFND)} branch (L741-758):
   * input error plus the cross-reference not-found message (set only when no message is yet
   * present, L744). Any {@link DataAccessException} is the {@code WHEN OTHER} abnormal branch
   * (L759-766) and is escalated as an {@link IoStatusException}.
   *
   * @param acctId the account id to look up
   * @param screen the screen receiving the not-found message on a miss
   * @return the resolved {@link CardXref}, or {@code null} when none exists for the account
   * @throws IoStatusException on an unexpected data-access failure (abnormal status)
   */
  private CardXref getCardXrefByAcct(Long acctId, AccountViewScreen screen) {
    try {
      List<CardXref> matches = cardXrefRepository.findByXrefAcctId(acctId);
      if (matches.isEmpty()) {
        // DFHRESP(NOTFND) (L741-758): record not found ('23').
        if (isReturnMsgOff(screen)) {
          screen.setErrMsg(buildXrefNotFoundMessage(acctId));
        }
        return null;
      }
      // The keyed read of the alternate-index path yields the first matching record (L737-740).
      return matches.get(0);
    } catch (DataAccessException ex) {
      // WHEN OTHER (L759-766): abnormal I/O status -> escalate.
      throw new IoStatusException(FILE_CARDXREF, OP_READ, IO_STATUS_UNKNOWN, ex);
    }
  }

  /**
   * Reads the account master by id, reproducing {@code 9300-GETACCTDATA-BYACCT} (L774-822).
   *
   * <p>A present record is the {@code DFHRESP(NORMAL)} branch ({@code FOUND-ACCT-IN-MASTER}, L788);
   * an {@link Optional#empty()} result is the {@code DFHRESP(NOTFND)} branch (L789-807): input
   * error plus the account not-found message (set only when no message is yet present, L793). Any
   * {@link DataAccessException} is the {@code WHEN OTHER} abnormal branch (L809-816) and is
   * escalated as an {@link IoStatusException}.
   *
   * @param acctId the account id to look up
   * @param screen the screen receiving the not-found message on a miss
   * @return the resolved {@link Account}, or {@code null} when it does not exist
   * @throws IoStatusException on an unexpected data-access failure (abnormal status)
   */
  private Account getAccountById(Long acctId, AccountViewScreen screen) {
    try {
      Optional<Account> account = accountRepository.findById(acctId);
      if (account.isEmpty()) {
        // DFHRESP(NOTFND) (L789-807): record not found ('23').
        if (isReturnMsgOff(screen)) {
          screen.setErrMsg(buildAcctNotFoundMessage(acctId));
        }
        return null;
      }
      return account.get();
    } catch (DataAccessException ex) {
      // WHEN OTHER (L809-816): abnormal I/O status -> escalate.
      throw new IoStatusException(FILE_ACCT, OP_READ, IO_STATUS_UNKNOWN, ex);
    }
  }

  /**
   * Reads the customer master by id, reproducing {@code 9400-GETCUSTDATA-BYCUST} (L825-872).
   *
   * <p>A present record is the {@code DFHRESP(NORMAL)} branch ({@code FOUND-CUST-IN-MASTER}, L838);
   * an {@link Optional#empty()} result is the {@code DFHRESP(NOTFND)} branch (L839-857): input
   * error plus the customer not-found message (set only when no message is yet present, L845). Any
   * {@link DataAccessException} is the {@code WHEN OTHER} abnormal branch (L858+) and is escalated
   * as an {@link IoStatusException}.
   *
   * @param custId the customer id (resolved from the cross-reference) to look up
   * @param screen the screen receiving the not-found message on a miss
   * @return the resolved {@link Customer}, or {@code null} when it does not exist
   * @throws IoStatusException on an unexpected data-access failure (abnormal status)
   */
  private Customer getCustomerById(Long custId, AccountViewScreen screen) {
    try {
      Optional<Customer> customer = customerRepository.findById(custId);
      if (customer.isEmpty()) {
        // DFHRESP(NOTFND) (L839-857): record not found ('23').
        if (isReturnMsgOff(screen)) {
          screen.setErrMsg(buildCustNotFoundMessage(custId));
        }
        return null;
      }
      return customer.get();
    } catch (DataAccessException ex) {
      // WHEN OTHER (L858+): abnormal I/O status -> escalate.
      throw new IoStatusException(FILE_CUST, OP_READ, IO_STATUS_UNKNOWN, ex);
    }
  }

  /**
   * Copies the resolved account and customer onto the screen, reproducing the field moves of {@code
   * 1200-SETUP-SCREEN-VARS} (L471-523).
   *
   * <p>This is only invoked on full success (both {@code FOUND-ACCT-IN-MASTER} and {@code
   * FOUND-CUST-IN-MASTER}), so both the account block (L471-491) and the customer block (L493-523)
   * apply and all detail fields are populated. The five monetary fields are carried as raw {@link
   * java.math.BigDecimal} with no rounding or formatting (AAP &sect;0.6.1); the controller/view
   * renders them. String fields are moved verbatim, mirroring the COBOL fixed-width {@code MOVE}
   * (no trimming).
   *
   * <p>Two faithful-translation details are preserved deliberately: the account expiry getter is
   * {@code getAcctExpiraionDate()} (the misspelling originates in the {@code CVACT01Y} copybook and
   * is retained across the entity), and the screen "city" field is sourced from customer address
   * line 3 ({@code CUST-ADDR-LINE-3}, L513).
   *
   * @param screen the screen to populate
   * @param account the resolved account master record
   * @param customer the resolved customer master record
   */
  private void populateScreen(AccountViewScreen screen, Account account, Customer customer) {
    // --- Account block (L471-491). ---
    screen.setAcstTus(account.getAcctActiveStatus());
    // Five monetary fields as raw BigDecimal (scale 2), never float/double (AAP 0.6.1).
    screen.setAcurBal(account.getAcctCurrBal());
    screen.setAcrdLim(account.getAcctCreditLimit());
    screen.setAcshLim(account.getAcctCashCreditLimit());
    screen.setAcrCycr(account.getAcctCurrCycCredit());
    screen.setAcrCydb(account.getAcctCurrCycDebit());
    screen.setAdtOpen(account.getAcctOpenDate());
    // getAcctExpiraionDate: misspelling preserved from the CVACT01Y copybook (faithful
    // translation).
    screen.setAexpDt(account.getAcctExpiraionDate());
    screen.setAreisDt(account.getAcctReissueDate());
    screen.setAaddGrp(account.getAcctGroupId());

    // --- Customer block (L493-523). ---
    screen.setAcstNum(formatId9(customer.getCustId()));
    // L496-503: STRING CUST-SSN(1:3) '-' (4:2) '-' (6:4) -> "ddd-dd-dddd".
    screen.setAcstSsn(formatSsn(customer.getCustSsn()));
    screen.setAcstFco(formatFico(customer.getCustFicoCreditScore()));
    screen.setAcstDob(customer.getCustDobYyyyMmDd());
    screen.setAcsFnam(customer.getCustFirstName());
    screen.setAcsMnam(customer.getCustMiddleName());
    screen.setAcsLnam(customer.getCustLastName());
    screen.setAcsAdl1(customer.getCustAddrLine1());
    screen.setAcsAdl2(customer.getCustAddrLine2());
    // L513: the "city" screen field is fed from customer address line 3.
    screen.setAcsCity(customer.getCustAddrLine3());
    screen.setAcsStte(customer.getCustAddrStateCd());
    screen.setAcsZipc(customer.getCustAddrZip());
    screen.setAcsCtry(customer.getCustAddrCountryCd());
    screen.setAcsPhn1(customer.getCustPhoneNum1());
    screen.setAcsPhn2(customer.getCustPhoneNum2());
    screen.setAcsGovt(customer.getCustGovtIssuedId());
    screen.setAcsEftc(customer.getCustEftAccountId());
    screen.setAcsPflg(customer.getCustPriCardHolderInd());

    // The displayed account filter echoes the 11-digit account id (CC-ACCT-ID).
    screen.setAcctSid(formatId11(account.getAcctId()));
  }

  // ---------------------------------------------------------------------------------------------
  // Not-found message builders. The literal text fragments are byte-exact from the COACTVWC STRING
  // blocks (including the documented spacing/punctuation quirks); the account/customer ids are
  // zero-padded to their VSAM key widths and the CICS RESP/REAS codes are rendered as fixed
  // NOTFND values (see the not-found constants above).
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds the cross-reference not-found message ({@code 9200} {@code STRING} block, L747-757).
   * Note the <strong>two spaces</strong> after {@code "Cross ref file."}, preserved verbatim.
   *
   * @param acctId the account id that was not found in the cross-reference
   * @return the byte-exact not-found message
   */
  private static String buildXrefNotFoundMessage(long acctId) {
    return "Account:"
        + formatId11(acctId)
        + " not found in Cross ref file.  Resp:"
        + formatRespCode(RESP_NOTFND)
        + " Reas:"
        + formatRespCode(REAS_DEFAULT);
  }

  /**
   * Builds the account-master not-found message ({@code 9300} {@code STRING} block, L796-806). Note
   * there is <strong>no space</strong> after {@code "Acct Master file."}, preserved verbatim.
   *
   * @param acctId the account id that was not found in the account master
   * @return the byte-exact not-found message
   */
  private static String buildAcctNotFoundMessage(long acctId) {
    return "Account:"
        + formatId11(acctId)
        + " not found in Acct Master file.Resp:"
        + formatRespCode(RESP_NOTFND)
        + " Reas:"
        + formatRespCode(REAS_DEFAULT);
  }

  /**
   * Builds the customer-master not-found message ({@code 9400} {@code STRING} block, L846-856).
   * Note the <strong>space after</strong> {@code "Resp:"} and the all-caps {@code "REAS:"}, both
   * preserved verbatim.
   *
   * @param custId the customer id that was not found in the customer master
   * @return the byte-exact not-found message
   */
  private static String buildCustNotFoundMessage(long custId) {
    return "CustId:"
        + formatId9(custId)
        + " not found in customer master.Resp: "
        + formatRespCode(RESP_NOTFND)
        + " REAS:"
        + formatRespCode(REAS_DEFAULT);
  }

  // ---------------------------------------------------------------------------------------------
  // Small, stateless helpers (mirror COBOL field semantics).
  // ---------------------------------------------------------------------------------------------

  /**
   * Normalizes the raw account filter to the COBOL value model ({@code 2200-EDIT-MAP-INPUTS}
   * L628-633): an explicit {@code "*"}, an empty/blank value, or {@code null} all become the
   * low-values sentinel ({@code null}); otherwise the trimmed digits are returned.
   *
   * @param acctSid the raw filter from the screen
   * @return the trimmed filter, or {@code null} for the blank / low-values state
   */
  private static String normalizeFilter(String acctSid) {
    if (acctSid == null) {
      return null;
    }
    String trimmed = acctSid.trim();
    if (trimmed.isEmpty() || "*".equals(trimmed)) {
      return null;
    }
    return trimmed;
  }

  /**
   * Parses a fixed-width card-number string ({@code XREF-CARD-NUM}, {@code PIC X(16)}) into the
   * numeric form carried on the commarea ({@code CDEMO-CARD-NUM}). Card numbers are numeric by the
   * domain contract; a blank/empty value yields {@code null}.
   *
   * @param cardNum the cross-reference card-number string
   * @return the numeric card number, or {@code null} when blank
   */
  private static Long parseCardNum(String cardNum) {
    if (cardNum == null) {
      return null;
    }
    String trimmed = cardNum.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return Long.parseLong(trimmed);
  }

  /**
   * Formats a social-security number as {@code "ddd-dd-dddd"} ({@code 1200-SETUP-SCREEN-VARS}
   * L496-503): the nine zero-padded digits split {@code (1:3)-(4:2)-(6:4)}.
   *
   * @param ssn the nine-digit SSN value ({@code CUST-SSN})
   * @return the dash-formatted SSN
   */
  private static String formatSsn(long ssn) {
    String digits = formatId9(ssn);
    return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5, 9);
  }

  /**
   * Renders an account id as eleven zero-padded digits ({@code PIC 9(11)}, e.g. {@code
   * WS-CARD-RID-ACCT-ID-X} / {@code CC-ACCT-ID}).
   *
   * @param value the account id
   * @return the eleven-digit zero-padded string
   */
  private static String formatId11(long value) {
    return String.format("%011d", value);
  }

  /**
   * Renders a customer id as nine zero-padded digits ({@code PIC 9(09)}, e.g. {@code CUST-ID} /
   * {@code WS-CARD-RID-CUST-ID-X}).
   *
   * @param value the customer id (or any nine-digit numeric field)
   * @return the nine-digit zero-padded string
   */
  private static String formatId9(long value) {
    return String.format("%09d", value);
  }

  /**
   * Renders a FICO credit score as three zero-padded digits ({@code CUST-FICO-CREDIT-SCORE}, {@code
   * PIC 9(03)}).
   *
   * @param value the FICO score
   * @return the three-digit zero-padded string
   */
  private static String formatFico(long value) {
    return String.format("%03d", value);
  }

  /**
   * Renders a CICS RESP/REAS code in the COBOL display form used by {@code ERROR-RESP}/{@code
   * ERROR-RESP2}: the nine significant zero-padded digits of the {@code PIC S9(09)} value.
   *
   * @param code the response or reason code
   * @return the nine-digit zero-padded string
   */
  private static String formatRespCode(int code) {
    return String.format("%09d", code);
  }

  /**
   * Tests whether a string is the COBOL blank / low-values state ({@code = SPACES OR =
   * LOW-VALUES}): {@code null}, empty, or all-whitespace.
   *
   * @param value the value to test
   * @return {@code true} when blank or low-values
   */
  private static boolean isBlankOrLowValues(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Tests whether the screen carries no error message yet ({@code WS-RETURN-MSG-OFF}, i.e. the
   * return message equals spaces). Used to honor the COBOL "first message wins" guards.
   *
   * @param screen the screen whose error message is inspected
   * @return {@code true} when no error message is currently set
   */
  private static boolean isReturnMsgOff(AccountViewScreen screen) {
    String msg = screen.getErrMsg();
    return msg == null || msg.trim().isEmpty();
  }

  /**
   * Tests whether every character of a non-empty string is an ASCII digit (the COBOL {@code IS
   * NUMERIC} class test for an unsigned display field).
   *
   * @param value the value to test
   * @return {@code true} when the value is non-empty and entirely digits
   */
  private static boolean isAllDigits(String value) {
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
   * Tests whether a non-empty string is entirely the digit {@code '0'} (the COBOL {@code EQUAL
   * ZEROES} test).
   *
   * @param value the value to test
   * @return {@code true} when the value is non-empty and entirely zeros
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
}

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
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.BillPayScreen;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolStringUtils;
import com.aws.carddemo.util.Messages;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online <strong>bill-payment</strong> business-logic service, migrated from the legacy CICS COBOL
 * program {@code COBIL00C} (CICS transaction {@code CB00}; source {@code
 * legacy/app/cbl/COBIL00C.cbl}).
 *
 * <p>This service reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.7.1) &mdash; the pseudo-conversational bill-payment flow: the operator enters an account
 * id, the current balance is displayed, and on confirmation ({@code Y}) the <em>full</em> balance
 * is paid by posting a new {@code BILL PAYMENT - ONLINE} transaction and zeroing the account
 * balance. The translation is paragraph-faithful: each COBOL {@code PROCEDURE DIVISION} paragraph
 * becomes one private method invoked in the original perform/branch order (AAP &sect;0.6.7).
 *
 * <p>The {@link com.aws.carddemo.web web} controller layer (sibling {@code BillPayController}) owns
 * the HTTP request/response, the {@link CardDemoCommarea} session state, BMS-equivalent screen
 * rendering ({@code EXEC CICS SEND}/{@code RECEIVE}), header population, cursor placement, and the
 * resolution of the raw attention identifier into a {@link CardWorkArea.Aid}. This service is
 * invoked with those already-resolved inputs and returns the next program to route to (the {@code
 * EXEC CICS XCTL} target) or {@code null} to redisplay the bill-payment screen.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7):
 *
 * <ul>
 *   <li>{@code MAIN-PARA} (L99-149) &rarr; {@link #processBillPay(BillPayScreen, CardDemoCommarea,
 *       CardWorkArea.Aid)}
 *   <li>{@code PROCESS-ENTER-KEY} (L154-244) &rarr; {@link #processEnterKey(BillPayScreen,
 *       CardDemoCommarea)}
 *   <li>{@code GET-CURRENT-TIMESTAMP} (L249-267) &rarr; {@link #getCurrentTimestamp()}
 *   <li>{@code RETURN-TO-PREV-SCREEN} (L273-284) &rarr; {@link
 *       #returnToPrevScreen(CardDemoCommarea)}
 *   <li>{@code SEND-BILLPAY-SCREEN} / {@code RECEIVE-BILLPAY-SCREEN} / {@code POPULATE-HEADER-INFO}
 *       (L289-338) &rarr; controller/view-owned (HTTP form binding + BMS render); not modeled here
 *   <li>{@code READ-ACCTDAT-FILE} (L343-372) &rarr; {@link #readAcctdatFile(BillPayScreen, Long)}
 *   <li>{@code UPDATE-ACCTDAT-FILE} (L377-403) &rarr; {@link #updateAcctdatFile(BillPayScreen,
 *       Account)}
 *   <li>{@code READ-CXACAIX-FILE} (L408-436) &rarr; {@link #readCxacaixFile(BillPayScreen, Long)}
 *   <li>{@code STARTBR-/READPREV-/ENDBR-TRANSACT-FILE} (L441-505) &rarr; {@link
 *       #findHighestTranId(BillPayScreen)}
 *   <li>{@code WRITE-TRANSACT-FILE} (L510-547) &rarr; {@link #writeTransactFile(BillPayScreen,
 *       Transaction, String)}
 *   <li>{@code CLEAR-CURRENT-SCREEN} (L552-555) &rarr; {@link #clearCurrentScreen(BillPayScreen)}
 *   <li>{@code INITIALIZE-ALL-FIELDS} (L560-566) &rarr; {@link #initializeAllFields(BillPayScreen)}
 * </ul>
 *
 * <p><strong>Parity notes.</strong>
 *
 * <ul>
 *   <li><b>Decimal fidelity.</b> The current balance and transaction amount are {@link BigDecimal}
 *       at scale 2; the new balance is computed with {@link BigDecimal#subtract(BigDecimal)} (never
 *       {@code float}/{@code double}), reproducing {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL -
 *       TRAN-AMT} so the balance becomes {@code 0.00} (AAP &sect;0.6.1).
 *   <li><b>Error handling = on-screen redisplay.</b> {@code COBIL00C} has <em>no</em> abend path:
 *       every {@code FILE STATUS} / {@code RESP} error moves a message into {@code WS-MESSAGE} and
 *       re-sends the screen. The migrated I/O helpers therefore catch {@link DataAccessException},
 *       set the byte-faithful message on the screen, and signal the caller to return {@code null}
 *       (redisplay) &mdash; they never throw. ({@code IoStatusException} from the exception package
 *       is intentionally not referenced because no unrecoverable branch exists in this program.)
 *   <li><b>Short-circuit on first error.</b> Once a COBOL paragraph sets {@code WS-ERR-FLG} to
 *       {@code 'Y'}, every subsequent {@code IF NOT ERR-FLG-ON} block is skipped and control falls
 *       through to {@code EXEC CICS RETURN}. That is reproduced here by returning {@code null}
 *       immediately after the message is set.
 *   <li><b>Cursor placement.</b> The COBOL {@code MOVE -1 TO ...L} cursor moves are a 3270/BMS
 *       rendering concern. The shared {@link BillPayScreen} DTO exposes no cursor attribute, so
 *       cursor placement is owned by the controller and is intentionally not modeled here.
 *   <li><b>Atomicity.</b> {@link #processBillPay(BillPayScreen, CardDemoCommarea,
 *       CardWorkArea.Aid)} is {@link Transactional}: on the confirmed-payment path the transaction
 *       {@code WRITE} and the account {@code REWRITE} commit atomically, mirroring the COBOL {@code
 *       READ ... UPDATE} lock held through the {@code REWRITE} within a single
 *       pseudo-conversational pass. Because both occur in the same pass, no inter-pass
 *       re-read/compare is required.
 * </ul>
 */
@Service
public class BillPayService {

  /** CICS transaction id of this program ({@code WS-TRANID}, {@code COBIL00C} L38). */
  static final String TRAN_ID = "CB00";

  /** Program name of this program ({@code WS-PGMNAME}, {@code COBIL00C} L37). */
  static final String PGM_NAME = "COBIL00C";

  /**
   * Sign-on program routed to when the COMMAREA is uninitialized (the {@code EIBCALEN = 0} guard,
   * L107-109) and the default {@code CDEMO-TO-PROGRAM} in {@code RETURN-TO-PREV-SCREEN} (L275-277).
   */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  /**
   * Main-menu program routed to on PF3 when there is no {@code CDEMO-FROM-PROGRAM} to return to
   * (L129-130).
   */
  static final String LIT_MENU_PGM = "COMEN01C";

  /**
   * Logical {@code ACCTDAT} file name ({@code WS-ACCTDAT-FILE}, L41); retained for traceability.
   */
  static final String LIT_ACCTDAT_FILE = "ACCTDAT";

  /**
   * Logical {@code CXACAIX} file name ({@code WS-CXACAIX-FILE}, L42); retained for traceability.
   */
  static final String LIT_CXACAIX_FILE = "CXACAIX";

  /**
   * Logical {@code TRANSACT} file name ({@code WS-TRANSACT-FILE}, L40); retained for traceability.
   */
  static final String LIT_TRANSACT_FILE = "TRANSACT";

  /** {@code 'Acct ID can NOT be empty...'} (L161). */
  static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

  /** {@code 'Invalid value. Valid values are (Y/N)...'} (L187). */
  static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

  /** {@code 'You have nothing to pay...'} (L201). */
  static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

  /** {@code 'Confirm to make a bill payment...'} (L237). */
  static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

  /** {@code 'Account ID NOT found...'} (L361, L392, L425). */
  static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

  /** {@code 'Unable to lookup Account...'} (L368). */
  static final String MSG_UNABLE_LOOKUP_ACCT = "Unable to lookup Account...";

  /** {@code 'Unable to Update Account...'} (L399). */
  static final String MSG_UNABLE_UPDATE_ACCT = "Unable to Update Account...";

  /** {@code 'Unable to lookup XREF AIX file...'} (L432). */
  static final String MSG_UNABLE_LOOKUP_XREF = "Unable to lookup XREF AIX file...";

  /** {@code 'Unable to lookup Transaction...'} (L463, L492). */
  static final String MSG_UNABLE_LOOKUP_TRAN = "Unable to lookup Transaction...";

  /** {@code 'Tran ID already exist...'} (L536). */
  static final String MSG_TRAN_ID_EXISTS = "Tran ID already exist...";

  /** {@code 'Unable to Add Bill pay Transaction...'} (L543). */
  static final String MSG_UNABLE_ADD_TRAN = "Unable to Add Bill pay Transaction...";

  /**
   * Prefix of the success message built by the COBOL {@code STRING} statement (L527-530).
   *
   * <p>The COBOL concatenates {@code 'Payment successful. '} (period + one trailing space, {@code
   * DELIMITED BY SIZE}) with {@code ' Your Transaction ID is '} (one leading space, {@code
   * DELIMITED BY SIZE}), so the rendered text has <strong>two</strong> spaces between {@code
   * "successful."} and {@code "Your"}. The transaction id (delimited by space) and a trailing
   * {@code '.'} are then appended. Reproduced byte-for-byte for golden-file parity.
   */
  static final String MSG_PAYMENT_SUCCESS_PREFIX = "Payment successful.  Your Transaction ID is ";

  /** {@code MOVE '02' TO TRAN-TYPE-CD} (L220); {@code TRAN-TYPE-CD PIC X(02)}. */
  static final String TRAN_TYPE_CD_BILL_PAY = "02";

  /**
   * {@code MOVE 2 TO TRAN-CAT-CD} (L221) where {@code TRAN-CAT-CD PIC 9(04)} &rarr; stored as the
   * zero-padded {@code "0002"} (the entity models this enumerated code as fixed-width text).
   */
  static final String TRAN_CAT_CD_BILL_PAY = "0002";

  /** {@code MOVE 'POS TERM' TO TRAN-SOURCE} (L222); {@code TRAN-SOURCE PIC X(10)}. */
  static final String TRAN_SOURCE_POS_TERM = "POS TERM";

  /** {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC} (L223); {@code TRAN-DESC PIC X(100)}. */
  static final String TRAN_DESC_BILL_PAYMENT = "BILL PAYMENT - ONLINE";

  /** {@code MOVE 999999999 TO TRAN-MERCHANT-ID} (L226); {@code TRAN-MERCHANT-ID PIC 9(09)}. */
  static final long TRAN_MERCHANT_ID_BILL_PAY = 999999999L;

  /**
   * {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME} (L227); {@code TRAN-MERCHANT-NAME PIC X(50)}.
   */
  static final String TRAN_MERCHANT_NAME = "BILL PAYMENT";

  /** {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY} (L228); {@code TRAN-MERCHANT-CITY PIC X(50)}. */
  static final String TRAN_MERCHANT_CITY = "N/A";

  /** {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP} (L229); {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
  static final String TRAN_MERCHANT_ZIP = "N/A";

  /** Fixed width of {@code TRAN-ID} / {@code WS-TRAN-ID-NUM PIC 9(16)} (L57); zero-padded to 16. */
  static final int TRAN_ID_WIDTH = 16;

  /**
   * Date/time pattern for the 19-character prefix of {@code WS-TIMESTAMP} ({@code DATESEP('-')},
   * {@code TIMESEP(':')}).
   */
  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /**
   * Fixed microsecond suffix appended to the timestamp. The COBOL moves {@code ZEROS} to {@code
   * WS-TIMESTAMP-TM-MS6} (L266), so the sub-second portion is always {@code .000000}.
   */
  private static final String TIMESTAMP_MICROS_SUFFIX = ".000000";

  /** {@code ACCTDAT} access: read account by id, rewrite the updated account. */
  private final AccountRepository accountRepository;

  /** {@code CXACAIX} access: read the card cross-reference by account id (alternate index). */
  private final CardXrefRepository cardXrefRepository;

  /**
   * {@code TRANSACT} access: browse for the highest transaction id and write the new transaction.
   */
  private final TransactionRepository transactionRepository;

  /**
   * Creates the bill-payment service with its repository collaborators.
   *
   * <p>Constructor injection is used (a single constructor, so {@code @Autowired} is not required);
   * all collaborators are stored in {@code private final} fields and the service holds no other
   * mutable state, so it is safe as a Spring singleton.
   *
   * @param accountRepository repository for the {@code account} table ({@code ACCTDAT}); must not
   *     be {@code null}
   * @param cardXrefRepository repository for the {@code card_xref} table ({@code CXACAIX}); must
   *     not be {@code null}
   * @param transactionRepository repository for the {@code transaction} table ({@code TRANSACT});
   *     must not be {@code null}
   */
  public BillPayService(
      AccountRepository accountRepository,
      CardXrefRepository cardXrefRepository,
      TransactionRepository transactionRepository) {
    this.accountRepository = accountRepository;
    this.cardXrefRepository = cardXrefRepository;
    this.transactionRepository = transactionRepository;
  }

  /**
   * Processes one bill-payment interaction, reproducing {@code COBIL00C MAIN-PARA} (L99-149).
   *
   * <p>The error flag and message are reset first (L101-105). The COBOL {@code EIBCALEN = 0} guard
   * (L107-109) &mdash; a program reached with no COMMAREA &mdash; is modeled by treating a blank
   * {@link CardDemoCommarea#getUserId() user id} as an uninitialized state: the target program is
   * set to {@link #LIT_SIGNON_PGM} and control returns to the previous screen. The controller
   * normally supplies a populated COMMAREA, but the guard is preserved for parity.
   *
   * <p>On the first entry (the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch, L112-122) the
   * re-enter flag is set and the screen is blanked for (re)display. The COBOL then checks the
   * CB00-specific {@code CDEMO-CB00-TRN-SELECTED} COMMAREA extension (L116-121): when an account
   * was selected on an upstream screen it is moved into {@code ACTIDINI} and {@code
   * PROCESS-ENTER-KEY} runs immediately. The shared {@link CardDemoCommarea} has no such field; per
   * the established cross-screen handoff pattern the controller pre-populates {@link
   * BillPayScreen#getActIdIn()}, so a non-blank account id on first entry triggers {@link
   * #processEnterKey(BillPayScreen, CardDemoCommarea)}. Either way the first entry redisplays
   * (returns {@code null}).
   *
   * <p>On a re-entry the method branches on the attention identifier exactly as the COBOL {@code
   * EVALUATE EIBAID} (L125-142): {@code ENTER} delegates to {@link #processEnterKey(BillPayScreen,
   * CardDemoCommarea)}; {@code PF3} returns to the caller (or the main menu when there is no
   * from-program); {@code PF4} clears the screen; any other key sets the shared invalid-key message
   * and redisplays.
   *
   * <p>This method is {@link Transactional} so that, on the confirmed-payment path, the transaction
   * write and the account update commit atomically (see the class documentation).
   *
   * @param screen the bill-payment screen contract carrying the operator's input ({@code actIdIn},
   *     {@code confirm}) and receiving the current balance, cleared fields, and message; must not
   *     be {@code null}
   * @param commarea the pseudo-conversational session/navigation state; must not be {@code null}
   * @param aid the resolved attention identifier (PF/ENTER key), or {@code null} if the raw key did
   *     not map to a known {@link CardWorkArea.Aid}
   * @return the program name to dispatch to (the {@code EXEC CICS XCTL} target, e.g. {@code
   *     "COMEN01C"} or {@code "COSGN00C"}), or {@code null} to redisplay the bill-payment screen
   * @throws NullPointerException if {@code screen} or {@code commarea} is {@code null}
   */
  @Transactional
  public String processBillPay(
      BillPayScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // MAIN-PARA L101-105: SET ERR-FLG-OFF; MOVE SPACES TO WS-MESSAGE, ERRMSGO.
    screen.setErrMsg("");

    // L107-109: EIBCALEN = 0 — no COMMAREA — return to sign-on.
    if (isBlankOrLowValues(commarea.getUserId())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
      return returnToPrevScreen(commarea);
    }

    // L112-122: first entry — paint the screen (and process a pre-selected account id).
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      // MOVE LOW-VALUES TO COBIL0AO; MOVE -1 TO ACTIDINL — blanking + cursor are controller
      // concerns.
      // CDEMO-CB00-TRN-SELECTED handoff (L116-121): the controller pre-populates actIdIn.
      if (!isBlankOrLowValues(screen.getActIdIn())) {
        return processEnterKey(screen, commarea);
      }
      return null;
    }

    // L125-142: re-entry — EVALUATE EIBAID.
    if (aid == CardWorkArea.Aid.ENTER) {
      return processEnterKey(screen, commarea);
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // L128-135: PF3 — return to the from-program, or the main menu when none is set.
      if (isBlankOrLowValues(commarea.getFromProgram())) {
        commarea.setToProgram(LIT_MENU_PGM);
      } else {
        commarea.setToProgram(commarea.getFromProgram());
      }
      return returnToPrevScreen(commarea);
    }
    if (aid == CardWorkArea.Aid.PFK04) {
      // L136-137: PF4 — clear the current screen and redisplay.
      clearCurrentScreen(screen);
      return null;
    }
    // L138-141: WHEN OTHER — invalid key.
    screen.setErrMsg(Messages.MSG_INVALID_KEY);
    return null;
  }

  /**
   * Validates the input and performs the payment, reproducing {@code PROCESS-ENTER-KEY} (L154-244).
   *
   * <p>The control flow and perform order are preserved exactly. The COBOL uses a shared {@code
   * WS-ERR-FLG}: once set, every subsequent {@code IF NOT ERR-FLG-ON} block is skipped and control
   * falls through to {@code EXEC CICS RETURN}. That is reproduced by returning {@code null}
   * immediately after the first error message is set (an observably equivalent short-circuit).
   *
   * <ol>
   *   <li>{@code SET CONF-PAY-NO} (L156) &rarr; the local {@code confirmPay} flag starts {@code
   *       false}.
   *   <li><b>Empty account id</b> (L158-164): a blank/low-values {@code ACTIDINI} yields {@link
   *       #MSG_ACCT_ID_EMPTY} and a redisplay.
   *   <li><b>Confirm evaluation</b> (L169-191): {@code MOVE ACTIDINI TO ACCT-ID XREF-ACCT-ID}
   *       (L170-171) is a lenient numeric parse &mdash; COBOL performs no numeric class test here,
   *       so a non-numeric id is not rejected; it simply fails the later account lookup. {@code
   *       EVALUATE CONFIRMI}: {@code 'Y'}/{@code 'y'} sets the confirm flag and reads the account;
   *       {@code 'N'}/{@code 'n'} clears the screen and stops; blank/low-values reads the account;
   *       any other value yields {@link #MSG_INVALID_CONFIRM}. After a successful read the current
   *       balance is shown (L193-194).
   *   <li><b>Nothing to pay</b> (L197-206): a non-positive balance with a non-blank account id
   *       yields {@link #MSG_NOTHING_TO_PAY}.
   *   <li><b>Pay vs prompt</b> (L208-244): when confirmed, the payment is posted (see {@link
   *       #postPayment(BillPayScreen, Long, Account)}); otherwise {@link #MSG_CONFIRM_PAYMENT}
   *       prompts for confirmation.
   * </ol>
   *
   * @param screen the bill-payment screen contract (read for input, mutated with balance/message)
   * @param commarea the session state (unused for routing here; the payment path never transfers
   *     control &mdash; it always redisplays)
   * @return always {@code null} (redisplay): {@code PROCESS-ENTER-KEY} performs no {@code XCTL}
   */
  private String processEnterKey(BillPayScreen screen, CardDemoCommarea commarea) {
    // L156: SET CONF-PAY-NO TO TRUE.
    boolean confirmPay = false;

    // L158-164: empty account id check.
    if (isBlankOrLowValues(screen.getActIdIn())) {
      screen.setErrMsg(MSG_ACCT_ID_EMPTY);
      return null;
    }

    // L170-171: MOVE ACTIDINI TO ACCT-ID / XREF-ACCT-ID (lenient — no COBOL numeric edit here).
    Long acctId = extractDigitsToLong(screen.getActIdIn());

    // L173-191: EVALUATE CONFIRMI.
    String confirm = screen.getConfirm();
    Optional<Account> readResult;
    if ("Y".equals(confirm) || "y".equals(confirm)) {
      // L174-177: confirmed — flag YES then read the account.
      confirmPay = true;
      readResult = readAcctdatFile(screen, acctId);
    } else if ("N".equals(confirm) || "n".equals(confirm)) {
      // L178-181: declined — clear the screen and stop (error flag on -> skip the rest).
      clearCurrentScreen(screen);
      return null;
    } else if (isBlankOrLowValues(confirm)) {
      // L182-184: no confirmation yet — read the account to display the balance.
      readResult = readAcctdatFile(screen, acctId);
    } else {
      // L185-190: invalid confirmation value.
      screen.setErrMsg(MSG_INVALID_CONFIRM);
      return null;
    }

    // The read failed (NOTFND / lookup error); the message is already set -> redisplay.
    if (readResult.isEmpty()) {
      return null;
    }
    Account account = readResult.get();

    // L193-194: MOVE ACCT-CURR-BAL TO WS-CURR-BAL TO CURBALI.
    screen.setCurBal(account.getAcctCurrBal());

    // L197-206: nothing-to-pay check (balance <= 0 with a non-blank account id).
    if (account.getAcctCurrBal() != null
        && account.getAcctCurrBal().compareTo(BigDecimal.ZERO) <= 0
        && !isBlankOrLowValues(screen.getActIdIn())) {
      screen.setErrMsg(MSG_NOTHING_TO_PAY);
      return null;
    }

    // L208-244: pay (when confirmed) or prompt for confirmation.
    if (confirmPay) {
      return postPayment(screen, acctId, account);
    }
    // L236-239: ELSE — ask the operator to confirm.
    screen.setErrMsg(MSG_CONFIRM_PAYMENT);
    return null;
  }

  /**
   * Posts the full-balance payment, reproducing the confirmed ({@code CONF-PAY-YES}) block inlined
   * in {@code PROCESS-ENTER-KEY} (L210-235).
   *
   * <p>The steps run in the COBOL order: read the card cross-reference for the card number (L211);
   * browse the transaction file for the highest id and add one (L212-217); build the new {@code
   * TRAN-RECORD} (L218-232); write it (L233); reduce the balance by the amount and update the
   * account (L234-235). The transaction amount is captured <em>before</em> the balance is reduced,
   * matching the COBOL move of {@code ACCT-CURR-BAL} into {@code TRAN-AMT} (L224) ahead of the
   * {@code COMPUTE} (L234).
   *
   * <p><b>Write-failure short-circuit.</b> When {@link #writeTransactFile(BillPayScreen,
   * Transaction, String)} reports a failure (duplicate key or other error) its message is already
   * on the screen, so this method returns immediately and does <em>not</em> reduce the balance or
   * update the account. This both honors the program-wide short-circuit-on-error contract and keeps
   * the balance and the (absent) transaction consistent; it also avoids leaving a JPA transaction
   * in a rollback-only state after a caught persistence exception.
   *
   * @param screen the screen contract (mutated with the balance, cleared fields, and message)
   * @param acctId the parsed account id used for the cross-reference lookup
   * @param account the account read for update; its balance is reduced on a successful write
   * @return always {@code null} (redisplay)
   */
  private String postPayment(BillPayScreen screen, Long acctId, Account account) {
    // L211: PERFORM READ-CXACAIX-FILE — obtain the card number for the transaction.
    Optional<CardXref> xref = readCxacaixFile(screen, acctId);
    if (xref.isEmpty()) {
      return null;
    }
    String xrefCardNum = xref.get().getXrefCardNum();

    // L212-217: STARTBR/READPREV/ENDBR from HIGH-VALUES — highest existing id, then add 1.
    OptionalLong highest = findHighestTranId(screen);
    if (highest.isEmpty()) {
      return null;
    }
    String tranId = CobolStringUtils.padLeftZeros(highest.getAsLong() + 1L, TRAN_ID_WIDTH);

    // L224: TRAN-AMT = ACCT-CURR-BAL captured BEFORE the balance is reduced.
    BigDecimal tranAmt = account.getAcctCurrBal();

    // L218-232: INITIALIZE TRAN-RECORD + field moves.
    Transaction transaction = buildPaymentTransaction(tranId, tranAmt, xrefCardNum);

    // L233: PERFORM WRITE-TRANSACT-FILE.
    if (!writeTransactFile(screen, transaction, tranId)) {
      return null;
    }

    // L234: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (-> 0.00).
    account.setAcctCurrBal(account.getAcctCurrBal().subtract(tranAmt));
    // L235: PERFORM UPDATE-ACCTDAT-FILE (overrides the success message only on failure).
    updateAcctdatFile(screen, account);
    return null;
  }

  /**
   * Builds the 26-character processing timestamp, reproducing {@code GET-CURRENT-TIMESTAMP}
   * (L249-267).
   *
   * <p>The COBOL formats the current date with {@code DATESEP('-')} and the current time with
   * {@code TIMESEP(':')} into {@code WS-TIMESTAMP}, then moves {@code ZEROS} into the six-digit
   * microseconds field {@code WS-TIMESTAMP-TM-MS6} (L266). The result is therefore {@code
   * yyyy-MM-dd HH:mm:ss.000000} (a space between the date and time, and an always-zero microsecond
   * portion), exactly 26 characters wide to fit the {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS PIC
   * X(26)} fields.
   *
   * @return the formatted 26-character timestamp
   */
  String getCurrentTimestamp() {
    return LocalDateTime.now().format(TIMESTAMP_FORMATTER) + TIMESTAMP_MICROS_SUFFIX;
  }

  /**
   * Resolves the program to transfer control to, reproducing {@code RETURN-TO-PREV-SCREEN}
   * (L273-284).
   *
   * <p>When {@code CDEMO-TO-PROGRAM} is blank/low-values it defaults to {@link #LIT_SIGNON_PGM}
   * (L275-277). The from-transaction and from-program are stamped with this program's identity
   * (L278-279) and the program context is reset to the "enter" state ({@code MOVE ZEROS TO
   * CDEMO-PGM-CONTEXT}, L280). The actual {@code EXEC CICS XCTL} (L281-284) is performed by the
   * controller using the returned program name.
   *
   * @param commarea the session/navigation state, mutated with the routing fields
   * @return the target program name (the {@code XCTL} target)
   */
  private String returnToPrevScreen(CardDemoCommarea commarea) {
    // L275-277: default the target to sign-on when unset.
    if (isBlankOrLowValues(commarea.getToProgram())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
    }
    // L278-280: stamp the from-routing and reset the program context.
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmContext(CardDemoCommarea.PGM_CONTEXT_ENTER);
    return commarea.getToProgram();
  }

  /**
   * Reads the account for update, reproducing {@code READ-ACCTDAT-FILE} (L343-372).
   *
   * <p>The COBOL {@code EXEC CICS READ ... UPDATE} maps to {@code accountRepository.findById}. The
   * {@code RESP} evaluation (L356-372) maps as follows: {@code NORMAL} returns the account; {@code
   * NOTFND} (FILE STATUS {@code '23'}) sets {@link #MSG_ACCT_NOT_FOUND}; any other I/O error sets
   * {@link #MSG_UNABLE_LOOKUP_ACCT}. Per the established service rule both error branches redisplay
   * the message (the program has no abend path), so this method returns {@link Optional#empty()} on
   * either failure and the caller returns {@code null}.
   *
   * @param screen the screen contract, mutated with the error message on failure
   * @param acctId the account id to read; a {@code null} id (non-numeric input) is treated as
   *     not-found, matching the COBOL behavior of reading with an unconvertible key
   * @return the account when found, otherwise {@link Optional#empty()} (message already set)
   */
  private Optional<Account> readAcctdatFile(BillPayScreen screen, Long acctId) {
    if (acctId == null) {
      // A non-numeric / unconvertible account id can never match a key -> NOTFND.
      screen.setErrMsg(MSG_ACCT_NOT_FOUND);
      return Optional.empty();
    }
    try {
      Optional<Account> account = accountRepository.findById(acctId);
      if (account.isEmpty()) {
        // L359-364: DFHRESP(NOTFND).
        screen.setErrMsg(MSG_ACCT_NOT_FOUND);
      }
      return account;
    } catch (DataAccessException e) {
      // L365-371: WHEN OTHER — unexpected I/O error.
      screen.setErrMsg(MSG_UNABLE_LOOKUP_ACCT);
      return Optional.empty();
    }
  }

  /**
   * Rewrites the updated account, reproducing {@code UPDATE-ACCTDAT-FILE} (L377-403).
   *
   * <p>The COBOL {@code EXEC CICS REWRITE} maps to {@code accountRepository.save}. On {@code
   * NORMAL} the method returns silently (the success message set by {@link
   * #writeTransactFile(BillPayScreen, Transaction, String)} is preserved). On a {@code NOTFND} the
   * COBOL sets {@link #MSG_ACCT_NOT_FOUND} and on any other error {@link #MSG_UNABLE_UPDATE_ACCT};
   * with JPA both surface as a {@link DataAccessException}, so the more specific update message is
   * used (and overrides the success message, exactly as the COBOL re-send would). The program has
   * no abend path, so the error is shown on screen rather than thrown.
   *
   * @param screen the screen contract, mutated with the error message only on failure
   * @param account the account to persist
   */
  private void updateAcctdatFile(BillPayScreen screen, Account account) {
    try {
      accountRepository.save(account);
    } catch (DataAccessException e) {
      // L396-402: WHEN OTHER — unable to update.
      screen.setErrMsg(MSG_UNABLE_UPDATE_ACCT);
    }
  }

  /**
   * Reads the card cross-reference by account id, reproducing {@code READ-CXACAIX-FILE} (L408-436).
   *
   * <p>The COBOL {@code EXEC CICS READ DATASET('CXACAIX') RIDFLD(XREF-ACCT-ID)} reads a single
   * record on the alternate index; the migrated path uses {@code
   * cardXrefRepository.findByXrefAcctId} and takes the first row. An empty result maps to {@code
   * NOTFND} (L423-428) with {@link #MSG_ACCT_NOT_FOUND}; an unexpected I/O error (L429-435) sets
   * {@link #MSG_UNABLE_LOOKUP_XREF}. Both redisplay (return {@link Optional#empty()}).
   *
   * @param screen the screen contract, mutated with the error message on failure
   * @param acctId the account id whose card cross-reference is read
   * @return the cross-reference row when found, otherwise {@link Optional#empty()}
   */
  private Optional<CardXref> readCxacaixFile(BillPayScreen screen, Long acctId) {
    if (acctId == null) {
      screen.setErrMsg(MSG_ACCT_NOT_FOUND);
      return Optional.empty();
    }
    try {
      List<CardXref> matches = cardXrefRepository.findByXrefAcctId(acctId);
      if (matches.isEmpty()) {
        // L423-428: DFHRESP(NOTFND).
        screen.setErrMsg(MSG_ACCT_NOT_FOUND);
        return Optional.empty();
      }
      // COBOL reads a single record on the AIX -> take the first row.
      return Optional.of(matches.get(0));
    } catch (DataAccessException e) {
      // L429-435: WHEN OTHER — unable to look up the XREF AIX file.
      screen.setErrMsg(MSG_UNABLE_LOOKUP_XREF);
      return Optional.empty();
    }
  }

  /**
   * Finds the highest existing transaction id, reproducing the browse {@code
   * STARTBR-/READPREV-/ENDBR-TRANSACT-FILE} (L441-505).
   *
   * <p>The COBOL positions the browse at {@code HIGH-VALUES} and issues a single {@code READPREV}
   * to read the last (highest-keyed) record, then ends the browse. When the file is empty the
   * {@code READPREV} returns {@code ENDFILE} and the COBOL moves {@code ZEROS} to {@code TRAN-ID}
   * (L487-488) &mdash; so the highest id is {@code 0} and the first generated id becomes {@code 1}.
   * This is implemented by reading all transactions ordered ascending by id and taking the last; an
   * empty list yields {@code 0}.
   *
   * <p>The {@code READPREV} / {@code STARTBR} unexpected-error branches (L460-466, L489-495) map to
   * a {@link DataAccessException}: {@link #MSG_UNABLE_LOOKUP_TRAN} is shown and {@link
   * OptionalLong#empty()} is returned so the caller redisplays.
   *
   * @param screen the screen contract, mutated with the error message on an I/O error
   * @return the highest existing transaction id (or {@code 0} when none exist) wrapped in an {@link
   *     OptionalLong}, or {@link OptionalLong#empty()} when a lookup error occurred
   */
  private OptionalLong findHighestTranId(BillPayScreen screen) {
    try {
      List<Transaction> all = transactionRepository.findAllByOrderByTranIdAsc();
      if (all.isEmpty()) {
        // L487-488: ENDFILE -> MOVE ZEROS TO TRAN-ID (no transactions yet).
        return OptionalLong.of(0L);
      }
      Transaction highest = all.get(all.size() - 1);
      Long parsed = extractDigitsToLong(highest.getTranId());
      // Stored ids are 16-digit zero-padded numerics; a null parse would be a corrupt key -> 0.
      return OptionalLong.of(parsed == null ? 0L : parsed);
    } catch (DataAccessException e) {
      // L460-466 / L489-495: unexpected I/O error browsing the transaction file.
      screen.setErrMsg(MSG_UNABLE_LOOKUP_TRAN);
      return OptionalLong.empty();
    }
  }

  /**
   * Writes the new transaction, reproducing {@code WRITE-TRANSACT-FILE} (L510-547).
   *
   * <p>The COBOL {@code EXEC CICS WRITE} maps to {@code transactionRepository.save}. On {@code
   * NORMAL} (L523-532) the program performs {@code INITIALIZE-ALL-FIELDS} (clearing the inputs and
   * positioning the cursor) <em>before</em> building the green success message, so this method
   * clears the fields first via {@link #initializeAllFields(BillPayScreen)} and then sets the
   * success text. The success message is composed exactly as the COBOL {@code STRING} (L527-530):
   * {@link #MSG_PAYMENT_SUCCESS_PREFIX} (with its two interior spaces) + the transaction id
   * (delimited by space, which keeps the full 16-character zero-padded id since it contains no
   * spaces) + {@code "."}.
   *
   * <p>A duplicate key/record ({@code DUPKEY}/{@code DUPREC}, L533-539) maps to a {@link
   * DataIntegrityViolationException} and yields {@link #MSG_TRAN_ID_EXISTS}; any other error
   * (L540-546) yields {@link #MSG_UNABLE_ADD_TRAN}. Both are shown on screen (no abend path) and
   * reported as a failure.
   *
   * <p>(The COBOL physically {@code SEND}s twice on success &mdash; once here and once at the end
   * of {@code PROCESS-ENTER-KEY} &mdash; but the net observable screen is this success state, so
   * the double send is intentionally not modeled.)
   *
   * @param screen the screen contract, mutated with cleared fields + the success/error message
   * @param transaction the transaction to persist
   * @param tranId the 16-character zero-padded transaction id used to build the success message
   * @return {@code true} when the write succeeded, {@code false} on a duplicate or other error
   */
  private boolean writeTransactFile(BillPayScreen screen, Transaction transaction, String tranId) {
    try {
      transactionRepository.save(transaction);
      // L524-531: INITIALIZE-ALL-FIELDS then build the green success message.
      initializeAllFields(screen);
      screen.setErrMsg(MSG_PAYMENT_SUCCESS_PREFIX + delimitedBySpace(tranId) + ".");
      return true;
    } catch (DataIntegrityViolationException e) {
      // L533-539: DUPKEY / DUPREC — transaction id already exists.
      screen.setErrMsg(MSG_TRAN_ID_EXISTS);
      return false;
    } catch (DataAccessException e) {
      // L540-546: WHEN OTHER — unable to add the bill-pay transaction.
      screen.setErrMsg(MSG_UNABLE_ADD_TRAN);
      return false;
    }
  }

  /**
   * Clears the screen for redisplay, reproducing {@code CLEAR-CURRENT-SCREEN} (L552-555).
   *
   * <p>The COBOL performs {@code INITIALIZE-ALL-FIELDS} and then {@code SEND-BILLPAY-SCREEN}; the
   * send (redisplay) is handled by the caller returning {@code null}, so this method only clears
   * the fields.
   *
   * @param screen the screen contract to clear
   */
  private void clearCurrentScreen(BillPayScreen screen) {
    initializeAllFields(screen);
  }

  /**
   * Clears the editable screen fields, reproducing {@code INITIALIZE-ALL-FIELDS} (L560-566).
   *
   * <p>The COBOL moves {@code -1} to {@code ACTIDINL} (cursor — a controller concern, not modeled)
   * and {@code SPACES} to {@code ACTIDINI}, {@code CURBALI}, {@code CONFIRMI}, and {@code
   * WS-MESSAGE}. The text fields are cleared to the empty string and the monetary {@code curBal} (a
   * {@link BigDecimal}) is cleared to {@code null}.
   *
   * @param screen the screen contract to clear
   */
  private void initializeAllFields(BillPayScreen screen) {
    // MOVE -1 TO ACTIDINL (cursor) is a controller/rendering concern and is not modeled here.
    screen.setActIdIn("");
    screen.setCurBal(null);
    screen.setConfirm("");
    screen.setErrMsg("");
  }

  /**
   * Builds the new bill-payment {@link Transaction}, reproducing {@code INITIALIZE TRAN-RECORD}
   * plus the field moves inlined in {@code PROCESS-ENTER-KEY} (L218-232).
   *
   * <p>The literal field values mirror the COBOL exactly: type code {@code "02"} (L220), category
   * code {@code "0002"} (L221, {@code MOVE 2 TO TRAN-CAT-CD PIC 9(04)}), source {@code "POS TERM"}
   * (L222), description {@code "BILL PAYMENT - ONLINE"} (L223), merchant id {@code 999999999}
   * (L226), merchant name {@code "BILL PAYMENT"} (L227), and merchant city/zip {@code "N/A"}
   * (L228-229). The origination and processing timestamps are set to the same value (L231-232) from
   * {@link #getCurrentTimestamp()}.
   *
   * @param tranId the 16-character zero-padded transaction id ({@code TRAN-ID})
   * @param tranAmt the transaction amount ({@code TRAN-AMT}) — the full current balance, scale 2
   * @param cardNum the card number from the cross-reference ({@code TRAN-CARD-NUM})
   * @return the populated transaction ready to persist
   */
  private Transaction buildPaymentTransaction(String tranId, BigDecimal tranAmt, String cardNum) {
    Transaction transaction = new Transaction();
    transaction.setTranId(tranId);
    transaction.setTranTypeCd(TRAN_TYPE_CD_BILL_PAY);
    transaction.setTranCatCd(TRAN_CAT_CD_BILL_PAY);
    transaction.setTranSource(TRAN_SOURCE_POS_TERM);
    transaction.setTranDesc(TRAN_DESC_BILL_PAYMENT);
    transaction.setTranAmt(tranAmt);
    transaction.setTranCardNum(cardNum);
    transaction.setTranMerchantId(TRAN_MERCHANT_ID_BILL_PAY);
    transaction.setTranMerchantName(TRAN_MERCHANT_NAME);
    transaction.setTranMerchantCity(TRAN_MERCHANT_CITY);
    transaction.setTranMerchantZip(TRAN_MERCHANT_ZIP);
    String timestamp = getCurrentTimestamp();
    transaction.setTranOrigTs(timestamp);
    transaction.setTranProcTs(timestamp);
    return transaction;
  }

  /**
   * Returns whether the value is COBOL {@code SPACES} or {@code LOW-VALUES} (the {@code = SPACES OR
   * LOW-VALUES} test used throughout {@code COBIL00C}).
   *
   * <p>{@code null}, the empty string, an all-spaces string, and an all-{@code NUL} ({@code
   * LOW-VALUES}) string are all treated as blank; any other content is not.
   *
   * @param value the value to test, possibly {@code null}
   * @return {@code true} when the value is {@code null}, empty, all spaces, or all low-values
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
   * Extracts the digit characters of {@code value} and parses them as a {@code long}, mirroring the
   * COBOL {@code MOVE} of an alphanumeric {@code ACTIDINI PIC X(11)} into a numeric {@code ACCT-ID
   * PIC 9(11)} (which copies only the numeric content).
   *
   * <p>Non-digit characters (including spaces and the {@code LOW-VALUES} pad) are dropped. A value
   * with no digits, or one whose digit run overflows a {@code long}, yields {@code null} so the
   * caller treats it as a key that cannot match any record (a not-found lookup).
   *
   * @param value the source text, possibly {@code null}
   * @return the parsed value, or {@code null} when there are no digits or the value overflows
   */
  private static Long extractDigitsToLong(String value) {
    if (value == null) {
      return null;
    }
    StringBuilder digits = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= '0' && c <= '9') {
        digits.append(c);
      }
    }
    if (digits.length() == 0) {
      return null;
    }
    try {
      return Long.parseLong(digits.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Returns the portion of {@code value} preceding its first space, reproducing the COBOL {@code
   * STRING ... DELIMITED BY SPACE} used when composing the success message (L529).
   *
   * @param value the source value, possibly {@code null}
   * @return the characters up to (but not including) the first space, the whole value when it has
   *     no space, or an empty string when {@code value} is {@code null}
   */
  private static String delimitedBySpace(String value) {
    if (value == null) {
      return "";
    }
    int spaceAt = value.indexOf(' ');
    return (spaceAt >= 0) ? value.substring(0, spaceAt) : value;
  }
}

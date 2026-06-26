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

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.TranAddScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolStringUtils;
import com.aws.carddemo.util.DateValidationService;
import com.aws.carddemo.util.Messages;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online business-logic service for the <strong>Add Transaction</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COTRN02C} (CICS transaction {@code CT02}; behavioral spec {@code
 * legacy/app/cbl/COTRN02C.cbl}, with record layouts {@code legacy/app/cpy/CVTRA05Y.cpy} and {@code
 * legacy/app/cpy/CVACT03Y.cpy}, the navigation COMMAREA {@code legacy/app/cpy/COCOM01Y.cpy}, and
 * the screen mapset {@code legacy/app/bms/COTRN02.bms}).
 *
 * <p><strong>Behavioral parity (Agent Action Plan &sect;0.1.1, &sect;0.6, &sect;0.7.1).</strong>
 * The program is a {@code STYLE B} named-paragraph pseudo-conversational transaction: it resolves
 * the account/card via the card cross-reference, validates every input field in the <em>exact</em>
 * COBOL order, and then writes a new transaction record with a generated identifier. Each numbered
 * COBOL paragraph is reproduced as a dedicated private method (1:1 traceability) and the
 * field-validation order is preserved byte-for-byte, because both the order and the literal
 * messages are externally observable and therefore load-bearing for parity.
 *
 * <p><strong>Short-circuit semantics.</strong> In the COBOL program every error path performs
 * {@code SEND-TRNADD-SCREEN}, which issues {@code EXEC CICS SEND} followed by {@code EXEC CICS
 * RETURN} &mdash; terminating the program immediately so that no subsequent validation runs. This
 * service mirrors that exactly: each validation step returns a {@code boolean} indicating whether
 * it raised an error, and {@link #processEnterKey} returns {@code null} (redisplay) the moment a
 * step reports an error, so a later step can never overwrite an earlier error message.
 *
 * <p><strong>Return contract.</strong> {@link #processTranAdd(TranAddScreen, CardDemoCommarea,
 * CardWorkArea.Aid)} returns the name of the next program to transfer to (the {@code EXEC CICS
 * XCTL} target on PF3 / the caller or main menu), or {@code null} to redisplay the current screen
 * with the {@link TranAddScreen#getErrMsg() message field} populated (validation failures,
 * not-found, duplicate-key, write failures, and the green success confirmation).
 *
 * <p><strong>State.</strong> The service is a stateless singleton: it holds <em>no</em> mutable
 * instance state. All pseudo-conversational state is carried by the supplied {@link
 * CardDemoCommarea} (user, role, navigation context) and {@link TranAddScreen} (operator input and
 * the message line), exactly as the COBOL COMMAREA and BMS symbolic map do.
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> The transaction amount arrives as the raw
 * screen string and is validated character-by-character ({@code NUMVAL-C} edit rules) before being
 * converted to a {@link BigDecimal} at scale&nbsp;2 with {@link RoundingMode#DOWN} (truncation),
 * never {@code float}/{@code double}, matching the COBOL {@code PIC S9(09)V99} {@code NUMVAL-C}
 * conversion. Malformed input is reported as the field message rather than failing data binding.
 *
 * <p><strong>Persistence and exception mapping (AAP &sect;0.6.4).</strong> Cross-reference lookups
 * map to {@link CardXrefRepository}; the transaction write maps to {@link TransactionRepository}. A
 * record-not-found becomes an empty {@link Optional}/{@link List} (the COBOL {@code NOTFND} branch)
 * and is shown on-screen. A duplicate key on write maps to {@link DataIntegrityViolationException}
 * and is shown on-screen, mirroring the COBOL {@code DUPKEY}/{@code DUPREC} message. Other
 * data-access failures on the cross-reference and write paths are <em>also</em> shown on-screen,
 * reproducing the legacy program's observable {@code DISPLAY}-then-{@code SEND} behavior. Only a
 * genuinely unexpected failure while browsing the transaction file for the next key (the {@code
 * STARTBR}/{@code READPREV} positioning that should always succeed against a healthy store) is
 * translated to {@link IoStatusException}, consistent with the project-wide exception-translation
 * strategy.
 *
 * <p><strong>Header rendering.</strong> The COBOL {@code POPULATE-HEADER-INFO} (title lines,
 * program name, transaction id, current date/time) is a presentation concern owned by the
 * web/controller layer; this service deliberately confines itself to the business logic enumerated
 * in the required method list and does not populate header fields. Likewise the {@code EIBCALEN =
 * 0} (no-COMMAREA) bounce to sign-on is a controller/session concern: the web layer always supplies
 * a valid navigation COMMAREA, so this service requires a non-{@code null} {@link
 * CardDemoCommarea}.
 */
@Service
public class TranAddService {

  // ===== Program identity (COTRN02C WORKING-STORAGE literals) ===================================

  /** {@code WS-TRANID PIC X(04) VALUE 'CT02'} — this screen's CICS transaction id. */
  static final String TRAN_ID_NAME = "CT02";

  /** {@code WS-PGMNAME PIC X(08) VALUE 'COTRN02C'} — this program's name. */
  static final String PGM_NAME = "COTRN02C";

  /** Sign-on program {@code 'COSGN00C'} — the {@code RETURN-TO-PREV-SCREEN} default target. */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  /** Main menu program {@code 'COMEN01C'} — the PF3 fallback when no caller is recorded. */
  static final String LIT_MENU_PGM = "COMEN01C";

  /** {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} — transaction store logical name. */
  static final String LIT_TRANSACT_FILE = "TRANSACT";

  /** {@code WS-CXACAIX-FILE PIC X(08) VALUE 'CXACAIX'} — card-xref alternate index (by account). */
  static final String LIT_CXACAIX_FILE = "CXACAIX";

  /** {@code WS-CCXREF-FILE PIC X(08) VALUE 'CCXREF'} — card-xref primary file (by card number). */
  static final String LIT_CCXREF_FILE = "CCXREF";

  // ===== Byte-exact operator messages (reproduced verbatim from COTRN02C) ========================

  /** {@code 'Account or Card Number must be entered...'} (VALIDATE-INPUT-KEY-FIELDS). */
  static final String MSG_ACCT_OR_CARD_REQUIRED = "Account or Card Number must be entered...";

  /** {@code 'Account ID must be Numeric...'}. */
  static final String MSG_ACCT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

  /** {@code 'Card Number must be Numeric...'}. */
  static final String MSG_CARD_NOT_NUMERIC = "Card Number must be Numeric...";

  /** {@code 'Account ID NOT found...'} (READ-CXACAIX-FILE, no matching alternate-index row). */
  static final String MSG_ACCT_ID_NOT_FOUND = "Account ID NOT found...";

  /** {@code 'Card Number NOT found...'} (READ-CCXREF-FILE, no matching primary row). */
  static final String MSG_CARD_NOT_FOUND = "Card Number NOT found...";

  /** {@code 'Unable to lookup Acct in XREF AIX file...'} (READ-CXACAIX-FILE I/O error). */
  static final String MSG_XREF_AIX_LOOKUP_FAIL = "Unable to lookup Acct in XREF AIX file...";

  /** {@code 'Unable to lookup Card # in XREF file...'} (READ-CCXREF-FILE I/O error). */
  static final String MSG_XREF_LOOKUP_FAIL = "Unable to lookup Card # in XREF file...";

  /** {@code 'Type CD can NOT be empty...'}. */
  static final String MSG_TYPE_CD_EMPTY = "Type CD can NOT be empty...";

  /** {@code 'Category CD can NOT be empty...'}. */
  static final String MSG_CATEGORY_CD_EMPTY = "Category CD can NOT be empty...";

  /** {@code 'Source can NOT be empty...'}. */
  static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";

  /** {@code 'Description can NOT be empty...'}. */
  static final String MSG_DESCRIPTION_EMPTY = "Description can NOT be empty...";

  /** {@code 'Amount can NOT be empty...'}. */
  static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";

  /** {@code 'Orig Date can NOT be empty...'}. */
  static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";

  /** {@code 'Proc Date can NOT be empty...'}. */
  static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";

  /** {@code 'Merchant ID can NOT be empty...'}. */
  static final String MSG_MERCHANT_ID_EMPTY = "Merchant ID can NOT be empty...";

  /** {@code 'Merchant Name can NOT be empty...'}. */
  static final String MSG_MERCHANT_NAME_EMPTY = "Merchant Name can NOT be empty...";

  /** {@code 'Merchant City can NOT be empty...'}. */
  static final String MSG_MERCHANT_CITY_EMPTY = "Merchant City can NOT be empty...";

  /** {@code 'Merchant Zip can NOT be empty...'}. */
  static final String MSG_MERCHANT_ZIP_EMPTY = "Merchant Zip can NOT be empty...";

  /** {@code 'Type CD must be Numeric...'}. */
  static final String MSG_TYPE_CD_NOT_NUMERIC = "Type CD must be Numeric...";

  /** {@code 'Category CD must be Numeric...'}. */
  static final String MSG_CATEGORY_CD_NOT_NUMERIC = "Category CD must be Numeric...";

  /** {@code 'Amount should be in format -99999999.99'} (no trailing ellipsis in the COBOL). */
  static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";

  /** {@code 'Orig Date should be in format YYYY-MM-DD'} (no trailing ellipsis in the COBOL). */
  static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";

  /** {@code 'Proc Date should be in format YYYY-MM-DD'} (no trailing ellipsis in the COBOL). */
  static final String MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";

  /** {@code 'Orig Date - Not a valid date...'} (CSUTLDTC semantic validation). */
  static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";

  /** {@code 'Proc Date - Not a valid date...'} (CSUTLDTC semantic validation). */
  static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";

  /** {@code 'Merchant ID must be Numeric...'}. */
  static final String MSG_MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

  /** {@code 'Confirm to add this transaction...'} (confirm field blank / 'N'). */
  static final String MSG_CONFIRM_ADD = "Confirm to add this transaction...";

  /** {@code 'Invalid value. Valid values are (Y/N)...'} (confirm field not Y/N/blank). */
  static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

  /** {@code 'Tran ID already exist...'} (WRITE-TRANSACT-FILE {@code DUPKEY}/{@code DUPREC}). */
  static final String MSG_TRAN_ID_EXISTS = "Tran ID already exist...";

  /** {@code 'Unable to Add Transaction...'} (WRITE-TRANSACT-FILE non-duplicate I/O error). */
  static final String MSG_ADD_FAILED = "Unable to Add Transaction...";

  // ===== Validation constants ====================================================================

  /**
   * Date edit/validation mask passed to {@link DateValidationService}, matching the COBOL {@code
   * WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} supplied to {@code CSUTLDTC}.
   */
  static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

  /**
   * Largest magnitude representable by the transaction-amount edit mask {@code PIC -99999999.99}
   * (eight integer digits, two fraction digits). The COBOL validates the raw {@code TRNAMTI} text
   * against this shape character-by-character; here the raw string is first validated by {@link
   * #isValidNumValC(String)}, then parsed by {@link #numValC(String)}, and finally its (scale-2,
   * truncated) magnitude is required to fit this mask.
   */
  private static final BigDecimal AMOUNT_MAX = new BigDecimal("99999999.99");

  /** COBOL monetary scale ({@code V99}) — two fraction digits. */
  private static final int AMOUNT_SCALE = 2;

  // ===== Collaborators (constructor-injected; never mutated) =====================================

  /** Transaction store ({@code TRANSACT} VSAM KSDS) — reads for the next key and the insert. */
  private final TransactionRepository transactionRepository;

  /** Card cross-reference store ({@code CCXREF} primary / {@code CXACAIX} alternate index). */
  private final CardXrefRepository cardXrefRepository;

  /**
   * Creates the service with its required repositories.
   *
   * <p>{@link DateValidationService} is intentionally <em>not</em> injected: it is a stateless
   * static utility (the {@code CSUTLDTC} date-validation routine) and is invoked through its static
   * methods, so no bean wiring is required for it.
   *
   * @param transactionRepository repository for the {@code TRANSACT} transaction store; must not be
   *     {@code null}
   * @param cardXrefRepository repository for the card cross-reference store; must not be {@code
   *     null}
   */
  public TranAddService(
      TransactionRepository transactionRepository, CardXrefRepository cardXrefRepository) {
    this.transactionRepository =
        Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
    this.cardXrefRepository =
        Objects.requireNonNull(cardXrefRepository, "cardXrefRepository must not be null");
  }

  // ===== Public entry point (COTRN02C MAIN-PARA) ================================================

  /**
   * Processes one pseudo-conversational turn of the Add Transaction screen ({@code COTRN02C} {@code
   * MAIN-PARA}, L107-159).
   *
   * <p>The first invocation paints a blank screen (and, when an upstream card selection was handed
   * off through {@link TranAddScreen#getCardNin()}, immediately drives the ENTER path so the
   * resolved account/card and any validation message appear on the first paint). Subsequent
   * invocations dispatch on the operator's attention identifier: ENTER validates and (on
   * confirmation) adds the transaction; PF3 returns to the caller or the main menu; PF4 clears the
   * screen; PF5 copies the most recent transaction's data into the form; any other key reports an
   * invalid-key message.
   *
   * <p>The method is transactional because the generated-id browse and the subsequent insert form a
   * single unit of work whose rollback semantics must match the COBOL program (AAP &sect;0.6.4).
   *
   * @param screen the Add Transaction screen DTO carrying operator input and the message line; must
   *     not be {@code null}
   * @param commarea the navigation COMMAREA carrying user, role, and from/to program context; must
   *     not be {@code null}
   * @param aid the resolved attention identifier (ENTER, PF3, PF4, PF5, …); may be {@code null},
   *     which is treated as an unsupported key
   * @return the next program to transfer to (PF3 → caller or main menu), or {@code null} to
   *     redisplay this screen with {@link TranAddScreen#getErrMsg()} populated
   */
  @Transactional
  public String processTranAdd(
      TranAddScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");
    // MAIN-PARA L109-113: SET ERR-FLG-OFF, USR-MODIFIED-NO; MOVE SPACES TO WS-MESSAGE / ERRMSGO.
    screen.setErrMsg("");
    return dispatchMainPara(screen, commarea, aid);
  }

  /**
   * Implements the {@code MAIN-PARA} dispatch: first-entry paint vs. the re-entry {@code EVALUATE
   * EIBAID} (L120-157).
   */
  private String dispatchMainPara(
      TranAddScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    // First entry — NOT CDEMO-PGM-REENTER (L120-133).
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      // CDEMO-CT02-TRN-SELECTED handoff: the controller pre-loads TranAddScreen.cardNin from the
      // upstream card selection. When present, drive the ENTER path immediately (L124-129).
      if (!isBlank(screen.getCardNin())) {
        return processEnterKey(screen, commarea);
      }
      // SEND-TRNADD-SCREEN: clean first display.
      return null;
    }
    // Re-entry — EVALUATE EIBAID (L135-157).
    if (aid == CardWorkArea.Aid.ENTER) {
      return processEnterKey(screen, commarea);
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // PF3 (L139-145): return to the caller, or the main menu when no caller was recorded.
      if (isBlank(commarea.getFromProgram())) {
        commarea.setToProgram(LIT_MENU_PGM);
      } else {
        commarea.setToProgram(commarea.getFromProgram());
      }
      return returnToPrevScreen(commarea);
    }
    if (aid == CardWorkArea.Aid.PFK04) {
      // PF4 (L147): clear the current screen.
      return clearCurrentScreen(screen);
    }
    if (aid == CardWorkArea.Aid.PFK05) {
      // PF5 (L149): copy the most recent transaction's data into the form.
      return copyLastTranData(screen, commarea);
    }
    // WHEN OTHER (L153-156): unsupported attention identifier.
    screen.setErrMsg(Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  // ===== PROCESS-ENTER-KEY (L164-188) ===========================================================

  /**
   * Validates the key fields, then the data fields, then acts on the confirmation flag ({@code
   * PROCESS-ENTER-KEY}). Mirrors the COBOL short-circuit: the first failing step issues {@code
   * SEND-TRNADD-SCREEN} + {@code RETURN}, so this method returns {@code null} immediately when a
   * step reports an error and never lets a later step overwrite the message.
   */
  private String processEnterKey(TranAddScreen screen, CardDemoCommarea commarea) {
    if (validateInputKeyFields(screen)) {
      return null;
    }
    if (validateInputDataFields(screen)) {
      return null;
    }
    // EVALUATE CONFIRMI (L171-186).
    String confirmValue = CobolStringUtils.trim(screen.getConfirm());
    if ("Y".equals(confirmValue) || "y".equals(confirmValue)) {
      addTransaction(screen);
      return null;
    }
    if ("N".equals(confirmValue) || "n".equals(confirmValue) || confirmValue.isEmpty()) {
      screen.setErrMsg(MSG_CONFIRM_ADD);
      return null;
    }
    screen.setErrMsg(MSG_INVALID_CONFIRM);
    return null;
  }

  // ===== VALIDATE-INPUT-KEY-FIELDS (L193-230) ===================================================

  /**
   * Resolves the transaction's account/card from whichever key field the operator supplied,
   * cross-referencing the other through the card-xref store.
   *
   * @return {@code true} when an error was reported (caller must redisplay); {@code false} on
   *     success
   */
  private boolean validateInputKeyFields(TranAddScreen screen) {
    String accountInput = screen.getActIdIn();
    String cardInput = screen.getCardNin();
    if (!isBlank(accountInput)) {
      // Account id supplied (L197-208).
      if (!isNumeric(accountInput)) {
        screen.setErrMsg(MSG_ACCT_ID_NOT_NUMERIC);
        return true;
      }
      long accountId = Long.parseLong(CobolStringUtils.trim(accountInput));
      // MOVE WS-ACCT-ID-N TO ACTIDINI (normalize to the 11-digit zoned key) then READ-CXACAIX-FILE.
      screen.setActIdIn(CobolStringUtils.padLeftZeros(accountId, 11));
      return readCxacaixFile(screen, accountId);
    }
    if (!isBlank(cardInput)) {
      // Card number supplied (L210-221).
      if (!isNumeric(cardInput)) {
        screen.setErrMsg(MSG_CARD_NOT_NUMERIC);
        return true;
      }
      long cardNumber = Long.parseLong(CobolStringUtils.trim(cardInput));
      // MOVE WS-CARD-NUM-N TO CARDNINI (normalize to the 16-digit key) then READ-CCXREF-FILE.
      String cardKey = CobolStringUtils.padLeftZeros(cardNumber, 16);
      screen.setCardNin(cardKey);
      return readCcxrefFile(screen, cardKey);
    }
    // WHEN OTHER (L223-227): neither key supplied.
    screen.setErrMsg(MSG_ACCT_OR_CARD_REQUIRED);
    return true;
  }

  // ===== READ-CXACAIX-FILE (L576-604) / READ-CCXREF-FILE (L609-637) =============================

  /**
   * Reads the card-xref alternate index by account id and adopts the resolved card number ({@code
   * READ-CXACAIX-FILE}).
   *
   * @return {@code true} when an error was reported; {@code false} on success
   */
  private boolean readCxacaixFile(TranAddScreen screen, long accountId) {
    List<CardXref> matches;
    try {
      matches = cardXrefRepository.findByXrefAcctId(accountId);
    } catch (DataAccessException ex) {
      // RESP other than NORMAL/NOTFND → on-screen message (the legacy program's observable
      // behavior); see the file-status mapping in the class documentation.
      screen.setErrMsg(MSG_XREF_AIX_LOOKUP_FAIL);
      return true;
    }
    if (matches.isEmpty()) {
      // NOTFND (L592-595).
      screen.setErrMsg(MSG_ACCT_ID_NOT_FOUND);
      return true;
    }
    // MOVE XREF-CARD-NUM TO CARDNINI: adopt the resolved card number.
    screen.setCardNin(matches.get(0).getXrefCardNum());
    return false;
  }

  /**
   * Reads the card-xref primary file by card number and adopts the resolved account id ({@code
   * READ-CCXREF-FILE}).
   *
   * @return {@code true} when an error was reported; {@code false} on success
   */
  private boolean readCcxrefFile(TranAddScreen screen, String cardKey) {
    Optional<CardXref> match;
    try {
      match = cardXrefRepository.findById(cardKey);
    } catch (DataAccessException ex) {
      screen.setErrMsg(MSG_XREF_LOOKUP_FAIL);
      return true;
    }
    if (match.isEmpty()) {
      // NOTFND (L625-628).
      screen.setErrMsg(MSG_CARD_NOT_FOUND);
      return true;
    }
    // MOVE XREF-ACCT-ID TO ACTIDINI: adopt the resolved 11-digit account id.
    screen.setActIdIn(CobolStringUtils.padLeftZeros(match.get().getXrefAcctId(), 11));
    return false;
  }

  // ===== VALIDATE-INPUT-DATA-FIELDS (L235-437) ==================================================

  /**
   * Validates every transaction data field in the <em>exact</em> COBOL order (AAP &sect;0.7.1):
   * eleven non-empty checks, then numeric checks for type and category codes, then the amount edit
   * mask, then the originating and processing date masks, then (after re-scaling the amount) the
   * {@code CSUTLDTC} semantic date validation, and finally the merchant-id numeric check. The first
   * failing check populates the message line and returns {@code true} so the caller redisplays
   * immediately.
   *
   * @return {@code true} when a validation error was reported; {@code false} when every field is
   *     valid
   */
  private boolean validateInputDataFields(TranAddScreen screen) {
    // --- Non-empty checks, exact order (L251-320) ---
    if (isBlank(screen.getTtypCd())) {
      screen.setErrMsg(MSG_TYPE_CD_EMPTY);
      return true;
    }
    if (isBlank(screen.getTcatCd())) {
      screen.setErrMsg(MSG_CATEGORY_CD_EMPTY);
      return true;
    }
    if (isBlank(screen.getTrnSrc())) {
      screen.setErrMsg(MSG_SOURCE_EMPTY);
      return true;
    }
    if (isBlank(screen.getTDesc())) {
      screen.setErrMsg(MSG_DESCRIPTION_EMPTY);
      return true;
    }
    if (isBlank(screen.getTrnAmt())) {
      // TRNAMTI = SPACES → a blank raw amount string models the empty amount field.
      screen.setErrMsg(MSG_AMOUNT_EMPTY);
      return true;
    }
    if (isBlank(screen.getTOrigDt())) {
      screen.setErrMsg(MSG_ORIG_DATE_EMPTY);
      return true;
    }
    if (isBlank(screen.getTProcDt())) {
      screen.setErrMsg(MSG_PROC_DATE_EMPTY);
      return true;
    }
    if (isBlank(screen.getMid())) {
      screen.setErrMsg(MSG_MERCHANT_ID_EMPTY);
      return true;
    }
    if (isBlank(screen.getMName())) {
      screen.setErrMsg(MSG_MERCHANT_NAME_EMPTY);
      return true;
    }
    if (isBlank(screen.getMCity())) {
      screen.setErrMsg(MSG_MERCHANT_CITY_EMPTY);
      return true;
    }
    if (isBlank(screen.getMZip())) {
      screen.setErrMsg(MSG_MERCHANT_ZIP_EMPTY);
      return true;
    }

    // --- Type / category numeric checks (L322-337) ---
    if (!isNumeric(screen.getTtypCd())) {
      screen.setErrMsg(MSG_TYPE_CD_NOT_NUMERIC);
      return true;
    }
    if (!isNumeric(screen.getTcatCd())) {
      screen.setErrMsg(MSG_CATEGORY_CD_NOT_NUMERIC);
      return true;
    }

    // --- Amount edit-mask check (L339-351) ---
    // The COBOL inspects the raw TRNAMTI text against PIC -99999999.99 character-by-character and
    // only parses it via NUMVAL-C once the shape is valid; it never abends on malformed input. We
    // reproduce that exactly: reject any non-NUMVAL-C string (e.g. "ABCDEFGH") with the format
    // message, then parse, then enforce the mask's eight integer + two fraction digits.
    if (!isValidNumValC(screen.getTrnAmt())) {
      screen.setErrMsg(MSG_AMOUNT_FORMAT);
      return true;
    }
    BigDecimal scaledAmount = numValC(screen.getTrnAmt()).setScale(AMOUNT_SCALE, RoundingMode.DOWN);
    if (scaledAmount.abs().compareTo(AMOUNT_MAX) > 0) {
      screen.setErrMsg(MSG_AMOUNT_FORMAT);
      return true;
    }

    // --- Date edit-mask checks (L353-381) ---
    if (!matchesDateMask(screen.getTOrigDt())) {
      screen.setErrMsg(MSG_ORIG_DATE_FORMAT);
      return true;
    }
    if (!matchesDateMask(screen.getTProcDt())) {
      screen.setErrMsg(MSG_PROC_DATE_FORMAT);
      return true;
    }

    // --- Amount NUMVAL-C conversion + reformat back into the field (L383-386) ---
    // COBOL reformats the validated amount back into TRNAMTI; we paint the canonical scale-2 plain
    // string (e.g. "100.00") so the redisplayed field shows the normalised value.
    screen.setTrnAmt(scaledAmount.toPlainString());

    // --- CSUTLDTC semantic date validation (L389-427) ---
    // COBOL tolerates message 2513 (unsupported range) exactly; reproduce that tolerance here.
    DateValidationService.DateValidationResult origResult =
        DateValidationService.validateDate(screen.getTOrigDt(), DATE_FORMAT_MASK);
    if (!origResult.isValid() && !origResult.isToleratedUnsupportedRange()) {
      screen.setErrMsg(MSG_ORIG_DATE_INVALID);
      return true;
    }
    DateValidationService.DateValidationResult procResult =
        DateValidationService.validateDate(screen.getTProcDt(), DATE_FORMAT_MASK);
    if (!procResult.isValid() && !procResult.isToleratedUnsupportedRange()) {
      screen.setErrMsg(MSG_PROC_DATE_INVALID);
      return true;
    }

    // --- Merchant id numeric check (L430-436) ---
    if (!isNumeric(screen.getMid())) {
      screen.setErrMsg(MSG_MERCHANT_ID_NOT_NUMERIC);
      return true;
    }

    return false;
  }

  // ===== ADD-TRANSACTION (L442-466) / WRITE-TRANSACT-FILE (L711-749) =============================

  /**
   * Builds the new {@link Transaction} from the validated screen fields and writes it ({@code
   * ADD-TRANSACTION}). The transaction id is the highest existing key plus one, zero-padded to the
   * 16-character key width, reproducing the COBOL {@code STARTBR}/{@code READPREV}/{@code +1}
   * sequence.
   */
  private void addTransaction(TranAddScreen screen) {
    String newTranId = computeNextTranId();

    // INITIALIZE TRAN-RECORD + the field MOVEs (L449-463), each padded to its copybook width.
    Transaction transaction = new Transaction();
    transaction.setTranId(newTranId);
    transaction.setTranTypeCd(CobolStringUtils.padRight(screen.getTtypCd(), 2));
    transaction.setTranCatCd(
        CobolStringUtils.padLeftZeros(CobolStringUtils.trim(screen.getTcatCd()), 4));
    transaction.setTranSource(CobolStringUtils.padRight(screen.getTrnSrc(), 10));
    transaction.setTranDesc(CobolStringUtils.padRight(screen.getTDesc(), 100));
    // The amount has already passed isValidNumValC in VALIDATE-INPUT-DATA-FIELDS and was
    // reformatted
    // to its canonical scale-2 string, so numValC always yields a non-null value here.
    transaction.setTranAmt(numValC(screen.getTrnAmt()).setScale(AMOUNT_SCALE, RoundingMode.DOWN));
    transaction.setTranMerchantId(Long.parseLong(CobolStringUtils.trim(screen.getMid())));
    transaction.setTranMerchantName(CobolStringUtils.padRight(screen.getMName(), 50));
    transaction.setTranMerchantCity(CobolStringUtils.padRight(screen.getMCity(), 50));
    transaction.setTranMerchantZip(CobolStringUtils.padRight(screen.getMZip(), 10));
    transaction.setTranCardNum(CobolStringUtils.padRight(screen.getCardNin(), 16));
    transaction.setTranOrigTs(CobolStringUtils.padRight(screen.getTOrigDt(), 26));
    transaction.setTranProcTs(CobolStringUtils.padRight(screen.getTProcDt(), 26));

    writeTransactFile(transaction, screen);
  }

  /**
   * Computes the next transaction id as {@code max(existing) + 1}, zero-padded to 16 characters.
   *
   * <p>The legacy program positions at end-of-file with {@code STARTBR}/{@code READPREV} to read
   * the highest key. Because the transaction id is a 16-character zero-padded key, ascending key
   * order is also ascending numeric order, so the last element returned by {@link
   * TransactionRepository#findAllByOrderByTranIdAsc()} is the current maximum. An empty store
   * yields a maximum of zero (first id {@code 0000000000000001}). A failure to read the store while
   * positioning for the next key is genuinely unexpected and is translated to {@link
   * IoStatusException}.
   */
  private String computeNextTranId() {
    List<Transaction> ordered;
    try {
      ordered = transactionRepository.findAllByOrderByTranIdAsc();
    } catch (DataAccessException ex) {
      throw new IoStatusException(LIT_TRANSACT_FILE, "READ", null, ex);
    }
    long maxId = 0L;
    if (!ordered.isEmpty()) {
      String highestKey = CobolStringUtils.trim(ordered.get(ordered.size() - 1).getTranId());
      if (isAllDigits(highestKey)) {
        maxId = Long.parseLong(highestKey);
      }
    }
    return CobolStringUtils.padLeftZeros(maxId + 1L, 16);
  }

  /**
   * Persists the new transaction ({@code WRITE-TRANSACT-FILE}). On success the screen is blanked
   * and the green confirmation message is shown; a duplicate key reports {@link
   * #MSG_TRAN_ID_EXISTS} and any other data-access failure reports {@link #MSG_ADD_FAILED} — both
   * on-screen, reproducing the legacy program's observable behavior.
   */
  private void writeTransactFile(Transaction transaction, TranAddScreen screen) {
    try {
      transactionRepository.save(transaction);
    } catch (DataIntegrityViolationException duplicateKey) {
      // DUPKEY / DUPREC (L739-742).
      screen.setErrMsg(MSG_TRAN_ID_EXISTS);
      return;
    } catch (DataAccessException ex) {
      // Any other non-normal response (L744-747).
      screen.setErrMsg(MSG_ADD_FAILED);
      return;
    }
    // NORMAL (L723-737): blank the form, then build the green success line. The COBOL STRING yields
    // two spaces between "successfully." and "Your" and appends the (space-delimited) tran id +
    // '.'.
    initializeAllFields(screen);
    screen.setErrMsg(
        "Transaction added successfully. "
            + " Your Tran ID is "
            + transaction.getTranId().trim()
            + ".");
  }

  // ===== COPY-LAST-TRAN-DATA (L471-495) =========================================================

  /**
   * PF5 handler: validates the key fields, copies the most recent transaction's data fields into
   * the form, and then runs the ENTER path so the operator can confirm adding a similar
   * transaction.
   */
  private String copyLastTranData(TranAddScreen screen, CardDemoCommarea commarea) {
    if (validateInputKeyFields(screen)) {
      return null;
    }
    Transaction last = readLastTransaction();
    if (last != null) {
      // Copy each field into its screen counterpart, truncated to the screen field width then
      // right-trimmed for display (the COBOL MOVEs into the smaller BMS fields).
      screen.setTtypCd(CobolStringUtils.rtrim(CobolStringUtils.truncate(last.getTranTypeCd(), 2)));
      screen.setTcatCd(CobolStringUtils.rtrim(CobolStringUtils.truncate(last.getTranCatCd(), 4)));
      screen.setTrnSrc(CobolStringUtils.rtrim(CobolStringUtils.truncate(last.getTranSource(), 10)));
      screen.setTrnAmt(moneyToString(last.getTranAmt()));
      screen.setTDesc(CobolStringUtils.rtrim(CobolStringUtils.truncate(last.getTranDesc(), 60)));
      screen.setTOrigDt(CobolStringUtils.truncate(last.getTranOrigTs(), 10));
      screen.setTProcDt(CobolStringUtils.truncate(last.getTranProcTs(), 10));
      if (last.getTranMerchantId() != null) {
        screen.setMid(CobolStringUtils.padLeftZeros(last.getTranMerchantId(), 9));
      }
      screen.setMName(
          CobolStringUtils.rtrim(CobolStringUtils.truncate(last.getTranMerchantName(), 30)));
      screen.setMCity(
          CobolStringUtils.rtrim(CobolStringUtils.truncate(last.getTranMerchantCity(), 25)));
      screen.setMZip(
          CobolStringUtils.rtrim(CobolStringUtils.truncate(last.getTranMerchantZip(), 10)));
    }
    return processEnterKey(screen, commarea);
  }

  /**
   * Reads the most recent (highest-id) transaction, or {@code null} when the store is empty. A
   * failure to read the store is genuinely unexpected and is translated to {@link
   * IoStatusException}.
   */
  private Transaction readLastTransaction() {
    List<Transaction> ordered;
    try {
      ordered = transactionRepository.findAllByOrderByTranIdAsc();
    } catch (DataAccessException ex) {
      throw new IoStatusException(LIT_TRANSACT_FILE, "READ", null, ex);
    }
    return ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);
  }

  // ===== Screen / navigation helpers (CLEAR / INITIALIZE / RETURN-TO-PREV) =======================

  /** PF4 handler ({@code CLEAR-CURRENT-SCREEN}): blank every field and redisplay. */
  private String clearCurrentScreen(TranAddScreen screen) {
    initializeAllFields(screen);
    return null;
  }

  /**
   * Blanks every operator input field and the message line ({@code INITIALIZE-ALL-FIELDS},
   * L762-779). Cleared fields are represented as {@code null} (unset) in the DTO; the message line
   * is reset to an empty string.
   */
  private void initializeAllFields(TranAddScreen screen) {
    screen.setActIdIn(null);
    screen.setCardNin(null);
    screen.setTtypCd(null);
    screen.setTcatCd(null);
    screen.setTrnSrc(null);
    screen.setTrnAmt(null);
    screen.setTDesc(null);
    screen.setTOrigDt(null);
    screen.setTProcDt(null);
    screen.setMid(null);
    screen.setMName(null);
    screen.setMCity(null);
    screen.setMZip(null);
    screen.setConfirm(null);
    screen.setErrMsg("");
  }

  /**
   * Prepares the navigation COMMAREA for an {@code EXEC CICS XCTL} back to the previous screen
   * ({@code RETURN-TO-PREV-SCREEN}, L500-511) and returns the target program name. Defaults the
   * target to the sign-on program when none was set, records this program/transaction as the
   * caller, and resets the program context to first-entry.
   */
  private String returnToPrevScreen(CardDemoCommarea commarea) {
    if (isBlank(commarea.getToProgram())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
    }
    commarea.setFromTranId(TRAN_ID_NAME);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmEnter();
    return commarea.getToProgram();
  }

  // ===== Field-shape helpers =====================================================================

  /**
   * Returns {@code true} when {@code value} is {@code null} or contains only spaces, modeling the
   * COBOL {@code = SPACES OR LOW-VALUES} test against a fixed-width field.
   */
  private static boolean isBlank(String value) {
    return value == null || CobolStringUtils.trim(value).isEmpty();
  }

  /**
   * Returns {@code true} when the trimmed {@code value} is a non-empty run of decimal digits,
   * modeling the COBOL {@code IS NUMERIC} class test for a zoned-decimal field. The value is
   * trimmed first so operator-entered fields that arrive space-padded are accepted, consistent with
   * the online-service convention established by the sibling menu services.
   */
  private static boolean isNumeric(String value) {
    return isAllDigits(CobolStringUtils.trim(value));
  }

  /**
   * Returns {@code true} when {@code value} is non-empty and every character is {@code '0'}–{@code
   * '9'}.
   */
  private static boolean isAllDigits(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch < '0' || ch > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code FUNCTION TEST-NUMVAL-C} parity: {@code true} when {@code raw} is a valid
   * numeric-with-currency string — the form {@code NUMVAL-C} accepts. Permits optional surrounding
   * whitespace, an optional single leading <em>or</em> trailing sign ({@code +}/{@code -}), an
   * optional leading currency symbol ({@code $}), grouping commas, and at most one decimal point
   * with a fractional part. At least one digit must be present; any other character makes the value
   * invalid (the legacy {@code "Amount should be in format -99999999.99"} condition).
   *
   * @param raw the raw screen characters
   * @return {@code true} when the value is a syntactically valid signed decimal
   */
  private static boolean isValidNumValC(String raw) {
    if (raw == null) {
      return false;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return false;
    }
    // Optional single trailing sign (NUMVAL-C accepts a trailing + or -).
    char last = s.charAt(s.length() - 1);
    boolean trailingSign = (last == '+' || last == '-');
    if (trailingSign) {
      s = s.substring(0, s.length() - 1).trim();
    }
    // Optional single leading sign — only when there was no trailing sign.
    if (!trailingSign && !s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
      s = s.substring(1).trim();
    }
    // Optional leading currency symbol.
    if (!s.isEmpty() && s.charAt(0) == '$') {
      s = s.substring(1).trim();
    }
    if (s.isEmpty()) {
      return false;
    }
    boolean sawDigit = false;
    boolean sawDot = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') {
        sawDigit = true;
      } else if (c == ',') {
        // Grouping comma — accepted (NUMVAL-C ignores grouping; position not strictly enforced).
        continue;
      } else if (c == '.') {
        if (sawDot) {
          return false; // at most one decimal point
        }
        sawDot = true;
      } else {
        return false; // any other character makes the amount invalid
      }
    }
    return sawDigit;
  }

  /**
   * {@code FUNCTION NUMVAL-C} parity: parses a numeric-with-currency string (already validated by
   * {@link #isValidNumValC(String)}) into a {@link BigDecimal}. Strips the optional currency
   * symbol, grouping commas and embedded spaces, and normalises a leading or trailing sign.
   *
   * @param raw the raw screen characters
   * @return the parsed value, or {@code null} when the string cannot be parsed
   */
  private static BigDecimal numValC(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return null;
    }
    boolean negative = false;
    char last = s.charAt(s.length() - 1);
    if (last == '+' || last == '-') {
      negative = (last == '-');
      s = s.substring(0, s.length() - 1).trim();
    } else if (s.charAt(0) == '+' || s.charAt(0) == '-') {
      negative = (s.charAt(0) == '-');
      s = s.substring(1).trim();
    }
    s = s.replace("$", "").replace(",", "").replace(" ", "");
    if (s.isEmpty() || ".".equals(s)) {
      return null;
    }
    try {
      BigDecimal value = new BigDecimal(s);
      return negative ? value.negate() : value;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Renders a monetary entity value as a plain scale-2 decimal string for the {@code PIC X(12)}
   * amount field ({@code ""} for {@code null}). Truncates to scale 2 ({@link RoundingMode#DOWN}) so
   * the painted value matches the stored amount to the cent.
   *
   * @param value the entity amount
   * @return the plain decimal string (e.g. {@code "100.00"}), or {@code ""} when {@code null}
   */
  private static String moneyToString(BigDecimal value) {
    return value == null ? "" : value.setScale(AMOUNT_SCALE, RoundingMode.DOWN).toPlainString();
  }

  /**
   * Returns {@code true} when {@code value} matches the {@code YYYY-MM-DD} edit mask exactly: a
   * 10-character string of four digits, {@code '-'}, two digits, {@code '-'}, two digits. This
   * reproduces the COBOL positional check (L356-365) and is independent of whether the date is
   * semantically valid (that is the subsequent {@code CSUTLDTC} concern).
   */
  private static boolean matchesDateMask(String value) {
    if (value == null || value.length() != 10) {
      return false;
    }
    return isAllDigits(value.substring(0, 4))
        && value.charAt(4) == '-'
        && isAllDigits(value.substring(5, 7))
        && value.charAt(7) == '-'
        && isAllDigits(value.substring(8, 10));
  }
}

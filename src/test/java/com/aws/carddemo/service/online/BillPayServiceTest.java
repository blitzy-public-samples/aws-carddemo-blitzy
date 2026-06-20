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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.BillPayScreen;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.Messages;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link BillPayService}, the online
 * bill-payment business-logic service migrated from the legacy CICS COBOL program {@code COBIL00C}
 * (CICS transaction {@code CB00}; behavioral spec {@code legacy/app/cbl/COBIL00C.cbl} with
 * copybooks {@code CVACT01Y} (account), {@code CVACT03Y} (card cross-reference), and {@code
 * CVTRA05Y} (transaction)).
 *
 * <p>The service implements the pseudo-conversational pay-in-full flow: read the account &rarr;
 * display the balance and confirm (Y/N) &rarr; resolve the card through the cross-reference &rarr;
 * generate the next transaction id &rarr; write a {@code BILL PAYMENT - ONLINE} transaction &rarr;
 * settle the balance to {@code 0.00} and rewrite the account. The three repositories ({@link
 * AccountRepository}, {@link CardXrefRepository}, {@link TransactionRepository}) are mocked &mdash;
 * no Spring context, no Testcontainers, no database &mdash; so each control-flow branch can be
 * exercised in isolation and the exact collaboration order asserted. The {@code @Transactional}
 * atomicity of the confirmed-payment path is integration-tested elsewhere.
 *
 * <p>Parity points pinned by these tests (Agent Action Plan):
 *
 * <ul>
 *   <li><strong>Control flow</strong> (&sect;0.7.1): the {@link InOrder} chain account &rarr; xref
 *       &rarr; transaction-browse &rarr; transaction-write &rarr; account-update, and the confirm
 *       gate / short-circuit-on-first-error that precedes every write.
 *   <li><strong>Decimal fidelity</strong> (&sect;0.6.1): the displayed balance and the written
 *       transaction amount are {@link BigDecimal} at scale&nbsp;2 (asserted by comparison
 *       <em>and</em> scale); the pay-in-full amount equals the displayed balance and the settled
 *       balance is exactly {@code 0.00}; no {@code float}/{@code double}.
 *   <li><strong>Navigation / state</strong> (&sect;0.6.5): the confirm (Y/N) state machine, PF3
 *       routing to the caller (or the main-menu default), PF4 clear, and the first-entry / re-entry
 *       / uninitialized-COMMAREA transitions.
 *   <li><strong>Message-literal parity</strong> (&sect;0.6.4): empty-id, invalid-confirm,
 *       nothing-to-pay, confirm-prompt, not-found, and duplicate messages are byte-exact; the
 *       success message is built from {@code 'Payment successful. '} + {@code ' Your Transaction ID
 *       is '}, so it carries <strong>two</strong> spaces between {@code "successful."} and {@code
 *       "Your"}.
 *   <li><strong>FILE STATUS mapping</strong> (&sect;0.6.4): a not-found record is a redisplay; a
 *       duplicate key is a redisplay; and &mdash; because {@code COBIL00C} has <em>no</em> abend
 *       path &mdash; an unexpected I/O error is <em>also</em> caught and redisplayed (the service
 *       never throws {@code IoStatusException}).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BillPayServiceTest {

  // ----- Shared test data -----------------------------------------------------------------------

  /** A valid 11-digit, zero-padded account id as entered on the screen ({@code ACTIDINI}). */
  private static final String ACCT_SID = "00000000123";

  /** The numeric account id parsed from {@link #ACCT_SID}. */
  private static final Long ACCT_ID = 123L;

  /** The customer id linked to the account by the card cross-reference. */
  private static final Long CUST_ID = 456L;

  /** A numeric 16-character card number (the cross-reference {@code XREF-CARD-NUM}). */
  private static final String CARD_NUM = "1234567890123456";

  /** A positive current balance (scale 2) used for the pay-in-full happy paths. */
  private static final String POSITIVE_BAL = "100.00";

  /**
   * The first generated transaction id: empty file &rarr; highest 0 &rarr; 1, zero-padded to 16.
   */
  private static final String FIRST_TRAN_ID = "0000000000000001";

  // ----- Mocked collaborators (the migrated VSAM datasets) + service under test -----------------

  @Mock private AccountRepository accountRepository;
  @Mock private CardXrefRepository cardXrefRepository;
  @Mock private TransactionRepository transactionRepository;

  @InjectMocks private BillPayService service;

  // ----- Fixture builders -----------------------------------------------------------------------

  /**
   * Builds an account master record with its primary key and current balance. The balance is a
   * scale-2 {@link BigDecimal} (decimal-fidelity parity, AAP &sect;0.6.1); the service reads and
   * rewrites only {@code acctCurrBal}, so the remaining fields are intentionally left unset.
   */
  private static Account account(long id, String bal) {
    Account account = new Account();
    account.setAcctId(id);
    account.setAcctCurrBal(new BigDecimal(bal));
    return account;
  }

  /**
   * Builds a card cross-reference row linking the account to its card number and owning customer.
   */
  private static CardXref xref(long acctId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(CARD_NUM);
    xref.setXrefAcctId(acctId);
    xref.setXrefCustId(CUST_ID);
    return xref;
  }

  /** Builds a bill-pay screen DTO carrying the entered account id and confirmation flag. */
  private static BillPayScreen screen(String actIdIn, String confirm) {
    BillPayScreen screen = new BillPayScreen();
    screen.setActIdIn(actIdIn);
    screen.setConfirm(confirm);
    return screen;
  }

  /**
   * A signed-on COMMAREA already in the re-enter (post-paint) state, so a submission is processed
   * as input. The service guards on a non-blank {@code userId} (the {@code EIBCALEN = 0} parity),
   * and a fresh {@link CardDemoCommarea} defaults to the {@code PGM-ENTER} context, so the re-entry
   * processing path is only reached after {@code setUserId(...)} and {@code setPgmReenter()}.
   */
  private static CardDemoCommarea reenteredCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUserId("USER0001");
    commarea.setUsrTypUser();
    commarea.setPgmReenter();
    return commarea;
  }

  /** A signed-on COMMAREA on first entry (the {@code PGM-ENTER} context, before any paint). */
  private static CardDemoCommarea firstEntryCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUserId("USER0001");
    commarea.setUsrTypUser();
    return commarea;
  }

  // ===== 1. Confirmed pay-in-full: writes the transaction, settles the balance, success message ==

  @ParameterizedTest
  @ValueSource(strings = {"Y", "y"})
  @DisplayName(
      "Confirm Y/y: pays full balance, writes transaction in order, settles account to 0.00")
  void confirmYes_paysBalanceInFull_writesTransaction_showsSuccess(String confirmValue) {
    Account acct = account(ACCT_ID, POSITIVE_BAL);
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(acct));
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID)));
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of());

    BillPayScreen screen = screen(ACCT_SID, confirmValue);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    // PROCESS-ENTER-KEY never transfers control: the payment path always redisplays.
    assertThat(next).isNull();

    // Decimal fidelity (AAP 0.6.1): the written amount equals the displayed balance, BigDecimal at
    // scale 2, never float/double; the sign matches COBOL (positive, MOVE ACCT-CURR-BAL TO
    // TRAN-AMT).
    ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(txnCaptor.capture());
    Transaction savedTxn = txnCaptor.getValue();
    assertThat(savedTxn.getTranAmt()).isEqualByComparingTo(new BigDecimal(POSITIVE_BAL));
    assertThat(savedTxn.getTranAmt().scale()).isEqualTo(2);
    // Generated id and resolved card number.
    assertThat(savedTxn.getTranId()).isEqualTo(FIRST_TRAN_ID);
    assertThat(savedTxn.getTranCardNum()).isEqualTo(CARD_NUM);
    // Byte-exact literal field values from INITIALIZE TRAN-RECORD (COBIL00C L218-232).
    assertThat(savedTxn.getTranTypeCd()).isEqualTo("02");
    assertThat(savedTxn.getTranCatCd()).isEqualTo("0002");
    assertThat(savedTxn.getTranSource()).isEqualTo("POS TERM");
    assertThat(savedTxn.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
    assertThat(savedTxn.getTranMerchantId()).isEqualTo(999999999L);
    assertThat(savedTxn.getTranMerchantName()).isEqualTo("BILL PAYMENT");

    // Pay in full (COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT): the balance settles to
    // exactly
    // 0.00 (scale 2) and is persisted.
    ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(acctCaptor.capture());
    assertThat(acctCaptor.getValue().getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("0.00"));
    assertThat(acctCaptor.getValue().getAcctCurrBal().scale()).isEqualTo(2);

    // Success-message parity (AAP 0.6.4): built from 'Payment successful. ' + ' Your Transaction ID
    // is ', so TWO spaces separate "successful." and "Your"; id (delimited by space) then '.'.
    assertThat(screen.getErrMsg())
        .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000001.");
    assertThat(screen.getErrMsg()).contains("Payment successful.");
    assertThat(screen.getErrMsg()).contains("Your Transaction ID is");
    assertThat(screen.getErrMsg()).contains("Payment successful.  Your Transaction ID is");
    // INITIALIZE-ALL-FIELDS runs on a successful write, clearing the editable inputs.
    assertThat(screen.getActIdIn()).isEmpty();
    assertThat(screen.getConfirm()).isEmpty();
    assertThat(screen.getCurBal()).isNull();

    // Control-flow parity (AAP 0.7.1): read account -> resolve xref -> browse for highest id ->
    // write transaction -> update account, in COBOL order, and nothing else.
    InOrder inOrder = inOrder(accountRepository, cardXrefRepository, transactionRepository);
    inOrder.verify(accountRepository).findById(ACCT_ID);
    inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
    inOrder.verify(transactionRepository).findAllByOrderByTranIdAsc();
    inOrder.verify(transactionRepository).save(any(Transaction.class));
    inOrder.verify(accountRepository).save(any(Account.class));
    verifyNoMoreInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 2. ENTER + no confirmation yet: displays the balance and prompts to confirm =============

  @Test
  @DisplayName("ENTER, blank confirm: shows the balance and prompts to confirm; no payment yet")
  void enter_validAccount_displaysBalance_promptsConfirm() {
    when(accountRepository.findById(ACCT_ID))
        .thenReturn(Optional.of(account(ACCT_ID, POSITIVE_BAL)));

    BillPayScreen screen = screen(ACCT_SID, null);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // Balance shown for the entered account (decimal fidelity, scale 2).
    assertThat(screen.getCurBal()).isEqualByComparingTo(new BigDecimal(POSITIVE_BAL));
    assertThat(screen.getCurBal().scale()).isEqualTo(2);
    // Byte-exact confirmation prompt (COBIL00C L237).
    assertThat(screen.getErrMsg()).isEqualTo("Confirm to make a bill payment...");
    // Nothing is posted until the operator confirms.
    verify(accountRepository, never()).save(any(Account.class));
    verifyNoInteractions(cardXrefRepository, transactionRepository);
  }

  // ===== 3. Empty account id: redisplay; no repository access ====================================

  @Test
  @DisplayName("Empty account id: redisplays 'Acct ID can NOT be empty...'; no repository access")
  void emptyAccountId_redisplays() {
    BillPayScreen screen = screen("", null);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Acct ID can NOT be empty...");
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 4. Invalid confirm value: redisplay; no repository access ===============================

  @Test
  @DisplayName("Invalid confirm value: redisplays the '(Y/N)' message; no repository access")
  void invalidConfirmValue_redisplays() {
    BillPayScreen screen = screen(ACCT_SID, "X");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Invalid value. Valid values are (Y/N)...");
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 5. Non-positive balance: nothing to pay =================================================

  @ParameterizedTest
  @ValueSource(strings = {"0.00", "-12.34"})
  @DisplayName("Balance <= 0: redisplays 'You have nothing to pay...'; no payment is posted")
  void nothingToPay_whenBalanceZeroOrNegative_redisplays(String balance) {
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID, balance)));

    BillPayScreen screen = screen(ACCT_SID, null);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getCurBal()).isEqualByComparingTo(new BigDecimal(balance));
    assertThat(screen.getErrMsg()).isEqualTo("You have nothing to pay...");
    verify(accountRepository, never()).save(any(Account.class));
    verifyNoInteractions(cardXrefRepository, transactionRepository);
  }

  // ===== 6. Account not found: redisplay; no downstream access ===================================

  @Test
  @DisplayName("Account not found: redisplays 'Account ID NOT found...'; no downstream access")
  void accountNotFound_redisplaysNotFound() {
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

    BillPayScreen screen = screen(ACCT_SID, null);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Account ID NOT found...");
    verifyNoInteractions(cardXrefRepository, transactionRepository);
  }

  // ===== 7. Cross-reference not found on a confirmed pay: not-found; no transaction ==============

  @Test
  @DisplayName("Xref not found on confirmed pay: 'Account ID NOT found...'; no transaction written")
  void xrefNotFound_redisplaysNotFound() {
    when(accountRepository.findById(ACCT_ID))
        .thenReturn(Optional.of(account(ACCT_ID, POSITIVE_BAL)));
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

    BillPayScreen screen = screen(ACCT_SID, "Y");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Account ID NOT found...");
    verify(transactionRepository, never()).save(any(Transaction.class));
    verify(accountRepository, never()).save(any(Account.class));
  }

  // ===== 8. Duplicate transaction id: redisplay; balance not reduced or saved ====================

  @Test
  @DisplayName("Duplicate transaction id: 'Tran ID already exist...'; balance not reduced or saved")
  void duplicateTranId_redisplays() {
    Account acct = account(ACCT_ID, POSITIVE_BAL);
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(acct));
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID)));
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of());
    when(transactionRepository.save(any(Transaction.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    BillPayScreen screen = screen(ACCT_SID, "Y");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Tran ID already exist...");
    // Write-failure short-circuit: the balance is neither reduced nor persisted.
    assertThat(acct.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal(POSITIVE_BAL));
    verify(accountRepository, never()).save(any(Account.class));
  }

  // ===== 9. Unexpected account-read I/O error: caught and redisplayed (no abend path) ============

  @Test
  @DisplayName("Account read I/O error: caught and redisplayed as 'Unable to lookup Account...'")
  void accountLookupThrows_redisplaysUnableToLookup_noException() {
    when(accountRepository.findById(ACCT_ID))
        .thenThrow(new DataAccessResourceFailureException("simulated VSAM I/O failure"));

    BillPayScreen screen = screen(ACCT_SID, "Y");
    CardDemoCommarea commarea = reenteredCommarea();

    // COBIL00C has no abend path: an unexpected FILE STATUS moves a message into WS-MESSAGE and
    // re-sends the screen. The migrated service therefore catches DataAccessException and
    // redisplays -- it does NOT throw IoStatusException (see BillPayService class documentation).
    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Unable to lookup Account...");
    verifyNoInteractions(cardXrefRepository, transactionRepository);
  }

  // ===== 10. Unexpected xref-read I/O error: caught and redisplayed ==============================

  @Test
  @DisplayName("Xref read I/O error: caught and redisplayed as 'Unable to lookup XREF AIX file...'")
  void xrefLookupThrows_redisplaysUnableToLookupXref() {
    when(accountRepository.findById(ACCT_ID))
        .thenReturn(Optional.of(account(ACCT_ID, POSITIVE_BAL)));
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID))
        .thenThrow(new DataAccessResourceFailureException("simulated AIX I/O failure"));

    BillPayScreen screen = screen(ACCT_SID, "Y");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Unable to lookup XREF AIX file...");
    verify(transactionRepository, never()).save(any(Transaction.class));
    verify(accountRepository, never()).save(any(Account.class));
  }

  // ===== 11. Unexpected transaction-browse I/O error: caught and redisplayed =====================

  @Test
  @DisplayName("Transaction browse I/O error: redisplayed as 'Unable to lookup Transaction...'")
  void transactionBrowseThrows_redisplaysUnableToLookupTran() {
    when(accountRepository.findById(ACCT_ID))
        .thenReturn(Optional.of(account(ACCT_ID, POSITIVE_BAL)));
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID)));
    when(transactionRepository.findAllByOrderByTranIdAsc())
        .thenThrow(new DataAccessResourceFailureException("simulated browse failure"));

    BillPayScreen screen = screen(ACCT_SID, "Y");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Unable to lookup Transaction...");
    verify(transactionRepository, never()).save(any(Transaction.class));
    verify(accountRepository, never()).save(any(Account.class));
  }

  // ===== 12. Unexpected transaction-write I/O error: caught and redisplayed; no account save =====

  @Test
  @DisplayName("Transaction write I/O error: 'Unable to Add Bill pay Transaction...'; no acct save")
  void transactionWriteOtherError_redisplaysUnableToAdd() {
    when(accountRepository.findById(ACCT_ID))
        .thenReturn(Optional.of(account(ACCT_ID, POSITIVE_BAL)));
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID)));
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of());
    when(transactionRepository.save(any(Transaction.class)))
        .thenThrow(new DataAccessResourceFailureException("simulated write failure"));

    BillPayScreen screen = screen(ACCT_SID, "Y");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Unable to Add Bill pay Transaction...");
    verify(accountRepository, never()).save(any(Account.class));
  }

  // ===== 13. Account rewrite fails after a successful write: update message overrides success ====

  @Test
  @DisplayName(
      "Account rewrite I/O error after write: overrides success with 'Unable to Update...'")
  void accountUpdateThrows_overridesSuccessWithUnableToUpdate() {
    Account acct = account(ACCT_ID, POSITIVE_BAL);
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(acct));
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID)));
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of());
    when(accountRepository.save(any(Account.class)))
        .thenThrow(new DataAccessResourceFailureException("simulated rewrite failure"));

    BillPayScreen screen = screen(ACCT_SID, "Y");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // The transaction was written (success message set), then the account REWRITE failed and the
    // update message overrides it, exactly as the COBOL re-send would.
    assertThat(screen.getErrMsg()).isEqualTo("Unable to Update Account...");
    // The balance was still computed to 0.00 before the failed save (scale 2).
    assertThat(acct.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("0.00"));
    verify(transactionRepository).save(any(Transaction.class));
  }

  // ===== 14. Confirm N/n: clears the screen and redisplays without paying ========================

  @ParameterizedTest
  @ValueSource(strings = {"N", "n"})
  @DisplayName("Confirm N/n: clears the screen and redisplays without paying; no repository access")
  void confirmNo_clearsScreen_doesNotPay(String confirmValue) {
    BillPayScreen screen = screen(ACCT_SID, confirmValue);
    screen.setCurBal(new BigDecimal(POSITIVE_BAL));
    screen.setErrMsg("stale message");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // CLEAR-CURRENT-SCREEN -> INITIALIZE-ALL-FIELDS clears the editable fields.
    assertThat(screen.getActIdIn()).isEmpty();
    assertThat(screen.getConfirm()).isEmpty();
    assertThat(screen.getCurBal()).isNull();
    assertThat(screen.getErrMsg()).isEmpty();
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 15. PF3 with a caller: returns to the calling program and stamps the hand-off ===========

  @Test
  @DisplayName("PF3 with caller context: returns to the calling program and stamps the hand-off")
  void pfk03_returnsToCallingProgram() {
    BillPayScreen screen = screen(ACCT_SID, null);
    CardDemoCommarea commarea = reenteredCommarea();
    commarea.setFromTranId("CA00");
    commarea.setFromProgram("COADM01C");

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.PFK03);

    // EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) -> control returns to the caller.
    assertThat(next).isEqualTo("COADM01C");
    assertThat(commarea.getToProgram()).isEqualTo("COADM01C");
    // RETURN-TO-PREV-SCREEN stamps this program as the new from-context and resets to the enter
    // state.
    assertThat(commarea.getFromProgram()).isEqualTo("COBIL00C");
    assertThat(commarea.getFromTranId()).isEqualTo("CB00");
    assertThat(commarea.isPgmEnter()).isTrue();
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 16. PF3 with no caller: defaults to the main menu =======================================

  @Test
  @DisplayName("PF3 with no caller context: defaults to the main menu (COMEN01C)")
  void pfk03_noCaller_defaultsToMainMenu() {
    BillPayScreen screen = screen(ACCT_SID, null);
    CardDemoCommarea commarea = reenteredCommarea();
    // from-program left blank (low-values) -> the main-menu default applies.

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getFromProgram()).isEqualTo("COBIL00C");
    assertThat(commarea.getFromTranId()).isEqualTo("CB00");
    assertThat(commarea.isPgmEnter()).isTrue();
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 17. PF4: clears the current screen and redisplays =======================================

  @Test
  @DisplayName("PF4: clears the current screen and redisplays; no repository access")
  void pfk04_clearsScreen_redisplays() {
    BillPayScreen screen = screen(ACCT_SID, "Y");
    screen.setCurBal(new BigDecimal(POSITIVE_BAL));
    screen.setErrMsg("stale message");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.PFK04);

    assertThat(next).isNull();
    assertThat(screen.getActIdIn()).isEmpty();
    assertThat(screen.getConfirm()).isEmpty();
    assertThat(screen.getCurBal()).isNull();
    assertThat(screen.getErrMsg()).isEmpty();
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 18. Unmapped attention key (null AID): shared invalid-key message =======================

  @Test
  @DisplayName("Unmapped attention key (null AID): redisplays the shared invalid-key message")
  void unmappedAid_redisplaysInvalidKey() {
    BillPayScreen screen = screen(ACCT_SID, null);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, null);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY);
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 19. Unhandled PF key (PF10): shared invalid-key message =================================

  @Test
  @DisplayName("Unhandled PF key (PF10): redisplays the shared invalid-key message")
  void otherPfKey_redisplaysInvalidKey() {
    BillPayScreen screen = screen(ACCT_SID, null);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.PFK10);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY);
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 20. First entry (blank account): paints the screen and advances to re-entry =============

  @Test
  @DisplayName("First entry (blank account): paints the screen, advances to re-entry; no reads")
  void firstEntry_blankAccount_advancesToReentry() {
    BillPayScreen screen = screen(null, null);
    CardDemoCommarea commarea = firstEntryCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // The re-enter flag is set so the next submission is processed as input.
    assertThat(commarea.isPgmReenter()).isTrue();
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 21. First entry with a pre-selected account: processes immediately ======================

  @Test
  @DisplayName("First entry with pre-selected account: processes immediately, prompts to confirm")
  void firstEntry_preselectedAccount_processesImmediately() {
    when(accountRepository.findById(ACCT_ID))
        .thenReturn(Optional.of(account(ACCT_ID, POSITIVE_BAL)));

    BillPayScreen screen = screen(ACCT_SID, null);
    CardDemoCommarea commarea = firstEntryCommarea();

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // The CDEMO-CB00-TRN-SELECTED hand-off runs PROCESS-ENTER-KEY immediately on first entry.
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(screen.getCurBal()).isEqualByComparingTo(new BigDecimal(POSITIVE_BAL));
    assertThat(screen.getErrMsg()).isEqualTo("Confirm to make a bill payment...");
    verify(accountRepository, never()).save(any(Account.class));
    verifyNoInteractions(cardXrefRepository, transactionRepository);
  }

  // ===== 22. Uninitialized COMMAREA (blank user id): routes to sign-on
  // ============================

  @Test
  @DisplayName("Uninitialized COMMAREA (blank user id): routes to sign-on (COSGN00C); no reads")
  void uninitializedCommarea_routesToSignon() {
    BillPayScreen screen = screen(ACCT_SID, "Y");
    CardDemoCommarea commarea = new CardDemoCommarea(); // user id blank -> EIBCALEN = 0 guard

    String next = service.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COSGN00C");
    assertThat(commarea.getToProgram()).isEqualTo("COSGN00C");
    assertThat(commarea.getFromProgram()).isEqualTo("COBIL00C");
    assertThat(commarea.getFromTranId()).isEqualTo("CB00");
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 23. Null screen argument: NPE guard before any I/O ======================================

  @Test
  @DisplayName("Null screen argument: throws NullPointerException before any I/O")
  void nullScreen_throwsNullPointerException() {
    CardDemoCommarea commarea = reenteredCommarea();

    assertThatThrownBy(() -> service.processBillPay(null, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("screen");
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }

  // ===== 24. Null commarea argument: NPE guard before any I/O ====================================

  @Test
  @DisplayName("Null commarea argument: throws NullPointerException before any I/O")
  void nullCommarea_throwsNullPointerException() {
    BillPayScreen screen = screen(ACCT_SID, "Y");

    assertThatThrownBy(() -> service.processBillPay(screen, null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("commarea");
    verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
  }
}

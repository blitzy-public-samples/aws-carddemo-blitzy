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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link AccountViewService}, the online
 * account-view business-logic service migrated from the legacy CICS COBOL program {@code COACTVWC}
 * (CICS transaction {@code CAVW}; behavioral spec {@code legacy/app/cbl/COACTVWC.cbl} with
 * copybooks {@code CVACT01Y} (account, five money fields), {@code CVACT03Y} (card cross-reference),
 * and {@code CVCUS01Y} (customer)).
 *
 * <p>The service is strictly <strong>read-only</strong>: it resolves an account through a fixed
 * three-step keyed-read chain &mdash; {@code cardXrefRepository.findByXrefAcctId(acctId).get(0)}
 * &rarr; {@code accountRepository.findById(acctId)} &rarr; {@code
 * customerRepository.findById(custId)} &mdash; that <strong>short-circuits</strong> on the first
 * miss. The three repositories are mocked (no Spring context, no Testcontainers, no database) so
 * each control-flow branch can be exercised in isolation and the exact collaboration order
 * asserted.
 *
 * <p>Parity points pinned by these tests (Agent Action Plan):
 *
 * <ul>
 *   <li><strong>Control flow</strong> (&sect;0.7.1): the {@link InOrder} chain xref &rarr; account
 *       &rarr; customer, and the downstream short-circuit (a not-found at any step never queries
 *       the later stores).
 *   <li><strong>Decimal fidelity</strong> (&sect;0.6.1): all five monetary fields are carried as
 *       {@link BigDecimal} at scale&nbsp;2 (asserted by comparison <em>and</em> scale); no {@code
 *       float}/{@code double}.
 *   <li><strong>Message-literal parity</strong> (&sect;0.6.4): the validation filter literal (with
 *       its deliberate <em>two</em> spaces after "must") and the three asymmetric not-found
 *       fragments (two-space, no-space, and trailing-space variants around {@code Resp:}) are
 *       byte-exact.
 *   <li><strong>Navigation / state</strong> (&sect;0.6.5): PF3 routing to the calling program (or
 *       the main-menu default) and the first-entry / re-entry context transitions.
 *   <li><strong>FILE STATUS mapping</strong> (&sect;0.6.4): an empty result is a redisplay (never
 *       an exception); an unexpected data-access failure escalates to {@link IoStatusException}.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccountViewServiceTest {

  // ----- Shared test data -----------------------------------------------------------------------

  /** A valid 11-digit, zero-padded account filter as entered on the screen ({@code ACCTSIDI}). */
  private static final String ACCT_SID = "00000000123";

  /** The numeric account id parsed from {@link #ACCT_SID}. */
  private static final Long ACCT_ID = 123L;

  /** The customer id linked to the account by the card cross-reference. */
  private static final Long CUST_ID = 456L;

  /** A numeric 16-character card number (the cross-reference {@code XREF-CARD-NUM}). */
  private static final String CARD_NUM = "1234567890123456";

  // ----- Mocked collaborators (the migrated VSAM datasets) + service under test -----------------

  @Mock private AccountRepository accountRepository;
  @Mock private CustomerRepository customerRepository;
  @Mock private CardXrefRepository cardXrefRepository;

  @InjectMocks private AccountViewService service;

  // ----- Fixture builders -----------------------------------------------------------------------

  /**
   * Builds an account master record with all five monetary balance/limit fields set to distinct,
   * scale-2 {@link BigDecimal} values (decimal-fidelity parity, AAP &sect;0.6.1) plus the text
   * fields the view paints.
   */
  private static Account account(long acctId) {
    Account account = new Account();
    account.setAcctId(acctId);
    account.setAcctActiveStatus("Y");
    account.setAcctCurrBal(new BigDecimal("1234.56"));
    account.setAcctCreditLimit(new BigDecimal("5000.00"));
    account.setAcctCashCreditLimit(new BigDecimal("2500.00"));
    account.setAcctCurrCycCredit(new BigDecimal("300.50"));
    account.setAcctCurrCycDebit(new BigDecimal("150.25"));
    account.setAcctOpenDate("2020-01-15");
    account.setAcctExpiraionDate("2030-01-15");
    account.setAcctReissueDate("2025-01-15");
    account.setAcctGroupId("GRP0000001");
    return account;
  }

  /** Builds a card cross-reference row linking the account to its owning customer. */
  private static CardXref xref(long acctId, long custId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(CARD_NUM);
    xref.setXrefAcctId(acctId);
    xref.setXrefCustId(custId);
    return xref;
  }

  /** Builds a customer master record with the detail fields the view paints. */
  private static Customer customer(long custId) {
    Customer customer = new Customer();
    customer.setCustId(custId);
    customer.setCustFirstName("JOHN");
    customer.setCustMiddleName("Q");
    customer.setCustLastName("PUBLIC");
    customer.setCustAddrLine1("123 MAIN ST");
    customer.setCustAddrLine2("APT 4B");
    // The screen "city" field is deliberately sourced from customer address line 3 (COACTVWC L513).
    customer.setCustAddrLine3("ANYTOWN");
    customer.setCustAddrStateCd("NY");
    customer.setCustAddrCountryCd("USA");
    customer.setCustAddrZip("10001");
    customer.setCustPhoneNum1("(555)123-4567");
    customer.setCustPhoneNum2("(555)765-4321");
    customer.setCustSsn(123456789L);
    customer.setCustGovtIssuedId("GOVT-ID-001");
    customer.setCustDobYyyyMmDd("1980-05-20");
    customer.setCustEftAccountId("EFT0000001");
    customer.setCustPriCardHolderInd("Y");
    customer.setCustFicoCreditScore(750L);
    return customer;
  }

  /** Builds a screen DTO carrying the supplied raw account-filter input ({@code ACCTSIDI}). */
  private static AccountViewScreen screen(String acctSid) {
    AccountViewScreen screen = new AccountViewScreen();
    screen.setAcctSid(acctSid);
    return screen;
  }

  /**
   * A standard-user COMMAREA already in the re-enter (post-paint) state, so a submission is
   * processed as input. A fresh {@link CardDemoCommarea} defaults to the {@code PGM-ENTER} context,
   * so the re-entry read path is only reached after {@code setPgmReenter()}.
   */
  private static CardDemoCommarea reenteredCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    commarea.setPgmReenter();
    return commarea;
  }

  // ===== 1. Valid account: full read chain, in order, with decimal-fidelity field mapping ========

  @Test
  @DisplayName("Valid account: reads xref->account->customer in order and maps all detail fields")
  void validAccount_fullChain_populatesScreen_inOrder() {
    Account accountRec = account(ACCT_ID);
    Customer customerRec = customer(CUST_ID);
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(accountRec));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customerRec));

    AccountViewScreen screen = screen(ACCT_SID);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    // The re-entry read path repaints the populated screen rather than transferring control.
    assertThat(next).isNull();

    // Control-flow parity (AAP 0.7.1): the three keyed reads run xref -> account -> customer, and
    // nothing else.
    InOrder inOrder = inOrder(cardXrefRepository, accountRepository, customerRepository);
    inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
    inOrder.verify(accountRepository).findById(ACCT_ID);
    inOrder.verify(customerRepository).findById(CUST_ID);
    inOrder.verifyNoMoreInteractions();

    // Decimal fidelity (AAP 0.6.1): five money fields as BigDecimal, scale 2, never float/double.
    assertThat(screen.getAcurBal()).isEqualByComparingTo(accountRec.getAcctCurrBal());
    assertThat(screen.getAcurBal().scale()).isEqualTo(2);
    assertThat(screen.getAcrdLim()).isEqualByComparingTo(accountRec.getAcctCreditLimit());
    assertThat(screen.getAcrdLim().scale()).isEqualTo(2);
    assertThat(screen.getAcshLim()).isEqualByComparingTo(accountRec.getAcctCashCreditLimit());
    assertThat(screen.getAcshLim().scale()).isEqualTo(2);
    assertThat(screen.getAcrCycr()).isEqualByComparingTo(accountRec.getAcctCurrCycCredit());
    assertThat(screen.getAcrCycr().scale()).isEqualTo(2);
    assertThat(screen.getAcrCydb()).isEqualByComparingTo(accountRec.getAcctCurrCycDebit());
    assertThat(screen.getAcrCydb().scale()).isEqualTo(2);

    // Account text fields (and the echoed 11-digit account filter).
    assertThat(screen.getAcstTus()).isEqualTo(accountRec.getAcctActiveStatus());
    assertThat(screen.getAdtOpen()).isEqualTo(accountRec.getAcctOpenDate());
    assertThat(screen.getAexpDt()).isEqualTo(accountRec.getAcctExpiraionDate());
    assertThat(screen.getAreisDt()).isEqualTo(accountRec.getAcctReissueDate());
    assertThat(screen.getAaddGrp()).isEqualTo(accountRec.getAcctGroupId());
    assertThat(screen.getAcctSid()).isEqualTo("00000000123");

    // Customer fields, including the formatted id/SSN/FICO and the address-line-3 -> city mapping.
    assertThat(screen.getAcstNum()).isEqualTo("000000456");
    assertThat(screen.getAcstSsn()).isEqualTo("123-45-6789");
    assertThat(screen.getAcstFco()).isEqualTo("750");
    assertThat(screen.getAcstDob()).isEqualTo(customerRec.getCustDobYyyyMmDd());
    assertThat(screen.getAcsFnam()).isEqualTo(customerRec.getCustFirstName());
    assertThat(screen.getAcsMnam()).isEqualTo(customerRec.getCustMiddleName());
    assertThat(screen.getAcsLnam()).isEqualTo(customerRec.getCustLastName());
    assertThat(screen.getAcsAdl1()).isEqualTo(customerRec.getCustAddrLine1());
    assertThat(screen.getAcsAdl2()).isEqualTo(customerRec.getCustAddrLine2());
    assertThat(screen.getAcsCity()).isEqualTo(customerRec.getCustAddrLine3());
    assertThat(screen.getAcsStte()).isEqualTo(customerRec.getCustAddrStateCd());
    assertThat(screen.getAcsZipc()).isEqualTo(customerRec.getCustAddrZip());
    assertThat(screen.getAcsCtry()).isEqualTo(customerRec.getCustAddrCountryCd());
    assertThat(screen.getAcsPhn1()).isEqualTo(customerRec.getCustPhoneNum1());
    assertThat(screen.getAcsPhn2()).isEqualTo(customerRec.getCustPhoneNum2());
    assertThat(screen.getAcsGovt()).isEqualTo(customerRec.getCustGovtIssuedId());
    assertThat(screen.getAcsEftc()).isEqualTo(customerRec.getCustEftAccountId());
    assertThat(screen.getAcsPflg()).isEqualTo(customerRec.getCustPriCardHolderInd());

    // Navigation/state parity (AAP 0.6.5): the cross-reference linkage is carried forward and the
    // pseudo-conversational context stays in re-entry.
    assertThat(commarea.getCustId()).isEqualTo(456L);
    assertThat(commarea.getCardNum()).isEqualTo(1_234_567_890_123_456L);
    assertThat(commarea.isPgmReenter()).isTrue();

    // A clean success path carries no error message.
    assertThat(screen.getErrMsg()).isEmpty();
  }

  // ===== 2. Invalid filter (non-numeric or zero): byte-exact two-space message, no reads
  // ==========

  @ParameterizedTest
  @ValueSource(strings = {"ABC", "00000000000"})
  @DisplayName("Invalid filter (non-numeric or zero): redisplays the two-space message; no reads")
  void invalidFilter_nonNumericOrZero_redisplays(String badFilter) {
    AccountViewScreen screen = screen(badFilter);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // Byte-exact: note the TWO spaces between "must" and "be" (AAP 0.7.1 message parity). This is
    // the view filter literal, distinct from the single-space edit-routine literal.
    assertThat(screen.getErrMsg()).isEqualTo("Account Filter must  be a non-zero 11 digit number");
    // INPUT-ERROR short-circuits before any read is attempted.
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  // ===== 2a. Over-length numeric filter: validation message, NOT an HTTP 500 abend ===============

  @Test
  @DisplayName("Over-length numeric filter: redisplays the two-space message; no abend; no reads")
  void overlongNumericFilter_redisplaysInsteadOfAbending() {
    // QA FINAL_ALT Issue 3 regression: an all-digit value longer than the CC-ACCT-ID PIC X(11)
    // field (here the exact 30-nine adversarial payload) is all-digits and non-zero, so before the
    // length guard it slipped past the numeric/zero edit and reached Long.parseLong, overflowing
    // Long.MAX_VALUE and throwing NumberFormatException -> 9999 abend -> HTTP 500. It must instead
    // be
    // folded into the standard invalid-filter branch: the same two-space message, no read
    // attempted,
    // and crucially NO exception escaping the service.
    AccountViewScreen screen = screen("999999999999999999999999999999");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Account Filter must  be a non-zero 11 digit number");
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    // The account id is reset to zero, exactly as for any other invalid filter.
    assertThat(commarea.getAcctId()).isZero();
  }

  // ===== 3. Cross-reference not found: two-space message; account/customer never read ============

  @Test
  @DisplayName("Xref not found: cross-ref message (two spaces); account/customer never queried")
  void xrefNotFound_redisplaysCrossRefMessage() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

    AccountViewScreen screen = screen(ACCT_SID);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(
            "Account:00000000123 not found in Cross ref file.  Resp:000000013 Reas:000000000");
    // Parity-critical fragment: TWO spaces after "file." (AAP 0.6.4).
    assertThat(screen.getErrMsg()).contains("Cross ref file.  Resp:");
    // Short-circuit: a missing cross-reference never queries the account or customer stores.
    verifyNoInteractions(accountRepository, customerRepository);
  }

  // ===== 4. Account master not found: no-space message; customer never read ======================

  @Test
  @DisplayName("Account not found: acct-master message (no space before Resp); customer not read")
  void accountNotFound_redisplaysAcctMasterMessage() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

    AccountViewScreen screen = screen(ACCT_SID);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(
            "Account:00000000123 not found in Acct Master file.Resp:000000013 Reas:000000000");
    // Parity-critical fragment: NO space before "Resp:" (AAP 0.6.4).
    assertThat(screen.getErrMsg()).contains("Acct Master file.Resp:");
    // Short-circuit: a missing account never queries the customer store.
    verifyNoInteractions(customerRepository);
  }

  // ===== 5. Customer master not found: trailing-space message ====================================

  @Test
  @DisplayName("Customer not found: customer-master message (trailing space after Resp:)")
  void customerNotFound_redisplaysCustomerMasterMessage() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

    AccountViewScreen screen = screen(ACCT_SID);
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo("CustId:000000456 not found in customer master.Resp: 000000013 REAS:000000000");
    // Parity-critical fragment: a trailing space after "Resp:" and the all-caps "REAS:" (AAP
    // 0.6.4).
    assertThat(screen.getErrMsg()).contains("in customer master.Resp: ");
  }

  // ===== 6. Unexpected data-access failure escalates as IoStatusException ========================

  @Test
  @DisplayName(
      "Unexpected I/O failure on a read escalates as IoStatusException (WHEN OTHER branch)")
  void repositoryThrows_mapsToIoStatusException() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID))
        .thenThrow(new DataAccessResourceFailureException("simulated VSAM I/O failure"));

    AccountViewScreen screen = screen(ACCT_SID);
    CardDemoCommarea commarea = reenteredCommarea();

    // The abnormal-status branch wraps the data-access failure as an IoStatusException naming the
    // failing file and operation (AAP 0.6.4 FILE STATUS mapping), rather than redisplaying.
    assertThatThrownBy(() -> service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(IoStatusException.class)
        .hasCauseInstanceOf(DataAccessResourceFailureException.class)
        .hasMessageContaining("I/O error on file CXACAIX during READ");

    // No downstream reads once the first keyed read fails abnormally.
    verifyNoInteractions(accountRepository, customerRepository);
  }

  // ===== 7. PF3 exit: returns to the calling program and stamps the navigation hand-off ==========

  @Test
  @DisplayName("PF3 exit: returns to the calling program and stamps the navigation hand-off")
  void pfk03_returnsToCallingProgram() {
    AccountViewScreen screen = screen(ACCT_SID);
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    commarea.setPgmReenter();
    commarea.setFromTranId("CT00");
    commarea.setFromProgram("COTRN00C");

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.PFK03);

    // EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) -> control returns to the caller.
    assertThat(next).isEqualTo("COTRN00C");
    assertThat(commarea.getToProgram()).isEqualTo("COTRN00C");
    assertThat(commarea.getToTranId()).isEqualTo("CT00");
    // This program stamps itself as the new "from" context for the screen it returns to.
    assertThat(commarea.getFromProgram()).isEqualTo("COACTVWC");
    assertThat(commarea.getFromTranId()).isEqualTo("CAVW");
    // The target re-enters fresh and the last map/mapset are recorded.
    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(commarea.getLastMapset()).isEqualTo("COACTVW");
    assertThat(commarea.getLastMap()).isEqualTo("CACTVWA");
    // Exit is a pure navigation action: no data is read.
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  // ===== 8. PF3 exit with no caller context: defaults to the main menu ===========================

  @Test
  @DisplayName("PF3 exit with no caller context: defaults to the main menu (COMEN01C / CM00)")
  void pfk03_noCaller_defaultsToMainMenu() {
    AccountViewScreen screen = screen(null);
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    commarea.setPgmReenter();
    // from-program / from-tranid left blank (low-values) -> the menu defaults apply.

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getToTranId()).isEqualTo("CM00");
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  // ===== 9. First entry (ENTER context): paints a blank prompt, advances to re-entry ============

  @Test
  @DisplayName("First entry: clears the filter, shows the prompt, advances to re-entry; no reads")
  void firstEntry_paintsPromptAndAdvancesToReentry() {
    // Any prior on-screen filter value is cleared on first entry.
    AccountViewScreen screen = screen("99999999999");
    CardDemoCommarea commarea = new CardDemoCommarea(); // fresh -> PGM-ENTER context
    commarea.setUsrTypUser();

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // 1200-SETUP-SCREEN-VARS clears the filter and shows the informational input prompt.
    assertThat(screen.getAcctSid()).isEmpty();
    assertThat(screen.getInfoMsg()).isEqualTo("Enter or update id of account to display");
    // 1400-SEND-SCREEN flips the context so the next submission is processed as input.
    assertThat(commarea.isPgmReenter()).isTrue();
    // First entry performs no reads.
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  // ===== 10. Blank filter on re-entry: "No input received" (cross-field edit override) ===========

  @Test
  @DisplayName("Blank filter on re-entry: redisplays 'No input received'; no reads")
  void blankFilter_redisplaysNoInputReceived() {
    // An all-spaces filter is normalized to the COBOL low-values (blank) state.
    AccountViewScreen screen = screen("   ");
    CardDemoCommarea commarea = reenteredCommarea();

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // The unguarded cross-field edit overwrites the field-level message with NO-SEARCH-CRITERIA.
    assertThat(screen.getErrMsg()).isEqualTo("No input received");
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  // ===== 11. Corrupt program context (neither ENTER nor REENTER): unexpected-scenario message ====

  @Test
  @DisplayName("Corrupt program context: shows the unexpected-scenario message; no reads")
  void corruptContext_showsUnexpectedScenarioMessage() {
    AccountViewScreen screen = screen(ACCT_SID);
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    commarea.setPgmContext(2); // neither PGM-ENTER (0) nor PGM-REENTER (1)

    String next = service.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("UNEXPECTED DATA SCENARIO");
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }
}

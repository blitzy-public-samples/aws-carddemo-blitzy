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
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.TranAddScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link TranAddService}, the online
 * add-transaction business-logic service migrated from the legacy CICS COBOL program {@code
 * COTRN02C} (CICS transaction {@code CT02}; behavioral spec {@code legacy/app/cbl/COTRN02C.cbl},
 * record layouts {@code legacy/app/cpy/CVTRA05Y.cpy} (transaction) and {@code
 * legacy/app/cpy/CVACT03Y.cpy} (card cross-reference), and COMMAREA {@code
 * legacy/app/cpy/COCOM01Y.cpy}).
 *
 * <p>{@code TranAddService} collaborates with exactly two repositories &mdash; the {@link
 * TransactionRepository} (the migrated {@code TRANSACT} VSAM KSDS) and the {@link
 * CardXrefRepository} (the migrated {@code CCXREF}/{@code CXACAIX} cross-reference) &mdash; which
 * are mocked here so every branch runs with no Spring context and no database. The third
 * collaborator, {@code com.aws.carddemo.util.DateValidationService}, is a stateless <em>static</em>
 * utility (the {@code CSUTLDTC}/{@code CEEDAYS} date routine) and is therefore exercised for real
 * with genuinely valid date strings rather than mocked, consistent with the sibling {@code
 * AccountUpdateServiceTest} and the agent prompt's "adapt if static" guidance.
 *
 * <p><strong>The defining parity feature is the EXACT validation order</strong> (Agent Action Plan
 * &sect;0.7.1). {@link TranAddService#processTranAdd(TranAddScreen, CardDemoCommarea,
 * CardWorkArea.Aid)} runs a fixed gauntlet &mdash; key fields (account/card numeric &rarr; xref
 * resolution) &rarr; eleven ordered non-empty checks &rarr; type/category numeric &rarr; amount and
 * date edit masks &rarr; semantic date validation &rarr; merchant-id numeric &rarr; confirmation
 * &rarr; insert. Each test keeps every <em>prior</em> field valid and spoils only the field under
 * test, so the asserted message proves the precise stopping point. Collaborator ordering is pinned
 * with Mockito {@link InOrder}.
 *
 * <p><strong>Byte-exact message parity.</strong> Every operator message is asserted against the
 * literal text verbatim from {@code COTRN02C} (cross-checked at the cited COBOL line numbers), so a
 * single-byte drift fails the test. The success line is the COBOL {@code STRING} of {@code
 * 'Transaction added successfully. '} + {@code ' Your Tran ID is '} + the (space-delimited) tran id
 * + {@code '.'}, which yields exactly <em>two</em> spaces between {@code "successfully."} and
 * {@code "Your"} (COTRN02C L728-732); it is asserted both as the full string and via {@code
 * contains} per fragment.
 *
 * <p><strong>Decimal fidelity</strong> (AAP &sect;0.6.1): the persisted {@code tranAmt} is asserted
 * as a {@link BigDecimal} at scale&nbsp;2 (never a binary {@code double}). <strong>FILE STATUS
 * mapping</strong> (AAP &sect;0.6.4): not-found and duplicate-key paths redisplay on-screen rather
 * than throw, while an unexpected failure positioning for the next transaction id is translated to
 * {@link IoStatusException}.
 */
@ExtendWith(MockitoExtension.class)
class TranAddServiceTest {

  // ===== Shared fixture literals ================================================================

  /** Raw account id the operator types; normalizes to the numeric account key {@code 1}. */
  private static final String ACCOUNT_ID_RAW = "00000000001";

  /**
   * Numeric account key derived from {@link #ACCOUNT_ID_RAW} (the {@code findByXrefAcctId} arg).
   */
  private static final long ACCOUNT_ID_NUM = 1L;

  /** Resolved 16-digit card number adopted from the cross-reference on the account path. */
  private static final String SAMPLE_CARD_NUM = "1234567890123456";

  /** The generated transaction id for an empty store (max id 0, plus one, zero-padded to 16). */
  private static final String EXPECTED_TRAN_ID = "0000000000000001";

  /** Mocked {@code TRANSACT} store: next-key browse and the insert. */
  @Mock private TransactionRepository transactionRepository;

  /** Mocked card cross-reference store: {@code findByXrefAcctId} / {@code findById}. */
  @Mock private CardXrefRepository cardXrefRepository;

  /**
   * Service under test. It is a stateless singleton; Mockito injects the two mocked repositories
   * through the service's single constructor. {@code DateValidationService} is static and is not a
   * constructor dependency, so it is intentionally absent here.
   */
  @InjectMocks private TranAddService service;

  // ===== Fixtures / helpers =====================================================================

  /**
   * Builds a fully populated, fully valid Add-Transaction screen on the <em>account</em> key path
   * (account id supplied, card number blank). Individual tests mutate exactly one field to drive a
   * specific branch. Dates are genuinely valid {@code YYYY-MM-DD} strings so the real {@code
   * DateValidationService} accepts them. The amount is a scale-2 {@link BigDecimal} per AAP
   * &sect;0.6.1.
   */
  private static TranAddScreen validScreen() {
    TranAddScreen screen = new TranAddScreen();
    screen.setActIdIn(ACCOUNT_ID_RAW);
    screen.setCardNin(null);
    screen.setTtypCd("01");
    screen.setTcatCd("0005");
    screen.setTrnSrc("POS");
    screen.setTDesc("GROCERY PURCHASE");
    screen.setTrnAmt(new BigDecimal("100.00"));
    screen.setTOrigDt("2024-01-15");
    screen.setTProcDt("2024-01-16");
    screen.setMid("000012345");
    screen.setMName("ACME STORE");
    screen.setMCity("SEATTLE");
    screen.setMZip("98101");
    screen.setConfirm("Y");
    return screen;
  }

  /** A card cross-reference row resolving the account key to {@link #SAMPLE_CARD_NUM}. */
  private static CardXref xref() {
    CardXref ref = new CardXref();
    ref.setXrefCardNum(SAMPLE_CARD_NUM);
    ref.setXrefCustId(100L);
    ref.setXrefAcctId(ACCOUNT_ID_NUM);
    return ref;
  }

  /**
   * A re-entry COMMAREA: {@code CDEMO-PGM-REENTER} so {@code MAIN-PARA} runs the EVALUATE EIBAID.
   */
  private static CardDemoCommarea reentered() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmReenter();
    return commarea;
  }

  /** A first-entry COMMAREA: a fresh instance defaults to {@code CDEMO-PGM-ENTER} (context 0). */
  private static CardDemoCommarea firstEntry() {
    return new CardDemoCommarea();
  }

  /**
   * Stubs the account-path cross-reference resolution so {@code VALIDATE-INPUT-KEY-FIELDS} succeeds
   * and the data-field gauntlet (or confirmation/insert) is reached. Called only by tests that
   * actually advance past key resolution, so it never leaves an unnecessary stub.
   */
  private void givenXrefResolvesAccountToCard() {
    when(cardXrefRepository.findByXrefAcctId(ACCOUNT_ID_NUM)).thenReturn(List.of(xref()));
  }

  // ===== 1. Happy path: validate -> resolve -> insert -> success (ADD-TRANSACTION) ==============

  @Test
  @DisplayName("valid input + confirm 'Y' saves the transaction with the two-space success line")
  void validInput_confirmed_savesTransaction_showsSuccess() {
    givenXrefResolvesAccountToCard();
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of());
    TranAddScreen screen = validScreen();

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    // On a successful insert the COBOL redisplays the same screen (null) with the green message.
    assertThat(next).isNull();

    // The persisted record carries the COBOL field-move semantics and decimal fidelity.
    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    Transaction saved = captor.getValue();
    assertThat(saved.getTranId()).isEqualTo(EXPECTED_TRAN_ID);
    // Decimal fidelity (AAP §0.6.1): scale-2 BigDecimal, asserted by comparison AND preserved
    // scale;
    // the amount is never compared or stored as a binary floating-point (double) value.
    assertThat(saved.getTranAmt()).isEqualByComparingTo("100.00");
    assertThat(saved.getTranAmt().scale()).isEqualTo(2);
    assertThat(saved.getTranTypeCd()).isEqualTo("01");
    assertThat(saved.getTranCatCd()).isEqualTo("0005");
    assertThat(saved.getTranSource()).isEqualTo("POS" + " ".repeat(7));
    assertThat(saved.getTranDesc()).hasSize(100).startsWith("GROCERY PURCHASE");
    assertThat(saved.getTranMerchantId()).isEqualTo(12345L);
    assertThat(saved.getTranMerchantName()).hasSize(50).startsWith("ACME STORE");
    assertThat(saved.getTranMerchantCity()).hasSize(50).startsWith("SEATTLE");
    assertThat(saved.getTranMerchantZip()).isEqualTo("98101" + " ".repeat(5));
    assertThat(saved.getTranCardNum()).isEqualTo(SAMPLE_CARD_NUM);
    assertThat(saved.getTranOrigTs()).hasSize(26).startsWith("2024-01-15");
    assertThat(saved.getTranProcTs()).hasSize(26).startsWith("2024-01-16");

    // Success message parity (COTRN02C L728-732): the STRING build yields TWO spaces between
    // "successfully." and "Your"; assert the full string and each fragment via contains.
    assertThat(screen.getErrMsg())
        .isEqualTo("Transaction added successfully.  Your Tran ID is " + EXPECTED_TRAN_ID + ".")
        .contains("Transaction added successfully.")
        .contains("Your Tran ID is")
        .contains("Transaction added successfully.  Your Tran ID is");

    // Control-flow parity (AAP §0.7.1): xref resolution -> next-id browse -> insert, in COBOL
    // order.
    InOrder inOrder = inOrder(cardXrefRepository, transactionRepository);
    inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCOUNT_ID_NUM);
    inOrder.verify(transactionRepository).findAllByOrderByTranIdAsc();
    inOrder.verify(transactionRepository).save(any(Transaction.class));
    inOrder.verifyNoMoreInteractions();
  }

  // ===== 2-5. Confirmation flag and key-field validation (VALIDATE-INPUT-KEY-FIELDS) ============

  @Test
  @DisplayName("confirm 'X' (not Y/N/blank) shows the byte-exact invalid-value message; no insert")
  void invalidConfirmValue_redisplays() {
    givenXrefResolvesAccountToCard();
    TranAddScreen screen = validScreen();
    screen.setConfirm("X");

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Invalid value. Valid values are (Y/N)...");
    verify(transactionRepository, never()).findAllByOrderByTranIdAsc();
    verify(transactionRepository, never()).save(any());
  }

  @Test
  @DisplayName("non-numeric account id stops at 'Account ID must be Numeric...' before any lookup")
  void accountIdNotNumeric_redisplays() {
    TranAddScreen screen = validScreen();
    screen.setActIdIn("ABC");

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Account ID must be Numeric...");
    verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
    verify(transactionRepository, never()).save(any());
  }

  @Test
  @DisplayName(
      "non-numeric card number stops at 'Card Number must be Numeric...' before any lookup")
  void cardNumberNotNumeric_redisplays() {
    TranAddScreen screen = validScreen();
    screen.setActIdIn(null);
    screen.setCardNin("12AB");

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Card Number must be Numeric...");
    verify(cardXrefRepository, never()).findById(anyString());
    verify(transactionRepository, never()).save(any());
  }

  @Test
  @DisplayName("neither account nor card entered stops at 'Account or Card Number must be entered'")
  void neitherAccountNorCardEntered_redisplays() {
    TranAddScreen screen = validScreen();
    screen.setActIdIn(null);
    screen.setCardNin(null);

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Account or Card Number must be entered...");
    verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
    verify(cardXrefRepository, never()).findById(anyString());
    verify(transactionRepository, never()).save(any());
  }

  // ===== 6. Eleven non-empty checks in EXACT COBOL order (VALIDATE-INPUT-DATA-FIELDS) ===========

  /**
   * Supplies the eleven non-empty checks in their exact COBOL declaration order (COTRN02C
   * L254-L314). For each case only the named field is blanked while every prior field stays valid,
   * so the asserted message proves the precise stopping point.
   */
  private static Stream<Arguments> emptyFieldCases() {
    return Stream.of(
        arguments(
            "Type CD",
            (Consumer<TranAddScreen>) s -> s.setTtypCd(""),
            "Type CD can NOT be empty..."),
        arguments(
            "Category CD",
            (Consumer<TranAddScreen>) s -> s.setTcatCd(""),
            "Category CD can NOT be empty..."),
        arguments(
            "Source", (Consumer<TranAddScreen>) s -> s.setTrnSrc(""), "Source can NOT be empty..."),
        arguments(
            "Description",
            (Consumer<TranAddScreen>) s -> s.setTDesc(""),
            "Description can NOT be empty..."),
        arguments(
            "Amount",
            (Consumer<TranAddScreen>) s -> s.setTrnAmt(null),
            "Amount can NOT be empty..."),
        arguments(
            "Orig Date",
            (Consumer<TranAddScreen>) s -> s.setTOrigDt(""),
            "Orig Date can NOT be empty..."),
        arguments(
            "Proc Date",
            (Consumer<TranAddScreen>) s -> s.setTProcDt(""),
            "Proc Date can NOT be empty..."),
        arguments(
            "Merchant ID",
            (Consumer<TranAddScreen>) s -> s.setMid(""),
            "Merchant ID can NOT be empty..."),
        arguments(
            "Merchant Name",
            (Consumer<TranAddScreen>) s -> s.setMName(""),
            "Merchant Name can NOT be empty..."),
        arguments(
            "Merchant City",
            (Consumer<TranAddScreen>) s -> s.setMCity(""),
            "Merchant City can NOT be empty..."),
        arguments(
            "Merchant Zip",
            (Consumer<TranAddScreen>) s -> s.setMZip(""),
            "Merchant Zip can NOT be empty..."));
  }

  @ParameterizedTest(name = "[{index}] blank {0} -> {2}")
  @MethodSource("emptyFieldCases")
  @DisplayName("each empty data field stops the gauntlet at its byte-exact message, in order")
  void emptyField_redisplays_inExactOrder(
      String label, Consumer<TranAddScreen> blanker, String expected) {
    givenXrefResolvesAccountToCard();
    TranAddScreen screen = validScreen();
    blanker.accept(screen);

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).as("empty %s field", label).isEqualTo(expected);
    verify(transactionRepository, never()).save(any());
  }

  @Test
  @DisplayName("an earlier empty field masks a later one (Type CD precedes Merchant Zip)")
  void emptyField_orderingPrecedence_earlierFieldWins() {
    givenXrefResolvesAccountToCard();
    TranAddScreen screen = validScreen();
    // Blank both the first and the last non-empty checks; the earliest in order must win.
    screen.setTtypCd("");
    screen.setMZip("");

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Type CD can NOT be empty...");
    verify(transactionRepository, never()).save(any());
  }

  // ===== 7. Numeric checks: type/category (post-empty) and merchant id (post-date-validation) ===

  /**
   * Supplies the three numeric checks. Type and category are validated immediately after the
   * non-empty block (COTRN02C L325/L331); the merchant-id numeric check (L432) runs only after the
   * amount and both date validations pass, so its case keeps genuinely valid dates so the real
   * {@code DateValidationService} lets the flow reach the merchant-id check.
   */
  private static Stream<Arguments> numericCheckCases() {
    return Stream.of(
        arguments(
            "Type CD",
            (Consumer<TranAddScreen>) s -> s.setTtypCd("AB"),
            "Type CD must be Numeric..."),
        arguments(
            "Category CD",
            (Consumer<TranAddScreen>) s -> s.setTcatCd("ABCD"),
            "Category CD must be Numeric..."),
        arguments(
            "Merchant ID",
            (Consumer<TranAddScreen>) s -> s.setMid("MERCHANTX"),
            "Merchant ID must be Numeric..."));
  }

  @ParameterizedTest(name = "[{index}] non-numeric {0} -> {2}")
  @MethodSource("numericCheckCases")
  @DisplayName(
      "non-numeric type, category and merchant-id fields each stop at their numeric message")
  void numericChecks_redisplay(String label, Consumer<TranAddScreen> spoiler, String expected) {
    givenXrefResolvesAccountToCard();
    TranAddScreen screen = validScreen();
    spoiler.accept(screen);

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).as("non-numeric %s field", label).isEqualTo(expected);
    verify(transactionRepository, never()).save(any());
  }

  // ===== 8-9. Not-found lookups (READ-CXACAIX-FILE / READ-CCXREF-FILE NOTFND) ===================

  @Test
  @DisplayName("account id with no cross-reference row shows 'Account ID NOT found...'; no insert")
  void accountNotFound_redisplays() {
    when(cardXrefRepository.findByXrefAcctId(2L)).thenReturn(List.of());
    TranAddScreen screen = validScreen();
    screen.setActIdIn("00000000002");

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Account ID NOT found...");
    verify(transactionRepository, never()).save(any());
  }

  @Test
  @DisplayName(
      "card number with no cross-reference row shows 'Card Number NOT found...'; no insert")
  void cardNumberNotFound_redisplays() {
    when(cardXrefRepository.findById(SAMPLE_CARD_NUM)).thenReturn(Optional.empty());
    TranAddScreen screen = validScreen();
    screen.setActIdIn(null);
    screen.setCardNin(SAMPLE_CARD_NUM);

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Card Number NOT found...");
    verify(transactionRepository, never()).save(any());
  }

  // ===== 10-11. Write outcomes: duplicate key, other I/O error, and the IoStatusException path ==

  @Test
  @DisplayName(
      "a duplicate-key write shows the byte-exact 'Tran ID already exist...' and redisplays")
  void duplicateTranId_redisplays() {
    givenXrefResolvesAccountToCard();
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of());
    when(transactionRepository.save(any(Transaction.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));
    TranAddScreen screen = validScreen();

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Tran ID already exist...");
    verify(transactionRepository).save(any(Transaction.class));
  }

  @Test
  @DisplayName("a non-duplicate write failure shows 'Unable to Add Transaction...' (redisplay)")
  void unexpectedPersistError_redisplaysUnableToAdd() {
    givenXrefResolvesAccountToCard();
    when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(List.of());
    when(transactionRepository.save(any(Transaction.class)))
        .thenThrow(new DataAccessResourceFailureException("write failed"));
    TranAddScreen screen = validScreen();

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Unable to Add Transaction...");
  }

  @Test
  @DisplayName("a failure positioning for the next tran id is translated to IoStatusException")
  void transactionBrowseFailure_throwsIoStatusException() {
    givenXrefResolvesAccountToCard();
    when(transactionRepository.findAllByOrderByTranIdAsc())
        .thenThrow(new DataAccessResourceFailureException("browse failed"));
    TranAddScreen screen = validScreen();
    CardDemoCommarea commarea = reentered();

    assertThatThrownBy(() -> service.processTranAdd(screen, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(IoStatusException.class);
    verify(transactionRepository, never()).save(any());
  }

  // ===== 12. PF-key navigation (MAIN-PARA EVALUATE EIBAID) ======================================

  @Test
  @DisplayName("PF3 with no caller returns to main menu COMEN01C and records this program/tranid")
  void pfk03_noCaller_returnsToMainMenu() {
    CardDemoCommarea commarea = reentered();

    String next = service.processTranAdd(new TranAddScreen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo(TranAddService.LIT_MENU_PGM).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getFromProgram()).isEqualTo(TranAddService.PGM_NAME).isEqualTo("COTRN02C");
    assertThat(commarea.getFromTranId()).isEqualTo(TranAddService.TRAN_ID_NAME).isEqualTo("CT02");
    assertThat(commarea.isPgmEnter()).isTrue();
    verify(transactionRepository, never()).save(any());
  }

  @Test
  @DisplayName("PF3 with a recorded caller returns to that caller program")
  void pfk03_withCaller_returnsToCaller() {
    CardDemoCommarea commarea = reentered();
    commarea.setFromProgram("COTRN00C");

    String next = service.processTranAdd(new TranAddScreen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COTRN00C");
    assertThat(commarea.getToProgram()).isEqualTo("COTRN00C");
  }

  @Test
  @DisplayName(
      "PF4 clears every field and the message line, then redisplays (CLEAR-CURRENT-SCREEN)")
  void pfk04_clearsScreen() {
    TranAddScreen screen = validScreen();
    screen.setErrMsg("stale message");

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.PFK04);

    assertThat(next).isNull();
    assertThat(screen.getActIdIn()).isNull();
    assertThat(screen.getCardNin()).isNull();
    assertThat(screen.getTtypCd()).isNull();
    assertThat(screen.getTrnAmt()).isNull();
    assertThat(screen.getConfirm()).isNull();
    assertThat(screen.getErrMsg()).isEmpty();
    verify(transactionRepository, never()).save(any());
  }

  @Test
  @DisplayName("an unsupported attention key shows the byte-exact invalid-key message")
  void unsupportedKey_redisplaysInvalidKey() {
    TranAddScreen screen = new TranAddScreen();

    String next = service.processTranAdd(screen, reentered(), CardWorkArea.Aid.PFK06);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Invalid key pressed. Please see below...");
    verify(transactionRepository, never()).save(any());
  }

  // ===== First-entry behavior and defensive guards (MAIN-PARA L107-159) =========================

  @Test
  @DisplayName("first entry with a blank card number paints a clean screen and sets PGM-REENTER")
  void firstEntry_blankCardNin_cleanDisplay() {
    CardDemoCommarea commarea = firstEntry();
    TranAddScreen screen = new TranAddScreen();

    String next = service.processTranAdd(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    // MAIN-PARA blanks the message line on entry (MOVE SPACES TO ERRMSGO, L109-113); the clean
    // first
    // paint adds no error, so the message line is an empty string (not null) on redisplay.
    assertThat(screen.getErrMsg()).isEmpty();
    verify(cardXrefRepository, never()).findByXrefAcctId(anyLong());
    verify(cardXrefRepository, never()).findById(anyString());
    verify(transactionRepository, never()).save(any());
  }

  @Test
  @DisplayName(
      "first entry with a pre-loaded card number drives the ENTER path (list->add handoff)")
  void firstEntry_cardNinHandoff_drivesEnterPath() {
    when(cardXrefRepository.findById(SAMPLE_CARD_NUM)).thenReturn(Optional.empty());
    CardDemoCommarea commarea = firstEntry();
    TranAddScreen screen = new TranAddScreen();
    screen.setCardNin(SAMPLE_CARD_NUM);

    String next = service.processTranAdd(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    // The handoff drove PROCESS-ENTER-KEY on the first paint: the card lookup ran and reported the
    // byte-exact not-found message, proving the ENTER path executed without an explicit key press.
    assertThat(screen.getErrMsg()).isEqualTo("Card Number NOT found...");
    verify(cardXrefRepository).findById(SAMPLE_CARD_NUM);
  }

  @Test
  @DisplayName("a null screen is rejected by the defensive guard")
  void nullScreen_throws() {
    assertThatThrownBy(() -> service.processTranAdd(null, reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
  }

  @Test
  @DisplayName("a null COMMAREA is rejected by the defensive guard")
  void nullCommarea_throws() {
    assertThatThrownBy(() -> service.processTranAdd(validScreen(), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("commarea must not be null");
  }
}

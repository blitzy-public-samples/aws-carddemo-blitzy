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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.TranViewScreen;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.Messages;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link TranViewService}, the online
 * transaction-view (detail) business-logic service migrated from the legacy CICS COBOL program
 * {@code COTRN01C} (CICS transaction {@code CT01}; behavioral spec {@code
 * legacy/app/cbl/COTRN01C.cbl}, record layout {@code legacy/app/cpy/CVTRA05Y.cpy}, COMMAREA {@code
 * legacy/app/cpy/COCOM01Y.cpy}, screen contract {@code legacy/app/bms/COTRN01.bms}).
 *
 * <p>{@code TranViewService} has exactly one collaborator &mdash; the {@link TransactionRepository}
 * that replaces the COBOL {@code EXEC CICS READ DATASET('TRANSACT')}. It is mocked here so the
 * tests run with no Spring context and no database, exercising every {@code MAIN-PARA} / {@code
 * PROCESS-ENTER-KEY} / {@code READ-TRANSACT-FILE} branch in isolation. Control-flow parity (Agent
 * Action Plan &sect;0.6.5, &sect;0.7.1) is asserted through the externally observable effects of
 * {@link TranViewService#processTranView(TranViewScreen, CardDemoCommarea, CardWorkArea.Aid)}: its
 * return value (the {@code EXEC CICS XCTL} target program, or {@code null} to redisplay), the
 * mutations it makes to the {@link CardDemoCommarea} navigation state, and the detail /
 * error-message content it writes to the {@link TranViewScreen}.
 *
 * <p><strong>Byte-exact message parity.</strong> The empty-id, not-found, lookup-error, and
 * invalid-key messages are compared against the production constants exactly as declared, so a
 * single-byte drift fails the test.
 *
 * <p><strong>Read-only parity.</strong> The not-found and lookup-error paths are asserted to set an
 * on-screen message and redisplay (return {@code null}) rather than throw, matching the legacy
 * {@code DFHRESP(NOTFND)} and {@code WHEN OTHER} branches which {@code SEND} rather than abend.
 */
@ExtendWith(MockitoExtension.class)
class TranViewServiceTest {

  /** A representative, fully populated 16-character transaction id used across the lookup tests. */
  private static final String SAMPLE_TRAN_ID = "0000000000000001";

  /** Mocked collaborator (the migrated {@code TRANSACT} read path). */
  @Mock private TransactionRepository transactionRepository;

  /**
   * Service under test. It is a stateless singleton; Mockito injects the mocked repository through
   * the service's single constructor.
   */
  @InjectMocks private TranViewService service;

  // ===== Fixtures / helpers =====================================================================

  /** A brand-new screen contract with no fields populated. */
  private static TranViewScreen screen() {
    return new TranViewScreen();
  }

  /** A screen carrying the supplied raw search id ({@code TRNIDINI}). */
  private static TranViewScreen screenWithId(String id) {
    TranViewScreen screen = screen();
    screen.setTrnIdIn(id);
    return screen;
  }

  /** A first-entry COMMAREA: a fresh instance defaults to {@code CDEMO-PGM-ENTER} (context 0). */
  private static CardDemoCommarea firstEntry() {
    return new CardDemoCommarea();
  }

  /**
   * A re-entry COMMAREA: {@code CDEMO-PGM-REENTER} is set so {@code MAIN-PARA} runs the EVALUATE.
   */
  private static CardDemoCommarea reentered() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmReenter();
    return commarea;
  }

  /**
   * A fully populated transaction whose wide fields force the COBOL right-truncation on the move.
   */
  private static Transaction sampleTransaction() {
    Transaction tx = new Transaction();
    tx.setTranId(SAMPLE_TRAN_ID);
    tx.setTranCardNum("4111111111111111");
    tx.setTranTypeCd("01");
    tx.setTranCatCd("0005");
    tx.setTranSource("ONLINEPOST");
    tx.setTranAmt(new BigDecimal("1234.56"));
    tx.setTranDesc("D".repeat(100));
    tx.setTranOrigTs("2022-07-18-12.34.56.789012");
    tx.setTranProcTs("2022-07-19-01.02.03.456789");
    tx.setTranMerchantId(12345L);
    tx.setTranMerchantName("N".repeat(50));
    tx.setTranMerchantCity("C".repeat(50));
    tx.setTranMerchantZip("12345-6789");
    return tx;
  }

  // ===== MAIN-PARA: guard clauses (COTRN01C L86-96)
  // ===============================================

  @Test
  @DisplayName("null screen is rejected (defensive Objects.requireNonNull guard)")
  void nullScreen_throws() {
    assertThatThrownBy(() -> service.processTranView(null, firstEntry(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
  }

  @Test
  @DisplayName("EIBCALEN=0 (null COMMAREA) routes to sign-on COSGN00C and clears the message")
  void noCommarea_routesToSignon() {
    TranViewScreen screen = screenWithId(SAMPLE_TRAN_ID);

    String next = service.processTranView(screen, null, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo(TranViewService.LIT_SIGNON_PGM).isEqualTo("COSGN00C");
    assertThat(screen.getErrMsg()).isEmpty();
    verify(transactionRepository, never()).findById(anyString());
  }

  // ===== MAIN-PARA: first entry (COTRN01C L99-109)
  // ================================================

  @Test
  @DisplayName("first entry with no selected id sets PGM-REENTER, clears detail, and redisplays")
  void firstEntry_noSelectedId_redisplaysWithoutLookup() {
    CardDemoCommarea commarea = firstEntry();
    TranViewScreen screen = screen();
    screen.setTrnId("STALE-VALUE");

    String next = service.processTranView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(screen.getTrnId()).isEmpty();
    assertThat(screen.getErrMsg()).isEmpty();
    verify(transactionRepository, never()).findById(anyString());
  }

  @Test
  @DisplayName("first entry with a selected id (list->view handoff) auto-looks-up and populates")
  void firstEntry_selectedId_autoLookupPopulates() {
    when(transactionRepository.findById(SAMPLE_TRAN_ID))
        .thenReturn(Optional.of(sampleTransaction()));
    CardDemoCommarea commarea = firstEntry();
    TranViewScreen screen = screenWithId(SAMPLE_TRAN_ID);

    String next = service.processTranView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(screen.getTrnId()).isEqualTo(SAMPLE_TRAN_ID);
    assertThat(screen.getErrMsg()).isEmpty();
    verify(transactionRepository).findById(SAMPLE_TRAN_ID);
  }

  // ===== PROCESS-ENTER-KEY (COTRN01C L144-192)
  // ====================================================

  @Test
  @DisplayName("ENTER with a blank id shows the byte-exact empty-id message and does not read")
  void enter_blankId_showsEmptyMessage() {
    TranViewScreen screen = screenWithId("   ");

    String next = service.processTranView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(TranViewService.MSG_TRAN_ID_EMPTY);
    assertThat(screen.getErrMsg()).isEqualTo("Tran ID can NOT be empty...");
    verify(transactionRepository, never()).findById(anyString());
  }

  @Test
  @DisplayName("ENTER with a found id populates all 13 detail fields with COBOL move semantics")
  void enter_foundId_populatesDetail() {
    when(transactionRepository.findById(SAMPLE_TRAN_ID))
        .thenReturn(Optional.of(sampleTransaction()));
    TranViewScreen screen = screenWithId(SAMPLE_TRAN_ID);

    String next = service.processTranView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEmpty();
    assertThat(screen.getTrnId()).isEqualTo(SAMPLE_TRAN_ID);
    assertThat(screen.getCardNum()).isEqualTo("4111111111111111");
    assertThat(screen.getTtypCd()).isEqualTo("01");
    assertThat(screen.getTcatCd()).isEqualTo("0005");
    assertThat(screen.getTrnSrc()).isEqualTo("ONLINEPOST");
    // Decimal fidelity (AAP §0.6.1): TRAN-AMT is a fixed-point money field carried through the move
    // unedited as a BigDecimal. Assert the value by comparison AND the preserved scale of 2; the
    // amount is never compared as a binary floating-point (double) value.
    assertThat(screen.getTrnAmt()).isEqualByComparingTo("1234.56");
    assertThat(screen.getTrnAmt().scale()).isEqualTo(2);
    // TRAN-DESC X(100) -> TDESCI X(60): truncated on the right to 60 characters.
    assertThat(screen.getTDesc()).hasSize(60).isEqualTo("D".repeat(60));
    // TRAN-ORIG-TS / TRAN-PROC-TS X(26) -> X(10): the leading yyyy-MM-dd date portion is kept.
    assertThat(screen.getTOrigDt()).isEqualTo("2022-07-18");
    assertThat(screen.getTProcDt()).isEqualTo("2022-07-19");
    // TRAN-MERCHANT-ID 9(09) -> MIDI X(9): nine zero-padded display digits.
    assertThat(screen.getMid()).isEqualTo("000012345");
    // TRAN-MERCHANT-NAME X(50) -> MNAMEI X(30) and TRAN-MERCHANT-CITY X(50) -> MCITYI X(25).
    assertThat(screen.getMName()).hasSize(30).isEqualTo("N".repeat(30));
    assertThat(screen.getMCity()).hasSize(25).isEqualTo("C".repeat(25));
    assertThat(screen.getMZip()).isEqualTo("12345-6789");
    // Control-flow parity (AAP §0.7.1): the empty-id validation precedes the read, and exactly one
    // keyed read of the TRANSACT store occurs (PROCESS-ENTER-KEY -> READ-TRANSACT-FILE order).
    InOrder inOrder = inOrder(transactionRepository);
    inOrder.verify(transactionRepository).findById(SAMPLE_TRAN_ID);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  @DisplayName("a short search id is right-padded to the 16-char key before the keyed read")
  void enter_shortId_isSpacePaddedToKeyWidth() {
    String paddedKey = "1" + " ".repeat(15);
    when(transactionRepository.findById(paddedKey)).thenReturn(Optional.empty());
    TranViewScreen screen = screenWithId("1");

    String next = service.processTranView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    verify(transactionRepository).findById(paddedKey);
  }

  @Test
  @DisplayName("ENTER with a not-found id shows the byte-exact not-found message and redisplays")
  void enter_notFound_showsNotFoundMessage() {
    when(transactionRepository.findById(SAMPLE_TRAN_ID)).thenReturn(Optional.empty());
    TranViewScreen screen = screenWithId(SAMPLE_TRAN_ID);

    String next = service.processTranView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(TranViewService.MSG_TRAN_NOT_FOUND);
    assertThat(screen.getErrMsg()).isEqualTo("Transaction ID NOT found...");
    assertThat(screen.getTrnId()).isEmpty();
  }

  @Test
  @DisplayName("a repository DataAccessException is surfaced on-screen (WHEN OTHER), never thrown")
  void enter_dataAccessException_showsLookupErrorMessage() {
    when(transactionRepository.findById(SAMPLE_TRAN_ID))
        .thenThrow(new DataAccessResourceFailureException("simulated database failure"));
    TranViewScreen screen = screenWithId(SAMPLE_TRAN_ID);

    String next = service.processTranView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(TranViewService.MSG_TRAN_LOOKUP_ERROR);
    assertThat(screen.getErrMsg()).isEqualTo("Unable to lookup Transaction...");
  }

  @Test
  @DisplayName("found record with null fields renders as spaces / blank per COBOL move semantics")
  void enter_foundWithNullFields_rendersBlanks() {
    Transaction sparse = new Transaction();
    sparse.setTranId(SAMPLE_TRAN_ID);
    // All other fields intentionally left null to exercise the null-source move branches.
    when(transactionRepository.findById(SAMPLE_TRAN_ID)).thenReturn(Optional.of(sparse));
    TranViewScreen screen = screenWithId(SAMPLE_TRAN_ID);

    service.processTranView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(screen.getTrnSrc()).isEqualTo(" ".repeat(10));
    assertThat(screen.getTDesc()).isEqualTo(" ".repeat(60));
    assertThat(screen.getMName()).isEqualTo(" ".repeat(30));
    // TRAN-MERCHANT-ID null -> nine spaces (a genuine numeric field never holds null in COBOL).
    assertThat(screen.getMid()).isEqualTo(" ".repeat(9));
    assertThat(screen.getTrnAmt()).isNull();
  }

  @Test
  @DisplayName("an over-wide merchant id keeps the right-most nine digits (high-order truncation)")
  void enter_overWideMerchantId_truncatesHighOrder() {
    Transaction tx = sampleTransaction();
    tx.setTranMerchantId(1234567890123L);
    when(transactionRepository.findById(SAMPLE_TRAN_ID)).thenReturn(Optional.of(tx));
    TranViewScreen screen = screenWithId(SAMPLE_TRAN_ID);

    service.processTranView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(screen.getMid()).hasSize(9).isEqualTo("567890123");
  }

  // ===== MAIN-PARA: PF-key navigation (COTRN01C L110-132)
  // =========================================

  @Test
  @DisplayName("PF3 with no recorded caller returns to the main menu COMEN01C")
  void pf3_noCaller_returnsToMainMenu() {
    CardDemoCommarea commarea = reentered();

    String next = service.processTranView(screen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo(TranViewService.LIT_MENU_PGM).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getFromProgram()).isEqualTo(TranViewService.PGM_NAME);
    assertThat(commarea.getFromTranId()).isEqualTo(TranViewService.TRAN_ID_NAME);
    assertThat(commarea.isPgmEnter()).isTrue();
    verify(transactionRepository, never()).findById(anyString());
  }

  @Test
  @DisplayName("PF3 with a recorded caller returns to that caller program")
  void pf3_withCaller_returnsToCaller() {
    CardDemoCommarea commarea = reentered();
    commarea.setFromProgram("COTRN00C");

    String next = service.processTranView(screen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COTRN00C");
    assertThat(commarea.getToProgram()).isEqualTo("COTRN00C");
  }

  @Test
  @DisplayName("PF4 clears all fields and redisplays (CLEAR-CURRENT-SCREEN)")
  void pf4_clearsAndRedisplays() {
    TranViewScreen screen = screenWithId(SAMPLE_TRAN_ID);
    screen.setTrnId(SAMPLE_TRAN_ID);
    screen.setErrMsg("previous message");

    String next = service.processTranView(screen, reentered(), CardWorkArea.Aid.PFK04);

    assertThat(next).isNull();
    assertThat(screen.getTrnIdIn()).isEmpty();
    assertThat(screen.getTrnId()).isEmpty();
    assertThat(screen.getErrMsg()).isEmpty();
    assertThat(screen.getTrnAmt()).isNull();
    verify(transactionRepository, never()).findById(anyString());
  }

  @Test
  @DisplayName("PF5 browses the transaction list COTRN00C")
  void pf5_browsesTransactionList() {
    CardDemoCommarea commarea = reentered();

    String next = service.processTranView(screen(), commarea, CardWorkArea.Aid.PFK05);

    assertThat(next).isEqualTo(TranViewService.LIT_TRAN_LIST_PGM).isEqualTo("COTRN00C");
    assertThat(commarea.getToProgram()).isEqualTo("COTRN00C");
    assertThat(commarea.getFromProgram()).isEqualTo(TranViewService.PGM_NAME);
    assertThat(commarea.isPgmEnter()).isTrue();
  }

  @ParameterizedTest
  @NullSource
  @EnumSource(
      value = CardWorkArea.Aid.class,
      names = {"ENTER", "PFK03", "PFK04", "PFK05"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("any other / unmapped key shows the shared invalid-key message and redisplays")
  void otherKey_showsInvalidKeyMessage(CardWorkArea.Aid aid) {
    TranViewScreen screen = screen();

    String next = service.processTranView(screen, reentered(), aid);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    verify(transactionRepository, never()).findById(anyString());
  }
}

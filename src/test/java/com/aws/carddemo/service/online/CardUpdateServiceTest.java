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
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.CardUpdateScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link CardUpdateService}, the online
 * card-update business-logic service migrated from the legacy CICS COBOL program {@code COCRDUPC}
 * (CICS transaction {@code CCUP}; behavioral spec {@code legacy/app/cbl/COCRDUPC.cbl}, card record
 * layout {@code legacy/app/cpy/CVACT02Y.cpy}, work area {@code legacy/app/cpy/CVCRD01Y.cpy},
 * COMMAREA {@code legacy/app/cpy/COCOM01Y.cpy}).
 *
 * <p>{@code CardUpdateService} has exactly one collaborator &mdash; the {@link CardRepository} that
 * replaces the COBOL {@code EXEC CICS READ/REWRITE DATASET('CARDDAT')}. It is mocked here so the
 * tests run with no Spring context, no {@code @MockBean}, and no database (Testcontainers and the
 * {@code @Transactional} rollback are integration-tested elsewhere). Every branch of the
 * confirm-then-save state machine is exercised in isolation.
 *
 * <p><strong>What is asserted (parity surface).</strong> {@link
 * CardUpdateService#processCardUpdate(CardUpdateScreen, CardDemoCommarea, CardWorkArea.Aid)} is a
 * pseudo-conversational turn: its observable effects are (1) the return value (the {@code EXEC CICS
 * XCTL} target program, or {@code null} to redisplay), (2) the navigation mutations it makes to the
 * {@link CardDemoCommarea}, (3) the cross-turn state flag and detail / message content it writes to
 * the {@link CardUpdateScreen}, and (4) the {@link CardRepository} interactions (read for display,
 * re-read for update, and rewrite) including their relative order.
 *
 * <p><strong>Control-flow parity</strong> (AAP &sect;0.7.1): the optimistic update is a
 * read-for-update &rarr; compare-to-snapshot &rarr; REWRITE cycle. {@link
 * #confirm_validEdits_savesCard_showsSuccess()} pins the COBOL ordering with an {@link InOrder}
 * that requires the re-read ({@code findById}) to precede the rewrite ({@code save}); {@link
 * #optimisticConflict_onReReadMismatch_redisplays()} pins the no-silent-lost-update rule (a
 * concurrently changed row is detected and {@code save} is never called).
 *
 * <p><strong>Byte-exact message parity</strong> (AAP &sect;0.6.5, &sect;0.7.3): the field-edit, the
 * (shared, UPPERCASE) account/card filter, the not-found, and the optimistic-conflict messages are
 * asserted against BOTH the production constant and the verbatim COBOL literal, so a single-byte
 * drift fails the test.
 *
 * <p><strong>FILE STATUS mapping</strong> (AAP &sect;0.6.4): a record-not-found read redisplays
 * with a message (never throws); an unexpected data-access failure on the display read maps to
 * {@link IoStatusException} (the abend-equivalent that rolls the transaction back).
 *
 * <p><strong>Decimal fidelity:</strong> N/A &mdash; the {@code card} record carries no monetary or
 * rate fields, so there is no {@link java.math.BigDecimal} arithmetic to verify on this screen.
 */
@ExtendWith(MockitoExtension.class)
class CardUpdateServiceTest {

  /** A valid 11-digit account filter ({@code ACCTSID}). */
  private static final String ACCT_ID = "12345678901";

  /** A valid 16-digit card filter / primary key ({@code CARDSID} &rarr; {@code card_num}). */
  private static final String CARD_NUM = "4111111111111111";

  /** Mocked collaborator: the migrated {@code CARDDAT} read / rewrite path. */
  @Mock private CardRepository cardRepository;

  /** Service under test; Mockito injects {@link #cardRepository} via the single-arg constructor. */
  @InjectMocks private CardUpdateService service;

  // ===============================================================================================
  // Fixtures / builders
  // ===============================================================================================

  /**
   * Builds a {@link Card} with the supplied identity and the mutable detail fields the update
   * screen edits (embossed name, active status, expiry) plus the preserved CVV.
   *
   * @param cardNum the 16-character primary key
   * @param acctId the owning account id
   * @param embossedName the embossed name (already in the stored case)
   * @param status the active-status flag ({@code "Y"}/{@code "N"})
   * @param expiry the {@code yyyy-MM-dd} expiry string
   * @param cvv the 3-character CVV (preserved across the update)
   * @return a fully populated card
   */
  private static Card card(
      String cardNum, long acctId, String embossedName, String status, String expiry, String cvv) {
    Card c = new Card();
    c.setCardNum(cardNum);
    c.setCardAcctId(acctId);
    c.setCardEmbossedName(embossedName);
    c.setCardActiveStatus(status);
    c.setCardExpiraionDate(expiry);
    c.setCardCvvCd(cvv);
    return c;
  }

  /** The canonical sample card: active, embossed {@code "JOHN DOE"}, expiring 2025-12-31. */
  private static Card sampleCard() {
    return card(CARD_NUM, 12_345_678_901L, "JOHN DOE", "Y", "2025-12-31", "123");
  }

  /** A brand-new, empty card-update screen contract. */
  private static CardUpdateScreen screen() {
    return new CardUpdateScreen();
  }

  /**
   * A re-entry COMMAREA: {@code CDEMO-PGM-REENTER} is set and the from-program is left blank so the
   * dispatch falls through to the {@code WHEN OTHER} branch that processes the operator's inputs
   * (rather than the first-entry "prompt for keys" branch).
   *
   * @return a re-entry COMMAREA
   */
  private static CardDemoCommarea reentered() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmReenter();
    return commarea;
  }

  /**
   * Primes the screen's cross-turn OLD snapshot ({@code CCUP-OLD-DETAILS}) to match {@link
   * #sampleCard()}, as if the card had already been fetched and displayed on a previous turn.
   *
   * @param s the screen whose OLD carrier fields are populated
   */
  private static void primeOldSnapshot(CardUpdateScreen s) {
    s.setOldCrdName("JOHN DOE");
    s.setOldCrdStcd("Y");
    s.setOldExpYear("2025");
    s.setOldExpMon("12");
    s.setOldExpDay("31");
    s.setOldCardCvvCd("123");
  }

  // ===============================================================================================
  // 0000-MAIN guard clauses (defensive Objects.requireNonNull)
  // ===============================================================================================

  @Test
  @DisplayName("null screen is rejected by the defensive guard")
  void nullScreen_throws() {
    assertThatThrownBy(() -> service.processCardUpdate(null, reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
  }

  @Test
  @DisplayName("null commarea is rejected by the defensive guard")
  void nullCommarea_throws() {
    assertThatThrownBy(() -> service.processCardUpdate(screen(), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("commarea must not be null");
  }

  // ===============================================================================================
  // 9000-READ-DATA: ENTER fetches and displays an existing card (SHOW-DETAILS)
  // ===============================================================================================

  @Test
  @DisplayName(
      "ENTER with valid keys reads the card, populates the screen, and prompts for changes")
  void enter_existingCard_loadsAndPromptsConfirm() {
    when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(sampleCard()));
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.DETAILS_NOT_FETCHED);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid(CARD_NUM);

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getUpdateState()).isEqualTo(CardUpdateService.SHOW_DETAILS);
    // Detail fields are populated from the (upper-cased) snapshot of the fetched row.
    assertThat(screen.getCrdName()).isEqualTo("JOHN DOE");
    assertThat(screen.getCrdStcd()).isEqualTo("Y");
    assertThat(screen.getExpMon()).isEqualTo("12");
    assertThat(screen.getExpYear()).isEqualTo("2025");
    assertThat(screen.getExpDay()).isEqualTo("31");
    // The "details shown, ready to edit" informational message (not an error).
    assertThat(screen.getInfoMsg()).isEqualTo(CardUpdateService.INFO_FOUND_CARDS);
    assertThat(screen.getInfoMsg()).isEqualTo("Details of selected card shown above");
    assertThat(screen.getErrMsg()).isEmpty();
    // The display read happened; nothing was saved yet.
    verify(cardRepository).findById(CARD_NUM);
    verify(cardRepository, never()).save(any(Card.class));
  }

  // ===============================================================================================
  // 9200-WRITE-PROCESSING: PF5 confirmation performs the optimistic rewrite
  // ===============================================================================================

  @Test
  @DisplayName("PF5 confirm with valid edits re-reads, rewrites the row, and shows success")
  void confirm_validEdits_savesCard_showsSuccess() {
    when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(sampleCard()));
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.CHANGES_OK_NOT_CONFIRMED);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid(CARD_NUM);
    primeOldSnapshot(screen);
    // NEW (edited) values differ from the OLD snapshot.
    screen.setCrdName("JANE DOE");
    screen.setCrdStcd("N");
    screen.setExpMon("06");
    screen.setExpYear("2030");
    screen.setExpDay("31");

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getUpdateState()).isEqualTo(CardUpdateService.CHANGES_OKAYED_AND_DONE);
    assertThat(screen.getInfoMsg()).isEqualTo(CardUpdateService.INFO_CONFIRM_UPDATE_SUCCESS);
    assertThat(screen.getInfoMsg()).isEqualTo("Changes committed to database");

    // Control-flow parity: the re-read-for-update MUST precede the rewrite.
    ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
    InOrder inOrder = inOrder(cardRepository);
    inOrder.verify(cardRepository).findById(CARD_NUM);
    inOrder.verify(cardRepository).save(saved.capture());
    inOrder.verifyNoMoreInteractions();

    Card written = saved.getValue();
    // Editable fields take the NEW values; keys / CVV / expiry-day are preserved.
    assertThat(written.getCardEmbossedName()).isEqualTo("JANE DOE");
    assertThat(written.getCardActiveStatus()).isEqualTo("N");
    assertThat(written.getCardExpiraionDate()).isEqualTo("2030-06-31");
    assertThat(written.getCardAcctId()).isEqualTo(12_345_678_901L);
    assertThat(written.getCardCvvCd()).isEqualTo("123");
    assertThat(written.getCardNum()).isEqualTo(CARD_NUM);
  }

  // ===============================================================================================
  // 1240-EDIT-CARDSTATUS / 1250-EDIT-EXPIRY-MON: field-level edits (redisplay, no write)
  // ===============================================================================================

  @Test
  @DisplayName("an invalid active status redisplays with the byte-exact field-edit message")
  void invalidActiveStatus_redisplays() {
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.SHOW_DETAILS);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid(CARD_NUM);
    primeOldSnapshot(screen);
    // Only the status changes, and to an invalid value; name / month / year stay valid.
    screen.setCrdName("JOHN DOE");
    screen.setCrdStcd("X");
    screen.setExpMon("12");
    screen.setExpYear("2025");
    screen.setExpDay("31");

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getUpdateState()).isEqualTo(CardUpdateService.CHANGES_NOT_OK);
    assertThat(screen.getErrMsg()).isEqualTo(CardUpdateService.MSG_CARD_STATUS_YES_NO);
    assertThat(screen.getErrMsg()).isEqualTo("Card Active Status must be Y or N");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("an out-of-range expiry month redisplays with the byte-exact field-edit message")
  void invalidExpiryMonth_redisplays() {
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.SHOW_DETAILS);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid(CARD_NUM);
    primeOldSnapshot(screen);
    // Name and status stay valid so the month edit is the first (and only) failure.
    screen.setCrdName("JOHN DOE");
    screen.setCrdStcd("Y");
    screen.setExpMon("13");
    screen.setExpYear("2025");
    screen.setExpDay("31");

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getUpdateState()).isEqualTo(CardUpdateService.CHANGES_NOT_OK);
    assertThat(screen.getErrMsg()).isEqualTo(CardUpdateService.MSG_EXPIRY_MONTH_NOT_VALID);
    assertThat(screen.getErrMsg()).isEqualTo("Card expiry month must be between 1 and 12");
    verifyNoInteractions(cardRepository);
  }

  // ===============================================================================================
  // 1210-EDIT-ACCOUNT / 1220-EDIT-CARD: search-key filter edits (no read attempted)
  // ===============================================================================================

  @Test
  @DisplayName("a non-11-digit account filter redisplays the UPPERCASE filter message, no read")
  void invalidAccountFilter_redisplays() {
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.DETAILS_NOT_FETCHED);
    screen.setAcctSid("123"); // not 11 digits
    screen.setCardSid(CARD_NUM);

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(CardUpdateService.MSG_ACCT_NOT_NUMERIC);
    assertThat(screen.getErrMsg())
        .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("a non-16-digit card filter redisplays the UPPERCASE filter message, no read")
  void invalidCardFilter_redisplays() {
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.DETAILS_NOT_FETCHED);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid("123"); // not 16 digits

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(CardUpdateService.MSG_CARD_NOT_NUMERIC);
    assertThat(screen.getErrMsg())
        .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  // ===============================================================================================
  // 9100-GETCARD-BYACCTCARD: NOTFND (FILE STATUS '23') redisplays rather than abends
  // ===============================================================================================

  @Test
  @DisplayName("a not-found card redisplays the not-found message and does not save")
  void cardNotFound_redisplaysNotFound() {
    when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.DETAILS_NOT_FETCHED);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid(CARD_NUM);

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getUpdateState()).isEqualTo(CardUpdateService.DETAILS_NOT_FETCHED);
    assertThat(screen.getErrMsg()).isEqualTo(CardUpdateService.MSG_DID_NOT_FIND_ACCTCARD);
    assertThat(screen.getErrMsg()).isEqualTo("Did not find cards for this search condition");
    verify(cardRepository).findById(CARD_NUM);
    verify(cardRepository, never()).save(any(Card.class));
  }

  // ===============================================================================================
  // 1200 no-change test: identical edits are reported, the row is not rewritten
  // ===============================================================================================

  @Test
  @DisplayName("submitting unchanged values reports 'no change' and does not touch the repository")
  void noChanges_redisplaysNoChangeMessage() {
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.SHOW_DETAILS);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid(CARD_NUM);
    primeOldSnapshot(screen);
    // NEW values are byte-identical to the OLD snapshot.
    screen.setCrdName("JOHN DOE");
    screen.setCrdStcd("Y");
    screen.setExpMon("12");
    screen.setExpYear("2025");
    screen.setExpDay("31");

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getUpdateState()).isEqualTo(CardUpdateService.SHOW_DETAILS);
    assertThat(screen.getErrMsg()).isEqualTo(CardUpdateService.MSG_NO_CHANGES_DETECTED);
    assertThat(screen.getErrMsg()).isEqualTo("No change detected with respect to values fetched.");
    verifyNoInteractions(cardRepository);
  }

  // ===============================================================================================
  // 9300-CHECK-CHANGE-IN-REC: concurrent change detected on re-read -> no silent lost update
  // ===============================================================================================

  @Test
  @DisplayName("PF5 confirm detects a concurrently changed row, redisplays, and never overwrites")
  void optimisticConflict_onReReadMismatch_redisplays() {
    // The row was changed by someone else since it was displayed (status flipped Y -> N).
    when(cardRepository.findById(CARD_NUM))
        .thenReturn(
            Optional.of(card(CARD_NUM, 12_345_678_901L, "JOHN DOE", "N", "2025-12-31", "123")));
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.CHANGES_OK_NOT_CONFIRMED);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid(CARD_NUM);
    primeOldSnapshot(screen); // OLD snapshot still has status "Y"
    screen.setCrdName("JANE DOE");
    screen.setCrdStcd("Y");
    screen.setExpMon("06");
    screen.setExpYear("2030");
    screen.setExpDay("31");

    String next = service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    // The COBOL maps a detected concurrent change back to SHOW-DETAILS for review.
    assertThat(screen.getUpdateState()).isEqualTo(CardUpdateService.SHOW_DETAILS);
    assertThat(screen.getErrMsg()).isEqualTo(CardUpdateService.MSG_DATA_WAS_CHANGED);
    assertThat(screen.getErrMsg()).isEqualTo("Record changed by some one else. Please review");
    verify(cardRepository).findById(CARD_NUM);
    verify(cardRepository, never()).save(any(Card.class));
  }

  // ===============================================================================================
  // 9100 WHEN OTHER (unexpected FILE STATUS) on the display read -> IoStatusException (abend)
  // ===============================================================================================

  @Test
  @DisplayName("an unexpected data-access failure on the display read maps to IoStatusException")
  void repositoryThrows_mapsToIoStatusException() {
    when(cardRepository.findById(CARD_NUM))
        .thenThrow(new DataAccessResourceFailureException("simulated CARDDAT outage"));
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.DETAILS_NOT_FETCHED);
    screen.setAcctSid(ACCT_ID);
    screen.setCardSid(CARD_NUM);

    assertThatThrownBy(() -> service.processCardUpdate(screen, reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(IoStatusException.class)
        .hasFieldOrPropertyWithValue("fileName", CardUpdateService.LIT_CARD_FILE_NAME)
        .hasFieldOrPropertyWithValue("fileName", "CARDDAT")
        .hasFieldOrPropertyWithValue("operation", "READ")
        .hasCauseInstanceOf(DataAccessResourceFailureException.class);
  }

  // ===============================================================================================
  // 0000-MAIN PF3/exit branch: XCTL back to the calling program (the card list)
  // ===============================================================================================

  @Test
  @DisplayName("PF3 transfers control back to the calling card-list program and records routing")
  void pfk03_returnsToCallingProgram() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setFromProgram(CardUpdateService.LIT_CARD_LIST_PGM); // "COCRDLIC"
    commarea.setFromTranId("CCLI");
    commarea.setLastMapset(CardUpdateService.LIT_CARD_LIST_MAPSET); // "COCRDLI"
    CardUpdateScreen screen = screen();
    screen.setUpdateState(CardUpdateService.SHOW_DETAILS);

    String next = service.processCardUpdate(screen, commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo(CardUpdateService.LIT_CARD_LIST_PGM).isEqualTo("COCRDLIC");
    assertThat(commarea.getToProgram()).isEqualTo("COCRDLIC");
    assertThat(commarea.getToTranId()).isEqualTo("CCLI");
    // This program records itself as the "from" context for the next screen.
    assertThat(commarea.getFromProgram())
        .isEqualTo(CardUpdateService.PGM_NAME)
        .isEqualTo("COCRDUPC");
    assertThat(commarea.getFromTranId()).isEqualTo(CardUpdateService.TRAN_ID).isEqualTo("CCUP");
    verifyNoInteractions(cardRepository);
  }
}

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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.CardViewScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardRepository;
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
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link CardViewService}, the online
 * card-detail / view business-logic service migrated from the legacy CICS COBOL program {@code
 * COCRDSLC} (CICS transaction {@code CCDL}; behavioral spec {@code legacy/app/cbl/COCRDSLC.cbl},
 * card layout {@code legacy/app/cpy/CVACT02Y.cpy}, work area {@code legacy/app/cpy/CVCRD01Y.cpy},
 * COMMAREA {@code legacy/app/cpy/COCOM01Y.cpy}).
 *
 * <p>{@code CardViewService} has exactly one collaborator &mdash; the {@link CardRepository} that
 * replaces the COBOL {@code EXEC CICS READ FILE('CARDDAT')}. It is mocked here so the tests run
 * with no Spring context and no database, exercising every {@code 0000-MAIN} dispatch branch, every
 * {@code 2200-EDIT-MAP-INPUTS} filter-validation branch, and every {@code 9100-GETCARD-BYACCTCARD}
 * FILE-STATUS branch in isolation.
 *
 * <p><strong>Control-flow parity</strong> (AAP &sect;0.7.1) is asserted both through the externally
 * observable effects of {@link CardViewService#processCardView(CardViewScreen, CardDemoCommarea,
 * CardWorkArea.Aid)} &mdash; its return value (the {@code EXEC CICS XCTL} target, or {@code null}
 * to redisplay), the {@link CardDemoCommarea} navigation mutations, and the field/message content
 * written to the {@link CardViewScreen} &mdash; and, for one representative read, through a Mockito
 * {@link InOrder} over a {@link CardViewScreen} spy that proves the filter edit precedes the
 * repository read which precedes screen population.
 *
 * <p><strong>Byte-exact message parity</strong> (agent prompt rule&nbsp;6): every on-screen message
 * is compared against the production constant <em>and</em> its byte-exact literal (including the
 * UPPERCASE filter messages and the three leading spaces of the found-card info line), so a
 * single-byte drift fails the test.
 *
 * <p><strong>FILE STATUS mapping</strong> (AAP &sect;0.6.4): a not-found read ({@code '23'} /
 * {@link Optional#empty()}) is asserted to redisplay with a message and never throw, whereas an
 * unexpected {@link org.springframework.dao.DataAccessException} is asserted to map to {@link
 * IoStatusException}.
 *
 * <p><strong>Decimal fidelity</strong> (AAP &sect;0.6.1): not applicable here &mdash; the {@link
 * Card} entity has no monetary/rate fields, so there is nothing to assert with {@code BigDecimal};
 * the requirement is satisfied trivially and, accordingly, no {@code float}/{@code double} appears
 * anywhere in these tests.
 *
 * <p><strong>AID handling.</strong> {@code COCRDSLC} honours only {@code ENTER} and {@code PF3};
 * every other key (and a {@code null} AID) is coerced to {@code ENTER} with <em>no</em> "invalid
 * key" message (the shared {@code util.Messages} constant is deliberately not used by this
 * program). That divergence from the generic invalid-key services is asserted explicitly.
 */
@ExtendWith(MockitoExtension.class)
class CardViewServiceTest {

  /** A representative valid 16-digit card number (the {@code CARDDAT} read key). */
  private static final String CARD_KEY = "0000000000000001";

  /** A representative valid 11-digit, non-zero account filter. */
  private static final String ACCT_FILTER = "00000000001";

  /** Mocked collaborator (the migrated {@code CARDDAT} read path). */
  @Mock private CardRepository cardRepository;

  /** Service under test; constructor-injected with the mocked repository. */
  @InjectMocks private CardViewService service;

  // ===== Fixtures / builders =====================================================================

  /**
   * Builds a fully populated {@link Card}. The entity intentionally has no monetary fields, so no
   * {@code BigDecimal} is involved (AAP &sect;0.6.1 is satisfied trivially).
   */
  private static Card card(String cardNum, long acctId) {
    Card card = new Card();
    card.setCardNum(cardNum);
    card.setCardAcctId(acctId);
    card.setCardCvvCd("123");
    card.setCardEmbossedName("JOHN Q PUBLIC");
    card.setCardExpiraionDate("2024-12-31"); // legacy "expiraion" spelling preserved on the getter
    card.setCardActiveStatus("Y");
    return card;
  }

  /** A blank screen contract carrying the supplied account / card filter values. */
  private static CardViewScreen screen(String acctFilter, String cardFilter) {
    CardViewScreen screen = new CardViewScreen();
    screen.setAcctSid(acctFilter);
    screen.setCardSid(cardFilter);
    return screen;
  }

  /** A first-entry COMMAREA: a fresh instance defaults to {@code CDEMO-PGM-ENTER} (context 0). */
  private static CardDemoCommarea firstEntry() {
    return new CardDemoCommarea();
  }

  /** A re-entry COMMAREA: {@code CDEMO-PGM-REENTER} so {@code 0000-MAIN} validates then reads. */
  private static CardDemoCommarea reentered() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmReenter();
    return commarea;
  }

  // ===== 9100-GETCARD-BYACCTCARD: read outcomes ==================================================

  @Test
  @DisplayName("re-entry with valid filters and a found card populates the detail and redisplays")
  void validCardNumber_found_populatesScreenAndRedisplays() {
    when(cardRepository.findById(CARD_KEY)).thenReturn(Optional.of(card(CARD_KEY, 12345678901L)));
    CardViewScreen screen = screen(ACCT_FILTER, CARD_KEY);
    CardDemoCommarea commarea = reentered();

    String next = service.processCardView(screen, commarea, CardWorkArea.Aid.ENTER);

    // Read-only display: redisplay the same screen (null XCTL target), never an exception.
    assertThat(next).isNull();
    // 1200-SETUP-SCREEN-VARS found-card population (COCRDSLC L474-484).
    assertThat(screen.getAcctSid()).isEqualTo("12345678901"); // formatZoned(card acct id, 11)
    assertThat(screen.getCardSid()).isEqualTo(CARD_KEY); // the 16-char card key
    assertThat(screen.getCrdName()).isEqualTo("JOHN Q PUBLIC");
    assertThat(screen.getCrdStcd()).isEqualTo("Y");
    assertThat(screen.getExpYear()).isEqualTo("2024");
    assertThat(screen.getExpMon()).isEqualTo("12");
    // FOUND-CARDS-FOR-ACCOUNT info line (byte-exact, three leading spaces) — COCRDSLC L129-130.
    assertThat(screen.getInfoMsg())
        .isEqualTo(CardViewService.MSG_FOUND_CARDS)
        .isEqualTo("   Displaying requested details");
    assertThat(screen.getErrMsg()).isEmpty();
    // 1400-SEND-SCREEN re-arms the pseudo-conversational flag (SET CDEMO-PGM-REENTER, L567).
    assertThat(commarea.isPgmReenter()).isTrue();
    // The single live read (9100-GETCARD-BYACCTCARD), keyed by card number only.
    verify(cardRepository).findById(CARD_KEY);
  }

  @Test
  @DisplayName("re-entry with a not-found card shows the byte-exact not-found message; no throw")
  void cardNotFound_redisplaysNotFound() {
    when(cardRepository.findById(CARD_KEY)).thenReturn(Optional.empty());
    CardViewScreen screen = screen(ACCT_FILTER, CARD_KEY);

    String next = service.processCardView(screen, reentered(), CardWorkArea.Aid.ENTER);

    // FILE STATUS '23' (NOTFND) is a user-facing condition: redisplay, never thrown (AAP §0.6.4).
    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(CardViewService.MSG_DID_NOT_FIND_CARD)
        .isEqualTo("Did not find cards for this search condition");
    verify(cardRepository).findById(CARD_KEY);
  }

  @Test
  @DisplayName("a found card with a null expiry yields empty month and year fields")
  void foundCard_nullExpiry_yieldsEmptyExpiryFields() {
    Card card = card(CARD_KEY, 12345678901L);
    card.setCardExpiraionDate(null);
    when(cardRepository.findById(CARD_KEY)).thenReturn(Optional.of(card));
    CardViewScreen screen = screen(ACCT_FILTER, CARD_KEY);

    service.processCardView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(screen.getExpYear()).isEmpty();
    assertThat(screen.getExpMon()).isEmpty();
    // The remaining detail is still populated from the found card.
    assertThat(screen.getCrdName()).isEqualTo("JOHN Q PUBLIC");
  }

  // ===== 2200-EDIT-MAP-INPUTS: filter validation (no read) =======================================

  @Test
  @DisplayName("a non-numeric account filter shows the byte-exact account-filter message; no read")
  void invalidAccountFilter_nonNumeric_redisplaysAccountFilterMessage() {
    // Account supplied but not an 11-digit number; the card is supplied and valid so the account
    // message is the one that latches (first-message-wins) and the cross-field rule does not fire.
    CardViewScreen screen = screen("ABC", CARD_KEY);

    String next = service.processCardView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(CardViewService.MSG_ACCT_FILTER_INVALID)
        .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("a wrong-length card filter shows the byte-exact card-filter message; no read")
  void invalidCardFilter_wrongLength_redisplaysCardFilterMessage() {
    // Account valid (so no account message latches first); card supplied but not 16 digits.
    CardViewScreen screen = screen(ACCT_FILTER, "12AB");

    String next = service.processCardView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(CardViewService.MSG_CARD_FILTER_INVALID)
        .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("both filters blank (sentinels '*' / spaces) shows NO-SEARCH-CRITERIA; no read")
  void bothFiltersBlank_showsNoSearchCriteria() {
    // '*' and SPACES both collapse to LOW-VALUES (2200-EDIT-MAP-INPUTS L615-627); the cross-field
    // rule (L636-640) overrides the per-field prompt with NO-SEARCH-CRITERIA-RECEIVED.
    CardViewScreen screen = screen("*", "   ");

    String next = service.processCardView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(CardViewService.MSG_NO_SEARCH_CRITERIA)
        .isEqualTo("No input received");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName(
      "an all-zero account filter is treated as not supplied (Account number not provided)")
  void accountFilterAllZeros_showsPromptForAcct() {
    // CC-ACCT-ID-N = ZEROS path (2210-EDIT-ACCOUNT L651-661); the card is valid so only the account
    // prompt latches and the cross-field both-blank rule does not fire.
    CardViewScreen screen = screen("00000000000", "0000000000000002");

    String next = service.processCardView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(CardViewService.MSG_PROMPT_FOR_ACCT)
        .isEqualTo("Account number not provided");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("a blank card filter (account valid) is treated as not supplied (Card number ...)")
  void cardFilterBlank_showsPromptForCard() {
    CardViewScreen screen = screen(ACCT_FILTER, "");

    String next = service.processCardView(screen, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(CardViewService.MSG_PROMPT_FOR_CARD)
        .isEqualTo("Card number not provided");
    verifyNoInteractions(cardRepository);
  }

  // ===== 9100-GETCARD-BYACCTCARD: WHEN OTHER (unexpected I/O)
  // =====================================

  @Test
  @DisplayName("a repository DataAccessException maps to IoStatusException (WHEN OTHER, §0.6.4)")
  void repositoryThrows_mapsToIoStatusException() {
    when(cardRepository.findById(CARD_KEY))
        .thenThrow(new DataAccessResourceFailureException("boom"));
    CardViewScreen screen = screen(ACCT_FILTER, CARD_KEY);

    assertThatThrownBy(() -> service.processCardView(screen, reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(IoStatusException.class)
        .hasCauseInstanceOf(DataAccessResourceFailureException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName())
                  .isEqualTo(CardViewService.LIT_CARD_FILE_NAME)
                  .isEqualTo("CARDDAT");
              assertThat(io.getOperation()).isEqualTo("READ");
            });
    verify(cardRepository).findById(CARD_KEY);
  }

  // ===== 0000-MAIN: navigation / dispatch ========================================================

  @Test
  @DisplayName(
      "PF3 with no recorded caller exits to the main menu COMEN01C and stamps the COMMAREA")
  void pfk03_noCaller_returnsToMainMenu() {
    CardDemoCommarea commarea = reentered();

    String next =
        service.processCardView(screen(ACCT_FILTER, CARD_KEY), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo(CardViewService.LIT_MENU_PGM).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getToTranId()).isEqualTo(CardViewService.LIT_MENU_TRAN).isEqualTo("CM00");
    // Control is recorded as returning FROM this program / transaction (L323-324).
    assertThat(commarea.getFromProgram()).isEqualTo(CardViewService.PGM_NAME).isEqualTo("COCRDSLC");
    assertThat(commarea.getFromTranId()).isEqualTo(CardViewService.TRAN_ID).isEqualTo("CCDL");
    // Role reset to standard user, caller re-entered, last map / mapset recorded (L326-329).
    assertThat(commarea.isUser()).isTrue();
    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(commarea.getLastMapset())
        .isEqualTo(CardViewService.LIT_THIS_MAPSET)
        .isEqualTo("COCRDSL");
    assertThat(commarea.getLastMap()).isEqualTo(CardViewService.LIT_THIS_MAP).isEqualTo("CCRDSLA");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("PF3 with a recorded caller exits to that caller program / transaction")
  void pfk03_withCaller_returnsToCaller() {
    CardDemoCommarea commarea = reentered();
    commarea.setFromProgram("COCRDLIC");
    commarea.setFromTranId("CCLI");

    String next =
        service.processCardView(screen(ACCT_FILTER, CARD_KEY), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COCRDLIC");
    assertThat(commarea.getToProgram()).isEqualTo("COCRDLIC");
    assertThat(commarea.getToTranId()).isEqualTo("CCLI");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("first entry from the card-list program reads the carried card immediately")
  void firstEntryFromCardList_readsImmediately() {
    when(cardRepository.findById(CARD_KEY)).thenReturn(Optional.of(card(CARD_KEY, 12345678901L)));
    CardDemoCommarea commarea = firstEntry(); // CDEMO-PGM-ENTER
    commarea.setFromProgram(CardViewService.LIT_CARD_LIST_PGM); // COCRDLIC
    commarea.setAcctId(1L);
    commarea.setCardNum(1L); // formatZoned(1, 16) -> CARD_KEY

    CardViewScreen screen = screen(null, null);

    String next = service.processCardView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getCrdName()).isEqualTo("JOHN Q PUBLIC");
    assertThat(screen.getInfoMsg())
        .isEqualTo(CardViewService.MSG_FOUND_CARDS)
        .isEqualTo("   Displaying requested details");
    verify(cardRepository).findById(CARD_KEY);
  }

  @Test
  @DisplayName("first entry from any other caller paints the empty search screen without reading")
  void firstEntry_notFromCardList_paintsSearchScreen() {
    CardDemoCommarea commarea = firstEntry(); // CDEMO-PGM-ENTER, no from-program
    CardViewScreen screen = screen(null, null);

    String next = service.processCardView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // 1200 default info prompt (WS-PROMPT-FOR-INPUT, L131-132).
    assertThat(screen.getInfoMsg())
        .isEqualTo(CardViewService.MSG_PROMPT_FOR_INPUT)
        .isEqualTo("Please enter Account and Card Number");
    assertThat(screen.getErrMsg()).isEmpty();
    assertThat(commarea.isPgmReenter()).isTrue();
    verifyNoInteractions(cardRepository);
  }

  @ParameterizedTest
  @NullSource
  @EnumSource(
      value = CardWorkArea.Aid.class,
      names = {"ENTER", "PFK03"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName(
      "any unmapped key (or a null AID) is coerced to ENTER: paint, no exit, no invalid-key")
  void unmappedOrNullAid_coercedToEnter_paintsSearchScreen(CardWorkArea.Aid aid) {
    // COCRDSLC L291-299 coerces every key except ENTER / PF3 to ENTER and shows NO invalid-key
    // message (the shared util.Messages.MSG_INVALID_KEY is deliberately not used by this program).
    CardDemoCommarea commarea = firstEntry();
    CardViewScreen screen = screen(null, null);

    String next = service.processCardView(screen, commarea, aid);

    assertThat(next).isNull(); // not an exit — the search screen is repainted
    assertThat(screen.getErrMsg()).isEmpty(); // no invalid-key message
    assertThat(screen.getInfoMsg()).isEqualTo(CardViewService.MSG_PROMPT_FOR_INPUT);
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName(
      "an unexpected pseudo-conversational context shows UNEXPECTED DATA SCENARIO; no read")
  void unexpectedContext_showsUnexpectedDataMessage() {
    CardDemoCommarea commarea = firstEntry();
    commarea.setPgmContext(2); // neither CDEMO-PGM-ENTER (0) nor CDEMO-PGM-REENTER (1)
    CardViewScreen screen = screen(ACCT_FILTER, CARD_KEY);

    String next = service.processCardView(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(CardViewService.MSG_UNEXPECTED_DATA)
        .isEqualTo("UNEXPECTED DATA SCENARIO");
    verifyNoInteractions(cardRepository);
  }

  // ===== Control-flow (PERFORM-order) parity via InOrder =========================================

  @Test
  @DisplayName("control flow: filter edit precedes the read, which precedes screen population")
  void controlFlow_validationThenReadThenPopulate() {
    Card card = card(CARD_KEY, 12345678901L);
    when(cardRepository.findById(CARD_KEY)).thenReturn(Optional.of(card));
    CardViewScreen screenSpy = spy(new CardViewScreen());
    screenSpy.setAcctSid(ACCT_FILTER);
    screenSpy.setCardSid(CARD_KEY);

    String next = service.processCardView(screenSpy, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // PERFORM-order parity (AAP §0.7.1): 2200-EDIT-MAP-INPUTS (reads the CARDSID filter) runs
    // before 9100-GETCARD-BYACCTCARD (findById), which runs before the 1200 detail population.
    InOrder ordered = inOrder(screenSpy, cardRepository);
    ordered.verify(screenSpy).getCardSid();
    ordered.verify(cardRepository).findById(CARD_KEY);
    ordered.verify(screenSpy).setCrdName("JOHN Q PUBLIC");
  }

  // ===== Defensive guards (Objects.requireNonNull) ===============================================

  @Test
  @DisplayName("a null screen is rejected by the defensive Objects.requireNonNull guard")
  void nullScreen_throws() {
    assertThatThrownBy(() -> service.processCardView(null, firstEntry(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("a null COMMAREA is rejected by the defensive Objects.requireNonNull guard")
  void nullCommarea_throws() {
    assertThatThrownBy(
            () ->
                service.processCardView(
                    screen(ACCT_FILTER, CARD_KEY), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("commarea must not be null");
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("the constructor rejects a null repository")
  void constructor_nullRepository_throws() {
    assertThatThrownBy(() -> new CardViewService(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("cardRepository must not be null");
  }
}

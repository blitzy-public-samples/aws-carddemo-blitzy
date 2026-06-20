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

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.CardListScreen;
import com.aws.carddemo.dto.screen.CardListScreen.CardListRow;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link CardListService}, the online
 * card-list / browse service migrated from the legacy CICS COBOL program {@code COCRDLIC} (CICS
 * transaction {@code CCLI}; behavioral spec {@code legacy/app/cbl/COCRDLIC.cbl}).
 *
 * <p>The service's sole collaborator is {@link CardRepository}, which is mocked so the
 * ascending-by-card-number browse source is fully controlled and deterministic. No Spring context,
 * {@code @MockBean}, database or Testcontainers is used — this is a fast, isolated unit test.
 *
 * <p>Control-flow parity (Agent Action Plan &sect;0.6.5, &sect;0.7.1) is asserted through the
 * externally observable effects of {@link CardListService#processCardList(CardListScreen,
 * CardDemoCommarea, CardWorkArea.Aid)}: its return value (the {@code XCTL} target program, or
 * {@code null} to redisplay the list on the same screen), the mutations it makes to the {@link
 * CardDemoCommarea} navigation state and the {@link CardListScreen} (rows, page indicator,
 * messages), and the byte-exact message literals it writes. The tests cover every branch: first
 * page, PF8 forward, PF8 bottom boundary, PF7 top boundary, {@code S} / {@code U} row selection,
 * invalid selection, invalid account filter, invalid card filter, an unexpected repository failure,
 * and PF3 exit.
 *
 * <p><strong>Paging.</strong> Mirroring the legacy pseudo-conversation (and the migrated service,
 * which reconstructs its browse cursor from the round-tripped screen), the paging tests drive
 * multiple turns over the <em>same</em> {@link CardListScreen} and {@link CardDemoCommarea}
 * instances so the page-to-page navigation state is carried exactly as the controller layer would
 * preserve it.
 */
@ExtendWith(MockitoExtension.class)
class CardListServiceTest {

  /** The card store, mocked so the browse candidate set is deterministic. */
  @Mock private CardRepository cardRepository;

  /** Service under test, with the mocked {@link CardRepository} injected via its constructor. */
  @InjectMocks private CardListService service;

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  /**
   * Zero-pads an integer to the 16-character card-number key width ({@code CARD-NUM PIC X(16)}).
   */
  private static String num(int n) {
    return String.format("%016d", n);
  }

  /** Zero-pads to the 11-character account-number width ({@code ACCTNO PIC X(11)}). */
  private static String acctStr(int n) {
    return String.format("%011d", 1000L + n);
  }

  /** Builds a card with a deterministic, parity-friendly layout (account id {@code 1000 + n}). */
  private static Card card(int n) {
    Card c = new Card();
    c.setCardNum(num(n));
    c.setCardAcctId(1000L + n);
    c.setCardCvvCd("123");
    c.setCardEmbossedName("CARDHOLDER NUMBER " + n);
    c.setCardExpiraionDate("2025-12-31");
    c.setCardActiveStatus("Y");
    return c;
  }

  /** Builds an ascending-by-card-number list of {@code count} cards (ids {@code 1..count}). */
  private static List<Card> cards(int count) {
    List<Card> list = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      list.add(card(i));
    }
    return list;
  }

  /** A fresh, empty Card List screen (the seven-row list is initialized empty). */
  private static CardListScreen screen() {
    return new CardListScreen();
  }

  /**
   * A commarea for a signed-on user on first entry to this program: {@code fromProgram} is unset,
   * so the service treats the turn as a cold start (EIBCALEN = 0) and paints page one from the top.
   */
  private static CardDemoCommarea firstEntry() {
    CardDemoCommarea c = new CardDemoCommarea();
    c.setUserId("USER0001");
    return c;
  }

  /**
   * A commarea for a re-entry into this same program: {@code fromProgram = "COCRDLIC"} so the
   * service receives and edits the screen inputs and restores the browse cursor (mirroring a CICS
   * pseudo-conversational re-entry from the same transaction).
   */
  private static CardDemoCommarea reentered() {
    CardDemoCommarea c = new CardDemoCommarea();
    c.setUserId("USER0001");
    c.setFromProgram("COCRDLIC");
    return c;
  }

  /** Builds a re-entry screen carrying a one-row selection (sel + account/card keys on a row). */
  private static CardListScreen screenWithSelection(
      int rowIndex, String sel, String acctNo, String crdNum) {
    CardListScreen s = screen();
    List<CardListRow> rows = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      rows.add(new CardListRow());
    }
    rows.get(rowIndex).setCrdSel(sel);
    rows.get(rowIndex).setAcctNo(acctNo);
    rows.get(rowIndex).setCrdNum(crdNum);
    s.setRows(rows);
    return s;
  }

  // ---------------------------------------------------------------------------------------------
  // First page
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("first entry paints a full seven-row page mapped in card-number order")
  void firstPage_full7Rows_populatesGrid() {
    when(cardRepository.findAll()).thenReturn(cards(7));
    CardListScreen s = screen();

    String next = service.processCardList(s, firstEntry(), CardWorkArea.Aid.ENTER);

    // A list/browse turn redisplays on the same screen (no XCTL).
    assertThat(next).isNull();

    // Exactly seven rows, each mapped from the candidate set in ascending card-number order.
    List<CardListRow> rows = s.getRows();
    assertThat(rows).hasSize(7);
    for (int i = 0; i < 7; i++) {
      assertThat(rows.get(i).getCrdNum()).isEqualTo(num(i + 1));
      assertThat(rows.get(i).getAcctNo()).isEqualTo(acctStr(i + 1));
      assertThat(rows.get(i).getCrdSts()).isEqualTo("Y");
    }
    assertThat(s.getPageNo()).isEqualTo("1");

    // Control-flow parity: the browse query is invoked exactly once, and nothing else.
    InOrder inOrder = inOrder(cardRepository);
    inOrder.verify(cardRepository).findAll();
    inOrder.verifyNoMoreInteractions();
  }

  // ---------------------------------------------------------------------------------------------
  // Forward paging (PF8)
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PF8 advances to the next page using the forward keyset")
  void forwardPaging_pf8_advancesPage() {
    when(cardRepository.findAll()).thenReturn(cards(16));
    CardListScreen s = screen();
    CardDemoCommarea commarea = firstEntry();

    // Turn 1: paint page one (cards 1..7).
    service.processCardList(s, commarea, CardWorkArea.Aid.ENTER);
    // Turn 2: page forward from the last displayed key.
    String next = service.processCardList(s, commarea, CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getPageNo()).isEqualTo("2");
    assertThat(s.getRows().get(0).getCrdNum()).isEqualTo(num(8));
    assertThat(s.getRows().get(6).getCrdNum()).isEqualTo(num(14));
  }

  @Test
  @DisplayName("PF8 again at the last page shows the byte-exact 'no more pages' boundary message")
  void forwardPaging_pf8_atLastPage_showsBottomMessage() {
    when(cardRepository.findAll()).thenReturn(cards(7)); // exactly one page
    CardListScreen s = screen();
    CardDemoCommarea commarea = firstEntry();

    // Turn 1: page one is also the last page; the end-of-data latch is raised on the screen.
    service.processCardList(s, commarea, CardWorkArea.Aid.ENTER);
    assertThat(s.getErrMsg()).isEqualTo("NO MORE RECORDS TO SHOW");

    // Turn 2: PF8 at the last page reports the page-down boundary and does not advance.
    String next = service.processCardList(s, commarea, CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("NO MORE PAGES TO DISPLAY");
    assertThat(s.getPageNo()).isEqualTo("1");
  }

  // ---------------------------------------------------------------------------------------------
  // Backward paging (PF7)
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PF7 on the first page shows the byte-exact 'no previous pages' boundary message")
  void backwardPaging_pf7_atFirstPage_showsTopMessage() {
    when(cardRepository.findAll()).thenReturn(cards(16));
    CardListScreen s = screen();
    CardDemoCommarea commarea = firstEntry();

    // Turn 1: page one.
    service.processCardList(s, commarea, CardWorkArea.Aid.ENTER);
    // Turn 2: PF7 at the top stays on page one and reports the page-up boundary.
    String next = service.processCardList(s, commarea, CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
    assertThat(s.getPageNo()).isEqualTo("1");
  }

  // ---------------------------------------------------------------------------------------------
  // Row selection routing (S -> card view, U -> card update)
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("row selected with 'S' forwards to the card-view program with the selected key")
  void selectRow_S_forwardsToCardView() {
    CardListScreen s = screenWithSelection(2, "S", acctStr(3), num(3));
    CardDemoCommarea commarea = reentered();

    String next = service.processCardList(s, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COCRDSLC");
    assertThat(commarea.getToProgram()).isEqualTo("COCRDSLC");
    assertThat(commarea.getToTranId()).isEqualTo("CCDL");
    assertThat(commarea.getFromProgram()).isEqualTo("COCRDLIC");
    assertThat(commarea.getFromTranId()).isEqualTo("CCLI");
    assertThat(commarea.isPgmEnter()).isTrue();
    // The selected row's keys are propagated so the downstream screen can read the record.
    assertThat(commarea.getCardNum()).isEqualTo(3L);
    assertThat(commarea.getAcctId()).isEqualTo(1003L);

    // A selection transfers before any browse, so the card store is never read.
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("row selected with 'U' forwards to the card-update program with the selected key")
  void selectRow_U_forwardsToCardUpdate() {
    CardListScreen s = screenWithSelection(0, "U", acctStr(5), num(5));
    CardDemoCommarea commarea = reentered();

    String next = service.processCardList(s, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COCRDUPC");
    assertThat(commarea.getToProgram()).isEqualTo("COCRDUPC");
    assertThat(commarea.getToTranId()).isEqualTo("CCUP");
    assertThat(commarea.getCardNum()).isEqualTo(5L);
    assertThat(commarea.getAcctId()).isEqualTo(1005L);

    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("an invalid selection code redisplays the list with the 'invalid action' message")
  void invalidSelectionCode_redisplaysInvalidSelection() {
    when(cardRepository.findAll()).thenReturn(cards(7));
    CardListScreen s = screenWithSelection(0, "X", acctStr(1), num(1));

    String next = service.processCardList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("INVALID ACTION CODE");
    // The list is still refreshed below the error message.
    assertThat(s.getRows().get(0).getCrdNum()).isEqualTo(num(1));
  }

  // ---------------------------------------------------------------------------------------------
  // Filter validation (account / card) — byte-exact UPPERCASE literals
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a non-numeric account filter redisplays with the byte-exact account-filter message")
  void invalidAccountFilter_redisplays() {
    CardListScreen s = screen();
    s.setAcctSid("ABC");

    String next = service.processCardList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    // A filter validation failure suppresses the browse entirely (no read is issued).
    verifyNoInteractions(cardRepository);
  }

  @Test
  @DisplayName("a non-numeric card filter redisplays with the byte-exact card-filter message")
  void invalidCardFilter_redisplays() {
    CardListScreen s = screen();
    s.setCardSid("12AB");

    String next = service.processCardList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    verifyNoInteractions(cardRepository);
  }

  // ---------------------------------------------------------------------------------------------
  // FILE STATUS 'other' -> IoStatusException
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an unexpected data-access failure is escalated as IoStatusException (CARDDAT)")
  void repositoryThrows_mapsToIoStatusException() {
    when(cardRepository.findAll())
        .thenThrow(new DataAccessResourceFailureException("simulated outage"));

    assertThatThrownBy(() -> service.processCardList(screen(), reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(IoStatusException.class)
        .hasMessageContaining("CARDDAT");
  }

  // ---------------------------------------------------------------------------------------------
  // PF3 exit
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PF3 returns to the main menu without reading the card store")
  void pfk03_returnsToCallingProgram() {
    CardDemoCommarea commarea = reentered();

    String next = service.processCardList(screen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getToTranId()).isEqualTo("CM00");
    assertThat(commarea.getFromProgram()).isEqualTo("COCRDLIC");
    assertThat(commarea.getFromTranId()).isEqualTo("CCLI");
    assertThat(commarea.isPgmEnter()).isTrue();
    verifyNoInteractions(cardRepository);
  }
}

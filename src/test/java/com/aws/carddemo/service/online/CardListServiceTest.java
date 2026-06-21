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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

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

  /**
   * Right-pads to the 16-character card-number key width, mirroring the service's {@code pad16}.
   */
  private static String pad16(String value) {
    return String.format("%-16s", value == null ? "" : value);
  }

  /**
   * Installs keyset-query stubs on the mocked repository that faithfully simulate the bounded
   * PostgreSQL keyset/range browse the service now issues, over an in-memory, ascending-by-card
   * list. This replaces the legacy {@code findAll()} full-table stub: each paging turn fetches only
   * one screen page (plus the read-ahead "peek" row), exactly as the {@code FETCH FIRST :n ROWS
   * ONLY} repository queries behave at runtime, so the page-content / navigation / boundary-message
   * parity assertions hold without a database.
   *
   * <p>The stubs are {@code lenient} because no single test exercises every browse direction (e.g.
   * a forward-paging test never issues the backward {@code READPREV} query), and the strict {@link
   * MockitoExtension} would otherwise reject the unused directions as unnecessary stubbing.
   *
   * @param all the deterministic card set the browse reads over (any order; sorted here ascending)
   */
  private void stubKeyset(List<Card> all) {
    List<Card> sorted = new ArrayList<>(all);
    sorted.sort(Comparator.comparing(c -> pad16(c.getCardNum())));
    // STARTBR GTEQ + READNEXT (refresh / page-up boundary): card_num >= key, ascending, limited.
    lenient()
        .when(
            cardRepository.findByCardNumGreaterThanEqualOrderByCardNumAsc(
                anyString(), any(Pageable.class)))
        .thenAnswer(inv -> forwardSlice(sorted, inv.getArgument(0), true, inv.getArgument(1)));
    // READNEXT past the previous page's last key (PF8 page-down): card_num > key, ascending.
    lenient()
        .when(
            cardRepository.findByCardNumGreaterThanOrderByCardNumAsc(
                anyString(), any(Pageable.class)))
        .thenAnswer(inv -> forwardSlice(sorted, inv.getArgument(0), false, inv.getArgument(1)));
    // READPREV page-up (PF7): card_num < key, descending (closest-below first), limited.
    lenient()
        .when(
            cardRepository.findByCardNumLessThanOrderByCardNumDesc(
                anyString(), any(Pageable.class)))
        .thenAnswer(inv -> backwardSlice(sorted, inv.getArgument(0), inv.getArgument(1)));
    // CA-NEXT-PAGE-EXISTS recomputation (PF8 guard): does any card_num > key exist?
    lenient()
        .when(cardRepository.existsByCardNumGreaterThan(anyString()))
        .thenAnswer(
            inv ->
                sorted.stream()
                    .anyMatch(c -> pad16(c.getCardNum()).compareTo(inv.getArgument(0)) > 0));
  }

  /** Ascending slice with {@code card_num >= key} (inclusive) or {@code > key} (exclusive). */
  private static List<Card> forwardSlice(
      List<Card> sorted, String key, boolean inclusive, Pageable page) {
    List<Card> out = new ArrayList<>();
    for (Card c : sorted) {
      int cmp = pad16(c.getCardNum()).compareTo(key);
      if (inclusive ? cmp >= 0 : cmp > 0) {
        out.add(c);
        if (out.size() >= page.getPageSize()) {
          break;
        }
      }
    }
    return out;
  }

  /**
   * Descending slice (closest-below first) with {@code card_num < key}, limited to the page size.
   */
  private static List<Card> backwardSlice(List<Card> sorted, String key, Pageable page) {
    List<Card> out = new ArrayList<>();
    for (int i = sorted.size() - 1; i >= 0; i--) {
      Card c = sorted.get(i);
      if (pad16(c.getCardNum()).compareTo(key) < 0) {
        out.add(c);
        if (out.size() >= page.getPageSize()) {
          break;
        }
      }
    }
    return out;
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
    stubKeyset(cards(7));
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

    // Control-flow parity: the first page issues exactly one bounded forward keyset read (STARTBR
    // GTEQ from the top, key = ""), and nothing else — no full-table scan, no extra probe.
    InOrder inOrder = inOrder(cardRepository);
    inOrder
        .verify(cardRepository)
        .findByCardNumGreaterThanEqualOrderByCardNumAsc(eq(""), any(Pageable.class));
    inOrder.verifyNoMoreInteractions();
  }

  // ---------------------------------------------------------------------------------------------
  // Forward paging (PF8)
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PF8 advances to the next page using the forward keyset")
  void forwardPaging_pf8_advancesPage() {
    stubKeyset(cards(16));
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
    stubKeyset(cards(7)); // exactly one page
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
    stubKeyset(cards(16));
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

  @Test
  @DisplayName("PF7 after PF8 walks back to the prior page via the backward (READPREV) keyset")
  void backwardPaging_pf7_afterPf8_returnsToPriorPage() {
    stubKeyset(cards(16));
    CardListScreen s = screen();
    CardDemoCommarea commarea = firstEntry();

    // Turn 1: page one (cards 1..7).
    service.processCardList(s, commarea, CardWorkArea.Aid.ENTER);
    // Turn 2: PF8 forward to page two (cards 8..14).
    service.processCardList(s, commarea, CardWorkArea.Aid.PFK08);
    assertThat(s.getPageNo()).isEqualTo("2");
    assertThat(s.getRows().get(0).getCrdNum()).isEqualTo(num(8));

    // Turn 3: PF7 page-up. The READPREV keyset returns the seven keys immediately below the
    // current first key (8) in descending order; the service reverses them to ascending so page
    // one (cards 1..7) is reconstructed byte-faithfully.
    String next = service.processCardList(s, commarea, CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getPageNo()).isEqualTo("1");
    assertThat(s.getRows().get(0).getCrdNum()).isEqualTo(num(1));
    assertThat(s.getRows().get(6).getCrdNum()).isEqualTo(num(7));
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
    stubKeyset(cards(7));
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
    when(cardRepository.findByCardNumGreaterThanEqualOrderByCardNumAsc(
            anyString(), any(Pageable.class)))
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

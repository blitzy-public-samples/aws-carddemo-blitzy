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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.TranListScreen;
import com.aws.carddemo.dto.screen.TranListScreen.TranListRow;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.Messages;
import java.math.BigDecimal;
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
import org.springframework.data.domain.Pageable;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link TranListService}, the online
 * transaction-list / browse service migrated from the legacy CICS COBOL program {@code COTRN00C}
 * (CICS transaction {@code CT00}; behavioral spec {@code legacy/app/cbl/COTRN00C.cbl}).
 *
 * <p>The service's only collaborator, {@link TransactionRepository}, is mocked so that the
 * ascending-by-transaction-id browse source is fully controlled and deterministic; no Spring
 * context, database, or Testcontainers is required. Control-flow parity (Agent Action Plan
 * &sect;0.6.5, &sect;0.7.1) is asserted three ways:
 *
 * <ul>
 *   <li><b>Externally observable effects</b> of {@link TranListService#processTranList(
 *       TranListScreen, CardDemoCommarea, CardWorkArea.Aid)}: its return value (the {@code XCTL}
 *       target program, or {@code null} to redisplay), the mutations it makes to the {@link
 *       CardDemoCommarea} navigation state and to the {@link TranListScreen} (rows, page indicator,
 *       keyset cursors), and the byte-exact message literals it writes.
 *   <li><b>Interaction ordering</b> via Mockito {@link InOrder}: the single VSAM-browse-equivalent
 *       query is verified to occur in the expected sequence (validation precedes the browse, and a
 *       forward page after a first page issues a second ordered browse).
 *   <li><b>Interaction suppression</b> via {@link org.mockito.Mockito#verifyNoInteractions}: the
 *       paths that the legacy program short-circuits <em>before</em> the browse (selection forward,
 *       a non-numeric filter, a {@code PF3} return, an edge-of-list key, an unmapped key, and the
 *       cold start) must never touch the repository &mdash; encoding the COBOL evaluate order.
 * </ul>
 *
 * <p>Decimal fidelity (&sect;0.6.1) is asserted on the row amount: the transaction {@code tAmt} is
 * a {@link BigDecimal} preserved at scale&nbsp;2 (value compared with {@code isEqualByComparingTo}
 * and scale asserted explicitly), never a floating-point type.
 */
@ExtendWith(MockitoExtension.class)
class TranListServiceTest {

  @Mock private TransactionRepository transactionRepository;

  @InjectMocks private TranListService service;

  // ===============================================================================================
  // Fixtures
  // ===============================================================================================

  /** Zero-pads an integer to the 16-character transaction-id key width ({@code TRAN-ID X(16)}). */
  private static String id(int n) {
    return String.format("%016d", n);
  }

  /**
   * Builds a transaction with a deterministic, parity-friendly layout: a zero-padded id, a known
   * origination timestamp ({@code 2022-07-18-...} &rarr; display date {@code 07/18/22}), a
   * descriptive text, and a monetary amount fixed at scale&nbsp;2.
   */
  private static Transaction tx(int n) {
    Transaction t = new Transaction();
    t.setTranId(id(n));
    t.setTranOrigTs("2022-07-18-12.34.56.123456");
    t.setTranDesc("Purchase number " + n);
    t.setTranAmt(new BigDecimal("100.00").add(new BigDecimal(n)));
    return t;
  }

  /** Builds an ascending-by-id list of {@code count} transactions (ids {@code 1..count}). */
  private static List<Transaction> txList(int count) {
    List<Transaction> list = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      list.add(tx(i));
    }
    return list;
  }

  /** Stubs the keyset browse source with {@code count} transactions (ids {@code 1..count}). */
  private void givenTransactions(int count) {
    stubKeyset(txList(count));
  }

  /**
   * Installs lenient keyset simulators over an in-memory ascending snapshot, modelling the two
   * repository browse methods {@link TranListService} now uses: the forward GTEQ page ({@link
   * TransactionRepository#findByTranIdGreaterThanEqualOrderByTranIdAsc}) and the backward
   * strictly-below descending page ({@link
   * TransactionRepository#findByTranIdLessThanOrderByTranIdDesc}). Both honor the {@link Pageable}
   * row limit, so the simulators reproduce the real "fetch only page_size + 1 rows" keyset
   * semantics rather than the legacy full-table read &mdash; the very behavior change finding F-1
   * mandates. Stubs are {@link org.mockito.Mockito#lenient() lenient} because a given test
   * exercises only one browse direction; the other stub goes unused without failing
   * strict-stubbing.
   */
  private void stubKeyset(List<Transaction> sorted) {
    lenient()
        .when(
            transactionRepository.findByTranIdGreaterThanEqualOrderByTranIdAsc(
                anyString(), any(Pageable.class)))
        .thenAnswer(inv -> forwardSlice(sorted, inv.getArgument(0), inv.getArgument(1)));
    lenient()
        .when(
            transactionRepository.findByTranIdLessThanOrderByTranIdDesc(
                anyString(), any(Pageable.class)))
        .thenAnswer(inv -> backwardSlice(sorted, inv.getArgument(0), inv.getArgument(1)));
  }

  /**
   * Reproduces {@code findByTranIdGreaterThanEqualOrderByTranIdAsc}: the ascending slice of
   * transactions with {@code tran_id >= key}, bounded to the {@link Pageable} page size.
   */
  private static List<Transaction> forwardSlice(
      List<Transaction> sorted, String key, Pageable pageable) {
    List<Transaction> out = new ArrayList<>();
    int limit = pageable.getPageSize();
    for (Transaction t : sorted) {
      if (t.getTranId().compareTo(key) >= 0) {
        out.add(t);
        if (out.size() >= limit) {
          break;
        }
      }
    }
    return out;
  }

  /**
   * Reproduces {@code findByTranIdLessThanOrderByTranIdDesc}: the descending (closest-below-first)
   * slice of transactions with {@code tran_id < key}, bounded to the {@link Pageable} page size.
   */
  private static List<Transaction> backwardSlice(
      List<Transaction> sorted, String key, Pageable pageable) {
    List<Transaction> out = new ArrayList<>();
    int limit = pageable.getPageSize();
    for (int i = sorted.size() - 1; i >= 0; i--) {
      Transaction t = sorted.get(i);
      if (t.getTranId().compareTo(key) < 0) {
        out.add(t);
        if (out.size() >= limit) {
          break;
        }
      }
    }
    return out;
  }

  /** A commarea for a signed-on standard user that has already entered this program (re-entry). */
  private static CardDemoCommarea reentered() {
    CardDemoCommarea c = new CardDemoCommarea();
    c.setUserId("USER0001");
    c.setPgmReenter();
    return c;
  }

  /** A commarea for a signed-on standard user on first entry to this program. */
  private static CardDemoCommarea firstEntry() {
    CardDemoCommarea c = new CardDemoCommarea();
    c.setUserId("USER0001");
    c.setPgmEnter();
    return c;
  }

  /** A fresh, empty screen with the ten-row list initialized empty. */
  private static TranListScreen screen() {
    return new TranListScreen();
  }

  /** A screen pre-populated with ten blank rows, then a single row carrying a selection. */
  private static TranListScreen screenWithSelection(int rowIndex, String sel, String trnId) {
    TranListScreen s = screen();
    List<TranListRow> rows = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      rows.add(new TranListRow());
    }
    rows.get(rowIndex).setSel(sel);
    rows.get(rowIndex).setTrnId(trnId);
    s.setRows(rows);
    return s;
  }

  // ===============================================================================================
  // Null-argument guards (Objects.requireNonNull on screen and commarea)
  // ===============================================================================================

  @Test
  @DisplayName("null screen is rejected before any browse")
  void nullScreen_throws() {
    assertThatThrownBy(() -> service.processTranList(null, reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(transactionRepository);
  }

  @Test
  @DisplayName("null commarea is rejected before any browse")
  void nullCommarea_throws() {
    assertThatThrownBy(() -> service.processTranList(screen(), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(transactionRepository);
  }

  // ===============================================================================================
  // MAIN-PARA: cold start and first entry
  // ===============================================================================================

  @Test
  @DisplayName("EIBCALEN=0 (no signed-on user) routes to sign-on without browsing")
  void coldStart_routesToSignon() {
    CardDemoCommarea commarea = new CardDemoCommarea(); // no userId
    commarea.setPgmReenter();

    String next = service.processTranList(screen(), commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COSGN00C");
    assertThat(commarea.getFromTranId()).isEqualTo("CT00");
    assertThat(commarea.getFromProgram()).isEqualTo("COTRN00C");
    assertThat(commarea.isPgmEnter()).isTrue();
    verifyNoInteractions(transactionRepository);
  }

  @Test
  @DisplayName("first entry marks re-entry and paints page one (full ten-row grid) from the top")
  void firstEntry_paintsFirstPage() {
    givenTransactions(25);
    CardDemoCommarea commarea = firstEntry();
    TranListScreen s = screen();

    String next = service.processTranList(s, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(s.getRows()).hasSize(10);
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1));
    assertThat(s.getRows().get(9).getTrnId()).isEqualTo(id(10));
    assertThat(s.getTrnIdFirst()).isEqualTo(id(1));
    assertThat(s.getTrnIdLast()).isEqualTo(id(10));
    assertThat(s.getPageNum()).isEqualTo("00000001");
    assertThat(s.isNextPageYes()).isTrue();
    assertThat(s.getErrMsg()).isEmpty();

    // Decimal fidelity (AAP 0.6.1): the row amount is a BigDecimal preserved at scale 2.
    BigDecimal amount = s.getRows().get(0).getTAmt();
    assertThat(amount).isEqualByComparingTo(new BigDecimal("101.00"));
    assertThat(amount.scale()).isEqualTo(2);

    // Control-flow parity: exactly one bounded keyset browse from the top of the file (GTEQ ""),
    // and nothing else.
    InOrder inOrder = inOrder(transactionRepository);
    inOrder
        .verify(transactionRepository)
        .findByTranIdGreaterThanEqualOrderByTranIdAsc(eq(""), any(Pageable.class));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  @DisplayName("ENTER then PF8 issues two ordered browse queries (validation -> query each turn)")
  void pagingSequence_entersThenForwards_queriesInOrder() {
    givenTransactions(25);
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER); // page 1 (browse #1)
    service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08); // page 2 (browse #2)

    // Both turns position with the same inclusive GTEQ keyset query (ENTER from the top, then PF8
    // from the page-one boundary), so the in-order multiplicity form verifies exactly two ordered
    // browses (one per turn), then no further interactions.
    InOrder inOrder = inOrder(transactionRepository);
    inOrder
        .verify(transactionRepository, times(2))
        .findByTranIdGreaterThanEqualOrderByTranIdAsc(anyString(), any(Pageable.class));
    inOrder.verifyNoMoreInteractions();
    assertThat(s.getPageNum()).isEqualTo("00000002");
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(11));
  }

  // ===============================================================================================
  // PROCESS-ENTER-KEY: transaction-id filter
  // ===============================================================================================

  @Test
  @DisplayName("ENTER with a blank filter browses from the top of the file")
  void enter_blankFilter_browsesFromTop() {
    givenTransactions(25);
    TranListScreen s = screen();

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1));
    assertThat(s.getPageNum()).isEqualTo("00000001");
    assertThat(s.getTrnIdIn()).isEmpty(); // filter echo cleared on a rendered page
  }

  @Test
  @DisplayName("ENTER with a full 16-digit numeric filter positions the browse GTEQ")
  void enter_numericFilter_positionsGteq() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setTrnIdIn(id(15));

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(15));
    assertThat(s.getTrnIdFirst()).isEqualTo(id(15));
    assertThat(s.getPageNum()).isEqualTo("00000001"); // counter reset, then incremented
  }

  @Test
  @DisplayName("ENTER with a short numeric filter is zero-padded before GTEQ positioning")
  void enter_shortNumericFilter_isZeroPadded() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setTrnIdIn("15"); // must zero-pad to id(15) for the fixed-width key comparison

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(15));
  }

  @Test
  @DisplayName("ENTER with a non-numeric filter shows the byte-exact message and never browses")
  void enter_nonNumericFilter_showsMessage() {
    TranListScreen s = screen();
    s.setTrnIdIn("ABC123");

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("Tran ID must be Numeric ...");
    assertThat(s.getRows()).isEmpty(); // not refreshed (legacy error flag suppresses the read)
    verifyNoInteractions(transactionRepository); // validation precedes the browse
  }

  @Test
  @DisplayName("ENTER with a filter beyond the last id shows the 'top of page' (NOTFND) message")
  void enter_filterBeyondEnd_showsTopOfPage() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setTrnIdIn(id(9999));

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are at the top of the page...");
    assertThat(s.isNextPageYes()).isFalse();
    assertThat(s.getRows()).hasSize(10); // freshly blanked page
    assertThat(s.getRows().get(0).getTrnId()).isEmpty();
  }

  @Test
  @DisplayName("ENTER against an empty file shows the 'top of page' (NOTFND) message")
  void enter_emptyFile_showsTopOfPage() {
    givenTransactions(0);
    TranListScreen s = screen();

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are at the top of the page...");
    assertThat(s.getPageNum()).isEqualTo("00000000");
    assertThat(s.isNextPageYes()).isFalse();
  }

  // ===============================================================================================
  // PROCESS-ENTER-KEY: row selection (navigation/state parity, AAP 0.6.5)
  // ===============================================================================================

  @Test
  @DisplayName("row selected with 'S' forwards to the transaction-view program without browsing")
  void selectS_forwardsToView() {
    TranListScreen s = screenWithSelection(2, "S", id(3));
    CardDemoCommarea commarea = reentered();

    String next = service.processTranList(s, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COTRN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COTRN01C");
    assertThat(commarea.getFromTranId()).isEqualTo("CT00");
    assertThat(commarea.getFromProgram()).isEqualTo("COTRN00C");
    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(s.getTrnSelected()).isEqualTo(id(3)); // selected id carried to COTRN01C
    verifyNoInteractions(transactionRepository); // selection short-circuits before the browse
  }

  @Test
  @DisplayName("row selected with lowercase 's' also forwards to the transaction-view program")
  void selectLowercaseS_forwardsToView() {
    TranListScreen s = screenWithSelection(0, "s", id(1));

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COTRN01C");
    assertThat(s.getTrnSelected()).isEqualTo(id(1));
    verifyNoInteractions(transactionRepository);
  }

  @Test
  @DisplayName("invalid selection flag sets the byte-exact message and still renders the list")
  void invalidSelection_showsMessageAndRendersList() {
    givenTransactions(25); // multi-page so the message survives onto a normal (non-terminal) page
    TranListScreen s = screenWithSelection(0, "X", id(1));

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("Invalid selection. Valid value is S");
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1)); // list still rendered
  }

  @Test
  @DisplayName("a selection flag paired with a blank row id is ignored and falls through to browse")
  void selectionWithBlankId_isIgnored() {
    givenTransactions(25);
    TranListScreen s = screenWithSelection(0, "S", "   ");

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull(); // no forward; falls through to the browse
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1));
  }

  // ===============================================================================================
  // MAIN-PARA: PF-key dispatch
  // ===============================================================================================

  @Test
  @DisplayName("PF3 returns to the main menu without browsing")
  void pf3_returnsToMenu() {
    CardDemoCommarea commarea = reentered();

    String next = service.processTranList(screen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getFromTranId()).isEqualTo("CT00");
    assertThat(commarea.getFromProgram()).isEqualTo("COTRN00C");
    assertThat(commarea.isPgmEnter()).isTrue();
    verifyNoInteractions(transactionRepository);
  }

  @Test
  @DisplayName("an unmapped key redisplays with the trimmed shared 'invalid key' message")
  void invalidKey_showsMessage() {
    TranListScreen s = screen();

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK04);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    verifyNoInteractions(transactionRepository);
  }

  // ===============================================================================================
  // PROCESS-PF8-KEY: page forward
  // ===============================================================================================

  @Test
  @DisplayName("PF8 with a forward keyset advances to the next page and refreshes the rows")
  void pf8_advancesPage() {
    givenTransactions(25);
    TranListScreen s = screen();
    // Establish the keyset of a displayed page one directly (hidden round-tripped cursors).
    s.setPageNumValue(1);
    s.setTrnIdLast(id(10));
    s.setNextPageYes(true);

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(11));
    assertThat(s.getRows().get(9).getTrnId()).isEqualTo(id(20));
    assertThat(s.getTrnIdFirst()).isEqualTo(id(11));
    assertThat(s.getTrnIdLast()).isEqualTo(id(20));
    assertThat(s.getPageNum()).isEqualTo("00000002");
    assertThat(s.isNextPageYes()).isTrue();
  }

  @Test
  @DisplayName("PF8 onto the final (partial) page shows the 'reached the bottom' message")
  void pf8_lastPartialPage_showsBottom() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setPageNumValue(2);
    s.setTrnIdLast(id(20));
    s.setNextPageYes(true);

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getPageNum()).isEqualTo("00000003");
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(21));
    assertThat(s.getRows().get(4).getTrnId()).isEqualTo(id(25));
    assertThat(s.getRows().get(5).getTrnId()).isEmpty(); // trailing rows blanked
    assertThat(s.isNextPageYes()).isFalse();
    assertThat(s.getErrMsg()).isEqualTo("You have reached the bottom of the page...");
  }

  @Test
  @DisplayName("PF8 with no further page shows the byte-exact 'already at the bottom' message")
  void pf8_noNextPage_showsAlreadyBottom() {
    TranListScreen s = screen();
    s.setNextPageYes(false); // already on the last page

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are already at the bottom of the page...");
    verifyNoInteractions(transactionRepository); // the boundary guard precedes any browse
  }

  @Test
  @DisplayName("PF8 with a next-page flag but no last cursor positions at HIGH-VALUES (NOTFND)")
  void pf8_nextPageButBlankCursor_usesHighValues() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setNextPageYes(true);
    s.setTrnIdLast(null); // force the HIGH-VALUES sentinel branch

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are at the top of the page...");
    assertThat(s.isNextPageYes()).isFalse();
  }

  // ===============================================================================================
  // PROCESS-PF7-KEY: page backward
  // ===============================================================================================

  @Test
  @DisplayName("PF7 on page one shows the byte-exact 'already at the top' message")
  void pf7_pageOne_showsAlreadyTop() {
    TranListScreen s = screen();
    s.setPageNumValue(1); // already on the first page

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are already at the top of the page...");
    verifyNoInteractions(transactionRepository); // the boundary guard precedes any browse
  }

  @Test
  @DisplayName("PF7 from a middle page steps back exactly one page")
  void pf7_fromMiddlePage_stepsBack() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setPageNumValue(3);
    s.setTrnIdFirst(id(21)); // first id of page three

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getPageNum()).isEqualTo("00000002");
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(11));
    assertThat(s.getRows().get(9).getTrnId()).isEqualTo(id(20));
    assertThat(s.isNextPageYes()).isTrue();
    assertThat(s.getErrMsg()).isEmpty();
  }

  @Test
  @DisplayName("PF7 back to the first page pins the counter and shows 'reached the top'")
  void pf7_backToFirstPage_showsReachedTop() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setPageNumValue(2);
    s.setTrnIdFirst(id(11)); // first id of page two

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getPageNum()).isEqualTo("00000001");
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1));
    assertThat(s.getRows().get(9).getTrnId()).isEqualTo(id(10));
    assertThat(s.getErrMsg()).isEqualTo("You have reached the top of the page...");
  }

  // ===============================================================================================
  // POPULATE-TRAN-DATA: field rendering (date derivation, truncation, decimal fidelity)
  // ===============================================================================================

  @Test
  @DisplayName("row fields render id, MM/DD/YY date, truncated description and scale-2 amount")
  void populate_rendersFields() {
    givenTransactions(3);
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    TranListRow row = s.getRows().get(0);
    assertThat(row.getTrnId()).isEqualTo(id(1));
    assertThat(row.getTDate()).isEqualTo("07/18/22"); // from 2022-07-18-...
    assertThat(row.getTDesc()).isEqualTo("Purchase number 1");
    assertThat(row.getSel()).isEmpty();
    assertThat(row.getTAmt()).isEqualByComparingTo(new BigDecimal("101.00"));
    assertThat(row.getTAmt().scale()).isEqualTo(2); // decimal fidelity (AAP 0.6.1)
  }

  @Test
  @DisplayName("a description longer than the list width is truncated to 26 characters")
  void populate_truncatesDescription() {
    Transaction t = tx(1);
    t.setTranDesc("This description is definitely longer than twenty-six characters");
    stubKeyset(List.of(t));
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(s.getRows().get(0).getTDesc()).hasSize(26).isEqualTo("This description is defini");
  }

  @Test
  @DisplayName("a null or too-short originating timestamp yields a blank display date")
  void populate_handlesUnusableTimestamp() {
    Transaction t = tx(1);
    t.setTranOrigTs(null);
    stubKeyset(List.of(t));
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(s.getRows().get(0).getTDate()).isEmpty();
  }

  @Test
  @DisplayName("a null description renders as an empty list cell")
  void populate_handlesNullDescription() {
    Transaction t = tx(1);
    t.setTranDesc(null);
    stubKeyset(List.of(t));
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(s.getRows().get(0).getTDesc()).isEmpty();
  }

  // ===============================================================================================
  // FILE STATUS OTHER -> IoStatusException (AAP 0.6.4)
  // ===============================================================================================

  @Test
  @DisplayName("an unexpected data-access failure is escalated as IoStatusException")
  void dataAccessFailure_escalatesAsIoStatusException() {
    when(transactionRepository.findByTranIdGreaterThanEqualOrderByTranIdAsc(
            anyString(), any(Pageable.class)))
        .thenThrow(new DataAccessResourceFailureException("simulated outage"));

    assertThatThrownBy(() -> service.processTranList(screen(), reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(IoStatusException.class)
        .hasMessageContaining("TRANSACT");
  }

  // ===============================================================================================
  // MAIN-PARA: a null AID on re-entry is treated as ENTER
  // ===============================================================================================

  @Test
  @DisplayName("a null aid on re-entry is treated as ENTER and paints the current page")
  void nullAid_treatedAsEnter() {
    givenTransactions(25);
    TranListScreen s = screen();

    String next = service.processTranList(s, reentered(), null);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1));
    assertThat(s.getPageNum()).isEqualTo("00000001");
  }
}

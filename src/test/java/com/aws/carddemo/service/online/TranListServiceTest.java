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
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link TranListService}, the online transaction-list /
 * browse service migrated from the legacy CICS COBOL program {@code COTRN00C} (CICS transaction
 * {@code CT00}; behavioral spec {@code legacy/app/cbl/COTRN00C.cbl}).
 *
 * <p>The service's sole collaborator is {@link TransactionRepository}, which is mocked so the
 * ascending-by-transaction-id browse source is fully controlled and deterministic. Control-flow
 * parity (Agent Action Plan &sect;0.6.5, &sect;0.7.1) is asserted through the externally observable
 * effects of {@link TranListService#processTranList(TranListScreen, CardDemoCommarea,
 * CardWorkArea.Aid)}: its return value (the {@code XCTL} target program, or {@code null} to
 * redisplay), the mutations it makes to the {@link CardDemoCommarea} navigation state and the
 * {@link TranListScreen} (rows, page indicator, keyset cursors), and the byte-exact message
 * literals it writes. No Spring context or database is required.
 */
class TranListServiceTest {

  private TransactionRepository repository;
  private TranListService service;

  @BeforeEach
  void setUp() {
    repository = mock(TransactionRepository.class);
    service = new TranListService(repository);
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  /** Zero-pads an integer to the 16-character transaction-id width. */
  private static String id(int n) {
    return String.format("%016d", n);
  }

  /** Builds a transaction with a deterministic, parity-friendly layout. */
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

  private void givenTransactions(int count) {
    when(repository.findAllByOrderByTranIdAsc()).thenReturn(txList(count));
  }

  /** A commarea for a signed-on standard user that has already entered this program. */
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

  /** Convenience: a screen carrying a one-row selection (sel + trnId on row index 0). */
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

  // ---------------------------------------------------------------------------------------------
  // Null-argument guards
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("null screen is rejected")
  void nullScreen_throws() {
    assertThatThrownBy(() -> service.processTranList(null, reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("null commarea is rejected")
  void nullCommarea_throws() {
    assertThatThrownBy(() -> service.processTranList(screen(), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // MAIN-PARA: cold start and first entry
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("EIBCALEN=0 (no signed-on user) routes to sign-on")
  void coldStart_routesToSignon() {
    CardDemoCommarea commarea = new CardDemoCommarea(); // no userId
    commarea.setPgmReenter();

    String next = service.processTranList(screen(), commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COSGN00C");
    assertThat(commarea.getFromTranId()).isEqualTo("CT00");
    assertThat(commarea.getFromProgram()).isEqualTo("COTRN00C");
    assertThat(commarea.isPgmEnter()).isTrue();
  }

  @Test
  @DisplayName("first entry marks re-entry and paints page one from the top")
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
  }

  // ---------------------------------------------------------------------------------------------
  // PROCESS-ENTER-KEY: transaction-id filter
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("ENTER with blank filter browses from the top")
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
  @DisplayName("ENTER with a full 16-digit numeric filter positions GTEQ")
  void enter_numericFilter_positionsGteq() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setTrnIdIn(id(15));

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(15));
    assertThat(s.getTrnIdFirst()).isEqualTo(id(15));
    assertThat(s.getPageNum()).isEqualTo("00000001"); // page counter reset, then incremented
  }

  @Test
  @DisplayName("ENTER with a short numeric filter is zero-padded before positioning")
  void enter_shortNumericFilter_isZeroPadded() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setTrnIdIn("15"); // must zero-pad to id(15) for the fixed-width key comparison

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(15));
  }

  @Test
  @DisplayName("ENTER with a non-numeric filter shows the byte-exact 'must be Numeric' message")
  void enter_nonNumericFilter_showsMessage() {
    givenTransactions(25);
    TranListScreen s = screen();
    s.setTrnIdIn("ABC123");

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("Tran ID must be Numeric ...");
    assertThat(s.getRows()).isEmpty(); // not refreshed (legacy error flag suppresses the read)
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

  // ---------------------------------------------------------------------------------------------
  // PROCESS-ENTER-KEY: row selection
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("row selected with 'S' forwards to the transaction-view program")
  void selectS_forwardsToView() {
    givenTransactions(25);
    TranListScreen s = screenWithSelection(2, "S", id(3));
    CardDemoCommarea commarea = reentered();

    String next = service.processTranList(s, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COTRN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COTRN01C");
    assertThat(commarea.getFromTranId()).isEqualTo("CT00");
    assertThat(commarea.getFromProgram()).isEqualTo("COTRN00C");
    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(s.getTrnSelected()).isEqualTo(id(3));
  }

  @Test
  @DisplayName("row selected with lowercase 's' also forwards to the transaction-view program")
  void selectLowercaseS_forwardsToView() {
    givenTransactions(25);
    TranListScreen s = screenWithSelection(0, "s", id(1));

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COTRN01C");
    assertThat(s.getTrnSelected()).isEqualTo(id(1));
  }

  @Test
  @DisplayName("invalid selection flag sets the byte-exact message and still renders the list")
  void invalidSelection_showsMessageAndRendersList() {
    givenTransactions(25); // multi-page so the message survives onto a normal page
    TranListScreen s = screenWithSelection(0, "X", id(1));

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("Invalid selection. Valid value is S");
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1)); // list still rendered
  }

  @Test
  @DisplayName("selection flag with a blank row id is ignored")
  void selectionWithBlankId_isIgnored() {
    givenTransactions(25);
    TranListScreen s = screenWithSelection(0, "S", "   ");

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull(); // no forward; falls through to the browse
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1));
  }

  // ---------------------------------------------------------------------------------------------
  // MAIN-PARA: PF-key dispatch
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PF3 returns to the main menu")
  void pf3_returnsToMenu() {
    CardDemoCommarea commarea = reentered();

    String next = service.processTranList(screen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getFromTranId()).isEqualTo("CT00");
    assertThat(commarea.getFromProgram()).isEqualTo("COTRN00C");
    assertThat(commarea.isPgmEnter()).isTrue();
  }

  @Test
  @DisplayName("an unmapped key redisplays with the trimmed shared 'invalid key' message")
  void invalidKey_showsMessage() {
    TranListScreen s = screen();

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK04);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
  }

  // ---------------------------------------------------------------------------------------------
  // PROCESS-PF8-KEY: page forward
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PF8 with a next page advances to the following page")
  void pf8_advancesPage() {
    givenTransactions(25);
    TranListScreen s = screen();
    // Establish page one first.
    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

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
    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER); // page 1
    service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08); // page 2

    String next =
        service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08); // page 3 (5 rows)

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
    givenTransactions(10); // exactly one page
    TranListScreen s = screen();
    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER); // page 1; nextPage=false

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are already at the bottom of the page...");
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

  // ---------------------------------------------------------------------------------------------
  // PROCESS-PF7-KEY: page backward
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PF7 on page one shows the byte-exact 'already at the top' message")
  void pf7_pageOne_showsAlreadyTop() {
    givenTransactions(25);
    TranListScreen s = screen();
    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER); // page 1

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are already at the top of the page...");
  }

  @Test
  @DisplayName("PF7 from a middle page steps back one page")
  void pf7_fromMiddlePage_stepsBack() {
    givenTransactions(25);
    TranListScreen s = screen();
    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER); // page 1
    service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08); // page 2
    service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08); // page 3

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK07); // back to page 2

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
    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER); // page 1
    service.processTranList(s, reentered(), CardWorkArea.Aid.PFK08); // page 2

    String next = service.processTranList(s, reentered(), CardWorkArea.Aid.PFK07); // back to page 1

    assertThat(next).isNull();
    assertThat(s.getPageNum()).isEqualTo("00000001");
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1));
    assertThat(s.getRows().get(9).getTrnId()).isEqualTo(id(10));
    assertThat(s.getErrMsg()).isEqualTo("You have reached the top of the page...");
  }

  // ---------------------------------------------------------------------------------------------
  // POPULATE-TRAN-DATA: field rendering
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("row fields render the id, MM/DD/YY date, truncated description and amount")
  void populate_rendersFields() {
    givenTransactions(3);
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    TranListRow row = s.getRows().get(0);
    assertThat(row.getTrnId()).isEqualTo(id(1));
    assertThat(row.getTDate()).isEqualTo("07/18/22"); // from 2022-07-18-...
    assertThat(row.getTDesc()).isEqualTo("Purchase number 1");
    assertThat(row.getTAmt()).isEqualByComparingTo(new BigDecimal("101.00"));
    assertThat(row.getSel()).isEmpty();
  }

  @Test
  @DisplayName("a description longer than the list width is truncated to 26 characters")
  void populate_truncatesDescription() {
    Transaction t = tx(1);
    t.setTranDesc("This description is definitely longer than twenty-six characters");
    when(repository.findAllByOrderByTranIdAsc()).thenReturn(List.of(t));
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(s.getRows().get(0).getTDesc()).hasSize(26).isEqualTo("This description is defini");
  }

  @Test
  @DisplayName("a null or too-short originating timestamp yields a blank display date")
  void populate_handlesUnusableTimestamp() {
    Transaction t = tx(1);
    t.setTranOrigTs(null);
    when(repository.findAllByOrderByTranIdAsc()).thenReturn(List.of(t));
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(s.getRows().get(0).getTDate()).isEmpty();
  }

  @Test
  @DisplayName("a null description renders as an empty list cell")
  void populate_handlesNullDescription() {
    // TRAN-DESC is a nullable column in the migrated schema; the list-view formatter must render a
    // missing description as SPACES (an empty cell) rather than propagating a null, mirroring the
    // COBOL MOVE of a low-values/space description into the X(26) display field.
    Transaction t = tx(1);
    t.setTranDesc(null);
    when(repository.findAllByOrderByTranIdAsc()).thenReturn(List.of(t));
    TranListScreen s = screen();

    service.processTranList(s, reentered(), CardWorkArea.Aid.ENTER);

    assertThat(s.getRows().get(0).getTDesc()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // FILE STATUS OTHER -> IoStatusException
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an unexpected data-access failure is escalated as IoStatusException")
  void dataAccessFailure_escalatesAsIoStatusException() {
    when(repository.findAllByOrderByTranIdAsc())
        .thenThrow(new DataAccessResourceFailureException("simulated outage"));

    assertThatThrownBy(() -> service.processTranList(screen(), reentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(IoStatusException.class)
        .hasMessageContaining("TRANSACT");
  }

  @Test
  @DisplayName("a null aid on re-entry is treated as ENTER")
  void nullAid_treatedAsEnter() {
    givenTransactions(25);
    TranListScreen s = screen();

    String next = service.processTranList(s, reentered(), null);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getTrnId()).isEqualTo(id(1));
    assertThat(s.getPageNum()).isEqualTo("00000001");
  }
}

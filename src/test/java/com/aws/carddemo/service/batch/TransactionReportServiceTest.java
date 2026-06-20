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
package com.aws.carddemo.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.TransactionType;
import com.aws.carddemo.domain.id.TransactionCategoryId;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionCategoryRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.repository.TransactionTypeRepository;
import com.aws.carddemo.util.NumberFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link TransactionReportService}, the batch
 * "transaction detail report" service migrated with 100% behavioral parity from the legacy z/OS
 * COBOL program {@code CBTRN03C} (behavioral spec {@code legacy/app/cbl/CBTRN03C.cbl}; report
 * layout copybook {@code legacy/app/cpy/CVTRA07Y.cpy}; sorted/filtered working set from {@code
 * legacy/app/jcl/TRANREPT.jcl}).
 *
 * <p>The service's only collaborators are four Spring Data repositories, all mocked here, so no
 * Spring context, Testcontainers, or database is required (Agent Action Plan &sect;0.6.7 local-only
 * parity). Every fully-formatted 133-character report line is handed to the {@code
 * Consumer<String>} report sink; these tests capture that stream into a {@link List} via {@code
 * lines::add} and assert on it directly.
 *
 * <p>The parity invariants pinned by these tests (AAP &sect;0.7.1 control-flow preservation) are
 * the intentional COBOL quirks that must be reproduced <em>bug-for-bug</em>:
 *
 * <ul>
 *   <li><b>EOF double-count</b> &mdash; at end-of-file the stale last record's amount is added to
 *       the page and account totals a <em>second</em> time (it was already added when its detail
 *       line was written), so the printed page and grand totals double-count the final record
 *       (CBTRN03C L200-204);
 *   <li><b>final account total never emitted</b> &mdash; {@code 1120-WRITE-ACCOUNT-TOTALS} runs
 *       only on a card control break and never at EOF, so the last (or only) card's account-total
 *       line is never written, and the number of account-total lines always equals {@code cards -
 *       1};
 *   <li><b>133-character records</b> &mdash; every emitted line is exactly 133 characters ({@code
 *       CVTRA07Y REPTFILE}, LRECL 133);
 *   <li><b>ascending card-number order</b> &mdash; the working set is read via {@code
 *       findAll(Sort.by(Sort.Direction.ASC, "tranCardNum"))} then date-filtered inclusively on
 *       {@code tranProcTs[0:10]};
 *   <li><b>three FATAL lookups</b> &mdash; a missing card-xref, transaction-type, or
 *       transaction-category record abends with an {@link IoStatusException} naming {@code
 *       CARDXREF}, {@code TRANTYPE}, or {@code TRANCATG} respectively; and
 *   <li><b>no output for an empty working set</b> &mdash; the report sink is never invoked.
 * </ul>
 *
 * <p>Amounts are never compared as hardcoded strings: each expected edited field is computed with
 * {@link NumberFormatter#formatReportAmount(BigDecimal)} (detail, {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}) or
 * {@link NumberFormatter#formatTotalAmount(BigDecimal)} (totals, {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}) so
 * the assertions stay in lock-step with the production formatter. All monetary values are {@link
 * BigDecimal}; no {@code float}/{@code double} is used anywhere (AAP &sect;0.6.1).
 *
 * <p>Because the Mockito mock returns the stubbed list verbatim and the production deliberately
 * does <em>not</em> re-sort in memory (it trusts the repository's {@code Sort}), every multi-record
 * fixture below is supplied already ordered ascending by {@code tranCardNum}.
 */
@ExtendWith(MockitoExtension.class)
class TransactionReportServiceTest {

  /** Inclusive lower bound passed to {@code run(...)}; mirrors {@code WS-START-DATE}. */
  private static final String START_DATE = "2023-01-01";

  /** Inclusive upper bound passed to {@code run(...)}; mirrors {@code WS-END-DATE}. */
  private static final String END_DATE = "2023-12-31";

  /** First card number; ordered before {@link #CARD_B} so two-card fixtures are pre-sorted. */
  private static final String CARD_A = "CARD000000000001";

  /** Second card number; ordered after {@link #CARD_A}. */
  private static final String CARD_B = "CARD000000000002";

  /** Default {@code TRAN-TYPE-CD} used by the happy-path fixtures ({@code PIC X(02)}). */
  private static final String TYPE_CD = "01";

  /** Default {@code TRAN-CAT-CD} used by the happy-path fixtures ({@code PIC 9(04)}). */
  private static final String CAT_CD = "0001";

  /** Default {@code XREF-ACCT-ID} returned by the card-xref lookup ({@code PIC 9(11)}). */
  private static final long ACCT_ID = 12345678901L;

  /** {@code REPT-SHORT-NAME 'DALYREPT'} &mdash; uniquely marks a report name-header line. */
  private static final String NAME_HEADER_MARKER = "DALYREPT";

  /** {@code REPORT-PAGE-TOTALS} label &mdash; uniquely marks a page-total line. */
  private static final String PAGE_TOTAL_LABEL = "Page Total";

  /** {@code REPORT-ACCOUNT-TOTALS} label &mdash; uniquely marks an account-total line. */
  private static final String ACCOUNT_TOTAL_LABEL = "Account Total";

  /** {@code REPORT-GRAND-TOTALS} label &mdash; uniquely marks a grand-total line. */
  private static final String GRAND_TOTAL_LABEL = "Grand Total";

  /** {@code FD-REPTFILE-REC PIC X(133)} &mdash; the fixed report record width. */
  private static final int REPORT_LINE_WIDTH = 133;

  /** Transaction master ({@code TRANSACT}); the date-filtered, card-ordered working set. */
  @Mock private TransactionRepository transactionRepository;

  /** Card cross-reference store ({@code CARDXREF}); the {@code 1500-A} lookup. */
  @Mock private CardXrefRepository cardXrefRepository;

  /** Transaction-type store ({@code TRANTYPE}); the {@code 1500-B} lookup. */
  @Mock private TransactionTypeRepository transactionTypeRepository;

  /** Transaction-category store ({@code TRANCATG}); the {@code 1500-C} lookup. */
  @Mock private TransactionCategoryRepository transactionCategoryRepository;

  /** System under test, rebuilt per test in {@link #setUp()} with the four mocked repositories. */
  private TransactionReportService service;

  @BeforeEach
  void setUp() {
    service =
        new TransactionReportService(
            transactionRepository,
            cardXrefRepository,
            transactionTypeRepository,
            transactionCategoryRepository);
  }

  // --- Phase A: 133-character line width ------------------------------------------------------

  @Test
  void every_emitted_report_line_is_exactly_133_characters() {
    stubWorkingSet(tx("TXN0000000000001", CARD_A, "10.00"));
    stubAllLookupsPresent();

    List<String> lines = run();

    // Headers, the detail line, and the EOF page/grand total lines all flow through
    // 1111-WRITE-REPORT-REC, which pads/truncates every record to FD-REPTFILE-REC PIC X(133).
    assertThat(lines).isNotEmpty();
    assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(REPORT_LINE_WIDTH));
  }

  // --- Phase B: EOF DOUBLE-COUNT (the parity quirk) -------------------------------------------

  @Test
  void eof_double_counts_last_transaction_in_page_and_grand_totals() {
    stubWorkingSet(tx("TXN0000000000001", CARD_A, "10.00"));
    stubAllLookupsPresent();

    List<String> lines = run();

    // The single detail line still shows the real amount, 10.00.
    String detailAmount = NumberFormatter.formatReportAmount(new BigDecimal("10.00"));
    assertThat(detailLine(lines, "TXN")).contains(detailAmount);

    // The last record's amount is added AGAIN at EOF, so the page and grand totals show 20.00
    // (10.00 from the detail + 10.00 re-added at EOF). This pins the double-count bug.
    String doubled = NumberFormatter.formatTotalAmount(new BigDecimal("20.00"));
    assertThat(onlyLineContaining(lines, PAGE_TOTAL_LABEL)).contains(doubled);
    assertThat(onlyLineContaining(lines, GRAND_TOTAL_LABEL)).contains(doubled);
  }

  @Test
  void eof_double_count_with_multiple_transactions_single_card() {
    // Three in-range transactions on one card; supplied in stable order on the same card number.
    stubWorkingSet(
        tx("TXN0000000000001", CARD_A, "10.00"),
        tx("TXN0000000000002", CARD_A, "20.00"),
        tx("TXN0000000000003", CARD_A, "30.00"));
    stubAllLookupsPresent();

    List<String> lines = run();

    // Detail lines show the individual amounts 10/20/30.
    assertThat(lines).anySatisfy(line -> assertThat(line).contains(reportAmount("10.00")));
    assertThat(lines).anySatisfy(line -> assertThat(line).contains(reportAmount("20.00")));
    assertThat(lines).anySatisfy(line -> assertThat(line).contains(reportAmount("30.00")));

    // Grand total = 10 + 20 + 30 + 30 (the last record re-added at EOF) = 90.00, NOT 60.00.
    assertThat(onlyLineContaining(lines, GRAND_TOTAL_LABEL)).contains(totalAmount("90.00"));
    assertThat(onlyLineContaining(lines, GRAND_TOTAL_LABEL)).doesNotContain(totalAmount("60.00"));
  }

  // --- Phase C: final account-total NEVER emitted (the parity quirk) --------------------------

  @Test
  void final_card_account_total_line_is_never_emitted() {
    stubWorkingSet(tx("TXN0000000000001", CARD_A, "10.00"));
    stubAllLookupsPresent();

    List<String> lines = run();

    // The only card's 1120-WRITE-ACCOUNT-TOTALS is skipped at EOF, so no account-total line exists.
    // Page/grand totals (which DO close out the report) carry different labels and are unaffected.
    assertThat(lines).noneMatch(line -> line.contains(ACCOUNT_TOTAL_LABEL));
    assertThat(lines).anyMatch(line -> line.contains(PAGE_TOTAL_LABEL));
    assertThat(lines).anyMatch(line -> line.contains(GRAND_TOTAL_LABEL));
  }

  @Test
  void previous_card_account_total_is_emitted_on_control_break_but_last_card_is_not() {
    // cardA has two transactions, cardB one; supplied pre-sorted ascending by card number so the
    // production reads cardA fully, breaks to cardB (writing cardA's account total), then ends.
    stubWorkingSet(
        tx("TXN0000000000001", CARD_A, "10.00"),
        tx("TXN0000000000002", CARD_A, "20.00"),
        tx("TXN0000000000003", CARD_B, "30.00"));
    stubAllLookupsPresent();

    List<String> lines = run();

    // Exactly ONE account-total line is emitted (cardA, on the break to cardB). cardB's account
    // total is never written at EOF. General rule: account-total lines == cards - 1 == 1.
    assertThat(lines).filteredOn(line -> line.contains(ACCOUNT_TOTAL_LABEL)).hasSize(1);
  }

  // --- Phase D: pagination at 20 lines --------------------------------------------------------

  @Test
  void pagination_writes_page_totals_and_repeats_headers_after_twenty_lines() {
    // 25 in-range detail lines on one card cross the WS_PAGE_SIZE = 20 boundary exactly once
    // (the break fires at the record whose entry line-counter is a multiple of 20), so a second
    // header block and a mid-report page-total line are emitted before the EOF totals.
    Transaction[] many = new Transaction[25];
    for (int i = 0; i < many.length; i++) {
      many[i] = tx(String.format("TXN%013d", i + 1), CARD_A, "1.00");
    }
    stubWorkingSet(many);
    stubAllLookupsPresent();

    List<String> lines = run();

    // A new page was started: the report name header (DALYREPT) appears more than once.
    assertThat(lines).filteredOn(line -> line.contains(NAME_HEADER_MARKER)).hasSizeGreaterThan(1);

    // At least one mid-report page-total line is emitted in addition to the EOF page total
    // (>= 2 page-total lines total), proving 1110-WRITE-PAGE-TOTALS fired during pagination.
    assertThat(lines)
        .filteredOn(line -> line.contains(PAGE_TOTAL_LABEL))
        .hasSizeGreaterThanOrEqualTo(2);
  }

  // --- Phase E: lookups are FATAL (abend on not-found) ----------------------------------------

  @Test
  void missing_card_xref_throws_io_status_exception_cardxref() {
    stubWorkingSet(tx("TXN0000000000001", CARD_A, "10.00"));
    // 1500-A-LOOKUP-XREF: an empty Optional is FATAL (INVALID KEY -> MOVE 23 -> abend). The
    // trantype/trancatg lookups are never reached, so they are intentionally not stubbed.
    when(cardXrefRepository.findById(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.run(START_DATE, END_DATE, line -> {}))
        .isInstanceOfSatisfying(
            IoStatusException.class, ex -> assertThat(ex.getFileName()).isEqualTo("CARDXREF"));
  }

  @Test
  void missing_transaction_type_throws_io_status_exception_trantype() {
    stubWorkingSet(tx("TXN0000000000001", CARD_A, "10.00"));
    stubXrefPresent();
    // 1500-B-LOOKUP-TRANTYPE: empty Optional is FATAL; the trancatg lookup is never reached.
    when(transactionTypeRepository.findById(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.run(START_DATE, END_DATE, line -> {}))
        .isInstanceOfSatisfying(
            IoStatusException.class, ex -> assertThat(ex.getFileName()).isEqualTo("TRANTYPE"));
  }

  @Test
  void missing_transaction_category_throws_io_status_exception_trancatg() {
    stubWorkingSet(tx("TXN0000000000001", CARD_A, "10.00"));
    stubXrefPresent();
    stubTrantypePresent();
    // 1500-C-LOOKUP-TRANCATG: empty Optional is FATAL. The key is the composite
    // (TRAN-TYPE-CD, TRAN-CAT-CD) modeled by TransactionCategoryId.
    when(transactionCategoryRepository.findById(any(TransactionCategoryId.class)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.run(START_DATE, END_DATE, line -> {}))
        .isInstanceOfSatisfying(
            IoStatusException.class, ex -> assertThat(ex.getFileName()).isEqualTo("TRANCATG"));
  }

  // --- Phase F: empty / date-filtered working set ---------------------------------------------

  @Test
  void empty_working_set_produces_no_output() {
    // An empty master => the first read sets END-OF-FILE with a null record => the in-range guard
    // fails => the PERFORM terminates with nothing written. The report sink is never invoked.
    stubWorkingSet();

    List<String> lines = run();

    assertThat(lines).isEmpty();
  }

  @Test
  void date_filter_excludes_out_of_range_transactions() {
    // Three in-range records (including both inclusive boundaries) and two out-of-range records,
    // all on one card. Only the in-range records become detail lines; the others are filtered out
    // before any lookup, exactly like the JCL SORT INCLUDE COND on TRAN-PROC-DT.
    stubWorkingSet(
        transaction(
            "TXNIN00000000001", CARD_A, TYPE_CD, CAT_CD, "POS", amt("11.00"), ts(START_DATE)),
        transaction(
            "TXNOUT0000000001", CARD_A, TYPE_CD, CAT_CD, "POS", amt("22.00"), ts("2022-12-31")),
        transaction(
            "TXNIN00000000002", CARD_A, TYPE_CD, CAT_CD, "POS", amt("33.00"), ts("2023-06-15")),
        transaction(
            "TXNOUT0000000002", CARD_A, TYPE_CD, CAT_CD, "POS", amt("44.00"), ts("2024-01-01")),
        transaction(
            "TXNIN00000000003", CARD_A, TYPE_CD, CAT_CD, "POS", amt("55.00"), ts(END_DATE)));
    stubAllLookupsPresent();

    List<String> lines = run();

    // Exactly the three in-range records yield detail lines; out-of-range records never appear.
    assertThat(lines).filteredOn(line -> line.startsWith("TXNIN")).hasSize(3);
    assertThat(lines).noneMatch(line -> line.startsWith("TXNOUT"));
  }

  // --- Phase G: amount formatting via NumberFormatter -----------------------------------------

  @Test
  void detail_and_total_amounts_use_number_formatter() {
    stubWorkingSet(tx("TXN0000000000001", CARD_A, "1234.56"));
    stubAllLookupsPresent();

    List<String> lines = run();

    // The detail line renders the amount via the report mask PIC -ZZZ,ZZZ,ZZZ.ZZ (width 15).
    assertThat(detailLine(lines, "TXN")).contains(reportAmount("1234.56"));

    // The grand total renders via the total mask PIC +ZZZ,ZZZ,ZZZ.ZZ (width 15, leading '+' for a
    // non-negative value). With the EOF double-count the grand total is 1234.56 + 1234.56 =
    // 2469.12.
    String expectedTotal = NumberFormatter.formatTotalAmount(new BigDecimal("2469.12"));
    assertThat(expectedTotal).startsWith("+");
    assertThat(onlyLineContaining(lines, GRAND_TOTAL_LABEL)).contains(expectedTotal);
  }

  // --- Phase H: control-flow / ordering -------------------------------------------------------

  @Test
  void working_set_is_read_ascending_by_card_number() {
    stubWorkingSet();
    ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);

    service.run(START_DATE, END_DATE, line -> {});

    // The working set is built via findAll(Sort.by(Sort.Direction.ASC, "tranCardNum")), the Java
    // counterpart of the TRANREPT.jcl SORT FIELDS=(TRAN-CARD-NUM,A).
    verify(transactionRepository).findAll(sortCaptor.capture());
    Sort captured = sortCaptor.getValue();
    assertThat(captured).isEqualTo(Sort.by(Sort.Direction.ASC, "tranCardNum"));
    assertThat(captured.getOrderFor("tranCardNum").getDirection()).isEqualTo(Sort.Direction.ASC);
  }

  // --- helpers --------------------------------------------------------------------------------

  /** Runs the report over {@code [START_DATE, END_DATE]} and returns every captured report line. */
  private List<String> run() {
    List<String> lines = new ArrayList<>();
    service.run(START_DATE, END_DATE, lines::add);
    return lines;
  }

  /** Stubs the date-filtered, card-ordered working set returned by {@code 0000-TRANFILE-OPEN}. */
  private void stubWorkingSet(Transaction... transactions) {
    when(transactionRepository.findAll(any(Sort.class))).thenReturn(List.of(transactions));
  }

  /** Stubs all three lookups ({@code 1500-A/B/C}) to return present records (the happy path). */
  private void stubAllLookupsPresent() {
    stubXrefPresent();
    stubTrantypePresent();
    stubTrancatgPresent();
  }

  /** Stubs {@code 1500-A-LOOKUP-XREF} to find a card-xref for any card number. */
  private void stubXrefPresent() {
    when(cardXrefRepository.findById(anyString())).thenReturn(Optional.of(cardXref(ACCT_ID)));
  }

  /** Stubs {@code 1500-B-LOOKUP-TRANTYPE} to find a transaction type for any code. */
  private void stubTrantypePresent() {
    when(transactionTypeRepository.findById(anyString()))
        .thenReturn(Optional.of(transactionType(TYPE_CD, "PURCHASE")));
  }

  /** Stubs {@code 1500-C-LOOKUP-TRANCATG} to find a transaction category for any composite key. */
  private void stubTrancatgPresent() {
    when(transactionCategoryRepository.findById(any(TransactionCategoryId.class)))
        .thenReturn(Optional.of(transactionCategory(TYPE_CD, CAT_CD, "RETAIL PURCHASE")));
  }

  /** Builds a minimal in-range transaction on {@code card} with the default type/category codes. */
  private Transaction tx(String id, String card, String amount) {
    return transaction(id, card, TYPE_CD, CAT_CD, "POS", amt(amount), ts("2023-06-15"));
  }

  /** Builds a fully specified {@link Transaction}; every amount is a {@link BigDecimal}. */
  private Transaction transaction(
      String id,
      String card,
      String typeCd,
      String catCd,
      String source,
      BigDecimal amount,
      String procTs) {
    Transaction transaction = new Transaction();
    transaction.setTranId(id);
    transaction.setTranCardNum(card);
    transaction.setTranTypeCd(typeCd);
    transaction.setTranCatCd(catCd);
    transaction.setTranSource(source);
    transaction.setTranAmt(amount);
    transaction.setTranProcTs(procTs);
    return transaction;
  }

  /** Builds a {@link CardXref} carrying the account id surfaced on detail lines. */
  private CardXref cardXref(long acctId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(CARD_A);
    xref.setXrefCustId(1L);
    xref.setXrefAcctId(acctId);
    return xref;
  }

  /** Builds a {@link TransactionType} lookup row. */
  private TransactionType transactionType(String type, String description) {
    TransactionType transactionType = new TransactionType();
    transactionType.setTranType(type);
    transactionType.setTranTypeDesc(description);
    return transactionType;
  }

  /** Builds a {@link TransactionCategory} lookup row keyed by the composite (type, category). */
  private TransactionCategory transactionCategory(String typeCd, String catCd, String description) {
    TransactionCategory category = new TransactionCategory();
    category.setId(new TransactionCategoryId(typeCd, catCd));
    category.setTranCatTypeDesc(description);
    return category;
  }

  /** Parses a scale-2 {@link BigDecimal} amount (never {@code float}/{@code double}). */
  private static BigDecimal amt(String value) {
    return new BigDecimal(value);
  }

  /**
   * Builds a 26-character {@code TRAN-PROC-TS} whose date portion ({@code [0:10]}) is {@code date}.
   */
  private static String ts(String date) {
    return date + "-00.00.00.000000";
  }

  /** The expected edited detail amount ({@code PIC -ZZZ,ZZZ,ZZZ.ZZ}) for {@code value}. */
  private static String reportAmount(String value) {
    return NumberFormatter.formatReportAmount(new BigDecimal(value));
  }

  /** The expected edited total amount ({@code PIC +ZZZ,ZZZ,ZZZ.ZZ}) for {@code value}. */
  private static String totalAmount(String value) {
    return NumberFormatter.formatTotalAmount(new BigDecimal(value));
  }

  /** Returns the single detail line (the one whose record starts with {@code idPrefix}). */
  private static String detailLine(List<String> lines, String idPrefix) {
    List<String> matches = lines.stream().filter(line -> line.startsWith(idPrefix)).toList();
    assertThat(matches).as("exactly one detail line starting with '%s'", idPrefix).hasSize(1);
    return matches.get(0);
  }

  /**
   * Returns the single report line that contains {@code marker}, asserting there is exactly one.
   */
  private static String onlyLineContaining(List<String> lines, String marker) {
    List<String> matches = lines.stream().filter(line -> line.contains(marker)).toList();
    assertThat(matches).as("exactly one line containing '%s'", marker).hasSize(1);
    return matches.get(0);
  }
}

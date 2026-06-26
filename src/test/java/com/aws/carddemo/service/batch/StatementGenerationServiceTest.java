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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.service.batch.FileIoService.WorkArea;
import com.aws.carddemo.util.CobolStringUtils;
import com.aws.carddemo.util.NumberFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link StatementGenerationService}, the
 * account-statement batch service migrated from the legacy z/OS COBOL program {@code
 * legacy/app/cbl/CBSTM03A.CBL} (with its centralized I/O subroutine {@code CBSTM03B}). The service
 * produces, for every card cross-reference, a fixed-width plain-text statement ({@code STMTFILE},
 * {@code LRECL=80}) and a matching HTML statement ({@code HTMLFILE}, {@code LRECL=100}), emitting
 * each output line to one of two injected {@link java.util.function.Consumer
 * Consumer&lt;String&gt;} sinks (Agent Action Plan &sect;0.4.1).
 *
 * <p><strong>Subroutine-injection contract (AAP &sect;0.4.2).</strong> Unlike every other service
 * in this package, {@code StatementGenerationService} performs <em>no</em> repository access: all
 * input file I/O is funnelled through the injected {@link FileIoService} &mdash; the Java
 * translation of {@code CALL 'CBSTM03B' USING WS-M03B-AREA}. Consequently this test mocks {@link
 * FileIoService} <em>only</em> (there are no repositories to wire) and drives the legacy {@code
 * CBSTM03B} cursor behavior by stubbing {@link FileIoService#process(WorkArea)} with a {@code
 * doAnswer} that inspects each {@link WorkArea}'s ddname + operation and mutates it in place
 * (status via {@link WorkArea#getReturnCode()}, record via {@link WorkArea#getRecord()}), exactly
 * as {@code CBSTM03B} returned its 2-byte FILE STATUS in {@code LK-M03B-RC} and never threw.
 *
 * <p>The tests are intentionally framework-light: no Spring context, no Testcontainers, no
 * database. They pin the <strong>behavioral-parity invariants</strong> the statement run must never
 * regress (AAP &sect;0.6.1, &sect;0.6.3, &sect;0.6.4, &sect;0.6.7):
 *
 * <ul>
 *   <li><b>Mainline short-circuit</b> &mdash; end-of-file on the <em>first</em> {@code XREFFILE}
 *       read produces zero statements (both sinks empty), mirroring the {@code 1000-MAINLINE}
 *       {@code IF END-OF-FILE = 'N'} guard.
 *   <li><b>Dual output</b> &mdash; one cross-reference yields one plain-text statement (with the
 *       byte-exact {@code START OF STATEMENT} banner) and one HTML statement (with the byte-exact
 *       document header), and every text line is exactly 80 characters ({@code STMT-FILE PIC
 *       X(80)}).
 *   <li><b>In-memory transaction ordering</b> &mdash; transactions are grouped by card and sorted
 *       ascending by {@code (tranCardNum, tranId)} regardless of the order {@link FileIoService}
 *       returns them, and cards appear in ascending card-number order.
 *   <li><b>Expense total</b> &mdash; the statement total equals the sum of the card's transaction
 *       amounts (scale-2 {@link BigDecimal}; no {@code float}/{@code double}).
 *   <li><b>Edited-field fidelity</b> &mdash; the current balance is the leading-zero {@code PIC
 *       9(9).99-} form (trailing blank for positive, {@code '-'} for negative); transaction and
 *       total amounts use the zero-suppressed trailing-sign {@link
 *       NumberFormatter#formatStatementAmount(BigDecimal)} mask.
 *   <li><b>Fatal keyed reads</b> &mdash; a missing customer or account ({@code '23'}) on the keyed
 *       reads is fatal here (only {@code '00'} is accepted), surfacing as an {@link
 *       IoStatusException}.
 *   <li><b>Delegation</b> &mdash; all input I/O flows through {@link
 *       FileIoService#process(WorkArea)} in the per-statement order {@code XREFFILE} read &rarr;
 *       {@code CUSTFILE} keyed read &rarr; {@code ACCTFILE} keyed read.
 * </ul>
 *
 * <p><strong>Note on the transaction buffer.</strong> The production {@code run(...)} reads the
 * entire transaction file <em>first</em> (the collapsed {@code 8100}/{@code 8500} table build) and
 * abends on an empty transaction file (the first {@code TRNXFILE} read must not be end-of-file), so
 * every fixture &mdash; even the "no statements" case &mdash; supplies at least one transaction.
 */
@ExtendWith(MockitoExtension.class)
class StatementGenerationServiceTest {

  // ----- Byte-faithful literals reconstructed from CBSTM03A WORKING-STORAGE
  // -----------------------

  /**
   * {@code ST-LINE0}: 31 stars + {@code START OF STATEMENT} (the COBOL {@code FILLER VALUE ALL
   * 'START OF STATEMENT' PIC X(18)}) + 31 stars = 80 characters. Reconstructed here because the
   * production constant is {@code private}; {@code emitStmt} pads to 80 and this value is already
   * 80, so the emitted banner is byte-identical.
   */
  private static final String START_OF_STATEMENT_BANNER =
      "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

  /** {@code HTML-L01}: the verbatim HTML document-type header line (before 100-char padding). */
  private static final String HTML_DOCTYPE = "<!DOCTYPE html>";

  /**
   * {@code ST-LINE8} label prefix {@code 'Current Balance :'} (width 20). The 13-character edited
   * {@code ST-CURR-BAL} immediately follows it, so on the emitted line the balance occupies columns
   * {@code [20, 33)}.
   */
  private static final String LABEL_CURR_BAL = "Current Balance    :";

  /** Column where the 13-character {@code ST-CURR-BAL} field begins on {@code ST-LINE8}. */
  private static final int CURR_BAL_START = 20;

  /** Width of the edited {@code ST-CURR-BAL} field ({@code PIC 9(9).99-}). */
  private static final int CURR_BAL_WIDTH = 13;

  /** Fixed record length of the plain-text statement file ({@code STMT-FILE PIC X(80)}). */
  private static final int STMT_LRECL = 80;

  /** Card-number / transaction-id fixed width ({@code PIC X(16)}). */
  private static final int CARD_TRAN_KEY_WIDTH = 16;

  // ----- Mocked collaborator (the sole input-I/O gateway, <- CBSTM03B) + service under test
  // -------

  /**
   * The only mock: the {@code CBSTM03B} translation. No repository is mocked because the service
   * never touches a repository &mdash; every read flows through this bean's {@link WorkArea}.
   */
  @Mock private FileIoService fileIoService;

  /** System under test, rebuilt fresh per test (constructor injection of the single mock). */
  private StatementGenerationService service;

  /** Captured plain-text statement lines (the {@code stmtSink}). */
  private List<String> stmt;

  /** Captured HTML statement lines (the {@code htmlSink}). */
  private List<String> html;

  /** Sequential cursor over the stubbed {@code XREFFILE}, (re)created on each {@code OPEN}. */
  private Iterator<CardXref> xrefCursor;

  /** Sequential cursor over the stubbed {@code TRNXFILE}, (re)created on each {@code OPEN}. */
  private Iterator<Transaction> trnxCursor;

  @BeforeEach
  void setUp() {
    service = new StatementGenerationService(fileIoService);
    stmt = new ArrayList<>();
    html = new ArrayList<>();
  }

  // ----- Fixture builders (domain entities use Lombok no-arg ctor + setters)
  // ----------------------

  /** Builds a card cross-reference row linking a card number to its customer and account ids. */
  private static CardXref xref(String cardNum, long custId, long acctId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(cardNum);
    xref.setXrefCustId(custId);
    xref.setXrefAcctId(acctId);
    return xref;
  }

  /**
   * Builds a customer master record. The name parts are deliberately space-free so the COBOL {@code
   * STRING ... DELIMITED BY ' '} name assembly yields exactly {@code first + ' ' + middle + ' ' +
   * last}; the remaining fields carry representative fixed-width values.
   */
  private static Customer customer(long id, String first, String middle, String last) {
    Customer customer = new Customer();
    customer.setCustId(id);
    customer.setCustFirstName(first);
    customer.setCustMiddleName(middle);
    customer.setCustLastName(last);
    customer.setCustAddrLine1("123 MAIN STREET");
    customer.setCustAddrLine2("SUITE 100");
    customer.setCustAddrLine3("ANYTOWN");
    customer.setCustAddrStateCd("WA");
    customer.setCustAddrCountryCd("USA");
    customer.setCustAddrZip("99999");
    customer.setCustFicoCreditScore(750L);
    return customer;
  }

  /** Builds an account master record with its primary key and a scale-aware current balance. */
  private static Account account(long id, String balance) {
    Account account = new Account();
    account.setAcctId(id);
    account.setAcctCurrBal(new BigDecimal(balance));
    return account;
  }

  /**
   * Builds a transaction record (the fields the statement renders: id, card, description, amount).
   */
  private static Transaction tran(String tranId, String cardNum, String desc, String amount) {
    Transaction tran = new Transaction();
    tran.setTranId(tranId);
    tran.setTranCardNum(cardNum);
    tran.setTranDesc(desc);
    tran.setTranAmt(new BigDecimal(amount));
    return tran;
  }

  // ----- FileIoService stub: drives the CBSTM03B cursor behavior via the WorkArea ----------------

  /**
   * Installs the stubbed {@link FileIoService#process(WorkArea)} answer that reproduces the legacy
   * {@code CBSTM03B} dispatch on ddname + operation, mutating the {@link WorkArea} in place:
   *
   * <ul>
   *   <li>{@code TRNXFILE}: {@code OPEN} (re)creates a sequential cursor over {@code trans}; {@code
   *       READ} advances it (status {@code '00'} with the record, or {@code '10'} at end-of-file);
   *       {@code CLOSE} discards it. Transactions are served in the exact order given &mdash;
   *       intentionally <em>unsorted</em> &mdash; so the assertions prove the service's own {@code
   *       (cardNum, tranId)} sort rather than the fixture order.
   *   <li>{@code XREFFILE}: same sequential lifecycle over {@code xrefs}, but the cursor is seeded
   *       in ascending {@code xrefCardNum} order, faithfully mirroring the production {@code
   *       FileIoService} (which reads the cross-reference via the bounded-memory streaming finder
   *       {@code streamAllByOrderByXrefCardNumAsc()}).
   *   <li>{@code CUSTFILE}/{@code ACCTFILE}: {@code OPEN}/{@code CLOSE} return {@code '00'}; {@code
   *       READ_KEY} parses the zoned-numeric key exactly as {@code CBSTM03B} did ({@code
   *       LK-M03B-KEY (1:LK-M03B-KEY-LN)}) and looks it up &mdash; a hit returns {@code '00'} with
   *       the entity, a miss returns {@code '23'} (record-not-found), never an exception.
   * </ul>
   *
   * @param xrefs the cross-reference rows to stream from {@code XREFFILE} (any order; sorted here)
   * @param custs the customer master keyed by customer id ({@code XREF-CUST-ID})
   * @param accts the account master keyed by account id ({@code XREF-ACCT-ID})
   * @param trans the transactions to stream from {@code TRNXFILE} (served in the given order)
   */
  private void configureFileIo(
      List<CardXref> xrefs,
      Map<Long, Customer> custs,
      Map<Long, Account> accts,
      List<Transaction> trans) {
    List<CardXref> orderedXrefs = new ArrayList<>(xrefs);
    orderedXrefs.sort(Comparator.comparing(CardXref::getXrefCardNum));
    List<Transaction> trnxData = new ArrayList<>(trans);

    doAnswer(
            invocation -> {
              WorkArea area = invocation.getArgument(0);
              String ddname = area.getDdname();
              WorkArea.Operation operation = area.getOperation();
              if (WorkArea.TRNXFILE.equals(ddname)) {
                if (operation == WorkArea.Operation.OPEN) {
                  trnxCursor = trnxData.iterator();
                  area.setReturnCode(FileIoService.STATUS_OK);
                } else if (operation == WorkArea.Operation.READ) {
                  advanceTransaction(area);
                } else if (operation == WorkArea.Operation.CLOSE) {
                  trnxCursor = null;
                  area.setReturnCode(FileIoService.STATUS_OK);
                }
              } else if (WorkArea.XREFFILE.equals(ddname)) {
                if (operation == WorkArea.Operation.OPEN) {
                  xrefCursor = orderedXrefs.iterator();
                  area.setReturnCode(FileIoService.STATUS_OK);
                } else if (operation == WorkArea.Operation.READ) {
                  advanceXref(area);
                } else if (operation == WorkArea.Operation.CLOSE) {
                  xrefCursor = null;
                  area.setReturnCode(FileIoService.STATUS_OK);
                }
              } else if (WorkArea.CUSTFILE.equals(ddname)) {
                lookupKeyed(area, operation, custs);
              } else if (WorkArea.ACCTFILE.equals(ddname)) {
                lookupKeyed(area, operation, accts);
              }
              return null;
            })
        .when(fileIoService)
        .process(any(WorkArea.class));
  }

  /**
   * Advances the {@code TRNXFILE} cursor: record + {@code '00'}, or {@code '10'} at end-of-file.
   */
  private void advanceTransaction(WorkArea area) {
    if (trnxCursor != null && trnxCursor.hasNext()) {
      area.setRecord(trnxCursor.next());
      area.setReturnCode(FileIoService.STATUS_OK);
    } else {
      area.setRecord(null);
      area.setReturnCode(FileIoService.STATUS_EOF);
    }
  }

  /**
   * Advances the {@code XREFFILE} cursor: record + {@code '00'}, or {@code '10'} at end-of-file.
   */
  private void advanceXref(WorkArea area) {
    if (xrefCursor != null && xrefCursor.hasNext()) {
      area.setRecord(xrefCursor.next());
      area.setReturnCode(FileIoService.STATUS_OK);
    } else {
      area.setRecord(null);
      area.setReturnCode(FileIoService.STATUS_EOF);
    }
  }

  /**
   * Reproduces a {@code CBSTM03B} keyed file ({@code CUSTFILE}/{@code ACCTFILE}): {@code
   * OPEN}/{@code CLOSE} succeed; {@code READ_KEY} parses the leading {@code keyLength} key digits
   * to a {@link Long} and looks the entity up &mdash; hit returns {@code '00'} with the record,
   * miss returns {@code '23'}.
   */
  private static void lookupKeyed(WorkArea area, WorkArea.Operation operation, Map<Long, ?> store) {
    if (operation == WorkArea.Operation.READ_KEY) {
      Object found = store.get(parseKey(area));
      if (found != null) {
        area.setRecord(found);
        area.setReturnCode(FileIoService.STATUS_OK);
      } else {
        area.setRecord(null);
        area.setReturnCode(FileIoService.STATUS_RECORD_NOT_FOUND);
      }
    } else {
      // OPEN / CLOSE on a keyed (random-access) file.
      area.setReturnCode(FileIoService.STATUS_OK);
    }
  }

  /**
   * Parses the work-area lookup key exactly as {@code CBSTM03B} ({@code LK-M03B-KEY (1:KEY-LN)}).
   */
  private static Long parseKey(WorkArea area) {
    return Long.parseLong(area.getKey().substring(0, area.getKeyLength()).trim());
  }

  // ----- Assertion helpers
  // ------------------------------------------------------------------------

  /**
   * Reconstructs the edited {@code ST-CURR-BAL} field ({@code PIC 9(9).99-}) for the given amount:
   * a 9-digit zero-filled integer part, a literal dot, a 2-digit fraction truncated with {@link
   * RoundingMode#DOWN}, and a trailing sign ({@code '-'} when negative, a space otherwise) &mdash;
   * 13 characters total. This mirrors the production {@code formatCurrBalance} (which is {@code
   * private}, so it is rebuilt here from the same public {@link CobolStringUtils} primitive rather
   * than hardcoding the padded digits).
   */
  private static String expectedCurrBalance(String amount) {
    BigDecimal scaled = new BigDecimal(amount).setScale(2, RoundingMode.DOWN);
    boolean negative = scaled.signum() < 0;
    String plain = scaled.abs().toPlainString();
    int dot = plain.indexOf('.');
    return CobolStringUtils.padLeftZeros(plain.substring(0, dot), 9)
        + "."
        + plain.substring(dot + 1)
        + (negative ? "-" : " ");
  }

  /**
   * Returns, in emission order, the transaction ids whose detail lines appear in {@code stmt}. A
   * detail line ({@code ST-LINE14}) begins with the 16-character left-justified {@code ST-TRANID},
   * so each line is matched against the supplied (16-wide padded) ids.
   */
  private List<String> detailLineTranIds(List<String> ids) {
    List<String> seen = new ArrayList<>();
    for (String line : stmt) {
      for (String id : ids) {
        if (line.startsWith(CobolStringUtils.padRight(id, CARD_TRAN_KEY_WIDTH))) {
          seen.add(id);
        }
      }
    }
    return seen;
  }

  /**
   * Returns the single {@code stmt} line that carries the current-balance label ({@code ST-LINE8}).
   */
  private String currentBalanceLine() {
    return stmt.stream()
        .filter(line -> line.startsWith(LABEL_CURR_BAL))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no current-balance line was emitted"));
  }

  /** Returns the single {@code stmt} line that carries the expense total ({@code ST-LINE14A}). */
  private String totalLine() {
    return stmt.stream()
        .filter(line -> line.startsWith("Total EXP:"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no total-expense line was emitted"));
  }

  /** Returns the single {@code stmt} detail line that begins with the given (16-wide) tran id. */
  private String lineStartingWith(String tranId) {
    String prefix = CobolStringUtils.padRight(tranId, CARD_TRAN_KEY_WIDTH);
    return stmt.stream()
        .filter(line -> line.startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no detail line for " + tranId));
  }

  // ===== Phase A: EOF on the first XREF read => no statements
  // ======================================

  @Test
  void eof_on_first_xref_read_produces_no_statements() {
    // A single transaction is required so the up-front transaction-table build does not abend on an
    // empty file; with no cross-references the mainline loop body never executes (the IF
    // END-OF-FILE = 'N' guard short-circuits), so neither sink receives a line.
    configureFileIo(
        List.of(),
        Map.of(),
        Map.of(),
        List.of(tran("TRX0000000000001", "4111111111111111", "PURCHASE", "10.00")));

    service.run(stmt::add, html::add);

    assertThat(stmt).isEmpty();
    assertThat(html).isEmpty();
  }

  // ===== Phase B: happy path => one text + one HTML statement
  // ======================================

  @Test
  void single_xref_produces_one_text_and_html_statement() {
    configureFileIo(
        List.of(xref("4111111111111111", 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(1L, account(1L, "100.00")),
        List.of(
            tran("TRX0000000000001", "4111111111111111", "PURCHASE", "10.00"),
            tran("TRX0000000000002", "4111111111111111", "REFUND", "25.50")));

    service.run(stmt::add, html::add);

    // The plain-text START-OF-STATEMENT banner is emitted byte-for-byte (already exactly 80 chars).
    assertThat(stmt).contains(START_OF_STATEMENT_BANNER);
    // The HTML statement is produced and opens with the document-type header (right-padded to 100).
    assertThat(html).isNotEmpty();
    assertThat(html).contains(CobolStringUtils.fixedWidth(HTML_DOCTYPE, 100));
    // ST-NAME assembles to "first middle last" (each part DELIMITED BY ' ').
    assertThat(stmt).anyMatch(line -> line.strip().equals("JOHN Q PUBLIC"));
  }

  @Test
  void text_statement_lines_respect_lrecl_80() {
    configureFileIo(
        List.of(xref("4111111111111111", 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(1L, account(1L, "100.00")),
        List.of(tran("TRX0000000000001", "4111111111111111", "PURCHASE", "10.00")));

    service.run(stmt::add, html::add);

    assertThat(stmt).isNotEmpty();
    assertThat(stmt).allSatisfy(line -> assertThat(line).hasSize(STMT_LRECL));
  }

  // ===== Phase C: in-memory transactions sorted (cardNum ASC, tranId ASC)
  // ==========================

  @Test
  void transactions_are_grouped_and_sorted_by_card_then_tran_id() {
    String card1 = "4111111111111111";
    String card2 = "4222222222222222";

    // Cross-references supplied with the higher card first; the stub seeds the cursor ascending
    // (mirroring the production FileIoService), so card1's statement must precede card2's. The
    // transactions are supplied fully scrambled across both cards and tran ids, so an ascending
    // detail order can only come from the service's own (cardNum, tranId) sort.
    configureFileIo(
        List.of(xref(card2, 2L, 2L), xref(card1, 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC"), 2L, customer(2L, "JANE", "R", "DOE")),
        Map.of(1L, account(1L, "100.00"), 2L, account(2L, "200.00")),
        List.of(
            tran("TRX0000000000004", card2, "D", "4.00"),
            tran("TRX0000000000002", card1, "B", "2.00"),
            tran("TRX0000000000003", card2, "C", "3.00"),
            tran("TRX0000000000001", card1, "A", "1.00")));

    service.run(stmt::add, html::add);

    List<String> expectedOrder =
        List.of("TRX0000000000001", "TRX0000000000002", "TRX0000000000003", "TRX0000000000004");
    assertThat(detailLineTranIds(expectedOrder)).containsExactlyElementsOf(expectedOrder);
  }

  // ===== Phase D: statement total = sum of the card's transaction amounts
  // ==========================

  @Test
  void statement_total_equals_sum_of_card_transaction_amounts() {
    String card = "4111111111111111";
    configureFileIo(
        List.of(xref(card, 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(1L, account(1L, "100.00")),
        List.of(
            tran("TRX0000000000001", card, "A", "10.00"),
            tran("TRX0000000000002", card, "B", "25.50"),
            tran("TRX0000000000003", card, "C", "4.49")));

    service.run(stmt::add, html::add);

    // 10.00 + 25.50 + 4.49 = 39.99, rendered with the trailing-sign zero-suppressed statement mask.
    String expectedTotal = NumberFormatter.formatStatementAmount(new BigDecimal("39.99"));
    assertThat(totalLine()).contains(expectedTotal);
  }

  // ===== Phase E: edited-amount fidelity
  // ===========================================================

  @Test
  void current_balance_uses_leading_zero_pic_9_9_99_minus() {
    String card = "4111111111111111";

    // Positive balance => leading-zero 13-char field ending in a blank sign.
    configureFileIo(
        List.of(xref(card, 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(1L, account(1L, "12.34")),
        List.of(tran("TRX0000000000001", card, "A", "10.00")));
    service.run(stmt::add, html::add);

    String positive =
        currentBalanceLine().substring(CURR_BAL_START, CURR_BAL_START + CURR_BAL_WIDTH);
    assertThat(positive).isEqualTo(expectedCurrBalance("12.34"));
    assertThat(positive).endsWith(" ");

    // Negative balance => same field width, trailing '-'.
    stmt = new ArrayList<>();
    html = new ArrayList<>();
    configureFileIo(
        List.of(xref(card, 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(1L, account(1L, "-12.34")),
        List.of(tran("TRX0000000000001", card, "A", "10.00")));
    service.run(stmt::add, html::add);

    String negative =
        currentBalanceLine().substring(CURR_BAL_START, CURR_BAL_START + CURR_BAL_WIDTH);
    assertThat(negative).isEqualTo(expectedCurrBalance("-12.34"));
    assertThat(negative).endsWith("-");
  }

  @Test
  void transaction_amounts_use_statement_amount_format() {
    String card = "4111111111111111";
    BigDecimal positiveAmt = new BigDecimal("25.50");
    BigDecimal negativeAmt = new BigDecimal("-12.34");
    configureFileIo(
        List.of(xref(card, 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(1L, account(1L, "100.00")),
        List.of(
            tran("TRX0000000000001", card, "PURCHASE", positiveAmt.toPlainString()),
            tran("TRX0000000000002", card, "REFUND", negativeAmt.toPlainString())));

    service.run(stmt::add, html::add);

    // Positive: zero-suppressed, trailing blank sign, width 13, no commas.
    assertThat(lineStartingWith("TRX0000000000001"))
        .contains(NumberFormatter.formatStatementAmount(positiveAmt));
    // Negative: same mask with a trailing '-'.
    String negativeFormatted = NumberFormatter.formatStatementAmount(negativeAmt);
    assertThat(negativeFormatted).endsWith("-");
    assertThat(lineStartingWith("TRX0000000000002")).contains(negativeFormatted);
  }

  // ===== Phase F: missing customer / account are FATAL ('23')
  // ======================================

  @Test
  void missing_customer_throws_io_status_exception() {
    String card = "4111111111111111";
    // The cross-reference points at customer 99, but the customer store is empty => CUSTFILE '23',
    // which CBSTM03A treats as fatal (2000-CUSTFILE-GET accepts only '00').
    configureFileIo(
        List.of(xref(card, 99L, 1L)),
        Map.of(),
        Map.of(1L, account(1L, "100.00")),
        List.of(tran("TRX0000000000001", card, "A", "10.00")));

    Throwable thrown = catchThrowable(() -> service.run(stmt::add, html::add));

    assertThat(thrown).isInstanceOf(IoStatusException.class);
    IoStatusException ex = (IoStatusException) thrown;
    assertThat(ex.getFileName()).isEqualTo(WorkArea.CUSTFILE);
    assertThat(ex.getFileStatus()).isEqualTo(FileIoService.STATUS_RECORD_NOT_FOUND);
  }

  @Test
  void missing_account_throws_io_status_exception() {
    String card = "4111111111111111";
    // The customer resolves, but the account store is empty => ACCTFILE '23', fatal in CBSTM03A
    // (3000-ACCTFILE-GET accepts only '00').
    configureFileIo(
        List.of(xref(card, 1L, 99L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(),
        List.of(tran("TRX0000000000001", card, "A", "10.00")));

    Throwable thrown = catchThrowable(() -> service.run(stmt::add, html::add));

    assertThat(thrown).isInstanceOf(IoStatusException.class);
    IoStatusException ex = (IoStatusException) thrown;
    assertThat(ex.getFileName()).isEqualTo(WorkArea.ACCTFILE);
    assertThat(ex.getFileStatus()).isEqualTo(FileIoService.STATUS_RECORD_NOT_FOUND);
  }

  // ===== Phase G: subroutine-injection contract (<- CALL 'CBSTM03B' USING WS-M03B-AREA)
  // ===========

  @Test
  void input_io_is_delegated_to_file_io_service_not_repositories() {
    // No repository is mocked or wired (there are none to wire): all input I/O must flow through
    // the
    // injected FileIoService work area, mirroring CALL 'CBSTM03B' USING WS-M03B-AREA.
    String card = "4111111111111111";
    configureFileIo(
        List.of(xref(card, 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(1L, account(1L, "100.00")),
        List.of(tran("TRX0000000000001", card, "A", "10.00")));

    service.run(stmt::add, html::add);

    verify(fileIoService, atLeastOnce()).process(any(WorkArea.class));
  }

  @Test
  void xref_then_cust_then_acct_read_order_per_statement() {
    String card = "4111111111111111";
    configureFileIo(
        List.of(xref(card, 1L, 1L)),
        Map.of(1L, customer(1L, "JOHN", "Q", "PUBLIC")),
        Map.of(1L, account(1L, "100.00")),
        List.of(tran("TRX0000000000001", card, "A", "10.00")));

    service.run(stmt::add, html::add);

    ArgumentCaptor<WorkArea> captor = ArgumentCaptor.forClass(WorkArea.class);
    verify(fileIoService, atLeastOnce()).process(captor.capture());
    List<String> reads = new ArrayList<>();
    for (WorkArea area : captor.getAllValues()) {
      reads.add(area.getDdname() + "|" + area.getOperation());
    }
    int xrefRead = reads.indexOf(WorkArea.XREFFILE + "|" + WorkArea.Operation.READ);
    int custRead = reads.indexOf(WorkArea.CUSTFILE + "|" + WorkArea.Operation.READ_KEY);
    int acctRead = reads.indexOf(WorkArea.ACCTFILE + "|" + WorkArea.Operation.READ_KEY);
    assertThat(xrefRead).isGreaterThanOrEqualTo(0);
    assertThat(custRead).isGreaterThan(xrefRead);
    assertThat(acctRead).isGreaterThan(custRead);
  }
}

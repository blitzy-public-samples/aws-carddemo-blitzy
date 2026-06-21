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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

/**
 * Behavioral / parity unit tests for {@link DailyTransactionPostService}, the Java translation of
 * the legacy COBOL batch program {@code CBTRN01C} (source {@code legacy/app/cbl/CBTRN01C.cbl}).
 *
 * <p><strong>What {@code CBTRN01C} actually does (and does not do).</strong> Despite its name, the
 * program is a <em>validation-only</em> pass: for every record of the sequential daily-transaction
 * file it verifies that the card resolves through the card cross-reference and that the referenced
 * account exists, emitting COBOL {@code DISPLAY} diagnostics. It performs <em>no</em> posting,
 * <em>no</em> writes/rewrites, and sets <em>no</em> {@code RETURN-CODE} (the COBOL {@code GOBACK}
 * leaves RC at its default {@code 0}; {@link DailyTransactionPostService#run()} is therefore {@code
 * void}). These tests pin that contract by proving — across every scenario — that no store is ever
 * mutated (Agent Action Plan &sect;0.4.1, &sect;0.7.1).
 *
 * <p><strong>Three observable control-flow branches (AAP &sect;0.6.4 FILE STATUS
 * semantics).</strong>
 *
 * <ul>
 *   <li><em>(a) card found + account found</em> &mdash; success diagnostics only; the account
 *       <em>is</em> read.
 *   <li><em>(b) card found + account missing</em> &mdash; {@code INVALID ACCOUNT NUMBER FOUND}
 *       (from {@code 3000-READ-ACCOUNT}) and {@code ACCOUNT <id> NOT FOUND} (from {@code
 *       MAIN-PARA}); the account <em>is</em> read.
 *   <li><em>(c) card cross-reference missing</em> &mdash; {@code INVALID CARD NUMBER FOR XREF}
 *       (from {@code 2000-LOOKUP-XREF}) and {@code CARD NUMBER <num> COULD NOT BE VERIFIED.
 *       SKIPPING TRANSACTION ID-<id>} (from {@code MAIN-PARA}); the account is <em>never</em> read
 *       because {@code 3000-READ-ACCOUNT} is skipped.
 * </ul>
 *
 * A card or account that cannot be resolved is a logged <em>skip</em> (VSAM {@code INVALID KEY} /
 * FILE STATUS {@code '23'}), never an abend; the only abend is an unrecoverable I/O failure on the
 * {@code DALYTRAN} cursor, which surfaces as an {@link IoStatusException} carrying file name {@code
 * "DALYTRAN"}.
 *
 * <p><strong>Read-ahead idiom.</strong> The legacy {@code MAIN-PARA} loop advances the cursor at
 * the top of each iteration and runs the verification on every iteration of the outer guard; the
 * COBOL {@code READ ... INTO} leaves the record area unchanged at end-of-file, so the last record
 * is re-verified exactly once on the EOF-detecting iteration. Consequently a single-record file
 * drives the cross-reference and account reads twice. These tests deliberately assert on the
 * <em>presence</em> of messages and on {@code atLeastOnce()} / captured-argument reads rather than
 * on exact invocation counts, so they remain faithful to that intentional duplication.
 *
 * <p>Pure Mockito unit tests: the three repositories are mocked, the service is constructed
 * directly (no Spring context, no database), and the SLF4J {@code DISPLAY} output is captured with
 * a Logback {@link ListAppender} attached for the duration of each test.
 */
@ExtendWith(MockitoExtension.class)
class DailyTransactionPostServiceTest {

  @Mock private DailyTransactionRepository dailyTransactionRepository;
  @Mock private CardXrefRepository cardXrefRepository;
  @Mock private AccountRepository accountRepository;

  /** System under test, constructed in {@link #setUp()} from the three mocked repositories. */
  private DailyTransactionPostService service;

  /** The Logback logger backing {@link DailyTransactionPostService}'s SLF4J {@code LOG}. */
  private Logger serviceLogger;

  /** Captures the service's {@code DISPLAY} output (one event per emitted line). */
  private ListAppender<ILoggingEvent> logAppender;

  /** The logger level before the test, restored in {@link #tearDown()} to avoid leakage. */
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    // Build the SUT from the mocks (the production constructor takes exactly these three
    // repositories — no Transaction/TCATBAL repositories, which is itself proof the pass never
    // posts).
    service =
        new DailyTransactionPostService(
            dailyTransactionRepository, cardXrefRepository, accountRepository);

    // Attach a ListAppender so the byte-faithful COBOL DISPLAY lines can be asserted. DEBUG keeps
    // the INFO/ERROR lines the service emits while remaining quiet about lower-level framework
    // chatter.
    serviceLogger = (Logger) LoggerFactory.getLogger(DailyTransactionPostService.class);
    originalLevel = serviceLogger.getLevel();
    serviceLogger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    serviceLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    serviceLogger.detachAppender(logAppender);
    logAppender.stop();
    serviceLogger.setLevel(originalLevel);
  }

  // -------------------------------------------------------------------------------------------
  // Phase A — branch (a): card and account both found.
  // -------------------------------------------------------------------------------------------

  @Test
  void run_with_valid_card_and_existing_account_logs_success_and_reads_account() {
    String cardNum = "4111111111111111";
    Long acctId = 123L;
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(dailyTransaction("TXN0000000000001", cardNum)));
    when(cardXrefRepository.findById(cardNum))
        .thenReturn(Optional.of(cardXref(cardNum, acctId, 100_000_001L)));
    when(accountRepository.findById(acctId)).thenReturn(Optional.of(account(acctId)));

    service.run();

    // Success path: the four xref-read diagnostics and the account-read confirmation, with none of
    // the failure messages from the other two branches.
    assertThat(messages())
        .contains("SUCCESSFUL READ OF XREF", "SUCCESSFUL READ OF ACCOUNT FILE")
        .noneMatch(line -> line.contains("NOT FOUND"))
        .noneMatch(line -> line.contains("COULD NOT BE VERIFIED"))
        .noneMatch(line -> line.contains("INVALID"))
        // Security/PII regression guard: the full PAN is never logged — only the masked last-four
        // form appears, and only at DEBUG (AAP §0.6.6, §0.7.3).
        .noneMatch(line -> line.contains(cardNum));
    // The account WAS read (3000-READ-ACCOUNT ran); atLeastOnce tolerates the read-ahead re-verify.
    verify(accountRepository, atLeastOnce()).findById(acctId);
    assertThat(service.getXrefReadStatus()).isZero();
    assertThat(service.getAcctReadStatus()).isZero();
    assertNoWrites();
  }

  // -------------------------------------------------------------------------------------------
  // Phase B — branch (b): card found, account missing.
  // -------------------------------------------------------------------------------------------

  @Test
  void run_with_valid_card_but_missing_account_logs_not_found_and_still_reads_account() {
    String cardNum = "4222222222222222";
    Long acctId = 999L;
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(dailyTransaction("TXN0000000000002", cardNum)));
    when(cardXrefRepository.findById(cardNum))
        .thenReturn(Optional.of(cardXref(cardNum, acctId, 100_000_002L)));
    when(accountRepository.findById(acctId)).thenReturn(Optional.empty());

    service.run();

    // 3000-READ-ACCOUNT logs the INVALID line; MAIN-PARA then logs 'ACCOUNT <id> NOT FOUND' with a
    // single space on each side of the id, matching the COBOL DISPLAY operand spacing.
    assertThat(messages())
        .contains("INVALID ACCOUNT NUMBER FOUND", "ACCOUNT " + acctId + " NOT FOUND");
    // The account read WAS attempted (we entered the xref-found branch).
    verify(accountRepository, atLeastOnce()).findById(acctId);
    assertThat(service.getXrefReadStatus()).isZero();
    assertThat(service.getAcctReadStatus()).isEqualTo(4);
    assertNoWrites();
  }

  // -------------------------------------------------------------------------------------------
  // Phase C — branch (c): card cross-reference missing → account NEVER read.
  // -------------------------------------------------------------------------------------------

  @Test
  void run_with_unknown_card_logs_skip_and_never_reads_account() {
    String cardNum = "4000000000000000";
    String tranId = "TXN0000000000003";
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(dailyTransaction(tranId, cardNum)));
    when(cardXrefRepository.findById(cardNum)).thenReturn(Optional.empty());

    service.run();

    // The skip diagnostic now masks the PAN to its last four digits (AAP §0.6.6, §0.7.3); the
    // non-PAN transaction id is retained for triage of the skip.
    String maskedCard = "*".repeat(cardNum.length() - 4) + cardNum.substring(cardNum.length() - 4);
    assertThat(messages())
        .contains(
            "INVALID CARD NUMBER FOR XREF",
            "CARD NUMBER "
                + maskedCard
                + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-"
                + tranId);
    // Security/PII regression guard: the full PAN must never appear in any captured log line.
    assertThat(messages()).noneMatch(line -> line.contains(cardNum));
    // Key control-flow parity: 3000-READ-ACCOUNT is skipped when the xref is missing, so the
    // account file is never read at all — not even on the read-ahead re-verification.
    verify(accountRepository, never()).findById(any());
    assertThat(service.getXrefReadStatus()).isEqualTo(4);
    assertNoWrites();
  }

  // -------------------------------------------------------------------------------------------
  // Phase D — sequential read order.
  // -------------------------------------------------------------------------------------------

  @Test
  void run_reads_daily_transactions_in_ascending_dalytran_id_order() {
    // An empty file is sufficient to capture the Sort used to open the cursor; the service still
    // performs exactly one (harmless) blank-record verification pass, which reads nothing further.
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of());

    service.run();

    ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
    verify(dailyTransactionRepository).findAll(sortCaptor.capture());
    Sort capturedSort = sortCaptor.getValue();
    assertThat(capturedSort).isEqualTo(Sort.by("dalytranId"));
    Sort.Order order = capturedSort.getOrderFor("dalytranId");
    assertThat(order).isNotNull();
    assertThat(order.getProperty()).isEqualTo("dalytranId");
    assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    assertNoWrites();
  }

  // -------------------------------------------------------------------------------------------
  // Phase E — mixed multi-record run: each record flows through its own branch independently.
  // -------------------------------------------------------------------------------------------

  @Test
  void run_processes_each_record_independently_through_correct_branch() {
    // Branch (a): card + account found.
    String cardA = "4111111111111111";
    Long acctA = 111L;
    // Branch (b): card found, account missing.
    String cardB = "4222222222222222";
    Long acctB = 222L;
    // Branch (c): card cross-reference missing. Placed LAST so the read-ahead re-verification lands
    // on the skip path and therefore never triggers an account read for this record.
    String cardC = "4333333333333333";
    String tranC = "TXN0000000000003";

    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(
            List.of(
                dailyTransaction("TXN0000000000001", cardA),
                dailyTransaction("TXN0000000000002", cardB),
                dailyTransaction(tranC, cardC)));
    when(cardXrefRepository.findById(cardA))
        .thenReturn(Optional.of(cardXref(cardA, acctA, 100_000_001L)));
    when(cardXrefRepository.findById(cardB))
        .thenReturn(Optional.of(cardXref(cardB, acctB, 100_000_002L)));
    when(cardXrefRepository.findById(cardC)).thenReturn(Optional.empty());
    when(accountRepository.findById(acctA)).thenReturn(Optional.of(account(acctA)));
    when(accountRepository.findById(acctB)).thenReturn(Optional.empty());

    service.run();

    // Each record emitted exactly the message set of its branch. The (c) skip diagnostic masks the
    // PAN to its last four digits (AAP §0.6.6, §0.7.3).
    String maskedCardC = "*".repeat(cardC.length() - 4) + cardC.substring(cardC.length() - 4);
    assertThat(messages())
        .contains(
            "SUCCESSFUL READ OF ACCOUNT FILE", // (a) account found
            "INVALID ACCOUNT NUMBER FOUND", // (b) account missing
            "ACCOUNT " + acctB + " NOT FOUND", // (b) account missing
            "INVALID CARD NUMBER FOR XREF", // (c) xref missing
            "CARD NUMBER "
                + maskedCardC
                + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-"
                + tranC); // (c) xref missing
    // Security/PII regression guard: no full PAN (for any of the three cards) ever appears in the
    // captured log output — only masked last-four forms are emitted.
    assertThat(messages())
        .noneMatch(line -> line.contains(cardA))
        .noneMatch(line -> line.contains(cardB))
        .noneMatch(line -> line.contains(cardC));

    // The account was read only for the two xref-found records (a, b) and never for the
    // xref-missing record (c). Capturing every account-key read proves both facts at once.
    ArgumentCaptor<Long> acctIdCaptor = ArgumentCaptor.forClass(Long.class);
    verify(accountRepository, atLeastOnce()).findById(acctIdCaptor.capture());
    assertThat(acctIdCaptor.getAllValues()).containsExactlyInAnyOrder(acctA, acctB);
    assertNoWrites();
  }

  // -------------------------------------------------------------------------------------------
  // Phase F — validation-only proof: no posting, no writes, no return code.
  // -------------------------------------------------------------------------------------------

  @Test
  void run_never_writes_to_any_repository() {
    // A representative happy-path run (card + account found). CBTRN01C is validation-only: it
    // performs no posting and sets no RETURN-CODE — run() is void — so no store is ever mutated.
    String cardNum = "4111111111111111";
    Long acctId = 123L;
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(dailyTransaction("TXN0000000000001", cardNum)));
    when(cardXrefRepository.findById(cardNum))
        .thenReturn(Optional.of(cardXref(cardNum, acctId, 100_000_001L)));
    when(accountRepository.findById(acctId)).thenReturn(Optional.of(account(acctId)));

    service.run();

    // No inserts/updates on any store ...
    verify(dailyTransactionRepository, never()).save(any());
    verify(cardXrefRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    // ... and no deletes either.
    verify(dailyTransactionRepository, never()).delete(any());
    verify(cardXrefRepository, never()).delete(any());
    verify(accountRepository, never()).delete(any());
    verify(dailyTransactionRepository, never()).deleteAll();
    verify(cardXrefRepository, never()).deleteAll();
    verify(accountRepository, never()).deleteAll();
  }

  // -------------------------------------------------------------------------------------------
  // Phase G — abend on an unrecoverable DALYTRAN cursor I/O failure.
  // -------------------------------------------------------------------------------------------

  @Test
  void run_throws_io_status_exception_for_dalytran_when_cursor_fails() {
    // The sequential cursor is established by findAll(Sort) inside 0000-DALYTRAN-OPEN; a
    // data-access
    // failure there is the unrecoverable I/O condition that CBTRN01C handles via
    // Z-DISPLAY-IO-STATUS
    // + Z-ABEND-PROGRAM, translated here into an IoStatusException for the DALYTRAN file.
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenThrow(new DataAccessResourceFailureException("daily-transaction cursor failure"));

    assertThatThrownBy(() -> service.run())
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo("DALYTRAN");
              assertThat(io.getOperation()).isEqualTo("OPEN");
              assertThat(io.getFileStatus()).isEqualTo("99");
            });

    // The abend reproduces the operator DISPLAY sequence before the run unit terminates.
    assertThat(messages())
        .contains("ERROR OPENING DAILY TRANSACTION FILE", "ABENDING PROGRAM")
        .anyMatch(line -> line.startsWith("FILE STATUS IS: NNNN"));
    // The failure happened while opening the cursor, so no record verification or write occurred.
    verify(cardXrefRepository, never()).findById(any());
    verify(accountRepository, never()).findById(any());
    verify(dailyTransactionRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------------------------
  // Helpers.
  // -------------------------------------------------------------------------------------------

  /** Returns the captured {@code DISPLAY} lines in emission order. */
  private List<String> messages() {
    return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Asserts the validation pass never wrote to any of the three stores (no posting occurs). */
  private void assertNoWrites() {
    verify(dailyTransactionRepository, never()).save(any());
    verify(cardXrefRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
  }

  /**
   * Builds a minimal {@link DailyTransaction} for a scenario, setting the identity and lookup key
   * the validation pass relies on plus a few representative fields. The amount is a {@link
   * BigDecimal} — never {@code float}/{@code double} — honoring the project-wide decimal-fidelity
   * rule (AAP &sect;0.6.1) even though this validation pass performs no arithmetic.
   *
   * @param id the daily-transaction id ({@code DALYTRAN-ID}, primary key / sort key)
   * @param cardNum the card number ({@code DALYTRAN-CARD-NUM}, the cross-reference lookup key)
   * @return a populated daily-transaction record
   */
  private static DailyTransaction dailyTransaction(String id, String cardNum) {
    DailyTransaction txn = new DailyTransaction();
    txn.setDalytranId(id);
    txn.setDalytranCardNum(cardNum);
    txn.setDalytranTypeCd("01");
    txn.setDalytranCatCd("0001");
    txn.setDalytranAmt(new BigDecimal("100.00"));
    return txn;
  }

  /**
   * Builds a {@link CardXref} row resolving a card number to an account and customer.
   *
   * @param cardNum the card number ({@code XREF-CARD-NUM}, primary key)
   * @param acctId the account id ({@code XREF-ACCT-ID}) the account read will use
   * @param custId the customer id ({@code XREF-CUST-ID}) echoed in the success diagnostics
   * @return a populated cross-reference record
   */
  private static CardXref cardXref(String cardNum, Long acctId, Long custId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(cardNum);
    xref.setXrefAcctId(acctId);
    xref.setXrefCustId(custId);
    return xref;
  }

  /**
   * Builds a minimal {@link Account} whose presence the validation pass confirms by key (the
   * account fields themselves are never used by {@code CBTRN01C}).
   *
   * @param acctId the account id ({@code ACCT-ID}, primary key)
   * @return an account record carrying only its identity
   */
  private static Account account(Long acctId) {
    Account account = new Account();
    account.setAcctId(acctId);
    return account;
  }
}

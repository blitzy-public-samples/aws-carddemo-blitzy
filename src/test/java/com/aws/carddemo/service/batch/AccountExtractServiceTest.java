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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
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
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link AccountExtractService}, the batch
 * "read-and-print account master" service migrated with 100% behavioral parity from the legacy
 * COBOL program {@code CBACT01C} (behavioral spec {@code legacy/app/cbl/CBACT01C.cbl}; record
 * copybook {@code legacy/app/cpy/CVACT01Y.cpy}).
 *
 * <p>The service's only collaborator is the {@link AccountRepository}, mocked here, so no Spring
 * context, Testcontainers, or database is required. Because every COBOL {@code DISPLAY} maps to an
 * SLF4J log statement, the externally observable behavior is the sequence of emitted log lines; the
 * tests attach a Logback {@link ListAppender} to the service logger and assert on the captured
 * events. The parity invariants pinned by these tests (Agent Action Plan &sect;0.6.7 paragraph
 * traceability, &sect;0.7.1 control-flow preservation) are:
 *
 * <ul>
 *   <li>records are visited in ascending {@code acctId} order via {@code
 *       findAll(Sort.by("acctId"))} &mdash; the VSAM sequential {@code RECORD KEY (FD-ACCT-ID)}
 *       order;
 *   <li>each accepted record is emitted <em>twice</em> &mdash; first the labeled field-by-field
 *       block from {@code 1100-DISPLAY-ACCT-RECORD} (11 captions, ending with the 49-dash
 *       separator), then the whole-record image from the main-loop {@code DISPLAY ACCOUNT-RECORD}
 *       &mdash; in that order (the deliberate "DOUBLE emission" quirk);
 *   <li>the field block omits {@code ACCT-ADDR-ZIP} (only 11 of the 12 business fields are
 *       labeled), whereas the whole-record image <em>includes</em> the ZIP value;
 *   <li>multiple records are emitted in the order supplied (the loop never reorders);
 *   <li>an empty master terminates cleanly &mdash; FILE STATUS {@code '10'} (end of file) is normal
 *       loop termination, never an error; and
 *   <li>an unexpected {@link org.springframework.dao.DataAccessException} maps to an {@link
 *       IoStatusException} for file {@code "ACCTFILE"} with the COBOL abend status {@code "99"};
 *       the failing operation is {@code "OPEN"} when {@code 0000-ACCTFILE-OPEN} fails and {@code
 *       "READ"} when {@code 1000-ACCTFILE-GET-NEXT} fails.
 * </ul>
 *
 * <p>All monetary fields are built as {@link BigDecimal}; no {@code float}/{@code double} is used
 * anywhere (AAP &sect;0.6.1).
 */
@ExtendWith(MockitoExtension.class)
class AccountExtractServiceTest {

  /**
   * The 49-dash separator emitted by {@code 1100-DISPLAY-ACCT-RECORD} (CBACT01C L130), preserved
   * byte-for-byte to match {@code AccountExtractService}'s {@code RECORD_SEPARATOR}.
   */
  private static final String RECORD_SEPARATOR =
      "-------------------------------------------------";

  /** The {@code ACCTFILE} store, mocked so the service is exercised without a database. */
  @Mock private AccountRepository accountRepository;

  /** System under test, instantiated per test in {@link #setUp()} with the mocked repository. */
  private AccountExtractService service;

  /**
   * The Logback logger backing {@code AccountExtractService.LOG}, used to capture DISPLAY output.
   */
  private Logger serviceLogger;

  /** Captures every logging event the service emits during a single {@link #service} run. */
  private ListAppender<ILoggingEvent> logWatcher;

  /** The logger level before the test installed its own, restored in {@link #tearDown()}. */
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    service = new AccountExtractService(accountRepository);

    // Attach a ListAppender to the service logger and lower the level so INFO and ERROR events
    // (every COBOL DISPLAY the service emits) are both captured regardless of ambient config.
    serviceLogger = (Logger) LoggerFactory.getLogger(AccountExtractService.class);
    originalLevel = serviceLogger.getLevel();
    serviceLogger.setLevel(Level.DEBUG);
    logWatcher = new ListAppender<>();
    logWatcher.start();
    serviceLogger.addAppender(logWatcher);
  }

  @AfterEach
  void tearDown() {
    serviceLogger.detachAppender(logWatcher);
    logWatcher.stop();
    serviceLogger.setLevel(originalLevel);
  }

  // --- Phase A: control flow / ordering -------------------------------------------------------

  @Test
  void run_reads_accounts_using_ascending_account_id_sort() {
    Account acctA =
        account(
            100000001L,
            "Y",
            "1000.00",
            "5000.00",
            "2500.00",
            "2020-01-15",
            "2025-01-15",
            "2021-01-15",
            "100.00",
            "50.00",
            "99950",
            "GRP1");
    when(accountRepository.findAll(any(Sort.class))).thenReturn(List.of(acctA));
    ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);

    service.run();

    // The open paragraph reads the master via findAll(Sort) — the Java counterpart of the VSAM
    // sequential read in ascending RECORD KEY (FD-ACCT-ID) order.
    verify(accountRepository).findAll(sortCaptor.capture());
    assertThat(sortCaptor.getValue()).isEqualTo(Sort.by("acctId"));
    assertThat(sortCaptor.getValue().getOrderFor("acctId").getDirection())
        .isEqualTo(Sort.Direction.ASC);
  }

  // --- Phase B: DOUBLE emission shape (the parity quirk) --------------------------------------

  @Test
  void run_emits_eleven_field_lines_plus_separator_and_whole_record_for_one_account() {
    Account acct =
        account(
            100000001L,
            "Y",
            "1234.56",
            "5000.00",
            "2500.00",
            "2020-01-15",
            "2025-01-15",
            "2021-01-15",
            "100.00",
            "50.00",
            "99950",
            "GRP1");
    when(accountRepository.findAll(any(Sort.class))).thenReturn(List.of(acct));

    service.run();

    List<String> lines = lines();

    // The field-by-field block: 11 labeled lines in copybook order, ending with the separator.
    // Captions (including the legacy "EXPIRAION" misspelling) are byte-exact to the production.
    assertThat(lines)
        .containsSubsequence(
            "ACCT-ID                 :100000001",
            "ACCT-ACTIVE-STATUS      :Y",
            "ACCT-CURR-BAL           :1234.56",
            "ACCT-CREDIT-LIMIT       :5000.00",
            "ACCT-CASH-CREDIT-LIMIT  :2500.00",
            "ACCT-OPEN-DATE          :2020-01-15",
            "ACCT-EXPIRAION-DATE     :2025-01-15",
            "ACCT-REISSUE-DATE       :2021-01-15",
            "ACCT-CURR-CYC-CREDIT    :100.00",
            "ACCT-CURR-CYC-DEBIT     :50.00",
            "ACCT-GROUP-ID           :GRP1",
            RECORD_SEPARATOR);

    // Exactly 11 labeled field lines are emitted for the single record (the separator excluded).
    assertThat(lines).filteredOn(line -> line.matches("ACCT-[A-Z-]+ +:.*")).hasSize(11);

    // The separator (a row of dashes) is emitted after the field block.
    assertThat(lines).contains(RECORD_SEPARATOR);

    // The whole-record line is ALSO emitted (the DOUBLE emission) and follows the separator.
    String wholeRecord = expectedWholeRecord(acct);
    assertThat(lines).containsSubsequence(RECORD_SEPARATOR, wholeRecord);

    // The whole-record image is the only place the ACCT-ADDR-ZIP value ("99950") appears.
    assertThat(wholeRecord).contains("99950");
    assertThat(lines).filteredOn(line -> line.contains("99950")).hasSize(1);

    // ACCT-ADDR-ZIP is NOT emitted as a labeled field line (only 11 of 12 fields are labeled).
    assertThat(lines).noneMatch(line -> line.contains("ACCT-ADDR-ZIP"));
  }

  // --- Phase C: multiple records & order preservation ----------------------------------------

  @Test
  void run_emits_double_block_for_each_account_in_input_order() {
    Account first =
        account(
            100000001L,
            "Y",
            "1000.00",
            "5000.00",
            "2500.00",
            "2020-01-15",
            "2025-01-15",
            "2021-01-15",
            "100.00",
            "50.00",
            "11111",
            "GRPA");
    Account second =
        account(
            100000002L,
            "N",
            "2000.00",
            "6000.00",
            "3000.00",
            "2020-02-15",
            "2025-02-15",
            "2021-02-15",
            "200.00",
            "60.00",
            "22222",
            "GRPB");
    Account third =
        account(
            100000003L,
            "Y",
            "3000.00",
            "7000.00",
            "3500.00",
            "2020-03-15",
            "2025-03-15",
            "2021-03-15",
            "300.00",
            "70.00",
            "33333",
            "GRPC");
    when(accountRepository.findAll(any(Sort.class))).thenReturn(List.of(first, second, third));

    service.run();

    List<String> lines = lines();

    // Each record contributes a field block (its ACCT-ID line + separator) AND a whole-record
    // line, and the three records appear strictly in the order supplied: the loop never reorders.
    assertThat(lines)
        .containsSubsequence(
            "ACCT-ID                 :100000001",
            RECORD_SEPARATOR,
            expectedWholeRecord(first),
            "ACCT-ID                 :100000002",
            RECORD_SEPARATOR,
            expectedWholeRecord(second),
            "ACCT-ID                 :100000003",
            RECORD_SEPARATOR,
            expectedWholeRecord(third));

    // One ACCT-ID line, one separator, and one whole-record line per account (three accounts).
    assertThat(lines).filteredOn(line -> line.startsWith("ACCT-ID                 :")).hasSize(3);
    assertThat(lines).filteredOn(RECORD_SEPARATOR::equals).hasSize(3);
    assertThat(lines)
        .contains(
            expectedWholeRecord(first), expectedWholeRecord(second), expectedWholeRecord(third));
  }

  // --- Phase D: clean EOF ---------------------------------------------------------------------

  @Test
  void run_completes_without_exception_when_no_accounts_exist() {
    // An empty master => FILE STATUS '10' on the first read => clean loop termination, not an
    // error.
    when(accountRepository.findAll(any(Sort.class))).thenReturn(List.of());

    assertThatCode(() -> service.run()).doesNotThrowAnyException();

    List<String> lines = lines();
    // No record was read, so neither the field-by-field block nor the whole-record line is emitted.
    assertThat(lines).noneMatch(line -> line.startsWith("ACCT-ID"));
    assertThat(lines).doesNotContain(RECORD_SEPARATOR);
  }

  // --- Phase E: abend / exception mapping -----------------------------------------------------

  @Test
  void run_throws_io_status_exception_for_acctfile_when_repository_fails() {
    DataAccessResourceFailureException cause =
        new DataAccessResourceFailureException("simulated VSAM I/O error");
    // findAll is invoked from 0000-ACCTFILE-OPEN, so the failure surfaces while opening the file.
    when(accountRepository.findAll(any(Sort.class))).thenThrow(cause);

    Throwable thrown = catchThrowable(() -> service.run());

    assertThat(thrown).isInstanceOf(IoStatusException.class);
    IoStatusException ioStatusException = (IoStatusException) thrown;
    assertThat(ioStatusException.getFileName()).isEqualTo("ACCTFILE");
    assertThat(ioStatusException.getOperation()).isEqualTo("OPEN");
    assertThat(ioStatusException.getFileStatus()).isEqualTo("99");
    assertThat(ioStatusException.getCause()).isSameAs(cause);

    // The clean-EOF path (Phase D) must never reach this abend mapping; here it is genuinely an
    // I/O failure, and the verb-specific error line plus 'ABENDING PROGRAM' are emitted at ERROR.
    assertThat(linesAtLevel(Level.ERROR)).contains("ERROR OPENING ACCTFILE", "ABENDING PROGRAM");
  }

  @Test
  void run_throws_io_status_exception_with_read_operation_when_scan_fails() {
    DataAccessResourceFailureException cause =
        new DataAccessResourceFailureException("simulated VSAM read error");
    // The open succeeds (iterator() is obtained); the failure surfaces on the first read
    // (hasNext()), so 1000-ACCTFILE-GET-NEXT abends with operation READ rather than OPEN.
    when(accountRepository.findAll(any(Sort.class))).thenReturn(new ReadFailingList(cause));

    Throwable thrown = catchThrowable(() -> service.run());

    assertThat(thrown).isInstanceOf(IoStatusException.class);
    IoStatusException ioStatusException = (IoStatusException) thrown;
    assertThat(ioStatusException.getFileName()).isEqualTo("ACCTFILE");
    assertThat(ioStatusException.getOperation()).isEqualTo("READ");
    assertThat(ioStatusException.getFileStatus()).isEqualTo("99");
    assertThat(ioStatusException.getCause()).isSameAs(cause);

    assertThat(linesAtLevel(Level.ERROR))
        .contains("ERROR READING ACCOUNT FILE", "ABENDING PROGRAM");
  }

  // --- helpers --------------------------------------------------------------------------------

  /** Returns every captured log message, formatted (with {@code {}} placeholders substituted). */
  private List<String> lines() {
    return logWatcher.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Returns the captured, formatted log messages emitted at exactly {@code level}. */
  private List<String> linesAtLevel(Level level) {
    return logWatcher.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /**
   * Reconstructs the whole-record image exactly as {@code
   * AccountExtractService#formatAccountRecord} builds it: every business field concatenated in
   * copybook ({@code CVACT01Y}) declaration order, <em>including</em> {@code ACCT-ADDR-ZIP} and
   * <em>excluding</em> the trailing {@code FILLER}.
   */
  private static String expectedWholeRecord(Account account) {
    return String.valueOf(account.getAcctId())
        + account.getAcctActiveStatus()
        + account.getAcctCurrBal()
        + account.getAcctCreditLimit()
        + account.getAcctCashCreditLimit()
        + account.getAcctOpenDate()
        + account.getAcctExpiraionDate()
        + account.getAcctReissueDate()
        + account.getAcctCurrCycCredit()
        + account.getAcctCurrCycDebit()
        + account.getAcctAddrZip()
        + account.getAcctGroupId();
  }

  /**
   * Builds a fully populated {@link Account} for display assertions. Every monetary value is a
   * {@link BigDecimal} (never {@code float}/{@code double}), matching the COBOL {@code S9(10)V99}
   * fixed-point fields.
   */
  private static Account account(
      long acctId,
      String activeStatus,
      String currBal,
      String creditLimit,
      String cashCreditLimit,
      String openDate,
      String expiraionDate,
      String reissueDate,
      String currCycCredit,
      String currCycDebit,
      String addrZip,
      String groupId) {
    Account account = new Account();
    account.setAcctId(acctId);
    account.setAcctActiveStatus(activeStatus);
    account.setAcctCurrBal(new BigDecimal(currBal));
    account.setAcctCreditLimit(new BigDecimal(creditLimit));
    account.setAcctCashCreditLimit(new BigDecimal(cashCreditLimit));
    account.setAcctOpenDate(openDate);
    account.setAcctExpiraionDate(expiraionDate);
    account.setAcctReissueDate(reissueDate);
    account.setAcctCurrCycCredit(new BigDecimal(currCycCredit));
    account.setAcctCurrCycDebit(new BigDecimal(currCycDebit));
    account.setAcctAddrZip(addrZip);
    account.setAcctGroupId(groupId);
    return account;
  }

  /**
   * A {@link List} whose iterator raises a supplied {@link RuntimeException} on the first {@code
   * hasNext()}, used to drive the read-path abend. It extends {@link AbstractList} (which, unlike
   * {@link java.util.ArrayList}, is not {@link java.io.Serializable}) so the zero-warning build's
   * {@code serial} lint does not demand a {@code serialVersionUID}. {@code iterator()} succeeds (so
   * the service's open completes); the failure surfaces only when the scan reads.
   */
  private static final class ReadFailingList extends AbstractList<Account> {

    private final RuntimeException failure;

    private ReadFailingList(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public Account get(int index) {
      throw failure;
    }

    @Override
    public int size() {
      return 1;
    }

    @Override
    public Iterator<Account> iterator() {
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          throw failure;
        }

        @Override
        public Account next() {
          throw failure;
        }
      };
    }
  }
}

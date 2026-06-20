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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Pure JUnit&nbsp;5 + AssertJ + Mockito unit tests for {@link AccountExtractService}, the batch
 * "read-and-print account master" service migrated from the legacy COBOL program {@code CBACT01C}
 * (behavioral spec {@code legacy/app/cbl/CBACT01C.cbl}).
 *
 * <p>The service performs no decimal arithmetic and its only collaborator is the {@link
 * AccountRepository}, which is mocked here, so no Spring context or database is required. Because
 * every COBOL {@code DISPLAY} maps to an SLF4J log statement, the externally observable behavior is
 * the sequence of log lines; the tests attach a Logback {@link ListAppender} to the service logger
 * and assert on the captured events. The parity invariants pinned are:
 *
 * <ul>
 *   <li>the program announces start, then end, around the scan ({@code DISPLAY 'START ...'} /
 *       {@code DISPLAY 'END ...'});
 *   <li>records are visited in ascending {@code acctId} order via {@code
 *       findAll(Sort.by("acctId"))} (the VSAM sequential {@code RECORD KEY} order);
 *   <li>each accepted record is emitted <em>twice</em> &mdash; the labeled field-by-field block
 *       ({@code 1100-DISPLAY-ACCT-RECORD}) followed by the whole-record image ({@code DISPLAY
 *       ACCOUNT-RECORD}) &mdash; in that order;
 *   <li>the field block uses the byte-exact COBOL captions, omits {@code ACCT-ADDR-ZIP}, and ends
 *       with the 49-dash separator, whereas the whole-record image <em>includes</em> {@code
 *       ACCT-ADDR-ZIP};
 *   <li>end-of-file terminates the loop cleanly (FILE STATUS {@code '10'} is not an error); and
 *   <li>an unexpected {@link DataAccessException} on the open or read path propagates as an {@link
 *       IoStatusException} carrying {@code ACCTFILE}, the failing operation, and the {@code "99"}
 *       sentinel status (never swallowed, never a {@code RecordNotFoundException}).
 * </ul>
 */
class AccountExtractServiceTest {

  /** The 49-dash separator emitted by {@code 1100-DISPLAY-ACCT-RECORD}. */
  private static final String SEPARATOR = "-------------------------------------------------";

  private AccountRepository accountRepository;
  private AccountExtractService service;
  private Logger serviceLogger;
  private ListAppender<ILoggingEvent> appender;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    accountRepository = mock(AccountRepository.class);
    service = new AccountExtractService(accountRepository);

    // Capture everything the service logs, regardless of any ambient log configuration. TRACE is
    // the lowest non-deprecated level, so INFO and ERROR events both reach the appender.
    serviceLogger = (Logger) org.slf4j.LoggerFactory.getLogger(AccountExtractService.class);
    originalLevel = serviceLogger.getLevel();
    serviceLogger.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    serviceLogger.detachAppender(appender);
    appender.stop();
    serviceLogger.setLevel(originalLevel);
  }

  @Test
  @DisplayName("run(): reads every record in ascending acctId order with both emissions per record")
  void run_displaysAllRecordsAscending_withFieldAndWholeRecordEmissions() {
    Account first =
        account(
            1L,
            "Y",
            "1000.00",
            "5000.00",
            "2000.00",
            "2020-01-01",
            "2025-01-01",
            "2021-01-01",
            "100.00",
            "50.00",
            "12345",
            "GRP1");
    Account second =
        account(
            2L,
            "N",
            "-25.50",
            "7500.00",
            "3000.00",
            "2019-06-15",
            "2024-06-15",
            "2022-06-15",
            "200.00",
            "75.25",
            "67890",
            "GRP2");
    // findAll(Sort) returns the records already in ascending-key order, as VSAM sequential read
    // does.
    when(accountRepository.findAll(any(Sort.class))).thenReturn(List.of(first, second));

    service.run();

    // The repository is queried with an ascending sort on the entity property name "acctId".
    verify(accountRepository).findAll(Sort.by("acctId"));

    List<String> messages = formattedMessages();
    // Boundaries: START first, END last.
    assertThat(messages).first().isEqualTo("START OF EXECUTION OF PROGRAM CBACT01C");
    assertThat(messages).last().isEqualTo("END OF EXECUTION OF PROGRAM CBACT01C");

    // One field-by-field block and one separator per record; two records total.
    assertThat(messages).filteredOn(m -> m.startsWith("ACCT-ID                 :")).hasSize(2);
    assertThat(messages).filteredOn(SEPARATOR::equals).hasSize(2);

    // Ascending order: the first ACCT-ID line is record 1, the second is record 2.
    List<String> idLines =
        messages.stream().filter(m -> m.startsWith("ACCT-ID                 :")).toList();
    assertThat(idLines).containsExactly("ACCT-ID                 :1", "ACCT-ID                 :2");

    // Field block for record 1 uses the byte-exact captions and omits ACCT-ADDR-ZIP.
    assertThat(messages)
        .containsSubsequence(
            "ACCT-ID                 :1",
            "ACCT-ACTIVE-STATUS      :Y",
            "ACCT-CURR-BAL           :1000.00",
            "ACCT-CREDIT-LIMIT       :5000.00",
            "ACCT-CASH-CREDIT-LIMIT  :2000.00",
            "ACCT-OPEN-DATE          :2020-01-01",
            "ACCT-EXPIRAION-DATE     :2025-01-01",
            "ACCT-REISSUE-DATE       :2021-01-01",
            "ACCT-CURR-CYC-CREDIT    :100.00",
            "ACCT-CURR-CYC-DEBIT     :50.00",
            "ACCT-GROUP-ID           :GRP1",
            SEPARATOR);
    assertThat(messages).noneMatch(m -> m.startsWith("ACCT-ADDR-ZIP"));

    // Per-record emission order: the field block (ending with the separator) precedes the
    // whole-record image, which includes ACCT-ADDR-ZIP (the value "12345").
    String wholeRecordOne =
        "1Y1000.005000.002000.002020-01-012025-01-012021-01-01100.0050.0012345GRP1";
    assertThat(messages).containsSubsequence(SEPARATOR, wholeRecordOne);
    assertThat(wholeRecordOne).contains("12345");

    // The negative balance is logged verbatim (BigDecimal, never converted to double).
    assertThat(messages).contains("ACCT-CURR-BAL           :-25.50");
  }

  @Test
  @DisplayName("run(): an empty account master logs only START and END and terminates cleanly")
  void run_emptyFile_logsStartAndEndOnly() {
    when(accountRepository.findAll(any(Sort.class))).thenReturn(new ArrayList<>());

    assertThatCode(() -> service.run()).doesNotThrowAnyException();

    List<String> messages = formattedMessages();
    assertThat(messages)
        .containsExactly(
            "START OF EXECUTION OF PROGRAM CBACT01C", "END OF EXECUTION OF PROGRAM CBACT01C");
    assertThat(messages).noneMatch(m -> m.startsWith("ACCT-ID"));
  }

  @Test
  @DisplayName("run(): a DataAccessException while opening abends as IoStatusException(OPEN)")
  void run_openFailure_throwsIoStatusExceptionForOpen() {
    DataAccessException cause = new DataAccessResourceFailureException("ACCTFILE open boom");
    when(accountRepository.findAll(any(Sort.class))).thenThrow(cause);

    assertThatThrownBy(() -> service.run())
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo("ACCTFILE");
              assertThat(io.getOperation()).isEqualTo("OPEN");
              assertThat(io.getFileStatus()).isEqualTo("99");
              assertThat(io.getCause()).isSameAs(cause);
              assertThat(io.getDisplayMessage()).startsWith("FILE STATUS IS: NNNN");
            });

    // The abend emits the verb-specific error line and the 'ABENDING PROGRAM' line at ERROR level,
    // and START was already announced before the failure.
    List<String> infos = messagesAtLevel(Level.INFO);
    List<String> errors = messagesAtLevel(Level.ERROR);
    assertThat(infos).contains("START OF EXECUTION OF PROGRAM CBACT01C");
    assertThat(infos).doesNotContain("END OF EXECUTION OF PROGRAM CBACT01C");
    assertThat(errors).contains("ERROR OPENING ACCTFILE", "ABENDING PROGRAM");
  }

  @Test
  @DisplayName("run(): a DataAccessException while reading abends as IoStatusException(READ)")
  void run_readFailure_throwsIoStatusExceptionForRead() {
    DataAccessException cause = new DataAccessResourceFailureException("ACCTFILE read boom");
    // The open (findAll().iterator()) succeeds; the failure surfaces on the first hasNext().
    when(accountRepository.findAll(any(Sort.class))).thenReturn(new ReadFailingList(cause));

    assertThatThrownBy(() -> service.run())
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo("ACCTFILE");
              assertThat(io.getOperation()).isEqualTo("READ");
              assertThat(io.getFileStatus()).isEqualTo("99");
              assertThat(io.getCause()).isSameAs(cause);
            });

    assertThat(messagesAtLevel(Level.ERROR))
        .contains("ERROR READING ACCOUNT FILE", "ABENDING PROGRAM");
  }

  @Test
  @DisplayName("AccountExtractService is a Spring @Service component")
  void serviceIsAnnotatedAsSpringService() {
    assertThat(AccountExtractService.class.isAnnotationPresent(Service.class)).isTrue();
  }

  /** Returns every captured log message, formatted (with {@code {}} placeholders substituted). */
  private List<String> formattedMessages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Returns the captured, formatted log messages emitted at exactly {@code level}. */
  private List<String> messagesAtLevel(Level level) {
    return appender.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /** Builds a fully populated {@link Account} for display assertions. */
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
   * {@link ArrayList}, is not {@link java.io.Serializable}) so the zero-warning build's {@code
   * serial} lint does not require a {@code serialVersionUID}. The {@code iterator()} override
   * succeeds (so the service's open completes); the failure surfaces only when the scan reads.
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
      return new Iterator<Account>() {
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

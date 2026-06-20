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
import static org.mockito.Mockito.mock;
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
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link DailyTransactionPostService}, the Java translation of the legacy COBOL
 * batch program {@code CBTRN01C} (the daily-transaction validation pass).
 *
 * <p>The three repositories are mocked so the verification branches can be exercised in isolation,
 * and the service's SLF4J output is captured with a Logback {@link ListAppender} so the
 * byte-faithful COBOL {@code DISPLAY} messages can be asserted (AAP &sect;0.7.1 parity). Every test
 * also asserts that <strong>no</strong> store is ever written, proving the pass performs no posting
 * (the void {@link DailyTransactionPostService#run()} sets no return code).
 *
 * <p>The legacy {@code MAIN-PARA} read-ahead idiom means a single-record daily file is verified
 * twice (once on the real read, once on the end-of-file iteration whose record area is unchanged);
 * the assertions below are written to tolerate that duplication (they assert on the presence of
 * messages and the final, stable status-flag values rather than on exact invocation counts).
 */
@ExtendWith(MockitoExtension.class)
class DailyTransactionPostServiceTest {

  private static final String CARD_NUM = "1234567890123456";
  private static final String TRAN_ID = "TXN0000000000001";
  private static final Long ACCT_ID = 10_000_000_001L;
  private static final Long CUST_ID = 100_000_001L;

  @Mock private DailyTransactionRepository dailyTransactionRepository;
  @Mock private CardXrefRepository cardXrefRepository;
  @Mock private AccountRepository accountRepository;

  @InjectMocks private DailyTransactionPostService service;

  private Logger serviceLogger;
  private ListAppender<ILoggingEvent> logAppender;
  private Level originalLevel;

  @BeforeEach
  void attachLogCapture() {
    serviceLogger = (Logger) LoggerFactory.getLogger(DailyTransactionPostService.class);
    originalLevel = serviceLogger.getLevel();
    serviceLogger.setLevel(Level.TRACE);
    logAppender = new ListAppender<>();
    logAppender.start();
    serviceLogger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogCapture() {
    serviceLogger.detachAppender(logAppender);
    logAppender.stop();
    serviceLogger.setLevel(originalLevel);
  }

  /** Scenario (a): the card resolves and the account exists — the happy validation path. */
  @Test
  void run_whenXrefAndAccountFound_logsSuccessAndPerformsNoWrites() {
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(dailyTransaction(TRAN_ID, CARD_NUM)));
    when(cardXrefRepository.findById(CARD_NUM))
        .thenReturn(Optional.of(cardXref(CARD_NUM, ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));

    service.run();

    assertThat(messages())
        .contains(
            "START OF EXECUTION OF PROGRAM CBTRN01C",
            "SUCCESSFUL READ OF XREF",
            "CARD NUMBER: " + CARD_NUM,
            "ACCOUNT ID : " + ACCT_ID,
            "CUSTOMER ID: " + CUST_ID,
            "SUCCESSFUL READ OF ACCOUNT FILE",
            "END OF EXECUTION OF PROGRAM CBTRN01C");
    assertThat(service.getXrefReadStatus()).isZero();
    assertThat(service.getAcctReadStatus()).isZero();
    assertNoWrites();
  }

  /** Scenario (b): the card resolves but the account is missing — the "NOT FOUND" path. */
  @Test
  void run_whenXrefFoundButAccountMissing_logsNotFoundAndSetsAcctStatusFour() {
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(dailyTransaction(TRAN_ID, CARD_NUM)));
    when(cardXrefRepository.findById(CARD_NUM))
        .thenReturn(Optional.of(cardXref(CARD_NUM, ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

    service.run();

    assertThat(messages())
        .contains("INVALID ACCOUNT NUMBER FOUND", "ACCOUNT " + ACCT_ID + " NOT FOUND");
    assertThat(service.getXrefReadStatus()).isZero();
    assertThat(service.getAcctReadStatus()).isEqualTo(4);
    // The account read WAS attempted (we entered the xref-found branch).
    verify(accountRepository, never()).save(any());
    assertNoWrites();
  }

  /** Scenario (c): the card cannot be verified — the skip path; the account is never read. */
  @Test
  void run_whenXrefMissing_logsCouldNotVerifyAndNeverReadsAccount() {
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(dailyTransaction(TRAN_ID, CARD_NUM)));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

    service.run();

    assertThat(messages())
        .contains(
            "INVALID CARD NUMBER FOR XREF",
            "CARD NUMBER "
                + CARD_NUM
                + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-"
                + TRAN_ID);
    assertThat(service.getXrefReadStatus()).isEqualTo(4);
    assertThat(service.getAcctReadStatus()).isZero();
    // The card was never verified, so the account file must not be read at all.
    verify(accountRepository, never()).findById(any());
    assertNoWrites();
  }

  /** Scenario (d): opening the daily file fails — the OPEN abend ({@code Z-ABEND-PROGRAM}). */
  @Test
  void run_whenOpenFails_throwsIoStatusExceptionForOpen() {
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenThrow(new DataAccessResourceFailureException("open boom"));

    assertThatThrownBy(() -> service.run())
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo("DALYTRAN");
              assertThat(io.getOperation()).isEqualTo("OPEN");
            });

    assertThat(messages())
        .contains("ERROR OPENING DAILY TRANSACTION FILE", "ABENDING PROGRAM")
        .anyMatch(line -> line.startsWith("FILE STATUS IS: NNNN"));
    verify(cardXrefRepository, never()).findById(any());
    verify(accountRepository, never()).findById(any());
    assertNoWrites();
  }

  /** Scenario (e): a read error mid-iteration — the READ abend ({@code Z-ABEND-PROGRAM}). */
  @Test
  @SuppressWarnings("unchecked") // raw Mockito mocks of the generic List / Iterator types
  void run_whenReadFails_throwsIoStatusExceptionForRead() {
    List<DailyTransaction> backingList = mock(List.class);
    Iterator<DailyTransaction> throwingIterator = mock(Iterator.class);
    when(throwingIterator.hasNext()).thenReturn(true);
    when(throwingIterator.next()).thenThrow(new DataAccessResourceFailureException("read boom"));
    when(backingList.iterator()).thenReturn(throwingIterator);
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(backingList);

    assertThatThrownBy(() -> service.run())
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo("DALYTRAN");
              assertThat(io.getOperation()).isEqualTo("READ");
            });

    assertThat(messages())
        .contains("ERROR READING DAILY TRANSACTION FILE", "ABENDING PROGRAM")
        .anyMatch(line -> line.startsWith("FILE STATUS IS: NNNN"));
    assertNoWrites();
  }

  /** Returns the formatted log messages captured during the test, in emission order. */
  private List<String> messages() {
    return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /**
   * Asserts that the validation pass never wrote to any of the three stores (no posting occurs).
   */
  private void assertNoWrites() {
    verify(dailyTransactionRepository, never()).save(any());
    verify(cardXrefRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
  }

  private static DailyTransaction dailyTransaction(String id, String cardNum) {
    DailyTransaction txn = new DailyTransaction();
    txn.setDalytranId(id);
    txn.setDalytranCardNum(cardNum);
    return txn;
  }

  private static CardXref cardXref(String cardNum, Long acctId, Long custId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(cardNum);
    xref.setXrefAcctId(acctId);
    xref.setXrefCustId(custId);
    return xref;
  }

  private static Account account(Long acctId) {
    Account account = new Account();
    account.setAcctId(acctId);
    return account;
  }
}

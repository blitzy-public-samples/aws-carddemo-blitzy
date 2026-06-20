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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CustomerRepository;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link CustomerExtractService}, the batch
 * customer-master extract migrated from the legacy COBOL program {@code CBCUS01C} (behavioral spec
 * {@code legacy/app/cbl/CBCUS01C.cbl}; record copybook {@code legacy/app/cpy/CVCUS01Y.cpy}).
 *
 * <p>{@code CustomerExtractService} has a single collaborator &mdash; {@link CustomerRepository}
 * &mdash; which is mocked here, and its only externally observable effects are the log lines it
 * emits (the COBOL {@code DISPLAY} statements). The tests therefore attach a Logback {@link
 * ListAppender} to the service logger and assert on the captured events. No Spring context or
 * database is required.
 *
 * <p>The parity invariants pinned here (Agent Action Plan &sect;0.6.7, &sect;0.7.1):
 *
 * <ul>
 *   <li><strong>Double display.</strong> Every customer record is logged <em>exactly twice</em> per
 *       iteration (once inside {@code 1000-CUSTFILE-GET-NEXT} and once in the {@code PROCEDURE
 *       DIVISION} main loop). See {@link #run_logsEachRecordTwiceInAscendingOrderWithCleanEof()}.
 *   <li><strong>Ascending key order.</strong> The repository is queried with {@code
 *       Sort.by("custId")} so the scan order matches the legacy {@code RECORD KEY IS FD-CUST-ID}
 *       sequential read.
 *   <li><strong>Clean end-of-file.</strong> Exhausting the cursor ({@code FILE STATUS '10'}) ends
 *       the loop without error and the program logs its start/end banners.
 *   <li><strong>Abend mapping.</strong> An unexpected repository {@link DataAccessException} on
 *       open or read is translated to an {@link IoStatusException} (never swallowed, never a {@code
 *       RecordNotFoundException}), carrying the failing file name, verb, the synthetic {@code "99"}
 *       status, and the original cause.
 *   <li><strong>Numeric fields rendered as-is.</strong> The genuine-numeric ids are logged without
 *       zoned zero-padding.
 * </ul>
 */
class CustomerExtractServiceTest {

  /** Start-of-execution banner, reproduced byte-for-byte from {@code CBCUS01C} L71. */
  private static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBCUS01C";

  /** End-of-execution banner, reproduced byte-for-byte from {@code CBCUS01C} L85. */
  private static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBCUS01C";

  private CustomerRepository customerRepository;
  private CustomerExtractService service;
  private Logger serviceLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    customerRepository = mock(CustomerRepository.class);
    service = new CustomerExtractService(customerRepository);

    // Capture the service's log output (the COBOL DISPLAY equivalents) for assertions.
    serviceLogger = (Logger) LoggerFactory.getLogger(CustomerExtractService.class);
    appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    serviceLogger.detachAppender(appender);
    appender.stop();
  }

  // ===== Fixtures / helpers =====================================================================

  /**
   * Builds a fully populated {@link Customer} whose {@code custFirstName} carries the supplied
   * unique token, so a record can be located unambiguously in the captured log.
   */
  private static Customer customer(long id, String uniqueToken) {
    Customer c = new Customer();
    c.setCustId(id);
    c.setCustFirstName(uniqueToken);
    c.setCustMiddleName("MIDDLE");
    c.setCustLastName("LAST");
    c.setCustAddrLine1("ADDR-LINE-1");
    c.setCustAddrLine2("ADDR-LINE-2");
    c.setCustAddrLine3("ADDR-LINE-3");
    c.setCustAddrStateCd("CA");
    c.setCustAddrCountryCd("USA");
    c.setCustAddrZip("90001");
    c.setCustPhoneNum1("111-222-3333");
    c.setCustPhoneNum2("444-555-6666");
    c.setCustSsn(123456789L);
    c.setCustGovtIssuedId("GOVT-ID-001");
    c.setCustDobYyyyMmDd("1990-01-15");
    c.setCustEftAccountId("EFT0000001");
    c.setCustPriCardHolderInd("Y");
    c.setCustFicoCreditScore(750L);
    return c;
  }

  /** All captured log messages in emission order. */
  private List<String> capturedMessages() {
    List<String> messages = new ArrayList<>();
    for (ILoggingEvent event : appender.list) {
      messages.add(event.getFormattedMessage());
    }
    return messages;
  }

  /** Counts how many captured messages contain the supplied token. */
  private long countMessagesContaining(String token) {
    return appender.list.stream()
        .filter(event -> event.getFormattedMessage().contains(token))
        .count();
  }

  /** Whether an ERROR-level message containing the supplied token was captured. */
  private boolean hasErrorContaining(String token) {
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .anyMatch(event -> event.getFormattedMessage().contains(token));
  }

  // ===== Happy path: double display, ascending order, clean EOF =================================

  @Test
  @DisplayName("run() logs each record twice, in ascending custId order, with a clean EOF")
  void run_logsEachRecordTwiceInAscendingOrderWithCleanEof() {
    Customer first = customer(1L, "ALICE-TOKEN-1");
    Customer second = customer(2L, "BOB-TOKEN-2");
    Customer third = customer(3L, "CAROL-TOKEN-3");
    // findAll(Sort) is the materialized ascending scan; supplied already in custId order.
    when(customerRepository.findAll(any(Sort.class)))
        .thenReturn(new ArrayList<>(List.of(first, second, third)));

    assertThatCode(() -> service.run()).doesNotThrowAnyException();

    // Ascending scan requested with exactly Sort.by("custId").
    verify(customerRepository).findAll(Sort.by("custId"));

    // Banners emitted exactly once each.
    assertThat(countMessagesContaining(START_BANNER)).isEqualTo(1L);
    assertThat(countMessagesContaining(END_BANNER)).isEqualTo(1L);

    // The parity-critical quirk: every record is emitted EXACTLY TWICE.
    assertThat(countMessagesContaining("ALICE-TOKEN-1")).isEqualTo(2L);
    assertThat(countMessagesContaining("BOB-TOKEN-2")).isEqualTo(2L);
    assertThat(countMessagesContaining("CAROL-TOKEN-3")).isEqualTo(2L);

    // Ascending emission order: first appearance of each token follows the custId order.
    List<String> messages = capturedMessages();
    int firstAlice = indexOfFirstContaining(messages, "ALICE-TOKEN-1");
    int firstBob = indexOfFirstContaining(messages, "BOB-TOKEN-2");
    int firstCarol = indexOfFirstContaining(messages, "CAROL-TOKEN-3");
    assertThat(firstAlice).isLessThan(firstBob);
    assertThat(firstBob).isLessThan(firstCarol);

    // Clean EOF: no error/abend output.
    assertThat(hasErrorContaining("ERROR")).isFalse();
    assertThat(hasErrorContaining("ABENDING PROGRAM")).isFalse();
  }

  @Test
  @DisplayName("run() over an empty master logs only the banners and never errors")
  void run_emptyMasterLogsBannersOnly() {
    when(customerRepository.findAll(any(Sort.class))).thenReturn(new ArrayList<>());

    assertThatCode(() -> service.run()).doesNotThrowAnyException();

    List<String> messages = capturedMessages();
    // Only the two banners are logged; no record images, no errors.
    assertThat(messages).containsExactly(START_BANNER, END_BANNER);
    assertThat(hasErrorContaining("ERROR")).isFalse();
  }

  @Test
  @DisplayName("each record image renders the numeric ids as-is and in copybook field order")
  void run_recordRenderingIncludesNumericFieldsAsIs() {
    Customer only = customer(42L, "DAVE-TOKEN");
    when(customerRepository.findAll(any(Sort.class))).thenReturn(new ArrayList<>(List.of(only)));

    service.run();

    // The record image (first occurrence after the start banner) must contain the field values.
    String recordImage =
        capturedMessages().stream()
            .filter(message -> message.contains("DAVE-TOKEN"))
            .findFirst()
            .orElseThrow();
    // Genuine-numeric ids are rendered as-is (no zoned zero-padding).
    assertThat(recordImage)
        .contains("42") // custId as-is (not "000000042")
        .contains("123456789") // custSsn as-is
        .contains("750") // custFicoCreditScore as-is
        .contains("DAVE-TOKEN") // custFirstName
        .contains("1990-01-15") // custDobYyyyMmDd
        .contains("USA"); // custAddrCountryCd
    // custId leads the concatenation (copybook field order).
    assertThat(recordImage).startsWith("42");
  }

  // ===== Abend paths: unexpected FILE STATUS -> IoStatusException ===============================

  @Test
  @DisplayName("an open failure abends with an OPEN IoStatusException and no end banner")
  void run_openFailureThrowsIoStatusException() {
    DataAccessException failure = new DataAccessResourceFailureException("open boom");
    when(customerRepository.findAll(any(Sort.class))).thenThrow(failure);

    Throwable thrown = catchThrowable(() -> service.run());

    assertThat(thrown).isInstanceOf(IoStatusException.class);
    IoStatusException io = (IoStatusException) thrown;
    assertThat(io.getFileName()).isEqualTo("CUSTFILE");
    assertThat(io.getOperation()).isEqualTo("OPEN");
    assertThat(io.getFileStatus()).isEqualTo("99");
    assertThat(io.getCause()).isSameAs(failure);

    // Parity DISPLAY lines: paragraph error, Z-DISPLAY-IO-STATUS, Z-ABEND-PROGRAM.
    assertThat(hasErrorContaining("ERROR OPENING CUSTFILE")).isTrue();
    assertThat(hasErrorContaining("FILE STATUS IS: NNNN")).isTrue();
    assertThat(hasErrorContaining("ABENDING PROGRAM")).isTrue();
    // The start banner was logged, but the run abended before the end banner / close.
    assertThat(countMessagesContaining(START_BANNER)).isEqualTo(1L);
    assertThat(countMessagesContaining(END_BANNER)).isEqualTo(0L);
  }

  @Test
  @DisplayName("a read failure abends with a READ IoStatusException")
  void run_readFailureThrowsIoStatusException() {
    DataAccessException failure = new DataAccessResourceFailureException("read boom");
    // Open succeeds (iterator is returned), but the first hasNext() raises the failure.
    when(customerRepository.findAll(any(Sort.class)))
        .thenReturn(new ReadFailingCustomerList(failure));

    Throwable thrown = catchThrowable(() -> service.run());

    assertThat(thrown).isInstanceOf(IoStatusException.class);
    IoStatusException io = (IoStatusException) thrown;
    assertThat(io.getFileName()).isEqualTo("CUSTFILE");
    assertThat(io.getOperation()).isEqualTo("READ");
    assertThat(io.getFileStatus()).isEqualTo("99");
    assertThat(io.getCause()).isSameAs(failure);

    assertThat(hasErrorContaining("ERROR READING CUSTOMER FILE")).isTrue();
    assertThat(hasErrorContaining("ABENDING PROGRAM")).isTrue();
  }

  // ===== Helpers ================================================================================

  private static int indexOfFirstContaining(List<String> messages, String token) {
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i).contains(token)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * A {@link List} of {@link Customer} whose iterator raises a {@link DataAccessException} on the
   * first {@code hasNext()} call &mdash; used to drive the read-failure (abend) branch of {@link
   * CustomerExtractService#run()} without a live database. The list is non-empty so {@code findAll}
   * appears to return data; the failure surfaces only when the service advances the cursor.
   */
  private static final class ReadFailingCustomerList extends AbstractList<Customer> {

    private final DataAccessException failure;

    private ReadFailingCustomerList(DataAccessException failure) {
      this.failure = failure;
    }

    @Override
    public Customer get(int index) {
      throw new IndexOutOfBoundsException(String.valueOf(index));
    }

    @Override
    public int size() {
      return 1;
    }

    @Override
    public Iterator<Customer> iterator() {
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          throw failure;
        }

        @Override
        public Customer next() {
          throw new NoSuchElementException();
        }
      };
    }
  }
}

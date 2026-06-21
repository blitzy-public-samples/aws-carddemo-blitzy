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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link CustomerExtractService}, the batch
 * customer-master extract migrated from the legacy COBOL program {@code CBCUS01C} (behavioral spec
 * {@code legacy/app/cbl/CBCUS01C.cbl}; record copybook {@code legacy/app/cpy/CVCUS01Y.cpy}).
 *
 * <p>No Spring context, Testcontainers, or database is involved: the sole collaborator {@link
 * CustomerRepository} is a Mockito {@link Mock}, and the only externally observable effect of the
 * service is the log lines it emits (the COBOL {@code DISPLAY} statements). A Logback {@link
 * ListAppender} is attached to the service logger so those events can be asserted directly.
 *
 * <p>The parity invariants pinned here (Agent Action Plan &sect;0.6.7 local-only parity,
 * &sect;0.7.1 control-flow preservation):
 *
 * <ul>
 *   <li><strong>Double display.</strong> Every customer record is logged <em>exactly twice</em> per
 *       iteration &mdash; once inside {@code 1000-CUSTFILE-GET-NEXT} (L96) and once in the {@code
 *       PROCEDURE DIVISION} main loop (L78). The two lines are byte-identical. This intentional
 *       bug-for-bug quirk is never collapsed to a single emission.
 *   <li><strong>Ascending key order.</strong> The repository is queried with exactly {@code
 *       Sort.by("custId")} so the scan order matches the legacy {@code RECORD KEY IS FD-CUST-ID}
 *       sequential read (verified through a {@link ArgumentCaptor}).
 *   <li><strong>Clean end-of-file.</strong> Exhausting the cursor ({@code FILE STATUS '10'}) ends
 *       the loop without error; only the start/end banners are logged.
 *   <li><strong>Abend mapping.</strong> An unexpected repository {@link DataAccessException} on the
 *       open or read path is translated to an {@link IoStatusException} for the logical file {@code
 *       "CUSTFILE"} (the {@code Z-ABEND-PROGRAM} translation), never swallowed and never a {@code
 *       RecordNotFoundException}.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CustomerExtractServiceTest {

  /** Start-of-execution banner, reproduced byte-for-byte from {@code CBCUS01C} L71. */
  private static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBCUS01C";

  /** End-of-execution banner, reproduced byte-for-byte from {@code CBCUS01C} L85. */
  private static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBCUS01C";

  /** The logical file name the service stamps onto an {@link IoStatusException}. */
  private static final String CUSTFILE = "CUSTFILE";

  @Mock CustomerRepository customerRepository;

  /**
   * The JPA persistence context, mocked so the service's per-row {@code detach(...)} (which bounds
   * heap during the streaming read, QA F-2) is a no-op under unit test.
   */
  @Mock EntityManager entityManager;

  CustomerExtractService service;

  private Logger serviceLogger;
  private ListAppender<ILoggingEvent> logAppender;
  private Level originalLevel;

  @BeforeEach
  void set_up() {
    service = new CustomerExtractService(customerRepository);
    // The EntityManager is field-injected (@PersistenceContext) in production; set the mock here so
    // the streaming read's per-row detach(...) is exercised as a no-op.
    ReflectionTestUtils.setField(service, "entityManager", entityManager);

    // Capture the service's log output (the COBOL DISPLAY equivalents) for assertions. The level
    // is forced to DEBUG so every INFO record image and ERROR abend line is retained regardless of
    // the ambient logback configuration; the original level is restored in tear_down().
    serviceLogger = (Logger) LoggerFactory.getLogger(CustomerExtractService.class);
    originalLevel = serviceLogger.getLevel();
    serviceLogger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    serviceLogger.addAppender(logAppender);
  }

  @AfterEach
  void tear_down() {
    serviceLogger.detachAppender(logAppender);
    logAppender.stop();
    serviceLogger.setLevel(originalLevel);
  }

  // ===== Phase A: ascending custId ordering =====================================================

  @Test
  void run_reads_customers_using_ascending_cust_id_sort() {
    when(customerRepository.streamAllByOrderByCustIdAsc())
        .thenReturn(Stream.of(customer(100_000_001L, "SOLO-TOKEN")));

    service.run();

    // The ascending scan must be issued via the streaming, ascending-key reader
    // streamAllByOrderByCustIdAsc(), which fetches in bounded windows rather than buffering the
    // whole table (QA F-2). The ascending custId ordering (the legacy RECORD KEY sequence) is
    // encoded in the repository method name and asserted against a real database in
    // CustomerRepositoryIntegrationTest.
    verify(customerRepository).streamAllByOrderByCustIdAsc();
  }

  // ===== Phase B: DOUBLE emission (the parity quirk) ============================================

  @Test
  void run_emits_each_customer_record_exactly_twice() {
    Customer only = customer(100_000_001L, "ALICE-ONLY-TOKEN");
    when(customerRepository.streamAllByOrderByCustIdAsc()).thenReturn(Stream.of(only));

    service.run();

    // Bug-for-bug parity: the whole record is displayed twice (1000-CUSTFILE-GET-NEXT + main loop).
    assertThat(countContaining("ALICE-ONLY-TOKEN")).isEqualTo(2L);

    List<String> records = recordLines();
    assertThat(records).hasSize(2);
    // The two emissions are byte-identical (same record image rendered twice).
    assertThat(records.get(0)).isEqualTo(records.get(1));

    // Whole-record content: numeric ids rendered as-is (no zoned zero-padding); custId leads the
    // concatenation, matching the CVCUS01Y copybook field order.
    String image = records.get(0);
    assertThat(image)
        .startsWith("100000001") // CUST-ID as-is (not "000000001" / not zero-padded)
        .contains("ALICE-ONLY-TOKEN") // CUST-FIRST-NAME
        .contains("123456789") // CUST-SSN as-is
        .contains("750"); // CUST-FICO-CREDIT-SCORE as-is
  }

  @Test
  void run_emits_two_lines_per_customer_for_multiple_customers_in_order() {
    Customer firstCustomer = customer(100_000_001L, "ALPHA-FIRST-TOKEN");
    Customer secondCustomer = customer(200_000_002L, "BETA-SECOND-TOKEN");
    // findAll(Sort) returns the materialized ascending scan, already in custId order.
    when(customerRepository.streamAllByOrderByCustIdAsc())
        .thenReturn(Stream.of(firstCustomer, secondCustomer));

    service.run();

    // Each customer is emitted exactly twice (four record lines in total).
    assertThat(countContaining("ALPHA-FIRST-TOKEN")).isEqualTo(2L);
    assertThat(countContaining("BETA-SECOND-TOKEN")).isEqualTo(2L);

    String firstImage = linesContaining("ALPHA-FIRST-TOKEN").get(0);
    String secondImage = linesContaining("BETA-SECOND-TOKEN").get(0);
    // containsExactly pins all three facts at once: four record lines, each customer's pair is
    // identical, and the first customer's pair precedes the second customer's pair.
    assertThat(recordLines()).containsExactly(firstImage, firstImage, secondImage, secondImage);
  }

  // ===== Phase C: clean end-of-file =============================================================

  @Test
  void run_completes_without_exception_when_no_customers_exist() {
    when(customerRepository.streamAllByOrderByCustIdAsc()).thenReturn(Stream.of());

    assertThatCode(() -> service.run()).doesNotThrowAnyException();

    // Only the start/end banners are logged; no per-record images and no error output.
    assertThat(messages()).containsExactly(START_BANNER, END_BANNER);
    assertThat(recordLines()).isEmpty();
    assertThat(loggedErrorContaining("ERROR")).isFalse();
    assertThat(loggedErrorContaining("ABENDING PROGRAM")).isFalse();
  }

  // ===== Phase D: abend / exception mapping (Z-prefixed abend path) =============================

  @Test
  void run_throws_io_status_exception_for_custfile_when_repository_fails() {
    DataAccessException failure = new DataAccessResourceFailureException("custfile open failed");
    when(customerRepository.streamAllByOrderByCustIdAsc()).thenThrow(failure);

    assertThatThrownBy(() -> service.run())
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              // The scan is materialized inside 0000-CUSTFILE-OPEN, so the failing verb is OPEN.
              assertThat(io.getFileName()).isEqualTo(CUSTFILE);
              assertThat(io.getOperation()).isEqualTo("OPEN");
              assertThat(io.getFileStatus()).isEqualTo("99");
              assertThat(io.getCause()).isSameAs(failure);
            });

    // Parity DISPLAY lines: paragraph error, Z-DISPLAY-IO-STATUS, then Z-ABEND-PROGRAM.
    assertThat(loggedErrorContaining("ERROR OPENING CUSTFILE")).isTrue();
    assertThat(loggedErrorContaining("FILE STATUS IS: NNNN")).isTrue();
    assertThat(loggedErrorContaining("ABENDING PROGRAM")).isTrue();
    // The start banner was logged, but the run abended before reaching the end banner.
    assertThat(countContaining(START_BANNER)).isEqualTo(1L);
    assertThat(countContaining(END_BANNER)).isEqualTo(0L);
  }

  @Test
  void run_throws_io_status_exception_for_custfile_when_read_fails() {
    DataAccessException failure = new DataAccessResourceFailureException("custfile read failed");
    // The open succeeds (the iterator is created), but advancing the cursor raises the failure,
    // exercising the 1000-CUSTFILE-GET-NEXT read-error branch (the READ abend).
    when(customerRepository.streamAllByOrderByCustIdAsc()).thenReturn(readFailingStream(failure));

    assertThatThrownBy(() -> service.run())
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo(CUSTFILE);
              assertThat(io.getOperation()).isEqualTo("READ");
              assertThat(io.getFileStatus()).isEqualTo("99");
              assertThat(io.getCause()).isSameAs(failure);
            });

    assertThat(loggedErrorContaining("ERROR READING CUSTOMER FILE")).isTrue();
    assertThat(loggedErrorContaining("ABENDING PROGRAM")).isTrue();
  }

  // ===== Fixtures / helpers =====================================================================

  /**
   * Builds a fully populated {@link Customer}. The supplied {@code firstNameToken} is stored as
   * {@code custFirstName} so the record can be located unambiguously in the captured log; the
   * remaining fields are given representative fixed-width values.
   */
  private static Customer customer(long id, String firstNameToken) {
    Customer c = new Customer();
    c.setCustId(id);
    c.setCustFirstName(firstNameToken);
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
    c.setCustSsn(123_456_789L);
    c.setCustGovtIssuedId("GOVT-ID-001");
    c.setCustDobYyyyMmDd("1990-01-15");
    c.setCustEftAccountId("EFT0000001");
    c.setCustPriCardHolderInd("Y");
    c.setCustFicoCreditScore(750L);
    return c;
  }

  /** All captured log messages, in emission order. */
  private List<String> messages() {
    return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Captured messages that are record images (i.e. every message that is not a banner). */
  private List<String> recordLines() {
    return messages().stream()
        .filter(message -> !message.equals(START_BANNER) && !message.equals(END_BANNER))
        .toList();
  }

  /** Counts how many captured messages contain the supplied token. */
  private long countContaining(String token) {
    return messages().stream().filter(message -> message.contains(token)).count();
  }

  /** Returns, in order, the captured messages that contain the supplied token. */
  private List<String> linesContaining(String token) {
    return messages().stream().filter(message -> message.contains(token)).toList();
  }

  /** Whether an {@code ERROR}-level message containing the supplied token was captured. */
  private boolean loggedErrorContaining(String token) {
    return logAppender.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .anyMatch(event -> event.getFormattedMessage().contains(token));
  }

  /**
   * Builds a {@link Stream} whose iterator reports a record is available ({@code hasNext() ==
   * true}) but raises the supplied {@link DataAccessException} when the record is read ({@code
   * next()}).
   *
   * <p>This drives the read-failure (abend) branch of {@link CustomerExtractService#run()} without
   * a live database: obtaining the iterator succeeds so the open completes, and the failure
   * surfaces only when the service advances the cursor inside {@code 1000-CUSTFILE-GET-NEXT}. A
   * typed iterator (rather than a raw {@code mock}) is used so the test compiles cleanly under the
   * project's zero-warning {@code -Werror -Xlint:all} build, and the reader is now the
   * JDBC-cursor-backed {@code streamAllByOrderByCustIdAsc()} (QA F-2).
   */
  private static Stream<Customer> readFailingStream(DataAccessException failure) {
    Iterator<Customer> failingIterator =
        new Iterator<>() {
          @Override
          public boolean hasNext() {
            return true;
          }

          @Override
          public Customer next() {
            throw failure;
          }
        };
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(failingIterator, 0), false);
  }
}

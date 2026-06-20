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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aws.carddemo.domain.Card;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardRepository;
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
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link CardExtractService}, the batch "read
 * and print card master" service migrated one-to-one from the legacy COBOL program {@code CBACT02C}
 * (behavioral spec {@code legacy/app/cbl/CBACT02C.cbl}; record copybook {@code
 * legacy/app/cpy/CVACT02Y.cpy}).
 *
 * <p>The service's only collaborator is the {@link CardRepository}, mocked here, and its only
 * externally observable effect is the sequence of SLF4J log lines it emits (the COBOL {@code
 * DISPLAY} statements). The tests therefore attach a Logback {@link ListAppender} to the service
 * logger and assert on the captured events; no Spring context or database is required (Agent Action
 * Plan &sect;0.6.7, local-only parity).
 *
 * <p>The parity invariants pinned here:
 *
 * <ul>
 *   <li><strong>Single emission (the {@code CBACT02C} quirk).</strong> Every card is logged
 *       <em>exactly once</em>. The per-read {@code DISPLAY CARD-RECORD} is commented out in the
 *       legacy source (L96) and there is no {@code 1100} display paragraph, so &mdash; unlike
 *       {@code CBACT01C}/{@code CBCUS01C}, which emit each record twice &mdash; only the main-loop
 *       {@code DISPLAY} fires. See {@link #run_emits_each_card_exactly_once()}.
 *   <li><strong>Ascending key order.</strong> The repository is queried with {@code
 *       Sort.by("cardNum")} so the scan order matches the legacy {@code RECORD KEY IS FD-CARD-NUM}
 *       sequential read.
 *   <li><strong>Clean end-of-file.</strong> Exhausting the cursor (FILE STATUS {@code '10'}) ends
 *       the loop cleanly, with no per-record emission and no error.
 *   <li><strong>Abend mapping.</strong> An unexpected repository data-access failure on the open
 *       path is translated to an {@link IoStatusException} carrying {@code CARDFILE}, the {@code
 *       OPEN} verb and the synthetic {@code "99"} status (never swallowed, never a {@code
 *       RecordNotFoundException}).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CardExtractServiceTest {

  /**
   * The fixed length of the COBOL {@code CARD-RECORD} image ({@code CVACT02Y} RECLN 150). Every
   * per-record {@code DISPLAY} emits a line of exactly this width, whereas the start/end banners
   * and the abend lines are shorter, so a length match cleanly isolates a whole-record emission.
   */
  private static final int RECORD_LENGTH = 150;

  @Mock private CardRepository cardRepository;

  private CardExtractService service;
  private Logger serviceLogger;
  private ListAppender<ILoggingEvent> logWatcher;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    service = new CardExtractService(cardRepository);

    // Capture the service's SLF4J output (the COBOL DISPLAY equivalents). DEBUG sits below INFO and
    // ERROR, so both the per-record INFO emissions and the abend ERROR lines reach the appender.
    serviceLogger = (Logger) LoggerFactory.getLogger(CardExtractService.class);
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

  // ===== Phase A: ascending-key ordering ========================================================

  @Test
  void run_reads_cards_using_ascending_card_num_sort() {
    Card only =
        card("4111111111111111", 12_345_678_901L, "123", "JOHN Q PUBLIC", "2025-12-31", "Y");
    when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of(only));

    service.run();

    // The sequential VSAM read over the CARDFILE KSDS keyed on FD-CARD-NUM is reproduced by an
    // ascending sort on the entity property "cardNum"; capture and assert the exact Sort used.
    ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
    verify(cardRepository).findAll(sortCaptor.capture());
    Sort actualSort = sortCaptor.getValue();

    assertThat(actualSort).isEqualTo(Sort.by("cardNum"));
    Sort.Order order = actualSort.getOrderFor("cardNum");
    assertThat(order).isNotNull();
    assertThat(order.getProperty()).isEqualTo("cardNum");
    assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
  }

  // ===== Phase B: single emission per record (the parity quirk) =================================

  @Test
  void run_emits_each_card_exactly_once() {
    Card only =
        card("4111111111111111", 12_345_678_901L, "123", "JOHN Q PUBLIC", "2025-12-31", "Y");
    when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of(only));

    service.run();

    // CBACT02C displays each card EXACTLY ONCE: the per-read DISPLAY CARD-RECORD at L96 is
    // commented out and there is no 1100 display paragraph, so only the main-loop DISPLAY emits.
    long emissions = countMessagesContaining("4111111111111111");
    assertThat(emissions)
        .as("each card must be emitted exactly once (single-emit parity, unlike CBACT03C/CBCUS01C)")
        .isEqualTo(1L);
    // Explicit guard against the DOUBLE-emission quirk that applies to Xref/Customer but NOT Card.
    assertThat(emissions).isNotEqualTo(2L);
    // Exactly one fixed-width (150-byte) CARD-RECORD image was logged.
    assertThat(recordLines()).hasSize(1);
  }

  @Test
  void run_emits_one_line_per_card_for_multiple_cards_in_input_order() {
    Card first = card("1000000000000001", 1L, "001", "ALICE", "2030-01-01", "Y");
    Card second = card("2000000000000002", 2L, "002", "BOB", "2031-02-02", "Y");
    Card third = card("3000000000000003", 3L, "003", "CAROL", "2032-03-03", "N");
    when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of(first, second, third));

    service.run();

    // One whole-record line per card, none duplicated: three records in, three record lines out.
    assertThat(recordLines()).hasSize(3);
    assertThat(countMessagesContaining("1000000000000001")).isEqualTo(1L);
    assertThat(countMessagesContaining("2000000000000002")).isEqualTo(1L);
    assertThat(countMessagesContaining("3000000000000003")).isEqualTo(1L);

    // Records are emitted in the ascending-key input order the sequential read returns them in.
    List<String> messages = formattedMessages();
    int firstIndex = indexOfFirstContaining(messages, "1000000000000001");
    int secondIndex = indexOfFirstContaining(messages, "2000000000000002");
    int thirdIndex = indexOfFirstContaining(messages, "3000000000000003");
    assertThat(firstIndex).isLessThan(secondIndex);
    assertThat(secondIndex).isLessThan(thirdIndex);
  }

  // ===== Phase C: clean end-of-file =============================================================

  @Test
  void run_completes_without_exception_when_no_cards_exist() {
    when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of());

    assertThatCode(() -> service.run()).doesNotThrowAnyException();

    // An empty card master logs no whole-record image (FILE STATUS '10' is clean EOF, not an
    // error); only the start/end banners are emitted.
    assertThat(recordLines()).isEmpty();
  }

  // ===== Phase D: abend / FILE STATUS -> exception mapping ======================================

  @Test
  void run_throws_io_status_exception_for_cardfile_when_repository_fails() {
    DataAccessResourceFailureException cause =
        new DataAccessResourceFailureException("CARDFILE open boom");
    when(cardRepository.findAll(any(Sort.class))).thenThrow(cause);

    // The unexpected FILE STATUS path (non-'00'/non-'10') maps to the 9999-ABEND-PROGRAM
    // equivalent: an IoStatusException for the CARDFILE OPEN carrying the synthetic "99" status.
    assertThatThrownBy(() -> service.run())
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo("CARDFILE");
              assertThat(io.getOperation()).isEqualTo("OPEN");
              assertThat(io.getFileStatus()).isEqualTo("99");
              assertThat(io.getCause()).isSameAs(cause);
              assertThat(io.getDisplayMessage()).startsWith("FILE STATUS IS: NNNN");
            });

    // The abend logs the verb-specific error banner at ERROR level and never reaches the end
    // banner: only a genuine I/O failure abends, and it transfers control immediately.
    assertThat(errorMessages()).contains("ERROR OPENING CARDFILE");
    assertThat(formattedMessages()).doesNotContain("END OF EXECUTION OF PROGRAM CBACT02C");
  }

  // ===== Helpers ================================================================================

  /** All captured log messages, formatted (any {@code {}} arguments substituted), in order. */
  private List<String> formattedMessages() {
    return logWatcher.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Counts the captured messages that contain the supplied token. */
  private long countMessagesContaining(String token) {
    return logWatcher.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains(token))
        .count();
  }

  /**
   * The captured whole-record images: messages whose length equals the fixed {@code CARD-RECORD}
   * width ({@link #RECORD_LENGTH}). Banners and error lines are shorter, so this isolates the
   * per-record emissions independently of the token-containment checks.
   */
  private List<String> recordLines() {
    return logWatcher.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.length() == RECORD_LENGTH)
        .toList();
  }

  /** The captured ERROR-level messages, formatted (the COBOL abend {@code DISPLAY} lines). */
  private List<String> errorMessages() {
    return logWatcher.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /** Returns the index of the first message containing {@code token}, or {@code -1} if none. */
  private static int indexOfFirstContaining(List<String> messages, String token) {
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i).contains(token)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Builds a fully populated {@link Card} with every {@code CVACT02Y} field set, including the
   * copybook's preserved misspelling {@code cardExpiraionDate}.
   */
  private static Card card(
      String cardNum,
      long cardAcctId,
      String cardCvvCd,
      String cardEmbossedName,
      String cardExpiraionDate,
      String cardActiveStatus) {
    Card entity = new Card();
    entity.setCardNum(cardNum);
    entity.setCardAcctId(cardAcctId);
    entity.setCardCvvCd(cardCvvCd);
    entity.setCardEmbossedName(cardEmbossedName);
    entity.setCardExpiraionDate(cardExpiraionDate);
    entity.setCardActiveStatus(cardActiveStatus);
    return entity;
  }
}

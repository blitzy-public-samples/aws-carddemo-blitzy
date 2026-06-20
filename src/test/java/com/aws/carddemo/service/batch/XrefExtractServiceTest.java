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
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardXrefRepository;
import java.util.List;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

/**
 * Pure JUnit&nbsp;5 + AssertJ + Mockito unit tests for {@link XrefExtractService}, the batch
 * "read-and-print card cross-reference master" service migrated from the legacy COBOL program
 * {@code CBACT03C} (behavioral spec {@code legacy/app/cbl/CBACT03C.cbl}, copybook {@code
 * legacy/app/cpy/CVACT03Y.cpy}).
 *
 * <p>The service performs no decimal arithmetic and its only collaborator is the {@link
 * CardXrefRepository}, which is mocked here, so no Spring context, Testcontainers, or database is
 * required (Agent Action Plan &sect;0.6.7, local-only validation). Because every COBOL {@code
 * DISPLAY} maps to an SLF4J log statement, the externally observable behavior is the sequence of
 * log lines; the tests attach a Logback {@link ListAppender} to the service logger and assert on
 * the captured events.
 *
 * <p>The parity invariants pinned (control-flow preservation, &sect;0.7.1) are:
 *
 * <ul>
 *   <li>the program announces start, then end, around the scan ({@code DISPLAY 'START ...'} /
 *       {@code DISPLAY 'END ...'});
 *   <li>records are visited in ascending {@code xrefCardNum} order via {@code
 *       findAll(Sort.by("xrefCardNum"))} (the VSAM sequential {@code RECORD KEY} order);
 *   <li><strong>each record is emitted exactly twice</strong> as two byte-identical whole-record
 *       lines &mdash; the intentional, bug-for-bug DOUBLE display of {@code CBACT03C}, in which the
 *       {@code 1000-XREFFILE-GET-NEXT} {@code DISPLAY CARD-XREF-RECORD} (active, not commented) and
 *       the main-loop {@code DISPLAY CARD-XREF-RECORD} both fire (contrast {@code CBACT02C}, which
 *       emits a single line);
 *   <li>end-of-file terminates the loop cleanly (FILE STATUS {@code '10'} is not an error); and
 *   <li>an unexpected {@code DataAccessException} on the open path propagates as an {@link
 *       IoStatusException} carrying {@code XREFFILE}, operation {@code OPEN}, and the {@code "99"}
 *       sentinel status (never swallowed).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class XrefExtractServiceTest {

  /** The byte-exact banner emitted at the start of the run ({@code DISPLAY 'START ...'}). */
  private static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBACT03C";

  /** The byte-exact banner emitted at the end of the run ({@code DISPLAY 'END ...'}). */
  private static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBACT03C";

  /**
   * First fixture card number; ascending-sorts before {@link #CARD_TWO} ({@code '4'} &lt; {@code
   * '5'}).
   */
  private static final String CARD_ONE = "4111111111111111";

  /** Second fixture card number; ascending-sorts after {@link #CARD_ONE}. */
  private static final String CARD_TWO = "5500000000000004";

  @Mock private CardXrefRepository cardXrefRepository;

  private XrefExtractService service;
  private Logger serviceLogger;
  private ListAppender<ILoggingEvent> logWatcher;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    service = new XrefExtractService(cardXrefRepository);

    // Capture everything the service logs, regardless of any ambient log configuration. DEBUG is
    // below INFO, so both the INFO record/banner lines and the ERROR abend lines reach the
    // appender.
    serviceLogger = (Logger) LoggerFactory.getLogger(XrefExtractService.class);
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

  // --- Phase A: ordering ---------------------------------------------------------------------

  @Test
  void run_reads_xrefs_using_ascending_xref_card_num_sort() {
    when(cardXrefRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(cardXref(CARD_ONE, 567890123L, 98765432109L)));

    service.run();

    // The repository is queried exactly once, with an ascending sort on the JPA property name
    // "xrefCardNum" (the legacy VSAM RECORD KEY FD-XREF-CARD-NUM read sequentially).
    ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
    verify(cardXrefRepository).findAll(sortCaptor.capture());

    Sort usedSort = sortCaptor.getValue();
    assertThat(usedSort).isEqualTo(Sort.by("xrefCardNum"));

    Sort.Order order = usedSort.getOrderFor("xrefCardNum");
    assertThat(order).isNotNull();
    assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    assertThat(order.isAscending()).isTrue();
  }

  // --- Phase B: DOUBLE emission (the parity quirk) -------------------------------------------

  @Test
  void run_emits_each_xref_record_exactly_twice() {
    CardXref xref = cardXref(CARD_ONE, 567890123L, 98765432109L);
    when(cardXrefRepository.findAll(any(Sort.class))).thenReturn(List.of(xref));

    service.run();

    List<String> messages = formattedMessages();
    List<String> recordLines = recordLines(messages);

    // Bug-for-bug DOUBLE display: the single record produced exactly two whole-record lines...
    assertThat(recordLines).hasSize(2);
    // ...and the two lines are byte-identical (both render the same CARD-XREF-RECORD image).
    assertThat(recordLines.get(0)).isEqualTo(recordLines.get(1));

    // The whole-record content is present: card number, zero-padded customer id, zero-padded
    // account id (CVACT03Y field order).
    assertThat(recordLines.get(0)).contains(CARD_ONE).contains("567890123").contains("98765432109");

    // Counting by the unique card number confirms the record content appears in exactly two lines.
    assertThat(messages).filteredOn(m -> m.contains(CARD_ONE)).hasSize(2);

    // The banners bracket the run (START first, END last).
    assertThat(messages).first().isEqualTo(START_BANNER);
    assertThat(messages).last().isEqualTo(END_BANNER);
  }

  @Test
  void run_emits_two_lines_per_record_for_multiple_xrefs_in_order() {
    CardXref first = cardXref(CARD_ONE, 567890123L, 98765432109L);
    CardXref second = cardXref(CARD_TWO, 111222333L, 44455566677L);
    // findAll(Sort) returns the rows already in ascending-key order, as the VSAM sequential read
    // does; the service iterates them in that order.
    when(cardXrefRepository.findAll(any(Sort.class))).thenReturn(List.of(first, second));

    service.run();

    List<String> messages = formattedMessages();
    List<String> recordLines = recordLines(messages);

    // Two records, each emitted twice -> four record lines total.
    assertThat(recordLines).hasSize(4);

    // The per-record pair stays together and is identical: [r1, r1, r2, r2] (get-next line then
    // main-loop line for each record).
    assertThat(recordLines.get(0)).contains(CARD_ONE).isEqualTo(recordLines.get(1));
    assertThat(recordLines.get(2)).contains(CARD_TWO).isEqualTo(recordLines.get(3));

    // Each record appears in exactly two lines.
    assertThat(recordLines).filteredOn(m -> m.contains(CARD_ONE)).hasSize(2);
    assertThat(recordLines).filteredOn(m -> m.contains(CARD_TWO)).hasSize(2);

    // Input order preserved: the first record's pair precedes the second record's pair.
    assertThat(recordLines.get(0)).isNotEqualTo(recordLines.get(2));
    assertThat(messages).first().isEqualTo(START_BANNER);
    assertThat(messages).last().isEqualTo(END_BANNER);
  }

  @Test
  void run_renders_record_image_with_cobol_fixed_width_zero_padding() {
    // Small ids exercise the COBOL fixed-width display: XREF-CUST-ID PIC 9(09) and XREF-ACCT-ID
    // PIC 9(11) are left zero-padded to their declared widths, and the unmodelled trailing FILLER
    // PIC X(14) renders as 14 spaces, so the whole-record image is exactly 50 characters
    // (CVACT03Y RECLN 50).
    CardXref xref = cardXref(CARD_ONE, 1L, 42L);
    when(cardXrefRepository.findAll(any(Sort.class))).thenReturn(List.of(xref));

    service.run();

    String expectedImage = CARD_ONE + "000000001" + "00000000042" + " ".repeat(14);
    List<String> recordLines = recordLines(formattedMessages());
    // Both DOUBLE-emission lines are the byte-exact 50-character fixed-width record image.
    assertThat(recordLines).hasSize(2);
    assertThat(recordLines.get(0)).isEqualTo(expectedImage).hasSize(50);
    assertThat(recordLines.get(1)).isEqualTo(expectedImage);
  }

  // --- Phase C: clean EOF --------------------------------------------------------------------

  @Test
  void run_completes_without_exception_when_no_xrefs_exist() {
    when(cardXrefRepository.findAll(any(Sort.class))).thenReturn(List.of());

    assertThatCode(() -> service.run()).doesNotThrowAnyException();

    List<String> messages = formattedMessages();
    // An empty cross-reference master logs only START and END and terminates cleanly...
    assertThat(messages).containsExactly(START_BANNER, END_BANNER);
    // ...with no per-record lines emitted.
    assertThat(recordLines(messages)).isEmpty();
  }

  // --- Phase D: abend / exception mapping ----------------------------------------------------

  @Test
  void run_throws_io_status_exception_for_xreffile_when_repository_fails() {
    DataAccessResourceFailureException cause =
        new DataAccessResourceFailureException("XREFFILE open boom");
    when(cardXrefRepository.findAll(any(Sort.class))).thenThrow(cause);

    assertThatThrownBy(() -> service.run())
        .isInstanceOfSatisfying(
            IoStatusException.class,
            io -> {
              // Only a genuine I/O failure maps to IoStatusException, naming the failing dataset.
              assertThat(io.getFileName()).isEqualTo("XREFFILE");
              assertThat(io.getOperation()).isEqualTo("OPEN");
              assertThat(io.getFileStatus()).isEqualTo("99");
              assertThat(io.getCause()).isSameAs(cause);
              assertThat(io.getDisplayMessage()).startsWith("FILE STATUS IS: NNNN");
            });

    // START was announced before the failure; END is never reached; the abend emits the
    // verb-specific error line and the IO-status display line at ERROR level.
    assertThat(messagesAtLevel(Level.INFO)).contains(START_BANNER).doesNotContain(END_BANNER);
    assertThat(messagesAtLevel(Level.ERROR))
        .contains("ERROR OPENING XREFFILE")
        .anyMatch(m -> m.startsWith("FILE STATUS IS: NNNN"));
  }

  // --- helpers -------------------------------------------------------------------------------

  /** Returns every captured log message, formatted (with {@code {}} placeholders substituted). */
  private List<String> formattedMessages() {
    return logWatcher.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Returns the captured, formatted log messages emitted at exactly {@code level}. */
  private List<String> messagesAtLevel(Level level) {
    return logWatcher.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /**
   * Returns only the per-record whole-record image lines, i.e. the formatted messages with the
   * START and END banners removed. In the success paths exercised here the service emits no ERROR
   * lines, so the remainder is exactly the {@code DISPLAY CARD-XREF-RECORD} output.
   */
  private static List<String> recordLines(List<String> messages) {
    return messages.stream()
        .filter(m -> !m.equals(START_BANNER))
        .filter(m -> !m.equals(END_BANNER))
        .toList();
  }

  /**
   * Builds a fully populated {@link CardXref} for display assertions (no floating-point fields).
   */
  private static CardXref cardXref(String xrefCardNum, long xrefCustId, long xrefAcctId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(xrefCardNum);
    xref.setXrefCustId(xrefCustId);
    xref.setXrefAcctId(xrefAcctId);
    return xref;
  }
}

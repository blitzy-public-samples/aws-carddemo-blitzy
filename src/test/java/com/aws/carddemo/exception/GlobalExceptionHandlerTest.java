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
package com.aws.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link GlobalExceptionHandler}, focused on the
 * <strong>security hygiene</strong> of the {@link RecordNotFoundException} (online FILE STATUS
 * {@code '23'}) handler.
 *
 * <p>The lookup key carried by a {@link RecordNotFoundException} may be a sensitive identifier — an
 * account id, a full card number, or a user id (the exception's own Javadoc documents this). The
 * Code Review finding (CWE-532 / information disclosure; AAP &sect;0.7.2) is that the original
 * handler echoed that raw key into both the HTTP response (as the {@code detail} text and a {@code
 * key} property) and the log line. These tests pin the corrected, leak-free contract:
 *
 * <ul>
 *   <li>the response {@code detail} is the fixed, key-free message {@code "Record not found"} and
 *       never contains the raw key;
 *   <li>the response body retains only the non-sensitive logical {@code "entity"} property and
 *       never carries a {@code "key"} property (nor the raw key in any other property value);
 *   <li>the {@code WARN} log records only a masked key (PCI-style "reveal the last four") and never
 *       the raw identifier, including short keys which are masked in full.
 * </ul>
 *
 * <p>These tests are intentionally framework-light: the handler is constructed directly with {@code
 * new} (it is stateless and performs no I/O) and every assertion uses AssertJ only. Log output is
 * captured with a Logback {@link ListAppender} attached directly to the handler's logger. The
 * sample identifiers below (a card number, a user id, a reference code) are non-sensitive test
 * literals used purely to prove they are <em>not</em> leaked — never real credentials.
 */
class GlobalExceptionHandlerTest {

  /** A representative 16-digit card number used to prove the raw value is never disclosed. */
  private static final String RAW_CARD_KEY = "4111111111111111";

  /** The card key's expected PCI-style masking: every digit but the last four replaced by '*'. */
  private static final String MASKED_CARD_KEY = "************1111";

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  /**
   * The not-found handler must return HTTP&nbsp;404 with the fixed, generic {@code detail} {@code
   * "Record not found"} — and that detail must never contain the raw lookup key (the original leak
   * propagated {@code ex.getMessage()}, which embeds the key, as the detail).
   */
  @Test
  void handleRecordNotFound_returns404WithGenericKeyFreeDetail() {
    RecordNotFoundException ex = new RecordNotFoundException("Card", RAW_CARD_KEY);

    ResponseEntity<ProblemDetail> response = handler.handleRecordNotFound(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getTitle()).isEqualTo("Record Not Found");
    assertThat(body.getDetail()).isEqualTo("Record not found");
    assertThat(body.getDetail()).doesNotContain(RAW_CARD_KEY);
  }

  /**
   * The response body must retain only the non-sensitive logical {@code "entity"} property and must
   * never surface the raw key — neither as a {@code "key"} property nor embedded in any other
   * property value.
   */
  @Test
  void handleRecordNotFound_omitsRawKeyFromResponseBody() {
    RecordNotFoundException ex = new RecordNotFoundException("Card", RAW_CARD_KEY);

    ProblemDetail body = handler.handleRecordNotFound(ex).getBody();

    assertThat(body).isNotNull();
    Map<String, Object> properties = body.getProperties();
    assertThat(properties).isNotNull();
    assertThat(properties).containsEntry("entity", "Card");
    assertThat(properties).doesNotContainKey("key");
    assertThat(properties.values())
        .noneMatch(value -> String.valueOf(value).contains(RAW_CARD_KEY));
  }

  /**
   * The {@code WARN} log line must record only a masked key (last four revealed) and must never
   * contain the raw identifier.
   */
  @Test
  void handleRecordNotFound_logsMaskedKeyNeverRawKey() {
    List<String> logs =
        captureHandlerLogs(
            () -> handler.handleRecordNotFound(new RecordNotFoundException("Card", RAW_CARD_KEY)));

    assertThat(logs).isNotEmpty();
    assertThat(logs).noneMatch(message -> message.contains(RAW_CARD_KEY));
    assertThat(logs).anyMatch(message -> message.contains("maskedKey=" + MASKED_CARD_KEY));
  }

  /**
   * A short key (four characters or fewer) must be masked in full so it is never echoed verbatim,
   * exercising the {@code length <= 4} masking branch.
   */
  @Test
  void handleRecordNotFound_masksShortKeyInFull() {
    String shortKey = "01";

    List<String> logs =
        captureHandlerLogs(
            () ->
                handler.handleRecordNotFound(
                    new RecordNotFoundException("TransactionType", shortKey)));

    assertThat(logs).anyMatch(message -> message.contains("maskedKey=**"));
    assertThat(logs).noneMatch(message -> message.contains("maskedKey=" + shortKey));
  }

  /**
   * When the exception carries no structured context (the message-only constructor coerces both the
   * entity and the key to the empty string), the handler must still return the generic 404 detail
   * and must add neither an {@code "entity"} nor a {@code "key"} property, exercising the blank-key
   * masking branch.
   */
  @Test
  void handleRecordNotFound_blankContext_returnsGenericDetailWithoutEntityOrKey() {
    RecordNotFoundException ex = new RecordNotFoundException("Record not found");

    ResponseEntity<ProblemDetail> response = handler.handleRecordNotFound(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getDetail()).isEqualTo("Record not found");
    Map<String, Object> properties = body.getProperties();
    assertThat(properties).isNotNull();
    assertThat(properties).doesNotContainKey("entity");
    assertThat(properties).doesNotContainKey("key");
  }

  /**
   * Runs {@code action} while a Logback {@link ListAppender} is attached to the {@link
   * GlobalExceptionHandler} logger, then returns the formatted messages it captured. The logger
   * level is forced to {@code TRACE} for the duration so the capture is independent of any
   * test-profile logging configuration, and is restored afterwards.
   *
   * @param action the handler invocation whose log output should be captured
   * @return the formatted log messages emitted during {@code action}; never {@code null}
   */
  private static List<String> captureHandlerLogs(Runnable action) {
    Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    Level previousLevel = handlerLogger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    handlerLogger.setLevel(Level.TRACE);
    handlerLogger.addAppender(appender);
    try {
      action.run();
    } finally {
      handlerLogger.detachAppender(appender);
      handlerLogger.setLevel(previousLevel);
      appender.stop();
    }
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }
}

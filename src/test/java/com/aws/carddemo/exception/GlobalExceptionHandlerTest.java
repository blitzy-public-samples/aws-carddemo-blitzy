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
import com.aws.carddemo.util.Messages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link GlobalExceptionHandler}, the Spring MVC
 * {@code @ControllerAdvice} that translates the {@code com.aws.carddemo.exception} hierarchy into
 * HTTP / screen error responses for the <strong>online</strong> layer (Agent Action Plan
 * &sect;0.6.6 — "Online abends map to a {@code @ControllerAdvice} handler"; &sect;0.4.2 — "FILE
 * STATUS / RESP &rarr; typed exceptions + {@code @ControllerAdvice}"; &sect;0.6.4
 * status&rarr;exception mapping).
 *
 * <p><strong>Pure-unit strategy.</strong> The advice is stateless and has no injected dependencies
 * (it uses only static {@link Messages} constants and a static logger), so every test constructs it
 * directly with {@code new GlobalExceptionHandler()} and invokes its {@code @ExceptionHandler}
 * methods directly. There is deliberately <em>no</em> {@code @SpringBootTest}, {@code @WebMvcTest},
 * {@code MockMvc}, Spring context, database, or mock — the exact status / {@link ProblemDetail}
 * contract is asserted without the cost or flakiness of a Spring slice test.
 *
 * <p><strong>Status mapping under test</strong> (the legacy split between recoverable "soft" screen
 * errors and unrecoverable abends):
 *
 * <ul>
 *   <li>{@link ValidationException} &rarr; <strong>400</strong>; the detail echoes the message, and
 *       a blank message falls back to {@link Messages#MSG_INVALID_KEY} trimmed of its COBOL {@code
 *       PIC X(50)} padding (the explicit {@code util.Messages} dependency this suite proves).
 *   <li>{@link AuthorizationException} &rarr; <strong>403</strong>; the detail is the preserved
 *       legacy {@link AuthorizationException#ADMIN_ONLY_MESSAGE}.
 *   <li>{@link RecordNotFoundException} &rarr; <strong>404</strong>; the detail is the fixed,
 *       key-free {@code "Record not found"} and the raw lookup key is never disclosed in the body
 *       or the log (CWE-532 / information disclosure; AAP &sect;0.7.2).
 *   <li>{@link IoStatusException} &rarr; <strong>500</strong>; carries the byte-faithful operator
 *       line {@code "FILE STATUS IS: NNNN0035"} and the {@code abendCode}/{@code fileStatus}/{@code
 *       file}/{@code operation} diagnostic properties.
 *   <li>{@link CardDemoException} &rarr; <strong>500</strong> (catch-all); echoes the message, or
 *       the default abend message when blank, plus the preserved {@code abendCode}.
 *   <li>any other {@link Exception} &rarr; <strong>500</strong>; returns only the generic abend
 *       message and never leaks the originating exception text or class name.
 * </ul>
 *
 * <p><strong>COBOL parity anchors.</strong> The unrecoverable (500) expectations are pinned to the
 * legacy archetypes: {@code legacy/app/cbl/COACTVWC.cbl} ABEND-ROUTINE moved {@code 'UNEXPECTED
 * ABEND OCCURRED.'} into the abend message [L919] and issued {@code EXEC CICS ABEND ABCODE('9999')}
 * [L935], while {@code legacy/app/cbl/CBTRN02C.cbl} {@code 9910-DISPLAY-IO-STATUS} [L714-L727]
 * rendered {@code 'FILE STATUS IS: NNNN'} followed by the four-character {@code IO-STATUS-04} (so a
 * status of {@code "35"} renders {@code "0035"}).
 *
 * <p>The sample identifiers used below (a 16-digit card number, a short reference code, and a
 * non-sensitive failure string) are test literals chosen purely to prove they are <em>not</em>
 * leaked; none is a real credential.
 */
class GlobalExceptionHandlerTest {

  /** A representative 16-digit card number used to prove the raw value is never disclosed. */
  private static final String RAW_CARD_KEY = "4111111111111111";

  /** The card key's expected PCI-style masking: every digit but the last four replaced by '*'. */
  private static final String MASKED_CARD_KEY = "************1111";

  /**
   * The byte-faithful generic abend message preserved from {@code COACTVWC.cbl} ABEND-ROUTINE
   * [L919]; asserted as a literal because the production constant is {@code private}.
   */
  private static final String DEFAULT_ABEND_MSG = "UNEXPECTED ABEND OCCURRED.";

  /**
   * The online CICS abend code preserved from {@code COACTVWC.cbl} {@code EXEC CICS ABEND
   * ABCODE('9999')} [L935]; asserted as a literal because the production constant is {@code
   * private}.
   */
  private static final String CICS_ABEND_CODE = "9999";

  /**
   * The fixed, key-free not-found detail. It deliberately carries no lookup key so a missing-record
   * response never discloses a sensitive identifier (CWE-532; AAP &sect;0.7.2).
   */
  private static final String RECORD_NOT_FOUND_DETAIL = "Record not found";

  /** Stateless advice under test, constructed directly — no Spring context and no injected deps. */
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  // ---------------------------------------------------------------------------------------------
  // ValidationException -> 400 Bad Request (recoverable field-edit soft error)
  // ---------------------------------------------------------------------------------------------

  /**
   * A validation failure with a user-facing message returns HTTP&nbsp;400 and echoes that message
   * verbatim as the {@link ProblemDetail} detail (message parity is the contract).
   */
  @Test
  void handleValidation_withMessage_returns400AndEchoesDetail() {
    ResponseEntity<ProblemDetail> response =
        handler.handleValidation(new ValidationException("Account ID must be numeric"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    ProblemDetail body = bodyOf(response);
    assertThat(body.getStatus()).isEqualTo(400);
    assertThat(body.getTitle()).isEqualTo("Validation Error");
    assertThat(body.getDetail()).isEqualTo("Account ID must be numeric");
  }

  /**
   * A blank validation message returns HTTP&nbsp;400 with the detail composed from {@link
   * com.aws.carddemo.util.Messages#MSG_INVALID_KEY} trimmed of its COBOL {@code PIC X(50)} padding.
   * This is the explicit {@code util.Messages} usage the folder specification requires this suite
   * to prove.
   */
  @Test
  void handleValidation_blankMessage_usesMessagesInvalidKeyTrimmed() {
    ResponseEntity<ProblemDetail> response = handler.handleValidation(new ValidationException(""));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bodyOf(response).getDetail()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
  }

  /**
   * When the offending screen field is known, it is surfaced as the {@code "field"} property (to
   * drive cursor placement / highlighting, mirroring the legacy {@code MOVE -1 TO <field>L}
   * convention) while the detail still echoes the validation message.
   */
  @Test
  void handleValidation_withFieldName_setsFieldProperty() {
    ResponseEntity<ProblemDetail> response =
        handler.handleValidation(new ValidationException("ACCTID", "Account ID must be numeric"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    ProblemDetail body = bodyOf(response);
    assertThat(body.getDetail()).isEqualTo("Account ID must be numeric");
    Map<String, Object> properties = body.getProperties();
    assertThat(properties).isNotNull();
    assertThat(properties).containsEntry("field", "ACCTID");
  }

  // ---------------------------------------------------------------------------------------------
  // AuthorizationException -> 403 Forbidden (CDEMO-USRTYP admin-only gate)
  // ---------------------------------------------------------------------------------------------

  /**
   * The no-argument authorization denial (the common {@code COMEN01C} admin-only gate) returns
   * HTTP&nbsp;403 with the preserved legacy text {@link AuthorizationException#ADMIN_ONLY_MESSAGE}.
   */
  @Test
  void handleAuthorization_noArg_returns403WithAdminOnlyMessage() {
    ResponseEntity<ProblemDetail> response =
        handler.handleAuthorization(new AuthorizationException());

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    ProblemDetail body = bodyOf(response);
    assertThat(body.getTitle()).isEqualTo("Authorization Denied");
    assertThat(body.getDetail()).isEqualTo(AuthorizationException.ADMIN_ONLY_MESSAGE);
  }

  /**
   * A blank authorization message defensively falls back to {@link
   * AuthorizationException#ADMIN_ONLY_MESSAGE}, exercising the handler's blank-message branch.
   */
  @Test
  void handleAuthorization_blankMessage_fallsBackToAdminOnlyMessage() {
    ResponseEntity<ProblemDetail> response =
        handler.handleAuthorization(new AuthorizationException(""));

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(bodyOf(response).getDetail()).isEqualTo(AuthorizationException.ADMIN_ONLY_MESSAGE);
  }

  // ---------------------------------------------------------------------------------------------
  // RecordNotFoundException -> 404 Not Found (online FILE STATUS '23'); leak-free contract
  // ---------------------------------------------------------------------------------------------

  /**
   * The not-found handler returns HTTP&nbsp;404 with the fixed, generic {@code detail} {@code
   * "Record not found"} — and that detail must never contain the raw lookup key (the corrected
   * contract; the original leak propagated {@code ex.getMessage()}, which embeds the key).
   */
  @Test
  void handleRecordNotFound_returns404WithGenericKeyFreeDetail() {
    RecordNotFoundException ex = new RecordNotFoundException("Card", RAW_CARD_KEY);

    ResponseEntity<ProblemDetail> response = handler.handleRecordNotFound(ex);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    ProblemDetail body = bodyOf(response);
    assertThat(body.getTitle()).isEqualTo("Record Not Found");
    assertThat(body.getDetail()).isEqualTo(RECORD_NOT_FOUND_DETAIL);
    assertThat(body.getDetail()).doesNotContain(RAW_CARD_KEY);
  }

  /**
   * The response body retains only the non-sensitive logical {@code "entity"} property and never
   * surfaces the raw key — neither as a {@code "key"} property nor embedded in any other property
   * value.
   */
  @Test
  void handleRecordNotFound_omitsRawKeyFromResponseBody() {
    RecordNotFoundException ex = new RecordNotFoundException("Card", RAW_CARD_KEY);

    ProblemDetail body = bodyOf(handler.handleRecordNotFound(ex));

    Map<String, Object> properties = body.getProperties();
    assertThat(properties).isNotNull();
    assertThat(properties).containsEntry("entity", "Card");
    assertThat(properties).doesNotContainKey("key");
    assertThat(properties.values())
        .noneMatch(value -> String.valueOf(value).contains(RAW_CARD_KEY));
  }

  /**
   * The {@code WARN} log line records only a masked key (PCI-style "reveal the last four") and
   * never the raw identifier.
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
   * A short key (four characters or fewer) is masked in full so it is never echoed verbatim,
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
   * entity and the key to the empty string), the handler still returns the generic 404 detail and
   * adds neither an {@code "entity"} nor a {@code "key"} property, exercising the blank-context
   * branch.
   */
  @Test
  void handleRecordNotFound_blankContext_returnsGenericDetailWithoutEntityOrKey() {
    RecordNotFoundException ex = new RecordNotFoundException("Record not found");

    ResponseEntity<ProblemDetail> response = handler.handleRecordNotFound(ex);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    ProblemDetail body = bodyOf(response);
    assertThat(body.getDetail()).isEqualTo(RECORD_NOT_FOUND_DETAIL);
    Map<String, Object> properties = body.getProperties();
    assertThat(properties).isNotNull();
    assertThat(properties).doesNotContainKey("entity");
    assertThat(properties).doesNotContainKey("key");
  }

  // ---------------------------------------------------------------------------------------------
  // IoStatusException -> 500 Internal Server Error (online abend equivalent of unexpected status)
  // ---------------------------------------------------------------------------------------------

  /**
   * An unexpected {@code FILE STATUS} returns HTTP&nbsp;500 carrying the byte-faithful operator
   * display line and the diagnostic properties: the preserved CICS {@code abendCode} {@code
   * "9999"}, the four-character formatted {@code fileStatus} ({@code "0035"} for raw {@code "35"},
   * the COBOL Branch B zero-pad), the logical {@code file} name, and the failing {@code operation}.
   */
  @Test
  void handleIoStatus_returns500WithAbendCodeAndFormattedStatusProperties() {
    ResponseEntity<ProblemDetail> response =
        handler.handleIoStatus(new IoStatusException("ACCTFILE", "READ", "35"));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    ProblemDetail body = bodyOf(response);
    assertThat(body.getTitle()).isEqualTo("I/O Error");
    assertThat(body.getDetail()).isEqualTo("FILE STATUS IS: NNNN0035");
    Map<String, Object> properties = body.getProperties();
    assertThat(properties).isNotNull();
    assertThat(properties).containsEntry("abendCode", CICS_ABEND_CODE);
    assertThat(properties).containsEntry("fileStatus", "0035");
    assertThat(properties).containsEntry("file", "ACCTFILE");
    assertThat(properties).containsEntry("operation", "READ");
  }

  // ---------------------------------------------------------------------------------------------
  // CardDemoException -> 500 Internal Server Error (catch-all, online abend equivalent)
  // ---------------------------------------------------------------------------------------------

  /**
   * The catch-all for the {@link CardDemoException} hierarchy returns HTTP&nbsp;500, echoes a
   * non-blank message as the detail, and attaches the preserved {@code abendCode}.
   */
  @Test
  void handleCardDemo_catchAll_returns500AndEchoesDetail() {
    ResponseEntity<ProblemDetail> response =
        handler.handleCardDemo(new CardDemoException("generic app failure"));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    ProblemDetail body = bodyOf(response);
    assertThat(body.getTitle()).isEqualTo("Application Error");
    assertThat(body.getDetail()).isEqualTo("generic app failure");
    assertThat(body.getProperties()).isNotNull();
    assertThat(body.getProperties()).containsEntry("abendCode", CICS_ABEND_CODE);
  }

  /**
   * A blank {@link CardDemoException} message falls back to the default abend message, proving the
   * preserved {@code DEFAULT_ABEND_MSG} value ({@code "UNEXPECTED ABEND OCCURRED."}, COACTVWC
   * L919).
   */
  @Test
  void handleCardDemo_blankMessage_usesDefaultAbendMessage() {
    ResponseEntity<ProblemDetail> response = handler.handleCardDemo(new CardDemoException(""));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(bodyOf(response).getDetail()).isEqualTo(DEFAULT_ABEND_MSG);
  }

  // ---------------------------------------------------------------------------------------------
  // Exception (fallback) -> 500 Internal Server Error (generic abend, no stack/message leak)
  // ---------------------------------------------------------------------------------------------

  /**
   * The final fallback for any non-{@code CardDemoException} returns HTTP&nbsp;500 with exactly the
   * generic abend message (parity with {@code COACTVWC} L919) and the preserved {@code abendCode}.
   *
   * <p><strong>Security (AAP &sect;0.7.2):</strong> the originating exception text and class name
   * are never exposed to the client. The raw message below is a non-sensitive test literal used
   * solely to prove it does not appear in the response detail.
   */
  @Test
  void handleUnexpected_returns500WithDefaultAbendMessageAndNoStackLeak() {
    RuntimeException internal = new RuntimeException("internal failure detail that must not leak");

    ResponseEntity<ProblemDetail> response = handler.handleUnexpected(internal);

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    ProblemDetail body = bodyOf(response);
    assertThat(body.getTitle()).isEqualTo("Unexpected Error");
    assertThat(body.getDetail()).isEqualTo(DEFAULT_ABEND_MSG);
    assertThat(body.getDetail()).doesNotContain("internal failure detail");
    assertThat(body.getDetail()).doesNotContain("RuntimeException");
    assertThat(body.getProperties()).isNotNull();
    assertThat(body.getProperties()).containsEntry("abendCode", CICS_ABEND_CODE);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Asserts the response and its body are non-{@code null} and returns the {@link ProblemDetail}
   * body, removing repetitive null guards from the individual tests.
   *
   * @param response the handler result to unwrap
   * @return the non-{@code null} {@link ProblemDetail} body
   */
  private static ProblemDetail bodyOf(ResponseEntity<ProblemDetail> response) {
    assertThat(response).isNotNull();
    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    return body;
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

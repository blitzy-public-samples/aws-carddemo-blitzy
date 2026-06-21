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

import com.aws.carddemo.util.Messages;
import java.time.OffsetDateTime;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central Spring MVC {@link ControllerAdvice} that translates the {@code
 * com.aws.carddemo.exception} hierarchy into HTTP / screen error responses for the
 * <strong>online</strong> layer, reproducing the legacy CICS online error and abend behaviour of
 * the migrated AWS CardDemo programs (Agent Action Plan &sect;0.6.6 — "Online abends map to a
 * {@code @ControllerAdvice} handler"; &sect;0.4.2 — "FILE STATUS / RESP &rarr; typed exceptions +
 * {@code @ControllerAdvice}").
 *
 * <p><strong>Scope: online only.</strong> This advice governs the web (controller) layer. Batch
 * abends are deliberately <em>out of scope</em> here: a Spring Batch step that raises one of these
 * exceptions maps the failure to a failed step exit status in the batch layer (AAP &sect;0.6.6),
 * not to an HTTP response. Consequently the online CICS abend code {@code "9999"} surfaced by this
 * class is distinct from the batch {@code CEE3ABD} abend code {@code 999} preserved on {@link
 * IoStatusException#BATCH_ABEND_CODE}; the two are not interchangeable.
 *
 * <p><strong>Source specification.</strong> The behaviour mirrors two legacy COBOL archetypes:
 *
 * <ul>
 *   <li>{@code legacy/app/cbl/CBTRN02C.cbl} — the per-file two-byte {@code FILE STATUS} branching
 *       and the {@code 9910-DISPLAY-IO-STATUS} / {@code 9999-ABEND-PROGRAM} paragraphs [L707-L727]
 *       that surfaced the failing status and terminated the run unit on an unrecoverable I/O
 *       condition.
 *   <li>{@code legacy/app/cbl/COACTVWC.cbl} — the online {@code ABEND-ROUTINE} [L916-L937]: when no
 *       message was set it defaulted to {@code 'UNEXPECTED ABEND OCCURRED.'} [L919], moved the
 *       program name into {@code ABEND-CULPRIT} [L922], and issued {@code EXEC CICS ABEND
 *       ABCODE('9999')} [L934-L935]. The same default text and abend code are preserved here as
 *       {@link #DEFAULT_ABEND_MSG} and {@link #CICS_ABEND_CODE}.
 * </ul>
 *
 * <p><strong>Response model.</strong> Every handler returns a {@link ResponseEntity} whose body is
 * an RFC&nbsp;7807 {@link ProblemDetail} (a Spring Boot 3 built-in, so no custom error DTO is
 * introduced). Although the class is annotated with {@code @ControllerAdvice} (not
 * {@code @RestControllerAdvice}), returning a {@code ResponseEntity} makes Spring write the body
 * through the configured message converters regardless, so the advice serves REST-style controllers
 * today and remains compatible with a future Thymeleaf view layer (which can add view-based advice
 * without altering this class).
 *
 * <p><strong>HTTP status mapping.</strong> The four statuses encode the legacy distinction between
 * recoverable "soft" screen errors (the COBOL programs set {@code ERR-FLG} and re-sent the screen)
 * and unrecoverable abends (control transferred to the abend paragraph):
 *
 * <table border="1">
 *   <caption>Exception &rarr; HTTP status mapping</caption>
 *   <tr><th>Exception</th><th>HTTP status</th><th>Legacy parity</th></tr>
 *   <tr><td>{@link ValidationException}</td><td>400 Bad Request</td><td>field-edit soft error</td></tr>
 *   <tr><td>{@link AuthorizationException}</td><td>403 Forbidden</td><td>{@code CDEMO-USRTYP} gate (service entry guard)</td></tr>
 *   <tr><td>{@link AccessDeniedException}</td><td>403 Forbidden</td><td>{@code CDEMO-USRTYP} gate ({@code @PreAuthorize} method security)</td></tr>
 *   <tr><td>{@link RecordNotFoundException}</td><td>404 Not Found</td><td>FILE STATUS {@code '23'}</td></tr>
 *   <tr><td>{@link IoStatusException}</td><td>500 Internal Server Error</td><td>I/O abend</td></tr>
 *   <tr><td>{@link CardDemoException}</td><td>500 Internal Server Error</td><td>generic abend (catch-all)</td></tr>
 *   <tr><td>{@link Exception}</td><td>500 Internal Server Error</td><td>unexpected abend (fallback)</td></tr>
 * </table>
 *
 * <p><strong>Security hygiene.</strong> Client-facing {@code detail} text never carries credentials
 * or raw stack traces (AAP &sect;0.7.2). The unrecoverable handlers log the server-side stack trace
 * for operators (mirroring the legacy joblog visibility) while returning only the byte-faithful
 * abend message to the caller.
 *
 * <p>The advice is stateless and side-effect-light: each handler merely composes a {@link
 * ProblemDetail} and logs. It performs no database access or other I/O, and it is auto-detected by
 * the {@code @SpringBootApplication} component scan, so no manual registration is required.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

  /** SLF4J logger used to mirror the COBOL {@code DISPLAY} of error and abend lines to the log. */
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * The online CICS abend code preserved from {@code EXEC CICS ABEND ABCODE('9999')} in the legacy
   * online {@code ABEND-ROUTINE} (for example {@code legacy/app/cbl/COACTVWC.cbl} [L934-L935], and
   * the same pattern in {@code COACTUPC}/{@code COCRDSLC}/{@code COCRDUPC}). It is surfaced as the
   * {@code "abendCode"} {@link ProblemDetail} property on every unrecoverable (HTTP&nbsp;500)
   * response so the externally observable abend code survives the migration (AAP &sect;0.6.6).
   */
  private static final String CICS_ABEND_CODE = "9999";

  /**
   * The default abend message preserved verbatim from the legacy online {@code ABEND-ROUTINE},
   * which executed {@code MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG} when no specific message
   * had been set ({@code legacy/app/cbl/COACTVWC.cbl} [L919]). It is the client-facing {@code
   * detail} for an unrecoverable condition that carries no message of its own, and is the only text
   * returned for the generic fallback so no internal exception detail ever leaks to the client.
   */
  private static final String DEFAULT_ABEND_MSG = "UNEXPECTED ABEND OCCURRED.";

  /**
   * The generic, key-free client-facing {@code detail} returned for every {@link
   * RecordNotFoundException} (online FILE STATUS {@code '23'}). It deliberately carries <em>no</em>
   * lookup key — which {@link RecordNotFoundException} documents may be an account id, card number,
   * or user id — so a missing-record response never discloses a sensitive identifier (AAP
   * &sect;0.7.2 security hygiene; CWE-532 / information disclosure). This also matches the legacy
   * COBOL parity: the sign-on program {@code legacy/app/cbl/COSGN00C.cbl} [L249] set the generic
   * screen message {@code 'User not found. Try again ...'} on {@code RESP = NOTFND} rather than
   * echoing the looked-up key.
   */
  private static final String RECORD_NOT_FOUND_DETAIL = "Record not found";

  /**
   * The fixed, path-free client-facing {@code detail} returned for every {@link
   * NoResourceFoundException} (an unmapped URL or static-resource miss, such as a mistyped route or
   * a browser's automatic {@code GET /favicon.ico} probe). The requested path is deliberately
   * <em>not</em> echoed back, keeping the response free of request-controlled content (AAP
   * &sect;0.7.2 security hygiene).
   */
  private static final String RESOURCE_NOT_FOUND_DETAIL = "The requested resource was not found.";

  /**
   * The fixed client-facing {@code detail} returned for every {@link
   * HttpRequestMethodNotSupportedException} (a request method a known route does not support). The
   * set of supported methods is conveyed through the {@code Allow} response header (RFC&nbsp;7231
   * &sect;6.5.5) rather than through this text.
   */
  private static final String METHOD_NOT_ALLOWED_DETAIL =
      "Request method not supported for this resource.";

  /**
   * The fixed, request-content-free client-facing {@code detail} returned for a request-binding or
   * type-conversion failure (a {@link BindException} — including its {@code
   * MethodArgumentNotValidException} subclass — or a {@link TypeMismatchException}, such as its
   * {@code MethodArgumentTypeMismatchException} subclass). The rejected value, field path, and
   * framework exception type are deliberately <em>not</em> echoed back, keeping the response free
   * of request-controlled content and of internal framework detail (AAP &sect;0.7.2 security
   * hygiene; CWE-209 / information disclosure).
   */
  private static final String BAD_INPUT_DETAIL =
      "One or more submitted fields are not in the expected format.";

  /** {@link ProblemDetail} property name carrying the response-composition timestamp. */
  private static final String PROP_TIMESTAMP = "timestamp";

  /** {@link ProblemDetail} property name carrying the preserved online CICS abend code. */
  private static final String PROP_ABEND_CODE = "abendCode";

  /** {@link ProblemDetail} property name carrying the offending screen field, when known. */
  private static final String PROP_FIELD = "field";

  /** {@link ProblemDetail} property name carrying the searched logical entity, when known. */
  private static final String PROP_ENTITY = "entity";

  /** {@link ProblemDetail} property name carrying the four-character formatted FILE STATUS. */
  private static final String PROP_FILE_STATUS = "fileStatus";

  /** {@link ProblemDetail} property name carrying the logical file / DD / dataset name. */
  private static final String PROP_FILE = "file";

  /** {@link ProblemDetail} property name carrying the failing I/O verb. */
  private static final String PROP_OPERATION = "operation";

  /**
   * Handles a {@link ValidationException} (the pervasive COBOL field-edit soft error) by returning
   * <strong>HTTP&nbsp;400 (Bad Request)</strong>.
   *
   * <p>The {@code detail} is the exception's user-facing message verbatim; when that message is
   * blank it falls back to {@link Messages#MSG_INVALID_KEY} trimmed of its COBOL {@code PIC X(50)}
   * padding ({@code "Invalid key pressed. Please see below..."}). When the offending field is known
   * it is surfaced as the {@code "field"} property to drive cursor placement / highlighting,
   * mirroring the legacy {@code MOVE -1 TO <field>L} convention. Logged at {@code WARN} because the
   * error is user-correctable and is <em>not</em> an abend.
   *
   * @param ex the validation failure raised by the online layer
   * @return a 400 response whose body is a {@link ProblemDetail} describing the invalid input
   */
  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ProblemDetail> handleValidation(ValidationException ex) {
    String message = ex.getMessage();
    String detail = isBlank(message) ? Messages.MSG_INVALID_KEY.trim() : message;
    ProblemDetail body = problemDetail(HttpStatus.BAD_REQUEST, detail, "Validation Error");
    String fieldName = ex.getFieldName();
    if (!isBlank(fieldName)) {
      body.setProperty(PROP_FIELD, fieldName);
    }
    log.warn("Validation error (field={}): {}", fieldName, detail);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * Handles an {@link AuthorizationException} (the COBOL {@code CDEMO-USRTYP} admin-only gate) by
   * returning <strong>HTTP&nbsp;403 (Forbidden)</strong>.
   *
   * <p>The {@code detail} is the exception message, which for the no-argument case is the preserved
   * legacy text {@link AuthorizationException#ADMIN_ONLY_MESSAGE} ({@code "No access - Admin Only
   * option..."}); a blank message defensively falls back to the same constant. Logged at {@code
   * WARN} because a denied navigation attempt is a recoverable, user-visible condition rather than
   * an abend.
   *
   * @param ex the role-gating denial raised by the online layer
   * @return a 403 response whose body is a {@link ProblemDetail} describing the denial
   */
  @ExceptionHandler(AuthorizationException.class)
  public ResponseEntity<ProblemDetail> handleAuthorization(AuthorizationException ex) {
    String message = ex.getMessage();
    String detail = isBlank(message) ? AuthorizationException.ADMIN_ONLY_MESSAGE : message;
    ProblemDetail body = problemDetail(HttpStatus.FORBIDDEN, detail, "Authorization Denied");
    log.warn("Authorization denied: {}", detail);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
  }

  /**
   * Handles Spring Security's framework {@link AccessDeniedException} — raised when method security
   * ({@code @EnableMethodSecurity(prePostEnabled = true)} in {@code
   * legacy/../config/SecurityConfig}) denies a {@code @PreAuthorize("hasRole('ADMIN')")} gate — by
   * returning <strong>HTTP&nbsp;403 (Forbidden)</strong>, the <em>same</em> status and screen
   * message as the service-layer {@link AuthorizationException} path.
   *
   * <p><strong>Why this handler exists (defense-in-depth parity).</strong> The legacy {@code
   * CDEMO-USRTYP} admin-only gate is reproduced in two complementary layers: the service entry
   * guard raises {@link AuthorizationException} (handled above), while the controller class-level
   * {@code @PreAuthorize} causes the framework to throw this {@link AccessDeniedException}
   * <em>before</em> the service is ever invoked. Without this handler the framework denial would
   * fall through to the unexpected-error catch-all {@link #handleUnexpected(Exception)} and surface
   * as a misleading HTTP&nbsp;500 abend rather than the correct 403. Mapping it here guarantees
   * that — regardless of which layer trips first — a non-admin navigation attempt yields an
   * identical 403 response.
   *
   * <p><strong>Message parity.</strong> Every method-security gate in the migrated application is
   * an {@code hasRole('ADMIN')} gate (the admin menu controller and the four admin user-management
   * services), so the denial is always the admin-only condition. The client-facing {@code detail}
   * is therefore the preserved legacy text {@link AuthorizationException#ADMIN_ONLY_MESSAGE}
   * ({@code "No access - Admin Only option..."}), unifying this path with {@link
   * #handleAuthorization(AuthorizationException)}. Spring's generic framework message is
   * deliberately <em>not</em> propagated to the caller. Logged at {@code WARN} because a denied
   * navigation attempt is a recoverable, user-visible condition rather than an abend.
   *
   * @param ex the framework method-security denial raised by a {@code @PreAuthorize} gate
   * @return a 403 response whose body is a {@link ProblemDetail} describing the denial
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    String detail = AuthorizationException.ADMIN_ONLY_MESSAGE;
    ProblemDetail body = problemDetail(HttpStatus.FORBIDDEN, detail, "Authorization Denied");
    // Log the framework cause (safe boilerplate, no credentials or stack) for operator correlation;
    // the client-facing detail remains the preserved legacy admin-only message.
    log.warn("Access denied by method security ({}): {}", ex.getClass().getSimpleName(), detail);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
  }

  /**
   * Handles a {@link RecordNotFoundException} (the online FILE STATUS {@code '23'} record-not-found
   * branch) by returning <strong>HTTP&nbsp;404 (Not Found)</strong>.
   *
   * <p><strong>Security hygiene (AAP &sect;0.7.2; CWE-532 — information disclosure).</strong> The
   * lookup key carried by {@link RecordNotFoundException} may be a sensitive identifier (an account
   * id, a full card number, or a user id). It is therefore <em>never</em> disclosed to the caller
   * or written to the log:
   *
   * <ul>
   *   <li>the client-facing {@code detail} is the fixed, key-free message {@link
   *       #RECORD_NOT_FOUND_DETAIL} — the exception's own message ({@code "Account not found for
   *       key: …"}) is deliberately <em>not</em> propagated, mirroring the generic legacy screen
   *       message {@code 'User not found. Try again ...'} ({@code legacy/app/cbl/COSGN00C.cbl}
   *       [L249]);
   *   <li>the response body surfaces only the non-sensitive logical {@code "entity"} (for example
   *       {@code "Account"}) for diagnostics; the raw {@code key} is <em>omitted</em> entirely;
   *   <li>the {@code WARN} log records the entity together with a {@linkplain #maskKey(String)
   *       masked} key (for example {@code ************1111}) so operators retain correlation value
   *       without the raw identifier ever reaching the joblog.
   * </ul>
   *
   * <p>The full structured context ({@link RecordNotFoundException#getKey()} / {@link
   * RecordNotFoundException#getMessage()}) remains available on the exception object itself for
   * controlled, in-process inspection — it is simply never emitted through these external channels.
   * Logged at {@code WARN}: a missing record on a required online read is user-visible but
   * recoverable, never an abend.
   *
   * @param ex the not-found condition raised by the online layer
   * @return a 404 response whose body is a {@link ProblemDetail} describing the missing record
   *     without exposing the raw lookup key
   */
  @ExceptionHandler(RecordNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleRecordNotFound(RecordNotFoundException ex) {
    ProblemDetail body =
        problemDetail(HttpStatus.NOT_FOUND, RECORD_NOT_FOUND_DETAIL, "Record Not Found");
    String entityName = ex.getEntityName();
    if (!isBlank(entityName)) {
      body.setProperty(PROP_ENTITY, entityName);
    }
    log.warn("Record not found (entity={}, maskedKey={})", entityName, maskKey(ex.getKey()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  /**
   * Handles an {@link IoStatusException} — the online abend equivalent of an unexpected {@code FILE
   * STATUS} — by returning <strong>HTTP&nbsp;500 (Internal Server Error)</strong>.
   *
   * <p>The {@code detail} is the byte-faithful operator line {@link
   * IoStatusException#getDisplayMessage()} ({@code "FILE STATUS IS: NNNN<formatted-status>"}, AAP
   * &sect;0.6.6). The diagnostic context is surfaced as properties: {@code "abendCode"} ({@link
   * #CICS_ABEND_CODE}), {@code "fileStatus"} ({@link IoStatusException#getFormattedStatus()}),
   * {@code "file"} and {@code "operation"}. Logged at {@code ERROR}, mirroring the COBOL {@code
   * DISPLAY 'ERROR ...'} followed by {@code 9910-DISPLAY-IO-STATUS}.
   *
   * @param ex the unexpected-status I/O failure raised by the online layer
   * @return a 500 response whose body is a {@link ProblemDetail} describing the I/O abend
   */
  @ExceptionHandler(IoStatusException.class)
  public ResponseEntity<ProblemDetail> handleIoStatus(IoStatusException ex) {
    String detail = ex.getDisplayMessage();
    ProblemDetail body = problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, detail, "I/O Error");
    body.setProperty(PROP_ABEND_CODE, CICS_ABEND_CODE);
    body.setProperty(PROP_FILE_STATUS, ex.getFormattedStatus());
    body.setProperty(PROP_FILE, ex.getFileName());
    body.setProperty(PROP_OPERATION, ex.getOperation());
    log.error(
        "Online I/O abend [{}] on file {} during {} - {}",
        CICS_ABEND_CODE,
        ex.getFileName(),
        ex.getOperation(),
        detail);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  /**
   * Catch-all for the {@link CardDemoException} hierarchy that returns <strong>HTTP&nbsp;500
   * (Internal Server Error)</strong>, the online equivalent of the COBOL abend.
   *
   * <p>Declared after the more specific subtype handlers for clarity; Spring already dispatches to
   * the nearest-matching handler, so this method receives only a {@code CardDemoException} thrown
   * directly or a future subtype without its own handler. The {@code detail} is the exception
   * message, or {@link #DEFAULT_ABEND_MSG} when blank, and the preserved {@code "abendCode"} is
   * attached. Logged at {@code ERROR} with the throwable so the stack trace is captured
   * server-side.
   *
   * @param ex the application-level error raised anywhere in the hierarchy
   * @return a 500 response whose body is a {@link ProblemDetail} describing the application error
   */
  @ExceptionHandler(CardDemoException.class)
  public ResponseEntity<ProblemDetail> handleCardDemo(CardDemoException ex) {
    String message = ex.getMessage();
    String detail = isBlank(message) ? DEFAULT_ABEND_MSG : message;
    ProblemDetail body =
        problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, detail, "Application Error");
    body.setProperty(PROP_ABEND_CODE, CICS_ABEND_CODE);
    log.error("Application abend [{}]: {}", CICS_ABEND_CODE, detail, ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  /**
   * Handles Spring MVC's {@link NoResourceFoundException} — raised when a request targets a URL
   * that maps to no controller handler and no static resource (for example a mistyped route, or a
   * browser's automatic {@code GET /favicon.ico} probe) — by returning <strong>HTTP&nbsp;404 (Not
   * Found)</strong>.
   *
   * <p><strong>Why this handler exists.</strong> {@link NoResourceFoundException} extends {@link
   * Exception}, so without a dedicated mapping it falls through to {@link
   * #handleUnexpected(Exception)} and is misreported as an HTTP&nbsp;500 abend carrying {@link
   * #CICS_ABEND_CODE}. That both inflates server-error (5xx) metrics and masks a genuine client
   * mistake. A routing/resource miss is a <em>client</em> error, not an application abend, so this
   * handler attaches <em>no</em> {@code "abendCode"} property and reserves the {@code "9999"} abend
   * exclusively for genuine unrecoverable server failures.
   *
   * <p><strong>Security hygiene.</strong> The client-facing {@code detail} is the fixed, path-free
   * {@link #RESOURCE_NOT_FOUND_DETAIL}; the requested path is never echoed back (AAP &sect;0.7.2).
   * Logged at {@code DEBUG} because unmapped-URL and {@code favicon.ico} probes are routine
   * background noise rather than actionable events.
   *
   * @param ex the unmapped-resource condition raised by the Spring MVC dispatcher
   * @return a 404 response whose body is a {@link ProblemDetail} describing the missing resource
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoResourceFound(NoResourceFoundException ex) {
    ProblemDetail body =
        problemDetail(HttpStatus.NOT_FOUND, RESOURCE_NOT_FOUND_DETAIL, "Resource Not Found");
    log.debug("No resource found for request: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  /**
   * Handles Spring MVC's {@link HttpRequestMethodNotSupportedException} — raised when a known route
   * is invoked with an HTTP method it does not support (for example a {@code GET} against a {@code
   * POST}-only action, or a {@code POST} against a {@code GET}-only render) — by returning
   * <strong>HTTP&nbsp;405 (Method Not Allowed)</strong> with the {@code Allow} response header
   * enumerating the supported methods, as RFC&nbsp;7231 &sect;6.5.5 requires.
   *
   * <p><strong>Why this handler exists.</strong> Like {@link NoResourceFoundException}, this
   * framework exception extends {@link Exception} and would otherwise be misreported by {@link
   * #handleUnexpected(Exception)} as an HTTP&nbsp;500 abend. A wrong-method request is a
   * <em>client</em> error, not an application abend, so this handler attaches <em>no</em> {@code
   * "abendCode"} property.
   *
   * <p><strong>Security hygiene.</strong> The client-facing {@code detail} is the fixed {@link
   * #METHOD_NOT_ALLOWED_DETAIL}; the supported methods are conveyed only through the {@code Allow}
   * header (which the framework reports via {@link
   * HttpRequestMethodNotSupportedException#getSupportedHttpMethods()}). Logged at {@code WARN}
   * because a method mismatch on an existing route can indicate a client-integration defect worth
   * surfacing.
   *
   * @param ex the method-not-supported condition raised by the Spring MVC dispatcher
   * @return a 405 response whose body is a {@link ProblemDetail} and whose {@code Allow} header
   *     lists the supported HTTP methods (when the framework reports them)
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex) {
    ProblemDetail body =
        problemDetail(
            HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_DETAIL, "Method Not Allowed");
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
    Set<HttpMethod> supportedMethods = ex.getSupportedHttpMethods();
    if (supportedMethods != null && !supportedMethods.isEmpty()) {
      builder.allow(supportedMethods.toArray(new HttpMethod[0]));
    }
    log.warn("Method not supported: {}", ex.getMessage());
    return builder.body(body);
  }

  /**
   * Handles a request data-binding or type-conversion failure by returning <strong>HTTP&nbsp;400
   * (Bad Request)</strong> rather than letting it fall through to the {@code Exception} catch-all
   * (which would mislabel a client typo as a {@code "9999"} server abend / HTTP 500).
   *
   * <p>This is the framework-level safety net for the kind of input the COBOL programs validate
   * character-by-character and <em>never</em> abend on: a non-numeric value typed into a field the
   * binder must convert. It covers {@link BindException} (and therefore its {@code
   * MethodArgumentNotValidException} subclass — {@code @ModelAttribute} binding/validation errors)
   * and {@link TypeMismatchException} (and therefore its {@code
   * MethodArgumentTypeMismatchException} subclass — {@code @RequestParam}/{@code @PathVariable}
   * conversion errors). The monetary screen fields are now carried as strings and validated in the
   * service layer, so well-behaved screens report the precise COBOL field message before reaching
   * here; this handler guarantees that any remaining binder-level conversion failure is still a
   * graceful, leak-free {@code 400}.
   *
   * <p>The client-facing {@code detail} is the fixed {@link #BAD_INPUT_DETAIL}; the rejected value,
   * field path, and framework exception type are deliberately withheld from the response (AAP
   * &sect;0.7.2 security hygiene) and logged at {@code WARN} (the error is user-correctable and is
   * <em>not</em> an abend, so no {@code "abendCode"} property is attached and the {@code "9999"}
   * code is reserved for genuine unrecoverable failures).
   *
   * @param ex the binding or type-conversion failure raised by the web binder
   * @return a 400 response whose body is a {@link ProblemDetail} describing the invalid input
   */
  @ExceptionHandler({BindException.class, TypeMismatchException.class})
  public ResponseEntity<ProblemDetail> handleBindingFailure(Exception ex) {
    ProblemDetail body = problemDetail(HttpStatus.BAD_REQUEST, BAD_INPUT_DETAIL, "Invalid Input");
    log.warn("Request binding/type-conversion failure: {}", ex.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * Final fallback for any non-CardDemo {@link Exception} (for example an unexpected runtime
   * failure), returning <strong>HTTP&nbsp;500 (Internal Server Error)</strong> so a caller never
   * sees a raw stack trace.
   *
   * <p>The client-facing {@code detail} is exactly {@link #DEFAULT_ABEND_MSG}; the originating
   * exception text and stack trace are deliberately withheld from the response and logged at {@code
   * ERROR} with the throwable instead, so operators retain full server-side diagnostics (mirroring
   * legacy joblog visibility) while the client receives only the generic abend message. The
   * preserved {@code "abendCode"} is attached for parity with the other unrecoverable responses.
   *
   * @param ex the unexpected error that escaped the typed hierarchy
   * @return a 500 response whose body is a {@link ProblemDetail} carrying only the generic abend
   *     message
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    ProblemDetail body =
        problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, DEFAULT_ABEND_MSG, "Unexpected Error");
    body.setProperty(PROP_ABEND_CODE, CICS_ABEND_CODE);
    log.error("Unexpected abend [{}]: {}", CICS_ABEND_CODE, DEFAULT_ABEND_MSG, ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  /**
   * Builds a base {@link ProblemDetail} for the supplied status and detail, applying the title and
   * a composition {@code "timestamp"} property shared by every handler.
   *
   * @param status the HTTP status for the response
   * @param detail the human-readable, client-safe detail message
   * @param title the short, human-readable summary of the problem type
   * @return a populated {@link ProblemDetail} ready for any handler-specific properties
   */
  private static ProblemDetail problemDetail(HttpStatus status, String detail, String title) {
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
    problemDetail.setTitle(title);
    problemDetail.setProperty(PROP_TIMESTAMP, OffsetDateTime.now());
    return problemDetail;
  }

  /**
   * Null-safe blank test mirroring {@link String#isBlank()} but tolerating a {@code null} argument.
   *
   * @param value the value to test; may be {@code null}
   * @return {@code true} when {@code value} is {@code null}, empty, or whitespace-only
   */
  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Produces a log-safe, masked rendering of a not-found lookup key so a sensitive identifier (an
   * account id, a full card number, or a user id) is never written to the log in the clear (AAP
   * &sect;0.7.2 security hygiene; CWE-532 — information disclosure).
   *
   * <p>The masking is PCI-style "reveal the last four": every character except the final four is
   * replaced with {@code '*'}. A key of four characters or fewer is masked in full so a short
   * identifier is never echoed verbatim, and a {@code null}, empty, or whitespace-only key yields
   * the empty string. The key is {@linkplain String#strip() stripped} first so trailing padding
   * from a fixed-width COBOL field does not skew the masked suffix.
   *
   * <table border="1">
   *   <caption>Masking examples</caption>
   *   <tr><th>Raw key</th><th>Masked</th></tr>
   *   <tr><td>{@code 4111111111111111}</td><td>{@code ************1111}</td></tr>
   *   <tr><td>{@code 00000000123}</td><td>{@code *******0123}</td></tr>
   *   <tr><td>{@code USER0001}</td><td>{@code ****0001}</td></tr>
   *   <tr><td>{@code 01}</td><td>{@code **}</td></tr>
   *   <tr><td>{@code ""} / {@code null}</td><td>{@code ""}</td></tr>
   * </table>
   *
   * @param key the raw lookup key to mask; may be {@code null}
   * @return the masked key, never {@code null}; the empty string when {@code key} is {@code null},
   *     empty, or whitespace-only
   */
  private static String maskKey(String key) {
    if (key == null) {
      return "";
    }
    String stripped = key.strip();
    int length = stripped.length();
    if (length == 0) {
      return "";
    }
    int visible = 4;
    if (length <= visible) {
      return "*".repeat(length);
    }
    return "*".repeat(length - visible) + stripped.substring(length - visible);
  }
}

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

/**
 * Root of the AWS CardDemo typed exception hierarchy.
 *
 * <p>This unchecked exception is the single base type for every error condition raised by the
 * migrated CardDemo application. It replaces the legacy z/OS COBOL error model — the per-file
 * two-byte {@code FILE STATUS} fields and CICS {@code RESP} codes that the original programs
 * inspected after each I/O verb — together with the COBOL abend path itself. In the daily-posting
 * archetype {@code legacy/app/cbl/CBTRN02C.cbl}, an unrecoverable I/O condition performed {@code
 * 9910-DISPLAY-IO-STATUS} (to surface the failing two-byte status) and then {@code
 * 9999-ABEND-PROGRAM}, which issued {@code CALL 'CEE3ABD'} to terminate the run unit immediately.
 *
 * <p>Behavioral-parity note: this class carries no COBOL business logic. It is the infrastructure
 * that lets the translated services and controllers raise typed errors mirroring those original
 * program branches, so that the where-and-why of each failure is preserved even though the runtime
 * is now Spring Boot rather than CICS/batch z/OS.
 *
 * <p>The exception is deliberately <em>unchecked</em>: it extends {@link RuntimeException} rather
 * than {@link Exception}. COBOL programs never declared error conditions on their call signatures;
 * an unrecoverable condition transferred control immediately to the abend paragraph and ended the
 * program. Modeling the hierarchy as unchecked reproduces that immediate-transfer semantics and
 * keeps the translated service and controller signatures free of pervasive {@code throws} clauses
 * and forced {@code try}/{@code catch} blocks.
 *
 * <p>The class is concrete (non-{@code abstract}) and non-{@code final}: it may be thrown directly
 * as a generic application error, and it also serves as the catch-all base that the more specific
 * subtypes refine, including:
 *
 * <ul>
 *   <li>{@code IoStatusException} — carries the originating two-byte FILE STATUS and abend code for
 *       I/O failures (the {@code 9999-ABEND-PROGRAM} equivalent).
 *   <li>{@code RecordNotFoundException} — the FILE STATUS {@code '23'} (record-not-found)
 *       condition.
 *   <li>{@code ValidationException} — field- and business-rule validation failures.
 *   <li>{@code AuthorizationException} — role-gating ({@code CDEMO-USRTYP}) violations.
 * </ul>
 *
 * <p>At the application boundary the error is translated rather than propagated to the caller:
 * {@code GlobalExceptionHandler} maps this type (and its subtypes) to the appropriate online
 * HTTP/screen response — the catch-all fallback maps to HTTP 500, the online equivalent of the
 * COBOL abend — while the batch layer maps it to a failed Spring Batch step exit status.
 */
public class CardDemoException extends RuntimeException {

  /**
   * Serialization version identifier.
   *
   * <p>{@link RuntimeException} (via {@link Throwable}) is {@link java.io.Serializable}; declaring
   * an explicit {@code serialVersionUID} pins the serialized form and suppresses the {@code serial}
   * compiler warning that the project's zero-warning build ({@code -Werror -Xlint:all}) would
   * otherwise promote to a build-breaking error.
   */
  private static final long serialVersionUID = 1L;

  /**
   * Creates a new exception with the supplied detail message.
   *
   * @param message the detail message describing the error condition; later retrievable via {@link
   *     #getMessage()}
   */
  public CardDemoException(String message) {
    super(message);
  }

  /**
   * Creates a new exception with the supplied detail message and underlying cause.
   *
   * @param message the detail message describing the error condition; later retrievable via {@link
   *     #getMessage()}
   * @param cause the underlying cause (for example a JDBC or file I/O failure); later retrievable
   *     via {@link #getCause()}. A {@code null} value indicates that the cause is nonexistent or
   *     unknown.
   */
  public CardDemoException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates a new exception that wraps the supplied cause.
   *
   * <p>The detail message is derived from the cause (it becomes {@code cause.toString()} when the
   * cause is non-{@code null}), matching {@link RuntimeException#RuntimeException(Throwable)}.
   *
   * @param cause the underlying cause; later retrievable via {@link #getCause()}. A {@code null}
   *     value indicates that the cause is nonexistent or unknown.
   */
  public CardDemoException(Throwable cause) {
    super(cause);
  }
}

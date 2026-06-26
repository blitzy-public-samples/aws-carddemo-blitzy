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
 * Signals that a record required by an <em>online</em> transaction could not be located, mirroring
 * the COBOL/CICS "record not found treated as an error" branches of the legacy CardDemo programs.
 *
 * <p>In the original z/OS application a CICS read of a missing record returned {@code RESP =
 * NOTFND} (response code {@code 13}), equivalent to VSAM {@code FILE STATUS '23'}. Online programs
 * that <em>require</em> the record then set the error flag and re-sent the screen with a "not
 * found" message. The canonical example is the sign-on program {@code legacy/app/cbl/COSGN00C.cbl},
 * whose {@code EVALUATE WS-RESP-CD} contained {@code WHEN 13 ... MOVE 'User not found. Try again
 * ...' TO WS-MESSAGE} (lines 247-251); the account, card, and transaction view programs behave
 * identically when the requested key does not exist.
 *
 * <p>This exception is translated by {@code GlobalExceptionHandler} to an HTTP&nbsp;404
 * (resource-missing) response. It is intentionally kept distinct from {@code ValidationException}
 * (HTTP&nbsp;400): a missing required record is "not found", whereas a malformed user-supplied
 * value is a "bad request". The legacy screens blurred the two (both merely set {@code ERR-FLG} and
 * re-sent a message), but separating them yields correct HTTP semantics while preserving the
 * user-facing message text.
 *
 * <p><strong>Scope boundary — do NOT throw this on batch find-or-create paths.</strong> Per the
 * Agent Action Plan §0.6.4, the daily-posting batch archetype {@code legacy/app/cbl/CBTRN02C.cbl}
 * does <em>not</em> treat a missing record as an error: the cross-reference and account look-ups
 * record reject reasons (codes {@code 100}/{@code 101}) instead of failing, and the
 * transaction-category-balance read accepts {@code '00' OR '23'} and <em>creates</em> the row when
 * it is absent (find-or-create / upsert). Those paths MUST map a missing row to {@link
 * java.util.Optional#empty()} / reject-writer or create logic and MUST NOT raise this exception.
 * Reserve {@code RecordNotFoundException} for the explicit online branches where a missing record
 * is a genuine, user-visible error.
 *
 * <p>Like every type in the hierarchy it is <em>unchecked</em>: it extends {@link
 * CardDemoException}, which in turn extends {@link RuntimeException}. Translated service and
 * controller signatures therefore stay free of {@code throws} clauses, reproducing the COBOL
 * immediate-transfer-to-error-handling semantics.
 *
 * <p>Instances are immutable and carry two pieces of diagnostic context in addition to the detail
 * message — the {@linkplain #getEntityName() logical entity} that was searched (mirroring the VSAM
 * file being read) and the {@linkplain #getKey() lookup key} that produced no match — so callers
 * and exception handlers can render a precise, deterministic "not found" message.
 */
public class RecordNotFoundException extends CardDemoException {

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
   * Logical store/entity that was searched when the lookup failed — for example {@code "Account"},
   * {@code "Card"}, {@code "CardXref"}, {@code "Transaction"}, or {@code "User"} — mirroring the
   * VSAM file being read in the legacy program. Never {@code null}: a {@code null} constructor
   * argument is coerced to the empty string.
   */
  private final String entityName;

  /**
   * Lookup key value, rendered as a string (for example an account id, card number, or user id),
   * that produced no matching record. Never {@code null}: a {@code null} constructor argument is
   * coerced to the empty string.
   */
  private final String key;

  /**
   * Creates a not-found exception for the supplied entity and key, deriving the detail message from
   * {@link #defaultMessage(String, String)}.
   *
   * @param entityName the logical store/entity that was searched (for example {@code "Account"});
   *     {@code null} is stored as the empty string
   * @param key the lookup key value that produced no match (for example {@code "00000000123"});
   *     {@code null} is stored as the empty string
   */
  public RecordNotFoundException(String entityName, String key) {
    super(defaultMessage(entityName, key));
    this.entityName = entityName == null ? "" : entityName;
    this.key = key == null ? "" : key;
  }

  /**
   * Creates a not-found exception for the supplied entity and key while preserving the underlying
   * cause, deriving the detail message from {@link #defaultMessage(String, String)}.
   *
   * @param entityName the logical store/entity that was searched (for example {@code "Account"});
   *     {@code null} is stored as the empty string
   * @param key the lookup key value that produced no match (for example {@code "00000000123"});
   *     {@code null} is stored as the empty string
   * @param cause the underlying cause (for example a JDBC or file I/O failure); later retrievable
   *     via {@link #getCause()}. A {@code null} value indicates that the cause is nonexistent or
   *     unknown.
   */
  public RecordNotFoundException(String entityName, String key, Throwable cause) {
    super(defaultMessage(entityName, key), cause);
    this.entityName = entityName == null ? "" : entityName;
    this.key = key == null ? "" : key;
  }

  /**
   * Creates a not-found exception from a fully-formed detail message.
   *
   * <p>Intended for callers that have already composed the user-facing text and do not need the
   * structured {@code entityName}/{@code key} context. Both {@link #getEntityName()} and {@link
   * #getKey()} return the empty string for instances created through this constructor.
   *
   * @param message the detail message describing the not-found condition; later retrievable via
   *     {@link #getMessage()}
   */
  public RecordNotFoundException(String message) {
    super(message);
    this.entityName = "";
    this.key = "";
  }

  /**
   * Returns the logical store/entity that was searched when the lookup failed.
   *
   * @return the entity name (for example {@code "Account"}); never {@code null}, and the empty
   *     string when the {@linkplain #RecordNotFoundException(String) message-only constructor} was
   *     used
   */
  public String getEntityName() {
    return entityName;
  }

  /**
   * Returns the lookup key value that produced no matching record.
   *
   * @return the key (for example {@code "00000000123"}); never {@code null}, and the empty string
   *     when the {@linkplain #RecordNotFoundException(String) message-only constructor} was used
   */
  public String getKey() {
    return key;
  }

  /**
   * Builds the deterministic detail message for a not-found condition.
   *
   * <p>The format is exactly {@code <entityName> + " not found for key: " + <key>} — for example
   * {@code "Account not found for key: 00000000123"}. A {@code null} {@code entityName} or {@code
   * key} is treated as the empty string so the rendered text never contains the literal {@code
   * "null"}.
   *
   * @param entityName the logical store/entity that was searched; {@code null} is treated as the
   *     empty string
   * @param key the lookup key value that produced no match; {@code null} is treated as the empty
   *     string
   * @return the composed detail message
   */
  private static String defaultMessage(String entityName, String key) {
    String safeEntityName = entityName == null ? "" : entityName;
    String safeKey = key == null ? "" : key;
    return safeEntityName + " not found for key: " + safeKey;
  }
}

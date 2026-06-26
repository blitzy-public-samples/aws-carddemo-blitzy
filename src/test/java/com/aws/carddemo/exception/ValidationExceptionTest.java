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

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ValidationException}, the Java migration of the pervasive COBOL
 * field-validation / invalid-input "soft error" branch in the online (CICS) programs (set the error
 * flag, move a user-facing literal to the screen message field, re-send the map). This is the
 * highest-frequency online exception; it maps to HTTP&nbsp;400.
 *
 * <p>The behavioral specification lives read-only under {@code legacy/app/cbl}:
 *
 * <ul>
 *   <li>{@code COSGN00C.cbl} L242 — {@code 'Wrong Password. Try again ...'} (note the single space
 *       before the ellipsis);
 *   <li>{@code COSGN00C.cbl} L254 — {@code 'Unable to verify the User ...'} (single space before
 *       the ellipsis); and
 *   <li>{@code COMEN01C.cbl} L131 — {@code 'Please enter a valid option number...'} (NO space
 *       before the ellipsis).
 * </ul>
 *
 * <p>These tests lock in the two invariants that must never drift: (a) the user-facing message is
 * preserved <em>byte-faithfully</em> from the COBOL literal — the screen renderer pads to the field
 * width, but the carried message is the meaningful legacy text; and (b) the {@code (String message,
 * Throwable cause)} and {@code (String fieldName, String message)} two-argument constructors are
 * unambiguously distinguished by parameter type ({@link Throwable} vs {@link String}).
 *
 * <p>The test is intentionally framework-light: no Spring context, database, Testcontainers, or
 * Mockito — just {@code new ValidationException(...)} and AssertJ fluent assertions, matching the
 * production class's transport-agnostic, framework-free design.
 */
class ValidationExceptionTest {

  // -----------------------------------------------------------------------------------------------
  // Byte-faithful legacy message preservation — the carried detail message IS the COBOL literal.
  // -----------------------------------------------------------------------------------------------

  /**
   * The single-argument constructor preserves the exact COBOL sign-on literal (COSGN00C L242),
   * including the single space before the ellipsis, and leaves the field name unset.
   */
  @Test
  void singleArgConstructor_preservesExactLegacyMessage() {
    ValidationException ex = new ValidationException("Wrong Password. Try again ...");

    // COSGN00C.cbl L242 — exact text, note the single space before "...".
    assertThat(ex.getMessage()).isEqualTo("Wrong Password. Try again ...");
    // The message-only constructor stores a null field name (form-level error).
    assertThat(ex.getFieldName()).isNull();
  }

  /**
   * Spot-checks additional legacy literals from other online programs, asserting them
   * byte-faithfully — in particular the space-vs-no-space distinction before the ellipsis across
   * programs.
   */
  @Test
  void messagesFromOtherOnlinePrograms_preservedExactly() {
    // COMEN01C.cbl L131 — NO space before "...".
    assertThat(new ValidationException("Please enter a valid option number...").getMessage())
        .isEqualTo("Please enter a valid option number...");
    // COSGN00C.cbl L254 — single space before "...".
    assertThat(new ValidationException("Unable to verify the User ...").getMessage())
        .isEqualTo("Unable to verify the User ...");
  }

  // -----------------------------------------------------------------------------------------------
  // Constructor disambiguation (the crux) — (String, Throwable) vs (String, String).
  //
  // Overload resolution is proven by parameter type: the (fieldName, message) call below yields a
  // NON-NULL field name and a NULL cause, while the (message, cause) call further down yields a
  // NULL/empty field name and a NON-NULL cause. Because the same two-string-vs-string+throwable
  // shapes produce mutually exclusive outcomes — and because this file compiles at all — the
  // compiler must be binding each call to a distinct constructor by parameter type (String vs
  // Throwable), not collapsing them into one ambiguous signature.
  // -----------------------------------------------------------------------------------------------

  /**
   * Exercises the {@code (String fieldName, String message)} overload: the first argument is stored
   * as the field name, the second is forwarded to the superclass as the detail message, and no
   * cause is recorded. A non-null field name together with a null cause proves the call bound to
   * {@code (String, String)} rather than {@code (String, Throwable)}.
   */
  @Test
  void fieldNameMessageConstructor_resolvesAndStoresFieldName() {
    ValidationException ex = new ValidationException("PASSWD", "Wrong Password. Try again ...");

    assertThat(ex.getFieldName()).isEqualTo("PASSWD");
    assertThat(ex.getMessage()).isEqualTo("Wrong Password. Try again ...");
    // No cause on this overload — proves it bound to (String, String), NOT (String, Throwable).
    assertThat(ex.getCause()).isNull();
  }

  /**
   * Exercises the {@code (String message, Throwable cause)} overload: the message is forwarded to
   * the superclass, the cause is retained, and no field name is recorded. A non-null cause together
   * with a null/empty field name proves the call bound to {@code (String, Throwable)} rather than
   * {@code (String, String)}.
   */
  @Test
  void messageCauseConstructor_resolvesAndStoresCause() {
    Throwable cause = new NumberFormatException("bad");

    ValidationException ex = new ValidationException("Account ID must be numeric", cause);

    assertThat(ex.getMessage()).isEqualTo("Account ID must be numeric");
    assertThat(ex.getCause()).isSameAs(cause);
    // No field name on this overload — proves it bound to (String, Throwable), NOT (String,
    // String).
    assertThat(ex.getFieldName()).isNullOrEmpty();
  }

  // -----------------------------------------------------------------------------------------------
  // Assignability within the typed exception hierarchy.
  // -----------------------------------------------------------------------------------------------

  /**
   * {@link ValidationException} is a {@link CardDemoException} and therefore an unchecked {@link
   * RuntimeException}, so translated service and controller methods need no {@code throws} clause.
   */
  @Test
  void isCardDemoException_andRuntimeException() {
    assertThat(new ValidationException("x"))
        .isInstanceOf(CardDemoException.class)
        .isInstanceOf(RuntimeException.class);
  }
}

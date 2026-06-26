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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link CardDemoException}, the unchecked root of the
 * AWS CardDemo typed-exception hierarchy.
 *
 * <p>{@code CardDemoException} replaces the legacy z/OS COBOL error model — the per-file two-byte
 * {@code FILE STATUS} fields and the abend path that the original programs followed on an
 * unrecoverable I/O condition. In the daily-posting archetype {@code legacy/app/cbl/CBTRN02C.cbl}
 * that path performed {@code 9910-DISPLAY-IO-STATUS} (to surface the failing two-byte status) and
 * then {@code 9999-ABEND-PROGRAM}, which issued {@code CALL 'CEE3ABD'} to terminate the run unit
 * immediately. Because that transfer was immediate and unconditional, the Java root is modeled as
 * an <em>unchecked</em> {@link RuntimeException} (Agent Action Plan &sect;0.6.4 FILE&nbsp;STATUS
 * &rarr; exception mapping, &sect;0.6.6 abend handling).
 *
 * <p>These tests are deliberately framework-free: no Spring context, no database, no
 * Testcontainers, and no mocks. They construct the exception directly and assert constructor /
 * message / cause wiring, the unchecked nature, and that the four refined subtypes ({@code
 * IoStatusException}, {@code RecordNotFoundException}, {@code ValidationException}, {@code
 * AuthorizationException}) extend the root. Inheritance is asserted with {@link
 * Class#isAssignableFrom(Class)} so the suite stays decoupled from each subtype's constructor
 * signature.
 *
 * <p>The production exception types live in this same package ({@code com.aws.carddemo.exception}),
 * so they are referenced here without {@code import} statements; adding such imports would be
 * redundant and would break the project's zero-warning build (Agent Action Plan &sect;0.7.3).
 */
class CardDemoExceptionTest {

  /**
   * The single-argument {@code (String)} constructor must store the supplied detail message and
   * leave the cause unset, mirroring {@link RuntimeException#RuntimeException(String)}.
   */
  @Test
  void messageConstructor_setsMessage_andNullCause() {
    CardDemoException ex = new CardDemoException("boom");

    assertThat(ex.getMessage()).isEqualTo("boom");
    assertThat(ex.getCause()).isNull();
  }

  /**
   * The {@code (String, Throwable)} constructor must store both the detail message and the exact
   * cause instance (chained-exception wiring used when a JDBC / file-I/O failure is wrapped).
   */
  @Test
  void messageCauseConstructor_setsMessageAndCause() {
    Throwable cause = new IllegalStateException("root");

    CardDemoException ex = new CardDemoException("boom", cause);

    assertThat(ex.getMessage()).isEqualTo("boom");
    assertThat(ex.getCause()).isSameAs(cause);
  }

  /**
   * The single-argument {@code (Throwable)} constructor must store the exact cause instance.
   *
   * <p>{@link RuntimeException#RuntimeException(Throwable)} derives the detail message from {@code
   * cause.toString()}; that derived value is a JDK contract rather than a CardDemo business
   * message, so the primary assertion is on the cause. The message is only checked for the wrapped
   * cause's text to document the JDK-derived behavior without coupling to its exact format.
   */
  @Test
  void causeConstructor_setsCause() {
    Throwable cause = new IllegalStateException("root");

    CardDemoException ex = new CardDemoException(cause);

    assertThat(ex.getCause()).isSameAs(cause);
    assertThat(ex.getMessage()).contains("root");
  }

  /**
   * {@code CardDemoException} must be unchecked, i.e. a {@link RuntimeException}. This reproduces
   * the legacy immediate-transfer / abend semantics: COBOL never declared error conditions on its
   * call signatures, so the translated services and controllers stay free of pervasive {@code
   * throws} clauses.
   */
  @Test
  void isUncheckedRuntimeException() {
    assertThat(new CardDemoException("x")).isInstanceOf(RuntimeException.class);
    assertThat(RuntimeException.class.isAssignableFrom(CardDemoException.class)).isTrue();
  }

  /**
   * Each of the four refined error types must extend the {@code CardDemoException} root so the
   * application boundary can catch the whole hierarchy with a single handler. Inheritance is
   * asserted against the {@link Class} objects via {@link Class#isAssignableFrom(Class)}, which
   * avoids constructing the subtypes and therefore does not couple this base test to their
   * individual constructor shapes.
   */
  @Test
  void fourSubtypes_extendCardDemoException() {
    assertThat(CardDemoException.class).isAssignableFrom(IoStatusException.class);
    assertThat(CardDemoException.class).isAssignableFrom(RecordNotFoundException.class);
    assertThat(CardDemoException.class).isAssignableFrom(ValidationException.class);
    assertThat(CardDemoException.class).isAssignableFrom(AuthorizationException.class);
  }

  /**
   * End-to-end throw / catch wiring: a {@code CardDemoException} can be thrown, is caught as both
   * its own type and as a {@link RuntimeException}, and carries the supplied detail message intact.
   */
  @Test
  void canBeThrownAndCaught() {
    assertThatThrownBy(
            () -> {
              throw new CardDemoException("kaput");
            })
        .isInstanceOf(CardDemoException.class)
        .isInstanceOf(RuntimeException.class)
        .hasMessage("kaput");
  }
}

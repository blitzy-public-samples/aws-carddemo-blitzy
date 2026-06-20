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
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link AuthorizationException}, the migrated
 * equivalent of the legacy COBOL role-based authorization denial that gates admin-only functions.
 *
 * <p>Behavioral specification (read-only under {@code legacy/app/cbl}): {@code COMEN01C.cbl} lines
 * 136-143. When a standard user ({@code CDEMO-USRTYP-USER}) selects a menu option flagged
 * admin-only ({@code CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'}), the program sets the error flag and
 * executes {@code MOVE 'No access - Admin Only option... ' TO WS-MESSAGE} (line 140) — redisplaying
 * the menu rather than dispatching the option. The migrated {@code GlobalExceptionHandler} maps
 * this exception to <strong>HTTP 403 (Forbidden)</strong> (Agent Action Plan &sect;0.6.5).
 *
 * <p>These tests are deliberately framework-free — no Spring context, no database, no
 * Testcontainers, and no mocks. They instantiate the exception directly and assert against the
 * production constant exactly as declared, keeping the suite fast and deterministic (AAP
 * &sect;0.6.7).
 *
 * <p>The headline invariants pinned here are (1) the no-argument constructor wires the externally
 * observable admin-only message {@link AuthorizationException#ADMIN_ONLY_MESSAGE}, byte-faithful to
 * the legacy literal, and (2) this <em>business</em> role-gating exception is decoupled from Spring
 * Security's framework {@code org.springframework.security.access.AccessDeniedException} (wired
 * separately in {@code com.aws.carddemo.config.SecurityConfig}).
 */
class AuthorizationExceptionTest {

  /**
   * The no-argument constructor must wire the detail message to the {@link
   * AuthorizationException#ADMIN_ONLY_MESSAGE} constant. This is the robust primary anchor: it
   * binds to the production constant rather than hardcoding the literal, so the test tracks the
   * production value if the constant is ever re-pinned.
   */
  @Test
  void noArgConstructor_usesAdminOnlyMessageConstant() {
    AuthorizationException ex = new AuthorizationException();

    assertThat(ex.getMessage()).isEqualTo(AuthorizationException.ADMIN_ONLY_MESSAGE);
  }

  /**
   * The {@link AuthorizationException#ADMIN_ONLY_MESSAGE} constant must carry the byte-faithful
   * legacy text from {@code COMEN01C.cbl} line 140. The COBOL literal {@code 'No access - Admin
   * Only option... '} is space-padded to the {@code WS-MESSAGE PIC X(80)} display width; the
   * production constant retains only the meaningful text {@code "No access - Admin Only option..."}
   * (three trailing dots, no trailing pad space). The {@code startsWith}/{@code contains}
   * assertions stay valid even if the production constant were ever to retain the trailing space.
   */
  @Test
  void adminOnlyMessageConstant_matchesLegacyText() {
    assertThat(AuthorizationException.ADMIN_ONLY_MESSAGE)
        .startsWith("No access - Admin Only option...");
    assertThat(AuthorizationException.ADMIN_ONLY_MESSAGE).contains("Admin Only");
    // Exact-equality check bound to the production constant's declared value: no trailing space,
    // three trailing dots. See AuthorizationException#ADMIN_ONLY_MESSAGE and COMEN01C.cbl line 140.
    assertThat(AuthorizationException.ADMIN_ONLY_MESSAGE)
        .isEqualTo("No access - Admin Only option...");
  }

  /** The single-argument constructor must set the supplied detail message verbatim. */
  @Test
  void messageConstructor_setsCustomMessage() {
    assertThat(new AuthorizationException("Forbidden function").getMessage())
        .isEqualTo("Forbidden function");
  }

  /**
   * The message-and-cause constructor must set both the detail message and the underlying cause
   * (the same instance, retrievable via {@link Throwable#getCause()}).
   */
  @Test
  void messageCauseConstructor_setsMessageAndCause() {
    Throwable cause = new IllegalStateException("denied");

    AuthorizationException ex = new AuthorizationException("Forbidden function", cause);

    assertThat(ex.getMessage()).isEqualTo("Forbidden function");
    assertThat(ex.getCause()).isSameAs(cause);
  }

  /**
   * The exception must sit within the CardDemo typed-exception hierarchy and remain unchecked: it
   * is a {@link CardDemoException} and therefore a {@link RuntimeException}, so translated service
   * and controller signatures stay free of pervasive {@code throws} clauses.
   */
  @Test
  void isCardDemoException_andRuntimeException() {
    assertThat(new AuthorizationException())
        .isInstanceOf(CardDemoException.class)
        .isInstanceOf(RuntimeException.class);
  }

  /**
   * The business role-gating exception must be decoupled from Spring Security's framework {@code
   * AccessDeniedException}. Per the production design, {@link AuthorizationException} mirrors the
   * COBOL {@code CDEMO-USRTYP} check and is intentionally independent of Spring Security; the
   * framework wiring lives in {@code com.aws.carddemo.config.SecurityConfig}, and {@code
   * GlobalExceptionHandler} maps this exception to HTTP 403.
   */
  @Test
  void isDecoupledFromSpringSecurityAccessDeniedException() {
    AuthorizationException ex = new AuthorizationException();

    // Direct proof: this is NOT Spring Security's framework AccessDeniedException. The type is
    // referenced by fully-qualified name so this package stays free of any Spring Security import,
    // exactly as the production class does.
    assertThat(ex).isNotInstanceOf(org.springframework.security.access.AccessDeniedException.class);

    // The actual supertype chain is AuthorizationException -> CardDemoException -> RuntimeException
    // -> Exception; none of these is the Spring Security framework type.
    assertThat(AuthorizationException.class.getSuperclass()).isEqualTo(CardDemoException.class);
    assertThat(CardDemoException.class.getSuperclass()).isEqualTo(RuntimeException.class);
    assertThat(RuntimeException.class.getSuperclass()).isEqualTo(Exception.class);
  }
}

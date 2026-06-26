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
 * Signals a role-based authorization denial — the migrated equivalent of the COBOL {@code
 * CDEMO-USRTYP} (user-type) gate that prevents a standard user from invoking an admin-only
 * function.
 *
 * <p>In the legacy z/OS application the pseudo-conversational COMMAREA ({@code
 * app/cpy/COCOM01Y.cpy} lines 19-44) carries the signed-on user's type, where {@code
 * CDEMO-USRTYP-ADMIN} holds {@code 'A'} and {@code CDEMO-USRTYP-USER} holds {@code 'U'}. The
 * main-menu program {@code legacy/app/cbl/COMEN01C.cbl} (lines 136-143) enforces the gate: when a
 * standard user selects a menu option whose option-type is {@code 'A'} (admin-only), it sets the
 * error flag and moves the denial message {@code 'No access - Admin Only option... '} into the
 * screen message field instead of transferring control to the selected program. The admin-only
 * screens — user management {@code CU00}-{@code CU03} and the admin menu {@code CA00} — are
 * therefore reachable only by an administrator. This exception reproduces that branch so the
 * translated services and controllers reject an unauthorized navigation attempt exactly where the
 * COBOL would have.
 *
 * <p><strong>Decoupled from Spring Security.</strong> This is the <em>business</em> role-gating
 * exception that mirrors the COBOL {@code CDEMO-USRTYP} check; it is intentionally independent of
 * Spring Security's framework {@code org.springframework.security.access.AccessDeniedException}.
 * The Spring Security wiring (authentication, the security filter chain, and any framework-level
 * access decisions) lives in {@code com.aws.carddemo.config.SecurityConfig}. Keeping this type free
 * of {@code config} and Spring Security dependencies preserves a clean, framework-agnostic
 * exception package and keeps the COBOL-parity denial distinct from infrastructure authentication
 * failures.
 *
 * <p>At the application boundary the denial is translated rather than propagated: {@code
 * GlobalExceptionHandler} maps this exception to <strong>HTTP 403 (Forbidden)</strong> and surfaces
 * the {@link #getMessage() detail message} — for the no-argument case the externally observable
 * legacy text {@link #ADMIN_ONLY_MESSAGE} — to the caller, mirroring the redisplayed screen message
 * the COBOL program produced.
 *
 * <p>Like its base type the exception is <em>unchecked</em>: it extends {@link CardDemoException}
 * (which in turn extends {@link RuntimeException}), so service and controller signatures stay free
 * of pervasive {@code throws} clauses, matching the COBOL model in which an authorization branch
 * simply redisplayed the screen rather than declaring a checked error condition.
 *
 * @see CardDemoException
 */
public class AuthorizationException extends CardDemoException {

  /**
   * Serialization version identifier.
   *
   * <p>{@link CardDemoException} is serializable (via {@link RuntimeException} and {@link
   * Throwable}); declaring an explicit {@code serialVersionUID} pins the serialized form and
   * suppresses the {@code serial} compiler warning that the project's zero-warning build ({@code
   * -Werror -Xlint:all}) would otherwise promote to a build-breaking error.
   */
  private static final long serialVersionUID = 1L;

  /**
   * The admin-only access-denied message, preserved verbatim from the legacy COBOL for behavioral
   * parity.
   *
   * <p>In {@code legacy/app/cbl/COMEN01C.cbl} (line 140), when a standard user ({@code
   * CDEMO-USRTYP-USER}) selects a menu option flagged admin-only ({@code
   * CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'}), the program executes {@code MOVE 'No access - Admin
   * Only option... ' TO WS-MESSAGE} and redisplays the menu rather than dispatching the option.
   *
   * <p>The COBOL literal is space-padded to the {@code WS-MESSAGE PIC X(80)} display width by the
   * renderer; the meaningful text reproduced here is {@code No access - Admin Only option...} (note
   * the three trailing dots and no trailing pad space). Because this is externally observable
   * screen text that {@code GlobalExceptionHandler} returns with the HTTP 403 response, it is held
   * constant to the character.
   */
  public static final String ADMIN_ONLY_MESSAGE = "No access - Admin Only option...";

  /**
   * Creates an authorization denial carrying the exact legacy admin-only message.
   *
   * <p>This is the common case: a standard user attempted an admin-only function, reproducing the
   * {@code COMEN01C} gate. The detail message is set to {@link #ADMIN_ONLY_MESSAGE}.
   */
  public AuthorizationException() {
    super(ADMIN_ONLY_MESSAGE);
  }

  /**
   * Creates an authorization denial with a caller-supplied detail message.
   *
   * <p>Provided for authorization scenarios that surface a message other than the standard
   * admin-only denial.
   *
   * @param message the detail message describing the authorization failure; later retrievable via
   *     {@link #getMessage()}
   */
  public AuthorizationException(String message) {
    super(message);
  }

  /**
   * Creates an authorization denial with a caller-supplied detail message and underlying cause.
   *
   * @param message the detail message describing the authorization failure; later retrievable via
   *     {@link #getMessage()}
   * @param cause the underlying cause; later retrievable via {@link #getCause()}. A {@code null}
   *     value indicates that the cause is nonexistent or unknown.
   */
  public AuthorizationException(String message, Throwable cause) {
    super(message, cause);
  }
}

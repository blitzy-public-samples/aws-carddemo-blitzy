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
 * Field-validation / invalid-input error raised by the migrated CardDemo online layer.
 *
 * <p>This is the highest-frequency exception across the translated online (CICS) programs. It
 * models the pervasive COBOL <em>soft-error</em> branch: when a program detected an empty, badly
 * formatted, or out-of-range screen field, it set the error flag, moved a user-facing literal into
 * the screen message field, and re-sent the same map so the user could correct the input. Crucially
 * this is <strong>not</strong> an abend — control was never transferred to the {@code
 * 9999-ABEND-PROGRAM} path; the conversation simply re-rendered the screen with a message.
 * Representative evidence from the source programs that this type translates:
 *
 * <ul>
 *   <li>{@code legacy/app/cbl/COSGN00C.cbl} — the sign-on {@code PROCESS-ENTER-KEY} edits move
 *       {@code 'Please enter User ID ...'} and {@code 'Please enter Password ...'} when those
 *       fields are {@code SPACES}/{@code LOW-VALUES}, and the verification branch moves {@code
 *       'Wrong Password. Try again ...'}, {@code 'User not found. Try again ...'}, or {@code
 *       'Unable to verify the User ...'} — each followed by {@code PERFORM SEND-SIGNON-SCREEN}.
 *   <li>{@code legacy/app/cbl/COMEN01C.cbl} — the main-menu option edit moves {@code 'Please enter
 *       a valid option number...'} when the entered option is non-numeric, zero, or beyond the menu
 *       option count, then {@code PERFORM SEND-MENU-SCREEN}.
 *   <li>{@code legacy/app/cbl/COACTUPC.cbl} — the account-update screen performs extensive
 *       per-field edits (date, SSN, phone, and numeric-format checks) that each yield a
 *       field-specific message and re-display the screen.
 * </ul>
 *
 * <p><strong>Message parity is the contract.</strong> The detail message carried by this exception
 * <em>is</em> the authoritative user-facing string. Callers must pass the exact legacy literal —
 * including its punctuation, ellipses, and any spacing the original field rendering preserved —
 * because {@code GlobalExceptionHandler} surfaces that string verbatim to reproduce the legacy
 * screen-message behavior. For an invalid AID / PF-key press the conventional message is {@code
 * com.aws.carddemo.util.Messages.MSG_INVALID_KEY} ({@code "Invalid key pressed. Please see below
 * ..."}); that composition happens in {@code GlobalExceptionHandler}, though this exception may
 * also be constructed directly with it. No secret is ever placed in a message: the legacy text
 * {@code 'Wrong Password. Try again ...'} is a generic prompt and contains no credential value.
 *
 * <p><strong>HTTP / screen mapping.</strong> This exception is intentionally free of any Spring or
 * HTTP dependency. The translation to an online response — HTTP&nbsp;400 (Bad Request), or a
 * re-render of the originating screen with the message in the error area — is performed centrally
 * by {@code GlobalExceptionHandler}. That keeps this type a thin, transport-agnostic carrier of the
 * legacy message and optional offending-field name.
 *
 * <p><strong>Recoverability.</strong> Unlike an I/O abend, a validation failure is recoverable and
 * user-correctable: the user adjusts the offending input and resubmits. This distinguishes it from
 * its sibling types in the hierarchy:
 *
 * <ul>
 *   <li>{@code RecordNotFoundException} — FILE STATUS {@code '23'} record-not-found, mapped to HTTP
 *       404.
 *   <li>{@code AuthorizationException} — {@code CDEMO-USRTYP} role-gating violation, mapped to HTTP
 *       403.
 *   <li>{@code ValidationException} (this type) — invalid input, mapped to HTTP 400.
 * </ul>
 *
 * <p>The exception is <em>unchecked</em> by inheritance from {@link CardDemoException} (ultimately
 * {@link RuntimeException}); translated service and controller methods therefore need no {@code
 * throws} clauses, mirroring the way COBOL never declared error conditions on a call signature.
 *
 * @see CardDemoException
 */
public class ValidationException extends CardDemoException {

  /**
   * Serialization version identifier.
   *
   * <p>{@link CardDemoException} is serializable (it ultimately extends {@link Throwable}, which
   * implements {@link java.io.Serializable}); declaring an explicit {@code serialVersionUID} pins
   * the serialized form and suppresses the {@code serial} compiler warning that the project's
   * zero-warning build ({@code -Werror -Xlint:all}) would otherwise promote to a build-breaking
   * error.
   */
  private static final long serialVersionUID = 1L;

  /**
   * Optional name of the offending screen field, for example {@code "USERID"}, {@code "PASSWD"},
   * {@code "OPTION"}, or {@code "ACCTID"}.
   *
   * <p>This value is {@code null} when the validation failure is form-level rather than tied to a
   * single field (for instance the generic {@code 'Unable to verify the User ...'} branch, which
   * relates to the credential pair as a whole rather than one input). Consumers — chiefly {@code
   * GlobalExceptionHandler} — must treat {@link #getFieldName()} as nullable and guard accordingly,
   * typically using it only to drive cursor placement / field highlighting when present.
   */
  private final String fieldName;

  /**
   * Creates a form-level validation error carrying the exact user-facing message.
   *
   * <p>Use this constructor when the failure is not attributable to one specific field, or when
   * field-level cursor positioning is unnecessary. The supplied {@code message} is the
   * authoritative user-facing string and must be the exact legacy literal (see the class
   * documentation). The {@linkplain #getFieldName() field name} is left {@code null}.
   *
   * @param message the exact user-facing validation text (preserved byte-faithfully from the COBOL
   *     literal); later retrievable via {@link #getMessage()}
   */
  public ValidationException(String message) {
    super(message);
    this.fieldName = null;
  }

  /**
   * Creates a form-level validation error carrying the exact user-facing message and an underlying
   * cause.
   *
   * <p>This overload is for the (rare) case where a validation failure is triggered while handling
   * another throwable — for example a parse or conversion failure that is reported to the user as
   * invalid input. The {@linkplain #getFieldName() field name} is left {@code null}.
   *
   * <p><strong>Disambiguation:</strong> this {@code (String, Throwable)} signature is distinct from
   * the {@link #ValidationException(String, String) (String fieldName, String message)} overload
   * because the second parameter is a {@link Throwable}, not a {@link String}. The compiler selects
   * this constructor when the second argument is a cause; it selects the other when the second
   * argument is a message string. (A bare untyped {@code null} as the second argument is ambiguous
   * across the two overloads — callers must cast, e.g. {@code (Throwable) null}, in that edge
   * case.)
   *
   * @param message the exact user-facing validation text; later retrievable via {@link
   *     #getMessage()}
   * @param cause the underlying cause (for example a {@link NumberFormatException} from a numeric
   *     edit); later retrievable via {@link #getCause()}. A {@code null} value indicates that the
   *     cause is nonexistent or unknown.
   */
  public ValidationException(String message, Throwable cause) {
    super(message, cause);
    this.fieldName = null;
  }

  /**
   * Creates a field-level validation error that records the offending field name alongside the
   * exact user-facing message.
   *
   * <p>Use this constructor when the failing screen field is known, so the online layer can
   * position the cursor on (or highlight) that field — mirroring the COBOL {@code MOVE -1 TO
   * <field>L} cursor-placement convention that accompanied each screen message. The {@code message}
   * (not the field name) is forwarded to the superclass as the detail message.
   *
   * <p><strong>Disambiguation:</strong> this {@code (String fieldName, String message)} signature
   * is distinct from the {@link #ValidationException(String, Throwable) (String message, Throwable
   * cause)} overload because the second parameter is a {@link String}, not a {@link Throwable}. The
   * argument <em>order</em> is {@code (fieldName, message)} — the field name comes first, the
   * user-facing message second.
   *
   * @param fieldName the name of the offending screen field (for example {@code "PASSWD"} or {@code
   *     "OPTION"}); may be {@code null} or empty when not applicable, in which case the error is
   *     effectively form-level
   * @param message the exact user-facing validation text (preserved byte-faithfully from the COBOL
   *     literal); later retrievable via {@link #getMessage()}
   */
  public ValidationException(String fieldName, String message) {
    super(message);
    this.fieldName = fieldName;
  }

  /**
   * Returns the name of the offending screen field, or {@code null} when the validation failure is
   * form-level.
   *
   * <p>Callers must treat the result as nullable: it is populated only by the {@link
   * #ValidationException(String, String) (fieldName, message)} constructor and is {@code null} for
   * the message-only and {@code (message, cause)} constructors. It is typically consumed by {@code
   * GlobalExceptionHandler} to drive cursor placement or field highlighting on the re-rendered
   * screen.
   *
   * @return the offending field name (for example {@code "USERID"}, {@code "PASSWD"}, {@code
   *     "OPTION"}, {@code "ACCTID"}), or {@code null}/empty when not associated with a single field
   */
  public String getFieldName() {
    return fieldName;
  }
}

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.rule;

import java.util.Objects;

/**
 * Immutable outcome of a field-validation rule; the Java abstraction of the COBOL
 * {@code INPUT-ERROR} flag + {@code WS-RETURN-MSG} message (see
 * {@code legacy/cbl/COACTUPC.cbl}). Detailed rationale lives in the decision log.
 *
 * <p>Throughout the legacy online-edit programs a field check sets the error flag and,
 * <em>only if no message has been latched yet</em>, builds the screen message:</p>
 * <pre>
 *     SET INPUT-ERROR TO TRUE
 *     IF WS-RETURN-MSG-OFF
 *        STRING ... DELIMITED BY SIZE INTO WS-RETURN-MSG
 *     END-IF
 * </pre>
 * <p>({@code INPUT-ERROR} is the 88-level {@code VALUE '1'} at
 * {@code legacy/cbl/COACTUPC.cbl:L173}; {@code WS-RETURN-MSG} is the
 * {@code PIC X(75)} screen message at {@code L479}; {@code WS-RETURN-MSG-OFF} is its
 * {@code VALUE SPACES} guard at {@code L480}.) This class captures the {@code (flag, message)}
 * pair as a single, self-consistent value. The <em>first-message-wins</em> latching itself
 * is the responsibility of the calling service (which inspects a sequence of
 * {@code ValidationResult}s and keeps the first invalid one); this type only guarantees
 * that an invalid result always carries a non-{@code null}, non-blank-by-contract message,
 * and a valid result carries the empty string (never {@code null}).</p>
 *
 * <p>Every rule component in this package (for example {@code NumericRequiredRule},
 * {@code AccountIdRule}, {@code UsSsnRule}) returns an instance of this type, and the
 * parent-folder services (for example {@code AccountService}, {@code CardService},
 * {@code ReportService}) consume it. It is therefore the single shared return convention
 * of the {@code service.rule} package and the foundational leaf on which the rules depend;
 * it in turn depends on nothing beyond the JDK.</p>
 *
 * <p>The type is deliberately a plain {@code final} class rather than a {@code record}: the
 * desired API exposes a static factory {@link #valid()} <em>and</em> a boolean instance
 * accessor {@link #isValid()}. A {@code record ValidationResult(boolean valid, String message)}
 * would auto-generate an instance accessor {@code valid()} that would clash with the static
 * {@code valid()} factory, which is illegal; a hand-written immutable class avoids the clash
 * and yields the clean, symmetric {@link #valid()} / {@link #invalid(String)} factory pair.</p>
 *
 * <p>Instances are immutable (both fields are {@code final}, there are no setters) and are
 * therefore safe to share across threads; the valid outcome is a cached singleton. A rule
 * message describes the offending field <em>by label only</em> (for example
 * {@code "SSN: First 3 chars must be all numeric."}) and never carries a raw sensitive value
 * such as an SSN, CVV, or password, so {@link #toString()} is safe to log.</p>
 */
public final class ValidationResult {

    /**
     * The single cached valid outcome. A valid result never varies, so one shared,
     * immutable instance is reused for every {@link #valid()} call. Its message is the
     * empty string (never {@code null}), mirroring the COBOL {@code WS-RETURN-MSG-OFF}
     * ({@code VALUE SPACES}) state.
     */
    private static final ValidationResult VALID = new ValidationResult(true, "");

    /** Whether the validated field passed the rule ({@code true}) or failed it ({@code false}). */
    private final boolean valid;

    /**
     * The screen/error message for a failed rule (the COBOL {@code WS-RETURN-MSG}); the empty
     * string for a passed rule. Never {@code null}.
     */
    private final String message;

    /**
     * Sole constructor, kept {@code private} so that instances are obtained only through the
     * {@link #valid()} and {@link #invalid(String)} factories, which enforce the field/message
     * invariants.
     *
     * @param valid   {@code true} for a passing result, {@code false} for a failing one
     * @param message the non-{@code null} outcome message (empty string when {@code valid})
     */
    private ValidationResult(boolean valid, String message) {
        this.valid = valid;
        this.message = message;
    }

    /**
     * Returns the shared, cached valid outcome. The result reports {@link #isValid()} as
     * {@code true} and {@link #message()} as the empty string.
     *
     * @return the cached valid {@code ValidationResult} singleton (the same instance every call)
     */
    public static ValidationResult valid() {
        return VALID;
    }

    /**
     * Creates a failing outcome carrying the supplied screen message (the Java equivalent of
     * setting {@code INPUT-ERROR} and building {@code WS-RETURN-MSG}). An invalid result must
     * always carry a message, so a {@code null} message is rejected.
     *
     * @param message the human-readable error message describing the failed field by label;
     *                must not be {@code null}
     * @return a new invalid {@code ValidationResult} carrying {@code message}
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public static ValidationResult invalid(String message) {
        return new ValidationResult(false, Objects.requireNonNull(message, "message"));
    }

    /**
     * Reports whether the validated field passed the rule.
     *
     * @return {@code true} if the field passed, {@code false} otherwise
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * Reports whether the validated field failed the rule; the logical negation of
     * {@link #isValid()}. Provided for readable call sites that branch on failure, mirroring
     * the COBOL {@code IF INPUT-ERROR} test.
     *
     * @return {@code true} if the field failed, {@code false} otherwise
     */
    public boolean isInvalid() {
        return !valid;
    }

    /**
     * Returns the outcome message: the error text for a failed rule (the COBOL
     * {@code WS-RETURN-MSG}) or the empty string for a passed rule. Never {@code null}.
     *
     * @return the non-{@code null} outcome message
     */
    public String message() {
        return message;
    }

    /**
     * Value-based equality: two results are equal when both their validity flag and their
     * message are equal.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code ValidationResult} with the same flag and message
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ValidationResult)) {
            return false;
        }
        ValidationResult that = (ValidationResult) o;
        return this.valid == that.valid && Objects.equals(this.message, that.message);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the value-based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(valid, message);
    }

    /**
     * Returns a diagnostic representation of this outcome. Because rule messages describe the
     * offending field by label only and never carry a raw sensitive value, this string is safe
     * to log.
     *
     * @return a string of the form {@code ValidationResult{valid=..., message='...'}}
     */
    @Override
    public String toString() {
        return "ValidationResult{valid=" + valid + ", message='" + message + "'}";
    }
}

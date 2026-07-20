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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Validates a signed monetary amount field, the Java migration of the COBOL edit paragraph
 * {@code 1250-EDIT-SIGNED-9V2} in {@code legacy/cbl/COACTUPC.cbl}
 * (source {@code app/cbl/COACTUPC.cbl}, lines {@code L2180}&ndash;{@code L2223}). The legacy
 * paragraph edits the account-maintenance monetary fields whose picture is {@code PIC S9(10)V99}
 * &mdash; the credit limit, cash credit limit, current balance, current-cycle credit, and
 * current-cycle debit. Detailed rationale lives in the decision log.
 *
 * <p><strong>Verbatim COBOL behavior and evaluation order</strong> (preserved exactly):</p>
 * <pre>
 *     1250-EDIT-SIGNED-9V2.
 *         SET FLG-SIGNED-NUMBER-NOT-OK TO TRUE
 *         IF WS-EDIT-SIGNED-NUMBER-9V2-X EQUAL LOW-VALUES OR SPACES
 *            ... STRING TRIM(name) ' must be supplied.' ...   GO TO EXIT   (1)
 *         END-IF
 *         IF FUNCTION TEST-NUMVAL-C(WS-EDIT-SIGNED-NUMBER-9V2-X) = 0
 *            CONTINUE                                                       (valid)
 *         ELSE
 *            ... STRING TRIM(name) ' is not valid' ...        GO TO EXIT   (2)
 *         END-IF
 *         SET FLG-SIGNED-NUMBER-ISVALID TO TRUE.                           (3)
 * </pre>
 * <ol>
 *   <li><strong>Not supplied</strong> &mdash; a {@code null} value (COBOL {@code LOW-VALUES}) or
 *       an all-whitespace value (COBOL {@code SPACES}) yields the message
 *       {@code "<field> must be supplied."} <em>with</em> a trailing period
 *       ({@code legacy/cbl/COACTUPC.cbl:L2191}).</li>
 *   <li><strong>Malformed</strong> &mdash; a value that {@code FUNCTION TEST-NUMVAL-C} would reject
 *       (i.e. it returns a non-zero offending-character position) yields the message
 *       {@code "<field> is not valid"} <em>without</em> a trailing period. This asymmetry (no
 *       period, unlike message&nbsp;1) is intentional in the legacy source
 *       ({@code legacy/cbl/COACTUPC.cbl:L2209}) and is reproduced byte-for-byte.</li>
 *   <li><strong>Valid</strong> otherwise.</li>
 * </ol>
 *
 * <p><strong>No zero rejection.</strong> Unlike {@code NumericRequiredRule} (COBOL
 * {@code 1245-EDIT-NUM-REQD}, which rejects a zero entry via {@code FUNCTION NUMVAL(...) = 0}), a
 * signed amount of zero (for example {@code "0.00"}) is a <em>valid</em> value here: paragraph
 * {@code 1250-EDIT-SIGNED-9V2} performs no zero check. This distinction is preserved deliberately.</p>
 *
 * <p><strong>{@code FUNCTION TEST-NUMVAL-C} reproduction.</strong> {@code TEST-NUMVAL-C} returns
 * {@code 0} when its argument conforms to the character grammar that {@code FUNCTION NUMVAL-C}
 * accepts, and a non-zero position otherwise. The accepted grammar reproduced here (faithful to the
 * IBM COBOL {@code NUMVAL-C} definition, for a currency/amount string) is:</p>
 * <ul>
 *   <li>optional leading and trailing spaces;</li>
 *   <li>at most one sign, expressed as <em>either</em> a leading {@code +}/{@code -}, <em>or</em> a
 *       trailing {@code +}/{@code -}, <em>or</em> a trailing {@code CR}/{@code DB} (the leading and
 *       trailing forms are mutually exclusive &mdash; a value may not carry both);</li>
 *   <li>an optional leading currency symbol ({@code $});</li>
 *   <li>one or more digits, optionally grouped with commas ({@code ,}) as thousands separators, or a
 *       leading-decimal form with no integer digits (for example {@code .45});</li>
 *   <li>at most one decimal point ({@code .}) with zero or more fractional digits.</li>
 * </ul>
 * <p>{@code CR} and {@code DB} denote a negative (credit/debit) amount per {@code NUMVAL-C}, as does a
 * trailing {@code -}; they must be upper-case, matching the mainframe {@code NUMVAL-C} contract. The
 * grammar is captured once in a precompiled {@link Pattern} ({@link #ACCEPTED}) so the rule stays
 * allocation-light and thread-safe.</p>
 *
 * <p><strong>Decimal discipline.</strong> The amount is modeled with {@link BigDecimal} at scale 2;
 * {@code double}/{@code float} are never used. COBOL {@code MOVE} of the edited value into
 * {@code PIC S9(10)V99} <em>truncates</em> any fractional digits beyond the hundredths place (it does
 * not round), so {@link #parseAmount(String)} normalizes with {@link RoundingMode#DOWN} to reproduce
 * the stored value exactly &mdash; {@code RoundingMode.DOWN} rounds toward zero, matching COBOL
 * truncation for both positive and negative amounts (for example {@code "1.239" -> 1.23} and
 * {@code "-1.239" -> -1.23}). For the ordinary two-decimal input this normalization is a no-op.</p>
 *
 * <p><strong>Statelessness.</strong> The rule is stateless aside from the compiled {@link #ACCEPTED}
 * pattern (an immutable, thread-safe constant), so the single Spring singleton is safe to share
 * across threads. It never throws to signal a validation failure &mdash; the outcome is conveyed
 * solely through the returned {@link ValidationResult}; latching the first failing message across a
 * sequence of fields is the calling service's responsibility. It depends only on the same-package
 * {@link ValidationRule}/{@link ValidationResult} contract and the JDK.</p>
 *
 * @see ValidationRule
 * @see ValidationResult
 */
@Component
public final class SignedAmountRule implements ValidationRule {

    /**
     * The numeric core of the {@code NUMVAL-C} grammar: one or more digits with optional thousands
     * grouping and an optional decimal part ({@code 1,234} / {@code 1234} / {@code 12.5} /
     * {@code 12.}), or a leading-decimal form ({@code .45}). Either alternative always contains at
     * least one digit, so an empty or sign/currency-only string is rejected. Grouping is validated
     * as strict thousands separators ({@code \d{1,3}(,\d{3})*}) so a malformed group such as
     * {@code "12,34"} is rejected, mirroring a well-formed amount entry.
     */
    private static final String NUMBER =
            "(?:(?:\\d{1,3}(?:,\\d{3})*|\\d+)(?:\\.\\d*)?|\\.\\d+)";

    /**
     * Precompiled acceptance pattern reproducing {@code FUNCTION TEST-NUMVAL-C == 0}. The input is
     * {@link String#strip() stripped} before matching, so the outer optional spaces of the grammar
     * are already removed; the internal {@code \s*} occurrences still permit spaces between the sign,
     * the currency symbol, and the number. The top-level alternation encodes the mutually exclusive
     * sign placement:
     * <ul>
     *   <li>option A &mdash; an optional leading {@code +}/{@code -}, an optional currency symbol, the
     *       {@link #NUMBER}, and <em>no</em> trailing sign; and</li>
     *   <li>option B &mdash; an optional currency symbol, the {@link #NUMBER}, and a required trailing
     *       sign ({@code +}, {@code -}, {@code CR}, or {@code DB}) with <em>no</em> leading sign.</li>
     * </ul>
     * A value carrying both a leading and a trailing sign matches neither alternative and is
     * therefore rejected, as {@code NUMVAL-C} requires.
     */
    private static final Pattern ACCEPTED = Pattern.compile(
            "^(?:"
          + "[+-]?\\s*\\$?\\s*" + NUMBER + "\\s*"
          + "|"
          + "\\$?\\s*" + NUMBER + "\\s*(?:[+-]|CR|DB)"
          + ")$");

    /**
     * Validates a signed monetary amount, reproducing COBOL paragraph {@code 1250-EDIT-SIGNED-9V2}.
     *
     * <p>The checks run in the legacy evaluation order: the not-supplied guard first, then the
     * {@code TEST-NUMVAL-C} numeric-format check. There is no zero check, so {@code "0.00"} is
     * valid.</p>
     *
     * @param fieldName the human-readable field label substituted into the outcome message (COBOL
     *                  {@code WS-EDIT-VARIABLE-NAME}); normalized with {@link ValidationRule#label(String)}
     * @param value     the amount as entered; {@code null} models COBOL {@code LOW-VALUES} and a blank
     *                  value models {@code SPACES}
     * @return {@link ValidationResult#valid()} when the amount is supplied and well-formed; otherwise
     *         {@link ValidationResult#invalid(String)} carrying the exact COBOL screen message
     *         ({@code "<field> must be supplied."} when blank, or {@code "<field> is not valid"} when
     *         malformed &mdash; note the deliberate absence of a trailing period on the latter)
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        String field = ValidationRule.label(fieldName);
        // (1) Not supplied: COBOL LOW-VALUES or SPACES -> "<field> must be supplied." (with period).
        if (ValidationRule.isBlank(value)) {
            return ValidationResult.invalid(field + " must be supplied.");
        }
        // (2) FUNCTION TEST-NUMVAL-C != 0 -> not a valid amount -> "<field> is not valid" (NO period).
        if (parseAmount(value) == null) {
            return ValidationResult.invalid(field + " is not valid");
        }
        // (3) All edits cleared: FLG-SIGNED-NUMBER-ISVALID.
        return ValidationResult.valid();
    }

    /**
     * Parses a candidate signed amount into a {@link BigDecimal} at scale 2, or returns {@code null}
     * when the value is not acceptable to the {@code NUMVAL-C} grammar (modeling
     * {@code FUNCTION TEST-NUMVAL-C != 0}). This is the extraction point a consuming service uses to
     * obtain the numeric value once {@link #validate(String, String)} has reported the field valid;
     * because both methods share {@link #ACCEPTED}, a non-{@code null} return here is exactly a valid
     * outcome there.
     *
     * <p>Normalization steps: leading/trailing whitespace is stripped; the value is matched against
     * {@link #ACCEPTED}; the sign is derived (a leading {@code -}, a trailing {@code -}, or a trailing
     * {@code CR}/{@code DB} denotes a negative amount); every non-digit, non-point character (spaces,
     * the currency symbol, thousands commas, sign glyphs, and the {@code CR}/{@code DB} letters) is
     * removed; a dangling trailing decimal point is dropped and a leading decimal point is padded with
     * a {@code 0}; and the result is converted with {@link BigDecimal#BigDecimal(String)} and
     * normalized to scale 2 with {@link RoundingMode#DOWN} (truncation toward zero, matching a COBOL
     * {@code MOVE} into {@code PIC S9(10)V99}). The {@link BigDecimal} construction is guarded so that
     * any residual {@link NumberFormatException} degrades to {@code null} rather than propagating.</p>
     *
     * @param value the amount as entered; may be {@code null} or blank
     * @return the parsed amount as a {@link BigDecimal} whose {@link BigDecimal#scale() scale} is
     *         always {@code 2}, or {@code null} when {@code value} is blank or malformed
     */
    public BigDecimal parseAmount(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (!ACCEPTED.matcher(stripped).matches()) {
            return null;
        }

        // Derive the sign: CR/DB and a trailing (or leading) '-' denote a negative amount; the
        // grammar guarantees at most one sign, so these tests are mutually exclusive.
        boolean negative = stripped.charAt(0) == '-'
                || stripped.charAt(stripped.length() - 1) == '-'
                || stripped.endsWith("CR")
                || stripped.endsWith("DB");

        // Strip everything except digits and the decimal point (removes spaces, '$', ',', signs,
        // and the CR/DB letters), then repair the boundary decimal-point forms the grammar allows.
        String digits = stripped.replaceAll("[^0-9.]", "");
        if (digits.endsWith(".")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        if (digits.startsWith(".")) {
            digits = "0" + digits;
        }
        if (digits.isEmpty()) {
            return null;
        }
        if (negative) {
            digits = "-" + digits;
        }

        try {
            // COBOL MOVE into PIC S9(10)V99 truncates (does not round); RoundingMode.DOWN reproduces
            // that truncation toward zero for both positive and negative amounts.
            return new BigDecimal(digits).setScale(2, RoundingMode.DOWN);
        } catch (NumberFormatException ex) {
            // Safety net: the pattern already guarantees a well-formed number, but any residual
            // format problem is reported as "not valid" (null) rather than thrown.
            return null;
        }
    }
}

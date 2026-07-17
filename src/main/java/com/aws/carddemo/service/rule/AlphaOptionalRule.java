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

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Validation rule reproducing the COBOL edit paragraph {@code 1235-EDIT-ALPHA-OPT}
 * (see {@code legacy/cbl/COACTUPC.cbl:L2012-L2059}), which permits only alphabetic
 * characters and spaces in an <em>optional</em> field.
 *
 * <p>This is the optional counterpart of {@code 1225-EDIT-ALPHA-REQD}
 * ({@code AlphaRequiredRule}). The single behavioral difference is that a blank value is
 * <strong>valid</strong> here &mdash; the field is optional, so an unsupplied value is
 * acceptable and there is no "must be supplied." message. The legacy paragraph makes this
 * explicit: when the field equals {@code LOW-VALUES}, equals {@code SPACES}, or trims to a
 * zero-length string it sets {@code FLG-ALPHA-ISVALID} and returns without an error
 * (see {@code legacy/cbl/COACTUPC.cbl:L2016-L2028}).</p>
 *
 * <p>The two checks are applied in the exact order of the COBOL paragraph, and this ordering
 * is behaviorally significant:</p>
 * <ol>
 *   <li><strong>Not-supplied (blank) check first.</strong> A {@code null} value (modeling
 *       {@code LOW-VALUES}), an empty value, or an all-whitespace value (modeling
 *       {@code SPACES} / a {@code FUNCTION TRIM} length of zero) is accepted as valid with no
 *       message, via {@link ValidationRule#isBlank(String)}.</li>
 *   <li><strong>Alpha-only check.</strong> When a value <em>is</em> supplied, the legacy code
 *       {@code INSPECT ... CONVERTING} maps every alphabetic character to a space and then
 *       tests whether the {@code FUNCTION TRIM} of the result is empty; any residual
 *       (non-alphabetic, non-space) character makes the field invalid
 *       (see {@code legacy/cbl/COACTUPC.cbl:L2030-L2053}). The equivalent Java test is a full
 *       match against {@link #ALPHA_ONLY}. On failure the rule emits the exact COBOL screen
 *       message {@code "<field> can have alphabets only."} (note the trailing period), built by
 *       substituting the trimmed field label from {@link ValidationRule#label(String)}.</li>
 * </ol>
 *
 * <p><strong>Character-set fidelity.</strong> The permitted set is taken verbatim from the
 * COBOL {@code INSPECT} literal {@code LIT-ALL-ALPHA-FROM-X}
 * (see {@code legacy/cbl/COACTUPC.cbl:L586-L593}), namely {@code LIT-UPPER}
 * ({@code 'A'}&ndash;{@code 'Z'}, 26 characters) followed by {@code LIT-LOWER}
 * ({@code 'a'}&ndash;{@code 'z'}, 26 characters) &mdash; 52 letters in all &mdash; with the
 * space character also allowed. This is expressed with the explicit ASCII ranges
 * {@code [A-Za-z ]}; {@link Character#isLetter(int)} is deliberately <em>not</em> used because
 * it would also accept non-ASCII Unicode letters that the fixed-width EBCDIC field never
 * contained, which would break behavioral parity.</p>
 *
 * <p>The rule is stateless and side-effect free, so the single Spring-managed instance is safe
 * to share across threads. It never throws to signal a validation failure and never logs the
 * raw field value; the outcome is conveyed solely by the returned {@link ValidationResult}, and
 * <em>first-message-wins</em> latching across a screen's fields is the calling service's
 * responsibility (mirroring the COBOL {@code WS-RETURN-MSG-OFF} guard), not this rule's.</p>
 *
 * @see AlphaRequiredRule
 * @see ValidationRule
 * @see ValidationResult
 */
@Component
public final class AlphaOptionalRule implements ValidationRule {

    /**
     * Full-match pattern accepting only the 52 ASCII letters {@code A}&ndash;{@code Z} and
     * {@code a}&ndash;{@code z} plus the space character, and the empty string. It is the Java
     * equivalent of the COBOL {@code INSPECT ... CONVERTING LIT-ALL-ALPHA-FROM TO
     * LIT-ALPHA-SPACES-TO} followed by the {@code FUNCTION TRIM(...) = 0} residue test
     * (see {@code legacy/cbl/COACTUPC.cbl:L586-L593,L2030-L2039}). Compiled once and reused
     * because {@link Pattern} is immutable and thread-safe.
     */
    private static final Pattern ALPHA_ONLY = Pattern.compile("^[A-Za-z ]*$");

    /**
     * Validates that an <em>optional</em> field, when supplied, contains only alphabetic
     * characters ({@code A}&ndash;{@code Z}, {@code a}&ndash;{@code z}) and spaces, reproducing
     * COBOL paragraph {@code 1235-EDIT-ALPHA-OPT}.
     *
     * <p>A not-supplied value is accepted first: a {@code null} value (COBOL {@code LOW-VALUES}),
     * an empty value, or an all-whitespace value (COBOL {@code SPACES} / trim length zero)
     * returns {@link ValidationResult#valid()} with no message. Otherwise the supplied value must
     * fully match {@link #ALPHA_ONLY}; if it does not, the rule returns
     * {@link ValidationResult#invalid(String)} carrying {@code "<field> can have alphabets
     * only."} with the trimmed {@code fieldName} substituted for {@code <field>}.</p>
     *
     * @param fieldName the human-readable field label (COBOL {@code WS-EDIT-VARIABLE-NAME})
     *                  substituted into the failure message; {@code null} or padded input is
     *                  normalized by {@link ValidationRule#label(String)}
     * @param value     the field content as entered; may be {@code null} (COBOL
     *                  {@code LOW-VALUES}) or blank (COBOL {@code SPACES}), both of which are
     *                  valid for an optional field
     * @return {@link ValidationResult#valid()} when the field is blank or contains only letters
     *         and spaces; otherwise {@link ValidationResult#invalid(String)} with the COBOL
     *         screen message
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        if (ValidationRule.isBlank(value)) {
            return ValidationResult.valid();
        }
        if (!ALPHA_ONLY.matcher(value).matches()) {
            return ValidationResult.invalid(ValidationRule.label(fieldName) + " can have alphabets only.");
        }
        return ValidationResult.valid();
    }
}

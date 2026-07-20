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
 * Validation rule for an <em>optional</em> alphanumeric field, the Java migration of the COBOL
 * edit paragraph {@code 1240-EDIT-ALPHANUM-OPT} in {@code legacy/cbl/COACTUPC.cbl}
 * (source {@code app/cbl/COACTUPC.cbl:L2061-L2107}). Detailed rationale lives in the decision log.
 *
 * <p>This rule is the <em>optional</em> counterpart of {@code 1230-EDIT-ALPHANUM-REQD}
 * ({@code AlphanumRequiredRule}). The two paragraphs share the identical alphanumeric character
 * test; the sole behavioral difference is that a not-supplied (blank) value is <strong>valid</strong>
 * here &mdash; the optional paragraph never emits the "must be supplied." message. It preserves the
 * legacy evaluation order exactly:</p>
 * <ol>
 *   <li><strong>Blank check first.</strong> A {@code null} value (COBOL {@code LOW-VALUES}), an
 *       all-spaces value ({@code SPACES}), or a value whose {@code FUNCTION TRIM} length is zero is
 *       accepted as valid, mirroring the leading guard at
 *       {@code legacy/cbl/COACTUPC.cbl:L2066-L2076} which sets {@code FLG-ALPHNANUM-ISVALID} and
 *       branches to the paragraph exit without building any message.</li>
 *   <li><strong>Alphanumeric-only check.</strong> When a value is present, the legacy code performs
 *       {@code INSPECT ... CONVERTING LIT-ALL-ALPHANUM-FROM TO LIT-ALPHANUM-SPACES-TO}
 *       ({@code L2079-L2082}), replacing every alphanumeric character with a space, and then tests
 *       whether the trimmed residue length is zero ({@code L2084-L2087}). A non-empty residue means
 *       at least one character was neither alphanumeric nor a space, so {@code INPUT-ERROR} is set
 *       and the screen message {@code WS-RETURN-MSG} is built from
 *       {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' can have numbers or alphabets only.'}
 *       ({@code L2090-L2098}).</li>
 * </ol>
 *
 * <p><strong>Character-set fidelity.</strong> The legacy conversion table {@code LIT-ALL-ALPHANUM-FROM-X}
 * ({@code legacy/cbl/COACTUPC.cbl:L586-L593}) is composed of {@code LIT-UPPER} ('A'&ndash;'Z', 26
 * characters), {@code LIT-LOWER} ('a'&ndash;'z', 26 characters), and {@code LIT-NUMBERS}
 * ('0'&ndash;'9', 10 characters) &mdash; exactly the 62 ASCII alphanumeric characters &mdash; and the
 * embedded space character in the fixed-width buffer is likewise permitted. This is reproduced with
 * the anchored pattern {@code ^[A-Za-z0-9 ]*$}: the value passes iff every character lies in
 * {A&ndash;Z, a&ndash;z, 0&ndash;9, space}, which is precisely the condition under which the COBOL
 * {@code INSPECT} leaves a zero-length trimmed residue. Explicit ASCII ranges are used rather than
 * {@link Character#isLetterOrDigit(char)}, because the latter would also accept non-ASCII Unicode
 * letters and digits that the COBOL class test rejects, breaking behavioral parity.</p>
 *
 * <p>Instances are stateless, side-effect free, and thread-safe, and are therefore registered as a
 * singleton Spring bean. In keeping with the {@link ValidationRule} contract this rule never throws
 * to signal a validation failure and never logs the raw field value; the outcome is conveyed solely
 * by the returned {@link ValidationResult}. The <em>first-message-wins</em> latching that the legacy
 * program performs via {@code WS-RETURN-MSG-OFF} is the responsibility of the calling service, not of
 * this rule.</p>
 *
 * @see ValidationRule
 * @see ValidationResult
 */
@Component
public final class AlphanumOptionalRule implements ValidationRule {

    /**
     * Anchored pattern that matches a value consisting solely of ASCII letters, ASCII digits, and
     * spaces. This is the Java equivalent of the COBOL {@code LIT-ALL-ALPHANUM-FROM-X} conversion set
     * ('A'&ndash;'Z', 'a'&ndash;'z', '0'&ndash;'9') plus the permitted embedded space; the leading
     * {@code ^} and trailing {@code $} anchors with the {@code *} quantifier require every character
     * to be a member of the set (an empty string also matches, which is harmless because the blank
     * guard in {@link #validate(String, String)} short-circuits before the pattern is consulted).
     * A compiled {@link Pattern} is immutable and thread-safe, so it is held as a shared constant.
     */
    private static final Pattern ALPHANUM_ONLY = Pattern.compile("^[A-Za-z0-9 ]*$");

    /**
     * Validates an optional alphanumeric field, reproducing COBOL {@code 1240-EDIT-ALPHANUM-OPT}.
     *
     * <p>A blank value (the COBOL not-supplied guard: {@code null}/{@code LOW-VALUES},
     * {@code SPACES}, or a zero-length trim) is accepted as valid because the field is optional.
     * Otherwise the value must contain only ASCII letters, ASCII digits, and spaces; any other
     * character fails the rule with the exact legacy message
     * {@code "<field> can have numbers or alphabets only."} (note the trailing period), where
     * {@code <field>} is the trimmed field label.</p>
     *
     * @param fieldName the human-readable field label (COBOL {@code WS-EDIT-VARIABLE-NAME}) used to
     *                  build the outcome message; may be {@code null} or padded, in which case
     *                  {@link ValidationRule#label(String)} normalizes it
     * @param value     the field content as entered; may be {@code null} (COBOL {@code LOW-VALUES})
     *                  or blank (COBOL {@code SPACES}), both of which are valid for an optional field
     * @return {@link ValidationResult#valid()} when the value is blank or wholly alphanumeric/space;
     *         otherwise {@link ValidationResult#invalid(String)} carrying the COBOL screen message
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        // Not supplied, but OK as optional: reproduces legacy L2066-L2076 (LOW-VALUES / SPACES /
        // FUNCTION LENGTH(FUNCTION TRIM(...)) = 0 -> valid, no message).
        if (ValidationRule.isBlank(value)) {
            return ValidationResult.valid();
        }
        // Only alphabets, digits, and spaces allowed: reproduces the INSPECT CONVERTING + trimmed
        // residue test at legacy L2079-L2101. A residual (non-matching) character yields the
        // exact COBOL message built at L2093-L2098.
        if (!ALPHANUM_ONLY.matcher(value).matches()) {
            return ValidationResult.invalid(
                    ValidationRule.label(fieldName) + " can have numbers or alphabets only.");
        }
        return ValidationResult.valid();
    }
}

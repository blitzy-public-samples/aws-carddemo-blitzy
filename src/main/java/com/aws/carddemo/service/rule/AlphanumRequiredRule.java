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
 * Required alphanumeric-field edit rule &mdash; the Java migration of COBOL edit paragraph
 * {@code 1230-EDIT-ALPHANUM-REQD} from {@code legacy/cbl/COACTUPC.cbl}
 * (source {@code app/cbl/COACTUPC.cbl:L1955-L2011}). It reproduces, with verbatim behavioral
 * parity, the two-stage check that the online account-update program applies to a mandatory
 * free-text field (for example an address line or a name): the field must be supplied, and
 * once supplied it may contain only letters, digits, and spaces.
 *
 * <p><strong>Evaluation order (preserved exactly from the COBOL).</strong> The paragraph runs
 * its two tests in a fixed order and short-circuits on the first failure, so the order is a
 * caller-visible contract and is preserved here:</p>
 * <ol>
 *   <li><em>Not-supplied (blank) test &mdash; first.</em> COBOL rejects the field when the
 *       buffer slice equals {@code LOW-VALUES}, equals {@code SPACES}, or has a
 *       {@code FUNCTION TRIM} length of zero ({@code app/cbl/COACTUPC.cbl:L1960-L1965}). In Java
 *       these three conditions collapse to {@link ValidationRule#isBlank(String)}
 *       ({@code null} models {@code LOW-VALUES}; empty or all-whitespace models {@code SPACES} /
 *       a trim length of zero). The failure message is
 *       <code>"&lt;field&gt; must be supplied."</code> ({@code app/cbl/COACTUPC.cbl:L1970-L1975}).</li>
 *   <li><em>Alphanumeric-only test &mdash; second.</em> COBOL performs
 *       {@code INSPECT ... CONVERTING LIT-ALL-ALPHANUM-FROM TO LIT-ALPHANUM-SPACES-TO}
 *       ({@code app/cbl/COACTUPC.cbl:L1984-L1986}), turning every allowed character into a space,
 *       and then treats the field as valid only when the trimmed residue is empty
 *       ({@code app/cbl/COACTUPC.cbl:L1988-L1991}); any leftover character means a disallowed
 *       character was present. The failure message is
 *       <code>"&lt;field&gt; can have numbers or alphabets only."</code>
 *       ({@code app/cbl/COACTUPC.cbl:L1997-L2002}).</li>
 * </ol>
 * <p>Because the blank test runs first and short-circuits, the alphanumeric test only ever sees
 * a value that contains at least one non-whitespace character.</p>
 *
 * <p><strong>Character-set fidelity.</strong> The COBOL "all alphanumeric" literal
 * {@code LIT-ALL-ALPHANUM-FROM-X} ({@code app/cbl/COACTUPC.cbl:L586-L593}) is composed of
 * {@code LIT-UPPER} ({@code A-Z}), {@code LIT-LOWER} ({@code a-z}), and {@code LIT-NUMBERS}
 * ({@code 0-9}) &mdash; exactly the 62 ASCII alphanumeric characters. A space is not part of that
 * {@code CONVERTING} source set, so a space is left untouched by the {@code INSPECT} and is then
 * removed by the trim; the net effect is that letters, digits, and spaces are permitted and
 * nothing else. This maps precisely to the anchored regular expression
 * {@code ^[A-Za-z0-9 ]*$}. Explicit ASCII ranges are used deliberately:
 * {@link Character#isLetterOrDigit(char)} is <em>not</em> used because it also accepts non-ASCII
 * Unicode letters and digits that the COBOL ASCII {@code INSPECT} would reject, which would be a
 * behavioral regression.</p>
 *
 * <p><strong>Statelessness and threading.</strong> The rule holds only one immutable, compiled
 * {@link Pattern} (itself thread-safe) and no mutable state, so the single Spring-managed
 * singleton instance is safe to share across all request and batch threads. Consistent with the
 * {@link ValidationRule} contract it never throws to report a validation failure &mdash; the
 * outcome is conveyed solely through the returned {@link ValidationResult} &mdash; and it does
 * not perform first-message-wins latching, which remains the calling service's responsibility.</p>
 *
 * @see ValidationRule
 * @see ValidationResult
 */
@Component
public final class AlphanumRequiredRule implements ValidationRule {

    /**
     * Anchored pattern matching a string composed solely of ASCII letters, ASCII digits, and
     * spaces &mdash; the exact set the COBOL {@code INSPECT ... CONVERTING} treats as removable
     * ({@code app/cbl/COACTUPC.cbl:L586-L593,L1984-L1986}). The {@code ^}/{@code $} anchors and
     * the {@code *} quantifier require <em>every</em> character to be in the allowed set; any
     * other character (punctuation, symbol, control, or non-ASCII letter/digit) fails the match,
     * mirroring a non-empty trimmed residue in the legacy program. Compiled once and reused
     * because the rule is a shared singleton.
     */
    private static final Pattern ALPHANUM_ONLY = Pattern.compile("^[A-Za-z0-9 ]*$");

    /**
     * Validates that {@code value} is supplied and contains only letters, digits, and spaces,
     * reproducing COBOL paragraph {@code 1230-EDIT-ALPHANUM-REQD}
     * ({@code app/cbl/COACTUPC.cbl:L1955-L2011}).
     *
     * <p>The two checks are applied in the legacy order and the first failure wins: the
     * not-supplied (blank) check first, then the alphanumeric-only check. The field label is
     * normalized with {@link ValidationRule#label(String)} (COBOL
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}) before being substituted into the outcome
     * message.</p>
     *
     * @param fieldName the human-readable field label used to build the outcome message
     *                  (COBOL {@code WS-EDIT-VARIABLE-NAME}); may be {@code null} or padded
     * @param value     the field content as entered; {@code null} models COBOL {@code LOW-VALUES}
     *                  and an empty or all-whitespace string models {@code SPACES}
     * @return {@link ValidationResult#valid()} when {@code value} is supplied and contains only
     *         letters, digits, and spaces; otherwise {@link ValidationResult#invalid(String)}
     *         carrying <code>"&lt;field&gt; must be supplied."</code> for a blank value or
     *         <code>"&lt;field&gt; can have numbers or alphabets only."</code> for a value that
     *         contains a disallowed character
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        String field = ValidationRule.label(fieldName);
        if (ValidationRule.isBlank(value)) {
            return ValidationResult.invalid(field + " must be supplied.");
        }
        if (!ALPHANUM_ONLY.matcher(value).matches()) {
            return ValidationResult.invalid(field + " can have numbers or alphabets only.");
        }
        return ValidationResult.valid();
    }
}

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
 * Alphabetic-required field edit &mdash; the Java re-platform of the COBOL edit paragraph
 * {@code 1225-EDIT-ALPHA-REQD} of the online account-update program
 * {@code legacy/cbl/COACTUPC.cbl} (source-branch {@code app/cbl/COACTUPC.cbl}, lines
 * L1898&ndash;L1953). It reproduces, with no feature expansion and to-the-character parity,
 * the two caller-visible outcomes the legacy paragraph could emit for a mandatory
 * "letters only" field (for example a customer first or last name).
 *
 * <h2>Legacy behavior reproduced (verbatim evaluation order)</h2>
 * The COBOL paragraph runs two checks in a fixed order and stops at the first failure:
 * <ol>
 *   <li><b>Not supplied (checked first).</b> If the field slice equals {@code LOW-VALUES}
 *       or {@code SPACES}, or {@code FUNCTION LENGTH(FUNCTION TRIM(...)) = 0}
 *       (L1903&ndash;L1908), the paragraph sets {@code INPUT-ERROR} and builds the message
 *       {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' must be supplied.'}
 *       (L1913&ndash;L1918), then exits. Modeled here as {@link ValidationRule#isBlank(String)}
 *       returning {@code true} &rarr; {@code "<field> must be supplied."}.</li>
 *   <li><b>Alphabets and space only.</b> Otherwise the paragraph moves the "all alpha"
 *       literal into the INSPECT-from set and
 *       {@code INSPECT ... CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALPHA-SPACES-TO}
 *       (L1925&ndash;L1928): every alphabetic character is turned into a space. If the
 *       trimmed residue then has {@code LENGTH = 0} (L1930&ndash;L1933) the value was made
 *       up solely of letters and spaces and is valid ({@code CONTINUE}); otherwise a
 *       non-alphabetic, non-space character remained and the paragraph builds
 *       {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' can have alphabets only.'}
 *       (L1939&ndash;L1944). Modeled here as {@link #ALPHA_ONLY} failing to match &rarr;
 *       {@code "<field> can have alphabets only."}.</li>
 * </ol>
 * When both checks pass the paragraph sets {@code FLG-ALPHA-ISVALID} (L1949); this class
 * returns {@link ValidationResult#valid()}.
 *
 * <h2>Character-set fidelity</h2>
 * The COBOL "all alpha" literal {@code LIT-ALL-ALPHA-FROM-X} is defined at
 * {@code legacy/cbl/COACTUPC.cbl:L586-L593} as {@code LIT-UPPER} =
 * {@code 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'} concatenated with {@code LIT-LOWER} =
 * {@code 'abcdefghijklmnopqrstuvwxyz'} &mdash; exactly the 52 ASCII letters, uppercase and
 * lowercase, and nothing else. A space is <em>not</em> in that set, so a space is left
 * untouched by the INSPECT and is subsequently removed by {@code TRIM}; hence spaces are
 * permitted anywhere in the value. This maps exactly to the regular expression
 * {@link #ALPHA_ONLY} = {@code ^[A-Za-z ]*$}: after converting every letter to a space, the
 * residue is all-spaces (trimmed length zero) if and only if every original character was a
 * letter or a space. Explicit ASCII ranges are used deliberately; {@link Character#isLetter(int)}
 * is intentionally <em>avoided</em> because it also accepts non-ASCII Unicode letters that the
 * legacy ASCII {@code INSPECT} would have rejected.
 *
 * <h2>Design</h2>
 * This is a stateless, side-effect-free Spring {@link Component} (a singleton bean) and is one
 * discrete implementation of the {@link ValidationRule} strategy contract, as described in the
 * decision log. It reuses the shared edit primitives {@link ValidationRule#isBlank(String)} and
 * {@link ValidationRule#label(String)} rather than re-implementing the not-supplied guard or the
 * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} label normalization. Consistent with every rule in
 * this package, it <em>always</em> returns its own outcome message and never latches; the
 * first-message-wins latching (the COBOL {@code IF WS-RETURN-MSG-OFF} guard) is the responsibility
 * of the calling service. The rule never throws to signal a validation failure &mdash; the outcome
 * is always conveyed through the returned {@link ValidationResult}. The compiled {@link #ALPHA_ONLY}
 * pattern is immutable and thread-safe, so sharing it as a {@code static final} field is safe and
 * keeps the build allocation- and warning-free.
 */
@Component
public final class AlphaRequiredRule implements ValidationRule {

    /**
     * Matches a value composed solely of ASCII letters ({@code A}&ndash;{@code Z},
     * {@code a}&ndash;{@code z}) and spaces, including the empty string. This is the exact Java
     * equivalent of the COBOL {@code INSPECT ... CONVERTING} over the 52-letter
     * {@code LIT-ALL-ALPHA-FROM-X} set followed by the {@code TRIM}-length-zero test: a value
     * matches here if and only if converting each of its letters to a space would leave only
     * spaces. The pattern is compiled once because it is immutable and thread-safe, which suits
     * this singleton bean and avoids per-call recompilation.
     */
    private static final Pattern ALPHA_ONLY = Pattern.compile("^[A-Za-z ]*$");

    /**
     * Validates that a required field contains only alphabetic characters and spaces,
     * reproducing COBOL {@code 1225-EDIT-ALPHA-REQD} of {@code legacy/cbl/COACTUPC.cbl}.
     *
     * <p>The checks run in the legacy order and stop at the first failure:</p>
     * <ol>
     *   <li>if {@code value} is not supplied &mdash; {@code null} (COBOL {@code LOW-VALUES}),
     *       empty, or entirely whitespace (COBOL {@code SPACES} / {@code TRIM} length zero), as
     *       reported by {@link ValidationRule#isBlank(String)} &mdash; the result is invalid with
     *       message {@code "<field> must be supplied."};</li>
     *   <li>otherwise, if the raw {@code value} contains any character other than an ASCII letter
     *       or a space (i.e. it does not match {@link #ALPHA_ONLY}), the result is invalid with
     *       message {@code "<field> can have alphabets only."};</li>
     *   <li>otherwise the result is {@link ValidationResult#valid()}.</li>
     * </ol>
     *
     * <p>The regular expression is applied to the <em>raw</em> {@code value}: because spaces are
     * permitted by the character class, leading, trailing, and interior spaces do not affect the
     * outcome &mdash; this is identical to the legacy convert-then-{@code TRIM} result. The
     * {@code <field>} token is the trimmed field label produced by
     * {@link ValidationRule#label(String)} (COBOL {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}).</p>
     *
     * @param fieldName the human-readable field label substituted into the outcome message
     *                  (COBOL {@code WS-EDIT-VARIABLE-NAME}); may be {@code null} or padded, in
     *                  which case {@link ValidationRule#label(String)} normalizes it
     * @param value     the field content as entered; may be {@code null} (COBOL {@code LOW-VALUES})
     *                  or blank (COBOL {@code SPACES})
     * @return {@link ValidationResult#valid()} when {@code value} is non-blank and consists only of
     *         ASCII letters and spaces; otherwise {@link ValidationResult#invalid(String)} carrying
     *         the exact COBOL screen message
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        String field = ValidationRule.label(fieldName);
        if (ValidationRule.isBlank(value)) {
            return ValidationResult.invalid(field + " must be supplied.");
        }
        if (!ALPHA_ONLY.matcher(value).matches()) {
            return ValidationResult.invalid(field + " can have alphabets only.");
        }
        return ValidationResult.valid();
    }
}

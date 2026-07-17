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

/**
 * Mandatory-numeric field edit rule; the Java abstraction of the COBOL paragraph
 * {@code 1245-EDIT-NUM-REQD} of {@code legacy/cbl/COACTUPC.cbl} (lines 2109&ndash;2178).
 * Detailed rationale lives in the decision log.
 *
 * <p>The legacy paragraph edits a required, unsigned, all-numeric, non-zero field (for example
 * an account identifier or a FICO score). It runs three checks <em>in order</em>, latching the
 * first failure's screen message into {@code WS-RETURN-MSG} and branching to its exit:</p>
 * <ol>
 *   <li><strong>Not supplied</strong> &mdash; the field equals {@code LOW-VALUES} or
 *       {@code SPACES}, or its trimmed length is zero
 *       ({@code legacy/cbl/COACTUPC.cbl:L2114-L2119}) &rarr; message
 *       {@code "<field> must be supplied."} ({@code L2124-L2126}).</li>
 *   <li><strong>Not numeric</strong> &mdash; the field fails the {@code IS NUMERIC} class test
 *       ({@code legacy/cbl/COACTUPC.cbl:L2137-L2138}) &rarr; message
 *       {@code "<field> must be all numeric."} ({@code L2144-L2146}).</li>
 *   <li><strong>Zero</strong> &mdash; {@code FUNCTION NUMVAL(...)} evaluates to zero
 *       ({@code legacy/cbl/COACTUPC.cbl:L2156-L2157}) &rarr; message
 *       {@code "<field> must not be zero."} ({@code L2161-L2163}).</li>
 * </ol>
 * <p>When all three checks pass the field is valid ({@code SET FLG-ALPHNANUM-ISVALID TO TRUE}
 * at {@code L2174}). The {@code <field>} token is {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}
 * &mdash; the caller-supplied field label with leading/trailing spaces removed &mdash; and each
 * message ends with a period, reproduced verbatim here.</p>
 *
 * <p>The check order is a behavioral contract: reordering it would change which message a given
 * value receives (for example, a blank value must report "must be supplied", never "must be all
 * numeric"), so the supplied &rarr; numeric &rarr; non-zero sequence is preserved exactly and the
 * evaluation short-circuits on the first failure. The individual tests delegate to the shared
 * {@code static} COBOL-edit primitives on {@link ValidationRule}
 * ({@link ValidationRule#label(String)}, {@link ValidationRule#isBlank(String)},
 * {@link ValidationRule#isAllDigits(String)}, {@link ValidationRule#isZeroValue(String)}) so the
 * blank / {@code IS NUMERIC} / {@code NUMVAL = 0} / {@code TRIM} semantics stay consistent with
 * every other rule and are not re-implemented here.</p>
 *
 * <p>The rule is stateless and side-effect free: it holds no fields and needs no injected
 * collaborators, so the implicit default constructor suffices (the constructor-injection mandate
 * is honored vacuously &mdash; there is nothing to inject). It is registered as a singleton
 * Spring bean via {@link Component} and is therefore safe to share across threads. It is
 * foundational within the {@code service.rule} package: it is constructor-injected and reused by
 * the composite rules {@code FicoScoreRule} and {@code UsSsnRule} (mirroring the legacy
 * {@code PERFORM 1245-EDIT-NUM-REQD} reuse), and it may be injected directly by parent-folder
 * services to validate any mandatory-numeric field. It depends only on {@link ValidationRule}
 * and {@link ValidationResult} (same package) and the Spring stereotype annotation.</p>
 */
@Component
public class NumericRequiredRule implements ValidationRule {

    /**
     * Validates that a field is supplied, all-numeric, and non-zero, reproducing
     * {@code 1245-EDIT-NUM-REQD} of {@code legacy/cbl/COACTUPC.cbl} exactly.
     *
     * <p>The three checks run in the legacy order and short-circuit on the first failure:</p>
     * <ol>
     *   <li>if {@code value} is not supplied (null / blank / trim-length zero) &rarr; invalid,
     *       {@code "<field> must be supplied."};</li>
     *   <li>otherwise if {@code value} is not all ASCII digits &rarr; invalid,
     *       {@code "<field> must be all numeric."};</li>
     *   <li>otherwise if {@code value} is numerically zero &rarr; invalid,
     *       {@code "<field> must not be zero."};</li>
     * </ol>
     * <p>otherwise the result is {@link ValidationResult#valid()}. The {@code <field>} token is
     * {@code fieldName} normalized through {@link ValidationRule#label(String)} (trimmed;
     * {@code null} becomes the empty string), matching {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}.
     * Because {@code value} models the legacy fixed-width buffer slice as entered, any embedded or
     * trailing space makes it non-numeric, exactly as the COBOL {@code IS NUMERIC} test behaves.</p>
     *
     * @param fieldName the human-readable field label used to build the message (COBOL
     *                  {@code WS-EDIT-VARIABLE-NAME}); may be {@code null} or padded
     * @param value     the field content as entered; may be {@code null} (COBOL {@code LOW-VALUES})
     *                  or blank (COBOL {@code SPACES})
     * @return {@link ValidationResult#valid()} when the value is supplied, all-numeric, and
     *         non-zero; otherwise {@link ValidationResult#invalid(String)} carrying the exact
     *         COBOL screen message for the first failed check
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        String field = ValidationRule.label(fieldName);

        // Check 1 - Not supplied (COBOL L2114-L2119): LOW-VALUES / SPACES / trim length = 0.
        if (ValidationRule.isBlank(value)) {
            return ValidationResult.invalid(field + " must be supplied.");
        }

        // Check 2 - Only all numeric allowed (COBOL L2137-L2138): IS NUMERIC class test.
        if (!ValidationRule.isAllDigits(value)) {
            return ValidationResult.invalid(field + " must be all numeric.");
        }

        // Check 3 - Must not be zero (COBOL L2156-L2157): FUNCTION NUMVAL(...) = 0.
        if (ValidationRule.isZeroValue(value)) {
            return ValidationResult.invalid(field + " must not be zero.");
        }

        // All three checks passed (COBOL L2174: SET FLG-ALPHNANUM-ISVALID TO TRUE).
        return ValidationResult.valid();
    }
}

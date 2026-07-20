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
 * Mandatory (not-supplied) field-validation rule &mdash; the Java migration of the COBOL edit
 * paragraph {@code 1215-EDIT-MANDATORY} in {@code legacy/cbl/COACTUPC.cbl}
 * (source {@code app/cbl/COACTUPC.cbl:L1824-L1854}).
 *
 * <p>The legacy paragraph flags a field as an input error when its buffer slice equals
 * {@code LOW-VALUES}, equals {@code SPACES}, or trims to a length of zero, and in that case
 * builds the screen message {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' must be
 * supplied.'}; otherwise the field is valid. This component reproduces that behavior exactly:
 * a {@code null}, all-whitespace, or empty value is "not supplied" (delegated to
 * {@link ValidationRule#isBlank(String)}), yielding {@link ValidationResult#invalid(String)}
 * with the message {@code "<field> must be supplied."} where {@code <field>} is the trimmed
 * field label (delegated to {@link ValidationRule#label(String)}); any other value yields
 * {@link ValidationResult#valid()}.</p>
 *
 * <p>The message text {@code " must be supplied."} (single leading space, trailing period) is a
 * preserved parity contract copied verbatim from the COBOL literal and must not be altered. The
 * COBOL {@code WS-RETURN-MSG-OFF} first-message-wins latch is intentionally not modeled here;
 * this rule always returns its own outcome, and selecting the first failing message is the
 * responsibility of the consuming service. Rationale for these decisions lives in
 * {@code docs/decision-log.md}.</p>
 *
 * <p>The component is stateless and side-effect free, so the single Spring-managed instance is
 * safe to share across threads.</p>
 */
@Component
public final class MandatoryFieldRule implements ValidationRule {

    /**
     * Validates that a field was supplied, reproducing COBOL {@code 1215-EDIT-MANDATORY}.
     *
     * @param fieldName the human-readable field label (COBOL {@code WS-EDIT-VARIABLE-NAME});
     *                  normalized with {@link ValidationRule#label(String)}, so a {@code null}
     *                  or space-padded label is handled
     * @param value     the field content as entered; may be {@code null} (modeling COBOL
     *                  {@code LOW-VALUES}) or blank (modeling {@code SPACES})
     * @return {@link ValidationResult#invalid(String)} carrying {@code "<field> must be
     *         supplied."} when {@code value} is not supplied (null, empty, or all whitespace);
     *         {@link ValidationResult#valid()} otherwise
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        String field = ValidationRule.label(fieldName);
        if (ValidationRule.isBlank(value)) {
            return ValidationResult.invalid(field + " must be supplied.");
        }
        return ValidationResult.valid();
    }
}

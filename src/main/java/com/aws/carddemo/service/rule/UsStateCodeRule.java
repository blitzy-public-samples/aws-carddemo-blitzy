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

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Validates that a customer address state code is one of the recognized two-letter US
 * state/territory codes &mdash; the Java migration of the COBOL edit paragraph
 * {@code 1270-EDIT-US-STATE-CD} of the online account-update program
 * {@code legacy/cbl/COACTUPC.cbl} (source {@code app/cbl/COACTUPC.cbl}, lines 2493&ndash;2513).
 * Detailed rationale lives in the decision log.
 *
 * <p>The legacy paragraph performs a single 88-level membership test: it moves the entered
 * state code into the {@code PIC X(2)} work field {@code US-STATE-CODE-TO-EDIT} and evaluates
 * the {@code 88 VALID-US-STATE-CODE} condition defined in the lookup copybook
 * {@code legacy/cpy/CSLKPCDY.cpy} (lines 1013&ndash;1069). When the code is a member the field
 * passes; otherwise the program flags {@code INPUT-ERROR} and, if no message has yet been
 * latched, builds the screen message</p>
 * <pre>
 *     STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
 *            ': is not a valid state code'
 *            DELIMITED BY SIZE INTO WS-RETURN-MSG
 * </pre>
 * <p>This rule reproduces that behavior exactly: a member value yields
 * {@link ValidationResult#valid()}; any other value (including a blank/spaces value, for which
 * the COBOL 88-level is simply {@code false}) yields
 * {@link ValidationResult#invalid(String)} carrying {@code "<field>: is not a valid state code"},
 * where {@code <field>} is the trimmed caller label produced by {@link ValidationRule#label(String)}
 * (the Java equivalent of {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}).</p>
 *
 * <p>The membership test is <em>case-sensitive</em>: the COBOL 88-level literals are upper-case,
 * so a lower-case entry such as {@code "ca"} is genuinely not a member and is reported invalid.
 * The input is therefore deliberately <em>not</em> upper-cased &mdash; doing so would accept
 * values the legacy program rejects and would be a behavioral regression. Leading and trailing
 * whitespace is stripped before the comparison (mirroring the fixed-width buffer slice the legacy
 * program compares), but the two significant characters are matched verbatim.</p>
 *
 * <p>Mandatory-ness, where a screen requires the field, is enforced separately and earlier by the
 * calling service (via {@code MandatoryFieldRule}) under the COBOL first-message-wins ordering, so
 * this rule intentionally does not distinguish "not supplied" from "not a member"; both are simply
 * not-a-member and produce the one state-code message, exactly as the legacy paragraph does.</p>
 *
 * <p>The class is a stateless, immutable Spring {@code @Component} (its only state is the shared,
 * immutable set of valid codes) and is therefore safe to inject and share as a singleton bean. It
 * never throws to signal a validation failure &mdash; the outcome is always conveyed through the
 * returned {@link ValidationResult}. It depends only on the same-package {@link ValidationRule} and
 * {@link ValidationResult} plus the JDK.</p>
 */
@Component
public final class UsStateCodeRule implements ValidationRule {

    /**
     * The immutable set of the 56 valid two-letter US state and territory codes &mdash; the 50
     * states, the District of Columbia ({@code DC}), and the five territories {@code AS} (American
     * Samoa), {@code GU} (Guam), {@code MP} (Northern Mariana Islands), {@code PR} (Puerto Rico),
     * and {@code VI} (US Virgin Islands). The membership is transcribed verbatim, in source order,
     * from the {@code 88 VALID-US-STATE-CODE} condition-name values in
     * {@code legacy/cpy/CSLKPCDY.cpy} (lines 1013&ndash;1069) and is the single source of truth for
     * both {@link #validate(String, String)} and {@link #isValidStateCode(String)}.
     *
     * <p>{@link Set#of(Object...)} yields an unmodifiable set and additionally rejects duplicate
     * elements at class-initialization time, so an accidental duplicate in this list would fail
     * fast rather than silently shrink the set; the co-located unit test further asserts the exact
     * cardinality of 56.</p>
     */
    private static final Set<String> VALID_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA",
            "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT",
            "VA", "WA", "WV", "WI", "WY", "DC", "AS", "GU", "MP", "PR", "VI");

    /**
     * The fixed message suffix appended after the trimmed field label, reproducing the COBOL
     * literal {@code ': is not a valid state code'} concatenated by the {@code STRING} statement at
     * {@code legacy/cbl/COACTUPC.cbl:L2503}. The leading colon-and-space is significant to parity.
     */
    private static final String NOT_VALID_MESSAGE_SUFFIX = ": is not a valid state code";

    /**
     * Validates a candidate state code, reproducing {@code 1270-EDIT-US-STATE-CD} of
     * {@code legacy/cbl/COACTUPC.cbl}.
     *
     * <p>Returns {@link ValidationResult#valid()} when {@code value} (after stripping surrounding
     * whitespace) is one of the {@link #VALID_STATE_CODES}; otherwise returns
     * {@link ValidationResult#invalid(String)} carrying the message
     * {@code label + ": is not a valid state code"}, where {@code label} is
     * {@link ValidationRule#label(String) ValidationRule.label(fieldName)}. A {@code null} or blank
     * {@code value} is not a member and therefore fails with that same message, matching the COBOL
     * 88-level test on a spaces field.</p>
     *
     * @param fieldName the human-readable field label (COBOL {@code WS-EDIT-VARIABLE-NAME}) that is
     *                  trimmed and substituted into the failure message; may be {@code null} or
     *                  padded, in which case {@link ValidationRule#label(String)} normalizes it
     * @param value     the entered state code; may be {@code null} (COBOL {@code LOW-VALUES}) or
     *                  blank (COBOL {@code SPACES})
     * @return {@link ValidationResult#valid()} if {@code value} is a recognized state/territory
     *         code, otherwise {@link ValidationResult#invalid(String)} with the state-code message
     */
    @Override
    public ValidationResult validate(String fieldName, String value) {
        if (isValidStateCode(value)) {
            return ValidationResult.valid();
        }
        return ValidationResult.invalid(ValidationRule.label(fieldName) + NOT_VALID_MESSAGE_SUFFIX);
    }

    /**
     * Reports whether a value is one of the recognized two-letter US state/territory codes,
     * exposing the {@code 88 VALID-US-STATE-CODE} membership test as a reusable predicate.
     *
     * <p>This is the shared entry point that {@link #validate(String, String)} itself uses, and it
     * is provided so composite rules that also need a state check (for example the
     * state/ZIP-combination edit {@code 1280-EDIT-US-STATE-ZIP-CD}, migrated as
     * {@code UsStateZipRule}) and higher-level services can reuse the exact same membership set
     * without duplicating it &mdash; keeping {@link #VALID_STATE_CODES} the single source of
     * truth.</p>
     *
     * <p>The comparison strips leading and trailing whitespace (so a padded fixed-width slice such
     * as {@code " CA "} is recognized) but is otherwise verbatim and case-sensitive: the legacy
     * 88-level values are upper-case, so a lower-case code is not a member. A {@code null} value is
     * treated as the empty string and is not a member.</p>
     *
     * @param value the candidate state code; may be {@code null}
     * @return {@code true} if the stripped {@code value} is a recognized state/territory code,
     *         {@code false} otherwise (including for {@code null} or blank input)
     */
    public boolean isValidStateCode(String value) {
        String code = value == null ? "" : value.strip();
        return VALID_STATE_CODES.contains(code);
    }
}

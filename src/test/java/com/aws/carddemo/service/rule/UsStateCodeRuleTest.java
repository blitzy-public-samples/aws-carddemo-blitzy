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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UsStateCodeRule}, the Java migration of the COBOL edit paragraph
 * {@code 1270-EDIT-US-STATE-CD} of {@code legacy/cbl/COACTUPC.cbl} (lines 2493&ndash;2513,
 * source-branch {@code app/cbl/COACTUPC.cbl}) and its backing {@code 88 VALID-US-STATE-CODE}
 * lookup in {@code legacy/cpy/CSLKPCDY.cpy} (lines 1013&ndash;1069, source-branch
 * {@code app/cpy/CSLKPCDY.cpy}).
 *
 * <p>The legacy paragraph is a single 88-level membership test. It moves the entered code into the
 * {@code PIC X(2)} work field {@code US-STATE-CODE-TO-EDIT} and evaluates {@code VALID-US-STATE-CODE};
 * when the code is a member it {@code CONTINUE}s, otherwise it flags {@code INPUT-ERROR} and (if no
 * message has yet been latched) builds the screen message with</p>
 * <pre>
 *     STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
 *            ': is not a valid state code'
 *            DELIMITED BY SIZE INTO WS-RETURN-MSG
 * </pre>
 *
 * <p>These tests pin the verbatim parity contract of that paragraph:</p>
 * <ul>
 *   <li><b>Message:</b> an invalid code yields exactly {@code "<field>: is not a valid state code"}
 *       &mdash; note the leading colon-and-space that the COBOL {@code STRING} literal contributes
 *       after the trimmed field label ({@code legacy/cbl/COACTUPC.cbl:L2503}).</li>
 *   <li><b>Case sensitivity:</b> the 88-level literals are upper-case, so the rule does <em>not</em>
 *       upper-case its input; a lower-case entry such as {@code "ca"} is genuinely not a member and
 *       is reported invalid (COBOL 88-level parity).</li>
 *   <li><b>Blank / not-supplied:</b> a {@code null} (COBOL {@code LOW-VALUES}), empty, or all-spaces
 *       (COBOL {@code SPACES}) value is simply "not a member" and fails with the same message.</li>
 *   <li><b>Cardinality:</b> the recognized set contains exactly the 56 codes (50 states + the
 *       District of Columbia + the 5 territories {@code AS}, {@code GU}, {@code MP}, {@code PR},
 *       {@code VI}) enumerated by the copybook 88-level.</li>
 * </ul>
 *
 * <p>{@link UsStateCodeRule} is stateless (its only state is the shared, immutable set of valid
 * codes), so it is exercised through plain construction ({@code new UsStateCodeRule()}) with no
 * Spring context, no Mockito, and no database &mdash; this is a pure JUnit&nbsp;5 + AssertJ unit
 * test. The membership cardinality is asserted through the public {@link UsStateCodeRule#isValidStateCode(String)}
 * predicate (rather than by reaching into the private set via reflection): every one of the 676
 * two-letter upper-case combinations is probed and exactly 56 are accepted, which both proves the
 * count and confirms no unexpected code slipped in.</p>
 */
class UsStateCodeRuleTest {

    /**
     * The exact COBOL screen message for the field label {@code "State"}, reused across every
     * failure assertion. It is the trimmed label followed verbatim by the literal
     * {@code ": is not a valid state code"} (leading colon-space significant to parity).
     */
    private static final String STATE_MESSAGE = "State: is not a valid state code";

    /**
     * The representative field label supplied by callers as the COBOL {@code WS-EDIT-VARIABLE-NAME};
     * it is trimmed and substituted into the outcome message.
     */
    private static final String FIELD = "State";

    /** System under test: stateless, so a single plainly-constructed instance is reused. */
    private final UsStateCodeRule rule = new UsStateCodeRule();

    // ---------------------------------------------------------------------------------------------
    // validate(...) — the 1270-EDIT-US-STATE-CD paragraph behavior
    // ---------------------------------------------------------------------------------------------

    /** A recognized 50-state code ({@code CA}) passes with the empty (no-error) message. */
    @Test
    void validate_recognizedState_isValidWithEmptyMessage() {
        ValidationResult result = rule.validate(FIELD, "CA");

        assertThat(result.isValid()).as("CA is a recognized state code").isTrue();
        assertThat(result.message()).as("a valid result carries the empty message").isEmpty();
    }

    /** A recognized territory code ({@code PR}) passes, confirming territories are members. */
    @Test
    void validate_recognizedTerritory_isValid() {
        ValidationResult result = rule.validate(FIELD, "PR");

        assertThat(result.isValid()).as("PR (Puerto Rico) is a recognized territory").isTrue();
        assertThat(result.message()).isEmpty();
    }

    /** An unknown code ({@code XX}) fails with the verbatim COBOL state-code message. */
    @Test
    void validate_unknownCode_isInvalidWithStateMessage() {
        ValidationResult result = rule.validate(FIELD, "XX");

        assertThat(result.isInvalid()).as("XX is not a member of VALID-US-STATE-CODE").isTrue();
        assertThat(result.message()).isEqualTo(STATE_MESSAGE);
    }

    /**
     * A lower-case code ({@code ca}) fails: the COBOL 88-level values are upper-case and the rule
     * deliberately does not upper-case its input, so {@code "ca"} is not a member (case parity).
     */
    @Test
    void validate_lowerCaseCode_isInvalid_caseParity() {
        ValidationResult result = rule.validate(FIELD, "ca");

        assertThat(result.isInvalid())
                .as("lower-case 'ca' is not a member; input is never upper-cased")
                .isTrue();
        assertThat(result.message()).isEqualTo(STATE_MESSAGE);
    }

    /** A {@code null} value (COBOL {@code LOW-VALUES}) is not a member and fails with the message. */
    @Test
    void validate_nullValue_isInvalidWithStateMessage() {
        ValidationResult result = rule.validate(FIELD, null);

        assertThat(result.isInvalid()).as("null models COBOL LOW-VALUES and is not a member").isTrue();
        assertThat(result.message()).isEqualTo(STATE_MESSAGE);
    }

    /** An empty value is not a member and fails with the message. */
    @Test
    void validate_emptyValue_isInvalidWithStateMessage() {
        ValidationResult result = rule.validate(FIELD, "");

        assertThat(result.isInvalid()).as("an empty value is not a member").isTrue();
        assertThat(result.message()).isEqualTo(STATE_MESSAGE);
    }

    /** An all-spaces value (COBOL {@code SPACES}) is not a member and fails with the message. */
    @Test
    void validate_blankSpaces_isInvalidWithStateMessage() {
        ValidationResult result = rule.validate(FIELD, "   ");

        assertThat(result.isInvalid()).as("a spaces value is simply not a member").isTrue();
        assertThat(result.message()).isEqualTo(STATE_MESSAGE);
    }

    /**
     * A padded value ({@code " CA "}) is stripped before the comparison and then recognized,
     * mirroring the fixed-width buffer slice the legacy program compares (documented behavior of
     * {@link UsStateCodeRule#isValidStateCode(String)}); the two significant characters are still
     * matched verbatim.
     */
    @Test
    void validate_paddedValue_isStrippedThenValid() {
        ValidationResult result = rule.validate(FIELD, " CA ");

        assertThat(result.isValid()).as("surrounding whitespace is stripped before matching").isTrue();
        assertThat(result.message()).isEmpty();
    }

    /**
     * The field label is trimmed before it is substituted into the failure message, reproducing
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} of the COBOL {@code STRING} statement: a padded
     * label {@code "  State  "} still yields exactly {@code "State: is not a valid state code"}.
     */
    @Test
    void validate_fieldLabelIsTrimmedIntoMessage() {
        ValidationResult result = rule.validate("  State  ", "XX");

        assertThat(result.message()).isEqualTo(STATE_MESSAGE);
    }

    // ---------------------------------------------------------------------------------------------
    // isValidStateCode(...) — the reusable 88 VALID-US-STATE-CODE membership predicate
    // ---------------------------------------------------------------------------------------------

    /**
     * A representative member from each block of the copybook list &mdash; the first state
     * ({@code AL}), interior states ({@code KS}, {@code NM}, {@code SC}), the last state
     * ({@code WY}), the District of Columbia ({@code DC}), and all five territories ({@code AS},
     * {@code GU}, {@code MP}, {@code PR}, {@code VI}) &mdash; is recognized.
     */
    @Test
    void isValidStateCode_representativeMembersAcrossAllBlocks_areValid() {
        assertThat(rule.isValidStateCode("AL")).as("AL is the first copybook code").isTrue();
        assertThat(rule.isValidStateCode("KS")).as("KS is an interior state code").isTrue();
        assertThat(rule.isValidStateCode("NM")).as("NM is an interior state code").isTrue();
        assertThat(rule.isValidStateCode("SC")).as("SC is an interior state code").isTrue();
        assertThat(rule.isValidStateCode("WY")).as("WY is the last state code").isTrue();
        assertThat(rule.isValidStateCode("DC")).as("District of Columbia is recognized").isTrue();
        assertThat(rule.isValidStateCode("AS")).as("American Samoa is recognized").isTrue();
        assertThat(rule.isValidStateCode("GU")).as("Guam is recognized").isTrue();
        assertThat(rule.isValidStateCode("MP")).as("Northern Mariana Islands is recognized").isTrue();
        assertThat(rule.isValidStateCode("PR")).as("Puerto Rico is recognized").isTrue();
        assertThat(rule.isValidStateCode("VI")).as("US Virgin Islands is recognized").isTrue();
    }

    /**
     * Near-miss and not-supplied values are all rejected: unknown codes ({@code XX}, {@code ZZ}),
     * a lower-case code ({@code ca}, case parity), an all-spaces value, and {@code null}.
     */
    @Test
    void isValidStateCode_nearMissesAndNonMembers_areInvalid() {
        assertThat(rule.isValidStateCode("XX")).as("XX is not a member").isFalse();
        assertThat(rule.isValidStateCode("ZZ")).as("ZZ is not a member").isFalse();
        assertThat(rule.isValidStateCode("ca")).as("lower-case is not a member (case-sensitive)").isFalse();
        assertThat(rule.isValidStateCode("")).as("empty is not a member").isFalse();
        assertThat(rule.isValidStateCode("   ")).as("blank is not a member").isFalse();
        assertThat(rule.isValidStateCode(null)).as("null is not a member").isFalse();
    }

    /**
     * The recognized set contains exactly 56 codes (50 states + DC + 5 territories), matching the
     * {@code 88 VALID-US-STATE-CODE} enumeration in {@code legacy/cpy/CSLKPCDY.cpy}. This is proven
     * through the public {@link UsStateCodeRule#isValidStateCode(String)} predicate rather than by
     * reflecting into the private set: because every valid code is a two-character upper-case
     * string, probing all 26&times;26 = 676 two-upper-case-letter combinations must accept exactly
     * 56 of them &mdash; asserting both the cardinality and that no unexpected code is a member.
     */
    @Test
    void validStateCodeSet_hasExactlyFiftySixMembers() {
        int recognized = 0;
        for (char first = 'A'; first <= 'Z'; first++) {
            for (char second = 'A'; second <= 'Z'; second++) {
                if (rule.isValidStateCode("" + first + second)) {
                    recognized++;
                }
            }
        }

        assertThat(recognized)
                .as("VALID-US-STATE-CODE defines exactly 56 members "
                        + "(50 states + DC + 5 territories) in legacy/cpy/CSLKPCDY.cpy")
                .isEqualTo(56);
    }
}

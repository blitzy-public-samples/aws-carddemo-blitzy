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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationResult}, the immutable {@code (flag, message)} outcome value
 * type returned by every rule component in the {@code service.rule} package. It is the Java
 * abstraction of the COBOL {@code INPUT-ERROR} flag plus the {@code WS-RETURN-MSG} screen message
 * used throughout the legacy online-edit programs (see {@code legacy/cbl/COACTUPC.cbl},
 * source-branch {@code app/cbl/COACTUPC.cbl}).
 *
 * <p>COBOL evidence (verified against {@code app/cbl/COACTUPC.cbl}):
 * <ul>
 *   <li>{@code INPUT-ERROR} &mdash; the 88-level {@code VALUE '1'} error flag at L173, set by a
 *       failing field edit via {@code SET INPUT-ERROR TO TRUE}.</li>
 *   <li>{@code WS-RETURN-MSG} &mdash; the {@code PIC X(75)} screen message at L479, with the
 *       {@code WS-RETURN-MSG-OFF} ({@code VALUE SPACES}) first-message-wins guard at L480.</li>
 *   <li>{@code 1215-EDIT-MANDATORY} (L1824) builds the mandatory-field message
 *       {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' must be supplied.' ... INTO
 *       WS-RETURN-MSG} at L1839-1843 &mdash; the origin of the representative
 *       {@code "Account Id must be supplied."} literal exercised below.</li>
 * </ul>
 *
 * <p>These tests lock down the value-type contract on which the whole {@code service.rule}
 * package depends (AAP &sect;0.6.4 import discipline, &sect;0.9 validation criteria): the
 * {@link ValidationResult#valid()} / {@link ValidationResult#invalid(String)} factories, the
 * non-{@code null}-message invariant of an invalid result, the empty-string ({@code ""}) message
 * of a valid result, the cached valid singleton, value-based {@code equals}/{@code hashCode}, and
 * the log-safe {@code toString}. This is a pure JUnit&nbsp;5 + AssertJ unit test &mdash; no Spring
 * context, no Mockito, and no database.
 */
class ValidationResultTest {

    /**
     * A representative failure message. It is the exact text the legacy {@code 1215-EDIT-MANDATORY}
     * paragraph emits when the account-ID mandatory edit fails ({@code WS-EDIT-VARIABLE-NAME} =
     * {@code "Account Id"}), i.e. a field label followed by {@code " must be supplied."}. A rule
     * message describes the offending field by label only and never carries a raw sensitive value.
     */
    private static final String ACCOUNT_ID_REQUIRED = "Account Id must be supplied.";

    @Test
    @DisplayName("valid() passes, is not invalid, and carries the empty (never-null) message")
    void valid_isValidAndHasEmptyMessage() {
        ValidationResult r = ValidationResult.valid();

        assertThat(r.isValid()).isTrue();
        assertThat(r.isInvalid()).isFalse();
        assertThat(r.message()).isEqualTo("");
    }

    @Test
    @DisplayName("invalid(msg) fails, is invalid, and carries the supplied message verbatim")
    void invalid_isInvalidAndCarriesMessage() {
        ValidationResult r = ValidationResult.invalid(ACCOUNT_ID_REQUIRED);

        assertThat(r.isValid()).isFalse();
        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message()).isEqualTo(ACCOUNT_ID_REQUIRED);
    }

    @Test
    @DisplayName("invalid(null) throws NullPointerException — an invalid result must carry a message")
    void invalidNull_throwsNpe() {
        assertThatThrownBy(() -> ValidationResult.invalid(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("valid() returns the same cached singleton on every call")
    void valid_returnsCachedSingleton() {
        assertThat(ValidationResult.valid()).isSameAs(ValidationResult.valid());
    }

    @Test
    @DisplayName("invalid results with equal messages are equal and share a hash code")
    void equalMessages_areEqualAndShareHashCode() {
        ValidationResult a = ValidationResult.invalid("X");
        ValidationResult b = ValidationResult.invalid("X");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("results differ when messages differ, and a valid result never equals an invalid one")
    void unequalResults_areNotEqual() {
        assertThat(ValidationResult.invalid("X")).isNotEqualTo(ValidationResult.invalid("Y"));
        assertThat(ValidationResult.valid()).isNotEqualTo(ValidationResult.invalid("X"));
    }

    @Test
    @DisplayName("toString() is non-null and contains the message text (log-safe, label-only)")
    void toString_isNonNullAndContainsMessage() {
        String rendered = ValidationResult.invalid("X").toString();

        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("X");
    }
}

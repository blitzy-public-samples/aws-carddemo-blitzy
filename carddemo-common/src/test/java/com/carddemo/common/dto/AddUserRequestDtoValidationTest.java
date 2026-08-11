/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * :purpose: Pin the user-id character set on the ONE path that can create a security user
 *     (``POST /users``, legacy ``COUSR01C`` / CICS ``CU01``). ``SEC-USR-ID`` is ``PIC X(08)`` on
 *     a VSAM key fed by an EBCDIC 3270 terminal, so an id outside A-Z / 0-9 was not
 *     representable on the mainframe. Unrestricted, the migrated screen accepted ``U+0410
 *     CYRILLIC CAPITAL A`` + ``DMIN001`` and granted it ``ROLE_ADMIN``: a distinct principal that
 *     renders identically to the legitimate ``ADMIN001`` in every audit record, panel and report
 *     (CWE-1007).
 * :output: Assertions that the homoglyph and other non-alphanumeric ids are refused, that
 *     legitimate ids pass, that the legacy presence and width edits keep their own messages, and
 *     that the single-byte edit refuses text the downstream fixed-width record cannot represent.
 */
@DisplayName("AddUserRequestDto — user id character set")
class AddUserRequestDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static AddUserRequestDto withUserId(String userId) {
        AddUserRequestDto dto = new AddUserRequestDto();
        dto.setUserId(userId);
        dto.setFirstName("FIRST");
        dto.setLastName("LAST");
        dto.setUserType("A");
        dto.setPassword("PASSWORD");
        return dto;
    }

    private static Set<ConstraintViolation<AddUserRequestDto>> violationsFor(String userId) {
        return validator.validate(withUserId(userId));
    }

    @Test
    @DisplayName("the Cyrillic homoglyph administrator id is refused")
    void refusesTheCyrillicHomoglyphId() {
        // U+0410 CYRILLIC CAPITAL LETTER A followed by "DMIN001" — length 8, so the width edit
        // never fires. Two independent edits claim it: the character set, and the single-byte
        // edit that keeps the value representable on the fixed-width record interface. Both
        // name userId, and the character-set message is the one the screen shows.
        Set<ConstraintViolation<AddUserRequestDto>> violations = violationsFor("\u0410DMIN001");

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder(
                        "User ID must contain only letters and digits",
                        "must contain only characters the downstream fixed-width record interface"
                                + " can represent (ISO-8859-1)");
        assertThat(violations)
                .allSatisfy(violation ->
                        assertThat(violation.getPropertyPath()).hasToString("userId"));
    }

    @Test
    @DisplayName("a name outside ISO-8859-1 is refused by the single-byte edit alone, naming its own field")
    void refusesASingleByteUnrepresentableName() {
        // firstName carries only the field-width edit, so U+0100 LATIN CAPITAL A WITH MACRON --
        // twenty characters or fewer and outside Latin-1 -- is refused by nothing but the
        // single-byte edit. Unrefused it reaches CobolRecordFormatter and becomes a substitute
        // byte in the downstream fixed-width record.
        AddUserRequestDto dto = withUserId("ADMIN001");
        dto.setFirstName("\u0100LEX");

        Set<ConstraintViolation<AddUserRequestDto>> violations = validator.validate(dto);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must contain only characters the downstream fixed-width record"
                        + " interface can represent (ISO-8859-1)");
        assertThat(violations.iterator().next().getPropertyPath()).hasToString("firstName");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\uFF21DMIN001",   // fullwidth Latin A
            "ADMIN00\u00A0",   // no-break space
            "ADMIN-01",        // punctuation
            "ADMIN_01",
            "ADMIN 01",        // interior blank
            "ADM\u0130N001",   // Turkish dotted capital I
            "\u0410\u0414\u041CIN001",
            "<script>"})
    @DisplayName("every non-alphanumeric id shape is refused")
    void refusesEveryNonAlphanumericShape(String userId) {
        assertThat(violationsFor(userId))
                .extracting(ConstraintViolation::getMessage)
                .contains("User ID must contain only letters and digits");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN001", "admin001", "AdMiN002", "USER0001", "A", "12345678", "ADMIN1  "})
    @DisplayName("legitimate ids pass, including the lower-case and blank-padded forms the normalizer folds")
    void acceptsLegitimateIds(String userId) {
        assertThat(violationsFor(userId)).isEmpty();
    }

    @Test
    @DisplayName("a blank-padded id still reports only the width message, as it did before")
    void blankPaddedIdStillReportsOnlyTheWidthMessage() {
        // "ADMIN001 " is nine characters, so the field-width edit refuses it exactly as it did
        // before the character set was restricted -- the new pattern deliberately tolerates
        // surrounding blanks so it cannot claim this case with a different message.
        assertThat(violationsFor("ADMIN001 "))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("User ID must be at most 8 characters");
    }

    @Test
    @DisplayName("an absent or blank id leaves the legacy presence edit to the service")
    void leavesPresenceEditToTheService() {
        // The verbatim COUSR01C literal "User ID can NOT be empty..." is reported by UserService,
        // so bean validation must NOT claim these cases with a different message.
        assertThat(violationsFor(null)).isEmpty();
        assertThat(violationsFor("")).isEmpty();
        assertThat(violationsFor("   ")).isEmpty();
    }

    @Test
    @DisplayName("an over-length id still reports the width message")
    void overLengthIdStillReportsTheWidthMessage() {
        assertThat(violationsFor("ADMIN0012"))
                .extracting(ConstraintViolation::getMessage)
                .contains("User ID must be at most 8 characters");
    }
}

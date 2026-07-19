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
package com.aws.carddemo.util.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link ScreenAttributes}, verifying that the migrated BMS
 * field display-attribute tokens preserve the legacy 3270 field-highlight
 * contract with zero feature expansion.
 *
 * <p>Parity oracle: {@code legacy/cpy/CSSETATY.cpy} (BMS set-attribute macro).
 * The macro turns an errored or blank field red ({@code MOVE DFHRED}) on
 * program re-entry and stamps a blank field with the marker character
 * ({@code MOVE '*'}). Exactly one color ({@code DFHRED}) and one marker
 * ({@code '*'}) are defined by the copybook, so these tests assert precisely
 * that mapping and deliberately introduce no additional colors or
 * attributes.</p>
 *
 * <p>This is a pure, dependency-free JUnit 5 unit test: it starts no Spring
 * context and touches no database, exercising only the compile-time constants
 * exposed by {@link ScreenAttributes} (same package, so no import of the
 * class under test is required). The {@code CDEMO-PGM-REENTER} re-entry guard
 * referenced by the copybook is owned by
 * {@code com.aws.carddemo.dto.CardDemoContext} and is intentionally not
 * exercised here.</p>
 */
class ScreenAttributesTest {

    /**
     * The error/blank highlight attribute must be the BMS symbolic color
     * {@code DFHRED}, migrated verbatim from the copybook statement
     * {@code MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O} in
     * {@code legacy/cpy/CSSETATY.cpy}.
     */
    @Test
    @DisplayName("ERROR_COLOR_ATTRIBUTE is the BMS DFHRED symbol")
    void errorColorAttributeIsDfhred() {
        assertEquals("DFHRED", ScreenAttributes.ERROR_COLOR_ATTRIBUTE);
    }

    /**
     * The {@code DFHRED} 3270 color must render for the browser as the CSS
     * color {@code red}, so the Thymeleaf screen migration reproduces the
     * legacy error/blank field highlight.
     */
    @Test
    @DisplayName("ERROR_COLOR_CSS renders DFHRED as CSS red")
    void errorColorCssIsRed() {
        assertEquals("red", ScreenAttributes.ERROR_COLOR_CSS);
    }

    /**
     * The blank-field marker must be the {@code '*'} character, migrated from
     * the copybook statement {@code MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O} in
     * {@code legacy/cpy/CSSETATY.cpy}. The production constant is a
     * {@code char}, so it is compared against the {@code '*'} char literal and
     * not the {@code "*"} String.
     */
    @Test
    @DisplayName("BLANK_FIELD_MARKER is the '*' char")
    void blankFieldMarkerIsAsterisk() {
        assertEquals('*', ScreenAttributes.BLANK_FIELD_MARKER);
    }

    /**
     * Preservation guard (AAP 0.2.2 - no feature expansion): the copybook
     * defines exactly one error color, so the migrated attribute must remain
     * {@code DFHRED} and must not have been broadened into any other BMS
     * extended-color symbol. The comparisons use string literals only and
     * therefore reference no additional production constants.
     */
    @Test
    @DisplayName("ERROR_COLOR_ATTRIBUTE is only DFHRED, no other BMS colors")
    void errorColorIsOnlyDfhredNoOtherColors() {
        assertEquals("DFHRED", ScreenAttributes.ERROR_COLOR_ATTRIBUTE);
        assertNotEquals("DFHBLUE", ScreenAttributes.ERROR_COLOR_ATTRIBUTE);
        assertNotEquals("DFHGREEN", ScreenAttributes.ERROR_COLOR_ATTRIBUTE);
        assertNotEquals("DFHTURQ", ScreenAttributes.ERROR_COLOR_ATTRIBUTE);
        assertNotEquals("DFHYELLO", ScreenAttributes.ERROR_COLOR_ATTRIBUTE);
        assertNotEquals("DFHNEUTR", ScreenAttributes.ERROR_COLOR_ATTRIBUTE);
        assertNotEquals("DFHPINK", ScreenAttributes.ERROR_COLOR_ATTRIBUTE);
    }
}

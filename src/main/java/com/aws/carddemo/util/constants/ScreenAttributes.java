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

/**
 * BMS field display-attribute tokens migrated from the CICS/BMS
 * {@code CSSETATY} "set attribute" macro, exposed as dependency-free style
 * constants for the Thymeleaf screen migration of the legacy BMS maps.
 *
 * <p>Origin: {@code legacy/cpy/CSSETATY.cpy} (BMS set-attribute template).</p>
 *
 * <p>The legacy macro highlights a screen field when it is in error
 * ({@code FLG-*-NOT-OK}) or blank ({@code FLG-*-BLANK}) <em>and</em> the
 * program is being re-entered ({@code CDEMO-PGM-REENTER}): it moves
 * {@code DFHRED} into the field's symbolic-map color byte and, when the field
 * is blank, additionally moves {@code '*'} into the field's symbolic-map
 * output byte. This class captures those two literal tokens so the migrated
 * web tier can reproduce the identical 3270 field-attribute contract:</p>
 * <ul>
 *   <li>{@link #ERROR_COLOR_ATTRIBUTE} ({@code DFHRED}) is the error/blank
 *       highlight, rendered for the browser as CSS {@link #ERROR_COLOR_CSS}
 *       ({@code red}).</li>
 *   <li>{@link #BLANK_FIELD_MARKER} ({@code '*'}) is the marker stamped into
 *       a blank field.</li>
 * </ul>
 *
 * <p>The highlight is applied only when a field is in error or blank
 * <em>and</em> the program is being re-entered. That re-entry state is owned
 * by the session context {@code com.aws.carddemo.dto.CardDemoContext} (the
 * migration of the {@code COCOM01Y} COMMAREA {@code CDEMO-PGM-REENTER} flag)
 * and is deliberately not redeclared here, preserving a single source of
 * truth for re-entry.</p>
 *
 * <p>Only the two tokens the {@code CSSETATY} macro references
 * ({@code DFHRED} and {@code '*'}) are modelled; no additional colors or
 * default attributes are introduced.</p>
 */
public final class ScreenAttributes {

    /** BMS {@code DFHRED} symbolic extended-color attribute; error/blank field highlight (CSSETATY). */
    public static final String ERROR_COLOR_ATTRIBUTE = "DFHRED";

    /** CSS rendering of {@code DFHRED} for the Thymeleaf screen migration (CSSETATY). */
    public static final String ERROR_COLOR_CSS = "red";

    /** Blank-field marker character stamped by the COBOL {@code MOVE '*'} (CSSETATY). */
    public static final char BLANK_FIELD_MARKER = '*';

    /** String form of {@link #BLANK_FIELD_MARKER} for convenient Thymeleaf template use (CSSETATY). */
    public static final String BLANK_FIELD_MARKER_STR = "*";

    /**
     * Prevents instantiation of this constants holder.
     *
     * @throws AssertionError always, because this class exposes only static tokens
     */
    private ScreenAttributes() {
        throw new AssertionError("No instances");
    }
}

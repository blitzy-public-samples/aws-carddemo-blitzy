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
package com.cardemo.common.util;

/**
 * Translated from CSSETATY.cpy — Screen attribute mapping preserved as validation-state helpers.
 *
 * <p>The original COBOL copybook {@code CSSETATY.cpy} (CardDemo_v1.0) is a parameterized
 * COPY-REPLACE macro template that applies DFHRED (red) screen attribute markers on BMS
 * map fields when a validation flag indicates an error condition and the program is in
 * re-enter mode. This Java utility class faithfully reproduces that conditional logic
 * as static helper methods suitable for a headless service layer.</p>
 *
 * <h3>COBOL Source Mapping (CSSETATY.cpy lines 17–27)</h3>
 * <pre>
 * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK)
 *    AND CDEMO-PGM-REENTER
 *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
 *     IF FLG-(TESTVAR1)-BLANK
 *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 *     END-IF
 * END-IF
 * </pre>
 *
 * <p>The three placeholders {@code (TESTVAR1)}, {@code (SCRNVAR2)}, {@code (MAPNAME3)} are
 * replaced at COBOL compile time via {@code COPY ... REPLACING}. In Java, callers pass
 * the field's current {@link FieldStatus} and the program re-enter flag directly.</p>
 *
 * @see FieldStatus
 * @see FieldAttribute
 */
public final class AttributeUtil {

    /**
     * Private constructor prevents instantiation of this utility class.
     */
    private AttributeUtil() {
        // Utility class — not instantiable
    }

    // -------------------------------------------------------------------------
    // Nested Enums
    // -------------------------------------------------------------------------

    /**
     * Validation status of an individual field, translated from COBOL 88-level conditions.
     *
     * <p>In the original COBOL source, each validated field has a corresponding flag variable
     * with three 88-level conditions:</p>
     * <ul>
     *   <li>{@code FLG-xxx-ISVALID} → {@link #VALID}</li>
     *   <li>{@code FLG-xxx-NOT-OK}  → {@link #NOT_OK}</li>
     *   <li>{@code FLG-xxx-BLANK}   → {@link #BLANK}</li>
     * </ul>
     */
    public enum FieldStatus {

        /**
         * The field passed validation (COBOL: {@code FLG-xxx-ISVALID}).
         */
        VALID,

        /**
         * The field failed validation with an invalid value (COBOL: {@code FLG-xxx-NOT-OK}).
         */
        NOT_OK,

        /**
         * The field was left blank / empty (COBOL: {@code FLG-xxx-BLANK}).
         */
        BLANK
    }

    /**
     * Display attribute to apply to a field, translated from BMS screen attribute constants.
     *
     * <p>In the COBOL source, {@code DFHRED} is a BMS attribute byte that renders a field
     * in red on a 3270 terminal. In this headless Java translation the attribute is preserved
     * as an enum value so that downstream layers (API responses, UI adapters) can map it to
     * appropriate visual indicators.</p>
     */
    public enum FieldAttribute {

        /**
         * Normal display attribute — no error highlighting (default when field is valid).
         */
        NORMAL,

        // DFHRED → ERROR_RED attribute (BMS red highlighting)
        /**
         * Error display attribute — corresponds to DFHRED (red highlighting for error fields).
         */
        ERROR_RED
    }

    // -------------------------------------------------------------------------
    // Core Methods
    // -------------------------------------------------------------------------

    /**
     * Determines the display attribute for a field based on its validation status and
     * the program's re-enter flag.
     *
     * <p>This faithfully reproduces the <em>outer</em> IF condition from
     * {@code CSSETATY.cpy} (lines 18–22):</p>
     * <pre>
     * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK)
     *    AND CDEMO-PGM-REENTER
     *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
     * </pre>
     *
     * @param fieldStatus    the validation status of the field; {@code null} is treated as
     *                       {@link FieldStatus#VALID}
     * @param programReenter {@code true} if the program is in re-enter mode
     *                       (COBOL: {@code CDEMO-PGM-REENTER})
     * @return {@link FieldAttribute#ERROR_RED} when the field is in error and the program
     *         is re-entering; {@link FieldAttribute#NORMAL} otherwise
     */
    public static FieldAttribute determineFieldAttribute(FieldStatus fieldStatus,
                                                         boolean programReenter) {
        if (isFieldInError(fieldStatus, programReenter)) {
            return FieldAttribute.ERROR_RED;
        }
        return FieldAttribute.NORMAL;
    }

    /**
     * Determines the display value for a field based on its validation status and
     * the program's re-enter flag.
     *
     * <p>This reproduces the <em>inner</em> IF within {@code CSSETATY.cpy} (lines 23–25):</p>
     * <pre>
     * IF FLG-(TESTVAR1)-BLANK
     *     MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
     * END-IF
     * </pre>
     *
     * <p>When the field is blank and in error during re-entry, the display value is replaced
     * with a single asterisk ({@code "*"}) to visually indicate a required field that was
     * left empty — matching the original COBOL behavior.</p>
     *
     * // MOVE '*' → placeholder for blank required fields
     *
     * @param currentValue   the current display value of the field; may be {@code null}
     * @param fieldStatus    the validation status of the field; {@code null} is treated as
     *                       {@link FieldStatus#VALID}
     * @param programReenter {@code true} if the program is in re-enter mode
     *                       (COBOL: {@code CDEMO-PGM-REENTER})
     * @return {@code "*"} if the field is blank and in error during re-entry;
     *         the original {@code currentValue} (or empty string if {@code null})
     *         in all other cases
     */
    public static String determineFieldDisplayValue(String currentValue,
                                                    FieldStatus fieldStatus,
                                                    boolean programReenter) {
        if (isFieldInError(fieldStatus, programReenter)) {
            // Inner IF: BLANK fields get the '*' placeholder marker
            if (fieldStatus == FieldStatus.BLANK) {
                // MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
                return "*";
            }
            // NOT_OK fields retain their current value for correction
            return currentValue != null ? currentValue : "";
        }
        // Field is not in error — return value unchanged
        return currentValue != null ? currentValue : "";
    }

    /**
     * Convenience method to test whether a field is in an error state during program re-entry.
     *
     * <p>Evaluates the combined condition from {@code CSSETATY.cpy} (lines 18–20):</p>
     * <pre>
     * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK)
     *    AND CDEMO-PGM-REENTER
     * </pre>
     *
     * @param fieldStatus    the validation status of the field; {@code null} is treated as
     *                       {@link FieldStatus#VALID} (no error)
     * @param programReenter {@code true} if the program is in re-enter mode
     *                       (COBOL: {@code CDEMO-PGM-REENTER})
     * @return {@code true} if the field status is {@link FieldStatus#NOT_OK} or
     *         {@link FieldStatus#BLANK} and the program is in re-enter mode;
     *         {@code false} otherwise
     */
    public static boolean isFieldInError(FieldStatus fieldStatus, boolean programReenter) {
        if (fieldStatus == null) {
            return false;
        }
        return (fieldStatus == FieldStatus.NOT_OK || fieldStatus == FieldStatus.BLANK)
                && programReenter;
    }
}

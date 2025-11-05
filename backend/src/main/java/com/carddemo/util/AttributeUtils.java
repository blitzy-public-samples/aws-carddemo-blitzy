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

package com.carddemo.util;

import java.util.Objects;

/**
 * AttributeUtils provides utility methods for managing BMS screen field attributes
 * transformed from COBOL CSSETATY copybook logic.
 * 
 * This class handles the transformation of COBOL field attribute setting patterns
 * into Java utility methods that support React component field styling and validation
 * feedback. It preserves the COBOL conditional error marking behavior where fields
 * are highlighted in red with asterisk markers when blank or invalid during screen re-entry.
 * 
 * COBOL Source Pattern (CSSETATY.cpy):
 * <pre>
 * IF (FLG-field-NOT-OK OR FLG-field-BLANK) AND CDEMO-PGM-REENTER
 *     MOVE DFHRED TO fieldC OF mapO
 *     IF FLG-field-BLANK
 *         MOVE '*' TO fieldO OF mapO
 *     END-IF
 * END-IF
 * </pre>
 * 
 * Java Equivalent Usage:
 * <pre>
 * FieldAttribute attr = AttributeUtils.createFieldAttribute(
 *     isNotOk,      // FLG-field-NOT-OK
 *     isBlank,      // FLG-field-BLANK
 *     isReenter     // CDEMO-PGM-REENTER
 * );
 * 
 * // In DTO/Response:
 * response.setFieldCssClass(attr.getCssClass());
 * response.setFieldColor(attr.getColorAttribute());
 * response.setShowMarker(attr.showErrorMarker());
 * </pre>
 * 
 * BMS Attribute Mapping:
 * <ul>
 *   <li>DFHRED (red color) → CSS class "field-error" or colorAttribute "RED"</li>
 *   <li>DFHPROT (protected) → isProtected = true, disabled in React</li>
 *   <li>DFHUNPROT (unprotected) → isProtected = false, enabled in React</li>
 *   <li>DFHBRT (bright/highlighted) → isHighlighted = true</li>
 *   <li>IC (initial cursor) → hasInitialCursor = true, autoFocus in React</li>
 *   <li>Asterisk marker (*) → showErrorMarker = true for blank fields</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
public final class AttributeUtils {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private AttributeUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Creates a FieldAttribute object with complete attribute state based on
     * COBOL conditional error marking logic from CSSETATY.cpy.
     * 
     * This method implements the COBOL pattern:
     * IF (FLG-NOT-OK OR FLG-BLANK) AND CDEMO-PGM-REENTER
     * 
     * @param isNotOk corresponds to FLG-field-NOT-OK flag indicating validation error
     * @param isBlank corresponds to FLG-field-BLANK flag indicating empty field
     * @param isReenter corresponds to CDEMO-PGM-REENTER flag indicating screen re-entry
     * @return FieldAttribute object with error, protection, and highlighting state
     */
    public static FieldAttribute createFieldAttribute(boolean isNotOk, boolean isBlank, boolean isReenter) {
        Objects.requireNonNull(Boolean.valueOf(isNotOk), "isNotOk flag cannot be null");
        Objects.requireNonNull(Boolean.valueOf(isBlank), "isBlank flag cannot be null");
        Objects.requireNonNull(Boolean.valueOf(isReenter), "isReenter flag cannot be null");
        
        // Apply COBOL conditional logic: (FLG-NOT-OK OR FLG-BLANK) AND CDEMO-PGM-REENTER
        boolean shouldHighlight = (isNotOk || isBlank) && isReenter;
        boolean shouldShowMarker = isBlank && isReenter;
        
        return new FieldAttribute(shouldHighlight, false, shouldHighlight, false, shouldShowMarker);
    }

    /**
     * Creates a FieldAttribute for an error state field.
     * Fields in error state are highlighted in red (DFHRED equivalent).
     * 
     * @return FieldAttribute with error highlighting enabled
     */
    public static FieldAttribute createErrorAttribute() {
        return new FieldAttribute(true, false, true, false, false);
    }

    /**
     * Creates a FieldAttribute for a normal state field.
     * Normal fields have no special highlighting or protection.
     * 
     * @return FieldAttribute with all flags set to false (normal state)
     */
    public static FieldAttribute createNormalAttribute() {
        return new FieldAttribute(false, false, false, false, false);
    }

    /**
     * Creates a FieldAttribute for a protected field.
     * Protected fields correspond to DFHPROT BMS attribute (read-only, disabled in React).
     * 
     * @return FieldAttribute with protection enabled
     */
    public static FieldAttribute createProtectedAttribute() {
        return new FieldAttribute(false, true, false, false, false);
    }

    /**
     * Creates a FieldAttribute for a highlighted field.
     * Highlighted fields correspond to DFHBRT BMS attribute (bright display).
     * 
     * @return FieldAttribute with highlighting enabled
     */
    public static FieldAttribute createHighlightedAttribute() {
        return new FieldAttribute(false, false, true, false, false);
    }

    /**
     * Creates a FieldAttribute for a field that should receive initial cursor focus.
     * Corresponds to IC BMS attribute (initial cursor positioning).
     * 
     * @return FieldAttribute with initial cursor flag enabled
     */
    public static FieldAttribute createInitialCursorAttribute() {
        return new FieldAttribute(false, false, false, true, false);
    }

    /**
     * Determines if error marker (asterisk '*') should be displayed based on COBOL logic.
     * 
     * COBOL Source: IF FLG-field-BLANK MOVE '*' TO fieldO
     * 
     * @param isBlank indicates if field is blank (FLG-field-BLANK)
     * @param isReenter indicates if screen is in re-entry mode (CDEMO-PGM-REENTER)
     * @return true if asterisk marker should be shown, false otherwise
     */
    public static boolean shouldShowErrorMarker(boolean isBlank, boolean isReenter) {
        return isBlank && isReenter;
    }

    /**
     * Determines if field should be highlighted based on COBOL conditional logic.
     * 
     * COBOL Source: IF (FLG-NOT-OK OR FLG-BLANK) AND CDEMO-PGM-REENTER
     * 
     * @param isNotOk indicates validation error (FLG-field-NOT-OK)
     * @param isBlank indicates empty field (FLG-field-BLANK)
     * @param isReenter indicates screen re-entry (CDEMO-PGM-REENTER)
     * @return true if field should be highlighted in red, false otherwise
     */
    public static boolean shouldHighlightField(boolean isNotOk, boolean isBlank, boolean isReenter) {
        return (isNotOk || isBlank) && isReenter;
    }

    /**
     * Returns CSS class name for field based on error state and highlighting requirements.
     * Used by React components to apply appropriate styling.
     * 
     * CSS Classes:
     * - "field-error" → red highlighting (DFHRED equivalent)
     * - "field-protected" → read-only styling (DFHPROT equivalent)
     * - "field-highlighted" → bright display (DFHBRT equivalent)
     * - "field-normal" → default styling
     * - "field-error-protected" → combination of error and protected
     * 
     * @param isError indicates field is in error state
     * @param isProtected indicates field is protected (read-only)
     * @param isHighlighted indicates field should be highlighted
     * @return CSS class name string for React component className prop
     */
    public static String getFieldCssClass(boolean isError, boolean isProtected, boolean isHighlighted) {
        if (isError && isProtected) {
            return "field-error-protected";
        } else if (isError || isHighlighted) {
            return "field-error";
        } else if (isProtected) {
            return "field-protected";
        } else {
            return "field-normal";
        }
    }

    /**
     * Checks if field is in error state based on COBOL error flags.
     * 
     * @param isNotOk indicates validation error (FLG-field-NOT-OK)
     * @param isBlank indicates empty field when required (FLG-field-BLANK)
     * @return true if field has validation error or is blank, false otherwise
     */
    public static boolean isFieldInError(boolean isNotOk, boolean isBlank) {
        return isNotOk || isBlank;
    }

    /**
     * Checks if field is protected (read-only).
     * 
     * @param isProtected protection flag corresponding to DFHPROT attribute
     * @return true if field is protected, false otherwise
     */
    public static boolean isFieldProtected(boolean isProtected) {
        return isProtected;
    }

    /**
     * FieldAttribute represents the complete attribute state of a BMS screen field.
     * This immutable class encapsulates all field attributes that can be set in
     * COBOL BMS mapsets and provides methods to query and export these attributes
     * for use in REST API responses consumed by React components.
     * 
     * Attribute Mapping:
     * <ul>
     *   <li>isError → field has validation error (DFHRED color should be applied)</li>
     *   <li>isProtected → field is read-only (DFHPROT attribute)</li>
     *   <li>isHighlighted → field should be bright (DFHBRT attribute)</li>
     *   <li>hasInitialCursor → cursor should start here (IC attribute)</li>
     *   <li>showErrorMarker → asterisk (*) should be displayed for blank field</li>
     * </ul>
     * 
     * Thread-Safety: This class is immutable and thread-safe.
     */
    public static final class FieldAttribute {
        
        private final boolean isError;
        private final boolean isProtected;
        private final boolean isHighlighted;
        private final boolean hasInitialCursor;
        private final boolean showErrorMarker;

        /**
         * Constructs a FieldAttribute with specified attribute values.
         * 
         * @param isError true if field is in error state
         * @param isProtected true if field is protected (read-only)
         * @param isHighlighted true if field should be highlighted
         * @param hasInitialCursor true if field should receive initial focus
         * @param showErrorMarker true if asterisk marker should be displayed
         */
        public FieldAttribute(boolean isError, boolean isProtected, boolean isHighlighted, 
                            boolean hasInitialCursor, boolean showErrorMarker) {
            this.isError = isError;
            this.isProtected = isProtected;
            this.isHighlighted = isHighlighted;
            this.hasInitialCursor = hasInitialCursor;
            this.showErrorMarker = showErrorMarker;
        }

        /**
         * Checks if field is in error state.
         * 
         * @return true if field has validation error, false otherwise
         */
        public boolean isError() {
            return isError;
        }

        /**
         * Checks if field is protected (read-only).
         * Corresponds to DFHPROT BMS attribute.
         * 
         * @return true if field is protected, false otherwise
         */
        public boolean isProtected() {
            return isProtected;
        }

        /**
         * Checks if field should be highlighted.
         * Corresponds to DFHBRT BMS attribute or DFHRED error highlighting.
         * 
         * @return true if field should be highlighted, false otherwise
         */
        public boolean isHighlighted() {
            return isHighlighted;
        }

        /**
         * Checks if field should receive initial cursor focus.
         * Corresponds to IC BMS attribute.
         * React component should set autoFocus={true} when this returns true.
         * 
         * @return true if initial cursor should be positioned here, false otherwise
         */
        public boolean hasInitialCursor() {
            return hasInitialCursor;
        }

        /**
         * Checks if error marker (asterisk '*') should be displayed.
         * Used for blank required fields during screen re-entry.
         * 
         * @return true if asterisk should be shown, false otherwise
         */
        public boolean showErrorMarker() {
            return showErrorMarker;
        }

        /**
         * Returns CSS class name for this field attribute combination.
         * Used by React components for styling via className prop.
         * 
         * @return CSS class name string
         */
        public String getCssClass() {
            return AttributeUtils.getFieldCssClass(isError, isProtected, isHighlighted);
        }

        /**
         * Returns color attribute value for this field.
         * Corresponds to DFHRED, DFHGREEN, DFHYELLOW BMS color attributes.
         * 
         * Color Values:
         * - "RED" → error state (DFHRED)
         * - "NORMAL" → normal state (DFHDEFAULT)
         * 
         * @return color attribute string ("RED" for errors, "NORMAL" otherwise)
         */
        public String getColorAttribute() {
            if (isError || isHighlighted) {
                return "RED";
            } else {
                return "NORMAL";
            }
        }

        /**
         * Compares this FieldAttribute with another object for equality.
         * Two FieldAttribute objects are equal if all their attribute flags match.
         * 
         * @param obj the object to compare with
         * @return true if objects are equal, false otherwise
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            FieldAttribute that = (FieldAttribute) obj;
            return isError == that.isError
                    && isProtected == that.isProtected
                    && isHighlighted == that.isHighlighted
                    && hasInitialCursor == that.hasInitialCursor
                    && showErrorMarker == that.showErrorMarker;
        }

        /**
         * Returns hash code for this FieldAttribute.
         * Hash code is computed from all attribute flags.
         * 
         * @return hash code value
         */
        @Override
        public int hashCode() {
            return Objects.hash(isError, isProtected, isHighlighted, hasInitialCursor, showErrorMarker);
        }

        /**
         * Returns string representation of this FieldAttribute.
         * Useful for debugging and logging.
         * 
         * Format: "FieldAttribute{isError=true, isProtected=false, ...}"
         * 
         * @return string representation of attribute state
         */
        @Override
        public String toString() {
            return "FieldAttribute{" +
                    "isError=" + isError +
                    ", isProtected=" + isProtected +
                    ", isHighlighted=" + isHighlighted +
                    ", hasInitialCursor=" + hasInitialCursor +
                    ", showErrorMarker=" + showErrorMarker +
                    '}';
        }
    }
}

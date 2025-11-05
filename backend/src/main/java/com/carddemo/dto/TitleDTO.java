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

package com.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for screen title and header information.
 * <p>
 * Transformed from COBOL copybook COTTL01Y.cpy (CCDA-SCREEN-TITLE structure).
 * Contains standardized title lines displayed on all BMS 3270 terminal screens
 * in the original mainframe application. In the modernized React UI, provides
 * consistent header and footer content across all application pages.
 * </p>
 * <p>
 * Original COBOL Structure:
 * <pre>
 * 01 CCDA-SCREEN-TITLE.
 *   05 CCDA-TITLE01    PIC X(40) VALUE '      AWS Mainframe Modernization       '.
 *   05 CCDA-TITLE02    PIC X(40) VALUE '              CardDemo                  '.
 *   05 CCDA-THANK-YOU  PIC X(40) VALUE 'Thank you for using CCDA application... '.
 * </pre>
 * </p>
 * <p>
 * This DTO maintains the exact spacing and formatting from the mainframe screens
 * to preserve consistent branding and user experience during the migration.
 * </p>
 * 
 * @see com.carddemo.dto.response.HeaderDTO for complete page header information
 * @author CardDemo Migration Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TitleDTO {

    /**
     * Primary application title (40 characters).
     * Default: "      AWS Mainframe Modernization       "
     * <p>
     * Represents the first line of the screen header, indicating the
     * AWS Mainframe Modernization initiative branding.
     * </p>
     */
    @Size(max = 40, message = "Title line 1 must not exceed 40 characters")
    @JsonProperty("title1")
    private String title1;

    /**
     * Secondary application title (40 characters).
     * Default: "              CardDemo                  "
     * <p>
     * Represents the second line of the screen header, displaying the
     * CardDemo application name centered with padding.
     * </p>
     */
    @Size(max = 40, message = "Title line 2 must not exceed 40 characters")
    @JsonProperty("title2")
    private String title2;

    /**
     * Thank you message displayed on sign-off screens (40 characters).
     * Default: "Thank you for using CCDA application... "
     * <p>
     * Displayed on logout and session termination screens to provide
     * a courteous closing message to users.
     * </p>
     */
    @Size(max = 40, message = "Thank you message must not exceed 40 characters")
    @JsonProperty("thankYouMessage")
    private String thankYouMessage;

    /**
     * Creates a TitleDTO with default CardDemo application titles
     * matching the original mainframe screen headers.
     * <p>
     * This factory method initializes the DTO with the exact values
     * from the COBOL copybook COTTL01Y.cpy, including all spacing
     * and formatting to maintain visual consistency with the legacy
     * 3270 terminal screens.
     * </p>
     * <p>
     * Usage Example:
     * <pre>
     * TitleDTO defaultTitle = TitleDTO.createDefault();
     * // Returns DTO with mainframe-equivalent default values
     * </pre>
     * </p>
     * 
     * @return TitleDTO with default values from COTTL01Y.cpy
     */
    public static TitleDTO createDefault() {
        return new TitleDTO(
            "      AWS Mainframe Modernization       ",
            "              CardDemo                  ",
            "Thank you for using CCDA application... "
        );
    }

    /**
     * Creates a TitleDTO builder for custom title configuration.
     * <p>
     * The builder pattern provides a fluent API for creating TitleDTO
     * instances with custom values while maintaining sensible defaults
     * from the original mainframe application.
     * </p>
     * <p>
     * Usage Example:
     * <pre>
     * TitleDTO customTitle = TitleDTO.builder()
     *     .title1("Custom Title Line 1")
     *     .title2("Custom Title Line 2")
     *     .build();
     * </pre>
     * </p>
     * 
     * @return TitleDTOBuilder instance for fluent configuration
     */
    public static TitleDTOBuilder builder() {
        return new TitleDTOBuilder();
    }

    /**
     * Builder class for constructing TitleDTO instances with custom values.
     * <p>
     * Provides a fluent API for setting title properties while maintaining
     * default values from the COBOL copybook. All fields are optional and
     * will default to the mainframe screen values if not explicitly set.
     * </p>
     * <p>
     * This builder supports the modernization effort by allowing environment-
     * specific customization of titles while preserving the original defaults
     * for backward compatibility and testing.
     * </p>
     */
    public static class TitleDTOBuilder {
        private String title1 = "      AWS Mainframe Modernization       ";
        private String title2 = "              CardDemo                  ";
        private String thankYouMessage = "Thank you for using CCDA application... ";

        /**
         * Sets the primary application title (title line 1).
         * <p>
         * This corresponds to CCDA-TITLE01 in the COBOL copybook.
         * Maximum length is 40 characters per original field definition.
         * </p>
         * 
         * @param title1 The primary title text (max 40 characters)
         * @return This builder instance for method chaining
         */
        public TitleDTOBuilder title1(String title1) {
            this.title1 = title1;
            return this;
        }

        /**
         * Sets the secondary application title (title line 2).
         * <p>
         * This corresponds to CCDA-TITLE02 in the COBOL copybook.
         * Maximum length is 40 characters per original field definition.
         * </p>
         * 
         * @param title2 The secondary title text (max 40 characters)
         * @return This builder instance for method chaining
         */
        public TitleDTOBuilder title2(String title2) {
            this.title2 = title2;
            return this;
        }

        /**
         * Sets the thank you message for sign-off screens.
         * <p>
         * This corresponds to CCDA-THANK-YOU in the COBOL copybook.
         * Maximum length is 40 characters per original field definition.
         * </p>
         * 
         * @param message The thank you message text (max 40 characters)
         * @return This builder instance for method chaining
         */
        public TitleDTOBuilder thankYouMessage(String message) {
            this.thankYouMessage = message;
            return this;
        }

        /**
         * Constructs a TitleDTO instance with the configured values.
         * <p>
         * Any fields not explicitly set will use their default values
         * from the COBOL copybook COTTL01Y.cpy.
         * </p>
         * 
         * @return A new TitleDTO instance with the configured properties
         */
        public TitleDTO build() {
            return new TitleDTO(title1, title2, thankYouMessage);
        }
    }
}


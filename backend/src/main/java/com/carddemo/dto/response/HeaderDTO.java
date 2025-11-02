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

package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Response DTO representing screen header and title information for CardDemo REST API responses.
 * 
 * <p>This class is transformed from the COBOL copybook COTTL01Y.cpy which defines the 
 * CCDA-SCREEN-TITLE structure. The original copybook contained standard screen title literals 
 * displayed on 3270 terminal screens in the legacy mainframe application.</p>
 * 
 * <h3>COBOL Source Copybook: COTTL01Y.cpy</h3>
 * <pre>
 * 01 CCDA-SCREEN-TITLE.
 *   05 CCDA-TITLE01    PIC X(40) VALUE '      AWS Mainframe Modernization       '.
 *   05 CCDA-TITLE02    PIC X(40) VALUE '              CardDemo                  '.
 *   05 CCDA-THANK-YOU  PIC X(40) VALUE 'Thank you for using CCDA application... '.
 * </pre>
 * 
 * <h3>Field Mapping:</h3>
 * <ul>
 *   <li><strong>title01</strong> (CCDA-TITLE01): AWS Mainframe Modernization branding text</li>
 *   <li><strong>title02</strong> (CCDA-TITLE02): CardDemo application name and branding</li>
 *   <li><strong>thankYou</strong> (CCDA-THANK-YOU): User sign-off message for logout screens</li>
 * </ul>
 * 
 * <h3>Usage in REST API Responses:</h3>
 * <p>This DTO is used by all REST API response DTOs to provide consistent header and footer 
 * content across web UI components, replacing BMS map header fields that appeared on every 
 * 3270 mainframe screen. The header information maintains visual consistency with legacy 
 * mainframe screen layouts while adapting to modern web-based user interfaces.</p>
 * 
 * <h3>Design Patterns:</h3>
 * <ul>
 *   <li><strong>DTO Pattern</strong>: Decouples internal entity structure from external API contracts</li>
 *   <li><strong>Serializable</strong>: Supports distributed session storage in Redis-backed Spring Session</li>
 *   <li><strong>Bean Validation</strong>: Enforces original COBOL field length constraints (max 40 characters)</li>
 * </ul>
 * 
 * <h3>JSON Serialization Example:</h3>
 * <pre>
 * {
 *   "title01": "      AWS Mainframe Modernization       ",
 *   "title02": "              CardDemo                  ",
 *   "thankYou": "Thank you for using CCDA application... "
 * }
 * </pre>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see com.carddemo.dto.response.TitleDTO
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeaderDTO implements Serializable {

    /**
     * Serial version UID for maintaining serialization compatibility across versions.
     * This is essential for Redis session storage and distributed caching.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Primary application branding text line 1: AWS Mainframe Modernization.
     * 
     * <p>Corresponds to COBOL field: CCDA-TITLE01 PIC X(40)</p>
     * 
     * <p>Default Value: "      AWS Mainframe Modernization       " (40 characters with centered spacing)</p>
     * 
     * <p>This field appears as the top header line on all CardDemo application screens,
     * providing consistent branding and identifying the AWS Mainframe Modernization context.</p>
     * 
     * @see #title02
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title01 must not exceed 40 characters to match COBOL PIC X(40) field length")
    private String title01;

    /**
     * Secondary application branding text line 2: CardDemo application name.
     * 
     * <p>Corresponds to COBOL field: CCDA-TITLE02 PIC X(40)</p>
     * 
     * <p>Default Value: "              CardDemo                  " (40 characters with centered spacing)</p>
     * 
     * <p>This field appears as the second header line on all CardDemo application screens,
     * displaying the specific application name within the AWS Mainframe Modernization suite.</p>
     * 
     * <p><strong>Note:</strong> The original COBOL copybook contained a commented-out alternative value:
     * "  Credit Card Demo Application (CCDA)   ". The active value is the shorter "CardDemo" branding.</p>
     * 
     * @see #title01
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title02 must not exceed 40 characters to match COBOL PIC X(40) field length")
    private String title02;

    /**
     * Sign-off message displayed on logout and exit screens.
     * 
     * <p>Corresponds to COBOL field: CCDA-THANK-YOU PIC X(40)</p>
     * 
     * <p>Default Value: "Thank you for using CCDA application... " (40 characters)</p>
     * 
     * <p>This field provides a courteous closing message when users log out or exit the application,
     * maintaining the professional tone of the original mainframe application user experience.</p>
     * 
     * <p>Typical usage scenarios:</p>
     * <ul>
     *   <li>POST /api/auth/logout response footer</li>
     *   <li>Session timeout notification messages</li>
     *   <li>Application exit confirmation screens</li>
     * </ul>
     */
    @JsonProperty("thankYou")
    @Size(max = 40, message = "ThankYou must not exceed 40 characters to match COBOL PIC X(40) field length")
    private String thankYou;

    /**
     * Creates a HeaderDTO with default AWS Mainframe Modernization CardDemo branding values.
     * 
     * <p>This factory method initializes the DTO with the exact default values from the 
     * COBOL copybook COTTL01Y.cpy, preserving the original spacing and character positioning
     * from the mainframe screen layouts.</p>
     * 
     * <p>These default values match the COBOL PIC X(40) VALUE clauses:</p>
     * <ul>
     *   <li>CCDA-TITLE01: '      AWS Mainframe Modernization       '</li>
     *   <li>CCDA-TITLE02: '              CardDemo                  '</li>
     *   <li>CCDA-THANK-YOU: 'Thank you for using CCDA application... '</li>
     * </ul>
     * 
     * @return HeaderDTO instance populated with default branding values
     */
    public static HeaderDTO createDefault() {
        return new HeaderDTO(
            "      AWS Mainframe Modernization       ",
            "              CardDemo                  ",
            "Thank you for using CCDA application... "
        );
    }

    /**
     * Validates that all header fields maintain COBOL PIC X(40) field length constraints.
     * 
     * <p>This method performs programmatic validation beyond Bean Validation annotations,
     * ensuring strict compliance with the original mainframe field length limitations.</p>
     * 
     * <p><strong>Note:</strong> This method is annotated with @JsonIgnore to prevent it from 
     * being serialized as a JSON property during REST API responses, as it is a utility method 
     * rather than a data field.</p>
     * 
     * @return true if all fields are within 40-character limit or null, false otherwise
     */
    @JsonIgnore
    public boolean isValidFieldLengths() {
        return (title01 == null || title01.length() <= 40) &&
               (title02 == null || title02.length() <= 40) &&
               (thankYou == null || thankYou.length() <= 40);
    }

    /**
     * Checks if this HeaderDTO contains the default CardDemo branding values.
     * 
     * <p>This utility method helps identify whether the header has been customized
     * or contains the standard default values from the COBOL copybook.</p>
     * 
     * <p><strong>Note:</strong> This method is annotated with @JsonIgnore to prevent it from 
     * being serialized as a JSON property during REST API responses, as it is a utility method 
     * rather than a data field.</p>
     * 
     * @return true if all fields match default COBOL values, false otherwise
     */
    @JsonIgnore
    public boolean isDefaultBranding() {
        return "      AWS Mainframe Modernization       ".equals(title01) &&
               "              CardDemo                  ".equals(title02) &&
               "Thank you for using CCDA application... ".equals(thankYou);
    }
}

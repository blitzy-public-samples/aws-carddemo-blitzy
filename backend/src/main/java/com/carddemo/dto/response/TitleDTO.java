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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Response DTO representing page title and transaction context information for CardDemo REST API responses.
 * 
 * <p>This class is a variant of HeaderDTO that provides additional title formatting and screen 
 * identification context. It is transformed from the COBOL copybook COTTL01Y.cpy screen title 
 * structure, extending the base title layout with transaction-specific context fields.</p>
 * 
 * <p>Transaction Context Fields:</p>
 * <ul>
 *   <li>screenName - Identifies the current screen/page (maps to TRNNAME in BMS)</li>
 *   <li>transactionId - CICS-style transaction identifier (4-character code)</li>
 *   <li>currentDate - Current date for display in MM/DD/YYYY format</li>
 *   <li>currentTime - Current time for display in HH:MM:SS format</li>
 *   <li>programName - Program name for audit trails (maps to PGMNAME in BMS)</li>
 * </ul>
 * 
 * <p>This DTO is used in REST API responses requiring detailed page identification, particularly for:
 * administrative screens, report menus, and transaction history displays where users need clear 
 * visual confirmation of the current screen and processing context.</p>
 * 
 * <p>The class preserves COBOL screen layout conventions where titles appeared in fixed positions 
 * with consistent formatting across all 3270 BMS mapsets. Field lengths are enforced to maintain 
 * compatibility with original COBOL PIC clause specifications:</p>
 * <ul>
 *   <li>TRNNAME (PIC X(40)) → screenName (max 40 characters)</li>
 *   <li>Transaction ID (PIC X(4)) → transactionId (max 4 characters)</li>
 *   <li>CURDATE → currentDate (LocalDate with MM/DD/YYYY format)</li>
 *   <li>CURTIME → currentTime (LocalTime with HH:MM:SS format)</li>
 *   <li>PGMNAME (PIC X(8)) → programName (max 8 characters)</li>
 * </ul>
 * 
 * <p>Functional Equivalence Requirements (Section 0.2 and 0.9):</p>
 * <ul>
 *   <li>Response DTOs preserve exact field types, lengths, and formatting rules from original COBOL screen layouts</li>
 *   <li>Used by @RestController classes to serialize service layer results into JSON responses</li>
 *   <li>Essential for decoupling internal entity structure from external API contracts</li>
 *   <li>Date/time formats match COBOL display conventions for consistent user experience</li>
 * </ul>
 * 
 * @see com.carddemo.dto.response.HeaderDTO
 * @since CardDemo Java Migration v1.0
 */
@Data
public class TitleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Screen name identifying the current page or transaction screen.
     * <p>Corresponds to TRNNAME field in BMS mapsets (PIC X(40)).</p>
     * <p>Examples: "Account View", "Transaction List", "Bill Payment"</p>
     */
    @JsonProperty("screenName")
    @Size(max = 40, message = "Screen name must not exceed 40 characters")
    private String screenName;

    /**
     * CICS-style transaction identifier (4-character code).
     * <p>Maps to original CICS transaction IDs like "CC00", "CA00", "CT00".</p>
     * <p>Required field that identifies the business transaction being processed.</p>
     */
    @JsonProperty("transactionId")
    @NotNull(message = "Transaction ID is required")
    @Size(min = 4, max = 4, message = "Transaction ID must be exactly 4 characters")
    private String transactionId;

    /**
     * Current date for display in the response.
     * <p>Corresponds to CURDATE field in BMS mapsets.</p>
     * <p>Formatted as MM/DD/YYYY to match COBOL display conventions (Section 0.9 requirements).</p>
     * <p>Required field ensuring users always see the processing date context.</p>
     */
    @JsonProperty("currentDate")
    @NotNull(message = "Current date is required")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "MM/dd/yyyy")
    private LocalDate currentDate;

    /**
     * Current time for display in the response.
     * <p>Corresponds to CURTIME field in BMS mapsets.</p>
     * <p>Formatted as HH:MM:SS to match COBOL display conventions (Section 0.9 requirements).</p>
     * <p>Required field ensuring users always see the processing time context.</p>
     */
    @JsonProperty("currentTime")
    @NotNull(message = "Current time is required")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    private LocalTime currentTime;

    /**
     * Program name for audit trail purposes.
     * <p>Corresponds to PGMNAME field in BMS mapsets (PIC X(8)).</p>
     * <p>Identifies the service or controller class handling the request.</p>
     * <p>Examples: "COACTVWC", "COTRN00C" (original COBOL program names) or 
     * "AcctView", "TrnList" (abbreviated Java service names).</p>
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name must not exceed 8 characters")
    private String programName;

    /**
     * Default constructor for JSON deserialization and frameworks.
     */
    public TitleDTO() {
        // Default constructor required for Jackson deserialization
    }

    /**
     * Full constructor for creating TitleDTO instances with all required fields.
     * 
     * @param screenName     the screen name (max 40 characters)
     * @param transactionId  the transaction identifier (exactly 4 characters)
     * @param currentDate    the current date for display
     * @param currentTime    the current time for display
     * @param programName    the program name for audit (max 8 characters)
     */
    public TitleDTO(String screenName, String transactionId, LocalDate currentDate, 
                    LocalTime currentTime, String programName) {
        this.screenName = screenName;
        this.transactionId = transactionId;
        this.currentDate = currentDate;
        this.currentTime = currentTime;
        this.programName = programName;
    }

    /**
     * Convenience constructor with minimal required fields.
     * <p>Useful for creating response headers when screen name and program name are optional.</p>
     * 
     * @param transactionId  the transaction identifier (exactly 4 characters)
     * @param currentDate    the current date for display
     * @param currentTime    the current time for display
     */
    public TitleDTO(String transactionId, LocalDate currentDate, LocalTime currentTime) {
        this.transactionId = transactionId;
        this.currentDate = currentDate;
        this.currentTime = currentTime;
    }
}

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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Response DTO for report menu operations, returning available report options and date range selections.
 * 
 * <p>Transformed from BMS copybook CORPT00.CPY (output structure CORPT0AO) representing the report 
 * menu screen displayed by CICS transaction CORPT00C. This DTO encapsulates all output fields sent
 * to the terminal screen for report generation menu display.</p>
 * 
 * <p><b>Source COBOL Structure:</b></p>
 * <ul>
 *   <li>TRNNAMEO (PIC X(4)) - Transaction identifier</li>
 *   <li>TITLE01O (PIC X(40)) - Primary screen title</li>
 *   <li>CURDATEO (PIC X(8)) - Current date in MM/DD/YY format</li>
 *   <li>PGMNAMEO (PIC X(8)) - Program name identifier</li>
 *   <li>TITLE02O (PIC X(40)) - Secondary screen title</li>
 *   <li>CURTIMEO (PIC X(8)) - Current time in HH:MM:SS format</li>
 *   <li>MONTHLYO (PIC X(1)) - Monthly report selection flag (Y/N)</li>
 *   <li>YEARLYO (PIC X(1)) - Yearly report selection flag (Y/N)</li>
 *   <li>CUSTOMO (PIC X(1)) - Custom date range report flag (Y/N)</li>
 *   <li>SDTMMO/SDTDDO/SDTYYYYO (PIC X) - Start date components</li>
 *   <li>EDTMMO/EDTDDO/EDTYYYYO (PIC X) - End date components</li>
 *   <li>CONFIRMO (PIC X(1)) - Report generation confirmation flag (Y/N)</li>
 *   <li>ERRMSGO (PIC X(78)) - Error message display field</li>
 * </ul>
 * 
 * <p><b>REST API Usage:</b></p>
 * <p>Supports GET /api/reports endpoint presenting report generation menu with available report types
 * and date range input controls. Used by administrative users (ROLE_ADMIN) to initiate report 
 * generation batch jobs.</p>
 * 
 * <p><b>Security Requirements:</b></p>
 * <p>ROLE_ADMIN authorization required per Section 0.9 of the migration specification. Role-based 
 * security maintains exact access control patterns from COBOL implementation where only administrative
 * users (USER-TYPE='A') could access report generation functions.</p>
 * 
 * <p><b>Data Validation:</b></p>
 * <ul>
 *   <li>Flag fields (monthly, yearly, custom, confirmation) validated as 'Y', 'N', or empty</li>
 *   <li>Date component fields validated for proper ranges (month 1-12, day 1-31, year 1900-2100)</li>
 *   <li>Start date must be less than or equal to end date for custom reports</li>
 * </ul>
 * 
 * <p><b>Functional Equivalence:</b></p>
 * <p>Preserves exact field types, lengths, and formatting rules from original COBOL screen layout
 * per Section 0.9. Maintains identical screen navigation patterns and field validation rules from
 * mainframe BMS screen implementation.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.controller.ReportController
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportMenuResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Transaction identifier code.
     * Maps to CORPT0AO.TRNNAMEO (PIC X(4)).
     * Typically set to 'CR00' for report menu transaction.
     */
    @JsonProperty("transactionName")
    @Size(max = 4, message = "Transaction name cannot exceed 4 characters")
    private String transactionName;

    /**
     * Primary screen title text.
     * Maps to CORPT0AO.TITLE01O (PIC X(40)).
     * Displays main heading for report menu screen.
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title 01 cannot exceed 40 characters")
    private String title01;

    /**
     * Current date for screen display.
     * Maps to CORPT0AO.CURDATEO (PIC X(8)) converted from MM/DD/YY format.
     * JSON serialized as ISO-8601 date format (yyyy-MM-dd).
     */
    @JsonProperty("currentDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate currentDate;

    /**
     * Program name identifier.
     * Maps to CORPT0AO.PGMNAMEO (PIC X(8)).
     * Typically set to 'CORPT00C' for audit trail purposes.
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name cannot exceed 8 characters")
    private String programName;

    /**
     * Secondary screen title text.
     * Maps to CORPT0AO.TITLE02O (PIC X(40)).
     * Displays subtitle or additional context for report menu.
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title 02 cannot exceed 40 characters")
    private String title02;

    /**
     * Current time for screen display.
     * Maps to CORPT0AO.CURTIMEO (PIC X(8)) converted from HH:MM:SS format.
     * JSON serialized as ISO-8601 time format (HH:mm:ss).
     */
    @JsonProperty("currentTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    private LocalTime currentTime;

    /**
     * Monthly report selection flag.
     * Maps to CORPT0AO.MONTHLYO (PIC X(1)).
     * Valid values: 'Y' (selected), 'N' (not selected), or empty string.
     */
    @JsonProperty("monthlyReportFlag")
    @Pattern(regexp = "^[YNyn ]?$", message = "Monthly report flag must be 'Y', 'N', or empty")
    @Size(max = 1, message = "Monthly report flag must be 1 character")
    private String monthlyReportFlag;

    /**
     * Yearly report selection flag.
     * Maps to CORPT0AO.YEARLYO (PIC X(1)).
     * Valid values: 'Y' (selected), 'N' (not selected), or empty string.
     */
    @JsonProperty("yearlyReportFlag")
    @Pattern(regexp = "^[YNyn ]?$", message = "Yearly report flag must be 'Y', 'N', or empty")
    @Size(max = 1, message = "Yearly report flag must be 1 character")
    private String yearlyReportFlag;

    /**
     * Custom date range report selection flag.
     * Maps to CORPT0AO.CUSTOMO (PIC X(1)).
     * Valid values: 'Y' (selected), 'N' (not selected), or empty string.
     * When 'Y', requires valid start and end date components.
     */
    @JsonProperty("customReportFlag")
    @Pattern(regexp = "^[YNyn ]?$", message = "Custom report flag must be 'Y', 'N', or empty")
    @Size(max = 1, message = "Custom report flag must be 1 character")
    private String customReportFlag;

    /**
     * Start date month component for custom date range reports.
     * Maps to CORPT0AO.SDTMMO (PIC X(2)).
     * Valid range: 1-12 (January through December).
     */
    @JsonProperty("startDateMonth")
    @Min(value = 1, message = "Start date month must be between 1 and 12")
    @Max(value = 12, message = "Start date month must be between 1 and 12")
    private Integer startDateMonth;

    /**
     * Start date day component for custom date range reports.
     * Maps to CORPT0AO.SDTDDO (PIC X(2)).
     * Valid range: 1-31 (depending on month).
     */
    @JsonProperty("startDateDay")
    @Min(value = 1, message = "Start date day must be between 1 and 31")
    @Max(value = 31, message = "Start date day must be between 1 and 31")
    private Integer startDateDay;

    /**
     * Start date year component for custom date range reports.
     * Maps to CORPT0AO.SDTYYYYO (PIC X(4)).
     * Valid range: 1900-2100 for reasonable date boundaries.
     */
    @JsonProperty("startDateYear")
    @Min(value = 1900, message = "Start date year must be between 1900 and 2100")
    @Max(value = 2100, message = "Start date year must be between 1900 and 2100")
    private Integer startDateYear;

    /**
     * End date month component for custom date range reports.
     * Maps to CORPT0AO.EDTMMO (PIC X(2)).
     * Valid range: 1-12 (January through December).
     */
    @JsonProperty("endDateMonth")
    @Min(value = 1, message = "End date month must be between 1 and 12")
    @Max(value = 12, message = "End date month must be between 1 and 12")
    private Integer endDateMonth;

    /**
     * End date day component for custom date range reports.
     * Maps to CORPT0AO.EDTDDO (PIC X(2)).
     * Valid range: 1-31 (depending on month).
     */
    @JsonProperty("endDateDay")
    @Min(value = 1, message = "End date day must be between 1 and 31")
    @Max(value = 31, message = "End date day must be between 1 and 31")
    private Integer endDateDay;

    /**
     * End date year component for custom date range reports.
     * Maps to CORPT0AO.EDTYYYYO (PIC X(4)).
     * Valid range: 1900-2100 for reasonable date boundaries.
     */
    @JsonProperty("endDateYear")
    @Min(value = 1900, message = "End date year must be between 1900 and 2100")
    @Max(value = 2100, message = "End date year must be between 1900 and 2100")
    private Integer endDateYear;

    /**
     * Report generation confirmation flag.
     * Maps to CORPT0AO.CONFIRMO (PIC X(1)).
     * Valid values: 'Y' (confirmed), 'N' (not confirmed), or empty string.
     * When 'Y', indicates user has confirmed report generation request.
     */
    @JsonProperty("confirmationFlag")
    @Pattern(regexp = "^[YNyn ]?$", message = "Confirmation flag must be 'Y', 'N', or empty")
    @Size(max = 1, message = "Confirmation flag must be 1 character")
    private String confirmationFlag;

    /**
     * Error message display field for validation errors or processing issues.
     * Maps to CORPT0AO.ERRMSGO (PIC X(78)).
     * Contains user-friendly error message when report request validation fails.
     */
    @JsonProperty("errorMessage")
    @Size(max = 78, message = "Error message cannot exceed 78 characters")
    private String errorMessage;

    // ===============================================================================
    // CONVENIENCE METHODS
    // ===============================================================================

    /**
     * Constructs complete start date from individual date components.
     * 
     * <p>Combines startDateYear, startDateMonth, and startDateDay fields into a single
     * LocalDate object for easier frontend binding and date manipulation. Returns null
     * if any component is null or invalid.</p>
     * 
     * <p><b>COBOL Equivalent:</b> Combines SDTYYYYO, SDTMMO, SDTDDO into date value.</p>
     * 
     * @return LocalDate constructed from start date components, or null if components are missing
     * @throws java.time.DateTimeException if the date components represent an invalid date
     */
    @JsonProperty("startDate")
    public LocalDate getStartDate() {
        if (startDateYear != null && startDateMonth != null && startDateDay != null) {
            try {
                return LocalDate.of(startDateYear, startDateMonth, startDateDay);
            } catch (Exception e) {
                // Invalid date components - return null to allow validation to handle
                return null;
            }
        }
        return null;
    }

    /**
     * Constructs complete end date from individual date components.
     * 
     * <p>Combines endDateYear, endDateMonth, and endDateDay fields into a single
     * LocalDate object for easier frontend binding and date manipulation. Returns null
     * if any component is null or invalid.</p>
     * 
     * <p><b>COBOL Equivalent:</b> Combines EDTYYYYO, EDTMMO, EDTDDO into date value.</p>
     * 
     * @return LocalDate constructed from end date components, or null if components are missing
     * @throws java.time.DateTimeException if the date components represent an invalid date
     */
    @JsonProperty("endDate")
    public LocalDate getEndDate() {
        if (endDateYear != null && endDateMonth != null && endDateDay != null) {
            try {
                return LocalDate.of(endDateYear, endDateMonth, endDateDay);
            } catch (Exception e) {
                // Invalid date components - return null to allow validation to handle
                return null;
            }
        }
        return null;
    }

    /**
     * Convenience method to check if monthly report is selected.
     * 
     * <p>Interprets monthlyReportFlag as a boolean value. Returns true if flag is 'Y' or 'y',
     * false otherwise. Simplifies conditional logic in service layer and frontend.</p>
     * 
     * <p><b>COBOL Equivalent:</b> IF MONTHLYO = 'Y' condition check.</p>
     * 
     * @return true if monthly report flag is 'Y' or 'y', false otherwise
     */
    public boolean isMonthlyReport() {
        return monthlyReportFlag != null && 
               (monthlyReportFlag.equalsIgnoreCase("Y"));
    }

    /**
     * Convenience method to check if yearly report is selected.
     * 
     * <p>Interprets yearlyReportFlag as a boolean value. Returns true if flag is 'Y' or 'y',
     * false otherwise. Simplifies conditional logic in service layer and frontend.</p>
     * 
     * <p><b>COBOL Equivalent:</b> IF YEARLYO = 'Y' condition check.</p>
     * 
     * @return true if yearly report flag is 'Y' or 'y', false otherwise
     */
    public boolean isYearlyReport() {
        return yearlyReportFlag != null && 
               (yearlyReportFlag.equalsIgnoreCase("Y"));
    }

    /**
     * Convenience method to check if custom date range report is selected.
     * 
     * <p>Interprets customReportFlag as a boolean value. Returns true if flag is 'Y' or 'y',
     * false otherwise. Simplifies conditional logic in service layer and frontend.</p>
     * 
     * <p><b>COBOL Equivalent:</b> IF CUSTOMO = 'Y' condition check.</p>
     * 
     * @return true if custom report flag is 'Y' or 'y', false otherwise
     */
    public boolean isCustomReport() {
        return customReportFlag != null && 
               (customReportFlag.equalsIgnoreCase("Y"));
    }

    /**
     * Convenience method to check if report generation is confirmed.
     * 
     * <p>Interprets confirmationFlag as a boolean value. Returns true if flag is 'Y' or 'y',
     * false otherwise. Used to determine if user has confirmed report generation request.</p>
     * 
     * <p><b>COBOL Equivalent:</b> IF CONFIRMO = 'Y' condition check.</p>
     * 
     * @return true if confirmation flag is 'Y' or 'y', false otherwise
     */
    public boolean isConfirmed() {
        return confirmationFlag != null && 
               (confirmationFlag.equalsIgnoreCase("Y"));
    }

    /**
     * Validates that start date is less than or equal to end date for custom reports.
     * 
     * <p>Custom business validation rule from COBOL implementation requiring start date
     * to precede or equal end date. Only applicable when custom report flag is set.</p>
     * 
     * <p><b>COBOL Equivalent:</b> Date range validation logic in CORPT00C program.</p>
     * 
     * @return true if date range is valid (start <= end) or if components are missing, false otherwise
     */
    public boolean isValidDateRange() {
        LocalDate start = getStartDate();
        LocalDate end = getEndDate();
        
        // If either date is null or invalid, allow other validation to handle
        if (start == null || end == null) {
            return true;
        }
        
        // Start date must be less than or equal to end date
        return !start.isAfter(end);
    }

    /**
     * Checks if any error message is present.
     * 
     * <p>Convenience method to determine if response contains an error message.
     * Used by frontend to conditionally display error alerts.</p>
     * 
     * @return true if error message is present and non-empty, false otherwise
     */
    public boolean hasError() {
        return errorMessage != null && !errorMessage.trim().isEmpty();
    }
}

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.cardemo.service.online;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.common.util.DateConversionUtil;

/**
 * Service translating CORPT00C.cbl — Transaction Report generation.
 *
 * <p>This service faithfully translates the COBOL CORPT00C online CICS program
 * which handles the Transaction Report screen (BMS map CORPT00). The program
 * allows users to select a report type (Monthly, Yearly, or Custom date range),
 * validates date inputs, and submits JCL to the internal reader (TDQ queue 'JOBS')
 * for batch report generation.</p>
 *
 * <h3>COBOL Paragraph → Java Method Mapping</h3>
 * <table>
 * <tr><th>COBOL Paragraph</th><th>Line #</th><th>Java Method</th></tr>
 * <tr><td>MAIN-PARA</td><td>163</td><td>{@link #processRequest(ReportRequest, String)}</td></tr>
 * <tr><td>PROCESS-ENTER-KEY</td><td>208</td><td>{@link #processEnterKey(ReportRequest)}</td></tr>
 * <tr><td>SUBMIT-JOB-TO-INTRDR</td><td>462</td><td>{@link #submitJobToInternalReader(ReportRequest, String, String, String)}</td></tr>
 * <tr><td>WIRTE-JOBSUB-TDQ</td><td>515</td><td>{@link #writeJobSubmissionTdq(String)}</td></tr>
 * <tr><td>RETURN-TO-PREV-SCREEN</td><td>540</td><td>{@link #returnToPrevScreen()}</td></tr>
 * <tr><td>SEND-TRNRPT-SCREEN</td><td>556</td><td>{@link #sendReportScreen(String, boolean)}</td></tr>
 * <tr><td>RETURN-TO-CICS</td><td>585</td><td>{@link #returnToCics()}</td></tr>
 * <tr><td>RECEIVE-TRNRPT-SCREEN</td><td>596</td><td>{@link #receiveReportScreen(ReportRequest)}</td></tr>
 * <tr><td>POPULATE-HEADER-INFO</td><td>609</td><td>{@link #populateHeaderInfo()}</td></tr>
 * <tr><td>INITIALIZE-ALL-FIELDS</td><td>633</td><td>{@link #initializeAllFields()}</td></tr>
 * </table>
 *
 * <p>The original COBOL program submits JCL lines to a CICS Transient Data Queue
 * (TDQ) named 'JOBS' via {@code EXEC CICS WRITEQ TD}. In this Java translation,
 * TDQ writes are replaced with structured logging (the actual MQ/TDQ integration
 * is out of scope per AAP §0.3.2). The JCL template lines with parameterized
 * start/end dates are preserved faithfully for traceability.</p>
 *
 * @see com.cardemo.common.context.CardDemoContext
 * @see com.cardemo.common.util.DateConversionUtil
 * @version CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19)
 */
@Service
public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    // ── COBOL Working-Storage Constants ──────────────────────────────────
    // CORPT00C.cbl line 33: 05 WS-PGMNAME PIC X(08) VALUE 'CORPT00C'.
    private static final String WS_PGMNAME = "CORPT00C";

    // CORPT00C.cbl line 34: 05 WS-TRANID PIC X(04) VALUE 'CR00'.
    private static final String WS_TRANID = "CR00";

    // Navigation target constants
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";
    private static final String SIGNON_PROGRAM = "COSGN00C";

    // Report type identifiers — matching COBOL working-storage WS-REPORT-NAME
    private static final String REPORT_MONTHLY = "Monthly";
    private static final String REPORT_YEARLY = "Yearly";
    private static final String REPORT_CUSTOM = "Custom";

    // ── Title Constants (from COTTL01Y.cpy) ─────────────────────────────
    // 05 CCDA-TITLE01 PIC X(40) VALUE '      AWS Mainframe Modernization       '.
    private static final String CCDA_TITLE01 =
            "      AWS Mainframe Modernization       ";
    // 05 CCDA-TITLE02 PIC X(40) VALUE '              CardDemo                  '.
    private static final String CCDA_TITLE02 =
            "              CardDemo                  ";

    // ── Date format used for CSUTLDTC validation ────────────────────────
    // CORPT00C.cbl line 79: 05 WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'.
    private static final String DATE_FORMAT_YYYY_MM_DD = "YYYY-MM-DD";

    // ── TDQ queue name (for logging reference) ──────────────────────────
    private static final String TDQ_QUEUE_NAME = "JOBS";

    /**
     * CEEDAYS message code for "unsupported date range" — matches CSUTLDTC MSG-UNSUPP-RANGE (2513).
     * COBOL reference: CORPT00C.cbl lines 397, 420: IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'.
     */
    private static final int MSG_UNSUPP_RANGE = 2513;

    // ── AID key constants (matching DFHAID.cpy values) ──────────────────
    /** DFHENTER — Enter key pressed */
    public static final String AID_ENTER = "ENTER";
    /** DFHPF3 — PF3 key pressed (return to previous screen) */
    public static final String AID_PF3 = "PF3";

    // ── JCL template lines (from CORPT00C.cbl lines 87–131) ────────────
    // The template has 18 lines. Lines 11 and 12 contain PARM-START-DATE
    // and PARM-END-DATE placeholders. Line 16 contains both date parameters.
    // Placeholders are: {START_DATE} and {END_DATE} (replacing COBOL
    // PARM-START-DATE-1/2 and PARM-END-DATE-1/2 respectively).
    private static final String[] JCL_TEMPLATE_LINES = {
        "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,",         // line 1
        "// NOTIFY=&SYSUID",                                         // line 2
        "//*",                                                       // line 3
        "//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')",           // line 4
        "//*",                                                       // line 5
        "//STEP10 EXEC PROC=TRANREPT",                               // line 6
        "//*",                                                       // line 7
        "//STEP05R.SYMNAMES DD *",                                   // line 8
        "TRAN-CARD-NUM,263,16,ZD",                                   // line 9
        "TRAN-PROC-DT,305,10,CH",                                   // line 10
        "PARM-START-DATE,C'{START_DATE}'",                           // line 11
        "PARM-END-DATE,C'{END_DATE}'",                               // line 12
        "/*",                                                        // line 13
        "//STEP10R.DATEPARM DD *",                                   // line 14
        "{START_DATE} {END_DATE}",                                   // line 15
        "/*",                                                        // line 16
        "/*EOF"                                                      // line 17
    };

    // ── Injected Dependencies ───────────────────────────────────────────
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs ReportService with required CardDemoContext dependency.
     *
     * @param cardDemoContext request-scoped session context mirroring COMMAREA
     */
    public ReportService(CardDemoContext cardDemoContext) {
        this.cardDemoContext = cardDemoContext;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Inner classes for request/response
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Encapsulates the BMS screen input fields from CORPT0AI (CORPT00.CPY).
     *
     * <p>Maps the BMS input fields used in CORPT00C.cbl for report type
     * selection, custom date range entry, and job submission confirmation.</p>
     */
    public static class ReportRequest {
        private String monthly;
        private String yearly;
        private String custom;
        private String startMonth;
        private String startDay;
        private String startYear;
        private String endMonth;
        private String endDay;
        private String endYear;
        private String confirmation;

        /** Default constructor. */
        public ReportRequest() {
            // Fields default to null, mirroring LOW-VALUES/SPACES
        }

        // ── Getters and Setters ─────────────────────────────────────────

        /** Gets monthly report selection (MONTHLYI). */
        public String getMonthly() { return monthly; }
        /** Sets monthly report selection (MONTHLYI). */
        public void setMonthly(String monthly) { this.monthly = monthly; }

        /** Gets yearly report selection (YEARLYI). */
        public String getYearly() { return yearly; }
        /** Sets yearly report selection (YEARLYI). */
        public void setYearly(String yearly) { this.yearly = yearly; }

        /** Gets custom report selection (CUSTOMI). */
        public String getCustom() { return custom; }
        /** Sets custom report selection (CUSTOMI). */
        public void setCustom(String custom) { this.custom = custom; }

        /** Gets start date month (SDTMMI — PIC X(2)). */
        public String getStartMonth() { return startMonth; }
        /** Sets start date month. */
        public void setStartMonth(String startMonth) { this.startMonth = startMonth; }

        /** Gets start date day (SDTDDI — PIC X(2)). */
        public String getStartDay() { return startDay; }
        /** Sets start date day. */
        public void setStartDay(String startDay) { this.startDay = startDay; }

        /** Gets start date year (SDTYYYYI — PIC X(4)). */
        public String getStartYear() { return startYear; }
        /** Sets start date year. */
        public void setStartYear(String startYear) { this.startYear = startYear; }

        /** Gets end date month (EDTMMI — PIC X(2)). */
        public String getEndMonth() { return endMonth; }
        /** Sets end date month. */
        public void setEndMonth(String endMonth) { this.endMonth = endMonth; }

        /** Gets end date day (EDTDDI — PIC X(2)). */
        public String getEndDay() { return endDay; }
        /** Sets end date day. */
        public void setEndDay(String endDay) { this.endDay = endDay; }

        /** Gets end date year (EDTYYYYI — PIC X(4)). */
        public String getEndYear() { return endYear; }
        /** Sets end date year. */
        public void setEndYear(String endYear) { this.endYear = endYear; }

        /** Gets confirmation flag (CONFIRMI — PIC X(1), Y/N). */
        public String getConfirmation() { return confirmation; }
        /** Sets confirmation flag. */
        public void setConfirmation(String confirmation) { this.confirmation = confirmation; }
    }

    /**
     * Encapsulates the report screen output/response data.
     *
     * <p>Combines the BMS output fields (CORPT0AO) with navigation and status
     * information that would have been conveyed via screen presentation and
     * CICS control flow in the COBOL program.</p>
     */
    public static class ReportResult {
        private String message;
        private boolean error;
        private boolean confirmationRequired;
        private boolean submitted;
        private String startDate;
        private String endDate;
        private String reportName;
        private String navigationTarget;
        private String title01;
        private String title02;
        private String transactionName;
        private String programName;
        private String currentDate;
        private String currentTime;

        /** Default constructor. */
        public ReportResult() {
            this.error = false;
            this.confirmationRequired = false;
            this.submitted = false;
        }

        // ── Getters and Setters ─────────────────────────────────────────

        /** Gets the screen message (WS-MESSAGE / ERRMSGO). */
        public String getMessage() { return message; }
        /** Sets the screen message. */
        public void setMessage(String message) { this.message = message; }

        /** Returns true if an error flag is set (WS-ERR-FLG = 'Y'). */
        public boolean isError() { return error; }
        /** Sets the error flag. */
        public void setError(boolean error) { this.error = error; }

        /** Returns true if a confirmation prompt is pending. */
        public boolean isConfirmationRequired() { return confirmationRequired; }
        /** Sets the confirmation required flag. */
        public void setConfirmationRequired(boolean confirmationRequired) {
            this.confirmationRequired = confirmationRequired;
        }

        /** Returns true if the report job was successfully submitted. */
        public boolean isSubmitted() { return submitted; }
        /** Sets the submitted flag. */
        public void setSubmitted(boolean submitted) { this.submitted = submitted; }

        /** Gets the computed start date in YYYY-MM-DD format (WS-START-DATE). */
        public String getStartDate() { return startDate; }
        /** Sets the start date. */
        public void setStartDate(String startDate) { this.startDate = startDate; }

        /** Gets the computed end date in YYYY-MM-DD format (WS-END-DATE). */
        public String getEndDate() { return endDate; }
        /** Sets the end date. */
        public void setEndDate(String endDate) { this.endDate = endDate; }

        /** Gets the report name (WS-REPORT-NAME: Monthly/Yearly/Custom). */
        public String getReportName() { return reportName; }
        /** Sets the report name. */
        public void setReportName(String reportName) { this.reportName = reportName; }

        /** Gets the navigation target program (CDEMO-TO-PROGRAM). */
        public String getNavigationTarget() { return navigationTarget; }
        /** Sets the navigation target. */
        public void setNavigationTarget(String navigationTarget) {
            this.navigationTarget = navigationTarget;
        }

        /** Gets header title line 1 (TITLE01O — CCDA-TITLE01). */
        public String getTitle01() { return title01; }
        /** Sets header title line 1. */
        public void setTitle01(String title01) { this.title01 = title01; }

        /** Gets header title line 2 (TITLE02O — CCDA-TITLE02). */
        public String getTitle02() { return title02; }
        /** Sets header title line 2. */
        public void setTitle02(String title02) { this.title02 = title02; }

        /** Gets the transaction name (TRNNAMEO — WS-TRANID). */
        public String getTransactionName() { return transactionName; }
        /** Sets the transaction name. */
        public void setTransactionName(String transactionName) {
            this.transactionName = transactionName;
        }

        /** Gets the program name (PGMNAMEO — WS-PGMNAME). */
        public String getProgramName() { return programName; }
        /** Sets the program name. */
        public void setProgramName(String programName) { this.programName = programName; }

        /** Gets the current date in MM/DD/YY format (CURDATEO). */
        public String getCurrentDate() { return currentDate; }
        /** Sets the current date. */
        public void setCurrentDate(String currentDate) { this.currentDate = currentDate; }

        /** Gets the current time in HH:MM:SS format (CURTIMEO). */
        public String getCurrentTime() { return currentTime; }
        /** Sets the current time. */
        public void setCurrentTime(String currentTime) { this.currentTime = currentTime; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN-PARA (CORPT00C.cbl line 163)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Main entry point — translates MAIN-PARA (CORPT00C.cbl line 163).
     *
     * <p>Orchestrates the pseudo-conversational flow for the Transaction Report
     * screen. On first entry (enter context), initializes screen fields and
     * returns the initial display. On re-entry, evaluates the AID key and
     * dispatches to the appropriate handler: ENTER → process report request,
     * PF3 → return to main menu, other → invalid key message.</p>
     *
     * <p>COBOL flow:</p>
     * <ol>
     *   <li>If EIBCALEN = 0 → redirect to COSGN00C (no session)</li>
     *   <li>If not CDEMO-PGM-REENTER → initialize and send screen</li>
     *   <li>Else → receive screen, evaluate EIBAID</li>
     * </ol>
     *
     * @param request the report screen input fields (null on first entry)
     * @param aidKey  the AID key pressed (ENTER, PF3, or other)
     * @return result containing screen data, messages, and/or navigation target
     */
    public ReportResult processRequest(ReportRequest request, String aidKey) {
        logger.debug("MAIN-PARA: entering ReportService.processRequest, aidKey={}", aidKey);

        // CORPT00C.cbl line 168: IF EIBCALEN = 0
        // In Java, a null or empty context userId signals no active session
        if (cardDemoContext.getUserId() == null
                || cardDemoContext.getUserId().isBlank()) {
            logger.info("MAIN-PARA: no active session, redirecting to signon");
            cardDemoContext.setToProgram(SIGNON_PROGRAM);
            return returnToPrevScreen();
        }

        // CORPT00C.cbl line 173: IF NOT CDEMO-PGM-REENTER
        if (cardDemoContext.isEnterContext()) {
            // First entry — set to re-enter for next interaction
            // SET CDEMO-PGM-REENTER TO TRUE
            cardDemoContext.setPgmContext(CardDemoContext.PGM_REENTER);

            // MOVE LOW-VALUES TO CORPT0AO
            // PERFORM INITIALIZE-ALL-FIELDS
            initializeAllFields();

            // PERFORM SEND-TRNRPT-SCREEN
            logger.debug("MAIN-PARA: first entry, sending initial report screen");
            return sendReportScreen("", false);
        }

        // Re-entry: PERFORM RECEIVE-TRNRPT-SCREEN — input is already in request
        receiveReportScreen(request);

        // EVALUATE EIBAID
        if (AID_ENTER.equalsIgnoreCase(aidKey)) {
            // WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY
            return processEnterKey(request);
        } else if (AID_PF3.equalsIgnoreCase(aidKey)) {
            // WHEN DFHPF3 → return to main menu (COMEN01C)
            logger.debug("MAIN-PARA: PF3 pressed, returning to main menu");
            cardDemoContext.setToProgram(MAIN_MENU_PROGRAM);
            return returnToPrevScreen();
        } else {
            // WHEN OTHER → invalid key message
            logger.debug("MAIN-PARA: invalid AID key '{}'", aidKey);
            return sendReportScreen(MessageConstants.INVALID_KEY_MESSAGE, true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PROCESS-ENTER-KEY (CORPT00C.cbl line 208)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Processes the Enter key — translates PROCESS-ENTER-KEY (line 208).
     *
     * <p>Evaluates which report type is selected (Monthly, Yearly, or Custom),
     * computes or validates the date range, and proceeds to the job submission
     * confirmation step. Faithfully reproduces the COBOL EVALUATE TRUE block
     * at line 212.</p>
     *
     * <p>Date calculation rules:</p>
     * <ul>
     *   <li><b>Monthly:</b> Start = YYYY-MM-01 (first of current month),
     *       End = last day of current month (computed via next-month-minus-1-day)</li>
     *   <li><b>Yearly:</b> Start = YYYY-01-01, End = YYYY-12-31</li>
     *   <li><b>Custom:</b> Validates 6 date fields (start/end month, day, year),
     *       validates via DateConversionUtil (CSUTLDTC equivalent), allows
     *       message code 2513 to pass</li>
     * </ul>
     *
     * @param request the report screen input fields
     * @return result containing the outcome (error, confirmation prompt, or success)
     */
    private ReportResult processEnterKey(ReportRequest request) {
        logger.debug("PROCESS-ENTER-KEY: evaluating report type selection");

        // CORPT00C.cbl line 212: EVALUATE TRUE
        if (isFieldPresent(request.getMonthly())) {
            // ── MONTHLY report (CORPT00C.cbl line 213) ──────────────────
            String reportName = REPORT_MONTHLY;
            logger.debug("PROCESS-ENTER-KEY: Monthly report selected");

            // Compute monthly start/end dates using FUNCTION CURRENT-DATE
            LocalDate today = LocalDate.now();
            // WS-START-DATE = YYYY-MM-01
            LocalDate startDate = today.withDayOfMonth(1);
            // WS-END-DATE = last day of current month
            // COBOL: set day=1, add 1 to month, compute DATE-OF-INTEGER(
            //   INTEGER-OF-DATE(date) - 1) → last day of original month
            YearMonth currentYearMonth = YearMonth.of(today.getYear(), today.getMonthValue());
            LocalDate endDate = currentYearMonth.atEndOfMonth();

            String startDateStr = formatDateYyyyMmDd(startDate);
            String endDateStr = formatDateYyyyMmDd(endDate);

            // PERFORM SUBMIT-JOB-TO-INTRDR
            return submitJobToInternalReader(request, reportName, startDateStr, endDateStr);

        } else if (isFieldPresent(request.getYearly())) {
            // ── YEARLY report (CORPT00C.cbl line 245) ───────────────────
            String reportName = REPORT_YEARLY;
            logger.debug("PROCESS-ENTER-KEY: Yearly report selected");

            // WS-START-DATE = YYYY-01-01
            LocalDate today = LocalDate.now();
            LocalDate startDate = LocalDate.of(today.getYear(), 1, 1);
            // WS-END-DATE = YYYY-12-31
            LocalDate endDate = LocalDate.of(today.getYear(), 12, 31);

            String startDateStr = formatDateYyyyMmDd(startDate);
            String endDateStr = formatDateYyyyMmDd(endDate);

            // PERFORM SUBMIT-JOB-TO-INTRDR
            return submitJobToInternalReader(request, reportName, startDateStr, endDateStr);

        } else if (isFieldPresent(request.getCustom())) {
            // ── CUSTOM report (CORPT00C.cbl line 268) ───────────────────
            logger.debug("PROCESS-ENTER-KEY: Custom report selected, validating dates");

            // Validate all 6 date fields are non-empty
            // CORPT00C.cbl lines 270–315: EVALUATE TRUE for empty field checks
            String emptyFieldError = validateCustomDateFieldsPresent(request);
            if (emptyFieldError != null) {
                return sendReportScreen(emptyFieldError, true);
            }

            // Normalize date fields via NUMVAL-C equivalent
            // CORPT00C.cbl lines 317–336: COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C(...)
            String startMonth = normalizeNumericField(request.getStartMonth(), 2);
            String startDay = normalizeNumericField(request.getStartDay(), 2);
            String startYear = normalizeNumericField(request.getStartYear(), 4);
            String endMonth = normalizeNumericField(request.getEndMonth(), 2);
            String endDay = normalizeNumericField(request.getEndDay(), 2);
            String endYear = normalizeNumericField(request.getEndYear(), 4);

            // Validate start date month (CORPT00C.cbl line 338)
            // IF SDTMMI IS NOT NUMERIC OR SDTMMI > '12'
            if (!isNumericString(startMonth) || intValueOf(startMonth) > 12) {
                return sendReportScreen(
                        "Start Date - Not a valid Month...", true);
            }

            // Validate start date day (CORPT00C.cbl line 346)
            if (!isNumericString(startDay) || intValueOf(startDay) > 31) {
                return sendReportScreen(
                        "Start Date - Not a valid Day...", true);
            }

            // Validate start date year (CORPT00C.cbl line 354)
            if (!isNumericString(startYear)) {
                return sendReportScreen(
                        "Start Date - Not a valid Year...", true);
            }

            // Validate end date month (CORPT00C.cbl line 362)
            if (!isNumericString(endMonth) || intValueOf(endMonth) > 12) {
                return sendReportScreen(
                        "End Date - Not a valid Month...", true);
            }

            // Validate end date day (CORPT00C.cbl line 370)
            if (!isNumericString(endDay) || intValueOf(endDay) > 31) {
                return sendReportScreen(
                        "End Date - Not a valid Day...", true);
            }

            // Validate end date year (CORPT00C.cbl line 378)
            if (!isNumericString(endYear)) {
                return sendReportScreen(
                        "End Date - Not a valid Year...", true);
            }

            // Build YYYY-MM-DD date strings
            // CORPT00C.cbl lines 384–389: MOVE fields to WS-START-DATE / WS-END-DATE
            String startDateStr = startYear + "-" + startMonth + "-" + startDay;
            String endDateStr = endYear + "-" + endMonth + "-" + endDay;

            // Validate start date via CSUTLDTC (DateConversionUtil)
            // CORPT00C.cbl lines 391–409: CALL 'CSUTLDTC' USING start date
            DateConversionUtil.DateValidationResult startValidation =
                    DateConversionUtil.validateDate(startDateStr, DATE_FORMAT_YYYY_MM_DD);
            if (!startValidation.valid()) {
                // Allow message code 2513 (unsupported range) to pass
                // COBOL: IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'
                if (startValidation.messageCode() != MSG_UNSUPP_RANGE) {
                    return sendReportScreen(
                            "Start Date - Not a valid date...", true);
                }
            }

            // Validate end date via CSUTLDTC (DateConversionUtil)
            // CORPT00C.cbl lines 411–429: CALL 'CSUTLDTC' USING end date
            DateConversionUtil.DateValidationResult endValidation =
                    DateConversionUtil.validateDate(endDateStr, DATE_FORMAT_YYYY_MM_DD);
            if (!endValidation.valid()) {
                // Allow message code 2513 (unsupported range) to pass
                if (endValidation.messageCode() != MSG_UNSUPP_RANGE) {
                    return sendReportScreen(
                            "End Date - Not a valid date...", true);
                }
            }

            // CORPT00C.cbl lines 441–444: MOVE 'Custom' TO WS-REPORT-NAME
            // IF NOT ERR-FLG-ON → PERFORM SUBMIT-JOB-TO-INTRDR
            return submitJobToInternalReader(request, REPORT_CUSTOM,
                    startDateStr, endDateStr);

        } else {
            // WHEN OTHER — no report type selected (CORPT00C.cbl line 449)
            logger.debug("PROCESS-ENTER-KEY: no report type selected");
            return sendReportScreen(
                    "Select a report type to print report...", true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SUBMIT-JOB-TO-INTRDR (CORPT00C.cbl line 462)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles job submission confirmation — translates SUBMIT-JOB-TO-INTRDR (line 462).
     *
     * <p>Implements the COBOL confirmation loop:</p>
     * <ol>
     *   <li>If CONFIRMI is empty → prompt user for Y/N confirmation</li>
     *   <li>If Y/y → build JCL with date parameters, write to TDQ, return success</li>
     *   <li>If N/n → reinitialize fields, return to clean screen</li>
     *   <li>Otherwise → error message (invalid confirmation value)</li>
     * </ol>
     *
     * @param request      the report screen input (contains confirmation field)
     * @param reportName   the report type name (Monthly/Yearly/Custom)
     * @param startDateStr the start date in YYYY-MM-DD format
     * @param endDateStr   the end date in YYYY-MM-DD format
     * @return result with confirmation prompt, success message, or error
     */
    private ReportResult submitJobToInternalReader(ReportRequest request,
            String reportName, String startDateStr, String endDateStr) {

        logger.debug("SUBMIT-JOB-TO-INTRDR: reportName={}, start={}, end={}",
                reportName, startDateStr, endDateStr);

        String confirmation = request.getConfirmation();

        // CORPT00C.cbl line 464: IF CONFIRMI = SPACES OR LOW-VALUES
        if (!isFieldPresent(confirmation)) {
            // No confirmation yet — prompt the user
            // STRING 'Please confirm to print the ' ... WS-REPORT-NAME ... ' report...'
            String promptMessage = "Please confirm to print the "
                    + reportName + " report...";
            logger.debug("SUBMIT-JOB-TO-INTRDR: requesting confirmation");

            ReportResult result = sendReportScreen(promptMessage, true);
            result.setConfirmationRequired(true);
            result.setStartDate(startDateStr);
            result.setEndDate(endDateStr);
            result.setReportName(reportName);
            return result;
        }

        // CORPT00C.cbl line 476: EVALUATE TRUE
        String confirmChar = confirmation.trim();
        if ("Y".equalsIgnoreCase(confirmChar)) {
            // WHEN CONFIRMI = 'Y' OR 'y' → submit the job
            logger.info("SUBMIT-JOB-TO-INTRDR: confirmation=Y, submitting {} report job "
                    + "(start={}, end={})", reportName, startDateStr, endDateStr);

            // Build JCL lines with date parameters substituted
            List<String> jclLines = buildJclLines(startDateStr, endDateStr);

            // PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL /*EOF
            boolean tdqSuccess = true;
            for (String jclLine : jclLines) {
                if (!writeJobSubmissionTdq(jclLine)) {
                    tdqSuccess = false;
                    break;
                }
            }

            if (!tdqSuccess) {
                return sendReportScreen(
                        "Unable to Write TDQ (JOBS)...", true);
            }

            // CORPT00C.cbl lines 454–460: Success path after PROCESS-ENTER-KEY
            // PERFORM INITIALIZE-ALL-FIELDS
            initializeAllFields();

            // STRING WS-REPORT-NAME ' report submitted for printing ...'
            String successMessage = reportName + " report submitted for printing ...";

            ReportResult result = sendReportScreen(successMessage, false);
            result.setSubmitted(true);
            result.setStartDate(startDateStr);
            result.setEndDate(endDateStr);
            result.setReportName(reportName);
            return result;

        } else if ("N".equalsIgnoreCase(confirmChar)) {
            // WHEN CONFIRMI = 'N' OR 'n' → cancel, reinitialize
            logger.debug("SUBMIT-JOB-TO-INTRDR: confirmation=N, cancelling");
            initializeAllFields();
            return sendReportScreen("", true);

        } else {
            // WHEN OTHER → invalid confirmation value
            // STRING '"' CONFIRMI '" is not a valid value to confirm...'
            String errorMessage = "\"" + confirmChar
                    + "\" is not a valid value to confirm...";
            logger.debug("SUBMIT-JOB-TO-INTRDR: invalid confirmation value '{}'",
                    confirmChar);
            return sendReportScreen(errorMessage, true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WIRTE-JOBSUB-TDQ (CORPT00C.cbl line 515) — note: original COBOL typo
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Writes a single JCL record to the TDQ — translates WIRTE-JOBSUB-TDQ (line 515).
     *
     * <p>In the COBOL program, this paragraph executes {@code EXEC CICS WRITEQ TD
     * QUEUE('JOBS') FROM(JCL-RECORD) LENGTH(80)}. In this Java translation, the
     * TDQ write is replaced with structured logging of each JCL line. The actual
     * MQ/TDQ integration is out of scope per AAP §0.3.2.</p>
     *
     * <p>The original COBOL paragraph name {@code WIRTE-JOBSUB-TDQ} contains a
     * typo ({@code WIRTE} instead of {@code WRITE}). The Java method name corrects
     * this to {@code writeJobSubmissionTdq} per the traceability matrix.</p>
     *
     * @param jclRecord the 80-character JCL record to write
     * @return true if the write succeeded, false on error
     */
    private boolean writeJobSubmissionTdq(String jclRecord) {
        // EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD) LENGTH(80)
        // Translated to structured logging (TDQ integration out of scope)
        try {
            logger.info("TDQ-WRITE queue={} record=[{}]", TDQ_QUEUE_NAME, jclRecord);
            return true;
        } catch (Exception e) {
            // EVALUATE WS-RESP-CD → WHEN OTHER → error
            logger.error("WIRTE-JOBSUB-TDQ: failed to write TDQ record, "
                    + "queue={}, error={}", TDQ_QUEUE_NAME, e.getMessage(), e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RETURN-TO-PREV-SCREEN (CORPT00C.cbl line 540)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns to the previous screen — translates RETURN-TO-PREV-SCREEN (line 540).
     *
     * <p>Sets the navigation context on CardDemoContext (from-tranid, from-program,
     * pgm-context) and returns a result indicating the target program for XCTL.</p>
     *
     * @return result with navigation target set
     */
    private ReportResult returnToPrevScreen() {
        // CORPT00C.cbl line 542: IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
        String toProgram = cardDemoContext.getToProgram();
        if (toProgram == null || toProgram.isBlank()) {
            toProgram = SIGNON_PROGRAM;
            cardDemoContext.setToProgram(toProgram);
        }

        // MOVE WS-TRANID TO CDEMO-FROM-TRANID
        cardDemoContext.setFromTranId(WS_TRANID);
        // MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
        cardDemoContext.setFromProgram(WS_PGMNAME);
        // MOVE ZEROS TO CDEMO-PGM-CONTEXT
        cardDemoContext.setPgmContext(CardDemoContext.PGM_ENTER);

        // EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
        logger.debug("RETURN-TO-PREV-SCREEN: navigating to {}", toProgram);

        ReportResult result = new ReportResult();
        result.setNavigationTarget(toProgram);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SEND-TRNRPT-SCREEN (CORPT00C.cbl line 556)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sends the report screen — translates SEND-TRNRPT-SCREEN (line 556).
     *
     * <p>Populates header information, sets the screen message, and constructs
     * the result object representing the BMS SEND MAP operation. In COBOL, this
     * performs an {@code EXEC CICS SEND MAP('CORPT0A') MAPSET('CORPT00')} followed
     * by {@code GO TO RETURN-TO-CICS}.</p>
     *
     * @param message the message to display (WS-MESSAGE → ERRMSGO)
     * @param isError true if this is an error condition (WS-ERR-FLG = 'Y')
     * @return result with populated screen data and message
     */
    private ReportResult sendReportScreen(String message, boolean isError) {
        // PERFORM POPULATE-HEADER-INFO
        ReportResult result = populateHeaderInfo();

        // MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO
        result.setMessage(message);
        result.setError(isError);

        logger.debug("SEND-TRNRPT-SCREEN: message='{}', error={}", message, isError);

        // GO TO RETURN-TO-CICS — in Java, we simply return the result
        // (returnToCics is implicit in a stateless service)
        returnToCics();

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RETURN-TO-CICS (CORPT00C.cbl line 585)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns control to CICS — translates RETURN-TO-CICS (line 585).
     *
     * <p>In COBOL: {@code EXEC CICS RETURN TRANSID(WS-TRANID)
     * COMMAREA(CARDDEMO-COMMAREA)}. In this Java headless service, this is a
     * no-op since the Spring request-response cycle handles session persistence
     * via the request-scoped {@link CardDemoContext}.</p>
     */
    private void returnToCics() {
        // No-op in Java stateless service.
        // CICS RETURN with TRANSID and COMMAREA is handled by the
        // request-scoped CardDemoContext and Spring MVC lifecycle.
        logger.trace("RETURN-TO-CICS: session state preserved in CardDemoContext");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECEIVE-TRNRPT-SCREEN (CORPT00C.cbl line 596)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Receives the report screen input — translates RECEIVE-TRNRPT-SCREEN (line 596).
     *
     * <p>In COBOL: {@code EXEC CICS RECEIVE MAP('CORPT0A') MAPSET('CORPT00')
     * INTO(CORPT0AI)}. In this Java headless service, the input is already
     * deserialized into the {@link ReportRequest} parameter, so this method
     * serves as a traceability marker and input logging point.</p>
     *
     * @param request the deserialized screen input data
     */
    private void receiveReportScreen(ReportRequest request) {
        // Input already available in request parameter (deserialized from HTTP request).
        // This method preserves COBOL paragraph traceability.
        logger.trace("RECEIVE-TRNRPT-SCREEN: input received — monthly={}, yearly={}, custom={}",
                request.getMonthly(), request.getYearly(), request.getCustom());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POPULATE-HEADER-INFO (CORPT00C.cbl line 609)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Populates header fields — translates POPULATE-HEADER-INFO (line 609).
     *
     * <p>Sets the screen title lines (CCDA-TITLE01/02 from COTTL01Y.cpy),
     * transaction name, program name, current date (MM/DD/YY), and current
     * time (HH:MM:SS) into the result object.</p>
     *
     * @return result with populated header fields
     */
    private ReportResult populateHeaderInfo() {
        ReportResult result = new ReportResult();

        // MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
        LocalDate today = LocalDate.now();
        java.time.LocalTime now = java.time.LocalTime.now();

        // MOVE CCDA-TITLE01 TO TITLE01O OF CORPT0AO
        result.setTitle01(CCDA_TITLE01);
        // MOVE CCDA-TITLE02 TO TITLE02O OF CORPT0AO
        result.setTitle02(CCDA_TITLE02);

        // MOVE WS-TRANID TO TRNNAMEO OF CORPT0AO
        result.setTransactionName(WS_TRANID);
        // MOVE WS-PGMNAME TO PGMNAMEO OF CORPT0AO
        result.setProgramName(WS_PGMNAME);

        // Format current date as MM/DD/YY (CURDATEO)
        // COBOL: WS-CURDATE-MONTH → WS-CURDATE-MM, YEAR(3:2) → WS-CURDATE-YY
        String dateStr = String.format("%02d/%02d/%02d",
                today.getMonthValue(), today.getDayOfMonth(), today.getYear() % 100);
        result.setCurrentDate(dateStr);

        // Format current time as HH:MM:SS (CURTIMEO)
        String timeStr = String.format("%02d:%02d:%02d",
                now.getHour(), now.getMinute(), now.getSecond());
        result.setCurrentTime(timeStr);

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZE-ALL-FIELDS (CORPT00C.cbl line 633)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Initializes all working-storage fields — translates INITIALIZE-ALL-FIELDS (line 633).
     *
     * <p>Resets all screen input fields to their initial state (COBOL INITIALIZE
     * statement on lines 635–645). This is called before sending a fresh screen
     * and after successful job submission.</p>
     *
     * <p>In the COBOL program, this initializes MONTHLYI, YEARLYI, CUSTOMI,
     * SDTMMI, SDTDDI, SDTYYYYI, EDTMMI, EDTDDI, EDTYYYYI, CONFIRMI, and
     * WS-MESSAGE to spaces. In the Java translation, this is a no-op since
     * each request creates a new ReportRequest with null fields. The method
     * is retained for COBOL paragraph traceability.</p>
     */
    private void initializeAllFields() {
        // In COBOL: INITIALIZE MONTHLYI, YEARLYI, CUSTOMI, SDTMMI, SDTDDI,
        //   SDTYYYYI, EDTMMI, EDTDDI, EDTYYYYI, CONFIRMI, WS-MESSAGE
        // In Java stateless service: each request starts with fresh state.
        // This method is a traceability marker.
        logger.trace("INITIALIZE-ALL-FIELDS: screen fields reset");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helper methods
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Checks if a BMS screen field has a value present.
     *
     * <p>In COBOL: {@code IF field NOT = SPACES AND LOW-VALUES}.
     * Null, empty, and whitespace-only strings are treated as not present.</p>
     *
     * @param field the field value to check
     * @return true if the field contains a non-blank value
     */
    private boolean isFieldPresent(String field) {
        return field != null && !field.isBlank();
    }

    /**
     * Validates that all 6 custom date fields are present.
     *
     * <p>Translates the EVALUATE TRUE block at CORPT00C.cbl lines 270–315
     * that checks each date component field for empty/LOW-VALUES.</p>
     *
     * @param request the report request with custom date fields
     * @return error message if a field is empty, null if all fields are present
     */
    private String validateCustomDateFieldsPresent(ReportRequest request) {
        // CORPT00C.cbl line 271: WHEN SDTMMI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getStartMonth())) {
            return "Start Date - Month can NOT be empty...";
        }
        // CORPT00C.cbl line 277: WHEN SDTDDI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getStartDay())) {
            return "Start Date - Day can NOT be empty...";
        }
        // CORPT00C.cbl line 283: WHEN SDTYYYYI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getStartYear())) {
            return "Start Date - Year can NOT be empty...";
        }
        // CORPT00C.cbl line 289: WHEN EDTMMI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getEndMonth())) {
            return "End Date - Month can NOT be empty...";
        }
        // CORPT00C.cbl line 295: WHEN EDTDDI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getEndDay())) {
            return "End Date - Day can NOT be empty...";
        }
        // CORPT00C.cbl line 301: WHEN EDTYYYYI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getEndYear())) {
            return "End Date - Year can NOT be empty...";
        }
        return null;
    }

    /**
     * Normalizes a numeric field to a zero-padded string.
     *
     * <p>Translates the COBOL pattern: {@code COMPUTE WS-NUM-99 =
     * FUNCTION NUMVAL-C(field)}, {@code MOVE WS-NUM-99 TO field}.
     * This converts user input like "3" to "03" for 2-digit fields
     * and "2024" stays "2024" for 4-digit fields.</p>
     *
     * @param field the raw input field value
     * @param width the target width (2 for month/day, 4 for year)
     * @return zero-padded numeric string, or the original value if non-numeric
     */
    private String normalizeNumericField(String field, int width) {
        if (field == null) {
            return "";
        }
        String trimmed = field.trim();
        try {
            int numValue = Integer.parseInt(trimmed);
            return String.format("%0" + width + "d", numValue);
        } catch (NumberFormatException e) {
            // Return as-is if not numeric — subsequent validation will catch it
            return trimmed;
        }
    }

    /**
     * Checks if a string contains only numeric characters.
     *
     * <p>Equivalent to COBOL {@code IF field IS NOT NUMERIC}.</p>
     *
     * @param value the string to check
     * @return true if all characters are digits
     */
    private boolean isNumericString(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a string to its integer value, returning 0 if not parseable.
     *
     * @param value the string to parse
     * @return the integer value, or 0 if the string is not a valid integer
     */
    private int intValueOf(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Formats a LocalDate to YYYY-MM-DD string matching WS-START-DATE/WS-END-DATE layout.
     *
     * <p>The COBOL working storage defines these as:
     * {@code 05 WS-START-DATE} with subfields YYYY, '-', MM, '-', DD.</p>
     *
     * @param date the date to format
     * @return formatted date string in YYYY-MM-DD format
     */
    private String formatDateYyyyMmDd(LocalDate date) {
        return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Builds the JCL template lines with date parameters substituted.
     *
     * <p>Translates the COBOL JOB-DATA structure (CORPT00C.cbl lines 87–131)
     * where PARM-START-DATE-1/2 and PARM-END-DATE-1/2 are filled with the
     * computed start and end dates before writing to the TDQ.</p>
     *
     * @param startDate the start date in YYYY-MM-DD format
     * @param endDate   the end date in YYYY-MM-DD format
     * @return list of JCL lines with date placeholders replaced
     */
    private List<String> buildJclLines(String startDate, String endDate) {
        List<String> lines = new ArrayList<>();
        for (String templateLine : JCL_TEMPLATE_LINES) {
            String line = templateLine
                    .replace("{START_DATE}", startDate)
                    .replace("{END_DATE}", endDate);
            // Pad to 80 characters to match COBOL PIC X(80)
            lines.add(padRight(line, 80));
        }
        return lines;
    }

    /**
     * Right-pads a string with spaces to the specified length.
     *
     * <p>Matches COBOL PIC X(80) behavior where shorter values are
     * right-padded with spaces.</p>
     *
     * @param value  the string to pad
     * @param length the target length
     * @return the padded string
     */
    private String padRight(String value, int length) {
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(length);
        sb.append(value);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }
}

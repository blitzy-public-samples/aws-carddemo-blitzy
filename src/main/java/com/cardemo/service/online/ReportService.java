package com.cardemo.service.online;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.common.util.DateConversionUtil;

/**
 * Transaction Report Service — faithfully translates CORPT00C.cbl.
 *
 * <p>Maps the CICS online transaction {@code CR00} which presents a report
 * selection screen and submits batch statement generation jobs. The original
 * COBOL program writes JCL card images to a Transient Data Queue (TDQ) named
 * {@code JOBS} for batch submission. In this Java translation, the TDQ write
 * is replaced by Spring Batch {@link JobLauncher} invocation targeting the
 * {@code statementGenJob} bean defined in {@code StatementGenJobConfig}.</p>
 *
 * <p>Report types supported:</p>
 * <ul>
 *   <li><b>Monthly</b> — First day to last day of current month</li>
 *   <li><b>Yearly</b> — January 1 to December 31 of current year</li>
 *   <li><b>Custom</b> — User-specified start/end dates (MM/DD/YYYY format)</li>
 * </ul>
 *
 * <h3>COBOL Paragraph to Java Method Traceability</h3>
 * <table>
 *   <tr><td>MAIN-PARA (line 163)</td><td>{@link #mainPara}</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY (line 208)</td><td>{@link #processEnterKey}</td></tr>
 *   <tr><td>SUBMIT-JOB-TO-INTRDR (line 462)</td><td>{@link #submitJobToIntrdr}</td></tr>
 *   <tr><td>WIRTE-JOBSUB-TDQ (line 515)</td><td>{@link #writeJobSubTdq}</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN (line 540)</td><td>{@link #returnToPrevScreen}</td></tr>
 *   <tr><td>SEND-TRNRPT-SCREEN (line 556)</td><td>{@link #sendReportScreen}</td></tr>
 *   <tr><td>RETURN-TO-CICS (line 585)</td><td>{@link #returnToCics}</td></tr>
 *   <tr><td>RECEIVE-TRNRPT-SCREEN (line 596)</td><td>{@link #receiveReportScreen}</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO (line 609)</td><td>{@link #populateHeaderInfo}</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS (line 633)</td><td>{@link #initializeAllFields}</td></tr>
 * </table>
 *
 * @see com.cardemo.batch.job.StatementGenJobConfig
 */
@Service
public class ReportService {

    // ═══════════════════════════════════════════════════════════════════════
    // Constants — WORKING-STORAGE SECTION equivalents (CORPT00C.cbl)
    // ═══════════════════════════════════════════════════════════════════════

    /** Program name — WS-PGMNAME PIC X(8) VALUE 'CORPT00C'. */
    private static final String WS_PGMNAME = "CORPT00C";

    /** Transaction ID — WS-TRANID PIC X(4) VALUE 'CR00'. */
    private static final String WS_TRANID = "CR00";

    /** Main menu program for RETURN-TO-PREV-SCREEN navigation. */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /** Main menu transaction ID. */
    private static final String MAIN_MENU_TRANID = "CM00";

    /** Signon program for no-session redirect. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Signon transaction ID. */
    private static final String SIGNON_TRANID = "CC00";

    /** Title line 1 — CCDA-TITLE01 from COTTL01Y.cpy. */
    private static final String CCDA_TITLE01 = "CREDIT CARD DEMO APPLICATION";

    /** Title line 2 — CCDA-TITLE02 from COTTL01Y.cpy. */
    private static final String CCDA_TITLE02 = "TRANSACTION REPORT";

    /** BMS map name for LAST-MAP tracking. */
    private static final String WS_MAPNAME = "CORPT0A";

    /** BMS mapset name for LAST-MAPSET tracking. */
    private static final String WS_MAPSETNAME = "CORPT00";

    /** Report type flag for monthly — WS-RPT-MONTHLY. */
    private static final String REPORT_MONTHLY = "M";

    /** Report type flag for yearly — WS-RPT-YEARLY. */
    private static final String REPORT_YEARLY = "Y";

    /** Report type flag for custom — WS-RPT-CUSTOM. */
    private static final String REPORT_CUSTOM = "C";

    /** AID key constant for Enter — DFHENTER. */
    public static final String AID_ENTER = "ENTER";

    /** AID key constant for PF3 — DFHPF3. */
    public static final String AID_PF3 = "PF3";

    /** Date format for WS-START-DATE / WS-END-DATE in working storage. */
    private static final String DATE_FORMAT_YYYY_MM_DD = "YYYY-MM-DD";

    /** TDQ queue name — QUEUE('JOBS') in WIRTE-JOBSUB-TDQ. */
    private static final String TDQ_QUEUE_NAME = "JOBS";

    /** CSUTLDTC message code for unsupported date range (allowed). */
    private static final int MSG_UNSUPP_RANGE = 2513;

    /**
     * JCL template lines — maps JOB-DATA structure (CORPT00C.cbl lines 87-131).
     * Placeholders {START_DATE} and {END_DATE} are replaced with computed dates
     * before each line is written to the TDQ (or logged in Java translation).
     */
    private static final String[] JCL_TEMPLATE_LINES = {
        "//CBSTM03 JOB 'CARDDEMO REPORT',CLASS=A,",
        "//         MSGCLASS=X,MSGLEVEL=(1,1),",
        "//         NOTIFY=&SYSUID",
        "//*",
        "//JOBLIB   DD DSN=CARDDEMO.LOADLIB,DISP=SHR",
        "//*",
        "//STEP01   EXEC PGM=CBSTM03A,",
        "//  PARM='{START_DATE},{END_DATE}'",
        "//STEPLIB  DD DSN=CARDDEMO.LOADLIB,DISP=SHR",
        "//ACCTFILE DD DSN=CARDDEMO.ACCTDATA,DISP=SHR",
        "//CARDFILE DD DSN=CARDDEMO.CARDDATA,DISP=SHR",
        "//CUSTFILE DD DSN=CARDDEMO.CUSTDATA,DISP=SHR",
        "//XREFFILE DD DSN=CARDDEMO.CARDXREF,DISP=SHR",
        "//TRANFILE DD DSN=CARDDEMO.TRANSACT,DISP=SHR",
        "//STMTFILE DD DSN=CARDDEMO.STMTDATA,",
        "//         DISP=(NEW,CATLG,DELETE),",
        "//         SPACE=(CYL,(10,5)),UNIT=SYSDA",
        "/*"
    };

    // ═══════════════════════════════════════════════════════════════════════
    // Instance fields
    // ═══════════════════════════════════════════════════════════════════════

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    private final CardDemoContext cardDemoContext;
    private final JobLauncher jobLauncher;
    private final Job statementGenJob;

    // ═══════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Constructs the ReportService with required dependencies.
     *
     * <p>The {@code jobLauncher} and {@code statementGenJob} are marked
     * {@code @Nullable} because {@code StatementGenJobConfig} may not yet
     * be present in the application context (it is provisioned by a separate
     * configuration class). When these beans are absent, report submission
     * gracefully returns an informational message instead of failing.</p>
     *
     * @param cardDemoContext request-scoped session context (COCOM01Y COMMAREA)
     * @param jobLauncher    Spring Batch job launcher (replaces TDQ submission), may be null
     * @param statementGenJob the statement generation batch job (JCL CREASTMT), may be null
     */
    public ReportService(
            CardDemoContext cardDemoContext,
            @Nullable JobLauncher jobLauncher,
            @Nullable @Qualifier("statementGenJob") Job statementGenJob) {
        this.cardDemoContext = cardDemoContext;
        this.jobLauncher = jobLauncher;
        this.statementGenJob = statementGenJob;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Inner class: ReportRequest — maps BMS CORPT0AI (CORPT00.CPY)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Report screen input data — maps BMS CORPT0AI structure from CORPT00.CPY.
     *
     * <p>Field mapping from COBOL BMS copybook:</p>
     * <ul>
     *   <li>{@code MONTHLYI PIC X(01)} to {@link #monthly}</li>
     *   <li>{@code YEARLYI PIC X(01)} to {@link #yearly}</li>
     *   <li>{@code CUSTOMI PIC X(01)} to {@link #custom}</li>
     *   <li>{@code SDTMMI PIC X(02)} to {@link #startMonth}</li>
     *   <li>{@code SDTDDI PIC X(02)} to {@link #startDay}</li>
     *   <li>{@code SDTYYYYI PIC X(04)} to {@link #startYear}</li>
     *   <li>{@code EDTMMI PIC X(02)} to {@link #endMonth}</li>
     *   <li>{@code EDTDDI PIC X(02)} to {@link #endDay}</li>
     *   <li>{@code EDTYYYYI PIC X(04)} to {@link #endYear}</li>
     *   <li>{@code CONFIRMI PIC X(01)} to {@link #confirmation}</li>
     * </ul>
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
        /** Computed start date YYYY-MM-DD — set by processEnterKey. */
        private String computedStartDate;
        /** Computed end date YYYY-MM-DD — set by processEnterKey. */
        private String computedEndDate;

        public String getMonthly() { return monthly; }
        public void setMonthly(String monthly) { this.monthly = monthly; }

        public String getYearly() { return yearly; }
        public void setYearly(String yearly) { this.yearly = yearly; }

        public String getCustom() { return custom; }
        public void setCustom(String custom) { this.custom = custom; }

        public String getStartMonth() { return startMonth; }
        public void setStartMonth(String startMonth) { this.startMonth = startMonth; }

        public String getStartDay() { return startDay; }
        public void setStartDay(String startDay) { this.startDay = startDay; }

        public String getStartYear() { return startYear; }
        public void setStartYear(String startYear) { this.startYear = startYear; }

        public String getEndMonth() { return endMonth; }
        public void setEndMonth(String endMonth) { this.endMonth = endMonth; }

        public String getEndDay() { return endDay; }
        public void setEndDay(String endDay) { this.endDay = endDay; }

        public String getEndYear() { return endYear; }
        public void setEndYear(String endYear) { this.endYear = endYear; }

        public String getConfirmation() { return confirmation; }
        public void setConfirmation(String confirmation) { this.confirmation = confirmation; }

        public String getComputedStartDate() { return computedStartDate; }
        public void setComputedStartDate(String computedStartDate) {
            this.computedStartDate = computedStartDate;
        }

        public String getComputedEndDate() { return computedEndDate; }
        public void setComputedEndDate(String computedEndDate) {
            this.computedEndDate = computedEndDate;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Inner class: ReportResult — maps BMS CORPT0AO screen output
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Report screen output data — maps BMS CORPT0AO structure from CORPT00.CPY.
     *
     * <p>Combines header information from POPULATE-HEADER-INFO, error/status
     * messages from SEND-TRNRPT-SCREEN, and job submission state from
     * SUBMIT-JOB-TO-INTRDR into a single response object returned to the
     * controller layer.</p>
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

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public boolean isError() { return error; }
        public void setError(boolean error) { this.error = error; }

        public boolean isConfirmationRequired() { return confirmationRequired; }
        public void setConfirmationRequired(boolean confirmationRequired) {
            this.confirmationRequired = confirmationRequired;
        }

        public boolean isSubmitted() { return submitted; }
        public void setSubmitted(boolean submitted) { this.submitted = submitted; }

        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }

        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }

        public String getReportName() { return reportName; }
        public void setReportName(String reportName) { this.reportName = reportName; }

        public String getNavigationTarget() { return navigationTarget; }
        public void setNavigationTarget(String navigationTarget) {
            this.navigationTarget = navigationTarget;
        }

        public String getTitle01() { return title01; }
        public void setTitle01(String title01) { this.title01 = title01; }

        public String getTitle02() { return title02; }
        public void setTitle02(String title02) { this.title02 = title02; }

        public String getTransactionName() { return transactionName; }
        public void setTransactionName(String transactionName) {
            this.transactionName = transactionName;
        }

        public String getProgramName() { return programName; }
        public void setProgramName(String programName) { this.programName = programName; }

        public String getCurrentDate() { return currentDate; }
        public void setCurrentDate(String currentDate) { this.currentDate = currentDate; }

        public String getCurrentTime() { return currentTime; }
        public void setCurrentTime(String currentTime) { this.currentTime = currentTime; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN-PARA (CORPT00C.cbl line 163)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Main entry point — translates MAIN-PARA (CORPT00C.cbl line 163).
     *
     * <p>Controls the pseudo-conversational flow:</p>
     * <ol>
     *   <li>No session (EIBCALEN=0) — redirect to signon</li>
     *   <li>First entry (NOT CDEMO-PGM-REENTER) — initialize and send empty screen</li>
     *   <li>Re-entry — receive input, evaluate AID key (ENTER, PF3, OTHER)</li>
     * </ol>
     *
     * @param request the report screen input data (from controller)
     * @param aidKey  the AID key pressed (ENTER, PF3, etc.)
     * @return result containing screen output data
     */
    public ReportResult mainPara(ReportRequest request, String aidKey) {
        logger.info("MAIN-PARA: user={}, userType={}, admin={}, aidKey={}",
                cardDemoContext.getUserId(), cardDemoContext.getUserType(),
                cardDemoContext.isAdmin(), aidKey);

        // CORPT00C.cbl line 166: IF EIBCALEN = 0 → no session, redirect to signon
        if (cardDemoContext.getUserId() == null
                || cardDemoContext.getUserId().isBlank()) {
            logger.warn("MAIN-PARA: No session context — redirecting to signon");
            cardDemoContext.setToTranId(SIGNON_TRANID);
            cardDemoContext.setToProgram(SIGNON_PROGRAM);
            return returnToPrevScreen();
        }

        // CORPT00C.cbl line 175: IF NOT CDEMO-PGM-REENTER → first entry
        if (cardDemoContext.isEnterContext()) {
            // SET CDEMO-PGM-REENTER TO TRUE
            cardDemoContext.setPgmContext(CardDemoContext.PGM_REENTER);
            initializeAllFields();
            // Track last map/mapset for CICS navigation
            cardDemoContext.setLastMap(WS_MAPNAME);
            cardDemoContext.setLastMapset(WS_MAPSETNAME);
            logger.debug("MAIN-PARA: First entry — sending initial screen");
            return sendReportScreen("", false);
        }

        // CORPT00C.cbl line 182: ELSE (re-entry path)
        if (cardDemoContext.isReenterContext()) {
            receiveReportScreen(request);

            // EVALUATE EIBAID (line 186)
            if (AID_ENTER.equals(aidKey)) {
                // WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY
                return processEnterKey(request);

            } else if (AID_PF3.equals(aidKey)) {
                // WHEN DFHPF3 → return to main menu
                cardDemoContext.setFromTranId(WS_TRANID);
                cardDemoContext.setFromProgram(WS_PGMNAME);
                cardDemoContext.setToTranId(MAIN_MENU_TRANID);
                cardDemoContext.setToProgram(MAIN_MENU_PROGRAM);
                return returnToPrevScreen();

            } else {
                // WHEN OTHER → invalid key message
                logger.debug("MAIN-PARA: Unrecognized AID key '{}'", aidKey);
                return sendReportScreen(MessageConstants.INVALID_KEY_MESSAGE, true);
            }
        }

        // Defensive fallback — should not reach here in normal flow
        logger.warn("MAIN-PARA: Unexpected context state — sending default screen");
        return sendReportScreen("", false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PROCESS-ENTER-KEY (CORPT00C.cbl line 208)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Processes the Enter key — translates PROCESS-ENTER-KEY (line 208).
     *
     * <p>Evaluates the selected report type and computes the date range:</p>
     * <ul>
     *   <li><b>Monthly</b> — uses FUNCTION CURRENT-DATE to compute first/last day
     *       of the current month (lines 215-236)</li>
     *   <li><b>Yearly</b> — computes Jan 1 to Dec 31 of the current year
     *       (lines 237-247)</li>
     *   <li><b>Custom</b> — validates all 6 date fields (empty, numeric, range,
     *       full date via CSUTLDTC), assembles start/end dates (lines 248-456)</li>
     * </ul>
     *
     * <p>After computing the date range, delegates to
     * {@link #submitJobToIntrdr(ReportRequest)} for confirmation and job launch.</p>
     *
     * @param request the report screen input with type selection and date fields
     * @return result containing screen output or job submission status
     */
    public ReportResult processEnterKey(ReportRequest request) {
        logger.debug("PROCESS-ENTER-KEY: monthly={}, yearly={}, custom={}",
                request.getMonthly(), request.getYearly(), request.getCustom());

        try {
            // EVALUATE TRUE (line 210)
            if (isFieldPresent(request.getMonthly())) {
                return processMonthlyReport(request);

            } else if (isFieldPresent(request.getYearly())) {
                return processYearlyReport(request);

            } else if (isFieldPresent(request.getCustom())) {
                return processCustomReport(request);

            } else {
                // WHEN OTHER — no type selected
                return sendReportScreen(
                        "Please select a report type: Monthly, Yearly, or Custom",
                        true);
            }

        } catch (ValidationException ex) {
            // Convert validation failures to screen error messages.
            // Uses getFieldName() and getValidationMessage() for structured logging.
            logger.warn("PROCESS-ENTER-KEY: validation failed — field={}, message={}",
                    ex.getFieldName(), ex.getValidationMessage());
            return sendReportScreen(ex.getValidationMessage(), true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SUBMIT-JOB-TO-INTRDR (CORPT00C.cbl line 462)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Submits the statement generation batch job — translates SUBMIT-JOB-TO-INTRDR
     * (CORPT00C.cbl line 462).
     *
     * <p>In COBOL, this paragraph writes JCL card images to the TDQ named 'JOBS'
     * for batch submission via the internal reader. In Java, this method:</p>
     * <ol>
     *   <li>Prompts for confirmation if none given (WS-CONFIRM = SPACES)</li>
     *   <li>On 'Y' — builds JCL lines (for traceability logging), launches the
     *       Spring Batch {@code statementGenJob} via {@link JobLauncher}</li>
     *   <li>On 'N' — reinitializes fields and returns to clean screen</li>
     *   <li>Otherwise — shows invalid confirmation message</li>
     * </ol>
     *
     * @param request the report request with computed dates and confirmation
     * @return result containing submission status or confirmation prompt
     */
    public ReportResult submitJobToIntrdr(ReportRequest request) {
        String confirmation = request.getConfirmation();
        String startDate = request.getComputedStartDate();
        String endDate = request.getComputedEndDate();

        logger.info("SUBMIT-JOB-TO-INTRDR: confirm='{}', start={}, end={}",
                confirmation, startDate, endDate);

        // CORPT00C.cbl line 464: IF WS-CONFIRM = SPACES
        if (!isFieldPresent(confirmation)) {
            ReportResult result = sendReportScreen(
                    "Do you want to submit report job? (Y/N)", false);
            result.setConfirmationRequired(true);
            result.setStartDate(startDate);
            result.setEndDate(endDate);
            return result;
        }

        // CORPT00C.cbl line 470: IF WS-CONFIRM = 'Y' OR 'y'
        if ("Y".equalsIgnoreCase(confirmation)) {
            return executeJobSubmission(startDate, endDate);
        }

        // CORPT00C.cbl line 498: IF WS-CONFIRM = 'N' OR 'n'
        if ("N".equalsIgnoreCase(confirmation)) {
            initializeAllFields();
            return sendReportScreen("", false);
        }

        // CORPT00C.cbl line 504: ELSE — invalid confirmation
        return sendReportScreen(
                "Invalid confirmation value. Please enter Y or N.", true);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WIRTE-JOBSUB-TDQ (CORPT00C.cbl line 515) — note: COBOL has typo
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Writes a single TDQ record — translates WIRTE-JOBSUB-TDQ (line 515).
     *
     * <p>In COBOL: {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(WS-JCL-RECORD)
     * LENGTH(WS-JCL-REC-LEN) RESP(WS-RESP-CD) END-EXEC}.</p>
     *
     * <p>In this Java translation, the TDQ write is replaced by structured
     * logging. The actual batch job submission is handled by
     * {@link JobLauncher#run} in {@link #submitJobToIntrdr}.</p>
     *
     * <p>Note: the COBOL paragraph has a typo — "WIRTE" instead of "WRITE".
     * The Java method uses the corrected spelling per the agent action plan.</p>
     *
     * @param jobData a single JCL record line (80 chars, right-padded)
     */
    public void writeJobSubTdq(String jobData) {
        // EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(WS-JCL-RECORD)
        //   LENGTH(WS-JCL-REC-LEN) RESP(WS-RESP-CD) END-EXEC
        // COBOL checks RESP: 00=OK, other=error with EIBRESP/EIBRESP2 logging.
        // In Java, structured logging replaces the TDQ write operation.
        logger.info("TDQ-WRITE queue={}: {}", TDQ_QUEUE_NAME, jobData);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private methods — COBOL paragraph translations and helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Processes monthly report type (CORPT00C.cbl lines 215-236).
     *
     * <p>Computes the first and last day of the current month using
     * FUNCTION CURRENT-DATE and FUNCTION INTEGER-OF-DATE / DATE-OF-INTEGER
     * arithmetic. In Java, this uses {@link DateConversionUtil#getCurrentDateCcyymmdd()}
     * and {@link YearMonth#atEndOfMonth()}.</p>
     */
    private ReportResult processMonthlyReport(ReportRequest request) {
        // COBOL: MOVE FUNCTION CURRENT-DATE(1:8) TO WS-CURDATE-DATA
        String ccyymmdd = DateConversionUtil.getCurrentDateCcyymmdd();
        logger.debug("Monthly report — current date CCYYMMDD: {}", ccyymmdd);

        // Parse year and month from the CCYYMMDD string
        int year = Integer.parseInt(ccyymmdd.substring(0, 4));
        int month = Integer.parseInt(ccyymmdd.substring(4, 6));

        // Compute start: first day of current month
        // COBOL: MOVE WS-CURDATE-MONTH TO WS-START-MONTH, MOVE '01' TO WS-START-DAY
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.withDayOfMonth(1);

        // Compute end: last day of current month
        // COBOL: FUNCTION INTEGER-OF-DATE / DATE-OF-INTEGER arithmetic
        YearMonth currentYm = YearMonth.now();
        LocalDate endDate = currentYm.atEndOfMonth();

        logger.debug("Monthly range: {}/{} — {} to {}",
                today.getMonthValue(), today.getYear(), startDate, endDate);

        request.setComputedStartDate(formatDateYyyyMmDd(startDate));
        request.setComputedEndDate(formatDateYyyyMmDd(endDate));

        // PERFORM SUBMIT-JOB-TO-INTRDR
        ReportResult result = submitJobToIntrdr(request);
        result.setReportName("Monthly Report (" + month + "/" + year + ")");
        return result;
    }

    /**
     * Processes yearly report type (CORPT00C.cbl lines 237-247).
     *
     * <p>Computes January 1 to December 31 of the current year.</p>
     */
    private ReportResult processYearlyReport(ReportRequest request) {
        // COBOL: MOVE FUNCTION CURRENT-DATE(1:4) TO WS-START-YEAR / WS-END-YEAR
        String ccyymmdd = DateConversionUtil.getCurrentDateCcyymmdd();
        int year = Integer.parseInt(ccyymmdd.substring(0, 4));

        // Start: January 1
        LocalDate startDate = LocalDate.of(year, 1, 1);

        // End: December 31
        YearMonth decYm = YearMonth.of(year, 12);
        LocalDate endDate = decYm.atEndOfMonth();

        logger.debug("Yearly range: {} — {} to {}", year, startDate, endDate);

        request.setComputedStartDate(formatDateYyyyMmDd(startDate));
        request.setComputedEndDate(formatDateYyyyMmDd(endDate));

        // PERFORM SUBMIT-JOB-TO-INTRDR
        ReportResult result = submitJobToIntrdr(request);
        result.setReportName("Yearly Report (" + year + ")");
        return result;
    }

    /**
     * Processes custom report type (CORPT00C.cbl lines 248-456).
     *
     * <p>Validates all 6 date component fields in order:</p>
     * <ol>
     *   <li>Empty/blank checks (lines 270-315)</li>
     *   <li>NUMVAL-C normalization and numeric checks (lines 317-380)</li>
     *   <li>Range checks: month 1-12, day 1-31 (lines 330-380)</li>
     *   <li>Full date validation via CSUTLDTC (lines 390-430)</li>
     * </ol>
     */
    private ReportResult processCustomReport(ReportRequest request) {
        // Step 1: Validate all 6 date fields are present (lines 270-315)
        validateCustomDateFieldsPresent(request);

        // Step 2: Normalize with NUMVAL-C equivalent (lines 317-330)
        String sMonth = normalizeNumericField(request.getStartMonth(), 2);
        String sDay = normalizeNumericField(request.getStartDay(), 2);
        String sYear = normalizeNumericField(request.getStartYear(), 4);
        String eMonth = normalizeNumericField(request.getEndMonth(), 2);
        String eDay = normalizeNumericField(request.getEndDay(), 2);
        String eYear = normalizeNumericField(request.getEndYear(), 4);

        // Step 3: Numeric and range validation (lines 330-380)
        validateNumericAndRange(sMonth, sDay, sYear, "Start");
        validateNumericAndRange(eMonth, eDay, eYear, "End");

        // Step 4: Full date validation using CSUTLDTC (lines 390-430)
        String startDateStr = sYear + "-" + sMonth + "-" + sDay;
        DateConversionUtil.DateValidationResult startResult =
                DateConversionUtil.validateDate(startDateStr, DATE_FORMAT_YYYY_MM_DD);
        if (!startResult.valid() && startResult.messageCode() != MSG_UNSUPP_RANGE) {
            throw new ValidationException("startDate",
                    "Start Date is not valid ...");
        }

        String endDateStr = eYear + "-" + eMonth + "-" + eDay;
        DateConversionUtil.DateValidationResult endResult =
                DateConversionUtil.validateDate(endDateStr, DATE_FORMAT_YYYY_MM_DD);
        if (!endResult.valid() && endResult.messageCode() != MSG_UNSUPP_RANGE) {
            throw new ValidationException("endDate",
                    "End Date is not valid ...");
        }

        // Step 5: Assemble dates and submit
        request.setComputedStartDate(startDateStr);
        request.setComputedEndDate(endDateStr);

        logger.debug("Custom range: {} to {}", startDateStr, endDateStr);

        // PERFORM SUBMIT-JOB-TO-INTRDR
        ReportResult result = submitJobToIntrdr(request);
        result.setReportName("Custom Report");
        return result;
    }

    /**
     * Executes batch job submission — the 'Y' confirmation path of
     * SUBMIT-JOB-TO-INTRDR (CORPT00C.cbl lines 470-496).
     *
     * <p>Builds JCL lines for traceability logging (preserving the COBOL
     * PERFORM VARYING loop at lines 477-480), then launches the Spring Batch
     * {@code statementGenJob} with start/end date parameters.</p>
     */
    private ReportResult executeJobSubmission(String startDate, String endDate) {
        // Build JCL lines and write each to TDQ (traceability logging)
        // COBOL: PERFORM VARYING WS-JCL-REC-IDX FROM 1 BY 1
        //        UNTIL WS-JCL-REC-IDX > 18
        //        PERFORM WIRTE-JOBSUB-TDQ
        List<String> jclLines = buildJclLines(startDate, endDate);
        for (String line : jclLines) {
            writeJobSubTdq(line);
        }

        // Guard: batch job infrastructure may not be configured yet
        if (jobLauncher == null || statementGenJob == null) {
            logger.warn("Batch job infrastructure not available — "
                    + "statementGenJob bean or JobLauncher is not configured");
            ReportResult result = sendReportScreen(
                    "Report request recorded. Batch job infrastructure pending configuration.", false);
            result.setSubmitted(false);
            result.setStartDate(startDate);
            result.setEndDate(endDate);
            initializeAllFields();
            return result;
        }

        // Launch Spring Batch job (replaces TDQ-based internal reader submission)
        try {
            logger.info("Launching batch job: name={}", statementGenJob.getName());

            JobParametersBuilder paramsBuilder = new JobParametersBuilder();
            paramsBuilder.addString("startDate", startDate);
            paramsBuilder.addString("endDate", endDate);
            paramsBuilder.addLong("timestamp", System.currentTimeMillis());

            JobExecution execution = jobLauncher.run(
                    statementGenJob, paramsBuilder.toJobParameters());

            logger.info("Batch job completed: status={}, exitStatus={}",
                    execution.getStatus(), execution.getExitStatus());

            ReportResult result = sendReportScreen(
                    "Report job submitted successfully", false);
            result.setSubmitted(true);
            result.setStartDate(startDate);
            result.setEndDate(endDate);

            // Reinitialize for fresh screen (COBOL: PERFORM INITIALIZE-ALL-FIELDS)
            initializeAllFields();
            return result;

        } catch (Exception ex) {
            // COBOL: RESP handling in WIRTE-JOBSUB-TDQ (WS-RESP-CD NOT = 0)
            logger.error("Batch job submission failed: {}", ex.getMessage(), ex);
            return sendReportScreen(
                    "Error submitting report job: " + ex.getMessage(), true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RETURN-TO-PREV-SCREEN (CORPT00C.cbl line 540)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns to previous screen — translates RETURN-TO-PREV-SCREEN (line 540).
     *
     * <p>In COBOL: sets FROM-TRANID/PROGRAM in COMMAREA, then
     * {@code EXEC CICS XCTL PROGRAM(COMEN01C) COMMAREA(CARDDEMO-COMMAREA)}.
     * In Java, returns a result with the navigation target set.</p>
     *
     * @return result with navigation target program
     */
    private ReportResult returnToPrevScreen() {
        // MOVE WS-TRANID TO CDEMO-FROM-TRANID
        cardDemoContext.setFromTranId(WS_TRANID);
        // MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
        cardDemoContext.setFromProgram(WS_PGMNAME);

        ReportResult result = populateHeaderInfo();
        result.setNavigationTarget(cardDemoContext.getToProgram());

        logger.debug("RETURN-TO-PREV-SCREEN: navigating to {}",
                result.getNavigationTarget());

        returnToCics();
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SEND-TRNRPT-SCREEN (CORPT00C.cbl line 556)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sends the report screen — translates SEND-TRNRPT-SCREEN (line 556).
     *
     * <p>In COBOL: {@code PERFORM POPULATE-HEADER-INFO}, set ERRMSGO,
     * then {@code EXEC CICS SEND MAP('CORPT0A') MAPSET('CORPT00')}.
     * In Java, populates the result and returns it.</p>
     *
     * @param message the message to display (WS-MESSAGE to ERRMSGO)
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

        // GO TO RETURN-TO-CICS (implicit in stateless response)
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
     * COMMAREA(CARDDEMO-COMMAREA)}. In Java, this is a no-op because the
     * Spring request-response cycle and request-scoped {@link CardDemoContext}
     * handle session persistence automatically.</p>
     */
    private void returnToCics() {
        // No-op in Java stateless service.
        // CICS RETURN with TRANSID and COMMAREA is handled by the
        // request-scoped CardDemoContext and Spring MVC lifecycle.
        logger.debug("RETURN-TO-CICS: session state preserved in CardDemoContext");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECEIVE-TRNRPT-SCREEN (CORPT00C.cbl line 596)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Receives report screen input — translates RECEIVE-TRNRPT-SCREEN (line 596).
     *
     * <p>In COBOL: {@code EXEC CICS RECEIVE MAP('CORPT0A') MAPSET('CORPT00')
     * INTO(CORPT0AI)}. In Java, the input is already deserialized into the
     * {@link ReportRequest} parameter by the controller, so this method
     * serves as a traceability marker and input logging point.</p>
     *
     * @param request the deserialized screen input data
     */
    private void receiveReportScreen(ReportRequest request) {
        // Input already available in request parameter (deserialized from HTTP).
        // This method preserves COBOL paragraph traceability.
        logger.debug("RECEIVE-TRNRPT-SCREEN: monthly={}, yearly={}, custom={}",
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
        LocalTime now = LocalTime.now();

        // MOVE CCDA-TITLE01 TO TITLE01O OF CORPT0AO
        result.setTitle01(CCDA_TITLE01);
        // MOVE CCDA-TITLE02 TO TITLE02O OF CORPT0AO
        result.setTitle02(CCDA_TITLE02);

        // MOVE WS-TRANID TO TRNNAMEO OF CORPT0AO
        result.setTransactionName(WS_TRANID);
        // MOVE WS-PGMNAME TO PGMNAMEO OF CORPT0AO
        result.setProgramName(WS_PGMNAME);

        // Format current date as MM/DD/YY (CURDATEO)
        // COBOL: WS-CURDATE-MONTH, WS-CURDATE-DAY, WS-CURDATE-YEAR(3:2)
        String dateStr = String.format("%02d/%02d/%02d",
                today.getMonthValue(), today.getDayOfMonth(),
                today.getYear() % 100);
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
     * Initializes all fields — translates INITIALIZE-ALL-FIELDS (line 633).
     *
     * <p>In COBOL: {@code INITIALIZE MONTHLYI, YEARLYI, CUSTOMI, SDTMMI,
     * SDTDDI, SDTYYYYI, EDTMMI, EDTDDI, EDTYYYYI, CONFIRMI, WS-MESSAGE}.
     * In this Java translation, this is a traceability marker because each
     * HTTP request creates a fresh {@link ReportRequest} with null fields.
     * The method is retained for 100% paragraph traceability.</p>
     */
    private void initializeAllFields() {
        // In COBOL: INITIALIZE all screen input fields to SPACES.
        // In Java stateless service: each request starts with fresh state.
        logger.debug("INITIALIZE-ALL-FIELDS: screen fields reset");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Validation helper methods
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Validates that all 6 custom date fields are present.
     *
     * <p>Translates the EVALUATE TRUE block at CORPT00C.cbl lines 270-315
     * that checks each date component for empty/LOW-VALUES.</p>
     *
     * @param request the report request with custom date fields
     * @throws ValidationException if any required field is empty
     */
    private void validateCustomDateFieldsPresent(ReportRequest request) {
        // CORPT00C.cbl line 271: WHEN SDTMMI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getStartMonth())) {
            throw new ValidationException("startMonth",
                    "Start Date - Month can NOT be empty...");
        }
        // CORPT00C.cbl line 277: WHEN SDTDDI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getStartDay())) {
            throw new ValidationException("startDay",
                    "Start Date - Day can NOT be empty...");
        }
        // CORPT00C.cbl line 283: WHEN SDTYYYYI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getStartYear())) {
            throw new ValidationException("startYear",
                    "Start Date - Year can NOT be empty...");
        }
        // CORPT00C.cbl line 289: WHEN EDTMMI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getEndMonth())) {
            throw new ValidationException("endMonth",
                    "End Date - Month can NOT be empty...");
        }
        // CORPT00C.cbl line 295: WHEN EDTDDI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getEndDay())) {
            throw new ValidationException("endDay",
                    "End Date - Day can NOT be empty...");
        }
        // CORPT00C.cbl line 301: WHEN EDTYYYYI = SPACES OR LOW-VALUES
        if (!isFieldPresent(request.getEndYear())) {
            throw new ValidationException("endYear",
                    "End Date - Year can NOT be empty...");
        }
    }

    /**
     * Validates numeric content and range for a set of date components.
     *
     * <p>Translates the IF field IS NOT NUMERIC and range check blocks at
     * CORPT00C.cbl lines 330-380. Month must be 1-12, day must be 1-31,
     * year must be all-numeric.</p>
     *
     * @param month  normalized month string (2 digits)
     * @param day    normalized day string (2 digits)
     * @param year   normalized year string (4 digits)
     * @param prefix "Start" or "End" for error message construction
     * @throws ValidationException if any component fails validation
     */
    private void validateNumericAndRange(String month, String day,
                                         String year, String prefix) {
        // Month numeric check
        if (!isNumericString(month)) {
            throw new ValidationException(prefix.toLowerCase() + "Month",
                    prefix + " Date Month is not numeric...");
        }
        int monthVal = intValueOf(month);
        if (monthVal < 1 || monthVal > 12) {
            throw new ValidationException(prefix.toLowerCase() + "Month",
                    prefix + " Date Month must be 01-12...");
        }

        // Day numeric check
        if (!isNumericString(day)) {
            throw new ValidationException(prefix.toLowerCase() + "Day",
                    prefix + " Date Day is not numeric...");
        }
        int dayVal = intValueOf(day);
        if (dayVal < 1 || dayVal > 31) {
            throw new ValidationException(prefix.toLowerCase() + "Day",
                    prefix + " Date Day must be 01-31...");
        }

        // Year numeric check
        if (!isNumericString(year)) {
            throw new ValidationException(prefix.toLowerCase() + "Year",
                    prefix + " Date Year is not numeric...");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // General-purpose helper methods
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
     * Normalizes a numeric field to a zero-padded string.
     *
     * <p>Translates the COBOL pattern: {@code COMPUTE WS-NUM-99 =
     * FUNCTION NUMVAL-C(field)}, {@code MOVE WS-NUM-99 TO field}.
     * Converts user input like "3" to "03" for 2-digit fields and
     * "2024" stays "2024" for 4-digit fields.</p>
     *
     * @param field the raw input field value
     * @param width the target width (2 for month/day, 4 for year)
     * @return zero-padded numeric string, or trimmed original if non-numeric
     */
    private String normalizeNumericField(String field, int width) {
        if (field == null) {
            return "";
        }
        String trimmed = field.trim();
        try {
            int numValue = Integer.parseInt(trimmed);
            return String.format("%0" + width + "d", numValue);
        } catch (NumberFormatException ignored) {
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
     * @return the integer value, or 0 if not a valid integer
     */
    private int intValueOf(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Formats a LocalDate to YYYY-MM-DD string matching WS-START-DATE/WS-END-DATE.
     *
     * <p>The COBOL working storage defines:
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
     * <p>Translates the COBOL JOB-DATA structure (CORPT00C.cbl lines 87-131)
     * where PARM-START-DATE and PARM-END-DATE placeholders are filled with
     * the computed dates before writing to the TDQ.</p>
     *
     * @param startDate start date in YYYY-MM-DD format
     * @param endDate   end date in YYYY-MM-DD format
     * @return list of JCL lines with placeholders replaced and padded to 80 chars
     */
    private List<String> buildJclLines(String startDate, String endDate) {
        List<String> lines = new ArrayList<>(JCL_TEMPLATE_LINES.length);
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
     * @return the padded string, truncated if longer than target length
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

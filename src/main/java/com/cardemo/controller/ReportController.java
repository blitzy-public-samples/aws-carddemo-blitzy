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
package com.cardemo.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.common.exception.ValidationException;
import com.cardemo.service.online.ReportService;

/**
 * REST Controller for Transaction Reports — translates CICS transaction CR00
 * (CORPT00C.cbl) into stateless REST endpoints.
 *
 * <p>This controller exposes the transaction report functionality as a headless
 * REST API. The original COBOL program ({@code CORPT00C.cbl}) allows users to
 * select a report type (MONTHLY, YEARLY, or CUSTOM with date range), validates
 * date inputs, and submits a batch job to a Transient Data Queue (TDQ named
 * {@code 'JOBS'}) for report generation via the internal reader.</p>
 *
 * <p>In this Java translation, the TDQ write is replaced by Spring Batch
 * {@code JobLauncher} invocation in the delegated {@link ReportService}.</p>
 *
 * <h3>Endpoint Mapping</h3>
 * <table>
 *   <tr><th>HTTP</th><th>COBOL Paragraph</th><th>Description</th></tr>
 *   <tr><td>GET /api/reports</td>
 *       <td>SEND-TRNRPT-SCREEN (line 556)</td>
 *       <td>Returns available report types and metadata</td></tr>
 *   <tr><td>POST /api/reports</td>
 *       <td>PROCESS-ENTER-KEY (line 208) → SUBMIT-JOB-TO-INTRDR (line 462)</td>
 *       <td>Validates input and submits report generation job</td></tr>
 * </table>
 *
 * <h3>BMS Map Reference</h3>
 * <p>The BMS map {@code CORPT00.bms} defines the screen layout with:</p>
 * <ul>
 *   <li>MONTHLY — radio selector at position (7,10)</li>
 *   <li>YEARLY — radio selector at position (9,10)</li>
 *   <li>CUSTOM — radio selector at position (11,10)</li>
 *   <li>SDTMM/SDTDD/SDTYYYY — start date components (MM/DD/YYYY)</li>
 *   <li>EDTMM/EDTDD/EDTYYYY — end date components (MM/DD/YYYY)</li>
 *   <li>CONFIRM — confirmation field (Y/N)</li>
 *   <li>ERRMSG — error message display area</li>
 * </ul>
 *
 * <h3>Design Principles</h3>
 * <ul>
 *   <li><b>Stateless</b>: CICS pseudo-conversational model translated to
 *       stateless REST endpoints — no server-side session state</li>
 *   <li><b>Delegation only</b>: All business logic resides in
 *       {@link ReportService}; this controller performs only request mapping,
 *       response wrapping, and error handling</li>
 *   <li><b>No feature expansion</b>: Only GET and POST endpoints as specified
 *       in the Agent Action Plan — no additional operations</li>
 * </ul>
 *
 * @see ReportService
 * @see com.cardemo.batch.job.StatementGenJobConfig
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    /** SLF4J logger for structured logging with correlation IDs. */
    private static final Logger logger = LoggerFactory.getLogger(ReportController.class);

    /** Report service delegate — all business logic is delegated here. */
    private final ReportService reportService;

    /**
     * Constructs the ReportController with required dependencies via
     * constructor injection.
     *
     * <p>Spring automatically resolves the {@link ReportService} bean from the
     * application context. No {@code @Autowired} annotation is needed when
     * there is a single constructor (Spring 4.3+ implicit injection).</p>
     *
     * @param reportService the report service for business logic delegation;
     *                      translates CORPT00C.cbl paragraphs into Java methods
     */
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/reports — Available report types
    // Maps to: SEND-TRNRPT-SCREEN initial presentation (CORPT00C.cbl line 556)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns the available report types for the transaction report screen.
     *
     * <p>Maps to the initial SEND-TRNRPT-SCREEN presentation in
     * CORPT00C.cbl (line 556), which displays the report selection screen
     * with three radio-selector options. The labels are taken directly from
     * the BMS map (CORPT00.bms) DFHMDF INITIAL values:</p>
     * <ul>
     *   <li>MONTHLY — "Monthly (Current Month)" (BMS line 93)</li>
     *   <li>YEARLY — "Yearly (Current Year)" (BMS line 107)</li>
     *   <li>CUSTOM — "Custom (Date Range)" (BMS line 121)</li>
     * </ul>
     *
     * @return 200 OK with a list of available report type descriptors
     */
    @GetMapping
    public ResponseEntity<List<ReportTypeInfo>> getAvailableReportTypes() {
        logger.info("GET /api/reports — returning available report types");

        List<ReportTypeInfo> reportTypes = List.of(
                new ReportTypeInfo(
                        "MONTHLY",
                        "Monthly (Current Month)",
                        "Generate transaction report for the current calendar month "
                                + "(first day to last day). Date range is computed automatically."
                ),
                new ReportTypeInfo(
                        "YEARLY",
                        "Yearly (Current Year)",
                        "Generate transaction report for the current calendar year "
                                + "(January 1 to December 31). Date range is computed automatically."
                ),
                new ReportTypeInfo(
                        "CUSTOM",
                        "Custom (Date Range)",
                        "Generate transaction report for a user-specified date range. "
                                + "Provide startDate and endDate in MM/DD/YYYY format."
                )
        );

        logger.info("Returning {} report types", reportTypes.size());
        return ResponseEntity.ok(reportTypes);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POST /api/reports — Submit report request
    // Maps to: MAIN-PARA (line 163) → PROCESS-ENTER-KEY (line 208)
    //          → SUBMIT-JOB-TO-INTRDR (line 462)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Submits a report generation request.
     *
     * <p>Maps to the COBOL MAIN-PARA (line 163) → PROCESS-ENTER-KEY (line 208)
     * → SUBMIT-JOB-TO-INTRDR (line 462) flow in CORPT00C.cbl.</p>
     *
     * <h3>Processing Flow</h3>
     * <ol>
     *   <li>Maps the simplified REST request DTO to the service's internal
     *       {@link ReportService.ReportRequest} format, translating:
     *       <ul>
     *         <li>{@code reportType} → {@code monthly/yearly/custom} flags</li>
     *         <li>{@code startDate} (MM/DD/YYYY) → individual month/day/year fields</li>
     *         <li>{@code endDate} (MM/DD/YYYY) → individual month/day/year fields</li>
     *         <li>{@code confirmed} → confirmation flag ("Y"/"N")</li>
     *       </ul>
     *   </li>
     *   <li>Delegates to {@link ReportService#processEnterKey} for report type
     *       routing, date computation (MONTHLY/YEARLY) or validation (CUSTOM),
     *       and initial job submission attempt</li>
     *   <li>If the service returns a confirmation-required result and the REST
     *       request already includes {@code confirmed=true}, directly invokes
     *       {@link ReportService#submitJobToIntrdr} to complete the submission
     *       (bypassing the two-step CICS pseudo-conversational confirmation flow)</li>
     *   <li>Returns the report result on success, or an appropriate HTTP error
     *       response for validation failures or unexpected errors</li>
     * </ol>
     *
     * <h3>Error Handling</h3>
     * <ul>
     *   <li>{@link ValidationException} → HTTP 400 Bad Request
     *       (invalid date range, invalid report type, blank required fields)</li>
     *   <li>General {@link Exception} → HTTP 500 Internal Server Error
     *       (unexpected failures during processing)</li>
     * </ul>
     *
     * @param request the report submission request containing report type,
     *                optional date range (for CUSTOM), and confirmation flag
     * @return 200 OK with report result on success,
     *         400 Bad Request for validation errors,
     *         or 500 Internal Server Error for unexpected failures
     */
    @PostMapping
    public ResponseEntity<Object> submitReport(@RequestBody ReportSubmitRequest request) {
        logger.info("POST /api/reports — reportType={}, confirmed={}",
                request.getReportType(), request.isConfirmed());

        // Log date range for CUSTOM report type (structured logging per AAP)
        if ("CUSTOM".equalsIgnoreCase(request.getReportType())) {
            logger.info("Custom report date range: startDate={}, endDate={}",
                    request.getStartDate(), request.getEndDate());
        }

        try {
            // Map simplified REST DTO → service-layer ReportRequest
            ReportService.ReportRequest serviceRequest = mapToServiceRequest(request);

            // Delegate to PROCESS-ENTER-KEY (CORPT00C.cbl line 208)
            // This handles report type routing, date computation/validation,
            // and initial job submission via SUBMIT-JOB-TO-INTRDR
            ReportService.ReportResult result = reportService.processEnterKey(serviceRequest);

            // Handle confirmation flow for stateless REST:
            // In CICS pseudo-conversational, confirmation is a two-screen flow.
            // In REST, the client can include confirmed=true in the initial request.
            // If the service returned confirmationRequired but the REST request
            // already has confirmation, directly invoke SUBMIT-JOB-TO-INTRDR
            // (CORPT00C.cbl line 462) to complete the submission.
            if (result.isConfirmationRequired() && request.isConfirmed()) {
                serviceRequest.setConfirmation("Y");
                result = reportService.submitJobToIntrdr(serviceRequest);
            }

            // Check if the service returned an error condition
            // (e.g., validation error caught internally by processEnterKey)
            if (result.isError()) {
                logger.warn("Report processing returned error: {}", result.getMessage());
                return ResponseEntity.badRequest().body(result);
            }

            logger.info("Report request processed successfully: reportName={}, submitted={}",
                    result.getReportName(), result.isSubmitted());
            return ResponseEntity.ok(result);

        } catch (ValidationException ex) {
            // ValidationException → HTTP 400 Bad Request
            // Maps to COBOL field validation errors: invalid date range components,
            // invalid report type, blank required fields in CUSTOM date entry
            logger.warn("Validation failed for report submission: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));

        } catch (Exception ex) {
            // General exception → HTTP 500 Internal Server Error
            // Maps to COBOL WIRTE-JOBSUB-TDQ RESP handling (line 525-535)
            // where non-NORMAL RESP codes result in error display
            logger.error("Unexpected error during report submission: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(
                            "Internal server error occurred while processing report request"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helper: REST request → Service request mapping
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Maps the simplified REST request DTO to the service's internal request format.
     *
     * <p>Translates the flat REST fields ({@code reportType}, {@code startDate},
     * {@code endDate}, {@code confirmed}) into the BMS-equivalent individual
     * fields used by {@link ReportService.ReportRequest}.</p>
     *
     * <p>Field mapping:</p>
     * <ul>
     *   <li>{@code reportType="MONTHLY"} → sets {@code monthly="X"}
     *       (MONTHLYI field non-blank indicates selection)</li>
     *   <li>{@code reportType="YEARLY"} → sets {@code yearly="X"}
     *       (YEARLYI field non-blank indicates selection)</li>
     *   <li>{@code reportType="CUSTOM"} → sets {@code custom="X"} plus
     *       parses date components from MM/DD/YYYY strings</li>
     *   <li>{@code confirmed=true} → sets {@code confirmation="Y"}
     *       (CONFIRMI BMS field)</li>
     * </ul>
     *
     * @param request the REST request DTO
     * @return the mapped service request with BMS-equivalent fields populated
     */
    private ReportService.ReportRequest mapToServiceRequest(ReportSubmitRequest request) {
        ReportService.ReportRequest serviceRequest = new ReportService.ReportRequest();

        String reportType = request.getReportType();
        if ("MONTHLY".equalsIgnoreCase(reportType)) {
            // MONTHLYI OF CORPT0AI NOT = SPACES → field is present
            serviceRequest.setMonthly("X");
        } else if ("YEARLY".equalsIgnoreCase(reportType)) {
            // YEARLYI OF CORPT0AI NOT = SPACES → field is present
            serviceRequest.setYearly("X");
        } else if ("CUSTOM".equalsIgnoreCase(reportType)) {
            // CUSTOMI OF CORPT0AI NOT = SPACES → field is present
            serviceRequest.setCustom("X");
            // Parse start date MM/DD/YYYY → individual SDTMM/SDTDD/SDTYYYY components
            parseAndSetDateComponents(request.getStartDate(), serviceRequest, true);
            // Parse end date MM/DD/YYYY → individual EDTMM/EDTDD/EDTYYYY components
            parseAndSetDateComponents(request.getEndDate(), serviceRequest, false);
        }

        // Map confirmation boolean → "Y" string (CONFIRMI BMS field value)
        if (request.isConfirmed()) {
            serviceRequest.setConfirmation("Y");
        }

        return serviceRequest;
    }

    /**
     * Parses an MM/DD/YYYY date string and sets the individual component fields
     * on the service request.
     *
     * <p>Maps the REST date format (MM/DD/YYYY) to the BMS screen individual
     * fields from CORPT00.bms:</p>
     * <ul>
     *   <li>Start: SDTMM (line 127) / SDTDD (line 138) / SDTYYYY (line 149)</li>
     *   <li>End: EDTMM (line 166) / EDTDD (line 177) / EDTYYYY (line 188)</li>
     * </ul>
     *
     * <p>If the date string is null, blank, or not in the expected 3-part format,
     * the fields are left unset (null), which the service layer will detect as
     * empty/missing fields during validation.</p>
     *
     * @param dateStr   the date string in MM/DD/YYYY format, may be null
     * @param request   the service request to populate
     * @param isStart   true for start date fields (SDT*), false for end date fields (EDT*)
     */
    private void parseAndSetDateComponents(String dateStr,
                                           ReportService.ReportRequest request,
                                           boolean isStart) {
        if (dateStr == null || dateStr.isBlank()) {
            return;
        }

        String[] parts = dateStr.split("/");
        if (parts.length == 3) {
            if (isStart) {
                request.setStartMonth(parts[0]);
                request.setStartDay(parts[1]);
                request.setStartYear(parts[2]);
            } else {
                request.setEndMonth(parts[0]);
                request.setEndDay(parts[1]);
                request.setEndYear(parts[2]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Inner DTO: ReportSubmitRequest — POST request body
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Request DTO for the POST /api/reports endpoint.
     *
     * <p>Provides a simplified REST API contract that maps to the BMS screen
     * input fields from CORPT00.bms. The controller translates this flat DTO
     * into the service's {@link ReportService.ReportRequest} which preserves
     * the individual BMS field structure.</p>
     *
     * <h3>Field Mapping from BMS (CORPT00.bms)</h3>
     * <ul>
     *   <li>{@code reportType} → MONTHLYI / YEARLYI / CUSTOMI radio selectors</li>
     *   <li>{@code startDate} → SDTMM / SDTDD / SDTYYYY components</li>
     *   <li>{@code endDate} → EDTMM / EDTDD / EDTYYYY components</li>
     *   <li>{@code confirmed} → CONFIRMI field (Y/N)</li>
     * </ul>
     *
     * <h3>Usage Examples</h3>
     * <pre>{@code
     * // Monthly report (no dates needed — computed automatically)
     * { "reportType": "MONTHLY", "confirmed": true }
     *
     * // Yearly report (no dates needed — computed automatically)
     * { "reportType": "YEARLY", "confirmed": true }
     *
     * // Custom report with date range
     * { "reportType": "CUSTOM", "startDate": "01/01/2026",
     *   "endDate": "03/31/2026", "confirmed": true }
     * }</pre>
     */
    public static class ReportSubmitRequest {

        /**
         * Report type selector: "MONTHLY", "YEARLY", or "CUSTOM".
         * Maps to WS-REPORT-NAME PIC X(10) in CORPT00C.cbl.
         */
        private String reportType;

        /**
         * Start date for CUSTOM reports in MM/DD/YYYY format.
         * Maps to SDTMM/SDTDD/SDTYYYY BMS fields.
         * Ignored for MONTHLY and YEARLY report types.
         */
        private String startDate;

        /**
         * End date for CUSTOM reports in MM/DD/YYYY format.
         * Maps to EDTMM/EDTDD/EDTYYYY BMS fields.
         * Ignored for MONTHLY and YEARLY report types.
         */
        private String endDate;

        /**
         * Confirmation flag. When true, the report job is submitted immediately
         * without requiring a separate confirmation step.
         * Maps to CONFIRMI BMS field (Y/N) at position (19,66).
         */
        private boolean confirmed;

        /** Default constructor for Jackson deserialization. */
        public ReportSubmitRequest() {
            // Default constructor required for JSON deserialization
        }

        /**
         * Returns the report type selection.
         * @return "MONTHLY", "YEARLY", or "CUSTOM"
         */
        public String getReportType() {
            return reportType;
        }

        /**
         * Sets the report type selection.
         * @param reportType the report type: "MONTHLY", "YEARLY", or "CUSTOM"
         */
        public void setReportType(String reportType) {
            this.reportType = reportType;
        }

        /**
         * Returns the start date for CUSTOM reports.
         * @return start date in MM/DD/YYYY format, or null if not applicable
         */
        public String getStartDate() {
            return startDate;
        }

        /**
         * Sets the start date for CUSTOM reports.
         * @param startDate the start date in MM/DD/YYYY format
         */
        public void setStartDate(String startDate) {
            this.startDate = startDate;
        }

        /**
         * Returns the end date for CUSTOM reports.
         * @return end date in MM/DD/YYYY format, or null if not applicable
         */
        public String getEndDate() {
            return endDate;
        }

        /**
         * Sets the end date for CUSTOM reports.
         * @param endDate the end date in MM/DD/YYYY format
         */
        public void setEndDate(String endDate) {
            this.endDate = endDate;
        }

        /**
         * Returns whether the user has confirmed report submission.
         * @return true if confirmed, false otherwise
         */
        public boolean isConfirmed() {
            return confirmed;
        }

        /**
         * Sets the confirmation flag.
         * @param confirmed true to confirm report submission
         */
        public void setConfirmed(boolean confirmed) {
            this.confirmed = confirmed;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Inner DTO: ReportTypeInfo — GET response item
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Response DTO describing an available report type.
     *
     * <p>Each instance represents one of the three report types available on
     * the CORPT00.bms screen. The {@code code} field maps to the COBOL
     * report type evaluation logic, the {@code name} field matches the BMS
     * DFHMDF INITIAL values, and the {@code description} provides additional
     * context for API consumers.</p>
     */
    public static class ReportTypeInfo {

        /** Report type code: "MONTHLY", "YEARLY", or "CUSTOM". */
        private final String code;

        /** Display name matching the BMS map DFHMDF INITIAL value. */
        private final String name;

        /** Human-readable description for API consumers. */
        private final String description;

        /**
         * Constructs a ReportTypeInfo instance.
         *
         * @param code        the report type code (e.g., "MONTHLY")
         * @param name        the display name (e.g., "Monthly (Current Month)")
         * @param description additional description for API consumers
         */
        public ReportTypeInfo(String code, String name, String description) {
            this.code = code;
            this.name = name;
            this.description = description;
        }

        /**
         * Returns the report type code.
         * @return the report type code
         */
        public String getCode() {
            return code;
        }

        /**
         * Returns the display name.
         * @return the display name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the description.
         * @return the description
         */
        public String getDescription() {
            return description;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Inner DTO: ErrorResponse — Error response body
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Error response DTO for HTTP 400/500 responses.
     *
     * <p>Provides a consistent error response format for both validation
     * errors (400 Bad Request from {@link ValidationException}) and
     * unexpected errors (500 Internal Server Error). The {@code error}
     * field is always {@code true} to distinguish error responses from
     * successful {@link ReportService.ReportResult} responses.</p>
     */
    public static class ErrorResponse {

        /** Always true to indicate this is an error response. */
        private final boolean error;

        /** Human-readable error message describing the failure. */
        private final String message;

        /**
         * Constructs an ErrorResponse with the specified message.
         *
         * @param message the error message describing the failure
         */
        public ErrorResponse(String message) {
            this.error = true;
            this.message = message;
        }

        /**
         * Returns whether this is an error response.
         * @return always true
         */
        public boolean isError() {
            return error;
        }

        /**
         * Returns the error message.
         * @return the error message
         */
        public String getMessage() {
            return message;
        }
    }
}

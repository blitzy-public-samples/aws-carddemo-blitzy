/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
 *
 * Part of the Java / Spring Boot re-platform of the AWS CardDemo mainframe
 * application. This REST controller re-expresses a CICS online program's screen
 * interaction as HTTP request/response, preserving the original field-level and
 * PF-key contracts with no feature expansion.
 */
package com.aws.carddemo.web;

import java.time.LocalDateTime;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.TransactionReportRequest;
import com.aws.carddemo.dto.TransactionReportResponse;
import com.aws.carddemo.mapper.ReportMapper;
import com.aws.carddemo.service.ReportService;
import com.aws.carddemo.service.ReportService.ReportRequest;
import com.aws.carddemo.service.ReportService.ReportResult;
import com.aws.carddemo.service.ReportService.ReportType;

/**
 * REST controller for the Transaction Reports screen &mdash; the Java re-platform of the
 * CardDemo COBOL online program {@code CORPT00C} (CICS transaction {@code CR00}; BMS map
 * {@code CORPT0A} / mapset {@code CORPT00}; source {@code legacy/cbl/CORPT00C.cbl},
 * formerly {@code app/cbl/CORPT00C.cbl}). It reproduces, with no feature expansion, the
 * request-entry behavior of that program per AAP &sect;0.5.3.
 *
 * <p>On the mainframe the operator chose one of three mutually exclusive report types
 * (monthly, yearly, or a custom start/end date range) and confirmed submission with a
 * {@code Y}/{@code N} flag; on confirmation the program submitted a batch job (paragraph
 * {@code SUBMIT-JOB-TO-INTRDR}, which wrote generated JCL to the JES internal reader).
 * <strong>This controller renders no report and launches no job.</strong> All request
 * validation, the confirmation gate, and the actual batch-job submission live in
 * {@link ReportService} (which owns the injected {@code JobLauncher} and the
 * {@code transactionReportJob} bean). Screen navigation and PF-key routing &mdash; the
 * concerns the COBOL {@code MAIN-PARA} handled around its {@code EVALUATE EIBAID} &mdash;
 * are this controller's responsibility.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li><strong>{@code GET /api/v1/reports/transactions}</strong> &mdash; the blank
 *       report-request screen (the COBOL first-entry {@code SEND MAP} with
 *       {@code LOW-VALUES}: report-type options, current date/time header, no message).</li>
 *   <li><strong>{@code POST /api/v1/reports/transactions}</strong> &mdash; a screen
 *       submission, routed on the transmitted Attention Identifier
 *       ({@link TransactionReportRequest#action()}).</li>
 * </ul>
 *
 * <h2>PF-key routing (parity with {@code CORPT00C} {@code EVALUATE EIBAID})</h2>
 * <ul>
 *   <li>{@link PfKeyAction#ENTER} (and, by the package-wide default-Enter convention, a
 *       {@code null} action) &rarr; {@code PROCESS-ENTER-KEY}: build the service request
 *       and submit through {@link ReportService}.</li>
 *   <li>{@link PfKeyAction#PF3} &rarr; navigate back to the Main Menu program
 *       ({@code COMEN01C} / transaction {@code CM00}); the COBOL performed
 *       {@code XCTL PROGRAM('COMEN01C')}. Because the re-platform is stateless HTTP with
 *       no {@code XCTL}, the navigation target is surfaced as response headers
 *       ({@value #NAV_PROGRAM_HEADER} / {@value #NAV_TRANSACTION_HEADER}).</li>
 *   <li>Any other key &rarr; the legacy {@code CCDA-MSG-INVALID-KEY} message
 *       (&quot;{@value #MSG_INVALID_KEY}&quot;, {@code legacy/cpy/CSMSG01Y.cpy}).</li>
 * </ul>
 *
 * <h2>Report-type selection and the custom date range</h2>
 * The three selection markers ({@link TransactionReportRequest#monthly()},
 * {@link TransactionReportRequest#yearly()}, {@link TransactionReportRequest#custom()})
 * map to {@link ReportType}. The COBOL {@code PROCESS-ENTER-KEY} {@code EVALUATE TRUE}
 * tested them in the fixed order monthly, then yearly, then custom, and fell through to a
 * &quot;select a report type&quot; message when none was set; that precedence and the
 * &quot;none selected&quot; outcome are preserved here (an unset type is passed to the
 * service as {@code null}). For {@link ReportType#CUSTOM} the discrete month/day/year
 * parts are passed through <em>verbatim</em> to the service as {@code year-month-day}
 * strings &mdash; the exact shape {@link ReportService} decomposes and validates
 * field-by-field &mdash; so that the service can reproduce every granular COBOL message
 * (for example distinguishing &quot;Month can NOT be empty&quot; from &quot;Not a valid
 * Month&quot;) and their first-failure-wins order. The parts are deliberately <em>not</em>
 * pre-composed via {@link ReportMapper#startDate(TransactionReportRequest)} here, because
 * collapsing them into a single {@code LocalDate} (or {@code null}) would lose the
 * blank-versus-invalid distinction the legacy messages depend on. Monthly and yearly
 * requests carry no operator dates (the service derives the range from the current date),
 * so their date arguments are {@code null}.
 *
 * <h2>Design</h2>
 * A thin, stateless controller: constructor injection only (no field injection, no
 * Lombok); parameterized generics throughout; explicit imports only (no wildcards, never
 * {@code javax.*}). There is no {@code try}/{@code catch}: every business outcome
 * (validation error, confirmation prompt, cancel, or submission result) is returned by the
 * service as a {@link ReportResult} message and surfaced as an HTTP {@code 200 OK} with the
 * message projected onto the response, exactly mirroring the COBOL &quot;re-display the
 * screen with a message&quot; behavior. Bean-validation failures on the request body are
 * handled by the application-wide exception handler, not here.
 */
@RestController
@RequestMapping("/api/v1/reports/transactions")
public class TransactionReportController {

    /**
     * This program's COBOL name ({@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'},
     * {@code legacy/cbl/CORPT00C.cbl:L37}). Retained for traceability.
     */
    static final String PROGRAM_NAME = "CORPT00C";

    /**
     * This program's CICS transaction id ({@code WS-TRANID PIC X(04) VALUE 'CR00'},
     * {@code legacy/cbl/CORPT00C.cbl:L38}). Retained for traceability.
     */
    static final String TRANSACTION_ID = "CR00";

    /**
     * The Main Menu program that PF3 returns to ({@code XCTL PROGRAM('COMEN01C')},
     * {@code legacy/cbl/CORPT00C.cbl:L188}).
     */
    static final String MENU_PROGRAM = "COMEN01C";

    /** The Main Menu program's CICS transaction id ({@code CM00}). */
    static final String MENU_TRANSACTION = "CM00";

    /**
     * Response header carrying the navigation target program name (the stateless-HTTP
     * stand-in for the COBOL {@code XCTL PROGRAM(...)}). Named consistently with the
     * application's other custom headers (for example {@code X-Correlation-Id}).
     */
    static final String NAV_PROGRAM_HEADER = "X-CardDemo-Next-Program";

    /** Response header carrying the navigation target's CICS transaction id. */
    static final String NAV_TRANSACTION_HEADER = "X-CardDemo-Next-Transaction";

    /**
     * The exact {@code CCDA-MSG-INVALID-KEY} literal from {@code legacy/cpy/CSMSG01Y.cpy}
     * (the padding to {@code PIC X(50)} is a 3270-display concern and is not reproduced,
     * matching how the re-platform carries un-padded logical text).
     */
    static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Separator used to assemble the discrete custom-range date parts into the
     * {@code year-month-day} string that {@link ReportService} decomposes and validates.
     */
    private static final String DATE_PART_SEPARATOR = "-";

    /** Report-request business logic and batch-job submission (never {@code null}). */
    private final ReportService reportService;

    /** Screen header / response projection assembler (never {@code null}). */
    private final ReportMapper reportMapper;

    /**
     * Creates the controller with its collaborators. Constructor injection only; the body
     * merely stores the references and invokes no overridable method, so it is safe against
     * construction-time {@code this}-escape.
     *
     * @param reportService the report-request service (validation, confirmation, and batch
     *                      submission); must not be {@code null}
     * @param reportMapper  the mapper that assembles the screen header and message response;
     *                      must not be {@code null}
     */
    public TransactionReportController(ReportService reportService, ReportMapper reportMapper) {
        this.reportService = reportService;
        this.reportMapper = reportMapper;
    }

    /**
     * Returns the blank Transaction Reports request screen. Reproduces the COBOL
     * first-entry path in {@code MAIN-PARA} that, when the program was entered fresh
     * (not re-entered), moved {@code LOW-VALUES} to the map and sent it: the report-type
     * options are unset, no status/error message is shown, and the header carries the
     * current date and time.
     *
     * @return {@code 200 OK} with the blank report-request screen projection
     */
    @GetMapping
    public ResponseEntity<TransactionReportResponse> showReportRequestScreen() {
        return ResponseEntity.ok(reportMapper.toResponse(null, LocalDateTime.now()));
    }

    /**
     * Handles a Transaction Reports screen submission. Reproduces the COBOL
     * {@code MAIN-PARA} {@code EVALUATE EIBAID} dispatch: the Enter key (or, per the
     * package default-Enter convention, an absent action) processes the request; PF3
     * navigates back to the Main Menu; any other key yields the invalid-key message.
     *
     * @param request the report-request screen input (report-type markers, discrete
     *                custom-range date parts, confirmation flag, and the pressed key);
     *                validated with Bean Validation via {@link Valid}
     * @return {@code 200 OK} with the resulting screen projection; PF3 additionally sets the
     *         navigation headers pointing at {@link #MENU_PROGRAM} / {@link #MENU_TRANSACTION}
     */
    @PostMapping
    public ResponseEntity<TransactionReportResponse> requestReport(
            @Valid @RequestBody TransactionReportRequest request) {
        final PfKeyAction action = request.action();
        if (action == PfKeyAction.PF3) {
            return navigateToMainMenu();
        }
        if (action == null || action == PfKeyAction.ENTER) {
            return processEnter(request);
        }
        return invalidKey();
    }

    /**
     * Reproduces the COBOL {@code PROCESS-ENTER-KEY} paragraph. Resolves the selected
     * report type (monthly &rarr; yearly &rarr; custom precedence, or {@code null} when none
     * is selected), assembles the custom start/end dates from their discrete parts when the
     * type is {@link ReportType#CUSTOM}, delegates validation, the confirmation gate, and
     * job submission to {@link ReportService#requestReport(ReportRequest)}, and projects the
     * returned message onto the screen response.
     *
     * @param request the report-request screen input
     * @return {@code 200 OK} with the service's outcome message projected onto the screen
     */
    private ResponseEntity<TransactionReportResponse> processEnter(TransactionReportRequest request) {
        final ReportType type = resolveReportType(request);

        String startDate = null;
        String endDate = null;
        if (type == ReportType.CUSTOM) {
            startDate = joinDateParts(request.startYear(), request.startMonth(), request.startDay());
            endDate = joinDateParts(request.endYear(), request.endMonth(), request.endDay());
        }

        final ReportRequest reportRequest = new ReportRequest(type, startDate, endDate, request.confirm());
        final ReportResult result = reportService.requestReport(reportRequest);
        return ResponseEntity.ok(reportMapper.toResponse(result.message(), LocalDateTime.now()));
    }

    /**
     * Builds the PF3 &quot;back to Main Menu&quot; response. The COBOL performed
     * {@code XCTL PROGRAM('COMEN01C')}; in the stateless-HTTP re-platform the navigation
     * target is conveyed via response headers while the body carries the current
     * (message-free) report screen projection for a uniform response shape.
     *
     * @return {@code 200 OK} with the navigation headers set to the Main Menu program /
     *         transaction and a message-free screen body
     */
    private ResponseEntity<TransactionReportResponse> navigateToMainMenu() {
        return ResponseEntity.ok()
                .header(NAV_PROGRAM_HEADER, MENU_PROGRAM)
                .header(NAV_TRANSACTION_HEADER, MENU_TRANSACTION)
                .body(reportMapper.toResponse(null, LocalDateTime.now()));
    }

    /**
     * Builds the &quot;invalid key&quot; response for any Attention Identifier other than
     * Enter or PF3, reproducing the COBOL {@code WHEN OTHER} branch that moved
     * {@code CCDA-MSG-INVALID-KEY} to the message field and re-sent the screen.
     *
     * @return {@code 200 OK} with the invalid-key message projected onto the screen
     */
    private ResponseEntity<TransactionReportResponse> invalidKey() {
        return ResponseEntity.ok(reportMapper.toResponse(MSG_INVALID_KEY, LocalDateTime.now()));
    }

    /**
     * Resolves the selected {@link ReportType} from the three mutually exclusive selection
     * markers, honoring the COBOL {@code EVALUATE TRUE} precedence (monthly, then yearly,
     * then custom). A marker counts as &quot;selected&quot; when it is non-{@code null} and
     * not blank, mirroring the legacy {@code NOT = SPACES AND LOW-VALUES} test. When no
     * marker is set, {@code null} is returned so the service reproduces the legacy
     * &quot;Select a report type to print report...&quot; outcome.
     *
     * @param request the report-request screen input
     * @return the selected report type, or {@code null} when none is selected
     */
    private static ReportType resolveReportType(TransactionReportRequest request) {
        if (notBlank(request.monthly())) {
            return ReportType.MONTHLY;
        }
        if (notBlank(request.yearly())) {
            return ReportType.YEARLY;
        }
        if (notBlank(request.custom())) {
            return ReportType.CUSTOM;
        }
        return null;
    }

    /**
     * Assembles the three discrete date parts into the {@code year-month-day} string that
     * {@link ReportService} decomposes and validates field-by-field. Each part is passed
     * through unchanged (only {@code null} is normalized to an empty token) so the service
     * sees exactly what the operator entered and can emit the precise per-field COBOL
     * message; the service owns all numeric, range, and calendar validation.
     *
     * @param year  the year part; may be {@code null} or blank
     * @param month the month part; may be {@code null} or blank
     * @param day   the day part; may be {@code null} or blank
     * @return the {@code year-month-day} composition (never {@code null})
     */
    private static String joinDateParts(String year, String month, String day) {
        return nullToEmpty(year) + DATE_PART_SEPARATOR + nullToEmpty(month) + DATE_PART_SEPARATOR + nullToEmpty(day);
    }

    /**
     * Null-safe blank test &mdash; the analog of the COBOL {@code NOT = SPACES AND
     * LOW-VALUES} selection test.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} when {@code value} is non-{@code null} and contains a
     *         non-whitespace character
     */
    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Returns the given value, or the empty string when it is {@code null}. Used so a
     * missing date part becomes an empty token in the assembled {@code year-month-day}
     * string rather than the literal text {@code "null"}.
     *
     * @param value the value to normalize; may be {@code null}
     * @return {@code value}, or {@code ""} when {@code value} is {@code null}
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

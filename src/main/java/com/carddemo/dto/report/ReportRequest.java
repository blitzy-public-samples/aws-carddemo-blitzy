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

package com.carddemo.dto.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Report submission request DTO — inbound payload for {@code POST /api/reports}.
 *
 * <p>Derived from the BMS symbolic copybook {@code app/cpy-bms/CORPT00.CPY} (input view
 * {@code CORPT0AI}) and the COBOL program {@code app/cbl/CORPT00C.cbl}. Replaces the
 * mainframe pseudo-conversational flow where the user fills the 3270 BMS screen and the
 * program submits a JCL via {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}; in the modernized
 * REST flow this DTO is JSON-deserialized by {@code ReportController}, validated by Spring
 * {@code @Valid}, and consumed by {@code ReportService} which constructs {@code JobParameters}
 * for an async {@code JobLauncher.run(...)} invocation (returning HTTP 202 Accepted).
 *
 * <p>Field consolidation (modernization improvements per AAP §0.4.1.7):
 * <pre>
 *   COBOL field (CORPT00.CPY)                            Java field
 *   ─────────────────────────                            ──────────
 *   MONTHLYI / YEARLYI / CUSTOMI  PIC X(1) × 3   ─→     reportType    (ReportType enum)
 *   SDTMMI / SDTDDI / SDTYYYYI    PIC X(2)+X(2)+X(4) ─→ startDate     (LocalDate)
 *   EDTMMI / EDTDDI / EDTYYYYI    PIC X(2)+X(2)+X(4) ─→ endDate       (LocalDate)
 *   CONFIRMI                      PIC X(1)         ─→  confirmation  (String, [YyNn])
 *   (no COBOL equivalent)                            ─→ outputFormat  (OutputFormat enum, optional)
 * </pre>
 *
 * <p><b>Validation strategy:</b>
 * <ul>
 *   <li>{@code @NotNull} on {@code reportType}, {@code startDate}, {@code endDate} — required</li>
 *   <li>{@code @NotBlank @Size(min=1, max=1) @Pattern(regexp="[YyNn]")} on {@code confirmation}
 *       — preserves COBOL case-insensitive Y/y/N/n semantics (see CORPT00C.cbl lines 478-494)</li>
 *   <li>{@code outputFormat} is nullable — {@code ReportService} applies a sensible default if absent</li>
 *   <li>{@code @AssertTrue} cross-field method {@code isEndDateOnOrAfterStartDate()} enforces
 *       {@code endDate >= startDate} for {@code CUSTOM} reports (mirrors COBOL validation at
 *       CORPT00C.cbl lines 256-303)</li>
 *   <li>For {@code MONTHLY} and {@code YEARLY} reports, the COBOL program auto-derives date
 *       boundaries (CORPT00C.cbl lines 213-255). The Java {@code ReportService} replicates this
 *       derivation, optionally normalizing client-supplied dates. The DTO itself does not enforce
 *       month/year alignment — that is a service-layer concern per folder requirements.</li>
 * </ul>
 *
 * <p><b>Async semantics:</b> {@code ReportController} returns HTTP 202 Accepted with a
 * {@code jobExecutionId} immediately after enqueueing the batch job; the actual report
 * generation runs asynchronously. The DTO is just the parameter carrier — async job execution
 * is a service-layer concern (see {@code ReportService.submitReportJob(ReportRequest)} which
 * uses {@code @Async} annotation per AAP §0.4.1.1).
 *
 * <p>Example JSON request body:
 * <pre>
 *   {
 *     "reportType": "CUSTOM",
 *     "startDate":  "2024-01-01",
 *     "endDate":    "2024-01-31",
 *     "outputFormat": "PDF",
 *     "confirmation": "Y"
 *   }
 * </pre>
 *
 * <p>Example JSON request body (auto-derived monthly report):
 * <pre>
 *   {
 *     "reportType": "MONTHLY",
 *     "startDate":  "2024-01-01",
 *     "endDate":    "2024-01-31",
 *     "confirmation": "Y"
 *   }
 * </pre>
 *
 * @see ReportType
 * @see OutputFormat
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Report generation request — inbound payload for POST /api/reports. " +
        "Consolidates 3 BMS X(1) flags into reportType enum and 6 BMS date parts into " +
        "startDate/endDate LocalDate fields (modernization improvements per AAP §0.4.1.7). " +
        "Async semantics: controller returns HTTP 202 Accepted after enqueueing the job; " +
        "report generation runs in the background via Spring Batch JobLauncher.")
public class ReportRequest {

    @NotNull(message = "reportType is required")
    @Schema(description = "Report-type selector. Consolidates COBOL MONTHLYI/YEARLYI/CUSTOMI " +
            "mutually-exclusive X(1) flags from CORPT00.CPY into a single type-safe enum. " +
            "MONTHLY/YEARLY auto-derive date ranges; CUSTOM requires explicit startDate/endDate.",
            example = "MONTHLY",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ReportType reportType;

    @NotNull(message = "startDate is required")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "Inclusive start date of the report period (ISO-8601 yyyy-MM-dd). " +
            "Consolidates COBOL SDTMMI X(2) + SDTDDI X(2) + SDTYYYYI X(4) from CORPT00.CPY " +
            "into a single LocalDate. For MONTHLY: aligned to month start (auto-derived); " +
            "for YEARLY: Jan 1 (auto-derived); for CUSTOM: client-supplied.",
            example = "2024-01-01",
            type = "string",
            format = "date",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate startDate;

    @NotNull(message = "endDate is required")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "Inclusive end date of the report period (ISO-8601 yyyy-MM-dd). " +
            "Consolidates COBOL EDTMMI X(2) + EDTDDI X(2) + EDTYYYYI X(4) from CORPT00.CPY " +
            "into a single LocalDate. For MONTHLY: aligned to month end (auto-derived); " +
            "for YEARLY: Dec 31 (auto-derived); for CUSTOM: client-supplied (must be >= startDate).",
            example = "2024-01-31",
            type = "string",
            format = "date",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate endDate;

    @Schema(description = "Optional output format selector (PDF, CSV, or HTML). " +
            "Modernization addition with no COBOL equivalent (original output was plain-text only). " +
            "If null, ReportService applies a sensible default (typically PDF).",
            example = "PDF",
            nullable = true)
    private OutputFormat outputFormat;

    @NotBlank(message = "confirmation is required")
    @Size(min = 1, max = 1, message = "confirmation must be exactly 1 character")
    @Pattern(regexp = "[YyNn]", message = "confirmation must be 'Y', 'y', 'N', or 'n'")
    @Schema(description = "Confirmation flag mirroring COBOL CONFIRMI PIC X(1) from CORPT00.CPY. " +
            "Allowed values: 'Y', 'y' (proceed with submission), 'N', 'n' (cancel). " +
            "Case-insensitive per COBOL CORPT00C.cbl lines 478-494 EVALUATE TRUE clause. " +
            "Service treats only 'Y'/'y' as affirmative; any other value cancels submission.",
            example = "Y",
            allowableValues = {"Y", "y", "N", "n"},
            minLength = 1,
            maxLength = 1,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String confirmation;

    /**
     * Cross-field validator ensuring {@code endDate} is on or after {@code startDate}.
     *
     * <p>Mirrors COBOL validation at {@code CORPT00C.cbl} lines 256-303 (the CUSTOM
     * branch of the {@code EVALUATE TRUE} dispatch on {@code CUSTOMI OF CORPT0AI}).
     * The COBOL code separately validates each date part (month 01-12, day 01-31,
     * year numeric, valid calendar date via {@code CSUTLDTC}); this Java method handles
     * the high-level constraint {@code endDate >= startDate} which is the most common
     * user error. Per-part validity is enforced by Jackson's date parser and Spring's
     * {@code @JsonFormat} during deserialization.
     *
     * <p>Behavior:
     * <ul>
     *   <li>Returns {@code true} if either date is {@code null} (defer to {@code @NotNull}
     *       constraints on the individual fields)</li>
     *   <li>Returns {@code true} if {@code endDate.isAfter(startDate)} OR
     *       {@code endDate.isEqual(startDate)}</li>
     *   <li>Returns {@code false} only if {@code endDate.isBefore(startDate)}</li>
     * </ul>
     *
     * <p>This validator runs as part of Spring's {@code @Valid} processing on the request body;
     * a {@code false} return triggers a 400 Bad Request response from
     * {@code GlobalExceptionHandler.handleMethodArgumentNotValid(...)}.
     *
     * <p>Note: For {@code MONTHLY} and {@code YEARLY} reportTypes, {@code ReportService}
     * may normalize client-supplied dates to month/year boundaries before passing to the
     * batch job — but the DTO-level constraint still applies.
     *
     * @return {@code true} if dates are valid relative to each other; {@code false} otherwise
     */
    @AssertTrue(message = "endDate must be on or after startDate")
    @JsonIgnore  // do NOT serialize this computed property; it's purely a validator
    public boolean isEndDateOnOrAfterStartDate() {
        if (startDate == null || endDate == null) {
            return true;  // null handling deferred to @NotNull constraints
        }
        return !endDate.isBefore(startDate);
    }
}

package com.carddemo.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Immutable JSON request body for the transaction-report submission operation
 * (<code>POST /reports</code>) handled by {@code ReportController}.
 *
 * <h2>Lineage</h2>
 * <p>This DTO is the Java&nbsp;17 re-expression of the input surface of the
 * legacy CICS online program {@code CORPT00C} and its BMS screen {@code CORPT00}.
 * On the mainframe the operator selected one of three mutually-exclusive report
 * options and (for the custom option) keyed a start and end date; the program
 * then assembled JCL and submitted a batch print job to the internal reader
 * ({@code SUBMIT-JOB-TO-INTRDR}). In the modernized stack that submission is
 * reproduced by {@code ReportService}, which launches a Spring Batch job
 * asynchronously via {@code JobLauncher} and returns a {@code ReportResponse}
 * carrying the {@code JobExecution} identifier (AAP&nbsp;&sect;0.3.2).</p>
 *
 * <h2>The three report modes</h2>
 * <p>{@code CORPT00C} exposes three radio-style screen flags — {@code MONTHLYI},
 * {@code YEARLYI}, and {@code CUSTOMI} — exactly one of which may be chosen. They
 * collapse cleanly into a single {@link #reportType()} discriminator:</p>
 * <ul>
 *   <li><strong>{@code MONTHLY}</strong> &rarr; the legacy program derives the range
 *       automatically from the current date (first day of the current month through
 *       the last day of the current month); {@link #startDate()} and
 *       {@link #endDate()} are <em>not</em> supplied by the client.</li>
 *   <li><strong>{@code YEARLY}</strong> &rarr; the range is auto-derived as
 *       {@code YYYY-01-01} through {@code YYYY-12-31} for the current year; again no
 *       client-supplied dates.</li>
 *   <li><strong>{@code CUSTOM}</strong> &rarr; the operator-entered start and end
 *       dates are used. On the 3270 screen each was keyed as separate month/day/year
 *       fields and validated as {@code YYYY-MM-DD} through the shared date utility
 *       {@code CSUTLDTC}; here they arrive as fully-formed {@link LocalDate} values.</li>
 * </ul>
 *
 * <h2>Why {@code startDate}/{@code endDate} carry no static annotation</h2>
 * <p>Their presence requirement is <strong>conditional</strong> on the value of
 * {@code reportType}: they are mandatory only when {@code reportType == "CUSTOM"}
 * and are ignored for {@code MONTHLY}/{@code YEARLY}. Bean Validation field
 * annotations cannot express such a cross-field rule, so this constraint is
 * deliberately enforced in {@code ReportService} (which also validates the dates
 * via {@code DateValidationService}, the {@code CSUTLDTC} equivalent, preserving the
 * legacy {@code YYYY-MM-DD} / leap-year / month-range parity). Keeping the rule out
 * of the DTO also keeps this type strictly <em>tier-0</em> — its only imports are
 * {@code java.time.LocalDate} and {@code jakarta.validation.constraints.*}.</p>
 *
 * <h2>Validation</h2>
 * <p>Only the unconditional constraints live here. {@link #reportType()} is
 * mandatory and must be one of the three recognized tokens; a {@code null} value is
 * caught by {@link NotNull} (because {@link Pattern} treats {@code null} as valid per
 * the Bean Validation specification), while a blank or unrecognized value is caught by
 * {@link Pattern} (whose {@code java.util.regex} match is whole-string anchored).
 * Either violation is surfaced as HTTP&nbsp;400 by the {@code GlobalExceptionHandler}
 * when the request is bound with {@code @Valid @RequestBody}.</p>
 *
 * <h2>Omitted screen field</h2>
 * <p>The BMS {@code CONFIRM} flag ({@code CONFIRMI PIC X(1)}, Y/N) present on the
 * {@code CORPT00} map is a terminal-only guard that asked the operator to confirm the
 * submission before JCL was built. It carries no business data and is intentionally
 * <strong>not</strong> part of the REST contract.</p>
 *
 * @param reportType the report mode discriminator; one of {@code "MONTHLY"},
 *                   {@code "YEARLY"}, or {@code "CUSTOM"}. Mandatory. Maps to the
 *                   {@code CORPT00C} {@code MONTHLY}/{@code YEARLY}/{@code CUSTOM}
 *                   screen flags.
 * @param startDate  inclusive start of the reporting period; required only when
 *                   {@code reportType == "CUSTOM"} (cross-field rule enforced in
 *                   {@code ReportService}), and ignored for {@code MONTHLY}/{@code YEARLY}
 *                   which auto-derive their ranges. Maps to the legacy
 *                   {@code SDTYYYY}/{@code SDTMM}/{@code SDTDD} fields.
 * @param endDate    inclusive end of the reporting period; required only when
 *                   {@code reportType == "CUSTOM"} (cross-field rule enforced in
 *                   {@code ReportService}), and ignored for {@code MONTHLY}/{@code YEARLY}.
 *                   Maps to the legacy {@code EDTYYYY}/{@code EDTMM}/{@code EDTDD} fields.
 */
public record ReportRequest(

        // Report mode discriminator — CORPT00C MONTHLY / YEARLY / CUSTOM screen flags.
        // @Pattern is whole-string anchored (java.util.regex matches semantics) and treats
        // null as valid, so @NotNull is required alongside it to reject a missing value.
        @NotNull(message = "Report type is required")
        @Pattern(regexp = "MONTHLY|YEARLY|CUSTOM",
                 message = "Report type must be MONTHLY, YEARLY, or CUSTOM")
        String reportType,

        // Inclusive period start — required only when reportType == CUSTOM.
        // The conditional requirement is a cross-field rule enforced in ReportService
        // (date-range parity with CORPT00C + CSUTLDTC), not a static annotation.
        LocalDate startDate,

        // Inclusive period end — required only when reportType == CUSTOM.
        // Cross-field requirement enforced in ReportService (see startDate).
        LocalDate endDate

) {
}

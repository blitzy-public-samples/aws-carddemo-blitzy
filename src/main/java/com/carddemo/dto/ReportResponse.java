package com.carddemo.dto;

import java.time.LocalDate;

/**
 * Immutable JSON response body returned by the asynchronous transaction-report
 * submission endpoint ({@code POST /reports}, handled by {@code ReportController}).
 *
 * <p><strong>Lineage.</strong> This DTO is the Spring Boot replacement for the online
 * CICS report-request program {@code CORPT00C} (BMS screen {@code CORPT00}). In the
 * legacy system, {@code CORPT00C} did not produce the report inline; it assembled a
 * batch job stream and handed it off for asynchronous execution via the internal
 * reader (the {@code SUBMIT-JOB-TO-INTRDR} paragraph writing to an extra-partition
 * transient-data queue). The migrated architecture preserves this fire-and-forget
 * semantics exactly: {@code ReportService} launches a Spring Batch job through the
 * {@code JobLauncher} and immediately returns, surfacing the resulting
 * {@code JobExecution} identifier as the report reference (see AAP &sect;0.3.2).</p>
 *
 * <h2>Asynchronous contract (key insight)</h2>
 * <p>This response carries a <em>reference</em> to the submitted job, <strong>not</strong>
 * the report content. Submission never blocks on job completion; the report is produced
 * out of band and the client uses {@link #jobExecutionId()} to poll for status or retrieve
 * the finished output separately. This mirrors the legacy behavior where the 3270 screen
 * simply confirmed that the job had been submitted for printing rather than rendering the
 * report itself.</p>
 *
 * <h2>Report types and effective date range</h2>
 * <p>{@code CORPT00C} supports three mutually exclusive report selections, each of which
 * resolves to an effective {@code [startDate, endDate]} window that is passed to the batch
 * job as run parameters:</p>
 * <ul>
 *   <li><strong>{@code MONTHLY}</strong> &mdash; the current calendar month
 *       (start = first day of the month, end = last day of the month);</li>
 *   <li><strong>{@code YEARLY}</strong> &mdash; the current calendar year
 *       (start = January&nbsp;1, end = December&nbsp;31);</li>
 *   <li><strong>{@code CUSTOM}</strong> &mdash; a caller-supplied range
 *       (the {@code SDTMM}/{@code SDTDD}/{@code SDTYYYY} and
 *       {@code EDTMM}/{@code EDTDD}/{@code EDTYYYY} screen fields), validated by the
 *       date-validation service that ports {@code CSUTLDTC}.</li>
 * </ul>
 * <p>{@link #reportType()} echoes the requested selection back to the caller, and
 * {@link #startDate()} / {@link #endDate()} report the <em>effective</em> window that was
 * actually applied (derived for {@code MONTHLY}/{@code YEARLY}, supplied for
 * {@code CUSTOM}).</p>
 *
 * <h2>Type mapping</h2>
 * <p>Per the migration's uniform type rules: the Spring Batch {@code JobExecution}
 * identifier is exposed as a {@link Long}; the job status and the report-type selector are
 * {@link String}s; and the two date boundaries are {@link java.time.LocalDate} values, which
 * replace the legacy {@code YYYY-MM-DD} character dates ({@code WS-START-DATE} /
 * {@code WS-END-DATE} formatted via {@code WS-DATE-FORMAT}).</p>
 *
 * <h2>Framework-light (Tier-0) posture</h2>
 * <p>This record intentionally depends only on the JDK ({@link java.time.LocalDate}). The
 * job reference is carried as a plain {@link Long} rather than a Spring Batch
 * {@code JobExecution} object, so the DTO never leaks framework types across the API
 * boundary and remains trivially serializable.</p>
 *
 * <h2>Serialization</h2>
 * <p>As a Java record this type is immutable and serializes via its component accessors to
 * the JSON contract below; Jackson renders each {@link java.time.LocalDate} as an ISO-8601
 * calendar date ({@code yyyy-MM-dd}):</p>
 * <pre>{@code
 * {
 *   "jobExecutionId": 123,
 *   "status": "STARTING",
 *   "reportType": "MONTHLY",
 *   "startDate": "2023-07-01",
 *   "endDate": "2023-07-31"
 * }
 * }</pre>
 *
 * @param jobExecutionId the Spring Batch {@code JobExecution} identifier issued at
 *                       submission time; the asynchronous report reference the client uses
 *                       to poll for status or retrieve the completed report (replaces the
 *                       legacy {@code SUBMIT-JOB-TO-INTRDR} transient-data-queue hand-off)
 * @param status         the {@code JobExecution} status captured at submission, e.g.
 *                       {@code "STARTING"} or {@code "STARTED"}
 * @param reportType     the requested report type echoed back to the caller; one of
 *                       {@code "MONTHLY"}, {@code "YEARLY"} or {@code "CUSTOM"}
 * @param startDate      the effective inclusive start of the reporting range (derived for
 *                       {@code MONTHLY}/{@code YEARLY}, supplied for {@code CUSTOM})
 * @param endDate        the effective inclusive end of the reporting range (derived for
 *                       {@code MONTHLY}/{@code YEARLY}, supplied for {@code CUSTOM})
 */
public record ReportResponse(
        Long jobExecutionId,
        String status,
        String reportType,
        LocalDate startDate,
        LocalDate endDate
) {
}

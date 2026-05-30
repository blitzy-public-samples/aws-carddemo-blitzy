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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Output format options for report generation submitted via {@code POST /api/reports}.
 *
 * <p>This enum is a <b>modernization addition</b> with no direct COBOL equivalent.
 * The original COBOL {@code CORPT00C} program (replaced by {@code ReportController}
 * and {@code ReportService}) writes report output via
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} triggering JCL {@code TRANREPT.jcl}
 * (now mapped to {@code TransactionReportJobConfig} per AAP §0.4.1.2). The original
 * mainframe output was plain-text sequential print output only — there was no
 * format selector in the BMS {@code CORPT00} screen.
 *
 * <p>The Java implementation enriches the report submission API by allowing clients
 * to optionally specify a desired output format. When the {@code outputFormat} field
 * on {@link ReportRequest} is {@code null}, {@code ReportService} applies a sensible
 * default (typically {@code PDF}).
 *
 * <p>Values:
 * <ul>
 *   <li>{@link #PDF}  — Portable Document Format (default; suitable for printing and archival)</li>
 *   <li>{@link #CSV}  — Comma-Separated Values (suitable for spreadsheet import / data analysis)</li>
 *   <li>{@link #HTML} — Hypertext Markup Language (suitable for web rendering; mirrors the HTML
 *       statement format produced by {@code CBSTM03A} {@code 5100-WRITE-HTML-HEADER})</li>
 * </ul>
 *
 * <p>Jackson serializes/deserializes enum values by name (default behavior). Clients send the
 * literal string {@code "PDF"}, {@code "CSV"}, or {@code "HTML"} in the JSON request body;
 * other values produce a deserialization error (HTTP 400 Bad Request).
 *
 * <p>This enum lives in the same package as {@link ReportRequest} and therefore does not
 * require an explicit import from {@code ReportRequest}.
 *
 * @see ReportRequest
 * @see ReportType
 */
@Schema(
    description = "Output format options for the report generation request. " +
                  "If null on the request, the service applies a sensible default (typically PDF). " +
                  "This is a modernization addition; the original COBOL CORPT00 screen had no format selector.",
    allowableValues = {"PDF", "CSV", "HTML"},
    example = "PDF"
)
public enum OutputFormat {

    /**
     * Portable Document Format — default output format suitable for printing and archival.
     * Typically rendered server-side via a PDF library (e.g., iText, OpenPDF) by the
     * batch job invoked by {@code ReportService}.
     */
    PDF,

    /**
     * Comma-Separated Values — suitable for spreadsheet import and data analysis tools.
     * Produced by a {@code FlatFileItemWriter} configured with comma delimiters.
     */
    CSV,

    /**
     * Hypertext Markup Language — suitable for web rendering.
     * Mirrors the HTML format produced by {@code CBSTM03A} paragraph
     * {@code 5100-WRITE-HTML-HEADER} (AAP §0.6.7) for statement output.
     */
    HTML
}

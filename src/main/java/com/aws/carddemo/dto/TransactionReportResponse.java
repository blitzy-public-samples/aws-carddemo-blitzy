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
 * application. This type re-expresses a BMS 3270 screen field contract as a
 * REST response DTO, preserving the original field names, lengths, and types
 * with no feature expansion.
 */
package com.aws.carddemo.dto;

/**
 * Response projection for the Transaction Reports screen.
 *
 * <p>Re-platforms the output group of BMS map {@code CORPT00} (symbolic map
 * {@code CORPT0AO}, driven by online program {@code CORPT00C}, exposed by
 * {@code TransactionReportController}). The Transaction Reports screen lets an
 * operator request a monthly, yearly, or custom date-range transaction report.
 * The report itself is produced by the batch layer, so this response carries
 * only the screen header metadata and a single status or error message that
 * confirms the request was submitted (for example {@code "Report submitted"}).</p>
 *
 * <p>Only the header and message output fields are projected. The selector and
 * date-range fields ({@code MONTHLYO}, {@code YEARLYO}, {@code CUSTOMO}, the
 * start and end date parts, and {@code CONFIRMO}) are request-side inputs and
 * are intentionally not echoed here; when the controller needs to redisplay
 * them it reuses the corresponding request object. The BMS attribute-byte
 * sub-fields (length, colour, protection) are omitted because the re-platformed
 * application performs no 3270 terminal rendering.</p>
 *
 * <p>Field lengths mirror the legacy {@code PIC X(n)} clauses in
 * {@code app/cpy-bms/CORPT00.CPY} so the external field contract is preserved
 * exactly. Status and error message text traces to
 * {@code app/cpy/CSMSG01Y.cpy}.</p>
 *
 * @param transactionName the transaction (screen) name shown in the header;
 *        maps to {@code TRNNAMEO}, {@code PIC X(4)}
 * @param title01 the first application title line; maps to {@code TITLE01O},
 *        {@code PIC X(40)}
 * @param currentDate the current date shown in the header; maps to
 *        {@code CURDATEO}, {@code PIC X(8)}
 * @param programName the originating program name shown in the header; maps to
 *        {@code PGMNAMEO}, {@code PIC X(8)}
 * @param title02 the second application title line; maps to {@code TITLE02O},
 *        {@code PIC X(40)}
 * @param currentTime the current time shown in the header; maps to
 *        {@code CURTIMEO}, {@code PIC X(8)}
 * @param errorMessage the status or error message returned after the
 *        report-submit request; maps to {@code ERRMSGO}, {@code PIC X(78)}
 */
public record TransactionReportResponse(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String errorMessage) {
}

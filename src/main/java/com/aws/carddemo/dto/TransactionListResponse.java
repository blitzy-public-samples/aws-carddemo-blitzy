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
package com.aws.carddemo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for the CardDemo <strong>Transaction List</strong> screen.
 *
 * <p>This record is the Java re-expression of the output side of the 3270 screen
 * driven by CICS transaction {@code CT00}, BMS map {@code COTRN00} (mapset
 * {@code COTRN0A}), and COBOL program {@code COTRN00C}. It corresponds to the
 * symbolic output group {@code COTRN0AO} defined in copybook
 * {@code app/cpy-bms/COTRN00.CPY} (relocated under {@code legacy/}).</p>
 *
 * <p>The screen shows a single page of up to ten transaction summary rows plus
 * the standard CardDemo screen header (transaction name, screen titles, current
 * date/time, program name), a page indicator, and an error/message line. The
 * field contract below is preserved verbatim from the BMS map so that the REST
 * contract remains byte-for-byte faithful to the original terminal screen; only
 * the observable data fields are carried. The 3270 attribute-byte sub-fields
 * ({@code *C}/{@code *P}/{@code *H}/{@code *V}), the per-row selection fields
 * ({@code SEL000n}), and the search-input field ({@code TRNIDINO}) belong to the
 * request side and are intentionally omitted here.</p>
 *
 * <p>Field-to-COBOL mapping (symbolic map {@code COTRN0AO}):</p>
 * <table border="1">
 *   <caption>COTRN0AO output field mapping</caption>
 *   <tr><th>DTO component</th><th>COBOL field</th><th>PIC</th></tr>
 *   <tr><td>{@code transactionName}</td><td>{@code TRNNAMEO}</td><td>X(4)</td></tr>
 *   <tr><td>{@code title01}</td><td>{@code TITLE01O}</td><td>X(40)</td></tr>
 *   <tr><td>{@code title02}</td><td>{@code TITLE02O}</td><td>X(40)</td></tr>
 *   <tr><td>{@code currentDate}</td><td>{@code CURDATEO}</td><td>X(8)</td></tr>
 *   <tr><td>{@code programName}</td><td>{@code PGMNAMEO}</td><td>X(8)</td></tr>
 *   <tr><td>{@code currentTime}</td><td>{@code CURTIMEO}</td><td>X(8)</td></tr>
 *   <tr><td>{@code pageNumber}</td><td>{@code PAGENUMO}</td><td>X(8)</td></tr>
 *   <tr><td>{@code transactions}</td>
 *       <td>{@code TRNIDnnO}/{@code TDATEnnO}/{@code TDESCnnO}/{@code TAMT00nO}</td>
 *       <td>row group</td></tr>
 *   <tr><td>{@code errorMessage}</td><td>{@code ERRMSGO}</td><td>X(78)</td></tr>
 * </table>
 *
 * <p>This is a read-only page projection: the {@code mapper/} layer populates the
 * {@link TransactionListRow} entries from {@code Transaction} entities. The record
 * carries no business logic. The {@code errorMessage} component is a plain string;
 * the canonical message texts it may carry trace to copybook
 * {@code app/cpy/CSMSG01Y.cpy} but are not defined in the DTO layer.</p>
 *
 * <p>The record is deeply immutable: the canonical constructor stores an
 * unmodifiable copy of {@code transactions}, so callers cannot mutate the page
 * contents after construction.</p>
 *
 * @param transactionName the transaction/screen name header ({@code TRNNAMEO}, X(4))
 * @param title01         the first title line ({@code TITLE01O}, X(40))
 * @param title02         the second title line ({@code TITLE02O}, X(40))
 * @param currentDate     the current date shown in the header ({@code CURDATEO}, X(8))
 * @param programName     the originating program name ({@code PGMNAMEO}, X(8))
 * @param currentTime     the current time shown in the header ({@code CURTIMEO}, X(8))
 * @param pageNumber      the page indicator ({@code PAGENUMO}, X(8))
 * @param transactions    the page of up to ten transaction summary rows; never
 *                        {@code null} and always unmodifiable after construction
 * @param errorMessage    the error/information message line ({@code ERRMSGO}, X(78))
 */
public record TransactionListResponse(
        String transactionName,
        String title01,
        String title02,
        String currentDate,
        String programName,
        String currentTime,
        String pageNumber,
        List<TransactionListRow> transactions,
        String errorMessage) {

    /**
     * Canonical constructor that enforces the record's read-only contract by
     * storing an unmodifiable copy of the supplied rows.
     *
     * <p>A {@code null} {@code transactions} argument is normalized to an empty
     * list so that {@link #transactions()} never returns {@code null}. This is a
     * structural immutability guarantee for the page projection and introduces no
     * business logic. The page holds up to ten rows, mirroring the ten fixed row
     * slots ({@code TRNID01O}..{@code TRNID10O}) of the {@code COTRN00} map.</p>
     */
    public TransactionListResponse {
        transactions = (transactions == null) ? List.of() : List.copyOf(transactions);
    }

    /**
     * A single transaction summary row of the Transaction List screen.
     *
     * <p>Each instance corresponds to one of the ten fixed row slots in the
     * {@code COTRN0AO} symbolic map (suffixes {@code 01}..{@code 10}). The
     * monetary {@code amount} is modeled as a {@link BigDecimal} carrying a
     * scale-2 value; floating-point types are never used for money. Scaling and
     * rounding are performed upstream by the mapper / monetary value object, so
     * this row carries the amount verbatim.</p>
     *
     * @param transactionId the 16-character transaction identifier
     *                      ({@code TRNIDnnO}, X(16))
     * @param date          the display date for the transaction
     *                      ({@code TDATEnnO}, X(8))
     * @param description   the transaction description ({@code TDESCnnO}, X(26))
     * @param amount        the transaction amount as a scale-2 {@link BigDecimal}
     *                      ({@code TAMT00nO}, X(12))
     */
    public record TransactionListRow(
            String transactionId,
            String date,
            String description,
            BigDecimal amount) {
    }
}

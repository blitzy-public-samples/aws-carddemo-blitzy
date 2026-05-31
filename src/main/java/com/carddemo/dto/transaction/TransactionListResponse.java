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
package com.carddemo.dto.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * Paginated response wrapper for the {@code GET /api/transactions} endpoint exposed by
 * {@code TransactionController}.
 *
 * <p>This DTO is the REST migration of the COBOL {@code COTRN00C} (transaction list)
 * program and its BMS list screen {@code app/cpy-bms/COTRN00.CPY}. The original 3270
 * screen displayed <strong>10 transactions per page</strong> (BMS fields
 * {@code TRNID01..TRNID10}, {@code TDATE01..TDATE10}, {@code TDESC01..TDESC10},
 * {@code TAMT001..TAMT010}) with a {@code PAGENUMI} page indicator, navigated via the
 * PF7 (page backward) and PF8 (page forward) keys. The modern stateless REST endpoint
 * reproduces this behavior by flattening the Spring Data {@code Page} shape into a
 * JSON-friendly structure.</p>
 *
 * <p><b>COBOL&nbsp;&rarr;&nbsp;REST pagination mapping</b> (from {@code app/cbl/COTRN00C.cbl}):</p>
 * <pre>
 *   COBOL construct (COTRN00C)                         Java field
 *   ──────────────────────────                         ──────────
 *   CDEMO-CT00-PAGE-NUM   PIC 9(08)              |-&gt;  currentPage  (zero-indexed in REST)
 *   CDEMO-CT00-NEXT-PAGE-FLG 88 NEXT-PAGE-YES    |-&gt;  hasNext      (PF8 forward availability)
 *   PROCESS-PF7-KEY: IF CDEMO-CT00-PAGE-NUM &gt; 1  |-&gt;  hasPrevious  (PF7 backward availability)
 *   BMS 10-row screen layout (COTRN00.CPY)       |-&gt;  pageSize     (default 10)
 *   (count of TRANSACT rows matching the query)  |-&gt;  totalElements
 *   (derived: ceil(totalElements / pageSize))    |-&gt;  totalPages
 *   each displayed row (TRNID/TDATE/TDESC/TAMT)  |-&gt;  content (List&lt;TransactionDto&gt;)
 * </pre>
 *
 * <p>The server no longer maintains a VSAM browse cursor
 * ({@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR}); instead, each request
 * recomputes the page from the Spring Data {@code Pageable} ({@code PageRequest.of(page, size, ...)}),
 * mirroring AAP §0.6.1. The {@code hasNext}/{@code hasPrevious} booleans give the client the
 * same navigation hints the 3270 screen derived from the {@code NEXT-PAGE} flag and the page
 * counter, so a UI (or curl client) knows when PF7/PF8 equivalents are enabled.</p>
 *
 * <p>Construction flow: {@code TransactionMapper.toListResponse(Page<Transaction> page)} builds
 * this record from a Spring Data {@code Page}; {@code TransactionService.list(Pageable)} returns it;
 * {@code TransactionController.listTransactions(...)} serializes it as the HTTP response body.</p>
 *
 * <p><b>CRITICAL — PR-28 (Jakarta EE namespace):</b> all validation constraint annotations are
 * imported from {@code jakarta.validation.constraints.*}, NEVER {@code javax.validation.*}, per the
 * Spring Boot 3.x / Jakarta EE 10 baseline.</p>
 *
 * <p>This is an immutable Java 17 {@code record} — the canonical constructor, the seven accessors
 * ({@code content()}, {@code totalElements()}, {@code totalPages()}, {@code currentPage()},
 * {@code pageSize()}, {@code hasNext()}, {@code hasPrevious()}), and
 * {@code equals}/{@code hashCode}/{@code toString} are auto-generated. Records are implicitly final,
 * so no Lombok is needed. {@code TransactionDto} resides in the same package
 * ({@code com.carddemo.dto.transaction}) and therefore needs no import.</p>
 *
 * <p>The compact constructor guarantees {@code content} is never {@code null} (it defaults to an
 * empty immutable list), preserving the Spring Data {@code Page} convention that the content list is
 * always present — possibly empty — but never {@code null}.</p>
 *
 * @param content       the transaction DTOs on the current page (never {@code null}; possibly empty)
 * @param totalElements total number of transactions matching the query across all pages
 * @param totalPages    total number of pages available given {@code totalElements} and {@code pageSize}
 * @param currentPage   current page number, zero-indexed per Spring Data convention
 * @param pageSize      number of entries per page (default 10, mirroring the BMS COTRN00 layout)
 * @param hasNext       {@code true} if a page exists after the current one (PF8 availability)
 * @param hasPrevious   {@code true} if a page exists before the current one (PF7 availability)
 * @see com.carddemo.dto.transaction.TransactionDto
 */
@Schema(
    description = "Paginated response wrapper for GET /api/transactions. Mirrors COBOL COTRN00C "
        + "list screen which displayed 10 transactions per page with PF7/PF8 navigation. "
        + "Default pageSize=10 matches BMS 10-row pattern; clients may request other sizes via "
        + "Spring Pageable convention."
)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionListResponse(

    @Schema(
        description = "List of transaction DTOs on the current page (max 10 by default to mirror BMS COTRN00 layout)",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    List<TransactionDto> content,

    @Schema(
        description = "Total number of transactions matching the query across all pages",
        example = "311",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @PositiveOrZero(message = "Total elements must be zero or positive")
    long totalElements,

    @Schema(
        description = "Total number of pages available given the totalElements and pageSize",
        example = "32",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @PositiveOrZero(message = "Total pages must be zero or positive")
    int totalPages,

    @Schema(
        description = "Current page number (zero-indexed per Spring Data convention). "
            + "Maps COBOL CDEMO-CT00-PAGE-NUM tracking.",
        example = "0",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @PositiveOrZero(message = "Current page must be zero or positive")
    int currentPage,

    @Schema(
        description = "Number of entries per page. Default 10 to mirror BMS COTRN00 10-row screen layout.",
        example = "10",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @Min(value = 1, message = "Page size must be at least 1")
    int pageSize,

    @Schema(
        description = "True if there are additional pages after the current page (equivalent to PF8 navigation availability)",
        example = "true",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    boolean hasNext,

    @Schema(
        description = "True if there are pages before the current page (equivalent to PF7 navigation availability)",
        example = "false",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    boolean hasPrevious

) {

    /**
     * Compact constructor providing defensive null protection on the content list.
     *
     * <p>Mirrors the Spring Data {@code Page} convention where the content is always a list
     * (possibly empty) rather than {@code null}. When a caller passes {@code null} for
     * {@code content} (for example, an empty result set that was not explicitly initialized),
     * it is normalized to {@link List#of()} (an immutable empty list). This keeps downstream
     * JSON serialization and client iteration safe — {@code content} can be iterated without a
     * null check — while the remaining pagination metadata ({@code totalElements},
     * {@code totalPages}, {@code currentPage}, {@code pageSize}, {@code hasNext},
     * {@code hasPrevious}) is carried through unchanged.</p>
     */
    public TransactionListResponse {
        if (content == null) {
            content = List.of();
        }
    }
}

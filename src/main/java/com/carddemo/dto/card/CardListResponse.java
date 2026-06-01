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
package com.carddemo.dto.card;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * Paginated response wrapper for the card list endpoint
 * {@code GET /api/accounts/{acctId}/cards}.
 *
 * <p>Translates the COBOL {@code COCRDLIC} card list program's BMS list screen
 * ({@code COCRDLI.CPY}) into a Spring REST paginated response. The original
 * BMS layout displayed 7 cards per page with PF7 (page-up) / PF8 (page-down)
 * navigation keys; the {@link #pageSize()} default of 7 mirrors that layout,
 * and the {@link #hasNext()} / {@link #hasPrevious()} flags inform clients
 * about navigation availability.</p>
 *
 * <p><strong>AAP &sect;0.6.1 &mdash; VSAM browse replacement:</strong> The original
 * implementation used VSAM {@code STARTBR DATASET('CARDAIX')} browse cursors
 * with positioned reads via {@code READNEXT} / {@code READPREV}. The Java
 * implementation replaces this with stateless Spring Data {@code Pageable} &mdash;
 * each REST request recomputes the cursor from {@code page} and {@code size}
 * query parameters; no server-side cursor lifecycle is maintained.</p>
 *
 * <p><strong>Page Conventions (Spring Data {@code Page} semantics):</strong></p>
 * <ul>
 *   <li>{@code content} &mdash; current page entries; ALWAYS non-null (empty list
 *       if no results) per the compact constructor's defensive guard</li>
 *   <li>{@code totalElements} &mdash; count across ALL pages</li>
 *   <li>{@code totalPages} &mdash; {@code ceil(totalElements / pageSize)}</li>
 *   <li>{@code currentPage} &mdash; ZERO-indexed page number (page 0 = first page)</li>
 *   <li>{@code pageSize} &mdash; entries per page; default 7 to mirror BMS layout</li>
 *   <li>{@code hasNext} &mdash; convenience flag equivalent to
 *       {@code currentPage + 1 < totalPages}</li>
 *   <li>{@code hasPrevious} &mdash; convenience flag equivalent to
 *       {@code currentPage > 0}</li>
 * </ul>
 *
 * <p><strong>JSON shape:</strong> Standard JSON object with named fields.
 * {@code @JsonInclude(NON_NULL)} suppresses null fields (though all primitive
 * fields and the defensive content list ensure no nulls are emitted in
 * practice).</p>
 *
 * <p><strong>Immutability:</strong> Implemented as a Java 17 {@code record}
 * for immutability. Records auto-generate the canonical constructor,
 * component accessors (e.g., {@code content()}, {@code totalElements()}),
 * {@code equals}, {@code hashCode}, and {@code toString}. Records are implicitly
 * {@code final}, so no Lombok is required. {@code CardDto} resides in the same
 * package ({@code com.carddemo.dto.card}) and therefore needs no import.</p>
 *
 * <p>Construction flow: {@code CardMapper.toListResponse(Page<Card> page)} builds this
 * record from a Spring Data {@code Page}; {@code CardService.listByAccount(Long, int, int)}
 * returns it; {@code CardController.listCards(...)} serializes it as the HTTP response body.</p>
 *
 * <p><strong>PR-28 (Jakarta EE namespace):</strong> all validation constraint annotations
 * are imported from {@code jakarta.validation.constraints.*}, NEVER {@code javax.validation.*},
 * per the Spring Boot 3.x / Jakarta EE 10 baseline.</p>
 *
 * @param content       the card DTOs on the current page (never {@code null}; possibly empty)
 * @param totalElements total number of cards matching the query across all pages
 * @param totalPages    total number of pages available given {@code totalElements} and {@code pageSize}
 * @param currentPage   current page number, zero-indexed per Spring Data convention
 * @param pageSize      number of entries per page (default 7, mirroring the BMS COCRDLI layout)
 * @param hasNext       {@code true} if a page exists after the current one (PF8 availability)
 * @param hasPrevious   {@code true} if a page exists before the current one (PF7 availability)
 * @see CardDto
 * @see "app/cpy-bms/COCRDLI.CPY"
 */
@Schema(
    description = "Paginated response wrapper for GET /api/accounts/{acctId}/cards. "
        + "Mirrors COBOL COCRDLIC list screen which displayed 7 cards per page with "
        + "PF7/PF8 navigation. Default pageSize=7 matches BMS 7-row pattern; clients "
        + "may request other sizes via Spring Pageable convention. "
        + "Replaces VSAM STARTBR DATASET('CARDAIX') browse cursor with stateless "
        + "Spring Pageable per AAP \u00a70.6.1."
)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardListResponse(

    @Schema(
        description = "List of card DTOs on the current page (max 7 by default to mirror BMS COCRDLI 7-row layout)",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    List<CardDto> content,

    @Schema(
        description = "Total number of cards matching the query across all pages",
        example = "50",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @PositiveOrZero(message = "Total elements must be zero or positive")
    long totalElements,

    @Schema(
        description = "Total number of pages available given the totalElements and pageSize",
        example = "8",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @PositiveOrZero(message = "Total pages must be zero or positive")
    int totalPages,

    @Schema(
        description = "Current page number (zero-indexed per Spring Data convention). "
            + "Maps COBOL CDEMO-CT00-PAGE-NUM / PAGENOI tracking.",
        example = "0",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    @PositiveOrZero(message = "Current page must be zero or positive")
    int currentPage,

    @Schema(
        description = "Number of entries per page. Default 7 to mirror BMS COCRDLI 7-row screen layout.",
        example = "7",
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
     * JSON serialization and client iteration safe &mdash; {@code content} can be iterated
     * without a null check &mdash; while the remaining pagination metadata
     * ({@code totalElements}, {@code totalPages}, {@code currentPage}, {@code pageSize},
     * {@code hasNext}, {@code hasPrevious}) is carried through unchanged.</p>
     */
    public CardListResponse {
        if (content == null) {
            content = List.of();
        }
    }
}

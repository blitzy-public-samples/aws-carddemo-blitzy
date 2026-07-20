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
package com.aws.carddemo.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.dto.AdminMenuResponse;
import com.aws.carddemo.dto.MainMenuResponse;

/**
 * Hand-written response-assembly mapper for the two CardDemo <em>menu</em> screens:
 * the <strong>Main Menu</strong> (BMS map {@code COMEN01}, online program {@code COMEN01C},
 * CICS transaction {@code CM00}) and the <strong>Admin Menu</strong> (BMS map
 * {@code COADM01}, online program {@code COADM01C}, CICS transaction {@code CA00}).
 *
 * <h2>Source lineage</h2>
 * <ul>
 *   <li>Menu-option catalogs {@code legacy/cpy/COMEN02Y.cpy} (ten regular-user options) and
 *       {@code legacy/cpy/COADM02Y.cpy} (four security/admin options) supply the option
 *       <em>labels</em>. Those labels are assembled by {@code service.MenuService}
 *       (from {@code COMEN02Y}/{@code COADM02Y}) and handed to this mapper as a ready
 *       {@code List<String>}; this mapper therefore contains <strong>no hard-coded menu
 *       text</strong>.</li>
 *   <li>BMS symbolic copybooks {@code legacy/cpy-bms/COMEN01.CPY} (output group
 *       {@code COMEN1AO}) and {@code legacy/cpy-bms/COADM01.CPY} (output group
 *       {@code COADM1AO}) define the outbound screen fields this mapper populates: the header
 *       ({@code TRNNAMEO}, {@code TITLE01O}, {@code CURDATEO}, {@code PGMNAMEO},
 *       {@code TITLE02O}, {@code CURTIMEO}), the twelve option lines
 *       ({@code OPTN001O}..{@code OPTN012O}) and the error/status line ({@code ERRMSGO}).</li>
 * </ul>
 *
 * <h2>Why one mapper</h2>
 * The Main Menu and Admin Menu response DTOs share an identical shape and both draw their
 * option lines from a menu-option catalog rather than a persisted JPA entity, so the two
 * assembly methods are consolidated here &mdash; aligning with the single sibling
 * {@code service.MenuService}. There is deliberately no {@code toEntity} direction: menus
 * have no persisted entity, and the selected {@code option} is read directly by
 * {@code service.MenuService} for routing, so the request DTOs
 * ({@code dto.MainMenuRequest} / {@code dto.AdminMenuRequest}) are not consumed here.
 *
 * <h2>Design constraints (honored)</h2>
 * <ul>
 *   <li>Stateless, side-effect-free Spring {@link Component} with no mutable instance
 *       state; {@link DateUtils} is used only through its {@code static} API.</li>
 *   <li>Explicit, hand-written field-by-field assignment &mdash; no mapping library
 *       (no MapStruct), for direct field-level traceability from BMS field to DTO
 *       component.</li>
 *   <li>{@code menuOptions} is stored as an immutable, defensive copy so a later mutation
 *       of the caller's list cannot alter an already-built response; a {@code null} list is
 *       normalized to an empty list, so assembly never throws a {@link NullPointerException}
 *       for a missing option list.</li>
 *   <li>The screen-constant header fields ({@code transactionName}, {@code title01},
 *       {@code title02}, {@code programName}) are supplied by the caller (the owning
 *       controller / {@code service.MenuService} context) and are copied through verbatim;
 *       this mapper never fabricates them.</li>
 *   <li>Menus carry no monetary or otherwise sensitive data; no {@code double}/{@code float}
 *       is used, and request contents are never logged here.</li>
 * </ul>
 */
@Component
public class MenuMapper {

    /**
     * Assembles the outbound {@link MainMenuResponse} for the Main Menu screen
     * ({@code COMEN01C}, transaction {@code CM00}), populating the symbolic output group
     * {@code COMEN1AO} of BMS map {@code COMEN01}.
     *
     * <p>The date and time header fields are derived from a single {@code now} instant so the
     * two values are always mutually consistent: {@code currentDate} ({@code CURDATEO}) is
     * rendered with {@link DateUtils#formatDateMmDdYy(java.time.LocalDate)} ({@code MM/DD/YY})
     * and {@code currentTime} ({@code CURTIMEO}) with
     * {@link DateUtils#formatTimeHhMmSs(java.time.LocalTime)} ({@code HH:MM:SS}). All other
     * values are passed straight through, and {@code menuOptions} is defensively copied into
     * an unmodifiable list (a {@code null} argument becomes an empty list).</p>
     *
     * @param menuOptions     the ordered menu-item labels to display, sourced from the
     *                        {@code COMEN02Y} catalog via {@code service.MenuService} (index
     *                        {@code 0} maps to {@code OPTN001O}); may be {@code null} (treated
     *                        as no options) and is stored as an immutable defensive copy
     * @param errorMessage    the error/status line ({@code ERRMSGO}); may be {@code null} when
     *                        there is no message to show
     * @param now             the instant used to render the header date and time; must not be
     *                        {@code null}
     * @param transactionName the caller-supplied transaction identifier shown in the header
     *                        ({@code TRNNAMEO}, e.g. {@code CM00}); passed through verbatim
     * @param title01         the caller-supplied first title line ({@code TITLE01O})
     * @param title02         the caller-supplied second title line ({@code TITLE02O})
     * @param programName     the caller-supplied program identifier shown in the header
     *                        ({@code PGMNAMEO}, e.g. {@code COMEN01C}); passed through verbatim
     * @return a fully populated, immutable {@link MainMenuResponse}
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public MainMenuResponse toMainMenuResponse(
            List<String> menuOptions,
            String errorMessage,
            LocalDateTime now,
            String transactionName,
            String title01,
            String title02,
            String programName) {

        Objects.requireNonNull(now, "now must not be null");

        return new MainMenuResponse(
                transactionName,
                title01,
                DateUtils.formatDateMmDdYy(now.toLocalDate()),
                programName,
                title02,
                DateUtils.formatTimeHhMmSs(now.toLocalTime()),
                defensiveCopy(menuOptions),
                errorMessage);
    }

    /**
     * Assembles the outbound {@link AdminMenuResponse} for the Admin Menu screen
     * ({@code COADM01C}, transaction {@code CA00}), populating the symbolic output group
     * {@code COADM1AO} of BMS map {@code COADM01}.
     *
     * <p>Field-by-field logic is identical to
     * {@link #toMainMenuResponse(List, String, LocalDateTime, String, String, String, String)},
     * applied against the admin response record: the header date/time are rendered from
     * {@code now} through {@link DateUtils}, the header constants and the error line are
     * passed through, and {@code menuOptions} (here sourced from the {@code COADM02Y} catalog)
     * is defensively copied into an unmodifiable list.</p>
     *
     * @param menuOptions     the ordered admin menu-item labels to display, sourced from the
     *                        {@code COADM02Y} catalog via {@code service.MenuService}; may be
     *                        {@code null} (treated as no options) and is stored as an immutable
     *                        defensive copy
     * @param errorMessage    the error/status line ({@code ERRMSGO}); may be {@code null} when
     *                        there is no message to show
     * @param now             the instant used to render the header date and time; must not be
     *                        {@code null}
     * @param transactionName the caller-supplied transaction identifier shown in the header
     *                        ({@code TRNNAMEO}, e.g. {@code CA00}); passed through verbatim
     * @param title01         the caller-supplied first title line ({@code TITLE01O})
     * @param title02         the caller-supplied second title line ({@code TITLE02O})
     * @param programName     the caller-supplied program identifier shown in the header
     *                        ({@code PGMNAMEO}, e.g. {@code COADM01C}); passed through verbatim
     * @return a fully populated, immutable {@link AdminMenuResponse}
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public AdminMenuResponse toAdminMenuResponse(
            List<String> menuOptions,
            String errorMessage,
            LocalDateTime now,
            String transactionName,
            String title01,
            String title02,
            String programName) {

        Objects.requireNonNull(now, "now must not be null");

        return new AdminMenuResponse(
                transactionName,
                title01,
                DateUtils.formatDateMmDdYy(now.toLocalDate()),
                programName,
                title02,
                DateUtils.formatTimeHhMmSs(now.toLocalTime()),
                defensiveCopy(menuOptions),
                errorMessage);
    }

    /**
     * Returns an immutable, defensive copy of a supplied option-label list, normalizing a
     * {@code null} argument to an empty list.
     *
     * <p>Copying decouples the built response from later mutation of the caller's list, and
     * the {@code null}-to-empty normalization guarantees the assembly never fails for a
     * missing list (the response records themselves also normalize on construction; this keeps
     * the mapper self-contained and never passes {@code null} downstream). Element order is
     * preserved so that index {@code 0} continues to correspond to {@code OPTN001O}.</p>
     *
     * @param menuOptions the caller-supplied labels, possibly {@code null}
     * @return an unmodifiable copy of {@code menuOptions}, or an empty list when it is
     *         {@code null}
     */
    private static List<String> defensiveCopy(List<String> menuOptions) {
        return (menuOptions == null) ? List.of() : List.copyOf(menuOptions);
    }
}

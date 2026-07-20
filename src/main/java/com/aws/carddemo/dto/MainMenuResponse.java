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
package com.aws.carddemo.dto;

import java.util.List;

/**
 * Response DTO for the CardDemo <em>Main Menu</em> screen.
 *
 * <p>Java re-expression of the {@code COMEN1AO} output group of BMS symbolic copybook
 * {@code COMEN01} (BMS map {@code COMEN01}, online program {@code COMEN01C}, CICS
 * transaction {@code CM00}). It transports the fields the legacy program rendered to the
 * 3270 screen: the header/metadata, the twelve menu-item label lines, and the
 * error/status message.</p>
 *
 * <p>The pseudo-conversational CICS screen is preserved as a REST response contract rather
 * than a rendered terminal (AAP 0.3.3); the 3270 attribute-byte fields
 * ({@code C}/{@code P}/{@code H}/{@code V}) and the inbound option-selection field
 * ({@code OPTIONO}) are therefore intentionally omitted from this outbound DTO. Field names,
 * ordering, and maximum lengths are preserved from the copybook for the field-level
 * traceability required by AAP 0.9.2; the documented maxima are informational only, since this
 * transport type carries already-formatted output and performs no validation or business logic.
 * Menu labels are populated by {@code MenuService}; the error-message text traces to the
 * common-message copybook {@code CSMSG01Y}.</p>
 *
 * @param transactionName header transaction identifier ("Tran:"); origin {@code TRNNAMEO}, {@code PIC X(4)}
 * @param title01         first application title line; origin {@code TITLE01O}, {@code PIC X(40)}
 * @param currentDate     formatted current date shown in the header; origin {@code CURDATEO}, {@code PIC X(8)}
 * @param programName     current program name ("Prog:"); origin {@code PGMNAMEO}, {@code PIC X(8)}
 * @param title02         second application title line; origin {@code TITLE02O}, {@code PIC X(40)}
 * @param currentTime     formatted current time shown in the header; origin {@code CURTIMEO}, {@code PIC X(8)}
 * @param menuOptions     ordered menu-item label lines: index {@code 0} maps to {@code OPTN001O} through
 *                        index {@code 11} to {@code OPTN012O}; up to twelve entries, each origin
 *                        {@code PIC X(40)} (at most 40 characters). Defensively copied into an
 *                        unmodifiable list by the canonical constructor; a {@code null} argument is
 *                        normalized to an empty list, so this component is never {@code null}.
 * @param errorMessage    error/status message line; origin {@code ERRMSGO}, {@code PIC X(78)}
 */
public record MainMenuResponse(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        List<String> menuOptions,
        String errorMessage) {

    /**
     * Canonical (compact) constructor that guarantees the immutability of {@link #menuOptions()}.
     *
     * <p>The supplied list is defensively copied into an unmodifiable list so the response cannot be
     * mutated after construction, and a {@code null} argument is normalized to an empty list so
     * {@link #menuOptions()} never returns {@code null}. Element ordering is preserved so that index
     * {@code 0} continues to correspond to {@code OPTN001O} and index {@code 11} to
     * {@code OPTN012O}.</p>
     */
    public MainMenuResponse {
        menuOptions = (menuOptions == null) ? List.of() : List.copyOf(menuOptions);
    }
}

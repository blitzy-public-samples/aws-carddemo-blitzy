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

import java.util.List;

/**
 * Response DTO for the CardDemo <strong>Admin Menu</strong> screen.
 *
 * <p>This immutable value object carries the outbound (screen-output) fields of the
 * 3270 map <b>COADM01</b> (mapset {@code COADM01}, DFHMDI {@code COADM1A}) as produced by
 * the online COBOL program <b>COADM01C</b>. It is the Java re-platforming of the symbolic
 * output group {@code COADM1AO} defined in {@code app/cpy-bms/COADM01.CPY} (retained for
 * reference under {@code legacy/cpy-bms/COADM01.CPY}).</p>
 *
 * <p>Only the display/output fields (the {@code -O} suffixed sub-fields of the symbolic map)
 * are modeled. The BMS attribute bytes (the {@code -C}/{@code -P}/{@code -H}/{@code -V}
 * suffixed sub-fields used for 3270 colour, protection, highlight and validation control) are
 * intentionally omitted, because the screens are re-expressed as REST request/response
 * contracts rather than rendered on a terminal. The {@code OPTIONO} option-selection field is
 * an inbound field and is carried by the corresponding request DTO, not by this response.</p>
 *
 * <p>Field names, maximum lengths and types are preserved one-for-one from the COBOL map to
 * keep the screen field contract traceable. The {@link #errorMessage()} value corresponds to
 * {@code ERRMSGO} and typically carries the status/error text whose message constants
 * originate in {@code app/cpy/CSMSG01Y.cpy} (for example the "Invalid key pressed" message);
 * it is modeled here as a plain string, matching the shape of {@code MainMenuResponse}.</p>
 *
 * @param transactionName the transaction identifier shown in the "Tran:" header; maps to
 *        {@code TRNNAMEO} (COBOL {@code PIC X(4)}, maximum 4 characters)
 * @param title01 the first title line; maps to {@code TITLE01O}
 *        (COBOL {@code PIC X(40)}, maximum 40 characters)
 * @param currentDate the displayed date in {@code mm/dd/yy} form; maps to {@code CURDATEO}
 *        (COBOL {@code PIC X(8)}, maximum 8 characters)
 * @param programName the program identifier shown in the "Prog:" header; maps to
 *        {@code PGMNAMEO} (COBOL {@code PIC X(8)}, maximum 8 characters)
 * @param title02 the second title line; maps to {@code TITLE02O}
 *        (COBOL {@code PIC X(40)}, maximum 40 characters)
 * @param currentTime the displayed time in {@code hh:mm:ss} form; maps to {@code CURTIMEO}
 *        (COBOL {@code PIC X(8)}, maximum 8 characters)
 * @param menuOptions the ordered admin-menu label lines, up to twelve entries with each line
 *        limited to 40 characters; maps to {@code OPTN001O} through {@code OPTN012O} (each
 *        COBOL {@code PIC X(40)}), where index {@code 0} is {@code OPTN001O} and index
 *        {@code 11} is {@code OPTN012O}. The list is normalized to an unmodifiable copy on
 *        construction and is never {@code null}.
 * @param errorMessage the error/status message line; maps to {@code ERRMSGO}
 *        (COBOL {@code PIC X(78)}, maximum 78 characters). Text constants trace to
 *        {@code app/cpy/CSMSG01Y.cpy}.
 */
public record AdminMenuResponse(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        List<String> menuOptions,
        String errorMessage) {

    /**
     * Canonical constructor that defensively normalizes {@code menuOptions} into an
     * unmodifiable list so that the response remains a true immutable value object.
     *
     * <p>A {@code null} list is treated as "no options" and replaced with an empty list; a
     * supplied list is copied so that later external mutation of the caller's list cannot
     * alter this response. All other fields are stored as provided.</p>
     */
    public AdminMenuResponse {
        menuOptions = (menuOptions == null) ? List.of() : List.copyOf(menuOptions);
    }
}

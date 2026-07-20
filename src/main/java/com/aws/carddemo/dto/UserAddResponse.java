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

/**
 * Immutable response payload for the CardDemo <em>Add User</em> screen.
 *
 * <p>This record re-expresses the output symbolic map field group
 * {@code COUSR1AO} of BMS map {@code COUSR01} — driven by the legacy online
 * program {@code COUSR01C} (CICS transaction {@code CU01}) — as an idiomatic
 * REST response DTO. It carries only the standard screen header metadata and
 * the status/error message line. The first name, last name, user id and user
 * type entered on the request are re-echoed to the caller through the redisplay
 * of the corresponding {@code UserAddRequest} object and are therefore
 * intentionally not duplicated in this output projection.</p>
 *
 * <p>Field-level traceability to the legacy symbolic map (see
 * {@code legacy/app/cpy-bms/COUSR01.CPY} and {@code legacy/app/bms/COUSR01.bms}).
 * Every component preserves the field name, maximum length and type of its
 * COBOL counterpart, honoring the 3270 field contract required for behavioral
 * parity:</p>
 *
 * <ul>
 *   <li>{@code transactionName} &rarr; {@code TRNNAMEO}, PIC {@code X(4)}</li>
 *   <li>{@code title01} &rarr; {@code TITLE01O}, PIC {@code X(40)}</li>
 *   <li>{@code currentDate} &rarr; {@code CURDATEO}, PIC {@code X(8)}</li>
 *   <li>{@code programName} &rarr; {@code PGMNAMEO}, PIC {@code X(8)}</li>
 *   <li>{@code title02} &rarr; {@code TITLE02O}, PIC {@code X(40)}</li>
 *   <li>{@code currentTime} &rarr; {@code CURTIMEO}, PIC {@code X(8)}</li>
 *   <li>{@code errorMessage} &rarr; {@code ERRMSGO}, PIC {@code X(78)}</li>
 * </ul>
 *
 * <p>The write-only {@code PASSWDO} field of the legacy output group is
 * deliberately omitted: passwords are accepted on the request only and are
 * never projected back to the client (a documented security improvement over
 * the legacy plaintext handling). Because the record exposes no sensitive
 * data, the compiler-generated {@link #toString()} is safe to use.</p>
 *
 * <p>The {@code errorMessage} component conveys the confirmation or validation
 * text shown on the map's message line — for example a "user added" confirmation
 * on success — whose common message constants trace to
 * {@code legacy/app/cpy/CSMSG01Y.cpy}.</p>
 *
 * @param transactionName the CICS transaction identifier shown in the header;
 *                        maps to {@code TRNNAMEO} (PIC {@code X(4)}).
 * @param title01         the first application title line; maps to
 *                        {@code TITLE01O} (PIC {@code X(40)}).
 * @param currentDate     the formatted current date ({@code mm/dd/yy}) shown in
 *                        the header; maps to {@code CURDATEO} (PIC {@code X(8)}).
 * @param programName     the online program name shown in the header; maps to
 *                        {@code PGMNAMEO} (PIC {@code X(8)}).
 * @param title02         the second application title line; maps to
 *                        {@code TITLE02O} (PIC {@code X(40)}).
 * @param currentTime     the formatted current time ({@code hh:mm:ss}) shown in
 *                        the header; maps to {@code CURTIMEO} (PIC {@code X(8)}).
 * @param errorMessage    the status or error message line (confirmation or
 *                        validation text); maps to {@code ERRMSGO}
 *                        (PIC {@code X(78)}).
 */
public record UserAddResponse(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String errorMessage) {
}

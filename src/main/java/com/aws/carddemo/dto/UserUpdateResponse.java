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

/**
 * Response payload for the CardDemo <em>Update User</em> screen.
 *
 * <p>This DTO is the Java re-platform of the {@code COUSR2AO} output symbolic map
 * emitted by the legacy CICS program {@code COUSR02C} (transaction {@code CU02})
 * through BMS mapset {@code COUSR02}. It is produced by
 * {@code UserUpdateController} and echoes the user record that was fetched for
 * editing (or the values just saved) back to the client, together with the
 * screen header fields and a status/error message. It carries the field-level
 * 3270 contract forward without rendering a terminal screen: the BMS attribute,
 * length, colour, highlight and modified-data-tag bytes are intentionally not
 * modelled because there is no terminal presentation layer in the migrated
 * application (see AAP &sect;0.3.3).</p>
 *
 * <p><strong>No password field is present.</strong> The legacy {@code COUSR2AO}
 * map defines a {@code PASSWDO PIC X(8)} output field, but the password is a
 * write-only credential on the request contract only and is never echoed back to
 * a caller. Omitting it here upholds the security constraint that passwords are
 * never returned or logged (see AAP &sect;0.9.3). For the same reason the record's
 * default {@code toString()} is safe to use: no sensitive value can leak through
 * it.</p>
 *
 * <p>Each component preserves the field name, maximum display length and type of
 * its originating {@code COUSR2AO} field so that the source-to-target mapping
 * remains fully traceable (see AAP &sect;0.9.2). All fields are modelled as
 * {@link String}; none of them is monetary, so no {@code BigDecimal} (and, per
 * project policy, never {@code double}/{@code float}) is involved.</p>
 *
 * <table class="striped">
 *   <caption>{@code COUSR2AO} output map field mapping</caption>
 *   <thead>
 *     <tr><th>Record component</th><th>COBOL field</th><th>PIC</th><th>Meaning</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code transactionName}</td><td>{@code TRNNAMEO}</td><td>X(4)</td><td>Transaction identifier shown in the header.</td></tr>
 *     <tr><td>{@code title01}</td><td>{@code TITLE01O}</td><td>X(40)</td><td>First header title line.</td></tr>
 *     <tr><td>{@code currentDate}</td><td>{@code CURDATEO}</td><td>X(8)</td><td>Current date, formatted {@code mm/dd/yy}.</td></tr>
 *     <tr><td>{@code programName}</td><td>{@code PGMNAMEO}</td><td>X(8)</td><td>Owning program name shown in the header.</td></tr>
 *     <tr><td>{@code title02}</td><td>{@code TITLE02O}</td><td>X(40)</td><td>Second header title line.</td></tr>
 *     <tr><td>{@code currentTime}</td><td>{@code CURTIMEO}</td><td>X(8)</td><td>Current time, formatted {@code hh:mm:ss}.</td></tr>
 *     <tr><td>{@code userId}</td><td>{@code USRIDINO}</td><td>X(8)</td><td>Echoed identifier of the user being updated.</td></tr>
 *     <tr><td>{@code firstName}</td><td>{@code FNAMEO}</td><td>X(20)</td><td>Fetched first name presented for editing.</td></tr>
 *     <tr><td>{@code lastName}</td><td>{@code LNAMEO}</td><td>X(20)</td><td>Fetched last name presented for editing.</td></tr>
 *     <tr><td>{@code userType}</td><td>{@code USRTYPEO}</td><td>X(1)</td><td>Fetched role code: {@code A}=Admin, {@code U}=User.</td></tr>
 *     <tr><td>{@code errorMessage}</td><td>{@code ERRMSGO}</td><td>X(78)</td><td>Status or error message; constants trace to {@code CSMSG01Y.cpy}.</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>The legacy screen's PF-key semantics (ENTER=Fetch, F3=Save&amp;Exit,
 * F4=Clear, F5=Save, F12=Cancel) are part of the request/action contract and are
 * not carried on this response type.</p>
 *
 * <p>Instances are immutable value objects; this type holds no business logic.</p>
 *
 * @param transactionName transaction identifier for the screen header
 *                        (from {@code TRNNAMEO}, {@code PIC X(4)})
 * @param title01         first header title line
 *                        (from {@code TITLE01O}, {@code PIC X(40)})
 * @param currentDate     current date in {@code mm/dd/yy} form
 *                        (from {@code CURDATEO}, {@code PIC X(8)})
 * @param programName     owning program name for the screen header
 *                        (from {@code PGMNAMEO}, {@code PIC X(8)})
 * @param title02         second header title line
 *                        (from {@code TITLE02O}, {@code PIC X(40)})
 * @param currentTime     current time in {@code hh:mm:ss} form
 *                        (from {@code CURTIMEO}, {@code PIC X(8)})
 * @param userId          echoed identifier of the user being updated
 *                        (from {@code USRIDINO}, {@code PIC X(8)})
 * @param firstName       fetched first name presented for editing
 *                        (from {@code FNAMEO}, {@code PIC X(20)})
 * @param lastName        fetched last name presented for editing
 *                        (from {@code LNAMEO}, {@code PIC X(20)})
 * @param userType        fetched role code, {@code A}=Admin or {@code U}=User
 *                        (from {@code USRTYPEO}, {@code PIC X(1)})
 * @param errorMessage    status or error message returned to the screen
 *                        (from {@code ERRMSGO}, {@code PIC X(78)})
 */
public record UserUpdateResponse(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String userId,
        String firstName,
        String lastName,
        String userType,
        String errorMessage) {
}

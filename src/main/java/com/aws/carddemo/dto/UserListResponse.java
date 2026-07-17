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
 * Immutable response payload for the CardDemo <em>List Users</em> screen.
 *
 * <p>This DTO is the Java re-platform of the 3270 output map produced by the
 * legacy online program {@code COUSR00C} (transaction {@code CU00}) via BMS
 * map {@code COUSR00} / mapset {@code COUSR0A}. It is emitted by
 * {@code UserListController} and is a read-only page projection assembled from
 * {@code UserSecurity} records through the DTO mapper layer.
 *
 * <p>The field-level contract of the original screen is preserved exactly: each
 * component below maps one-to-one to an output field of the {@code COUSR0AO}
 * symbolic copybook, retaining the field's name intent, maximum length, and
 * character (alphanumeric {@code PIC X}) type. Presentation-only artifacts of
 * the terminal map are intentionally <strong>not</strong> carried here, because
 * the migration re-expresses the screen as a REST contract rather than a
 * rendered 3270 panel (AAP &sect;0.3.3): the per-field attribute bytes
 * (the {@code C}/{@code P}/{@code H}/{@code V} suffixed sub-fields), the
 * per-row selection inputs ({@code SEL0001O}&ndash;{@code SEL0010O}), the
 * {@code USRIDINO} search-input field, and the static PF-key hint line are all
 * omitted.
 *
 * <p><strong>Security:</strong> the user password (legacy {@code SEC-USR-PWD})
 * is never projected onto this response under any circumstance (AAP
 * &sect;0.9.3). No password component exists on either this record or its
 * nested {@link UserListRow}.
 *
 * <p>Field-to-source mapping (component &rarr; {@code COUSR0AO} field &rarr;
 * COBOL picture):
 * <table border="1">
 *   <caption>List Users response field contract</caption>
 *   <tr><th>Component</th><th>COBOL field</th><th>Picture</th></tr>
 *   <tr><td>{@code transactionName}</td><td>{@code TRNNAMEO}</td><td>{@code PIC X(4)}</td></tr>
 *   <tr><td>{@code title01}</td><td>{@code TITLE01O}</td><td>{@code PIC X(40)}</td></tr>
 *   <tr><td>{@code title02}</td><td>{@code TITLE02O}</td><td>{@code PIC X(40)}</td></tr>
 *   <tr><td>{@code currentDate}</td><td>{@code CURDATEO}</td><td>{@code PIC X(8)}</td></tr>
 *   <tr><td>{@code programName}</td><td>{@code PGMNAMEO}</td><td>{@code PIC X(8)}</td></tr>
 *   <tr><td>{@code currentTime}</td><td>{@code CURTIMEO}</td><td>{@code PIC X(8)}</td></tr>
 *   <tr><td>{@code pageNumber}</td><td>{@code PAGENUMO}</td><td>{@code PIC X(8)}</td></tr>
 *   <tr><td>{@code users}</td><td>{@code USRID01O}&ndash;{@code USRID10O} (and
 *       {@code FNAME}/{@code LNAME}/{@code UTYPE})</td><td>ten rows</td></tr>
 *   <tr><td>{@code errorMessage}</td><td>{@code ERRMSGO}</td><td>{@code PIC X(78)}</td></tr>
 * </table>
 *
 * <p>The screen presents a fixed page of ten user slots; {@link #users()}
 * therefore carries up to ten ordered {@link UserListRow} entries (fewer on a
 * final, partial page, and empty when the response conveys only an error).
 * Population of the list and the exact row count are owned by the mapping and
 * service layers; this record only transports the projected rows.
 *
 * <p>Instances are immutable value objects: the {@code users} list is defensively
 * copied into an unmodifiable list by the canonical constructor, so callers
 * cannot mutate the response after construction.
 *
 * @param transactionName the originating transaction identifier shown in the
 *                         screen header (legacy {@code TRNNAMEO}, {@code PIC X(4)})
 * @param title01         the first application title line (legacy
 *                         {@code TITLE01O}, {@code PIC X(40)})
 * @param title02         the second application title line (legacy
 *                         {@code TITLE02O}, {@code PIC X(40)})
 * @param currentDate     the current date rendered in the header, formatted
 *                         {@code mm/dd/yy} (legacy {@code CURDATEO},
 *                         {@code PIC X(8)})
 * @param programName     the current program identifier shown in the header
 *                         (legacy {@code PGMNAMEO}, {@code PIC X(8)})
 * @param currentTime     the current time rendered in the header, formatted
 *                         {@code hh:mm:ss} (legacy {@code CURTIMEO},
 *                         {@code PIC X(8)})
 * @param pageNumber      the page indicator for the paged user list (legacy
 *                         {@code PAGENUMO}, {@code PIC X(8)})
 * @param users           the ordered page of user summary rows (up to ten);
 *                         never {@code null} after construction
 * @param errorMessage    the informational or error message line; sourced from
 *                         the common message constants in {@code CSMSG01Y}
 *                         (legacy {@code ERRMSGO}, {@code PIC X(78)})
 */
public record UserListResponse(
        String transactionName,
        String title01,
        String title02,
        String currentDate,
        String programName,
        String currentTime,
        String pageNumber,
        List<UserListRow> users,
        String errorMessage) {

    /**
     * Canonical constructor that guarantees value-object immutability for the
     * {@code users} collection.
     *
     * <p>A {@code null} list is normalized to an empty list, and any supplied
     * list is defensively copied into an unmodifiable snapshot. This keeps the
     * response safe to share and prevents post-construction mutation without
     * introducing any business behavior.
     */
    public UserListResponse {
        users = (users == null) ? List.of() : List.copyOf(users);
    }

    /**
     * A single user summary row within the List Users page.
     *
     * <p>Each row is the Java projection of one occurrence of the repeating
     * {@code COUSR0AO} output group (occurrences {@code 01}&ndash;{@code 10}),
     * preserving the field-level contract of the original 3270 line. The row
     * deliberately carries no password and no per-row selection flag.
     *
     * @param userId    the eight-character user identifier (legacy
     *                  {@code USRIDnnO}, {@code PIC X(8)})
     * @param firstName the user's first name (legacy {@code FNAMEnnO},
     *                  {@code PIC X(20)})
     * @param lastName  the user's last name (legacy {@code LNAMEnnO},
     *                  {@code PIC X(20)})
     * @param userType  the single-character user type: {@code "A"} for an
     *                  administrator or {@code "U"} for a standard user (legacy
     *                  {@code UTYPEnnO}, {@code PIC X(1)})
     */
    public record UserListRow(
            String userId,
            String firstName,
            String lastName,
            String userType) {
    }
}

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
 * Response DTO for the CardDemo Sign-on screen.
 *
 * <p>This immutable data holder is the REST-response counterpart of the 3270
 * sign-on map <strong>COSGN00</strong> (online program {@code COSGN00C},
 * transaction {@code CC00}), surfaced by {@code SignonController}. It carries
 * the display / output (protected) fields that the legacy program placed into
 * the symbolic output group <strong>{@code COSGN0AO}</strong> before issuing
 * {@code EXEC CICS SEND MAP}. Migrating the mainframe UI to REST preserves the
 * field-level 3270 contract (field names, lengths, and PIC-derived types)
 * rather than rendering a terminal, per the migration plan.</p>
 *
 * <p>Field-length provenance from {@code COSGN0AO} (in {@code COSGN00.CPY}) is
 * documented per component below. The values are intentionally modeled as
 * {@link String} to mirror the fixed-width {@code PIC X(n)} character fields of
 * the copybook; length is a documented contract rather than an enforced
 * constraint, because output constraints belong to the inbound request DTO, not
 * to a response body.</p>
 *
 * <p><strong>Security:</strong> the sign-on map's {@code PASSWDO} field is
 * deliberately <em>not</em> represented here. The password is a write-only,
 * inbound credential and must never be echoed back to a client, so it is absent
 * from this response contract. The 3270 attribute bytes (the {@code C}/{@code P}
 * /{@code H}/{@code V} suffixed sub-fields of {@code COSGN0AO}) are likewise
 * omitted, as they are terminal-rendering hints with no bearing on the data
 * contract.</p>
 *
 * <p>This type is a plain, immutable data carrier: it holds no business logic
 * and performs no validation. Being a {@code record}, it exposes canonical
 * accessors, {@code equals}/{@code hashCode}, and a {@code toString} generated
 * by the compiler; the default {@code toString} is safe to use because no
 * sensitive field is present.</p>
 *
 * @param transactionName the transaction identifier shown after the
 *                         {@code 'Tran :'} header label; maps to {@code TRNNAMEO}
 *                         ({@code PIC X(4)}).
 * @param title01         the first application title line; maps to
 *                        {@code TITLE01O} ({@code PIC X(40)}).
 * @param currentDate     the displayed current date in {@code mm/dd/yy} form;
 *                        maps to {@code CURDATEO} ({@code PIC X(8)}).
 * @param programName     the current program name shown after the
 *                        {@code 'Prog :'} header label; maps to {@code PGMNAMEO}
 *                        ({@code PIC X(8)}).
 * @param title02         the second application title line; maps to
 *                        {@code TITLE02O} ({@code PIC X(40)}).
 * @param currentTime     the displayed current time in {@code hh:mm:ss} form;
 *                        maps to {@code CURTIMEO} ({@code PIC X(9)}).
 * @param applId          the CICS application identifier (APPLID); maps to
 *                        {@code APPLIDO} ({@code PIC X(8)}).
 * @param sysId           the CICS system identifier (SYSID); maps to
 *                        {@code SYSIDO} ({@code PIC X(8)}).
 * @param userId          the echoed user identifier entered on the screen; maps
 *                        to {@code USERIDO} ({@code PIC X(8)}).
 * @param errorMessage    the error / status message line (rendered RED, bright
 *                        on the 3270); maps to {@code ERRMSGO}
 *                        ({@code PIC X(78)}). Its text originates from the common
 *                        message constants in {@code CSMSG01Y.cpy}.
 */
public record SignonResponse(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String applId,
        String sysId,
        String userId,
        String errorMessage) {
}

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
 * Immutable response payload for the CardDemo <em>Delete User</em> screen.
 *
 * <p>This record is the Java re-platform of the BMS symbolic <em>output</em>
 * map {@code COUSR3AO} (map set {@code COUSR03}, defined in
 * {@code legacy/cpy-bms/COUSR03.CPY} and {@code legacy/bms/COUSR03.bms}) that
 * was driven by the legacy CICS program {@code COUSR03C} &mdash; whose function
 * is documented as <q>Delete a user from USRSEC file</q>. In the migrated,
 * layered Spring Boot architecture it is produced by
 * {@code UserDeleteController} and serialized to JSON as the client-visible
 * representation of the Delete User screen, replacing the 3270 terminal
 * rendering while preserving the field-level contract of the original map.</p>
 *
 * <h2>Screen semantics preserved</h2>
 * <p>The Delete User transaction ({@code CU03}) is a read-then-confirm flow:
 * the operator keys a user id and presses {@code ENTER} to <em>fetch</em> the
 * matching {@code USRSEC} record, whereupon {@link #firstName() first name},
 * {@link #lastName() last name} and {@link #userType() user type} are echoed
 * back for visual confirmation; pressing {@code F5} then deletes the user.
 * This response therefore carries only the <em>fetched</em> detail needed to
 * confirm a deletion &mdash; it never initiates one. The available program
 * function keys are {@code ENTER=Fetch}, {@code F3=Back}, {@code F4=Clear} and
 * {@code F5=Delete}.</p>
 *
 * <h2>Security</h2>
 * <p>The Delete User map exposes <strong>no password field</strong> (the
 * legacy {@code COUSR3AO} map and {@code COUSR03C} program reference none), and
 * this DTO deliberately preserves that omission per the migration's data
 * protection requirements. No credential or other sensitive value is present,
 * so the record's compiler-generated {@link #toString()} is safe to log.</p>
 *
 * <h2>Field-level traceability</h2>
 * <p>Each component maps one-to-one to an output field of {@code COUSR3AO},
 * preserving the field name intent, {@code String} type and maximum length
 * ({@code PIC X(n)}) of the original 3270 contract. Attribute/control bytes
 * (the {@code *C}/{@code *P}/{@code *H}/{@code *V} sub-fields used only for
 * terminal rendering) are intentionally omitted because there is no terminal
 * to render. Status text carried by {@link #errorMessage()} traces to the
 * shared message copybook {@code legacy/cpy/CSMSG01Y.cpy} (for example
 * {@code CCDA-MSG-INVALID-KEY}) as well as program-local prompts such as
 * {@code "Press PF5 key to delete this user ..."} and the deletion
 * confirmation {@code "<id> has been deleted ..."}.</p>
 *
 * @param transactionName the transaction identifier shown in the screen header;
 *                         maps to {@code COUSR3AO.TRNNAMEO}, {@code PIC X(4)}
 * @param title01         the first application title header line;
 *                         maps to {@code COUSR3AO.TITLE01O}, {@code PIC X(40)}
 * @param title02         the second application title header line;
 *                         maps to {@code COUSR3AO.TITLE02O}, {@code PIC X(40)}
 * @param currentDate     the current date as displayed in the header
 *                         (formatted {@code mm/dd/yy}); maps to
 *                         {@code COUSR3AO.CURDATEO}, {@code PIC X(8)}
 * @param programName     the originating program name shown in the header;
 *                         maps to {@code COUSR3AO.PGMNAMEO}, {@code PIC X(8)}
 * @param currentTime     the current time as displayed in the header
 *                         (formatted {@code hh:mm:ss}); maps to
 *                         {@code COUSR3AO.CURTIMEO}, {@code PIC X(8)}
 * @param userId          the echoed id of the user selected for deletion;
 *                         maps to {@code COUSR3AO.USRIDINO}, {@code PIC X(8)}
 * @param firstName       the fetched first name shown for delete confirmation;
 *                         maps to {@code COUSR3AO.FNAMEO}, {@code PIC X(20)}
 * @param lastName        the fetched last name shown for delete confirmation;
 *                         maps to {@code COUSR3AO.LNAMEO}, {@code PIC X(20)}
 * @param userType        the fetched user role, {@code A} (Admin) or
 *                         {@code U} (User); maps to
 *                         {@code COUSR3AO.USRTYPEO}, {@code PIC X(1)}
 * @param errorMessage    the status or error message presented to the operator;
 *                         maps to {@code COUSR3AO.ERRMSGO}, {@code PIC X(78)}
 */
public record UserDeleteResponse(
        String transactionName,
        String title01,
        String title02,
        String currentDate,
        String programName,
        String currentTime,
        String userId,
        String firstName,
        String lastName,
        String userType,
        String errorMessage) {
}

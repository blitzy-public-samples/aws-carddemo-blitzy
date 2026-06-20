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
package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Screen view contract for the CardDemo <strong>Update User</strong> online screen.
 *
 * <p>This DTO is the modernized, framework-light migration of the legacy CICS/BMS map {@code
 * COUSR02} (mapset {@code COUSR2A}), driven by transaction {@code CU02} and restricted to
 * administrator users. It is <em>not</em> a JPA entity; it simply carries the field contract that
 * the 3270 map exchanged with the terminal so the web / online-service layer can render the screen
 * and receive its input.
 *
 * <p>Field names, order, and fixed widths are preserved verbatim from the authoritative symbolic
 * copybook {@code app/cpy-bms/COUSR02.CPY} (the {@code COUSR2AI} input map); every legacy {@code
 * PIC X(n)} data item becomes a {@link String} bounded by {@link Size}{@code (max = n)}. Only the
 * meaningful data-value fields are modelled — the BMS length/attribute/colour plumbing items and
 * the {@code COUSR2AO} output redefinition are intentionally omitted. The BMS source {@code
 * app/bms/COUSR02.bms} is the rendering reference; the password field is defined {@code ATTRB=DRK}
 * (non-display / masked).
 *
 * <p>On this screen {@code usrIdIn} is the lookup key, while {@code fName}, {@code lName}, {@code
 * passwd} and {@code usrType} are the editable attributes of the user record. The actual fetch and
 * the optimistic update of the {@code user_security} row are performed by {@code
 * com.aws.carddemo.service.online.UserUpdateService}.
 *
 * <p><strong>Credential hygiene:</strong> {@code passwd} is sensitive. It is annotated {@link
 * ToString.Exclude} so it never appears in {@link #toString()} output or logs; when changed it is
 * re-hashed (BCrypt) by the service before persistence — clear-text passwords are never stored or
 * logged.
 *
 * <p>Migration authority: AAP §0.4.1 (screen DTOs derived from {@code cpy-bms} + {@code bms},
 * preserving field lengths), §0.3.4 (the BMS field contract is the UI fidelity reference; no design
 * system is in scope), and §0.6.6 (credential hygiene).
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class UserUpdateScreen {

  // ---- Screen header / context fields (display-only chrome) ----

  /** Transaction identifier shown in the screen header. */
  @Size(max = 4)
  private String trnName; // TRNNAME PIC X(4)

  /** First title line (application-name banner). */
  @Size(max = 40)
  private String title01; // TITLE01 PIC X(40)

  /** Current date header, formatted {@code mm/dd/yy}. */
  @Size(max = 8)
  private String curDate; // CURDATE PIC X(8)

  /** Program identifier shown in the screen header. */
  @Size(max = 8)
  private String pgmName; // PGMNAME PIC X(8)

  /** Second title line (screen-title banner). */
  @Size(max = 40)
  private String title02; // TITLE02 PIC X(40)

  /** Current time header, formatted {@code hh:mm:ss}. */
  @Size(max = 8)
  private String curTime; // CURTIME PIC X(8)

  // ---- User record fields ----

  /** User id to look up and update — the screen key entered by the operator. */
  @Size(max = 8)
  private String usrIdIn; // USRIDIN PIC X(8)

  /** First name (editable). */
  @Size(max = 20)
  private String fName; // FNAME PIC X(20)

  /** Last name (editable). */
  @Size(max = 20)
  private String lName; // LNAME PIC X(20)

  /**
   * Password (editable, sensitive). Rendered {@code ATTRB=DRK} (masked) on the 3270 screen.
   * Excluded from {@link #toString()} and never logged; re-hashed by the service when changed (AAP
   * §0.6.6).
   */
  @ToString.Exclude
  @Size(max = 8)
  private String passwd; // PASSWD PIC X(8)

  /** User type: {@code 'A'} = admin, {@code 'U'} = standard user. */
  @Size(max = 1)
  private String usrType; // USRTYPE PIC X(1)

  // ---- Operator message line ----

  /** Error / informational message line displayed to the operator. */
  @Size(max = 78)
  private String errMsg; // ERRMSG PIC X(78)
}

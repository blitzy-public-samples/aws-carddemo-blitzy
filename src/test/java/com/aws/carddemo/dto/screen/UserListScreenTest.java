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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit 5 + AssertJ unit tests for {@link UserListScreen}, the screen view contract migrated
 * from the legacy CICS/BMS list-users map {@code COUSR00} (CICS transaction {@code CU00},
 * admin-only).
 *
 * <p>The legacy screen is a paged list with a fixed 3270 geometry of <strong>ten</strong> repeating
 * user rows. These tests pin the behavioural-parity invariants that matter for the migration (Agent
 * Action Plan &sect;0.1.1 / &sect;0.7.3):
 *
 * <ul>
 *   <li><b>Field round-trip</b> &mdash; every header, paging/filter and message scalar, and every
 *       logical row field, stores and returns its value unchanged.
 *   <li><b>Nested row type</b> &mdash; {@link UserListScreen.UserListRow} round-trips via its
 *       no-arg constructor, setters and getters.
 *   <li><b>List cardinality &times;10</b> &mdash; the fixed page geometry is preserved; the {@code
 *       rows} list holds exactly ten rows and index order maps to legacy row order.
 *   <li><b>Field-width fidelity</b> &mdash; the {@code @Size(max = n)} constraints match the BMS
 *       {@code LENGTH} metadata from {@code legacy/app/cpy-bms/COUSR00.CPY} and {@code
 *       legacy/app/bms/COUSR00.bms} (AAP &sect;0.4.1, "preserving field lengths").
 *   <li><b>Fresh-instance defaults</b> &mdash; a new screen has null scalars and a non-null, empty
 *       {@code rows} list.
 * </ul>
 *
 * <p>This is intentionally a framework-light unit test: no Spring context, database, Testcontainers
 * or mocking is involved &mdash; only plain construction, setters/getters and AssertJ assertions.
 */
class UserListScreenTest {

  /**
   * 3a. Every header, paging/filter and message scalar must round-trip through its setter/getter
   * pair without mutation (COUSR00 symbolic fields {@code TRNNAMEI}, {@code TITLE01I}, {@code
   * CURDATEI}, {@code PGMNAMEI}, {@code TITLE02I}, {@code CURTIMEI}, {@code PAGENUMI}, {@code
   * USRIDINI}, {@code ERRMSGI}).
   */
  @Test
  void header_and_paging_fields_round_trip() {
    UserListScreen screen = new UserListScreen();

    screen.setTrnName("CU00");
    screen.setTitle01("AWS Mainframe Modernization - CardDemo");
    screen.setCurDate("07/18/22");
    screen.setPgmName("COUSR00C");
    screen.setTitle02("List Users");
    screen.setCurTime("12:30:45");
    screen.setPageNum("00000001");
    screen.setUsrIdIn("USER0001");
    screen.setErrMsg("No more users to display.");

    assertThat(screen.getTrnName()).isEqualTo("CU00");
    assertThat(screen.getTitle01()).isEqualTo("AWS Mainframe Modernization - CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("07/18/22");
    assertThat(screen.getPgmName()).isEqualTo("COUSR00C");
    assertThat(screen.getTitle02()).isEqualTo("List Users");
    assertThat(screen.getCurTime()).isEqualTo("12:30:45");
    assertThat(screen.getPageNum()).isEqualTo("00000001");
    assertThat(screen.getUsrIdIn()).isEqualTo("USER0001");
    assertThat(screen.getErrMsg()).isEqualTo("No more users to display.");
  }

  /**
   * 3b. The nested {@link UserListScreen.UserListRow} POJO must round-trip its five logical fields
   * ({@code SEL000<n>}, {@code USRID0<n>}, {@code FNAME0<n>}, {@code LNAME0<n>}, {@code UTYPE0<n>})
   * through its no-arg constructor, setters and getters. The BMS length/attribute plumbing
   * sub-fields ({@code *L}, {@code *F}/{@code *A}) are deliberately not modelled.
   */
  @Test
  void user_list_row_round_trips_via_setters_and_getters() {
    UserListScreen.UserListRow row = new UserListScreen.UserListRow();

    row.setSel("S");
    row.setUsrId("USER0001");
    row.setFName("John");
    row.setLName("Doe");
    row.setUType("U");

    assertThat(row.getSel()).isEqualTo("S");
    assertThat(row.getUsrId()).isEqualTo("USER0001");
    assertThat(row.getFName()).isEqualTo("John");
    assertThat(row.getLName()).isEqualTo("Doe");
    assertThat(row.getUType()).isEqualTo("U");
  }

  /**
   * 3c. CRITICAL parity invariant: the list-users screen has a fixed geometry of exactly ten rows.
   * After populating {@code rows} with ten distinct {@link UserListScreen.UserListRow} instances,
   * the list must report size 10 and preserve index order (index 0 = legacy row 1 &hellip; index 9
   * = legacy row 10), verified at both ends.
   */
  @Test
  void rows_list_holds_exactly_ten_user_rows() {
    UserListScreen screen = new UserListScreen();

    List<UserListScreen.UserListRow> rows = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      // Distinct values per row prove the index -> legacy-row mapping is preserved.
      rows.add(
          newRow(
              "S",
              String.format("USER%04d", i),
              "First" + i,
              "Last" + i,
              (i % 2 == 0) ? "A" : "U"));
    }
    screen.setRows(rows);

    assertThat(screen.getRows()).hasSize(10);

    UserListScreen.UserListRow first = screen.getRows().get(0);
    assertThat(first.getSel()).isEqualTo("S");
    assertThat(first.getUsrId()).isEqualTo("USER0001");
    assertThat(first.getFName()).isEqualTo("First1");
    assertThat(first.getLName()).isEqualTo("Last1");
    assertThat(first.getUType()).isEqualTo("U");

    UserListScreen.UserListRow last = screen.getRows().get(9);
    assertThat(last.getSel()).isEqualTo("S");
    assertThat(last.getUsrId()).isEqualTo("USER0010");
    assertThat(last.getFName()).isEqualTo("First10");
    assertThat(last.getLName()).isEqualTo("Last10");
    assertThat(last.getUType()).isEqualTo("A");
  }

  /**
   * 3d. Field-width fidelity (BMS {@code LENGTH} parity): the {@code @Size(max = n)} constraints on
   * every screen scalar and on every logical row field must equal the legacy fixed widths declared
   * in {@code COUSR00.CPY} / {@code COUSR00.bms}. Verified via reflection so the assertion fails if
   * a width ever drifts from the legacy contract.
   */
  @Test
  void size_constraints_match_bms_field_widths() throws NoSuchFieldException {
    // Header + paging/filter + message scalars.
    assertThat(sizeMaxOf(UserListScreen.class, "trnName")).isEqualTo(4);
    assertThat(sizeMaxOf(UserListScreen.class, "title01")).isEqualTo(40);
    assertThat(sizeMaxOf(UserListScreen.class, "curDate")).isEqualTo(8);
    assertThat(sizeMaxOf(UserListScreen.class, "pgmName")).isEqualTo(8);
    assertThat(sizeMaxOf(UserListScreen.class, "title02")).isEqualTo(40);
    assertThat(sizeMaxOf(UserListScreen.class, "curTime")).isEqualTo(8);
    assertThat(sizeMaxOf(UserListScreen.class, "pageNum")).isEqualTo(8);
    assertThat(sizeMaxOf(UserListScreen.class, "usrIdIn")).isEqualTo(8);
    assertThat(sizeMaxOf(UserListScreen.class, "errMsg")).isEqualTo(78);

    // The five logical repeating-row fields (UserListRow).
    assertThat(sizeMaxOf(UserListScreen.UserListRow.class, "sel")).isEqualTo(1);
    assertThat(sizeMaxOf(UserListScreen.UserListRow.class, "usrId")).isEqualTo(8);
    assertThat(sizeMaxOf(UserListScreen.UserListRow.class, "fName")).isEqualTo(20);
    assertThat(sizeMaxOf(UserListScreen.UserListRow.class, "lName")).isEqualTo(20);
    assertThat(sizeMaxOf(UserListScreen.UserListRow.class, "uType")).isEqualTo(1);
  }

  /**
   * 3d (round-trip form). Each field must accept and return a value at its full legacy width,
   * documenting the BMS {@code LENGTH} parity with concrete maximum-width payloads: the
   * 20-character first/last names and 8-character user id on a row, and the 78-character error
   * line, 8-character page number and 8-character user-id filter on the screen.
   */
  @Test
  void field_widths_accept_full_length_bms_values() {
    UserListScreen.UserListRow row = new UserListScreen.UserListRow();
    String fullFirstName = "F".repeat(20);
    String fullLastName = "L".repeat(20);
    String fullUserId = "U".repeat(8);
    row.setFName(fullFirstName);
    row.setLName(fullLastName);
    row.setUsrId(fullUserId);
    assertThat(row.getFName()).isEqualTo(fullFirstName).hasSize(20);
    assertThat(row.getLName()).isEqualTo(fullLastName).hasSize(20);
    assertThat(row.getUsrId()).isEqualTo(fullUserId).hasSize(8);

    UserListScreen screen = new UserListScreen();
    String fullErrMsg = "E".repeat(78);
    String fullPageNum = "9".repeat(8);
    String fullUsrIdIn = "U".repeat(8);
    screen.setErrMsg(fullErrMsg);
    screen.setPageNum(fullPageNum);
    screen.setUsrIdIn(fullUsrIdIn);
    assertThat(screen.getErrMsg()).isEqualTo(fullErrMsg).hasSize(78);
    assertThat(screen.getPageNum()).isEqualTo(fullPageNum).hasSize(8);
    assertThat(screen.getUsrIdIn()).isEqualTo(fullUsrIdIn).hasSize(8);
  }

  /**
   * 3e. A freshly constructed screen has all scalar fields unset (null) and a {@code rows} list
   * that is non-null and empty &mdash; the production DTO initializes {@code rows} to an empty,
   * mutable list so callers can populate rows without a null check.
   */
  @Test
  void fresh_instance_has_null_scalars_and_empty_rows() {
    UserListScreen screen = new UserListScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getPageNum()).isNull();
    assertThat(screen.getUsrIdIn()).isNull();
    assertThat(screen.getErrMsg()).isNull();

    assertThat(screen.getRows()).isNotNull().isEmpty();
  }

  /** Builds a {@link UserListScreen.UserListRow} from its five logical field values. */
  private static UserListScreen.UserListRow newRow(
      String sel, String usrId, String fName, String lName, String uType) {
    UserListScreen.UserListRow row = new UserListScreen.UserListRow();
    row.setSel(sel);
    row.setUsrId(usrId);
    row.setFName(fName);
    row.setLName(lName);
    row.setUType(uType);
    return row;
  }

  /**
   * Reads the {@code max} attribute of the {@link Size} constraint declared on the named field of
   * the given type, asserting first that the constraint is present.
   */
  private static int sizeMaxOf(Class<?> declaringType, String fieldName)
      throws NoSuchFieldException {
    Field field = declaringType.getDeclaredField(fieldName);
    Size size = field.getAnnotation(Size.class);
    assertThat(size)
        .as(
            "field '%s' on %s must declare a @Size constraint",
            fieldName, declaringType.getSimpleName())
        .isNotNull();
    return size.max();
  }
}

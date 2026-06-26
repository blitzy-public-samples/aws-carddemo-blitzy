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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link MainMenuScreen}, the Java screen view contract for the legacy CICS/BMS
 * main-menu map {@code COMEN01} (CICS transaction {@code CM00}, program {@code COMEN01C}).
 *
 * <p>These tests pin the field contract migrated verbatim from {@code
 * legacy/app/cpy-bms/COMEN01.CPY} and {@code legacy/app/bms/COMEN01.bms} (AAP &sect;0.4.1 /
 * &sect;0.3.4). The single most important screen-specific invariant is the <strong>twelve</strong>
 * repeating menu-option lines ({@code OPTN001I}..{@code OPTN012I}), which preserve the fixed 24x80
 * 3270 menu geometry. The BMS {@code LENGTH} metadata of every field is asserted both behaviourally
 * (round-trip of values at the exact width) and structurally (reflecting on the {@link Size}
 * constraints declared by the production DTO).
 *
 * <p>Scope: this is a framework-light test &mdash; it constructs the DTO with {@code new} and
 * exercises only plain getters/setters with AssertJ. There is no Spring context, database,
 * Testcontainers, or mocking, in keeping with the contract's role as a pure data carrier.
 */
class MainMenuScreenTest {

  /**
   * The six header fields ({@code TRNNAME}, {@code TITLE01}, {@code CURDATE}, {@code PGMNAME},
   * {@code TITLE02}, {@code CURTIME}) plus the operator {@code OPTION} selection and the {@code
   * ERRMSG} message line must round-trip through their getters/setters exactly as supplied.
   */
  @Test
  void header_and_scalar_fields_round_trip() {
    MainMenuScreen screen = new MainMenuScreen();

    screen.setTrnName("CM00");
    screen.setTitle01("AWS Mainframe Modernization");
    screen.setCurDate("08/22/22");
    screen.setPgmName("COMEN01C");
    screen.setTitle02("CardDemo");
    screen.setCurTime("17:02:43");
    screen.setOption("01");
    screen.setErrMsg("Welcome to the Main Menu");

    assertThat(screen.getTrnName()).isEqualTo("CM00");
    assertThat(screen.getTitle01()).isEqualTo("AWS Mainframe Modernization");
    assertThat(screen.getCurDate()).isEqualTo("08/22/22");
    assertThat(screen.getPgmName()).isEqualTo("COMEN01C");
    assertThat(screen.getTitle02()).isEqualTo("CardDemo");
    assertThat(screen.getCurTime()).isEqualTo("17:02:43");
    assertThat(screen.getOption()).isEqualTo("01");
    assertThat(screen.getErrMsg()).isEqualTo("Welcome to the Main Menu");
  }

  /**
   * The twelve repeating {@code OPTN001I}..{@code OPTN012I} lines are modelled as a {@code
   * List<String>}; index {@code 0} maps to {@code OPTN001I} and index {@code 11} to {@code
   * OPTN012I}. The list must round-trip exactly, preserve order, and carry precisely twelve entries
   * (the fixed 3270 menu cardinality). Each rendered label must fit the {@code PIC X(40)} cell.
   */
  @Test
  void menu_options_list_round_trips_twelve_entries() {
    MainMenuScreen screen = new MainMenuScreen();

    List<String> options = new ArrayList<>();
    options.add("Account View"); // OPTN001I
    options.add("Account Update");
    options.add("Credit Card List");
    options.add("Credit Card View");
    options.add("Credit Card Update");
    options.add("Transaction List");
    options.add("Transaction View");
    options.add("Transaction Add");
    options.add("Transaction Reports");
    options.add("Bill Payment");
    options.add("User List (Admin)");
    options.add("Account Reports"); // OPTN012I

    screen.setOptions(options);

    assertThat(screen.getOptions())
        .hasSize(12)
        .containsExactly(
            "Account View",
            "Account Update",
            "Credit Card List",
            "Credit Card View",
            "Credit Card Update",
            "Transaction List",
            "Transaction View",
            "Transaction Add",
            "Transaction Reports",
            "Bill Payment",
            "User List (Admin)",
            "Account Reports");
    assertThat(screen.getOptions().get(0)).isEqualTo("Account View");
    assertThat(screen.getOptions().get(11)).isEqualTo("Account Reports");
    assertThat(screen.getOptions())
        .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(40));
  }

  /**
   * Field-width fidelity against the BMS {@code LENGTH} metadata: {@code ERRMSG} is {@code X(78)},
   * {@code OPTION} is {@code X(2)}, and every menu-option line is {@code X(40)}. Values supplied at
   * exactly those widths must be carried verbatim, proving the contract never silently truncates or
   * pads beyond the 3270 cell sizes.
   */
  @Test
  void field_width_fidelity_preserves_bms_lengths() {
    MainMenuScreen screen = new MainMenuScreen();

    String errMsg78 =
        String.format("%-78s", "Invalid option. Please select a valid menu number from the list.");
    assertThat(errMsg78).hasSize(78);
    screen.setErrMsg(errMsg78);
    assertThat(screen.getErrMsg()).hasSize(78).isEqualTo(errMsg78);

    screen.setOption("99");
    assertThat(screen.getOption()).hasSize(2).isEqualTo("99");

    String optionLine40 = String.format("%-40s", "Account View");
    assertThat(optionLine40).hasSize(40);
    List<String> options = new ArrayList<>();
    options.add(optionLine40);
    screen.setOptions(options);
    assertThat(screen.getOptions().get(0)).hasSize(40).isEqualTo(optionLine40);
  }

  /**
   * Structural width parity: the production DTO declares a {@link Size} constraint on each scalar
   * field whose {@code max} must equal the BMS {@code LENGTH}. The {@code options} list
   * intentionally carries no {@link Size} annotation (its per-entry width is documented on the
   * field), so it is excluded from this structural check.
   */
  @Test
  void size_constraints_match_bms_field_widths() throws NoSuchFieldException {
    assertThat(sizeMax("trnName")).isEqualTo(4);
    assertThat(sizeMax("title01")).isEqualTo(40);
    assertThat(sizeMax("curDate")).isEqualTo(8);
    assertThat(sizeMax("pgmName")).isEqualTo(8);
    assertThat(sizeMax("title02")).isEqualTo(40);
    assertThat(sizeMax("curTime")).isEqualTo(8);
    assertThat(sizeMax("option")).isEqualTo(2);
    assertThat(sizeMax("errMsg")).isEqualTo(78);
  }

  /**
   * A freshly constructed contract exposes {@code null} for every scalar field while the options
   * list is eagerly initialized to a non-null, empty, mutable list (per the production no-arg
   * constructor), so callers may populate option lines without risking a {@link
   * NullPointerException}.
   */
  @Test
  void fresh_instance_has_null_scalars_and_empty_options_list() {
    MainMenuScreen screen = new MainMenuScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getOption()).isNull();
    assertThat(screen.getErrMsg()).isNull();

    assertThat(screen.getOptions()).isNotNull().isEmpty();
  }

  /**
   * Reads the {@link Size#max()} declared on the named {@link MainMenuScreen} field via reflection.
   * Kept local so the test imports neither {@code java.lang.reflect.Field} nor any Bean Validation
   * runtime beyond the {@link Size} annotation type itself.
   *
   * @param fieldName the declared field name on {@link MainMenuScreen}
   * @return the {@code max} attribute of that field's {@link Size} constraint
   * @throws NoSuchFieldException if the field does not exist on the production DTO
   */
  private static int sizeMax(String fieldName) throws NoSuchFieldException {
    return MainMenuScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class).max();
  }
}

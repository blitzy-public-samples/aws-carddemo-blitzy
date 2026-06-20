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
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link CardViewScreen}, the Java screen-view contract migrated
 * field-for-field from the legacy CICS/BMS <em>View Credit Card Detail</em> mapset {@code COCRDSL}
 * (CICS transaction {@code CCDL}, online program {@code COCRDSLC}).
 *
 * <p>These tests are deliberately framework-light: {@code CardViewScreen} is a plain POJO "view
 * contract" with no persistence, Spring, or domain semantics, so the tests instantiate it directly
 * and assert with AssertJ only &mdash; no Spring context, database, Testcontainers, or Mockito.
 *
 * <p>The assertions guard three properties:
 *
 * <ul>
 *   <li><b>Getter/setter round-trip</b> &mdash; every one of the 15 BMS data fields is wired to a
 *       distinct backing field (a swapped accessor is caught because each field is given a distinct
 *       value);
 *   <li><b>Fixed-width fidelity</b> (Agent Action Plan &sect;0.4.1) &mdash; the {@code PIC X(n)}
 *       width declared by each {@link Size} annotation matches the {@code DFHMDF LENGTH} metadata
 *       in {@code legacy/app/bms/COCRDSL.bms}. The card-detail screens ({@code COCRDSL}/{@code
 *       COCRDUP}) use {@code ERRMSG = X(80)} and {@code INFOMSG = X(40)}, which differ from the
 *       {@code X(78)} and {@code X(45)} widths seen on most other CardDemo screens; these are
 *       pinned explicitly so a copy-paste width error from another screen would fail the build;
 *   <li><b>Fresh-instance defaults</b> &mdash; a newly constructed instance exposes {@code null}
 *       for every field, the expected JavaBean baseline for form binding.
 * </ul>
 *
 * <p>This read-only view screen intentionally has <strong>no</strong> {@code expDay} field; the
 * expiry-day field exists only on the editable {@code CardUpdateScreen} ({@code COCRDUP}).
 */
class CardViewScreenTest {

  /**
   * Verifies that every BMS data field round-trips through its setter and getter. Each field is
   * assigned a distinct value so that a mis-wired accessor (one returning a different field) is
   * detected.
   */
  @Test
  void all_fields_round_trip() {
    CardViewScreen screen = new CardViewScreen();

    // Distinct, exactly-width values for the wide fields so swapped accessors are caught.
    String title01 = exactWidth("T1", 40);
    String title02 = exactWidth("T2", 40);
    String crdName = exactWidth("NAME", 50);
    String infoMsg = exactWidth("INFO", 40);
    String errMsg = exactWidth("ERROR", 80);
    String fkeys = exactWidth("FKEY", 75);

    screen.setTrnName("CCDL");
    screen.setTitle01(title01);
    screen.setCurDate("08/22/22");
    screen.setPgmName("COCRDSLC");
    screen.setTitle02(title02);
    screen.setCurTime("17:02:42");
    screen.setAcctSid("00000000011");
    screen.setCardSid("4111111111111111");
    screen.setCrdName(crdName);
    screen.setCrdStcd("Y");
    screen.setExpMon("12");
    screen.setExpYear("2026");
    screen.setInfoMsg(infoMsg);
    screen.setErrMsg(errMsg);
    screen.setFkeys(fkeys);

    assertThat(screen.getTrnName()).isEqualTo("CCDL");
    assertThat(screen.getTitle01()).isEqualTo(title01);
    assertThat(screen.getCurDate()).isEqualTo("08/22/22");
    assertThat(screen.getPgmName()).isEqualTo("COCRDSLC");
    assertThat(screen.getTitle02()).isEqualTo(title02);
    assertThat(screen.getCurTime()).isEqualTo("17:02:42");
    assertThat(screen.getAcctSid()).isEqualTo("00000000011");
    assertThat(screen.getCardSid()).isEqualTo("4111111111111111");
    assertThat(screen.getCrdName()).isEqualTo(crdName);
    assertThat(screen.getCrdStcd()).isEqualTo("Y");
    assertThat(screen.getExpMon()).isEqualTo("12");
    assertThat(screen.getExpYear()).isEqualTo("2026");
    assertThat(screen.getInfoMsg()).isEqualTo(infoMsg);
    assertThat(screen.getErrMsg()).isEqualTo(errMsg);
    assertThat(screen.getFkeys()).isEqualTo(fkeys);
  }

  /**
   * Pins the BMS field widths for the fields whose lengths are screen-specific. The setter performs
   * no truncation (the POJO stores the value verbatim), so a full-width value round-trips intact,
   * proving the contract honours the 3270 field-length declarations.
   */
  @Test
  void field_width_fidelity_round_trip() {
    CardViewScreen screen = new CardViewScreen();

    // CARDSID = PIC X(16).
    String cardSid = exactWidth("1234567890", 16);
    // INFOMSG = PIC X(40) on the card-detail screen (NOT the X(45) used on most other screens).
    String infoMsg = exactWidth("I", 40);
    // ERRMSG = PIC X(80) on the card-detail screen (NOT the X(78) used on most other screens).
    String errMsg = exactWidth("E", 80);
    // FKEYS = PIC X(75).
    String fkeys = exactWidth("F", 75);

    // Guard the fixtures themselves so the widths under test are unambiguous.
    assertThat(cardSid).hasSize(16);
    assertThat(infoMsg).hasSize(40);
    assertThat(errMsg).hasSize(80);
    assertThat(fkeys).hasSize(75);

    screen.setCardSid(cardSid);
    screen.setInfoMsg(infoMsg);
    screen.setErrMsg(errMsg);
    screen.setFkeys(fkeys);

    assertThat(screen.getCardSid()).hasSize(16).isEqualTo(cardSid);
    assertThat(screen.getInfoMsg()).hasSize(40).isEqualTo(infoMsg);
    assertThat(screen.getErrMsg()).hasSize(80).isEqualTo(errMsg);
    assertThat(screen.getFkeys()).hasSize(75).isEqualTo(fkeys);
  }

  /**
   * Asserts that the {@link Size} {@code max} on each field equals the corresponding {@code DFHMDF
   * LENGTH} from {@code legacy/app/bms/COCRDSL.bms}, locking the migrated contract to the legacy
   * 3270 field widths.
   */
  @Test
  void size_annotation_max_matches_bms_length() throws NoSuchFieldException {
    assertThat(sizeMaxOf("trnName")).isEqualTo(4);
    assertThat(sizeMaxOf("title01")).isEqualTo(40);
    assertThat(sizeMaxOf("curDate")).isEqualTo(8);
    assertThat(sizeMaxOf("pgmName")).isEqualTo(8);
    assertThat(sizeMaxOf("title02")).isEqualTo(40);
    assertThat(sizeMaxOf("curTime")).isEqualTo(8);
    assertThat(sizeMaxOf("acctSid")).isEqualTo(11);
    assertThat(sizeMaxOf("cardSid")).isEqualTo(16);
    assertThat(sizeMaxOf("crdName")).isEqualTo(50);
    assertThat(sizeMaxOf("crdStcd")).isEqualTo(1);
    assertThat(sizeMaxOf("expMon")).isEqualTo(2);
    assertThat(sizeMaxOf("expYear")).isEqualTo(4);
    assertThat(sizeMaxOf("infoMsg")).isEqualTo(40);
    assertThat(sizeMaxOf("errMsg")).isEqualTo(80);
    assertThat(sizeMaxOf("fkeys")).isEqualTo(75);
  }

  /** A freshly constructed screen exposes {@code null} for every field (JavaBean baseline). */
  @Test
  void fresh_instance_fields_default_null() {
    CardViewScreen screen = new CardViewScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getAcctSid()).isNull();
    assertThat(screen.getCardSid()).isNull();
    assertThat(screen.getCrdName()).isNull();
    assertThat(screen.getCrdStcd()).isNull();
    assertThat(screen.getExpMon()).isNull();
    assertThat(screen.getExpYear()).isNull();
    assertThat(screen.getInfoMsg()).isNull();
    assertThat(screen.getErrMsg()).isNull();
    assertThat(screen.getFkeys()).isNull();
  }

  /**
   * Returns the {@link Size} {@code max} attribute declared on the named field of {@link
   * CardViewScreen}. Only the annotation is read (no value access), so no reflective accessibility
   * elevation is required.
   *
   * @param fieldName the declared field name to inspect
   * @return the {@code @Size(max = ...)} value for that field
   * @throws NoSuchFieldException if the field does not exist on {@link CardViewScreen}
   */
  private static int sizeMaxOf(String fieldName) throws NoSuchFieldException {
    Size size = CardViewScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class);
    assertThat(size).as("@Size annotation on field '%s'", fieldName).isNotNull();
    return size.max();
  }

  /**
   * Builds a string of exactly {@code length} characters by repeating {@code seed} and truncating.
   * Used to produce deterministic, exactly-width fixtures for the fixed-width screen fields.
   *
   * @param seed the non-empty text repeated to fill the result
   * @param length the exact length of the returned string
   * @return a string whose length is exactly {@code length}
   */
  private static String exactWidth(String seed, int length) {
    StringBuilder builder = new StringBuilder(length);
    while (builder.length() < length) {
      builder.append(seed);
    }
    return builder.substring(0, length);
  }
}

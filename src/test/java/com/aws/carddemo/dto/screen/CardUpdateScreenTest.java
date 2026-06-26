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
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link CardUpdateScreen}, the modernized screen view contract for the legacy
 * CICS/BMS card-update map {@code COCRDUP} (mapset {@code COCRDUP}, map {@code CCRDUPA}, CICS
 * transaction {@code CCUP}).
 *
 * <p>The assertions in this test are bound to the production source {@code
 * src/main/java/com/aws/carddemo/dto/screen/CardUpdateScreen.java} and the authoritative legacy
 * specifications {@code legacy/app/cpy-bms/COCRDUP.CPY} (the BMS symbolic copybook, which supplies
 * the field names and {@code PIC X(n)} widths) and {@code legacy/app/bms/COCRDUP.bms} (the mapset,
 * which supplies the matching {@code LENGTH=} geometry). Per Agent Action Plan §0.4.1 the migration
 * "preserves field lengths"; this test pins that contract field-by-field.
 *
 * <p>The card-update screen is the editable twin of the read-only card-view screen ({@code
 * COCRDSL}). It exposes two fields that the view screen does <em>not</em>: the editable
 * expiry-<em>day</em> field {@code EXPDAY} ({@code PIC X(2)}) and the second program-function-key
 * legend line {@code FKEYSC} ({@code PIC X(18)}). Those distinguishing members are exercised
 * directly so that this test compiles only against the genuine {@code CardUpdateScreen} API.
 *
 * <p>This is a deliberately framework-free unit test: it constructs the DTO with {@code new} and
 * verifies behaviour with AssertJ only. No Spring context, database, Testcontainers, or mocking is
 * involved, keeping the test fast, deterministic, and free of external dependencies.
 */
class CardUpdateScreenTest {

  /**
   * Every logical screen field must round-trip through its setter and getter unchanged. Each of the
   * 17 fields is assigned a distinct value so that any mis-wired accessor (for example a getter
   * that returns a sibling field) is detected.
   */
  @Test
  void all_fields_round_trip_through_getters_and_setters() {
    CardUpdateScreen screen = new CardUpdateScreen();

    // Header / screen-chrome fields (6).
    screen.setTrnName("CCUP");
    screen.setTitle01("AWS CardDemo");
    screen.setCurDate("01/15/26");
    screen.setPgmName("COCRDUPC");
    screen.setTitle02("Update Credit Card Details");
    screen.setCurTime("12:30:45");

    // Card identification fields (2).
    screen.setAcctSid("12345678901");
    screen.setCardSid("4111111111111111");

    // Editable card-detail fields (5, including the update-only expiry day).
    screen.setCrdName("JOHN Q CARDHOLDER");
    screen.setCrdStcd("Y");
    screen.setExpMon("12");
    screen.setExpYear("2026");
    screen.setExpDay("31");

    // Message and dual program-function-key legend fields (4).
    screen.setInfoMsg("Card details updated successfully");
    screen.setErrMsg("Card status code must be Y or N");
    screen.setFkeys("ENTER=Process F3=Exit");
    screen.setFkeysc("F5=Save F12=Cancel");

    assertThat(screen.getTrnName()).isEqualTo("CCUP");
    assertThat(screen.getTitle01()).isEqualTo("AWS CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("01/15/26");
    assertThat(screen.getPgmName()).isEqualTo("COCRDUPC");
    assertThat(screen.getTitle02()).isEqualTo("Update Credit Card Details");
    assertThat(screen.getCurTime()).isEqualTo("12:30:45");
    assertThat(screen.getAcctSid()).isEqualTo("12345678901");
    assertThat(screen.getCardSid()).isEqualTo("4111111111111111");
    assertThat(screen.getCrdName()).isEqualTo("JOHN Q CARDHOLDER");
    assertThat(screen.getCrdStcd()).isEqualTo("Y");
    assertThat(screen.getExpMon()).isEqualTo("12");
    assertThat(screen.getExpYear()).isEqualTo("2026");
    assertThat(screen.getExpDay()).isEqualTo("31");
    assertThat(screen.getInfoMsg()).isEqualTo("Card details updated successfully");
    assertThat(screen.getErrMsg()).isEqualTo("Card status code must be Y or N");
    assertThat(screen.getFkeys()).isEqualTo("ENTER=Process F3=Exit");
    assertThat(screen.getFkeysc()).isEqualTo("F5=Save F12=Cancel");
  }

  /**
   * Values exactly as wide as their BMS {@code LENGTH=} geometry must round-trip unchanged, with no
   * truncation in the DTO layer. The widths asserted here mirror {@code COCRDUP.bms} / {@code
   * COCRDUP.CPY}: card number {@code X(16)}, error line {@code X(80)}, informational line {@code
   * X(40)}, and the dual PF-key legend {@code X(21)} + {@code X(18)}. Note the card-screen message
   * widths are 80 and 40 (not the 78 / 45 seen on some other CardDemo maps).
   */
  @Test
  void fields_round_trip_at_their_bms_widths() {
    CardUpdateScreen screen = new CardUpdateScreen();

    String cardNumber = "4111111111111111"; // X(16)
    String errorLine = "E".repeat(80); // X(80)
    String infoLine = "I".repeat(40); // X(40)
    String fkeyLine1 = "K".repeat(21); // X(21)
    String fkeyLine2 = "C".repeat(18); // X(18)

    screen.setCardSid(cardNumber);
    screen.setErrMsg(errorLine);
    screen.setInfoMsg(infoLine);
    screen.setFkeys(fkeyLine1);
    screen.setFkeysc(fkeyLine2);

    assertThat(screen.getCardSid()).isEqualTo(cardNumber).hasSize(16);
    assertThat(screen.getErrMsg()).isEqualTo(errorLine).hasSize(80);
    assertThat(screen.getInfoMsg()).isEqualTo(infoLine).hasSize(40);
    assertThat(screen.getFkeys()).isEqualTo(fkeyLine1).hasSize(21);
    assertThat(screen.getFkeysc()).isEqualTo(fkeyLine2).hasSize(18);
  }

  /**
   * Pins the members that distinguish the card-<em>update</em> screen from the read-only
   * card-<em>view</em> screen: the editable expiry day {@code EXPDAY} ({@code X(2)}) and the second
   * PF-key legend line {@code FKEYSC} ({@code X(18)}) alongside the primary legend {@code FKEYS}
   * ({@code X(21)}). A test copied from the view screen would fail to compile against this API
   * because the view screen exposes neither {@code expDay} nor {@code fkeysc}.
   */
  @Test
  void update_screen_exposes_editable_exp_day_and_dual_pf_key_legend() {
    CardUpdateScreen screen = new CardUpdateScreen();

    screen.setExpDay("31");
    screen.setFkeys("ENTER=Process F3=Exit");
    screen.setFkeysc("F5=Save F12=Cancel");

    assertThat(screen.getExpDay()).isEqualTo("31").hasSize(2);
    assertThat(screen.getFkeys()).isEqualTo("ENTER=Process F3=Exit").hasSize(21);
    assertThat(screen.getFkeysc()).isEqualTo("F5=Save F12=Cancel").hasSize(18);
  }

  /**
   * Each field's {@link Size} constraint must declare a {@code max} equal to the original BMS field
   * width, so downstream Bean Validation rejects over-long input exactly where the 3270 map would.
   * The widths are read straight off the declared fields by reflection and compared to the values
   * taken from {@code COCRDUP.CPY} / {@code COCRDUP.bms}.
   */
  @Test
  void size_annotation_max_matches_bms_field_widths() throws NoSuchFieldException {
    assertThat(maxSize("trnName")).isEqualTo(4);
    assertThat(maxSize("title01")).isEqualTo(40);
    assertThat(maxSize("curDate")).isEqualTo(8);
    assertThat(maxSize("pgmName")).isEqualTo(8);
    assertThat(maxSize("title02")).isEqualTo(40);
    assertThat(maxSize("curTime")).isEqualTo(8);
    assertThat(maxSize("acctSid")).isEqualTo(11);
    assertThat(maxSize("cardSid")).isEqualTo(16);
    assertThat(maxSize("crdName")).isEqualTo(50);
    assertThat(maxSize("crdStcd")).isEqualTo(1);
    assertThat(maxSize("expMon")).isEqualTo(2);
    assertThat(maxSize("expYear")).isEqualTo(4);
    assertThat(maxSize("expDay")).isEqualTo(2);
    assertThat(maxSize("infoMsg")).isEqualTo(40);
    assertThat(maxSize("errMsg")).isEqualTo(80);
    assertThat(maxSize("fkeys")).isEqualTo(21);
    assertThat(maxSize("fkeysc")).isEqualTo(18);
  }

  /**
   * A freshly constructed screen carries no field state: the no-argument constructor must leave
   * every String property {@code null} so callers populate the contract explicitly via setters.
   */
  @Test
  void fresh_instance_has_all_null_string_fields() {
    CardUpdateScreen screen = new CardUpdateScreen();

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
    assertThat(screen.getExpDay()).isNull();
    assertThat(screen.getInfoMsg()).isNull();
    assertThat(screen.getErrMsg()).isNull();
    assertThat(screen.getFkeys()).isNull();
    assertThat(screen.getFkeysc()).isNull();
  }

  /**
   * Reads the {@link Size#max()} value declared on the named {@link CardUpdateScreen} field.
   *
   * @param fieldName the declared field name to inspect
   * @return the {@code max} attribute of the field's {@link Size} constraint
   * @throws NoSuchFieldException if the field does not exist on {@link CardUpdateScreen}
   */
  private static int maxSize(String fieldName) throws NoSuchFieldException {
    Field field = CardUpdateScreen.class.getDeclaredField(fieldName);
    Size size = field.getAnnotation(Size.class);
    assertThat(size).as("@Size present on field '%s'", fieldName).isNotNull();
    return size.max();
  }
}

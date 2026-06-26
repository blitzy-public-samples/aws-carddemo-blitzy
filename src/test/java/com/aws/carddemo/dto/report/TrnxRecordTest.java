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
package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TrnxRecord}, the Java migration of the COBOL {@code 01}-level group
 * item {@code TRNX-RECORD} in copybook {@code legacy/app/cpy/COSTM01.CPY} (source-branch {@code
 * app/cpy/COSTM01.CPY}, lines 20-36) — the enriched statement transaction record consumed by the
 * statement-generation flow {@code CBSTM03A}/{@code CBSTM03B}.
 *
 * <p>These tests pin the COBOL parity invariants that must not drift across implementations:
 *
 * <ul>
 *   <li>all ten {@code PIC X(n)} alphanumeric fields round-trip unchanged through their
 *       getter/setter pairs;
 *   <li>{@code TRNX-CAT-CD PIC 9(04)} is modeled as {@link Integer} (compile-time bound);
 *   <li>{@code TRNX-MERCHANT-ID PIC 9(09)} is modeled as {@link Long} (compile-time bound, folder
 *       requirement);
 *   <li>{@code TRNX-AMT PIC S9(09)V99} is a signed {@link BigDecimal} at scale {@code 2} — never
 *       {@code float}/{@code double} (AAP &sect;0.6.1); and
 *   <li>the trailing {@code FILLER PIC X(20)} is exposed only as the {@code FILLER_LEN} width
 *       constant, never as a data field.
 * </ul>
 *
 * <p>The test is intentionally framework-light: no Spring context, database, Testcontainers, or
 * Mockito — just {@code new TrnxRecord()} and AssertJ fluent assertions, matching the production
 * class's framework-free design.
 */
class TrnxRecordTest {

  // -----------------------------------------------------------------------------------------------
  // 3a. All ten PIC X(n) alphanumeric fields round-trip through their getter/setter pairs.
  // -----------------------------------------------------------------------------------------------

  @Test
  void string_fields_round_trip() {
    TrnxRecord record = new TrnxRecord();
    record.setTrnxCardNum("4111111111111111"); // X(16)
    record.setTrnxId("0000000000000123"); // X(16)
    record.setTrnxTypeCd("DB"); // X(02)
    record.setTrnxSource("POS"); // X(10)
    record.setTrnxDesc("GROCERY STORE PURCHASE"); // X(100)
    record.setTrnxMerchantName("ACME STORE"); // X(50)
    record.setTrnxMerchantCity("SEATTLE"); // X(50)
    record.setTrnxMerchantZip("98101"); // X(10)
    record.setTrnxOrigTs("2022-07-18-12.00.00.000000"); // X(26)
    record.setTrnxProcTs("2022-07-18-12.00.01.000000"); // X(26)

    assertThat(record.getTrnxCardNum()).isEqualTo("4111111111111111");
    assertThat(record.getTrnxId()).isEqualTo("0000000000000123");
    assertThat(record.getTrnxTypeCd()).isEqualTo("DB");
    assertThat(record.getTrnxSource()).isEqualTo("POS");
    assertThat(record.getTrnxDesc()).isEqualTo("GROCERY STORE PURCHASE");
    assertThat(record.getTrnxMerchantName()).isEqualTo("ACME STORE");
    assertThat(record.getTrnxMerchantCity()).isEqualTo("SEATTLE");
    assertThat(record.getTrnxMerchantZip()).isEqualTo("98101");
    assertThat(record.getTrnxOrigTs()).isEqualTo("2022-07-18-12.00.00.000000");
    assertThat(record.getTrnxProcTs()).isEqualTo("2022-07-18-12.00.01.000000");
  }

  // -----------------------------------------------------------------------------------------------
  // 3b. TRNX-CAT-CD PIC 9(04) is modeled as Integer (compile-time bound) and round-trips.
  // -----------------------------------------------------------------------------------------------

  @Test
  void cat_cd_is_integer_and_round_trips() {
    TrnxRecord record = new TrnxRecord();
    record.setTrnxCatCd(5); // 9(04)
    Integer catCd = record.getTrnxCatCd(); // compile-time proof: Integer
    assertThat(catCd).isEqualTo(5);
  }

  // -----------------------------------------------------------------------------------------------
  // 3c. TRNX-MERCHANT-ID PIC 9(09) is modeled as Long (folder requirement) and round-trips.
  // -----------------------------------------------------------------------------------------------

  @Test
  void merchant_id_is_long_and_round_trips() {
    TrnxRecord record = new TrnxRecord();
    record.setTrnxMerchantId(999999999L); // 9(09), max 9-digit value
    Long merchantId = record.getTrnxMerchantId(); // compile-time proof: Long
    assertThat(merchantId).isEqualTo(999999999L);
  }

  // -----------------------------------------------------------------------------------------------
  // 3d. TRNX-AMT PIC S9(09)V99 is a signed BigDecimal at scale 2 (AAP 0.6.1) and round-trips.
  // -----------------------------------------------------------------------------------------------

  @Test
  void amount_is_bigdecimal_scale_2_and_round_trips() {
    TrnxRecord record = new TrnxRecord();
    BigDecimal amount = new BigDecimal("123456.78");
    record.setTrnxAmt(amount);
    BigDecimal stored = record.getTrnxAmt(); // compile-time proof: BigDecimal, not float/double
    assertThat(stored).isEqualByComparingTo("123456.78");
    assertThat(stored.scale()).isEqualTo(2);
  }

  @Test
  void amount_supports_negative_signed_values() {
    TrnxRecord record = new TrnxRecord();
    record.setTrnxAmt(new BigDecimal("-999999999.99"));
    assertThat(record.getTrnxAmt()).isEqualByComparingTo("-999999999.99");
    assertThat(record.getTrnxAmt()).isNegative();
  }

  // -----------------------------------------------------------------------------------------------
  // 3e. The trailing FILLER PIC X(20) is exposed only as the FILLER_LEN width constant.
  // -----------------------------------------------------------------------------------------------

  @Test
  void filler_len_constant_is_twenty() {
    assertThat(TrnxRecord.FILLER_LEN).isEqualTo(20);
  }
}

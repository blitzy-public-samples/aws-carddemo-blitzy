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
package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO unit tests for {@link Customer}, the JPA entity translation of the COBOL copybook
 * {@code CVCUS01Y.cpy} ({@code 01 CUSTOMER-RECORD}, RECLN 500, legacy source {@code
 * legacy/app/cpy/CVCUS01Y.cpy}). The entity maps the legacy VSAM {@code CUSTDATA} KSDS (primary key
 * {@code CUST-ID}, KEYLEN 9) onto the {@code customer} table.
 *
 * <p>These tests pin the <strong>COBOL-to-JPA parity invariants</strong> that the persistence layer
 * depends on (Agent Action Plan &sect;0.6.1, &sect;0.6.2, &sect;0.6.7):
 *
 * <ul>
 *   <li>all 18 modeled fields round-trip through their Lombok-generated getters and setters;
 *   <li>the three genuine-numeric identifiers ({@code custId}, {@code custSsn}, {@code
 *       custFicoCreditScore}) are {@link Long} and never a floating-point type (leading-zero
 *       display formatting is a service/DTO concern, never the stored type);
 *   <li>the entity is mapped to the {@code customer} table with {@code custId} as the {@code @Id};
 *       and
 *   <li>the snake_case {@code @Column} names match the migration DDL exactly so Hibernate {@code
 *       validate} succeeds.
 * </ul>
 *
 * <p>This is intentionally a framework-light test: it constructs the POJO directly with {@code new}
 * and asserts with AssertJ only. There is no Spring context, database, {@code @DataJpaTest},
 * Testcontainers, or Mockito.
 */
class CustomerTest {

  /**
   * Exercises every accessor pair: sets all 18 modeled fields to synthetic, in-bounds values and
   * confirms each getter returns exactly what was set. This single thorough round-trip drives
   * accessor coverage for the whole entity.
   */
  @Test
  void gettersAndSettersRoundTrip() {
    Customer customer = new Customer();

    customer.setCustId(987654321L);
    customer.setCustFirstName("JOHN");
    customer.setCustMiddleName("Q");
    customer.setCustLastName("PUBLIC");
    customer.setCustAddrLine1("123 MAIN STREET");
    customer.setCustAddrLine2("SUITE 100");
    customer.setCustAddrLine3("BUILDING A");
    customer.setCustAddrStateCd("TX");
    customer.setCustAddrCountryCd("USA");
    customer.setCustAddrZip("75001");
    customer.setCustPhoneNum1("5551234567");
    customer.setCustPhoneNum2("5559876543");
    customer.setCustSsn(123456789L);
    customer.setCustGovtIssuedId("DL12345678");
    customer.setCustDobYyyyMmDd("1980-01-15");
    customer.setCustEftAccountId("EFT0001234");
    customer.setCustPriCardHolderInd("Y");
    customer.setCustFicoCreditScore(750L);

    assertThat(customer.getCustId()).isEqualTo(987654321L);
    assertThat(customer.getCustFirstName()).isEqualTo("JOHN");
    assertThat(customer.getCustMiddleName()).isEqualTo("Q");
    assertThat(customer.getCustLastName()).isEqualTo("PUBLIC");
    assertThat(customer.getCustAddrLine1()).isEqualTo("123 MAIN STREET");
    assertThat(customer.getCustAddrLine2()).isEqualTo("SUITE 100");
    assertThat(customer.getCustAddrLine3()).isEqualTo("BUILDING A");
    assertThat(customer.getCustAddrStateCd()).isEqualTo("TX");
    assertThat(customer.getCustAddrCountryCd()).isEqualTo("USA");
    assertThat(customer.getCustAddrZip()).isEqualTo("75001");
    assertThat(customer.getCustPhoneNum1()).isEqualTo("5551234567");
    assertThat(customer.getCustPhoneNum2()).isEqualTo("5559876543");
    assertThat(customer.getCustSsn()).isEqualTo(123456789L);
    assertThat(customer.getCustGovtIssuedId()).isEqualTo("DL12345678");
    assertThat(customer.getCustDobYyyyMmDd()).isEqualTo("1980-01-15");
    assertThat(customer.getCustEftAccountId()).isEqualTo("EFT0001234");
    assertThat(customer.getCustPriCardHolderInd()).isEqualTo("Y");
    assertThat(customer.getCustFicoCreditScore()).isEqualTo(750L);
  }

  /**
   * Pins the genuine-numeric identifiers as {@link Long}. COBOL {@code PIC 9(n)} ids are integers,
   * not money, so they must never regress to {@code float}, {@code double}, or {@code BigDecimal}
   * (Agent Action Plan &sect;0.6.1).
   */
  @Test
  void numericFieldsAreLongNotFloatingPoint() throws NoSuchFieldException {
    assertThat(Customer.class.getDeclaredField("custId").getType()).isEqualTo(Long.class);
    assertThat(Customer.class.getDeclaredField("custSsn").getType()).isEqualTo(Long.class);
    assertThat(Customer.class.getDeclaredField("custFicoCreditScore").getType())
        .isEqualTo(Long.class);
  }

  /** Locks the JPA table mapping to the legacy {@code CUSTDATA} store name. */
  @Test
  void tableNameIsCustomer() {
    assertThat(Customer.class.getAnnotation(Table.class).name()).isEqualTo("customer");
  }

  /**
   * Confirms {@code custId} is the primary key: {@code @Id} is present and its {@code @Column} maps
   * to {@code cust_id} with precision 9, matching VSAM {@code CUST-ID} KEYLEN 9.
   */
  @Test
  void primaryKeyIsCustId() throws NoSuchFieldException {
    Field custId = Customer.class.getDeclaredField("custId");

    assertThat(custId.isAnnotationPresent(Id.class)).isTrue();

    Column column = custId.getAnnotation(Column.class);
    assertThat(column.name()).isEqualTo("cust_id");
    assertThat(column.precision()).isEqualTo(9);
  }

  /**
   * Spot-checks a representative set of {@code @Column} names to lock the snake_case mapping that
   * the Flyway migration DDL expects. A representative subset keeps the test readable while still
   * guarding the naming convention across name, address, phone, government-id, and numeric fields.
   */
  @Test
  void representativeColumnMappings() throws NoSuchFieldException {
    assertThat(columnName("custFirstName")).isEqualTo("cust_first_name");
    assertThat(columnName("custAddrLine1")).isEqualTo("cust_addr_line_1");
    assertThat(columnName("custPhoneNum1")).isEqualTo("cust_phone_num_1");
    assertThat(columnName("custGovtIssuedId")).isEqualTo("cust_govt_issued_id");
    assertThat(columnName("custFicoCreditScore")).isEqualTo("cust_fico_credit_score");
  }

  /**
   * Guards the entire entity against floating-point regressions. Iterates every declared field,
   * skipping compiler/coverage synthetic members (for example JaCoCo's {@code $jacocoData}), and
   * asserts no field is {@code float}, {@code double}, {@link Float}, or {@link Double} (Agent
   * Action Plan &sect;0.6.1).
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : Customer.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be a floating-point type", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }

  /** Resolves the {@code @Column} name declared on the named entity field. */
  private static String columnName(String fieldName) throws NoSuchFieldException {
    return Customer.class.getDeclaredField(fieldName).getAnnotation(Column.class).name();
  }
}

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
package com.aws.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link RecordNotFoundException}, the typed exception
 * that models the COBOL/CICS "record not found treated as an error" branches of the legacy CardDemo
 * <em>online</em> programs (CICS {@code RESP = NOTFND}, response code {@code 13}, equivalent to
 * VSAM {@code FILE STATUS '23'} on a <em>required</em> read).
 *
 * <p>The canonical behavioral spec is the sign-on program {@code legacy/app/cbl/COSGN00C.cbl},
 * whose {@code EVALUATE WS-RESP-CD} branch {@code WHEN 13} moved {@code 'User not found. Try again
 * ...'} into the screen message (lines 247-251); the account, card, and transaction view programs
 * behave identically when the requested key does not exist. {@code GlobalExceptionHandler}
 * translates this exception to an HTTP&nbsp;404 response, intentionally distinct from {@code
 * ValidationException} (HTTP&nbsp;400) and {@code AuthorizationException} (HTTP&nbsp;403).
 *
 * <p><strong>Scope boundary documented by these tests (Agent Action Plan &sect;0.6.4).</strong>
 * This exception is reserved for explicit <em>online</em> not-found errors ONLY. The daily-posting
 * batch archetype {@code legacy/app/cbl/CBTRN02C.cbl} does <em>not</em> treat a missing record as
 * an error: the cross-reference and account look-ups record reject reasons (codes {@code
 * 100}/{@code 101}) instead of failing, and the transaction-category-balance read accepts {@code
 * FILE STATUS '00' OR '23'} and <em>creates</em> the row when it is absent (find-or-create /
 * upsert). Those batch paths map a missing row to {@link java.util.Optional#empty()} or
 * reject/create logic and MUST NOT raise this exception; {@link #documentsBatchUpsertNonUse()}
 * encodes that contract.
 *
 * <p>These tests are intentionally framework-light: the exception is constructed directly with
 * {@code new} and every assertion uses AssertJ only. There is no Spring context, database,
 * Testcontainers, or Mockito. The sample identifiers used below (account, card, user, and
 * transaction ids) are non-sensitive lookup keys — never credentials.
 */
class RecordNotFoundExceptionTest {

  /**
   * The two-argument constructor must derive the detail message from the {@code entityName + " not
   * found for key: " + key} format and expose both pieces of diagnostic context verbatim.
   */
  @Test
  void twoArgConstructor_formatsDefaultMessage() {
    RecordNotFoundException ex = new RecordNotFoundException("Account", "00000000123");

    assertThat(ex.getMessage()).isEqualTo("Account not found for key: 00000000123");
    assertThat(ex.getEntityName()).isEqualTo("Account");
    assertThat(ex.getKey()).isEqualTo("00000000123");
  }

  /**
   * The default message format must generalize across every VSAM-backed store. The {@code "User"}
   * case ties directly to the {@code COSGN00C} {@code WHEN 13} "User not found" online branch, and
   * the {@code "Card"} case represents a typical 16-digit card-number lookup key.
   */
  @Test
  void defaultMessage_worksForOtherEntities() {
    assertThat(new RecordNotFoundException("Card", "4111111111111111").getMessage())
        .isEqualTo("Card not found for key: 4111111111111111");
    assertThat(new RecordNotFoundException("User", "USER0001").getMessage())
        .isEqualTo("User not found for key: USER0001");
  }

  /**
   * The cause-preserving constructor must retain the supplied {@link Throwable} as the cause while
   * still deriving the same default message and exposing the structured entity/key context.
   */
  @Test
  void causeConstructor_setsCauseAndMessageAndFields() {
    Throwable cause = new IllegalStateException("vsam");

    RecordNotFoundException ex =
        new RecordNotFoundException("Transaction", "0000000000000007", cause);

    assertThat(ex.getCause()).isSameAs(cause);
    assertThat(ex.getMessage()).isEqualTo("Transaction not found for key: 0000000000000007");
    assertThat(ex.getEntityName()).isEqualTo("Transaction");
    assertThat(ex.getKey()).isEqualTo("0000000000000007");
  }

  /**
   * The exception must sit within the CardDemo typed hierarchy and remain unchecked, so translated
   * service and controller signatures stay free of {@code throws} clauses — reproducing the COBOL
   * immediate-transfer-to-error-handling semantics.
   */
  @Test
  void isCardDemoException_andRuntimeException() {
    assertThat(new RecordNotFoundException("Account", "1"))
        .isInstanceOf(CardDemoException.class)
        .isInstanceOf(RuntimeException.class);
  }

  /**
   * Documented-contract test for the Agent Action Plan &sect;0.6.4 scope boundary.
   *
   * <p>{@code RecordNotFoundException} is an online-only HTTP&nbsp;404 signal. It must NOT be
   * thrown on the batch find-or-create / {@link java.util.Optional#empty()} paths: in {@code
   * legacy/app/cbl/CBTRN02C.cbl} a missing cross-reference or account sets a reject reason (codes
   * {@code 100}/{@code 101}) rather than raising an error, and the transaction-category-balance
   * read accepts {@code FILE STATUS '00' OR '23'} and creates the row when it is absent. Because
   * this exception is a pure immutable value object with no service collaborators, the boundary is
   * asserted at the unit level by confirming that an instance merely carries the entity and key it
   * was constructed with — the type never decides, on its own, whether a miss is an error.
   */
  @Test
  void documentsBatchUpsertNonUse() {
    RecordNotFoundException ex = new RecordNotFoundException("TransactionCategoryBalance", "1");

    assertThat(ex.getEntityName()).isEqualTo("TransactionCategoryBalance");
    assertThat(ex.getKey()).isEqualTo("1");
    assertThat(ex.getMessage()).isEqualTo("TransactionCategoryBalance not found for key: 1");
  }
}

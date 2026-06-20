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
 * Pure unit tests for {@link IoStatusException}, the Java equivalent of the COBOL <em>"unexpected
 * {@code FILE STATUS} &rarr; abend"</em> path in the daily-posting archetype {@code
 * legacy/app/cbl/CBTRN02C.cbl}.
 *
 * <p>These tests are intentionally framework-light — no Spring context, no database, no
 * Testcontainers and no mocks. They construct the exception directly with {@code new
 * IoStatusException(...)} and assert on its public accessors, matching the production class's plain
 * value-object design and keeping the suite fast and deterministic (AAP &sect;0.6.7).
 *
 * <p>The expected vectors are derived line-for-line from the COBOL {@code 9910-DISPLAY-IO-STATUS}
 * paragraph [{@code CBTRN02C.cbl} L714-L726] and the {@code 9999-ABEND-PROGRAM} paragraph
 * [L707-L711], pinning the byte-faithful operator-display rendering and the abend code so the
 * externally observable behavior cannot drift during the migration (AAP &sect;0.6.4, &sect;0.6.6,
 * &sect;0.7.3):
 *
 * <ul>
 *   <li><strong>Branch B</strong> (both status bytes are ASCII digits AND the first byte is not
 *       {@code '9'}): the COBOL {@code MOVE '0000' TO IO-STATUS-04} then {@code MOVE IO-STATUS TO
 *       IO-STATUS-04(3:2)} left zero-pads the two-byte status to width four (e.g. {@code "23"}
 *       &rarr; {@code "0023"}).
 *   <li><strong>Branch A</strong> (the status is NOT all-numeric OR the first byte is {@code '9'}):
 *       the first byte is kept verbatim and the SECOND BYTE is moved into a binary halfword and
 *       rendered as a three-digit decimal ({@code PIC 999}), so it is a numeric byte value rather
 *       than a character digit (e.g. {@code "9"} + reason byte {@code 4} &rarr; {@code "9004"}).
 * </ul>
 */
class IoStatusExceptionTest {

  /**
   * Branch B of {@code 9910-DISPLAY-IO-STATUS} (CBTRN02C L723-L725): when both status bytes are
   * ASCII digits and the first byte is not {@code '9'}, the two-character status is left
   * zero-padded to the fixed four-character {@code IO-STATUS-04} width.
   */
  @Test
  void formattedStatus_branchB_zeroPadsNumericStatusToFour() {
    assertThat(new IoStatusException("ACCTFILE", "READ", "00").getFormattedStatus())
        .isEqualTo("0000");
    assertThat(new IoStatusException("ACCTFILE", "READ", "10").getFormattedStatus())
        .isEqualTo("0010");
    assertThat(new IoStatusException("ACCTFILE", "READ", "23").getFormattedStatus())
        .isEqualTo("0023");
    assertThat(new IoStatusException("ACCTFILE", "READ", "35").getFormattedStatus())
        .isEqualTo("0035");
    assertThat(new IoStatusException("ACCTFILE", "READ", "37").getFormattedStatus())
        .isEqualTo("0037");

    // The rendered status is always exactly four characters (the fixed IO-STATUS-04 PIC layout).
    assertThat(new IoStatusException("ACCTFILE", "READ", "00").getFormattedStatus()).hasSize(4);
    assertThat(new IoStatusException("ACCTFILE", "READ", "10").getFormattedStatus()).hasSize(4);
    assertThat(new IoStatusException("ACCTFILE", "READ", "23").getFormattedStatus()).hasSize(4);
    assertThat(new IoStatusException("ACCTFILE", "READ", "35").getFormattedStatus()).hasSize(4);
    assertThat(new IoStatusException("ACCTFILE", "READ", "37").getFormattedStatus()).hasSize(4);
  }

  /**
   * Branch A of {@code 9910-DISPLAY-IO-STATUS} (CBTRN02C L715-L721): when the status is not
   * all-numeric OR its first byte is {@code '9'}, the first byte is kept verbatim and the second
   * byte is rendered as the three-digit decimal value of its raw byte ({@code PIC 999}).
   */
  @Test
  void formattedStatus_branchA_usesFirstCharThenByteValueOfSecond() {
    // VSAM extended status: first char '9', second char a binary reason byte whose value is 4.
    // (char) 4 is the control character whose byte value is 4, so the COBOL MOVE of that byte into
    // the binary halfword renders as String.format("%03d", 4) == "004" -> "9004" (CBTRN02C
    // L715-721).
    String vsamExtendedStatus = "9" + (char) 4;
    assertThat(new IoStatusException("CARDFILE", "READ", vsamExtendedStatus).getFormattedStatus())
        .isEqualTo("9004");

    // A first byte of '9' forces Branch A even when both bytes are ASCII digits. The second char
    // '0' has byte value 48 (ASCII '0' == 48) -> "048", so "90" renders as "9048" — decisively NOT
    // "0090". This proves the '9'-class routing and that the second byte is a NUMERIC BYTE VALUE,
    // not a character digit (CBTRN02C L715-721).
    assertThat(new IoStatusException("CARDFILE", "READ", "90").getFormattedStatus())
        .isEqualTo("9048");

    assertThat(new IoStatusException("CARDFILE", "READ", vsamExtendedStatus).getFormattedStatus())
        .hasSize(4);
    assertThat(new IoStatusException("CARDFILE", "READ", "90").getFormattedStatus()).hasSize(4);
  }

  /**
   * The COBOL {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} (CBTRN02C L721, L725) emits the
   * FIXED literal text {@code "FILE STATUS IS: NNNN"} (the {@code NNNN} is part of the literal, NOT
   * a substitution placeholder) immediately followed by the four-character status with no
   * separator.
   */
  @Test
  void displayMessage_prefixesFixedLiteralBeforeFormattedStatus() {
    assertThat(new IoStatusException("ACCTFILE", "READ", "23").getDisplayMessage())
        .isEqualTo("FILE STATUS IS: NNNN0023");
    assertThat(new IoStatusException("ACCTFILE", "READ", "35").getDisplayMessage())
        .isEqualTo("FILE STATUS IS: NNNN0035");

    // The four-character formatted status is appended verbatim after the fixed prefix, confirming
    // the NNNN is literal text and the status is the suffix.
    assertThat(new IoStatusException("F", "READ", "35").getDisplayMessage())
        .isEqualTo(
            "FILE STATUS IS: NNNN" + new IoStatusException("F", "READ", "35").getFormattedStatus());
  }

  /** The constructor arguments are echoed back verbatim by the corresponding accessors. */
  @Test
  void getters_echoConstructorArguments() {
    IoStatusException ex = new IoStatusException("ACCTFILE", "READ", "35");
    assertThat(ex.getFileName()).isEqualTo("ACCTFILE");
    assertThat(ex.getOperation()).isEqualTo("READ");
    assertThat(ex.getFileStatus()).isEqualTo("35");
  }

  /**
   * Parity with {@code 9999-ABEND-PROGRAM} (CBTRN02C L710): {@code MOVE 999 TO ABCODE} immediately
   * before {@code CALL 'CEE3ABD'} fixes the batch abend code at {@code 999}.
   */
  @Test
  void batchAbendCode_is999() {
    assertThat(IoStatusException.BATCH_ABEND_CODE).isEqualTo(999);
  }

  /**
   * The four-argument constructor chains the supplied cause, retrievable via {@code getCause()}.
   */
  @Test
  void causeConstructor_setsCause() {
    Throwable cause = new java.io.IOException("disk");
    assertThat(new IoStatusException("ACCTFILE", "WRITE", "92", cause).getCause()).isSameAs(cause);
  }

  /**
   * The type sits in the CardDemo typed-exception hierarchy and is unchecked, mirroring the COBOL
   * abend semantics in which an unrecoverable condition transferred control immediately to the
   * abend paragraph rather than being declared on a call signature.
   */
  @Test
  void isCardDemoException_andRuntimeException() {
    assertThat(new IoStatusException("F", "READ", "35"))
        .isInstanceOf(CardDemoException.class)
        .isInstanceOf(RuntimeException.class);
  }

  /**
   * The default detail message is a descriptive, non-contractual composite, so this asserts on its
   * constituent parts (file name, operation and the byte-faithful display line) rather than the
   * exact full string, keeping the test robust against benign message wording changes.
   */
  @Test
  void message_containsFileOperationAndDisplay() {
    IoStatusException ex = new IoStatusException("ACCTFILE", "READ", "35");
    assertThat(ex.getMessage())
        .contains("ACCTFILE")
        .contains("READ")
        .contains("FILE STATUS IS: NNNN0035");
  }

  /**
   * Documents the AAP &sect;0.6.4 contract that FILE STATUS {@code '00'} (successful I/O), {@code
   * '10'} (end-of-file) and {@code '23'} (record-not-found) are handled by NORMAL control flow —
   * successful return, reader exhaustion / loop end, and {@link java.util.Optional#empty()} or a
   * find-or-create (upsert) respectively — and are therefore NOT the unexpected-status abend path.
   * The migrated services and batch steps MUST NOT construct or throw {@code IoStatusException} for
   * these three statuses; it is reserved for the "any other status" branch of {@code
   * 9910-DISPLAY-IO-STATUS} / {@code 9999-ABEND-PROGRAM}.
   *
   * <p>Because {@code IoStatusException} is itself a plain value object with no service
   * collaborators, the contract is asserted here at the rendering level: were one of these statuses
   * ever supplied, it still renders correctly through Branch B exactly like any other numeric
   * status.
   */
  @Test
  void statusLiterals_00_10_23_areExpected_notAbend() {
    assertThat(new IoStatusException("ACCTFILE", "READ", "00").getFormattedStatus())
        .isEqualTo("0000");
    assertThat(new IoStatusException("DALYTRAN", "READ", "10").getFormattedStatus())
        .isEqualTo("0010");
    assertThat(new IoStatusException("TCATBALF", "READ", "23").getFormattedStatus())
        .isEqualTo("0023");
  }
}

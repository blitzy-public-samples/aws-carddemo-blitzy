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
package com.aws.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link Messages}, the constant holder migrated
 * verbatim from the legacy AWS CardDemo COBOL copybooks {@code app/cpy/CSMSG01Y.cpy} (user
 * messages) and {@code app/cpy/CSMSG02Y.cpy} (abend work-area field widths).
 *
 * <p>The headline parity invariant verified here is that the two display messages are
 * <strong>exactly 50 characters</strong> long (COBOL {@code PIC X(50)}), right-padded with spaces.
 * Those messages are sent verbatim to fixed 24&times;80 3270 screen fields, so a wrong length would
 * shift the rendered layout and break screen-layout parity (Agent Action Plan &sect;0.6). The
 * {@code ABEND_*_LENGTH} constants must equal the COBOL picture clauses {@code 4 / 8 / 50 / 72},
 * because they drive the typed-exception / abend-record formatting (&sect;0.6.6).
 *
 * <p>These tests are deliberately free of any framework dependency: no Spring context, no database,
 * and no mocks. They assert against the production constants exactly as declared — never relaxing
 * the {@code length == 50} invariant or the abend width values.
 */
class MessagesTest {

  /**
   * {@code CCDA-MSG-THANK-YOU} ({@code PIC X(50)}) must be exactly 50 characters and, once its
   * trailing space padding is removed, equal the migrated base text byte-for-byte.
   */
  @Test
  @DisplayName("MSG_THANK_YOU is exactly 50 chars (PIC X(50)) with the expected base text")
  void thank_you_message_is_padded_to_fifty_chars() {
    // Non-negotiable parity invariant: the field width is fixed at 50 bytes.
    assertThat(Messages.MSG_THANK_YOU).hasSize(50);
    // The visible content (sans right padding) matches the COBOL VALUE literal exactly.
    assertThat(Messages.MSG_THANK_YOU.stripTrailing())
        .isEqualTo("Thank you for using CardDemo application...");
  }

  /**
   * {@code CCDA-MSG-INVALID-KEY} ({@code PIC X(50)}) must be exactly 50 characters and, once its
   * trailing space padding is removed, equal the migrated base text byte-for-byte.
   */
  @Test
  @DisplayName("MSG_INVALID_KEY is exactly 50 chars (PIC X(50)) with the expected base text")
  void invalid_key_message_is_padded_to_fifty_chars() {
    // Non-negotiable parity invariant: the field width is fixed at 50 bytes.
    assertThat(Messages.MSG_INVALID_KEY).hasSize(50);
    // The visible content (sans right padding) matches the COBOL VALUE literal exactly.
    assertThat(Messages.MSG_INVALID_KEY.stripTrailing())
        .isEqualTo("Invalid key pressed. Please see below...");
  }

  /**
   * Assigning a {@code VALUE} literal to a COBOL {@code PIC X(n)} field left-justifies the literal
   * and pads it on the <em>right</em> with spaces. Because both texts are shorter than 50
   * characters, each constant must begin with a non-space character and end with a space — proving
   * the padding is trailing (right) and not leading (left).
   */
  @Test
  @DisplayName("Both messages are right-padded with trailing spaces, never left-padded")
  void messages_are_right_padded_not_left_padded() {
    assertThat(Messages.MSG_THANK_YOU).doesNotStartWith(" ").endsWith(" ");
    assertThat(Messages.MSG_INVALID_KEY).doesNotStartWith(" ").endsWith(" ");
  }

  /**
   * The {@code ABEND-DATA} work area from {@code CSMSG02Y.cpy} declares four fixed-width fields:
   * {@code ABEND-CODE PIC X(4)}, {@code ABEND-CULPRIT PIC X(8)}, {@code ABEND-REASON PIC X(50)} and
   * {@code ABEND-MSG PIC X(72)}. The migrated width constants must match those picture clauses so
   * abend-record formatting remains layout-faithful to the mainframe original.
   */
  @Test
  @DisplayName("ABEND_* widths match CSMSG02Y PIC clauses: 4 / 8 / 50 / 72")
  void abend_field_widths_match_cobol_picture_clauses() {
    assertThat(Messages.ABEND_CODE_LENGTH).isEqualTo(4);
    assertThat(Messages.ABEND_CULPRIT_LENGTH).isEqualTo(8);
    assertThat(Messages.ABEND_REASON_LENGTH).isEqualTo(50);
    assertThat(Messages.ABEND_MSG_LENGTH).isEqualTo(72);
  }
}

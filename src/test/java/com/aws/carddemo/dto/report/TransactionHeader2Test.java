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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TransactionHeader2}, the Java migration of the COBOL {@code 01
 * TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'} 01-level (legacy copybook {@code
 * legacy/app/cpy/CVTRA07Y.cpy}, line 48; source-branch {@code app/cpy/CVTRA07Y.cpy}).
 *
 * <p>{@code TRANSACTION-HEADER-2} is the Daily Transaction Report separator rule that the legacy
 * batch program {@code CBTRN03C} prints between the column-title header and the transaction detail
 * rows. Because the COBOL field carries no variable data (it is a pure {@code VALUE ALL '-'}
 * literal), the production type is modeled as a non-instantiable constant holder.
 *
 * <p>These tests pin the single <strong>byte-faithful parity invariant</strong> on which the
 * golden-file report output depends (Agent Action Plan &sect;0.6.1): the separator line is composed
 * of <em>exactly</em> 133 hyphen characters, matching the {@code PIC X(133)} width.
 *
 * <p>This is intentionally a framework-light test: it references the constants directly and asserts
 * with AssertJ only. There is no Spring context, database, Testcontainers, or Mockito.
 */
class TransactionHeader2Test {

  // --- 3a. SEPARATOR_LINE parity: exactly 133 hyphens (CVTRA07Y.cpy line 48) ---

  @Test
  void separator_line_has_length_133() {
    assertThat(TransactionHeader2.SEPARATOR_LINE).hasSize(133);
  }

  @Test
  void separator_line_is_all_hyphens() {
    // Compare against an independently computed expected value rather than hand-typing 133 dashes;
    // isEqualTo pins both the length and the content (every character must be a hyphen).
    assertThat(TransactionHeader2.SEPARATOR_LINE).isEqualTo("-".repeat(133));
  }

  // --- 3b. LENGTH constant parity: matches the PIC X(133) declared width ---

  @Test
  void length_constant_is_133() {
    assertThat(TransactionHeader2.LENGTH).isEqualTo(133);
  }

  // --- 3c. Constant-holder shape: private no-arg constructor (covers the ctor for JaCoCo) ---

  @Test
  void class_has_a_private_no_arg_constructor() throws Exception {
    Constructor<TransactionHeader2> ctor = TransactionHeader2.class.getDeclaredConstructor();
    assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
    ctor.setAccessible(true);
    assertThat(ctor.newInstance()).isNotNull();
  }
}

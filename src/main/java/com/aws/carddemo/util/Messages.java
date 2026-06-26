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

/**
 * Application-wide message text and abend work-area field widths migrated verbatim from the legacy
 * AWS CardDemo COBOL copybooks {@code CSMSG01Y} and {@code CSMSG02Y}.
 *
 * <p>The {@code MSG_*} constants reproduce the exact byte content of the corresponding COBOL {@code
 * PIC X(50)} fields — including the trailing-space padding that COBOL applies when a fixed-width
 * field is initialized from a shorter {@code VALUE} literal. Preserving this padding is required so
 * that on-screen messages and any serialized records remain byte-faithful to the mainframe output
 * (golden-file parity, per Agent Action Plan §0.6).
 *
 * <p>The {@code ABEND_*_LENGTH} constants capture the fixed widths of the {@code ABEND-DATA} work
 * area used by the legacy abend routine. They are consumed by the {@code exception} package when
 * formatting abend records so that the externally observable layout matches the COBOL original.
 *
 * <p>Source copybooks:
 *
 * <ul>
 *   <li>{@code app/cpy/CSMSG01Y.cpy} — {@code 01 CCDA-COMMON-MESSAGES} (two {@code PIC X(50)}
 *       messages).
 *   <li>{@code app/cpy/CSMSG02Y.cpy} — {@code 01 ABEND-DATA} work area (four {@code VALUE SPACES}
 *       fields).
 * </ul>
 *
 * <p>This is a non-instantiable utility class; every member is {@code public static final}.
 */
public final class Messages {

  /**
   * Sign-off message displayed when a user exits the application.
   *
   * <p>Source: {@code CSMSG01Y.cpy} field {@code CCDA-MSG-THANK-YOU PIC X(50)}. The COBOL {@code
   * VALUE} literal is 49 characters ({@code "Thank you for using CardDemo application..."} followed
   * by 6 spaces); the {@code PIC X(50)} field right-pads it with one additional space, so the
   * stored value is exactly 50 characters = 43 characters of text + 7 trailing spaces. Reproduced
   * byte-for-byte for golden-file parity.
   */
  public static final String MSG_THANK_YOU = "Thank you for using CardDemo application...       ";

  /**
   * Error message shown when an unmapped attention (AID / PF) key is pressed.
   *
   * <p>Source: {@code CSMSG01Y.cpy} field {@code CCDA-MSG-INVALID-KEY PIC X(50)}. The COBOL {@code
   * VALUE} literal is 49 characters ({@code "Invalid key pressed. Please see below..."} followed by
   * 9 spaces); the {@code PIC X(50)} field right-pads it with one additional space, so the stored
   * value is exactly 50 characters = 40 characters of text + 10 trailing spaces. Reproduced
   * byte-for-byte for golden-file parity.
   */
  public static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...          ";

  /**
   * Fixed width, in characters, of {@code ABEND-DATA} field {@code ABEND-CODE PIC X(4)} from {@code
   * CSMSG02Y.cpy}.
   */
  public static final int ABEND_CODE_LENGTH = 4;

  /**
   * Fixed width, in characters, of {@code ABEND-DATA} field {@code ABEND-CULPRIT PIC X(8)} from
   * {@code CSMSG02Y.cpy}.
   */
  public static final int ABEND_CULPRIT_LENGTH = 8;

  /**
   * Fixed width, in characters, of {@code ABEND-DATA} field {@code ABEND-REASON PIC X(50)} from
   * {@code CSMSG02Y.cpy}.
   */
  public static final int ABEND_REASON_LENGTH = 50;

  /**
   * Fixed width, in characters, of {@code ABEND-DATA} field {@code ABEND-MSG PIC X(72)} from {@code
   * CSMSG02Y.cpy}.
   */
  public static final int ABEND_MSG_LENGTH = 72;

  /** Utility class — not meant to be instantiated. */
  private Messages() {
    // utility class
    throw new AssertionError("Messages is a non-instantiable utility class");
  }
}

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
package com.cardemo.common.message;

/**
 * System-wide message string constants used across the CardDemo application.
 *
 * <p>This class is a direct, faithful translation of the COBOL copybook
 * {@code app/cpy/CSMSG01Y.cpy} which defines the {@code CCDA-COMMON-MESSAGES}
 * group. Each constant preserves the exact COBOL {@code VALUE} clause text
 * padded to the {@code PIC X(50)} field width for 100% business logic parity
 * with the original mainframe implementation.</p>
 *
 * <h2>COBOL Source Reference</h2>
 * <pre>
 * 01 CCDA-COMMON-MESSAGES.
 *   05 CCDA-MSG-THANK-YOU         PIC X(50) VALUE
 *        'Thank you for using CardDemo application...      '.
 *   05 CCDA-MSG-INVALID-KEY       PIC X(50) VALUE
 *        'Invalid key pressed. Please see below...         '.
 * </pre>
 *
 * <h2>Traceability</h2>
 * <ul>
 *   <li>{@code CCDA-MSG-THANK-YOU}  &rarr; {@link #THANK_YOU_MESSAGE}</li>
 *   <li>{@code CCDA-MSG-INVALID-KEY} &rarr; {@link #INVALID_KEY_MESSAGE}</li>
 * </ul>
 *
 * @see <a href="legacy/app/cpy/CSMSG01Y.cpy">Original COBOL copybook</a>
 * @version CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19)
 */
public final class MessageConstants {

    // ← CCDA-MSG-THANK-YOU PIC X(50)
    // COBOL VALUE: 'Thank you for using CardDemo application...      '
    // Runtime length: 50 characters (PIC X(50) right-padded with spaces)
    /**
     * Thank-you message displayed upon successful completion or application exit.
     * Faithfully preserves the 50-character fixed-width value from COBOL
     * field {@code CCDA-MSG-THANK-YOU PIC X(50)}.
     */
    public static final String THANK_YOU_MESSAGE =
            "Thank you for using CardDemo application...       ";

    // ← CCDA-MSG-INVALID-KEY PIC X(50)
    // COBOL VALUE: 'Invalid key pressed. Please see below...         '
    // Runtime length: 50 characters (PIC X(50) right-padded with spaces)
    /**
     * Error message displayed when the user presses an unrecognized key.
     * Faithfully preserves the 50-character fixed-width value from COBOL
     * field {@code CCDA-MSG-INVALID-KEY PIC X(50)}.
     */
    public static final String INVALID_KEY_MESSAGE =
            "Invalid key pressed. Please see below...          ";

    /**
     * Private constructor to prevent instantiation.
     * This is a utility constants class following the constants-holder pattern.
     */
    private MessageConstants() {
        // Utility class — not designed for instantiation
    }
}

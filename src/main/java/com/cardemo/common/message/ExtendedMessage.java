/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.common.message;

/**
 * Extended message structure for abend (abnormal end) diagnostic reporting.
 *
 * <p>This class is a direct, faithful translation of the COBOL copybook
 * {@code CSMSG02Y.cpy} (originally named {@code CABENDD.CPY}), which defines
 * the {@code ABEND-DATA} group — work areas used by the abend routine across
 * the CardDemo application for capturing diagnostic data when an abnormal
 * termination occurs.</p>
 *
 * <h3>Source COBOL Structure</h3>
 * <pre>{@code
 * 01  ABEND-DATA.
 *   05  ABEND-CODE      PIC X(4)   VALUE SPACES.
 *   05  ABEND-CULPRIT   PIC X(8)   VALUE SPACES.
 *   05  ABEND-REASON    PIC X(50)  VALUE SPACES.
 *   05  ABEND-MSG       PIC X(72)  VALUE SPACES.
 * }</pre>
 *
 * <h3>COBOL-to-Java Field Mapping</h3>
 * <table>
 *   <caption>Field mapping from CSMSG02Y.cpy ABEND-DATA</caption>
 *   <tr><th>COBOL Field</th><th>Java Field</th><th>Max Length</th></tr>
 *   <tr><td>ABEND-CODE PIC X(4)</td><td>abendCode</td><td>4</td></tr>
 *   <tr><td>ABEND-CULPRIT PIC X(8)</td><td>abendCulprit</td><td>8</td></tr>
 *   <tr><td>ABEND-REASON PIC X(50)</td><td>abendReason</td><td>50</td></tr>
 *   <tr><td>ABEND-MSG PIC X(72)</td><td>abendMsg</td><td>72</td></tr>
 * </table>
 *
 * <p>All fields are initialized to empty strings, matching the COBOL
 * {@code VALUE SPACES} initialization semantics. This class is a mutable POJO
 * (not a record) to preserve the in-place update semantics of COBOL working
 * storage.</p>
 *
 * @see com.cardemo.common.message.MessageConstants
 */
public class ExtendedMessage {

    // ── Field Definitions (← CSMSG02Y.cpy ABEND-DATA group) ──────────

    /** Abend code identifying the type of abnormal termination. */
    private String abendCode = "";      // ← ABEND-CODE PIC X(4) — max 4 chars

    /** Name of the program or module that caused the abend. */
    private String abendCulprit = "";   // ← ABEND-CULPRIT PIC X(8) — max 8 chars

    /** Descriptive reason for the abnormal termination. */
    private String abendReason = "";    // ← ABEND-REASON PIC X(50) — max 50 chars

    /** Full diagnostic message associated with the abend. */
    private String abendMsg = "";       // ← ABEND-MSG PIC X(72) — max 72 chars

    // ── Constructor ───────────────────────────────────────────────────

    /**
     * Constructs a new {@code ExtendedMessage} with all fields initialized to
     * empty strings, matching the COBOL {@code VALUE SPACES} default.
     */
    public ExtendedMessage() {
        // Fields are initialized at declaration to "" (COBOL VALUE SPACES equivalent).
        // No additional initialization required.
    }

    // ── Getters ───────────────────────────────────────────────────────

    /**
     * Returns the abend code.
     *
     * @return the abend code (max 4 characters), never {@code null}
     */
    public String getAbendCode() {
        return abendCode;
    }

    /**
     * Returns the name of the program or module that caused the abend.
     *
     * @return the culprit identifier (max 8 characters), never {@code null}
     */
    public String getAbendCulprit() {
        return abendCulprit;
    }

    /**
     * Returns the reason for the abnormal termination.
     *
     * @return the abend reason text (max 50 characters), never {@code null}
     */
    public String getAbendReason() {
        return abendReason;
    }

    /**
     * Returns the full diagnostic message associated with the abend.
     *
     * @return the diagnostic message (max 72 characters), never {@code null}
     */
    public String getAbendMsg() {
        return abendMsg;
    }

    // ── Setters ───────────────────────────────────────────────────────

    /**
     * Sets the abend code.
     *
     * @param abendCode the abend code (max 4 characters)
     */
    public void setAbendCode(String abendCode) {
        this.abendCode = abendCode;
    }

    /**
     * Sets the name of the program or module that caused the abend.
     *
     * @param abendCulprit the culprit identifier (max 8 characters)
     */
    public void setAbendCulprit(String abendCulprit) {
        this.abendCulprit = abendCulprit;
    }

    /**
     * Sets the reason for the abnormal termination.
     *
     * @param abendReason the abend reason text (max 50 characters)
     */
    public void setAbendReason(String abendReason) {
        this.abendReason = abendReason;
    }

    /**
     * Sets the full diagnostic message associated with the abend.
     *
     * @param abendMsg the diagnostic message (max 72 characters)
     */
    public void setAbendMsg(String abendMsg) {
        this.abendMsg = abendMsg;
    }

    // ── Object Overrides ──────────────────────────────────────────────

    /**
     * Returns a diagnostic string representation of this extended message,
     * suitable for logging and troubleshooting.
     *
     * <p>Format: {@code ExtendedMessage{abendCode='...', abendCulprit='...',
     * abendReason='...', abendMsg='...'}}</p>
     *
     * @return a string representation containing all abend diagnostic fields
     */
    @Override
    public String toString() {
        return "ExtendedMessage{"
                + "abendCode='" + abendCode + '\''
                + ", abendCulprit='" + abendCulprit + '\''
                + ", abendReason='" + abendReason + '\''
                + ", abendMsg='" + abendMsg + '\''
                + '}';
    }
}

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.common.enums;

/**
 * Enumerates VSAM file status codes used throughout the CardDemo application.
 *
 * <p>In the original COBOL programs, every VSAM file I/O operation sets a two-byte
 * {@code FILE STATUS} field that is checked after each READ, WRITE, REWRITE, DELETE,
 * STARTBR, READNEXT, and ENDBR operation. The common pattern in CardDemo COBOL
 * programs is:</p>
 *
 * <pre>
 *   EXEC CICS READ
 *        DATASET  (WS-FILE-NAME)
 *        INTO     (WS-RECORD)
 *        RIDFLD   (WS-KEY)
 *        RESP     (WS-RESP-CD)
 *        RESP2    (WS-REAS-CD)
 *   END-EXEC
 *   EVALUATE WS-RESP-CD
 *       WHEN DFHRESP(NORMAL)    → STATUS '00'
 *       WHEN DFHRESP(NOTFND)    → STATUS '23'
 *       WHEN DFHRESP(DUPREC)    → STATUS '22'
 *       WHEN DFHRESP(NOTOPEN)   → STATUS '35'
 *       ...
 *   END-EVALUATE
 * </pre>
 *
 * <p>This enum provides the Java equivalent of these two-byte status codes. Each
 * constant maps to a specific VSAM condition and is used to construct the
 * appropriate exception in the CardDemo exception hierarchy:</p>
 * <ul>
 *   <li>{@link #SUCCESS} (00) — Operation completed normally</li>
 *   <li>{@link #DUPLICATE_RECORD} (22) →
 *       {@link com.cardemo.common.exception.DuplicateRecordException}</li>
 *   <li>{@link #RECORD_NOT_FOUND} (23) →
 *       {@link com.cardemo.common.exception.RecordNotFoundException}</li>
 *   <li>{@link #FILE_NOT_AVAILABLE} (35) →
 *       {@link com.cardemo.common.exception.FileStatusException}</li>
 * </ul>
 *
 * <p>The {@code code} field preserves the original two-character COBOL
 * representation for logging and diagnostic compatibility.</p>
 *
 * @see com.cardemo.common.exception.FileStatusException
 * @see com.cardemo.common.exception.RecordNotFoundException
 * @see com.cardemo.common.exception.DuplicateRecordException
 */
public enum FileStatusCode {

    /**
     * Successful completion — VSAM status '00'.
     * <p>The I/O operation completed without error. This is the normal outcome
     * for READ, WRITE, REWRITE, DELETE, STARTBR, READNEXT, and ENDBR operations.
     * In COBOL: {@code DFHRESP(NORMAL)} or {@code FILE STATUS = '00'}.</p>
     */
    SUCCESS("00", "Successful completion"),

    /**
     * Duplicate record — VSAM status '22'.
     * <p>A WRITE operation attempted to insert a record with a primary key that
     * already exists in the VSAM KSDS dataset. In COBOL: {@code DFHRESP(DUPREC)}
     * or {@code FILE STATUS = '22'}.</p>
     * <p>Maps to {@link com.cardemo.common.exception.DuplicateRecordException}.</p>
     */
    DUPLICATE_RECORD("22", "Duplicate record — key already exists"),

    /**
     * Record not found — VSAM status '23'.
     * <p>A READ or DELETE operation specified a key that does not exist in the
     * VSAM KSDS dataset. In COBOL: {@code DFHRESP(NOTFND)} or
     * {@code FILE STATUS = '23'}.</p>
     * <p>Maps to {@link com.cardemo.common.exception.RecordNotFoundException}.</p>
     */
    RECORD_NOT_FOUND("23", "Record not found — key does not exist"),

    /**
     * End of file — VSAM status '10'.
     * <p>A READNEXT operation reached the end of the VSAM dataset during a
     * browse operation. In COBOL: {@code DFHRESP(ENDFILE)} or
     * {@code FILE STATUS = '10'}.</p>
     */
    END_OF_FILE("10", "End of file reached during browse"),

    /**
     * File not available — VSAM status '35'.
     * <p>The specified VSAM dataset is not open or not available for processing.
     * In COBOL: {@code DFHRESP(NOTOPEN)} or {@code FILE STATUS = '35'}.
     * This typically occurs during the batch window when CLOSEFIL has closed
     * files for batch processing.</p>
     * <p>Maps to {@link com.cardemo.common.exception.FileStatusException}.</p>
     */
    FILE_NOT_AVAILABLE("35", "File not available or not open"),

    /**
     * Sequence error — VSAM status '21'.
     * <p>An attempted WRITE violates the ascending key sequence requirement
     * of the VSAM KSDS. In COBOL: {@code FILE STATUS = '21'}.</p>
     */
    SEQUENCE_ERROR("21", "Sequence error — key out of ascending order"),

    /**
     * Invalid key — VSAM status '23' variant for STARTBR.
     * <p>A STARTBR operation specified an invalid or out-of-range key.
     * Treated identically to RECORD_NOT_FOUND in most CardDemo programs.</p>
     */
    INVALID_KEY("23", "Invalid key for browse operation"),

    /**
     * I/O error — VSAM status '30'.
     * <p>A permanent I/O error occurred during a VSAM operation. In COBOL:
     * {@code FILE STATUS = '30'}. This is a non-recoverable error that
     * triggers the {@code 9999-ABEND-PROGRAM} paragraph.</p>
     */
    IO_ERROR("30", "Permanent I/O error"),

    /**
     * Logic error — VSAM status '92'.
     * <p>A logic error occurred (e.g., READ UPDATE without prior READ,
     * or REWRITE without prior READ UPDATE). In COBOL: {@code FILE STATUS = '92'}.</p>
     */
    LOGIC_ERROR("92", "Logic error — invalid I/O operation sequence"),

    /**
     * Record locked — VSAM status '68'.
     * <p>The requested record is currently locked by another task (CICS
     * multi-user contention). In COBOL: {@code DFHRESP(LOCKED)} or
     * {@code FILE STATUS = '68'}.</p>
     */
    RECORD_LOCKED("68", "Record locked by another task");

    /**
     * The two-character VSAM file status code.
     * <p>Preserves the original COBOL two-byte representation for logging
     * and diagnostic compatibility.</p>
     */
    private final String code;

    /**
     * Human-readable description of this file status condition.
     */
    private final String description;

    /**
     * Constructs a file status code constant.
     *
     * @param code        the two-character VSAM file status code
     * @param description human-readable description of the condition
     */
    FileStatusCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the two-character VSAM file status code.
     *
     * @return the status code (e.g., "00", "22", "23", "35")
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the human-readable description of this status condition.
     *
     * @return the description string
     */
    public String getDescription() {
        return description;
    }

    /**
     * Indicates whether this status code represents a successful operation.
     *
     * @return {@code true} if this is {@link #SUCCESS}, {@code false} otherwise
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Resolves a {@code FileStatusCode} from the given two-character code string.
     *
     * <p>Iterates through all enum constants and returns the first one whose
     * {@code code} field matches the input. If no match is found, throws
     * {@link IllegalArgumentException}.</p>
     *
     * @param code the two-character VSAM file status code to look up
     * @return the matching {@code FileStatusCode} constant
     * @throws IllegalArgumentException if no constant matches the given code
     */
    public static FileStatusCode fromCode(String code) {
        for (FileStatusCode status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown VSAM file status code: " + code);
    }

    /**
     * Returns a formatted string including the enum name, code, and description.
     *
     * @return string in format {@code "NAME(code: description)"}
     */
    @Override
    public String toString() {
        return name() + "(" + code + ": " + description + ")";
    }
}

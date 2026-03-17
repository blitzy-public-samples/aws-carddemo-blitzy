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
package com.cardemo.common.exception;

import com.cardemo.common.enums.FileStatusCode;

/**
 * Exception indicating that a record with the same key already exists.
 *
 * <p>Maps directly to VSAM file status code '22' ({@code DFHRESP(DUPREC)}).
 * This condition occurs when a WRITE operation attempts to insert a record
 * with a primary key that already exists in the VSAM KSDS dataset.</p>
 *
 * <h2>COBOL Usage Patterns</h2>
 * <p>In the original COBOL programs, this condition is handled after WRITE
 * operations, particularly in:</p>
 * <ul>
 *   <li>COUSR01C.cbl (User Add) — checks for duplicate SEC-USR-ID before WRITE</li>
 *   <li>COTRN02C.cbl (Transaction Add) — checks for duplicate TRAN-ID after WRITE</li>
 *   <li>CBTRN02C.cbl (Daily Posting) — handles duplicate transaction records</li>
 * </ul>
 *
 * <pre>
 *   EXEC CICS WRITE DATASET('USRSEC') FROM(WS-USER-RECORD)
 *        RIDFLD(WS-USR-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
 *   END-EXEC
 *   IF WS-RESP-CD = DFHRESP(DUPREC)
 *      MOVE 'User ID already exists' TO WS-ERR-MSG
 *      ...
 *   END-IF
 * </pre>
 *
 * @see FileStatusCode#DUPLICATE_RECORD
 * @see FileStatusException
 */
public class DuplicateRecordException extends FileStatusException {

    private static final long serialVersionUID = 1L;

    /**
     * The key value that caused the duplicate key violation.
     * Corresponds to the COBOL {@code RIDFLD} value in the failed
     * EXEC CICS WRITE operation.
     */
    private final String keyValue;

    /**
     * Constructs a new {@code DuplicateRecordException} with the dataset name
     * and the key value that already exists.
     *
     * @param datasetName the name of the dataset (e.g., "USRSEC", "user_security")
     * @param keyValue    the key value that caused the duplicate violation
     */
    public DuplicateRecordException(String datasetName, String keyValue) {
        super(FileStatusCode.DUPLICATE_RECORD, datasetName,
              "Duplicate record in " + datasetName + " for key: " + keyValue);
        this.keyValue = keyValue;
    }

    /**
     * Constructs a new {@code DuplicateRecordException} with a custom message.
     *
     * @param datasetName the name of the dataset
     * @param keyValue    the key value that caused the duplicate violation
     * @param message     a custom detail message
     */
    public DuplicateRecordException(String datasetName, String keyValue, String message) {
        super(FileStatusCode.DUPLICATE_RECORD, datasetName, message);
        this.keyValue = keyValue;
    }

    /**
     * Constructs a new {@code DuplicateRecordException} wrapping a root cause.
     *
     * @param datasetName the name of the dataset
     * @param keyValue    the key value that caused the duplicate violation
     * @param cause       the underlying cause (e.g., JPA DataIntegrityViolationException)
     */
    public DuplicateRecordException(String datasetName, String keyValue, Throwable cause) {
        super(FileStatusCode.DUPLICATE_RECORD, datasetName,
              "Duplicate record in " + datasetName + " for key: " + keyValue, cause);
        this.keyValue = keyValue;
    }

    /**
     * Returns the key value that caused the duplicate key violation.
     *
     * @return the duplicate key value string
     */
    public String getKeyValue() {
        return keyValue;
    }
}

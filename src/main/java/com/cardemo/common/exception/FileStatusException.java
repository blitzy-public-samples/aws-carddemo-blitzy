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
 * Exception representing a VSAM file status error condition.
 *
 * <p>This is the base exception class for all VSAM file I/O errors in the
 * CardDemo application. It carries a {@link FileStatusCode} enum constant
 * that identifies the specific two-byte VSAM status code that triggered the
 * error, providing a direct mapping to the original COBOL error handling.</p>
 *
 * <h2>COBOL Origin</h2>
 * <p>In the original COBOL programs, VSAM file operations return a two-byte
 * file status code in the {@code FILE STATUS} field. Non-zero status codes
 * are evaluated in paragraphs such as {@code 9910-DISPLAY-IO-STATUS} and
 * may trigger the {@code 9999-ABEND-PROGRAM} abend routine. Common patterns:</p>
 * <pre>
 *   EVALUATE WS-RESP-CD
 *       WHEN DFHRESP(NORMAL)   → continue
 *       WHEN DFHRESP(NOTFND)   → handle record-not-found (STATUS '23')
 *       WHEN DFHRESP(DUPREC)   → handle duplicate key (STATUS '22')
 *       WHEN DFHRESP(NOTOPEN)  → handle file not available (STATUS '35')
 *       WHEN OTHER             → 9999-ABEND-PROGRAM
 *   END-EVALUATE
 * </pre>
 *
 * <h2>Exception Hierarchy</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException
 *     └── FileStatusException           (this class)
 *         ├── RecordNotFoundException   (STATUS '23' — DFHRESP(NOTFND))
 *         └── DuplicateRecordException  (STATUS '22' — DFHRESP(DUPREC))
 * </pre>
 *
 * @see FileStatusCode
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 */
public class FileStatusException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * The VSAM file status code that caused this exception.
     */
    private final FileStatusCode statusCode;

    /**
     * The name of the VSAM dataset (or equivalent table/entity) involved
     * in the failed operation. Corresponds to the COBOL
     * {@code DATASET(WS-FILE-NAME)} parameter in EXEC CICS I/O commands.
     */
    private final String datasetName;

    /**
     * Constructs a new {@code FileStatusException} with the specified status code,
     * dataset name, and detail message.
     *
     * @param statusCode  the VSAM file status code that caused this exception
     * @param datasetName the name of the dataset involved (e.g., "ACCTDATA", "accounts")
     * @param message     a detail message describing the error condition
     */
    public FileStatusException(FileStatusCode statusCode, String datasetName, String message) {
        super(message);
        this.statusCode = statusCode;
        this.datasetName = datasetName;
    }

    /**
     * Constructs a new {@code FileStatusException} with the specified status code,
     * dataset name, detail message, and root cause.
     *
     * @param statusCode  the VSAM file status code that caused this exception
     * @param datasetName the name of the dataset involved
     * @param message     a detail message describing the error condition
     * @param cause       the underlying cause of this exception
     */
    public FileStatusException(FileStatusCode statusCode, String datasetName,
                               String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.datasetName = datasetName;
    }

    /**
     * Returns the VSAM file status code associated with this exception.
     *
     * @return the {@link FileStatusCode} enum constant
     */
    public FileStatusCode getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the name of the VSAM dataset (or PostgreSQL table) involved.
     *
     * @return the dataset name (e.g., "ACCTDATA", "accounts")
     */
    public String getDatasetName() {
        return datasetName;
    }

    /**
     * Returns a formatted string including the status code, dataset name,
     * and the exception message for diagnostic output.
     *
     * @return formatted diagnostic string
     */
    @Override
    public String toString() {
        return "FileStatusException{" +
                "statusCode=" + statusCode +
                ", datasetName='" + datasetName + '\'' +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}

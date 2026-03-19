/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
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

/**
 * Exception indicating that a record with the same key already exists.
 *
 * <p>Maps directly to VSAM file status code {@code '22'}
 * ({@code DFHRESP(DUPREC)} / {@code DFHRESP(DUPKEY)}). This condition occurs
 * when a WRITE operation to a VSAM dataset (or its PostgreSQL equivalent)
 * detects a duplicate primary key or alternate key.</p>
 *
 * <h2>COBOL Usage Patterns</h2>
 * <p>In the original COBOL programs, this condition is handled after WRITE
 * operations, particularly in:</p>
 * <ul>
 *   <li>COBIL00C.cbl — handles {@code DFHRESP(DUPKEY)} and
 *       {@code DFHRESP(DUPREC)} on transaction write</li>
 *   <li>COCRDLIC.cbl — handles {@code DFHRESP(DUPREC)} on card record
 *       operations</li>
 *   <li>COTRN02C.cbl (Transaction Add) — checks for duplicate TRAN-ID after
 *       WRITE</li>
 *   <li>CBTRN02C.cbl (Daily Posting) — handles duplicate transaction records
 *       during batch posting</li>
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
 * <h2>Exception Hierarchy</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException
 *     └── FileStatusException (STATUS code "22")
 *         └── DuplicateRecordException (this class)
 * </pre>
 *
 * @see FileStatusException
 */
public class DuplicateRecordException extends FileStatusException {

    private static final long serialVersionUID = 1L;

    /**
     * The fixed VSAM file status code for duplicate record conditions.
     * This constant is always passed to the parent {@link FileStatusException}
     * constructor to preserve COBOL traceability.
     */
    private static final String DUPLICATE_STATUS_CODE = "22";

    /**
     * Constructs a new {@code DuplicateRecordException} with the specified
     * detail message.
     *
     * <p>The VSAM file status code {@code "22"} is automatically set via the
     * parent constructor.</p>
     *
     * @param message the detail message describing the duplicate condition
     */
    public DuplicateRecordException(String message) {
        super(DUPLICATE_STATUS_CODE, message);
    }

    /**
     * Constructs a new {@code DuplicateRecordException} with the specified
     * detail message and root cause.
     *
     * <p>Use this constructor when wrapping lower-level exceptions (e.g.,
     * JPA {@code DataIntegrityViolationException}) with COBOL-traceable
     * context. The VSAM file status code {@code "22"} is automatically
     * set.</p>
     *
     * @param message the detail message describing the duplicate condition
     * @param cause   the underlying cause of this exception
     */
    public DuplicateRecordException(String message, Throwable cause) {
        super(DUPLICATE_STATUS_CODE, message, cause);
    }

    /**
     * Convenience constructor that builds a descriptive message from the
     * entity name and key value.
     *
     * <p>Usage example:
     * {@code throw new DuplicateRecordException("Account", "00000000001");}</p>
     *
     * @param entityName the name of the entity or dataset (e.g., "Account",
     *                   "USRSEC")
     * @param key        the key value that caused the duplicate violation
     */
    public DuplicateRecordException(String entityName, String key) {
        this("Duplicate " + entityName + " with key: " + key);
    }
}

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
 * Exception indicating that a requested record was not found.
 *
 * <p>Maps directly to VSAM file status code {@code '23'}
 * ({@code DFHRESP(NOTFND)}). This is the most commonly handled non-success
 * condition in CardDemo COBOL programs, occurring when:</p>
 * <ul>
 *   <li>A READ operation specifies a key that does not exist in the VSAM
 *       KSDS dataset</li>
 *   <li>A DELETE operation targets a non-existent record</li>
 *   <li>A STARTBR operation specifies a key beyond the dataset's key
 *       range</li>
 * </ul>
 *
 * <h2>COBOL Usage Patterns</h2>
 * <p>In the original COBOL programs, this condition is handled after every
 * keyed READ operation:</p>
 * <pre>
 *   EXEC CICS READ DATASET('ACCTDATA') INTO(WS-ACCOUNT-RECORD)
 *        RIDFLD(WS-ACCT-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
 *   END-EXEC
 *   IF WS-RESP-CD = DFHRESP(NOTFND)
 *      MOVE 'Account not found' TO WS-ERR-MSG
 *      ...
 *   END-IF
 * </pre>
 *
 * <p>Programs that check for this condition include:</p>
 * <ul>
 *   <li>COACTVWC.cbl — account view (handles NOTFND on ACCTDATA)</li>
 *   <li>COACTUPC.cbl — account update (handles NOTFND on ACCTDATA,
 *       CUSTDATA, XREFDATA)</li>
 *   <li>COCRDSLC.cbl — card detail (handles NOTFND on CARDDATA)</li>
 *   <li>COSGN00C.cbl — sign-on (handles NOTFND on USRSEC for user
 *       lookup)</li>
 *   <li>COBIL00C.cbl — bill payment (handles NOTFND on ACCTDATA)</li>
 *   <li>CBTRN02C.cbl — daily posting batch (INVALID KEY on READ maps to
 *       validation fail reason 100/101)</li>
 * </ul>
 *
 * <h2>Exception Hierarchy</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException
 *     └── FileStatusException (STATUS code "23")
 *         └── RecordNotFoundException (this class)
 * </pre>
 *
 * @see FileStatusException
 */
public class RecordNotFoundException extends FileStatusException {

    private static final long serialVersionUID = 1L;

    /**
     * The fixed VSAM file status code for record-not-found conditions.
     * This constant is always passed to the parent {@link FileStatusException}
     * constructor to preserve COBOL traceability.
     */
    private static final String NOT_FOUND_STATUS_CODE = "23";

    /**
     * Constructs a new {@code RecordNotFoundException} with the specified
     * detail message.
     *
     * <p>The VSAM file status code {@code "23"} is automatically set via the
     * parent constructor.</p>
     *
     * @param message the detail message describing the not-found condition
     */
    public RecordNotFoundException(String message) {
        super(NOT_FOUND_STATUS_CODE, message);
    }

    /**
     * Constructs a new {@code RecordNotFoundException} with the specified
     * detail message and root cause.
     *
     * <p>Use this constructor when wrapping lower-level exceptions (e.g.,
     * JPA {@code NoResultException}, {@code EmptyResultDataAccessException})
     * with COBOL-traceable context. The VSAM file status code {@code "23"}
     * is automatically set.</p>
     *
     * @param message the detail message describing the not-found condition
     * @param cause   the underlying cause of this exception
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(NOT_FOUND_STATUS_CODE, message, cause);
    }

    /**
     * Convenience constructor that builds a descriptive message from the
     * entity name and key value.
     *
     * <p>Usage example:
     * {@code throw new RecordNotFoundException("Account", "00000000001");}</p>
     *
     * @param entityName the name of the entity or dataset (e.g., "Account",
     *                   "ACCTDATA")
     * @param key        the key value that was not found
     */
    public RecordNotFoundException(String entityName, String key) {
        this(entityName + " not found with key: " + key);
    }
}

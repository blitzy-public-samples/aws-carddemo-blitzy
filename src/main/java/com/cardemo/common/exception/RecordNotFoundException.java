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
 * Exception indicating that a requested record was not found.
 *
 * <p>Maps directly to VSAM file status code '23' ({@code DFHRESP(NOTFND)}).
 * This is the most commonly handled non-success condition in CardDemo COBOL
 * programs, occurring when:</p>
 * <ul>
 *   <li>A READ operation specifies a key that does not exist in the VSAM KSDS</li>
 *   <li>A DELETE operation targets a non-existent record</li>
 *   <li>A STARTBR operation specifies a key beyond the dataset's key range</li>
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
 * <p>Programs that check for this condition include COACTVWC (account view),
 * COACTUPC (account update), COCRDSLC (card detail), COTRN01C (transaction view),
 * and all user management programs (COUSR00C-03C).</p>
 *
 * @see FileStatusCode#RECORD_NOT_FOUND
 * @see FileStatusException
 */
public class RecordNotFoundException extends FileStatusException {

    private static final long serialVersionUID = 1L;

    /**
     * The key value that was searched for but not found.
     * Corresponds to the COBOL {@code RIDFLD} value in the failed
     * EXEC CICS READ operation.
     */
    private final String keyValue;

    /**
     * Constructs a new {@code RecordNotFoundException} with the dataset name
     * and the key value that was not found.
     *
     * @param datasetName the name of the dataset searched (e.g., "ACCTDATA", "accounts")
     * @param keyValue    the key value that was not found (e.g., "00000000001")
     */
    public RecordNotFoundException(String datasetName, String keyValue) {
        super(FileStatusCode.RECORD_NOT_FOUND, datasetName,
              "Record not found in " + datasetName + " for key: " + keyValue);
        this.keyValue = keyValue;
    }

    /**
     * Constructs a new {@code RecordNotFoundException} with a custom message.
     *
     * @param datasetName the name of the dataset searched
     * @param keyValue    the key value that was not found
     * @param message     a custom detail message
     */
    public RecordNotFoundException(String datasetName, String keyValue, String message) {
        super(FileStatusCode.RECORD_NOT_FOUND, datasetName, message);
        this.keyValue = keyValue;
    }

    /**
     * Constructs a new {@code RecordNotFoundException} wrapping a root cause.
     *
     * @param datasetName the name of the dataset searched
     * @param keyValue    the key value that was not found
     * @param cause       the underlying cause (e.g., JPA NoResultException)
     */
    public RecordNotFoundException(String datasetName, String keyValue, Throwable cause) {
        super(FileStatusCode.RECORD_NOT_FOUND, datasetName,
              "Record not found in " + datasetName + " for key: " + keyValue, cause);
        this.keyValue = keyValue;
    }

    /**
     * Returns the key value that was searched for but not found.
     *
     * @return the key value string
     */
    public String getKeyValue() {
        return keyValue;
    }
}

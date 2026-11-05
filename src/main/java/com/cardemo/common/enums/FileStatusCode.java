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
package com.cardemo.common.enums;

import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.common.exception.RecordNotFoundException;

/**
 * Enumerates VSAM two-byte file status codes used throughout the CardDemo
 * application, mapping each code to its human-readable description and the
 * corresponding Java exception type.
 *
 * <p>In the original COBOL programs, every VSAM file I/O operation sets a
 * two-byte {@code FILE STATUS} field that is inspected after each READ, WRITE,
 * REWRITE, DELETE, STARTBR, READNEXT, and ENDBR operation. The common
 * pattern across all 28 CardDemo COBOL programs is:</p>
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
 *       WHEN DFHRESP(NORMAL)    → STATUS '00' (success)
 *       WHEN DFHRESP(NOTFND)    → STATUS '23' (record not found)
 *       WHEN DFHRESP(DUPREC)    → STATUS '22' (duplicate key)
 *       WHEN DFHRESP(NOTOPEN)   → STATUS '35' (file not available)
 *       WHEN DFHRESP(ENDFILE)   → STATUS '10' (end of file)
 *       WHEN OTHER              → 9999-ABEND-PROGRAM
 *   END-EVALUATE
 * </pre>
 *
 * <h2>Batch Programs</h2>
 * <p>In batch programs (CBTRN02C.cbl, CBSTM03B.CBL), the file status is
 * captured in a two-byte working storage field:</p>
 * <pre>
 *   01  TRANFILE-STATUS.
 *       05  TRANFILE-STAT1      PIC X.
 *       05  TRANFILE-STAT2      PIC X.
 *   01  IO-STATUS.
 *       05  IO-STAT1            PIC X.
 *       05  IO-STAT2            PIC X.
 * </pre>
 * <p>The {@code 9910-DISPLAY-IO-STATUS} paragraph formats and displays the
 * two-byte status before invoking {@code 9999-ABEND-PROGRAM}. In
 * CBSTM03B.CBL, the status is propagated via the linkage area field
 * {@code LK-M03B-RC PIC X(02)} for callers to inspect.</p>
 *
 * <h2>Status Code to Exception Mapping</h2>
 * <table>
 *   <caption>VSAM Status Code to Java Exception Mapping</caption>
 *   <tr><th>Status</th><th>Constant</th><th>Java Exception</th></tr>
 *   <tr><td>00</td><td>{@link #SUCCESS}</td>
 *       <td>{@code null} — no exception</td></tr>
 *   <tr><td>02</td><td>{@link #DUPLICATE_ALTERNATE_KEY}</td>
 *       <td>{@link DuplicateRecordException}</td></tr>
 *   <tr><td>10</td><td>{@link #END_OF_FILE}</td>
 *       <td>{@link RecordNotFoundException}</td></tr>
 *   <tr><td>22</td><td>{@link #DUPLICATE_KEY}</td>
 *       <td>{@link DuplicateRecordException}</td></tr>
 *   <tr><td>23</td><td>{@link #RECORD_NOT_FOUND}</td>
 *       <td>{@link RecordNotFoundException}</td></tr>
 *   <tr><td>35</td><td>{@link #FILE_NOT_AVAILABLE}</td>
 *       <td>{@link FileStatusException}</td></tr>
 *   <tr><td>46</td><td>{@link #SEQUENTIAL_READ_NO_POSITION}</td>
 *       <td>{@link FileStatusException}</td></tr>
 *   <tr><td>47</td><td>{@link #READ_FILE_NOT_OPEN}</td>
 *       <td>{@link FileStatusException}</td></tr>
 * </table>
 *
 * <h2>Exception Hierarchy</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException
 *     └── FileStatusException
 *         ├── RecordNotFoundException   (STATUS '23', '10')
 *         └── DuplicateRecordException  (STATUS '22', '02')
 * </pre>
 *
 * @see FileStatusException
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 */
public enum FileStatusCode {

    /**
     * Successful completion — VSAM status {@code '00'}.
     *
     * <p>The I/O operation completed without error. This is the normal outcome
     * for READ, WRITE, REWRITE, DELETE, STARTBR, READNEXT, and ENDBR
     * operations. In COBOL: {@code DFHRESP(NORMAL)} or
     * {@code FILE STATUS = '00'}.</p>
     *
     * <p>The {@link #toException()} method returns {@code null} for this
     * constant because no exception is warranted for a successful operation.
     * Callers should check for {@code SUCCESS} before invoking
     * {@code toException()}.</p>
     */
    SUCCESS("00", "Operation successful"),

    /**
     * Duplicate alternate key — VSAM status {@code '02'}.
     *
     * <p>A WRITE or REWRITE operation succeeded on the primary key but
     * detected a duplicate on an alternate index (AIX). In COBOL, this
     * occurs when writing to datasets with {@code ALTERNATEINDEX} defined
     * (e.g., CARDDATA AIX on CARD-ACCT-ID, CARDXREF AIX on XREF-ACCT-ID,
     * TRANSACT AIX on TRAN-ORIG-TS).</p>
     *
     * <p>Maps to {@link DuplicateRecordException} via
     * {@link #toException()}.</p>
     */
    DUPLICATE_ALTERNATE_KEY("02", "Duplicate alternate key"),

    /**
     * End of file reached — VSAM status {@code '10'}.
     *
     * <p>A READNEXT operation reached the end of the VSAM dataset during a
     * browse operation. In COBOL: {@code DFHRESP(ENDFILE)} or
     * {@code FILE STATUS = '10'}. This is commonly encountered in:</p>
     * <ul>
     *   <li>COCRDLIC.cbl — card list browse loop</li>
     *   <li>COTRN00C.cbl — transaction list browse loop</li>
     *   <li>COUSR00C.cbl — user list browse loop</li>
     *   <li>CBTRN02C.cbl — batch sequential read of DALYTRAN</li>
     * </ul>
     *
     * <p>Maps to {@link RecordNotFoundException} via
     * {@link #toException()}.</p>
     */
    END_OF_FILE("10", "End of file reached"),

    /**
     * Duplicate primary key — VSAM status {@code '22'}.
     *
     * <p>A WRITE operation attempted to insert a record with a primary key
     * that already exists in the VSAM KSDS dataset. In COBOL:
     * {@code DFHRESP(DUPREC)} or {@code FILE STATUS = '22'}. This
     * condition is handled in:</p>
     * <ul>
     *   <li>COTRN02C.cbl — duplicate TRAN-ID on transaction add</li>
     *   <li>COUSR01C.cbl — duplicate SEC-USR-ID on user add</li>
     *   <li>COBIL00C.cbl — duplicate transaction during bill payment</li>
     * </ul>
     *
     * <p>Maps to {@link DuplicateRecordException} via
     * {@link #toException()}.</p>
     */
    DUPLICATE_KEY("22", "Duplicate primary key"),

    /**
     * Record not found — VSAM status {@code '23'}.
     *
     * <p>A READ or DELETE operation specified a key that does not exist in
     * the VSAM KSDS dataset. In COBOL: {@code DFHRESP(NOTFND)} or
     * {@code FILE STATUS = '23'}. This is the most commonly handled
     * non-success condition in CardDemo:</p>
     * <ul>
     *   <li>COACTVWC.cbl — account view (ACCTDATA)</li>
     *   <li>COACTUPC.cbl — account update (ACCTDATA, CUSTDATA, XREFDATA)</li>
     *   <li>COCRDSLC.cbl — card detail (CARDDATA)</li>
     *   <li>COSGN00C.cbl — sign-on (USRSEC)</li>
     *   <li>COBIL00C.cbl — bill payment (ACCTDATA)</li>
     *   <li>CBTRN02C.cbl — daily posting (reject code 100/101)</li>
     * </ul>
     *
     * <p>Maps to {@link RecordNotFoundException} via
     * {@link #toException()}.</p>
     */
    RECORD_NOT_FOUND("23", "Record not found"),

    /**
     * File not available — VSAM status {@code '35'}.
     *
     * <p>The specified VSAM dataset is not open or not available for
     * processing. In COBOL: {@code DFHRESP(NOTOPEN)} or
     * {@code FILE STATUS = '35'}. This typically occurs during the batch
     * window when the CLOSEFIL JCL job has closed files for batch
     * processing via {@code DFHFC TYPE=CLOSE}.</p>
     *
     * <p>Maps to {@link FileStatusException} via {@link #toException()}.</p>
     */
    FILE_NOT_AVAILABLE("35", "File not available"),

    /**
     * Sequential read without position — VSAM status {@code '46'}.
     *
     * <p>A READNEXT was issued without a prior STARTBR to establish the
     * browse position. In COBOL, this condition occurs if the
     * {@code EXEC CICS STARTBR} was not executed or failed before
     * issuing {@code EXEC CICS READNEXT}.</p>
     *
     * <p>Maps to {@link FileStatusException} via {@link #toException()}.</p>
     */
    SEQUENTIAL_READ_NO_POSITION("46", "Sequential read without position"),

    /**
     * Read on file not opened — VSAM status {@code '47'}.
     *
     * <p>A READ was issued against a file that has not been opened for
     * input or I/O. In COBOL, this occurs when a batch program attempts
     * to READ a file before its corresponding OPEN statement has
     * executed.</p>
     *
     * <p>Maps to {@link FileStatusException} via {@link #toException()}.</p>
     */
    READ_FILE_NOT_OPEN("47", "Read on file not opened");

    /**
     * The two-character VSAM file status code (e.g., {@code "00"},
     * {@code "22"}, {@code "23"}).
     *
     * <p>Preserves the original COBOL {@code PIC X(02)} representation
     * for logging and diagnostic compatibility with the mainframe
     * system.</p>
     */
    private final String code;

    /**
     * Human-readable description of this file status condition.
     */
    private final String description;

    /**
     * Constructs a file status code constant with the given two-character
     * code and human-readable description.
     *
     * @param code        the two-character VSAM file status code
     *                    (e.g., {@code "00"}, {@code "23"})
     * @param description human-readable description of the condition
     */
    FileStatusCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the two-character VSAM file status code.
     *
     * <p>The returned value corresponds to the COBOL {@code PIC X(02)}
     * FILE STATUS field content (e.g., {@code "00"}, {@code "22"},
     * {@code "23"}, {@code "35"}).</p>
     *
     * @return the two-character status code; never {@code null}
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the human-readable description of this status condition.
     *
     * @return the description string; never {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Resolves a {@code FileStatusCode} from the given two-character code
     * string.
     *
     * <p>Iterates through all enum constants and returns the first one
     * whose {@link #getCode()} matches the input. If no match is found,
     * throws {@link IllegalArgumentException}.</p>
     *
     * <p>This method mirrors the COBOL pattern of inspecting the FILE
     * STATUS two-byte field after every I/O operation.</p>
     *
     * @param code the two-character VSAM file status code to look up
     *             (e.g., {@code "00"}, {@code "23"})
     * @return the matching {@code FileStatusCode} constant
     * @throws IllegalArgumentException if no constant matches the given
     *                                  code
     */
    public static FileStatusCode fromCode(String code) {
        for (FileStatusCode status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException(
                "Unknown VSAM file status code: " + code);
    }

    /**
     * Maps this file status code to the corresponding exception in the
     * CardDemo exception hierarchy.
     *
     * <p>This method translates VSAM FILE STATUS semantics into Java
     * exceptions, preserving the original COBOL error-handling behavior:</p>
     * <ul>
     *   <li>{@link #SUCCESS} → {@code null} (no exception for status
     *       {@code '00'})</li>
     *   <li>{@link #DUPLICATE_KEY} → {@link DuplicateRecordException}
     *       (status {@code '22'})</li>
     *   <li>{@link #DUPLICATE_ALTERNATE_KEY} →
     *       {@link DuplicateRecordException} (status {@code '02'})</li>
     *   <li>{@link #RECORD_NOT_FOUND} → {@link RecordNotFoundException}
     *       (status {@code '23'})</li>
     *   <li>{@link #END_OF_FILE} → {@link RecordNotFoundException}
     *       (status {@code '10'})</li>
     *   <li>{@link #FILE_NOT_AVAILABLE}, {@link #SEQUENTIAL_READ_NO_POSITION},
     *       {@link #READ_FILE_NOT_OPEN} → {@link FileStatusException}</li>
     * </ul>
     *
     * <p><strong>Important:</strong> {@link #SUCCESS} returns {@code null}.
     * Callers should check for {@code SUCCESS} before calling this method
     * if a non-null result is expected.</p>
     *
     * @return the corresponding {@link RuntimeException}, or {@code null}
     *         if this status represents a successful operation
     */
    public RuntimeException toException() {
        return switch (this) {
            case SUCCESS -> null;
            case DUPLICATE_KEY ->
                    new DuplicateRecordException("Duplicate primary key");
            case DUPLICATE_ALTERNATE_KEY ->
                    new DuplicateRecordException("Duplicate alternate key");
            case RECORD_NOT_FOUND ->
                    new RecordNotFoundException("Record not found");
            case END_OF_FILE ->
                    new RecordNotFoundException("End of file reached");
            case FILE_NOT_AVAILABLE ->
                    new FileStatusException(this.code, "File not available");
            case SEQUENTIAL_READ_NO_POSITION ->
                    new FileStatusException(this.code,
                            "Sequential read without position");
            case READ_FILE_NOT_OPEN ->
                    new FileStatusException(this.code,
                            "Read on file not opened");
        };
    }

    /**
     * Returns a formatted string combining the status code and its
     * description.
     *
     * <p>Format: {@code "code: description"} (e.g.,
     * {@code "23: Record not found"}).</p>
     *
     * @return the formatted status code string
     */
    @Override
    public String toString() {
        return code + ": " + description;
    }
}

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
 * Exception representing a VSAM file status error condition.
 *
 * <p>This is the parent exception class for all file I/O related errors
 * originating from the COBOL programs' {@code FILE STATUS} handling. It carries
 * the original two-character VSAM status code for diagnostic traceability,
 * preserving the exact value from the COBOL runtime.</p>
 *
 * <h2>COBOL Origin</h2>
 * <p>Every VSAM file operation in the original COBOL CardDemo programs uses a
 * two-byte {@code FILE STATUS} field to communicate the result of the I/O
 * operation. The status fields follow a consistent pattern across all batch and
 * online programs:</p>
 *
 * <h3>Batch Programs (CBTRN02C.cbl, CBSTM03B.CBL)</h3>
 * <pre>
 *   01  TRANFILE-STATUS.
 *       05  TRANFILE-STAT1      PIC X.
 *       05  TRANFILE-STAT2      PIC X.
 *   01  IO-STATUS.
 *       05  IO-STAT1            PIC X.
 *       05  IO-STAT2            PIC X.
 * </pre>
 * <p>The {@code 9910-DISPLAY-IO-STATUS} paragraph formats and displays the
 * two-byte status for operator diagnostics before invoking
 * {@code 9999-ABEND-PROGRAM}. In CBSTM03B.CBL, the status is propagated via
 * the linkage area field {@code LK-M03B-RC PIC X(02)} for callers to inspect.</p>
 *
 * <h3>CICS Programs (COACTUPC.cbl, COACTVWC.cbl, COBIL00C.cbl, COCRDLIC.cbl)</h3>
 * <pre>
 *   EVALUATE WS-RESP-CD
 *       WHEN DFHRESP(NORMAL)   → continue (STATUS '00')
 *       WHEN DFHRESP(NOTFND)   → handle record-not-found (STATUS '23')
 *       WHEN DFHRESP(DUPREC)   → handle duplicate key (STATUS '22')
 *       WHEN DFHRESP(NOTOPEN)  → handle file not available (STATUS '35')
 *       WHEN OTHER             → 9999-ABEND-PROGRAM
 *   END-EVALUATE
 * </pre>
 *
 * <h2>Common VSAM File Status Codes</h2>
 * <table>
 *   <caption>VSAM Status Code to Exception Mapping</caption>
 *   <tr><th>Status</th><th>Meaning</th><th>Java Handling</th></tr>
 *   <tr><td>00</td><td>Successful completion</td><td>No exception thrown</td></tr>
 *   <tr><td>02</td><td>Duplicate alternate key</td>
 *       <td>{@code FileStatusException("02", ...)}</td></tr>
 *   <tr><td>10</td><td>End of file</td>
 *       <td>{@code FileStatusException("10", ...)}</td></tr>
 *   <tr><td>22</td><td>Duplicate primary key</td>
 *       <td>{@link DuplicateRecordException} (subclass)</td></tr>
 *   <tr><td>23</td><td>Record not found</td>
 *       <td>{@link RecordNotFoundException} (subclass)</td></tr>
 *   <tr><td>35</td><td>File not available</td>
 *       <td>{@code FileStatusException("35", ...)}</td></tr>
 *   <tr><td>46</td><td>Sequential read without position</td>
 *       <td>{@code FileStatusException("46", ...)}</td></tr>
 *   <tr><td>47</td><td>Read on file not opened</td>
 *       <td>{@code FileStatusException("47", ...)}</td></tr>
 * </table>
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
 * <h2>Usage Examples</h2>
 * <pre>
 *   // Direct usage for a file-not-available condition
 *   throw new FileStatusException("35", "VSAM file not available: ACCTDATA");
 *
 *   // Wrapping a lower-level cause
 *   throw new FileStatusException("47", "Read on unopened TRANFILE", ioException);
 *
 *   // Catching and inspecting the original status code
 *   try {
 *       accountRepository.findById(acctId);
 *   } catch (FileStatusException e) {
 *       log.error("File status {} on operation", e.getFileStatusCode());
 *   }
 * </pre>
 *
 * @see CardDemoException
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 */
public class FileStatusException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * The original VSAM two-character file status code that caused this
     * exception. This value matches the COBOL {@code PIC X(02)} layout of the
     * {@code FILE STATUS} field (e.g., {@code "23"} for record not found,
     * {@code "22"} for duplicate record, {@code "35"} for file not available).
     *
     * <p>This field is immutable and preserves the exact COBOL FILE STATUS
     * value for diagnostic traceability between the Java runtime and the
     * original COBOL program behavior.</p>
     */
    private final String fileStatusCode;

    /**
     * Constructs a new {@code FileStatusException} with the specified VSAM
     * file status code and detail message.
     *
     * <p>This constructor corresponds to the COBOL pattern where a file status
     * code is captured (e.g., {@code MOVE TRANFILE-STATUS TO IO-STATUS}) and
     * then displayed via {@code 9910-DISPLAY-IO-STATUS} before the program
     * abends.</p>
     *
     * @param fileStatusCode the two-character VSAM file status code (e.g.,
     *                       {@code "23"}, {@code "35"}); must not be
     *                       {@code null}
     * @param message        the detail message describing the error condition;
     *                       may be retrieved later by {@link #getMessage()}
     * @throws NullPointerException if {@code fileStatusCode} is {@code null}
     */
    public FileStatusException(String fileStatusCode, String message) {
        super(message);
        if (fileStatusCode == null) {
            throw new NullPointerException("fileStatusCode must not be null");
        }
        this.fileStatusCode = fileStatusCode;
    }

    /**
     * Constructs a new {@code FileStatusException} with the specified VSAM
     * file status code, detail message, and root cause.
     *
     * <p>Use this constructor when wrapping lower-level exceptions (e.g., JPA
     * persistence exceptions, JDBC I/O errors) with the original COBOL-equivalent
     * file status context. The cause preserves the original error while the
     * status code and message add COBOL-traceable context.</p>
     *
     * @param fileStatusCode the two-character VSAM file status code (e.g.,
     *                       {@code "22"}, {@code "47"}); must not be
     *                       {@code null}
     * @param message        the detail message describing the error condition;
     *                       may be retrieved later by {@link #getMessage()}
     * @param cause          the underlying cause of this exception; may be
     *                       retrieved later by {@link #getCause()}
     * @throws NullPointerException if {@code fileStatusCode} is {@code null}
     */
    public FileStatusException(String fileStatusCode, String message,
                               Throwable cause) {
        super(message, cause);
        if (fileStatusCode == null) {
            throw new NullPointerException("fileStatusCode must not be null");
        }
        this.fileStatusCode = fileStatusCode;
    }

    /**
     * Returns the original VSAM two-character file status code associated with
     * this exception.
     *
     * <p>The returned value corresponds to the COBOL {@code PIC X(02)} FILE
     * STATUS field content at the time of the error. Common values include
     * {@code "00"} (success — never thrown), {@code "22"} (duplicate key),
     * {@code "23"} (record not found), {@code "35"} (file not available),
     * {@code "10"} (end of file), {@code "46"} (sequential read without
     * position), and {@code "47"} (read on file not opened).</p>
     *
     * @return the two-character VSAM file status code; never {@code null}
     */
    public String getFileStatusCode() {
        return fileStatusCode;
    }
}

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
package com.cardemo.batch.writer;

import com.cardemo.common.exception.CardDemoException;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Spring Batch {@link ItemWriter} that writes rejected daily transaction records
 * to the DALYREJS sequential output file.
 *
 * <p>This class faithfully translates the reject-file-writing logic from the COBOL
 * batch program CBTRN02C.cbl. Each rejected daily transaction is written as a
 * fixed-width 430-character record matching the original COBOL FD structure.</p>
 *
 * <h2>COBOL Paragraph Traceability</h2>
 * <table>
 *   <tr><th>COBOL Paragraph</th><th>Java Method</th><th>Description</th></tr>
 *   <tr>
 *     <td>{@code 0300-DALYREJS-OPEN} (line 291)</td>
 *     <td>{@link #open(ExecutionContext)}</td>
 *     <td>OPEN OUTPUT DALYREJS-FILE; check status '00' else error + abend</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 2500-WRITE-REJECT-REC} (lines 446–465)</td>
 *     <td>{@link #write(Chunk)}</td>
 *     <td>WRITE FD-REJS-RECORD FROM REJECT-RECORD; check status '00' else error + abend</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 9300-DALYREJS-CLOSE} (lines 637–653)</td>
 *     <td>{@link #close()}</td>
 *     <td>CLOSE DALYREJS-FILE; check status '00' else error + abend</td>
 *   </tr>
 * </table>
 *
 * <h2>Record Format (430 characters per line)</h2>
 * <pre>
 * Positions   1–350 : REJECT-TRAN-DATA              PIC X(350) — original daily transaction
 * Positions 351–354 : WS-VALIDATION-FAIL-REASON     PIC 9(04)  — zero-padded reject code
 * Positions 355–430 : WS-VALIDATION-FAIL-REASON-DESC PIC X(76) — right-padded description
 * </pre>
 *
 * <h2>Reject Reason Codes (set by TransactionPostingProcessor)</h2>
 * <ul>
 *   <li>{@code 0100} — INVALID CARD NUMBER FOUND (1500-A-LOOKUP-XREF)</li>
 *   <li>{@code 0101} — ACCOUNT RECORD NOT FOUND (1500-B-LOOKUP-ACCT)</li>
 *   <li>{@code 0102} — OVERLIMIT TRANSACTION (1500-B-LOOKUP-ACCT)</li>
 *   <li>{@code 0103} — TRANSACTION RECEIVED AFTER ACCT EXPIRATION (1500-B-LOOKUP-ACCT)</li>
 * </ul>
 *
 * @see RejectRecord
 */
@Component
public class RejectFileWriter implements ItemWriter<RejectFileWriter.RejectRecord>, ItemStream {

    private static final Logger log = LoggerFactory.getLogger(RejectFileWriter.class);

    /**
     * Length of the REJECT-TRAN-DATA field — COBOL {@code PIC X(350)}.
     * Contains the original daily transaction data from DALYTRAN-RECORD.
     */
    private static final int TRANSACTION_DATA_LENGTH = 350;

    /**
     * Length of the WS-VALIDATION-FAIL-REASON field — COBOL {@code PIC 9(04)}.
     * Zero-padded numeric reject reason code.
     */
    private static final int REASON_CODE_LENGTH = 4;

    /**
     * Length of the WS-VALIDATION-FAIL-REASON-DESC field — COBOL {@code PIC X(76)}.
     * Right-padded human-readable reject description.
     */
    private static final int REASON_DESC_LENGTH = 76;

    /**
     * The configured output file path for the DALYREJS reject file.
     * Injected from {@code cardemo.batch.reject-file-path} application property.
     */
    private final String outputFilePath;

    /**
     * Buffered character stream writer for sequential output to the reject file.
     * Opened in {@link #open(ExecutionContext)} and closed in {@link #close()}.
     */
    private BufferedWriter writer;

    /**
     * Constructs a {@code RejectFileWriter} with the DALYREJS output file path
     * injected from application configuration.
     *
     * <p>The output file path is externalized via the
     * {@code cardemo.batch.reject-file-path} property, following the AAP rule
     * of no hardcoded file paths.</p>
     *
     * @param outputFilePath the file system path where rejected records will be written
     */
    public RejectFileWriter(
            @Value("${cardemo.batch.reject-file-path:./output/dalyrejs.txt}") String outputFilePath) {
        this.outputFilePath = outputFilePath;
    }

    /**
     * Opens the DALYREJS reject output file for writing.
     *
     * <p><strong>COBOL Traceability:</strong> {@code 0300-DALYREJS-OPEN}
     * (CBTRN02C.cbl line 291)</p>
     * <pre>
     * OPEN OUTPUT DALYREJS-FILE
     * IF DALYREJS-STATUS = '00'
     *     MOVE 0 TO APPL-RESULT
     * ELSE
     *     DISPLAY 'ERROR OPENING DALY REJECTS FILE'
     *     PERFORM 9999-ABEND-PROGRAM
     * END-IF
     * </pre>
     *
     * @param executionContext the Spring Batch execution context for this step
     * @throws ItemStreamException if the file cannot be opened (maps to COBOL abend)
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            writer = new BufferedWriter(new FileWriter(outputFilePath));
            log.info("DALYREJS reject file opened: {}", outputFilePath);
        } catch (IOException e) {
            log.error("ERROR OPENING DAILY REJECTS FILE: {}", outputFilePath, e);
            throw new ItemStreamException(
                    "Failed to open reject file: " + outputFilePath, e);
        }
    }

    /**
     * Writes a chunk of rejected transaction records to the DALYREJS output file.
     *
     * <p>Each {@link RejectRecord} is formatted as a 430-character fixed-width line
     * matching the COBOL {@code FD-REJS-RECORD} structure, then written followed by
     * a platform line separator. The writer is flushed after each chunk to ensure
     * data integrity.</p>
     *
     * <p><strong>COBOL Traceability:</strong> {@code 2500-WRITE-REJECT-REC}
     * (CBTRN02C.cbl lines 446–465)</p>
     * <pre>
     * MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
     * MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
     * WRITE FD-REJS-RECORD FROM REJECT-RECORD
     * IF DALYREJS-STATUS NOT = '00'
     *     DISPLAY 'ERROR WRITING TO REJECTS FILE'
     *     PERFORM 9999-ABEND-PROGRAM
     * END-IF
     * </pre>
     *
     * @param chunk the chunk of reject records to write
     * @throws CardDemoException if an I/O error occurs during writing
     *         (maps to COBOL {@code PERFORM 9999-ABEND-PROGRAM})
     */
    @Override
    public void write(Chunk<? extends RejectRecord> chunk) throws Exception {
        for (RejectRecord reject : chunk) {
            String rejectLine = formatRejectRecord(reject);
            try {
                writer.write(rejectLine);
                writer.newLine();
            } catch (IOException e) {
                log.error("ERROR WRITING TO REJECTS FILE");
                throw new CardDemoException("ERROR WRITING TO REJECTS FILE", e);
            }
            log.debug("Reject record written: code={}, desc={}",
                    reject.rejectReasonCode(),
                    reject.rejectReasonDescription() != null
                            ? reject.rejectReasonDescription().trim() : "");
        }
        try {
            writer.flush();
        } catch (IOException e) {
            log.error("ERROR WRITING TO REJECTS FILE");
            throw new CardDemoException("ERROR WRITING TO REJECTS FILE", e);
        }
    }

    /**
     * Closes the DALYREJS reject output file, flushing any buffered data first.
     *
     * <p><strong>COBOL Traceability:</strong> {@code 9300-DALYREJS-CLOSE}
     * (CBTRN02C.cbl lines 637–653)</p>
     * <pre>
     * CLOSE DALYREJS-FILE
     * IF DALYREJS-STATUS NOT = '00'
     *     DISPLAY 'ERROR CLOSING DAILY REJECTS FILE'
     *     PERFORM 9999-ABEND-PROGRAM
     * END-IF
     * </pre>
     *
     * @throws ItemStreamException if the file cannot be closed (maps to COBOL abend)
     */
    @Override
    public void close() throws ItemStreamException {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
                log.info("DALYREJS reject file closed: {}", outputFilePath);
            } catch (IOException e) {
                log.error("ERROR CLOSING DAILY REJECTS FILE: {}", outputFilePath, e);
                throw new ItemStreamException(
                        "Failed to close reject file: " + outputFilePath, e);
            } finally {
                writer = null;
            }
        }
    }

    /**
     * Updates the Spring Batch execution context.
     *
     * <p>No persistent state needs to be saved for the reject file writer between
     * chunk commits, so this method is a no-op. The writer position is implicitly
     * managed by the underlying {@link BufferedWriter}.</p>
     *
     * @param executionContext the Spring Batch execution context for this step
     * @throws ItemStreamException never thrown by this implementation
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // No-op: no restartable state to persist for reject file writing.
        // The COBOL original has no equivalent checkpoint concept for the
        // sequential DALYREJS output file.
    }

    /**
     * Formats a single reject record as a fixed-width 430-character string matching
     * the COBOL {@code FD-REJS-RECORD} structure.
     *
     * <p>Layout:</p>
     * <ul>
     *   <li>Positions 1–350: {@code REJECT-TRAN-DATA PIC X(350)} — original transaction
     *       data, right-padded with spaces</li>
     *   <li>Positions 351–354: {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} — zero-padded
     *       reason code</li>
     *   <li>Positions 355–430: {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} —
     *       right-padded description</li>
     * </ul>
     *
     * @param reject the reject record to format
     * @return the formatted 430-character line
     */
    private String formatRejectRecord(RejectRecord reject) {
        // REJECT-TRAN-DATA PIC X(350) — right-pad to 350 chars
        String tranData = padRight(
                reject.originalTransactionData(), TRANSACTION_DATA_LENGTH);
        // WS-VALIDATION-FAIL-REASON PIC 9(04) — zero-pad to 4 digits
        String reasonCode = String.format("%04d", reject.rejectReasonCode());
        // WS-VALIDATION-FAIL-REASON-DESC PIC X(76) — right-pad to 76 chars
        String reasonDesc = padRight(
                reject.rejectReasonDescription(), REASON_DESC_LENGTH);
        return tranData + reasonCode + reasonDesc;
    }

    /**
     * Right-pads a string with spaces to the specified length, matching COBOL
     * {@code PIC X(n)} behavior. If the input exceeds the target length, it is
     * truncated to exactly {@code length} characters.
     *
     * @param value  the input string (may be {@code null}, treated as empty)
     * @param length the target padded length
     * @return the padded or truncated string of exactly {@code length} characters
     */
    private static String padRight(String value, int length) {
        String safeValue = (value != null) ? value : "";
        if (safeValue.length() >= length) {
            return safeValue.substring(0, length);
        }
        return String.format("%-" + length + "s", safeValue);
    }

    /**
     * Data record representing a rejected daily transaction destined for the
     * DALYREJS output file.
     *
     * <p><strong>COBOL Origin:</strong> CBTRN02C.cbl working storage structures:</p>
     * <pre>
     * 01 REJECT-RECORD.
     *    05 REJECT-TRAN-DATA          PIC X(350).
     *    05 VALIDATION-TRAILER        PIC X(80).
     *
     * 01 WS-VALIDATION-TRAILER.
     *    05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
     *    05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
     * </pre>
     *
     * <p><strong>Reject Reason Codes</strong> (set by TransactionPostingProcessor,
     * consumed by this writer):</p>
     * <ul>
     *   <li>{@code 0100} — INVALID CARD NUMBER FOUND
     *       (CBTRN02C paragraph 1500-A-LOOKUP-XREF, line 386)</li>
     *   <li>{@code 0101} — ACCOUNT RECORD NOT FOUND
     *       (CBTRN02C paragraph 1500-B-LOOKUP-ACCT, line 398)</li>
     *   <li>{@code 0102} — OVERLIMIT TRANSACTION
     *       (CBTRN02C paragraph 1500-B-LOOKUP-ACCT, line 411)</li>
     *   <li>{@code 0103} — TRANSACTION RECEIVED AFTER ACCT EXPIRATION
     *       (CBTRN02C paragraph 1500-B-LOOKUP-ACCT, line 418)</li>
     * </ul>
     *
     * @param originalTransactionData   the complete 350-character daily transaction
     *                                  record as read from the DALYTRAN input file
     *                                  (maps to {@code REJECT-TRAN-DATA PIC X(350)})
     * @param rejectReasonCode          the 4-digit numeric reject reason code
     *                                  (maps to {@code WS-VALIDATION-FAIL-REASON PIC 9(04)})
     * @param rejectReasonDescription   the human-readable reject description, up to
     *                                  76 characters
     *                                  (maps to {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)})
     */
    public record RejectRecord(
            String originalTransactionData,
            int rejectReasonCode,
            String rejectReasonDescription
    ) {
    }
}

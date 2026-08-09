/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.transaction.batch;

import com.carddemo.common.batch.BatchOutputPathResolver;
import com.carddemo.common.batch.FixedWidthText;
import com.carddemo.common.domain.DailyTransaction;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/**
 * :purpose: Write rejected daily-transaction records to the ``DALYREJS`` sink as
 *     fixed-width 430-byte records, mirroring the ``CBTRN02C``
 *     ``2500-WRITE-REJECT-REC`` paragraph. Each record is a 350-byte reconstructed
 *     ``DALYTRAN-RECORD`` image (``CVTRA06Y`` DISPLAY serialization) followed by an
 *     80-byte validation trailer holding a 4-digit reason code and a 76-character
 *     reason description, exactly reproducing the legacy ``REJECT-RECORD`` layout.
 * :output: A Spring Batch :class:`ItemStreamWriter` that appends one 430-character
 *     line per rejected item to a PER-RUN generation of the configured file resource. The
 *     430-character payload is byte-exact with the mainframe reject record; the modern sink
 *     is newline-delimited between records.
 * :note: ``@StepScope`` because the destination is derived from the job execution. The
 *     legacy job stream allocated a NEW generation for every run
 *     (``DSN=AWS.M2.CARDDEMO.DALYREJS(+1)``, ``DISP=(NEW,CATLG,DELETE)``), so a run could
 *     only ever create or discard ITS OWN generation
 *     [app/jcl/POSTTRAN.jcl]. Writing one fixed name instead meant a later run destroyed an
 *     earlier run's deliverable: a failing run's cleanup deleted the previous run's reject
 *     records outright, and a successful run truncated them - a zero-data-loss violation
 *     against a downstream file interface, with ``BATCH_JOB_EXECUTION`` still reporting
 *     COMPLETED for a file that no longer existed.
 * :note: The delegate saves NO stream state and deletes an existing file of its own
 *     generation name. Restart therefore works: a restarted execution is a new execution, so
 *     it opens its own generation and writes the rejects of the records the restart still has
 *     to process, while the failed execution's generation stays exactly as the legacy abend
 *     would have left it. With saved state and a shared name, a restart demanded the byte
 *     offset of a file the cleanup listener had already removed and failed instantly with
 *     ``File is not writable``, which made restart-after-failure permanently impossible.
 */
@Component
@StepScope
public class RejectFileItemWriter implements ItemStreamWriter<PostingItem> {

    /**
     * Total length of a reject record: 350-byte DALYTRAN image + 80-byte trailer.
     */
    private static final int REJECT_RECORD_LENGTH = 430;

    /**
     * Length of the reconstructed ``DALYTRAN-RECORD`` image (``CVTRA06Y``, RECLN 350).
     */
    private static final int DALYTRAN_IMAGE_LENGTH = 350;

    /**
     * Length of the ``VALIDATION-TRAILER`` (``WS-VALIDATION-TRAILER``): 4 + 76.
     */
    private static final int VALIDATION_TRAILER_LENGTH = 80;

    /**
     * Record delimiter for the reject sink, pinned to a single ``LF`` so the record
     * length stays 430 payload bytes plus exactly one delimiter byte on every host,
     * independent of ``System.lineSeparator()``.
     */
    private static final String RECORD_SEPARATOR = "\n";

    /**
     * ASCII overpunch characters for a non-negative zoned-decimal last digit,
     * indexed by that digit (``0`` -> ``{`` .. ``9`` -> ``I``).
     */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /**
     * ASCII overpunch characters for a negative zoned-decimal last digit, indexed
     * by that digit (``0`` -> ``}`` .. ``9`` -> ``R``).
     */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    /**
     * Delegate that owns the file lifecycle so writes participate in the chunk
     * transaction and the Spring Batch stream lifecycle.
     */
    private final FlatFileItemWriter<PostingItem> delegate;

    /** This run's generation of the reject file, resolved once at construction. */
    private final java.nio.file.Path rejectFile;

    /**
     * :purpose: Construct the writer and its :class:`FlatFileItemWriter` delegate
     *     bound to the configured reject-file resource and the fixed-width line
     *     aggregator.
     * :param rejectFileName: BASE file name of the reject sink, read from the
     *     ``carddemo.batch.reject-file`` property and defaulting to the legacy
     *     ``DALYREJS`` data-set name ``dalyrejs.txt``; never an absolute path baked
     *     into the code.
     * :param jobExecutionId: id of the job execution this step belongs to, used as the
     *     generation number of the file this run writes.
     * :param pathResolver: shared resolver that confines the reject file to the
     *     configured ``carddemo.batch.output-dir`` root, creating the directory so
     *     the delegate can open the file, and rejecting absolute paths, ``..``
     *     traversal and symlink escapes (CWE-22).
     * :note: The name is resolved through the shared batch root rather than opened
     *     directly, and it is generation-qualified per run
     *     (``dalyrejs.txt`` -> ``dalyrejs.G0033V00.txt``); the delegate encodes in
     *     ``ISO-8859-1`` and pins the record delimiter to a single ``LF``. ``DALYREJS`` is a
     *     ``RECFM=F LRECL=430`` data set [app/jcl/POSTTRAN.jcl], so its length is a BYTE
     *     contract. Rationale for all of these is in docs/decision-log.md, sections 9.5
     *     (shared batch output root) and 10.1 (encoding and record delimiter).
     */
    public RejectFileItemWriter(
            @Value("${carddemo.batch.reject-file:dalyrejs.txt}") String rejectFileName,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId,
            BatchOutputPathResolver pathResolver) {
        this.rejectFile = pathResolver.resolveOutputGeneration(rejectFileName, jobExecutionId);
        this.delegate = new FlatFileItemWriterBuilder<PostingItem>()
                .name("rejectFileItemWriter")
                .resource(new FileSystemResource(this.rejectFile))
                // This generation belongs to THIS execution: it is created fresh, and no
                // byte offset from a previous execution is resumed. Both are what make a
                // restart of a failed instance succeed instead of demanding a deleted file.
                .shouldDeleteIfExists(true)
                .saveState(false)
                .encoding(StandardCharsets.ISO_8859_1.name())
                .lineSeparator(RECORD_SEPARATOR)
                .lineAggregator(this::toFixedWidthLine)
                // Restart tolerance. A FlatFileItemWriter that saves its state resumes at the
                // byte offset the previous attempt reached, which requires that file to still
                // exist at that length -- and the failed attempt's reject generation is
                // deliberately DELETED by rejectFileCleanupListener, so the restart opened
                // against a file that was gone and died in open() before reading a record.
                // The legacy job stream allocated a NEW DALYREJS generation per run rather
                // than appending to the previous one, so state-free is also the faithful
                // behaviour: each attempt writes its own complete reject generation from the
                // beginning, and shouldDeleteIfExists truncates any file left behind.
                .saveState(false)
                .shouldDeleteIfExists(true)
                .build();
    }

    /**
     * :purpose: Expose the generation this run writes, so a caller (and the step's cleanup
     *     listener) can address exactly the file this execution opened.
     * :returns: the resolved path of this run's reject generation.
     */
    public java.nio.file.Path getRejectFile() {
        return rejectFile;
    }

    /**
     * :purpose: Serialize the rejected items of a chunk to the reject sink,
     *     defensively passing through only items flagged as rejected.
     * :param chunk: the chunk of posting items routed to the reject writer.
     * :throws Exception: if the underlying file write fails.
     */
    @Override
    public void write(Chunk<? extends PostingItem> chunk) throws Exception {
        Chunk<PostingItem> rejects = new Chunk<>();
        for (PostingItem item : chunk) {
            if (item != null && item.isRejected()) {
                rejects.add(item);
            }
        }
        if (!rejects.isEmpty()) {
            delegate.write(rejects);
        }
    }

    /**
     * :purpose: Open the delegate stream, creating the reject file for this run.
     * :param executionContext: the step execution context.
     * :throws ItemStreamException: if the resource cannot be opened.
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        delegate.open(executionContext);
    }

    /**
     * :purpose: Persist the delegate stream state into the execution context.
     * :param executionContext: the step execution context.
     * :throws ItemStreamException: if the state cannot be updated.
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        delegate.update(executionContext);
    }

    /**
     * :purpose: Flush and close the delegate stream, releasing the reject file.
     * :throws ItemStreamException: if the resource cannot be closed.
     */
    @Override
    public void close() throws ItemStreamException {
        delegate.close();
    }

    /**
     * :purpose: Build one 430-character reject record for a posting item: the
     *     350-byte reconstructed ``DALYTRAN-RECORD`` image concatenated with the
     *     80-byte validation trailer (4-digit reason code + 76-char description).
     * :param item: the rejected posting item carrying the daily-transaction record,
     *     reason code and reason description.
     * :returns: the 430-character fixed-width reject record, without a line separator.
     */
    private String toFixedWidthLine(PostingItem item) {
        DailyTransaction tran = item.getDailyTransaction();

        StringBuilder image = new StringBuilder(DALYTRAN_IMAGE_LENGTH);
        image.append(padRight(tran.getDalytranId(), 16));            // 1-16   DALYTRAN-ID       X(16)
        image.append(padRight(tran.getDalytranTypeCd(), 2));         // 17-18  DALYTRAN-TYPE-CD  X(02)
        image.append(padLeftZeros(tran.getDalytranCatCd(), 4));      // 19-22  DALYTRAN-CAT-CD   9(04)
        image.append(padRight(tran.getDalytranSource(), 10));        // 23-32  DALYTRAN-SOURCE   X(10)
        image.append(padRight(tran.getDalytranDesc(), 100));         // 33-132 DALYTRAN-DESC     X(100)
        image.append(encodeAmountZoned(tran.getDalytranAmt()));      // 133-143 DALYTRAN-AMT     S9(09)V99
        image.append(padLeftZeros(tran.getDalytranMerchantId(), 9)); // 144-152 DALYTRAN-MERCHANT-ID 9(09)
        image.append(padRight(tran.getDalytranMerchantName(), 50));  // 153-202 DALYTRAN-MERCHANT-NAME X(50)
        image.append(padRight(tran.getDalytranMerchantCity(), 50));  // 203-252 DALYTRAN-MERCHANT-CITY X(50)
        image.append(padRight(tran.getDalytranMerchantZip(), 10));   // 253-262 DALYTRAN-MERCHANT-ZIP  X(10)
        image.append(padRight(tran.getDalytranCardNum(), 16));       // 263-278 DALYTRAN-CARD-NUM      X(16)
        image.append(padRight(tran.getDalytranOrigTs(), 26));        // 279-304 DALYTRAN-ORIG-TS       X(26)
        image.append(padRight(tran.getDalytranProcTs(), 26));        // 305-330 DALYTRAN-PROC-TS       X(26)
        image.append(padRight("", 20));                              // 331-350 FILLER                X(20)

        String imageStr = image.toString();
        if (imageStr.length() != DALYTRAN_IMAGE_LENGTH) {
            throw new IllegalStateException(
                    "Reconstructed DALYTRAN image must be " + DALYTRAN_IMAGE_LENGTH
                            + " characters but was " + imageStr.length());
        }

        String trailer = padLeftZeros(item.getRejectCode(), 4)       // 351-354 WS-VALIDATION-FAIL-REASON      9(04)
                + padRight(item.getRejectDescription(), 76);         // 355-430 WS-VALIDATION-FAIL-REASON-DESC X(76)
        if (trailer.length() != VALIDATION_TRAILER_LENGTH) {
            throw new IllegalStateException(
                    "Validation trailer must be " + VALIDATION_TRAILER_LENGTH
                            + " characters but was " + trailer.length());
        }

        String record = imageStr + trailer;
        if (record.length() != REJECT_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "Reject record must be " + REJECT_RECORD_LENGTH
                            + " characters but was " + record.length());
        }
        return record;
    }

    /**
     * :purpose: Left-justify and space-pad an alphanumeric value to a fixed width,
     *     truncating on the right when longer, reproducing a COBOL ``X(n)`` MOVE.
     * :param value: the source value; a ``null`` is treated as an empty string.
     * :param width: the target field width.
     * :returns: a string of exactly ``width`` characters.
     */
    private static String padRight(String value, int width) {
        // Reduced to single-byte text FIRST: the field width is a BYTE width, so a code
        // point that ISO-8859-1 cannot represent must occupy exactly one character here
        // or the record would come out shorter than its declared 430 bytes (a
        // supplementary code point is two Java characters but encodes to one byte).
        String safe = (value == null) ? "" : FixedWidthText.toSingleByteText(value);
        if (safe.length() >= width) {
            return safe.substring(0, width);
        }
        return safe + " ".repeat(width - safe.length());
    }

    /**
     * :purpose: Right-justify and zero-pad an integer value to a fixed width,
     *     reproducing a COBOL unsigned ``9(n)`` DISPLAY field.
     * :param value: the source value; a ``null`` is treated as zero.
     * :param width: the target field width.
     * :returns: a string of exactly ``width`` digits.
     */
    private static String padLeftZeros(Integer value, int width) {
        return zeroPad(value == null ? 0L : value.longValue(), width);
    }

    /**
     * :purpose: Right-justify and zero-pad a long value to a fixed width,
     *     reproducing a COBOL unsigned ``9(n)`` DISPLAY field.
     * :param value: the source value; a ``null`` is treated as zero.
     * :param width: the target field width.
     * :returns: a string of exactly ``width`` digits.
     */
    private static String padLeftZeros(Long value, int width) {
        return zeroPad(value == null ? 0L : value.longValue(), width);
    }

    /**
     * :purpose: Format the absolute magnitude of a value as a fixed-width,
     *     zero-filled digit string, keeping the low-order digits on overflow as a
     *     COBOL numeric MOVE would.
     * :param value: the value to format.
     * :param width: the target field width.
     * :returns: a string of exactly ``width`` digits.
     */
    private static String zeroPad(long value, int width) {
        String digits = Long.toString(Math.abs(value));
        if (digits.length() > width) {
            digits = digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * :purpose: Encode a monetary amount as an 11-character ``S9(09)V99`` zoned
     *     decimal with a trailing overpunch sign, where the eleventh character
     *     encodes both the final digit and the sign.
     * :param amt: the amount to encode; a ``null`` is treated as zero.
     * :returns: the 11-character zoned-decimal representation.
     * :note: Excess fraction digits are truncated toward zero, not rounded, because
     *     a COBOL ``MOVE`` into the ``V99`` receiver carries no ``ROUNDED`` phrase.
     */
    private static String encodeAmountZoned(BigDecimal amt) {
        BigDecimal scaled = (amt == null ? BigDecimal.ZERO : amt).setScale(2, RoundingMode.DOWN);
        boolean negative = scaled.signum() < 0;
        BigInteger unsigned = scaled.abs().movePointRight(2).toBigInteger();
        String digits = String.format("%011d", unsigned);
        if (digits.length() != 11) {
            throw new IllegalStateException(
                    "DALYTRAN-AMT exceeds S9(09)V99 capacity: " + scaled.toPlainString());
        }
        String firstTen = digits.substring(0, 10);
        int lastDigit = digits.charAt(10) - '0';
        char overpunch = negative ? NEGATIVE_OVERPUNCH[lastDigit] : POSITIVE_OVERPUNCH[lastDigit];
        return firstTen + overpunch;
    }
}

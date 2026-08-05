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
 *     line per rejected item to a configurable file resource. The 430-character
 *     payload is byte-exact with the mainframe reject record; the modern sink is
 *     newline-delimited between records.
 */
@Component
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

    /**
     * :purpose: Construct the writer and its :class:`FlatFileItemWriter` delegate
     *     bound to the configured reject-file resource and the fixed-width line
     *     aggregator.
     * :param rejectFileName: file name of the reject sink, read from the
     *     ``carddemo.batch.reject-file`` property and defaulting to the legacy
     *     ``DALYREJS`` data-set name ``dalyrejs.txt``; never an absolute path baked
     *     into the code.
     * :param pathResolver: shared resolver that confines the reject file to the
     *     configured ``carddemo.batch.output-dir`` root, creating the directory so
     *     the delegate can open the file, and rejecting absolute paths, ``..``
     *     traversal and symlink escapes (CWE-22).
     * :note: The name is resolved through the shared batch root rather than opened
     *     directly. ``FileSystemResource`` used to resolve the bare
     *     default against the PROCESS WORKING DIRECTORY - ``/app`` inside the
     *     container, on the read-only root filesystem - so the very first rejected
     *     transaction would have failed the posting step with
     *     ``java.io.IOException: No such file or directory``, losing the DALYREJS
     *     reject records that AAP section 0.6.4 makes a frozen 430-byte contract.
     * :note: The delegate encodes in ``ISO-8859-1``, exactly as the other
     *     fixed-width batch writers do (``RecordDumpItemWriter``,
     *     ``CombineTransactionsTasklet``). ``DALYREJS`` is a ``RECFM=F LRECL=430``
     *     data set [app/jcl/POSTTRAN.jcl], so 430 is a BYTE count, not a character
     *     count: under the platform default UTF-8 a single accented character in
     *     ``DALYTRAN-DESC`` or ``DALYTRAN-MERCHANT-NAME`` made the record 431+ bytes
     *     and byte-shifted every field after it, so a byte-offset downstream reader
     *     lost the 4-digit reject reason code entirely. One character maps to exactly
     *     one byte in ISO-8859-1, which keeps the frozen record length (AAP 0.7.6).
     */
    public RejectFileItemWriter(
            @Value("${carddemo.batch.reject-file:dalyrejs.txt}") String rejectFileName,
            BatchOutputPathResolver pathResolver) {
        this.delegate = new FlatFileItemWriterBuilder<PostingItem>()
                .name("rejectFileItemWriter")
                .resource(new FileSystemResource(pathResolver.resolveOutput(rejectFileName)))
                .encoding(StandardCharsets.ISO_8859_1.name())
                .lineAggregator(this::toFixedWidthLine)
                .build();
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
     */
    private static String encodeAmountZoned(BigDecimal amt) {
        BigDecimal scaled = (amt == null ? BigDecimal.ZERO : amt).setScale(2, RoundingMode.HALF_UP);
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

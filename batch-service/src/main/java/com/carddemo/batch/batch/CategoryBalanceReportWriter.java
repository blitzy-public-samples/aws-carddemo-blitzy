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

package com.carddemo.batch.batch;

import com.carddemo.common.batch.BatchOutputPathResolver;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import com.carddemo.common.batch.BatchOutputPathResolver;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.domain.TranCatBal;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * :purpose: {@code @StepScope} {@link ItemStreamWriter} that produces the
 *  CardDemo Transaction Category Balance report, one fixed-format line per
 *  {@link TranCatBal} record. It is the Java analogue of the legacy
 *  ``PRTCATBL`` job's ``STEP10R`` DFSORT step, whose
 *  ``OUTREC FIELDS=(TRANCAT-ACCT-ID, X, TRANCAT-TYPE-CD, X, TRANCAT-CD, X,
 *  TRAN-CAT-BAL, EDIT=(TTTTTTTTT.TT), 9X)`` renders the unloaded ``TCATBALF``
 *  file (record layout ``CVTRA01Y``). It is a report-only writer: it never
 *  mutates a {@link TranCatBal}, never calls a repository, and produces no
 *  control breaks or totals.
 * :output: A newly created flat file at the ``outputFile`` job-parameter path
 *  containing one line per input record. Each line is the account id
 *  (11 digits), a single space, the transaction type code (2 characters), a
 *  single space, the transaction category code (4 digits), a single space, the
 *  edited category balance (12 characters), and 9 trailing spaces, matching the
 *  DFSORT ``OUTREC`` field order and spacing.
 * :note: The reader
 *  (``TranCatBalRepository.findAllByOrderByTrancatAcctIdAscTrancatTypeCdAscTrancatCdAsc(pageable)``,
 *  the full composite-key ascending order equivalent of DFSORT
 *  ``SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)``), the
 *  ``outputFile`` job parameter, and the wiring of this writer as the step's
 *  {@link ItemStreamWriter} are supplied by the batch ``config/`` package;
 *  because this is the step's main writer, Spring Batch automatically manages
 *  {@link #open}, {@link #update}, and {@link #close} without an explicit
 *  ``.stream(...)`` registration.
 * :note: {@code @StepScope} is required so that the ``#{jobParameters[...]}``
 *  ``outputFile`` value is bound lazily per step execution.
 */
@Component
@StepScope
public class CategoryBalanceReportWriter implements ItemStreamWriter<TranCatBal> {

    /**
     * Delegate that owns file opening, line writing, and closing; this class
     * only supplies the per-record line format through {@link #toLine}.
     */
    private final FlatFileItemWriter<TranCatBal> delegate;

    /**
     * :purpose: Construct the writer and build the delegating
     *  {@link FlatFileItemWriter} bound to the report output path.
     * :param outputFile: report file name supplied by the ``outputFile`` job
     *  parameter (the ``PRTCATBL`` ``SORTOUT`` /
     *  ``AWS.M2.CARDDEMO.TCATBALF.REPT`` sink equivalent); resolved and confined
     *  to the configured output root by {@code pathResolver}.
     * :param pathResolver: resolver that confines the report file to the
     *  allowlisted batch output root, rejecting absolute, ``..`` traversal, and
     *  symlink-escape paths (CWE-22).
     */
    public CategoryBalanceReportWriter(
            @Value("#{jobParameters['outputFile']}") String outputFile,
            BatchOutputPathResolver pathResolver) {
        Path resolved = pathResolver.resolveOutput(outputFile);
        this.delegate = new FlatFileItemWriterBuilder<TranCatBal>()
                .name("categoryBalanceReportWriter")
                .resource(new FileSystemResource(resolved))
                .lineAggregator(this::toLine)
                .build();
    }

    /**
     * :purpose: Format one transaction-category-balance record into its report
     *  line, reproducing the ``PRTCATBL`` ``STEP10R`` DFSORT ``OUTREC FIELDS``
     *  order and spacing. The record separator is appended by the delegating
     *  {@link FlatFileItemWriter}, so it is not included here.
     * :param balance: the record to format; its composite-key components and
     *  category balance are read but never modified.
     * :returns: the formatted report line: account id (11 digits), a single
     *  space, transaction type code (2 characters), a single space, transaction
     *  category code (4 digits), a single space, the edited balance
     *  (12 characters), and 9 trailing spaces.
     */
    private String toLine(TranCatBal balance) {
        String acctId = String.format("%011d", balance.getTrancatAcctId());
        String typeCd = fixed(balance.getTrancatTypeCd(), 2);
        String catCd = String.format("%04d", balance.getTrancatCd());
        String editedBalance = editBalance(balance.getTranCatBal());
        return acctId + " " + typeCd + " " + catCd + " " + editedBalance + "         ";
    }

    /**
     * :purpose: Render a text value into a fixed-width field, left-justified and
     *  space-padded on the right (or truncated), reproducing the fixed-width
     *  placement of a COBOL ``PIC X(n)`` field such as ``TRANCAT-TYPE-CD``.
     * :param value: the source value; a ``null`` value is treated as empty.
     * :param width: the exact output width in characters.
     * :returns: a string of exactly ``width`` characters.
     */
    private static String fixed(String value, int width) {
        String safe = (value == null) ? "" : value;
        if (safe.length() > width) {
            return safe.substring(0, width);
        }
        StringBuilder builder = new StringBuilder(width);
        builder.append(safe);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /**
     * :purpose: Reproduce the DFSORT ``EDIT=(TTTTTTTTT.TT)`` mask applied to
     *  ``TRAN-CAT-BAL`` (``PIC S9(09)V99``). Each ``T`` digit indicator always
     *  prints its digit, so the integer part is zero-padded to 9 digits and two
     *  decimal digits always follow the literal ``.``. The mask carries no ``S``
     *  sign indicator, so the magnitude (absolute value) is emitted.
     * :param value: the category balance to edit; a ``null`` value is treated as
     *  zero. Only the display form is produced; the stored precision and scale
     *  are not altered.
     * :returns: a 12-character string of the form ``000000000.00`` (9 integer
     *  digits, a literal ``.``, and 2 decimal digits); for example ``123.45``
     *  becomes ``000000123.45`` and ``-5.00`` becomes ``000000005.00``.
     */
    private static String editBalance(BigDecimal value) {
        BigDecimal magnitude = (value == null ? BigDecimal.ZERO : value).abs();
        DecimalFormat format =
                new DecimalFormat("000000000.00", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(magnitude);
    }

    /**
     * :purpose: Open the delegating writer and its output resource at the start
     *  of the step, ensuring a correlation id is present in the logging context
     *  for the run.
     * :param executionContext: the step execution context passed to the
     *  delegate.
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        CorrelationIdContext.getOrCreateCorrelationId();
        delegate.open(executionContext);
    }

    /**
     * :purpose: Persist the delegate's stream state to the step execution
     *  context between chunks.
     * :param executionContext: the step execution context passed to the
     *  delegate.
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        delegate.update(executionContext);
    }

    /**
     * :purpose: Flush and release the delegating writer and its output resource
     *  at the end of the step.
     */
    @Override
    public void close() throws ItemStreamException {
        delegate.close();
    }

    /**
     * :purpose: Write one chunk of already-ordered records to the report by
     *  delegating to the {@link FlatFileItemWriter}, which formats each record
     *  through {@link #toLine}.
     * :param chunk: the ordered batch of records to render; the records are read
     *  but never modified.
     */
    @Override
    public void write(Chunk<? extends TranCatBal> chunk) throws Exception {
        CorrelationIdContext.getOrCreateCorrelationId();
        delegate.write(chunk);
    }
}

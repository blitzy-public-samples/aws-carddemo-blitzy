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

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.carddemo.common.batch.BatchOutputPathResolver;
import com.carddemo.common.batch.FixedWidthText;
import com.carddemo.common.config.CorrelationIdContext;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * :purpose: Stateful {@link ItemStreamWriter} that produces the CardDemo Daily
 *  Transaction Report as a fixed-width, paginated text file. It is the Java
 *  analogue of the report-writing paragraphs of the legacy batch program
 *  ``CBTRN03C`` (``1100-WRITE-TRANSACTION-REPORT``, ``1120-WRITE-HEADERS``,
 *  ``1120-WRITE-DETAIL``, ``1110-WRITE-PAGE-TOTALS``,
 *  ``1120-WRITE-ACCOUNT-TOTALS``, ``1110-WRITE-GRAND-TOTALS``,
 *  ``1111-WRITE-REPORT-REC``); every emitted record is a 133-character line
 *  whose field layout is defined by the ``CVTRA07Y`` copybook. The writer
 *  consumes an already-ordered stream of {@link TransactionReportItem} (sorted
 *  by card number and filtered to the report date range by the reader) and
 *  drives a per-card control break, 20-line pagination, and running page,
 *  account, and grand totals.
 * :output: A newly created report file at the ``reportFile`` job-parameter path
 *  containing the report-name and column headers, one detail line per input
 *  item, per-page and per-account subtotal lines, and the closing page-total and
 *  grand-total block; each amount is edited with the ``CVTRA07Y``
 *  ``-ZZZ,ZZZ,ZZZ.ZZ`` (detail) or ``+ZZZ,ZZZ,ZZZ.ZZ`` (totals) mask. An empty
 *  selection window still produces the closing block, exactly as the source
 *  program's end-of-file path does. The control-break card number is never
 *  written to the file or to any log line.
 * :note: The end-of-report block reproduces the ``CBTRN03C`` end-of-file branch
 *  literally: ``ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL`` runs against
 *  the record area, which at end of file still holds the LAST record read — so
 *  the final record contributes to the closing page and grand totals a second
 *  time — followed by ``1110-WRITE-PAGE-TOTALS`` and
 *  ``1110-WRITE-GRAND-TOTALS`` and by no closing account-total line. The
 *  reproduction is deliberate: the report is a frozen downstream layout, so the
 *  arithmetic and the line set are preserved rather than corrected.
 * :note: The file is written in ISO-8859-1 and every field value is reduced to
 *  single-byte text before it is padded, so ``FD-REPTFILE-REC PIC X(133)`` is a
 *  BYTE contract for any text the relational store can hold, not merely a
 *  character count (see {@link FixedWidthText}).
 * :note: The reader
 *  (``TransactionRepository.findByProcTsDateRangeOrderByCardNum(startDate,
 *  endDate, pageable)``), the ``startDate``/``endDate``/``reportFile`` job
 *  parameters, and the wiring of this writer as the step's
 *  {@link ItemStreamWriter} are supplied by the batch ``config/`` package;
 *  Spring Batch then invokes {@link #open}, {@link #update}, and {@link #close}
 *  around the chunk loop. The owning step must run single-threaded so the
 *  control-break and pagination state remain coherent.
 * :note: {@code @StepScope} is required both for late binding of the
 *  ``#{jobParameters[...]}`` values and so the mutable report state is fresh
 *  for each step execution.
 */
@Component
@StepScope
public class TransactionDetailReportWriter implements ItemStreamWriter<TransactionReportItem> {

    /** Width, in characters, of every physical report record (``FD-REPTFILE-REC`` PIC X(133)). */
    private static final int LINE_WIDTH = 133;

    /** Lines per page before a page break is forced (``WS-PAGE-SIZE`` VALUE 20). */
    private static final int PAGE_SIZE = 20;

    /** Total width of an edited amount field: one sign column plus the 14-character magnitude ``ZZZ,ZZZ,ZZZ.ZZ``. */
    private static final int AMOUNT_FIELD_WIDTH = 15;

    /** Width of the amount magnitude ``ZZZ,ZZZ,ZZZ.ZZ`` (excludes the leading sign column). */
    private static final int AMOUNT_MAGNITUDE_WIDTH = 14;

    /** Dot-filler width of the page-total line (``REPORT-PAGE-TOTALS`` FILLER PIC X(86)). */
    private static final int PAGE_TOTAL_DOT_COUNT = 86;

    /** Dot-filler width of the account-total line (``REPORT-ACCOUNT-TOTALS`` FILLER PIC X(84)). */
    private static final int ACCOUNT_TOTAL_DOT_COUNT = 84;

    /** Dot-filler width of the grand-total line (``REPORT-GRAND-TOTALS`` FILLER PIC X(86)). */
    private static final int GRAND_TOTAL_DOT_COUNT = 86;

    /** Full-width separator rule (``TRANSACTION-HEADER-2`` PIC X(133) VALUE ALL '-'). */
    private static final String SEPARATOR_LINE = "-".repeat(LINE_WIDTH);

    /**
     * Amount magnitude formatter reproducing the ``ZZZ,ZZZ,ZZZ.ZZ`` edit mask
     * (comma grouping, two decimals). Constructed with {@link Locale#US} symbols
     * so the grouping separator is always ',' and the decimal separator always
     * '.', independent of the host locale.
     */
    private final DecimalFormat amountFormat =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    /** Report start date, ``YYYY-MM-DD`` (``REPT-START-DATE`` X(10)); bound from the ``startDate`` job parameter. */
    private final String startDate;

    /** Report end date, ``YYYY-MM-DD`` (``REPT-END-DATE`` X(10)); bound from the ``endDate`` job parameter. */
    private final String endDate;

    /** Requested report file name (``reportFile`` job parameter); retained for diagnostics. */
    private final String reportFile;

    /** Canonical report path confined to the allowlisted output root; opened in {@link #open}. */
    private final Path resolvedReportFile;

    /** Open handle to the report file; created in {@link #open} and released in {@link #close}. */
    private BufferedWriter out;

    /** Physical line counter driving the page-break test (``WS-LINE-COUNTER``). */
    private int lineCounter;

    /** True until the first detail row has triggered the header block (``WS-FIRST-TIME``). */
    private boolean firstTime;

    /** Card number of the current control-break group (``WS-CURR-CARD-NUM``); never rendered or logged. */
    private String currentCardNum;

    /** Running total for the current page (``WS-PAGE-TOTAL``). */
    private BigDecimal pageTotal;

    /** Running total for the current account/card group (``WS-ACCOUNT-TOTAL``). */
    private BigDecimal accountTotal;

    /** Running total across the whole report (``WS-GRAND-TOTAL``). */
    private BigDecimal grandTotal;

    /**
     * Amount of the most recently rendered detail row — the Java stand-in for the
     * ``TRAN-AMT`` still sitting in the ``TRAN-RECORD`` area when ``CBTRN03C``
     * reaches end of file, which its end-of-file branch adds to the page and
     * account totals once more. Zero until the first row is rendered, matching an
     * empty selection window.
     */
    private BigDecimal lastAmount;

    /**
     * :purpose: Construct the writer, binding the report date range and output
     *  path from the enclosing step's job parameters.
     * :param startDate: report range start date in ``YYYY-MM-DD`` form
     *  (``startDate`` job parameter, rendered as ``REPT-START-DATE``).
     * :param endDate: report range end date in ``YYYY-MM-DD`` form
     *  (``endDate`` job parameter, rendered as ``REPT-END-DATE``).
     * :param reportFile: report file name to create (``reportFile`` job
     *  parameter); resolved and confined to the configured output root by
     *  {@code pathResolver}.
     * :param pathResolver: resolver that confines the report file to the
     *  allowlisted batch output root, rejecting absolute, ``..`` traversal, and
     *  symlink-escape paths (CWE-22).
     */
    public TransactionDetailReportWriter(
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate,
            @Value("#{jobParameters['reportFile']}") String reportFile,
            BatchOutputPathResolver pathResolver) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.reportFile = reportFile;
        this.resolvedReportFile = pathResolver.resolveOutput(reportFile);
    }

    /**
     * :purpose: Initialize report state and open the output file at the start of
     *  the step, before any chunk is written. The file is opened with truncation
     *  so a restart regenerates the whole report from the first record.
     * :param executionContext: the step execution context (not used; the report
     *  is regenerated wholesale on restart because the owning reader disables
     *  state saving, so no partial writer state is resumed).
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        CorrelationIdContext.getOrCreateCorrelationId();
        lineCounter = 0;
        firstTime = true;
        currentCardNum = null;
        pageTotal = BigDecimal.ZERO;
        accountTotal = BigDecimal.ZERO;
        grandTotal = BigDecimal.ZERO;
        lastAmount = BigDecimal.ZERO;
        try {
            // ISO-8859-1 keeps one character equal to one byte, so the X(133)
            // record length of FD-REPTFILE-REC is a byte contract downstream
            // readers can still parse by offset.
            out = Files.newBufferedWriter(resolvedReportFile, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new ItemStreamException(
                    "Failed to open the daily transaction report file: " + reportFile, e);
        }
    }

    /**
     * :purpose: Persist writer state to the step execution context. The report is
     *  regenerated wholesale on restart (the owning reader disables state saving
     *  and {@link #open} truncates the file), so no partial writer state is
     *  stored here.
     * :param executionContext: the step execution context (unused).
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // The report is regenerated in full on restart; no partial state persists.
    }

    /**
     * :purpose: Emit the end-of-report block and release the output file. Runs
     *  once at the end of the step and reproduces the ``CBTRN03C`` end-of-file
     *  branch literally: the last rendered amount is added to the page and
     *  account totals a second time (at end of file ``TRAN-AMT`` still holds the
     *  last record read), then ``1110-WRITE-PAGE-TOTALS`` and
     *  ``1110-WRITE-GRAND-TOTALS`` run; no closing account-total line is written
     *  because the source branch performs no ``1120-WRITE-ACCOUNT-TOTALS``. The
     *  block is emitted even when the selection window matched no row, so an
     *  empty window yields the same three-line skeleton the source program
     *  produces rather than a zero-byte file that a failed run could not be
     *  distinguished from.
     */
    @Override
    public void close() throws ItemStreamException {
        try {
            if (out != null) {
                // ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL — the record area
                // still holds the last record, so it is counted once more here. The
                // account total is accumulated but never written: the end-of-file
                // branch has no 1120-WRITE-ACCOUNT-TOTALS.
                pageTotal = pageTotal.add(lastAmount);
                accountTotal = accountTotal.add(lastAmount);
                writePageTotals();
                writeGrandTotals();
                out.flush();
            }
        } catch (IOException e) {
            throw new ItemStreamException(
                    "Failed to finalize the daily transaction report file: " + reportFile, e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    throw new ItemStreamException(
                            "Failed to close the daily transaction report file: " + reportFile, e);
                } finally {
                    out = null;
                }
            }
        }
    }

    /**
     * :purpose: Render one chunk of already-ordered report rows, driving the
     *  per-card control break, first-time and page-break header emission, the
     *  page/account amount accumulation, and the detail line for each item, in
     *  the same order as the ``CBTRN03C`` main loop.
     * :param chunk: the ordered batch of report rows to render.
     */
    @Override
    public void write(Chunk<? extends TransactionReportItem> chunk) throws Exception {
        CorrelationIdContext.getOrCreateCorrelationId();
        for (TransactionReportItem item : chunk) {
            // Control break: a new card group closes out the previous group's
            // account total. The very first row (currentCardNum == null) has no
            // prior group to close.
            if (currentCardNum != null && !currentCardNum.equals(item.getTranCardNum())) {
                writeAccountTotals();
            }
            currentCardNum = item.getTranCardNum();

            // First-time header block; sets lineCounter to 4 so the immediately
            // following page-break test cannot fire for the first row.
            if (firstTime) {
                firstTime = false;
                writeHeaders();
            }

            // Page break at every full page boundary.
            if (lineCounter % PAGE_SIZE == 0) {
                writePageTotals();
                writeHeaders();
            }

            // Accumulate the amount into the page and account running totals.
            BigDecimal amount = (item.getTranAmt() == null) ? BigDecimal.ZERO : item.getTranAmt();
            pageTotal = pageTotal.add(amount);
            accountTotal = accountTotal.add(amount);
            // Retain the amount as the record area's TRAN-AMT: the end-of-file
            // branch adds whatever the last read left there to the closing totals.
            lastAmount = amount;

            writeDetail(item);
        }
    }

    /**
     * :purpose: Write the four-line report header block (report-name header,
     *  blank line, column header, and separator rule) and advance the line
     *  counter by four. Java analogue of ``1120-WRITE-HEADERS``.
     */
    private void writeHeaders() {
        // REPORT-NAME-HEADER: short name, long name, date-range label, range.
        writeLine(fixed("DALYREPT", 38)
                + fixed("Daily Transaction Report", 41)
                + fixed("Date Range: ", 12)
                + fixed(startDate, 10)
                + fixed(" to ", 4)
                + fixed(endDate, 10));
        lineCounter++;

        // WS-BLANK-LINE.
        writeLine("");
        lineCounter++;

        // TRANSACTION-HEADER-1: column labels.
        writeLine(fixed("Transaction ID", 17)
                + fixed("Account ID", 12)
                + fixed("Transaction Type", 19)
                + fixed("Tran Category", 35)
                + fixed("Tran Source", 14)
                + fixed(" ", 1)
                + fixed("        Amount", 16));
        lineCounter++;

        // TRANSACTION-HEADER-2: full-width separator rule.
        writeLine(SEPARATOR_LINE);
        lineCounter++;
    }

    /**
     * :purpose: Write one transaction detail line from a fully-resolved report
     *  row and advance the line counter by one. Java analogue of
     *  ``1120-WRITE-DETAIL``; the fixed single-space, ``-``, four-space, and
     *  two-space literals are the ``CVTRA07Y`` FILLER fields.
     * :param item: the resolved report row to render.
     */
    private void writeDetail(TransactionReportItem item) {
        writeLine(fixed(item.getTranId(), 16)
                + " "
                + fixed(item.getAccountId(), 11)
                + " "
                + fixed(item.getTranTypeCd(), 2)
                + "-"
                + fixed(item.getTranTypeDesc(), 15)
                + " "
                + String.format(Locale.ROOT, "%04d", item.getTranCatCd())
                + "-"
                + fixed(item.getTranCatDesc(), 29)
                + " "
                + fixed(item.getTranSource(), 10)
                + "    "
                + editAmount(item.getTranAmt(), false)
                + "  ");
        lineCounter++;
    }

    /**
     * :purpose: Write the current page total and its separator rule, roll the
     *  page total into the grand total, reset the page total, and advance the
     *  line counter by two. Java analogue of ``1110-WRITE-PAGE-TOTALS``.
     */
    private void writePageTotals() {
        writeLine(fixed("Page Total", 11)
                + ".".repeat(PAGE_TOTAL_DOT_COUNT)
                + editAmount(pageTotal, true));
        lineCounter++;

        grandTotal = grandTotal.add(pageTotal);
        pageTotal = BigDecimal.ZERO;

        writeLine(SEPARATOR_LINE);
        lineCounter++;
    }

    /**
     * :purpose: Write the current account (card-group) total and its separator
     *  rule, reset the account total, and advance the line counter by two. Java
     *  analogue of ``1120-WRITE-ACCOUNT-TOTALS``.
     */
    private void writeAccountTotals() {
        writeLine(fixed("Account Total", 13)
                + ".".repeat(ACCOUNT_TOTAL_DOT_COUNT)
                + editAmount(accountTotal, true));
        lineCounter++;

        accountTotal = BigDecimal.ZERO;

        writeLine(SEPARATOR_LINE);
        lineCounter++;
    }

    /**
     * :purpose: Write the report grand-total line. Java analogue of
     *  ``1110-WRITE-GRAND-TOTALS``; the line counter is not advanced, matching
     *  the source paragraph.
     */
    private void writeGrandTotals() {
        writeLine(fixed("Grand Total", 11)
                + ".".repeat(GRAND_TOTAL_DOT_COUNT)
                + editAmount(grandTotal, true));
    }

    /**
     * :purpose: Emit one physical report record: pad or truncate the supplied
     *  content to exactly {@link #LINE_WIDTH} characters (left-justified, right
     *  space-padded) and append a newline. Java analogue of
     *  ``1111-WRITE-REPORT-REC``; the line counter is advanced by callers, not
     *  here, so the counts match the source paragraphs exactly.
     * :param content: the significant characters of the line before padding.
     */
    private void writeLine(String content) {
        try {
            out.write(fixed(content, LINE_WIDTH));
            out.write("\n");
        } catch (IOException e) {
            throw new ItemStreamException(
                    "Failed to write a line to the daily transaction report file: " + reportFile, e);
        }
    }

    /**
     * :purpose: Render an amount with the ``CVTRA07Y`` edit mask into a
     *  fixed-width, 15-character field: a single sign column followed by the
     *  14-character magnitude ``ZZZ,ZZZ,ZZZ.ZZ`` (comma grouping, two decimals,
     *  leading-zero suppression to spaces). Display rounding is HALF_UP and does
     *  not alter any stored amount.
     * :param value: the amount to render; ``null`` is treated as zero.
     * :param totalMask: ``true`` for a total field (``+ZZZ,ZZZ,ZZZ.ZZ``, sign
     *  column '+' when non-negative); ``false`` for a detail field
     *  (``-ZZZ,ZZZ,ZZZ.ZZ``, sign column blank when non-negative).
     * :output: exactly {@link #AMOUNT_FIELD_WIDTH} (15) characters.
     */
    private String editAmount(BigDecimal value, boolean totalMask) {
        BigDecimal amount = (value == null) ? BigDecimal.ZERO : value;
        int sign = amount.signum();

        char signChar;
        if (sign < 0) {
            signChar = '-';
        } else if (totalMask) {
            signChar = '+';
        } else {
            signChar = ' ';
        }

        String magnitude;
        if (sign == 0) {
            // Full zero-suppression renders an all-zero magnitude as blanks.
            magnitude = " ".repeat(AMOUNT_MAGNITUDE_WIDTH);
        } else {
            String formatted = amountFormat.format(amount.abs().setScale(2, RoundingMode.HALF_UP));
            magnitude = padLeft(formatted, AMOUNT_MAGNITUDE_WIDTH);
        }

        return signChar + magnitude;
    }

    /**
     * :purpose: Left-justify a field value into a fixed width, space-padding on
     *  the right and truncating on the right when the value is longer than the
     *  field. A ``null`` value is treated as empty. The value is first reduced to
     *  single-byte text so the returned width is a BYTE width in the ISO-8859-1
     *  encoding the report is written in, keeping every ``CVTRA07Y`` field at its
     *  declared offset for text the relational store may hold outside Latin-1.
     * :param value: the field value (may be ``null``).
     * :param width: the target field width in characters, equal to bytes.
     * :output: a string of exactly ``width`` characters, each encoding to one
     *  byte.
     */
    private static String fixed(String value, int width) {
        String v = (value == null) ? "" : FixedWidthText.toSingleByteText(value);
        if (v.length() == width) {
            return v;
        }
        if (v.length() > width) {
            return v.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(v);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * :purpose: Right-justify a value into a fixed width by space-padding on the
     *  left; values already at least as wide as the field are returned
     *  unchanged.
     * :param value: the value to right-justify.
     * :param width: the target field width in characters.
     * :output: a string of at least ``width`` characters (exactly ``width`` for
     *  inputs no wider than the field).
     */
    private static String padLeft(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        int pad = width - value.length();
        StringBuilder sb = new StringBuilder(width);
        for (int i = 0; i < pad; i++) {
            sb.append(' ');
        }
        sb.append(value);
        return sb.toString();
    }
}

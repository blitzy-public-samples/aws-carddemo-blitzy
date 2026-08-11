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
 * language governing permissions and limitations under the License
 */
package com.carddemo.reporting.batch;

import com.carddemo.common.batch.BatchOutputPathResolver;
import com.carddemo.common.batch.FailedOutputCleanupListener;
import com.carddemo.common.batch.FixedWidthText;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.reporting.repository.AccountRepository;
import com.carddemo.reporting.repository.CardXrefRepository;
import com.carddemo.reporting.repository.CustomerRepository;
import com.carddemo.reporting.repository.TransactionRepository;
import com.carddemo.reporting.mapper.StatementMapper;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader;
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.transform.PassThroughLineAggregator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * :purpose: Re-platforms the legacy batch statement engine (``CBSTM03A`` / ``CBSTM03B``
 *     with the ``COSTM01`` record layout and the ``CREASTMT`` job flow) as a self-contained
 *     Spring Batch job. It walks the card cross-reference in ascending card-number order and,
 *     for each card, produces a plain-text statement (80-column fixed-width lines) and an HTML
 *     statement (100-column lines) from the shared JPA repositories via the sibling {@link
 *     StatementMapper}; the per-card documents are concatenated into two output files.
 * :output: Registers the ``statementGenerationJob`` job and its single chunk step,
 *     launched on demand by the reporting-service job scheduler; the step writes the
 *     concatenated text and HTML statement files.
 * :note: Both files are written in ISO-8859-1 and every field is reduced to single-byte
 *     text before it is padded, so the ``FD-STMTFILE-REC PIC X(80)`` and ``HTML-FIXED-LN PIC
 *     X(100)`` layouts are BYTE contracts, matching the ``LRECL=80`` and ``LRECL=100`` DD
 *     cards of ``CREASTMT``.
 * :note: The job takes only the two output file names (``stmtFile`` and ``htmlFile``, the
 *     ``STMTFILE`` and ``HTMLFILE`` DD names of ``CREASTMT``). There is no date window: the
 *     ``CREASTMT`` SORT step re-keys the WHOLE ``TRANSACT`` file by card number and
 *     transaction id with no date filter, so ``CBSTM03A`` statements a card's full history
 *     [app/jcl/CREASTMT.JCL].
 */
@Configuration("statementGenerationJobConfig")
public class StatementGenerationJob {

    /** :purpose: Frozen bean name of the on-demand statement-generation job. */
    private static final String JOB_NAME = "statementGenerationJob";

    /** :purpose: Name of the single chunk-oriented statement-generation step. */
    private static final String STEP_NAME = "statementGenerationStep";

    /** :purpose: Record delimiter for both statement sinks, pinned to a single ``LF``
     *  so an ``STMTFILE`` line stays 80 payload bytes and an ``HTMLFILE`` line 100,
     *  each plus exactly one delimiter byte, independent of
     *  ``System.lineSeparator()`` [app/jcl/CREASTMT.jcl LRECL=80 / LRECL=100]. */
    private static final String RECORD_SEPARATOR = "\n";

    /** :purpose: Ten raised to the ninth power; the modulus that keeps the
     *  low-order nine integer digits (COBOL high-order truncation). */
    private static final BigInteger TEN_POW_9 = BigInteger.TEN.pow(9);

    /** :purpose: One hundred; splits a scale-2 unscaled value into its integer
     *  and two-digit fraction components. */
    private static final BigInteger ONE_HUNDRED = BigInteger.valueOf(100L);

    // Fixed HTML record literals (COBOL ``HTML-FIXED-LN`` 88-levels), reproduced
    // byte-for-byte. Each is padded to 100 characters at emission time.
    private static final String HTML_L01 = "<!DOCTYPE html>";
    private static final String HTML_L02 = "<html lang=\"en\">";
    private static final String HTML_L03 = "<head>";
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";
    private static final String HTML_L06 = "</head>";
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";
    private static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
    private static final String HTML_LTRS = "<tr>";
    private static final String HTML_LTRE = "</tr>";
    private static final String HTML_LTDE = "</td>";
    private static final String HTML_L10 =
            "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#1d1d96b3;\">";
    private static final String HTML_L15 =
            "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#FFAF33;\">";
    private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";
    private static final String HTML_L17 = "<p>410 Terry Ave N</p>";
    private static final String HTML_L18 = "<p>Seattle WA 99999</p>";
    private static final String HTML_L22_35 =
            "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">";
    private static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#33FFD1; text-align:center;\">";
    private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";
    private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";
    private static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";
    private static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";
    private static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
    private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";
    private static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";
    private static final String HTML_L75 = "<h3>End of Statement</h3>";
    private static final String HTML_L78 = "</table>";
    private static final String HTML_L79 = "</body>";
    private static final String HTML_L80 = "</html>";

    /**
     * :purpose: Read the card cross-reference sequentially in ascending card-number order,
     *     reproducing the ``CBSTM03A`` ``1000-MAINLINE`` walk of the keyed ``XREFFILE``.
     * :param cardXrefRepository: the shared card cross-reference repository that supplies the
     *     paged, sorted ``findAll`` used as the reader source.
     * :returns: a paging repository reader over {@link CardXref} sorted by ``xrefCardNum``
     *     ascending.
     * :note: ``@StepScope`` is required, not merely convenient: a ``RepositoryItemReader`` is
     *     an ``ItemStream`` that holds the page cursor of the read it is performing, so a
     *     singleton instance would be ONE cursor shared by every concurrent step execution and two
     *     statement runs launched together would consume each other's pages - one statement file
     *     repeating cards, another missing them - while both executions still reported COMPLETED.
     *     A step-scoped bean gives each execution its own cursor.
     */
    @Bean
    @StepScope
    public RepositoryItemReader<CardXref> statementCardXrefReader(CardXrefRepository cardXrefRepository) {
        return new RepositoryItemReaderBuilder<CardXref>()
                .name("statementCardXrefReader")
                .repository(cardXrefRepository)
                .methodName("findAll")
                .sorts(Map.of("xrefCardNum", Sort.Direction.ASC))
                .pageSize(100)
                .build();
    }

    /**
     * :purpose: Assemble one statement model per card cross-reference,
     *  reproducing the ``CBSTM03A`` per-card lookups ``2000-CUSTFILE-GET`` and
     *  ``3000-ACCTFILE-GET`` and the ``4000``/``6000`` transaction gather, with
     *  model assembly delegated to {@link StatementMapper}.
     * :param statementMapper: the collaborator that assembles the statement model.
     * :param customerRepository: the shared customer repository.
     * :param accountRepository: the shared account repository.
     * :param transactionRepository: the shared transaction repository.
     * :returns: the configured statement item processor.
     */
    @Bean
    public StatementItemProcessor statementItemProcessor(StatementMapper statementMapper,
                                                         CustomerRepository customerRepository,
                                                         AccountRepository accountRepository,
                                                         TransactionRepository transactionRepository) {
        return new StatementItemProcessor(statementMapper, customerRepository, accountRepository,
                transactionRepository);
    }

    /**
     * :purpose: Chunk processor that turns a single card cross-reference into a
     *  fully assembled statement model. It resolves the owning customer and
     *  account and gathers the card's transactions in ascending transaction-id
     *  order (the ``CREASTMT`` SORT key), then delegates model assembly to
     *  {@link StatementMapper}. A missing customer or account raises
     *  {@link RecordNotFoundException}, failing the step to mirror the legacy
     *  ``9999-ABEND-PROGRAM`` fatal abend.
     */
    static class StatementItemProcessor
            implements ItemProcessor<CardXref, StatementMapper.StatementModel> {

        private final StatementMapper statementMapper;
        private final CustomerRepository customerRepository;
        private final AccountRepository accountRepository;
        private final TransactionRepository transactionRepository;

        /**
         * :purpose: Construct the processor with its shared collaborators.
         * :param statementMapper: the statement model assembler.
         * :param customerRepository: the shared customer repository.
         * :param accountRepository: the shared account repository.
         * :param transactionRepository: the shared transaction repository.
         */
        StatementItemProcessor(StatementMapper statementMapper,
                               CustomerRepository customerRepository,
                               AccountRepository accountRepository,
                               TransactionRepository transactionRepository) {
            this.statementMapper = statementMapper;
            this.customerRepository = customerRepository;
            this.accountRepository = accountRepository;
            this.transactionRepository = transactionRepository;
        }

        /**
         * :purpose: Resolve the customer, account and transactions for one card
         *  cross-reference and assemble the statement model.
         * :param xref: the card cross-reference driving this statement.
         * :returns: the assembled statement model for the card.
         */
        @Override
        public StatementMapper.StatementModel process(CardXref xref) {
            Customer customer = customerRepository.findById(xref.getXrefCustId())
                    .orElseThrow(() -> new RecordNotFoundException(
                            "Customer not found for id " + xref.getXrefCustId()));
            Account account = accountRepository.findById(xref.getXrefAcctId())
                    .orElseThrow(() -> new RecordNotFoundException(
                            "Account not found for id " + xref.getXrefAcctId()));
            List<Transaction> transactions =
                    transactionRepository.findByTranCardNumOrderByTranIdAsc(xref.getXrefCardNum());
            return statementMapper.toStatement(account, customer, xref, transactions);
        }
    }

    /**
     * :purpose: Build the dual-output statement writer, late-binding the text and
     *  HTML output file names from job parameters so the on-demand launcher can
     *  supply them while the legacy ``STMTFILE`` / ``HTMLFILE`` defaults keep the
     *  job runnable with no parameters at all.
     * :param stmtPath: the plain-text statement output file name.
     * :param htmlPath: the HTML statement output file name.
     * :param pathResolver: shared resolver that confines both files to the
     *  configured ``carddemo.batch.output-dir`` root, rejecting absolute paths,
     *  ``..`` traversal and symlink escapes (CWE-22), and creating the directory
     *  so the flat-file writers can open their files.
     * :returns: the configured statement item writer.
     * :note: Both names are bare file names, never relative paths: they are resolved
     *  through the shared output root so the files land in the writable, mounted
     *  batch directory rather than against the process working directory, which is
     *  on the container's read-only root filesystem.
     */
    @Bean
    @StepScope
    public StatementItemWriter statementItemWriter(
            @Value("#{jobParameters['stmtFile'] ?: 'statements.txt'}") String stmtPath,
            @Value("#{jobParameters['htmlFile'] ?: 'statements.html'}") String htmlPath,
            BatchOutputPathResolver pathResolver) {
        return new StatementItemWriter(
                new FileSystemResource(pathResolver.resolveOutput(stmtPath)),
                new FileSystemResource(pathResolver.resolveOutput(htmlPath)));
    }

    /**
     * :purpose: Stream writer that expands each statement model into many
     *  plain-text and HTML output lines and delegates them to two
     *  {@link FlatFileItemWriter} instances (text and HTML). It reproduces the
     *  ``CBSTM03A`` dual ``WRITE`` to ``STMTFILE`` (80-column) and ``HTMLFILE``
     *  (100-column); each run recreates both files (no append), mirroring the
     *  ``CREASTMT`` delete-then-create job flow.
     */
    static class StatementItemWriter implements ItemStreamWriter<StatementMapper.StatementModel> {

        private final FlatFileItemWriter<String> textDelegate;
        private final FlatFileItemWriter<String> htmlDelegate;

        /**
         * :purpose: Construct the writer and its two flat-file delegates.
         * :param textResource: the plain-text statement output resource.
         * :param htmlResource: the HTML statement output resource.
         */
        StatementItemWriter(FileSystemResource textResource, FileSystemResource htmlResource) {
            // ISO-8859-1 keeps one character equal to one byte, so FD-STMTFILE-REC
            // PIC X(80) and HTML-FIXED-LN PIC X(100) stay BYTE contracts — the
            // record lengths CREASTMT declares on its STMTFILE (LRECL=80) and
            // HTMLFILE (LRECL=100) DD cards — for any text the relational store
            // can hold. Under the platform default of UTF-8 a single accented
            // customer name pushed its lines two bytes over the declared length.
            // RECORD_SEPARATOR pins the delimiter to a single LF for the same
            // reason: the JVM default would add a second CR byte to every line.
            this.textDelegate = new FlatFileItemWriterBuilder<String>()
                    .name("statementTextWriter")
                    .resource(textResource)
                    .encoding(StandardCharsets.ISO_8859_1.name())
                    .lineSeparator(RECORD_SEPARATOR)
                    .lineAggregator(new PassThroughLineAggregator<>())
                    .build();
            this.htmlDelegate = new FlatFileItemWriterBuilder<String>()
                    .name("statementHtmlWriter")
                    .resource(htmlResource)
                    .encoding(StandardCharsets.ISO_8859_1.name())
                    .lineSeparator(RECORD_SEPARATOR)
                    .lineAggregator(new PassThroughLineAggregator<>())
                    .build();
        }

        /**
         * :purpose: Open both flat-file delegates for the step.
         * :param executionContext: the step execution context.
         */
        @Override
        public void open(ExecutionContext executionContext) throws ItemStreamException {
            textDelegate.open(executionContext);
            htmlDelegate.open(executionContext);
        }

        /**
         * :purpose: Persist both delegates' restart state to the step context.
         * :param executionContext: the step execution context.
         */
        @Override
        public void update(ExecutionContext executionContext) throws ItemStreamException {
            textDelegate.update(executionContext);
            htmlDelegate.update(executionContext);
        }

        /**
         * :purpose: Close both flat-file delegates, releasing the output files.
         */
        @Override
        public void close() throws ItemStreamException {
            textDelegate.close();
            htmlDelegate.close();
        }

        /**
         * :purpose: Render each statement model in the chunk to its plain-text and
         *  HTML lines and write them to the respective delegate.
         * :param chunk: the chunk of assembled statement models.
         */
        @Override
        public void write(Chunk<? extends StatementMapper.StatementModel> chunk) throws Exception {
            List<String> textLines = new ArrayList<>();
            List<String> htmlLines = new ArrayList<>();
            for (StatementMapper.StatementModel model : chunk.getItems()) {
                textLines.addAll(renderText(model));
                htmlLines.addAll(renderHtml(model));
            }
            textDelegate.write(new Chunk<>(textLines));
            htmlDelegate.write(new Chunk<>(htmlLines));
        }
    }

    /**
     * :purpose: Render one statement model to its plain-text lines, reproducing
     *  the ``CBSTM03A`` ``5000-CREATE-STATEMENT``/``6000-WRITE-TRANS``/``4000``
     *  ``STMTFILE`` emission. Every returned line is exactly 80 characters, and
     *  the line order (including the repeated separators) matches the source.
     * :param model: the assembled statement model for one card.
     * :returns: the ordered list of 80-character plain-text statement lines.
     */
    static List<String> renderText(StatementMapper.StatementModel model) {
        String stName = pad(model.getCustomerName(), 75);
        String stAdd1 = pad(model.getAddressLine1(), 50);
        String stAdd2 = pad(model.getAddressLine2(), 50);
        String stAdd3 = pad(model.getAddressLine3(), 80);
        long accountIdValue = model.getAccountId() == null ? 0L : model.getAccountId();
        String stAcctId = pad(String.format(Locale.ROOT, "%011d", accountIdValue), 20);
        String stCurrBal = formatSignedZeroFilled(model.getCurrentBalance());
        int ficoValue = model.getFicoScore() == null ? 0 : model.getFicoScore();
        String stFico = pad(String.format(Locale.ROOT, "%03d", ficoValue), 20);
        String stTotal = formatSuppressed(model.getTotalAmount());

        String dashes = "-".repeat(80);

        List<String> lines = new ArrayList<>();
        lines.add("*".repeat(31) + "START OF STATEMENT" + "*".repeat(31));            // ST-LINE0
        lines.add(stName + " ".repeat(5));                                            // ST-LINE1
        lines.add(stAdd1 + " ".repeat(30));                                           // ST-LINE2
        lines.add(stAdd2 + " ".repeat(30));                                           // ST-LINE3
        lines.add(stAdd3);                                                            // ST-LINE4
        lines.add(dashes);                                                            // ST-LINE5
        lines.add(" ".repeat(33) + "Basic Details " + " ".repeat(33));               // ST-LINE6
        lines.add(dashes);                                                            // ST-LINE5
        lines.add("Account ID         :" + stAcctId + " ".repeat(40));               // ST-LINE7
        lines.add("Current Balance    :" + stCurrBal + " ".repeat(47));              // ST-LINE8
        lines.add("FICO Score         :" + stFico + " ".repeat(40));                 // ST-LINE9
        lines.add(dashes);                                                            // ST-LINE10
        lines.add(" ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30));          // ST-LINE11
        lines.add(dashes);                                                            // ST-LINE12
        lines.add("Tran ID         " + pad("Tran Details    ", 51) + "  Tran Amount"); // ST-LINE13
        lines.add(dashes);                                                            // ST-LINE12
        List<StatementMapper.StatementTransaction> transactions = model.getTransactions();
        if (transactions != null) {
            for (StatementMapper.StatementTransaction transaction : transactions) {
                String stTranId = pad(transaction.getTranId(), 16);
                String stTranDt = pad(transaction.getDescription(), 49);
                String stTranAmt = formatSuppressed(transaction.getAmount());
                lines.add(stTranId + " " + stTranDt + "$" + stTranAmt);               // ST-LINE14
            }
        }
        lines.add(dashes);                                                            // ST-LINE12
        lines.add("Total EXP:" + " ".repeat(56) + "$" + stTotal);                     // ST-LINE14A
        lines.add("*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));              // ST-LINE15
        return lines;
    }

    /**
     * :purpose: Left-justify a value into a fixed width, space-padding on the
     *  right and truncating on the right when the value is longer, reproducing
     *  COBOL fixed-length ``MOVE`` semantics. The value is first reduced to
     *  single-byte text, so the returned width is a BYTE width in the ISO-8859-1
     *  encoding both statement files are written in and the X(80)/X(100) record
     *  contracts hold for text outside Latin-1.
     * :param value: the field value; ``null`` is treated as empty.
     * :param width: the target field width in characters, equal to bytes.
     * :returns: a string of exactly ``width`` characters, each encoding to one
     *  byte.
     */
    static String pad(String value, int width) {
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
     * :purpose: Render one statement model to its HTML lines, reproducing the ``CBSTM03A``
     *     ``5100``/``5200``/``6000``/``4000`` ``HTMLFILE`` emission. Each card yields one complete
     *     HTML document; every returned line is exactly 100 characters. Name and address lines
     *     copy characters up to the first double-space (COBOL ``DELIMITED BY ' '``); the
     *     basic-detail and per-transaction lines use the whole fixed-width field (``DELIMITED BY
     *     '*'``).
     * :param model: the assembled statement model for one card.
     * :returns: the ordered list of 100-character HTML statement lines.
     * :note: Every value interpolated into a line is HTML-escaped first. The legacy program
     *     wrote customer text into the document unescaped, which in a browser-rendered target
     *     makes any name, address or transaction description containing markup executable script
     *     (stored XSS). Escaping is applied to the VALUE, before the fixed-width ``MOVE``, so
     *     ``HTML-FIXED-LN PIC X(100)`` still holds; escaping is the identity transformation for
     *     text without ``& < > " '``, so no byte of normal statement output changes.
     */
    static List<String> renderHtml(StatementMapper.StatementModel model) {
        long accountIdValue = model.getAccountId() == null ? 0L : model.getAccountId();
        String stAcctId = htmlEscape(pad(String.format(Locale.ROOT, "%011d", accountIdValue), 20));
        String stCurrBal = htmlEscape(formatSignedZeroFilled(model.getCurrentBalance()));
        int ficoValue = model.getFicoScore() == null ? 0 : model.getFicoScore();
        String stFico = htmlEscape(pad(String.format(Locale.ROOT, "%03d", ficoValue), 20));

        String acctHeader = "<h3>Statement for Account Number: " + stAcctId + "</h3>";
        String nameLine = "<p style=\"font-size:16px\">"
                + htmlEscape(trimAtDoubleSpace(model.getCustomerName())) + "  " + "</p>";
        String addressLine1 =
                "<p>" + htmlEscape(trimAtDoubleSpace(model.getAddressLine1())) + "  " + "</p>";
        String addressLine2 =
                "<p>" + htmlEscape(trimAtDoubleSpace(model.getAddressLine2())) + "  " + "</p>";
        String addressLine3 =
                "<p>" + htmlEscape(trimAtDoubleSpace(model.getAddressLine3())) + "  " + "</p>";
        String acctBasic = "<p>Account ID         : " + stAcctId + "</p>";
        String balanceBasic = "<p>Current Balance    : " + stCurrBal + "</p>";
        String ficoBasic = "<p>FICO Score         : " + stFico + "</p>";

        List<String> raw = new ArrayList<>();
        // 5100-WRITE-HTML-HEADER
        raw.add(HTML_L01);
        raw.add(HTML_L02);
        raw.add(HTML_L03);
        raw.add(HTML_L04);
        raw.add(HTML_L05);
        raw.add(HTML_L06);
        raw.add(HTML_L07);
        raw.add(HTML_L08);
        raw.add(HTML_LTRS);
        raw.add(HTML_L10);
        raw.add(acctHeader);
        raw.add(HTML_LTDE);
        raw.add(HTML_LTRE);
        raw.add(HTML_LTRS);
        raw.add(HTML_L15);
        raw.add(HTML_L16);
        raw.add(HTML_L17);
        raw.add(HTML_L18);
        raw.add(HTML_LTDE);
        raw.add(HTML_LTRE);
        raw.add(HTML_LTRS);
        raw.add(HTML_L22_35);
        // 5200-WRITE-HTML-NMADBS
        raw.add(nameLine);
        raw.add(addressLine1);
        raw.add(addressLine2);
        raw.add(addressLine3);
        raw.add(HTML_LTDE);
        raw.add(HTML_LTRE);
        raw.add(HTML_LTRS);
        raw.add(HTML_L30_42);
        raw.add(HTML_L31);
        raw.add(HTML_LTDE);
        raw.add(HTML_LTRE);
        raw.add(HTML_LTRS);
        raw.add(HTML_L22_35);
        raw.add(acctBasic);
        raw.add(balanceBasic);
        raw.add(ficoBasic);
        raw.add(HTML_LTDE);
        raw.add(HTML_LTRE);
        raw.add(HTML_LTRS);
        raw.add(HTML_L30_42);
        raw.add(HTML_L43);
        raw.add(HTML_LTDE);
        raw.add(HTML_LTRE);
        raw.add(HTML_LTRS);
        raw.add(HTML_L47);
        raw.add(HTML_L48);
        raw.add(HTML_LTDE);
        raw.add(HTML_L50);
        raw.add(HTML_L51);
        raw.add(HTML_LTDE);
        raw.add(HTML_L53);
        raw.add(HTML_L54);
        raw.add(HTML_LTDE);
        raw.add(HTML_LTRE);
        // 6000-WRITE-TRANS (one block per transaction)
        List<StatementMapper.StatementTransaction> transactions = model.getTransactions();
        if (transactions != null) {
            for (StatementMapper.StatementTransaction transaction : transactions) {
                // Escape the value, then MOVE it into its COBOL field width: the
                // escaped form must be bounded by the field, not by the X(100)
                // record, or an expanded value would push the closing tag out of
                // the line.
                String stTranId = pad(htmlEscape(transaction.getTranId()), 16);
                String stTranDt = pad(htmlEscape(transaction.getDescription()), 49);
                String stTranAmt = htmlEscape(formatSuppressed(transaction.getAmount()));
                raw.add(HTML_LTRS);
                raw.add(HTML_L58);
                raw.add("<p>" + stTranId + "</p>");
                raw.add(HTML_LTDE);
                raw.add(HTML_L61);
                raw.add("<p>" + stTranDt + "</p>");
                raw.add(HTML_LTDE);
                raw.add(HTML_L64);
                raw.add("<p>" + stTranAmt + "</p>");
                raw.add(HTML_LTDE);
                raw.add(HTML_LTRE);
            }
        }
        // 4000-TRNXFILE-GET (statement close)
        raw.add(HTML_LTRS);
        raw.add(HTML_L10);
        raw.add(HTML_L75);
        raw.add(HTML_LTDE);
        raw.add(HTML_LTRE);
        raw.add(HTML_L78);
        raw.add(HTML_L79);
        raw.add(HTML_L80);

        List<String> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            lines.add(pad(line, 100));
        }
        return lines;
    }

    /**
     * :purpose: Escape the five characters that carry meaning in HTML so a value
     *  taken from the datastore is rendered as text and can never be parsed as
     *  markup or script by a browser.
     * :param value: the value to escape; ``null`` is treated as empty.
     * :returns: the value with ``&``, ``<``, ``>``, ``"`` and ``'`` replaced by
     *  their character references; the value unchanged when it contains none of
     *  them.
     */
    static String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        // The ampersand must be replaced first, otherwise the ampersands this
        // method introduces would themselves be escaped again.
        boolean needsEscaping = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
                needsEscaping = true;
                break;
            }
        }
        if (!needsEscaping) {
            // The overwhelmingly common case: ordinary statement text is returned
            // unchanged, so no allocation and no byte of existing output moves.
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * :purpose: Return the leading portion of a value up to, but not including,
     *  the first double-space, reproducing COBOL ``STRING ... DELIMITED BY '  '``.
     * :param value: the source value; ``null`` is treated as empty.
     * :returns: the substring before the first double-space, or the whole value
     *  when it contains none.
     */
    static String trimAtDoubleSpace(String value) {
        if (value == null) {
            return "";
        }
        int idx = value.indexOf("  ");
        return idx < 0 ? value : value.substring(0, idx);
    }

    /**
     * :purpose: Format a monetary value with the COBOL ``9(9).99-`` edit picture:
     *  a nine-digit zero-filled integer part, a two-digit fraction, and a trailing
     *  sign (``-`` when negative, space otherwise). Values wider than nine integer
     *  digits keep their low-order nine (COBOL high-order truncation).
     * :param value: the amount to format; ``null`` is treated as zero.
     * :returns: a 13-character edited string.
     */
    static String formatSignedZeroFilled(BigDecimal value) {
        return editNumeric(value, false);
    }

    /**
     * :purpose: Format a monetary value with the COBOL ``Z(9).99-`` edit picture:
     *  a nine-position integer part with leading-zero suppression (leading zeros
     *  become spaces, an all-zero integer part is all spaces), a two-digit fraction
     *  that is never suppressed, and a trailing sign (``-`` when negative, space
     *  otherwise). Values wider than nine integer digits keep their low-order nine.
     * :param value: the amount to format; ``null`` is treated as zero.
     * :returns: a 13-character edited string.
     */
    static String formatSuppressed(BigDecimal value) {
        return editNumeric(value, true);
    }

    /**
     * :purpose: Shared numeric-edit routine for the ``9(9).99-`` and ``Z(9).99-``
     *  pictures: normalize to scale 2 by truncating toward zero (COBOL ``MOVE``
     *  into a ``V99`` receiver carries no ``ROUNDED`` phrase), then render the
     *  low-order nine integer
     *  digits (zero-filled or leading-zero-suppressed), the two fraction digits, and
     *  the trailing sign.
     * :param value: the amount to format; ``null`` is treated as zero.
     * :param suppress: ``true`` for leading-zero suppression (``Z``); ``false`` for
     *  zero fill (``9``).
     * :returns: a 13-character edited string.
     */
    private static String editNumeric(BigDecimal value, boolean suppress) {
        BigDecimal amount = (value == null) ? BigDecimal.ZERO : value;
        BigDecimal scaled = amount.setScale(2, RoundingMode.DOWN);
        boolean negative = scaled.signum() < 0;
        BigInteger unscaled = scaled.abs().unscaledValue();
        BigInteger integerPart = unscaled.divide(ONE_HUNDRED).mod(TEN_POW_9);
        BigInteger fractionPart = unscaled.mod(ONE_HUNDRED);

        String integerField;
        if (suppress) {
            if (integerPart.signum() == 0) {
                integerField = " ".repeat(9);
            } else {
                String digits = integerPart.toString();
                integerField = " ".repeat(9 - digits.length()) + digits;
            }
        } else {
            integerField = String.format(Locale.ROOT, "%09d", integerPart);
        }
        String fractionField = String.format(Locale.ROOT, "%02d", fractionPart);
        char sign = negative ? '-' : ' ';
        return integerField + "." + fractionField + sign;
    }

    /**
     * :purpose: Build the cleanup listener for the statement step, bound to both statement
     *  outputs, so a run that does not complete successfully leaves neither file behind. A
     *  failed run previously left two zero-byte files that read as a finished statement
     *  run that simply had nothing to say.
     * :param pathResolver: shared resolver giving the same paths the writer opens.
     * :param stmtPath: the plain-text statement output file name.
     * :param htmlPath: the HTML statement output file name.
     * :returns: the cleanup listener bound to both statement output paths.
     */
    @Bean
    @StepScope
    public FailedOutputCleanupListener statementOutputCleanupListener(
            BatchOutputPathResolver pathResolver,
            @Value("#{jobParameters['stmtFile'] ?: 'statements.txt'}") String stmtPath,
            @Value("#{jobParameters['htmlFile'] ?: 'statements.html'}") String htmlPath) {
        return new FailedOutputCleanupListener(
                pathResolver.resolveOutput(stmtPath),
                pathResolver.resolveOutput(htmlPath));
    }

    /**
     * :purpose: Define the chunk-oriented statement-generation step: read the card
     *  cross-reference, assemble each statement model, and write the dual text and
     *  HTML output. The writer is registered as a stream so its file delegates are
     *  opened, updated and closed by the step.
     * :param jobRepository: the Spring Batch job repository.
     * :param transactionManager: the platform transaction manager for the chunk.
     * :param statementCardXrefReader: the card cross-reference reader.
     * :param statementItemProcessor: the statement-model processor.
     * :param statementItemWriter: the dual text/HTML statement writer.
     * :param statementCleanupListener: step-scoped listener removing both statement
     *  artifacts when the step does not complete successfully.
     * :returns: the configured statement-generation step.
     */
    @Bean
    public Step statementGenerationStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager,
                                        RepositoryItemReader<CardXref> statementCardXrefReader,
                                        StatementItemProcessor statementItemProcessor,
                                        StatementItemWriter statementItemWriter,
                                        FailedOutputCleanupListener statementCleanupListener) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<CardXref, StatementMapper.StatementModel>chunk(10)
                .transactionManager(transactionManager)
                .reader(statementCardXrefReader)
                .processor(statementItemProcessor)
                .writer(statementItemWriter)
                .stream(statementItemWriter)
                .listener((StepExecutionListener) statementCleanupListener)
                .build();
    }

    /**
     * :purpose: Remove both statement artifacts when the step does not complete
     *  successfully, so a failed run leaves neither the text nor the HTML statement
     *  behind; the legacy job stream deleted the previous generation and created a new
     *  one, and an abending ``CBSTM03A`` left the operator with no statement at all
     *  [app/jcl/CREASTMT.JCL ``STEP030``/``STEP040``].
     * :param stmtPath: the plain-text statement output file name, the same job parameter
     *  and default the writer binds.
     * :param htmlPath: the HTML statement output file name, likewise.
     * :param pathResolver: resolver confining both names to the batch output root, so the
     *  listener addresses exactly the files the writer opened.
     * :returns: the cleanup listener for one statement step execution.
     * :note: ``@StepScope`` is required because the destinations are job parameters: a
     *  singleton listener would be bound to whichever execution created it and could
     *  delete another run's statements.
     */
    @Bean
    @StepScope
    public FailedOutputCleanupListener statementCleanupListener(
            @Value("#{jobParameters['stmtFile'] ?: 'statements.txt'}") String stmtPath,
            @Value("#{jobParameters['htmlFile'] ?: 'statements.html'}") String htmlPath,
            BatchOutputPathResolver pathResolver) {
        return new FailedOutputCleanupListener(
                pathResolver.resolveOutput(stmtPath), pathResolver.resolveOutput(htmlPath));
    }

    /**
     * :purpose: Define the on-demand statement-generation job as a single step.
     * :param jobRepository: the Spring Batch job repository.
     * :param statementGenerationStep: the statement-generation step.
     * :returns: the configured ``statementGenerationJob`` job.
     */
    @Bean
    public Job statementGenerationJob(JobRepository jobRepository, Step statementGenerationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(statementGenerationStep)
                .build();
    }
}

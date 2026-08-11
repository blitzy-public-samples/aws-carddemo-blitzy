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

import com.carddemo.batch.config.JobSchedulingConfig;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.batch.BatchOutputPathResolver;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.domain.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * :purpose: Idempotent, ordered combine pass over the unified ``transactions`` table; the
 *     Java analogue of the legacy ``COMBTRAN`` job (``app/jcl/COMBTRAN.jcl``). The legacy
 *     ``STEP05R`` sorts the transaction backup concatenated with the system-generated interest
 *     transactions by ``SORT FIELDS=(TRAN-ID,A)`` into a combined ``SORTOUT`` file, and
 *     ``STEP10`` reloads that combined file into the transaction master via IDCAMS ``REPRO``.
 *     In the relational target there is a single ``transactions`` table and the interest
 *     transactions are already persisted into it by the interest-calculation job, so the
 *     reload is idempotent and this tasklet performs no INSERT, UPDATE, or DELETE. It walks
 *     the table once in ``tranId`` ascending order (the ``SORT FIELDS=(TRAN-ID,A)`` semantics)
 *     and writes each transaction as a fixed-width ``CVTRA05Y`` record to the combined output
 *     file, reproducing the ``STEP05R`` ``SORTOUT`` artifact.
 * :output: The combined output file at the resolved ``outputFile`` path holding every
 *     transaction as a 350-character fixed-width record in ``tranId`` order, and a single INFO
 *     log line reporting the combined row count — the count only, never any transaction
 *     content, card number, amount, or other record field (PII safety).
 * :note: The batch ``config`` package wires this tasklet into a single-step ``Job`` via
 *     ``new StepBuilder(name, jobRepository).tasklet(tasklet, transactionManager).build()``,
 *     sets the job-level correlation id, and launches it through the auto-configured
 *     ``JobOperator``; the module's ``JdbcBatchConfiguration`` supplies the JDBC job
 *     repository with ``@EnableJdbcJobRepository`` so the run is persisted to the ``BATCH_*``
 *     tables.
 * :note: Structured JSON logging and the ``correlationId`` MDC key are supplied by the
 *     module's ``logback-spring.xml`` together with {@link CorrelationIdContext}; this tasklet
 *     only ensures a correlation id is present before it logs.
 */
@Component
public class CombineTransactionsTasklet implements Tasklet {

    /** SLF4J logger; the ``correlationId`` MDC value is rendered by ``logback-spring.xml``. */
    private static final Logger log = LoggerFactory.getLogger(CombineTransactionsTasklet.class);

    /**
     * Database fetch page size for the reconciliation scan; independent of any
     * chunk size, keeping the ordered walk memory-bounded across arbitrarily
     * large tables.
     */
    private static final int PAGE_SIZE = 500;

    /** Repository over the unified ``transactions`` table (copybook ``CVTRA05Y``). */
    private final TransactionRepository transactionRepository;

    /** Resolver confining the combined output path to the configured output root. */
    private final BatchOutputPathResolver pathResolver;

    /**
     * Combined-print file name used when the run supplies no ``outputFile`` job
     * parameter, so every launch path resolves a destination - not only the HTTP
     * launcher in {@link JobSchedulingConfig}.
     */
    private final String defaultOutputFile;

    /**
     * :purpose: Construct the tasklet with the repository used for the ordered
     *  combine scan and the resolver that confines the combined output path.
     * :param transactionRepository: Spring Data JPA repository exposing the
     *  ``findAllByOrderByTranIdAsc(Pageable)`` key-ordered finder over the
     *  unified ``transactions`` table.
     * :param pathResolver: resolver that normalizes the requested ``outputFile``
     *  and confines it to the configured batch output root.
     * :param defaultOutputFile: configured combined-print name applied when the run
     *  carries no ``outputFile`` job parameter, which is the case for the
     *  ``--spring.batch.job.name`` command-line launch path.
     */
    public CombineTransactionsTasklet(TransactionRepository transactionRepository,
                                      BatchOutputPathResolver pathResolver,
                                      @Value("${carddemo.batch.combined-transaction-file:"
                                              + JobSchedulingConfig.DEFAULT_COMBINED_TRANSACTION_FILE + "}")
                                      String defaultOutputFile) {
        this.transactionRepository = transactionRepository;
        this.pathResolver = pathResolver;
        this.defaultOutputFile = defaultOutputFile;
    }

    /**
     * :purpose: Walk the unified ``transactions`` table once in ``tranId`` ascending order,
     *     paging through the rows, and write each transaction as a fixed-width ``CVTRA05Y`` record
     *     to the combined output file (the ``STEP05R`` ``SORTOUT`` artifact); emit a single
     *     aggregate INFO log line with the combined row count. Performs no persistence or mutation
     *     of the table.
     * :param contribution: the step contribution for the current step execution (not modified;
     *     this combine pass contributes no read or write count).
     * :param chunkContext: the chunk context for the current step execution, supplying the
     *     ``outputFile`` job parameter for the combined file path.
     * :returns: {@link RepeatStatus#FINISHED}, signalling that the tasklet completed its work
     *     in a single invocation.
     * :note: An I/O failure while writing the combined file propagates out of ``execute`` so
     *     the step ends ``FAILED`` (the batch analogue of the legacy non-zero completion code); a
     *     clean run ends ``COMPLETED``.
     */
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        CorrelationIdContext.getOrCreateCorrelationId();

        Object outputFileParam = chunkContext.getStepContext().getJobParameters().get("outputFile");
        String requested = (outputFileParam == null) ? null : outputFileParam.toString();
        String outputFile = (requested == null || requested.isBlank()) ? defaultOutputFile : requested;
        Path resolved = pathResolver.resolveOutput(outputFile);

        long combinedCount = 0;
        // REPLACE, not report. Files.newBufferedWriter's default encoder is STRICT, so a
        // single unrepresentable code point anywhere in the 300-row feed aborted the whole
        // combine step with UnmappableCharacterException and left a truncated file behind.
        // CobolRecordFormatter already reduces every text field to single-byte text before
        // padding, so nothing unrepresentable should reach the encoder; this configuration
        // is what guarantees that if one ever does, the job still delivers a byte-exact
        // record with a visible '?' substitute instead of failing the whole run.
        CharsetEncoder encoder = StandardCharsets.ISO_8859_1.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(resolved), encoder))) {
            int pageIndex = 0;
            List<Transaction> page;
            do {
                page = transactionRepository.findAllByOrderByTranIdAsc(PageRequest.of(pageIndex, PAGE_SIZE));
                for (Transaction transaction : page) {
                    writer.write(CobolRecordFormatter.transactionRecord(transaction));
                    writer.write('\n');
                    combinedCount++;
                }
                pageIndex++;
            } while (page.size() == PAGE_SIZE);
        }

        log.info("Combined transaction file complete: {} transactions written in TRAN-ID order",
                combinedCount);

        return RepeatStatus.FINISHED;
    }
}

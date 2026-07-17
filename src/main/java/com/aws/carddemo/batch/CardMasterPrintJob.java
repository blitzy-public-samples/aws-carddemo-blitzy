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
package com.aws.carddemo.batch;

import java.util.Map;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.repository.CardRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration that reproduces the legacy COBOL batch program
 * {@code CBACT02C} ("Read and print card data file").
 *
 * <p>This is the set-based, chunk-oriented re-platforming of
 * {@code legacy/cbl/CBACT02C.cbl} (source {@code app/cbl/CBACT02C.cbl}). The
 * mainframe program opens the indexed {@code CARDFILE} dataset
 * ({@code CARDDATA.VSAM.KSDS}, record layout copybook {@code CVACT02Y}) as
 * {@code INPUT}, walks it sequentially in ascending {@code RECORD KEY}
 * ({@code FD-CARD-NUM}) order until end-of-file, and {@code DISPLAY}s each
 * {@code CARD-RECORD}. It is strictly <strong>read-only</strong>: it never
 * writes, rewrites, or deletes a record (AAP &sect;0.4.4, &sect;0.5.4).</p>
 *
 * <h2>COBOL parity contract</h2>
 * <ul>
 *   <li><strong>Sequential ascending-key scan.</strong> The COBOL
 *       {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop reads {@code CARDFILE} one
 *       record at a time through {@code 1000-CARDFILE-GET-NEXT}. Here that
 *       becomes a paged {@link RepositoryItemReader} over the {@code card} table
 *       ordered by {@code cardNum} ascending, preserving the VSAM primary-key
 *       browse order exactly.</li>
 *   <li><strong>Print every field.</strong> The COBOL {@code DISPLAY CARD-RECORD}
 *       emits the whole record to {@code SYSOUT}. The chunk writer logs every
 *       card field at {@code INFO}, the natural analog of {@code SYSOUT}.</li>
 *   <li><strong>Return-code semantics.</strong> A clean run corresponds to
 *       {@code RC 0}: the step and job complete successfully. An I/O failure in
 *       the COBOL program leads to {@code 9999-ABEND-PROGRAM} and {@code RC 8};
 *       in the Java target a data-access failure raised by the reader propagates
 *       out of the step, failing the job with a non-zero exit status, which the
 *       CI/CD trigger surfaces as a non-zero return code (AAP &sect;0.5.4,
 *       &sect;0.7.2 M1).</li>
 * </ul>
 *
 * <h2>Sensitive data &mdash; CVV masking</h2>
 * The {@link Card} entity carries a card verification value (CVV) that is a
 * demonstration-only legacy anti-pattern. It is treated as sensitive and is
 * <strong>never logged in full</strong> (AAP &sect;0.7.3 L1, &sect;0.9.3): the
 * writer emits a fixed mask ({@value #CVV_MASK}) in the CVV position and never
 * reads {@link Card#getCvv()}, so the value cannot leak into the batch log by
 * construction. Formatting is centralized in {@link #formatCardRecord(Card)} so
 * this guarantee is explicit and verifiable.
 *
 * <h2>Batch infrastructure</h2>
 * <ul>
 *   <li><strong>No {@code @EnableBatchProcessing}.</strong> This configuration
 *       relies entirely on Spring Boot's batch auto-configuration for the
 *       {@link JobRepository}, {@link PlatformTransactionManager}, and job
 *       launching infrastructure; declaring {@code @EnableBatchProcessing} would
 *       make that auto-configuration back off (see
 *       {@code com.aws.carddemo.config.BatchConfig}).</li>
 *   <li><strong>Correlation-ID propagation.</strong> The job registers the
 *       {@link CorrelationIdJobListener} so every batch log line carries a
 *       correlation id across the job and step boundaries (Observability rule,
 *       AAP &sect;0.9.5).</li>
 *   <li><strong>No launch at startup.</strong> {@code spring.batch.job.enabled=false}
 *       in {@code application.yml} prevents any job (including this one) from
 *       running when the context starts; it is launched explicitly (by injecting
 *       the {@code cardMasterPrintJob} bean with the auto-configured
 *       {@code JobLauncher}) or by name from the CI/CD workflow, the modern
 *       equivalent of the mainframe JCL scheduler.</li>
 * </ul>
 *
 * @see CorrelationIdJobListener
 * @see Card
 * @see CardRepository
 */
@Configuration("cardMasterPrintJobConfig")
public class CardMasterPrintJob {

    /**
     * SLF4J logger used by the chunk writer to emit each card record, mirroring
     * the COBOL {@code DISPLAY CARD-RECORD} to {@code SYSOUT}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CardMasterPrintJob.class);

    /**
     * Canonical Spring Batch {@code Job} name. Kept as a constant so it stays in
     * lock-step with the bean method name {@link #cardMasterPrintJob} that other
     * components (and the CI/CD launch-by-name trigger) rely upon.
     */
    private static final String JOB_NAME = "cardMasterPrintJob";

    /**
     * Canonical Spring Batch {@code Step} name for the single read-and-print
     * step of this job.
     */
    private static final String STEP_NAME = "cardMasterPrintStep";

    /**
     * Reader name used as the {@code ExecutionContext} key prefix under which the
     * {@link RepositoryItemReader} persists its paging save-state; it must be
     * stable across restarts.
     */
    private static final String READER_NAME = "cardMasterPrintItemReader";

    /**
     * JPA entity property (and therefore repository sort key) that reproduces the
     * VSAM primary key {@code FD-CARD-NUM}. Used to order the sequential scan
     * ascending.
     */
    private static final String CARD_NUM_PROPERTY = "cardNum";

    /**
     * Chunk commit interval and reader page size. A modest value keeps memory
     * bounded while streaming the card master; it doubles as the
     * {@link RepositoryItemReader} page size so a chunk maps to one page read.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Fixed mask emitted in place of the sensitive CVV. The real value is never
     * read or logged, satisfying the "never log the CVV in full" rule
     * (AAP &sect;0.7.3 L1, &sect;0.9.3).
     */
    private static final String CVV_MASK = "***";

    /**
     * Defines the {@code cardMasterPrintJob} batch job: a single-step job that
     * reads and prints the entire card master, with correlation-id logging.
     *
     * @param jobRepository             the auto-configured Spring Batch job
     *                                  repository (never {@code null})
     * @param cardMasterPrintStep       the read-and-print step defined by
     *                                  {@link #cardMasterPrintStep} (never
     *                                  {@code null})
     * @param correlationIdJobListener  the cross-cutting listener that publishes
     *                                  a correlation id into the logging MDC for
     *                                  the job's lifetime (never {@code null})
     * @return the fully built {@link Job}
     */
    @Bean
    public Job cardMasterPrintJob(JobRepository jobRepository,
                                  Step cardMasterPrintStep,
                                  CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(correlationIdJobListener)
                .start(cardMasterPrintStep)
                .build();
    }

    /**
     * Defines the single chunk-oriented step of {@link #cardMasterPrintJob}.
     *
     * <p>The step reads every {@link Card} in ascending {@code cardNum} order and
     * logs each one; it performs no persistence, preserving the read-only nature
     * of {@code CBACT02C}. Both the reader and the writer are created inline (as
     * step-local collaborators rather than shared beans) because they are used by
     * this step alone.</p>
     *
     * <p>The reader is a {@link RepositoryItemReader} backed by the
     * {@link CardRepository}: it invokes the inherited {@code findAll(Pageable)}
     * with a {@code cardNum} ascending sort, reproducing the VSAM ascending
     * primary-key browse of {@code CARDFILE}. The writer logs all card fields
     * with the CVV masked (see {@link #formatCardRecord(Card)}).</p>
     *
     * @param jobRepository      the auto-configured Spring Batch job repository
     *                           (never {@code null})
     * @param transactionManager the auto-configured transaction manager that
     *                           bounds each chunk (never {@code null})
     * @param cardRepository     the Spring Data repository that backs the
     *                           sequential card read (never {@code null})
     * @return the fully built {@link Step}
     */
    @Bean
    public Step cardMasterPrintStep(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager,
                                    CardRepository cardRepository) {

        // Inline reader: sequential ascending-key scan of the card master, backed
        // by CardRepository. findAll(Pageable) is invoked with a cardNum-ascending
        // sort so the browse order matches the VSAM primary key (FD-CARD-NUM).
        final RepositoryItemReader<Card> reader = new RepositoryItemReaderBuilder<Card>()
                .name(READER_NAME)
                .repository(cardRepository)
                .methodName("findAll")
                .pageSize(CHUNK_SIZE)
                .sorts(Map.of(CARD_NUM_PROPERTY, Sort.Direction.ASC))
                .build();

        // Inline writer: log every card field (CVV masked); no persistence, so the
        // step remains strictly read-only just like CBACT02C's DISPLAY loop.
        final ItemWriter<Card> writer = chunk -> {
            for (final Card card : chunk) {
                LOGGER.info("{}", formatCardRecord(card));
            }
        };

        return new StepBuilder(STEP_NAME, jobRepository)
                .<Card, Card>chunk(CHUNK_SIZE, transactionManager)
                .reader(reader)
                .writer(writer)
                .build();
    }

    /**
     * Builds the single-line, log-safe rendering of a card record used by the
     * chunk writer, mirroring the COBOL {@code DISPLAY CARD-RECORD}.
     *
     * <p>All non-sensitive fields (card number, owning account id, embossed name,
     * expiration date, active status) are rendered verbatim. The sensitive CVV is
     * represented only by the fixed mask {@value #CVV_MASK}; the real value is
     * never read here, so it cannot appear in the batch log. This method is
     * package-private and {@code static} to make the masking guarantee directly
     * unit-testable.</p>
     *
     * @param card the card to render (never {@code null})
     * @return a single-line description of the card with the CVV masked
     */
    static String formatCardRecord(Card card) {
        return new StringBuilder(160)
                .append("CBACT02C CARD-RECORD")
                .append(" | cardNum=").append(card.getCardNum())
                .append(" | acctId=").append(card.getAcctId())
                .append(" | embossedName=").append(card.getCardEmbossedName())
                .append(" | expirationDate=").append(card.getCardExpirationDate())
                .append(" | activeStatus=").append(card.getCardActiveStatus())
                .append(" | cvv=").append(CVV_MASK)
                .toString();
    }
}

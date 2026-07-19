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

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.repository.CardXrefRepository;

/**
 * Spring Batch {@link Configuration} that re-platforms the legacy batch program
 * {@code legacy/cbl/CBACT03C.cbl} (source {@code app/cbl/CBACT03C.cbl}),
 * <em>"Read and print account cross reference data file"</em>.
 *
 * <h2>Legacy behavior reproduced (CBACT03C)</h2>
 * <p>The COBOL program opens the indexed VSAM KSDS {@code XREFFILE} (record layout copybook
 * {@code CVACT03Y}, {@code CARD-XREF-RECORD}, fixed length 50 bytes, {@code RECORD KEY IS
 * FD-XREF-CARD-NUM}) as {@code INPUT} with {@code ACCESS MODE IS SEQUENTIAL} (CBACT03C L29-L33),
 * then walks it front-to-back in ascending primary-key order until end-of-file, issuing
 * {@code DISPLAY CARD-XREF-RECORD} for every record (CBACT03C L74-L81, paragraph
 * {@code 1000-XREFFILE-GET-NEXT} L92-L116). On a clean pass the program falls through to
 * {@code GOBACK} with return code {@code 0}; any non end-of-file I/O error routes through
 * {@code 9999-ABEND-PROGRAM} ({@code CALL 'CEE3ABD'}) which abends the job with a non-zero return
 * code (CBACT03C L110-L114, L154-L158).</p>
 *
 * <h2>Target construct mapping</h2>
 * <ul>
 *   <li><strong>Sequential VSAM read &rarr; chunk-oriented {@link Step}.</strong> The
 *       record-at-a-time {@code READ ... NEXT} loop becomes a single {@code <CardXref, CardXref>}
 *       chunk step whose reader streams the {@code card_xref} table (the relational form of
 *       {@code CARDXREF.VSAM.KSDS}) in ascending {@code xref_card_num} order, exactly preserving the
 *       VSAM ascending-key browse.</li>
 *   <li><strong>{@code DISPLAY CARD-XREF-RECORD} &rarr; logging {@link ItemWriter}.</strong> Each
 *       streamed {@link CardXref} is written to the application log with all cross-reference fields
 *       ({@code XREF-CARD-NUM}, {@code XREF-CUST-ID}, {@code XREF-ACCT-ID}); no row is created,
 *       updated, or deleted &mdash; the job is strictly read-only (AAP &sect;0.4.4, &sect;0.5.4).</li>
 *   <li><strong>{@code CEE3ABD} abend / return codes &rarr; framework exit status.</strong> A clean
 *       pass leaves the step and job {@code COMPLETED} (return code {@code 0}); any exception raised
 *       while reading or writing propagates, fails the step, and yields a {@code FAILED} job and a
 *       non-zero return code &mdash; the same caller-visible {@code RC 0} / {@code RC 8} outcome the
 *       COBOL produced. No bespoke error handling is required here because the chunk model already
 *       maps a thrown exception onto a failed batch execution.</li>
 * </ul>
 *
 * <h2>Infrastructure contract</h2>
 * <p>Consistent with every other CardDemo batch configuration, this class does <strong>not</strong>
 * declare {@code @EnableBatchProcessing} and does <strong>not</strong> redeclare any batch
 * infrastructure bean: the {@link JobRepository} and the batch {@link PlatformTransactionManager}
 * are supplied by Spring Boot's batch auto-configuration and injected as method parameters (see
 * {@code com.aws.carddemo.config.BatchConfig} for the full rationale). The job never runs at
 * startup because {@code application.yml} sets {@code spring.batch.job.enabled=false}; it is
 * launched explicitly (by bean injection or by name through the CI/CD workflow &mdash; the modern
 * equivalent of the mainframe JCL scheduler). The {@code correlationIdJobListener} is registered so
 * every log line emitted during the run carries the observability correlation id.</p>
 *
 * @see CardXref
 * @see CardXrefRepository
 * @see CorrelationIdJobListener
 */
@Configuration("xrefPrintJobConfig")
public class XrefPrintJob {

    /**
     * Logger used by the read-only {@link ItemWriter} to emit one line per cross-reference record,
     * the direct analog of the COBOL {@code DISPLAY CARD-XREF-RECORD} statement.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(XrefPrintJob.class);

    /**
     * Chunk size for the step and page size for the reader.
     *
     * <p>The two are kept identical so each transactional chunk corresponds to exactly one reader
     * page, bounding memory while streaming the full cross-reference table. The value comfortably
     * exceeds the seeded {@code card_xref} row count, so the print pass completes in a single page
     * and single commit, while remaining correct for an arbitrarily large table.</p>
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * JPA/entity property name of the cross-reference primary key ({@code XREF-CARD-NUM}, column
     * {@code xref_card_num}). Sorting the reader ascending on this property reproduces the VSAM
     * {@code RECORD KEY} browse order of {@code CBACT03C}.
     */
    private static final String XREF_CARD_NUM_PROPERTY = "xrefCardNum";

    /**
     * {@link org.springframework.data.repository.PagingAndSortingRepository} method invoked by the
     * reader. {@code findAll} is inherited by {@link CardXrefRepository} from
     * {@code JpaRepository}; the {@link RepositoryItemReader} appends a {@code Pageable} carrying the
     * ascending {@code xrefCardNum} sort, so the {@code findAll(Pageable)} overload is invoked.
     */
    private static final String READER_METHOD_NAME = "findAll";

    /**
     * Name assigned to the reader; also its {@code ExecutionContext} state-key prefix. A stable name
     * is required because the reader saves its paging state for restartability.
     */
    private static final String READER_NAME = "xrefPrintItemReader";

    /**
     * Legacy program name printed in the START/END execution banners by
     * {@link ExecutionBannerJobListener}, byte-identical to the CBACT03C {@code DISPLAY} literals
     * ({@code legacy/cbl/CBACT03C.cbl} L71 START / L85 END).
     */
    private static final String PROGRAM_NAME = "CBACT03C";

    /**
     * Defines the {@code xrefPrintJob} batch job: a single-step job that prints the card
     * cross-reference file, reproducing {@code CBACT03C}.
     *
     * <p>The bean name is exactly {@code xrefPrintJob} (from the method name), which is the
     * identifier used to launch the job by name. The {@code correlationIdJobListener} is attached so
     * the correlation id propagates across the batch boundary for the whole execution, and an
     * {@link ExecutionBannerJobListener} for {@code CBACT03C} is attached after it to reproduce the
     * legacy {@code START}/{@code END OF EXECUTION OF PROGRAM CBACT03C} banners (START on entry; END
     * only on normal completion).</p>
     *
     * @param jobRepository             the auto-configured Spring Batch {@link JobRepository};
     *                                  never {@code null}
     * @param xrefPrintStep             the single {@link Step} of this job (see
     *                                  {@link #xrefPrintStep(JobRepository, PlatformTransactionManager, CardXrefRepository)});
     *                                  never {@code null}
     * @param correlationIdJobListener  the cross-cutting listener that publishes the correlation id
     *                                  into the logging context for the run; never {@code null}
     * @return the configured {@link Job}; never {@code null}
     */
    @Bean
    public Job xrefPrintJob(JobRepository jobRepository,
                            Step xrefPrintStep,
                            CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder("xrefPrintJob", jobRepository)
                .listener(correlationIdJobListener)
                .listener(new ExecutionBannerJobListener(PROGRAM_NAME))
                .start(xrefPrintStep)
                .build();
    }

    /**
     * Defines the single chunk-oriented {@link Step} that streams every {@link CardXref} in
     * ascending primary-key order and logs it.
     *
     * <p>The step is {@code <CardXref, CardXref>}: items flow from the reader straight to the writer
     * with no {@code ItemProcessor}, mirroring the COBOL loop that reads a record and immediately
     * displays it. The reader and writer are constructed inline (see
     * {@link #xrefItemReader(CardXrefRepository)} and {@link #xrefItemWriter()}); the framework
     * registers the reader as an {@code ItemStream} and manages its open/update/close lifecycle per
     * execution.</p>
     *
     * @param jobRepository       the auto-configured Spring Batch {@link JobRepository};
     *                            never {@code null}
     * @param transactionManager  the auto-configured batch {@link PlatformTransactionManager}
     *                            that governs each chunk's transaction; never {@code null}
     * @param cardXrefRepository  the Spring Data repository backing the reader; never {@code null}
     * @return the configured {@link Step}; never {@code null}
     */
    @Bean
    public Step xrefPrintStep(JobRepository jobRepository,
                              PlatformTransactionManager transactionManager,
                              CardXrefRepository cardXrefRepository) {
        return new StepBuilder("xrefPrintStep", jobRepository)
                .<CardXref, CardXref>chunk(CHUNK_SIZE, transactionManager)
                .reader(xrefItemReader(cardXrefRepository))
                .writer(xrefItemWriter())
                .build();
    }

    /**
     * Builds the read-only {@link RepositoryItemReader} that streams the {@code card_xref} table in
     * ascending {@code xref_card_num} order, reproducing the sequential VSAM {@code RECORD KEY}
     * browse of {@code CBACT03C}.
     *
     * <p>The reader is backed by {@link CardXrefRepository} and drives its inherited
     * {@code findAll(Pageable)} method: on each page the {@link RepositoryItemReader} constructs a
     * {@code Pageable} carrying the ascending {@link #XREF_CARD_NUM_PROPERTY} sort, so rows are
     * returned in the exact primary-key order the legacy KSDS browse produced. Paging keeps memory
     * bounded, and {@code saveState} is enabled so the reader's position is persisted for
     * restartability.</p>
     *
     * @param cardXrefRepository the repository to page over; never {@code null}
     * @return a fully configured, read-only {@link RepositoryItemReader} over {@link CardXref}
     */
    private RepositoryItemReader<CardXref> xrefItemReader(CardXrefRepository cardXrefRepository) {
        return new RepositoryItemReaderBuilder<CardXref>()
                .name(READER_NAME)
                .repository(cardXrefRepository)
                .methodName(READER_METHOD_NAME)
                .pageSize(CHUNK_SIZE)
                .sorts(Map.of(XREF_CARD_NUM_PROPERTY, Sort.Direction.ASC))
                .saveState(true)
                .build();
    }

    /**
     * Builds the logging {@link ItemWriter} that reproduces {@code DISPLAY CARD-XREF-RECORD}.
     *
     * <p>For every {@link CardXref} in the chunk the writer emits a single log line carrying all
     * three cross-reference fields &mdash; {@code XREF-CARD-NUM}, {@code XREF-CUST-ID}, and
     * {@code XREF-ACCT-ID} &mdash; with field-name labels acting as the separators the COBOL
     * {@code DISPLAY} rendered. The writer performs <strong>no</strong> persistence: {@code CBACT03C}
     * only reads and prints, so this job never inserts, updates, or deletes a row. All three fields
     * are non-sensitive identifiers (see {@link CardXref}), so logging them in full preserves the
     * legacy output without exposing protected data.</p>
     *
     * @return a read-only, logging {@link ItemWriter} over {@link CardXref}
     */
    private ItemWriter<CardXref> xrefItemWriter() {
        return chunk -> {
            for (CardXref cardXref : chunk) {
                LOGGER.info("CARD-XREF-RECORD XREF-CARD-NUM={} XREF-CUST-ID={} XREF-ACCT-ID={}",
                        cardXref.getXrefCardNum(), cardXref.getCustId(), cardXref.getAcctId());
            }
        };
    }
}

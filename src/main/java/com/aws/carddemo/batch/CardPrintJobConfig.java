package com.aws.carddemo.batch;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
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

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.repository.CardRepository;

/**
 * Spring Batch job configuration translating the mainframe batch program {@code CBACT02C}
 * ("Read and print card data file"), orchestrated by JCL job {@code READCARD}.
 *
 * <p>Origin and traceability (AAP &sect;0.4.1, &sect;0.6.3, Explainability rule): this class is the
 * one-for-one Java target of {@code legacy/cbl/CBACT02C.cbl} (source branch
 * {@code app/cbl/CBACT02C.cbl}) as launched by {@code legacy/jcl/READCARD.jcl} (source branch
 * {@code app/jcl/READCARD.jcl}). {@code CBACT02C} is a purely diagnostic, <strong>read-only</strong>
 * verification utility: it opens the VSAM {@code CARDDAT} KSDS ({@code CVACT02Y CARD-RECORD}, record
 * length 150) for {@code INPUT}, walks every record in ascending primary-key ({@code CARD-NUM})
 * order, {@code DISPLAY}s each one, then closes the file and ends. {@code READCARD.jcl} declares only
 * {@code SYSOUT}/{@code SYSPRINT} sinks and <em>no</em> output dataset, so this job likewise performs
 * no writes and produces no output file.</p>
 *
 * <p><strong>COBOL control flow preserved (paragraph mapping).</strong> The COBOL procedure body
 * {@code PERFORM UNTIL END-OF-FILE = 'Y' ... PERFORM 1000-CARDFILE-GET-NEXT ... DISPLAY CARD-RECORD}
 * becomes a chunk-oriented {@code reader -> writer} step:</p>
 * <ul>
 *   <li>{@code 0000-CARDFILE-OPEN} ({@code OPEN INPUT CARDFILE-FILE}) and
 *       {@code 9000-CARDFILE-CLOSE} ({@code CLOSE CARDFILE-FILE}) &mdash; the VSAM file
 *       enable/disable lifecycle &mdash; are subsumed by the Spring-managed datasource and the
 *       reader's {@code open}/{@code close} item-stream callbacks; there is no explicit open/close
 *       paragraph to translate (AAP &sect;0.6.3).</li>
 *   <li>{@code 1000-CARDFILE-GET-NEXT} ({@code READ CARDFILE-FILE INTO CARD-RECORD}) becomes the
 *       {@link #cardPrintReader()} {@link RepositoryItemReader}. FILE STATUS {@code '00'} (record
 *       read) corresponds to the reader returning a {@link Card}; FILE STATUS {@code '10'}
 *       (end-of-file, the COBOL {@code APPL-EOF} normal-termination path that sets
 *       {@code END-OF-FILE = 'Y'}) corresponds to the reader returning {@code null}, which ends the
 *       step normally &mdash; no exception is required for the expected end-of-data condition.</li>
 *   <li>{@code DISPLAY CARD-RECORD} becomes the {@link #cardPrintWriter()} which logs exactly one
 *       structured line per record.</li>
 * </ul>
 *
 * <p><strong>Deterministic ordering (parity with the KSDS base cluster, AAP &sect;0.6.6).</strong>
 * A VSAM {@code SEQUENTIAL} read of a KSDS returns records in ascending primary-key order. The
 * reader therefore sorts by the {@code cardNum} entity property ascending, which the Flyway schema
 * backs with a {@code C}/{@code POSIX} collation on {@code card_num CHAR(16)} so the Java/PostgreSQL
 * ordering reproduces the legacy bytewise ordering.</p>
 *
 * <p><strong>{@code DISPLAY} &rarr; structured logging, with sensitive-data protection.</strong> The
 * original {@code DISPLAY CARD-RECORD} wrote the entire 150-byte record &mdash; including the full
 * primary account number ({@code CARD-NUM}) and the card verification value ({@code CARD-CVV-CD})
 * &mdash; to {@code SYSOUT}. This diagnostic output has no downstream consumer (there is no output
 * dataset), so reproducing its observable business behavior means: read every record in key order,
 * emit one line per record, and terminate normally at end-of-file. It does <em>not</em> require
 * leaking payment credentials into application logs. Consistent with the deliberate CWE-532 posture
 * of {@link Card#toString()} (which never emits the PAN, CVV, or any business field) and with the
 * parity-vs-hygiene resolution recorded for sensitive data (AAP &sect;0.6.7), {@link #cardPrintWriter()}
 * emits a structured line in which the PAN is masked to its last four digits and the CVV is omitted
 * entirely (see {@link #formatCardLine(Card)} / {@link #maskPan(String)}). The full rationale and the
 * rejected alternative of logging the raw record are recorded in {@code docs/decision-log.md}.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint).</strong> This class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies the
 * persistent, restartable {@link JobRepository} and the {@link PlatformTransactionManager} injected
 * through the constructor. The job reads through {@link CardRepository} only; it never mutates data
 * and declares no {@code @Transactional} write boundary.</p>
 *
 * @see com.aws.carddemo.domain.Card
 * @see com.aws.carddemo.repository.CardRepository
 */
@Configuration
public class CardPrintJobConfig {

    /**
     * Logger used by the writer to emit one structured, PAN-masked line per card record &mdash; the
     * {@code DISPLAY CARD-RECORD} equivalent (see class Javadoc).
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CardPrintJobConfig.class);

    /**
     * Chunk size for the step and page size for the paging reader. The value governs only the
     * commit/paging granularity of this read-only scan (no records are ever written), so a modest
     * fixed value keeps memory bounded while limiting the number of {@code SELECT} round-trips.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Number of trailing primary-account-number digits left unmasked when logging, matching the
     * common "last four" convention for safe card display.
     */
    private static final int UNMASKED_PAN_DIGITS = 4;

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding each chunk's (read-only) transaction. */
    private final PlatformTransactionManager transactionManager;

    /** Repository over the {@code CARDDAT} table, replacing the VSAM {@code CARDDAT} KSDS. */
    private final CardRepository cardRepository;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager}
     * @param cardRepository     the Spring Data repository for {@link Card} records
     */
    public CardPrintJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CardRepository cardRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.cardRepository = cardRepository;
    }

    /**
     * Reader reproducing {@code 1000-CARDFILE-GET-NEXT} ({@code READ CARDFILE-FILE INTO CARD-RECORD}
     * against the {@code CARDDAT} KSDS opened {@code INPUT} {@code SEQUENTIAL}).
     *
     * <p>It pages through {@link CardRepository} using the inherited
     * {@code findAll(org.springframework.data.domain.Pageable)} method, sorting by the
     * {@code cardNum} property ascending so records are returned in the same ascending primary-key
     * order a VSAM sequential KSDS read would yield (AAP &sect;0.6.6). When the underlying data is
     * exhausted the reader returns {@code null}, which is the Spring Batch equivalent of COBOL FILE
     * STATUS {@code '10'} (the {@code APPL-EOF} normal-termination path); no {@code EndOfFileException}
     * is needed for that expected condition.</p>
     *
     * <p>The bean is {@code @StepScope} so a fresh, correctly-initialized reader instance is created
     * for each step execution, keeping the job restartable and free of state leakage between runs.</p>
     *
     * @return a paging {@link RepositoryItemReader} over all cards, ordered ascending by
     *         {@code cardNum}
     */
    @Bean
    @StepScope
    public RepositoryItemReader<Card> cardPrintReader() {
        return new RepositoryItemReaderBuilder<Card>()
                .name("cardPrintReader")
                .repository(cardRepository)
                .methodName("findAll")
                .pageSize(CHUNK_SIZE)
                .sorts(Map.of("cardNum", Sort.Direction.ASC))
                .build();
    }

    /**
     * Writer reproducing {@code DISPLAY CARD-RECORD}: it logs exactly one structured, PAN-masked line
     * per card at {@code INFO} level via SLF4J. No records are written to any datastore or file
     * (READCARD has no output dataset), so this is a pure side-effect (logging) writer.
     *
     * <p>The formatted line masks the primary account number to its last four digits and omits the
     * card verification value entirely, honoring the codebase's CWE-532 sensitive-data posture (see
     * class Javadoc and {@link #formatCardLine(Card)}).</p>
     *
     * @return an {@link ItemWriter} that logs one masked line per {@link Card}
     */
    @Bean
    public ItemWriter<Card> cardPrintWriter() {
        return chunk -> {
            for (Card card : chunk) {
                LOGGER.info("{}", formatCardLine(card));
            }
        };
    }

    /**
     * The single chunk-oriented step of {@link #cardPrintJob()}, wiring the {@link #cardPrintReader()}
     * to the {@link #cardPrintWriter()} within the auto-configured transaction boundary. The explicit
     * {@code <Card, Card>} type witness on {@code chunk(...)} keeps the builder fully generic (no raw
     * types), satisfying the zero-warning build.
     *
     * @return the {@code cardPrintStep} {@link Step}
     */
    @Bean
    public Step cardPrintStep() {
        return new StepBuilder("cardPrintStep", jobRepository)
                .<Card, Card>chunk(CHUNK_SIZE, transactionManager)
                .reader(cardPrintReader())
                .writer(cardPrintWriter())
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to legacy JCL job {@code READCARD} / program
     * {@code CBACT02C}. It consists of the single {@link #cardPrintStep()} and completes with batch
     * status {@code COMPLETED} once every card record has been read and logged.
     *
     * @return the {@code cardPrintJob} {@link Job}
     */
    @Bean
    public Job cardPrintJob() {
        return new JobBuilder("cardPrintJob", jobRepository)
                .start(cardPrintStep())
                .build();
    }

    /**
     * Builds the structured, non-sensitive log line emitted for a single card, reproducing the
     * intent of {@code DISPLAY CARD-RECORD} while protecting payment credentials. The primary account
     * number is masked (see {@link #maskPan(String)}) and the card verification value
     * ({@code CARD-CVV-CD}) is deliberately never included (CWE-532; consistent with
     * {@link Card#toString()}).
     *
     * <p>Package-private so it can be exercised directly by unit tests.</p>
     *
     * @param card the card to render; must not be {@code null}
     * @return a single-line, PAN-masked, CVV-free representation of the card
     */
    static String formatCardLine(Card card) {
        return "CARD-RECORD"
                + " card-num=" + maskPan(card.getCardNum())
                + " acct-id=" + card.getCardAcctId()
                + " active-status=" + card.getCardActiveStatus()
                + " expiry-date=" + card.getCardExpiraionDate()
                + " embossed-name=" + card.getCardEmbossedName();
    }

    /**
     * Masks a primary account number for safe logging, revealing only the last
     * {@value #UNMASKED_PAN_DIGITS} characters and replacing every preceding character with
     * {@code '*'}. Leading/trailing whitespace (for example the trailing spaces of a fixed-width
     * {@code CHAR(16)} value) is stripped before masking. Values with {@value #UNMASKED_PAN_DIGITS}
     * or fewer characters are fully masked so no digit is revealed, and a {@code null} value renders
     * as the literal {@code "null"}.
     *
     * <p>Package-private so it can be exercised directly by unit tests.</p>
     *
     * @param pan the raw primary account number ({@code CARD-NUM}), possibly {@code null} or
     *            space-padded
     * @return the masked representation safe to write to logs
     */
    static String maskPan(String pan) {
        if (pan == null) {
            return "null";
        }
        String trimmed = pan.strip();
        int length = trimmed.length();
        if (length <= UNMASKED_PAN_DIGITS) {
            return "*".repeat(length);
        }
        return "*".repeat(length - UNMASKED_PAN_DIGITS)
                + trimmed.substring(length - UNMASKED_PAN_DIGITS);
    }
}

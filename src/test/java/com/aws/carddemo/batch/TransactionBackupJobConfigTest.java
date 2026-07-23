package com.aws.carddemo.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.FixedWidthRecordMapper;
import com.aws.carddemo.util.batch.AtomicFileStepPublisher;
import com.aws.carddemo.util.batch.BatchFilePathResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link TransactionBackupJobConfig}, the Spring Batch configuration that
 * translates the mainframe JCL job {@code TRANBKP.jcl} (an {@code IDCAMS REPRO} of the transaction
 * master to a sequential 350-byte backup) in the AWS CardDemo COBOL&rarr;Java/Spring Boot migration.
 *
 * <p><strong>Oracle:</strong> {@code legacy/cpy/CVTRA05Y.cpy} ({@code TRAN-RECORD}, fixed record
 * length {@code RECLN = 350}) &mdash; 13 data fields followed by a trailing {@code FILLER PIC X(20)}
 * pad. These tests assert the parity-critical guarantees of the backup export:</p>
 * <ul>
 *   <li>every exported record is exactly 350 bytes with byte-correct field positions;</li>
 *   <li>a record round-trips losslessly through {@link FixedWidthRecordMapper}
 *       ({@code parse(encode(x))} reproduces the field values);</li>
 *   <li>monetary values use {@link BigDecimal} with the COBOL trailing-overpunch sign, never
 *       floating point;</li>
 *   <li>the writer emits one 350-byte line per transaction, in the order supplied, terminated by a
 *       single {@code '\n'} (validated against a real temporary file &mdash; no database, no
 *       Testcontainers);</li>
 *   <li>the reader pages the repository ordered ascending by {@code tranId} (KSDS primary-key order),
 *       verified by capturing the {@link Pageable} passed to the repository.</li>
 * </ul>
 *
 * <p>This is a plain JUnit&nbsp;5 unit test: no Spring context, no database, and no Testcontainers.
 * The declarative {@code transactionBackupStep}/{@code transactionBackupJob} beans wire the reader and
 * writer together through Spring's {@code @Configuration}/{@code @StepScope} proxying (which a direct
 * method call cannot reproduce, because the step method intentionally passes a {@code null} job-parameter
 * placeholder); their construction is therefore exercised by the running application context rather than
 * here. The reader, the writer, and the byte-exact record encoding &mdash; the substantive behavior of
 * this configuration &mdash; are fully covered below.</p>
 */
class TransactionBackupJobConfigTest {

    /** Mirrors {@link TransactionBackupJobConfig} timestamp rendering for building expected values. */
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private static final LocalDateTime ORIG_TS = LocalDateTime.of(2022, 7, 18, 9, 5, 3, 123_456_000);
    private static final LocalDateTime PROC_TS = LocalDateTime.of(2022, 7, 18, 9, 5, 4, 0);

    // ------------------------------------------------------------------------------------------
    // Layout / constants
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CVTRA05Y mapper describes the exact 350-byte TRAN-RECORD layout")
    void recordMapperDescribes350ByteLayout() {
        assertThat(TransactionBackupJobConfig.RECORD_LENGTH).isEqualTo(350);
        assertThat(TransactionBackupJobConfig.TRAN_RECORD_MAPPER.getRecordLength()).isEqualTo(350);
        // The mapper's charset must be single-byte so a 350-char string re-encodes to exactly 350 bytes.
        assertThat(TransactionBackupJobConfig.TRAN_RECORD_MAPPER.getCharset())
                .isEqualTo(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------------------------------
    // encodeRecord: byte-exact serialization
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("encodeRecord produces exactly 350 bytes")
    void encodeProducesExactly350Bytes() {
        String line = TransactionBackupJobConfig.encodeRecord(fullyPopulated("0000000000000001"));
        assertThat(line.getBytes(StandardCharsets.ISO_8859_1)).hasSize(350);
    }

    @Test
    @DisplayName("encodeRecord round-trips every field through the CVTRA05Y mapper")
    void encodeRoundTripsAllFields() {
        Transaction tx = fullyPopulated("0000000000000042");

        byte[] bytes = TransactionBackupJobConfig.encodeRecord(tx).getBytes(StandardCharsets.ISO_8859_1);
        FixedWidthRecordMapper.ParsedRecord parsed = TransactionBackupJobConfig.TRAN_RECORD_MAPPER.parse(bytes);

        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_ID)).isEqualTo("0000000000000042");
        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_TYPE_CD)).isEqualTo("01");
        assertThat(parsed.getNumeric(TransactionBackupJobConfig.F_TRAN_CAT_CD)).isEqualTo(5L);
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_SOURCE)).isEqualTo("POS");
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_DESC)).isEqualTo("GROCERY PURCHASE");
        assertThat(parsed.getSignedDecimal(TransactionBackupJobConfig.F_TRAN_AMT))
                .isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(parsed.getNumeric(TransactionBackupJobConfig.F_TRAN_MERCHANT_ID)).isEqualTo(123456789L);
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_MERCHANT_NAME)).isEqualTo("ACME FOODS");
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_MERCHANT_CITY)).isEqualTo("SEATTLE");
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_MERCHANT_ZIP)).isEqualTo("98101");
        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_CARD_NUM)).isEqualTo("4111111111111111");
        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_ORIG_TS)).isEqualTo(TS_FORMATTER.format(ORIG_TS));
        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_PROC_TS)).isEqualTo(TS_FORMATTER.format(PROC_TS));
    }

    @Test
    @DisplayName("encodeRecord places fields at the correct byte offsets and pads FILLER with spaces")
    void encodeUsesCorrectByteOffsets() {
        byte[] bytes = TransactionBackupJobConfig.encodeRecord(fullyPopulated("0000000000000001"))
                .getBytes(StandardCharsets.ISO_8859_1);

        // TRAN-ID occupies bytes 0-15 (offset 0, length 16).
        assertThat(new String(bytes, 0, 16, StandardCharsets.ISO_8859_1)).isEqualTo("0000000000000001");
        // TRAN-TYPE-CD occupies bytes 16-17.
        assertThat(new String(bytes, 16, 2, StandardCharsets.ISO_8859_1)).isEqualTo("01");
        // TRAN-CAT-CD occupies bytes 18-21, zero-padded numeric.
        assertThat(new String(bytes, 18, 4, StandardCharsets.ISO_8859_1)).isEqualTo("0005");
        // The trailing FILLER PIC X(20) occupies bytes 330-349 and defaults to spaces.
        assertThat(new String(bytes, 330, 20, StandardCharsets.ISO_8859_1)).isEqualTo(" ".repeat(20));
    }

    @Test
    @DisplayName("encodeRecord writes null optional fields as COBOL defaults (spaces and zeros)")
    void encodeHandlesNullOptionalFields() {
        Transaction tx = new Transaction();
        tx.setTranId("0000000000000007"); // only the key is populated

        byte[] bytes = TransactionBackupJobConfig.encodeRecord(tx).getBytes(StandardCharsets.ISO_8859_1);
        assertThat(bytes).hasSize(350);

        FixedWidthRecordMapper.ParsedRecord parsed = TransactionBackupJobConfig.TRAN_RECORD_MAPPER.parse(bytes);
        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_ID)).isEqualTo("0000000000000007");
        assertThat(parsed.getNumeric(TransactionBackupJobConfig.F_TRAN_CAT_CD)).isZero();
        assertThat(parsed.getNumeric(TransactionBackupJobConfig.F_TRAN_MERCHANT_ID)).isZero();
        assertThat(parsed.getSignedDecimal(TransactionBackupJobConfig.F_TRAN_AMT))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_DESC)).isEmpty();
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_ORIG_TS)).isEmpty();
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_PROC_TS)).isEmpty();
    }

    @Test
    @DisplayName("encodeRecord preserves the COBOL trailing-overpunch sign for negative and positive amounts")
    void encodeUsesOverpunchSign() {
        Transaction negative = fullyPopulated("0000000000000001");
        negative.setTranAmt(new BigDecimal("-12.34"));
        FixedWidthRecordMapper.ParsedRecord negParsed = TransactionBackupJobConfig.TRAN_RECORD_MAPPER
                .parse(TransactionBackupJobConfig.encodeRecord(negative).getBytes(StandardCharsets.ISO_8859_1));
        assertThat(negParsed.getSignedDecimal(TransactionBackupJobConfig.F_TRAN_AMT))
                .isEqualByComparingTo(new BigDecimal("-12.34"));
        assertThat(negParsed.isNegative(TransactionBackupJobConfig.F_TRAN_AMT)).isTrue();

        Transaction positive = fullyPopulated("0000000000000002");
        positive.setTranAmt(new BigDecimal("12.34"));
        FixedWidthRecordMapper.ParsedRecord posParsed = TransactionBackupJobConfig.TRAN_RECORD_MAPPER
                .parse(TransactionBackupJobConfig.encodeRecord(positive).getBytes(StandardCharsets.ISO_8859_1));
        assertThat(posParsed.getSignedDecimal(TransactionBackupJobConfig.F_TRAN_AMT))
                .isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(posParsed.isNegative(TransactionBackupJobConfig.F_TRAN_AMT)).isFalse();
    }

    @Test
    @DisplayName("encodeRecord rejects a null transaction")
    void encodeRejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> TransactionBackupJobConfig.encodeRecord(null));
    }

    // ------------------------------------------------------------------------------------------
    // Writer: real fixed-width file output
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("writer emits back-to-back 350-byte records (undelimited RECFM=FB), in order, atomically published")
    void writerEmits350ByteRecordsInOrder(@TempDir Path tempDir) throws Exception {
        // A resolver rooted at the test's temp directory: the output path (a direct child) passes
        // safe-root containment (finding #18), and the writer streams into a sibling in-progress temp
        // file that is atomically renamed onto the final path only on step COMPLETED (finding #19).
        AtomicFileStepPublisher publisher = new AtomicFileStepPublisher(
                new BatchFilePathResolver(List.of(tempDir.toString())));
        TransactionBackupJobConfig config = newConfig(publisher);
        Path out = tempDir.resolve("TRANSACT.BKUP");

        Transaction first = fullyPopulated("0000000000000001");
        Transaction second = fullyPopulated("0000000000000002");
        second.setTranAmt(new BigDecimal("-98765.43"));

        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        FlatFileItemWriter<Transaction> writer =
                config.transactionBackupWriter(out.toString(), stepExecution);
        writer.open(new ExecutionContext());
        try {
            writer.write(new Chunk<>(first, second));
        } finally {
            writer.close();
        }
        // The final path does not exist until the step completes and the listener publishes atomically.
        assertThat(Files.exists(out)).isFalse();
        stepExecution.setStatus(BatchStatus.COMPLETED);
        stepExecution.setExitStatus(ExitStatus.COMPLETED);
        publisher.afterStep(stepExecution);

        byte[] all = Files.readAllBytes(out);
        byte[] expected1 = TransactionBackupJobConfig.encodeRecord(first).getBytes(StandardCharsets.ISO_8859_1);
        byte[] expected2 = TransactionBackupJobConfig.encodeRecord(second).getBytes(StandardCharsets.ISO_8859_1);

        // Two 350-byte records, back-to-back, NO delimiter: 2 * 350 = 700 bytes (RECFM=FB, finding #17).
        assertThat(all).hasSize(700);
        assertThat(Arrays.copyOfRange(all, 0, 350)).isEqualTo(expected1);
        assertThat(Arrays.copyOfRange(all, 350, 700)).isEqualTo(expected2);

        // The records appear in the exact order supplied (the reader guarantees tranId order upstream).
        FixedWidthRecordMapper.ParsedRecord line1 = TransactionBackupJobConfig.TRAN_RECORD_MAPPER
                .parse(Arrays.copyOfRange(all, 0, 350));
        FixedWidthRecordMapper.ParsedRecord line2 = TransactionBackupJobConfig.TRAN_RECORD_MAPPER
                .parse(Arrays.copyOfRange(all, 350, 700));
        assertThat(line1.getText(TransactionBackupJobConfig.F_TRAN_ID)).isEqualTo("0000000000000001");
        assertThat(line2.getText(TransactionBackupJobConfig.F_TRAN_ID)).isEqualTo("0000000000000002");
    }

    @Test
    @DisplayName("writer requires the outputPath job parameter")
    void writerRequiresOutputPath() {
        TransactionBackupJobConfig config = newConfig();
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        assertThatNullPointerException()
                .isThrownBy(() -> config.transactionBackupWriter(null, stepExecution));
    }

    // ------------------------------------------------------------------------------------------
    // Reader: paging ordered by tranId ascending (KSDS primary-key order)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("reader pages the repository ordered ascending by tranId")
    void readerPagesOrderedByTranId() throws Exception {
        JobRepository jobRepository = mock(JobRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionRepository repository = mock(TransactionRepository.class);

        Transaction only = fullyPopulated("0000000000000001");
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(only)));

        TransactionBackupJobConfig config =
                new TransactionBackupJobConfig(jobRepository, transactionManager, repository,
                        new AtomicFileStepPublisher(new BatchFilePathResolver(
                                List.of(System.getProperty("java.io.tmpdir")))));
        RepositoryItemReader<Transaction> reader = config.transactionBackupReader();

        reader.open(new ExecutionContext());
        Transaction read;
        try {
            read = reader.read();
        } finally {
            reader.close();
        }

        assertThat(read).isNotNull();
        assertThat(read.getTranId()).isEqualTo("0000000000000001");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        // The reader fetches page 0 on the first read; capture that request and assert its sort/paging.
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce())
                .findAll(pageableCaptor.capture());
        Pageable firstPage = pageableCaptor.getAllValues().get(0);
        assertThat(firstPage.getPageNumber()).isZero();
        assertThat(firstPage.getPageSize()).isEqualTo(100);
        Sort.Order tranIdOrder = firstPage.getSort().getOrderFor("tranId");
        assertThat(tranIdOrder).isNotNull();
        assertThat(tranIdOrder.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    // ------------------------------------------------------------------------------------------
    // Step / Job wiring
    // ------------------------------------------------------------------------------------------

    /**
     * The declarative {@code transactionBackupStep}/{@code transactionBackupJob} beans compose the
     * {@code @StepScope} reader and writer through Spring's {@code @Configuration} CGLIB proxying (the
     * step method deliberately passes a {@code null} placeholder for the job-parameter-bound writer,
     * which the scoped proxy replaces at step-execution time). This exercises that wiring in a real but
     * database-free application context: no {@code DataSource}, no Flyway, no Testcontainers.
     */
    @Test
    @DisplayName("step and job beans wire together under @StepScope proxying, without a database")
    void stepAndJobBeansWireTogether() {
        new ApplicationContextRunner()
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(TransactionRepository.class, () -> mock(TransactionRepository.class))
                .withBean(AtomicFileStepPublisher.class, () -> new AtomicFileStepPublisher(
                        new BatchFilePathResolver(List.of(System.getProperty("java.io.tmpdir")))))
                .withUserConfiguration(StepScopeConfiguration.class, TransactionBackupJobConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Job.class);
                    assertThat(context.getBean(TransactionBackupJobConfig.JOB_NAME, Job.class).getName())
                            .isEqualTo("transactionBackupJob");
                    assertThat(context.getBean(TransactionBackupJobConfig.STEP_NAME, Step.class).getName())
                            .isEqualTo("transactionBackupStep");
                });
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /**
     * Registers Spring Batch's {@code step} scope so the {@code @StepScope} reader/writer beans resolve
     * to lazy scoped proxies, allowing {@code transactionBackupStep}/{@code transactionBackupJob} to be
     * built inside a database-free application context.
     *
     * <p><b>Intentionally not {@code @Configuration}.</b> This helper is consumed only via
     * {@link ApplicationContextRunner#withUserConfiguration} in {@link #stepAndJobBeansWireTogether()},
     * where its {@code static @Bean} is still processed in configuration <em>lite</em> mode (a class
     * with {@code @Bean} methods is a lite candidate). It must <strong>not</strong> carry a
     * {@code @Component}/{@code @Configuration} stereotype: this class lives in a package that the
     * full-application {@code @SpringBootTest} integration tests component-scan, and a scannable
     * {@code stepScope} bean here would collide with Spring Batch's own
     * {@code ScopeConfiguration.stepScope()} ({@code BeanDefinitionOverrideException}), breaking every
     * full-context integration test.</p>
     */
    static class StepScopeConfiguration {
        @Bean
        public static StepScope stepScope() {
            return new StepScope();
        }
    }

    private static TransactionBackupJobConfig newConfig() {
        return newConfig(new AtomicFileStepPublisher(
                new BatchFilePathResolver(List.of(System.getProperty("java.io.tmpdir")))));
    }

    private static TransactionBackupJobConfig newConfig(AtomicFileStepPublisher publisher) {
        return new TransactionBackupJobConfig(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                mock(TransactionRepository.class),
                publisher);
    }

    private static Transaction fullyPopulated(String tranId) {
        Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setTranTypeCd("01");
        tx.setTranCatCd(5);
        tx.setTranSource("POS");
        tx.setTranDesc("GROCERY PURCHASE");
        tx.setTranAmt(new BigDecimal("1234.56"));
        tx.setMerchantId(123456789L);
        tx.setMerchantName("ACME FOODS");
        tx.setMerchantCity("SEATTLE");
        tx.setMerchantZip("98101");
        tx.setCardNum("4111111111111111");
        tx.setOrigTs(ORIG_TS);
        tx.setProcTs(PROC_TS);
        return tx;
    }
}

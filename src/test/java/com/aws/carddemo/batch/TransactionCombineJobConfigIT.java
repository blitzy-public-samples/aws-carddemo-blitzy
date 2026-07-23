package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.config.BatchConfig;
import com.aws.carddemo.util.FixedWidthRecordMapper;
import com.aws.carddemo.util.FixedWidthRecordMapper.FieldDef;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * End-to-end Spring Batch integration / parity test for {@link TransactionCombineJobConfig}, launching
 * the real {@code transactionCombineJob} against a live PostgreSQL database (Testcontainers) and
 * asserting it reproduces the mainframe transaction-combine/sort behavior byte-for-byte.
 *
 * <p><strong>Origin and provenance (AAP &sect;0.6.10).</strong> The job under test migrates three
 * retained, read-only legacy sources:</p>
 * <ul>
 *   <li>{@code legacy/jcl/COMBTRAN.jcl} &mdash; its {@code STEP05R} runs {@code PGM=SORT} over the
 *       concatenation of the transaction backup file ({@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)}) and the
 *       system-generated transaction file ({@code AWS.M2.CARDDEMO.SYSTRAN(0)}), using
 *       {@code SYMNAMES TRAN-ID,1,16,CH} and {@code SORT FIELDS=(TRAN-ID,A)} &mdash; i.e. an ascending
 *       sort on the 16-byte {@code TRAN-ID} key at position 1, treated as character data &mdash; into
 *       the combined output ({@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)}); its {@code STEP10} then
 *       runs {@code PGM=IDCAMS} {@code REPRO} to load the combined file into the {@code TRANSACT}
 *       KSDS;</li>
 *   <li>{@code legacy/ctl/REPROCT.ctl} &mdash; the generic IDCAMS control statement
 *       {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}; and</li>
 *   <li>{@code legacy/proc/REPROC.prc} &mdash; the cataloged IDCAMS {@code REPRO} procedure wrapping
 *       {@code REPROCT.ctl}.</li>
 * </ul>
 *
 * <p><strong>Collation parity &mdash; the headline concern (AAP &sect;0.6.6).</strong> EBCDIC/ASCII
 * and locale-sensitive collations order mixed digit/letter and mixed-case keys differently. The legacy
 * {@code SORT FIELDS=(TRAN-ID,A)} ordering is reproduced by comparing the raw {@code TRAN-ID} bytes
 * <em>bytewise</em> (a {@code C} / {@code POSIX} collation), never through a locale-sensitive
 * collator. This test deliberately uses a fixture whose bytewise ordering differs from the JVM's
 * {@link Collator} ordering for {@link Locale#US}, then proves the job emits the <em>bytewise</em>
 * order &mdash; so it fails loudly if a locale collation ever creeps in.</p>
 *
 * <p><strong>Records.</strong> Each record is the 350-byte {@code CVTRA05Y} {@code TRAN-RECORD} layout
 * ({@code legacy/cpy/CVTRA05Y.cpy}). Test records are assembled with a {@link FixedWidthRecordMapper}
 * that mirrors that layout exactly, so the monetary {@code TRAN-AMT PIC S9(09)V99} field is a signed
 * decimal backed by {@link BigDecimal}; no {@code float}/{@code double} is used anywhere.</p>
 *
 * <p><strong>Harness.</strong> This test extends {@link AbstractPostgresIntegrationTest}, inheriting the
 * single shared {@code postgres:18-alpine} Testcontainers database, the {@code test} profile, and the
 * {@code @DynamicPropertySource} that binds the container's live JDBC coordinates. It deliberately does
 * <em>not</em> use {@code @SpringBatchTest}: a single {@link JobLauncherTestUtils} is supplied by the
 * nested {@link TransactionCombineJobTestConfig} and bound to the {@code transactionCombineJob} bean via
 * an explicit {@link Qualifier}. The slice imports only the combine job, so the binding is already
 * unambiguous; the qualifier states the target explicitly and keeps the binding correct even if further
 * {@link Job} beans are ever added to the slice. Job auto-execution at startup is disabled by the
 * {@code test} profile
 * ({@code spring.batch.job.enabled=false}), so the context boots without running any job and the test
 * launches the target job explicitly.</p>
 *
 * <p><strong>Context slice &mdash; why the ORM layer is excluded.</strong> The
 * {@link TransactionCombineJobConfig} under test is entirely <em>file-based</em>: it reads two 350-byte
 * flat files, merges and globally bytewise-sorts them, and writes a 350-byte flat file. It never touches
 * JPA, an {@code EntityManager}, or any {@code @Entity} &mdash; its only collaborators are the
 * Spring Batch {@link JobRepository} and a {@link org.springframework.transaction.PlatformTransactionManager},
 * both backed by the container's {@code DataSource}. Accordingly this test pins its context to the nested
 * {@link TransactionCombineJobTestConfig} via {@code @SpringBootTest(classes = ...)} and excludes
 * {@link HibernateJpaAutoConfiguration} and {@link JpaRepositoriesAutoConfiguration}. This keeps the
 * slice minimal and correct for a file-based job: {@code DataSource}, Flyway (whose {@code V0} migration
 * materialises the {@code BATCH_*} metadata tables) and Spring Batch auto-configuration remain active so
 * the {@link JobRepository} runs against the real PostgreSQL container, while the ORM layer &mdash; which
 * this job does not exercise &mdash; is not loaded. Pinning an explicit configuration also makes the
 * context source deterministic rather than depending on {@code @SpringBootConfiguration} package
 * discovery.</p>
 *
 * <p>This end-to-end test complements the in-process unit test {@link TransactionCombineJobConfigTest},
 * which exercises the behavioral core directly without a Spring context or database.</p>
 *
 * @see TransactionCombineJobConfig
 * @see TransactionCombineJobConfigTest
 */
// Non-web batch parity slice: WebEnvironment.NONE suppresses the servlet security auto-config
// generated dev-password WARN, and disabling Prometheus export lets the slice fall back to a
// SimpleMeterRegistry so Spring Batch's duplicate spring.batch.job.active meter never trips the
// Prometheus same-tag-keys collision WARN — keeps start logs warning-free (review finding #35).
@SpringBootTest(classes = TransactionCombineJobConfigIT.TransactionCombineJobTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "management.prometheus.metrics.export.enabled=false")
class TransactionCombineJobConfigIT extends AbstractPostgresIntegrationTest {

    /** Fixed {@code CVTRA05Y} record length in bytes (mirrors {@link TransactionCombineJobConfig#RECORD_LENGTH}). */
    private static final int RECORD_LENGTH = TransactionCombineJobConfig.RECORD_LENGTH;

    /** Byte width of the {@code TRAN-ID} sort key (bytes 1-16 of the record). */
    private static final int TRAN_ID_LENGTH = 16;

    /**
     * Single-byte charset used to read the combined output back into fixed-width lines. It matches the
     * charset the job writes with ({@link FixedWidthRecordMapper}'s default {@link StandardCharsets#ISO_8859_1}),
     * so every byte value {@code 0x00}-{@code 0xFF} round-trips losslessly and record widths are measured
     * in encoded bytes.
     */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * Fifteen-character suffix appended to a single discriminating first character to form each 16-byte
     * {@code TRAN-ID}. The suffix is identical for every key, so ordering is decided entirely by the
     * first character &mdash; making both the bytewise and the locale orderings trivial to reason about.
     */
    private static final String KEY_SUFFIX = "PARITYKEY000001";

    /**
     * Mapper mirroring the production 350-byte {@code CVTRA05Y} {@code TRAN-RECORD} layout (field widths
     * sum to {@code RECORD_LENGTH}: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20).
     * Used to assemble byte-exact test records and to parse the combined output back.
     */
    private static final FixedWidthRecordMapper RECORD_MAPPER = FixedWidthRecordMapper.of(
            FieldDef.text("TRAN-ID", 16),
            FieldDef.text("TRAN-TYPE-CD", 2),
            FieldDef.numeric("TRAN-CAT-CD", 4),
            FieldDef.text("TRAN-SOURCE", 10),
            FieldDef.text("TRAN-DESC", 100),
            FieldDef.signedDecimal("TRAN-AMT", 11, 2),
            FieldDef.numeric("TRAN-MERCHANT-ID", 9),
            FieldDef.text("TRAN-MERCHANT-NAME", 50),
            FieldDef.text("TRAN-MERCHANT-CITY", 50),
            FieldDef.text("TRAN-MERCHANT-ZIP", 10),
            FieldDef.text("TRAN-CARD-NUM", 16),
            FieldDef.text("TRAN-ORIG-TS", 26),
            FieldDef.text("TRAN-PROC-TS", 26),
            FieldDef.filler(20));

    /**
     * Bytewise ({@code C} / {@code POSIX}) comparator over the raw {@code TRAN-ID} bytes, mirroring the
     * production job's {@link Arrays#compareUnsigned(byte[], byte[])} ordering. For pure-ASCII keys this
     * is identical to {@link String#compareTo(String)}; it is expressed over encoded bytes to match the
     * job semantics precisely.
     */
    private static final Comparator<String> BYTEWISE =
            (left, right) -> Arrays.compareUnsigned(
                    left.getBytes(RECORD_CHARSET), right.getBytes(RECORD_CHARSET));

    /**
     * The transaction backup input ({@code SORTIN} DD 1), deliberately unsorted. First characters
     * {@code z, B, a, 9} span lowercase, uppercase and digits.
     */
    private static final Map<String, BigDecimal> BACKUP_INPUT = backupFixture();

    /**
     * The system-transaction input ({@code SORTIN} DD 2), deliberately unsorted. First characters
     * {@code A, 0, b, Z} interleave with the backup set so neither file nor the concatenation is
     * pre-ordered.
     */
    private static final Map<String, BigDecimal> SYSTEM_INPUT = systemFixture();

    /** Union of both inputs: every {@code TRAN-ID} that must appear exactly once in the combined output. */
    private static final Map<String, BigDecimal> EXPECTED_AMOUNTS = expectedAmounts();

    /**
     * The bytewise-ascending sequence of the eight fixture first characters. Bytewise groups all
     * uppercase before any lowercase ({@code '0' < '9' < 'A' < 'B' < 'Z' < 'a' < 'b' < 'z'}); this is an
     * environment-independent oracle because it is defined purely by ASCII byte values.
     */
    private static final String EXPECTED_BYTEWISE_FIRST_CHARS = "09ABZabz";

    /** Per-test temporary directory for the input and output dataset files (managed by JUnit 5). */
    @TempDir
    Path tempDir;

    /** The single {@link JobLauncherTestUtils} bound to {@code transactionCombineJob} (see nested config). */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * The pinned context configuration for this test: a minimal, JPA-free Spring Batch slice.
     *
     * <p>{@link Import}s the production {@link TransactionCombineJobConfig} so the real
     * {@code transactionCombineJob} bean is present, together with {@link BatchConfig} which supplies the
     * shared {@code batchFilePathResolver} the combine tasklet now depends on for safe-root input
     * resolution and atomic output publication (finding #18), and enables Spring Boot auto-configuration
     * with the ORM layer excluded ({@link HibernateJpaAutoConfiguration}, {@link JpaRepositoriesAutoConfiguration}).
     * What remains &mdash; {@code DataSource}, Flyway and Spring Batch auto-configuration &mdash; is
     * exactly what a file-based batch job needs: Flyway's {@code V0} migration creates the {@code BATCH_*}
     * metadata tables in the container and Spring Batch builds a JDBC-backed {@link JobRepository},
     * {@link JobLauncher} and {@link org.springframework.transaction.PlatformTransactionManager} over the
     * Testcontainers {@code DataSource}. The ORM layer is not loaded because this job never uses it (see
     * the class Javadoc). This is a plain {@code @Configuration} (not {@code @SpringBootConfiguration}) so
     * it never participates in package-level configuration discovery for other tests.</p>
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class
    })
    @Import({TransactionCombineJobConfig.class, BatchConfig.class})
    static class TransactionCombineJobTestConfig {

        /**
         * Supplies exactly one {@link JobLauncherTestUtils}, bound to the {@code transactionCombineJob}
         * bean via {@link Qualifier}, using the auto-configured {@link JobLauncher} and
         * {@link JobRepository}. The explicit {@link Qualifier} names the target job so the binding is
         * unambiguous and self-documenting even though the slice imports only the combine job.
         *
         * @param jobLauncher            the auto-configured Spring Batch {@link JobLauncher}
         * @param jobRepository          the auto-configured Spring Batch {@link JobRepository}
         * @param transactionCombineJob  the {@code transactionCombineJob} bean (selected by qualifier)
         * @return a launcher utility wired to the combine job
         */
        @Bean
        JobLauncherTestUtils transactionCombineJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier(TransactionCombineJobConfig.JOB_NAME) Job transactionCombineJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(transactionCombineJob);
            return utils;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    /**
     * The job launches successfully and completes: {@link BatchStatus#COMPLETED} and
     * {@link ExitStatus#COMPLETED}, having combined both inputs into a single output containing every
     * record.
     *
     * @throws Exception if the job cannot be launched or the output cannot be read
     */
    @Test
    void jobCompletes_combinesAndSorts() throws Exception {
        CombineResult result = runStandardCombine();

        assertThat(result.execution().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(result.execution().getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(outputLines(result.output()))
                .as("the combined output must contain every input record")
                .hasSize(EXPECTED_AMOUNTS.size());
    }

    /**
     * Byte-parity: every combined output record is exactly {@code RECORD_LENGTH} (350) bytes wide, and
     * the file contains exactly one line per input record.
     *
     * @throws Exception if the job cannot be launched or the output cannot be read
     */
    @Test
    void everyOutputLineIs350Bytes() throws Exception {
        CombineResult result = runStandardCombine();
        assertThat(result.execution().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = outputLines(result.output());
        assertThat(lines).hasSize(EXPECTED_AMOUNTS.size());
        for (String line : lines) {
            assertThat(line.getBytes(RECORD_CHARSET))
                    .as("every combined output record must be exactly %d bytes", RECORD_LENGTH)
                    .hasSize(RECORD_LENGTH);
        }
    }

    /**
     * Headline collation test: the combined output is strictly ascending by the 16-character
     * {@code TRAN-ID}, compared <em>byte-by-byte</em> ({@code C} / {@code POSIX} collation), reproducing
     * {@code SORT FIELDS=(TRAN-ID,A)}. The emitted order must equal the bytewise-sorted order and the
     * environment-independent oracle sequence {@code "09ABZabz"}.
     *
     * @throws Exception if the job cannot be launched or the output cannot be read
     */
    @Test
    void outputSortedAscendingByTranId_bytewiseCPosixCollation() throws Exception {
        CombineResult result = runStandardCombine();
        assertThat(result.execution().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> emitted = tranIdsInOrder(result.output());

        List<String> bytewiseExpected = new ArrayList<>(EXPECTED_AMOUNTS.keySet());
        bytewiseExpected.sort(BYTEWISE);

        assertThat(emitted)
                .as("combined output must be ordered by raw TRAN-ID bytes (C/POSIX collation)")
                .containsExactlyElementsOf(bytewiseExpected);
        assertThat(firstChars(emitted))
                .as("first-character order must be the bytewise ASCII sequence")
                .isEqualTo(EXPECTED_BYTEWISE_FIRST_CHARS);
        assertStrictlyAscendingBytewise(emitted);
    }

    /**
     * Proves the fixture genuinely distinguishes bytewise ordering from a locale collation and that the
     * job emits the bytewise order (bytewise wins). The robust, environment-independent distinguisher is
     * that bytewise places the uppercase {@code 'Z'} key before the lowercase {@code 'a'} key
     * ({@code 0x5A < 0x61}), whereas any case-insensitive-primary locale collator places {@code 'a'}
     * before {@code 'Z'}.
     *
     * @throws Exception if the job cannot be launched or the output cannot be read
     */
    @Test
    void bytewiseOrderDiffersFromLocaleOrder_andBytewiseWins() throws Exception {
        List<String> keys = new ArrayList<>(EXPECTED_AMOUNTS.keySet());

        List<String> bytewiseOrder = new ArrayList<>(keys);
        bytewiseOrder.sort(BYTEWISE);

        List<String> localeOrder = new ArrayList<>(keys);
        localeOrder.sort(Collator.getInstance(Locale.US));

        // The fixture must actually distinguish the two collations, otherwise the test proves nothing.
        assertThat(localeOrder)
                .as("fixture must distinguish bytewise (C/POSIX) ordering from locale collation")
                .isNotEqualTo(bytewiseOrder);

        String upperZ = tranId('Z');
        String lowerA = tranId('a');
        assertThat(bytewiseOrder.indexOf(upperZ))
                .as("bytewise places uppercase 'Z' before lowercase 'a'")
                .isLessThan(bytewiseOrder.indexOf(lowerA));
        assertThat(localeOrder.indexOf(lowerA))
                .as("a locale collator places lowercase 'a' before uppercase 'Z'")
                .isLessThan(localeOrder.indexOf(upperZ));

        // The job must emit the bytewise order, never the locale order.
        CombineResult result = runStandardCombine();
        assertThat(result.execution().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> emitted = tranIdsInOrder(result.output());
        assertThat(emitted)
                .as("the combine job must emit the bytewise (C/POSIX) order")
                .isEqualTo(bytewiseOrder)
                .isNotEqualTo(localeOrder);
    }

    /**
     * Completeness and monetary fidelity: every input record appears in the output exactly once (no
     * drops, no duplicates from the merge), the written count equals the sum of the two input counts,
     * and each record's {@code TRAN-AMT} round-trips as the exact {@link BigDecimal} it was written with
     * (including a negative amount, exercising the trailing overpunch sign).
     *
     * @throws Exception if the job cannot be launched or the output cannot be read
     */
    @Test
    void allInputRecordsPresentExactlyOnce() throws Exception {
        CombineResult result = runStandardCombine();
        assertThat(result.execution().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = outputLines(result.output());
        assertThat(lines)
                .as("written count must equal the sum of the two input counts")
                .hasSize(BACKUP_INPUT.size() + SYSTEM_INPUT.size());

        List<String> emittedIds = tranIdsInOrder(result.output());
        assertThat(emittedIds)
                .as("every input record must appear exactly once in the combined output")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_AMOUNTS.keySet())
                .doesNotHaveDuplicates();

        for (String line : lines) {
            String id = line.substring(0, TRAN_ID_LENGTH);
            assertThat(amountOf(line))
                    .as("TRAN-AMT must be preserved as a BigDecimal for %s", id)
                    .isEqualByComparingTo(EXPECTED_AMOUNTS.get(id));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Writes both inputs to the temp directory, launches {@code transactionCombineJob} with the three
     * dataset-path job parameters plus a unique {@code run.id}, and returns the execution and output
     * path.
     *
     * @return the job execution and the combined-output path
     * @throws Exception if the fixtures cannot be written or the job cannot be launched
     */
    private CombineResult runStandardCombine() throws Exception {
        Path backup = writeInput("backup.dat", BACKUP_INPUT);
        Path system = writeInput("system.dat", SYSTEM_INPUT);
        Path output = tempDir.resolve("combined.dat");

        JobParameters parameters = new JobParametersBuilder()
                .addString(TransactionCombineJobConfig.PARAM_BACKUP_INPUT, backup.toString())
                .addString(TransactionCombineJobConfig.PARAM_SYSTEM_INPUT, system.toString())
                .addString(TransactionCombineJobConfig.PARAM_COMBINED_OUTPUT, output.toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(parameters);
        return new CombineResult(execution, output);
    }

    /**
     * Writes the given records to a strict undelimited {@code RECFM=FB} 350-byte fixed-width file in the
     * temp directory (contiguous 350-byte records, no record delimiter), matching the framing the combine
     * job reads and writes (finding #17).
     *
     * @param fileName the file name within {@link #tempDir}
     * @param records  the ordered {@code TRAN-ID -> TRAN-AMT} records to write
     * @return the written file path
     * @throws IOException if the file cannot be written
     */
    private Path writeInput(String fileName, Map<String, BigDecimal> records) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (Map.Entry<String, BigDecimal> entry : records.entrySet()) {
            buffer.writeBytes(buildRecord(entry.getKey(), entry.getValue()));
        }
        Path file = tempDir.resolve(fileName);
        Files.write(file, buffer.toByteArray());
        return file;
    }

    /**
     * Assembles a byte-exact 350-byte {@code CVTRA05Y} record carrying the given key and amount; all
     * other fields take their COBOL defaults (spaces / zeros / positive-zero overpunch), so no record
     * ever contains a raw line-feed or carriage-return byte.
     *
     * @param tranId the 16-character {@code TRAN-ID}
     * @param amount the {@code TRAN-AMT} value (scale 2)
     * @return the 350-byte record image
     */
    private static byte[] buildRecord(String tranId, BigDecimal amount) {
        return RECORD_MAPPER.newRecord()
                .setText("TRAN-ID", tranId)
                .setSignedDecimal("TRAN-AMT", amount)
                .build();
    }

    /**
     * Reads the combined output as fixed-width records by slicing the undelimited {@code RECFM=FB} image
     * into contiguous 350-byte blocks (finding #17): the output carries no record delimiter, so it is
     * read by length rather than by line. The file length must be an exact multiple of the record length
     * (which also proves no stray delimiter byte was written), and each slice is decoded with the
     * single-byte record charset so every byte round-trips losslessly.
     *
     * @param output the combined-output path
     * @return the output records (each a 350-character record)
     * @throws IOException if the file cannot be read or its length is not a multiple of the record length
     */
    private static List<String> outputLines(Path output) throws IOException {
        byte[] all = Files.readAllBytes(output);
        if (all.length % RECORD_LENGTH != 0) {
            throw new IOException("combined output length " + all.length
                    + " is not a multiple of the " + RECORD_LENGTH + "-byte record length "
                    + "(expected an undelimited RECFM=FB image with no record delimiter)");
        }
        List<String> records = new ArrayList<>(all.length / RECORD_LENGTH);
        for (int offset = 0; offset < all.length; offset += RECORD_LENGTH) {
            records.add(new String(all, offset, RECORD_LENGTH, RECORD_CHARSET));
        }
        return records;
    }

    /**
     * Extracts the {@code TRAN-ID} (bytes 1-16) of each output record, in emitted order.
     *
     * @param output the combined-output path
     * @return the ordered list of transaction ids
     * @throws IOException if the file cannot be read
     */
    private static List<String> tranIdsInOrder(Path output) throws IOException {
        List<String> ids = new ArrayList<>();
        for (String line : outputLines(output)) {
            ids.add(line.substring(0, TRAN_ID_LENGTH));
        }
        return ids;
    }

    /**
     * Decodes the {@code TRAN-AMT} of a single output line back to a {@link BigDecimal}, proving the
     * monetary field survives the combine.
     *
     * @param line one 350-character output record
     * @return the decoded signed decimal amount
     */
    private static BigDecimal amountOf(String line) {
        return RECORD_MAPPER.parse(line.getBytes(RECORD_CHARSET)).getSignedDecimal("TRAN-AMT");
    }

    /**
     * Concatenates the first character of every id, yielding a compact, human-readable ordering oracle.
     *
     * @param ids the ordered transaction ids
     * @return the first characters in order
     */
    private static String firstChars(List<String> ids) {
        StringBuilder builder = new StringBuilder(ids.size());
        for (String id : ids) {
            builder.append(id.charAt(0));
        }
        return builder.toString();
    }

    /**
     * Asserts the ids are strictly ascending under the bytewise comparator (no equal or descending
     * adjacent pair).
     *
     * @param ids the ordered transaction ids
     */
    private static void assertStrictlyAscendingBytewise(List<String> ids) {
        for (int i = 1; i < ids.size(); i++) {
            assertThat(BYTEWISE.compare(ids.get(i - 1), ids.get(i)))
                    .as("records must be strictly ascending by TRAN-ID (bytewise) at index %d", i)
                    .isNegative();
        }
    }

    /**
     * Builds a 16-character {@code TRAN-ID} from a single discriminating first character and the shared
     * suffix.
     *
     * @param first the discriminating first character
     * @return the 16-character transaction id
     */
    private static String tranId(char first) {
        return first + KEY_SUFFIX;
    }

    private static Map<String, BigDecimal> backupFixture() {
        Map<String, BigDecimal> records = new LinkedHashMap<>();
        records.put(tranId('z'), new BigDecimal("8.80"));
        records.put(tranId('B'), new BigDecimal("-12.34"));
        records.put(tranId('a'), new BigDecimal("0.01"));
        records.put(tranId('9'), new BigDecimal("250.55"));
        return records;
    }

    private static Map<String, BigDecimal> systemFixture() {
        Map<String, BigDecimal> records = new LinkedHashMap<>();
        records.put(tranId('A'), new BigDecimal("504.77"));
        records.put(tranId('0'), new BigDecimal("100.00"));
        records.put(tranId('b'), new BigDecimal("76543.21"));
        records.put(tranId('Z'), new BigDecimal("999999999.99"));
        return records;
    }

    private static Map<String, BigDecimal> expectedAmounts() {
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        merged.putAll(BACKUP_INPUT);
        merged.putAll(SYSTEM_INPUT);
        return merged;
    }

    /**
     * A launched combine execution paired with its combined-output path.
     *
     * @param execution the job execution
     * @param output    the combined-output file path
     */
    private record CombineResult(JobExecution execution, Path output) {
    }
}

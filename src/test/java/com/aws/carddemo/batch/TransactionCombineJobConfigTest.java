package com.aws.carddemo.batch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.util.batch.BatchFilePathResolver;

/**
 * Pure JUnit&#160;5 unit test for {@link TransactionCombineJobConfig}, the Spring Batch translation of
 * the mainframe transaction-combine job {@code COMBTRAN} ({@code legacy/jcl/COMBTRAN.jcl} +
 * {@code legacy/ctl/REPROCT.ctl} + {@code legacy/proc/REPROC.prc}).
 *
 * <p>This test constructs no Spring context, starts no database, and uses no Testcontainers; it
 * exercises the behavioral core of the job &mdash; the package-visible static
 * {@link TransactionCombineJobConfig#combineAndSort(Path, Path, Path)} and
 * {@link TransactionCombineJobConfig#splitRecords(byte[])} &mdash; directly, over small
 * {@code CVTRA05Y} (350-byte) fixtures written to a {@link TempDir}.</p>
 *
 * <p><b>What it proves (AAP &sect;0.6.6, &sect;0.6.4):</b></p>
 * <ul>
 *   <li>the combined output is ordered ascending by {@code TRAN-ID} using <em>bytewise</em>
 *       ({@code C}/{@code POSIX}) collation, and that this differs from the JVM's locale-sensitive
 *       {@link Collator} ordering of the same keys (the {@code SORT FIELDS=(TRAN-ID,A)} parity risk);</li>
 *   <li>every combined record is exactly 350 bytes, both inputs are fully represented, and the written
 *       count equals the sum of the input counts;</li>
 *   <li>each record's 350 bytes are preserved verbatim (byte-exact), including trailing content;</li>
 *   <li>the concatenation order (backup before system) is preserved for equal keys (stable sort),
 *       including across multiple spilled runs of the external merge sort;</li>
 *   <li>the record framing is strictly undelimited {@code RECFM=FB} (contiguous 350-byte blocks, no
 *       delimiter assumed or written), a line-feed byte inside a record is treated as ordinary content
 *       rather than a delimiter, and truncated/wrong-length inputs are rejected.</li>
 * </ul>
 *
 * <p>No {@code float}/{@code double} arithmetic appears anywhere; records are handled as raw bytes.</p>
 */
class TransactionCombineJobConfigTest {

    /** The fixed {@code CVTRA05Y} record length in bytes. */
    private static final int LEN = TransactionCombineJobConfig.RECORD_LENGTH;

    /** Per-test temporary directory managed by JUnit&#160;5. */
    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------------------------------
    // Collation parity: bytewise (C/POSIX) ordering, NOT locale-sensitive collation.
    // ------------------------------------------------------------------------------------------

    /**
     * Proves the combined output is ordered by the raw {@code TRAN-ID} bytes (ASCII / {@code C}
     * collation), reproducing {@code SORT FIELDS=(TRAN-ID,A)}. The keys are chosen so a locale-sensitive
     * {@link Collator} orders them differently (it interleaves case and pushes {@code 'Z'} last, whereas
     * bytewise places all uppercase before any lowercase). The test asserts the combine result equals
     * the bytewise order and that the locale-collator order genuinely differs, so it cannot pass under a
     * locale collator.
     *
     * @throws IOException if the fixture files cannot be written or the output read
     */
    @Test
    void combineAndSortOrdersByTranIdBytewiseNotLocaleCollation() throws IOException {
        // First bytes: '0'(0x30) < 'A'(0x41) < 'Z'(0x5A) < 'a'(0x61) < 'b'(0x62).
        String id0 = "0AAAAAAAAAAAAAAA";
        String idA = "A000000000000001";
        String idZ = "Z000000000000001";
        String idaLower = "a000000000000001";
        String idbLower = "b000000000000001";

        // Split arbitrarily across the two inputs, deliberately out of order, to force a real sort.
        Path backup = writeInput("backup.dat", List.of(
                record(idZ, 'Z'),
                record(id0, '0'),
                record(idbLower, 'b')));
        Path system = writeInput("system.dat", List.of(
                record(idaLower, 'a'),
                record(idA, 'A')));
        Path out = tempDir.resolve("combined.dat");

        long written = TransactionCombineJobConfig.combineAndSort(backup, system, out);
        assertEquals(5, written, "written count must equal the total input count");

        List<String> bytewiseExpected = List.of(id0, idA, idZ, idaLower, idbLower);
        assertEquals(bytewiseExpected, tranIdsInOrder(out),
                "combined output must be ordered by raw TRAN-ID bytes (C/POSIX collation)");

        // Sanity: the same keys under a US locale collator order differently, so this fixture actually
        // distinguishes bytewise ordering from locale collation.
        List<String> localeOrder = new ArrayList<>(bytewiseExpected);
        localeOrder.sort(Collator.getInstance(Locale.US));
        assertNotEquals(bytewiseExpected, localeOrder,
                "fixture must distinguish bytewise ordering from locale-collator ordering");
    }

    // ------------------------------------------------------------------------------------------
    // Count, length and byte-exact preservation.
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies that every combined record is exactly 350 bytes, both inputs are fully represented
     * (count equals the sum of the two input counts), and each record's full 350 bytes are preserved
     * byte-for-byte through the combine (matched by {@code TRAN-ID}, with a per-record distinct trailing
     * fill proving the trailing bytes are not altered).
     *
     * @throws IOException if the fixture files cannot be written or the output read
     */
    @Test
    void combineAndSortPreservesEveryRecordByteExactAndCountEqualsSum() throws IOException {
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("TXN0000000000010", record("TXN0000000000010", 'p'));
        expected.put("TXN0000000000005", record("TXN0000000000005", 'q'));
        expected.put("TXN0000000000020", record("TXN0000000000020", 'r'));
        expected.put("TXN0000000000001", record("TXN0000000000001", 's'));

        Path backup = writeInput("bkup.dat", List.of(
                expected.get("TXN0000000000010"),
                expected.get("TXN0000000000005")));
        Path system = writeInput("sys.dat", List.of(
                expected.get("TXN0000000000020"),
                expected.get("TXN0000000000001")));
        Path out = tempDir.resolve("combined.dat");

        long written = TransactionCombineJobConfig.combineAndSort(backup, system, out);
        assertEquals(4, written, "count must equal the sum of input record counts");

        List<byte[]> outRecords = TransactionCombineJobConfig.splitRecords(Files.readAllBytes(out));
        assertEquals(4, outRecords.size(), "output must contain every input record exactly once");
        for (byte[] rec : outRecords) {
            assertEquals(LEN, rec.length, "every combined record must be exactly 350 bytes");
            String id = tranId(rec);
            assertArrayEquals(expected.get(id), rec,
                    "record for " + id + " must be preserved byte-for-byte");
        }

        // Ascending by TRAN-ID (bytewise): 1 < 5 < 10 < 20 (as fixed-width strings).
        assertEquals(
                List.of("TXN0000000000001", "TXN0000000000005",
                        "TXN0000000000010", "TXN0000000000020"),
                tranIdsInOrder(out),
                "combined output must be ascending by TRAN-ID");
    }

    /**
     * Verifies the combined output is an undelimited {@code RECFM=FB} image (finding #17): contiguous
     * 350-byte records with no record delimiter, so the total output size is exactly
     * {@code recordCount * 350} bytes, no line-feed byte is appended after any record, and slicing the
     * output into 350-byte blocks recovers the records in {@code TRAN-ID} order.
     *
     * @throws IOException if the fixture files cannot be written or the output read
     */
    @Test
    void combineAndSortWritesUndelimitedFixedBlockRecordsWithNoDelimiter() throws IOException {
        Path backup = writeInput("b.dat", List.of(record("TXN2", 'x'), record("TXN4", 'x')));
        Path system = writeInput("s.dat", List.of(record("TXN1", 'y'), record("TXN3", 'y')));
        Path out = tempDir.resolve("out.dat");

        long written = TransactionCombineJobConfig.combineAndSort(backup, system, out);
        assertEquals(4, written);

        byte[] raw = Files.readAllBytes(out);
        assertEquals(4 * LEN, raw.length,
                "output must be contiguous 350-byte records with no delimiter (RECFM=FB)");
        assertEquals(0, raw.length % LEN, "output length must be an exact multiple of the record length");
        assertEquals(List.of("TXN1", "TXN2", "TXN3", "TXN4"), tranIdsInOrder(out),
                "sliced 350-byte blocks must recover the records in ascending TRAN-ID order");
    }

    /**
     * Exercises the external, bounded-memory merge sort across <em>many</em> spilled runs (finding #22):
     * driving {@link TransactionCombineJobConfig#combineAndSort(Path, Path, Path, int)} with a per-run
     * bound of one record forces one run per input record, so the k-way merge &mdash; not an in-memory
     * {@code List.sort} &mdash; produces the global ordering. Proves the merge yields a fully sorted
     * output and preserves stability (backup before system for equal keys) across run boundaries.
     *
     * @throws IOException if the fixture files cannot be written or the output read
     */
    @Test
    void combineAndSortAcrossManySpilledRunsIsGloballySortedAndStable() throws IOException {
        // Equal-keyed duplicates in each input plus interleaved distinct keys, all out of order.
        byte[] dupBackupFirst = record("DUP0000000000001", 'B');
        byte[] dupBackupSecond = record("DUP0000000000001", 'C');
        byte[] dupSystem = record("DUP0000000000001", 'S');
        Path backup = writeInput("multi-b.dat", List.of(
                record("TXN0000000000030", 'x'),
                dupBackupFirst,
                record("TXN0000000000010", 'x'),
                dupBackupSecond));
        Path system = writeInput("multi-s.dat", List.of(
                record("TXN0000000000020", 'y'),
                dupSystem,
                record("TXN0000000000005", 'y')));
        Path out = tempDir.resolve("multi-out.dat");

        long written = TransactionCombineJobConfig.combineAndSort(backup, system, out, 1);
        assertEquals(7, written, "every input record must appear exactly once");

        // Global ascending TRAN-ID order (three equal DUP keys collapse to one id in the list).
        assertEquals(
                List.of("DUP0000000000001", "DUP0000000000001", "DUP0000000000001",
                        "TXN0000000000005", "TXN0000000000010", "TXN0000000000020", "TXN0000000000030"),
                tranIdsInOrder(out),
                "multi-run merge must yield a globally ascending TRAN-ID ordering");

        // Stability across runs: the two backup DUP records (in read order) precede the system DUP.
        List<byte[]> outRecords = TransactionCombineJobConfig.splitRecords(Files.readAllBytes(out));
        assertArrayEquals(dupBackupFirst, outRecords.get(0),
                "first backup duplicate (earliest read) must sort first among equal keys");
        assertArrayEquals(dupBackupSecond, outRecords.get(1),
                "second backup duplicate must sort second, preserving backup read order");
        assertArrayEquals(dupSystem, outRecords.get(2),
                "system duplicate must sort last among equal keys (backup read before system)");
    }

    /**
     * Verifies the sort is stable: when two records share a {@code TRAN-ID} (not expected in production,
     * as {@code TRAN-ID} is the unique key), the backup record precedes the system record, matching the
     * physical {@code SORTIN} concatenation order (backup read first).
     *
     * @throws IOException if the fixture files cannot be written or the output read
     */
    @Test
    void combineAndSortStableOrderKeepsBackupBeforeSystemForEqualTranId() throws IOException {
        byte[] backupDup = record("DUPKEY0000000001", 'B');
        byte[] systemDup = record("DUPKEY0000000001", 'S');
        Path backup = writeInput("bk.dat", List.of(backupDup));
        Path system = writeInput("sy.dat", List.of(systemDup));
        Path out = tempDir.resolve("dup.dat");

        TransactionCombineJobConfig.combineAndSort(backup, system, out);

        List<byte[]> outRecords = TransactionCombineJobConfig.splitRecords(Files.readAllBytes(out));
        assertEquals(2, outRecords.size());
        assertArrayEquals(backupDup, outRecords.get(0), "backup record must sort first for equal keys");
        assertArrayEquals(systemDup, outRecords.get(1), "system record must sort second for equal keys");
    }

    // ------------------------------------------------------------------------------------------
    // Record framing / splitting behavior.
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies that an undelimited {@code RECFM=FB} image (contiguous fixed-length blocks, no line
     * feeds) whose length is an exact multiple of 350 is split into the correct number of records.
     */
    @Test
    void splitRecordsSupportsUndelimitedFixedLengthBlocks() {
        byte[] r1 = record("FIXED00000000001", 'a');
        byte[] r2 = record("FIXED00000000002", 'b');
        byte[] concatenated = new byte[LEN * 2];
        System.arraycopy(r1, 0, concatenated, 0, LEN);
        System.arraycopy(r2, 0, concatenated, LEN, LEN);

        List<byte[]> records = TransactionCombineJobConfig.splitRecords(concatenated);
        assertEquals(2, records.size(), "an undelimited 700-byte image must split into two 350-byte records");
        assertArrayEquals(r1, records.get(0));
        assertArrayEquals(r2, records.get(1));
    }

    /**
     * Verifies strict undelimited {@code RECFM=FB} framing (finding #17): a line-feed byte occurring
     * <em>inside</em> a 350-byte record is treated as ordinary record content, never as a delimiter.
     * Two records each carrying an embedded line feed are concatenated with no delimiter and must split
     * purely by length into exactly two byte-exact records.
     */
    @Test
    void splitRecordsTreatsLineFeedAsOrdinaryContentNotDelimiter() {
        byte[] r1 = record("LF00000000000001", 'a');
        byte[] r2 = record("LF00000000000002", 'b');
        // Embed a line feed well inside each record's content region (byte 20).
        r1[20] = (byte) '\n';
        r2[20] = (byte) '\n';
        byte[] concatenated = new byte[LEN * 2];
        System.arraycopy(r1, 0, concatenated, 0, LEN);
        System.arraycopy(r2, 0, concatenated, LEN, LEN);

        List<byte[]> records = TransactionCombineJobConfig.splitRecords(concatenated);
        assertEquals(2, records.size(),
                "an undelimited 700-byte image must split into exactly two 350-byte records");
        assertArrayEquals(r1, records.get(0),
                "embedded line feed must be preserved as content, not treated as a record boundary");
        assertArrayEquals(r2, records.get(1));
    }

    /**
     * Verifies that an undelimited input whose length is not a multiple of the 350-byte record length is
     * rejected with an {@link IllegalArgumentException}.
     */
    @Test
    void splitRecordsRejectsUndelimitedNonMultipleLength() {
        byte[] bad = new byte[LEN + 7];
        Arrays.fill(bad, (byte) '0');
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TransactionCombineJobConfig.splitRecords(bad));
        assertTrue(ex.getMessage().contains("multiple"),
                "message should explain the multiple-of-record-length requirement");
    }

    /**
     * Verifies an empty input yields no records.
     */
    @Test
    void splitRecordsReturnsEmptyForEmptyInput() {
        assertEquals(0, TransactionCombineJobConfig.splitRecords(new byte[0]).size());
    }

    /**
     * Verifies that a truncated input &mdash; one whose byte length is not an exact multiple of the
     * 350-byte record length &mdash; is rejected during the combine. Under the strict undelimited
     * {@code RECFM=FB} reader (finding #17) a partial final block raises an {@link IOException} rather
     * than being silently split on a delimiter or padded.
     *
     * @throws IOException if the valid fixture file cannot be written
     */
    @Test
    void combineAndSortRejectsTruncatedFinalBlock() throws IOException {
        byte[] tooShort = new byte[LEN - 1];
        Arrays.fill(tooShort, (byte) '0');
        Path backup = writeInput("short.dat", List.of(tooShort));
        Path system = writeInput("ok.dat", List.of(record("TXN0000000000001", 'a')));
        Path out = tempDir.resolve("bad.dat");

        IOException ex = assertThrows(IOException.class,
                () -> TransactionCombineJobConfig.combineAndSort(backup, system, out));
        assertTrue(ex.getMessage().contains("truncated") || ex.getMessage().contains("partial"),
                "the diagnostic should identify the truncated/partial final record");
    }

    // ------------------------------------------------------------------------------------------
    // Configuration contract constants (COMBTRAN mapping).
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies the configuration's public contract constants match the {@code COMBTRAN} mapping: the
     * 350-byte record length, the job/step names, and the three dataset job-parameter keys.
     */
    @Test
    void contractConstantsMatchCombtranMapping() {
        assertEquals(350, TransactionCombineJobConfig.RECORD_LENGTH);
        assertEquals("transactionCombineJob", TransactionCombineJobConfig.JOB_NAME);
        assertEquals("transactionCombineStep", TransactionCombineJobConfig.STEP_NAME);
        assertEquals("backupInput", TransactionCombineJobConfig.PARAM_BACKUP_INPUT);
        assertEquals("systemInput", TransactionCombineJobConfig.PARAM_SYSTEM_INPUT);
        assertEquals("combinedOutput", TransactionCombineJobConfig.PARAM_COMBINED_OUTPUT);
    }

    // ------------------------------------------------------------------------------------------
    // Bean wiring and @StepScope tasklet behavior.
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies the {@code @Bean} factory methods build a non-null tasklet and a step/job carrying the
     * {@code COMBTRAN} names. Mock collaborators stand in for the auto-configured {@link JobRepository}
     * and {@link PlatformTransactionManager}; building the step/job does not invoke them.
     */
    @Test
    void beanMethodsBuildNamedJobStepAndTasklet() {
        TransactionCombineJobConfig config = newConfig();

        Tasklet tasklet = config.transactionCombineTasklet("b", "s", "o");
        assertNotNull(tasklet, "the tasklet bean must be created");

        Step step = config.transactionCombineStep();
        assertEquals(TransactionCombineJobConfig.STEP_NAME, step.getName(),
                "the step must carry the COMBTRAN step name");

        Job job = config.transactionCombineJob();
        assertEquals(TransactionCombineJobConfig.JOB_NAME, job.getName(),
                "the job must carry the COMBTRAN job name");
    }

    /**
     * Verifies the {@code @StepScope} tasklet reads its three path parameters, performs the combine and
     * returns {@link RepeatStatus#FINISHED} in a single execution. The tasklet ignores the step
     * contribution and chunk context, so {@code null} arguments are acceptable in this unit context.
     *
     * @throws Exception if the tasklet execution fails
     */
    @Test
    void taskletExecutesCombineAndReturnsFinished() throws Exception {
        Path backup = writeInput("tk-b.dat", List.of(record("TXN2", 'x'), record("TXN0", 'x')));
        Path system = writeInput("tk-s.dat", List.of(record("TXN1", 'y')));
        Path out = tempDir.resolve("tk-out.dat");
        TransactionCombineJobConfig config = newConfig();

        Tasklet tasklet = config.transactionCombineTasklet(
                backup.toString(), system.toString(), out.toString());
        RepeatStatus status = tasklet.execute(null, null);

        assertEquals(RepeatStatus.FINISHED, status, "the tasklet must finish in a single execution");
        assertEquals(List.of("TXN0", "TXN1", "TXN2"), tranIdsInOrder(out),
                "the tasklet must write the combined, TRAN-ID-ordered output");
    }

    /**
     * Verifies the tasklet fails fast (before any file access) when a required path parameter is blank,
     * mirroring a JCL error for an unsatisfied DD, and that the diagnostic names the missing parameter.
     */
    @Test
    void taskletRejectsBlankParameter() {
        TransactionCombineJobConfig config = newConfig();
        Tasklet tasklet = config.transactionCombineTasklet("   ", "system", "out");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tasklet.execute(null, null));
        assertTrue(ex.getMessage().contains(TransactionCombineJobConfig.PARAM_BACKUP_INPUT),
                "the diagnostic should name the missing job parameter");
    }

    // ------------------------------------------------------------------------------------------
    // Fixture helpers.
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a {@link TransactionCombineJobConfig} with mock batch collaborators and a real
     * {@link BatchFilePathResolver} rooted at {@link #tempDir}, so tasklet executions resolve inputs and
     * atomically publish the output within the per-test temporary directory (finding #18).
     *
     * @return a config instance for the bean-wiring and tasklet tests
     */
    private TransactionCombineJobConfig newConfig() {
        return new TransactionCombineJobConfig(
                mock(JobRepository.class), mock(PlatformTransactionManager.class),
                new BatchFilePathResolver(List.of(tempDir.toString())));
    }

    /**
     * Builds a 350-byte {@code CVTRA05Y} record whose {@code TRAN-ID} (bytes 1-16) is the given id
     * (left-justified, space-padded) and whose remaining 334 bytes are filled with {@code fill} so each
     * record has distinct, verifiable trailing content. Under the undelimited {@code RECFM=FB} framing
     * any fill byte is preserved verbatim; a printable fill is used purely for readable diagnostics.
     *
     * @param tranId the transaction id (up to 16 characters)
     * @param fill   the fill byte for the remaining 334 bytes
     * @return a 350-byte record image
     */
    private static byte[] record(String tranId, char fill) {
        byte[] rec = new byte[LEN];
        Arrays.fill(rec, (byte) fill);
        byte[] id = tranId.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 16; i++) {
            rec[i] = i < id.length ? id[i] : (byte) ' ';
        }
        return rec;
    }

    /**
     * Writes records to a temporary input file as a strict undelimited {@code RECFM=FB} image
     * (contiguous 350-byte records, no delimiter), matching the framing the combine job reads and writes
     * (finding #17).
     *
     * @param name    the file name within the temporary directory
     * @param records the records to write
     * @return the path of the written file
     * @throws IOException if the file cannot be written
     */
    private Path writeInput(String name, List<byte[]> records) throws IOException {
        Path file = tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            for (byte[] rec : records) {
                out.write(rec);
            }
        }
        return file;
    }

    /**
     * Extracts the trimmed {@code TRAN-ID} (bytes 1-16) of a record.
     *
     * @param record a 350-byte record image
     * @return the {@code TRAN-ID} with trailing spaces removed
     */
    private static String tranId(byte[] record) {
        return new String(record, 0, 16, StandardCharsets.ISO_8859_1).stripTrailing();
    }

    /**
     * Reads the combined output file and returns the trimmed {@code TRAN-ID} of each record in order.
     *
     * @param output the combined output path
     * @return the ordered list of trimmed transaction ids
     * @throws IOException if the file cannot be read
     */
    private static List<String> tranIdsInOrder(Path output) throws IOException {
        List<byte[]> records = TransactionCombineJobConfig.splitRecords(Files.readAllBytes(output));
        List<String> ids = new ArrayList<>(records.size());
        for (byte[] rec : records) {
            ids.add(tranId(rec));
        }
        return ids;
    }
}

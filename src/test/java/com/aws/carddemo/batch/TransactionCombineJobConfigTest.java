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
 *   <li>the concatenation order (backup before system) is preserved for equal keys (stable sort);</li>
 *   <li>the record framing (LF-delimited, undelimited {@code RECFM=FB}, and CR/LF tolerance) behaves as
 *       documented and wrong-length records are rejected.</li>
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
     * Verifies the combined output framing: one 350-byte record per line, each terminated by a single
     * line feed (including a trailing line feed after the final record), so the total output size is
     * {@code recordCount * (350 + 1)} bytes and every 351st byte is a line feed.
     *
     * @throws IOException if the fixture files cannot be written or the output read
     */
    @Test
    void combineAndSortWritesLineFeedFramedRecordsWithTrailingNewline() throws IOException {
        Path backup = writeInput("b.dat", List.of(record("TXN2", 'x'), record("TXN4", 'x')));
        Path system = writeInput("s.dat", List.of(record("TXN1", 'y'), record("TXN3", 'y')));
        Path out = tempDir.resolve("out.dat");

        long written = TransactionCombineJobConfig.combineAndSort(backup, system, out);
        assertEquals(4, written);

        byte[] raw = Files.readAllBytes(out);
        assertEquals(4 * (LEN + 1), raw.length, "each record occupies 350 content bytes plus one LF");
        for (int i = 0; i < 4; i++) {
            assertEquals((byte) '\n', raw[i * (LEN + 1) + LEN],
                    "each 350-byte record must be followed by a line feed");
        }
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
     * Verifies CR/LF terminators are tolerated on read: a trailing carriage return before each line
     * feed is stripped so each record is still exactly 350 bytes.
     */
    @Test
    void splitRecordsToleratesCrLfTerminators() {
        byte[] r1 = record("CRLF000000000001", 'a');
        byte[] r2 = record("CRLF000000000002", 'b');
        byte[] framed = new byte[(LEN + 2) * 2];
        int pos = 0;
        for (byte[] rec : List.of(r1, r2)) {
            System.arraycopy(rec, 0, framed, pos, LEN);
            pos += LEN;
            framed[pos++] = (byte) '\r';
            framed[pos++] = (byte) '\n';
        }

        List<byte[]> records = TransactionCombineJobConfig.splitRecords(framed);
        assertEquals(2, records.size());
        assertArrayEquals(r1, records.get(0), "CR must be stripped, leaving a 350-byte record");
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
     * Verifies that a line-framed record whose content length is not 350 bytes is rejected during the
     * combine (the layout mapper enforces the exact record length).
     *
     * @throws IOException if the fixture files cannot be written
     */
    @Test
    void combineAndSortRejectsRecordOfWrongLength() throws IOException {
        byte[] tooShort = new byte[LEN - 1];
        Arrays.fill(tooShort, (byte) '0');
        Path backup = writeInput("short.dat", List.of(tooShort));
        Path system = writeInput("ok.dat", List.of(record("TXN0000000000001", 'a')));
        Path out = tempDir.resolve("bad.dat");

        assertThrows(IllegalArgumentException.class,
                () -> TransactionCombineJobConfig.combineAndSort(backup, system, out));
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
        TransactionCombineJobConfig config = new TransactionCombineJobConfig(
                mock(JobRepository.class), mock(PlatformTransactionManager.class));

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
        TransactionCombineJobConfig config = new TransactionCombineJobConfig(
                mock(JobRepository.class), mock(PlatformTransactionManager.class));

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
        TransactionCombineJobConfig config = new TransactionCombineJobConfig(
                mock(JobRepository.class), mock(PlatformTransactionManager.class));
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
     * Builds a 350-byte {@code CVTRA05Y} record whose {@code TRAN-ID} (bytes 1-16) is the given id
     * (left-justified, space-padded) and whose remaining 334 bytes are filled with {@code fill} so each
     * record has distinct, verifiable trailing content. The fill character must not be a line feed or
     * carriage return, so the record survives the LF-framed round trip unchanged (as real transaction
     * records, which contain no control bytes, do).
     *
     * @param tranId the transaction id (up to 16 characters)
     * @param fill   the printable fill byte for the remaining 334 bytes
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
     * Writes records to a temporary input file, one 350-byte record per line terminated by a line feed.
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
                out.write('\n');
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

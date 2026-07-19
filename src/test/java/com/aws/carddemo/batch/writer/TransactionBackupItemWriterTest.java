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
package com.aws.carddemo.batch.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.Transaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Fast, standalone unit tests for {@link TransactionBackupItemWriter} that focus on the
 * <strong>unique-per-run output filename</strong> introduced to resolve QA finding <strong>F7</strong>
 * (concurrent-run filename collision silently loses a backup).
 *
 * <p>These tests do not start a Spring context or a database. They exercise the writer's
 * {@link org.springframework.batch.core.StepExecutionListener} lifecycle directly against a JUnit
 * {@link TempDir}, constructing {@link StepExecution}s with the same helper factory
 * ({@link MetaDataInstanceFactory}) the other writer unit tests in this package use. The
 * byte-exactness of the 350-byte record image itself is covered comprehensively by the
 * Testcontainers integration test {@code com.aws.carddemo.batch.TransactionBackupJobTest}; here we
 * verify only that the filename derivation is collision-free and that the open/write/close path still
 * produces a well-formed record with the new name.</p>
 *
 * @see TransactionBackupItemWriter
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
class TransactionBackupItemWriterTest {

    /** Filename prefix used by the writer under test (its production default). */
    private static final String FILE_PREFIX = "TRANSACT.BKUP.";

    /** Backup step name used when building {@link StepExecution} fixtures. */
    private static final String STEP_NAME = "transactionBackupStep";

    /**
     * Expected filename shape when both execution ids are present: the prefix, a 17-digit
     * {@code yyyyMMddHHmmssSSS} millisecond timestamp, then {@code .jobExecutionId-stepExecutionId}.
     */
    private static final String EXECUTION_ID_NAME_REGEX = "TRANSACT\\.BKUP\\.\\d{17}\\.\\d+-\\d+";

    /** Per-test temporary output directory. */
    @TempDir
    private Path backupDir;

    /**
     * Two runs whose {@link StepExecution}s carry distinct {@code JobRepository}-assigned ids must
     * produce two <em>distinct</em> files, even though both {@code beforeStep} calls occur within the
     * same wall-clock second. This is the direct regression guard for F7: before the fix, a
     * second-granularity timestamp alone produced one shared filename and silently lost a backup.
     */
    @Test
    @DisplayName("F7: two runs with distinct execution ids in the same second produce two distinct files")
    void twoRunsWithDistinctExecutionIdsProduceTwoDistinctFiles() {
        TransactionBackupItemWriter writer =
                new TransactionBackupItemWriter(backupDir.toString(), FILE_PREFIX);

        runEmptyBackup(writer, jobStep(1001L, 2001L));
        runEmptyBackup(writer, jobStep(1002L, 2002L));

        List<Path> files = listBackupFiles();
        assertThat(files)
                .as("two runs must not collide on one filename")
                .hasSize(2);
        assertThat(files)
                .extracting(path -> path.getFileName().toString())
                .doesNotHaveDuplicates();
        assertThat(files)
                .allSatisfy(path -> assertThat(path.getFileName().toString())
                        .matches(EXECUTION_ID_NAME_REGEX));
    }

    /**
     * The filename embeds the millisecond timestamp and the {@code jobExecutionId-stepExecutionId}
     * suffix that guarantees cross-process uniqueness (both ids come from the shared batch-metadata
     * sequences in a real launch).
     */
    @Test
    @DisplayName("F7: filename embeds a millisecond timestamp and the execution-id suffix")
    void filenameEmbedsMillisTimestampAndExecutionIdSuffix() {
        TransactionBackupItemWriter writer =
                new TransactionBackupItemWriter(backupDir.toString(), FILE_PREFIX);

        runEmptyBackup(writer, jobStep(4242L, 8484L));

        List<Path> files = listBackupFiles();
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName().toString())
                .matches("TRANSACT\\.BKUP\\.\\d{17}\\.4242-8484");
    }

    /**
     * Defensive fallback: an unpersisted {@link StepExecution} (built with the two-argument
     * constructor, so {@link StepExecution#getId()} is {@code null}) is never produced by the
     * framework during a real launch, but the writer must still emit a unique filename. In that case
     * a high-resolution {@code nanoTime} token is used, keeping the name collision-free.
     */
    @Test
    @DisplayName("F7: an unpersisted step execution falls back to a unique nanoTime token")
    void unpersistedStepExecutionFallsBackToNanoTimeToken() {
        TransactionBackupItemWriter writer =
                new TransactionBackupItemWriter(backupDir.toString(), FILE_PREFIX);

        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(1001L);
        StepExecution unpersisted = new StepExecution(STEP_NAME, jobExecution);
        assertThat(unpersisted.getId()).as("precondition: step id is null before persistence").isNull();

        runEmptyBackup(writer, unpersisted);

        List<Path> files = listBackupFiles();
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName().toString())
                .matches("TRANSACT\\.BKUP\\.\\d{17}\\.run\\d+");
    }

    /**
     * The open/write/close path still emits a well-formed 350-byte record followed by a single line
     * feed under the new filename, confirming the {@code beforeStep} filename change did not disturb
     * the byte-exact record serialization.
     *
     * @throws Exception if writing fails
     */
    @Test
    @DisplayName("F7: write path still emits a byte-exact 350-byte record under the new filename")
    void writesByteExactRecordUnderNewFilename() throws Exception {
        TransactionBackupItemWriter writer =
                new TransactionBackupItemWriter(backupDir.toString(), FILE_PREFIX);
        StepExecution stepExecution = jobStep(7L, 9L);

        writer.beforeStep(stepExecution);
        writer.write(Chunk.of(sampleTransaction()));
        writer.afterStep(stepExecution);

        List<Path> files = listBackupFiles();
        assertThat(files).hasSize(1);

        byte[] bytes = Files.readAllBytes(files.get(0));
        assertThat(bytes)
                .as("one 350-byte record plus a single line-feed delimiter")
                .hasSize(351);
        assertThat(bytes[350]).isEqualTo((byte) '\n');

        String record = new String(bytes, 0, 350, StandardCharsets.ISO_8859_1);
        assertThat(record).hasSize(350);
        assertThat(record).startsWith("0000000000000001");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Runs an empty backup step (open then close, no records) for the given execution, producing one
     * output file whose name is what these tests assert on.
     *
     * @param writer        the writer under test
     * @param stepExecution the step execution driving the filename
     */
    private static void runEmptyBackup(TransactionBackupItemWriter writer, StepExecution stepExecution) {
        writer.beforeStep(stepExecution);
        writer.afterStep(stepExecution);
    }

    /**
     * Builds a persisted-style {@link StepExecution} with explicit job- and step-execution ids,
     * mirroring the identifiers the shared {@code JobRepository} assigns during a real launch.
     *
     * @param jobExecutionId  the job execution id
     * @param stepExecutionId the step execution id
     * @return the configured step execution
     */
    private static StepExecution jobStep(long jobExecutionId, long stepExecutionId) {
        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(jobExecutionId);
        return MetaDataInstanceFactory.createStepExecution(jobExecution, STEP_NAME, stepExecutionId);
    }

    /**
     * Lists the regular files currently in the temporary backup directory.
     *
     * @return the produced backup files (never {@code null})
     */
    private List<Path> listBackupFiles() {
        try (Stream<Path> stream = Files.list(backupDir)) {
            return stream.filter(Files::isRegularFile).toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to list backup directory " + backupDir, ex);
        }
    }

    /**
     * Builds a minimal, unpersisted {@link Transaction} fixture in the copybook field order of
     * {@code CVTRA05Y.cpy}; only the id needs to be recognizable for the byte-exact assertion.
     *
     * @return a new transaction fixture
     */
    private static Transaction sampleTransaction() {
        return new Transaction(
                "0000000000000001",
                "01",
                5,
                "POS",
                "UNIT TEST BACKUP RECORD",
                new BigDecimal("100.00"),
                123456789L,
                "TEST MERCHANT",
                "TEST CITY",
                "12345",
                "0000000000000002",
                "2026-01-19-15.30.12.000000",
                "2026-01-19-15.30.12.000000");
    }
}

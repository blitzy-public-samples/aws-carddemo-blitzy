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
 * language governing permissions and limitations under the License.
 */
package com.carddemo.transaction.batch;

import com.carddemo.common.batch.BatchOutputPathResolver;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * :purpose: Publish the ``DALYREJS`` reject generation for a completed transaction-posting
 *     instance by renaming the staging file {@link RejectFileItemWriter} appended to onto the
 *     published generation name, and retain that staging file untouched when the instance has
 *     not completed.
 * :output: On completion, one file at the published generation name holding every reject
 *     the instance committed across all of its executions, plus a log line stating the record
 *     count, the byte length and the SHA-256 of what was published. On failure, no published
 *     file and a log line stating how many committed rejects are being carried forward for the
 *     restart.
 * :note: This is what makes the reject file a DELIVERABLE rather than a side effect. Two
 *     properties matter to a downstream reader, and neither held before: the published name
 *     appears only when it is complete (a single atomic rename, so a reader never sees a
 *     partially written file under it), and a restart of a failed instance publishes the
 *     rejects of BOTH the failed execution and the restart - the staging file is
 *     instance-scoped and appended, so nothing committed is lost.
 * :note: Bound to the JOB, not the step, because the decision to publish depends on the
 *     outcome of the whole instance.
 */
@Component
public class RejectFilePublishListener implements JobExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RejectFilePublishListener.class);

    /** Length of one reject record: 350-byte DALYTRAN image + 80-byte trailer. */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Configured base name of the reject sink. */
    private final String rejectFileName;

    /** Resolver that confines both names to the configured batch output root. */
    private final BatchOutputPathResolver pathResolver;

    /**
     * :purpose: Construct the listener over the configured reject file name and the
     *  shared output-root resolver.
     * :param rejectFileName: BASE file name of the reject sink, the same
     *  ``carddemo.batch.reject-file`` property {@link RejectFileItemWriter} reads.
     * :param pathResolver: resolver that produces the generation name for a job
     *  instance inside the allowlisted output root.
     */
    public RejectFilePublishListener(
            @Value("${carddemo.batch.reject-file:dalyrejs.txt}") String rejectFileName,
            BatchOutputPathResolver pathResolver) {
        this.rejectFileName = rejectFileName;
        this.pathResolver = pathResolver;
    }

    /**
     * :purpose: Publish or retain the instance's reject generation according to the
     *  outcome of the run.
     * :param jobExecution: the finished execution, whose job instance identifies the
     *  generation.
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getJobInstance() == null) {
            return;
        }
        long instanceId = jobExecution.getJobInstance().getInstanceId();
        Path published = pathResolver.resolveOutputGeneration(rejectFileName, instanceId);
        Path staging = published.resolveSibling(
                published.getFileName() + RejectFileItemWriter.STAGING_SUFFIX);

        if (!Files.exists(staging)) {
            // No reject was written by this instance. Nothing to publish, and no empty
            // file is created: an empty published generation would tell a downstream
            // reader that a complete reject set exists and is empty, which is only true
            // if the run actually completed with zero rejects - and that case is already
            // reported by the tally.
            return;
        }

        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            LOGGER.warn("Transaction posting instance {} ended {}; its reject staging file {} is"
                            + " retained with {} committed reject record(s) so a restart of this"
                            + " instance publishes them together with its own",
                    instanceId, jobExecution.getStatus(), staging.getFileName(),
                    countRecords(staging));
            return;
        }

        try {
            long records = countRecords(staging);
            long bytes = Files.size(staging);
            String digest = sha256(staging);
            publishAtomically(staging, published);
            LOGGER.info("Published DALYREJS generation {}: {} reject record(s), {} byte(s),"
                            + " sha256={}",
                    published.getFileName(), records, bytes, digest);
        } catch (IOException e) {
            // A deliverable that could not be published must not leave the run looking
            // clean: the exit status is downgraded so the caller and the JobRepository
            // both record that the output is missing.
            LOGGER.error("Failed to publish the DALYREJS generation {} from {}: {}",
                    published.getFileName(), staging.getFileName(), e.getMessage());
            jobExecution.setExitStatus(jobExecution.getExitStatus().and(
                    org.springframework.batch.core.ExitStatus.FAILED.addExitDescription(
                            "Reject generation could not be published: " + e.getMessage())));
        }
    }

    /**
     * :purpose: Move the staging file onto the published name without a window in which
     *  a reader could observe a partial file under that name.
     * :param staging: the file the run appended to.
     * :param published: the generation name a downstream reader scans for.
     * :raises IOException: when neither an atomic nor a replacing move succeeds.
     */
    private static void publishAtomically(Path staging, Path published) throws IOException {
        try {
            Files.move(staging, published,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some volume drivers refuse ATOMIC_MOVE across their own mount. A replacing
            // move on the same directory is still a single rename on every file system
            // this deployment uses; the fallback exists so publication cannot fail purely
            // on the strength of the flag.
            Files.move(staging, published, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * :purpose: Count the fixed-width reject records a file holds, for the published
     *  tally and the carried-forward tally.
     * :param file: the reject file to measure.
     * :returns: the number of 430-byte records, or 0 when the file cannot be read.
     * :note: Each record is 430 payload bytes plus exactly one LF, so the count is the
     *  byte length divided by 431. Measuring the FILE rather than counting writes is
     *  what makes the reported tally agree with the delivered artefact.
     */
    static long countRecords(Path file) {
        try {
            long size = Files.size(file);
            return size / (REJECT_RECORD_LENGTH + 1L);
        } catch (IOException e) {
            LOGGER.warn("Could not measure reject file {}: {}", file.getFileName(), e.getMessage());
            return 0L;
        }
    }

    /**
     * :purpose: Compute the SHA-256 of the published generation so a downstream
     *  consumer can verify the transfer it received.
     * :param file: the file to digest.
     * :returns: the lower-case hexadecimal digest, or ``unavailable`` when it cannot be
     *  computed.
     */
    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }
}

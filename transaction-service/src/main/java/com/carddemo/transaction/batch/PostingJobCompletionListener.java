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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

/**
 * :purpose: Report the end-of-run posting tallies and set the batch return code
 *           for the transaction-posting job, mirroring the ``CBTRN02C``
 *           end-of-run ``DISPLAY`` statements and ``RETURN-CODE`` handling: a
 *           return code of ``0`` when nothing was rejected and ``4`` when one
 *           or more records were rejected.
 * :output: Two INFO log lines carrying the ``TRANSACTIONS PROCESSED`` and
 *          ``TRANSACTIONS REJECTED`` tallies, and — when at least one record
 *          was rejected — a ``COMPLETED_WITH_REJECTS`` exit status that signals
 *          return code 4 while the batch status stays ``COMPLETED``.
 */
@Component
public class PostingJobCompletionListener implements JobExecutionListener {

    /**
     * :purpose: Shared ``ExecutionContext`` key under which the reject-routing
     *           path of the transaction-posting step publishes the running
     *           count of rejected records for this listener to read back.
     */
    public static final String REJECT_COUNT_KEY = "posting.rejectCount";

    /**
     * :purpose: Exit code reported when the daily-transaction feed yielded no records.
     *     ``CBTRN02C`` treats an unusable input as a hard failure (application result
     *     code 12), never as a clean run. Set by the posting step, whose failure
     *     Spring Batch propagates onto the job.
     */
    public static final String EMPTY_FEED_EXIT_CODE = "FAILED_EMPTY_FEED";

    /**
     * :purpose: Exit description carried with :data:`EMPTY_FEED_EXIT_CODE`, mapping
     *     the legacy application result code 12.
     */
    public static final String EMPTY_FEED_EXIT_DESCRIPTION =
            "Return code 12: the daily transaction feed contained no records";

    private static final Logger log = LoggerFactory.getLogger(PostingJobCompletionListener.class);

    /**
     * :purpose: Emit the processed and rejected tallies and map the legacy
     *           ``RETURN-CODE`` for a normally completed run — code 4 when
     *           records were rejected, code 0 otherwise — while leaving a
     *           ``FAILED`` or ``STOPPED`` job's batch status and exit code
     *           untouched so a hard failure is never reported as a clean run.
     * :param jobExecution: the completed job execution whose ``COMPLETED`` step
     *        executions supply the read count (processed total) and the rejected
     *        count published under :data:`REJECT_COUNT_KEY`.
     * :output: For a ``COMPLETED`` run, the two tally lines plus the
     *          ``COMPLETED_WITH_REJECTS`` exit status (return code 4) when the
     *          rejected total is greater than zero. For a ``FAILED`` or ``STOPPED``
     *          run, one diagnostic line naming the outcome and NO tally, and the
     *          status and exit code Spring Batch assigned are left untouched.
     * :note: A run that ends abnormally reports no tally.
     *          ``CBTRN02C`` reaches its ``DISPLAY 'TRANSACTIONS PROCESSED :'`` and
     *          ``DISPLAY 'TRANSACTIONS REJECTED  :'`` statements only on the normal
     *          end-of-file path; ``9999-ABEND-PROGRAM`` calls ``CEE3ABD`` and the
     *          run ends with no tally at all. Emitting one anyway printed
     *          ``PROCESSED :0 / REJECTED :0`` for a run that had committed records —
     *          a figure that contradicted the committed work and had no legacy
     *          analogue.
     * :note: The empty-feed verdict belongs to the step, not to this listener
     *          : the step knows both its own read count and
     *          whether the feed table is genuinely empty, and failing the step makes
     *          Spring Batch carry that status and exit code onto the job, so the
     *          job and step metadata an operator queries can never disagree.
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        // A run that did not complete normally has no legacy tally to report.
        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            log.error("Transaction posting run ended {} (exit code {}); no end-of-run tally is"
                            + " reported because the legacy program abends before its"
                            + " TRANSACTIONS PROCESSED / TRANSACTIONS REJECTED displays",
                    jobExecution.getStatus(),
                    jobExecution.getExitStatus() == null
                            ? "" : jobExecution.getExitStatus().getExitCode());
            return;
        }

        long processedCount = 0L;
        long rejectCount = 0L;
        for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
            // Tally only successfully completed step executions: restarted or
            // failed partial executions must not double-count reads or rejects.
            if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
                continue;
            }
            processedCount += stepExecution.getReadCount();
            rejectCount += stepExecution.getExecutionContext().getLong(REJECT_COUNT_KEY, 0L);
        }

        log.info("TRANSACTIONS PROCESSED :{}", processedCount);
        log.info("TRANSACTIONS REJECTED  :{}", rejectCount);

        // Map the legacy return code only for a normally completed run. A FAILED
        // or STOPPED job keeps the status and exit code Spring Batch assigned, so
        // rejects can never mask a hard failure as COMPLETED_WITH_REJECTS.
        if (rejectCount > 0L) {
            jobExecution.setExitStatus(new ExitStatus(
                    "COMPLETED_WITH_REJECTS",
                    "Return code 4: " + rejectCount + " transaction(s) rejected"));
        }
    }
}

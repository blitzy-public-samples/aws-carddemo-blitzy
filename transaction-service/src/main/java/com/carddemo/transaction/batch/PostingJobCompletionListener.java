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
     *     code 12), never as a clean run.
     */
    public static final String EMPTY_FEED_EXIT_CODE = "FAILED_EMPTY_FEED";

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
     * :output: Fails the execution with :data:`EMPTY_FEED_EXIT_CODE` (return code 12)
     *          when a completed run read no records at all; otherwise sets the
     *          ``COMPLETED_WITH_REJECTS`` exit status (return code 4) when the rejected
     *          total is greater than zero, and leaves the existing exit status alone.
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
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

        // An empty feed is an operational failure, not a clean run. The legacy job
        // step was scheduled because a DALYTRAN feed had been delivered, so reading
        // zero records means the input never arrived or was not visible to this run.
        // Reporting COMPLETED in that case is a silent false success: the operator
        // believes the day's transactions were posted when nothing was.
        if (jobExecution.getStatus() == BatchStatus.COMPLETED && processedCount == 0L) {
            log.error("Daily transaction feed was empty: no records were read from DALYTRAN");
            jobExecution.setStatus(BatchStatus.FAILED);
            jobExecution.setExitStatus(new ExitStatus(
                    EMPTY_FEED_EXIT_CODE,
                    "Return code 12: the daily transaction feed contained no records"));
            return;
        }

        // Map the legacy return code only for a normally completed run. A FAILED
        // or STOPPED job keeps the status and exit code Spring Batch assigned, so
        // rejects can never mask a hard failure as COMPLETED_WITH_REJECTS.
        if (jobExecution.getStatus() == BatchStatus.COMPLETED && rejectCount > 0L) {
            jobExecution.setExitStatus(new ExitStatus(
                    "COMPLETED_WITH_REJECTS",
                    "Return code 4: " + rejectCount + " transaction(s) rejected"));
        }
    }
}

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

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
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

    private static final Logger log = LoggerFactory.getLogger(PostingJobCompletionListener.class);

    /**
     * :purpose: Durable batch metadata store, consulted to read back the step
     *           executions of the EARLIER executions of this job instance.
     */
    private final JobRepository jobRepository;

    /**
     * :purpose: Construct the listener over the batch metadata store.
     * :param jobRepository: the durable batch metadata store.
     */
    public PostingJobCompletionListener(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * :purpose: Emit the processed and rejected tallies and map the legacy ``RETURN-CODE`` for
     *     a normally completed run — code 4 when records were rejected, code 0 otherwise — while
     *     leaving a ``FAILED`` or ``STOPPED`` job's batch status and exit code untouched so a hard
     *     failure is never reported as a clean run.
     * :param jobExecution: the completed job execution whose ``COMPLETED`` step executions
     *     supply the read count (processed total) and the rejected count published under
     *     :data:`REJECT_COUNT_KEY`.
     * :output: For a ``COMPLETED`` run, the two tally lines plus the
     *     ``COMPLETED_WITH_REJECTS`` exit status (return code 4) when the rejected total is
     *     greater than zero. For a ``FAILED`` or ``STOPPED`` run, one diagnostic line naming the
     *     outcome and NO tally, and the status and exit code Spring Batch assigned are left
     *     untouched.
     * :note: A run that ends abnormally reports no tally. ``CBTRN02C`` reaches its ``DISPLAY
     *     'TRANSACTIONS PROCESSED :'`` and ``DISPLAY 'TRANSACTIONS REJECTED :'`` statements only
     *     on the normal end-of-file path; ``9999-ABEND-PROGRAM`` calls ``CEE3ABD`` and the run
     *     ends with no tally at all. Emitting one anyway printed ``PROCESSED :0 / REJECTED :0``
     *     for a run that had committed records — a figure that contradicted the committed work and
     *     had no legacy analogue.
     * :note: The empty-feed verdict belongs to the step, not to this listener
     * : the step knows both its own read count and whether the feed table is genuinely empty,
     *     and failing the step makes Spring Batch carry that status and exit code onto the job, so
     *     the job and step metadata an operator queries can never disagree.
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

        // Tallied across EVERY execution of this job INSTANCE, not just this one. A
        // legacy run always reprocessed the whole feed, so one run's displays covered the
        // whole feed; a Spring Batch restart resumes after the last committed record, so
        // this execution alone knows only what the restart processed. Summing the
        // instance is what makes the two displays describe the same set of records as the
        // published reject generation, which is also instance-scoped: a restarted
        // instance previously reported "TRANSACTIONS REJECTED :0" for records an earlier
        // execution had already committed and delivered.
        long processedCount = 0L;
        long rejectCount = 0L;
        for (JobExecution execution : executionsOfThisInstance(jobExecution)) {
            for (StepExecution stepExecution : execution.getStepExecutions()) {
                processedCount += committedRecords(stepExecution);
                // The reject count is published into the step execution context, which is
                // persisted in the SAME transaction as the chunk, so the value read back
                // is by construction the number of rejects that were COMMITTED - never a
                // rolled-back one. An execution that ended abnormally therefore still
                // contributes the rejects it delivered into the instance's generation, and
                // must: a restart resumes after the last committed record and never
                // reprocesses them, so excluding them lost them from the tally entirely.
                rejectCount += stepExecution.getExecutionContext().getLong(REJECT_COUNT_KEY, 0L);
            }
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

    /**
     * :purpose: Count the feed records one step execution actually COMMITTED, which is the
     *     quantity the legacy ``TRANSACTIONS PROCESSED`` display reports.
     * :param stepExecution: the step execution to measure.
     * :returns: the number of records committed by that step execution.
     * :note: For a step that COMPLETED, every record it read was processed and committed, so
     *     the read count is exact and is the figure the legacy display used. For a step that ended
     *     abnormally the read count is NOT usable: it includes the record of the in-flight chunk
     *     that rolled back (the observed failure read 5 and committed 4), so counting reads would
     *     report one more record than the feed holds once the restart's own reads are added. What
     *     the execution committed is what left the processor inside a committed chunk - the items
     *     written plus the items the processor filtered.
     */
    private static long committedRecords(StepExecution stepExecution) {
        if (stepExecution.getStatus() == BatchStatus.COMPLETED) {
            return stepExecution.getReadCount();
        }
        return stepExecution.getWriteCount() + stepExecution.getFilterCount();
    }

    /**
     * :purpose: Read back every execution of the job instance this execution belongs to, so
     *     the end-of-run tallies describe the whole instance.
     * :param jobExecution: the finishing execution.
     * :returns: all executions of its instance, or just this one when the instance cannot be
     *     resolved or the store returns nothing.
     * :note: The list the store returns does not carry the in-flight state of the CURRENT
     *     execution (its step executions are still being written), so this execution is always
     *     taken from the argument and the store is consulted only for the earlier ones.
     * :note: A store read that fails degrades to the current execution alone rather than
     *     propagating: the tally is the only published record of the run's outcome, so losing it -
     *     and failing an otherwise successful job from an end-of-run listener - is worse than
     *     reporting the current execution's share and saying so.
     */
    private List<JobExecution> executionsOfThisInstance(JobExecution jobExecution) {
        List<JobExecution> executions = new ArrayList<>();
        executions.add(jobExecution);
        if (jobExecution.getJobInstance() == null) {
            return executions;
        }
        try {
            for (JobExecution earlier
                    : jobRepository.getJobExecutions(jobExecution.getJobInstance())) {
                if (earlier != null && earlier.getId() != jobExecution.getId()) {
                    executions.add(earlier);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the earlier executions of posting instance {}: {}."
                            + " The tallies below cover this execution only",
                    jobExecution.getJobInstance().getInstanceId(), e.getMessage());
        }
        return executions;
    }
}

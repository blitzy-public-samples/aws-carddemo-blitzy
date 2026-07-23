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

    private static final Logger log = LoggerFactory.getLogger(PostingJobCompletionListener.class);

    /**
     * :purpose: Emit the processed and rejected tallies and, when rejects
     *           occurred, mark the job with an exit status carrying return
     *           code 4 without downgrading the batch status to failed.
     * :param jobExecution: the completed job execution whose step executions
     *        supply the read count (processed total) and the rejected count
     *        published under :data:`REJECT_COUNT_KEY`.
     * :output: Sets the ``COMPLETED_WITH_REJECTS`` exit status when the
     *          rejected total is greater than zero; otherwise leaves the
     *          default ``COMPLETED`` exit status (return code 0).
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        long processedCount = 0L;
        long rejectCount = 0L;
        for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
            processedCount += stepExecution.getReadCount();
            rejectCount += stepExecution.getExecutionContext().getLong(REJECT_COUNT_KEY, 0L);
        }

        log.info("TRANSACTIONS PROCESSED :{}", processedCount);
        log.info("TRANSACTIONS REJECTED  :{}", rejectCount);

        if (rejectCount > 0L) {
            jobExecution.setExitStatus(new ExitStatus(
                    "COMPLETED_WITH_REJECTS",
                    "Return code 4: " + rejectCount + " transaction(s) rejected"));
        }
    }
}

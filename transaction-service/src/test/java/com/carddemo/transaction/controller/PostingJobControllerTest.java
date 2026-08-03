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
package com.carddemo.transaction.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.transaction.config.PostingJobLaunchConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * :purpose: Tests for the operator entry point of the daily transaction-posting job
 *     stream (``app/jcl/POSTTRAN.jcl`` + ``CBTRN02C``). Before it existed the job had no
 *     launch surface in any deployable artefact — no endpoint, scheduler or runner
 *     referenced it — so the most business-critical batch job of the migration was
 *     reachable only from tests (QA Issue 2).
 * :output: Assertions that the job is listed, that a submission is dispatched to the
 *     launcher and acknowledged with ``202`` and a durable handle, that the business date
 *     is forwarded, that an unknown job name and a refused submission are reported as
 *     errors rather than acceptances, and that an execution's outcome can be read back.
 */
@DisplayName("PostingJobController (/transactions/batch launch surface)")
class PostingJobControllerTest {

    private PostingJobLaunchConfig postingJobLaunchConfig;
    private JobRepository jobRepository;
    private MockMvc mockMvc;

    /**
     * :purpose: Build the controller over mocked collaborators with the shared error
     *     contract applied.
     */
    @BeforeEach
    void setUp() {
        postingJobLaunchConfig = mock(PostingJobLaunchConfig.class);
        jobRepository = mock(JobRepository.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PostingJobController(postingJobLaunchConfig, jobRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * :purpose: The launchable job is listed under its verbatim bean name, so the surface
     *     is discoverable and POSTTRAN is visibly reachable.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("lists transactionPostingJob as the launchable job")
    void listsTheLaunchableJob() throws Exception {
        mockMvc.perform(get("/transactions/batch/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("transactionPostingJob"));
    }

    /**
     * :purpose: A submission is accepted with ``202`` and the durable execution handle,
     *     and the requested business date reaches the launcher unchanged.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("accepts a submission and returns the durable execution handle")
    void acceptsASubmission() throws Exception {
        when(postingJobLaunchConfig.launchTransactionPosting(eq("2026-08-03")))
                .thenReturn(execution(BatchStatus.STARTING, ExitStatus.EXECUTING));

        mockMvc.perform(post("/transactions/batch/jobs/transactionPostingJob")
                        .param("postingDate", "2026-08-03"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("transactionPostingJob"))
                .andExpect(jsonPath("$.jobExecutionId").value(42))
                .andExpect(jsonPath("$.status").value("STARTING"));

        verify(postingJobLaunchConfig).launchTransactionPosting("2026-08-03");
    }

    /**
     * :purpose: A submission that names no date is still accepted; the launcher applies
     *     the current business date, exactly as the daily job stream ran without a PARM.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("a submission without a business date is dispatched with none, letting the launcher default it")
    void acceptsASubmissionWithoutABusinessDate() throws Exception {
        when(postingJobLaunchConfig.launchTransactionPosting(isNull()))
                .thenReturn(execution(BatchStatus.STARTING, ExitStatus.EXECUTING));

        mockMvc.perform(post("/transactions/batch/jobs/transactionPostingJob"))
                .andExpect(status().isAccepted());

        verify(postingJobLaunchConfig).launchTransactionPosting(null);
    }

    /**
     * :purpose: An unknown job name is refused rather than silently ignored.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("an unknown job name is refused")
    void unknownJobIsRefused() throws Exception {
        mockMvc.perform(post("/transactions/batch/jobs/notAJob"))
                .andExpect(status().isBadRequest());
    }

    /**
     * :purpose: A refused submission — the business date's feed has already been posted —
     *     is reported to the caller instead of being acknowledged as an accepted run, so a
     *     posting cycle can never be silently double-submitted.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("a refused submission is reported as an error, never as an acceptance")
    void refusedSubmissionIsReported() throws Exception {
        when(postingJobLaunchConfig.launchTransactionPosting(eq("2026-08-03")))
                .thenThrow(new JobInstanceAlreadyCompleteException("already posted"));

        mockMvc.perform(post("/transactions/batch/jobs/transactionPostingJob")
                        .param("postingDate", "2026-08-03"))
                .andExpect(status().isBadRequest());
    }

    /**
     * :purpose: A previously launched run's outcome is readable from the durable metadata,
     *     so an asynchronous submission can be followed to completion.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("reports the outcome of a previously launched run")
    void reportsAPriorExecution() throws Exception {
        when(jobRepository.getJobExecution(42L))
                .thenReturn(execution(BatchStatus.COMPLETED, ExitStatus.COMPLETED));

        mockMvc.perform(get("/transactions/batch/jobs/executions/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobName").value("transactionPostingJob"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.exitCode").value("COMPLETED"));
    }

    /**
     * :purpose: An execution id with no persisted row answers ``404``, never the ``500`` a
     *     raw data-access failure would produce.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("an unknown execution id answers 404")
    void unknownExecutionAnswersNotFound() throws Exception {
        when(jobRepository.getJobExecution(999999L))
                .thenThrow(new EmptyResultDataAccessException(1));

        mockMvc.perform(get("/transactions/batch/jobs/executions/999999"))
                .andExpect(status().isNotFound());
    }

    /**
     * :purpose: Build a job execution fixture with the given outcome.
     * :param status: the batch status to report.
     * :param exitStatus: the exit status to report.
     * :returns: the job execution fixture.
     */
    private static JobExecution execution(BatchStatus status, ExitStatus exitStatus) {
        JobExecution jobExecution = new JobExecution(42L,
                new JobInstance(7L, "transactionPostingJob"), new JobParameters());
        jobExecution.setStatus(status);
        jobExecution.setExitStatus(exitStatus);
        return jobExecution;
    }
}

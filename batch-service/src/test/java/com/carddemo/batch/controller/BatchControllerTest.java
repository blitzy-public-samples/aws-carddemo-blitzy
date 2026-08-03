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
package com.carddemo.batch.controller;

import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.batch.config.JobSchedulingConfig;
import com.carddemo.common.config.GlobalExceptionHandler;
import org.springframework.dao.EmptyResultDataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * :purpose: Tests for the batch launch surface that makes the configured job streams
 *     reachable at runtime. Before it existed every request under ``/batch`` answered
 *     ``404``, so the ``CBTRN03C`` / ``TRANREPT`` transaction-detail report a report
 *     request must submit could not be started at all.
 * :output: Assertions that the launchable jobs are listed, that a launch is dispatched to
 *     the launcher owning that job's parameter contract and acknowledged with a durable
 *     handle, that an unknown job and a refused submission are reported as errors rather
 *     than as acceptances, and that a prior execution's outcome can be read back.
 */
@DisplayName("BatchController (/batch launch surface)")
class BatchControllerTest {

    private JobSchedulingConfig jobSchedulingConfig;
    private JobRepository jobRepository;
    private MockMvc mockMvc;

    /**
     * :purpose: Build the controller over mocked collaborators with the shared error
     *     contract applied.
     */
    @BeforeEach
    void setUp() {
        jobSchedulingConfig = mock(JobSchedulingConfig.class);
        jobRepository = mock(JobRepository.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BatchController(jobSchedulingConfig, jobRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * :purpose: The launchable jobs are listed, so the surface is discoverable and the
     *     transaction-detail report is visibly reachable.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("lists the launchable jobs")
    void listsLaunchableJobs() throws Exception {
        mockMvc.perform(get("/batch/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("transactionDetailReportJob")))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("interestCalculationJob")))
                .andExpect(jsonPath("$.length()").value(9));
    }

    /**
     * :purpose: A transaction-detail report launch reaches the launcher with the supplied
     *     window and is acknowledged with the accepted execution's durable handle.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("dispatches a transaction-detail report launch and acknowledges it")
    void dispatchesTransactionDetailReport() throws Exception {
        // No report file was named, so the launcher applies its default file name.
        when(jobSchedulingConfig.launchTransactionDetailReport("2026-08-01", "2026-08-31"))
                .thenReturn(execution(42L, "transactionDetailReportJob", BatchStatus.STARTED,
                        new ExitStatus("EXECUTING")));

        mockMvc.perform(post("/batch/jobs/transactionDetailReportJob")
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-08-31"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("transactionDetailReportJob"))
                .andExpect(jsonPath("$.jobExecutionId").value(42))
                .andExpect(jsonPath("$.status").value("STARTED"));

        verify(jobSchedulingConfig).launchTransactionDetailReport("2026-08-01", "2026-08-31");
    }

    /**
     * :purpose: An interest-calculation launch passes the business date through.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("dispatches an interest-calculation launch with its business date")
    void dispatchesInterestCalculation() throws Exception {
        when(jobSchedulingConfig.launchInterestCalculation("2026-08-01"))
                .thenReturn(execution(7L, "interestCalculationJob", BatchStatus.STARTING,
                        new ExitStatus("UNKNOWN")));

        mockMvc.perform(post("/batch/jobs/interestCalculationJob").param("parmDate", "2026-08-01"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").value(7));

        verify(jobSchedulingConfig).launchInterestCalculation("2026-08-01");
    }

    /**
     * :purpose: A file-producing job launched without an explicit name uses the launcher's
     *     configured default rather than being rejected.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("launches a dump job on its configured default output name")
    void launchesDumpJobOnConfiguredDefault() throws Exception {
        when(jobSchedulingConfig.launchAccountRead())
                .thenReturn(execution(9L, "accountReadJob", BatchStatus.STARTED,
                        new ExitStatus("EXECUTING")));

        mockMvc.perform(post("/batch/jobs/accountReadJob"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("accountReadJob"));

        verify(jobSchedulingConfig).launchAccountRead();
    }

    /**
     * :purpose: An unknown job name is an error, never a silent acceptance.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("rejects an unknown job name")
    void rejectsUnknownJob() throws Exception {
        mockMvc.perform(post("/batch/jobs/notAJob"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown batch job: notAJob"));
    }

    /**
     * :purpose: A refused submission is surfaced as an error, so the caller can never
     *     mistake a rejected launch for an accepted one.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("surfaces a refused submission instead of acknowledging it")
    void surfacesRefusedSubmission() throws Exception {
        when(jobSchedulingConfig.launchInterestCalculation(any()))
                .thenThrow(new JobExecutionAlreadyRunningException("already running"));

        mockMvc.perform(post("/batch/jobs/interestCalculationJob").param("parmDate", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        org.hamcrest.Matchers.containsString("Unable to submit batch job")));
    }

    /**
     * :purpose: The outcome of a previously launched run can be read back, which is how an
     *     asynchronous submission is followed to completion or failure.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("reports the outcome of a previous execution")
    void reportsPreviousExecutionOutcome() throws Exception {
        JobExecution finished = execution(42L, "transactionDetailReportJob", BatchStatus.FAILED,
                new ExitStatus("FAILED", "Return code 12"));
        when(jobRepository.getJobExecution(eq(42L))).thenReturn(finished);

        mockMvc.perform(get("/batch/jobs/executions/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.exitCode").value("FAILED"))
                .andExpect(jsonPath("$.exitMessage").value("Return code 12"));
    }

    /**
     * :purpose: An unknown execution id is reported as not found.
     * :raises Exception: if the request fails.
     */
    @Test
    @DisplayName("reports an unknown execution id as not found")
    void reportsUnknownExecutionAsNotFound() throws Exception {
        when(jobRepository.getJobExecution(eq(999L))).thenReturn(null);

        mockMvc.perform(get("/batch/jobs/executions/999"))
                .andExpect(status().isNotFound());
    }

    /**
     * :purpose: Build a job execution with a known identity and outcome.
     * :param executionId: the execution identifier.
     * :param jobName: the job's name.
     * :param status: the batch status.
     * :param exitStatus: the exit status.
     * :returns: the populated {@link JobExecution}.
     */
    private static JobExecution execution(long executionId, String jobName,
                                          BatchStatus status, ExitStatus exitStatus) {
        JobExecution execution = new JobExecution(executionId,
                new JobInstance(executionId, jobName), new JobParameters());
        execution.setStatus(status);
        execution.setExitStatus(exitStatus);
        return execution;
    }

    /**
     * :purpose: A blank parameter is not a value: the submission still runs on the JCL
     *  default rather than on an empty file name. Passing the blank through would name a
     *  file the job cannot write.
     * :raises Exception: if the request cannot be performed.
     */
    @Test
    @DisplayName("a blank parameter falls back to the JCL default")
    void blankParameterFallsBackToDefault() throws Exception {
        when(jobSchedulingConfig.launchAccountRead())
                .thenReturn(execution(14L, "accountReadJob", BatchStatus.STARTED, ExitStatus.EXECUTING));

        mockMvc.perform(post("/batch/jobs/accountReadJob").param("file", "   "))
                .andExpect(status().isAccepted());

        verify(jobSchedulingConfig).launchAccountRead();
        verify(jobSchedulingConfig, never()).launchAccountRead(anyString());
    }

    /**
     * :purpose: The JDBC-backed repository resolves an execution with a single-row query, so
     *  an unknown id surfaces as an empty result rather than a null return. Both spellings
     *  of "not found" must answer 404 rather than the 500 a raw data-access failure gets.
     * :raises Exception: if the request cannot be performed.
     */
    @Test
    @DisplayName("an empty result for an unknown execution id is reported as not found")
    void emptyResultForUnknownExecutionIdIsRejected() throws Exception {
        when(jobRepository.getJobExecution(999L)).thenThrow(
                new EmptyResultDataAccessException("Incorrect result size: expected 1, actual 0", 1));

        mockMvc.perform(get("/batch/jobs/executions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No batch job execution found for id 999"));
    }

}

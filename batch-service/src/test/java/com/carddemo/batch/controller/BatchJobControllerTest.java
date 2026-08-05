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

import com.carddemo.batch.config.JobSchedulingConfig;
import com.carddemo.common.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Verify the HTTP launch surface that makes the nine batch-service jobs
 *  reachable at runtime (without it, every ``launch*`` method
 *  of {@link JobSchedulingConfig} was dead code and the Spring Batch dashboard
 *  panels could never show data). It asserts the discovery catalogue, that a bare
 *  submission with no request body launches through the JCL-derived default
 *  parameters, that supplied parameters win over those defaults, that an unknown
 *  job name is rejected, and that a submitted execution can be polled by id.
 * :output: JUnit 5 assertions over a standalone {@link MockMvc} stack wrapping the
 *  controller with the shared {@link GlobalExceptionHandler}, with the launch
 *  coordinator and the batch read model mocked.
 */
class BatchJobControllerTest {

    /** :purpose: The mocked launch coordinator whose ``launch*`` methods the controller dispatches to. */
    private JobSchedulingConfig jobLaunchers;

    /** :purpose: The mocked batch read model backing the execution-status endpoint. */
    private JobRepository jobRepository;

    /** :purpose: Standalone MVC stack over the controller under test. */
    private MockMvc mockMvc;

    /**
     * :purpose: Build the standalone MVC stack with mocked collaborators and the
     *  shared exception handler, so the HTTP status contract under test is the one
     *  the deployed service applies.
     */
    @BeforeEach
    void setUp() {
        this.jobLaunchers = mock(JobSchedulingConfig.class);
        this.jobRepository = mock(JobRepository.class);
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new BatchJobController(jobLaunchers, jobRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * :purpose: Build a stub execution carrying the identity the controller renders.
     * :param jobName: the job name reported by the execution's instance.
     * :param instanceId: the job instance id.
     * :param executionId: the job execution id.
     * :returns: a {@link JobExecution} whose instance and id are populated.
     */
    private static JobExecution execution(String jobName, long instanceId, long executionId) {
        return new JobExecution(executionId, new JobInstance(instanceId, jobName), new JobParameters());
    }

    /**
     * :purpose: Verify the discovery endpoint lists all nine jobs in declaration
     *  order together with the parameters each accepts.
     */
    @Test
    @DisplayName("GET /batch/jobs lists all nine jobs and their parameters")
    void listsAllNineJobs() throws Exception {
        mockMvc.perform(get("/batch/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.interestCalculationJob[0]").value("parmDate"))
                .andExpect(jsonPath("$.accountReadJob[0]").value("outputFile"))
                .andExpect(jsonPath("$.cardReadJob[0]").value("outputFile"))
                .andExpect(jsonPath("$.cardXrefReadJob[0]").value("outputFile"))
                .andExpect(jsonPath("$.customerReadJob[0]").value("outputFile"))
                .andExpect(jsonPath("$.dailyTransactionValidationJob[0]").value("inputFile"))
                .andExpect(jsonPath("$.categoryBalanceReportJob[0]").value("outputFile"))
                .andExpect(jsonPath("$.transactionDetailReportJob[0]").value("startDate"))
                .andExpect(jsonPath("$.transactionDetailReportJob[1]").value("endDate"))
                .andExpect(jsonPath("$.transactionDetailReportJob[2]").value("reportFile"))
                .andExpect(jsonPath("$.combineTransactionsJob[0]").value("outputFile"));
    }

    /**
     * :purpose: Verify a bare submission with no request body is accepted and runs
     *  through the parameterless launcher overload, which carries the default the
     *  legacy ``EXEC`` card hard-codes.
     */
    @Test
    @DisplayName("POST with no body launches interestCalculationJob on the JCL default date")
    void launchesInterestCalculationWithJclDefaultDate() throws Exception {
        when(jobLaunchers.launchInterestCalculation())
                .thenReturn(execution("interestCalculationJob", 11L, 21L));

        mockMvc.perform(post("/batch/jobs/interestCalculationJob"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("interestCalculationJob"))
                .andExpect(jsonPath("$.jobInstanceId").value(11))
                .andExpect(jsonPath("$.jobExecutionId").value(21))
                .andExpect(jsonPath("$.status").value("STARTING"));

        verify(jobLaunchers).launchInterestCalculation();
        verify(jobLaunchers, never()).launchInterestCalculation(anyString());
    }

    /**
     * :purpose: Verify a supplied business date overrides the JCL-derived default.
     */
    @Test
    @DisplayName("POST with parmDate overrides the JCL default date")
    void suppliedParmDateWins() throws Exception {
        when(jobLaunchers.launchInterestCalculation("2024010100"))
                .thenReturn(execution("interestCalculationJob", 12L, 22L));

        mockMvc.perform(post("/batch/jobs/interestCalculationJob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parmDate\":\"2024010100\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").value(22));

        verify(jobLaunchers).launchInterestCalculation("2024010100");
    }

    /**
     * :purpose: Verify a bare submission of the date-ranged report job runs through
     *  the reporting window the legacy ``SYMNAMES`` control card hard-codes.
     */
    @Test
    @DisplayName("POST with no body launches transactionDetailReportJob on the JCL default window")
    void launchesTransactionDetailReportWithJclDefaultWindow() throws Exception {
        when(jobLaunchers.launchTransactionDetailReport(
                JobSchedulingConfig.DEFAULT_REPORT_START_DATE,
                JobSchedulingConfig.DEFAULT_REPORT_END_DATE))
                .thenReturn(execution("transactionDetailReportJob", 13L, 23L));

        mockMvc.perform(post("/batch/jobs/transactionDetailReportJob"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("transactionDetailReportJob"));

        verify(jobLaunchers).launchTransactionDetailReport("2022-01-01", "2022-07-06");
    }

    /**
     * :purpose: Verify a blank parameter value is treated as absent, so the job
     *  still launches on its default rather than on an empty file name.
     */
    @Test
    @DisplayName("Blank outputFile falls back to the default report file")
    void blankParameterFallsBackToDefault() throws Exception {
        when(jobLaunchers.launchAccountRead())
                .thenReturn(execution("accountReadJob", 14L, 24L));

        mockMvc.perform(post("/batch/jobs/accountReadJob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outputFile\":\"   \"}"))
                .andExpect(status().isAccepted());

        verify(jobLaunchers).launchAccountRead();
        verify(jobLaunchers, never()).launchAccountRead(anyString());
    }

    /**
     * :purpose: Verify a job name this service does not own is rejected with the
     *  shared not-found contract rather than launching anything.
     */
    @Test
    @DisplayName("POST of an unknown job name returns 404")
    void unknownJobNameIsRejected() throws Exception {
        mockMvc.perform(post("/batch/jobs/notAJob"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Unknown batch job: notAJob"));
    }

    /**
     * :purpose: Verify a submitted execution can be polled by the id the launch
     *  response returned.
     */
    @Test
    @DisplayName("GET /batch/jobs/executions/{id} returns the persisted execution")
    void returnsPersistedExecution() throws Exception {
        when(jobRepository.getJobExecution(24L))
                .thenReturn(execution("accountReadJob", 14L, 24L));

        mockMvc.perform(get("/batch/jobs/executions/24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobName").value("accountReadJob"))
                .andExpect(jsonPath("$.jobExecutionId").value(24))
                .andExpect(jsonPath("$.steps.length()").value(0));
    }

    /**
     * :purpose: Verify polling an execution id the repository does not know returns
     *  the shared not-found contract instead of a null body.
     */
    @Test
    @DisplayName("GET /batch/jobs/executions/{id} returns 404 for an unknown id")
    void unknownExecutionIdIsRejected() throws Exception {
        when(jobRepository.getJobExecution(999L)).thenReturn(null);

        mockMvc.perform(get("/batch/jobs/executions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Unknown batch job execution: 999"));
    }

    /**
     * :purpose: Verify the way the JDBC-backed repository actually reports an
     *  unknown id - an empty single-row result, not a ``null`` return - is answered
     *  with 404 rather than the 500 a raw data-access failure would produce.
     */
    @Test
    @DisplayName("GET /batch/jobs/executions/{id} returns 404 when the JDBC repository reports an empty result")
    void emptyResultForUnknownExecutionIdIsRejected() throws Exception {
        when(jobRepository.getJobExecution(999L))
                .thenThrow(new EmptyResultDataAccessException("Incorrect result size: expected 1, actual 0", 1));

        mockMvc.perform(get("/batch/jobs/executions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Unknown batch job execution: 999"));
    }
}

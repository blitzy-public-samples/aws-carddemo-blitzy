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
package com.carddemo.batch.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.batch.controller.BatchController;
import com.carddemo.common.batch.BatchExitMessageSanitizer;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * :purpose: Pin the per-job authority of the batch launch surface INSIDE
 *     ``batch-service`` itself, so the control holds for a request that reaches this
 *     service directly over the internal network rather than through the gateway. The
 *     eight operator jobs re-platform JCL streams — ``INTCALC``, ``READACCT``,
 *     ``READCARD``, ``READCUST``, ``READXREF``, ``PRTCATBL``, ``COMBTRAN`` and the
 *     daily-transaction validation stream — that had NO CICS transaction and therefore
 *     no online user path at all, so launching them requires ``ROLE_ADMIN``. The
 *     ``CBTRN03C`` / ``TRANREPT`` transaction-detail report is the one exception: a
 *     ``CORPT00C`` report request hands it off from main-menu option 9, a
 *     ``userType 'U'`` option, so a signed-on user may launch it.
 * :output: Assertions over the authorization outcome of each launchable job, of the
 *     read-only endpoints, and of an anonymous caller.
 * :note: ``batch-service`` disables CSRF (it is not browser-facing and issues no
 *     token), so no CSRF post-processor is applied. The principal is rebuilt by the
 *     shared ``SessionContextAuthenticationFilter`` from the Redis-backed
 *     COMMAREA-equivalent session, which the tests seed directly.
 */
@WebMvcTest(BatchController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class BatchLaunchAuthorizationTest {

    /**
     * :purpose: Minimal configuration source for the chain-focused web slice. Its
     *     presence in this package shadows the production ``BatchServiceApplication``,
     *     whose ``@EntityScan`` / ``@EnableJpaRepositories`` would otherwise demand a JPA
     *     persistence unit this slice neither provides nor needs. The launch controller
     *     is contributed explicitly and wired to the mocked collaborators; security and
     *     exception handling arrive through ``@Import``.
     * :note: The shadow applies to the WHOLE package, so any other test placed in
     *     ``com.carddemo.batch.config`` that needs the real application context must name
     *     ``BatchServiceApplication`` explicitly rather than silently booting this slice.
     */
    @SpringBootConfiguration
    static class SliceConfig {

        /**
         * :purpose: Contribute the launch surface to the slice context.
         * :param jobSchedulingConfig: the mocked on-demand launcher.
         * :param jobRepository: the mocked durable batch metadata store.
         * :returns: the controller whose request mappings the slice registers.
         */
        @Bean
        BatchController batchController(JobSchedulingConfig jobSchedulingConfig,
                                       JobRepository jobRepository) {
            return new BatchController(jobSchedulingConfig, jobRepository);
        }
    }

    /** On-demand launcher; a refused request must never reach it. */
    @MockitoBean
    private JobSchedulingConfig jobSchedulingConfig;

    /** Durable batch metadata store consulted by the status endpoint. */
    @MockitoBean
    private JobRepository jobRepository;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * :purpose: Build the session a successful sign-on leaves behind: one
     *     COMMAREA-equivalent context carrying the user id and type.
     * :param userId: the signed-on user id (``SEC-USR-ID``).
     * :param type: the signed-on user type (``SEC-USR-TYPE``).
     * :returns: a mock session carrying only that context.
     */
    private static MockHttpSession signedOnSession(String userId, SessionContext.UserType type) {
        SessionContext context = new SessionContext();
        context.setUserId(userId);
        context.setUserType(type);
        context.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.SESSION_CONTEXT, context);
        return session;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "interestCalculationJob",
            "categoryBalanceReportJob",
            "dailyTransactionValidationJob",
            "accountReadJob",
            "cardReadJob",
            "cardXrefReadJob",
            "customerReadJob",
            "combineTransactionsJob"})
    @DisplayName("a signed-on USER is refused every operator-only job launch")
    void userIsRefusedOperatorOnlyJobLaunch(String jobName) throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        mockMvc.perform(post("/batch/jobs/{jobName}", jobName).session(session))
                .andExpect(status().isForbidden());

        verifyNoInteractions(jobSchedulingConfig);
    }

    @Test
    @DisplayName("a signed-on USER may launch the transaction-detail report (CORPT00C option 9)")
    void userMayLaunchTheTransactionDetailReport() throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);
        when(jobSchedulingConfig.launchTransactionDetailReport(anyString(), anyString()))
                .thenReturn(acceptedExecution());

        mockMvc.perform(post("/batch/jobs/transactionDetailReportJob")
                        .param("startDate", "2022-01-01")
                        .param("endDate", "2022-07-06")
                        .session(session))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("a signed-on ADMIN may launch an operator-only job")
    void adminMayLaunchAnOperatorOnlyJob() throws Exception {
        MockHttpSession session =
                signedOnSession("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        when(jobSchedulingConfig.launchInterestCalculation()).thenReturn(acceptedExecution());

        mockMvc.perform(post("/batch/jobs/interestCalculationJob").session(session))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("the read-only launch-surface endpoints stay open to a signed-on USER")
    void readOnlyEndpointsStayOpenToAUser() throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        mockMvc.perform(get("/batch/jobs").session(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an anonymous caller is refused a job launch with 401")
    void anonymousIsRefusedAJobLaunch() throws Exception {
        mockMvc.perform(post("/batch/jobs/interestCalculationJob"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jobSchedulingConfig);
    }

    /**
     * :purpose: The execution-status read surface carries the operator authority. A signed-on
     *     USER could otherwise enumerate every run and read its exit description, which for a
     *     failed run is the stack trace of the cause; the same capability is already
     *     ADMIN-only in transaction-service, so the two must not disagree.
     */
    @Test
    @DisplayName("a signed-on USER is refused the execution-status read surface with 403")
    void userIsRefusedTheExecutionStatusSurface() throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        mockMvc.perform(get("/batch/jobs/executions/{id}", 6L).session(session))
                .andExpect(status().isForbidden());

        verifyNoInteractions(jobRepository);
    }

    /**
     * :purpose: An anonymous caller is refused the execution-status read surface before any
     *     lookup happens.
     */
    @Test
    @DisplayName("an anonymous caller is refused the execution-status read surface with 401")
    void anonymousIsRefusedTheExecutionStatusSurface() throws Exception {
        mockMvc.perform(get("/batch/jobs/executions/{id}", 6L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jobRepository);
    }

    /**
     * :purpose: An ADMIN reads the run, and even for that principal the response carries the
     *     OUTCOME rather than the diagnostic: the stack trace Spring Batch records as the exit
     *     description of a failed run is replaced, so the exception type, the generated SQL
     *     and the framework frames never reach the wire from any principal.
     */
    @Test
    @DisplayName("an ADMIN reads the run and the failure stack trace is not published")
    void adminReadsTheRunWithoutTheFailureStackTrace() throws Exception {
        MockHttpSession session =
                signedOnSession("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        when(jobRepository.getJobExecution(6L)).thenReturn(failedExecution());

        mockMvc.perform(get("/batch/jobs/executions/{id}", 6L).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.exitMessage")
                        .value(BatchExitMessageSanitizer.WITHHELD_MESSAGE));
    }

    /**
     * :purpose: The legacy-visible outcome text -- the ``CBTRN02C`` reject tally the posting
     *     job's own listener writes -- is still published verbatim, so sanitizing the
     *     diagnostic did not cost the caller the return code the mainframe reported.
     */
    @Test
    @DisplayName("the legacy return-code text is still published unchanged")
    void legacyReturnCodeTextIsPublishedUnchanged() throws Exception {
        MockHttpSession session =
                signedOnSession("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        JobExecution execution = new JobExecution(3L, new JobInstance(3L, "job"), new JobParameters());
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setExitStatus(new ExitStatus("COMPLETED_WITH_REJECTS",
                "Return code 4: 38 transaction(s) rejected"));
        when(jobRepository.getJobExecution(3L)).thenReturn(execution);

        mockMvc.perform(get("/batch/jobs/executions/{id}", 3L).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitCode").value("COMPLETED_WITH_REJECTS"))
                .andExpect(jsonPath("$.exitMessage")
                        .value("Return code 4: 38 transaction(s) rejected"));
    }

    /**
     * :purpose: Build the execution a failed run leaves behind, with the exit description
     *     Spring Batch actually recorded for the observed failure: the exception type, the
     *     generated UPDATE statement and a framework frame.
     * :returns: a FAILED {@link JobExecution} carrying that description.
     */
    private static JobExecution failedExecution() {
        JobExecution execution = new JobExecution(6L, new JobInstance(6L, "interestCalculationJob"),
                new JobParameters());
        execution.setStatus(BatchStatus.FAILED);
        execution.setExitStatus(new ExitStatus("FAILED",
                "org.springframework.orm.ObjectOptimisticLockingFailureException: Unexpected row "
                        + "count (expected row count 1 but was 0) [update accounts set "
                        + "acct_active_status=?,version=? where acct_id=? and version=?] for entity "
                        + "[com.carddemo.common.domain.Account with id '6']\n\tat "
                        + "org.springframework.orm.jpa.vendor.HibernateJpaDialect.java:223"));
        return execution;
    }

    /**
     * :purpose: Build the durable execution handle an accepted submission answers with.
     * :returns: a {@link JobExecution} carrying an id, its owning instance and a status.
     */
    private static JobExecution acceptedExecution() {
        JobExecution execution = new JobExecution(1L,
                new JobInstance(1L, "job"), new JobParameters());
        execution.setStatus(BatchStatus.STARTED);
        execution.setExitStatus(ExitStatus.EXECUTING);
        return execution;
    }
}

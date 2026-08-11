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
package com.carddemo.transaction.config;

import com.carddemo.common.exception.CardDemoException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * :purpose: Pin the validation of ``postingDate``, the IDENTIFYING job parameter of a posting
 *     cycle. It is the instance key of the run and it is persisted verbatim in
 *     ``BATCH_JOB_EXECUTION_PARAMS`` as the audit record of the run, so while it was
 *     unvalidated any caller could mint unlimited job instances that re-post the same feed
 *     under names like ``NOT-A-DATE``, ``2026-13-45``, 24 nines or an injection string, and
 *     the batch audit trail then carried them permanently. The legacy ``PARM`` was a real
 *     date consumed by the program, so the shared ``CSUTLDTC`` replacement is the gate —
 *     ``DateUtil`` with STRICT resolution [app/cbl/CSUTLDTC.cbl].
 * :output: Assertions that a malformed or impossible date is refused BEFORE the batch job
 *     repository is touched at all (so no instance and no audit row can exist), that a real
 *     date reaches the repository verbatim, and that a submission naming no date still posts
 *     the current day's feed.
 */
@DisplayName("postingDate validation (POSTTRAN launch surface)")
class PostingDateValidationTest {

    /** Wire format of the business date, as the legacy PARM carried it. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Name the posting job is recorded under. */
    private static final String JOB_NAME = "transactionPostingJob";

    /** Mocked batch job repository: every job instance and audit row would be created here. */
    private final JobRepository jobRepository = mock(JobRepository.class);

    /** Stand-in for the ``transactionPostingJob`` bean. */
    private final Job postingJob = mock(Job.class);

    /** The launcher under test, built over the mocked repository. */
    private final PostingJobLaunchConfig launcher =
            new PostingJobLaunchConfig(jobRepository, postingJob);

    /**
     * :purpose: Supply the two parts of the {@link Job} contract the batch operator consults
     *     on the accepted path — the job name it records the instance under and the parameter
     *     validator it applies — which a bare mock would return as ``null``.
     */
    @BeforeEach
    void stubJobContract() {
        when(postingJob.getName()).thenReturn(JOB_NAME);
        when(postingJob.getJobParametersValidator())
                .thenReturn(new DefaultJobParametersValidator());
        // The operator records the run before executing it and then reads the execution back;
        // a bare mock would hand it null. Returning a real JobExecution built from the
        // submitted parameters is what a live repository does, and it keeps the accepted
        // path running far enough for the persisted parameters to be observable.
        when(jobRepository.createJobExecution(any(), any(JobParameters.class), any()))
                .thenAnswer(invocation -> new JobExecution(
                        1L,
                        new JobInstance(1L, JOB_NAME),
                        invocation.getArgument(1, JobParameters.class)));
    }

    /**
     * :purpose: Recover the job parameters that actually reached the batch job repository,
     *     which is the point at which a job instance and its ``BATCH_JOB_EXECUTION_PARAMS``
     *     audit row come into being.
     * :returns: the first {@link JobParameters} handed to the repository, empty when the
     *     submission never got that far.
     * :note: The parameters are recovered by scanning the recorded interactions rather than
     *     by verifying one named repository method, so the assertion pins the observable
     *     contract (what gets persisted) without encoding which internal call the operator
     *     happens to use to persist it.
     */
    private Optional<JobParameters> submittedParameters() {
        return mockingDetails(jobRepository).getInvocations().stream()
                .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
                .filter(JobParameters.class::isInstance)
                .map(JobParameters.class::cast)
                .findFirst();
    }

    /**
     * :purpose: Every value the QA run submitted — plus an impossible calendar date and the
     *     alternative date formats a caller might assume — is refused with the domain
     *     message, and the batch job repository is never touched, so no job instance and no
     *     audit row is created.
     * :param submitted: the value that must be refused.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "NOT-A-DATE",
        "2026-13-45",
        "2026-02-30",
        "2025-02-29",
        "999999999999999999999999",
        "2026-07-23' OR 1=1--",
        "<script>alert(1)</script>",
        "../../etc/passwd",
        "26-07-23",
        "2026/07/23",
        "07-23-2026"})
    @DisplayName("an invalid business date is refused before any job instance exists")
    void invalidBusinessDateIsRefused(String submitted) {
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> launcher.launchTransactionPosting(submitted))
                .withMessage(PostingJobLaunchConfig.INVALID_POSTING_DATE_MESSAGE);

        verifyNoInteractions(jobRepository);
    }

    /**
     * :purpose: The refusal quotes neither the submitted value nor any internal detail, so a
     *     hostile string is never reflected back to the caller.
     */
    @Test
    @DisplayName("the refusal does not echo the submitted value")
    void refusalDoesNotEchoTheSubmittedValue() {
        String hostile = "<script>alert(1)</script>";

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> launcher.launchTransactionPosting(hostile))
                .withMessageNotContaining("script")
                .withMessageNotContaining(hostile);
    }

    /**
     * :purpose: A real date is accepted and reaches the repository verbatim, so the instance
     *     key of a posting cycle — and with it the once-only posting guarantee — is unchanged
     *     by the new validation.
     * :raises Exception: if the submission is refused.
     */
    @Test
    @DisplayName("a valid business date reaches the repository verbatim")
    void validBusinessDateIsAcceptedVerbatim() throws Exception {
        launcher.launchTransactionPosting("2026-09-02");

        assertThat(submittedParameters())
                .as("job parameters reaching the batch job repository")
                .isPresent()
                .get()
                .extracting(parameters -> parameters.getString(
                        PostingJobLaunchConfig.POSTING_DATE_KEY))
                .isEqualTo("2026-09-02");
    }

    /**
     * :purpose: A leap day in a real leap year is a valid business date, proving the STRICT
     *     resolution rejects impossible dates without rejecting rare legitimate ones.
     * :raises Exception: if the submission is refused.
     */
    @Test
    @DisplayName("a leap day in a leap year is accepted")
    void leapDayIsAccepted() throws Exception {
        launcher.launchTransactionPosting("2024-02-29");

        assertThat(submittedParameters())
                .isPresent()
                .get()
                .extracting(parameters -> parameters.getString(
                        PostingJobLaunchConfig.POSTING_DATE_KEY))
                .isEqualTo("2024-02-29");
    }

    /**
     * :purpose: A submission that names no date still posts the current day's feed, exactly
     *     as the daily job stream ran without a ``PARM``.
     * :param submitted: an absent date, as null, empty or whitespace.
     * :raises Exception: if the submission is refused.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("an absent business date defaults to the current date")
    void absentBusinessDateDefaultsToToday(String submitted) throws Exception {
        launcher.launchTransactionPosting(submitted);

        assertThat(submittedParameters())
                .isPresent()
                .get()
                .extracting(parameters -> parameters.getString(
                        PostingJobLaunchConfig.POSTING_DATE_KEY))
                .isEqualTo(LocalDate.now().format(ISO));
    }

    /**
     * :purpose: A ``null`` date is the unattended-submission case and must behave as the
     *     blank case does, defaulting to the current date rather than failing.
     * :raises Exception: if the submission is refused.
     */
    @Test
    @DisplayName("a null business date defaults to the current date")
    void nullBusinessDateDefaultsToToday() throws Exception {
        launcher.launchTransactionPosting(null);

        assertThat(submittedParameters())
                .isPresent()
                .get()
                .extracting(parameters -> parameters.getString(
                        PostingJobLaunchConfig.POSTING_DATE_KEY))
                .isEqualTo(LocalDate.now().format(ISO));
    }

    /**
     * :purpose: A date carrying surrounding whitespace is accepted and normalized, so an
     *     operator submission is not refused over padding the legacy ``PARM`` tolerated.
     * :raises Exception: if the submission is refused.
     */
    @Test
    @DisplayName("surrounding whitespace is trimmed rather than refused")
    void surroundingWhitespaceIsTrimmed() throws Exception {
        launcher.launchTransactionPosting("  2026-09-02  ");

        assertThat(submittedParameters())
                .isPresent()
                .get()
                .extracting(parameters -> parameters.getString(
                        PostingJobLaunchConfig.POSTING_DATE_KEY))
                .isEqualTo("2026-09-02");
    }
}

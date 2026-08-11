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
package com.carddemo.common.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * :purpose: Pin the contract of {@link BatchExitMessageSanitizer}: the legacy-visible outcome
 *     text of a batch run reaches the caller unchanged, and every framework diagnostic shape
 *     is replaced. The fixture strings are the values this application actually produced --
 *     the 41-character reject tally of a COMPLETED_WITH_REJECTS run and the 2,500-character
 *     stack trace of the observed FAILED run.
 * :output: Assertions over each publishable and each withheld shape.
 */
class BatchExitMessageSanitizerTest {

    /**
     * :purpose: The exit description recorded for the failed interest-calculation run, in the
     *     shape Spring Batch stored it: exception type, generated SQL with its bound
     *     parameters, entity and identifier, then the stack frames.
     */
    private static final String FAILED_RUN_DESCRIPTION =
            "org.springframework.orm.ObjectOptimisticLockingFailureException: Unexpected row count "
                    + "(expected row count 1 but was 0) [update accounts set acct_active_status=?,"
                    + "acct_addr_zip=?,acct_cash_credit_limit=?,version=? where acct_id=? and version=?] "
                    + "for entity [com.carddemo.common.domain.Account with id '6']\n"
                    + "\tat org.springframework.orm.jpa.vendor.HibernateExceptionTranslator"
                    + ".convertHibernateAccessException(HibernateExceptionTranslator.java:204)\n"
                    + "\tat org.springframework.orm.jpa.vendor.HibernateJpaDialect"
                    + ".translateExceptionIfPossible(HibernateJpaDialect.java:223)\n"
                    + "\tat org.springframework.batch.core.step.tasklet.TaskletStep"
                    + ".doExecute(TaskletStep.java:277)";

    @Test
    @DisplayName("the legacy reject tally is published unchanged")
    void publishesTheLegacyRejectTally() {
        String legacy = "Return code 4: 38 transaction(s) rejected";

        assertThat(BatchExitMessageSanitizer.sanitize(legacy)).isEqualTo(legacy);
    }

    @Test
    @DisplayName("an absent or empty description is published as it is")
    void publishesAbsentAndEmptyDescriptions() {
        assertThat(BatchExitMessageSanitizer.sanitize(null)).isNull();
        assertThat(BatchExitMessageSanitizer.sanitize("")).isEmpty();
    }

    @Test
    @DisplayName("the failed run's stack trace is withheld in full")
    void withholdsTheFailedRunStackTrace() {
        String sanitized = BatchExitMessageSanitizer.sanitize(FAILED_RUN_DESCRIPTION);

        assertThat(sanitized).isEqualTo(BatchExitMessageSanitizer.WITHHELD_MESSAGE);
        // Nothing of the diagnostic survives: not the type, the statement, the entity, the
        // identifier, the frames or the source line numbers.
        assertThat(sanitized)
                .doesNotContain("Exception")
                .doesNotContain("update accounts")
                .doesNotContain("com.carddemo")
                .doesNotContain(".java:")
                .doesNotContain("\tat ")
                .doesNotContain("=?");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // A bare exception type on one line: caught by the dotted-identifier rule.
            "java.lang.IllegalStateException: step failed",
            // A single stack frame with its source reference.
            "at com.carddemo.batch.Job.run(Job.java:42)",
            // A statement echoed with no type name anywhere: caught by the SQL rule.
            "could not execute select acct_id from accounts",
            "insert into transactions (tran_id) values (?)",
            "delete from card_xref where xref_card_num = ?",
            // A host name, which is topology information.
            "connection refused to postgres.carddemo.internal"})
    @DisplayName("every framework diagnostic shape is withheld")
    void withholdsEveryDiagnosticShape(String description) {
        assertThat(BatchExitMessageSanitizer.sanitize(description))
                .isEqualTo(BatchExitMessageSanitizer.WITHHELD_MESSAGE);
    }

    @Test
    @DisplayName("a description longer than the publishable bound is withheld even single-line")
    void withholdsAnOverlongSingleLineDescription() {
        String overlong = "Return code 4: " + "9".repeat(BatchExitMessageSanitizer.MAX_PUBLISHABLE_LENGTH);

        assertThat(BatchExitMessageSanitizer.sanitize(overlong))
                .isEqualTo(BatchExitMessageSanitizer.WITHHELD_MESSAGE);
    }

    @Test
    @DisplayName("prose keeps its sentence-ending periods")
    void publishesProseWithSentencePeriods() {
        String prose = "Return code 0. No transactions were rejected.";

        assertThat(BatchExitMessageSanitizer.sanitize(prose)).isEqualTo(prose);
    }

    @Test
    @DisplayName("the sanitizer is a static utility and cannot be instantiated")
    void cannotBeInstantiated() throws Exception {
        var constructor = BatchExitMessageSanitizer.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .hasRootCauseInstanceOf(AssertionError.class);
    }
}

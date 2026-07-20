/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch.writer;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.StepExecution;

/**
 * Shared <strong>fail-closed</strong> decision that governs whether an end-of-step external file
 * may be atomically published to its final name, used by every batch writer that stages output to a
 * temporary {@code .part} file in {@code beforeStep}/write and then, in
 * {@link org.springframework.batch.core.StepExecutionListener#afterStep(StepExecution) afterStep},
 * either atomically renames it to the final name (on success) or discards it (on failure).
 *
 * <h2>Why this decision must be fail-closed (QA finding F-P5-E)</h2>
 *
 * <p>The external fixed-width file layouts CardDemo emits (transaction backup, daily-reject file,
 * interest {@code SYSTRAN}, statements, transaction report) are part of the migrated external
 * interface contract (AAP &sect;0.8.1 G3). A consumer must never observe a <em>final-named partial
 * file</em>: on the mainframe a GDG generation was cataloged only once the writing step completed
 * cleanly, so the Java re-platform must publish the final name only on a positively-verified clean
 * completion.</p>
 *
 * <p>The writers originally gated the publish on a <em>negative</em> check of the in-memory step
 * status &mdash; {@code stepExecution.getStatus() != BatchStatus.FAILED} (or the equivalent
 * {@code == BatchStatus.FAILED} inverted). That gate is <strong>fail-open</strong> and was proven
 * (5/5 deterministic) to publish a partial file on a mid-write database-connection loss:</p>
 *
 * <ul>
 *   <li>On the <strong>success</strong> path, {@code AbstractStep.execute} sets
 *       {@link BatchStatus#COMPLETED} <em>before</em> it invokes {@code afterStep}, so at
 *       {@code afterStep} time the status is reliably {@code COMPLETED}.</li>
 *   <li>On the <strong>connection-loss</strong> path the chunk rollback itself fails ("Rolling back
 *       with transaction in unknown state", "Application exception overridden by rollback
 *       exception"); the batch-metadata update that would durably mark the step {@code FAILED} and
 *       record the failure exception runs <em>after</em> {@code afterStep} (and then itself fails on
 *       the dead connection). At {@code afterStep} time the in-memory status is therefore still
 *       {@code STARTED} &mdash; neither {@code COMPLETED} nor {@code FAILED} &mdash; and
 *       {@link StepExecution#getFailureExceptions()} can still be empty. A negative
 *       {@code != FAILED} gate consequently passes and the partial staging file is renamed to its
 *       final name, even though the job ultimately returns {@code RC=8 / FAILED}.</li>
 * </ul>
 *
 * <p>The fix is to publish only on the <strong>positive</strong> {@code COMPLETED} signal, which is
 * set on (and only on) the success path before {@code afterStep}. This rejects every non-clean
 * outcome uniformly: the {@code STARTED} connection-loss race, an explicit {@code FAILED}, and the
 * {@code UNKNOWN}/{@code STOPPED}/{@code ABANDONED} terminal states. The additional
 * {@link StepExecution#getFailureExceptions()} emptiness check and the writer-supplied {@code ioError}
 * flag are defense-in-depth for a step that reached {@code COMPLETED} yet recorded a failure
 * exception or suffered a flush/close/publish I/O error at the file boundary.</p>
 *
 * <p>Because the CardDemo batch steps are chunk-oriented and <em>not</em> fault-tolerant (no skip /
 * retry policy), Spring Batch sets {@code COMPLETED} only after the reader is fully drained, so a
 * {@code COMPLETED} status is itself the authoritative "all items were processed" signal; no
 * separate expected-record-count reconciliation is required.</p>
 *
 * <p>This type is a stateless, package-private utility with a single static predicate; it is
 * intentionally not a Spring bean so the writers can consult it without injection and it remains
 * directly unit-testable.</p>
 *
 * @see org.springframework.batch.core.StepExecutionListener#afterStep(StepExecution)
 */
final class BatchFilePublishDecision {

    private BatchFilePublishDecision() {
        // Utility class: no instances.
    }

    /**
     * Reports whether a staged external output file may be published to its final name, returning
     * {@code true} only for a positively-verified clean completion.
     *
     * <p>Publication is permitted when <em>all</em> of the following hold:</p>
     * <ol>
     *   <li>{@code stepExecution} is non-{@code null} and its {@link StepExecution#getStatus()
     *       status} is exactly {@link BatchStatus#COMPLETED} (the positive success signal set before
     *       {@code afterStep}; note {@code COMPLETED} also covers the "completed with rejects" RC-4
     *       case, whose warning is carried in the {@link org.springframework.batch.core.ExitStatus}
     *       code string, not the {@link BatchStatus});</li>
     *   <li>the step recorded no failure exceptions
     *       ({@link StepExecution#getFailureExceptions()} is empty); and</li>
     *   <li>the calling writer observed no I/O error at its own file boundary
     *       ({@code ioError} is {@code false}) &mdash; this includes an open/write/flush/close
     *       failure and, when re-evaluated after the publish attempt, a failed atomic move.</li>
     * </ol>
     *
     * @param stepExecution the completing step execution; may be {@code null}, which is treated as
     *                      not-clean (never publish)
     * @param ioError       {@code true} if the writer observed any I/O error affecting the staged
     *                      output; when {@code true} the result is always {@code false}
     * @return {@code true} only when the step completed cleanly and no I/O error occurred, so the
     *         staged file may be atomically published; {@code false} otherwise (the caller must
     *         discard the staging file and publish nothing)
     */
    static boolean isCleanCompletion(StepExecution stepExecution, boolean ioError) {
        return stepExecution != null
                && !ioError
                && stepExecution.getStatus() == BatchStatus.COMPLETED
                && stepExecution.getFailureExceptions().isEmpty();
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/**
 * :purpose: Remove the output a step was writing when that step does not complete
 *  successfully, so a failed run leaves nothing behind that could be mistaken for a
 *  finished one. A flat-file writer opens its resource when the step starts and closes it
 *  when the step ends whatever the outcome, so a failed run previously left a file
 *  carrying the legacy start and end banners and no data records at all - the exact shape
 *  of a successful empty run - or a zero-byte report.
 * :output: A {@link StepExecutionListener} that deletes the registered paths after an
 *  unsuccessful step and leaves the step's own exit status untouched.
 * :note: A successful step is never touched. Deletion failures are logged rather than
 *  raised: the step has already failed, and masking its cause with an I/O error from the
 *  cleanup would lose the real reason for the failure.
 * :note: Success is a ``COMPLETED`` batch status together with an exit code that begins
 *  with ``COMPLETED``. Spring Batch's own convention is that a step which succeeded with
 *  something to report carries a qualified success code — ``COMPLETED_WITH_SKIPS``, and in
 *  this system ``COMPLETED_WITH_REJECTS`` for a posting run that produced the ``DALYREJS``
 *  reject generation — and the file such a run wrote is precisely its deliverable. A
 *  business FAILURE signalled through the exit code alone (for example
 *  ``FAILED_EMPTY_FEED``) is still cleaned up.
 * :note: Where a step's output name comes from a job parameter, the listener must be
 *  declared ``@StepScope`` and resolve that name through the same
 *  {@link BatchOutputPathResolver} the writer uses, so it deletes the file the execution
 *  actually opened.
 */
public class FailedOutputCleanupListener implements StepExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(FailedOutputCleanupListener.class);

    /** Files this step writes, removed when the step does not complete successfully. */
    private final List<Path> outputPaths;

    /**
     * :purpose: Register the output paths a step writes.
     * :param outputPaths: the resolved paths to remove on an unsuccessful step; null
     *  entries are ignored so an optional second output can be passed unconditionally.
     */
    public FailedOutputCleanupListener(Path... outputPaths) {
        // Stream.filter rather than List.of: List.of rejects a null element outright,
        // which would break the documented contract that an absent optional output may
        // be passed unconditionally.
        this.outputPaths = Arrays.stream(outputPaths)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * :purpose: Delete the registered output when the step did not complete
     *  successfully.
     * :param stepExecution: the finished step execution.
     * :returns: ``null``, leaving the step's own exit status in place.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (succeeded(stepExecution)) {
            return null;
        }
        for (Path path : outputPaths) {
            try {
                if (Files.deleteIfExists(path)) {
                    LOGGER.warn("Step {} ended {}; removed its incomplete output {}",
                            stepExecution.getStepName(), stepExecution.getStatus(), path);
                }
            } catch (IOException e) {
                // Deliberately not rethrown: the step has already failed and its cause
                // must remain the reported one.
                LOGGER.error("Step {} ended {}; its incomplete output {} could not be removed: {}",
                        stepExecution.getStepName(), stepExecution.getStatus(), path,
                        e.getMessage());
            }
        }
        return null;
    }

    /**
     * :purpose: Decide whether a finished step succeeded, and may therefore keep the file
     *  it wrote.
     * :param stepExecution: the finished step execution.
     * :returns: ``true`` when the batch status is ``COMPLETED`` and the exit code is
     *  ``COMPLETED`` or a qualified success code beginning with it (for example
     *  ``COMPLETED_WITH_REJECTS``); ``false`` for any failure, stop, or business failure
     *  signalled through the exit code.
     */
    private static boolean succeeded(StepExecution stepExecution) {
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            return false;
        }
        ExitStatus exitStatus = stepExecution.getExitStatus();
        String exitCode = (exitStatus == null) ? null : exitStatus.getExitCode();
        return exitCode != null && exitCode.startsWith(ExitStatus.COMPLETED.getExitCode());
    }

}

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
package com.carddemo.common.dto;

/**
 * :purpose: Carry the identity and outcome of one batch job execution across the batch
 *  launch boundary, so a submission is acknowledged with a durable handle and its later
 *  outcome can be polled. It replaces the legacy fire-and-forget TDQ ``'JOBS'`` write,
 *  after which the submitting program had no handle on the job it had queued.
 * :output: An immutable record serialized as the response of the batch launch and
 *  execution-status endpoints, and deserialized by the reporting-service client that
 *  submits the transaction-detail report.
 * :note: A no-argument constructor is not required: the record is deserialized through
 *  its canonical constructor.
 *
 * :param jobName: name of the launched job, e.g. ``transactionDetailReportJob``.
 * :param jobExecutionId: durable ``BATCH_JOB_EXECUTION`` identifier of this run.
 * :param jobInstanceId: durable ``BATCH_JOB_INSTANCE`` identifier this run belongs to.
 * :param status: batch status at the time of the response (``STARTING``, ``STARTED``,
 *  ``COMPLETED``, ``FAILED``).
 * :param exitCode: exit code once the run has finished, otherwise the in-flight code.
 * :param exitMessage: exit description once the run has finished; empty while running.
 */
public record BatchJobExecutionDto(String jobName,
                                   Long jobExecutionId,
                                   Long jobInstanceId,
                                   String status,
                                   String exitCode,
                                   String exitMessage) {
}

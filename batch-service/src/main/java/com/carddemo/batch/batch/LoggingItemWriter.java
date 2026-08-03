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

package com.carddemo.batch.batch;

import com.carddemo.common.config.CorrelationIdContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * :purpose: Generic, log-only {@link ItemWriter} that records processed-record
 *  counts for the sequential read/print "dump" jobs migrated from the legacy
 *  COBOL batch programs ``CBACT01C`` (account master), ``CBACT02C`` (card
 *  master), ``CBACT03C`` (card cross-reference) and ``CBCUS01C`` (customer
 *  master), and reused as the pass-through sink for the ``CBTRN01C``
 *  daily-transaction validation-read pass. It is the Java analogue of the
 *  legacy ``DISPLAY`` record sink and emits counts only — the record-type label
 *  and the number of records seen — never the contents of any item, so no card
 *  number, CVV, SSN, government id, customer name or balance reaches the log
 *  stream (PII safety).
 * :note: The class carries no annotation of its own: the sibling ``config``
 *  package declares one writer per consuming step as a ``@Bean @StepScope``
 *  factory method returning ``new LoggingItemWriter<>(label)``. The step scope is
 *  part of the contract, because the running total below belongs to one step
 *  execution: a single instance shared by a singleton step accumulated across
 *  every execution and interleaved between concurrent ones.
 * :note: Structured JSON logging and the ``correlationId`` MDC key are supplied
 *  by the module's ``logback-spring.xml`` together with
 *  {@link CorrelationIdContext}; this writer only ensures a correlation id is
 *  present before it logs.
 * :param <T>: the item type streamed by the step; its contents are never
 *  inspected or logged.
 */
public class LoggingItemWriter<T> implements ItemWriter<T> {

    /** SLF4J logger; the ``correlationId`` MDC value is rendered by ``logback-spring.xml``. */
    private static final Logger log = LoggerFactory.getLogger(LoggingItemWriter.class);

    /** Record-type label applied when the constructor is supplied a ``null`` label. */
    private static final String DEFAULT_LABEL = "record";

    /**
     * Human-readable record-type label (e.g. ``account``, ``card``, ``cardXref``,
     * ``customer``, ``dailyTransactionValidation``) reported in each log line.
     */
    private final String label;

    /**
     * Cumulative count of records written across every chunk of ONE step
     * execution, mirroring the running record count the COBOL dump implicitly
     * walked through. Its per-execution meaning depends on the writer being
     * step-scoped (see the class note).
     */
    private final AtomicLong runningTotal = new AtomicLong(0);

    /**
     * :purpose: Create a writer that reports counts under the given record-type
     *  label.
     * :param label: the human-readable record-type label included in each log
     *  line; a ``null`` label defaults to ``record``.
     */
    public LoggingItemWriter(String label) {
        this.label = (label != null) ? label : DEFAULT_LABEL;
    }

    /**
     * :purpose: Count the items in the delivered chunk and emit a single
     *  aggregate INFO log line for the chunk, stamped with the MDC correlation
     *  id; performs no persistence or mutation.
     * :param chunk: the chunk of items delivered by the step; only its size is
     *  read — item contents are never inspected or logged.
     * :output: increments the running total by the chunk size and logs one line
     *  reporting the number of records added and the running total under the
     *  configured record-type label.
     */
    @Override
    public void write(Chunk<? extends T> chunk) {
        CorrelationIdContext.getOrCreateCorrelationId();
        long added = chunk.size();
        long total = runningTotal.addAndGet(added);
        log.info("Processed {} {} record(s); running total {}", added, label, total);
    }
}

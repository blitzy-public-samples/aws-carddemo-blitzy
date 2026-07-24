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
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemWriter;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.transform.LineAggregator;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * :purpose: Restartable {@link ItemStreamWriter} that prints each record of a
 *  data-management read job to a controlled output file, reproducing the
 *  ``DISPLAY`` dump of the legacy read/print COBOL programs (``CBACT01C``,
 *  ``CBACT02C``, ``CBACT03C``, ``CBCUS01C``). A start banner
 *  (``START OF EXECUTION OF PROGRAM ...``) is written before the first record
 *  and an end banner (``END OF EXECUTION OF PROGRAM ...``) after the last,
 *  matching the program's opening and closing ``DISPLAY`` lines. Each record is
 *  rendered by the supplied {@link LineAggregator}; the enclosing
 *  {@link FlatFileItemWriter} appends the line separator and persists its own
 *  restart state, so a restart resumes appending after the last committed
 *  record.
 * :output: One output file at the resolved path holding the start banner, one
 *  block per record, and the end banner. Only an aggregate record count is
 *  logged on close; no record content is ever logged (PII safety).
 * :note: The output path is resolved and confined to the configured batch output
 *  root by {@link BatchOutputPathResolver} before this writer is constructed, so
 *  the ``FileSystemResource`` here always refers to an authorized location.
 */
public class RecordDumpItemWriter<T> implements ItemStreamWriter<T> {

    /** SLF4J logger; the ``correlationId`` MDC value is rendered by ``logback-spring.xml``. */
    private static final Logger log = LoggerFactory.getLogger(RecordDumpItemWriter.class);

    /** Line separator used for banners and between records; fixed to LF for determinism. */
    private static final String LINE_SEPARATOR = "\n";

    /** Delegating flat-file writer that owns the output resource and restart state. */
    private final FlatFileItemWriter<T> delegate;

    /** Logical writer name, used in the completion log line. */
    private final String name;

    /** Running count of records written by this writer instance. */
    private long writtenCount;

    /**
     * :purpose: Build the record-dump writer over a resolved, authorized output
     *  path, wiring the per-record formatter and the start/end program banners.
     * :param name: the logical writer name (also the {@link FlatFileItemWriter}
     *  name); appears in the completion log line.
     * :param resolvedPath: the output file path already resolved and confined to
     *  the configured output root.
     * :param startBanner: the banner written before the first record.
     * :param endBanner: the banner written after the last record.
     * :param lineAggregator: renders each record into its printed representation.
     */
    public RecordDumpItemWriter(String name,
                                Path resolvedPath,
                                String startBanner,
                                String endBanner,
                                LineAggregator<T> lineAggregator) {
        this.name = name;
        this.delegate = new FlatFileItemWriterBuilder<T>()
                .name(name)
                .resource(new FileSystemResource(resolvedPath))
                .encoding(StandardCharsets.ISO_8859_1.name())
                .lineSeparator(LINE_SEPARATOR)
                .lineAggregator(lineAggregator)
                .headerCallback(writer -> writer.write(startBanner))
                .footerCallback(writer -> writer.write(endBanner))
                .build();
    }

    /**
     * :purpose: Open the delegating writer and its output resource at the start
     *  of the step, ensuring a correlation id is present for the run.
     * :param executionContext: the step execution context passed to the delegate.
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        CorrelationIdContext.getOrCreateCorrelationId();
        delegate.open(executionContext);
    }

    /**
     * :purpose: Persist the delegate's stream state to the step execution context
     *  between chunks so a restart resumes after the last committed record.
     * :param executionContext: the step execution context passed to the delegate.
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        delegate.update(executionContext);
    }

    /**
     * :purpose: Flush and release the delegating writer and its output resource at
     *  the end of the step and log the aggregate record count.
     */
    @Override
    public void close() throws ItemStreamException {
        try {
            delegate.close();
        } finally {
            log.info("{} wrote {} record(s) to the dump output file", name, writtenCount);
        }
    }

    /**
     * :purpose: Write one chunk of already-ordered records to the output file by
     *  delegating to the {@link FlatFileItemWriter}, which renders each record
     *  through the configured {@link LineAggregator}.
     * :param chunk: the ordered batch of records to render; read but never
     *  modified.
     */
    @Override
    public void write(Chunk<? extends T> chunk) throws Exception {
        CorrelationIdContext.getOrCreateCorrelationId();
        delegate.write(chunk);
        writtenCount += chunk.size();
    }
}

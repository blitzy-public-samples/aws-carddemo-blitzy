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
package com.carddemo.common.config;

import com.carddemo.common.batch.BatchOutputPathResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * :purpose: Contribute the shared {@link BatchOutputPathResolver} to any service that
 *  writes or reads batch files, so every generated artefact lands in one configured,
 *  allowlisted, writable root instead of a working-directory-relative path. The
 *  delivered containers run with a read-only root filesystem and ``WorkingDir=/app``,
 *  where a relative name such as ``output/statements.txt`` or ``dalyrejs.txt`` can never
 *  be created; the resolver's default root lives under the JVM temporary directory,
 *  which is a writable tmpfs in those containers.
 * :output: A single ``batchOutputPathResolver`` bean shared by the statement, reject,
 *  report, dump and combine writers across services.
 * :note: Imported explicitly by each service application class, because service
 *  component scanning is confined to that service's own package and therefore never
 *  reaches ``com.carddemo.common``.
 */
@Configuration
public class BatchOutputConfig {

    /**
     * :purpose: Build the shared batch path resolver from the configured roots.
     * :param outputDir: configured output root (``carddemo.batch.output-dir``); when
     *  blank, ``<java.io.tmpdir>/carddemo-batch/output`` is used.
     * :param inputDir: configured input root (``carddemo.batch.input-dir``); when blank,
     *  ``<java.io.tmpdir>/carddemo-batch/input`` is used.
     * :returns: the resolver confining every batch file to an allowlisted root.
     */
    @Bean
    public BatchOutputPathResolver batchOutputPathResolver(
            @Value("${carddemo.batch.output-dir:}") String outputDir,
            @Value("${carddemo.batch.input-dir:}") String inputDir) {
        return new BatchOutputPathResolver(outputDir, inputDir);
    }

}

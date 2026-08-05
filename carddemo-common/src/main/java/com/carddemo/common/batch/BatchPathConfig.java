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

package com.carddemo.common.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * :purpose: Declare the shared {@link BatchOutputPathResolver} bean for the two
 *  batch-capable CardDemo services. It is imported explicitly (never
 *  auto-configured) so only the services that actually write or read batch files
 *  - ``batch-service`` for its nine data-management jobs and
 *  ``reporting-service`` for the ``CBSTM03A`` statement writer - materialize the
 *  batch working directories; the other seven services never create one.
 * :output: A single ``batchOutputPathResolver`` bean whose output and input roots
 *  are bound from ``carddemo.batch.output-dir`` and ``carddemo.batch.input-dir``.
 *  Both roots are created, canonicalized and (for the output root) proven
 *  writable while the bean is constructed, i.e. during context refresh, so a
 *  container that was started without a writable batch directory fails fast with
 *  a precise message instead of accepting a job launch and then failing the step
 *  with ``java.io.IOException: No such file or directory``.
 * :note: When a property is blank the resolver falls back to
 *  ``<java.io.tmpdir>/carddemo-batch/{output,input}``, which keeps the jobs
 *  runnable on a workstation and inside a container whose only writable mount is
 *  ``/tmp``. Deployments set the two properties (``CARDDEMO_BATCH_OUTPUT_DIR`` /
 *  ``CARDDEMO_BATCH_INPUT_DIR``) to a mounted directory.
 */
@Configuration(proxyBeanMethods = false)
public class BatchPathConfig {

    /**
     * :purpose: Build the path resolver that confines every batch file the service
     *  opens to an allowlisted root (CWE-22).
     * :param outputDir: the ``carddemo.batch.output-dir`` value; blank selects the
     *  temporary-directory fallback.
     * :param inputDir: the ``carddemo.batch.input-dir`` value; blank selects the
     *  temporary-directory fallback.
     * :returns: the configured {@link BatchOutputPathResolver}.
     */
    @Bean
    public BatchOutputPathResolver batchOutputPathResolver(
            @Value("${carddemo.batch.output-dir:}") String outputDir,
            @Value("${carddemo.batch.input-dir:}") String inputDir) {
        return new BatchOutputPathResolver(outputDir, inputDir);
    }
}

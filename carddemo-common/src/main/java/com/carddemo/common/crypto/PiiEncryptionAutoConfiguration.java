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
package com.carddemo.common.crypto;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * :purpose: Validate the PII encryption key EAGERLY, while the application context is still
 *           starting, for every service that persists data — so a missing, blank or structurally
 *           invalid key aborts startup with a precise message instead of letting the service report
 *           UP and then fail every request that touches an encrypted column.
 * :output: The validated key installed in {@link PiiEncryptionKey} for the JPA
 *          {@link CryptoConverter}, or an {@link IllegalStateException} that fails the context.
 * :note: Registered through ``META-INF/spring/...AutoConfiguration.imports``, so every service on
 *        carddemo-common's classpath is covered with no per-service wiring.
 * :note: Only services that actually persist data are validated: the check is skipped when the
 *        context declares no ``DataSource`` bean. That exempts the api-gateway (which has no
 *        datasource) and web-slice tests, while covering all eight JPA services. The look-up reads
 *        bean DEFINITIONS and deliberately does not instantiate the ``DataSource``.
 * :note: The validator is a ``BeanFactoryPostProcessor`` so it runs before ANY singleton is created.
 *        As an ordinary bean it ran after the datasource and Flyway beans, and a misconfiguration was
 *        reported by whichever of those failed first - an obscure message about a JDBC URL - instead
 *        of the precise configuration error.
 */
@AutoConfiguration
public class PiiEncryptionAutoConfiguration {

    /**
     * :purpose: Contribute the startup validator.
     * :returns: the validator bean; declared ``static`` so the post-processor is available without
     *     instantiating this configuration class early.
     */
    @Bean
    static PiiEncryptionKeyValidator piiEncryptionKeyValidator() {
        return new PiiEncryptionKeyValidator();
    }

    /**
     * :purpose: Resolve and validate the PII key during context refresh.
     */
    static class PiiEncryptionKeyValidator implements BeanFactoryPostProcessor {

        private static final Logger log = LoggerFactory.getLogger(PiiEncryptionKeyValidator.class);

        /**
         * :purpose: Install the validated key, or fail the context before any bean is created.
         * :param beanFactory: the bean factory, read for a ``DataSource`` definition and for the
         *     Environment that resolves the key.
         * :raises IllegalStateException: when this service persists data and no usable key is
         *     configured. The message names the environment variable and property to set and never
         *     includes key material.
         */
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (beanFactory.getBeanNamesForType(DataSource.class, true, false).length == 0) {
                log.debug("No DataSource in this context; PII encryption key validation skipped");
                return;
            }
            Environment environment = beanFactory.getBean(Environment.class);
            PiiEncryptionKey.configure(environment.getProperty(PiiEncryptionKey.KEY_PROPERTY));
            log.info("PII encryption key validated (AES-256); sensitive columns are encrypted at rest");
        }
    }
}

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
package com.carddemo.common.json;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Contribute {@link LegacyTextModule} to every service's shared object mapper, so
 *     text normalisation is a property of the JSON boundary rather than of each DTO.
 * :output: The {@link LegacyTextModule} bean, which Spring Boot's Jackson auto-configuration
 *     collects and registers on the mapper it builds.
 * :note: Registered through the library's auto-configuration import file, so a service gets
 *     it by depending on ``carddemo-common`` and nothing else. Guarded by
 *     ``@ConditionalOnMissingBean`` so a service that needs different text handling can
 *     replace it by declaring its own module of the same type.
 */
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class LegacyTextAutoConfiguration {

    /**
     * :purpose: Provide the shared text-normalisation module.
     * :returns: the module registering the string value handlers for both directions.
     */
    @Bean
    @ConditionalOnMissingBean
    LegacyTextModule legacyTextModule() {
        return new LegacyTextModule();
    }
}

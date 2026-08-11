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
package com.carddemo.user;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

/**
 * :purpose: Shared Testcontainers base for full-context user-service
 *     integration tests. Starts a single, singleton PostgreSQL 18 container
 *     (PostgreSQL only -- the user-service is stateless, so NO Redis) and
 *     registers its JDBC coordinates via {@link DynamicPropertySource}. Under
 *     the ``test`` profile the schema comes exclusively from the owning modules'
 *     committed Flyway migrations (this module ships none of its own) and
 *     Hibernate merely validates the entity mapping against it. Integration
 *     subclasses extend this class to obtain a real database without
 *     re-declaring container plumbing.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /**
     * :purpose: Bind the Spring datasource to the shared, already-migrated ``postgres:18``
     *     container from
     *     :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`, whose schema
     *     comes exclusively from the owning modules' committed Flyway migrations - including
     *     the ``security_users`` table and its ten seeded users owned by auth-service. With
     *     ``ddl-auto: validate`` from the ``test`` profile, the entity mapping is asserted
     *     against the deployed schema instead of being generated from the entities.
     * :param registry: the Spring dynamic property registry.
     */
    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }
}

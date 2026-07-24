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
package com.carddemo.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * :purpose: Enable servlet Spring Session backed by Redis so the externalized CICS
 *     COMMAREA state (``com.carddemo.common.dto.SessionContext``) and the Spring
 *     Security context survive across the stateless API gateway and the downstream
 *     microservices (AAP 0.6.3).
 * :note: The Redis connection is auto-configured from ``application.yml``
 *     (``spring.data.redis.*``); the servlet variant ``@EnableRedisHttpSession`` is
 *     used deliberately — never the reactive ``@EnableRedisWebSession``.
 */
@Configuration
@EnableRedisHttpSession
public class SessionConfig {
}

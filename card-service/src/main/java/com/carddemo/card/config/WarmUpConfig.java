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
package com.carddemo.card.config;

import com.carddemo.card.service.CardService;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.startup.LoopingWarmUpTask;
import com.carddemo.common.startup.WarmUpTask;

import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * :purpose: Contribute this service's own read path to the start-up warm-up, so that the
 *     readiness signal means the card list can already be served inside the 200 ms
 *     95th-percentile target of AAP 0.7.1 rather than after a minute of traffic. A freshly
 *     ready instance measured 1530 ms at the 95th percentile under 150 concurrent users with
 *     this path cold, against 52 ms once warm.
 * :output: One {@link WarmUpTask} that repeats ``COCRDLIC``'s unfiltered first-page browse
 *     — the same seven-row page the screen shows — AND the serialization of the response it
 *     produces, for its share of the warm-up budget.
 * :note: No filter is supplied, which is the browse the screen performs on entry and needs
 *     no fixture to exist. Each invocation is handed its OWN throwaway {@link SessionContext}:
 *     the browse reads and sets the terminal-page latch that reproduces
 *     ``CA-LAST-PAGE-SHOWN``, and giving each invocation a fresh context keeps the warm-up
 *     from carrying latch state between iterations and keeps it out of any real session, which
 *     lives in Redis and is never touched here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "carddemo.warmup", name = "enabled", matchIfMissing = true)
public class WarmUpConfig {

    /**
     * :purpose: Build the card-list warm-up task.
     * :param cardService: the service whose read path is warmed.
     * :param objectMapper: the application's mapper, used to serialize the response the
     *     browse produces, so the write half of the request is warmed with the read half.
     * :param concurrency: threads invoking the browse at once, matching the arrival
     *     concurrency this warm-up exists to prepare for.
     * :returns: the task, ordered after the shared infrastructure tasks so it receives
     *     the remainder of the warm-up budget.
     */
    @Bean
    @Order(25)
    public WarmUpTask cardListWarmUpTask(
            CardService cardService,
            ObjectMapper objectMapper,
            @Value("${carddemo.warmup.concurrency:32}") int concurrency) {
        return new LoopingWarmUpTask("card-list", concurrency,
                () -> () -> objectMapper.writeValueAsString(
                        cardService.listCards(null, null, 1, new SessionContext())));
    }
}

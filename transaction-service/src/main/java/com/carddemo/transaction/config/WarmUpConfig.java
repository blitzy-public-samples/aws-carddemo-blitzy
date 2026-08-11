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
package com.carddemo.transaction.config;

import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.TransactionListRequestDto;
import com.carddemo.common.startup.LoopingWarmUpTask;
import com.carddemo.common.startup.WarmUpTask;
import com.carddemo.transaction.service.TransactionService;

import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * :purpose: Contribute this service's own read path to the start-up warm-up, so that the
 *     readiness signal means the transaction list can already be served inside the 200 ms
 *     95th-percentile target of AAP 0.7.1 rather than after a minute of traffic. A freshly
 *     ready instance measured 2137 ms at the 95th percentile under 150 concurrent users with
 *     this path cold, against 47 ms once warm.
 * :output: One {@link WarmUpTask} that repeats ``COTRN00C``'s unfiltered first-page browse
 *     — the same ten-row page the screen shows on entry — AND the serialization of the
 *     response it produces, for its share of the warm-up budget.
 * :note: The request is built fresh per invocation because the list request carries the
 *     paging cursor the browse writes back into it; reusing one instance would walk the
 *     warm-up through the file instead of repeating the same read. An absent action is ENTER,
 *     which is the screen's entry path. Each invocation is handed its own throwaway {@link
 *     SessionContext}, so no real session — those live in Redis — is read or written, and the
 *     browse itself is {@code @Transactional(readOnly = true)}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "carddemo.warmup", name = "enabled", matchIfMissing = true)
public class WarmUpConfig {

    /**
     * :purpose: Build the transaction-list warm-up task.
     * :param transactionService: the service whose read path is warmed.
     * :param objectMapper: the application's mapper, used to serialize the response the
     *     browse produces, so the write half of the request is warmed with the read half.
     * :param concurrency: threads invoking the browse at once, matching the arrival
     *     concurrency this warm-up exists to prepare for.
     * :returns: the task, ordered after the shared infrastructure tasks so it receives
     *     the remainder of the warm-up budget.
     */
    @Bean
    @Order(25)
    public WarmUpTask transactionListWarmUpTask(
            TransactionService transactionService,
            ObjectMapper objectMapper,
            @Value("${carddemo.warmup.concurrency:32}") int concurrency) {
        return new LoopingWarmUpTask("transaction-list", concurrency, () -> () -> {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setPageNumber(1);
            objectMapper.writeValueAsString(
                    transactionService.listTransactions(request, new SessionContext()));
        });
    }
}

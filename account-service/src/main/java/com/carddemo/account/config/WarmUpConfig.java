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
package com.carddemo.account.config;

import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.service.AccountService;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.startup.LoopingWarmUpTask;
import com.carddemo.common.startup.WarmUpTask;

import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;

import java.util.List;

/**
 * :purpose: Contribute this service's own read path to the start-up warm-up, so that the
 *     readiness signal means the account view can already be served inside the 200 ms
 *     95th-percentile target of AAP 0.7.1 rather than after a minute of traffic.
 * :output: One {@link WarmUpTask} that repeats ``COACTVWC``'s read — the ordered
 *     cross-reference, account and customer lookup plus the view mapping — for its share of
 *     the warm-up budget.
 * :note: The identifier is taken from the cross-reference table rather than invented,
 *     because the legacy read short-circuits on a missing cross-reference and a fabricated
 *     account id would exercise only the failure path. An empty table yields no task at all.
 *     The session context is passed as ``null``, which {@link AccountService#viewAccount}
 *     documents as "ignored", so the warm-up carries no session and mutates nothing; the read
 *     itself is {@code @Transactional(readOnly = true)}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "carddemo.warmup", name = "enabled", matchIfMissing = true)
public class WarmUpConfig {

    /**
     * :purpose: Build the account-view warm-up task.
     * :param accountService: the service whose read path is warmed.
     * :param cardXrefRepository: source of one real account identifier to read.
     * :param objectMapper: the application's mapper, used to serialize the response the
     *     read produces. Serializing it is part of the warm-up on purpose: the response
     *     view is the largest DTO this service returns, and its serializer graph is built
     *     on first use, so warming the read alone would leave the write half of the
     *     request cold.
     * :param concurrency: threads invoking the read at once, matching the arrival
     *     concurrency this warm-up exists to prepare for.
     * :returns: the task, ordered after the shared infrastructure tasks so it receives
     *     the remainder of the warm-up budget.
     */
    @Bean
    @Order(25)
    public WarmUpTask accountViewWarmUpTask(
            AccountService accountService,
            CardXrefRepository cardXrefRepository,
            ObjectMapper objectMapper,
            @Value("${carddemo.warmup.concurrency:32}") int concurrency) {
        return new LoopingWarmUpTask("account-view", concurrency, () -> {
            List<CardXref> firstXref = cardXrefRepository.findAll(PageRequest.of(0, 1)).getContent();
            if (firstXref.isEmpty()) {
                return null;
            }
            Long acctId = firstXref.get(0).getXrefAcctId();
            if (acctId == null) {
                return null;
            }
            return () -> objectMapper.writeValueAsString(accountService.viewAccount(acctId, null));
        });
    }
}

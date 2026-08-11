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

import com.carddemo.common.constant.MenuOptions;
import com.carddemo.common.startup.LoopingWarmUpTask;
import com.carddemo.common.startup.WarmUpTask;
import com.carddemo.gateway.controller.MenuController.MenuOptionView;
import com.carddemo.gateway.controller.MenuController.MenuResponse;

import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;

/**
 * :purpose: Contribute the gateway's own menu-assembly path to the start-up warm-up, so
 *     that its readiness signal means the menu can already be served inside the 200 ms
 *     95th-percentile target of AAP 0.7.1 rather than after a minute of traffic. A freshly
 *     ready gateway measured 573 ms at the 95th percentile under 150 concurrent users with
 *     this path cold, against 11 ms once warm.
 * :output: One {@link WarmUpTask} that repeats the role-filtered option assembly of
 *     ``COMEN01C`` (``CM00``) and the unfiltered assembly of ``COADM01C`` (``CA00``), and
 *     serializes each result through the application's mapper, for its share of the warm-up
 *     budget.
 * :note: The task calls neither {@code MenuController} nor the running server. It
 *     reproduces the controller's assembly over the SAME {@link MenuOptions#MAIN_MENU_OPTIONS}
 *     / {@link MenuOptions#ADMIN_MENU_OPTIONS} constants and the same response records, which
 *     is what makes it safe to run before the first caller: the controller reads and rewrites
 *     the pseudo-conversational {@code SessionContext} on the current servlet request, and
 *     there is no request and no session during start-up. It therefore warms the option
 *     assembly and Jackson's serializers for the response records; the servlet, security and
 *     session layers in front of them are warmed by real traffic, deliberately, because a
 *     loopback warm-up request would be counted against the anonymous rate-limit budget the
 *     container healthcheck depends on (see {@code WarmUpAutoConfiguration}).
 * :note: Both menu shapes are assembled because they are separate response bodies with
 *     separate option lists; warming only one would leave the other's serializer to be built
 *     by its first real caller.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "carddemo.warmup", name = "enabled", matchIfMissing = true)
public class WarmUpConfig {

    /** :purpose: Main-menu transaction id, as {@code MenuController} reports it. */
    private static final String MAIN_TRANID = "CM00";

    /** :purpose: Main-menu legacy program name, as {@code MenuController} reports it. */
    private static final String MAIN_PROGRAM = "COMEN01C";

    /** :purpose: Admin-menu transaction id, as {@code MenuController} reports it. */
    private static final String ADMIN_TRANID = "CA00";

    /** :purpose: Admin-menu legacy program name, as {@code MenuController} reports it. */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /** :purpose: ``SEC-USR-TYPE`` value marking an option as administrator-only. */
    private static final String ADMIN_USER_TYPE = "A";

    /**
     * :purpose: Build the menu-assembly warm-up task.
     * :param objectMapper: the application's mapper, so the serializers warmed are the
     *     ones a real response will use.
     * :param concurrency: threads assembling at once, matching the arrival concurrency
     *     this warm-up exists to prepare for.
     * :returns: the task, ordered after the shared infrastructure tasks so it receives
     *     the remainder of the warm-up budget.
     */
    @Bean
    @Order(25)
    public WarmUpTask menuWarmUpTask(
            ObjectMapper objectMapper,
            @Value("${carddemo.warmup.concurrency:32}") int concurrency) {
        return new LoopingWarmUpTask("menu", concurrency, () -> () -> {
            objectMapper.writeValueAsString(mainMenu());
            objectMapper.writeValueAsString(adminMenu());
        });
    }

    /**
     * :purpose: Assemble the main menu as a regular (non-administrator) user sees it,
     *     which is the filtered branch and therefore the more expensive of the two.
     * :returns: the response the ``/menu`` endpoint would return for ``ROLE_USER``.
     */
    private static MenuResponse mainMenu() {
        List<MenuOptionView> options = MenuOptions.MAIN_MENU_OPTIONS.stream()
                .filter(option -> !ADMIN_USER_TYPE.equals(option.userType()))
                .map(option -> new MenuOptionView(
                        option.optionNumber(),
                        option.optionName(),
                        option.programName()))
                .toList();
        return new MenuResponse(MAIN_TRANID, MAIN_PROGRAM, options, null);
    }

    /**
     * :purpose: Assemble the admin menu, which applies no role filter.
     * :returns: the response the ``/admin/menu`` endpoint would return.
     */
    private static MenuResponse adminMenu() {
        List<MenuOptionView> options = MenuOptions.ADMIN_MENU_OPTIONS.stream()
                .map(option -> new MenuOptionView(
                        option.optionNumber(),
                        option.optionName(),
                        option.programName()))
                .toList();
        return new MenuResponse(ADMIN_TRANID, ADMIN_PROGRAM, options, null);
    }
}

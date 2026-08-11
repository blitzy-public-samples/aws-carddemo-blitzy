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
package com.carddemo.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * :purpose: Install {@link RequestRejectedEnvelopeHandler} on every servlet service, so a
 *     request the Spring Security firewall refuses is answered with the shared
 *     ``ErrorResponse`` envelope instead of the container's abbreviated error document.
 * :output: The handler bean plus the {@link WebSecurityCustomizer} that hands it to
 *     ``WebSecurity``, which is what installs it on ``FilterChainProxy``.
 * :note: A ``RequestRejectedHandler`` bean is NOT wired automatically —
 *     ``WebSecurityConfiguration`` collects ``WebSecurityCustomizer`` beans and nothing else,
 *     so ``web.requestRejectedHandler(handler)`` is the supported route and the customizer
 *     below is not optional plumbing.
 * :note: Registered through the library's auto-configuration import file, so a service
 *     gets the behaviour by depending on ``carddemo-common``. Gated on the servlet stack and
 *     on Spring Security being present, so the pure batch classpath is unaffected, and on
 *     ``@ConditionalOnMissingBean`` so a service can install a different handler.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({RequestRejectedHandler.class, WebSecurityCustomizer.class})
public class RequestFirewallAutoConfiguration {

    /**
     * :purpose: Provide the envelope-writing rejection handler.
     * :returns: the handler that completes a refused request in the shared shape.
     */
    @Bean
    @ConditionalOnMissingBean(RequestRejectedHandler.class)
    RequestRejectedHandler requestRejectedEnvelopeHandler() {
        return new RequestRejectedEnvelopeHandler();
    }

    /**
     * :purpose: Hand the handler to ``WebSecurity`` so ``FilterChainProxy`` delegates to it.
     * :param requestRejectedHandler: the handler to install; the bean above unless a service
     *     declared its own.
     * :returns: the customizer performing the installation.
     */
    @Bean
    WebSecurityCustomizer requestRejectedHandlerCustomizer(
            RequestRejectedHandler requestRejectedHandler) {
        return web -> web.requestRejectedHandler(requestRejectedHandler);
    }
}

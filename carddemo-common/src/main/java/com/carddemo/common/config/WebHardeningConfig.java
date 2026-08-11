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
package com.carddemo.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;

/**
 * :purpose: Apply the container-level hardening that every CardDemo servlet service
 *     shares: an inbound request-body size cap and the suppression of the container's default
 *     HTML error page, which fingerprints the server and echoes exception text to the caller.
 *     Together with the Spring Security response headers this closes the transport-level
 *     findings of the runtime security review.
 * :output: A {@link RequestSizeLimitFilter} registration, a Tomcat customizer that
 *     silences the ``ErrorReportValve`` report and server-info banner, and a store-nothing
 *     {@link FlashMapManager} that keeps ``DispatcherServlet`` from reading the shared session
 *     on every request.
 * :note: The cap is configurable through ``carddemo.http.max-request-body-bytes`` and
 *     defaults to {@link RequestSizeLimitFilter#DEFAULT_MAX_BODY_BYTES}.
 * :note: A service activates this configuration with
 *     ``@Import(WebHardeningConfig.class)``, matching the ``@Import`` convention of the other
 *     ``carddemo-common`` configurations. It is inert outside servlet web applications.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebHardeningConfig {

    /**
     * :purpose: Register the request-body size cap ahead of every application filter so
     *     an oversized payload is rejected before any handler or converter sees it.
     * :param maxBodyBytes: configured cap in bytes.
     * :returns: the filter registration mapped to every request path.
     */
    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilterRegistration(
            @Value("${carddemo.http.max-request-body-bytes:" + RequestSizeLimitFilter.DEFAULT_MAX_BODY_BYTES + "}")
            long maxBodyBytes) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(maxBodyBytes));
        registration.addUrlPatterns("/*");
        registration.setName("requestSizeLimitFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * :purpose: Stop Tomcat's ``ErrorReportValve`` from rendering its HTML report and
     *     server-version banner, so an unhandled error returns a bare status line
     *     instead of a container fingerprint and internal message.
     * :returns: a customizer applied to the embedded Tomcat factory.
     */
    @Bean
    @ConditionalOnClass(name = "org.apache.catalina.valves.ErrorReportValve")
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> errorReportValveCustomizer() {
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                for (Valve valve : host.getPipeline().getValves()) {
                    if (valve instanceof ErrorReportValve errorReportValve) {
                        errorReportValve.setShowReport(false);
                        errorReportValve.setShowServerInfo(false);
                    }
                }
            }
        });
    }

    /**
     * :purpose: Replace the MVC default ``SessionFlashMapManager`` so ``DispatcherServlet``
     *     stops loading the caller's session on EVERY request to look for flash attributes this
     *     application never produces.
     * :returns: a {@link FlashMapManager} that reports no input flash map and keeps no output
     *     one.
     * :note: ``DispatcherServlet.doService`` calls the flash-map manager before routing, and
     *     the default implementation reads the session. With a Redis-backed shared session that
     *     read makes the hop OWN the session for the request, so Spring Session writes it back
     *     when the response commits. That write is what turned a successful sign-on into HTTP 500:
     *     sign-on rotates the session id — deleting the old store entry, which is the
     *     session-fixation protection — and the gateway hop then tried to save the entry that no
     *     longer existed (``IllegalStateException: Session was invalidated``), outside any handler
     *     that could recover the proxied 200. Nothing in CardDemo uses flash attributes (no
     *     ``RedirectAttributes``, no ``RedirectView``), so the read has no purpose to preserve,
     *     and removing it also drops one Redis round trip per request.
     * :note: Session idle timeout is unaffected: every authenticated request still reads the
     *     session through ``SessionContextAuthenticationFilter``, which is what refreshes
     *     ``lastAccessedTime``.
     */
    @Bean(DispatcherServlet.FLASH_MAP_MANAGER_BEAN_NAME)
    FlashMapManager flashMapManager() {
        return new NoFlashMapManager();
    }

    /**
     * :purpose: Flash-map manager that stores nothing, for an API that never redirects.
     * :note: Declared as a named type rather than a lambda because ``FlashMapManager``
     *     has two methods.
     */
    private static final class NoFlashMapManager implements FlashMapManager {

        /**
         * :purpose: Report that the request carries no input flash map.
         * :param request: the current request, not read.
         * :param response: the current response, not written.
         * :returns: ``null``, the contract's value for "no flash map".
         */
        @Override
        public FlashMap retrieveAndUpdate(HttpServletRequest request, HttpServletResponse response) {
            return null;
        }

        /**
         * :purpose: Discard the output flash map instead of persisting it in the session.
         * :param flashMap: the map produced by the handler, if any.
         * :param request: the current request, not read.
         * :param response: the current response, not written.
         */
        @Override
        public void saveOutputFlashMap(FlashMap flashMap, HttpServletRequest request,
                                       HttpServletResponse response) {
            // Intentionally empty: no CardDemo handler produces flash attributes, and
            // persisting an empty map would reintroduce the per-request session write.
        }
    }
}

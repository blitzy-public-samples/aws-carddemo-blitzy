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

/**
 * :purpose: Apply the container-level hardening that every CardDemo servlet service
 *     shares: an inbound request-body size cap and the suppression of the container's
 *     default HTML error page, which fingerprints the server and echoes exception text
 *     to the caller. Together with the Spring Security response headers this closes the
 *     transport-level findings of the runtime security review.
 * :output: A {@link RequestSizeLimitFilter} registration and a Tomcat customizer that
 *     silences the ``ErrorReportValve`` report and server-info banner.
 * :note: The cap is configurable through ``carddemo.http.max-request-body-bytes`` and
 *     defaults to {@link RequestSizeLimitFilter#DEFAULT_MAX_BODY_BYTES}.
 * :note: A service activates this configuration with
 *     ``@Import(WebHardeningConfig.class)``, matching the ``@Import`` convention of the
 *     other ``carddemo-common`` configurations. It is inert outside servlet web
 *     applications.
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
}

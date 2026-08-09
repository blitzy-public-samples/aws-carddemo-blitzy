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

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * :purpose: Suppress the servlet container's own HTML error report. A request the
 *  container rejects before it reaches the servlet -- for example a URI carrying an
 *  encoded path separator, which Tomcat refuses while parsing -- never reaches the
 *  Spring dispatcher, so neither the shared ``@RestControllerAdvice`` nor
 *  {@link CardDemoErrorController} can render the documented JSON envelope for it.
 *  Left at its default the container answers such a request with a full HTML error
 *  page, which is a second, undocumented error shape in the API contract.
 * :output: A {@link WebServerFactoryCustomizer} that installs an
 *  {@link ErrorReportValve} with report and server-info rendering disabled, so a
 *  container-level rejection returns the bare status with an empty body and
 *  discloses neither markup nor server identity. Every error that does reach the
 *  application still renders the {@link com.carddemo.common.dto.ErrorResponse}
 *  envelope.
 * :note: Import it alongside {@link GlobalExceptionHandler} and
 *  {@link CardDemoErrorController} in every web-enabled service; it is not
 *  auto-configured.
 */
@Configuration
@ConditionalOnClass(name = "org.apache.catalina.valves.ErrorReportValve")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ContainerErrorReportConfig {

    /** :purpose: Logger for container-level error rendering. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerErrorReportConfig.class);

    /**
     * :purpose: Replace the HOST-level error-report valve class with the silent
     *  variant. The rejection this exists for happens while the container parses
     *  the request line, before any context is selected, so it is rendered by the
     *  host pipeline's valve; a context-level valve would never see it.
     * :returns: the customizer that installs {@link SilentErrorReportValve} on the
     *  embedded Tomcat host before it starts.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> silentContainerErrorReport() {
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(SilentErrorReportValve.class.getName());
            }
        });
    }

    /**
     * :purpose: Error-report valve that renders the documented CardDemo error
     *  envelope as JSON instead of Tomcat's HTML page, so a rejection the container
     *  answers on its own is indistinguishable in shape from every error the
     *  application answers.
     * :output: A public no-argument valve class Tomcat instantiates by name as the
     *  host's ``errorReportValveClass``. The body carries the status, its reason
     *  phrase, and the request URI; no server identity, exception message, or
     *  markup is disclosed.
     */
    public static class SilentErrorReportValve extends ErrorReportValve {

        /**
         * :purpose: Disable Tomcat's report and server-info rendering; the envelope
         *  is written by {@link #report}.
         */
        public SilentErrorReportValve() {
            setShowReport(false);
            setShowServerInfo(false);
        }

        /**
         * :purpose: Write the CardDemo error envelope for a container-level failure.
         * :param request: the rejected request.
         * :param response: the response to render into.
         * :param throwable: the failure the container recorded, deliberately not
         *  disclosed to the caller.
         */
        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            int statusCode = response.getStatus();
            if (statusCode < 400 || response.getContentWritten() > 0) {
                return;
            }
            if (!response.setErrorReported()) {
                return;
            }
            HttpStatus status = HttpStatus.resolve(statusCode);
            String reason = (status == null) ? "Error" : status.getReasonPhrase();
            // The one authoritative envelope shape, shared with every other non-MVC
            // failure path, so a container-level rejection is indistinguishable in shape
            // from an application error. The path is PAN-masked by the writer.
            String body = ErrorEnvelopeWriter.json(statusCode, reason, request.getRequestURI(),
                    resolveCorrelationId(request), null);
            try {
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8);
                PrintWriter writer = response.getReporter();
                if (writer != null) {
                    writer.write(body);
                    response.finishResponse();
                }
            } catch (IOException | IllegalStateException e) {
                // The response is already gone; nothing further can be reported.
                LOGGER.debug("Unable to render the container error envelope", e);
            }
        }

        /**
         * :purpose: Recover the correlation id for a failure the container itself answers, so the
         *  envelope carries the SAME id the caller holds whenever such an id exists.
         * :param request: the rejected request.
         * :returns: the id {@link CorrelationIdFilter} published as a request attribute when that
         *  filter ran; otherwise the sanitized inbound ``X-Correlation-Id`` supplied by the
         *  caller; otherwise ``null``.
         * :note: The ``null`` case is reachable and is not a defect. A URI the container refuses
         *  while parsing the request line -- an encoded path separator, for instance -- is
         *  rejected before a context is selected, so NO servlet filter runs and the server never
         *  mints an id for it. Reading the inbound header here means a caller that supplies its
         *  own id can still correlate even that class of rejection; a caller that supplies none
         *  gets ``null``, matching the fact that no id was ever issued or logged. ``traceId``
         *  stays ``null`` for the same reason: no observation scope was ever opened.
         */
        private String resolveCorrelationId(Request request) {
            Object established = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
            if (established instanceof String existing) {
                String sanitized = CorrelationIdContext.sanitize(existing);
                if (sanitized != null) {
                    return sanitized;
                }
            }
            return CorrelationIdContext.sanitize(
                    request.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
        }

    }
}

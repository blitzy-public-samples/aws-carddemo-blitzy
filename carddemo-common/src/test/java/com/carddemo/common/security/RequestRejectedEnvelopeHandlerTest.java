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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.config.CorrelationIdContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Pin the firewall-refusal contract: ``FilterChainProxy`` delegates a rejected
 *     request to the installed {@link RequestRejectedHandler}, and this one completes the
 *     response in the shared ``ErrorResponse`` shape rather than calling ``sendError`` and
 *     leaving the body to the container's ERROR dispatch. Also pins that the
 *     auto-configuration actually installs it, since a handler nobody hands to
 *     ``WebSecurity`` has no effect at all.
 * :output: JUnit assertions only.
 */
class RequestRejectedEnvelopeHandlerTest {

    /** :purpose: Reader for the envelope, which the writer emits as JSON by hand. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RequestRejectedEnvelopeHandler handler = new RequestRejectedEnvelopeHandler();

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestFirewallAutoConfiguration.class));

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestFirewallAutoConfiguration.class));

    @AfterEach
    void clearCorrelationId() {
        CorrelationIdContext.clear();
    }

    /**
     * :purpose: The refusal is completed here and now, in the full envelope, carrying the
     *     correlation id that is still in the MDC — the member the container's own error
     *     document could never supply, because that document is produced after the
     *     correlation-id filter has unwound.
     */
    @Test
    @DisplayName("a rejected request is answered with the shared 400 envelope")
    void writesEnvelopeForRejectedRequest() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CorrelationIdContext.setCorrelationId("corr-firewall");

        handler.handle(new MockHttpServletRequest("GET", "/accounts"), response,
                new RequestRejectedException("The request was rejected because the URL "
                        + "contained a potentially malicious String \"//\""));

        assertThat(response.getStatus()).isEqualTo(400);
        Map<String, Object> body = readBody(response);
        assertThat(body).containsOnlyKeys("timestamp", "status", "error", "errorCode",
                "message", "path", "traceId", "correlationId", "fieldErrors");
        assertThat(body.get("errorCode")).isEqualTo("REQUEST_REJECTED");
        assertThat(body.get("message")).isEqualTo("The request could not be processed");
        assertThat(body.get("path")).isEqualTo("/accounts");
        assertThat(body.get("correlationId")).isEqualTo("corr-firewall");
    }

    /**
     * :purpose: The envelope names nothing about which firewall check refused the request, so
     *     a caller cannot map the boundary by reading the refusals.
     */
    @Test
    @DisplayName("the envelope discloses nothing about the check that refused the request")
    void doesNotDiscloseTheRejectionReason() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest("GET", "/accounts"), response,
                new RequestRejectedException("potentially malicious String \"%2f\" in the URL"));

        assertThat(response.getContentAsString())
                .doesNotContain("malicious", "%2f", "firewall", "Exception");
    }

    /**
     * :purpose: This refusal is decided before ``FilterChainProxy`` enters the chain, so
     *     ``HeaderWriterFilter`` never runs and nothing else supplies a cache directive. A
     *     refusal must not be cacheable, so the handler supplies one itself.
     */
    @Test
    @DisplayName("the refusal carries a no-store cache directive and the sniffing guard")
    void setsNoStoreOnTheRefusal() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest("GET", "/accounts"), response,
                new RequestRejectedException("rejected"));

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /**
     * :purpose: A card number in the refused path is reduced to its last four digits, so a
     *     rejection cannot become the place a PAN is written in full.
     */
    @Test
    @DisplayName("a card number in the refused path is redacted")
    void redactsCardNumberInPath() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest("GET", "/cards/9680294154603697"), response,
                new RequestRejectedException("rejected"));

        assertThat(response.getContentAsString()).doesNotContain("9680294154603697");
        assertThat(readBody(response).get("path")).asString().endsWith("3697");
    }

    /**
     * :purpose: The handler has no effect unless something hands it to ``WebSecurity``: no
     *     framework code autowires a ``RequestRejectedHandler`` bean. The auto-configuration
     *     must therefore contribute BOTH the handler and the customizer that installs it.
     */
    @Test
    @DisplayName("the auto-configuration contributes the handler and installs it")
    void autoConfigurationInstallsTheHandler() {
        servletRunner.run(context -> {
            assertThat(context).hasSingleBean(RequestRejectedHandler.class);
            assertThat(context.getBean(RequestRejectedHandler.class))
                    .isInstanceOf(RequestRejectedEnvelopeHandler.class);
            assertThat(context).hasSingleBean(WebSecurityCustomizer.class);
        });
    }

    /**
     * :purpose: A service that installs a different rejection policy keeps it: the shared
     *     handler backs off, and the customizer installs the service's own bean.
     */
    @Test
    @DisplayName("a service's own handler replaces the shared one and is the one installed")
    void serviceSuppliedHandlerWins() {
        servletRunner.withBean(RequestRejectedHandler.class, HttpStatusRequestRejectedHandler::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(RequestRejectedHandler.class);
                    assertThat(context.getBean(RequestRejectedHandler.class))
                            .isInstanceOf(HttpStatusRequestRejectedHandler.class);
                    assertThat(context).hasSingleBean(WebSecurityCustomizer.class);
                });
    }

    /**
     * :purpose: Outside a servlet application the configuration contributes nothing, so a
     *     non-servlet module can depend on the library without pulling in web security types.
     */
    @Test
    @DisplayName("contributes nothing outside a servlet application")
    void contributesNothingOutsideServletApplication() {
        nonWebRunner.run(context -> {
            assertThat(context).doesNotHaveBean(RequestRejectedHandler.class);
            assertThat(context).doesNotHaveBean(WebSecurityCustomizer.class);
        });
    }

    /**
     * :purpose: Parse the written envelope.
     * :param response: the completed response.
     * :returns: the envelope as a map.
     * :raises Exception: if the body cannot be read.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBody(MockHttpServletResponse response) throws Exception {
        return MAPPER.readValue(response.getContentAsString(), Map.class);
    }
}

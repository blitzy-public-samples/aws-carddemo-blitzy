package com.carddemo.account.config;

import com.carddemo.events.correlation.CorrelationScope;
import com.carddemo.events.correlation.EventCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request of the account service one correlation identifier, and puts it on every log record
 * the request produces.
 *
 * <p>ADDITIVE. The source needed no such identifier: one CICS transaction ran in one process and
 * wrote to one job log, so {@code app/cbl/CBTRN02C.cbl:L227-L230} could print a count and a reader
 * knew which run it belonged to. Six services and five topics cannot.
 *
 * <p>A caller that supplies {@code X-Correlation-Id} holding one Universally Unique Identifier
 * (UUID) keeps that value, so a correlation started outside this platform reaches every record
 * inside it. Any other request is given a fresh value. Nothing else is accepted:
 * {@link EventCorrelation#parse(String)} refuses text that is not one rendered identifier, so a
 * caller cannot widen a log field or write a line of its own through this header.
 *
 * <p>The value is echoed on the response, which is how a caller learns the identifier to quote when
 * reporting a problem. It is written before the chain runs so a refusal carries it too.
 *
 * <p>The filter runs one place ahead of {@code config/RequestRateCeilingFilter}, which is itself
 * ahead of the security chain. A throttled request and an unauthenticated one therefore carry the
 * identifier as well, and those are the requests an operator is most often asked about.
 *
 * <p>The management surface is not exempt. A probe costs one field, and a failing probe is exactly
 * the record an operator wants to join to the rest of a deployment's output.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 2)
public class CorrelationContextFilter extends OncePerRequestFilter {

    /**
     * Opens one scope for the request, echoes the identifier, and closes the scope on the way out.
     *
     * @param request  the arriving request, read for the correlation header alone
     * @param response the answer, which carries the identifier back
     * @param chain    the rest of the filter chain
     * @throws ServletException when the chain raises one
     * @throws IOException      when the chain raises one
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        UUID correlationId = EventCorrelation
                .parse(request.getHeader(EventCorrelation.CORRELATION_ID_REQUEST_HEADER))
                .orElseGet(EventCorrelation::newCorrelationId);

        try (CorrelationScope scope = CorrelationScope.open().withCorrelation(correlationId)) {
            response.setHeader(EventCorrelation.CORRELATION_ID_REQUEST_HEADER,
                    correlationId.toString());
            chain.doFilter(request, response);
        }
    }
}

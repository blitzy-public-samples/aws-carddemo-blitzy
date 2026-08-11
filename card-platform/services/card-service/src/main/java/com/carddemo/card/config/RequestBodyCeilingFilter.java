package com.carddemo.card.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses an oversized declared request body before Jackson reads it.
 *
 * <p>The filter runs at {@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} minus two, ahead of
 * the security chain, so an oversized body presenting a credential is refused before the bcrypt
 * verification that credential would otherwise cost. It defers when the request carries no
 * {@code Authorization} header: the chain answers such a request with 401 and no password to verify,
 * which is the answer it gave when this filter ran after the chain. Both observable answers are
 * therefore unchanged, and only the price of the refusal moved.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 2)
public class RequestBodyCeilingFilter extends OncePerRequestFilter {

    /** Fixed refusal carrying no route, card number or submitted value. */
    static final String REFUSAL_BODY = """
            {"status":413,"error":"Payload too large",\
            "messages":["This request declares a body larger than this service reads."]}""";

    private final long ceilingBytes;

    /**
     * Builds the filter from the validated {@code carddemo.api} block.
     *
     * @param properties validated service configuration
     */
    public RequestBodyCeilingFilter(CardProperties properties) {
        CardProperties checked = Objects.requireNonNull(properties, "properties must be present");
        this.ceilingBytes = checked.api().maxRequestBodyBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            // No credential, so no password verification is spent whatever the body declares, and
            // the chain answers 401 exactly as it did before this filter was ordered ahead of it.
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > ceilingBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CONNECTION, "close");
            response.getWriter().write(REFUSAL_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}

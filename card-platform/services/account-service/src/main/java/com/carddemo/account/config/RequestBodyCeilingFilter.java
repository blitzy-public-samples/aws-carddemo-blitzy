package com.carddemo.account.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request whose declared body is larger than this service reads.
 *
 * <p>The source maps use fixed-width fields. A JavaScript Object Notation (JSON) request has no
 * inherent bound, so this filter refuses an oversized declared body before Jackson allocates it.
 * {@link JsonReadCeilingConfig} covers a chunked request that declares no length.
 */
@Component
public class RequestBodyCeilingFilter extends OncePerRequestFilter {

    /** Fixed refusal text carrying no route, identifier or submitted value. */
    static final String REFUSAL_BODY = """
            {"status":413,"error":"Payload too large",\
            "messages":["This request declares a body larger than this service reads."]}""";

    /** Largest declared request body, in bytes. */
    private final long ceilingBytes;

    /**
     * Builds the filter from the validated {@code carddemo.api} block.
     *
     * @param properties validated service configuration
     */
    public RequestBodyCeilingFilter(AccountProperties properties) {
        AccountProperties checked = Objects.requireNonNull(properties, "properties must be present");
        this.ceilingBytes = checked.api().maxRequestBodyBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

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

package com.carddemo.authorization.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.carddemo.authorization.api.GlobalExceptionHandler.ApiErrorResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request whose declared body is larger than this service reads.
 *
 * <p>ADDITIVE. A 3270 terminal cannot send an oversized request: every field of every mapset under
 * {@code app/bms/} has a fixed width, and {@code app/csd/CARDDEMO.CSD} sizes the Communication Area
 * a transaction receives. A JavaScript Object Notation (JSON) body has no such bound, so a caller
 * holding one credential could otherwise post megabytes and have the parser build it before any
 * validation ran.
 *
 * <p>The ceiling is declared by {@code carddemo.api.max-request-body-bytes}. The widest legitimate
 * body is one authorization request, whose largest component is
 * {@code DALYTRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA06Y.cpy:L9}, so the shipped ceiling is
 * generous by three orders of magnitude and still bounds the parser.
 *
 * <p>The check reads {@code Content-Length} alone and never touches the stream, so a refusal costs
 * nothing. A request that declares no length is bounded instead by the document length constraint
 * {@link JsonReadCeilingConfig} sets on the parser, so both shapes are covered.
 *
 * <p>The refusal is {@code 413} carrying one fixed text. It names no route and no value, for the
 * reason {@code config/SecurityConfig} gives for its own answers. The four members it writes are the
 * members of {@link ApiErrorResponse}, so one schema describes every refusal this service writes from
 * a handler or from a filter.
 *
 * <p>An instance holds no mutable state, so request threads share one.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class RequestBodyCeilingFilter extends OncePerRequestFilter {

    /** The one text a refusal carries. It names no route, no header and no value. */
    static final String REFUSAL_MESSAGE =
            "This request declares a body larger than this service reads.";

    /**
     * Renders the moment the body was built, at the shape {@link ApiErrorResponse} declares.
     *
     * <p>Seconds and three fraction digits are always written. {@code Instant#toString()} drops
     * seconds when they are zero, which the published pattern refuses.
     */
    private static final DateTimeFormatter TIMESTAMP_SHAPE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Largest body this service reads, in bytes. */
    private final long ceilingBytes;

    /**
     * Takes the ceiling.
     *
     * @param ceilingBytes value of {@code carddemo.api.max-request-body-bytes}, one byte or more
     * @throws IllegalStateException when the ceiling falls below one. The message names the property
     *                               and carries no value
     */
    public RequestBodyCeilingFilter(
            @Value("${carddemo.api.max-request-body-bytes:65536}") long ceilingBytes) {
        if (ceilingBytes < 1) {
            throw new IllegalStateException(
                    "carddemo.api.max-request-body-bytes counts up from one byte");
        }
        this.ceilingBytes = ceilingBytes;
    }

    /**
     * Refuses an oversized request and passes every other one along unchanged.
     *
     * @param request  the request under inspection
     * @param response the response a refusal is written to
     * @param chain    the rest of the chain
     * @throws ServletException when the rest of the chain raises one
     * @throws IOException      when the response cannot be written
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        if (request.getContentLengthLong() > this.ceilingBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CONNECTION, "close");
            response.getWriter().write(refusalBody());
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Builds the refusal body, carrying the four members {@link ApiErrorResponse} declares.
     *
     * <p>The members are written by hand rather than through an object mapper, so a refusal raised
     * before the parser ran depends on no serializer configuration.
     *
     * @return the body, holding no route, no header and no value read from the request
     */
    private static String refusalBody() {
        return "{\"status\":" + HttpStatus.CONTENT_TOO_LARGE.value()
                + ",\"error\":\"" + ApiErrorResponse.UNPROCESSABLE
                + "\",\"messages\":[\"" + REFUSAL_MESSAGE
                + "\"],\"timestamp\":\"" + TIMESTAMP_SHAPE.format(Instant.now()) + "\"}";
    }
}

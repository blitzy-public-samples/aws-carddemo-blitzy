/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.account.config;

import com.aws.carddemo.account.exception.ApiError;

import io.swagger.v3.oas.annotations.Hidden;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom {@link ErrorController} that renders every container/servlet-dispatched error as the
 * service's uniform, sanitized {@link ApiError} JSON envelope (finding F-13).
 *
 * <h2>The gap this closes</h2>
 * <p>{@code GlobalExceptionHandler} (a {@code @RestControllerAdvice}) translates exceptions raised
 * <em>inside</em> {@code DispatcherServlet} handling into {@link ApiError}. Some rejections, however,
 * never reach that advice and instead land on the servlet container's error dispatch to
 * {@code /error}:</p>
 * <ul>
 *   <li>a response that a controller/advice produced but that then failed HTTP content negotiation
 *       (for example an unmatched route whose {@code ApiError} cannot be rendered as the client's
 *       requested {@code text/html}), which the container re-dispatches to {@code /error};</li>
 *   <li>an error signalled via {@code HttpServletResponse#sendError};</li>
 *   <li>a direct request to {@code /error}.</li>
 * </ul>
 * <p>Spring Boot's default {@code BasicErrorController} answers those with a Whitelabel HTML page
 * (for {@code text/html} clients) or Boot's own JSON error map &mdash; neither of which matches the
 * documented {@link ApiError} contract, and the HTML page in particular is an inconsistent envelope.
 * Declaring this bean (an {@link ErrorController}) makes Spring Boot back off its
 * {@code BasicErrorController}, so this controller owns {@code /error} and always answers with
 * {@link ApiError} JSON.</p>
 *
 * <h2>Sanitization</h2>
 * <p>The body is derived <em>only</em> from the container's numeric status attribute and the
 * (digit-masked) original request path. The servlet-supplied error {@code message} and any exception
 * detail are never copied into the response: {@link #messageFor(HttpStatus)} returns a fixed generic
 * phrase per status class, so no exception type, stack frame, SQL, framework detail, or submitted
 * value can leak (CWE-209; AAP &sect;0.6.6). The path is masked exactly as elsewhere in the service
 * &mdash; every all-digit segment becomes {@code {accountId}} &mdash; so the sensitive 11-digit
 * account id is never serialized (CWE-532).</p>
 *
 * <h2>Content type</h2>
 * <p>The response {@code Content-Type} is pinned to {@code application/json} on the
 * {@link ResponseEntity}. Presetting a concrete content type makes Spring write the body with the
 * JSON converter directly, bypassing {@code Accept}-header negotiation; this is what guarantees a
 * JSON envelope even for the {@code Accept: text/html} case that originally produced the Whitelabel
 * page, and it cannot itself re-trigger a negotiation failure (and thus cannot loop back to
 * {@code /error}).</p>
 *
 * <h2>Scope / connector-level cases</h2>
 * <p>This controller governs errors that reach the servlet {@code /error} dispatch. Rejections
 * handled entirely at the TCP/HTTP connector <em>before</em> a request is dispatched to the servlet
 * engine &mdash; notably a {@code TRACE} request refused by Tomcat's {@code allowTrace=false} default,
 * and oversized request-URI/header rejections enforced by the connector's {@code maxHttpHeaderSize}
 * limit &mdash; are produced by Tomcat's protocol layer and are not reachable by any servlet, filter,
 * or {@link ErrorController}. Those remain safe container rejections and are documented here as
 * genuinely outside application-layer control, matching the finding's "where technically
 * controllable" scope.</p>
 *
 * <p>{@link Hidden} keeps the {@code /error} path out of the generated OpenAPI document so the
 * published API contract still advertises only the two account operations.</p>
 */
@RestController
@Hidden
public class ApiErrorController implements ErrorController {

    /** Generic 404 summary (mirrors {@code GlobalExceptionHandler} for a uniform contract). */
    private static final String NOT_FOUND_MESSAGE = "Requested resource was not found";

    /** Generic 405 summary (mirrors {@code GlobalExceptionHandler}). */
    private static final String METHOD_NOT_ALLOWED_MESSAGE = "Request method not supported";

    /** Generic 406 summary (mirrors {@code GlobalExceptionHandler}). */
    private static final String NOT_ACCEPTABLE_MESSAGE = "Not acceptable";

    /** Generic 415 summary (mirrors {@code GlobalExceptionHandler}). */
    private static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Request content type is not supported";

    /** Generic 403 summary. */
    private static final String FORBIDDEN_MESSAGE = "Access is denied";

    /** Generic fallback summary for any other client (4xx) error. */
    private static final String CLIENT_ERROR_MESSAGE = "Request could not be processed";

    /** Generic 5xx summary (mirrors {@code GlobalExceptionHandler#UNEXPECTED_ERROR_MESSAGE}). */
    private static final String SERVER_ERROR_MESSAGE =
            "An unexpected error occurred while processing the request";

    /** Placeholder substituted for an all-digit path segment so the account id never leaks. */
    private static final String ACCOUNT_ID_PLACEHOLDER = "{accountId}";

    /**
     * Renders the current error dispatch as a sanitized {@link ApiError} JSON response.
     *
     * @param request the error-dispatched request, carrying the container error attributes
     * @return a {@link ResponseEntity} whose status matches the container status and whose body is
     *         the uniform {@link ApiError}, pinned to {@code application/json}
     */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ApiError> handleError(final HttpServletRequest request) {
        final HttpStatus status = resolveStatus(request);
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                messageFor(status),
                resolvePath(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Resolves the HTTP status from the container's {@link RequestDispatcher#ERROR_STATUS_CODE}
     * attribute, defaulting to {@code 500} when it is absent (a direct {@code /error} hit) or is not
     * a recognised status code &mdash; mirroring Boot's own default.
     *
     * @param request the current request
     * @return the resolved {@link HttpStatus}
     */
    private static HttpStatus resolveStatus(final HttpServletRequest request) {
        final Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer statusCode) {
            final HttpStatus resolved = HttpStatus.resolve(statusCode);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * Maps a status to a fixed, generic, already-sanitized summary. No servlet- or exception-supplied
     * text is ever used, so the message can never leak implementation detail (CWE-209).
     *
     * @param status the resolved status
     * @return the generic summary for that status
     */
    private static String messageFor(final HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> NOT_FOUND_MESSAGE;
            case METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED_MESSAGE;
            case NOT_ACCEPTABLE -> NOT_ACCEPTABLE_MESSAGE;
            case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE_MESSAGE;
            case FORBIDDEN -> FORBIDDEN_MESSAGE;
            default -> status.is4xxClientError() ? CLIENT_ERROR_MESSAGE : SERVER_ERROR_MESSAGE;
        };
    }

    /**
     * Derives the sanitized {@code path} for the error body: the original request URI (recovered from
     * {@link RequestDispatcher#ERROR_REQUEST_URI} on an error dispatch, else the current URI) with
     * every all-digit segment masked to {@value #ACCOUNT_ID_PLACEHOLDER}. This matches the masking in
     * {@code GlobalExceptionHandler#resolvePath} / {@code RequestBodySizeLimitFilter#sanitizePath}, so
     * no concrete 11-digit account id reaches the body (AAP &sect;0.6.6; CWE-532).
     *
     * @param request the current request
     * @return the digit-masked path, for example {@code /api/v1/accounts/{accountId}} or {@code /error}
     */
    private static String resolvePath(final HttpServletRequest request) {
        final Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        final String uri = (errorUri instanceof String s && !s.isEmpty())
                ? s
                : request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return uri;
        }
        final String[] segments = uri.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            final String segment = segments[i];
            if (!segment.isEmpty() && segment.chars().allMatch(Character::isDigit)) {
                segments[i] = ACCOUNT_ID_PLACEHOLDER;
            }
        }
        return String.join("/", segments);
    }
}

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

import com.carddemo.common.security.SensitiveDataMasker;
import com.carddemo.common.dto.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: Replace Spring Boot's ``BasicErrorController`` so that a container-level
 *  error dispatch -- an error raised outside any ``@RestControllerAdvice`` reach,
 *  such as a servlet ``sendError``, an exception escaping a filter, or a request
 *  that never matched a handler -- answers with exactly the same
 *  {@link ErrorResponse} envelope as every handled failure. Without it two
 *  mutually inconsistent error shapes coexist: the documented envelope for handled
 *  exceptions and Boot's abbreviated ``{timestamp,status,error,path}`` body for
 *  everything else.
 * :output: A ``@RestController`` mapped to the configured error path (``/error``)
 *  that returns the populated envelope with the dispatched status, the original
 *  request URI, and the current trace/correlation id. No exception message,
 *  stack trace, or framework internal is disclosed - only the status reason
 *  phrase - so the response cannot leak implementation detail.
 * :note: Registering a bean of type {@link ErrorController} makes Boot's own
 *  controller back off. Import it alongside
 *  {@link GlobalExceptionHandler} in every web-enabled service; it is not
 *  auto-configured.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CardDemoErrorController implements ErrorController {

    /** :purpose: Logger for container-level error dispatches. */
    private static final Logger log = LoggerFactory.getLogger(CardDemoErrorController.class);

    /**
     * :purpose: Error attributes provider, used to consume the recorded error for
     *  logging. Resolved lazily and tolerated as absent so the controller can also
     *  be imported into a context that carries no web error auto-configuration.
     */
    private final ObjectProvider<ErrorAttributes> errorAttributes;

    /**
     * :purpose: Construct the controller with Boot's error-attributes provider.
     * :param errorAttributes: provider of the component that records the dispatched error.
     */
    public CardDemoErrorController(ObjectProvider<ErrorAttributes> errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    /**
     * :purpose: Render the documented error envelope for a dispatched error,
     *  preserving the status the container resolved.
     * :param request: the error dispatch request carrying the standard
     *  ``jakarta.servlet.error.*`` attributes.
     * :returns: the envelope with the dispatched status, reason phrase, original
     *  request URI and trace id.
     */
    @RequestMapping(value = "${server.error.path:${error.path:/error}}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(),
                status.getReasonPhrase(), resolvePath(request));
        body.setTraceId(ErrorResponseFactory.traceId());
        // Consume the recorded error so it is not re-reported downstream; the
        // exception itself is deliberately not surfaced to the caller.
        ErrorAttributes attributes = errorAttributes.getIfAvailable();
        Throwable error = (attributes == null) ? null
                : attributes.getError(new org.springframework.web.context.request.ServletWebRequest(request));
        if (error != null) {
            // The path is masked for the LOG only; the response body keeps the URI the
            // caller itself supplied, which is part of the shared error contract.
            log.warn("Error dispatch for {} resolved to {}: {}",
                    SensitiveDataMasker.maskPan(body.getPath()), status.value(),
                    error.getClass().getSimpleName());
        } else {
            log.warn("Error dispatch for {} resolved to {}",
                    SensitiveDataMasker.maskPan(body.getPath()), status.value());
        }
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Resolve the dispatched HTTP status, defaulting to 500 when the
     *  container recorded none or recorded an unknown code.
     * :param request: the error dispatch request.
     * :returns: the resolved status.
     */
    private HttpStatus resolveStatus(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (attribute instanceof Integer code) {
            HttpStatus resolved = HttpStatus.resolve(code);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * :purpose: Resolve the URI of the request that failed, rather than the
     *  ``/error`` dispatch path.
     * :param request: the error dispatch request.
     * :returns: the original request URI, or the dispatch URI when absent.
     */
    private String resolvePath(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (attribute instanceof String uri && !uri.isBlank()) {
            return uri;
        }
        return request.getRequestURI();
    }

}

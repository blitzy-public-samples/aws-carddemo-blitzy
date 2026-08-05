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
package com.carddemo.reporting.client;

import com.carddemo.common.dto.BatchJobExecutionDto;
import com.carddemo.common.exception.CardDemoException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * :purpose: Submit the ``CBTRN03C`` / ``TRANREPT`` transaction-detail report to
 *  batch-service, which owns that job stream. It re-platforms ``CORPT00C``'s
 *  ``SUBMIT-JOB-TO-INTRDR`` write to the CICS transient data queue ``'JOBS'``: the
 *  legacy program handed the job to another subsystem to run and learned immediately
 *  whether the hand-off itself had succeeded. Report requests previously launched the
 *  statement job instead, leaving the transaction-detail report with no caller at all.
 * :output: A {@link BatchJobExecutionDto} carrying the accepted run's durable execution
 *  handle, or a {@link CardDemoException} bearing the frozen ``Unable to Write TDQ
 *  (JOBS)...`` message for every hand-off failure.
 * :note: The caller's ``SESSION`` cookie is forwarded so the launch is authorized as the
 *  signed-on user against batch-service's own authorization boundary; the shared Redis
 *  session makes that cookie meaningful in either service. The hand-off is synchronous
 *  and its failure is raised to the caller, while the job itself runs asynchronously in
 *  batch-service.
 */
@Component
public class BatchJobClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchJobClient.class);

    /** Frozen ``CORPT00C`` message for a failed submission hand-off. */
    public static final String SUBMIT_FAILURE_MESSAGE = "Unable to Write TDQ (JOBS)...";

    /**
     * Prefix used when batch-service ANSWERS but refuses the run, so the caller reads the
     * real reason (an already-running or already-complete instance, a restart violation,
     * unusable parameters) instead of the TDQ literal, which means only "the hand-off itself
     * could not be written" and misrepresents a refusal.
     */
    public static final String SUBMISSION_REFUSED_PREFIX = "Report submission was not accepted: ";

    /** Job stream submitted for a report request, named verbatim as its job bean. */
    private static final String TRANSACTION_DETAIL_REPORT_JOB = "transactionDetailReportJob";

    /** Session cookie forwarded so batch-service can authorize the submission. */
    private static final String SESSION_COOKIE_NAME = "SESSION";

    /** HTTP client bound to the batch-service base URI. */
    private final RestClient restClient;

    /** Current request, used only to read the caller's session cookie. */
    private final HttpServletRequest httpRequest;

    /**
     * :purpose: Build the client against the configured batch-service base URI.
     * :param httpRequest: the current request, injected as a scoped proxy.
     * :param batchServiceUri: base URI of batch-service (``BATCH_SERVICE_URI``).
     */
    public BatchJobClient(HttpServletRequest httpRequest,
                          @Value("${carddemo.batch-service.uri:http://batch-service:8080}")
                          String batchServiceUri) {
        this.restClient = RestClient.builder().baseUrl(batchServiceUri).build();
        this.httpRequest = httpRequest;
    }

    /**
     * :purpose: Submit the transaction-detail report for a reporting window.
     * :param startDate: inclusive window start in ``YYYY-MM-DD`` wire form
     *  (``PARM-START-DATE``).
     * :param endDate: inclusive window end in ``YYYY-MM-DD`` wire form
     *  (``PARM-END-DATE``).
     * :returns: the accepted run's durable execution handle.
     * :raises CardDemoException: with the frozen ``Unable to Write TDQ (JOBS)...``
     *  message whenever batch-service refuses the run or cannot be reached, so a failed
     *  hand-off is never reported to the user as a successful submission and no message
     *  other than the one ``CORPT00C`` emits ever reaches the screen.
     */
    public BatchJobExecutionDto submitTransactionDetailReport(String startDate, String endDate) {
        LOGGER.info("Submitting {} to batch-service (startDate={}, endDate={})",
                TRANSACTION_DETAIL_REPORT_JOB, startDate, endDate);
        try {
            BatchJobExecutionDto accepted = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/batch/jobs/{jobName}")
                            .queryParam("startDate", startDate)
                            .queryParam("endDate", endDate)
                            .build(TRANSACTION_DETAIL_REPORT_JOB))
                    .header(HttpHeaders.COOKIE, sessionCookieHeader())
                    .retrieve()
                    .body(BatchJobExecutionDto.class);
            if (accepted == null || accepted.jobExecutionId() == null) {
                throw new CardDemoException(SUBMIT_FAILURE_MESSAGE);
            }
            LOGGER.info("Accepted {} as execution {} (status {})", TRANSACTION_DETAIL_REPORT_JOB,
                    accepted.jobExecutionId(), accepted.status());
            return accepted;
        } catch (RestClientResponseException e) {
            // batch-service answered, so the hand-off itself was written: the run was
            // REFUSED. Report why, rather than claiming the TDQ write failed.
            String reason = refusalReason(e);
            LOGGER.error("Submission of {} was refused with status {}: {}",
                    TRANSACTION_DETAIL_REPORT_JOB, e.getStatusCode().value(), reason);
            throw new CardDemoException(SUBMISSION_REFUSED_PREFIX + reason, e);
        } catch (RestClientException e) {
            // No answer at all (batch-service unreachable, timeout, unreadable response):
            // the hand-off never landed, which is exactly what the frozen literal reports.
            LOGGER.error("Submission of {} could not be handed off: {}",
                    TRANSACTION_DETAIL_REPORT_JOB, e.getMessage());
            throw new CardDemoException(SUBMIT_FAILURE_MESSAGE, e);
        }
    }

    /**
     * :purpose: Extract the reason batch-service gave for refusing a run from its error
     *  response body, falling back to the HTTP status text when the body carries no message.
     * :param e: the error response raised by the REST client.
     * :returns: a single-line reason suitable for the user-facing refusal message.
     */
    private static String refusalReason(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String message = extractJsonMessage(body);
        if (message != null && !message.isBlank()) {
            return message.replace('\n', ' ').trim();
        }
        String statusText = e.getStatusText();
        return statusText == null || statusText.isBlank()
                ? "HTTP " + e.getStatusCode().value()
                : statusText;
    }

    /**
     * :purpose: Read the ``message`` member of a JSON error body without pulling in a parser
     *  dependency on this hot path; the body is the shared error envelope produced by
     *  ``GlobalExceptionHandler``.
     * :param body: the raw response body; may be ``null`` or empty.
     * :returns: the ``message`` value, or ``null`` when it is absent.
     */
    private static String extractJsonMessage(String body) {
        if (body == null) {
            return null;
        }
        int keyIndex = body.indexOf("\"message\"");
        if (keyIndex < 0) {
            return null;
        }
        int valueStart = body.indexOf('"', body.indexOf(':', keyIndex) + 1);
        if (valueStart < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = valueStart + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                value.append(body.charAt(++i));
                continue;
            }
            if (c == '"') {
                break;
            }
            value.append(c);
        }
        return value.toString();
    }

    /**
     * :purpose: Render the caller's session cookie as a ``Cookie`` header value.
     * :returns: the ``SESSION=<value>`` header, or an empty string when the request
     *  carries no session cookie.
     */
    private String sessionCookieHeader() {
        Cookie[] cookies = httpRequest.getCookies();
        if (cookies == null) {
            return "";
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return SESSION_COOKIE_NAME + "=" + cookie.getValue();
            }
        }
        return "";
    }

}

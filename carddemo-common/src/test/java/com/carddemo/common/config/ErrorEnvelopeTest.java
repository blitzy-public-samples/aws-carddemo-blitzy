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

import com.carddemo.common.dto.BillPaymentRequestDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.FieldValidationException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.common.exception.UpstreamUnavailableException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Verify that {@link GlobalExceptionHandler} answers EVERY failure - the
 *  CardDemo domain exceptions and the framework MVC failures alike - with the one
 *  documented ``ErrorResponse`` envelope, and that a bean-validation failure
 *  surfaces exactly ONE message, the highest-precedence one in legacy screen
 *  order, as the COBOL ``EVALUATE TRUE`` edit blocks do.
 * :output: Exercises 405, 415, 400 (missing body, unreadable body, parameter type
 *  mismatch), 404 and the domain 400/404 through a stand-alone MockMvc, asserting
 *  the envelope keys are present and JSON is returned in every case.
 */
class ErrorEnvelopeTest {

    /** :purpose: Stand-alone MockMvc bound to the probe controller and the shared advice. */
    private MockMvc mockMvc;

    /**
     * :purpose: Probe controller exposing one endpoint per failure mode the advice
     *  must translate.
     */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        /**
         * :purpose: Endpoint that accepts only PUT, so a POST yields 405.
         * :param payload: the validated bill-payment payload.
         * :returns: the echoed payload when valid.
         */
        @PutMapping(value = "/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
        BillPaymentRequestDto validated(@Valid @RequestBody BillPaymentRequestDto payload) {
            return payload;
        }

        /**
         * :purpose: Endpoint that raises a domain not-found failure.
         * :returns: never returns normally.
         */
        @GetMapping("/notfound")
        String notFound() {
            throw new RecordNotFoundException("Did not find this account in account card xref file");
        }

        /**
         * :purpose: Endpoint that raises a domain business failure.
         * :returns: never returns normally.
         */
        @GetMapping("/business")
        String business() {
            // A literal a program actually MOVEs: COACTVWC:L672. The 88-level
            // 'Account number must be a non zero 11 digit number' is declared by
            // COACTVWC/COACTUPC and SET by neither, so it is not used as a probe.
            throw new CardDemoException("Account Filter must  be a non-zero 11 digit number");
        }

        /**
         * :purpose: Endpoint that raises a named-field input edit failure covering two
         *  members, as a legacy cross-field edit does.
         * :returns: never returns normally.
         */
        @GetMapping("/fieldedit")
        String fieldEdit() {
            throw new FieldValidationException(null,
                    List.of("custAddrStateCd", "custAddrZip"), "Invalid zip code for state");
        }

        /**
         * :purpose: Endpoint that requires an int query parameter, so a non-numeric
         *  value yields a type mismatch.
         * :param page: the page number.
         * :returns: the echoed page number.
         */
        @GetMapping("/typed")
        String typed(@RequestParam int page) {
            return String.valueOf(page);
        }

        /**
         * :purpose: Endpoint that raises an unreachable-collaborator failure carrying a
         *  frozen legacy literal.
         * :returns: never returns normally.
         */
        @GetMapping("/unreachable")
        String unreachable() {
            throw new UpstreamUnavailableException("Unable to Write TDQ (JOBS)...");
        }

        /**
         * :purpose: Endpoint whose datastore cannot be REACHED, as when Redis or
         *  PostgreSQL is down, so the advice's outage mapping is exercised.
         * :returns: never returns.
         */
        @GetMapping("/datastore-unreachable")
        String datastoreUnreachable() {
            throw new org.springframework.dao.DataAccessResourceFailureException(
                    "Unable to connect to Redis at redis:6379");
        }

        /**
         * :purpose: Endpoint whose datastore REJECTED the statement, which must stay a
         *  500 rather than being downgraded to an outage.
         * :returns: never returns.
         */
        @GetMapping("/rejected")
        String rejected() {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "could not execute statement");
        }

        /**
         * :purpose: Endpoint that raises the failure a session-store or datastore command
         *  timeout is translated into.
         * :returns: never returns normally.
         */
        @GetMapping("/timeout")
        String timeout() {
            throw new QueryTimeoutException("Redis command timed out");
        }
    }

    /**
     * :purpose: Build the stand-alone MockMvc with the probe controller and the
     *  shared advice registered exactly as a service registers it.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * :purpose: An unsupported method answers 405 with the documented envelope
     *  rather than Spring Boot's abbreviated body.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("405 carries the documented envelope")
    void methodNotAllowedCarriesEnvelope() throws Exception {
        mockMvc.perform(post("/probe/validated").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/probe/validated"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * :purpose: An unsupported media type answers 415 with the documented envelope.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("415 carries the documented envelope")
    void unsupportedMediaTypeCarriesEnvelope() throws Exception {
        mockMvc.perform(put("/probe/validated").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.path").value("/probe/validated"));
    }

    /**
     * :purpose: A missing request body answers 400 with the documented envelope.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("a missing body carries the documented envelope")
    void missingBodyCarriesEnvelope() throws Exception {
        mockMvc.perform(put("/probe/validated").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/probe/validated"));
    }

    /**
     * :purpose: An unreadable (malformed) body answers 400 with the documented envelope.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("a malformed body carries the documented envelope")
    void malformedBodyCarriesEnvelope() throws Exception {
        mockMvc.perform(put("/probe/validated").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"1\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    /**
     * :purpose: A parameter type mismatch answers 400 with the documented envelope.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("a field edit failure names every faulted member in the envelope")
    void fieldEditFailureNamesItsMembers() throws Exception {
        mockMvc.perform(get("/probe/fieldedit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid zip code for state"))
                .andExpect(jsonPath("$.fieldErrors.custAddrStateCd")
                        .value("Invalid zip code for state"))
                .andExpect(jsonPath("$.fieldErrors.custAddrZip")
                        .value("Invalid zip code for state"));
    }

    /**
     * :purpose: A parameter type mismatch answers 400 inside the documented envelope.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("a parameter type mismatch carries the documented envelope")
    void typeMismatchCarriesEnvelope() throws Exception {
        mockMvc.perform(get("/probe/typed").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * :purpose: The domain failures keep their frozen COBOL messages and their
     *  statuses inside the same envelope.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("domain failures keep their frozen messages in the same envelope")
    void domainFailuresKeepFrozenMessages() throws Exception {
        mockMvc.perform(get("/probe/notfound"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message")
                        .value("Did not find this account in account card xref file"));

        mockMvc.perform(get("/probe/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Account Filter must  be a non-zero 11 digit number"));
    }

    /**
     * :purpose: A bean-validation failure reports exactly ONE message - the legacy
     *  literal of the violated field - and never the framework's generic size text
     *  nor an invented summary. The account id carries NO width constraint, so an
     *  over-width value is not a bean-validation failure at all: COBIL00C performs no
     *  numeric or width edit and answers every unreadable key with the single literal
     *  'Account ID NOT found...', which BillPaymentService raises.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("validation reports exactly one COBOL literal, and only for constrained fields")
    void validationReportsSingleLiteralInScreenOrder() throws Exception {
        mockMvc.perform(put("/probe/validated").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"123456789012\",\"confirm\":\"YESPLEASE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Invalid value. Valid values are (Y/N)..."))
                .andExpect(jsonPath("$.fieldErrors.confirm")
                        .value("Invalid value. Valid values are (Y/N)..."))
                .andExpect(jsonPath("$.fieldErrors.accountId").doesNotExist());

        // The same over-width account id, alone, is accepted by the request contract.
        mockMvc.perform(put("/probe/validated").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"123456789012\",\"confirm\":\"\"}"))
                .andExpect(status().isOk());
    }

    /**
     * :purpose: A violation of the confirm flag alone reports the COBIL00C
     *  invalid-value literal, proving no framework string reaches the contract.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("an over-width confirm flag reports the COBIL00C literal")
    void overWidthConfirmReportsLegacyLiteral() throws Exception {
        mockMvc.perform(put("/probe/validated").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"00000000001\",\"confirm\":\"YESPLEASE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value. Valid values are (Y/N)..."))
                .andExpect(jsonPath("$.fieldErrors.confirm")
                        .value("Invalid value. Valid values are (Y/N)..."));
    }

    /**
     * :purpose: A collaborator that could not be reached reports ``503`` with a
     *  ``Retry-After`` hint and keeps its frozen legacy message byte-for-byte, so a caller
     *  can tell an outage apart from a rejected request without the observable text
     *  changing.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("an unreachable collaborator reports 503 and keeps its frozen message")
    void unreachableCollaboratorReports503() throws Exception {
        mockMvc.perform(get("/probe/unreachable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Unable to Write TDQ (JOBS)..."))
                .andExpect(jsonPath("$.path").value("/probe/unreachable"));
    }

    /**
     * :purpose: An UNREACHABLE datastore answers 503 with the documented envelope and the
     *  shared outage message -- the same detail the pre-dispatcher filter writes, so the
     *  contract does not depend on where the outage was detected -- and the datastore's
     *  address never reaches the body.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("an unreachable datastore answers 503 with the outage envelope")
    void unreachableDatastoreAnswers503() throws Exception {
        mockMvc.perform(get("/probe/datastore-unreachable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value(DatastoreOutageErrorFilter.MESSAGE))
                .andExpect(jsonPath("$.path").value("/probe/datastore-unreachable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("redis:6379"))));
    }

    /**
     * :purpose: A session-store or datastore command timeout is the same outage: ``503``
     *  with a ``Retry-After`` hint rather than the ``500`` the generic data-access mapping
     *  would report, and no driver detail is disclosed.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("a store timeout reports 503 rather than 500 and discloses no driver detail")
    void storeTimeoutReports503() throws Exception {
        mockMvc.perform(get("/probe/timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(DatastoreOutageErrorFilter.MESSAGE))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Redis command timed out"))));
    }

    /**
     * :purpose: A statement the datastore itself REJECTED stays a 500: only an
     *  unreachable datastore is an outage, so the narrower 503 mapping must not swallow
     *  the general data-access failure.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("a rejected statement still answers 500")
    void rejectedStatementStillAnswers500() throws Exception {
        mockMvc.perform(get("/probe/rejected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }
}

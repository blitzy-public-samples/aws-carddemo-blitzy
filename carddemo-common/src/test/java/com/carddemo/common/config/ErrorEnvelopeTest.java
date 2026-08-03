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
import com.carddemo.common.exception.RecordNotFoundException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
            throw new CardDemoException("Account number must be a non zero 11 digit number");
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
                        .value("Account number must be a non zero 11 digit number"));
    }

    /**
     * :purpose: A bean-validation failure reports exactly ONE message - the legacy
     *  literal of the first field in screen order - and never the framework's
     *  generic size text nor an invented summary.
     * :raises Exception: propagated from MockMvc.
     */
    @Test
    @DisplayName("validation reports exactly one COBOL literal, in screen order")
    void validationReportsSingleLiteralInScreenOrder() throws Exception {
        mockMvc.perform(put("/probe/validated").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"123456789012\",\"confirm\":\"YESPLEASE\"}"))
                .andExpect(status().isBadRequest())
                // accountId is declared first, exactly as the COBIL00C screen edits it.
                .andExpect(jsonPath("$.message")
                        .value("Account number must be a non zero 11 digit number"))
                .andExpect(jsonPath("$.fieldErrors.accountId")
                        .value("Account number must be a non zero 11 digit number"))
                .andExpect(jsonPath("$.fieldErrors.confirm").doesNotExist());
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
}

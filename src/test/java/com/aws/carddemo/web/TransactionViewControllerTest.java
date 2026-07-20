/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.TransactionViewRequest;
import com.aws.carddemo.dto.TransactionViewResponse;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.TransactionMapper;
import com.aws.carddemo.service.TransactionService;

/**
 * {@code @WebMvcTest} slice tests for {@link TransactionViewController} &mdash;
 * the REST re-platform of the CardDemo online <em>transaction view / detail</em>
 * program {@code COTRN01C} (CICS transaction {@code CT01}, relocated during the
 * migration to {@code legacy/cbl/COTRN01C.cbl}; BMS map {@code COTRN01} in
 * {@code legacy/bms/COTRN01.bms}; symbolic copybook
 * {@code legacy/cpy-bms/COTRN01.CPY}).
 *
 * <h2>What is verified (and why it maps to COBOL parity)</h2>
 * <p>These tests exercise the full, wired web slice &mdash; the controller, the
 * <em>real</em> {@link TransactionMapper} (imported, not mocked, so the
 * field-level BMS contract is genuinely validated), the security filter chain
 * from {@link SecurityConfig}, and the {@code @RestControllerAdvice}
 * {@code GlobalExceptionHandler} that {@code @WebMvcTest} auto-includes. Only the
 * business collaborator {@link TransactionService} is mocked, because the keyed
 * VSAM read it re-expresses is out of scope for a web-slice test.</p>
 * <ul>
 *   <li><strong>Authentication</strong> (AAP &sect;0.7.3, L1): an unauthenticated
 *       request is rejected with {@code 401} by the filter chain before the
 *       controller runs.</li>
 *   <li><strong>PF-key routing</strong> (AAP H2): the Java {@link PfKeyAction}
 *       routes exactly as the COBOL {@code EVALUATE EIBAID} block &mdash;
 *       {@code ENTER}=fetch, {@code PF3}=back to the main menu ({@code COMEN01C}/
 *       {@code CM00}), {@code PF4}=clear the screen, {@code PF5}=browse the
 *       transaction list ({@code COTRN00C}/{@code CT00}), any other key = the
 *       invalid-key advisory ({@code CCDA-MSG-INVALID-KEY}).</li>
 *   <li><strong>Not-found propagation</strong> (AAP M1): a missing transaction id
 *       surfaces the COBOL {@code DFHRESP(NOTFND)} "Transaction ID NOT found..."
 *       as {@code 404 application/problem+json}.</li>
 *   <li><strong>Monetary fidelity</strong> (AAP H3): the response {@code amount}
 *       is a scale-2 {@link BigDecimal} rendered as a plain decimal on the wire;
 *       no {@code double}/{@code float} is used anywhere.</li>
 *   <li><strong>Field-contract parity</strong> (AAP H2): every display field of
 *       the {@code COTRN1AO} symbolic map is present on the response DTO with the
 *       mapper's documented derivations (zero-padded category code and merchant
 *       id, leading-ten date truncation).</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The slice imports only {@link SecurityConfig} (so the real authorization
 * rules and RFC-7807 auth failures apply) and {@link TransactionMapper} (so the
 * real entity&rarr;DTO projection is exercised). {@link TransactionService} is a
 * {@link MockitoBean}. Requests are built with the real
 * {@link TransactionViewRequest} record and responses are parsed back into the
 * real {@link TransactionViewResponse} record, so the JSON contract is validated
 * end to end. CSRF is disabled by {@link SecurityConfig} (a stateless HTTP Basic
 * API), so the {@code POST} submissions need no CSRF token. The tests are
 * deterministic and headless (no Testcontainers, no database).</p>
 */
@WebMvcTest(TransactionViewController.class)
@Import({SecurityConfig.class, TransactionMapper.class})
class TransactionViewControllerTest {

    /** The single {@code @RequestMapping} path exposed by the controller under test. */
    private static final String ENDPOINT = "/api/v1/transactions/view";

    /** A 16-character transaction id that the mocked service resolves to a stub record. */
    private static final String EXISTING_TRAN_ID = "0000000000000001";

    /** A 16-character transaction id that the mocked service reports as not found. */
    private static final String MISSING_TRAN_ID = "0000000000000099";

    /** Response header naming the next program to display (COBOL {@code XCTL PROGRAM}). */
    private static final String NAV_HEADER_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header naming the next program's CICS transaction id. */
    private static final String NAV_HEADER_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** MockMvc wired with the Spring Security filter chain (filters ON). */
    @Autowired
    private MockMvc mockMvc;

    /** The Boot-configured mapper used to build request bodies and parse responses. */
    @Autowired
    private ObjectMapper objectMapper;

    /** The mocked business collaborator (keyed transaction read). */
    @MockitoBean
    private TransactionService transactionService;

    /**
     * Builds a fully populated stub {@link Transaction} whose fields exercise every
     * derivation performed by {@link TransactionMapper#toViewResponse}. The category
     * code {@code 5} and merchant id {@code 123L} are deliberately small so the
     * zero-padding to {@code X(4)} ({@code "0005"}) and {@code X(9)}
     * ({@code "000000123"}) is observable, and the 26-character timestamps prove the
     * leading-ten {@code YYYY-MM-DD} truncation. The amount is a scale-2
     * {@link BigDecimal} so monetary fidelity can be asserted to the cent.
     *
     * @return an in-memory transaction matching {@link #EXISTING_TRAN_ID}
     */
    private static Transaction sampleTransaction() {
        return new Transaction(
                EXISTING_TRAN_ID,                 // tranId          -> TRNIDO
                "01",                             // typeCd          -> TTYPCDO
                Integer.valueOf(5),               // catCd           -> TCATCDO ("0005")
                "POS",                            // tranSource      -> TRNSRCO
                "GROCERY STORE PURCHASE",         // tranDesc        -> TDESCO
                new BigDecimal("1234.56"),        // tranAmt         -> TRNAMTO (scale 2)
                Long.valueOf(123L),               // tranMerchantId  -> MIDO ("000000123")
                "WHOLE FOODS MARKET",             // tranMerchantName-> MNAMEO
                "AUSTIN",                         // tranMerchantCity-> MCITYO
                "78701",                          // tranMerchantZip -> MZIPO
                "4111111111111111",               // cardNum         -> CARDNUMO
                "2023-01-15.12.30.45.123456",     // origTs          -> TORIGDTO ("2023-01-15")
                "2023-01-16.08.15.00.000000");    // procTs          -> TPROCDTO ("2023-01-16")
    }

    /**
     * Serializes a {@link TransactionViewRequest} to its JSON wire form using the
     * application {@link ObjectMapper}, so the {@code POST} body is exactly what a
     * real client would send and the request DTO's binding contract is exercised.
     *
     * @param request the request to serialize
     * @return the JSON representation of {@code request}
     * @throws Exception if serialization fails
     */
    private String toJson(TransactionViewRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    /**
     * Parses a JSON response body back into the real {@link TransactionViewResponse}
     * record. Parsing into the record (rather than asserting on raw JSON) makes the
     * field assertions robust regardless of {@code non_null} inclusion: an omitted
     * {@code null} property deserializes back to {@code null}.
     *
     * @param result the completed MockMvc exchange
     * @return the deserialized response DTO
     * @throws Exception if the body cannot be read or parsed
     */
    private TransactionViewResponse parse(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                TransactionViewResponse.class);
    }

    // ------------------------------------------------------------------------
    // A. Security — unauthenticated access is rejected before the controller
    // ------------------------------------------------------------------------

    /**
     * An unauthenticated request to the protected view endpoint is rejected with
     * {@code 401 Unauthorized} by the security filter chain, and the business
     * collaborator is never reached &mdash; the Java form of the CICS sign-on gate
     * (AAP &sect;0.7.3, hotspot L1). No {@code @WithMockUser} is present on this
     * test, so the request is anonymous.
     */
    @Test
    @DisplayName("A. GET without authentication is rejected with 401")
    void getWithoutAuthenticationReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------
    // B. Screen entry — GET returns a blank COTRN1A screen
    // ------------------------------------------------------------------------

    /**
     * {@code GET} returns the blank screen-entry view (the COBOL
     * {@code NOT CDEMO-PGM-REENTER} branch): {@code 200 OK}, the standard header
     * populated (transaction name {@code CT01}, program {@code COTRN01C}, both
     * title lines), every detail field blank, no amount and no message. The service
     * is not consulted for a blank screen.
     */
    @Test
    @WithMockUser
    @DisplayName("B. GET returns a blank transaction-view screen")
    void getReturnsBlankScreen() throws Exception {
        MvcResult result = mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        TransactionViewResponse body = parse(result);
        assertThat(body.transactionName()).isEqualTo("CT01");
        assertThat(body.programName()).isEqualTo("COTRN01C");
        assertThat(body.title01()).isEqualTo("      AWS Mainframe Modernization       ");
        assertThat(body.title02()).isEqualTo("              CardDemo                  ");
        assertThat(body.transactionId()).isEmpty();
        assertThat(body.cardNumber()).isEmpty();
        assertThat(body.typeCode()).isEmpty();
        assertThat(body.categoryCode()).isEmpty();
        assertThat(body.amount()).isNull();
        assertThat(body.errorMessage()).isNull();

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------
    // C. ENTER — an existing transaction is fetched and projected
    // ------------------------------------------------------------------------

    /**
     * {@code POST} with {@link PfKeyAction#ENTER} and an existing id performs the
     * keyed lookup (COBOL {@code PROCESS-ENTER-KEY}) and returns the fully populated
     * detail projected by the real {@link TransactionMapper}: the id, card number
     * and type are echoed verbatim; the category code is zero-padded to {@code X(4)}
     * ({@code "0005"}); the merchant id is zero-padded to {@code X(9)}
     * ({@code "000000123"}); the origination/processing timestamps are truncated to
     * their leading {@code YYYY-MM-DD} ten characters; and the amount is a scale-2
     * {@link BigDecimal}. The service is invoked exactly once with the submitted id.
     */
    @Test
    @WithMockUser
    @DisplayName("C. POST ENTER with an existing id returns the transaction detail")
    void postEnterWithExistingIdReturnsTransactionDetail() throws Exception {
        when(transactionService.viewTransaction(EXISTING_TRAN_ID)).thenReturn(sampleTransaction());

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionViewRequest(EXISTING_TRAN_ID, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        TransactionViewResponse body = parse(result);
        assertThat(body.transactionName()).isEqualTo("CT01");
        assertThat(body.programName()).isEqualTo("COTRN01C");
        assertThat(body.transactionId()).isEqualTo(EXISTING_TRAN_ID);
        assertThat(body.cardNumber()).isEqualTo("4111111111111111");
        assertThat(body.typeCode()).isEqualTo("01");
        assertThat(body.categoryCode()).isEqualTo("0005");
        assertThat(body.source()).isEqualTo("POS");
        assertThat(body.description()).isEqualTo("GROCERY STORE PURCHASE");
        assertThat(body.merchantId()).isEqualTo("000000123");
        assertThat(body.merchantName()).isEqualTo("WHOLE FOODS MARKET");
        assertThat(body.merchantCity()).isEqualTo("AUSTIN");
        assertThat(body.merchantZip()).isEqualTo("78701");
        assertThat(body.originDate()).isEqualTo("2023-01-15");
        assertThat(body.processDate()).isEqualTo("2023-01-16");
        assertThat(body.amount()).isEqualByComparingTo("1234.56");
        assertThat(body.amount().scale()).isEqualTo(2);
        assertThat(body.errorMessage()).isNull();

        verify(transactionService).viewTransaction(EXISTING_TRAN_ID);
    }

    // ------------------------------------------------------------------------
    // D. ENTER — a missing transaction propagates to a 404 problem detail
    // ------------------------------------------------------------------------

    /**
     * {@code POST} with {@link PfKeyAction#ENTER} and an unknown id lets the
     * {@link RecordNotFoundException} thrown by the service propagate (the
     * controller deliberately does not catch it) to the {@code GlobalExceptionHandler},
     * which renders it as {@code 404 Not Found} with an RFC-7807
     * {@code application/problem+json} body carrying the COBOL
     * {@code DFHRESP(NOTFND)} message "Transaction ID NOT found...". This is the
     * caller-visible outcome of the legacy {@code EVALUATE WS-RESP-CD WHEN DFHRESP(NOTFND)}
     * branch (AAP M1).
     */
    @Test
    @WithMockUser
    @DisplayName("D. POST ENTER with an unknown id returns 404 problem+json")
    void postEnterWithUnknownIdReturnsNotFoundProblemDetail() throws Exception {
        when(transactionService.viewTransaction(MISSING_TRAN_ID))
                .thenThrow(new RecordNotFoundException("Transaction ID NOT found..."));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionViewRequest(MISSING_TRAN_ID, PfKeyAction.ENTER))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value("Transaction ID NOT found..."));

        verify(transactionService).viewTransaction(MISSING_TRAN_ID);
    }

    // ------------------------------------------------------------------------
    // E. PF3 — navigate back to the main menu (COMEN01C / CM00)
    // ------------------------------------------------------------------------

    /**
     * {@code POST} with {@link PfKeyAction#PF3} reproduces the COBOL {@code DFHPF3}
     * branch: it hands control back to the main menu {@code COMEN01C} (CICS
     * transaction {@code CM00}). The target program and transaction id are surfaced
     * as the {@code X-CardDemo-Next-*} navigation headers (the Java form of
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)}), the body is a blank screen, and the
     * business service is never consulted for a navigation key.
     */
    @Test
    @WithMockUser
    @DisplayName("E. POST PF3 navigates back to the main menu (COMEN01C/CM00)")
    void postPf3NavigatesToMainMenu() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionViewRequest(null, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(NAV_HEADER_PROGRAM, "COMEN01C"))
                .andExpect(header().string(NAV_HEADER_TRANSACTION, "CM00"));

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------
    // F. PF4 — clear the current screen (no lookup)
    // ------------------------------------------------------------------------

    /**
     * {@code POST} with {@link PfKeyAction#PF4} reproduces the COBOL {@code DFHPF4}
     * branch ({@code CLEAR-CURRENT-SCREEN}): the screen is blanked and re-sent with
     * {@code 200 OK}, no navigation headers are emitted, and &mdash; crucially
     * &mdash; the service is not called <em>even though a transaction id is present
     * in the request</em>, proving PF4 is a pure clear that never triggers a lookup.
     */
    @Test
    @WithMockUser
    @DisplayName("F. POST PF4 clears the screen without a lookup")
    void postPf4ClearsScreenWithoutLookup() throws Exception {
        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionViewRequest(EXISTING_TRAN_ID, PfKeyAction.PF4))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(NAV_HEADER_PROGRAM))
                .andExpect(header().doesNotExist(NAV_HEADER_TRANSACTION))
                .andReturn();

        TransactionViewResponse body = parse(result);
        assertThat(body.transactionName()).isEqualTo("CT01");
        assertThat(body.transactionId()).isEmpty();
        assertThat(body.cardNumber()).isEmpty();
        assertThat(body.amount()).isNull();
        assertThat(body.errorMessage()).isNull();

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------
    // G. PF5 — navigate to the transaction list (COTRN00C / CT00)
    // ------------------------------------------------------------------------

    /**
     * {@code POST} with {@link PfKeyAction#PF5} reproduces the COBOL {@code DFHPF5}
     * branch: it navigates to the transaction list {@code COTRN00C} (CICS
     * transaction {@code CT00}), surfacing the target via the {@code X-CardDemo-Next-*}
     * navigation headers. As with PF3, the body is a blank screen and the service is
     * never consulted.
     */
    @Test
    @WithMockUser
    @DisplayName("G. POST PF5 navigates to the transaction list (COTRN00C/CT00)")
    void postPf5NavigatesToTransactionList() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionViewRequest(null, PfKeyAction.PF5))))
                .andExpect(status().isOk())
                .andExpect(header().string(NAV_HEADER_PROGRAM, "COTRN00C"))
                .andExpect(header().string(NAV_HEADER_TRANSACTION, "CT00"));

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------
    // H. Other / unmapped key — the invalid-key advisory
    // ------------------------------------------------------------------------

    /**
     * {@code POST} with an attention key this screen does not map ({@link PfKeyAction#PF7}
     * here) reproduces the COBOL {@code WHEN OTHER} branch: {@code 200 OK} with the
     * verbatim invalid-key advisory ({@code CCDA-MSG-INVALID-KEY}) and no lookup. The
     * message text matches the controller's rendering of the {@code PIC X(50)}
     * literal with its fixed-field trailing padding removed.
     */
    @Test
    @WithMockUser
    @DisplayName("H. POST with an unmapped key returns the invalid-key advisory")
    void postUnmappedKeyReturnsInvalidKeyMessage() throws Exception {
        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionViewRequest(null, PfKeyAction.PF7))))
                .andExpect(status().isOk())
                .andReturn();

        TransactionViewResponse body = parse(result);
        assertThat(body.errorMessage()).isEqualTo("Invalid key pressed. Please see below...");
        assertThat(body.transactionId()).isEmpty();
        assertThat(body.amount()).isNull();

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------
    // I. Field-contract parity + monetary fidelity (COTRN1AO symbolic map)
    // ------------------------------------------------------------------------

    /**
     * Asserts that the successful-detail response preserves the field-level contract
     * of the {@code COTRN1AO} symbolic map: every display field of
     * {@code legacy/cpy-bms/COTRN01.CPY} is present on the JSON response under its
     * modern DTO name, with the mapper's documented derivations applied. This is the
     * executable proof of the BMS field-level contract (AAP H2).
     *
     * <p>Monetary fidelity (AAP H3) is proven twice over: the raw wire form carries
     * {@code "amount":1234.56} (a plain, scale-2 decimal &mdash; never scientific
     * notation and never a floating-point coercion), and the value parsed back into
     * the {@link TransactionViewResponse} record is a {@link BigDecimal} at scale
     * {@code 2}.</p>
     */
    @Test
    @WithMockUser
    @DisplayName("I. POST ENTER preserves the COTRN01 field contract and scale-2 amount")
    void postEnterPreservesFieldContractAndScaleTwoAmount() throws Exception {
        when(transactionService.viewTransaction(EXISTING_TRAN_ID)).thenReturn(sampleTransaction());

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionViewRequest(EXISTING_TRAN_ID, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                // Header fields (TRNNAMEO / TITLE01O / CURDATEO / PGMNAMEO / TITLE02O / CURTIMEO).
                .andExpect(jsonPath("$.transactionName").value("CT01"))
                .andExpect(jsonPath("$.title01").exists())
                .andExpect(jsonPath("$.title02").exists())
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.programName").value("COTRN01C"))
                .andExpect(jsonPath("$.currentTime").exists())
                // Detail fields (TRNIDO / CARDNUMO / TTYPCDO / TCATCDO / TRNSRCO / TDESCO / TRNAMTO).
                .andExpect(jsonPath("$.transactionId").value(EXISTING_TRAN_ID))
                .andExpect(jsonPath("$.cardNumber").value("4111111111111111"))
                .andExpect(jsonPath("$.typeCode").value("01"))
                .andExpect(jsonPath("$.categoryCode").value("0005"))
                .andExpect(jsonPath("$.source").value("POS"))
                .andExpect(jsonPath("$.description").value("GROCERY STORE PURCHASE"))
                .andExpect(jsonPath("$.amount").exists())
                // Date fields (TORIGDTO / TPROCDTO) and merchant fields (MIDO / MNAMEO / MCITYO / MZIPO).
                .andExpect(jsonPath("$.originDate").value("2023-01-15"))
                .andExpect(jsonPath("$.processDate").value("2023-01-16"))
                .andExpect(jsonPath("$.merchantId").value("000000123"))
                .andExpect(jsonPath("$.merchantName").value("WHOLE FOODS MARKET"))
                .andExpect(jsonPath("$.merchantCity").value("AUSTIN"))
                .andExpect(jsonPath("$.merchantZip").value("78701"))
                .andReturn();

        // Monetary fidelity on the wire: plain, scale-2 decimal (no double/float, no scientific form).
        assertThat(result.getResponse().getContentAsString()).contains("\"amount\":1234.56");

        // Monetary fidelity in the typed contract: BigDecimal at scale 2.
        TransactionViewResponse body = parse(result);
        assertThat(body.amount()).isEqualByComparingTo("1234.56");
        assertThat(body.amount().scale()).isEqualTo(2);
    }

    /**
     * {@code POST} with {@link PfKeyAction#ENTER} and a blank transaction id
     * reproduces the first edit of the COBOL {@code PROCESS-ENTER-KEY} paragraph
     * ({@code TRNIDINI = SPACES OR LOW-VALUES}): the empty-id message
     * "Tran ID can NOT be empty..." is returned on the same screen with
     * {@code 200 OK}, and the service is never invoked &mdash; the controller
     * rejects the blank key before any keyed read, exactly as the legacy program did.
     */
    @Test
    @WithMockUser
    @DisplayName("I(edge). POST ENTER with a blank id returns the empty-id message, no lookup")
    void postEnterWithBlankIdReturnsEmptyIdMessageWithoutLookup() throws Exception {
        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new TransactionViewRequest("", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andReturn();

        TransactionViewResponse body = parse(result);
        assertThat(body.errorMessage()).isEqualTo("Tran ID can NOT be empty...");
        assertThat(body.transactionId()).isEmpty();
        assertThat(body.amount()).isNull();

        verifyNoInteractions(transactionService);
    }
}


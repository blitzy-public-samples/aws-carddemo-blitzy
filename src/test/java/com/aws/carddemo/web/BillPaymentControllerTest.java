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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.BillPaymentRequest;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.BillPaymentMapper;
import com.aws.carddemo.service.BillPaymentService;
import com.aws.carddemo.service.BillPaymentService.BillPaymentResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
 * &#64;WebMvcTest} slice test for {@link BillPaymentController} &mdash; the Java
 * re-platform of the CardDemo COBOL online program {@code COBIL00C} (CICS
 * transaction {@code CB00}, BMS map {@code COBIL00}, source
 * {@code legacy/cbl/COBIL00C.cbl}). The Bill Payment screen pays an account
 * balance in full.
 *
 * <h2>What this slice verifies (and what it deliberately does not)</h2>
 * <p>This is a pure web-layer slice: only the controller, the imported
 * {@link SecurityConfig} filter chain, the real {@link BillPaymentMapper}, and the
 * auto-detected {@code GlobalExceptionHandler} are loaded. The business service
 * {@link BillPaymentService} is replaced by a Mockito mock
 * ({@link org.springframework.test.context.bean.override.mockito.MockitoBean
 * &#64;MockitoBean}), so these tests assert the <em>HTTP/JSON contract and
 * routing</em> the controller owns &mdash; PF-key dispatch, request/response field
 * mapping, monetary scale on the wire, and exception-to-status translation &mdash;
 * <strong>not</strong> the confirm-matrix business logic itself (that is covered
 * exhaustively by {@code BillPaymentServiceTest}). The controller intentionally
 * projects only {@link BillPaymentResult#newBalance()} and
 * {@link BillPaymentResult#message()} onto the response; it never inspects
 * {@code success} or {@code postedTransaction}, and the assertions below reflect
 * exactly that.</p>
 *
 * <h2>Behavioral-parity anchors (COBOL {@code COBIL00C})</h2>
 * <ul>
 *   <li>The {@code EVALUATE EIBAID} dispatch (Enter / PF3 / PF4 / other) becomes
 *       the {@link PfKeyAction} routing exercised by cases B and J.</li>
 *   <li>The {@code PROCESS-ENTER-KEY} confirmation matrix
 *       ({@code Y}/{@code y}/{@code N}/{@code n}/blank/other), the
 *       &quot;nothing to pay&quot; guard, and the empty-account-id message travel
 *       in the service {@link BillPaymentResult} and are surfaced verbatim as the
 *       response {@code errorMessage} (cases C&ndash;H).</li>
 *   <li>The CICS {@code DFHRESP(NOTFND)} account path becomes a
 *       {@link RecordNotFoundException} mapped to HTTP {@code 404} (case I).</li>
 *   <li>Every monetary value is a scale-2 {@link BigDecimal} on the wire; no
 *       {@code double}/{@code float} appears anywhere in this test (case K).</li>
 * </ul>
 *
 * <p>The suite is deterministic and headless: it opens no database and starts no
 * container (a pure MVC slice), and it contributes to the &ge;80% line-coverage
 * gate for the online web layer.</p>
 *
 * @see BillPaymentController
 * @see BillPaymentService
 * @see BillPaymentMapper
 */
@WebMvcTest(BillPaymentController.class)
@Import({SecurityConfig.class, BillPaymentMapper.class})
@WithMockUser
class BillPaymentControllerTest {

    /** The Bill Payment endpoint path ({@code @RequestMapping("/api/v1/billpay")}). */
    private static final String ENDPOINT = "/api/v1/billpay";

    /**
     * A representative eleven-digit account id ({@code ACTIDINI PIC X(11)} in the
     * BMS map). Eleven characters, digits only, so it satisfies the request DTO's
     * {@code @Size(max = 11)} / {@code @Pattern("^\\d{0,11}$")} constraints and
     * reaches the controller rather than being rejected as a 400.
     */
    private static final String ACCOUNT_ID = "11111111111";

    /** Transaction name shown in the Bill Payment screen header ({@code WS-TRANID}). */
    private static final String TRANSACTION_NAME = "CB00";

    /** Program name shown in the Bill Payment screen header ({@code WS-PGMNAME}). */
    private static final String PROGRAM_NAME = "COBIL00C";

    /** Program navigated to on PF3 (Main Menu, {@code COMEN01C}). */
    private static final String BACK_PROGRAM_NAME = "COMEN01C";

    /** Transaction id of the Main Menu screen navigated to on PF3 ({@code CM00}). */
    private static final String BACK_TRANSACTION_ID = "CM00";

    /** Response header advertising the next program to enter (PF3 navigation). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header advertising the next screen's transaction id (PF3 navigation). */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * The exact invalid-key message the controller shows for an unhandled key
     * (the COBOL {@code CCDA-MSG-INVALID-KEY} literal in the {@code WHEN OTHER}
     * branch). Mirrored here because the controller constant is private.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * A representative full success message, matching the shape the service builds
     * on a posted payment ({@code "Payment successful. " + " Your Transaction ID is "
     * + tranId + "."}, with the two-space gap after &quot;successful.&quot;). The
     * controller passes {@link BillPaymentResult#message()} through unchanged, so
     * the exact text is defined by the stub and asserted on the response.
     */
    private static final String SUCCESS_MESSAGE =
            "Payment successful.  Your Transaction ID is 0000000000000001.";

    /**
     * A fixed 26-character timestamp text ({@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS}
     * format {@code YYYY-MM-DD-HH.MM.SS.ffffff}) used to build the stub posted
     * transaction. Its value is irrelevant to the controller (which never reads the
     * posted transaction); it exists only so {@link BillPaymentResult#paid} receives
     * a well-formed record.
     */
    private static final String TIMESTAMP = "2024-01-01-12.00.00.000000";

    /** Entry point for performing HTTP requests against the sliced web layer. */
    @Autowired
    private MockMvc mockMvc;

    /** Boot-configured mapper used to serialize request bodies exactly as the app would. */
    @Autowired
    private ObjectMapper objectMapper;

    /** Mocked bill-payment business service; the controller's sole business collaborator. */
    @MockitoBean
    private BillPaymentService billPaymentService;

    // ------------------------------------------------------------------------
    // Test fixtures / helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a well-formed stub posted transaction for the confirmed-payment cases.
     * The attributes mirror the fixed bill-payment values the service sets on the
     * {@code TRAN-RECORD}; only a valid instance is required because the controller
     * never inspects it.
     *
     * @param tranId the 16-character transaction id
     * @param amount the transaction amount as a plain decimal string
     * @return a fully-constructed {@link Transaction}
     */
    private static Transaction postedTransaction(String tranId, String amount) {
        return new Transaction(
                tranId,
                "02",
                2,
                "POS TERM",
                "BILL PAYMENT - ONLINE",
                new BigDecimal(amount),
                999_999_999L,
                "BILL PAYMENT",
                "N/A",
                "N/A",
                "1234567890123456",
                TIMESTAMP,
                TIMESTAMP);
    }

    /**
     * Serializes a request DTO to JSON using the application's own
     * {@link ObjectMapper}, so the wire form the controller deserializes is exactly
     * what the running application would receive.
     *
     * @param request the request DTO to serialize
     * @return the JSON representation
     * @throws Exception if serialization fails
     */
    private String json(BillPaymentRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    // ------------------------------------------------------------------------
    // A. Authentication
    // ------------------------------------------------------------------------

    /**
     * Case A &mdash; an unauthenticated request is rejected with {@code 401
     * Unauthorized} rendered as RFC&nbsp;7807 {@code application/problem+json} by the
     * {@code ProblemDetailAuthenticationEntryPoint} wired in {@link SecurityConfig}.
     * The business service is never consulted.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithAnonymousUser
    @DisplayName("A: unauthenticated request -> 401 problem+json")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(billPaymentService);
    }

    // ------------------------------------------------------------------------
    // B. GET blank screen
    // ------------------------------------------------------------------------

    /**
     * Case B &mdash; {@code GET} renders the initial, blank Bill Payment screen
     * (COBOL first-entry path): HTTP {@code 200} with the standard header populated
     * (transaction / titles / date / program / time) and no account id, balance, or
     * message. The service is not called for a screen render.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("B: GET -> 200 blank screen with header, no account/balance/error")
    void getReturnsBlankScreen() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME))
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.title01").exists())
                .andExpect(jsonPath("$.title02").exists())
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.currentTime").exists())
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.currentBalance").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        verifyNoInteractions(billPaymentService);
    }

    // ------------------------------------------------------------------------
    // C. POST Enter, blank confirm -> display balance (no posting)
    // ------------------------------------------------------------------------

    /**
     * Case C &mdash; {@code POST} Enter with a blank confirmation displays the
     * current balance and the &quot;confirm to pay&quot; prompt without posting a
     * payment (COBOL {@code EVALUATE CONFIRMI WHEN SPACES}). The controller passes
     * the entered account id and the blank confirm straight to the service, then
     * projects the returned balance onto {@code currentBalance} (scale 2) and the
     * prompt onto {@code errorMessage}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("C: POST Enter blank confirm -> 200 display balance + confirm prompt")
    void postEnterBlankConfirmDisplaysBalance() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, ""))
                .thenReturn(BillPaymentResult.display(
                        new BigDecimal("100.00"), BillPaymentService.CONFIRM_PROMPT_MESSAGE));

        String body = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.errorMessage").value(BillPaymentService.CONFIRM_PROMPT_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        // Scale-2 plain decimal on the wire (parsing to double would hide the scale).
        assertThat(body).contains("\"currentBalance\":100.00");

        verify(billPaymentService).payBill(ACCOUNT_ID, "");
    }

    // ------------------------------------------------------------------------
    // D. POST Enter, confirm Y/y -> pay full balance
    // ------------------------------------------------------------------------

    /**
     * Case D1 &mdash; {@code POST} Enter with confirm {@code 'Y'} pays the full
     * balance: the service posts a transaction and returns the reduced balance
     * ({@code 0.00}). The controller surfaces that {@code 0.00} (scale 2) as
     * {@code currentBalance} and the success message as {@code errorMessage}. An
     * {@link ArgumentCaptor} confirms the controller forwarded the exact entered
     * account id and confirmation flag.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("D1: POST Enter confirm 'Y' -> 200 pay full balance (newBalance 0.00)")
    void postEnterConfirmYPaysFullBalance() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, "Y"))
                .thenReturn(BillPaymentResult.paid(
                        postedTransaction("0000000000000001", "100.00"),
                        new BigDecimal("0.00"),
                        SUCCESS_MESSAGE));

        String body = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "Y", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.errorMessage").value(SUCCESS_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"currentBalance\":0.00");

        ArgumentCaptor<String> accountCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> confirmCaptor = ArgumentCaptor.forClass(String.class);
        verify(billPaymentService).payBill(accountCaptor.capture(), confirmCaptor.capture());
        assertThat(accountCaptor.getValue()).isEqualTo(ACCOUNT_ID);
        assertThat(confirmCaptor.getValue()).isEqualTo("Y");
    }

    /**
     * Case D2 &mdash; the lower-case {@code 'y'} is accepted identically to
     * {@code 'Y'} (COBOL {@code EVALUATE CONFIRMI WHEN 'Y' WHEN 'y'}). The controller
     * forwards {@code 'y'} verbatim and again projects the reduced balance as a
     * scale-2 {@code currentBalance}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("D2: POST Enter confirm 'y' (lower-case) -> 200 pay full balance")
    void postEnterConfirmLowercaseYPaysFullBalance() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, "y"))
                .thenReturn(BillPaymentResult.paid(
                        postedTransaction("0000000000000002", "250.00"),
                        new BigDecimal("0.00"),
                        SUCCESS_MESSAGE));

        String body = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "y", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(SUCCESS_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"currentBalance\":0.00");

        verify(billPaymentService).payBill(ACCOUNT_ID, "y");
    }

    // ------------------------------------------------------------------------
    // E. POST Enter, confirm N/n -> cancel (no posting)
    // ------------------------------------------------------------------------

    /**
     * Case E1 &mdash; {@code POST} Enter with confirm {@code 'N'} cancels the
     * payment (COBOL {@code EVALUATE CONFIRMI WHEN 'N'}): the service returns a
     * message-only result with an empty message (the screen is simply cleared of any
     * prompt) and no balance. The controller renders HTTP {@code 200}; because the
     * message is an empty string it is still present on the wire (only {@code null}
     * fields are omitted), while {@code currentBalance} is absent.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("E1: POST Enter confirm 'N' -> 200 cancel, no payment")
    void postEnterConfirmNCancels() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, "N"))
                .thenReturn(BillPaymentResult.message(""));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "N", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.errorMessage").value(""))
                .andExpect(jsonPath("$.currentBalance").doesNotExist());

        verify(billPaymentService).payBill(ACCOUNT_ID, "N");
    }

    /**
     * Case E2 &mdash; the lower-case {@code 'n'} cancels identically to {@code 'N'}
     * (COBOL {@code WHEN 'N' WHEN 'n'}). The confirm flag is forwarded verbatim and
     * no payment is posted.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("E2: POST Enter confirm 'n' (lower-case) -> 200 cancel, no payment")
    void postEnterConfirmLowercaseNCancels() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, "n"))
                .thenReturn(BillPaymentResult.message(""));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "n", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(""))
                .andExpect(jsonPath("$.currentBalance").doesNotExist());

        verify(billPaymentService).payBill(ACCOUNT_ID, "n");
    }

    // ------------------------------------------------------------------------
    // F. POST Enter, invalid confirm -> "Invalid value..." (no posting)
    // ------------------------------------------------------------------------

    /**
     * Case F &mdash; {@code POST} Enter with a confirmation value other than
     * {@code Y}/{@code y}/{@code N}/{@code n}/blank yields the exact COBOL
     * &quot;Invalid value. Valid values are (Y/N)...&quot; message (the
     * {@code EVALUATE CONFIRMI WHEN OTHER} branch) and no payment. The message is
     * asserted against the service's public constant to keep the contract in lock-step
     * with the business layer.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("F: POST Enter invalid confirm 'X' -> 200 invalid-value message, no payment")
    void postEnterInvalidConfirmReturnsInvalidValueMessage() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, "X"))
                .thenReturn(BillPaymentResult.message(BillPaymentService.INVALID_CONFIRM_MESSAGE));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "X", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(BillPaymentService.INVALID_CONFIRM_MESSAGE))
                .andExpect(jsonPath("$.currentBalance").doesNotExist());

        verify(billPaymentService).payBill(ACCOUNT_ID, "X");
    }

    // ------------------------------------------------------------------------
    // G. POST Enter, nothing to pay (balance <= 0)
    // ------------------------------------------------------------------------

    /**
     * Case G &mdash; when the account balance is zero (or negative) the service
     * returns the exact COBOL &quot;You have nothing to pay...&quot; message together
     * with the (zero) balance for display, and no payment is posted. The controller
     * surfaces the {@code 0.00} balance (scale 2) as {@code currentBalance} and the
     * guard message as {@code errorMessage}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("G: POST Enter, balance <= 0 -> 200 nothing-to-pay message, no payment")
    void postEnterNothingToPayReturnsGuardMessage() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, ""))
                .thenReturn(BillPaymentResult.display(
                        new BigDecimal("0.00"), BillPaymentService.NOTHING_TO_PAY_MESSAGE));

        String body = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(BillPaymentService.NOTHING_TO_PAY_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"currentBalance\":0.00");

        verify(billPaymentService).payBill(ACCOUNT_ID, "");
    }

    // ------------------------------------------------------------------------
    // H. POST Enter, empty account id
    // ------------------------------------------------------------------------

    /**
     * Case H &mdash; an empty account id is valid at the DTO layer
     * ({@code @Pattern("^\\d{0,11}$")} permits the empty string), so the request
     * reaches the controller and the service returns the exact COBOL &quot;Acct ID
     * can NOT be empty...&quot; message. The controller renders HTTP {@code 200},
     * echoes the empty account id, and surfaces the guard message; no balance is
     * shown. This confirms empty-id handling stays on the 200 message path rather
     * than becoming a 400.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("H: POST Enter empty accountId -> 200 'Acct ID can NOT be empty' message")
    void postEnterEmptyAccountIdReturnsEmptyMessage() throws Exception {
        when(billPaymentService.payBill("", ""))
                .thenReturn(BillPaymentResult.message(BillPaymentService.ACCT_ID_EMPTY_MESSAGE));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest("", "", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(BillPaymentService.ACCT_ID_EMPTY_MESSAGE))
                .andExpect(jsonPath("$.currentBalance").doesNotExist());

        verify(billPaymentService).payBill("", "");
    }

    // ------------------------------------------------------------------------
    // I. POST Enter, account not found -> 404
    // ------------------------------------------------------------------------

    /**
     * Case I &mdash; when the service cannot find the account it throws
     * {@link RecordNotFoundException} (the COBOL {@code DFHRESP(NOTFND)} path). The
     * controller does not catch it; the {@code GlobalExceptionHandler} translates it
     * to HTTP {@code 404} rendered as {@code application/problem+json} with the
     * standard title and the exception's message as the detail.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("I: POST Enter, account not found -> 404 problem+json")
    void postEnterAccountNotFoundReturns404() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, "Y"))
                .thenThrow(RecordNotFoundException.of("Account", ACCOUNT_ID));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "Y", PfKeyAction.ENTER))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value("Account not found: " + ACCOUNT_ID));

        verify(billPaymentService).payBill(ACCOUNT_ID, "Y");
    }

    // ------------------------------------------------------------------------
    // J. PF-key dispatch (PF3 back / PF4 clear / other)
    // ------------------------------------------------------------------------

    /**
     * Case J1 &mdash; PF3 navigates back to the Main Menu (COBOL
     * {@code RETURN-TO-PREV-SCREEN} / {@code XCTL} to {@code COMEN01C}). The
     * controller returns HTTP {@code 200} with the {@code X-CardDemo-Next-*} headers
     * advertising the {@code COMEN01C}/{@code CM00} destination and a header body
     * naming that next screen. The bill-payment service is not involved in
     * navigation.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("J1: POST PF3 -> 200 back to Main Menu (CM00/COMEN01C) with nav headers")
    void postPf3NavigatesToMainMenu() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "", PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, BACK_PROGRAM_NAME))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, BACK_TRANSACTION_ID))
                .andExpect(jsonPath("$.transactionName").value(BACK_TRANSACTION_ID))
                .andExpect(jsonPath("$.programName").value(BACK_PROGRAM_NAME));

        verifyNoInteractions(billPaymentService);
    }

    /**
     * Case J2 &mdash; PF4 clears the current screen (COBOL
     * {@code CLEAR-CURRENT-SCREEN}). Even though a confirmation value is submitted,
     * the controller dispatches on the key first, returns a blank Bill Payment screen
     * ({@code 200}), and never posts a payment.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("J2: POST PF4 -> 200 blank screen, no payment")
    void postPf4ClearsScreen() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "Y", PfKeyAction.PF4))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME))
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.currentBalance").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        verifyNoInteractions(billPaymentService);
    }

    /**
     * Case J3 &mdash; any other (unmapped) key produces the exact
     * &quot;Invalid key pressed...&quot; message (COBOL {@code EVALUATE EIBAID WHEN
     * OTHER} / {@code CCDA-MSG-INVALID-KEY}) with HTTP {@code 200} and no payment.
     * {@link PfKeyAction#PF7} stands in for an unhandled key.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("J3: POST unmapped key (PF7) -> 200 invalid-key message, no payment")
    void postOtherKeyReturnsInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "", PfKeyAction.PF7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(INVALID_KEY_MESSAGE));

        verifyNoInteractions(billPaymentService);
    }

    // ------------------------------------------------------------------------
    // Bean Validation (@Valid) -> 400
    // ------------------------------------------------------------------------

    /**
     * A non-numeric account id violates the request DTO's
     * {@code @Pattern("^\\d{0,11}$")} constraint, so Bean Validation rejects the
     * request before the controller body runs: the {@code GlobalExceptionHandler}
     * maps {@code MethodArgumentNotValidException} to HTTP {@code 400}
     * {@code application/problem+json}. The service is never called. This complements
     * case H (empty id, which is valid and reaches the 200 message path).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("Validation: POST non-numeric accountId -> 400 problem+json, service untouched")
    void postInvalidAccountIdFormatReturns400() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest("abc", "", PfKeyAction.ENTER))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"));

        verifyNoInteractions(billPaymentService);
    }

    // ------------------------------------------------------------------------
    // K. Monetary fidelity (BigDecimal scale 2)
    // ------------------------------------------------------------------------

    /**
     * Case K &mdash; monetary values are always serialized as scale-2 plain decimals,
     * even when the service returns a value at a different scale. Here the service
     * returns a balance of {@code 100.5} (scale 1); the response's canonical
     * constructor normalizes it to {@code 100.50} (scale 2, {@code HALF_UP}), which
     * must appear verbatim on the wire. Asserting against the raw JSON body (rather
     * than a JsonPath numeric, which would parse to a scale-losing double) proves the
     * exact two-decimal representation the COBOL {@code COMP-3} contract requires.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("K: monetary balance normalized to scale-2 plain decimal on the wire")
    void balanceIsSerializedAtScaleTwo() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, ""))
                .thenReturn(BillPaymentResult.display(
                        new BigDecimal("100.5"), BillPaymentService.CONFIRM_PROMPT_MESSAGE));

        String body = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"currentBalance\":100.50");
        assertThat(body).doesNotContain("\"currentBalance\":100.5,");
        assertThat(body).doesNotContain("\"currentBalance\":100.5}");

        verify(billPaymentService).payBill(ACCOUNT_ID, "");
    }

    // ------------------------------------------------------------------------
    // L. Field-contract parity with the COBIL00 copybook
    // ------------------------------------------------------------------------

    /**
     * Case L &mdash; the response exposes exactly the field names of the
     * {@code COBIL00} symbolic map contract: the standard header
     * ({@code transactionName}, {@code title01}, {@code currentDate},
     * {@code programName}, {@code title02}, {@code currentTime}) plus the screen body
     * ({@code accountId}, {@code currentBalance}, {@code errorMessage}). A populated
     * display response is used so every contract field is present and can be asserted
     * by name, guarding against accidental renames that would break the wire contract.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("L: response field names match the COBIL00 screen contract")
    void responseFieldNamesMatchScreenContract() throws Exception {
        when(billPaymentService.payBill(ACCOUNT_ID, ""))
                .thenReturn(BillPaymentResult.display(
                        new BigDecimal("100.00"), BillPaymentService.CONFIRM_PROMPT_MESSAGE));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BillPaymentRequest(ACCOUNT_ID, "", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME))
                .andExpect(jsonPath("$.title01").exists())
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.title02").exists())
                .andExpect(jsonPath("$.currentTime").exists())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.currentBalance").exists())
                .andExpect(jsonPath("$.errorMessage").value(BillPaymentService.CONFIRM_PROMPT_MESSAGE));

        verify(billPaymentService).payBill(ACCOUNT_ID, "");
    }
}

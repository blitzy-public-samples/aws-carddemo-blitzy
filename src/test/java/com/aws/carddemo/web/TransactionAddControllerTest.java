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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

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
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.TransactionAddRequest;
import com.aws.carddemo.dto.TransactionAddResponse;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.TransactionMapper;
import com.aws.carddemo.security.CardDemoUserDetailsService;
import com.aws.carddemo.service.TransactionService;
import com.aws.carddemo.service.TransactionService.AddTransactionCommand;
import com.aws.carddemo.service.TransactionService.TransactionValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest @WebMvcTest}
 * slice test for {@link TransactionAddController} &mdash; the REST re-expression of the
 * online COBOL/CICS program {@code COTRN02C} (CICS transaction {@code CT02}, BMS map
 * {@code COTRN02}), relocated to {@code legacy/cbl/COTRN02C.cbl}.
 *
 * <p>The suite pins the observable <em>Transaction Add</em> contract so the migration
 * preserves 100% of the legacy screen behavior (AAP &sect;0.9.2, &sect;0.9.6) without
 * feature expansion:</p>
 * <ul>
 *   <li><strong>Validate&nbsp;&rarr;&nbsp;confirm&nbsp;&rarr;&nbsp;add flow</strong>
 *       &mdash; the COBOL {@code PROCESS-ENTER-KEY} {@code EVALUATE CONFIRMI}: a
 *       {@code 'Y'}/{@code 'y'} confirm commits the add; {@code 'N'}/{@code 'n'}, blank
 *       or absent re-prompts with "{@code Confirm to add this transaction...}"; any other
 *       value yields "{@code Invalid value. Valid values are (Y/N)...}".</li>
 *   <li><strong>Controller never assigns {@code tranId}</strong> (AAP &sect;0.7.1 H5)
 *       &mdash; the 16-character id is the service's responsibility (increment-from-max
 *       {@code IdGenerator} parity). Proven three ways: the captured
 *       {@link AddTransactionCommand} carries none of the operator-entered id, the command
 *       record structurally has no {@code tranId}/{@code id} component, and the real
 *       {@link TransactionMapper#toEntity(TransactionAddRequest)} leaves the entity id
 *       {@code null}.</li>
 *   <li><strong>Attention-key routing</strong> (COBOL {@code EVALUATE EIBAID}) &mdash;
 *       PF4 clears the form, PF5 copies the last transaction, PF3 navigates back to the
 *       main menu ({@code COMEN01C}/{@code CM00}), any other key is the invalid-key
 *       redisplay.</li>
 *   <li><strong>Field-contract + monetary fidelity</strong> (AAP &sect;0.7.1 H2/H3) &mdash;
 *       the response echoes the {@code COTRN02} field names and the amount is an exact
 *       {@link BigDecimal} at scale 2 (never {@code double}/{@code float}); no internal id
 *       leaks onto the add-form response.</li>
 * </ul>
 *
 * <p><strong>Harness.</strong> Pure web slice (no Docker, no database): only
 * {@link TransactionAddController} is instantiated, with the production
 * {@link SecurityConfig} and the real {@link TransactionMapper} imported so the security
 * filter chain and the entity&harr;DTO mapping are exercised for real. The
 * {@link TransactionService} is mocked with {@link MockitoBean @MockitoBean} (the
 * business edits and id generation are unit-tested against the real service elsewhere).
 * {@link CardDemoUserDetailsService} is mocked to give the imported security configuration
 * a deterministic {@code UserDetailsService} bean; authentication itself is supplied by
 * {@link WithMockUser @WithMockUser}. CSRF is disabled by {@link SecurityConfig}, so the
 * POST cases need no CSRF token.</p>
 */
@WebMvcTest(TransactionAddController.class)
@Import({SecurityConfig.class, TransactionMapper.class})
@WithMockUser
@DisplayName("TransactionAddController (CT02 / COTRN02C) web slice")
class TransactionAddControllerTest {

    /** Base path of the Transaction Add screen, matching the controller's {@code @RequestMapping}. */
    private static final String ADD_PATH = "/api/v1/transactions/add";

    /** Response-header name advertising the next program to enter (PF3 navigation contract). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response-header name advertising the next screen's CICS transaction id (PF3 navigation contract). */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** The service-assigned 16-character transaction id returned by the mocked add (COBOL {@code TRAN-ID}). */
    private static final String SERVICE_ASSIGNED_TRAN_ID = "0000000000000312";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** The real, imported hand-written mapper &mdash; used to prove {@code toEntity} never sets the id. */
    @Autowired
    private TransactionMapper transactionMapper;

    @MockitoBean
    private TransactionService transactionService;

    /**
     * Mocked so the imported {@link SecurityConfig} has a single, deterministic
     * {@code UserDetailsService} bean. It is never invoked because
     * {@link WithMockUser @WithMockUser}/{@link WithAnonymousUser @WithAnonymousUser}
     * populate the security context directly; declaring it simply keeps the security
     * wiring in the slice unambiguous.
     */
    @MockitoBean
    private CardDemoUserDetailsService userDetailsService;

    // ------------------------------------------------------------------------
    // Test fixtures / helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a fully-populated, structurally-valid add request (every DTO
     * {@code @Size}/{@code @Pattern}/{@code @Digits} constraint satisfied) using the
     * account-id key path, so a POST reaches the controller body rather than being
     * rejected by {@code @Valid} at HTTP 400.
     *
     * @param confirm the confirm flag ({@code "Y"}, {@code "N"}, {@code "X"}, {@code null}, ...)
     * @param action  the transmitted attention key (may be {@code null} = ENTER)
     * @return a valid {@link TransactionAddRequest}
     */
    private static TransactionAddRequest fullRequest(String confirm, PfKeyAction action) {
        return new TransactionAddRequest(
                "00000000011",              // accountId  (ACTIDINI, 11 digits)
                null,                       // cardNumber (CARDNINI) - account path in use
                "01",                       // typeCode   (TTYPCDI)
                "0005",                     // categoryCode (TCATCDI)
                "POS",                      // source     (TRNSRCI)
                "Test purchase",            // description (TDESCI)
                new BigDecimal("100.00"),   // amount     (TRNAMTI) - BigDecimal scale 2
                "2024-01-15",               // originDate (TORIGDTI)
                "2024-01-15",               // processDate (TPROCDTI)
                "123456789",                // merchantId (MIDI)
                "Test Merchant",            // merchantName (MNAMEI)
                "Seattle",                  // merchantCity (MCITYI)
                "98101",                    // merchantZip (MZIPI)
                confirm,                    // confirm    (CONFIRMI)
                action);                    // action     (EIBAID)
    }

    /**
     * Builds a persisted-transaction stub carrying the service-assigned id, standing in
     * for the entity the mocked {@link TransactionService#addTransaction(AddTransactionCommand)}
     * (or {@link TransactionService#findLastTransaction()}) returns.
     *
     * @return a {@link Transaction} whose {@code tranId} is {@value #SERVICE_ASSIGNED_TRAN_ID}
     */
    private static Transaction stubPersisted() {
        return new Transaction(
                SERVICE_ASSIGNED_TRAN_ID,   // tranId (assigned by the service, never the controller)
                "01",                       // typeCd
                5,                          // catCd
                "POS",                      // tranSource
                "Test purchase",            // tranDesc
                new BigDecimal("100.00"),   // tranAmt
                123456789L,                 // tranMerchantId
                "Test Merchant",            // tranMerchantName
                "Seattle",                  // tranMerchantCity
                "98101",                    // tranMerchantZip
                "1234567890123456",         // cardNum
                "2024-01-15",               // origTs
                "2024-01-15");              // procTs
    }

    /**
     * Deserializes an add-screen response body back into its DTO so field values (and,
     * critically, the {@code amount} scale) can be asserted robustly regardless of
     * null-omission serialization settings.
     *
     * @param body the JSON response body
     * @return the parsed {@link TransactionAddResponse}
     * @throws Exception if the body cannot be parsed
     */
    private TransactionAddResponse parse(String body) throws Exception {
        return objectMapper.readValue(body, TransactionAddResponse.class);
    }

    // ------------------------------------------------------------------------
    // A. Authentication gate
    // ------------------------------------------------------------------------

    /**
     * A. An unauthenticated request to the protected Transaction Add endpoint is
     * rejected with HTTP 401 by the security filter chain ({@code anyRequest()
     * .authenticated()} plus the {@code ProblemDetailAuthenticationEntryPoint}); the
     * request never reaches the controller, so the service is untouched.
     */
    @Test
    @WithAnonymousUser
    @DisplayName("A. Unauthenticated request is rejected with 401")
    void unauthenticatedRequestIsRejectedWith401() throws Exception {
        mockMvc.perform(get(ADD_PATH))
                .andExpect(status().isUnauthorized());

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
        verify(transactionService, never()).findLastTransaction();
    }

    // ------------------------------------------------------------------------
    // B. GET - blank add screen (first-entry SEND-TRNADD-SCREEN)
    // ------------------------------------------------------------------------

    /**
     * B. {@code GET} returns the first-entry blank add screen at HTTP 200: the header is
     * populated (transaction {@code CT02}, program {@code COTRN02C}) while every entry
     * field and the message line are blank/absent, and no internal id is present.
     */
    @Test
    @DisplayName("B. GET returns a blank add screen with header, no error, no id leak")
    void getReturnsBlankAddScreen() throws Exception {
        String body = mockMvc.perform(get(ADD_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value("CT02"))
                .andExpect(jsonPath("$.programName").value("COTRN02C"))
                // No internal transaction id is part of the add-form contract.
                .andExpect(jsonPath("$.transactionId").doesNotExist())
                .andExpect(jsonPath("$.tranId").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        TransactionAddResponse response = parse(body);
        assertThat(response.transactionName()).isEqualTo("CT02");
        assertThat(response.programName()).isEqualTo("COTRN02C");
        assertThat(response.errorMessage()).isNull();
        assertThat(response.accountId()).isNull();
        assertThat(response.cardNumber()).isNull();
        assertThat(response.amount()).isNull();
        assertThat(response.confirm()).isNull();

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
    }

    // ------------------------------------------------------------------------
    // C-F. ENTER key - the confirm-then-commit EVALUATE CONFIRMI
    // ------------------------------------------------------------------------

    /**
     * C. A fully-valid ENTER submit with the confirm flag left blank shows the
     * "{@code Confirm to add this transaction...}" prompt at HTTP 200 and echoes the
     * entered values; the add is <em>not</em> yet performed (COBOL {@code WHEN SPACES}).
     */
    @Test
    @DisplayName("C. ENTER with blank confirm shows the confirm prompt; add not yet performed")
    void postEnterValidAwaitsConfirmation() throws Exception {
        TransactionAddRequest request = fullRequest(null, PfKeyAction.ENTER);

        String body = mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Confirm to add this transaction..."))
                // Entered values are echoed for the confirm redisplay.
                .andExpect(jsonPath("$.accountId").value("00000000011"))
                .andExpect(jsonPath("$.typeCode").value("01"))
                .andExpect(jsonPath("$.description").value("Test purchase"))
                .andReturn().getResponse().getContentAsString();

        TransactionAddResponse response = parse(body);
        assertThat(response.amount()).isEqualByComparingTo("100.00");

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
    }

    /**
     * D. A confirmed ENTER submit ({@code confirm = 'Y'}) commits the add and shows the
     * COBOL success message carrying the service-assigned id.
     *
     * <p><strong>Invariant (AAP &sect;0.7.1 H5): the controller never assigns the
     * transaction id.</strong> The {@link AddTransactionCommand} captured on the way into
     * the service is proven to carry only the operator-entered fields (the amount rendered
     * into the {@code [+-]NNNNNNNN.NN} text the service edits expect) and, structurally, to
     * have no {@code tranId}/{@code id} component at all &mdash; so the controller has no
     * channel through which to set an id. The id in the success message
     * ({@value #SERVICE_ASSIGNED_TRAN_ID}) originates entirely from the service's return
     * value.</p>
     */
    @Test
    @DisplayName("D. ENTER confirm 'Y' adds the transaction; controller never assigns tranId")
    void postEnterConfirmYAddsTransaction() throws Exception {
        when(transactionService.addTransaction(any(AddTransactionCommand.class)))
                .thenReturn(stubPersisted());
        TransactionAddRequest request = fullRequest("Y", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(
                        "Transaction added successfully.  Your Tran ID is " + SERVICE_ASSIGNED_TRAN_ID + "."))
                .andExpect(jsonPath("$.transactionName").value("CT02"))
                .andExpect(jsonPath("$.programName").value("COTRN02C"))
                // Even on success the add-form response never carries an internal id field.
                .andExpect(jsonPath("$.transactionId").doesNotExist())
                .andExpect(jsonPath("$.tranId").doesNotExist());

        ArgumentCaptor<AddTransactionCommand> captor = ArgumentCaptor.forClass(AddTransactionCommand.class);
        verify(transactionService, times(1)).addTransaction(captor.capture());
        AddTransactionCommand command = captor.getValue();

        // The controller forwards only the operator-entered fields, in the copybook order.
        assertThat(command.accountId()).isEqualTo("00000000011");
        assertThat(command.cardNumber()).isNull();
        assertThat(command.typeCd()).isEqualTo("01");
        assertThat(command.categoryCd()).isEqualTo("0005");
        assertThat(command.source()).isEqualTo("POS");
        assertThat(command.description()).isEqualTo("Test purchase");
        // Amount is rendered into the signed fixed-point text the service edits expect.
        assertThat(command.amount()).isEqualTo("+00000100.00");
        assertThat(command.origDate()).isEqualTo("2024-01-15");
        assertThat(command.procDate()).isEqualTo("2024-01-15");
        assertThat(command.merchantId()).isEqualTo("123456789");
        assertThat(command.merchantName()).isEqualTo("Test Merchant");
        assertThat(command.merchantCity()).isEqualTo("Seattle");
        assertThat(command.merchantZip()).isEqualTo("98101");

        // Structural proof: the command carries no transaction-id component whatsoever, so
        // the controller cannot have assigned one - the id is exclusively the service's job.
        boolean hasIdComponent = Arrays.stream(AddTransactionCommand.class.getRecordComponents())
                .map(RecordComponent::getName)
                .anyMatch(name -> name.equalsIgnoreCase("tranId") || name.equalsIgnoreCase("id"));
        assertThat(hasIdComponent)
                .as("AddTransactionCommand must carry no transaction-id component")
                .isFalse();
    }

    /**
     * D (case-insensitive). A lower-case {@code confirm = 'y'} is accepted exactly like
     * {@code 'Y'} (COBOL {@code WHEN 'Y' WHEN 'y'}), committing the add.
     */
    @Test
    @DisplayName("D. ENTER confirm lower-case 'y' is accepted and adds the transaction")
    void postEnterConfirmLowercaseYAddsTransaction() throws Exception {
        when(transactionService.addTransaction(any(AddTransactionCommand.class)))
                .thenReturn(stubPersisted());
        TransactionAddRequest request = fullRequest("y", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(
                        "Transaction added successfully.  Your Tran ID is " + SERVICE_ASSIGNED_TRAN_ID + "."));

        verify(transactionService, times(1)).addTransaction(any(AddTransactionCommand.class));
    }

    /**
     * E. {@code confirm = 'N'} re-prompts with "{@code Confirm to add this
     * transaction...}" at HTTP 200 (COBOL {@code WHEN 'N'} is treated identically to a
     * blank confirm &mdash; it re-prompts, it does not cancel or clear); the add is not
     * performed.
     */
    @Test
    @DisplayName("E. ENTER confirm 'N' re-prompts (does not add)")
    void postEnterConfirmNReprompts() throws Exception {
        TransactionAddRequest request = fullRequest("N", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Confirm to add this transaction..."));

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
    }

    /**
     * E (case-insensitive). A lower-case {@code confirm = 'n'} behaves like {@code 'N'}
     * (COBOL {@code WHEN 'N' WHEN 'n'}): it re-prompts and does not add.
     */
    @Test
    @DisplayName("E. ENTER confirm lower-case 'n' re-prompts (does not add)")
    void postEnterConfirmLowercaseNReprompts() throws Exception {
        TransactionAddRequest request = fullRequest("n", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Confirm to add this transaction..."));

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
    }

    /**
     * F. A confirm flag outside {@code Y}/{@code N}/blank (here {@code 'X'}) yields the
     * "{@code Invalid value. Valid values are (Y/N)...}" message at HTTP 200 (COBOL
     * {@code EVALUATE CONFIRMI WHEN OTHER}); the add is not performed. The single-character
     * confirm carries no value {@code @Pattern}, so this reaches the controller branch
     * rather than being rejected at the transport layer.
     */
    @Test
    @DisplayName("F. ENTER invalid confirm 'X' shows the invalid-value message (does not add)")
    void postEnterInvalidConfirmShowsInvalidValueMessage() throws Exception {
        TransactionAddRequest request = fullRequest("X", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Invalid value. Valid values are (Y/N)..."));

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
    }

    // ------------------------------------------------------------------------
    // G. Service-driven field edits surface as a same-screen 200 message
    // ------------------------------------------------------------------------

    /**
     * G. An empty required field is not rejected by the DTO (the add fields are optional
     * at the transport layer to support the confirm-then-commit flow); on a confirmed
     * add the service raises a {@link TransactionValidationException} carrying the exact
     * COBOL {@code WS-MESSAGE} text, which the controller surfaces as a same-screen HTTP
     * 200 redisplay (COBOL edit failure = {@code SEND-TRNADD-SCREEN}, not an abend). Here
     * the empty {@code typeCode} maps to "{@code Type CD can NOT be empty...}".
     */
    @Test
    @DisplayName("G. ENTER confirm 'Y' with an empty field echoes the service edit message at 200")
    void postEnterEmptyFieldSurfacesServiceValidationMessage() throws Exception {
        when(transactionService.addTransaction(any(AddTransactionCommand.class)))
                .thenThrow(new TransactionValidationException("Type CD can NOT be empty..."));
        // Omit typeCode (COBOL first data-field empty edit).
        TransactionAddRequest request = new TransactionAddRequest(
                "00000000011", null, null, "0005", "POS", "Test purchase",
                new BigDecimal("100.00"), "2024-01-15", "2024-01-15",
                "123456789", "Test Merchant", "Seattle", "98101", "Y", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Type CD can NOT be empty..."))
                // The submitted values are echoed back for correction.
                .andExpect(jsonPath("$.accountId").value("00000000011"));

        verify(transactionService, times(1)).addTransaction(any(AddTransactionCommand.class));
    }

    /**
     * G (format edit). A malformed field surfaces its exact service message the same way:
     * the controller does not reinterpret or wrap the edit text. Here the service reports
     * "{@code Amount should be in format -99999999.99}" and it is echoed verbatim at 200.
     */
    @Test
    @DisplayName("G. ENTER confirm 'Y' surfaces a service format-edit message verbatim at 200")
    void postEnterServiceFormatEditMessageIsSurfacedVerbatim() throws Exception {
        when(transactionService.addTransaction(any(AddTransactionCommand.class)))
                .thenThrow(new TransactionValidationException("Amount should be in format -99999999.99"));
        TransactionAddRequest request = fullRequest("Y", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Amount should be in format -99999999.99"));

        verify(transactionService, times(1)).addTransaction(any(AddTransactionCommand.class));
    }

    // ------------------------------------------------------------------------
    // 404 / 409 - keyed-lookup and integrity outcomes propagate to the global handler
    // ------------------------------------------------------------------------

    /**
     * A {@link RecordNotFoundException} from the service (unknown account/card
     * cross-reference) is deliberately not caught by the controller; it propagates to the
     * shared {@code GlobalExceptionHandler}, which maps it to HTTP 404 with an RFC-7807
     * {@code application/problem+json} body (AAP &sect;0.7.2 M1).
     */
    @Test
    @DisplayName("404. Service RecordNotFoundException propagates to the global 404 handler")
    void postRecordNotFoundPropagatesTo404() throws Exception {
        when(transactionService.addTransaction(any(AddTransactionCommand.class)))
                .thenThrow(new RecordNotFoundException("Account ID NOT found..."));
        TransactionAddRequest request = fullRequest("Y", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verify(transactionService, times(1)).addTransaction(any(AddTransactionCommand.class));
    }

    /**
     * A {@link DuplicateKeyException} from the service (id collision) likewise propagates
     * to the global handler and is mapped to HTTP 409 with a problem+json body, rather than
     * being swallowed as a same-screen message (AAP &sect;0.7.2 M1).
     */
    @Test
    @DisplayName("409. Service DuplicateKeyException propagates to the global 409 handler")
    void postDuplicateKeyPropagatesTo409() throws Exception {
        when(transactionService.addTransaction(any(AddTransactionCommand.class)))
                .thenThrow(new DuplicateKeyException("Tran ID already exist..."));
        TransactionAddRequest request = fullRequest("Y", PfKeyAction.ENTER);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verify(transactionService, times(1)).addTransaction(any(AddTransactionCommand.class));
    }

    // ------------------------------------------------------------------------
    // H-I. Attention-key routing (COBOL EVALUATE EIBAID)
    // ------------------------------------------------------------------------

    /**
     * H (PF4). PF4 ({@code CLEAR-CURRENT-SCREEN}) redisplays a blank form at HTTP 200:
     * every entered value is discarded (the submitted {@code accountId} does not appear
     * on the response) and no service call is made.
     */
    @Test
    @DisplayName("H. PF4 clears the form to a blank add screen")
    void postPf4ClearsForm() throws Exception {
        TransactionAddRequest request = fullRequest("Y", PfKeyAction.PF4);

        String body = mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionName").value("CT02"))
                .andExpect(jsonPath("$.programName").value("COTRN02C"))
                // The submitted account id must be cleared, not echoed.
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        TransactionAddResponse response = parse(body);
        assertThat(response.accountId()).isNull();
        assertThat(response.amount()).isNull();
        assertThat(response.errorMessage()).isNull();

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
        verify(transactionService, never()).findLastTransaction();
    }

    /**
     * H (PF5). PF5 ({@code COPY-LAST-TRAN-DATA}) pre-fills the detail fields from the last
     * transaction (fetched through {@link TransactionService#findLastTransaction()}) while
     * preserving the operator-entered key, then falls through to the enter-key flow; with a
     * blank confirm the copied values are shown under the "{@code Confirm to add this
     * transaction...}" prompt and the add is not yet performed.
     */
    @Test
    @DisplayName("H. PF5 copies the last transaction and shows the confirm prompt (does not add)")
    void postPf5CopyLastPrefillsAndPrompts() throws Exception {
        when(transactionService.findLastTransaction()).thenReturn(Optional.of(stubPersisted()));
        // Blank confirm + preserved account key; the detail fields are irrelevant (copied over).
        TransactionAddRequest request = new TransactionAddRequest(
                "00000000011", null, null, null, null, null, null, null, null,
                null, null, null, null, null, PfKeyAction.PF5);

        String body = mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Confirm to add this transaction..."))
                // Operator-entered key preserved; detail fields copied from the last transaction.
                .andExpect(jsonPath("$.accountId").value("00000000011"))
                .andExpect(jsonPath("$.typeCode").value("01"))
                .andExpect(jsonPath("$.description").value("Test purchase"))
                .andReturn().getResponse().getContentAsString();

        TransactionAddResponse response = parse(body);
        assertThat(response.amount()).isEqualByComparingTo("100.00");

        verify(transactionService, times(1)).findLastTransaction();
        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
    }

    /**
     * H (PF5, empty master). When there is no prior transaction to copy
     * ({@link TransactionService#findLastTransaction()} is empty), PF5 redisplays the
     * entry screen unchanged with the informational "copy last unavailable" note and does
     * not add.
     */
    @Test
    @DisplayName("H. PF5 with an empty transaction master shows the copy-last-unavailable note")
    void postPf5CopyLastWithEmptyMasterShowsUnavailable() throws Exception {
        when(transactionService.findLastTransaction()).thenReturn(Optional.empty());
        TransactionAddRequest request = fullRequest(null, PfKeyAction.PF5);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(
                        "Copy last transaction is not available. Please enter transaction details."));

        verify(transactionService, times(1)).findLastTransaction();
        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
    }

    /**
     * H (PF3). PF3 ({@code RETURN-TO-PREV-SCREEN}) navigates back to the main menu: the
     * response advertises the target in the {@code X-CardDemo-Next-Program} /
     * {@code X-CardDemo-Next-Transaction} headers ({@code COMEN01C}/{@code CM00}) and names
     * the same target in the body. No service call is made.
     */
    @Test
    @DisplayName("H. PF3 navigates back to the main menu (COMEN01C / CM00)")
    void postPf3NavigatesBackToMainMenu() throws Exception {
        TransactionAddRequest request = fullRequest("Y", PfKeyAction.PF3);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, "COMEN01C"))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, "CM00"))
                .andExpect(jsonPath("$.programName").value("COMEN01C"))
                .andExpect(jsonPath("$.transactionName").value("CM00"));

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
        verify(transactionService, never()).findLastTransaction();
    }

    /**
     * I. Any other attention key (here PF7) is the COBOL {@code WHEN OTHER} branch: HTTP
     * 200 with "{@code Invalid key pressed. Please see below...}" and no service call.
     */
    @Test
    @DisplayName("I. An unmapped key shows the invalid-key message")
    void postOtherKeyShowsInvalidKeyMessage() throws Exception {
        TransactionAddRequest request = fullRequest("Y", PfKeyAction.PF7);

        mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Invalid key pressed. Please see below..."));

        verify(transactionService, never()).addTransaction(any(AddTransactionCommand.class));
        verify(transactionService, never()).findLastTransaction();
    }

    // ------------------------------------------------------------------------
    // J. tranId invariant complement + field-contract & monetary fidelity
    // ------------------------------------------------------------------------

    /**
     * J (invariant complement). The real, imported {@link TransactionMapper#toEntity}
     * &mdash; the other place an id could conceivably be set &mdash; also leaves the entity
     * {@code tranId} {@code null}: mapping the add request produces an unpersisted entity
     * with no id, reinforcing that id assignment is exclusively the service's job.
     */
    @Test
    @DisplayName("J. TransactionMapper.toEntity never assigns tranId")
    void mapperToEntityNeverAssignsTranId() {
        Transaction entity = transactionMapper.toEntity(fullRequest("Y", PfKeyAction.ENTER));
        assertThat(entity.getTranId()).isNull();
    }

    /**
     * J (field contract + monetary fidelity). On the confirm redisplay the response
     * preserves the {@code COTRN02} field names (camelCase re-expression of the symbolic
     * map) and the amount is carried as an exact {@link BigDecimal} at scale 2, serialized
     * as a plain decimal ({@code 100.00}) &mdash; never binary floating point. No internal
     * transaction id leaks onto the add-form response.
     */
    @Test
    @DisplayName("J. Response preserves the field contract and a scale-2 plain-decimal amount")
    void responsePreservesFieldContractAndAmountScaleTwo() throws Exception {
        TransactionAddRequest request = fullRequest(null, PfKeyAction.ENTER);

        String body = mockMvc.perform(post(ADD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                // Field-name contract (COTRN02 symbolic map -> DTO), header + echoed fields.
                .andExpect(jsonPath("$.transactionName").exists())
                .andExpect(jsonPath("$.programName").exists())
                .andExpect(jsonPath("$.accountId").value("00000000011"))
                .andExpect(jsonPath("$.typeCode").value("01"))
                .andExpect(jsonPath("$.categoryCode").value("0005"))
                .andExpect(jsonPath("$.source").value("POS"))
                .andExpect(jsonPath("$.merchantId").value("123456789"))
                .andExpect(jsonPath("$.merchantName").value("Test Merchant"))
                .andExpect(jsonPath("$.merchantCity").value("Seattle"))
                .andExpect(jsonPath("$.merchantZip").value("98101"))
                .andExpect(jsonPath("$.originDate").value("2024-01-15"))
                .andExpect(jsonPath("$.processDate").value("2024-01-15"))
                // No leaked internal id on the add-form contract.
                .andExpect(jsonPath("$.transactionId").doesNotExist())
                .andExpect(jsonPath("$.tranId").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // Monetary fidelity: exact BigDecimal at scale 2, rendered as a plain decimal.
        assertThat(body).contains("\"amount\":100.00");
        TransactionAddResponse response = parse(body);
        assertThat(response.amount()).isEqualByComparingTo("100.00");
        assertThat(response.amount().scale()).isEqualTo(2);
    }
}

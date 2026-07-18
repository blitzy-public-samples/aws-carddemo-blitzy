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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aws.carddemo.config.JacksonConfig;
import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.AccountViewRequest;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.AccountMapper;
import com.aws.carddemo.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest @WebMvcTest}
 * slice test for {@link AccountViewController} &mdash; the Java re-platform of the
 * online COBOL program {@code COACTVWC} (CICS transaction {@code CAVW}, BMS map
 * {@code COACTVW}). It verifies the <strong>Account View / inquiry</strong> REST
 * contract that merges an account with its owning customer.
 *
 * <h2>What this test guarantees (parity focus)</h2>
 * <ul>
 *   <li><b>Field-contract parity (AAP H2).</b> The merged {@code Account} +
 *       {@code Customer} view is exercised through the <em>real</em>
 *       {@link AccountMapper}, so the field-level movement (for example
 *       {@code city} sourced from {@code CUST-ADDR-LINE-3}, the display ZIP taking
 *       the leading five characters of the stored ZIP, and the zero-padded account
 *       and customer ids) is asserted end-to-end rather than mocked away.</li>
 *   <li><b>Monetary fidelity (AAP H3).</b> The five monetary fields are asserted to
 *       serialize as plain JSON numbers at scale&nbsp;2 (for example
 *       {@code 5000.00}); no floating-point value is used anywhere in this test.</li>
 *   <li><b>Sensitive-field discipline (AAP 0.9.3).</b> The SSN, date of birth and
 *       government-issued id <em>are</em> part of this legacy display screen and are
 *       asserted present, while the response is asserted to carry no {@code password}
 *       and no {@code cvv} property.</li>
 *   <li><b>Not-found propagation (AAP M1).</b> A {@link RecordNotFoundException}
 *       raised by the service is asserted to propagate uncaught to the
 *       {@code GlobalExceptionHandler} and surface as an RFC-7807
 *       {@code application/problem+json} 404.</li>
 *   <li><b>PF-key semantics.</b> {@code PF3} back-navigation emits the next
 *       program/transaction headers without touching the service; an unmapped key
 *       redisplays the screen with the invalid-key message.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The slice loads only {@link AccountViewController}. The real
 * {@link SecurityConfig} (filters on, CSRF disabled, stateless HTTP&nbsp;Basic) and
 * the real {@link AccountMapper} are imported so the security and mapping contracts
 * are exercised; {@link JacksonConfig} is imported so the monetary
 * {@code WRITE_BIGDECIMAL_AS_PLAIN} and {@code NON_NULL} serialization rules that
 * this test asserts against are deterministically active in the slice. The single
 * business collaborator, {@link AccountService}, is replaced with a Mockito mock via
 * {@link MockitoBean}. Authentication is supplied by {@link WithMockUser} (any
 * authenticated user; this screen requires no admin role and reads no principal for
 * business logic). This is a pure, deterministic, headless web slice &mdash; no
 * database and no Testcontainers &mdash; contributing to the &ge;80% coverage gate.</p>
 */
@WebMvcTest(AccountViewController.class)
@Import({SecurityConfig.class, AccountMapper.class, JacksonConfig.class})
class AccountViewControllerTest {

    /** REST endpoint under test ({@code COACTVWC} / CICS {@code CAVW}). */
    private static final String VIEW_PATH = "/api/v1/accounts/view";

    /** Response header carrying the next program to navigate to (COBOL {@code XCTL}). */
    private static final String NEXT_PROGRAM_HEADER = "X-CardDemo-Next-Program";

    /** Response header carrying the next transaction to navigate to. */
    private static final String NEXT_TRANSACTION_HEADER = "X-CardDemo-Next-Transaction";

    /** Request header carrying the calling program (COMMAREA {@code CDEMO-FROM-PROGRAM}). */
    private static final String FROM_PROGRAM_HEADER = "X-CardDemo-From-Program";

    /** Request header carrying the calling transaction (COMMAREA {@code CDEMO-FROM-TRANID}). */
    private static final String FROM_TRANSACTION_HEADER = "X-CardDemo-From-Transaction";

    /** Default back-navigation program when no caller is recorded ({@code COMEN01C} main menu). */
    private static final String DEFAULT_BACK_PROGRAM = "COMEN01C";

    /** Default back-navigation transaction when no caller is recorded ({@code CM00}). */
    private static final String DEFAULT_BACK_TRANSACTION = "CM00";

    /** Screen header transaction id echoed by the controller ({@code LIT-THISTRANID}). */
    private static final String THIS_TRANSACTION = "CAVW";

    /** Screen header program name echoed by the controller ({@code LIT-THISPGM}). */
    private static final String THIS_PROGRAM = "COACTVWC";

    /** First-entry prompt shown on the blank inquiry screen ({@code WS-PROMPT-FOR-INPUT}). */
    private static final String PROMPT_FOR_INPUT = "Enter or update id of account to display";

    /** Standard invalid-key message ({@code CCDA-MSG-INVALID-KEY}). */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /** Valid 11-digit account id used by the happy-path / navigation cases. */
    private static final String ACCOUNT_ID = "11111111111";

    /** Numeric form of {@link #ACCOUNT_ID} passed to the mocked service. */
    private static final long ACCOUNT_ID_LONG = 11_111_111_111L;

    /** A syntactically valid but non-existent account id used by the not-found case. */
    private static final String MISSING_ACCOUNT_ID = "99999999999";

    /** Numeric form of {@link #MISSING_ACCOUNT_ID} passed to the mocked service. */
    private static final long MISSING_ACCOUNT_ID_LONG = 99_999_999_999L;

    /** Deterministic customer id (nine digits) used by the stub customer. */
    private static final long CUSTOMER_ID_LONG = 222_222_222L;

    // Sensitive fixture values (present on this display screen for legacy parity).
    private static final String STUB_SSN = "123456789";
    private static final String STUB_DOB = "1985-06-15";
    private static final String STUB_GOVT_ID = "GOVID1234567890";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountService accountService;

    // ---------------------------------------------------------------------
    // Deterministic stub entities (built via the public all-args constructors;
    // the JPA no-arg constructors are protected and out of package scope).
    // All monetary values are BigDecimal at scale 2 -- never double/float.
    // ---------------------------------------------------------------------

    /**
     * Builds a deterministic {@link Account} stub. Monetary fields carry distinct
     * scale-2 values so the serialized JSON unambiguously demonstrates decimal
     * fidelity (for example a trailing {@code .00} is preserved).
     *
     * @return a fully populated account fixture
     */
    private static Account stubAccount() {
        return new Account(
                ACCOUNT_ID_LONG,                 // acctId
                "Y",                             // acctActiveStatus
                new BigDecimal("1234.56"),       // currBal        -> currentBalance
                new BigDecimal("5000.00"),       // creditLimit    -> creditLimit
                new BigDecimal("2500.00"),       // cashCreditLimit -> cashLimit
                "2020-01-15",                    // acctOpenDate    -> dateOpened
                "2027-01-15",                    // acctExpirationDate -> expiryDate
                "2024-01-15",                    // acctReissueDate -> reissueDate
                new BigDecimal("300.00"),        // currCycCredit   -> currentCycleCredit
                new BigDecimal("150.75"),        // currCycDebit    -> currentCycleDebit
                "9999999999",                    // acctAddrZip (not shown on the view screen)
                "GRP01");                        // groupId         -> groupId
    }

    /**
     * Builds a deterministic {@link Customer} stub. The address line 3 becomes the
     * displayed {@code city} and the ten-character stored ZIP is truncated to its
     * leading five characters for the displayed {@code zipCode}, matching
     * {@link AccountMapper}.
     *
     * @return a fully populated customer fixture
     */
    private static Customer stubCustomer() {
        return new Customer(
                CUSTOMER_ID_LONG,                // custId              -> customerId
                "JOHN",                          // custFirstName       -> firstName
                "Q",                             // custMiddleName      -> middleName
                "PUBLIC",                        // custLastName        -> lastName
                "123 MAIN ST",                   // custAddrLine1       -> addressLine1
                "APT 4",                         // custAddrLine2       -> addressLine2
                "SPRINGFIELD",                   // custAddrLine3       -> city
                "IL",                            // custAddrStateCd     -> stateCode
                "USA",                           // custAddrCountryCd   -> countryCode
                "6270412345",                    // custAddrZip         -> zipCode (leading 5 = 62704)
                "(555)123-4567",                 // custPhoneNum1       -> phone1
                "(555)987-6543",                 // custPhoneNum2       -> phone2
                STUB_SSN,                        // custSsn (SENSITIVE) -> ssn
                STUB_GOVT_ID,                    // custGovtIssuedId (SENSITIVE) -> governmentId
                STUB_DOB,                        // custDob (SENSITIVE) -> dateOfBirth
                "EFT0000001",                    // custEftAccountId    -> eftAccountId
                "Y",                             // custPriCardHolderInd -> primaryHolderFlag
                720);                            // custFicoCreditScore -> ficoScore ("720")
    }

    /**
     * Serializes a request record to its JSON body using the application
     * {@link ObjectMapper}. Serialization performs no validation, so this can build
     * both valid and deliberately invalid request bodies.
     *
     * @param request the request DTO to serialize
     * @return the JSON representation of {@code request}
     * @throws Exception if serialization fails
     */
    private String toJson(AccountViewRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    // =====================================================================
    // A. Authentication -- unauthenticated requests are rejected with 401
    // =====================================================================

    /**
     * A.1 &mdash; an unauthenticated {@code GET} of the inquiry screen is rejected
     * with {@code 401 Unauthorized} by the imported {@link SecurityConfig} (the view
     * path is neither {@code permitAll} nor admin-only). The controller is never
     * reached, so the service is untouched.
     */
    @Test
    void getWithoutAuthenticationIsUnauthorized() throws Exception {
        mockMvc.perform(get(VIEW_PATH))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountService);
    }

    /**
     * A.2 &mdash; an unauthenticated {@code POST} is likewise rejected with
     * {@code 401 Unauthorized}. Security is evaluated before body binding, so a
     * well-formed body does not change the outcome.
     */
    @Test
    void postWithoutAuthenticationIsUnauthorized() throws Exception {
        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new AccountViewRequest(ACCOUNT_ID, PfKeyAction.ENTER))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountService);
    }

    // =====================================================================
    // B. GET -- blank first-entry inquiry screen
    // =====================================================================

    /**
     * B &mdash; an authenticated {@code GET} returns the blank first-entry screen
     * ({@code 200 OK}) carrying the header fields and the "enter account id" prompt,
     * with no account id and no error message. Null fields are omitted from the JSON
     * (Jackson {@code NON_NULL}). The service is never called on first entry.
     */
    @Test
    @WithMockUser
    void getReturnsBlankInquiryScreen() throws Exception {
        mockMvc.perform(get(VIEW_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionName").value(THIS_TRANSACTION))
                .andExpect(jsonPath("$.programName").value(THIS_PROGRAM))
                .andExpect(jsonPath("$.infoMessage").value(PROMPT_FOR_INPUT))
                // Header date/time are populated (the COBOL 1100-SCREEN-INIT header).
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.currentTime").exists())
                // No account was read and no error is present on first entry.
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        verifyNoInteractions(accountService);
    }

    // =====================================================================
    // C. POST ENTER -- account found (happy path, merged view via real mapper)
    // =====================================================================

    /**
     * C &mdash; an authenticated {@code POST} with {@code ENTER} and a valid account
     * id fetches the account and returns the merged account+customer view
     * ({@code 200 OK}). The assertions confirm the real {@link AccountMapper} field
     * movement (zero-padded ids, {@code city} from address line 3, leading-five ZIP)
     * and monetary fidelity (plain scale-2 decimals).
     */
    @Test
    @WithMockUser
    void postEnterReturnsMergedAccountViewOnHappyPath() throws Exception {
        when(accountService.viewAccount(ACCOUNT_ID_LONG))
                .thenReturn(new AccountService.AccountDetail(stubAccount(), stubCustomer()));

        MvcResult result = mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new AccountViewRequest(ACCOUNT_ID, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // --- Screen header echo ---
                .andExpect(jsonPath("$.transactionName").value(THIS_TRANSACTION))
                .andExpect(jsonPath("$.programName").value(THIS_PROGRAM))
                // --- Account identity / status ---
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.accountStatus").value("Y"))
                .andExpect(jsonPath("$.groupId").value("GRP01"))
                // --- Customer identity + merged address (mapper-derived) ---
                .andExpect(jsonPath("$.customerId").value("222222222"))
                .andExpect(jsonPath("$.firstName").value("JOHN"))
                .andExpect(jsonPath("$.middleName").value("Q"))
                .andExpect(jsonPath("$.lastName").value("PUBLIC"))
                .andExpect(jsonPath("$.addressLine1").value("123 MAIN ST"))
                .andExpect(jsonPath("$.addressLine2").value("APT 4"))
                // city <- CUST-ADDR-LINE-3
                .andExpect(jsonPath("$.city").value("SPRINGFIELD"))
                .andExpect(jsonPath("$.stateCode").value("IL"))
                .andExpect(jsonPath("$.countryCode").value("USA"))
                // zipCode = leading five characters of the stored ten-character ZIP
                .andExpect(jsonPath("$.zipCode").value("62704"))
                .andExpect(jsonPath("$.phone1").value("(555)123-4567"))
                .andExpect(jsonPath("$.phone2").value("(555)987-6543"))
                .andExpect(jsonPath("$.eftAccountId").value("EFT0000001"))
                .andExpect(jsonPath("$.primaryHolderFlag").value("Y"))
                .andExpect(jsonPath("$.ficoScore").value("720"))
                // --- Monetary fields: numeric JSON values (not strings) ---
                .andExpect(jsonPath("$.creditLimit").isNumber())
                .andExpect(jsonPath("$.cashLimit").isNumber())
                .andExpect(jsonPath("$.currentBalance").isNumber())
                .andExpect(jsonPath("$.currentCycleCredit").isNumber())
                .andExpect(jsonPath("$.currentCycleDebit").isNumber())
                .andReturn();

        // Monetary fidelity: assert the exact scale-2 plain-decimal token crosses the
        // wire (Jackson emits BigDecimal with its scale preserved and no space after
        // the colon), proving no floating-point artifact and no lost trailing zero.
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"creditLimit\":5000.00")
                .contains("\"cashLimit\":2500.00")
                .contains("\"currentBalance\":1234.56")
                .contains("\"currentCycleCredit\":300.00")
                .contains("\"currentCycleDebit\":150.75");

        verify(accountService).viewAccount(ACCOUNT_ID_LONG);
    }

    // =====================================================================
    // D. POST ENTER -- account not found -> 404 (exception propagation)
    // =====================================================================

    /**
     * D &mdash; when the service raises {@link RecordNotFoundException}, the
     * controller does <em>not</em> swallow it: it propagates to the
     * {@code GlobalExceptionHandler} and surfaces as an RFC-7807
     * {@code application/problem+json} {@code 404 Not Found}, preserving the COBOL
     * "account not found" caller-visible outcome (AAP 0.7.2 M1).
     */
    @Test
    @WithMockUser
    void postEnterPropagatesNotFoundAs404ProblemJson() throws Exception {
        when(accountService.viewAccount(MISSING_ACCOUNT_ID_LONG))
                .thenThrow(RecordNotFoundException.of("Account", MISSING_ACCOUNT_ID_LONG));

        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new AccountViewRequest(MISSING_ACCOUNT_ID, PfKeyAction.ENTER))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Account not found: " + MISSING_ACCOUNT_ID));

        verify(accountService).viewAccount(MISSING_ACCOUNT_ID_LONG);
    }

    // =====================================================================
    // E. Sensitive-data discipline (screen-parity fields present; no secrets)
    // =====================================================================

    /**
     * E &mdash; the SSN, date of birth and government-issued id <em>are</em> part of
     * this legacy display screen, so they appear in the response and are asserted
     * equal to the stub values (this is correct behavior, unlike a CVV or a
     * password). The response is additionally asserted to carry no {@code password}
     * and no {@code cvv} property anywhere in its body.
     */
    @Test
    @WithMockUser
    void postEnterExposesScreenSensitiveFieldsButNeverPasswordOrCvv() throws Exception {
        when(accountService.viewAccount(ACCOUNT_ID_LONG))
                .thenReturn(new AccountService.AccountDetail(stubAccount(), stubCustomer()));

        MvcResult result = mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new AccountViewRequest(ACCOUNT_ID, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                // Displayed for legacy parity -> present with the exact stub values.
                .andExpect(jsonPath("$.ssn").value(STUB_SSN))
                .andExpect(jsonPath("$.dateOfBirth").value(STUB_DOB))
                .andExpect(jsonPath("$.governmentId").value(STUB_GOVT_ID))
                // This screen has no password and no CVV field.
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.cvv").doesNotExist())
                .andReturn();

        // Defense in depth: no "password" / "cvv" token anywhere in the body, in any case.
        String body = result.getResponse().getContentAsString().toLowerCase(java.util.Locale.ROOT);
        assertThat(body).doesNotContain("password").doesNotContain("cvv");
    }

    // =====================================================================
    // F. POST PF3 -- back navigation (COBOL XCTL); service never called
    // =====================================================================

    /**
     * F.1 &mdash; {@code PF3} with no navigation context navigates back to the main
     * menu ({@code COMEN01C} / {@code CM00}), emitting the next-program and
     * next-transaction headers. The service is never called (an {@code XCTL}
     * transfers control and reads no account). A valid account id is supplied
     * because {@code @Valid} runs before the routing logic for every submit.
     */
    @Test
    @WithMockUser
    void postPf3NavigatesBackToMainMenuByDefault() throws Exception {
        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new AccountViewRequest(ACCOUNT_ID, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(NEXT_PROGRAM_HEADER, DEFAULT_BACK_PROGRAM))
                .andExpect(header().string(NEXT_TRANSACTION_HEADER, DEFAULT_BACK_TRANSACTION));

        verifyNoInteractions(accountService);
    }

    /**
     * F.2 &mdash; {@code PF3} navigates back to the recorded caller when the
     * navigation-context headers are present (the explicit translation of the
     * COMMAREA {@code CDEMO-FROM-PROGRAM} / {@code CDEMO-FROM-TRANID}). The emitted
     * next-program/next-transaction headers echo the supplied caller.
     */
    @Test
    @WithMockUser
    void postPf3NavigatesBackToCallerWhenFromContextPresent() throws Exception {
        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(FROM_PROGRAM_HEADER, "COTRN00C")
                        .header(FROM_TRANSACTION_HEADER, "CT00")
                        .content(toJson(new AccountViewRequest(ACCOUNT_ID, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(NEXT_PROGRAM_HEADER, "COTRN00C"))
                .andExpect(header().string(NEXT_TRANSACTION_HEADER, "CT00"));

        verifyNoInteractions(accountService);
    }

    // =====================================================================
    // G. POST other/unmapped key -- redisplay with invalid-key message
    // =====================================================================

    /**
     * G &mdash; any attention key other than {@code ENTER} or {@code PF3} redisplays
     * the screen ({@code 200 OK}) with the standard invalid-key message, performs no
     * navigation, and never calls the service (the COBOL "invalid key pressed"
     * path). {@code PF12} stands in for an unmapped key here.
     */
    @Test
    @WithMockUser
    void postUnmappedKeyRedisplaysWithInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new AccountViewRequest(ACCOUNT_ID, PfKeyAction.PF12))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(INVALID_KEY_MESSAGE))
                // A redisplay carries no account detail and no navigation headers.
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(header().doesNotExist(NEXT_PROGRAM_HEADER))
                .andExpect(header().doesNotExist(NEXT_TRANSACTION_HEADER));

        verify(accountService, never()).viewAccount(anyLong());
    }

    // =====================================================================
    // H. Field-contract parity -- @Valid rejects a malformed account id (400)
    // =====================================================================

    /**
     * H &mdash; the {@code accountId} field carries the BMS numeric contract
     * ({@code @Pattern("^\\d{1,11}$")}). Posting a non-numeric id with {@code ENTER}
     * fails Bean Validation before the routing logic, so the request is rejected
     * with an RFC-7807 {@code application/problem+json} {@code 400 Bad Request} whose
     * detail names the offending {@code accountId} field but never echoes the
     * rejected value (a rejected value could be a password or a CVV). The service is
     * never reached.
     */
    @Test
    @WithMockUser
    void postEnterWithInvalidAccountIdIsRejectedWith400AndHidesRejectedValue() throws Exception {
        // Non-numeric -> violates @Pattern. Stands in for a value that must never be
        // echoed back in the error response.
        String rejectedValue = "SECRET99";

        MvcResult result = mockMvc.perform(post(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new AccountViewRequest(rejectedValue, PfKeyAction.ENTER))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("accountId")        // the offending field name is reported
                .doesNotContain(rejectedValue); // the rejected value is never echoed

        verifyNoInteractions(accountService);
    }
}

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

import java.math.BigDecimal;
import java.util.Map;

import jakarta.persistence.OptimisticLockException;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.AccountMapper;
import com.aws.carddemo.security.CardDemoUserDetailsService;
import com.aws.carddemo.service.AccountService;
import com.aws.carddemo.service.AccountService.AccountDetail;
import com.aws.carddemo.service.AccountService.AccountUpdateCommand;
import com.aws.carddemo.service.AccountService.AccountUpdateResult;
import com.aws.carddemo.service.AccountService.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest @WebMvcTest}
 * slice test for {@link AccountUpdateController} &mdash; the online re-expression of COBOL
 * program {@code COACTUPC} (CICS transaction {@code CAUP}, the largest online program,
 * source {@code legacy/cbl/COACTUPC.cbl}; BMS map {@code legacy/bms/COACTUP.bms}).
 *
 * <p>The Account Update screen is a stateful, PF-key-gated update flow. These tests verify the
 * full web contract the controller is responsible for, leaving all business logic to the mocked
 * {@link AccountService}:</p>
 * <ul>
 *   <li><b>PF-key validity gate</b> ({@code COACTUPC 0000-MAIN} L906&ndash;915): {@code ENTER}
 *       and {@code PF3} are always valid; {@code PF5} (save/confirm) is valid only while the
 *       carried flow state is {@link Status#CHANGES_OK_NOT_CONFIRMED}; {@code PF12} (cancel) is
 *       valid only once details have been fetched; any other/contextually-invalid key is coerced
 *       to {@code ENTER} (re-display, never an error).</li>
 *   <li><b>Pseudo-conversational flow state</b> (AAP hotspot H1): carried in the
 *       {@code X-CardDemo-*} headers rather than server session state. The controller always
 *       invokes {@link AccountService#updateAccount(AccountUpdateCommand, boolean)} with
 *       {@code reentry == true} (every submit is a re-entry in the conversation); the
 *       first-time-versus-re-entry distinction is carried in
 *       {@link AccountUpdateCommand#priorStatus()} ({@code null} on first entry).</li>
 *   <li><b>Status state machine</b> mapped onto {@code 200 OK} same-screen messages with the
 *       {@code X-CardDemo-Status} flow header (SHOW_DETAILS, CHANGES_OK_NOT_CONFIRMED,
 *       CHANGES_NOT_OK, DONE).</li>
 *   <li><b>Optimistic-lock conflict</b> (AAP hotspot H6): {@link OptimisticLockingFailureException}
 *       and {@link OptimisticLockException} both surface as HTTP {@code 409} with a fixed, safe
 *       message; a missing record surfaces as {@code 404}; a malformed body fails {@code @Valid}
 *       with {@code 400}. These are produced by the auto-included {@code GlobalExceptionHandler}.</li>
 *   <li><b>Split-field DTO contract</b> (AAP hotspot H2/H3): the account/customer entity fields
 *       are decomposed into the screen's split fields by the <em>real</em> {@link AccountMapper};
 *       monetary values are plain scale-2 decimals; the SSN, date of birth, and government id are
 *       present for legacy display parity while no password or CVV ever appears.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The real {@link SecurityConfig} (HTTP&nbsp;Basic, stateless, CSRF disabled) and the real
 * {@link AccountMapper} are imported so the security gate and the field decomposition are exercised
 * exactly as in production; only the {@link AccountService} collaborator (and the security
 * {@link CardDemoUserDetailsService}, referenced by the filter chain) are replaced with Mockito
 * beans. The {@code GlobalExceptionHandler} {@code @RestControllerAdvice} is auto-included by the
 * slice. Tests are deterministic and headless (no database or Testcontainers).</p>
 */
@WebMvcTest(AccountUpdateController.class)
@Import({SecurityConfig.class, AccountMapper.class})
@DisplayName("AccountUpdateController (COACTUPC / CAUP) web slice")
class AccountUpdateControllerTest {

    // ------------------------------------------------------------------------------------
    // Endpoint and pseudo-conversational flow-state header names (mirroring the controller).
    // ------------------------------------------------------------------------------------

    private static final String ENDPOINT = "/api/v1/accounts/update";

    private static final String H_PRIOR_STATUS = "X-CardDemo-Prior-Status";
    private static final String H_STATUS = "X-CardDemo-Status";
    private static final String H_FROM_PROGRAM = "X-CardDemo-From-Program";
    private static final String H_FROM_TRANSACTION = "X-CardDemo-From-Transaction";
    private static final String H_NEXT_PROGRAM = "X-CardDemo-Next-Program";
    private static final String H_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";
    private static final String H_ACCOUNT_VERSION = "X-CardDemo-Account-Version";

    // ------------------------------------------------------------------------------------
    // Deterministic fixture values. The account id and customer id are display-formatted by the
    // mapper (%011d / %09d); the split-field expectations below are the mapper's decomposition of
    // these stored composite values (dates -> Y/M/D, SSN -> 3/2/4, phone "(AAA)PPP-LLLL").
    // ------------------------------------------------------------------------------------

    private static final long ACCT_ID = 12_345_678_901L;
    private static final String ACCT_ID_DISPLAY = "12345678901";
    private static final long CUST_ID = 123_456_789L;
    private static final String CUST_ID_DISPLAY = "123456789";
    private static final long ACCOUNT_VERSION = 7L;

    private static final String OPEN_DATE = "2020-01-15";
    private static final String EXPIRY_DATE = "2025-12-31";
    private static final String REISSUE_DATE = "2021-06-01";
    private static final String SSN = "123456789";
    private static final String DOB = "1985-06-15";
    private static final String GOVERNMENT_ID = "GOVT1234567890";
    private static final String PHONE_1 = "(555)123-4567";
    private static final String PHONE_2 = "(444)987-6543";

    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal CASH_LIMIT = new BigDecimal("1500.00");
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1234.56");
    private static final BigDecimal CYCLE_CREDIT = new BigDecimal("250.00");
    private static final BigDecimal CYCLE_DEBIT = new BigDecimal("75.25");

    // Caller-visible flow messages supplied by the (mocked) service, reproduced here so the
    // controller's info/error routing can be asserted against a known string.
    private static final String MSG_SHOW_DETAILS = "Update account details presented above.";
    private static final String MSG_CONFIRM = "Changes validated.Press F5 to save";
    private static final String MSG_SUCCESS = "Changes committed to database";
    private static final String MSG_VALIDATION_ERROR = "Account Status must be Y or N.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountService accountService;

    /**
     * The security {@link CardDemoUserDetailsService} the imported {@link SecurityConfig} filter
     * chain delegates authentication to. It is mocked so the slice context wires a deterministic
     * {@code DaoAuthenticationProvider}; the authenticated tests use {@link WithMockUser}, so this
     * mock is never actually invoked.
     */
    @MockitoBean
    private CardDemoUserDetailsService userDetailsService;

    // ====================================================================================
    // A. Security: an unauthenticated request is rejected before the controller runs.
    // ====================================================================================

    @Test
    @DisplayName("A: unauthenticated POST is rejected with 401 and the service is never called")
    void unauthenticatedRequestIsUnauthorized() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "ENTER"))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountService);
    }

    // ====================================================================================
    // B. GET renders the initial blank Account Update screen (first-entry prompt).
    // ====================================================================================

    @Test
    @DisplayName("B: GET returns a 200 blank screen with the search-key prompt and no service call")
    @WithMockUser
    void getRendersBlankScreen() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(H_STATUS))
                .andExpect(jsonPath("$.transactionName").value("CAUP"))
                .andExpect(jsonPath("$.programName").value("COACTUPC"))
                .andExpect(jsonPath("$.infoMessage").value("Enter or update id of account to update"))
                .andExpect(jsonPath("$.functionKeys").value("ENTER=Process F3=Exit"))
                .andExpect(jsonPath("$.functionKeySave").value("F5=Save"))
                .andExpect(jsonPath("$.functionKeyCancel").value("F12=Cancel"))
                // Split data fields are blank on the first entry (no record fetched yet); the
                // API omits null properties (spring.jackson.default-property-inclusion=non_null),
                // so a blank field is absent from the JSON rather than present as null.
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.creditLimit").doesNotExist())
                .andExpect(jsonPath("$.ssnPart1").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        verifyNoInteractions(accountService);
    }

    // ====================================================================================
    // C. POST ENTER, first entry -> fetch & show details (SHOW_DETAILS).
    // ====================================================================================

    @Test
    @DisplayName("C: POST ENTER first-time shows details; reentry=true, priorStatus=null, split fields echoed")
    @WithMockUser
    void postEnterFirstTimeShowsDetails() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.SHOW_DETAILS, detail(), MSG_SHOW_DETAILS));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "ENTER"))))
                .andExpect(status().isOk())
                .andExpect(header().string(H_STATUS, "SHOW_DETAILS"))
                .andExpect(header().string(H_ACCOUNT_VERSION, "7"))
                // Account/customer fields decomposed by the real AccountMapper.
                .andExpect(jsonPath("$.accountId").value(ACCT_ID_DISPLAY))
                .andExpect(jsonPath("$.customerId").value(CUST_ID_DISPLAY))
                .andExpect(jsonPath("$.openYear").value("2020"))
                .andExpect(jsonPath("$.openMonth").value("01"))
                .andExpect(jsonPath("$.openDay").value("15"))
                .andExpect(jsonPath("$.ssnPart1").value("123"))
                .andExpect(jsonPath("$.ssnPart2").value("45"))
                .andExpect(jsonPath("$.ssnPart3").value("6789"))
                .andExpect(jsonPath("$.infoMessage").value(MSG_SHOW_DETAILS))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        ArgumentCaptor<AccountUpdateCommand> captor = ArgumentCaptor.forClass(AccountUpdateCommand.class);
        // eq(true): every submit is a re-entry (COACTUPC L306); the method's reentry flag is always true.
        verify(accountService).updateAccount(captor.capture(), eq(true));
        AccountUpdateCommand command = captor.getValue();
        // First entry carries no prior-status header -> null flow state (ACUP-DETAILS-NOT-FETCHED).
        assertThat(command.priorStatus()).isNull();
        assertThat(command.confirmSave()).isFalse();
        assertThat(command.cancel()).isFalse();
        assertThat(command.accountId()).isEqualTo(ACCT_ID_DISPLAY);
    }

    // ====================================================================================
    // D. POST ENTER, re-entry -> edits valid, awaiting confirm (CHANGES_OK_NOT_CONFIRMED).
    // ====================================================================================

    @Test
    @DisplayName("D: POST ENTER re-entry with valid edits returns CHANGES_OK_NOT_CONFIRMED (PF5 now enabled)")
    @WithMockUser
    void postEnterReentryChangesOkNotConfirmed() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.CHANGES_OK_NOT_CONFIRMED, detail(), MSG_CONFIRM));

        mockMvc.perform(post(ENDPOINT)
                        .header(H_PRIOR_STATUS, "SHOW_DETAILS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "ENTER"))))
                .andExpect(status().isOk())
                .andExpect(header().string(H_STATUS, "CHANGES_OK_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.infoMessage").value(MSG_CONFIRM))
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.functionKeySave").value("F5=Save"));

        ArgumentCaptor<AccountUpdateCommand> captor = ArgumentCaptor.forClass(AccountUpdateCommand.class);
        verify(accountService).updateAccount(captor.capture(), eq(true));
        AccountUpdateCommand command = captor.getValue();
        // Re-entry carries the prior flow state back in the command (COMMAREA ACUP-CHANGE-ACTION).
        assertThat(command.priorStatus()).isEqualTo(Status.SHOW_DETAILS);
        assertThat(command.confirmSave()).isFalse();
        assertThat(command.cancel()).isFalse();
    }

    // ====================================================================================
    // E. POST ENTER, re-entry -> validation errors (CHANGES_NOT_OK); PF5 stays disabled.
    // ====================================================================================

    @Test
    @DisplayName("E: POST ENTER re-entry with invalid edits returns CHANGES_NOT_OK on the error line")
    @WithMockUser
    void postEnterReentryChangesNotOk() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.CHANGES_NOT_OK, detail(), MSG_VALIDATION_ERROR));

        mockMvc.perform(post(ENDPOINT)
                        .header(H_PRIOR_STATUS, "SHOW_DETAILS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "ENTER"))))
                .andExpect(status().isOk())
                // The error status keeps PF5 disabled: a subsequent PF5 would be coerced to ENTER.
                .andExpect(header().string(H_STATUS, "CHANGES_NOT_OK"))
                .andExpect(jsonPath("$.errorMessage").value(MSG_VALIDATION_ERROR))
                .andExpect(jsonPath("$.infoMessage").doesNotExist());

        ArgumentCaptor<AccountUpdateCommand> captor = ArgumentCaptor.forClass(AccountUpdateCommand.class);
        verify(accountService).updateAccount(captor.capture(), eq(true));
        assertThat(captor.getValue().confirmSave()).isFalse();
    }

    // ====================================================================================
    // F. POST PF5 while CHANGES_OK_NOT_CONFIRMED -> commit succeeds (DONE).
    // ====================================================================================

    @Test
    @DisplayName("F: POST PF5 in CHANGES_OK_NOT_CONFIRMED commits (DONE); confirmSave=true, version passed through")
    @WithMockUser
    void postPf5CommitsWhenConfirmable() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.DONE, detail(), MSG_SUCCESS));

        mockMvc.perform(post(ENDPOINT)
                        .header(H_PRIOR_STATUS, "CHANGES_OK_NOT_CONFIRMED")
                        .header(H_ACCOUNT_VERSION, "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "PF5"))))
                .andExpect(status().isOk())
                .andExpect(header().string(H_STATUS, "DONE"))
                .andExpect(header().string(H_ACCOUNT_VERSION, "7"))
                .andExpect(jsonPath("$.infoMessage").value(MSG_SUCCESS))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        ArgumentCaptor<AccountUpdateCommand> captor = ArgumentCaptor.forClass(AccountUpdateCommand.class);
        verify(accountService).updateAccount(captor.capture(), eq(true));
        AccountUpdateCommand command = captor.getValue();
        // PF5 is honoured (not coerced) because the carried state permits confirmation.
        assertThat(command.confirmSave()).isTrue();
        assertThat(command.cancel()).isFalse();
        assertThat(command.priorStatus()).isEqualTo(Status.CHANGES_OK_NOT_CONFIRMED);
        assertThat(command.expectedVersion()).isEqualTo(ACCOUNT_VERSION);
    }

    @Test
    @DisplayName("F-P4-A: an oversized (out-of-long-range) account-version header is parsed defensively to null (HTTP 200, not 500) and the cross-request check is skipped")
    @WithMockUser
    void oversizedVersionHeaderIsParsedDefensivelyNotA500() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.DONE, detail(), MSG_SUCCESS));

        // 20 nines: all-digit (so it passes the header's digit scan) but far larger than
        // Long.MAX_VALUE. Before the fix Long.valueOf overflowed with an unhandled
        // NumberFormatException that the global handler mapped to HTTP 500; it must now degrade to
        // null exactly like any other unparseable version header.
        String oversizedVersion = "99999999999999999999";

        mockMvc.perform(post(ENDPOINT)
                        .header(H_PRIOR_STATUS, "CHANGES_OK_NOT_CONFIRMED")
                        .header(H_ACCOUNT_VERSION, oversizedVersion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "PF5"))))
                // Bounded outcome: a same-screen 200, never an HTTP 500.
                .andExpect(status().isOk());

        ArgumentCaptor<AccountUpdateCommand> captor = ArgumentCaptor.forClass(AccountUpdateCommand.class);
        verify(accountService).updateAccount(captor.capture(), eq(true));
        // The oversized header is treated exactly like any other invalid value: expectedVersion is
        // null, so the controller skips the cross-request concurrency check (F-P4-A).
        assertThat(captor.getValue().expectedVersion()).isNull();
    }

    // ====================================================================================
    // G. POST PF5 -> optimistic-lock conflict -> 409 with a fixed, safe message (both providers).
    // ====================================================================================

    @Test
    @DisplayName("G1: Spring OptimisticLockingFailureException maps to 409 with a fixed safe message")
    @WithMockUser
    void postPf5SpringOptimisticLockConflict() throws Exception {
        String rawProviderMessage = "Row was updated or deleted RAWLOCKTOKEN by another transaction";
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenThrow(new OptimisticLockingFailureException(rawProviderMessage));

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .header(H_PRIOR_STATUS, "CHANGES_OK_NOT_CONFIRMED")
                        .header(H_ACCOUNT_VERSION, "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "PF5"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent Update Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("The record was updated by another transaction; please retry."))
                .andReturn();

        // The raw provider message must never be echoed to the caller.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("RAWLOCKTOKEN");
    }

    @Test
    @DisplayName("G2: Jakarta OptimisticLockException maps to the identical 409 safe-message contract")
    @WithMockUser
    void postPf5JakartaOptimisticLockConflict() throws Exception {
        String rawProviderMessage = "javax persistence RAWLOCKTOKEN version mismatch";
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenThrow(new OptimisticLockException(rawProviderMessage));

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .header(H_PRIOR_STATUS, "CHANGES_OK_NOT_CONFIRMED")
                        .header(H_ACCOUNT_VERSION, "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "PF5"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent Update Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("The record was updated by another transaction; please retry."))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("RAWLOCKTOKEN");
    }

    // ====================================================================================
    // H. POST PF5 when NOT confirmable -> PF-gate coerces to ENTER (no commit).
    // ====================================================================================

    @Test
    @DisplayName("H: PF5 outside CHANGES_OK_NOT_CONFIRMED is coerced to ENTER; confirmSave stays false")
    @WithMockUser
    void postPf5CoercedToEnterWhenNotConfirmable() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.SHOW_DETAILS, detail(), MSG_SHOW_DETAILS));

        mockMvc.perform(post(ENDPOINT)
                        // Carried state is SHOW_DETAILS, so PF5 is not permitted and is coerced to ENTER.
                        .header(H_PRIOR_STATUS, "SHOW_DETAILS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "PF5"))))
                .andExpect(status().isOk())
                .andExpect(header().string(H_STATUS, "SHOW_DETAILS"));

        ArgumentCaptor<AccountUpdateCommand> captor = ArgumentCaptor.forClass(AccountUpdateCommand.class);
        verify(accountService).updateAccount(captor.capture(), eq(true));
        AccountUpdateCommand command = captor.getValue();
        // Coercion means the service is asked to re-display, not to commit or cancel.
        assertThat(command.confirmSave()).isFalse();
        assertThat(command.cancel()).isFalse();
    }

    // ====================================================================================
    // I. POST PF12 cancel behaviour (valid only once details fetched).
    // ====================================================================================

    @Test
    @DisplayName("I1: PF12 with details fetched cancels edits; cancel=true, confirmSave=false")
    @WithMockUser
    void postPf12CancelsWhenDetailsFetched() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.SHOW_DETAILS, detail(), MSG_SHOW_DETAILS));

        mockMvc.perform(post(ENDPOINT)
                        .header(H_PRIOR_STATUS, "CHANGES_OK_NOT_CONFIRMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "PF12"))))
                .andExpect(status().isOk())
                .andExpect(header().string(H_STATUS, "SHOW_DETAILS"));

        ArgumentCaptor<AccountUpdateCommand> captor = ArgumentCaptor.forClass(AccountUpdateCommand.class);
        verify(accountService).updateAccount(captor.capture(), eq(true));
        AccountUpdateCommand command = captor.getValue();
        assertThat(command.cancel()).isTrue();
        assertThat(command.confirmSave()).isFalse();
    }

    @Test
    @DisplayName("I2: PF12 without a fetched account is coerced to ENTER; cancel=false")
    @WithMockUser
    void postPf12CoercedToEnterWhenNotFetched() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.SHOW_DETAILS, detail(), MSG_SHOW_DETAILS));

        mockMvc.perform(post(ENDPOINT)
                        // No prior-status header -> details not fetched -> PF12 invalid -> ENTER.
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "PF12"))))
                .andExpect(status().isOk());

        ArgumentCaptor<AccountUpdateCommand> captor = ArgumentCaptor.forClass(AccountUpdateCommand.class);
        verify(accountService).updateAccount(captor.capture(), eq(true));
        AccountUpdateCommand command = captor.getValue();
        assertThat(command.cancel()).isFalse();
        assertThat(command.confirmSave()).isFalse();
        assertThat(command.priorStatus()).isNull();
    }

    // ====================================================================================
    // J. POST ENTER -> account not found -> 404 problem+json.
    // ====================================================================================

    @Test
    @DisplayName("J: a missing account surfaces as 404 Record Not Found problem+json")
    @WithMockUser
    void postEnterAccountNotFound() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenThrow(RecordNotFoundException.of("Account", ACCT_ID_DISPLAY));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "ENTER"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Account not found: " + ACCT_ID_DISPLAY));
    }

    // ====================================================================================
    // K. POST PF3 -> back navigation (no service call, next-screen headers set).
    // ====================================================================================

    @Test
    @DisplayName("K: PF3 navigates back to the menu with next-screen headers and no service call")
    @WithMockUser
    void postPf3NavigatesBack() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("action", "PF3"))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(H_STATUS))
                .andExpect(header().string(H_NEXT_PROGRAM, "COMEN01C"))
                .andExpect(header().string(H_NEXT_TRANSACTION, "CM00"));

        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("K2: PF3 honours the caller's from-program/from-transaction as the return target")
    @WithMockUser
    void postPf3NavigatesBackToCaller() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .header(H_FROM_PROGRAM, "COADM01C")
                        .header(H_FROM_TRANSACTION, "CA00")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("action", "PF3"))))
                .andExpect(status().isOk())
                .andExpect(header().string(H_NEXT_PROGRAM, "COADM01C"))
                .andExpect(header().string(H_NEXT_TRANSACTION, "CA00"));

        verifyNoInteractions(accountService);
    }

    // ====================================================================================
    // L. Sensitive-data parity: SSN/DOB/government-id present; no password or CVV ever.
    // ====================================================================================

    @Test
    @DisplayName("L: SSN/DOB/government-id are echoed for parity while password and CVV never appear")
    @WithMockUser
    void sensitiveFieldsPresentButNoSecretsLeaked() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.SHOW_DETAILS, detail(), MSG_SHOW_DETAILS));

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "ENTER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ssnPart1").value("123"))
                .andExpect(jsonPath("$.ssnPart2").value("45"))
                .andExpect(jsonPath("$.ssnPart3").value("6789"))
                .andExpect(jsonPath("$.dobYear").value("1985"))
                .andExpect(jsonPath("$.dobMonth").value("06"))
                .andExpect(jsonPath("$.dobDay").value("15"))
                .andExpect(jsonPath("$.governmentId").value(GOVERNMENT_ID))
                .andReturn();

        String body = result.getResponse().getContentAsString().toLowerCase();
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("cvv");
    }

    // ====================================================================================
    // M. Monetary fidelity: BigDecimal scale-2, rendered as plain decimals (no float).
    // ====================================================================================

    @Test
    @DisplayName("M: monetary fields serialize as plain scale-2 decimals")
    @WithMockUser
    void monetaryFieldsAreScaleTwoDecimals() throws Exception {
        when(accountService.updateAccount(any(AccountUpdateCommand.class), anyBoolean()))
                .thenReturn(new AccountUpdateResult(Status.SHOW_DETAILS, detail(), MSG_SHOW_DETAILS));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("accountId", ACCT_ID_DISPLAY, "action", "ENTER"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"creditLimit\":5000.00")))
                .andExpect(content().string(containsString("\"cashLimit\":1500.00")))
                .andExpect(content().string(containsString("\"currentBalance\":1234.56")))
                .andExpect(content().string(containsString("\"currentCycleCredit\":250.00")))
                .andExpect(content().string(containsString("\"currentCycleDebit\":75.25")));
    }

    // ====================================================================================
    // N. Field-contract parity: @Valid rejects a malformed split field before the service runs.
    // ====================================================================================

    @Test
    @DisplayName("N: a non-numeric openMonth fails @Valid with 400 Validation Failed and no service call")
    @WithMockUser
    void malformedSplitFieldFailsValidation() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "accountId", ACCT_ID_DISPLAY,
                                "openMonth", "XX",
                                "action", "ENTER"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(containsString("openMonth")))
                // The rejected value must not be echoed back in the problem detail.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(containsString("XX"))));

        verifyNoInteractions(accountService);
    }

    // ====================================================================================
    // Fixtures & helpers.
    // ====================================================================================

    /** A populated {@link AccountDetail} whose split-field decomposition the tests assert against. */
    private AccountDetail detail() {
        return new AccountDetail(sampleAccount(), sampleCustomer());
    }

    /**
     * A fully populated {@link Account} using the public parameterized constructor. All monetary
     * values are {@link BigDecimal} scale-2 fixtures (never {@code double}/{@code float}); the
     * optimistic-lock version is set so the controller emits the {@code X-CardDemo-Account-Version}
     * header.
     */
    private Account sampleAccount() {
        Account account = new Account(
                ACCT_ID,
                "Y",
                CURRENT_BALANCE,
                CREDIT_LIMIT,
                CASH_LIMIT,
                OPEN_DATE,
                EXPIRY_DATE,
                REISSUE_DATE,
                CYCLE_CREDIT,
                CYCLE_DEBIT,
                "12345",
                "0001");
        account.setVersion(ACCOUNT_VERSION);
        return account;
    }

    /** A fully populated {@link Customer} using the public parameterized constructor. */
    private Customer sampleCustomer() {
        return new Customer(
                CUST_ID,
                "JOHN",
                "Q",
                "PUBLIC",
                "123 MAIN ST",
                "APT 4",
                "ANYTOWN",
                "VA",
                "USA",
                "12345",
                PHONE_1,
                PHONE_2,
                SSN,
                GOVERNMENT_ID,
                DOB,
                "EFT0000000001",
                "Y",
                750);
    }

    /** Serializes a request-body field map to JSON using the context {@link ObjectMapper}. */
    private String body(Map<String, String> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }
}
